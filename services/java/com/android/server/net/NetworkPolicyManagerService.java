/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_PAID_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_PAID;
import static android.net.NetworkPolicyManager.dumpPolicy;
import static android.net.NetworkPolicyManager.dumpRules;

import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.os.IPowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Service that maintains low-level network policy rules and collects usage
 * statistics to drive those rules.
 * <p>
 * Derives active rules by combining a given policy with other system status,
 * and delivers to listeners, such as {@link ConnectivityManager}, for
 * enforcement.
 */
public class NetworkPolicyManagerService extends INetworkPolicyManager.Stub {
    private static final String TAG = "NetworkPolicy";
    private static final boolean LOGD = true;

    private Context mContext;
    private IActivityManager mActivityManager;
    private IPowerManager mPowerManager;

    private Object mRulesLock = new Object();

    private boolean mScreenOn;

    /** Current network policy for each UID. */
    private SparseIntArray mUidPolicy = new SparseIntArray();
    /** Current derived network rules for each UID. */
    private SparseIntArray mUidRules = new SparseIntArray();

    /** Foreground at both UID and PID granularity. */
    private SparseBooleanArray mUidForeground = new SparseBooleanArray();
    private SparseArray<SparseBooleanArray> mUidPidForeground = new SparseArray<
            SparseBooleanArray>();

    private final RemoteCallbackList<INetworkPolicyListener> mListeners = new RemoteCallbackList<
            INetworkPolicyListener>();

    // TODO: periodically poll network stats and write to disk
    // TODO: save/restore policy information from disk

    // TODO: keep whitelist of system-critical services that should never have
    // rules enforced, such as system, phone, and radio UIDs.

    public NetworkPolicyManagerService(
            Context context, IActivityManager activityManager, IPowerManager powerManager) {
        mContext = checkNotNull(context, "missing context");
        mActivityManager = checkNotNull(activityManager, "missing activityManager");
        mPowerManager = checkNotNull(powerManager, "missing powerManager");
    }

    public void systemReady() {
        // TODO: read current policy+stats from disk and generate NMS rules

        updateScreenOn();

        try {
            mActivityManager.registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            // ouch, no foregroundActivities updates means some processes may
            // never get network access.
            Slog.e(TAG, "unable to register IProcessObserver", e);
        }

        // TODO: traverse existing processes to know foreground state, or have
        // activitymanager dispatch current state when new observer attached.

        final IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, screenFilter);

        final IntentFilter shutdownFilter = new IntentFilter();
        shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(mShutdownReceiver, shutdownFilter);

    }

    private IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
            // only someone like AMS should only be calling us
            mContext.enforceCallingOrSelfPermission(
                    MANAGE_APP_TOKENS, "requires MANAGE_APP_TOKENS permission");

            synchronized (mRulesLock) {
                // because a uid can have multiple pids running inside, we need to
                // remember all pid states and summarize foreground at uid level.

                // record foreground for this specific pid
                SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
                if (pidForeground == null) {
                    pidForeground = new SparseBooleanArray(2);
                    mUidPidForeground.put(uid, pidForeground);
                }
                pidForeground.put(pid, foregroundActivities);
                computeUidForegroundL(uid);
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            // only someone like AMS should only be calling us
            mContext.enforceCallingOrSelfPermission(
                    MANAGE_APP_TOKENS, "requires MANAGE_APP_TOKENS permission");

            synchronized (mRulesLock) {
                // clear records and recompute, when they exist
                final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
                if (pidForeground != null) {
                    pidForeground.delete(pid);
                    computeUidForegroundL(uid);
                }
            }
        }
    };

    private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mRulesLock) {
                // screen-related broadcasts are protected by system, no need
                // for permissions check.
                updateScreenOn();
            }
        }
    };

    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO: persist any pending stats during clean shutdown
            Log.d(TAG, "persisting stats");
        }
    };

    @Override
    public void setUidPolicy(int uid, int policy) {
        // TODO: create permission for modifying data policy
        mContext.enforceCallingOrSelfPermission(
                UPDATE_DEVICE_STATS, "requires UPDATE_DEVICE_STATS permission");

        final int oldPolicy;
        synchronized (mRulesLock) {
            oldPolicy = getUidPolicy(uid);
            mUidPolicy.put(uid, policy);
            updateRulesForUidL(uid);
        }

        // TODO: consider dispatching BACKGROUND_DATA_SETTING broadcast
    }

    @Override
    public int getUidPolicy(int uid) {
        synchronized (mRulesLock) {
            return mUidPolicy.get(uid, POLICY_NONE);
        }
    }

    @Override
    public void registerListener(INetworkPolicyListener listener) {
        mListeners.register(listener);

        synchronized (mRulesLock) {
            // dispatch any existing rules to new listeners
            final int size = mUidRules.size();
            for (int i = 0; i < size; i++) {
                final int uid = mUidRules.keyAt(i);
                final int uidRules = mUidRules.valueAt(i);
                if (uidRules != RULE_ALLOW_ALL) {
                    try {
                        listener.onRulesChanged(uid, uidRules);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    @Override
    public void unregisterListener(INetworkPolicyListener listener) {
        mListeners.unregister(listener);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, "requires DUMP permission");

        synchronized (mRulesLock) {
            fout.println("Policy status for known UIDs:");

            final SparseBooleanArray knownUids = new SparseBooleanArray();
            collectKeys(mUidPolicy, knownUids);
            collectKeys(mUidForeground, knownUids);
            collectKeys(mUidRules, knownUids);

            final int size = knownUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = knownUids.keyAt(i);
                fout.print("  UID=");
                fout.print(uid);

                fout.print(" policy=");
                final int policyIndex = mUidPolicy.indexOfKey(uid);
                if (policyIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpPolicy(fout, mUidPolicy.valueAt(policyIndex));
                }

                fout.print(" foreground=");
                final int foregroundIndex = mUidPidForeground.indexOfKey(uid);
                if (foregroundIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpSparseBooleanArray(fout, mUidPidForeground.valueAt(foregroundIndex));
                }

                fout.print(" rules=");
                final int rulesIndex = mUidRules.indexOfKey(uid);
                if (rulesIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpRules(fout, mUidRules.valueAt(rulesIndex));
                }

                fout.println();
            }
        }
    }

    @Override
    public boolean isUidForeground(int uid) {
        synchronized (mRulesLock) {
            // only really in foreground when screen is also on
            return mUidForeground.get(uid, false) && mScreenOn;
        }
    }

    /**
     * Foreground for PID changed; recompute foreground at UID level. If
     * changed, will trigger {@link #updateRulesForUidL(int)}.
     */
    private void computeUidForegroundL(int uid) {
        final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);

        // current pid is dropping foreground; examine other pids
        boolean uidForeground = false;
        final int size = pidForeground.size();
        for (int i = 0; i < size; i++) {
            if (pidForeground.valueAt(i)) {
                uidForeground = true;
                break;
            }
        }

        final boolean oldUidForeground = mUidForeground.get(uid, false);
        if (oldUidForeground != uidForeground) {
            // foreground changed, push updated rules
            mUidForeground.put(uid, uidForeground);
            updateRulesForUidL(uid);
        }
    }

    private void updateScreenOn() {
        synchronized (mRulesLock) {
            try {
                mScreenOn = mPowerManager.isScreenOn();
            } catch (RemoteException e) {
            }
            updateRulesForScreenL();
        }
    }

    /**
     * Update rules that might be changed by {@link #mScreenOn} value.
     */
    private void updateRulesForScreenL() {
        // only update rules for anyone with foreground activities
        final int size = mUidForeground.size();
        for (int i = 0; i < size; i++) {
            if (mUidForeground.valueAt(i)) {
                final int uid = mUidForeground.keyAt(i);
                updateRulesForUidL(uid);
            }
        }
    }

    private void updateRulesForUidL(int uid) {
        final int uidPolicy = getUidPolicy(uid);
        final boolean uidForeground = isUidForeground(uid);

        // derive active rules based on policy and active state
        int uidRules = RULE_ALLOW_ALL;
        if (!uidForeground && (uidPolicy & POLICY_REJECT_PAID_BACKGROUND) != 0) {
            // uid in background, and policy says to block paid data
            uidRules = RULE_REJECT_PAID;
        }

        // TODO: only dispatch when rules actually change

        // record rule locally to dispatch to new listeners
        mUidRules.put(uid, uidRules);

        // dispatch changed rule to existing listeners
        final int length = mListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
            if (listener != null) {
                try {
                    listener.onRulesChanged(uid, uidRules);
                } catch (RemoteException e) {
                }
            }
        }
        mListeners.finishBroadcast();
    }

    private static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }
    
    private static void collectKeys(SparseIntArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void collectKeys(SparseBooleanArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void dumpSparseBooleanArray(PrintWriter fout, SparseBooleanArray value) {
        fout.print("[");
        final int size = value.size();
        for (int i = 0; i < size; i++) {
            fout.print(value.keyAt(i) + "=" + value.valueAt(i));
            if (i < size - 1) fout.print(",");
        }
        fout.print("]");
    }
}
