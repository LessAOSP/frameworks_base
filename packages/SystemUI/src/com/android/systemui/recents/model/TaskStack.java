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
 * limitations under the License.
 */

package com.android.systemui.recents.model;

import com.android.systemui.recents.Constants;
import com.android.systemui.recents.misc.NamedCounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;


/**
 * An interface for a task filter to query whether a particular task should show in a stack.
 */
interface TaskFilter {
    /** Returns whether the filter accepts the specified task */
    public boolean acceptTask(Task t, int index);
}

/**
 * A list of filtered tasks.
 */
class FilteredTaskList {
    ArrayList<Task> mTasks = new ArrayList<Task>();
    ArrayList<Task> mFilteredTasks = new ArrayList<Task>();
    HashMap<Task.TaskKey, Integer> mTaskIndices = new HashMap<Task.TaskKey, Integer>();
    TaskFilter mFilter;

    /** Sets the task filter, saving the current touch state */
    boolean setFilter(TaskFilter filter) {
        ArrayList<Task> prevFilteredTasks = new ArrayList<Task>(mFilteredTasks);
        mFilter = filter;
        updateFilteredTasks();
        if (!prevFilteredTasks.equals(mFilteredTasks)) {
            return true;
        } else {
            // If the tasks are exactly the same pre/post filter, then just reset it
            mFilter = null;
            return false;
        }
    }

    /** Removes the task filter and returns the previous touch state */
    void removeFilter() {
        mFilter = null;
        updateFilteredTasks();
    }

    /** Adds a new task to the task list */
    void add(Task t) {
        mTasks.add(t);
        updateFilteredTasks();
    }

    /** Sets the list of tasks */
    void set(List<Task> tasks) {
        mTasks.clear();
        mTasks.addAll(tasks);
        updateFilteredTasks();
    }

    /** Removes a task from the base list only if it is in the filtered list */
    boolean remove(Task t) {
        if (mFilteredTasks.contains(t)) {
            boolean removed = mTasks.remove(t);
            updateFilteredTasks();
            return removed;
        }
        return false;
    }

    /** Returns the index of this task in the list of filtered tasks */
    int indexOf(Task t) {
        return mTaskIndices.get(t.key);
    }

    /** Returns the size of the list of filtered tasks */
    int size() {
        return mFilteredTasks.size();
    }

    /** Returns whether the filtered list contains this task */
    boolean contains(Task t) {
        return mTaskIndices.containsKey(t.key);
    }

    /** Updates the list of filtered tasks whenever the base task list changes */
    private void updateFilteredTasks() {
        mFilteredTasks.clear();
        if (mFilter != null) {
            int taskCount = mTasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task t = mTasks.get(i);
                if (mFilter.acceptTask(t, i)) {
                    mFilteredTasks.add(t);
                }
            }
        } else {
            mFilteredTasks.addAll(mTasks);
        }
        updateFilteredTaskIndices();
    }

    /** Updates the mapping of tasks to indices. */
    private void updateFilteredTaskIndices() {
        mTaskIndices.clear();
        int taskCount = mFilteredTasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task t = mFilteredTasks.get(i);
            mTaskIndices.put(t.key, i);
        }
    }

    /** Returns whether this task list is filtered */
    boolean hasFilter() {
        return (mFilter != null);
    }

    /** Returns the list of filtered tasks */
    ArrayList<Task> getTasks() {
        return mFilteredTasks;
    }
}

/**
 * The task stack contains a list of multiple tasks.
 */
public class TaskStack {

    /** Task stack callbacks */
    public interface TaskStackCallbacks {
        /* Notifies when a task has been added to the stack */
        public void onStackTaskAdded(TaskStack stack, Task t);
        /* Notifies when a task has been removed from the stack */
        public void onStackTaskRemoved(TaskStack stack, Task t);
        /** Notifies when the stack was filtered */
        public void onStackFiltered(TaskStack newStack, ArrayList<Task> curTasks, Task t);
        /** Notifies when the stack was un-filtered */
        public void onStackUnfiltered(TaskStack newStack, ArrayList<Task> curTasks);
    }

    /** A pair of indices representing the group and task positions in the stack and group. */
    public static class GroupTaskIndex {
        public int groupIndex; // Index in the stack
        public int taskIndex;  // Index in the group

        public GroupTaskIndex() {}

        public GroupTaskIndex(int gi, int ti) {
            groupIndex = gi;
            taskIndex = ti;
        }
    }

    FilteredTaskList mTaskList = new FilteredTaskList();
    TaskStackCallbacks mCb;

    ArrayList<TaskGrouping> mGroups = new ArrayList<TaskGrouping>();
    HashMap<String, TaskGrouping> mAffinitiesGroups = new HashMap<String, TaskGrouping>();
    HashMap<TaskGrouping, Integer> mGroupsIndices = new HashMap<TaskGrouping, Integer>();

    /** Sets the callbacks for this task stack */
    public void setCallbacks(TaskStackCallbacks cb) {
        mCb = cb;
    }

    /** Adds a new task */
    public void addTask(Task t) {
        mTaskList.add(t);
        if (mCb != null) {
            mCb.onStackTaskAdded(this, t);
        }
    }

    /** Removes a task */
    public void removeTask(Task t) {
        if (mTaskList.contains(t)) {
            // Remove the task from the list
            mTaskList.remove(t);
            // Remove it from the group as well, and if it is empty, remove the group
            TaskGrouping group = t.group;
            group.removeTask(t);
            if (group.getTaskCount() == 0) {
                removeGroup(group);
            }
            if (mCb != null) {
                // Notify that a task has been removed
                mCb.onStackTaskRemoved(this, t);
            }
        }
    }

    /** Sets a few tasks in one go */
    public void setTasks(List<Task> tasks) {
        ArrayList<Task> taskList = mTaskList.getTasks();
        int taskCount = taskList.size();
        for (int i = 0; i < taskCount; i++) {
            Task t = taskList.get(i);
            // Remove the task from the list
            mTaskList.remove(t);
            // Remove it from the group as well, and if it is empty, remove the group
            TaskGrouping group = t.group;
            group.removeTask(t);
            if (group.getTaskCount() == 0) {
                removeGroup(group);
            }
            if (mCb != null) {
                // Notify that a task has been removed
                mCb.onStackTaskRemoved(this, t);
            }
        }
        mTaskList.set(tasks);
        for (Task t : tasks) {
            if (mCb != null) {
                mCb.onStackTaskAdded(this, t);
            }
        }
    }

    /** Gets the front task */
    public Task getFrontMostTask() {
        return mTaskList.getTasks().get(mTaskList.size() - 1);
    }

    /** Gets the tasks */
    public ArrayList<Task> getTasks() {
        return mTaskList.getTasks();
    }

    /** Gets the number of tasks */
    public int getTaskCount() {
        return mTaskList.size();
    }

    /** Returns the index of this task in this current task stack */
    public int indexOfTask(Task t) {
        return mTaskList.indexOf(t);
    }

    /******** Filtering ********/

    /** Filters the stack into tasks similar to the one specified */
    public void filterTasks(final Task t) {
        ArrayList<Task> oldStack = new ArrayList<Task>(mTaskList.getTasks());

        // Set the task list filter
        boolean filtered = mTaskList.setFilter(new TaskFilter() {
            @Override
            public boolean acceptTask(Task at, int i) {
                return t.key.baseIntent.getComponent().getPackageName().equals(
                        at.key.baseIntent.getComponent().getPackageName());
            }
        });
        if (filtered && mCb != null) {
            mCb.onStackFiltered(this, oldStack, t);
        }
    }

    /** Unfilters the current stack */
    public void unfilterTasks() {
        ArrayList<Task> oldStack = new ArrayList<Task>(mTaskList.getTasks());

        // Unset the filter, then update the virtual scroll
        mTaskList.removeFilter();
        if (mCb != null) {
            mCb.onStackUnfiltered(this, oldStack);
        }
    }

    /** Returns whether tasks are currently filtered */
    public boolean hasFilteredTasks() {
        return mTaskList.hasFilter();
    }

    /******** Grouping ********/

    /** Adds a group to the set */
    public void addGroup(TaskGrouping group) {
        mGroups.add(group);
        mAffinitiesGroups.put(group.affiliation, group);
        updateTaskGroupingIndices();
    }

    public void removeGroup(TaskGrouping group) {
        // XXX: Ensure that there are no more tasks in this group
        mGroups.remove(group);
        mAffinitiesGroups.remove(group.affiliation);
        mGroupsIndices.remove(group);
        updateTaskGroupingIndices();
    }

    /** Adds a mapping from a task to a group. */
    public void addTaskToGroup(TaskGrouping group, Task task) {
        if (!mAffinitiesGroups.containsKey(group.affiliation)) {
            throw new RuntimeException("Unexpected group");
        }
        group.addTask(task);
    }

    /** Returns the group with the specified affiliation. */
    public TaskGrouping getGroupWithAffiliation(String affiliation) {
        return mAffinitiesGroups.get(affiliation);
    }

    /** Returns the number of groups. */
    public int getGroupingCount() {
        return mGroups.size();
    }

    /** Returns the group and task indices for a given task. */
    public void getGroupIndexForTask(Task task, GroupTaskIndex indices) {
        indices.groupIndex = mGroupsIndices.get(task.group);
        indices.taskIndex = task.group.indexOf(task);
    }

    /**
     * Temporary: This method will simulate affiliation groups by
     */
    public void createSimulatedAffiliatedGroupings() {
        if (Constants.DebugFlags.App.EnableSimulatedTaskGroups) {
            HashMap<Task.TaskKey, Task> taskMap = new HashMap<Task.TaskKey, Task>();
            // Sort all tasks by increasing firstActiveTime of the task
            ArrayList<Task> tasks = mTaskList.getTasks();
            Collections.sort(tasks, new Comparator<Task>() {
                @Override
                public int compare(Task task, Task task2) {
                    return (int) (task.key.firstActiveTime - task2.key.firstActiveTime);
                }
            });
            // Create groups when sequential packages are the same
            NamedCounter counter = new NamedCounter("task-group", "");
            int taskCount = tasks.size();
            String prevPackage = "";
            String prevAffiliation = "";
            for (int i = 0; i < taskCount; i++) {
                Task t = tasks.get(i);
                String packageName = t.key.baseIntent.getComponent().getPackageName();
                TaskGrouping group;
                if (packageName.equals(prevPackage)) {
                    group = getGroupWithAffiliation(prevAffiliation);
                } else {
                    String affiliation = counter.nextName();
                    group = new TaskGrouping(affiliation);
                    addGroup(group);
                    prevAffiliation = affiliation;
                    prevPackage = packageName;
                }
                group.addTask(t);
                taskMap.put(t.key, t);
            }
            // Sort groups by increasing latestActiveTime of the group
            Collections.sort(mGroups, new Comparator<TaskGrouping>() {
                @Override
                public int compare(TaskGrouping taskGrouping, TaskGrouping taskGrouping2) {
                    return (int) (taskGrouping.latestActiveTimeInGroup -
                            taskGrouping2.latestActiveTimeInGroup);
                }
            });
            updateTaskGroupingIndices();
            // Sort group tasks by increasing firstActiveTime of the task, and also build a new list of
            // tasks
            int taskIndex = 0;
            int groupCount = mGroups.size();
            for (int i = 0; i < groupCount; i++) {
                TaskGrouping group = mGroups.get(i);
                Collections.sort(group.mTasks, new Comparator<Task.TaskKey>() {
                    @Override
                    public int compare(Task.TaskKey taskKey, Task.TaskKey taskKey2) {
                        return (int) (taskKey.firstActiveTime - taskKey2.firstActiveTime);
                    }
                });
                ArrayList<Task.TaskKey> groupTasks = group.mTasks;
                int groupTaskCount = groupTasks.size();
                for (int j = 0; j < groupTaskCount; j++) {
                    tasks.set(taskIndex, taskMap.get(groupTasks.get(j)));
                    taskIndex++;
                }
            }
            mTaskList.set(tasks);
        } else {
            // Create a group per task
            NamedCounter counter = new NamedCounter("task-group", "");
            ArrayList<Task> tasks = mTaskList.getTasks();
            int taskCount = tasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task t = tasks.get(i);
                TaskGrouping group = new TaskGrouping(counter.nextName());
                addGroup(group);
                group.addTask(t);
            }
        }
    }

    /** Updates the mapping of tasks to indices. */
    private void updateTaskGroupingIndices() {
        mGroupsIndices.clear();
        int groupsCount = mGroups.size();
        for (int i = 0; i < groupsCount; i++) {
            TaskGrouping g = mGroups.get(i);
            mGroupsIndices.put(g, i);
        }
    }

    @Override
    public String toString() {
        String str = "Tasks:\n";
        for (Task t : mTaskList.getTasks()) {
            str += "  " + t.toString() + "\n";
        }
        return str;
    }
}