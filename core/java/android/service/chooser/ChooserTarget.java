/*
 * Copyright (C) 2015 The Android Open Source Project
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


package android.service.chooser;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Log;

/**
 * A ChooserTarget represents a deep-link into an application as returned by a
 * {@link android.service.chooser.ChooserTargetService}.
 */
public final class ChooserTarget implements Parcelable {
    private static final String TAG = "ChooserTarget";

    /**
     * The title of this target that will be shown to the user. The title may be truncated
     * if it is too long to display in the space provided.
     */
    private CharSequence mTitle;

    /**
     * The icon that will be shown to the user to represent this target.
     * The system may resize this icon as appropriate.
     */
    private Bitmap mIcon;

    /**
     * The IntentSender that will be used to deliver the intent to the target.
     * It will be {@link android.content.Intent#fillIn(android.content.Intent, int)} filled in}
     * by the real intent sent by the application.
     */
    private IntentSender mIntentSender;

    /**
     * A raw intent provided in lieu of an IntentSender. Will be filled in and sent
     * by {@link #sendIntent(Context, Intent)}.
     */
    private Intent mIntent;

    /**
     * The score given to this item. It can be normalized.
     */
    private float mScore;

    /**
     * Construct a deep link target for presentation by a chooser UI.
     *
     * <p>A target is composed of a title and an icon for presentation to the user.
     * The UI presenting this target may truncate the title if it is too long to be presented
     * in the available space, as well as crop, resize or overlay the supplied icon.</p>
     *
     * <p>The creator of a target may supply a ranking score. This score is assumed to be relative
     * to the other targets supplied by the same
     * {@link ChooserTargetService#onGetChooserTargets(ComponentName, IntentFilter) query}.
     * Scores should be in the range from 0.0f (unlikely match) to 1.0f (very relevant match).
     * Scores for a set of targets do not need to sum to 1.</p>
     *
     * <p>Before being sent, the PendingIntent supplied will be
     * {@link Intent#fillIn(Intent, int) filled in} by the Intent originally supplied
     * to the chooser. When constructing a PendingIntent for use in a ChooserTarget, make sure
     * that you permit the relevant fields to be filled in using the appropriate flags such as
     * {@link Intent#FILL_IN_ACTION}, {@link Intent#FILL_IN_CATEGORIES},
     * {@link Intent#FILL_IN_DATA} and {@link Intent#FILL_IN_CLIP_DATA}. Note that
     * {@link Intent#FILL_IN_CLIP_DATA} is required to appropriately receive URI permission grants
     * for {@link Intent#ACTION_SEND} intents.</p>
     *
     * <p>Take care not to place custom {@link android.os.Parcelable} types into
     * the PendingIntent as extras, as the system will not be able to unparcel it to merge
     * additional extras.</p>
     *
     * @param title title of this target that will be shown to a user
     * @param icon icon to represent this target
     * @param score ranking score for this target between 0.0f and 1.0f, inclusive
     * @param pendingIntent PendingIntent to fill in and send if the user chooses this target
     */
    public ChooserTarget(CharSequence title, Bitmap icon, float score,
            PendingIntent pendingIntent) {
        this(title, icon, score, pendingIntent.getIntentSender());
    }

    /**
     * Construct a deep link target for presentation by a chooser UI.
     *
     * <p>A target is composed of a title and an icon for presentation to the user.
     * The UI presenting this target may truncate the title if it is too long to be presented
     * in the available space, as well as crop, resize or overlay the supplied icon.</p>
     *
     * <p>The creator of a target may supply a ranking score. This score is assumed to be relative
     * to the other targets supplied by the same
     * {@link ChooserTargetService#onGetChooserTargets(ComponentName, IntentFilter) query}.
     * Scores should be in the range from 0.0f (unlikely match) to 1.0f (very relevant match).
     * Scores for a set of targets do not need to sum to 1.</p>
     *
     * <p>Before being sent, the IntentSender supplied will be
     * {@link Intent#fillIn(Intent, int) filled in} by the Intent originally supplied
     * to the chooser. When constructing an IntentSender for use in a ChooserTarget, make sure
     * that you permit the relevant fields to be filled in using the appropriate flags such as
     * {@link Intent#FILL_IN_ACTION}, {@link Intent#FILL_IN_CATEGORIES},
     * {@link Intent#FILL_IN_DATA} and {@link Intent#FILL_IN_CLIP_DATA}. Note that
     * {@link Intent#FILL_IN_CLIP_DATA} is required to appropriately receive URI permission grants
     * for {@link Intent#ACTION_SEND} intents.</p>
     *
     * <p>Take care not to place custom {@link android.os.Parcelable} types into
     * the IntentSender as extras, as the system will not be able to unparcel it to merge
     * additional extras.</p>
     *
     * @param title title of this target that will be shown to a user
     * @param icon icon to represent this target
     * @param score ranking score for this target between 0.0f and 1.0f, inclusive
     * @param intentSender IntentSender to fill in and send if the user chooses this target
     */
    public ChooserTarget(CharSequence title, Bitmap icon, float score, IntentSender intentSender) {
        mTitle = title;
        mIcon = icon;
        if (score > 1.f || score < 0.f) {
            throw new IllegalArgumentException("Score " + score + " out of range; "
                    + "must be between 0.0f and 1.0f");
        }
        mScore = score;
        mIntentSender = intentSender;
    }

    /**
     * Construct a deep link target for presentation by a chooser UI.
     *
     * <p>A target is composed of a title and an icon for presentation to the user.
     * The UI presenting this target may truncate the title if it is too long to be presented
     * in the available space, as well as crop, resize or overlay the supplied icon.</p>
     *
     * <p>The creator of a target may supply a ranking score. This score is assumed to be relative
     * to the other targets supplied by the same
     * {@link ChooserTargetService#onGetChooserTargets(ComponentName, IntentFilter) query}.
     * Scores should be in the range from 0.0f (unlikely match) to 1.0f (very relevant match).
     * Scores for a set of targets do not need to sum to 1.</p>
     *
     * <p>Before being sent, the Intent supplied will be
     * {@link Intent#fillIn(Intent, int) filled in} by the Intent originally supplied
     * to the chooser.</p>
     *
     * <p>Take care not to place custom {@link android.os.Parcelable} types into
     * the Intent as extras, as the system will not be able to unparcel it to merge
     * additional extras.</p>
     *
     * @param title title of this target that will be shown to a user
     * @param icon icon to represent this target
     * @param score ranking score for this target between 0.0f and 1.0f, inclusive
     * @param intent Intent to fill in and send if the user chooses this target
     */
    public ChooserTarget(CharSequence title, Bitmap icon, float score, Intent intent) {
        mTitle = title;
        mIcon = icon;
        if (score > 1.f || score < 0.f) {
            throw new IllegalArgumentException("Score " + score + " out of range; "
                    + "must be between 0.0f and 1.0f");
        }
        mScore = score;
        mIntent = intent;
    }

    ChooserTarget(Parcel in) {
        mTitle = in.readCharSequence();
        if (in.readInt() != 0) {
            mIcon = Bitmap.CREATOR.createFromParcel(in);
        } else {
            mIcon = null;
        }
        mScore = in.readFloat();
        mIntentSender = IntentSender.readIntentSenderOrNullFromParcel(in);
        if (in.readInt() != 0) {
            mIntent = Intent.CREATOR.createFromParcel(in);
        }
    }

    /**
     * Returns the title of this target for display to a user. The UI displaying the title
     * may truncate this title if it is too long to be displayed in full.
     *
     * @return the title of this target, intended to be shown to a user
     */
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns the icon representing this target for display to a user. The UI displaying the icon
     * may crop, resize or overlay this icon.
     *
     * @return the icon representing this target, intended to be shown to a user
     */
    public Bitmap getIcon() {
        return mIcon;
    }

    /**
     * Returns the ranking score supplied by the creator of this ChooserTarget.
     * Values are between 0.0f and 1.0f. The UI displaying the target may
     * take this score into account when sorting and merging targets from multiple sources.
     *
     * @return the ranking score for this target between 0.0f and 1.0f, inclusive
     */
    public float getScore() {
        return mScore;
    }

    /**
     * Returns the raw IntentSender supplied by the ChooserTarget's creator.
     * This may be null if the creator specified a regular Intent instead.
     *
     * <p>To fill in and send the intent, see {@link #sendIntent(Context, Intent)}.</p>
     *
     * @return the IntentSender supplied by the ChooserTarget's creator
     */
    public IntentSender getIntentSender() {
        return mIntentSender;
    }

    /**
     * Returns the Intent supplied by the ChooserTarget's creator.
     * This may be null if the creator specified an IntentSender or PendingIntent instead.
     *
     * <p>To fill in and send the intent, see {@link #sendIntent(Context, Intent)}.</p>
     *
     * @return the Intent supplied by the ChooserTarget's creator
     */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Fill in the IntentSender supplied by the ChooserTarget's creator and send it.
     *
     * @param context the sending Context; generally the Activity presenting the chooser UI
     * @param fillInIntent the Intent provided to the Chooser to be sent to a selected target
     * @return true if sending the Intent was successful
     */
    public boolean sendIntent(Context context, Intent fillInIntent) {
        if (fillInIntent != null) {
            fillInIntent.migrateExtraStreamToClipData();
            fillInIntent.prepareToLeaveProcess();
        }
        if (mIntentSender != null) {
            try {
                mIntentSender.sendIntent(context, 0, fillInIntent, null, null);
                return true;
            } catch (IntentSender.SendIntentException e) {
                Log.e(TAG, "sendIntent " + this + " failed", e);
                return false;
            }
        } else if (mIntent != null) {
            try {
                final Intent toSend = new Intent(mIntent);
                toSend.fillIn(fillInIntent, 0);
                context.startActivity(toSend);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "sendIntent " + this + " failed", e);
                return false;
            }
        } else {
            Log.e(TAG, "sendIntent " + this + " failed - no IntentSender or Intent to send");
            return false;
        }
    }

    /**
     * Same as {@link #sendIntent(Context, Intent)}, but offers a userId field to use
     * for launching the {@link #getIntent() intent} using
     * {@link Activity#startActivityAsCaller(Intent, Bundle, int)} if the
     * {@link #getIntentSender() IntentSender} is not present. If the IntentSender is present,
     * it will be invoked as usual with its own calling identity.
     *
     * @hide internal use only.
     */
    public boolean sendIntentAsCaller(Activity context, Intent fillInIntent, int userId) {
        if (fillInIntent != null) {
            fillInIntent.migrateExtraStreamToClipData();
            fillInIntent.prepareToLeaveProcess();
        }
        if (mIntentSender != null) {
            try {
                mIntentSender.sendIntent(context, 0, fillInIntent, null, null);
                return true;
            } catch (IntentSender.SendIntentException e) {
                Log.e(TAG, "sendIntent " + this + " failed", e);
                return false;
            }
        } else if (mIntent != null) {
            try {
                final Intent toSend = new Intent(mIntent);
                toSend.fillIn(fillInIntent, 0);
                context.startActivityAsCaller(toSend, null, userId);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "sendIntent " + this + " failed", e);
                return false;
            }
        } else {
            Log.e(TAG, "sendIntent " + this + " failed - no IntentSender or Intent to send");
            return false;
        }
    }

    /**
     * The UserHandle is only used if we're launching a raw intent. The IntentSender will be
     * launched with its associated identity.
     *
     * @hide Internal use only
     */
    public boolean sendIntentAsUser(Activity context, Intent fillInIntent, UserHandle user) {
        if (fillInIntent != null) {
            fillInIntent.migrateExtraStreamToClipData();
            fillInIntent.prepareToLeaveProcess();
        }
        if (mIntentSender != null) {
            try {
                mIntentSender.sendIntent(context, 0, fillInIntent, null, null);
                return true;
            } catch (IntentSender.SendIntentException e) {
                Log.e(TAG, "sendIntent " + this + " failed", e);
                return false;
            }
        } else if (mIntent != null) {
            try {
                final Intent toSend = new Intent(mIntent);
                toSend.fillIn(fillInIntent, 0);
                context.startActivityAsUser(toSend, user);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "sendIntent " + this + " failed", e);
                return false;
            }
        } else {
            Log.e(TAG, "sendIntent " + this + " failed - no IntentSender or Intent to send");
            return false;
        }
    }

    @Override
    public String toString() {
        return "ChooserTarget{"
                + (mIntentSender != null ? mIntentSender.getCreatorPackage() : mIntent)
                + ", "
                + "'" + mTitle
                + "', " + mScore + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(mTitle);
        if (mIcon != null) {
            dest.writeInt(1);
            mIcon.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeFloat(mScore);
        IntentSender.writeIntentSenderOrNullToParcel(mIntentSender, dest);
        dest.writeInt(mIntent != null ? 1 : 0);
        if (mIntent != null) {
            mIntent.writeToParcel(dest, 0);
        }
    }

    public static final Creator<ChooserTarget> CREATOR
            = new Creator<ChooserTarget>() {
        @Override
        public ChooserTarget createFromParcel(Parcel source) {
            return new ChooserTarget(source);
        }

        @Override
        public ChooserTarget[] newArray(int size) {
            return new ChooserTarget[size];
        }
    };
}
