/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package android.content;

import java.util.List;

/**
 * Class for scheduling various types of tasks with the scheduling framework on the device.
 *
 * Get an instance of this class through {@link Context#getSystemService(String)}.
 */
public abstract class TaskManager {
    /*
     * Returned from {@link #schedule(Task)} when an invalid parameter was supplied. This can occur
     * if the run-time for your task is too short, or perhaps the system can't resolve the
     * requisite {@link TaskService} in your package.
     */
    static final int RESULT_INVALID_PARAMETERS = -1;
    /**
     * Returned from {@link #schedule(Task)} if this application has made too many requests for
     * work over too short a time.
     */
    // TODO: Determine if this is necessary.
    static final int RESULT_OVER_QUOTA = -2;

    /*
     * @param task The task you wish scheduled. See {@link Task#TaskBuilder} for more detail on
     * the sorts of tasks you can schedule.
     * @return If >0, this int corresponds to the taskId of the successfully scheduled task.
     * Otherwise you have to compare the return value to the error codes defined in this class.
     */
    public abstract int schedule(Task task);

    /**
     * Cancel a task that is pending in the TaskManager.
     * @param taskId unique identifier for this task. Obtain this value from the tasks returned by
     * {@link #getAllPendingTasks()}.
     * @return
     */
    public abstract void cancel(int taskId);

    /**
     * Cancel all tasks that have been registered with the TaskManager by this package.
     */
    public abstract void cancelAll();

    /**
     * @return a list of all the tasks registered by this package that have not yet been executed.
     */
    public abstract List<Task> getAllPendingTasks();

}
