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

package com.android.server.notification;

import com.android.server.notification.NotificationManagerService.NotificationRecord;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps track of notification activity, display, and user interaction.
 *
 * <p>This class receives signals from NoMan and keeps running stats of
 * notification usage. Some metrics are updated as events occur. Others, namely
 * those involving durations, are updated as the notification is canceled.</p>
 *
 * <p>This class is thread-safe.</p>
 *
 * {@hide}
 */
public class NotificationUsageStats {
    // Guarded by synchronized(this).
    private final Map<String, AggregatedStats> mStats = new HashMap<String, AggregatedStats>();
    private final SQLiteLog mSQLiteLog;

    public NotificationUsageStats(Context context) {
        mSQLiteLog = new SQLiteLog(context);
    }

    /**
     * Called when a notification has been posted.
     */
    public synchronized void registerPostedByApp(NotificationRecord notification) {
        notification.stats = new SingleNotificationStats();
        notification.stats.posttimeElapsedMs = SystemClock.elapsedRealtime();
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numPostedByApp++;
        }
        mSQLiteLog.logPosted(notification);
    }

    /**
     * Called when a notification has been updated.
     */
    public void registerUpdatedByApp(NotificationRecord notification, NotificationRecord old) {
        notification.stats = old.stats;
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numUpdatedByApp++;
        }
    }

    /**
     * Called when the originating app removed the notification programmatically.
     */
    public synchronized void registerRemovedByApp(NotificationRecord notification) {
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numRemovedByApp++;
            stats.collect(notification.stats);
        }
        mSQLiteLog.logRemoved(notification);
    }

    /**
     * Called when the user dismissed the notification via the UI.
     */
    public synchronized void registerDismissedByUser(NotificationRecord notification) {
        notification.stats.onDismiss();
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numDismissedByUser++;
            stats.collect(notification.stats);
        }
        mSQLiteLog.logDismissed(notification);
    }

    /**
     * Called when the user clicked the notification in the UI.
     */
    public synchronized void registerClickedByUser(NotificationRecord notification) {
        notification.stats.onClick();
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numClickedByUser++;
        }
        mSQLiteLog.logClicked(notification);
    }

    /**
     * Called when the notification is canceled because the user clicked it.
     *
     * <p>Called after {@link #registerClickedByUser(NotificationRecord)}.</p>
     */
    public synchronized void registerCancelDueToClick(NotificationRecord notification) {
        // No explicit stats for this (the click has already been registered in
        // registerClickedByUser), just make sure the single notification stats
        // are folded up into aggregated stats.
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.collect(notification.stats);
        }
    }

    /**
     * Called when the notification is canceled due to unknown reasons.
     *
     * <p>Called for notifications of apps being uninstalled, for example.</p>
     */
    public synchronized void registerCancelUnknown(NotificationRecord notification) {
        // Fold up individual stats.
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.collect(notification.stats);
        }
    }

    // Locked by this.
    private AggregatedStats[] getAggregatedStatsLocked(NotificationRecord record) {
        StatusBarNotification n = record.sbn;

        String user = String.valueOf(n.getUserId());
        String userPackage = user + ":" + n.getPackageName();

        // TODO: Use pool of arrays.
        return new AggregatedStats[] {
                getOrCreateAggregatedStatsLocked(user),
                getOrCreateAggregatedStatsLocked(userPackage),
                getOrCreateAggregatedStatsLocked(n.getKey()),
        };
    }

    // Locked by this.
    private AggregatedStats getOrCreateAggregatedStatsLocked(String key) {
        AggregatedStats result = mStats.get(key);
        if (result == null) {
            result = new AggregatedStats(key);
            mStats.put(key, result);
        }
        return result;
    }

    public synchronized void dump(PrintWriter pw, String indent) {
        for (AggregatedStats as : mStats.values()) {
            as.dump(pw, indent);
        }
        mSQLiteLog.dump(pw, indent);
    }

    /**
     * Aggregated notification stats.
     */
    private static class AggregatedStats {
        public final String key;

        // ---- Updated as the respective events occur.
        public int numPostedByApp;
        public int numUpdatedByApp;
        public int numRemovedByApp;
        public int numClickedByUser;
        public int numDismissedByUser;

        // ----  Updated when a notification is canceled.
        public final Aggregate posttimeMs = new Aggregate();
        public final Aggregate posttimeToDismissMs = new Aggregate();
        public final Aggregate posttimeToFirstClickMs = new Aggregate();

        public AggregatedStats(String key) {
            this.key = key;
        }

        public void collect(SingleNotificationStats singleNotificationStats) {
            posttimeMs.addSample(
	            SystemClock.elapsedRealtime() - singleNotificationStats.posttimeElapsedMs);
            if (singleNotificationStats.posttimeToDismissMs >= 0) {
                posttimeToDismissMs.addSample(singleNotificationStats.posttimeToDismissMs);
            }
            if (singleNotificationStats.posttimeToFirstClickMs >= 0) {
                posttimeToFirstClickMs.addSample(singleNotificationStats.posttimeToFirstClickMs);
            }
        }

        public void dump(PrintWriter pw, String indent) {
            pw.println(toStringWithIndent(indent));
        }

        @Override
        public String toString() {
            return toStringWithIndent("");
        }

        private String toStringWithIndent(String indent) {
            return indent + "AggregatedStats{\n" +
                    indent + "  key='" + key + "',\n" +
                    indent + "  numPostedByApp=" + numPostedByApp + ",\n" +
                    indent + "  numUpdatedByApp=" + numUpdatedByApp + ",\n" +
                    indent + "  numRemovedByApp=" + numRemovedByApp + ",\n" +
                    indent + "  numClickedByUser=" + numClickedByUser + ",\n" +
                    indent + "  numDismissedByUser=" + numDismissedByUser + ",\n" +
                    indent + "  posttimeMs=" + posttimeMs + ",\n" +
                    indent + "  posttimeToDismissMs=" + posttimeToDismissMs + ",\n" +
                    indent + "  posttimeToFirstClickMs=" + posttimeToFirstClickMs + ",\n" +
                    indent + "}";
        }
    }

    /**
     * Tracks usage of an individual notification that is currently active.
     */
    public static class SingleNotificationStats {
        /** SystemClock.elapsedRealtime() when the notification was posted. */
        public long posttimeElapsedMs = -1;
        /** Elapsed time since the notification was posted until it was first clicked, or -1. */
        public long posttimeToFirstClickMs = -1;
        /** Elpased time since the notification was posted until it was dismissed by the user. */
        public long posttimeToDismissMs = -1;

        /**
         * Called when the user clicked the notification.
         */
        public void onClick() {
            if (posttimeToFirstClickMs < 0) {
                posttimeToFirstClickMs = SystemClock.elapsedRealtime() - posttimeElapsedMs;
            }
        }

        /**
         * Called when the user removed the notification.
         */
        public void onDismiss() {
            if (posttimeToDismissMs < 0) {
                posttimeToDismissMs = SystemClock.elapsedRealtime() - posttimeElapsedMs;
            }
        }

        @Override
        public String toString() {
            return "SingleNotificationStats{" +
                    "posttimeElapsedMs=" + posttimeElapsedMs +
                    ", posttimeToFirstClickMs=" + posttimeToFirstClickMs +
                    ", posttimeToDismissMs=" + posttimeToDismissMs +
                    '}';
        }
    }

    /**
     * Aggregates long samples to sum and averages.
     */
    public static class Aggregate {
        long numSamples;
        double avg;
        double sum2;
        double var;

        public void addSample(long sample) {
            // Welford's "Method for Calculating Corrected Sums of Squares"
            // http://www.jstor.org/stable/1266577?seq=2
            numSamples++;
            final double n = numSamples;
            final double delta = sample - avg;
            avg += (1.0 / n) * delta;
            sum2 += ((n - 1) / n) * delta * delta;
            final double divisor = numSamples == 1 ? 1.0 : n - 1.0;
            var = sum2 / divisor;
        }

        @Override
        public String toString() {
            return "Aggregate{" +
                    "numSamples=" + numSamples +
                    ", avg=" + avg +
                    ", var=" + var +
                    '}';
        }
    }

    private static class SQLiteLog {
        private static final String TAG = "NotificationSQLiteLog";

        // Message types passed to the background handler.
        private static final int MSG_POST = 1;
        private static final int MSG_CLICK = 2;
        private static final int MSG_REMOVE = 3;
        private static final int MSG_DISMISS = 4;

        private static final String DB_NAME = "notification_log.db";
        private static final int DB_VERSION = 1;

        /** Age in ms after which events are pruned from the DB. */
        private static final long HORIZON_MS = 7 * 24 * 60 * 60 * 1000L;  // 1 week
        /** Delay between pruning the DB. Used to throttle pruning. */
        private static final long PRUNE_MIN_DELAY_MS = 6 * 60 * 60 * 1000L;  // 6 hours
        /** Mininum number of writes between pruning the DB. Used to throttle pruning. */
        private static final long PRUNE_MIN_WRITES = 1024;

        // Table 'log'
        private static final String TAB_LOG = "log";
        private static final String COL_EVENT_USER_ID = "event_user_id";
        private static final String COL_EVENT_TYPE = "event_type";
        private static final String COL_EVENT_TIME = "event_time_ms";
        private static final String COL_KEY = "key";
        private static final String COL_PKG = "pkg";
        private static final String COL_NOTIFICATION_ID = "nid";
        private static final String COL_TAG = "tag";
        private static final String COL_WHEN_MS = "when_ms";
        private static final String COL_DEFAULTS = "defaults";
        private static final String COL_FLAGS = "flags";
        private static final String COL_PRIORITY = "priority";
        private static final String COL_CATEGORY = "category";
        private static final String COL_ACTION_COUNT = "action_count";

        private static final int EVENT_TYPE_POST = 1;
        private static final int EVENT_TYPE_CLICK = 2;
        private static final int EVENT_TYPE_REMOVE = 3;
        private static final int EVENT_TYPE_DISMISS = 4;

        private static long sLastPruneMs;
        private static long sNumWrites;

        private final SQLiteOpenHelper mHelper;
        private final Handler mWriteHandler;

        private static final long DAY_MS = 24 * 60 * 60 * 1000;

        public SQLiteLog(Context context) {
            HandlerThread backgroundThread = new HandlerThread("notification-sqlite-log",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            mWriteHandler = new Handler(backgroundThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    NotificationRecord r = (NotificationRecord) msg.obj;
                    long nowMs = System.currentTimeMillis();
                    switch (msg.what) {
                        case MSG_POST:
                            writeEvent(r.sbn.getPostTime(), EVENT_TYPE_POST, r, true);
                            break;
                        case MSG_CLICK:
                            writeEvent(nowMs, EVENT_TYPE_CLICK, r, false);
                            break;
                        case MSG_REMOVE:
                            writeEvent(nowMs, EVENT_TYPE_REMOVE, r, false);
                            break;
                        case MSG_DISMISS:
                            writeEvent(nowMs, EVENT_TYPE_DISMISS, r, false);
                            break;
                        default:
                            Log.wtf(TAG, "Unknown message type: " + msg.what);
                            break;
                    }
                }
            };
            mHelper = new SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
                @Override
                public void onCreate(SQLiteDatabase db) {
                    db.execSQL("CREATE TABLE " + TAB_LOG + " (" +
                            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            COL_EVENT_USER_ID + " INT," +
                            COL_EVENT_TYPE + " INT," +
                            COL_EVENT_TIME + " INT," +
                            COL_KEY + " TEXT," +
                            COL_PKG + " TEXT," +
                            COL_NOTIFICATION_ID + " INT," +
                            COL_TAG + " TEXT," +
                            COL_WHEN_MS + " INT," +
                            COL_DEFAULTS + " INT," +
                            COL_FLAGS + " INT," +
                            COL_PRIORITY + " INT," +
                            COL_CATEGORY + " TEXT," +
                            COL_ACTION_COUNT + " INT" +
                            ")");
                }

                @Override
                public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                    db.execSQL("DROP TABLE IF EXISTS " + TAB_LOG);
                    onCreate(db);
                }
            };
        }

        public void logPosted(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_POST, notification));
        }

        public void logClicked(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_CLICK, notification));
        }

        public void logRemoved(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_REMOVE, notification));
        }

        public void logDismissed(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_DISMISS, notification));
        }

        public void printPostFrequencies(PrintWriter pw, String indent) {
            SQLiteDatabase db = mHelper.getReadableDatabase();
            long nowMs = System.currentTimeMillis();
            String q = "SELECT " +
                    COL_EVENT_USER_ID + ", " +
                    COL_PKG + ", " +
                    // Bucket by day by looking at 'floor((nowMs - eventTimeMs) / dayMs)'
                    "CAST(((" + nowMs + " - " + COL_EVENT_TIME + ") / " + DAY_MS + ") AS int) " +
                        "AS day, " +
                    "COUNT(*) AS cnt " +
                    "FROM " + TAB_LOG + " " +
                    "WHERE " +
                    COL_EVENT_TYPE + "=" + EVENT_TYPE_POST + " " +
                    "GROUP BY " + COL_EVENT_USER_ID + ", day, " + COL_PKG;
            Cursor cursor = db.rawQuery(q, null);
            try {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    int userId = cursor.getInt(0);
                    String pkg = cursor.getString(1);
                    int day = cursor.getInt(2);
                    int count = cursor.getInt(3);
                    pw.println(indent + "post_frequency{user_id=" + userId + ",pkg=" + pkg +
                            ",day=" + day + ",count=" + count + "}");
                }
            } finally {
                cursor.close();
            }
        }

        private void writeEvent(long eventTimeMs, int eventType, NotificationRecord r,
                boolean populateNotificationDetails) {
            ContentValues cv = new ContentValues();
            cv.put(COL_EVENT_USER_ID, r.sbn.getUser().getIdentifier());
            cv.put(COL_EVENT_TIME, eventTimeMs);
            cv.put(COL_EVENT_TYPE, eventType);
            putNotificationIdentifiers(r, cv);
            if (populateNotificationDetails) {
                putNotificationDetails(r, cv);
            }
            SQLiteDatabase db = mHelper.getWritableDatabase();
            if (db.insert(TAB_LOG, null, cv) < 0) {
                Log.wtf(TAG, "Error while trying to insert values: " + cv);
            }
            sNumWrites++;
            pruneIfNecessary(db);
        }

        private void pruneIfNecessary(SQLiteDatabase db) {
            // Prune if we haven't in a while.
            long nowMs = System.currentTimeMillis();
            if (sNumWrites > PRUNE_MIN_WRITES ||
                    nowMs - sLastPruneMs > PRUNE_MIN_DELAY_MS) {
                sNumWrites = 0;
                sLastPruneMs = nowMs;
                long horizonStartMs = nowMs - HORIZON_MS;
                int deletedRows = db.delete(TAB_LOG, COL_EVENT_TIME + " < ?",
                        new String[] { String.valueOf(horizonStartMs) });
                Log.d(TAG, "Pruned event entries: " + deletedRows);
            }
        }

        private static void putNotificationIdentifiers(NotificationRecord r, ContentValues outCv) {
            outCv.put(COL_KEY, r.sbn.getKey());
            outCv.put(COL_PKG, r.sbn.getPackageName());
        }

        private static void putNotificationDetails(NotificationRecord r, ContentValues outCv) {
            outCv.put(COL_NOTIFICATION_ID, r.sbn.getId());
            if (r.sbn.getTag() != null) {
                outCv.put(COL_TAG, r.sbn.getTag());
            }
            outCv.put(COL_WHEN_MS, r.sbn.getPostTime());
            outCv.put(COL_FLAGS, r.getNotification().flags);
            outCv.put(COL_PRIORITY, r.getNotification().priority);
            if (r.getNotification().category != null) {
                outCv.put(COL_CATEGORY, r.getNotification().category);
            }
            outCv.put(COL_ACTION_COUNT, r.getNotification().actions != null ?
                    r.getNotification().actions.length : 0);
        }

        public void dump(PrintWriter pw, String indent) {
            printPostFrequencies(pw, indent);
        }
    }
}
