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

package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ILauncherApps;
import android.content.pm.IOnAppsChangedListener;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class for retrieving a list of launchable activities for the current user and any associated
 * managed profiles. This is mainly for use by launchers. Apps can be queried for each user profile.
 * Since the PackageManager will not deliver package broadcasts for other profiles, you can register
 * for package changes here.
 */
public class LauncherApps {

    static final String TAG = "LauncherApps";
    static final boolean DEBUG = false;

    private Context mContext;
    private ILauncherApps mService;

    private List<OnAppsChangedListener> mListeners
            = new ArrayList<OnAppsChangedListener>();

    /**
     * Callbacks for changes to this and related managed profiles.
     */
    public interface OnAppsChangedListener {
        /**
         * Indicates that a package was removed from the specified profile.
         *
         * @param user The UserHandle of the profile that generated the change.
         * @param packageName The name of the package that was removed.
         */
        void onPackageRemoved(UserHandle user, String packageName);

        /**
         * Indicates that a package was added to the specified profile.
         *
         * @param user The UserHandle of the profile that generated the change.
         * @param packageName The name of the package that was added.
         */
        void onPackageAdded(UserHandle user, String packageName);

        /**
         * Indicates that a package was modified in the specified profile.
         *
         * @param user The UserHandle of the profile that generated the change.
         * @param packageName The name of the package that has changed.
         */
        void onPackageChanged(UserHandle user, String packageName);

        /**
         * Indicates that one or more packages have become available. For
         * example, this can happen when a removable storage card has
         * reappeared.
         *
         * @param user The UserHandle of the profile that generated the change.
         * @param packageNames The names of the packages that have become
         *            available.
         * @param replacing Indicates whether these packages are replacing
         *            existing ones.
         */
        void onPackagesAvailable(UserHandle user, String[] packageNames, boolean replacing);

        /**
         * Indicates that one or more packages have become unavailable. For
         * example, this can happen when a removable storage card has been
         * removed.
         *
         * @param user The UserHandle of the profile that generated the change.
         * @param packageNames The names of the packages that have become
         *            unavailable.
         * @param replacing Indicates whether the packages are about to be
         *            replaced with new versions.
         */
        void onPackagesUnavailable(UserHandle user, String[] packageNames, boolean replacing);
    }

    /** @hide */
    public LauncherApps(Context context, ILauncherApps service) {
        mContext = context;
        mService = service;
    }

    /**
     * Retrieves a list of launchable activities that match {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}, for a specified user.
     *
     * @param packageName The specific package to query. If null, it checks all installed packages
     *            in the profile.
     * @param user The UserHandle of the profile.
     * @return List of launchable activities. Can be an empty list but will not be null.
     */
    public List<LauncherActivityInfo> getActivityList(String packageName, UserHandle user) {
        List<ResolveInfo> activities = null;
        try {
            activities = mService.getLauncherActivities(packageName, user);
        } catch (RemoteException re) {
        }
        if (activities == null) {
            return Collections.EMPTY_LIST;
        }
        ArrayList<LauncherActivityInfo> lais = new ArrayList<LauncherActivityInfo>();
        final int count = activities.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo ri = activities.get(i);
            LauncherActivityInfo lai = new LauncherActivityInfo(mContext, ri, user);
            if (DEBUG) {
                Log.v(TAG, "Returning activity for profile " + user + " : "
                        + lai.getComponentName());
            }
            lais.add(lai);
        }
        return lais;
    }

    static ComponentName getComponentName(ResolveInfo ri) {
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    /**
     * Returns the activity info for a given intent and user handle, if it resolves. Otherwise it
     * returns null.
     *
     * @param intent The intent to find a match for.
     * @param user The profile to look in for a match.
     * @return An activity info object if there is a match.
     */
    public LauncherActivityInfo resolveActivity(Intent intent, UserHandle user) {
        try {
            ResolveInfo ri = mService.resolveActivity(intent, user);
            if (ri != null) {
                LauncherActivityInfo info = new LauncherActivityInfo(mContext, ri, user);
                return info;
            }
        } catch (RemoteException re) {
            return null;
        }
        return null;
    }

    /**
     * Starts an activity in the specified profile.
     *
     * @param component The ComponentName of the activity to launch
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     * @param user The UserHandle of the profile
     */
    public void startActivityForProfile(ComponentName component, Rect sourceBounds,
            Bundle opts, UserHandle user) {
        if (DEBUG) {
            Log.i(TAG, "StartActivityForProfile " + component + " " + user.getIdentifier());
        }
        try {
            mService.startActivityAsUser(component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            // Oops!
        }
    }

    /**
     * Checks if the package is installed and enabled for a profile.
     *
     * @param packageName The package to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the package exists and is enabled.
     */
    public boolean isPackageEnabledForProfile(String packageName, UserHandle user) {
        try {
            return mService.isPackageEnabled(packageName, user);
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Checks if the activity exists and it enabled for a profile.
     *
     * @param component The activity to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the activity exists and is enabled.
     */
    public boolean isActivityEnabledForProfile(ComponentName component, UserHandle user) {
        try {
            return mService.isActivityEnabled(component, user);
        } catch (RemoteException re) {
            return false;
        }
    }


    /**
     * Adds a listener for changes to packages in current and managed profiles.
     *
     * @param listener The listener to add.
     */
    public synchronized void addOnAppsChangedListener(OnAppsChangedListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
            if (mListeners.size() == 1) {
                try {
                    mService.addOnAppsChangedListener(mAppsChangedListener);
                } catch (RemoteException re) {
                }
            }
        }
    }

    /**
     * Removes a listener that was previously added.
     *
     * @param listener The listener to remove.
     * @see #addOnAppsChangedListener(OnAppsChangedListener)
     */
    public synchronized void removeOnAppsChangedListener(OnAppsChangedListener listener) {
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            try {
                mService.removeOnAppsChangedListener(mAppsChangedListener);
            } catch (RemoteException re) {
            }
        }
    }

    private IOnAppsChangedListener.Stub mAppsChangedListener = new IOnAppsChangedListener.Stub() {

        @Override
        public void onPackageRemoved(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageRemoved " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (OnAppsChangedListener listener : mListeners) {
                    listener.onPackageRemoved(user, packageName);
                }
            }
        }

        @Override
        public void onPackageChanged(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageChanged " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (OnAppsChangedListener listener : mListeners) {
                    listener.onPackageChanged(user, packageName);
                }
            }
        }

        @Override
        public void onPackageAdded(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageAdded " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (OnAppsChangedListener listener : mListeners) {
                    listener.onPackageAdded(user, packageName);
                }
            }
        }

        @Override
        public void onPackagesAvailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesAvailable " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (OnAppsChangedListener listener : mListeners) {
                    listener.onPackagesAvailable(user, packageNames, replacing);
                }
            }
        }

        @Override
        public void onPackagesUnavailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesUnavailable " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (OnAppsChangedListener listener : mListeners) {
                    listener.onPackagesUnavailable(user, packageNames, replacing);
                }
            }
        }
    };
}
