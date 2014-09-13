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

package com.android.server.notification;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LruCache;
import android.util.Slog;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;

/**
 * This {@link NotificationSignalExtractor} attempts to validate
 * people references. Also elevates the priority of real people.
 *
 * {@hide}
 */
public class ValidateNotificationPeople implements NotificationSignalExtractor {
    private static final String TAG = "ValidateNotificationPeople";
    private static final boolean INFO = true;
    private static final boolean DEBUG = false;

    private static final boolean ENABLE_PEOPLE_VALIDATOR = true;
    private static final String SETTING_ENABLE_PEOPLE_VALIDATOR =
            "validate_notification_people_enabled";
    private static final String[] LOOKUP_PROJECTION = { Contacts._ID, Contacts.STARRED };
    private static final int MAX_PEOPLE = 10;
    private static final int PEOPLE_CACHE_SIZE = 200;

    /** Indicates that the notification does not reference any valid contacts. */
    static final float NONE = 0f;

    /**
     * Affinity will be equal to or greater than this value on notifications
     * that reference a valid contact.
     */
    static final float VALID_CONTACT = 0.5f;

    /**
     * Affinity will be equal to or greater than this value on notifications
     * that reference a starred contact.
     */
    static final float STARRED_CONTACT = 1f;

    protected boolean mEnabled;
    private Context mBaseContext;

    // maps raw person handle to resolved person object
    private LruCache<String, LookupResult> mPeopleCache;
    private Map<Integer, Context> mUserToContextMap;

    public void initialize(Context context) {
        if (DEBUG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mUserToContextMap = new ArrayMap<>();
        mBaseContext = context;
        mPeopleCache = new LruCache<String, LookupResult>(PEOPLE_CACHE_SIZE);
        mEnabled = ENABLE_PEOPLE_VALIDATOR && 1 == Settings.Global.getInt(
                mBaseContext.getContentResolver(), SETTING_ENABLE_PEOPLE_VALIDATOR, 1);
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (!mEnabled) {
            if (INFO) Slog.i(TAG, "disabled");
            return null;
        }
        if (record == null || record.getNotification() == null) {
            if (INFO) Slog.i(TAG, "skipping empty notification");
            return null;
        }
        if (record.getUserId() == UserHandle.USER_ALL) {
            if (INFO) Slog.i(TAG, "skipping global notification");
            return null;
        }
        Context context = getContextAsUser(record.getUser());
        if (context == null) {
            if (INFO) Slog.i(TAG, "skipping notification that lacks a context");
            return null;
        }
        return validatePeople(context, record);
    }

    @Override
    public void setConfig(RankingConfig config) {
        // ignore: config has no relevant information yet.
    }

    public float getContactAffinity(UserHandle userHandle, Bundle extras) {
        if (DEBUG) Slog.d(TAG, "checking affinity for " + userHandle);
        if (extras == null) return NONE;
        final String key = Long.toString(System.nanoTime());
        final float[] affinityOut = new float[1];
        Context context = getContextAsUser(userHandle);
        if (context == null) {
            return NONE;
        }
        final PeopleRankingReconsideration prr = validatePeople(context, key, extras, affinityOut);
        float affinity = affinityOut[0];
        if (prr != null) {
            prr.work();
            affinity = Math.max(prr.getContactAffinity(), affinity);
        }
        return affinity;
    }

    private Context getContextAsUser(UserHandle userHandle) {
        Context context = mUserToContextMap.get(userHandle.getIdentifier());
        if (context == null) {
            try {
                context = mBaseContext.createPackageContextAsUser("android", 0, userHandle);
                mUserToContextMap.put(userHandle.getIdentifier(), context);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "failed to create package context for lookups", e);
            }
        }
        return context;
    }

    private RankingReconsideration validatePeople(Context context,
            final NotificationRecord record) {
        final String key = record.getKey();
        final Bundle extras = record.getNotification().extras;
        final float[] affinityOut = new float[1];
        final RankingReconsideration rr = validatePeople(context, key, extras, affinityOut);
        record.setContactAffinity(affinityOut[0]);
        return rr;
    }

    private PeopleRankingReconsideration validatePeople(Context context, String key, Bundle extras,
            float[] affinityOut) {
        float affinity = NONE;
        if (extras == null) {
            return null;
        }

        final String[] people = getExtraPeople(extras);
        if (people == null || people.length == 0) {
            return null;
        }

        if (INFO) Slog.i(TAG, "Validating: " + key + " for " + context.getUserId());
        final LinkedList<String> pendingLookups = new LinkedList<String>();
        for (int personIdx = 0; personIdx < people.length && personIdx < MAX_PEOPLE; personIdx++) {
            final String handle = people[personIdx];
            if (TextUtils.isEmpty(handle)) continue;

            synchronized (mPeopleCache) {
                final String cacheKey = getCacheKey(context.getUserId(), handle);
                LookupResult lookupResult = mPeopleCache.get(cacheKey);
                if (lookupResult == null || lookupResult.isExpired()) {
                    pendingLookups.add(handle);
                } else {
                    if (DEBUG) Slog.d(TAG, "using cached lookupResult: " + lookupResult.mId);
                }
                if (lookupResult != null) {
                    affinity = Math.max(affinity, lookupResult.getAffinity());
                }
            }
        }

        // record the best available data, so far:
        affinityOut[0] = affinity;

        if (pendingLookups.isEmpty()) {
            if (INFO) Slog.i(TAG, "final affinity: " + affinity);
            return null;
        }

        if (DEBUG) Slog.d(TAG, "Pending: future work scheduled for: " + key);
        return new PeopleRankingReconsideration(context, key, pendingLookups);
    }

    private String getCacheKey(int userId, String handle) {
        return Integer.toString(userId) + ":" + handle;
    }

    // VisibleForTesting
    public static String[] getExtraPeople(Bundle extras) {
        Object people = extras.get(Notification.EXTRA_PEOPLE);
        if (people instanceof String[]) {
            return (String[]) people;
        }

        if (people instanceof ArrayList) {
            ArrayList arrayList = (ArrayList) people;

            if (arrayList.isEmpty()) {
                return null;
            }

            if (arrayList.get(0) instanceof String) {
                ArrayList<String> stringArray = (ArrayList<String>) arrayList;
                return stringArray.toArray(new String[stringArray.size()]);
            }

            if (arrayList.get(0) instanceof CharSequence) {
                ArrayList<CharSequence> charSeqList = (ArrayList<CharSequence>) arrayList;
                final int N = charSeqList.size();
                String[] array = new String[N];
                for (int i = 0; i < N; i++) {
                    array[i] = charSeqList.get(i).toString();
                }
                return array;
            }

            return null;
        }

        if (people instanceof String) {
            String[] array = new String[1];
            array[0] = (String) people;
            return array;
        }

        if (people instanceof char[]) {
            String[] array = new String[1];
            array[0] = new String((char[]) people);
            return array;
        }

        if (people instanceof CharSequence) {
            String[] array = new String[1];
            array[0] = ((CharSequence) people).toString();
            return array;
        }

        if (people instanceof CharSequence[]) {
            CharSequence[] charSeqArray = (CharSequence[]) people;
            final int N = charSeqArray.length;
            String[] array = new String[N];
            for (int i = 0; i < N; i++) {
                array[i] = charSeqArray[i].toString();
            }
            return array;
        }

        return null;
    }

    private LookupResult resolvePhoneContact(Context context, final String number) {
        Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        return searchContacts(context, phoneUri);
    }

    private LookupResult resolveEmailContact(Context context, final String email) {
        Uri numberUri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI,
                Uri.encode(email));
        return searchContacts(context, numberUri);
    }

    private LookupResult searchContacts(Context context, Uri lookupUri) {
        LookupResult lookupResult = new LookupResult();
        Cursor c = null;
        try {
            c = context.getContentResolver().query(lookupUri, LOOKUP_PROJECTION, null, null, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                lookupResult.readContact(c);
            }
        } catch(Throwable t) {
            Slog.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return lookupResult;
    }

    private static class LookupResult {
        private static final long CONTACT_REFRESH_MILLIS = 60 * 60 * 1000;  // 1hr
        public static final int INVALID_ID = -1;

        private final long mExpireMillis;
        private int mId;
        private boolean mStarred;

        public LookupResult() {
            mId = INVALID_ID;
            mStarred = false;
            mExpireMillis = System.currentTimeMillis() + CONTACT_REFRESH_MILLIS;
        }

        public void readContact(Cursor cursor) {
            final int idIdx = cursor.getColumnIndex(Contacts._ID);
            if (idIdx >= 0) {
                mId = cursor.getInt(idIdx);
                if (DEBUG) Slog.d(TAG, "contact _ID is: " + mId);
            } else {
                if (DEBUG) Slog.d(TAG, "invalid cursor: no _ID");
            }
            final int starIdx = cursor.getColumnIndex(Contacts.STARRED);
            if (starIdx >= 0) {
                mStarred = cursor.getInt(starIdx) != 0;
                if (DEBUG) Slog.d(TAG, "contact STARRED is: " + mStarred);
            } else {
                if (DEBUG) Slog.d(TAG, "invalid cursor: no STARRED");
            }
        }

        public boolean isExpired() {
            return mExpireMillis < System.currentTimeMillis();
        }

        public boolean isInvalid() {
            return mId == INVALID_ID || isExpired();
        }

        public float getAffinity() {
            if (isInvalid()) {
                return NONE;
            } else if (mStarred) {
                return STARRED_CONTACT;
            } else {
                return VALID_CONTACT;
            }
        }

        public LookupResult setStarred(boolean starred) {
            mStarred = starred;
            return this;
        }

        public LookupResult setId(int id) {
            mId = id;
            return this;
        }
    }

    private class PeopleRankingReconsideration extends RankingReconsideration {
        private final LinkedList<String> mPendingLookups;
        private final Context mContext;

        private float mContactAffinity = NONE;

        private PeopleRankingReconsideration(Context context, String key, LinkedList<String> pendingLookups) {
            super(key);
            mContext = context;
            mPendingLookups = pendingLookups;
        }

        @Override
        public void work() {
            if (INFO) Slog.i(TAG, "Executing: validation for: " + mKey);
            for (final String handle: mPendingLookups) {
                LookupResult lookupResult = null;
                final Uri uri = Uri.parse(handle);
                if ("tel".equals(uri.getScheme())) {
                    if (DEBUG) Slog.d(TAG, "checking telephone URI: " + handle);
                    lookupResult = resolvePhoneContact(mContext, uri.getSchemeSpecificPart());
                } else if ("mailto".equals(uri.getScheme())) {
                    if (DEBUG) Slog.d(TAG, "checking mailto URI: " + handle);
                    lookupResult = resolveEmailContact(mContext, uri.getSchemeSpecificPart());
                } else if (handle.startsWith(Contacts.CONTENT_LOOKUP_URI.toString())) {
                    if (DEBUG) Slog.d(TAG, "checking lookup URI: " + handle);
                    lookupResult = searchContacts(mContext, uri);
                } else {
                    lookupResult = new LookupResult();  // invalid person for the cache
                    Slog.w(TAG, "unsupported URI " + handle);
                }
                if (lookupResult != null) {
                    synchronized (mPeopleCache) {
                        final String cacheKey = getCacheKey(mContext.getUserId(), handle);
                        mPeopleCache.put(cacheKey, lookupResult);
                    }
                    if (DEBUG) Slog.d(TAG, "lookup contactAffinity is " + lookupResult.getAffinity());
                    mContactAffinity = Math.max(mContactAffinity, lookupResult.getAffinity());
                } else {
                    if (DEBUG) Slog.d(TAG, "lookupResult is null");
                }
            }
        }

        @Override
        public void applyChangesLocked(NotificationRecord operand) {
            float affinityBound = operand.getContactAffinity();
            operand.setContactAffinity(Math.max(mContactAffinity, affinityBound));
            if (INFO) Slog.i(TAG, "final affinity: " + operand.getContactAffinity());
        }

        public float getContactAffinity() {
            return mContactAffinity;
        }
    }
}

