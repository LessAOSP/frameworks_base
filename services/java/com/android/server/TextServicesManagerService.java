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

package com.android.server;

import com.android.internal.content.PackageMonitor;
import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.service.textservice.SpellCheckerService;
import android.util.Log;
import android.util.Slog;
import android.view.textservice.SpellCheckerInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class TextServicesManagerService extends ITextServicesManager.Stub {
    private static final String TAG = TextServicesManagerService.class.getSimpleName();
    private static final boolean DBG = false;

    private final Context mContext;
    private boolean mSystemReady;
    private final TextServicesMonitor mMonitor;
    private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap =
            new HashMap<String, SpellCheckerInfo>();
    private final ArrayList<SpellCheckerInfo> mSpellCheckerList = new ArrayList<SpellCheckerInfo>();
    private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups =
            new HashMap<String, SpellCheckerBindGroup>();

    public void systemReady() {
        if (!mSystemReady) {
            mSystemReady = true;
        }
    }

    public TextServicesManagerService(Context context) {
        mSystemReady = false;
        mContext = context;
        mMonitor = new TextServicesMonitor();
        mMonitor.register(context, true);
        synchronized (mSpellCheckerMap) {
            buildSpellCheckerMapLocked(context, mSpellCheckerList, mSpellCheckerMap);
        }
    }

    private class TextServicesMonitor extends PackageMonitor {
        @Override
        public void onSomePackagesChanged() {
            synchronized (mSpellCheckerMap) {
                buildSpellCheckerMapLocked(mContext, mSpellCheckerList, mSpellCheckerMap);
                // TODO: Update for each locale
                SpellCheckerInfo sci = getCurrentSpellChecker(null);
                if (sci == null) return;
                final String packageName = sci.getPackageName();
                final int change = isPackageDisappearing(packageName);
                if (change == PACKAGE_PERMANENT_CHANGE || change == PACKAGE_TEMPORARY_CHANGE) {
                    // Package disappearing
                    setCurrentSpellChecker(findAvailSpellCheckerLocked(null, packageName));
                } else if (isPackageModified(packageName)) {
                    // Package modified
                    setCurrentSpellChecker(findAvailSpellCheckerLocked(null, packageName));
                }
            }
        }
    }

    private static void buildSpellCheckerMapLocked(Context context,
            ArrayList<SpellCheckerInfo> list, HashMap<String, SpellCheckerInfo> map) {
        list.clear();
        map.clear();
        final PackageManager pm = context.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(SpellCheckerService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
        final int N = services.size();
        for (int i = 0; i < N; ++i) {
            final ResolveInfo ri = services.get(i);
            final ServiceInfo si = ri.serviceInfo;
            final ComponentName compName = new ComponentName(si.packageName, si.name);
            if (!android.Manifest.permission.BIND_TEXT_SERVICE.equals(si.permission)) {
                Slog.w(TAG, "Skipping text service " + compName
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_TEXT_SERVICE);
                continue;
            }
            if (DBG) Slog.d(TAG, "Add: " + compName);
            final SpellCheckerInfo sci = new SpellCheckerInfo(context, ri);
            list.add(sci);
            map.put(sci.getId(), sci);
        }
        if (DBG) {
            Slog.d(TAG, "buildSpellCheckerMapLocked: " + list.size() + "," + map.size());
        }
    }

    // TODO: find an appropriate spell checker for specified locale
    private SpellCheckerInfo findAvailSpellCheckerLocked(String locale, String prefPackage) {
        final int spellCheckersCount = mSpellCheckerList.size();
        if (spellCheckersCount == 0) {
            Slog.w(TAG, "no available spell checker services found");
            return null;
        }
        if (prefPackage != null) {
            for (int i = 0; i < spellCheckersCount; ++i) {
                final SpellCheckerInfo sci = mSpellCheckerList.get(i);
                if (prefPackage.equals(sci.getPackageName())) {
                    if (DBG) {
                        Slog.d(TAG, "findAvailSpellCheckerLocked: " + sci.getPackageName());
                    }
                    return sci;
                }
            }
        }
        if (spellCheckersCount > 1) {
            Slog.w(TAG, "more than one spell checker service found, picking first");
        }
        return mSpellCheckerList.get(0);
    }

    // TODO: Save SpellCheckerService by supported languages. Currently only one spell
    // checker is saved.
    @Override
    public SpellCheckerInfo getCurrentSpellChecker(String locale) {
        synchronized (mSpellCheckerMap) {
            String curSpellCheckerId =
                    Settings.Secure.getString(mContext.getContentResolver(),
                            Settings.Secure.SPELL_CHECKER_SERVICE);
            if (DBG) {
                Slog.w(TAG, "getCurrentSpellChecker: " + curSpellCheckerId);
            }
            if (TextUtils.isEmpty(curSpellCheckerId)) {
                final SpellCheckerInfo sci = findAvailSpellCheckerLocked(null, null);
                if (sci == null) return null;
                // Set the current spell checker if there is one or more spell checkers
                // available. In this case, "sci" is the first one in the available spell
                // checkers.
                setCurrentSpellChecker(sci);
                return sci;
            }
            return mSpellCheckerMap.get(curSpellCheckerId);
        }
    }

    @Override
    public void getSpellCheckerService(SpellCheckerInfo info, String locale,
            ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener) {
        if (!mSystemReady) {
            return;
        }
        if (info == null || tsListener == null || scListener == null) {
            Slog.e(TAG, "getSpellCheckerService: Invalid input.");
            return;
        }
        final String sciId = info.getId();
        synchronized(mSpellCheckerMap) {
            if (!mSpellCheckerMap.containsKey(sciId)) {
                return;
            }
            if (mSpellCheckerBindGroups.containsKey(sciId)) {
                final SpellCheckerBindGroup bindGroup = mSpellCheckerBindGroups.get(sciId);
                if (bindGroup != null) {
                    final InternalDeathRecipient recipient =
                            mSpellCheckerBindGroups.get(sciId).addListener(
                                    tsListener, locale, scListener);
                    if (recipient == null) {
                        if (DBG) {
                            Slog.w(TAG, "Didn't create a death recipient.");
                        }
                        return;
                    }
                    if (bindGroup.mSpellChecker == null & bindGroup.mConnected) {
                        Slog.e(TAG, "The state of the spell checker bind group is illegal.");
                        bindGroup.removeAll();
                    } else if (bindGroup.mSpellChecker != null) {
                        if (DBG) {
                            Slog.w(TAG, "Existing bind found. Return a spell checker session now.");
                        }
                        try {
                            final ISpellCheckerSession session =
                                    bindGroup.mSpellChecker.getISpellCheckerSession(
                                            recipient.mScLocale, recipient.mScListener);
                            tsListener.onServiceConnected(session);
                            return;
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Exception in getting spell checker session: " + e);
                            bindGroup.removeAll();
                        }
                    }
                }
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                startSpellCheckerServiceInnerLocked(info, locale, tsListener, scListener);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return;
    }

    private void startSpellCheckerServiceInnerLocked(SpellCheckerInfo info, String locale,
            ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener) {
        final String sciId = info.getId();
        final InternalServiceConnection connection = new InternalServiceConnection(
                sciId, locale, scListener);
        final Intent serviceIntent = new Intent(SpellCheckerService.SERVICE_INTERFACE);
        serviceIntent.setComponent(info.getComponent());
        if (DBG) {
            Slog.w(TAG, "bind service: " + info.getId());
        }
        if (!mContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            Slog.e(TAG, "Failed to get a spell checker service.");
            return;
        }
        final SpellCheckerBindGroup group = new SpellCheckerBindGroup(
                connection, tsListener, locale, scListener);
        mSpellCheckerBindGroups.put(sciId, group);
    }

    @Override
    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        if (DBG) {
            Slog.d(TAG, "getEnabledSpellCheckers: " + mSpellCheckerList.size());
            for (int i = 0; i < mSpellCheckerList.size(); ++i) {
                Slog.d(TAG, "EnabledSpellCheckers: " + mSpellCheckerList.get(i).getPackageName());
            }
        }
        return mSpellCheckerList.toArray(new SpellCheckerInfo[mSpellCheckerList.size()]);
    }

    @Override
    public void finishSpellCheckerService(ISpellCheckerSessionListener listener) {
        if (DBG) {
            Slog.d(TAG, "FinishSpellCheckerService");
        }
        synchronized(mSpellCheckerMap) {
            for (SpellCheckerBindGroup group : mSpellCheckerBindGroups.values()) {
                if (group == null) continue;
                group.removeListener(listener);
            }
        }
    }

    private void setCurrentSpellChecker(SpellCheckerInfo sci) {
        if (DBG) {
            Slog.w(TAG, "setCurrentSpellChecker: " + sci.getId());
        }
        if (sci == null || mSpellCheckerMap.containsKey(sci.getId())) return;
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.SPELL_CHECKER_SERVICE, sci == null ? "" : sci.getId());
    }

    // SpellCheckerBindGroup contains active text service session listeners.
    // If there are no listeners anymore, the SpellCheckerBindGroup instance will be removed from
    // mSpellCheckerBindGroups
    private class SpellCheckerBindGroup {
        private final InternalServiceConnection mInternalConnection;
        private final ArrayList<InternalDeathRecipient> mListeners =
                new ArrayList<InternalDeathRecipient>();
        public ISpellCheckerService mSpellChecker;
        public boolean mConnected;

        public SpellCheckerBindGroup(InternalServiceConnection connection,
                ITextServicesSessionListener listener, String locale,
                ISpellCheckerSessionListener scListener) {
            mInternalConnection = connection;
            mConnected = false;
            addListener(listener, locale, scListener);
        }

        public void onServiceConnected(ISpellCheckerService spellChecker) {
            if (DBG) {
                Slog.d(TAG, "onServiceConnected");
            }
            synchronized(mSpellCheckerMap) {
                for (InternalDeathRecipient listener : mListeners) {
                    try {
                        final ISpellCheckerSession session = spellChecker.getISpellCheckerSession(
                                listener.mScLocale, listener.mScListener);
                        listener.mTsListener.onServiceConnected(session);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Exception in getting spell checker session: " + e);
                        removeAll();
                        return;
                    }
                }
                mSpellChecker = spellChecker;
                mConnected = true;
            }
        }

        public InternalDeathRecipient addListener(ITextServicesSessionListener tsListener,
                String locale, ISpellCheckerSessionListener scListener) {
            if (DBG) {
                Slog.d(TAG, "addListener: " + locale);
            }
            InternalDeathRecipient recipient = null;
            synchronized(mSpellCheckerMap) {
                try {
                    final int size = mListeners.size();
                    for (int i = 0; i < size; ++i) {
                        if (mListeners.get(i).hasSpellCheckerListener(scListener)) {
                            // do not add the lister if the group already contains this.
                            return null;
                        }
                    }
                    recipient = new InternalDeathRecipient(
                            this, tsListener, locale, scListener);
                    scListener.asBinder().linkToDeath(recipient, 0);
                    mListeners.add(new InternalDeathRecipient(
                            this, tsListener, locale, scListener));
                } catch(RemoteException e) {
                    // do nothing
                }
                cleanLocked();
            }
            return recipient;
        }

        public void removeListener(ISpellCheckerSessionListener listener) {
            if (DBG) {
                Slog.d(TAG, "remove listener");
            }
            synchronized(mSpellCheckerMap) {
                final int size = mListeners.size();
                final ArrayList<InternalDeathRecipient> removeList =
                        new ArrayList<InternalDeathRecipient>();
                for (int i = 0; i < size; ++i) {
                    final InternalDeathRecipient tempRecipient = mListeners.get(i);
                    if(tempRecipient.hasSpellCheckerListener(listener)) {
                        removeList.add(tempRecipient);
                    }
                }
                final int removeSize = removeList.size();
                for (int i = 0; i < removeSize; ++i) {
                    mListeners.remove(removeList.get(i));
                }
                cleanLocked();
            }
        }

        private void cleanLocked() {
            if (DBG) {
                Slog.d(TAG, "cleanLocked");
            }
            if (mListeners.isEmpty()) {
                if (mSpellCheckerBindGroups.containsKey(this)) {
                    mSpellCheckerBindGroups.remove(this);
                }
                // Unbind service when there is no active clients.
                mContext.unbindService(mInternalConnection);
            }
        }

        public void removeAll() {
            Slog.e(TAG, "Remove the spell checker bind unexpectedly.");
            mListeners.clear();
            cleanLocked();
        }
    }

    private class InternalServiceConnection implements ServiceConnection {
        private final ISpellCheckerSessionListener mListener;
        private final String mSciId;
        private final String mLocale;
        public InternalServiceConnection(
                String id, String locale, ISpellCheckerSessionListener listener) {
            mSciId = id;
            mLocale = locale;
            mListener = listener;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized(mSpellCheckerMap) {
                if (DBG) {
                    Slog.w(TAG, "onServiceConnected: " + name);
                }
                ISpellCheckerService spellChecker = ISpellCheckerService.Stub.asInterface(service);
                final SpellCheckerBindGroup group = mSpellCheckerBindGroups.get(mSciId);
                if (group != null) {
                    group.onServiceConnected(spellChecker);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSpellCheckerBindGroups.remove(mSciId);
        }
    }

    private class InternalDeathRecipient implements IBinder.DeathRecipient {
        public final ITextServicesSessionListener mTsListener;
        public final ISpellCheckerSessionListener mScListener;
        public final String mScLocale;
        private final SpellCheckerBindGroup mGroup;
        public InternalDeathRecipient(SpellCheckerBindGroup group,
                ITextServicesSessionListener tsListener, String scLocale,
                ISpellCheckerSessionListener scListener) {
            mTsListener = tsListener;
            mScListener = scListener;
            mScLocale = scLocale;
            mGroup = group;
        }

        public boolean hasSpellCheckerListener(ISpellCheckerSessionListener listener) {
            return mScListener.equals(listener);
        }

        @Override
        public void binderDied() {
            mGroup.removeListener(mScListener);
        }
    }
}
