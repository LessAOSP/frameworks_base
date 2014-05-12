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

package com.android.server.task.controllers;

import android.content.ComponentName;
import android.content.Task;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uniquely identifies a task internally.
 * Created from the public {@link android.content.Task} object when it lands on the scheduler.
 * Contains current state of the requirements of the task, as well as a function to evaluate
 * whether it's ready to run.
 * This object is shared among the various controllers - hence why the different fields are atomic.
 * This isn't strictly necessary because each controller is only interested in a specific field,
 * and the receivers that are listening for global state change will all run on the main looper,
 * but we don't enforce that so this is safer.
 * @hide
 */
public class TaskStatus {
    final int taskId;
    final int userId;
    ComponentName component;

    final AtomicBoolean chargingConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean timeConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean idleConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean meteredConstraintSatisfied = new AtomicBoolean();
    final AtomicBoolean connectivityConstraintSatisfied = new AtomicBoolean();

    private final boolean hasChargingConstraint;
    private final boolean hasTimingConstraint;
    private final boolean hasIdleConstraint;
    private final boolean hasMeteredConstraint;
    private final boolean hasConnectivityConstraint;

    private long earliestRunTimeElapsedMillis;
    private long latestRunTimeElapsedMillis;

    /** Provide a unique handle to the service that this task will be run on. */
    public int getServiceToken() {
        return component.hashCode() + userId;
    }

    /** Generate a TaskStatus object for a given task and uid. */
    // TODO: reimplement this to reuse these objects instead of creating a new one each time?
    static TaskStatus getForTaskAndUid(Task task, int uId) {
        return new TaskStatus(task, uId);
    }

    /** Set up the state of a newly scheduled task. */
    TaskStatus(Task task, int userId) {
        this.taskId = task.getTaskId();
        this.userId = userId;
        this.component = task.getService();

        hasChargingConstraint = task.isRequireCharging();
        hasIdleConstraint = task.isRequireDeviceIdle();

        // Timing constraints
        if (task.isPeriodic()) {
            long elapsedNow = SystemClock.elapsedRealtime();
            earliestRunTimeElapsedMillis = elapsedNow;
            latestRunTimeElapsedMillis = elapsedNow + task.getIntervalMillis();
            hasTimingConstraint = true;
        } else if (task.getMinLatencyMillis() != 0L || task.getMaxExecutionDelayMillis() != 0L) {
            earliestRunTimeElapsedMillis = task.getMinLatencyMillis() > 0L ?
                    task.getMinLatencyMillis() : Long.MAX_VALUE;
            latestRunTimeElapsedMillis = task.getMaxExecutionDelayMillis() > 0L ?
                    task.getMaxExecutionDelayMillis() : Long.MAX_VALUE;
            hasTimingConstraint = true;
        } else {
            hasTimingConstraint = false;
        }

        // Networking constraints
        hasMeteredConstraint = task.getNetworkCapabilities() == Task.NetworkType.UNMETERED;
        hasConnectivityConstraint = task.getNetworkCapabilities() == Task.NetworkType.ANY;
    }

    boolean hasConnectivityConstraint() {
        return hasConnectivityConstraint;
    }

    boolean hasMeteredConstraint() {
        return hasMeteredConstraint;
    }

    boolean hasChargingConstraint() {
        return hasChargingConstraint;
    }

    boolean hasTimingConstraint() {
        return hasTimingConstraint;
    }

    boolean hasIdleConstraint() {
        return hasIdleConstraint;
    }

    long getEarliestRunTime() {
        return earliestRunTimeElapsedMillis;
    }

    long getLatestRunTime() {
        return latestRunTimeElapsedMillis;
    }

    /**
     * @return whether this task is ready to run, based on its requirements.
     */
    public synchronized boolean isReady() {
        return (!hasChargingConstraint || chargingConstraintSatisfied.get())
                && (!hasTimingConstraint || timeConstraintSatisfied.get())
                && (!hasConnectivityConstraint || connectivityConstraintSatisfied.get())
                && (!hasMeteredConstraint || meteredConstraintSatisfied.get())
                && (!hasIdleConstraint || idleConstraintSatisfied.get());
    }

    @Override
    public int hashCode() {
        int result = component.hashCode();
        result = 31 * result + taskId;
        result = 31 * result + userId;
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskStatus)) return false;

        TaskStatus that = (TaskStatus) o;
        return ((taskId == that.taskId)
                && (userId == that.userId)
                && (component.equals(that.component)));
    }
}
