/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui;

import android.app.Application;

import com.android.systemui.recent.RecentTasksLoader;
import com.android.systemui.recent.RecentsActivity;

public class SystemUIApplication extends Application {
    private RecentTasksLoader mRecentTasksLoader;
    private boolean mWaitingForWinAnimStart;
    private RecentsActivity.WindowAnimationStartListener mWinAnimStartListener;

    public RecentTasksLoader getRecentTasksLoader() {
        if (mRecentTasksLoader == null) {
            mRecentTasksLoader = new RecentTasksLoader(this);
        }
        return mRecentTasksLoader;
    }

    public void setWaitingForWinAnimStart(boolean waiting) {
        mWaitingForWinAnimStart = waiting;
    }

    public void setWindowAnimationStartListener(
            RecentsActivity.WindowAnimationStartListener startListener) {
        mWinAnimStartListener = startListener;
    }

    public RecentsActivity.WindowAnimationStartListener getWindowAnimationListener() {
        return mWinAnimStartListener;
    }

    public void onWindowAnimationStart() {
        if (mWinAnimStartListener != null) {
            mWinAnimStartListener.onWindowAnimationStart();
        }
        mWaitingForWinAnimStart = false;
    }

    public boolean isWaitingForWindowAnimationStart() {
        return mWaitingForWinAnimStart;
    }
}