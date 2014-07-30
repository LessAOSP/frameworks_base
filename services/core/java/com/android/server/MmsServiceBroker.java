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

package com.android.server;

import com.android.internal.telephony.IMms;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.Slog;

/**
 * This class is a proxy for MmsService APIs. We need this because MmsService runs
 * in phone process and may crash anytime. This manages a connection to the actual
 * MmsService and bridges the public SMS/MMS APIs with MmsService implementation.
 */
public class MmsServiceBroker extends SystemService {
    private static final String TAG = "MmsServiceBroker";

    private static final ComponentName MMS_SERVICE_COMPONENT =
            new ComponentName("com.android.mms.service", "com.android.mms.service.MmsService");

    private static final int MSG_TRY_CONNECTING = 1;

    private Context mContext;
    // The actual MMS service instance to invoke
    private volatile IMms mService;
    private boolean mIsConnecting;

    // Cached system service instances
    private volatile AppOpsManager mAppOpsManager = null;
    private volatile PackageManager mPackageManager = null;
    private volatile TelephonyManager mTelephonyManager = null;

    private final Handler mConnectionHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TRY_CONNECTING:
                    tryConnecting();
                    break;
                default:
                    Slog.e(TAG, "Unknown message");
            }
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slog.i(TAG, "MmsService connected");
            synchronized (MmsServiceBroker.this) {
                mService = IMms.Stub.asInterface(service);
                mIsConnecting = false;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slog.i(TAG, "MmsService unexpectedly disconnected");
            synchronized (MmsServiceBroker.this) {
                mService = null;
                mIsConnecting = false;
            }
        }
    };

    public MmsServiceBroker(Context context) {
        super(context);
        mContext = context;
        mService = null;
        mIsConnecting = false;
    }

    @Override
    public void onStart() {
        publishBinderService("imms", new BinderService());
    }

    public void systemRunning() {
        tryConnecting();
    }

    private void tryConnecting() {
        Slog.i(TAG, "Connecting to MmsService");
        synchronized (this) {
            if (mIsConnecting) {
                Slog.d(TAG, "Already connecting");
                return;
            }
            final Intent intent = new Intent();
            intent.setComponent(MMS_SERVICE_COMPONENT);
            try {
                if (mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
                    mIsConnecting = true;
                } else {
                    Slog.e(TAG, "Failed to connect to MmsService");
                }
            } catch (SecurityException e) {
                Slog.e(TAG, "Forbidden to connect to MmsService", e);
            }
        }
    }

    private void ensureService() {
        if (mService == null) {
            // Service instance lost, kicking off the connection again
            mConnectionHandler.sendMessage(mConnectionHandler.obtainMessage(MSG_TRY_CONNECTING));
            throw new RuntimeException("MMS service is not connected");
        }
    }

    /**
     * Making sure when we obtain the mService instance it is always valid.
     * Throws {@link RuntimeException} when it is empty.
     */
    private IMms getServiceGuarded() {
        ensureService();
        return mService;
    }

    private AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        }
        return mAppOpsManager;
    }

    private PackageManager getPackageManager() {
        if (mPackageManager == null) {
            mPackageManager = mContext.getPackageManager();
        }
        return mPackageManager;
    }

    private TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
        }
        return mTelephonyManager;
    }

    /*
     * Throws a security exception unless the caller has carrier privilege.
     */
    private void enforceCarrierPrivilege() {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        for (String pkg : packages) {
            if (getTelephonyManager().checkCarrierPrivilegesForPackage(pkg) ==
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        }
        throw new SecurityException("No carrier privilege");
    }

    // Service API calls implementation, proxied to the real MmsService in "com.android.mms.service"
    private final class BinderService extends IMms.Stub {
        @Override
        public void sendMessage(long subId, String callingPkg, byte[] pdu, String locationUrl,
                PendingIntent sentIntent) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS, "Send MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().sendMessage(subId, callingPkg, pdu, locationUrl, sentIntent);
        }

        @Override
        public void downloadMessage(long subId, String callingPkg, String locationUrl,
                PendingIntent downloadedIntent) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.RECEIVE_MMS,
                    "Download MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_RECEIVE_MMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().downloadMessage(subId, callingPkg, locationUrl, downloadedIntent);
        }

        @Override
        public void updateMmsSendStatus(int messageRef, boolean success) throws RemoteException {
            enforceCarrierPrivilege();
            getServiceGuarded().updateMmsSendStatus(messageRef, success);
        }

        @Override
        public void updateMmsDownloadStatus(int messageRef, byte[] pdu) throws RemoteException {
            enforceCarrierPrivilege();
            getServiceGuarded().updateMmsDownloadStatus(messageRef, pdu);
        }

        @Override
        public boolean getCarrierConfigBoolean(String name, boolean defaultValue)
                throws RemoteException {
            return getServiceGuarded().getCarrierConfigBoolean(name, defaultValue);
        }

        @Override
        public int getCarrierConfigInt(String name, int defaultValue) throws RemoteException {
            return getServiceGuarded().getCarrierConfigInt(name, defaultValue);
        }

        @Override
        public String getCarrierConfigString(String name, String defaultValue)
                throws RemoteException {
            return getServiceGuarded().getCarrierConfigString(name, defaultValue);
        }

        @Override
        public void setCarrierConfigBoolean(String callingPkg, String name, boolean value)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS, "Set MMS config");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().setCarrierConfigBoolean(callingPkg, name, value);
        }

        @Override
        public void setCarrierConfigInt(String callingPkg, String name, int value)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS, "Set MMS config");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().setCarrierConfigInt(callingPkg, name, value);
        }

        @Override
        public void setCarrierConfigString(String callingPkg, String name, String value)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS, "Set MMS config");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().setCarrierConfigString(callingPkg, name, value);
        }

        @Override
        public Uri importTextMessage(String callingPkg, String address, int type, String text,
                long timestampMillis, boolean seen, boolean read) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Import SMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            return getServiceGuarded().importTextMessage(
                    callingPkg, address, type, text, timestampMillis, seen, read);
        }

        @Override
        public Uri importMultimediaMessage(String callingPkg, byte[] pdu, String messageId,
                long timestampSecs, boolean seen, boolean read) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Import MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            return getServiceGuarded().importMultimediaMessage(
                    callingPkg, pdu, messageId, timestampSecs, seen, read);
        }

        @Override
        public boolean deleteStoredMessage(String callingPkg, Uri messageUri)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS,
                    "Delete SMS/MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            return getServiceGuarded().deleteStoredMessage(callingPkg, messageUri);
        }

        @Override
        public boolean deleteStoredConversation(String callingPkg, long conversationId)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Delete conversation");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            return getServiceGuarded().deleteStoredConversation(callingPkg, conversationId);
        }

        @Override
        public boolean updateStoredMessageStatus(String callingPkg, Uri messageUri,
                ContentValues statusValues) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS,
                    "Update SMS/MMS message");
            return getServiceGuarded()
                    .updateStoredMessageStatus(callingPkg, messageUri, statusValues);
        }

        @Override
        public boolean archiveStoredConversation(String callingPkg, long conversationId,
                boolean archived) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS,
                    "Update SMS/MMS message");
            return getServiceGuarded()
                    .archiveStoredConversation(callingPkg, conversationId, archived);
        }

        @Override
        public Uri addTextMessageDraft(String callingPkg, String address, String text)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Add SMS draft");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            return getServiceGuarded().addTextMessageDraft(callingPkg, address, text);
        }

        @Override
        public Uri addMultimediaMessageDraft(String callingPkg, byte[] pdu) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Add MMS draft");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            return getServiceGuarded().addMultimediaMessageDraft(callingPkg, pdu);
        }

        @Override
        public void sendStoredMessage(long subId, String callingPkg, Uri messageUri,
                PendingIntent sentIntent) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS,
                    "Send stored MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().sendStoredMessage(subId, callingPkg, messageUri, sentIntent);
        }

        @Override
        public void setAutoPersisting(String callingPkg, boolean enabled) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Set auto persist");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().setAutoPersisting(callingPkg, enabled);
        }

        @Override
        public boolean getAutoPersisting() throws RemoteException {
            return getServiceGuarded().getAutoPersisting();
        }
    }
}
