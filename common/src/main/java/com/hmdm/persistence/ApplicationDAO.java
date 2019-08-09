/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.rest.json.ApplicationConfigurationLink;
import com.hmdm.rest.json.ApplicationVersionConfigurationLink;
import com.hmdm.rest.json.LinkConfigurationsToAppRequest;
import com.hmdm.rest.json.LinkConfigurationsToAppVersionRequest;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.util.ApplicationUtil;
import org.mybatis.guice.transactional.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.mapper.ApplicationMapper;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.util.FileUtil;

import javax.validation.constraints.NotNull;

@Singleton
public class ApplicationDAO extends AbstractLinkedDAO<Application, ApplicationConfigurationLink> {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDAO.class);

    private final ApplicationMapper mapper;
    private final CustomerDAO customerDAO;
    private final String filesDirectory;
    private final String baseUrl;
    private final String aaptCommand;

    @Inject
    public ApplicationDAO(ApplicationMapper mapper, CustomerDAO customerDAO,
                          @Named("files.directory") String filesDirectory,
                          @Named("base.url") String baseUrl,
                          @Named("aapt.command") String aaptCommand) {
        this.mapper = mapper;
        this.customerDAO = customerDAO;
        this.filesDirectory = filesDirectory;
        this.baseUrl = baseUrl;
        this.aaptCommand = aaptCommand;
    }

    public List<Application> getAllApplications() {
        return getList(this.mapper::getAllApplications);
    }

    public List<Application> getAllApplicationsByValue(String value) {
        return getList(customerId -> this.mapper.getAllApplicationsByValue(customerId, "%" + value + "%"));
    }

    public List<Application> getAllApplicationsByUrl(String url) {
        return getList(customerId -> this.mapper.getAllApplicationsByUrl(customerId, url));
    }

    /**
     * <p>Creates new application record in DB.</p>
     *
     * @param application an application record to be created.
     * @throws DuplicateApplicationException if another application record with same package ID and version already
     *         exists either for current or master customer account.
     */
    @Transactional
    public int insertApplication(Application application) throws IOException, InterruptedException {
        log.debug("Entering #insertApplication: application = {}", application);

        // If an APK-file was set for new app then make the file available in Files area and parse the app parameters
        // from it (package ID, version)
        final String filePath = application.getFilePath();
        if (filePath != null && !filePath.trim().isEmpty()) {
            final int customerId = SecurityContext.get().getCurrentUser().get().getCustomerId();
            Customer customer = customerDAO.findById(customerId);

            File movedFile = FileUtil.moveFile(customer, filesDirectory, null, filePath);
            if (movedFile != null) {
                final String fileName = movedFile.getAbsolutePath();
                final String[] commands = {this.aaptCommand, "dump", "badging", fileName};
                log.debug("Executing shell-commands: {}", Arrays.toString(commands));
                final Process exec = Runtime.getRuntime().exec(commands);

                final AtomicReference<String> appPkg = new AtomicReference<>();
                final AtomicReference<String> appVersion = new AtomicReference<>();
                final List<String> errorLines = new ArrayList<>();

                // Process the error stream by collecting all the error lines for further logging
                StreamGobbler errorGobbler = new StreamGobbler(exec.getErrorStream(), "ERROR", errorLines::add);

                // Process the output by analzying the line starting with "package:"
                StreamGobbler outputGobbler = new StreamGobbler(exec.getInputStream(), "APK-file DUMP", line -> {
                    if (line.startsWith("package:")) {
                        Scanner scanner = new Scanner(line).useDelimiter(" ");
                        while (scanner.hasNext()) {
                            final String token = scanner.next();
                            if (token.startsWith("name=")) {
                                String appPkgLocal = token.substring("name=".length());
                                if (appPkgLocal.startsWith("'") && appPkgLocal.endsWith("'")) {
                                    appPkgLocal = appPkgLocal.substring(1, appPkgLocal.length() - 1);
                                }
                                appPkg.set(appPkgLocal);
                            } else if (token.startsWith("versionName=")) {
                                String appVersionLocal = token.substring("versionName=".length());
                                if (appVersionLocal.startsWith("'") && appVersionLocal.endsWith("'")) {
                                    appVersionLocal = appVersionLocal.substring(1, appVersionLocal.length() - 1);
                                }
                                appVersion.set(appVersionLocal);
                            }
                        }
                    }
                });

                // Get ready to consume input and error streams from the process
                errorGobbler.start();
                outputGobbler.start();

                final int exitCode = exec.waitFor();

                outputGobbler.join();
                errorGobbler.join();

                if (exitCode == 0) {
                    // If URL is not specified explicitly for new app then set the application URL to reference to that
                    // file
                    if ((application.getUrl() == null || application.getUrl().trim().isEmpty())) {
                        application.setUrl(this.baseUrl + "/files/" + customer.getFilesDir() + "/" + movedFile.getName());
                    }

                    log.debug("Parsed application name and version from APK-file {}: {} {}",
                            movedFile.getName(), appPkg, appVersion);

                    application.setPkg(appPkg.get());
                    application.setVersion(appVersion.get());

                } else {
                    log.error("Could not analyze the .apk-file {}. The system process returned: {}. The error message follows:", fileName, exitCode);
                    errorLines.forEach(log::error);
                    throw new DAOException("Could not analyze the .apk-file");
                }
            } else {
                log.error("Could not move the uploaded .apk-file {}", filePath);
                throw new DAOException("Could not move the uploaded .apk-file");
            }
        }

        guardDuplicateApp(application);
//        guardDowngradeAppVersion(application);
        final Application existingApplication = resolveApplication(application);

        final ApplicationVersion applicationVersion;

        if (existingApplication == null) {
            insertRecord(application, this.mapper::insertApplication);
            applicationVersion = new ApplicationVersion(application);
        } else {
            applicationVersion = new ApplicationVersion(application);
            applicationVersion.setApplicationId(existingApplication.getId());
            application = existingApplication;
        }

        this.mapper.insertApplicationVersion(applicationVersion);
        // Auto update the configurations
        if (existingApplication != null) {
            doAutoUpdateToApplicationVersion(applicationVersion);

        }
//        this.mapper.updateApplicationLatestVersion(application.getId(), applicationVersion.getId());
        this.mapper.recalculateLatestVersion(application.getId());


        return application.getId();
    }

    /**
     * <p>Checks if another application with same package ID and version already exists or not.</p>
     *
     * @param application an application to check against duplicates.
     * @throws DuplicateApplicationException if a duplicated application is found.
     */
    private void guardDuplicateApp(Application application) {
        if (application.getPkg() != null && application.getVersion() != null) {
            final List<Application> dbApps = findByPackageIdAndVersion(application.getPkg(), application.getVersion());
            if (!dbApps.isEmpty()) {
                throw new DuplicateApplicationException(application.getPkg(), application.getVersion(), dbApps.get(0).getCustomerId());
            }
        }
    }

    /**
     * <p>Checks if another application with same package ID and version already exists or not.</p>
     *
     * @param application an application to check against duplicates.
     * @throws DuplicateApplicationException if a duplicated application is found.
     */
    private void guardDuplicateApp(Application application, ApplicationVersion version) {
        if (application.getPkg() != null && version.getVersion() != null) {
            final List<Application> dbApps = findByPackageIdAndVersion(application.getPkg(), version.getVersion());
            if (!dbApps.isEmpty()) {
                throw new DuplicateApplicationException(application.getPkg(), version.getVersion(), dbApps.get(0).getCustomerId());
            }
        }
    }

    /**
     * <p>Checks if another application with same package ID and version already exists or not.</p>
     *
     * @param application an application to check against duplicates.
     * @throws DuplicateApplicationException if a duplicated application is found.
     */
//    private void guardDowngradeAppVersion(Application application) {
//        if (application.getPkg() != null && application.getVersion() != null) {
//            final List<Application> dbApps = findByPackageIdAndNewerVersion(application.getPkg(), application.getVersion());
//            if (!dbApps.isEmpty()) {
//                throw new RecentApplicationVersionExistsException(application.getPkg(), application.getVersion(), dbApps.get(0).getCustomerId());
//            }
//        }
//    }

    /**
     * <p>Checks if another application with same package ID and version already exists or not.</p>
     *
     * @param application an application to check against duplicates.
     * @throws DuplicateApplicationException if a duplicated application is found.
     */
//    private void guardDowngradeAppVersion(Application application, ApplicationVersion version) {
//        if (application.getPkg() != null && version.getVersion() != null) {
//            final List<Application> dbApps = findByPackageIdAndNewerVersion(application.getPkg(), version.getVersion());
//            if (!dbApps.isEmpty()) {
//                throw new RecentApplicationVersionExistsException(application.getPkg(), version.getVersion(), dbApps.get(0).getCustomerId());
//            }
//        }
//    }

    /**
     * <p>Determines the application which the specified application version corresponds to.</p>
     *
     * @param application an application version to resolve application for.
     * @return an application matching the specified application version or <code>null</code> if there is none found.
     * @throws IllegalArgumentException if there is more than 1 candidate application found.
     * @throws CommonAppAccessException if resolved application is a common application and current user is not
     *         a super-admin.
     */
    private Application resolveApplication(Application application) {
        if (application.getPkg() != null) {
            final List<Application> dbApps = findByPackageId(application.getPkg());
            if (!dbApps.isEmpty()) {
                if (dbApps.size() == 1) {
                    return SecurityContext.get().getCurrentUser().map(currentUser -> {
                        final Application dbApp = dbApps.get(0);
                        if (dbApp.isCommon()) {
                            if (dbApp.getCustomerId() != currentUser.getCustomerId()) {
                                throw new CommonAppAccessException(application.getPkg(), dbApps.get(0).getCustomerId());
                            }
                            return dbApp;
                        } else if (dbApp.getCustomerId() == currentUser.getCustomerId()) {
                            return dbApp;
                        } else {
                            return null;
                        }
                    }).orElseThrow(SecurityException::onAnonymousAccess);
                } else {
                    throw new IllegalStateException("More than 1 application with same package ID found: "
                            + application.getPkg());
                }
            }
        }

        return null;
    }

    /**
     * <p>Updates existing application record in DB.</p>
     *
     * @param application an application record to be updated.
     * @throws DuplicateApplicationException if another application record with same package ID and version already
     *         exists either for current or master customer account.
     */
    @Transactional
    public void updateApplication(Application application) {
        final List<Application> dbApps = findByPackageIdAndVersion(application.getPkg(), application.getVersion());
        if (!dbApps.isEmpty()) {
            final boolean exists = dbApps.stream().anyMatch(app -> !app.getId().equals(application.getId()));
            if (exists) {
                throw new DuplicateApplicationException(application.getPkg(), application.getVersion(), dbApps.get(0).getCustomerId());
            }
        }

        updateRecord(application, this.mapper::updateApplication, SecurityException::onApplicationAccessViolation);
    }

    /**
     * <p>Updates existing application version record in DB.</p>
     *
     * @param applicationVersion an application version record to be updated.
     * @throws DuplicateApplicationException if another application record with same package ID and version already
     *         exists either for current or master customer account.
     */
    @Transactional
    public void updateApplicationVersion(ApplicationVersion applicationVersion) {
        final Application application = getSingleRecord(
                () -> this.mapper.findById(applicationVersion.getApplicationId()),
                SecurityException::onApplicationAccessViolation
        );
        final List<Application> dbApps = findByPackageIdAndVersion(application.getPkg(), applicationVersion.getVersion());
        if (!dbApps.isEmpty()) {
            final boolean exists = dbApps.stream().anyMatch(app -> !app.getId().equals(application.getId()));
            if (exists) {
                throw new DuplicateApplicationException(application.getPkg(), applicationVersion.getVersion(), dbApps.get(0).getCustomerId());
            }
        }

        this.mapper.updateApplicationVersion(applicationVersion);
        this.mapper.recalculateLatestVersion(application.getId());
    }

    /**
     * <p>Removes the application referenced by the specified ID. The associated application versions are removed as
     * well.</p>
     *
     * @param id an ID of an application to delete.
     * @throws SecurityException if current user is not granted a permission to delete the specified application.
     */
    @Transactional
    public void removeApplicationById(Integer id) {
        Application dbApplication = this.mapper.findById(id);
        if (dbApplication != null && dbApplication.isCommonApplication()) {
            if (!SecurityContext.get().isSuperAdmin()) {
                throw SecurityException.onAdminDataAccessViolation("delete common application");
            }
        }

        boolean used = this.mapper.isApplicationUsedInConfigurations(id);
        if (used) {
            throw new ApplicationReferenceExistsException(id, "configurations");
        }

        updateById(
                id,
                this::findById,
                (record) -> this.mapper.removeApplicationById(record.getId()),
                SecurityException::onApplicationAccessViolation
        );
    }

    public List<ApplicationConfigurationLink> getApplicationConfigurations(Integer id) {
        return getLinkedList(
                id,
                this::findById,
                customerId -> this.mapper.getApplicationConfigurations(customerId, id),
                SecurityException::onApplicationAccessViolation
        );
    }

    public List<ApplicationVersionConfigurationLink> getApplicationVersionConfigurations(Integer versionId) {
        final ApplicationVersion applicationVersion = findApplicationVersionById(versionId);
        final Application application = this.mapper.findById(applicationVersion.getApplicationId());
        final int userCustomerId = SecurityContext.get().getCurrentUser().get().getCustomerId();

        if (application.isCommon() || application.getCustomerId() == userCustomerId) {
            return this.mapper.getApplicationVersionConfigurationsWithCandidates(userCustomerId, versionId);
        } else {
            throw SecurityException.onApplicationAccessViolation(application);
        }
    }

    @Transactional
    public void updateApplicationConfigurations(LinkConfigurationsToAppRequest request) {
        final List<ApplicationConfigurationLink> deletedLinks = request.getConfigurations().stream().filter(c -> c.getId() != null && c.getAction() == 0).collect(Collectors.toList());
        deletedLinks.forEach(link -> {
            this.mapper.deleteApplicationConfigurationLink(link.getId());
//            final int mainAppUpdateCount =
//                    this.mapper.clearConfigurationMainApplication(link.getConfigurationId(), request.getApplicationId());
//            log.debug("Cleared {} main application of application #{} ({}) for configuration #{} ({})",
//                    mainAppUpdateCount, link.getApplicationId(), link.getApplicationName(),
//                    link.getConfigurationId(), link.getConfigurationName());
//            final int contentAppUpdateCount =
//                    this.mapper.clearConfigurationContentApplication(link.getConfigurationId(), request.getApplicationId());
//            log.debug("Cleared {} content application of application #{} ({}) for configuration #{} ({})",
//                    contentAppUpdateCount, link.getApplicationId(), link.getApplicationName(),
//                    link.getConfigurationId(), link.getConfigurationName());
        });

        final List<ApplicationConfigurationLink> updatedLinks = request.getConfigurations().stream().filter(c -> c.getId() != null && c.getAction() > 0).collect(Collectors.toList());
        updatedLinks.forEach(this.mapper::updateApplicationConfigurationLink);

        final List<ApplicationConfigurationLink> newLinks = request.getConfigurations().stream().filter(c -> c.getId() == null && c.getAction() > 0).collect(Collectors.toList());
        this.insertApplicationConfigurations(request.getApplicationId(), newLinks);

        SecurityContext.get().getCurrentUser().ifPresent(user -> {
            this.mapper.recheckConfigurationMainApplications(user.getCustomerId());
            this.mapper.recheckConfigurationContentApplications(user.getCustomerId());
            this.mapper.recheckConfigurationKioskModes(user.getCustomerId());
        });

    }

    @Transactional
    public void updateApplicationVersionConfigurations(LinkConfigurationsToAppVersionRequest request) {
        final int applicationVersionId = request.getApplicationVersionId();
        this.removeApplicationConfigurationsByVersionId(applicationVersionId);

        // If this version is set for installation, then other versions of same app must be set for de-installation
        final List<ApplicationVersionConfigurationLink> configurations = request.getConfigurations();
        configurations.forEach(link -> {
            if (link.getAction() == 1) {
                final int uninstalledCount = this.mapper.uninstallOtherVersions(applicationVersionId, link.getConfigurationId());
                log.debug("Uninstalled {} application versions of application #{} ({}) for configuration #{} ({})",
                        uninstalledCount, link.getApplicationId(), link.getApplicationName(),
                        link.getConfigurationId(), link.getConfigurationName());

                // Update the Main App and Content App references to refer to new application version (if necessary)
//                final int mainAppUpdateCount
//                        = this.mapper.syncConfigurationMainApplication(link.getConfigurationId(), applicationVersionId);
//                log.debug("Synchronized {} main application versions of application #{} ({}) for configuration #{} ({})",
//                        mainAppUpdateCount, link.getApplicationId(), link.getApplicationName(),
//                        link.getConfigurationId(), link.getConfigurationName());
//                final int contentAppUpdateCount
//                        = this.mapper.syncConfigurationContentApplication(link.getConfigurationId(), applicationVersionId);
//                log.debug("Synchronized {} content application versions of application #{} ({}) for configuration #{} ({})",
//                        contentAppUpdateCount, link.getApplicationId(), link.getApplicationName(),
//                        link.getConfigurationId(), link.getConfigurationName());
            }
        });

        this.insertApplicationVersionConfigurations(applicationVersionId, configurations);

        SecurityContext.get().getCurrentUser().ifPresent(user -> {
            this.mapper.recheckConfigurationMainApplications(user.getCustomerId());
            this.mapper.recheckConfigurationContentApplications(user.getCustomerId());
            this.mapper.recheckConfigurationKioskModes(user.getCustomerId());
        });
    }

    public void removeApplicationConfigurationsByVersionId(Integer applicationVersionId) {
        final ApplicationVersion applicationVersion = findApplicationVersionById(applicationVersionId);
        final Application application = this.mapper.findById(applicationVersion.getApplicationId());
        final int userCustomerId = SecurityContext.get().getCurrentUser().get().getCustomerId();

        if (application.isCommon() || application.getCustomerId() == userCustomerId) {
            this.mapper.removeApplicationVersionConfigurationsById(userCustomerId, applicationVersionId);
        } else {
            throw SecurityException.onApplicationAccessViolation(application);
        }
    }

    public void insertApplicationVersionConfigurations(Integer applicationVersionId, List<ApplicationVersionConfigurationLink> configurations) {
        if (configurations != null && !configurations.isEmpty()) {
            final ApplicationVersion applicationVersion = findApplicationVersionById(applicationVersionId);
            final Application application = this.mapper.findById(applicationVersion.getApplicationId());
            final int userCustomerId = SecurityContext.get().getCurrentUser().get().getCustomerId();

            if (application.isCommon() || application.getCustomerId() == userCustomerId) {
                this.mapper.insertApplicationVersionConfigurations(application.getId(), applicationVersionId, configurations);
            } else {
                throw SecurityException.onApplicationAccessViolation(application);
            }

        }
    }

//    public void removeApplicationConfigurationsById(Integer applicationId) {
//        updateLinkedData(
//                applicationId,
//                this::findById,
//                app -> this.mapper.removeApplicationConfigurationsById(
//                        SecurityContext.get().getCurrentUser().get().getCustomerId(), app.getId()
//                ),
//                SecurityException::onApplicationAccessViolation
//        );
//    }

    public void insertApplicationConfigurations(Integer applicationId, List<ApplicationConfigurationLink> configurations) {
        if (configurations != null && !configurations.isEmpty()) {
            updateLinkedData(
                    applicationId,
                    this::findById,
                    app -> this.mapper.insertApplicationConfigurations(app.getId(), app.getLatestVersion(), configurations),
                    SecurityException::onApplicationAccessViolation
            );
        }
    }

    public List<Application> findByPackageIdAndVersion(String pkg, String version) {
        return getList(customerId -> this.mapper.findByPackageIdAndVersion(customerId, pkg, version));
    }

    public List<Application> findByPackageIdAndNewerVersion(String pkg, String version) {
        return getList(customerId -> this.mapper.findByPackageIdAndNewerVersion(customerId, pkg, version));
    }

    public List<Application> findByPackageId(String pkg) {
        return getList(customerId -> this.mapper.findByPackageId(customerId, pkg));
    }

    public Application findById(int id) {
        return this.mapper.findById(id);
    }

    public ApplicationVersion findApplicationVersionById(int id) {
        return this.mapper.findVersionById(id);
    }

    public List<Application> getAllAdminApplications() {
        if (SecurityContext.get().isSuperAdmin()) {
            return this.mapper.getAllAdminApplications();
        } else {
            throw SecurityException.onAdminDataAccessViolation("get all applications");
        }
    }

    public List<Application> getAllAdminApplicationsByValue(String value) {
        if (SecurityContext.get().isSuperAdmin()) {
            return getList(customerId -> this.mapper.getAllAdminApplicationsByValue("%" + value + "%"));
        } else {
            throw SecurityException.onAdminDataAccessViolation("get all applications");
        }
    }

    @Transactional
    public void turnApplicationIntoCommon_Transaction(Integer id, Map<File, File> filesToCopyCollector) {
        Application application = this.mapper.findById(id);
        if (application != null) {
            if (!application.isCommonApplication()) {
                guardDuplicateApp(application);

                final int currentUserCustomerId = SecurityContext.get().getCurrentUser().get().getCustomerId();
                final Customer newAppCustomer = customerDAO.findById(currentUserCustomerId);

                // Create new common application record
                final Application newCommonApplication = new Application();
                newCommonApplication.setPkg(application.getPkg());
                newCommonApplication.setName(application.getName());
                newCommonApplication.setShowIcon(application.getShowIcon());
                newCommonApplication.setSystem(application.isSystem());
                newCommonApplication.setCustomerId(newAppCustomer.getId());
                newCommonApplication.setLatestVersion(null);

                this.mapper.insertApplication(newCommonApplication);
                final Integer newAppId = newCommonApplication.getId();

                // Find all applications among all customers which have the same package ID and build the list of
                // all possible version for target application
                final List<Application> candidateApplications = mapper.findAllByPackageId(application.getPkg());
                final List<Application> affectedApplications = new ArrayList<>();
                final Map<Integer, Customer> affectedCustomers = new HashMap<>();
                final List<ApplicationVersion> affectedAppVersions = new ArrayList<>();
                Map<String, ApplicationVersion> candidateApplicationVersions = new HashMap<>();
                candidateApplications.forEach(app -> {
                    final List<ApplicationVersion> applicationVersions = this.mapper.getApplicationVersions(app.getId());
                    applicationVersions.forEach(ver -> {
                        final String normalizedVersion = ApplicationUtil.normalizeVersion(ver.getVersion());
                        if (!candidateApplicationVersions.containsKey(normalizedVersion)) {
                            candidateApplicationVersions.put(normalizedVersion, ver);
                        } else {
                            log.debug("Will use following substitution for application versions when turning application {} to common: {} -> {}",
                                    application.getPkg(), ver, candidateApplicationVersions.get(normalizedVersion));
                        }

                        affectedAppVersions.add(ver);
                    });

                    affectedApplications.add(app);
                    affectedCustomers.put(app.getId(), customerDAO.findById(app.getCustomerId()));
                });

                // Re-create the collected application versions and link them to new application. At the same time
                // collect the files to copy to master-customer account
                final Map<String, Integer> versionIdMapping = new HashMap<>();
                candidateApplicationVersions.forEach((normalizedVersionText, appVersionObject) -> {
                    final String newUrl = translateAppVersionUrl(
                            appVersionObject,
                            affectedCustomers.get(appVersionObject.getApplicationId()),
                            newAppCustomer,
                            filesToCopyCollector
                    );

                    ApplicationVersion newAppVersion = new ApplicationVersion();
                    newAppVersion.setApplicationId(newAppId);
                    newAppVersion.setVersion(appVersionObject.getVersion());
                    newAppVersion.setUrl(newUrl);

                    this.mapper.insertApplicationVersion(newAppVersion);

                    versionIdMapping.put(normalizedVersionText, newAppVersion.getId());
                });

                // Replace the references to existing applications and application versions to new one
                affectedAppVersions.forEach(appVer -> {
                    final String normalizedVersionText = ApplicationUtil.normalizeVersion(appVer.getVersion());
                    final Integer newAppVersionId = versionIdMapping.get(normalizedVersionText);

                    mapper.changeConfigurationsApplication(appVer.getApplicationId(), appVer.getId(), newAppId, newAppVersionId);
                    mapper.changeConfigurationsMainApplication(appVer.getId(), newAppVersionId);
                    mapper.changeConfigurationsContentApplication(appVer.getId(), newAppVersionId);

                });

                // Remove migrated applications
                affectedApplications.forEach(app -> {
                    mapper.removeApplicationById(app.getId());
                });

                // Evaluate the most recent version for new common app
                this.mapper.recalculateLatestVersion(newCommonApplication.getId());
            }
        }
    }
    
    public void turnApplicationIntoCommon(Integer id) {
        if (SecurityContext.get().isSuperAdmin()) {
            final Map<File, File> filesToCopy = new HashMap<>();

            turnApplicationIntoCommon_Transaction(id, filesToCopy);

            // Move the files from affected versions
            filesToCopy.forEach((currentAppFile, newAppFile) -> {
                if (newAppFile.exists()) {
                    log.warn("Skip copying file: {} -> {} since the target file already exists",
                            currentAppFile.getAbsolutePath(), newAppFile.getAbsolutePath());
                } else if (!currentAppFile.exists()) {
                    log.warn("Skip copying file: {} -> {} since the source file does not exist",
                            currentAppFile.getAbsolutePath(), newAppFile.getAbsolutePath());
                } else if (!currentAppFile.isFile()) {
                    log.warn("Skip copying file: {} -> {} since the source file is not a regular file",
                            currentAppFile.getAbsolutePath(), newAppFile.getAbsolutePath());
                } else {
                    log.debug("Copying file: {} -> {}...", currentAppFile.getAbsolutePath(), newAppFile.getAbsolutePath());
                    try {
                        Path newAppFileDir = newAppFile.toPath().getParent();
                        newAppFileDir = Files.createDirectories(newAppFileDir);
                        if (!Files.exists(newAppFileDir)) {
                            log.error("Couldn't create a directory '{}' in files area for Master-customer account",
                                    newAppFileDir.toAbsolutePath());
                        } else {
                            Files.copy(currentAppFile.toPath(), newAppFile.toPath());
                            deleteAppFile(currentAppFile);
                        }
                    } catch (IOException e) {
                        log.error("Failed to copy file: {} -> {} due to unexpected error. The process continues.",
                                currentAppFile.getAbsolutePath(), newAppFile.getAbsolutePath());
                    }
                }
            });
        } else {
            throw SecurityException.onAdminDataAccessViolation("turn application into common");
        }
    }

    private void deleteAppFile(File appFile) {
        final boolean deleted = appFile.delete();
        if (deleted) {
            log.info("Deleted the file {} when turning application to common",
                    appFile.getAbsolutePath());
        } else {
            log.error("Failed to delete the file {} when turning application to common",
                    appFile.getAbsolutePath());
        }
    }

    /**
     * <p>Gets the list of versions for specified application.</p>
     *
     * @param id an ID of an application to get versions for.
     * @return a list of versions for requested application.
     */
    public List<ApplicationVersion> getApplicationVersions(Integer id) {
        return SecurityContext.get().getCurrentUser()
                .map(currentUser -> {
                    Application dbApplication = this.mapper.findById(id);
                    if (dbApplication != null) {
                        if (dbApplication.getCustomerId() == currentUser.getCustomerId() || dbApplication.isCommonApplication()) {
                            return this.mapper.getApplicationVersions(id);
                        }
                    }

                    throw SecurityException.onApplicationAccessViolation(id);
                })
                .orElseThrow(SecurityException::onAnonymousAccess);


    }

    /**
     * <p>Removes the application version referenced by the specified ID.</p>
     *
     * @param id an ID of an application to delete.
     * @return an URL for the deleted application version.
     * @throws SecurityException if current user is not granted a permission to delete the specified application.
     */
    @Transactional
    public String removeApplicationVersionById(@NotNull Integer id) {
        ApplicationVersion dbApplicationVersion = this.mapper.findVersionById(id);
        if (dbApplicationVersion != null) {
            if (dbApplicationVersion.isDeletionProhibited()) {
                throw SecurityException.onApplicationVersionAccessViolation(id);
            }

            if (dbApplicationVersion.isCommonApplication()) {
                if (!SecurityContext.get().isSuperAdmin()) {
                    throw SecurityException.onAdminDataAccessViolation("delete common application version");
                }
            }

            final Application dbApplication = this.mapper.findById(dbApplicationVersion.getApplicationId());
            boolean used = this.mapper.isApplicationVersionUsedInConfigurations(id);
            if (used) {
                throw new ApplicationReferenceExistsException(id, "configurations");
            }

//            final Integer toAppVersionId = this.mapper.getPrecedingVersion(dbApplication.getId());
//            this.mapper.autoUpdateConfigurationsApplication(id, toAppVersionId);
//            int autoUpdatedMainAppsCount = this.mapper.autoUpdateConfigurationsMainApplication(
//                    id, toAppVersionId
//            );
//            int autoUpdatedContentAppsCount = this.mapper.autoUpdateConfigurationsContentApplication(
//                    id, toAppVersionId
//            );
//
//            log.debug("Auto-updated main application for {} configurations", autoUpdatedMainAppsCount);
//            log.debug("Auto-updated content application for {} configurations", autoUpdatedContentAppsCount);

            this.mapper.removeApplicationVersionById(id);

            // Recalculate latest version for application if necessary
            if (dbApplication.getLatestVersion() != null && dbApplication.getLatestVersion().equals(id)) {
                this.mapper.recalculateLatestVersion(dbApplication.getId());
            }


            return dbApplicationVersion.getUrl();
        }

        return null;
    }

    /**
     * <p>Removes the application version referenced by the specified ID and deletes the associated APK-file from local
     * file system.</p>
     *
     * @param id an ID of an application to delete.
     * @throws SecurityException if current user is not granted a permission to delete the specified application.
     */
    public void removeApplicationVersionByIdWithAPKFile(@NotNull Integer id) {
        final int customerId = SecurityContext.get().getCurrentUser().get().getCustomerId();
        final Customer customer = customerDAO.findById(customerId);
        final String url = this.removeApplicationVersionById(id);
        if (url != null && !url.trim().isEmpty()) {
            final String apkFile = FileUtil.translateURLToLocalFilePath(customer, url, baseUrl);
            if (apkFile != null) {
                final boolean deleted = FileUtil.deleteFile(filesDirectory, apkFile);
                if (!deleted) {
                    log.warn("Could not delete the APK-file {} related to deleted application version #{}", apkFile, id);
                }
            }
        }
    }

    /**
     * <p>Creates new application version record in DB.</p>
     *
     * @param applicationVersion an application version record to be created.
     * @throws DuplicateApplicationException if another application record with same package ID and version already
     *         exists either for current or master customer account.
     */
    @Transactional
    public int insertApplicationVersion(ApplicationVersion applicationVersion) throws IOException, InterruptedException {
        log.debug("Entering #insertApplicationVersion: application = {}", applicationVersion);

        // If an APK-file was set for new app then make the file available in Files area and parse the app parameters
        // from it (package ID, version)
        final AtomicReference<String> appPkg = new AtomicReference<>();

        final String filePath = applicationVersion.getFilePath();
        if (filePath != null && !filePath.trim().isEmpty()) {
            final int customerId = SecurityContext.get().getCurrentUser().get().getCustomerId();
            Customer customer = customerDAO.findById(customerId);

            File movedFile = FileUtil.moveFile(customer, filesDirectory, null, filePath);
            if (movedFile != null) {
                final String fileName = movedFile.getAbsolutePath();
                final String[] commands = {this.aaptCommand, "dump", "badging", fileName};
                log.debug("Executing shell-commands: {}", Arrays.toString(commands));
                final Process exec = Runtime.getRuntime().exec(commands);

                final AtomicReference<String> appVersion = new AtomicReference<>();
                final List<String> errorLines = new ArrayList<>();

                // Process the error stream by collecting all the error lines for further logging
                StreamGobbler errorGobbler = new StreamGobbler(exec.getErrorStream(), "ERROR", errorLines::add);

                // Process the output by analzying the line starting with "package:"
                StreamGobbler outputGobbler = new StreamGobbler(exec.getInputStream(), "APK-file DUMP", line -> {
                    if (line.startsWith("package:")) {
                        Scanner scanner = new Scanner(line).useDelimiter(" ");
                        while (scanner.hasNext()) {
                            final String token = scanner.next();
                            if (token.startsWith("name=")) {
                                String appPkgLocal = token.substring("name=".length());
                                if (appPkgLocal.startsWith("'") && appPkgLocal.endsWith("'")) {
                                    appPkgLocal = appPkgLocal.substring(1, appPkgLocal.length() - 1);
                                }
                                appPkg.set(appPkgLocal);
                            } else if (token.startsWith("versionName=")) {
                                String appVersionLocal = token.substring("versionName=".length());
                                if (appVersionLocal.startsWith("'") && appVersionLocal.endsWith("'")) {
                                    appVersionLocal = appVersionLocal.substring(1, appVersionLocal.length() - 1);
                                }
                                appVersion.set(appVersionLocal);
                            }
                        }
                    }
                });

                // Get ready to consume input and error streams from the process
                errorGobbler.start();
                outputGobbler.start();

                final int exitCode = exec.waitFor();

                outputGobbler.join();
                errorGobbler.join();
                
                if (exitCode == 0) {
                    // If URL is not specified explicitly for new app then set the application URL to reference to that
                    // file
                    if ((applicationVersion.getUrl() == null || applicationVersion.getUrl().trim().isEmpty())) {
                        applicationVersion.setUrl(this.baseUrl + "/files/" + customer.getFilesDir() + "/" + movedFile.getName());
                    }

                    log.debug("Parsed application name and version from APK-file {}: {} {}", movedFile.getName(), appPkg, appVersion);

                    applicationVersion.setVersion(appVersion.get());

                } else {
                    log.error("Could not analyze the .apk-file {}. The system process returned: {}. The error message follows:", fileName, exitCode);
                    errorLines.forEach(log::error);
                    throw new DAOException("Could not analyze the .apk-file");
                }
            } else {
                log.error("Could not move the uploaded .apk-file {}", filePath);
                throw new DAOException("Could not move the uploaded .apk-file");
            }
        }

        final Application existingApplication = findById(applicationVersion.getApplicationId());
        if (existingApplication != null && existingApplication.isCommonApplication()) {
            if (!SecurityContext.get().isSuperAdmin()) {
                throw SecurityException.onApplicationAccessViolation(existingApplication);
            }
        }

        // Check the version package id against application's package id - they must be the same
        if (existingApplication != null && appPkg.get() != null) {
            if (!existingApplication.getPkg().equals(appPkg.get())) {
                throw new ApplicationVersionPackageMismatchException(appPkg.get(), existingApplication.getPkg());
            }
        }

        guardDuplicateApp(existingApplication, applicationVersion);
//        guardDowngradeAppVersion(existingApplication, applicationVersion);

        this.mapper.insertApplicationVersion(applicationVersion);

        // Auto update the configurations
        doAutoUpdateToApplicationVersion(applicationVersion);

//        this.mapper.updateApplicationLatestVersion(existingApplication.getId(), applicationVersion.getId());
        this.mapper.recalculateLatestVersion(existingApplication.getId());


        return applicationVersion.getId();
    }

    private String translateAppVersionUrl(ApplicationVersion appVersion,
                                          Customer appCustomer,
                                          Customer newAppCustomer,
                                          Map<File, File> fileToCopyCollector) {
        // Update application URL and link it to new customer and copy application file to master
        // customer
        final String currentApplicationUrl = appVersion.getUrl();
        if (currentApplicationUrl != null) {
            final String currentCustomerFileDirUrlPart = "/" + appCustomer.getFilesDir() + "/";
            int pos = currentApplicationUrl.indexOf(currentCustomerFileDirUrlPart);
            if (pos >= 0) {

                final String relativeFilePath = currentApplicationUrl.substring(pos + 1);
                final File newCustomerFilesBaseDir = new File(this.filesDirectory, newAppCustomer.getFilesDir());

                final File currentAppFile = new File(this.filesDirectory, relativeFilePath);
                final File newAppFile = new File(newCustomerFilesBaseDir, relativeFilePath);

                fileToCopyCollector.put(currentAppFile, newAppFile);

                return this.baseUrl + "/files/" + newAppCustomer.getFilesDir() + "/" + relativeFilePath;
            } else {
                log.warn("Invalid application URL does not contain the base directory for customer files: {}" ,
                        currentApplicationUrl);
            }
        }

        return null;
    }

    /**
     * <p>Updates the configurations which have the AUTO-UPDATE flag set to true to refer to newly added application
     * version.</p>
     *
     * @param newApplicationVersion a new application version to update the configuration references to.
     */
    private void doAutoUpdateToApplicationVersion( ApplicationVersion newApplicationVersion) {
        int autoUpdatedConfigAppsCount  = this.mapper.autoUpdateConfigurationsApplication(
                newApplicationVersion.getApplicationId(), newApplicationVersion.getId()
        );
        int autoUpdatedMainAppsCount = this.mapper.autoUpdateConfigurationsMainApplication(
                newApplicationVersion.getApplicationId(), newApplicationVersion.getId()
        );
        int autoUpdatedContentAppsCount = this.mapper.autoUpdateConfigurationsContentApplication(
                newApplicationVersion.getApplicationId(), newApplicationVersion.getId()
        );

        log.debug("Auto-updated {} application links for configurations", autoUpdatedConfigAppsCount);
        log.debug("Auto-updated main application for {} configurations", autoUpdatedMainAppsCount);
        log.debug("Auto-updated content application for {} configurations", autoUpdatedContentAppsCount);
    }

    /**
     * <p>Gets the lookup list of applications matching the package ID with specified filter.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @param resultsCount a maximum number of items to be included to list.
     * @return a response with list of applications matching the specified filter.
     */
    public List<LookupItem> getApplicationPkgLookup(String filter, int resultsCount) {
        String searchFilter = '%' + filter.trim() + '%';
        return SecurityContext.get().getCurrentUser()
                .map(u -> this.mapper.findMatchingApplicationPackages(u.getCustomerId(), searchFilter, resultsCount))
                .orElse(new ArrayList<>());
    }

    /**
     * <p>A consumer for the stream contents. Outputs the line read from the stream and passes it to provided line
     * consumer.</p>
     */
    private class StreamGobbler extends Thread {
        private final InputStream is;
        private final String type;
        private final Consumer<String> lineConsumer;

        private StreamGobbler(InputStream is, String type, Consumer<String> lineConsumer) {
            this.is = is;
            this.type = type;
            this.lineConsumer = lineConsumer;
        }

        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug(type + "> " + line);
                    this.lineConsumer.accept(line);
                }
            } catch (Exception e) {
                log.error("An error in {} stream handler for external process {}", this.type, aaptCommand, e);
            }
        }
    }
}