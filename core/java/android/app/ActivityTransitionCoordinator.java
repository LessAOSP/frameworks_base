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
package android.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Base class for ExitTransitionCoordinator and EnterTransitionCoordinator, classes
 * that manage activity transitions and the communications coordinating them between
 * Activities. The ExitTransitionCoordinator is created in the
 * ActivityOptions#makeSceneTransitionAnimation. The EnterTransitionCoordinator
 * is created by ActivityOptions#createEnterActivityTransition by Activity when the window is
 * attached.
 *
 * Typical startActivity goes like this:
 * 1) ExitTransitionCoordinator created with ActivityOptions#makeSceneTransitionAnimation
 * 2) Activity#startActivity called and that calls startExit() through
 *    ActivityOptions#dispatchStartExit
 *    - Exit transition starts by setting transitioning Views to INVISIBLE
 * 3) Launched Activity starts, creating an EnterTransitionCoordinator.
 *    - The Window is made translucent
 *    - The Window background alpha is set to 0
 *    - The transitioning views are made INVISIBLE
 *    - MSG_SET_LISTENER is sent back to the ExitTransitionCoordinator.
 * 4) The shared element transition completes.
 *    - MSG_TAKE_SHARED_ELEMENTS is sent to the EnterTransitionCoordinator
 * 5) The MSG_TAKE_SHARED_ELEMENTS is received by the EnterTransitionCoordinator.
 *    - Shared elements are made VISIBLE
 *    - Shared elements positions and size are set to match the end state of the calling
 *      Activity.
 *    - The shared element transition is started
 *    - If the window allows overlapping transitions, the views transition is started by setting
 *      the entering Views to VISIBLE and the background alpha is animated to opaque.
 *    - MSG_HIDE_SHARED_ELEMENTS is sent to the ExitTransitionCoordinator
 * 6) MSG_HIDE_SHARED_ELEMENTS is received by the ExitTransitionCoordinator
 *    - The shared elements are made INVISIBLE
 * 7) The exit transition completes in the calling Activity.
 *    - MSG_EXIT_TRANSITION_COMPLETE is sent to the EnterTransitionCoordinator.
 * 8) The MSG_EXIT_TRANSITION_COMPLETE is received by the EnterTransitionCoordinator.
 *    - If the window doesn't allow overlapping enter transitions, the enter transition is started
 *      by setting entering views to VISIBLE and the background is animated to opaque.
 * 9) The background opacity animation completes.
 *    - The window is made opaque
 * 10) The calling Activity gets an onStop() call
 *    - onActivityStopped() is called and all exited Views are made VISIBLE.
 *
 * Typical finishAfterTransition goes like this:
 * 1) finishAfterTransition() creates an ExitTransitionCoordinator and calls startExit()
 *    - The Window start transitioning to Translucent with a new ActivityOptions.
 *    - If no background exists, a black background is substituted
 *    - The shared elements in the scene are matched against those shared elements
 *      that were sent by comparing the names.
 *    - The exit transition is started by setting Views to INVISIBLE.
 * 2) The ActivityOptions is received by the Activity and an EnterTransitionCoordinator is created.
 *    - All transitioning views are made VISIBLE to reverse what was done when onActivityStopped()
 *      was called
 * 3) The Window is made translucent and a callback is received
 *    - The background alpha is animated to 0
 * 4) The background alpha animation completes
 * 5) The shared element transition completes
 *    - After both 4 & 5 complete, MSG_TAKE_SHARED_ELEMENTS is sent to the
 *      EnterTransitionCoordinator
 * 6) MSG_TAKE_SHARED_ELEMENTS is received by EnterTransitionCoordinator
 *    - Shared elements are made VISIBLE
 *    - Shared elements positions and size are set to match the end state of the calling
 *      Activity.
 *    - The shared element transition is started
 *    - If the window allows overlapping transitions, the views transition is started by setting
 *      the entering Views to VISIBLE.
 *    - MSG_HIDE_SHARED_ELEMENTS is sent to the ExitTransitionCoordinator
 * 7) MSG_HIDE_SHARED_ELEMENTS is received by the ExitTransitionCoordinator
 *    - The shared elements are made INVISIBLE
 * 8) The exit transition completes in the finishing Activity.
 *    - MSG_EXIT_TRANSITION_COMPLETE is sent to the EnterTransitionCoordinator.
 *    - finish() is called on the exiting Activity
 * 9) The MSG_EXIT_TRANSITION_COMPLETE is received by the EnterTransitionCoordinator.
 *    - If the window doesn't allow overlapping enter transitions, the enter transition is started
 *      by setting entering views to VISIBLE.
 */
abstract class ActivityTransitionCoordinator extends ResultReceiver {
    private static final String TAG = "ActivityTransitionCoordinator";

    /**
     * For Activity transitions, the called Activity's listener to receive calls
     * when transitions complete.
     */
    static final String KEY_REMOTE_RECEIVER = "android:remoteReceiver";

    protected static final String KEY_SCREEN_X = "shared_element:screenX";
    protected static final String KEY_SCREEN_Y = "shared_element:screenY";
    protected static final String KEY_TRANSLATION_Z = "shared_element:translationZ";
    protected static final String KEY_WIDTH = "shared_element:width";
    protected static final String KEY_HEIGHT = "shared_element:height";
    protected static final String KEY_BITMAP = "shared_element:bitmap";
    protected static final String KEY_SCALE_TYPE = "shared_element:scaleType";
    protected static final String KEY_IMAGE_MATRIX = "shared_element:imageMatrix";

    protected static final ImageView.ScaleType[] SCALE_TYPE_VALUES = ImageView.ScaleType.values();

    /**
     * Sent by the exiting coordinator (either EnterTransitionCoordinator
     * or ExitTransitionCoordinator) after the shared elements have
     * become stationary (shared element transition completes). This tells
     * the remote coordinator to take control of the shared elements and
     * that animations may begin. The remote Activity won't start entering
     * until this message is received, but may wait for
     * MSG_EXIT_TRANSITION_COMPLETE if allowOverlappingTransitions() is true.
     */
    public static final int MSG_SET_REMOTE_RECEIVER = 100;

    /**
     * Sent by the entering coordinator to tell the exiting coordinator
     * to hide its shared elements after it has started its shared
     * element transition. This is temporary until the
     * interlock of shared elements is figured out.
     */
    public static final int MSG_HIDE_SHARED_ELEMENTS = 101;

    /**
     * Sent by the exiting coordinator (either EnterTransitionCoordinator
     * or ExitTransitionCoordinator) after the shared elements have
     * become stationary (shared element transition completes). This tells
     * the remote coordinator to take control of the shared elements and
     * that animations may begin. The remote Activity won't start entering
     * until this message is received, but may wait for
     * MSG_EXIT_TRANSITION_COMPLETE if allowOverlappingTransitions() is true.
     */
    public static final int MSG_TAKE_SHARED_ELEMENTS = 103;

    /**
     * Sent by the exiting coordinator (either
     * EnterTransitionCoordinator or ExitTransitionCoordinator) after
     * the exiting Views have finished leaving the scene. This will
     * be ignored if allowOverlappingTransitions() is true on the
     * remote coordinator. If it is false, it will trigger the enter
     * transition to start.
     */
    public static final int MSG_EXIT_TRANSITION_COMPLETE = 104;

    /**
     * Sent by Activity#startActivity to begin the exit transition.
     */
    public static final int MSG_START_EXIT_TRANSITION = 105;

    /**
     * It took too long for a message from the entering Activity, so we canceled the transition.
     */
    public static final int MSG_CANCEL = 106;

    /**
     * When returning, this is the destination location for the shared element.
     */
    public static final int MSG_SHARED_ELEMENT_DESTINATION = 107;

    /**
     * Send the shared element positions.
     */
    public static final int MSG_SEND_SHARED_ELEMENT_DESTINATION = 108;

    private Window mWindow;
    final protected ArrayList<String> mAllSharedElementNames;
    final protected ArrayList<View> mSharedElements = new ArrayList<View>();
    final protected ArrayList<String> mSharedElementNames = new ArrayList<String>();
    final protected ArrayList<View> mTransitioningViews = new ArrayList<View>();
    final protected SharedElementListener mListener;
    protected ResultReceiver mResultReceiver;
    final private FixedEpicenterCallback mEpicenterCallback = new FixedEpicenterCallback();
    final protected boolean mIsReturning;
    private Runnable mPendingTransition;
    private boolean mIsStartingTransition;

    public ActivityTransitionCoordinator(Window window,
            ArrayList<String> allSharedElementNames,
            SharedElementListener listener, boolean isReturning) {
        super(new Handler());
        mWindow = window;
        mListener = listener;
        mAllSharedElementNames = allSharedElementNames;
        mIsReturning = isReturning;
    }

    protected void viewsReady(ArrayMap<String, View> sharedElements) {
        setSharedElements(sharedElements);
        if (getViewsTransition() != null) {
            getDecor().captureTransitioningViews(mTransitioningViews);
            mTransitioningViews.removeAll(mSharedElements);
        }
        setEpicenter();
    }

    protected void stripOffscreenViews() {
        Rect r = new Rect();
        for (int i = mTransitioningViews.size() - 1; i >= 0; i--) {
            View view = mTransitioningViews.get(i);
            if (!view.getGlobalVisibleRect(r)) {
                mTransitioningViews.remove(i);
            }
        }
    }

    protected Window getWindow() {
        return mWindow;
    }

    protected ViewGroup getDecor() {
        return (mWindow == null) ? null : (ViewGroup) mWindow.getDecorView();
    }

    /**
     * Sets the transition epicenter to the position of the first shared element.
     */
    protected void setEpicenter() {
        View epicenter = null;
        if (!mAllSharedElementNames.isEmpty() && !mSharedElementNames.isEmpty() &&
                mAllSharedElementNames.get(0).equals(mSharedElementNames.get(0))) {
            epicenter = mSharedElements.get(0);
        }
        setEpicenter(epicenter);
    }

    private void setEpicenter(View view) {
        if (view == null) {
            mEpicenterCallback.setEpicenter(null);
        } else {
            Rect epicenter = new Rect();
            view.getBoundsOnScreen(epicenter);
            mEpicenterCallback.setEpicenter(epicenter);
        }
    }

    public ArrayList<String> getAcceptedNames() {
        return mSharedElementNames;
    }

    public ArrayList<String> getMappedNames() {
        ArrayList<String> names = new ArrayList<String>(mSharedElements.size());
        for (int i = 0; i < mSharedElements.size(); i++) {
            names.add(mSharedElements.get(i).getTransitionName());
        }
        return names;
    }

    public ArrayList<View> copyMappedViews() {
        return new ArrayList<View>(mSharedElements);
    }

    public ArrayList<String> getAllSharedElementNames() { return mAllSharedElementNames; }

    protected Transition setTargets(Transition transition, boolean add) {
        if (transition == null || (add &&
                (mTransitioningViews == null || mTransitioningViews.isEmpty()))) {
            return null;
        }
        // Add the targets to a set containing transition so that transition
        // remains unaffected. We don't want to modify the targets of transition itself.
        TransitionSet set = new TransitionSet();
        if (mTransitioningViews != null) {
            for (int i = mTransitioningViews.size() - 1; i >= 0; i--) {
                View view = mTransitioningViews.get(i);
                if (add) {
                    set.addTarget(view);
                } else {
                    set.excludeTarget(view, true);
                }
            }
        }
        // By adding the transition after addTarget, we prevent addTarget from
        // affecting transition.
        set.addTransition(transition);
        return set;
    }

    protected Transition configureTransition(Transition transition,
            boolean includeTransitioningViews) {
        if (transition != null) {
            transition = transition.clone();
            transition.setEpicenterCallback(mEpicenterCallback);
            transition = setTargets(transition, includeTransitioningViews);
        }
        return transition;
    }

    protected static Transition mergeTransitions(Transition transition1, Transition transition2) {
        if (transition1 == null) {
            return transition2;
        } else if (transition2 == null) {
            return transition1;
        } else {
            TransitionSet transitionSet = new TransitionSet();
            transitionSet.addTransition(transition1);
            transitionSet.addTransition(transition2);
            return transitionSet;
        }
    }

    protected ArrayMap<String, View> mapSharedElements(ArrayList<String> accepted,
            ArrayList<View> localViews) {
        ArrayMap<String, View> sharedElements = new ArrayMap<String, View>();
        if (!mAllSharedElementNames.isEmpty()) {
            if (accepted != null) {
                for (int i = 0; i < accepted.size(); i++) {
                    sharedElements.put(accepted.get(i), localViews.get(i));
                }
            } else {
                getDecor().findNamedViews(sharedElements);
            }
        }
        return sharedElements;
    }

    private void setSharedElements(ArrayMap<String, View> sharedElements) {
        sharedElements.retainAll(mAllSharedElementNames);
        mListener.remapSharedElements(mAllSharedElementNames, sharedElements);
        sharedElements.retainAll(mAllSharedElementNames);
        for (int i = 0; i < mAllSharedElementNames.size(); i++) {
            String name = mAllSharedElementNames.get(i);
            View sharedElement = sharedElements.get(name);
            if (sharedElement != null) {
                mSharedElementNames.add(name);
                mSharedElements.add(sharedElement);
            }
        }
    }

    protected void setResultReceiver(ResultReceiver resultReceiver) {
        mResultReceiver = resultReceiver;
    }

    protected abstract Transition getViewsTransition();

    private static void setSharedElementState(View view, String name, Bundle transitionArgs,
            int[] parentLoc) {
        Bundle sharedElementBundle = transitionArgs.getBundle(name);
        if (sharedElementBundle == null) {
            return;
        }

        if (view instanceof ImageView) {
            int scaleTypeInt = sharedElementBundle.getInt(KEY_SCALE_TYPE, -1);
            if (scaleTypeInt >= 0) {
                ImageView imageView = (ImageView) view;
                ImageView.ScaleType scaleType = SCALE_TYPE_VALUES[scaleTypeInt];
                imageView.setScaleType(scaleType);
                if (scaleType == ImageView.ScaleType.MATRIX) {
                    float[] matrixValues = sharedElementBundle.getFloatArray(KEY_IMAGE_MATRIX);
                    Matrix matrix = new Matrix();
                    matrix.setValues(matrixValues);
                    imageView.setImageMatrix(matrix);
                }
            }
        }

        float z = sharedElementBundle.getFloat(KEY_TRANSLATION_Z);
        view.setTranslationZ(z);

        int x = sharedElementBundle.getInt(KEY_SCREEN_X);
        int y = sharedElementBundle.getInt(KEY_SCREEN_Y);
        int width = sharedElementBundle.getInt(KEY_WIDTH);
        int height = sharedElementBundle.getInt(KEY_HEIGHT);

        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);

        int left = x - parentLoc[0];
        int top = y - parentLoc[1];
        int right = left + width;
        int bottom = top + height;
        view.layout(left, top, right, bottom);
    }

    protected ArrayList<SharedElementOriginalState> setSharedElementState(
            Bundle sharedElementState, final ArrayList<View> snapshots) {
        ArrayList<SharedElementOriginalState> originalImageState =
                new ArrayList<SharedElementOriginalState>();
        if (sharedElementState != null) {
            int[] tempLoc = new int[2];
            for (int i = 0; i < mSharedElementNames.size(); i++) {
                View sharedElement = mSharedElements.get(i);
                String name = mSharedElementNames.get(i);
                SharedElementOriginalState originalState = getOldSharedElementState(sharedElement,
                        name, sharedElementState);
                originalImageState.add(originalState);
                View parent = (View) sharedElement.getParent();
                parent.getLocationOnScreen(tempLoc);
                setSharedElementState(sharedElement, name, sharedElementState, tempLoc);
            }
        }
        mListener.setSharedElementStart(mSharedElementNames, mSharedElements, snapshots);

        getDecor().getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                        mListener.setSharedElementEnd(mSharedElementNames, mSharedElements,
                                snapshots);
                        return true;
                    }
                }
        );
        return originalImageState;
    }

    private static SharedElementOriginalState getOldSharedElementState(View view, String name,
            Bundle transitionArgs) {

        SharedElementOriginalState state = new SharedElementOriginalState();
        state.mLeft = view.getLeft();
        state.mTop = view.getTop();
        state.mRight = view.getRight();
        state.mBottom = view.getBottom();
        state.mMeasuredWidth = view.getMeasuredWidth();
        state.mMeasuredHeight = view.getMeasuredHeight();
        if (!(view instanceof ImageView)) {
            return state;
        }
        Bundle bundle = transitionArgs.getBundle(name);
        if (bundle == null) {
            return state;
        }
        int scaleTypeInt = bundle.getInt(KEY_SCALE_TYPE, -1);
        if (scaleTypeInt < 0) {
            return state;
        }

        ImageView imageView = (ImageView) view;
        state.mScaleType = imageView.getScaleType();
        if (state.mScaleType == ImageView.ScaleType.MATRIX) {
            state.mMatrix = new Matrix(imageView.getImageMatrix());
        }
        return state;
    }

    protected ArrayList<View> createSnapshots(Bundle state, Collection<String> names) {
        int numSharedElements = names.size();
        if (numSharedElements == 0) {
            return null;
        }
        ArrayList<View> snapshots = new ArrayList<View>(numSharedElements);
        Context context = getWindow().getContext();
        int[] parentLoc = new int[2];
        getDecor().getLocationOnScreen(parentLoc);
        for (String name: names) {
            Bundle sharedElementBundle = state.getBundle(name);
            if (sharedElementBundle != null) {
                Bitmap bitmap = sharedElementBundle.getParcelable(KEY_BITMAP);
                View snapshot = new View(context);
                Resources resources = getWindow().getContext().getResources();
                if (bitmap != null) {
                    snapshot.setBackground(new BitmapDrawable(resources, bitmap));
                }
                snapshot.setTransitionName(name);
                setSharedElementState(snapshot, name, state, parentLoc);
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    protected static void setOriginalSharedElementState(ArrayList<View> sharedElements,
            ArrayList<SharedElementOriginalState> originalState) {
        for (int i = 0; i < originalState.size(); i++) {
            View view = sharedElements.get(i);
            SharedElementOriginalState state = originalState.get(i);
            if (view instanceof ImageView && state.mScaleType != null) {
                ImageView imageView = (ImageView) view;
                imageView.setScaleType(state.mScaleType);
                if (state.mScaleType == ImageView.ScaleType.MATRIX) {
                  imageView.setImageMatrix(state.mMatrix);
                }
            }
            // origignal widthspec might be AT_MOST,  but it should work for most
            // cases.
            int widthSpec = View.MeasureSpec.makeMeasureSpec(state.mMeasuredWidth,
                    View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(state.mMeasuredHeight,
                    View.MeasureSpec.EXACTLY);
            view.measure(widthSpec, heightSpec);
            view.layout(state.mLeft, state.mTop, state.mRight, state.mBottom);
        }
    }

    protected Bundle captureSharedElementState() {
        Bundle bundle = new Bundle();
        Rect tempBounds = new Rect();
        for (int i = 0; i < mSharedElementNames.size(); i++) {
            View sharedElement = mSharedElements.get(i);
            String name = mSharedElementNames.get(i);
            captureSharedElementState(sharedElement, name, bundle, tempBounds);
        }
        return bundle;
    }

    protected void clearState() {
        // Clear the state so that we can't hold any references accidentally and leak memory.
        mWindow = null;
        mSharedElements.clear();
        mTransitioningViews.clear();
        mResultReceiver = null;
        mPendingTransition = null;
    }

    protected long getFadeDuration() {
        return getWindow().getTransitionBackgroundFadeDuration();
    }

    protected static void setTransitionAlpha(ArrayList<View> views, float alpha) {
        int numSharedElements = views.size();
        for (int i = 0; i < numSharedElements; i++) {
            views.get(i).setTransitionAlpha(alpha);
        }
    }

    /**
     * Captures placement information for Views with a shared element name for
     * Activity Transitions.
     *
     * @param view           The View to capture the placement information for.
     * @param name           The shared element name in the target Activity to apply the placement
     *                       information for.
     * @param transitionArgs Bundle to store shared element placement information.
     * @param tempBounds     A temporary Rect for capturing the current location of views.
     */
    protected static void captureSharedElementState(View view, String name, Bundle transitionArgs,
            Rect tempBounds) {
        Bundle sharedElementBundle = new Bundle();
        tempBounds.set(0, 0, view.getWidth(), view.getHeight());
        view.getBoundsOnScreen(tempBounds);
        sharedElementBundle.putInt(KEY_SCREEN_X, tempBounds.left);
        int width = tempBounds.width();
        sharedElementBundle.putInt(KEY_WIDTH, width);

        sharedElementBundle.putInt(KEY_SCREEN_Y, tempBounds.top);
        int height = tempBounds.height();
        sharedElementBundle.putInt(KEY_HEIGHT, height);

        sharedElementBundle.putFloat(KEY_TRANSLATION_Z, view.getTranslationZ());

        if (width > 0 && height > 0) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            sharedElementBundle.putParcelable(KEY_BITMAP, bitmap);
        }

        if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            int scaleTypeInt = scaleTypeToInt(imageView.getScaleType());
            sharedElementBundle.putInt(KEY_SCALE_TYPE, scaleTypeInt);
            if (imageView.getScaleType() == ImageView.ScaleType.MATRIX) {
                float[] matrix = new float[9];
                imageView.getImageMatrix().getValues(matrix);
                sharedElementBundle.putFloatArray(KEY_IMAGE_MATRIX, matrix);
            }
        }

        transitionArgs.putBundle(name, sharedElementBundle);
    }


    protected void startTransition(Runnable runnable) {
        if (mIsStartingTransition) {
            mPendingTransition = runnable;
        } else {
            mIsStartingTransition = true;
            runnable.run();
        }
    }

    protected void transitionStarted() {
        mIsStartingTransition = false;
    }

    protected class ContinueTransitionListener extends Transition.TransitionListenerAdapter {
        @Override
        public void onTransitionStart(Transition transition) {
            mIsStartingTransition = false;
            Runnable pending = mPendingTransition;
            mPendingTransition = null;
            if (pending != null) {
                startTransition(pending);
            }
        }
    }

    private static int scaleTypeToInt(ImageView.ScaleType scaleType) {
        for (int i = 0; i < SCALE_TYPE_VALUES.length; i++) {
            if (scaleType == SCALE_TYPE_VALUES[i]) {
                return i;
            }
        }
        return -1;
    }

    private static class FixedEpicenterCallback extends Transition.EpicenterCallback {
        private Rect mEpicenter;

        public void setEpicenter(Rect epicenter) { mEpicenter = epicenter; }

        @Override
        public Rect onGetEpicenter(Transition transition) {
            return mEpicenter;
        }
    }

    static class SharedElementOriginalState {
        int mLeft;
        int mTop;
        int mRight;
        int mBottom;
        int mMeasuredWidth;
        int mMeasuredHeight;
        ImageView.ScaleType mScaleType;
        Matrix mMatrix;
    }

}
