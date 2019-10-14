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

package com.hmdm.persistence.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;

import java.io.Serializable;

@ApiModel(description = "A specification of a single application version installed and used on mobile device")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApplicationVersion implements Serializable {

    private static final long serialVersionUID = -7253076157423236073L;
    @ApiModelProperty("An application version ID")
    private Integer id;

    @ApiModelProperty("An application ID")
    private Integer applicationId;

    @ApiModelProperty("A version of application")
    private String version;

    @ApiModelProperty("An URL for application package")
    private String url;

    @ApiModelProperty(hidden = true)
    private boolean deletionProhibited;

    @ApiModelProperty(hidden = true)
    private boolean commonApplication;

    @ApiModelProperty(hidden = true)
    private boolean system;

    @ApiModelProperty(hidden = true)
    private String apkHash;

    /**
     * <p>A path to uploaded file to link this application to when adding an application.</p>
     */
    @ApiModelProperty(hidden = true)
    private String filePath;

    /**
     * <p>Constructs new <code>ApplicationVersion</code> instance. This implementation does nothing.</p>
     */
    public ApplicationVersion() {
    }

    /**
     * <p>Constructs new <code>ApplicationVersion</code> instance. This implementation does nothing.</p>
     */
    public ApplicationVersion(Application application) {
        this.applicationId = application.getId();
        this.version = application.getVersion();
        this.url = application.getUrl();
    }



    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Integer applicationId) {
        this.applicationId = applicationId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isDeletionProhibited() {
        return deletionProhibited;
    }

    public void setDeletionProhibited(boolean deletionProhibited) {
        this.deletionProhibited = deletionProhibited;
    }

    public boolean isCommonApplication() {
        return commonApplication;
    }

    public void setCommonApplication(boolean commonApplication) {
        this.commonApplication = commonApplication;
    }

    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getApkHash() {
        return apkHash;
    }

    public void setApkHash(String apkHash) {
        this.apkHash = apkHash;
    }

    @Override
    public String toString() {
        return "ApplicationVersion{" +
                "id=" + id +
                ", applicationId=" + applicationId +
                ", version='" + version + '\'' +
                ", system='" + system + '\'' +
                ", url='" + url + '\'' +
                ", apkHash='" + apkHash + '\'' +
                ", deletionProhibited='" + deletionProhibited + '\'' +
                ", commonApplication='" + commonApplication + '\'' +
                ", filePath='" + filePath + '\'' +
                '}';
    }
}
