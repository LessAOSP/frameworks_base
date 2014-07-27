/**
 * Copyright (c) 2014, The Android Open Source Project
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

package com.android.server.notification;

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_UNKNOWN;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.ZenModeConfig;
import android.telecomm.TelecommManager;
import android.util.Slog;

import com.android.internal.R;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;

/**
 * NotificationManagerService helper for functionality related to zen mode.
 */
public class ZenModeHelper {
    private static final String TAG = "ZenModeHelper";

    private static final String ACTION_ENTER_ZEN = "enter_zen";
    private static final int REQUEST_CODE_ENTER = 100;
    private static final String ACTION_EXIT_ZEN = "exit_zen";
    private static final int REQUEST_CODE_EXIT = 101;
    private static final String EXTRA_TIME = "time";

    private final Context mContext;
    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final AppOpsManager mAppOps;
    private final ZenModeConfig mDefaultConfig;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private ComponentName mDefaultPhoneApp;
    private int mZenMode;
    private ZenModeConfig mConfig;
    private AudioManager mAudioManager;
    private int mPreviousRingerMode = -1;

    public ZenModeHelper(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mDefaultConfig = readDefaultConfig(context.getResources());
        mConfig = mDefaultConfig;
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ENTER_ZEN);
        filter.addAction(ACTION_EXIT_ZEN);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        mContext.registerReceiver(new ZenBroadcastReceiver(), filter);
    }

    public static ZenModeConfig readDefaultConfig(Resources resources) {
        XmlResourceParser parser = null;
        try {
            parser = resources.getXml(R.xml.default_zen_mode_config);
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                final ZenModeConfig config = ZenModeConfig.readXml(parser);
                if (config != null) return config;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error reading default zen mode config from resource", e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
        return new ZenModeConfig();
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    public boolean shouldIntercept(NotificationRecord record) {
        if (mZenMode != Global.ZEN_MODE_OFF) {
            if (isSystem(record)) {
                return false;
            }
            if (isAlarm(record)) {
                if (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                    ZenLog.traceIntercepted(record, "alarm");
                    return true;
                }
                return false;
            }
            // audience has veto power over all following rules
            if (!audienceMatches(record)) {
                ZenLog.traceIntercepted(record, "!audienceMatches");
                return true;
            }
            if (isCall(record)) {
                if (!mConfig.allowCalls) {
                    ZenLog.traceIntercepted(record, "!allowCalls");
                    return true;
                }
                return false;
            }
            if (isMessage(record)) {
                if (!mConfig.allowMessages) {
                    ZenLog.traceIntercepted(record, "!allowMessages");
                    return true;
                }
                return false;
            }
            ZenLog.traceIntercepted(record, "!allowed");
            return true;
        }
        return false;
    }

    public int getZenMode() {
        return mZenMode;
    }

    public void setZenMode(int zenModeValue) {
        Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, zenModeValue);
    }

    public void updateZenMode() {
        final int mode = Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE, Global.ZEN_MODE_OFF);
        if (mode != mZenMode) {
            Slog.d(TAG, String.format("updateZenMode: %s -> %s",
                    Global.zenModeToString(mZenMode),
                    Global.zenModeToString(mode)));
            ZenLog.traceUpdateZenMode(mZenMode, mode);
        }
        mZenMode = mode;
        final boolean zen = mZenMode != Global.ZEN_MODE_OFF;
        final String[] exceptionPackages = null; // none (for now)

        // call restrictions
        final boolean muteCalls = zen && !mConfig.allowCalls;
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, USAGE_NOTIFICATION_RINGTONE,
                muteCalls ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        mAppOps.setRestriction(AppOpsManager.OP_PLAY_AUDIO, USAGE_NOTIFICATION_RINGTONE,
                muteCalls ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);

        // restrict vibrations with no hints
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, USAGE_UNKNOWN,
                zen ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);

        // alarm restrictions
        final boolean muteAlarms = mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, USAGE_ALARM,
                muteAlarms ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        mAppOps.setRestriction(AppOpsManager.OP_PLAY_AUDIO, USAGE_ALARM,
                muteAlarms ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);

        // force ringer mode into compliance
        if (mAudioManager != null) {
            int ringerMode = mAudioManager.getRingerMode();
            int forcedRingerMode = -1;
            if (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                    mPreviousRingerMode = ringerMode;
                    Slog.d(TAG, "Silencing ringer");
                    forcedRingerMode = AudioManager.RINGER_MODE_SILENT;
                }
            } else {
                if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    Slog.d(TAG, "Unsilencing ringer");
                    forcedRingerMode = mPreviousRingerMode != -1 ? mPreviousRingerMode
                            : AudioManager.RINGER_MODE_NORMAL;
                    mPreviousRingerMode = -1;
                }
            }
            if (forcedRingerMode != -1) {
                mAudioManager.setRingerMode(forcedRingerMode);
                ZenLog.traceSetRingerMode(forcedRingerMode);
            }
        }
        dispatchOnZenModeChanged();
    }

    public boolean allowDisable(int what, IBinder token, String pkg) {
        // TODO(cwren): delete this API before the next release. Bug:15344099
        boolean allowDisable = true;
        String reason = null;
        if (isDefaultPhoneApp(pkg)) {
            allowDisable = mZenMode == Global.ZEN_MODE_OFF || mConfig.allowCalls;
            reason = mZenMode == Global.ZEN_MODE_OFF ? "zenOff" : "allowCalls";
        }
        ZenLog.traceAllowDisable(pkg, allowDisable, reason);
        return allowDisable;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mZenMode=");
        pw.println(Global.zenModeToString(mZenMode));
        pw.print(prefix); pw.print("mConfig="); pw.println(mConfig);
        pw.print(prefix); pw.print("mDefaultConfig="); pw.println(mDefaultConfig);
        pw.print(prefix); pw.print("mPreviousRingerMode="); pw.println(mPreviousRingerMode);
        pw.print(prefix); pw.print("mDefaultPhoneApp="); pw.println(mDefaultPhoneApp);
    }

    public void readXml(XmlPullParser parser) throws XmlPullParserException, IOException {
        final ZenModeConfig config = ZenModeConfig.readXml(parser);
        if (config != null) {
            setConfig(config);
        }
    }

    public void writeXml(XmlSerializer out) throws IOException {
        mConfig.writeXml(out);
    }

    public ZenModeConfig getConfig() {
        return mConfig;
    }

    public boolean setConfig(ZenModeConfig config) {
        if (config == null || !config.isValid()) return false;
        if (config.equals(mConfig)) return true;
        ZenLog.traceConfig(mConfig, config);
        mConfig = config;
        dispatchOnConfigChanged();
        final String val = Integer.toString(mConfig.hashCode());
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_CONFIG_ETAG, val);
        updateAlarms();
        updateZenMode();
        return true;
    }

    private void handleRingerModeChanged() {
        if (mAudioManager != null) {
            // follow ringer mode if necessary
            final int ringerMode = mAudioManager.getRingerMode();
            int newZen = -1;
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                if (mZenMode != Global.ZEN_MODE_NO_INTERRUPTIONS) {
                    newZen = Global.ZEN_MODE_NO_INTERRUPTIONS;
                }
            } else if ((ringerMode == AudioManager.RINGER_MODE_NORMAL
                    || ringerMode == AudioManager.RINGER_MODE_VIBRATE)
                    && mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                newZen = Global.ZEN_MODE_OFF;
            }
            if (newZen != -1) {
                ZenLog.traceFollowRingerMode(ringerMode, mZenMode, newZen);
                setZenMode(newZen);
            }
        }
    }

    private void dispatchOnConfigChanged() {
        for (Callback callback : mCallbacks) {
            callback.onConfigChanged();
        }
    }

    private void dispatchOnZenModeChanged() {
        for (Callback callback : mCallbacks) {
            callback.onZenModeChanged();
        }
    }

    private boolean isSystem(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_SYSTEM);
    }

    private boolean isAlarm(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_ALARM)
                || record.isCategory(Notification.CATEGORY_EVENT)
                || record.isAudioStream(AudioManager.STREAM_ALARM)
                || record.isAudioAttributesUsage(AudioAttributes.USAGE_ALARM);
    }

    private boolean isCall(NotificationRecord record) {
        return isDefaultPhoneApp(record.sbn.getPackageName())
                || record.isCategory(Notification.CATEGORY_CALL);
    }

    private boolean isDefaultPhoneApp(String pkg) {
        if (mDefaultPhoneApp == null) {
            final TelecommManager telecomm =
                    (TelecommManager) mContext.getSystemService(Context.TELECOMM_SERVICE);
            mDefaultPhoneApp = telecomm != null ? telecomm.getDefaultPhoneApp() : null;
            Slog.d(TAG, "Default phone app: " + mDefaultPhoneApp);
        }
        return pkg != null && mDefaultPhoneApp != null
                && pkg.equals(mDefaultPhoneApp.getPackageName());
    }

    private boolean isDefaultMessagingApp(NotificationRecord record) {
        final int userId = record.getUserId();
        if (userId == UserHandle.USER_NULL || userId == UserHandle.USER_ALL) return false;
        final String defaultApp = Secure.getStringForUser(mContext.getContentResolver(),
                Secure.SMS_DEFAULT_APPLICATION, userId);
        return Objects.equals(defaultApp, record.sbn.getPackageName());
    }

    private boolean isMessage(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_MESSAGE) || isDefaultMessagingApp(record);
    }

    private boolean audienceMatches(NotificationRecord record) {
        switch (mConfig.allowFrom) {
            case ZenModeConfig.SOURCE_ANYONE:
                return true;
            case ZenModeConfig.SOURCE_CONTACT:
                return record.getContactAffinity() >= ValidateNotificationPeople.VALID_CONTACT;
            case ZenModeConfig.SOURCE_STAR:
                return record.getContactAffinity() >= ValidateNotificationPeople.STARRED_CONTACT;
            default:
                Slog.w(TAG, "Encountered unknown source: " + mConfig.allowFrom);
                return true;
        }
    }

    private void updateAlarms() {
        updateAlarm(ACTION_ENTER_ZEN, REQUEST_CODE_ENTER,
                mConfig.sleepStartHour, mConfig.sleepStartMinute);
        updateAlarm(ACTION_EXIT_ZEN, REQUEST_CODE_EXIT,
                mConfig.sleepEndHour, mConfig.sleepEndMinute);
    }

    private void updateAlarm(String action, int requestCode, int hr, int min) {
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final long now = System.currentTimeMillis();
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hr);
        c.set(Calendar.MINUTE, min);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) {
            c.add(Calendar.DATE, 1);
        }
        final long time = c.getTimeInMillis();
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, requestCode,
                new Intent(action).putExtra(EXTRA_TIME, time), PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        if (mConfig.sleepMode != null) {
            Slog.d(TAG, String.format("Scheduling %s for %s, %s in the future, now=%s",
                    action, ts(time), time - now, ts(now)));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        }
    }

    private static String ts(long time) {
        return new Date(time) + " (" + time + ")";
    }

    private final Runnable mRingerModeChanged = new Runnable() {
        @Override
        public void run() {
            handleRingerModeChanged();
        }
    };

    private class SettingsObserver extends ContentObserver {
        private final Uri ZEN_MODE = Global.getUriFor(Global.ZEN_MODE);

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(ZEN_MODE, false /*notifyForDescendents*/, this);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (ZEN_MODE.equals(uri)) {
                updateZenMode();
            }
        }
    }

    private class ZenBroadcastReceiver extends BroadcastReceiver {
        private final Calendar mCalendar = Calendar.getInstance();

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_ENTER_ZEN.equals(intent.getAction())) {
                setZenMode(intent, Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
            } else if (ACTION_EXIT_ZEN.equals(intent.getAction())) {
                setZenMode(intent, Global.ZEN_MODE_OFF);
            } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) {
                mHandler.post(mRingerModeChanged);
            }
        }

        private void setZenMode(Intent intent, int zenModeValue) {
            final long schTime = intent.getLongExtra(EXTRA_TIME, 0);
            final long now = System.currentTimeMillis();
            Slog.d(TAG, String.format("%s scheduled for %s, fired at %s, delta=%s",
                    intent.getAction(), ts(schTime), ts(now), now - schTime));

            final int[] days = ZenModeConfig.tryParseDays(mConfig.sleepMode);
            boolean enter = false;
            final int day = getDayOfWeek(schTime);
            if (days != null) {
                for (int i = 0; i < days.length; i++) {
                    if (days[i] == day) {
                        enter = true;
                        ZenModeHelper.this.setZenMode(zenModeValue);
                        break;
                    }
                }
            }
            ZenLog.traceDowntime(enter, day, days);
            updateAlarms();
        }

        private int getDayOfWeek(long time) {
            mCalendar.setTimeInMillis(time);
            return mCalendar.get(Calendar.DAY_OF_WEEK);
        }
    }

    public static class Callback {
        void onConfigChanged() {}
        void onZenModeChanged() {}
    }
}
