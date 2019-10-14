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

package com.hmdm.util;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * <p>A service used for running the standalone tasks in background.</p>
 *
 * @author isv
 */
@Singleton
public class BackgroundTaskRunnerService {

    private final static Logger logger = LoggerFactory.getLogger(BackgroundTaskRunnerService.class);

    /**
     * <p>An executor for the tasks to be executed in background.</p>
     */
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);


    /**
     * <p>Constructs new <code>BackgroundTaskRunnerService</code> instance. This implementation does nothing.</p>
     */
    public BackgroundTaskRunnerService() {
        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdown));
    }

    /**
     * <p>Submits the specified task for execution in the background thread.</p>
     *
     * @param task a task to be executed in background.
     */
    public void submitTask(Runnable task) {
        logger.debug("Submitting task for execution: {}. The current state of executor: active tasks: {}, " +
                        "tasks count: {}, queue size: {}",
                task, executor.getActiveCount(), executor.getTaskCount(), executor.getQueue().size());
        this.executor.submit(task);
    }

}
