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

package com.android.systemui.recents.views;

import android.app.ActivityOptions;
import android.app.TaskStackBuilder;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsTaskLoader;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;


/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout implements TaskStackView.TaskStackViewCallbacks {

    /** The RecentsView callbacks */
    public interface RecentsViewCallbacks {
        public void onTaskLaunching();
    }

    // The space partitioning root of this container
    SpaceNode mBSP;
    // Recents view callbacks
    RecentsViewCallbacks mCb;

    public RecentsView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /** Sets the callbacks */
    public void setCallbacks(RecentsViewCallbacks cb) {
        mCb = cb;
    }

    /** Set/get the bsp root node */
    public void setBSP(SpaceNode n) {
        mBSP = n;

        // Create and add all the stacks for this partition of space.
        removeAllViews();
        ArrayList<TaskStack> stacks = mBSP.getStacks();
        for (TaskStack stack : stacks) {
            TaskStackView stackView = new TaskStackView(getContext(), stack);
            stackView.setCallbacks(this);
            addView(stackView);
        }
    }

    /** Launches the first task from the first stack if possible */
    public boolean launchFirstTask() {
        // Get the first stack view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            TaskStackView stackView = (TaskStackView) getChildAt(i);
            TaskStack stack = stackView.mStack;
            ArrayList<Task> tasks = stack.getTasks();

            // Get the first task in the stack
            if (!tasks.isEmpty()) {
                Task task = tasks.get(tasks.size() - 1);
                TaskView tv = null;

                // Try and use the first child task view as the source of the launch animation
                if (stackView.getChildCount() > 0) {
                    TaskView stv = (TaskView) stackView.getChildAt(stackView.getChildCount() - 1);
                    if (stv.getTask() == task) {
                        tv = stv;
                    }
                }
                onTaskLaunched(stackView, tv, stack, task);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[RecentsView|measure]",
                "width: " + width + " height: " + height, Console.AnsiGreen);
        Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsStartup,
                Constants.DebugFlags.App.TimeRecentsStartupKey, "RecentsView.onMeasure");

        // We measure our stack views sans the status bar.  It will handle the nav bar itself.
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        int childWidth = width - config.systemInsets.right;
        int childHeight = height - config.systemInsets.top;

        // Measure each child
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, widthMode),
                        MeasureSpec.makeMeasureSpec(childHeight, heightMode));
            }
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout, "[RecentsView|layout]",
                new Rect(left, top, right, bottom) + " changed: " + changed, Console.AnsiGreen);
        Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsStartup,
                Constants.DebugFlags.App.TimeRecentsStartupKey, "RecentsView.onLayout");

        // We offset our stack views by the status bar height.  It will handle the nav bar itself.
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        top += config.systemInsets.top;

        // Layout each child
        // XXX: Based on the space node for that task view
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();
                child.layout(left, top, left + width, top + height);
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        Console.log(Constants.DebugFlags.UI.Draw, "[RecentsView|dispatchDraw]", "",
                Console.AnsiPurple);
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout,
                "[RecentsView|fitSystemWindows]", "insets: " + insets, Console.AnsiGreen);

        // Update the configuration with the latest system insets and trigger a relayout
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        config.updateSystemInsets(insets);
        requestLayout();

        return true;
    }

    /** Closes any open info panes */
    public boolean closeOpenInfoPanes() {
        if (mBSP != null) {
            // Get the first stack view
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                TaskStackView stackView = (TaskStackView) getChildAt(i);
                if (stackView.closeOpenInfoPanes()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Unfilters any filtered stacks */
    public boolean unfilterFilteredStacks() {
        if (mBSP != null) {
            // Check if there are any filtered stacks and unfilter them before we back out of Recents
            boolean stacksUnfiltered = false;
            ArrayList<TaskStack> stacks = mBSP.getStacks();
            for (TaskStack stack : stacks) {
                if (stack.hasFilteredTasks()) {
                    stack.unfilterTasks();
                    stacksUnfiltered = true;
                }
            }
            return stacksUnfiltered;
        }
        return false;
    }

    /**** TaskStackView.TaskStackCallbacks Implementation ****/

    @Override
    public void onTaskLaunched(final TaskStackView stackView, final TaskView tv,
                               final TaskStack stack, final Task task) {
        // Notify any callbacks of the launching of a new task
        if (mCb != null) {
            mCb.onTaskLaunching();
        }

        // Close any open info panes
        closeOpenInfoPanes();

        final Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                TaskViewTransform transform;
                View sourceView = tv;
                int offsetX = 0;
                int offsetY = 0;
                int stackScroll = stackView.getStackScroll();
                if (tv == null) {
                    // If there is no actual task view, then use the stack view as the source view
                    // and then offset to the expected transform rect, but bound this to just
                    // outside the display rect (to ensure we don't animate from too far away)
                    RecentsConfiguration config = RecentsConfiguration.getInstance();
                    sourceView = stackView;
                    transform = stackView.getStackTransform(stack.indexOfTask(task), stackScroll);
                    offsetX = transform.rect.left;
                    offsetY = Math.min(transform.rect.top, config.displayRect.height());
                } else {
                    transform = stackView.getStackTransform(stack.indexOfTask(task), stackScroll);
                }

                // Compute the thumbnail to scale up from
                ActivityOptions opts = null;
                int thumbnailWidth = transform.rect.width();
                int thumbnailHeight = transform.rect.height();
                if (task.thumbnail != null && thumbnailWidth > 0 && thumbnailHeight > 0 &&
                        task.thumbnail.getWidth() > 0 && task.thumbnail.getHeight() > 0) {
                    // Resize the thumbnail to the size of the view that we are animating from
                    Bitmap b = Bitmap.createBitmap(thumbnailWidth, thumbnailHeight,
                            Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    c.drawBitmap(task.thumbnail,
                            new Rect(0, 0, task.thumbnail.getWidth(), task.thumbnail.getHeight()),
                            new Rect(0, 0, thumbnailWidth, thumbnailHeight), null);
                    c.setBitmap(null);
                    opts = ActivityOptions.makeThumbnailScaleUpAnimation(sourceView,
                            b, offsetX, offsetY);
                }


                if (task.isActive) {
                    // Bring an active task to the foreground
                    RecentsTaskLoader.getInstance().getSystemServicesProxy()
                            .moveTaskToFront(task.key.id, opts);
                } else {
                    // Launch the activity with the desired animation
                    Intent i = new Intent(task.key.baseIntent);
                    i.setFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                            | Intent.FLAG_ACTIVITY_TASK_ON_HOME
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        UserHandle taskUser = new UserHandle(task.userId);
                        if (opts != null) {
                            getContext().startActivityAsUser(i, opts.toBundle(), taskUser);
                        } else {
                            getContext().startActivityAsUser(i, taskUser);
                        }
                    } catch (ActivityNotFoundException anfe) {
                        Console.logError(getContext(), "Could not start Activity");
                    }
                }

                Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsLaunchTask,
                        Constants.DebugFlags.App.TimeRecentsLaunchKey, "startActivity");
            }
        };

        Console.logTraceTime(Constants.DebugFlags.App.TimeRecentsLaunchTask,
                Constants.DebugFlags.App.TimeRecentsLaunchKey, "onTaskLaunched");

        // Launch the app right away if there is no task view, otherwise, animate the icon out first
        if (tv == null || !Constants.Values.TaskView.AnimateFrontTaskBarOnLeavingRecents) {
            post(launchRunnable);
        } else {
            tv.animateOnLeavingRecents(launchRunnable);
        }
    }

    @Override
    public void onTaskAppInfoLaunched(Task t) {
        // Create a new task stack with the application info details activity
        Intent baseIntent = t.key.baseIntent;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", baseIntent.getComponent().getPackageName(), null));
        intent.setComponent(intent.resolveActivity(getContext().getPackageManager()));
        TaskStackBuilder.create(getContext())
                .addNextIntentWithParentStack(intent).startActivities();
    }
}
