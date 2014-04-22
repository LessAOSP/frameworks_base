/**
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.task;

import android.app.task.ITaskCallback;
import android.app.task.TaskParams;

import android.os.Bundle;

/**
 * Interface that the framework uses to communicate with application code
 * that implements a TaskService.  End user code does not implement this interface directly;
 * instead, the app's idle service implementation will extend android.app.maintenance.IdleService.
 * {@hide}
 */
oneway interface ITaskService {
    /** Begin execution of application's task. */
    void startTask(in TaskParams taskParams);
    /** Stop execution of application's task. */
    void stopTask(in TaskParams taskParams);
}
