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

package android.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.util.Log;
import android.util.Pair;
import android.view.View;

import java.util.ArrayList;
import java.util.Map;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
 * Context.startActivity(Intent, Bundle)} and related methods.
 */
public class ActivityOptions {
    private static final String TAG = "ActivityOptions";

    /**
     * The package name that created the options.
     * @hide
     */
    public static final String KEY_PACKAGE_NAME = "android:packageName";

    /**
     * Type of animation that arguments specify.
     * @hide
     */
    public static final String KEY_ANIM_TYPE = "android:animType";

    /**
     * Custom enter animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_ENTER_RES_ID = "android:animEnterRes";

    /**
     * Custom exit animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_EXIT_RES_ID = "android:animExitRes";

    /**
     * Bitmap for thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_THUMBNAIL = "android:animThumbnail";

    /**
     * Start X position of thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_START_X = "android:animStartX";

    /**
     * Start Y position of thumbnail animation.
     * @hide
     */
    public static final String KEY_ANIM_START_Y = "android:animStartY";

    /**
     * Initial width of the animation.
     * @hide
     */
    public static final String KEY_ANIM_START_WIDTH = "android:animStartWidth";

    /**
     * Initial height of the animation.
     * @hide
     */
    public static final String KEY_ANIM_START_HEIGHT = "android:animStartHeight";

    /**
     * Callback for when animation is started.
     * @hide
     */
    public static final String KEY_ANIM_START_LISTENER = "android:animStartListener";

    /**
     * For Activity transitions, the calling Activity's TransitionListener used to
     * notify the called Activity when the shared element and the exit transitions
     * complete.
     */
    private static final String KEY_TRANSITION_COMPLETE_LISTENER
            = "android:transitionCompleteListener";

    /**
     * For Activity transitions, the called Activity's listener to receive calls
     * when transitions complete.
     */
    private static final String KEY_TRANSITION_TARGET_LISTENER = "android:transitionTargetListener";

    /**
     * The names of shared elements that are transitioned to the started Activity.
     * This is also the name of shared elements that the started Activity accepted.
     */
    private static final String KEY_SHARED_ELEMENT_NAMES = "android:shared_element_names";

    /**
     * The shared elements names of the views in the calling Activity.
     */
    private static final String KEY_LOCAL_ELEMENT_NAMES = "android:local_element_names";

    /** @hide */
    public static final int ANIM_NONE = 0;
    /** @hide */
    public static final int ANIM_CUSTOM = 1;
    /** @hide */
    public static final int ANIM_SCALE_UP = 2;
    /** @hide */
    public static final int ANIM_THUMBNAIL_SCALE_UP = 3;
    /** @hide */
    public static final int ANIM_THUMBNAIL_SCALE_DOWN = 4;
    /** @hide */
    public static final int ANIM_SCENE_TRANSITION = 5;

    private static final int MSG_SET_LISTENER = 100;
    private static final int MSG_HIDE_SHARED_ELEMENTS = 101;
    private static final int MSG_PREPARE_RESTORE = 102;
    private static final int MSG_RESTORE = 103;

    private String mPackageName;
    private int mAnimationType = ANIM_NONE;
    private int mCustomEnterResId;
    private int mCustomExitResId;
    private Bitmap mThumbnail;
    private int mStartX;
    private int mStartY;
    private int mStartWidth;
    private int mStartHeight;
    private IRemoteCallback mAnimationStartedListener;
    private ResultReceiver mTransitionCompleteListener;
    private ArrayList<String> mSharedElementNames;
    private ArrayList<String> mLocalElementNames;

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeCustomAnimation(Context context,
            int enterResId, int exitResId) {
        return makeCustomAnimation(context, enterResId, exitResId, null, null);
    }

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @param handler If <var>listener</var> is non-null this must be a valid
     * Handler on which to dispatch the callback; otherwise it should be null.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    public static ActivityOptions makeCustomAnimation(Context context,
            int enterResId, int exitResId, Handler handler, OnAnimationStartedListener listener) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = context.getPackageName();
        opts.mAnimationType = ANIM_CUSTOM;
        opts.mCustomEnterResId = enterResId;
        opts.mCustomExitResId = exitResId;
        opts.setOnAnimationStartedListener(handler, listener);
        return opts;
    }

    private void setOnAnimationStartedListener(Handler handler,
            OnAnimationStartedListener listener) {
        if (listener != null) {
            final Handler h = handler;
            final OnAnimationStartedListener finalListener = listener;
            mAnimationStartedListener = new IRemoteCallback.Stub() {
                @Override public void sendResult(Bundle data) throws RemoteException {
                    h.post(new Runnable() {
                        @Override public void run() {
                            finalListener.onAnimationStarted();
                        }
                    });
                }
            };
        }
    }

    /**
     * Callback for use with {@link ActivityOptions#makeThumbnailScaleUpAnimation}
     * to find out when the given animation has started running.
     * @hide
     */
    public interface OnAnimationStartedListener {
        void onAnimationStarted();
    }

    /** @hide */
    public interface ActivityTransitionTarget {
        void sharedElementTransitionComplete(Bundle transitionArgs);
        void exitTransitionComplete();
    }

    /**
     * Create an ActivityOptions specifying an animation where the new
     * activity is scaled from a small originating area of the screen to
     * its final full representation.
     *
     * <p>If the Intent this is being used with has not set its
     * {@link android.content.Intent#setSourceBounds Intent.setSourceBounds},
     * those bounds will be filled in for you based on the initial
     * bounds passed in here.
     *
     * @param source The View that the new activity is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param startX The x starting location of the new activity, relative to <var>source</var>.
     * @param startY The y starting location of the activity, relative to <var>source</var>.
     * @param startWidth The initial width of the new activity.
     * @param startHeight The initial height of the new activity.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeScaleUpAnimation(View source,
            int startX, int startY, int startWidth, int startHeight) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = ANIM_SCALE_UP;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.mStartWidth = startWidth;
        opts.mStartHeight = startHeight;
        return opts;
    }

    /**
     * Create an ActivityOptions specifying an animation where a thumbnail
     * is scaled from a given position to the new activity window that is
     * being started.
     *
     * <p>If the Intent this is being used with has not set its
     * {@link android.content.Intent#setSourceBounds Intent.setSourceBounds},
     * those bounds will be filled in for you based on the initial
     * thumbnail location and size provided here.
     *
     * @param source The View that this thumbnail is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the initial thumbnail
     * of the animation.
     * @param startX The x starting location of the bitmap, relative to <var>source</var>.
     * @param startY The y starting location of the bitmap, relative to <var>source</var>.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeThumbnailScaleUpAnimation(View source,
            Bitmap thumbnail, int startX, int startY) {
        return makeThumbnailScaleUpAnimation(source, thumbnail, startX, startY, null);
    }

    /**
     * Create an ActivityOptions specifying an animation where a thumbnail
     * is scaled from a given position to the new activity window that is
     * being started.
     *
     * @param source The View that this thumbnail is animating from.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the initial thumbnail
     * of the animation.
     * @param startX The x starting location of the bitmap, relative to <var>source</var>.
     * @param startY The y starting location of the bitmap, relative to <var>source</var>.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    public static ActivityOptions makeThumbnailScaleUpAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener) {
        return makeThumbnailAnimation(source, thumbnail, startX, startY, listener, true);
    }

    /**
     * Create an ActivityOptions specifying an animation where an activity window
     * is scaled from a given position to a thumbnail at a specified location.
     *
     * @param source The View that this thumbnail is animating to.  This
     * defines the coordinate space for <var>startX</var> and <var>startY</var>.
     * @param thumbnail The bitmap that will be shown as the final thumbnail
     * of the animation.
     * @param startX The x end location of the bitmap, relative to <var>source</var>.
     * @param startY The y end location of the bitmap, relative to <var>source</var>.
     * @param listener Optional OnAnimationStartedListener to find out when the
     * requested animation has started running.  If for some reason the animation
     * is not executed, the callback will happen immediately.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     * @hide
     */
    public static ActivityOptions makeThumbnailScaleDownAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener) {
        return makeThumbnailAnimation(source, thumbnail, startX, startY, listener, false);
    }

    private static ActivityOptions makeThumbnailAnimation(View source,
            Bitmap thumbnail, int startX, int startY, OnAnimationStartedListener listener,
            boolean scaleUp) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = source.getContext().getPackageName();
        opts.mAnimationType = scaleUp ? ANIM_THUMBNAIL_SCALE_UP : ANIM_THUMBNAIL_SCALE_DOWN;
        opts.mThumbnail = thumbnail;
        int[] pts = new int[2];
        source.getLocationOnScreen(pts);
        opts.mStartX = pts[0] + startX;
        opts.mStartY = pts[1] + startY;
        opts.setOnAnimationStartedListener(source.getHandler(), listener);
        return opts;
    }

    /**
     * Create an ActivityOptions to transition between Activities using cross-Activity scene
     * animations. This method carries the position of one shared element to the started Activity.
     *
     * <p>This requires {@link android.view.Window#FEATURE_CONTENT_TRANSITIONS} to be
     * enabled on the calling Activity to cause an exit transition. The same must be in
     * the called Activity to get an entering transition.</p>
     * @param sharedElement The View to transition to the started Activity. sharedElement must
     *                      have a non-null sharedElementName.
     * @param sharedElementName The shared element name as used in the target Activity. This may
     *                          be null if it has the same name as sharedElement.
     * @return Returns a new ActivityOptions object that you can use to
     *         supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeSceneTransitionAnimation(View sharedElement,
            String sharedElementName) {
        return makeSceneTransitionAnimation(
                new Pair<View, String>(sharedElement, sharedElementName));
    }

    /**
     * Create an ActivityOptions to transition between Activities using cross-Activity scene
     * animations. This method carries the position of multiple shared elements to the started
     * Activity.
     *
     * <p>This requires {@link android.view.Window#FEATURE_CONTENT_TRANSITIONS} to be
     * enabled on the calling Activity to cause an exit transition. The same must be in
     * the called Activity to get an entering transition.</p>
     * @param sharedElements The View to transition to the started Activity along with the
     *                       shared element name as used in the started Activity. The view
     *                       must have a non-null sharedElementName.
     * @return Returns a new ActivityOptions object that you can use to
     *         supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeSceneTransitionAnimation(
            Pair<View, String>... sharedElements) {
        ActivityOptions opts = new ActivityOptions();
        opts.mAnimationType = ANIM_SCENE_TRANSITION;
        opts.mSharedElementNames = new ArrayList<String>();
        opts.mLocalElementNames = new ArrayList<String>();

        if (sharedElements != null) {
            for (Pair<View, String> sharedElement : sharedElements) {
                opts.addSharedElement(sharedElement.first, sharedElement.second);
            }
        }
        return opts;
    }

    private ActivityOptions() {
    }

    /** @hide */
    public ActivityOptions(Bundle opts) {
        mPackageName = opts.getString(KEY_PACKAGE_NAME);
        mAnimationType = opts.getInt(KEY_ANIM_TYPE);
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                mCustomEnterResId = opts.getInt(KEY_ANIM_ENTER_RES_ID, 0);
                mCustomExitResId = opts.getInt(KEY_ANIM_EXIT_RES_ID, 0);
                mAnimationStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_ANIM_START_LISTENER));
                break;

            case ANIM_SCALE_UP:
                mStartX = opts.getInt(KEY_ANIM_START_X, 0);
                mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
                mStartWidth = opts.getInt(KEY_ANIM_START_WIDTH, 0);
                mStartHeight = opts.getInt(KEY_ANIM_START_HEIGHT, 0);
                break;

            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                mThumbnail = (Bitmap)opts.getParcelable(KEY_ANIM_THUMBNAIL);
                mStartX = opts.getInt(KEY_ANIM_START_X, 0);
                mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
                mAnimationStartedListener = IRemoteCallback.Stub.asInterface(
                        opts.getBinder(KEY_ANIM_START_LISTENER));
                break;

            case ANIM_SCENE_TRANSITION:
                mTransitionCompleteListener = opts.getParcelable(KEY_TRANSITION_COMPLETE_LISTENER);
                mSharedElementNames = opts.getStringArrayList(KEY_SHARED_ELEMENT_NAMES);
                mLocalElementNames = opts.getStringArrayList(KEY_LOCAL_ELEMENT_NAMES);
                break;
        }
    }

    /** @hide */
    public String getPackageName() {
        return mPackageName;
    }

    /** @hide */
    public int getAnimationType() {
        return mAnimationType;
    }

    /** @hide */
    public int getCustomEnterResId() {
        return mCustomEnterResId;
    }

    /** @hide */
    public int getCustomExitResId() {
        return mCustomExitResId;
    }

    /** @hide */
    public Bitmap getThumbnail() {
        return mThumbnail;
    }

    /** @hide */
    public int getStartX() {
        return mStartX;
    }

    /** @hide */
    public int getStartY() {
        return mStartY;
    }

    /** @hide */
    public int getStartWidth() {
        return mStartWidth;
    }

    /** @hide */
    public int getStartHeight() {
        return mStartHeight;
    }

    /** @hide */
    public IRemoteCallback getOnAnimationStartListener() {
        return mAnimationStartedListener;
    }

    /** @hide */
    public ArrayList<String> getSharedElementNames() { return mSharedElementNames; }

    /** @hide */
    public ArrayList<String> getLocalElementNames() { return mLocalElementNames; }

    /** @hide */
    public void dispatchSceneTransitionStarted(final ActivityTransitionTarget target,
            ArrayList<String> sharedElementNames) {
        if (mTransitionCompleteListener != null) {
            IRemoteCallback callback = new IRemoteCallback.Stub() {
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    if (data == null) {
                        target.exitTransitionComplete();
                    } else {
                        target.sharedElementTransitionComplete(data);
                    }
                }
            };
            Bundle bundle = new Bundle();
            bundle.putBinder(KEY_TRANSITION_TARGET_LISTENER, callback.asBinder());
            bundle.putStringArrayList(KEY_SHARED_ELEMENT_NAMES, sharedElementNames);
            mTransitionCompleteListener.send(MSG_SET_LISTENER, bundle);
        }
    }

    /** @hide */
    public void dispatchSharedElementsReady() {
        if (mTransitionCompleteListener != null) {
            mTransitionCompleteListener.send(MSG_HIDE_SHARED_ELEMENTS, null);
        }
    }

    /** @hide */
    public void dispatchPrepareRestore() {
        if (mTransitionCompleteListener != null) {
            mTransitionCompleteListener.send(MSG_PREPARE_RESTORE, null);
        }
    }

    /** @hide */
    public void dispatchRestore(Bundle sharedElementsArgs) {
        if (mTransitionCompleteListener != null) {
            mTransitionCompleteListener.send(MSG_RESTORE, sharedElementsArgs);
        }
    }

    /** @hide */
    public void abort() {
        if (mAnimationStartedListener != null) {
            try {
                mAnimationStartedListener.sendResult(null);
            } catch (RemoteException e) {
            }
        }
    }

    /** @hide */
    public static void abort(Bundle options) {
        if (options != null) {
            (new ActivityOptions(options)).abort();
        }
    }

    /**
     * Update the current values in this ActivityOptions from those supplied
     * in <var>otherOptions</var>.  Any values
     * defined in <var>otherOptions</var> replace those in the base options.
     */
    public void update(ActivityOptions otherOptions) {
        if (otherOptions.mPackageName != null) {
            mPackageName = otherOptions.mPackageName;
        }
        mSharedElementNames = null;
        mLocalElementNames = null;
        switch (otherOptions.mAnimationType) {
            case ANIM_CUSTOM:
                mAnimationType = otherOptions.mAnimationType;
                mCustomEnterResId = otherOptions.mCustomEnterResId;
                mCustomExitResId = otherOptions.mCustomExitResId;
                mThumbnail = null;
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
                mAnimationStartedListener = otherOptions.mAnimationStartedListener;
                mTransitionCompleteListener = null;
                break;
            case ANIM_SCALE_UP:
                mAnimationType = otherOptions.mAnimationType;
                mStartX = otherOptions.mStartX;
                mStartY = otherOptions.mStartY;
                mStartWidth = otherOptions.mStartWidth;
                mStartHeight = otherOptions.mStartHeight;
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
                mAnimationStartedListener = null;
                mTransitionCompleteListener = null;
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                mAnimationType = otherOptions.mAnimationType;
                mThumbnail = otherOptions.mThumbnail;
                mStartX = otherOptions.mStartX;
                mStartY = otherOptions.mStartY;
                if (mAnimationStartedListener != null) {
                    try {
                        mAnimationStartedListener.sendResult(null);
                    } catch (RemoteException e) {
                    }
                }
                mAnimationStartedListener = otherOptions.mAnimationStartedListener;
                mTransitionCompleteListener = null;
                break;
            case ANIM_SCENE_TRANSITION:
                mAnimationType = otherOptions.mAnimationType;
                mTransitionCompleteListener = otherOptions.mTransitionCompleteListener;
                mThumbnail = null;
                mAnimationStartedListener = null;
                mSharedElementNames = otherOptions.mSharedElementNames;
                mLocalElementNames = otherOptions.mLocalElementNames;
                break;
        }
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
     * Context.startActivity(Intent, Bundle)} and related methods.
     * Note that the returned Bundle is still owned by the ActivityOptions
     * object; you must not modify it, but can supply it to the startActivity
     * methods that take an options Bundle.
     */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        if (mPackageName != null) {
            b.putString(KEY_PACKAGE_NAME, mPackageName);
        }
        switch (mAnimationType) {
            case ANIM_CUSTOM:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                b.putInt(KEY_ANIM_ENTER_RES_ID, mCustomEnterResId);
                b.putInt(KEY_ANIM_EXIT_RES_ID, mCustomExitResId);
                b.putBinder(KEY_ANIM_START_LISTENER, mAnimationStartedListener
                        != null ? mAnimationStartedListener.asBinder() : null);
                break;
            case ANIM_SCALE_UP:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                b.putInt(KEY_ANIM_START_X, mStartX);
                b.putInt(KEY_ANIM_START_Y, mStartY);
                b.putInt(KEY_ANIM_START_WIDTH, mStartWidth);
                b.putInt(KEY_ANIM_START_HEIGHT, mStartHeight);
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                b.putParcelable(KEY_ANIM_THUMBNAIL, mThumbnail);
                b.putInt(KEY_ANIM_START_X, mStartX);
                b.putInt(KEY_ANIM_START_Y, mStartY);
                b.putBinder(KEY_ANIM_START_LISTENER, mAnimationStartedListener
                        != null ? mAnimationStartedListener.asBinder() : null);
                break;
            case ANIM_SCENE_TRANSITION:
                b.putInt(KEY_ANIM_TYPE, mAnimationType);
                if (mTransitionCompleteListener != null) {
                    b.putParcelable(KEY_TRANSITION_COMPLETE_LISTENER, mTransitionCompleteListener);
                }
                b.putStringArrayList(KEY_SHARED_ELEMENT_NAMES, mSharedElementNames);
                b.putStringArrayList(KEY_LOCAL_ELEMENT_NAMES, mLocalElementNames);
                break;
        }
        return b;
    }

    /**
     * Return the filtered options only meant to be seen by the target activity itself
     * @hide
     */
    public ActivityOptions forTargetActivity() {
        if (mAnimationType == ANIM_SCENE_TRANSITION) {
            final ActivityOptions result = new ActivityOptions();
            result.update(this);
            return result;
        }

        return null;
    }

    /** @hide */
    public void addSharedElements(Map<String, View> sharedElements) {
        for (Map.Entry<String, View> entry : sharedElements.entrySet()) {
            addSharedElement(entry.getValue(), entry.getKey());
        }
    }

    /** @hide */
    public void updateSceneTransitionAnimation(Transition exitTransition,
            Transition sharedElementTransition, SharedElementSource sharedElementSource) {
        mTransitionCompleteListener = new ExitTransitionListener(exitTransition,
                sharedElementTransition, sharedElementSource);
    }

    private void addSharedElement(View view, String name) {
        String sharedElementName = view.getSharedElementName();
        if (name == null) {
            name = sharedElementName;
        }
        mSharedElementNames.add(name);
        mLocalElementNames.add(sharedElementName);
    }

    /** @hide */
    public interface SharedElementSource {
        Bundle getSharedElementExitState();
        void acceptedSharedElements(ArrayList<String> sharedElementNames);
        void hideSharedElements();
        void restore(Bundle sharedElementState);
        void prepareForRestore();
    }

    private static class ExitTransitionListener extends ResultReceiver
            implements Transition.TransitionListener {
        private boolean mSharedElementNotified;
        private IRemoteCallback mTransitionCompleteCallback;
        private boolean mExitComplete;
        private boolean mSharedElementComplete;
        private SharedElementSource mSharedElementSource;

        public ExitTransitionListener(Transition exitTransition, Transition sharedElementTransition,
                SharedElementSource sharedElementSource) {
            super(null);
            mSharedElementSource = sharedElementSource;
            exitTransition.addListener(this);
            sharedElementTransition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    mSharedElementComplete = true;
                    notifySharedElement();
                    transition.removeListener(this);
                }
            });
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            switch (resultCode) {
                case MSG_SET_LISTENER:
                    IBinder listener = resultData.getBinder(KEY_TRANSITION_TARGET_LISTENER);
                    mTransitionCompleteCallback = IRemoteCallback.Stub.asInterface(listener);
                    ArrayList<String> sharedElementNames
                            = resultData.getStringArrayList(KEY_SHARED_ELEMENT_NAMES);
                    mSharedElementSource.acceptedSharedElements(sharedElementNames);
                    notifySharedElement();
                    notifyExit();
                    break;
                case MSG_HIDE_SHARED_ELEMENTS:
                    mSharedElementSource.hideSharedElements();
                    break;
                case MSG_PREPARE_RESTORE:
                    mSharedElementSource.prepareForRestore();
                    break;
                case MSG_RESTORE:
                    mSharedElementSource.restore(resultData);
                    break;
            }
        }

        @Override
        public void onTransitionStart(Transition transition) {
        }

        @Override
        public void onTransitionEnd(Transition transition) {
            mExitComplete = true;
            notifyExit();
            transition.removeListener(this);
        }

        @Override
        public void onTransitionCancel(Transition transition) {
            onTransitionEnd(transition);
        }

        @Override
        public void onTransitionPause(Transition transition) {
        }

        @Override
        public void onTransitionResume(Transition transition) {
        }

        private void notifySharedElement() {
            if (!mSharedElementNotified && mSharedElementComplete
                    && mTransitionCompleteCallback != null) {
                mSharedElementNotified = true;
                try {
                    Bundle sharedElementState = mSharedElementSource.getSharedElementExitState();
                    mTransitionCompleteCallback.sendResult(sharedElementState);
                } catch (RemoteException e) {
                    Log.w(TAG, "Couldn't notify that the transition ended", e);
                }
            }
        }

        private void notifyExit() {
            if (mExitComplete && mTransitionCompleteCallback != null) {
                try {
                    mTransitionCompleteCallback.sendResult(null);
                } catch (RemoteException e) {
                    Log.w(TAG, "Couldn't notify that the transition ended", e);
                }
            }
        }
    }
}
