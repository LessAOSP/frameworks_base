/**
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

package com.android.server.voiceinteraction;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Helper to manage the database of the sound models that have been registered on the device.
 *
 * @hide
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    static final String TAG = "SoundModelDBHelper";
    static final boolean DBG = false;

    private static final String NAME = "sound_model.db";
    private static final int VERSION = 2;

    public static interface KeyphraseContract {
        public static final String TABLE = "keyphrase";
        public static final String KEY_ID = "_id";
        public static final String KEY_RECOGNITION_MODES = "modes";
        public static final String KEY_LOCALE = "locale";
        public static final String KEY_HINT_TEXT = "hint_text";
        public static final String KEY_USERS = "users";
        public static final String KEY_SOUND_MODEL_ID = "sound_model_id";
    }

    public static interface SoundModelContract {
        public static final String TABLE = "sound_model";
        public static final String KEY_ID = "_id";
        public static final String KEY_TYPE = "type";
        public static final String KEY_DATA = "data";
    }

    // Table Create Statements
    private static final String CREATE_TABLE_KEYPRHASES = "CREATE TABLE "
            + KeyphraseContract.TABLE + "("
            + KeyphraseContract.KEY_ID + " INTEGER PRIMARY KEY,"
            + KeyphraseContract.KEY_RECOGNITION_MODES + " INTEGER,"
            + KeyphraseContract.KEY_USERS + " TEXT,"
            + KeyphraseContract.KEY_SOUND_MODEL_ID + " TEXT,"
            + KeyphraseContract.KEY_LOCALE + " TEXT,"
            + KeyphraseContract.KEY_HINT_TEXT + " TEXT" + ")";

    private static final String CREATE_TABLE_SOUND_MODEL = "CREATE TABLE "
            + SoundModelContract.TABLE + "("
            + SoundModelContract.KEY_ID + " TEXT PRIMARY KEY,"
            + SoundModelContract.KEY_TYPE + " INTEGER,"
            + SoundModelContract.KEY_DATA + " BLOB" + ")";

    private final UserManager mUserManager;

    public DatabaseHelper(Context context) {
        super(context, NAME, null, VERSION);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // creating required tables
        db.execSQL(CREATE_TABLE_KEYPRHASES);
        db.execSQL(CREATE_TABLE_SOUND_MODEL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: For now, drop older tables and recreate new ones.
        db.execSQL("DROP TABLE IF EXISTS " + KeyphraseContract.TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + SoundModelContract.TABLE);
        onCreate(db);
    }

    public boolean addOrUpdateKeyphraseSoundModel(KeyphraseSoundModel soundModel) {
        synchronized(this) {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            // Generate a random ID for the model.
            values.put(SoundModelContract.KEY_ID, soundModel.uuid.toString());
            values.put(SoundModelContract.KEY_DATA, soundModel.data);
            values.put(SoundModelContract.KEY_TYPE, SoundTrigger.SoundModel.TYPE_KEYPHRASE);
    
            boolean status = true;
            if (db.insertWithOnConflict(SoundModelContract.TABLE, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1) {
                for (Keyphrase keyphrase : soundModel.keyphrases) {
                    status &= addOrUpdateKeyphraseLocked(db, soundModel.uuid, keyphrase);
                }
                db.close();
                return status;
            } else {
                Slog.w(TAG, "Failed to persist sound model to database");
                db.close();
                return false;
            }
        }
    }

    private boolean addOrUpdateKeyphraseLocked(
            SQLiteDatabase db, UUID modelId, Keyphrase keyphrase) {
        ContentValues values = new ContentValues();
        values.put(KeyphraseContract.KEY_ID, keyphrase.id);
        values.put(KeyphraseContract.KEY_RECOGNITION_MODES, keyphrase.recognitionModes);
        values.put(KeyphraseContract.KEY_SOUND_MODEL_ID, modelId.toString());
        values.put(KeyphraseContract.KEY_HINT_TEXT, keyphrase.text);
        values.put(KeyphraseContract.KEY_LOCALE, keyphrase.locale);
        values.put(KeyphraseContract.KEY_USERS, getCommaSeparatedString(keyphrase.users));
        if (db.insertWithOnConflict(
                KeyphraseContract.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1) {
            return true;
        } else {
            Slog.w(TAG, "Failed to persist keyphrase to database");
            return false;
        }
    }

    /**
     * Deletes the sound model and associated keyphrases.
     */
    public boolean deleteKeyphraseSoundModel(UUID uuid) {
        synchronized(this) {
            SQLiteDatabase db = getWritableDatabase();
            String modelId = uuid.toString();
            String soundModelClause = SoundModelContract.KEY_ID + "=" + modelId;
            boolean status = true;
            if (db.delete(SoundModelContract.TABLE, soundModelClause, null) == 0) {
                Slog.w(TAG, "No sound models deleted from the database");
                status = false;
            }
            String keyphraseClause = KeyphraseContract.KEY_SOUND_MODEL_ID + "=" + modelId;
            if (db.delete(KeyphraseContract.TABLE, keyphraseClause, null) == 0) {
                Slog.w(TAG, "No keyphrases deleted from the database");
                status = false;
            }
            db.close();
            return status;
        }
    }

    /**
     * Lists all the keyphrase sound models currently registered with the system.
     */
    public List<KeyphraseSoundModel> getKephraseSoundModels() {
        synchronized(this) {
            List<KeyphraseSoundModel> models = new ArrayList<>();
            String selectQuery = "SELECT  * FROM " + SoundModelContract.TABLE;
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(selectQuery, null);
    
            // looping through all rows and adding to list
            if (c.moveToFirst()) {
                do {
                    int type = c.getInt(c.getColumnIndex(SoundModelContract.KEY_TYPE));
                    if (type != SoundTrigger.SoundModel.TYPE_KEYPHRASE) {
                        // Ignore non-keyphrase sound models.
                        continue;
                    }
                    String id = c.getString(c.getColumnIndex(SoundModelContract.KEY_ID));
                    byte[] data = c.getBlob(c.getColumnIndex(SoundModelContract.KEY_DATA));
                    // Get all the keyphrases for this this sound model.
                    // Validate the sound model.
                    if (id == null) {
                        Slog.w(TAG, "Ignoring sound model since it doesn't specify an ID");
                        continue;
                    }
                    KeyphraseSoundModel model = new KeyphraseSoundModel(
                            UUID.fromString(id), data, getKeyphrasesForSoundModelLocked(db, id));
                    if (DBG) {
                        Slog.d(TAG, "Adding model: " + model);
                    }
                    models.add(model);
                } while (c.moveToNext());
            }
            c.close();
            db.close();
            return models;
        }
    }

    private Keyphrase[] getKeyphrasesForSoundModelLocked(SQLiteDatabase db, String modelId) {
        List<Keyphrase> keyphrases = new ArrayList<>();
        String selectQuery = "SELECT  * FROM " + KeyphraseContract.TABLE
                + " WHERE " + KeyphraseContract.KEY_SOUND_MODEL_ID + " = '" + modelId + "'";
        Cursor c = db.rawQuery(selectQuery, null);

        // looping through all rows and adding to list
        if (c.moveToFirst()) {
            do {
                int id = c.getInt(c.getColumnIndex(KeyphraseContract.KEY_ID));
                int modes = c.getInt(c.getColumnIndex(KeyphraseContract.KEY_RECOGNITION_MODES));
                int[] users = getArrayForCommaSeparatedString(
                        c.getString(c.getColumnIndex(KeyphraseContract.KEY_USERS)));
                String locale = c.getString(c.getColumnIndex(KeyphraseContract.KEY_LOCALE));
                String hintText = c.getString(c.getColumnIndex(KeyphraseContract.KEY_HINT_TEXT));

                // Only add keyphrases meant for the current user.
                if (users == null) {
                    // No users present in the keyphrase.
                    Slog.w(TAG, "Ignoring keyphrase since it doesn't specify users");
                    continue;
                }
                boolean isAvailableForCurrentUser = false;
                int currentUser = mUserManager.getUserHandle();
                for (int user : users) {
                    if (currentUser == user) {
                        isAvailableForCurrentUser = true;
                        break;
                    }
                }
                if (!isAvailableForCurrentUser) {
                    Slog.w(TAG, "Ignoring keyphrase since it's not for the current user");
                    continue;
                }

                keyphrases.add(new Keyphrase(id, modes, locale, hintText, users));
            } while (c.moveToNext());
        }
        Keyphrase[] keyphraseArr = new Keyphrase[keyphrases.size()];
        keyphrases.toArray(keyphraseArr);
        c.close();
        return keyphraseArr;
    }


    private static String getCommaSeparatedString(int[] users) {
        if (users == null || users.length == 0) {
            return "";
        }
        String csv = "";
        for (int user : users) {
            csv += String.valueOf(user);
            csv += ",";
        }
        return csv.substring(0, csv.length() - 1);
    }

    private static int[] getArrayForCommaSeparatedString(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String[] usersStr = text.split(",");
        int[] users = new int[usersStr.length];
        for (int i = 0; i < usersStr.length; i++) {
            users[i] = Integer.valueOf(usersStr[i]);
        }
        return users;
    }
}
