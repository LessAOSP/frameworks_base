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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.internal.R;
import com.android.internal.policy.impl.keyguard.KeyguardActivityLauncher.CameraWidgetInfo;

public class CameraWidgetFrame extends KeyguardWidgetFrame implements View.OnClickListener {
    private static final String TAG = CameraWidgetFrame.class.getSimpleName();
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final int WIDGET_ANIMATION_DURATION = 250;
    private static final int WIDGET_WAIT_DURATION = 650;

    interface Callbacks {
        void onLaunchingCamera();
        void onCameraLaunched();
    }

    private final Handler mHandler = new Handler();
    private final KeyguardActivityLauncher mActivityLauncher;
    private final Callbacks mCallbacks;
    private final WindowManager mWindowManager;
    private final Point mRenderedSize = new Point();
    private final int[] mScreenLocation = new int[2];

    private View mWidgetView;
    private long mLaunchCameraStart;
    private boolean mActive;
    private boolean mTransitioning;
    private boolean mDown;
    private boolean mWindowFocused;

    private final Runnable mLaunchCameraRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mTransitioning)
                return;
            mLaunchCameraStart = SystemClock.uptimeMillis();
            if (DEBUG) Log.d(TAG, "Launching camera at " + mLaunchCameraStart);
            mActivityLauncher.launchCamera();
        }};

    private final Runnable mRenderRunnable = new Runnable() {
        @Override
        public void run() {
            render();
        }};

    private final Runnable mTransitionToCameraRunnable = new Runnable() {
        @Override
        public void run() {
            transitionToCamera();
        }};

    private CameraWidgetFrame(Context context, Callbacks callbacks,
            KeyguardActivityLauncher activityLauncher) {
        super(context);

        mCallbacks = callbacks;
        mActivityLauncher = activityLauncher;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static CameraWidgetFrame create(Context context, Callbacks callbacks,
            KeyguardActivityLauncher launcher) {
        if (context == null || callbacks == null || launcher == null)
            return null;

        CameraWidgetInfo widgetInfo = launcher.getCameraWidgetInfo();
        if (widgetInfo == null)
            return null;
        View widgetView = widgetInfo.layoutId > 0 ?
                inflateWidgetView(context, widgetInfo) :
                inflateGenericWidgetView(context);
        if (widgetView == null)
            return null;

        ImageView preview = new ImageView(context);
        preview.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        preview.setScaleType(ScaleType.FIT_CENTER);
        preview.setContentDescription(preview.getContext().getString(
                R.string.keyguard_accessibility_camera));
        CameraWidgetFrame cameraWidgetFrame = new CameraWidgetFrame(context, callbacks, launcher);
        cameraWidgetFrame.addView(preview);
        cameraWidgetFrame.mWidgetView = widgetView;
        preview.setOnClickListener(cameraWidgetFrame);
        return cameraWidgetFrame;
    }

    private static View inflateWidgetView(Context context, CameraWidgetInfo widgetInfo) {
        if (DEBUG) Log.d(TAG, "inflateWidgetView: " + widgetInfo.contextPackage);
        View widgetView = null;
        Exception exception = null;
        try {
            Context cameraContext = context.createPackageContext(
                    widgetInfo.contextPackage, Context.CONTEXT_RESTRICTED);
            LayoutInflater cameraInflater = (LayoutInflater)
                    cameraContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            cameraInflater = cameraInflater.cloneInContext(cameraContext);
            widgetView = cameraInflater.inflate(widgetInfo.layoutId, null, false);
        } catch (NameNotFoundException e) {
            exception = e;
        } catch (RuntimeException e) {
            exception = e;
        }
        if (exception != null) {
            Log.w(TAG, "Error creating camera widget view", exception);
        }
        return widgetView;
    }

    private static View inflateGenericWidgetView(Context context) {
        if (DEBUG) Log.d(TAG, "inflateGenericWidgetView");
        ImageView iv = new ImageView(context);
        iv.setImageResource(com.android.internal.R.drawable.ic_lockscreen_camera);
        iv.setScaleType(ScaleType.CENTER);
        iv.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return iv;
    }

    public void render() {
        try {
            int width = getRootView().getWidth();
            int height = getRootView().getHeight();
            if (mRenderedSize.x == width && mRenderedSize.y == height) {
                if (DEBUG) Log.d(TAG, String.format("already rendered at size=%sx%s",
                        width, height));
                return;
            }
            if (width == 0 || height == 0) {
                return;
            }
            if (DEBUG) Log.d(TAG, String.format("render size=%sx%s instance=%s at %s",
                    width, height,
                    Integer.toHexString(hashCode()),
                    SystemClock.uptimeMillis()));

            Bitmap offscreen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(offscreen);
            mWidgetView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            mWidgetView.layout(0, 0, width, height);
            mWidgetView.draw(c);
            ((ImageView)getChildAt(0)).setImageBitmap(offscreen);
            mRenderedSize.set(width, height);
        } catch (Throwable t) {
            Log.w(TAG, "Error rendering camera widget", t);
            removeAllViews();
            View genericView = inflateGenericWidgetView(mContext);
            addView(genericView);
        }
    }

    private void transitionToCamera() {
        if (mTransitioning || mDown) return;

        mTransitioning = true;

        final View child = getChildAt(0);
        final View root = getRootView();

        final int startWidth = child.getWidth();
        final int startHeight = child.getHeight();

        final int finishWidth = root.getWidth();
        final int finishHeight = root.getHeight();

        final float scaleX = (float) finishWidth / startWidth;
        final float scaleY = (float) finishHeight / startHeight;
        final float scale = Math.round( Math.max(scaleX, scaleY) * 100) / 100f;

        final int[] loc = new int[2];
        root.getLocationInWindow(loc);
        final int finishCenter = loc[1] + finishHeight / 2;

        child.getLocationInWindow(loc);
        final int startCenter = loc[1] + startHeight / 2;

        if (DEBUG) Log.d(TAG, String.format("Transitioning to camera. " +
                "(start=%sx%s, finish=%sx%s, scale=%s,%s, startCenter=%s, finishCenter=%s)",
                startWidth, startHeight,
                finishWidth, finishHeight,
                scaleX, scaleY,
                startCenter, finishCenter));

        enableWindowExitAnimation(false);
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationY(finishCenter - startCenter)
            .setDuration(WIDGET_ANIMATION_DURATION)
            .withEndAction(mLaunchCameraRunnable)
            .start();

        mCallbacks.onLaunchingCamera();
    }

    @Override
    public void onClick(View v) {
        if (DEBUG) Log.d(TAG, "clicked");
        if (mTransitioning) return;
        if (mActive) {
            cancelTransitionToCamera();
            transitionToCamera();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        mWindowFocused = hasWindowFocus;
        if (DEBUG) Log.d(TAG, "onWindowFocusChanged: " + hasWindowFocus);
        if (!hasWindowFocus) {
            mTransitioning = false;
            if (mLaunchCameraStart > 0) {
                long launchTime = SystemClock.uptimeMillis() - mLaunchCameraStart;
                if (DEBUG) Log.d(TAG, String.format("Camera took %sms to launch", launchTime));
                mLaunchCameraStart = 0;
                onCameraLaunched();
            }
        }
    }

    @Override
    public void onActive(boolean isActive) {
        mActive = isActive;
        if (mActive) {
            rescheduleTransitionToCamera();
        } else {
            reset();
        }
    }

    @Override
    public boolean onUserInteraction(MotionEvent event) {
        if (!mWindowFocused) {
            if (DEBUG) Log.d(TAG, "onUserInteraction eaten: !mWindowFocused");
            return true;
        }
        if (mTransitioning) {
            if (DEBUG) Log.d(TAG, "onUserInteraction eaten: mTransitioning");
            return true;
        }

        getLocationOnScreen(mScreenLocation);
        int rawBottom = mScreenLocation[1] + getHeight();
        if (event.getRawY() > rawBottom) {
            if (DEBUG) Log.d(TAG, "onUserInteraction eaten: below widget");
            return true;
        }

        int action = event.getAction();
        mDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
        if (mActive) {
            rescheduleTransitionToCamera();
        }
        if (DEBUG) Log.d(TAG, "onUserInteraction observed, not eaten");
        return false;
    }

    @Override
    protected void onFocusLost() {
        if (DEBUG) Log.d(TAG, "onFocusLost");
        cancelTransitionToCamera();
        super.onFocusLost();
    }

    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOff");
        reset();
    }

    private void rescheduleTransitionToCamera() {
        if (DEBUG) Log.d(TAG, "rescheduleTransitionToCamera at " + SystemClock.uptimeMillis());
        mHandler.removeCallbacks(mTransitionToCameraRunnable);
        mHandler.postDelayed(mTransitionToCameraRunnable, WIDGET_WAIT_DURATION);
    }

    private void cancelTransitionToCamera() {
        if (DEBUG) Log.d(TAG, "cancelTransitionToCamera at " + SystemClock.uptimeMillis());
        mHandler.removeCallbacks(mTransitionToCameraRunnable);
    }

    private void onCameraLaunched() {
        mCallbacks.onCameraLaunched();
        reset();
    }

    private void reset() {
        if (DEBUG) Log.d(TAG, "reset");
        mLaunchCameraStart = 0;
        mTransitioning = false;
        mDown = false;
        cancelTransitionToCamera();
        animate().cancel();
        setScaleX(1);
        setScaleY(1);
        setTranslationY(0);
        enableWindowExitAnimation(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Log.d(TAG, String.format("onSizeChanged new=%sx%s old=%sx%s at %s",
                w, h, oldw, oldh, SystemClock.uptimeMillis()));
        mHandler.post(mRenderRunnable);
        super.onSizeChanged(w, h, oldw, oldh);
    }

    private void enableWindowExitAnimation(boolean isEnabled) {
        View root = getRootView();
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (!(lp instanceof WindowManager.LayoutParams))
            return;
        WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) lp;
        int newWindowAnimations = isEnabled ? com.android.internal.R.style.Animation_LockScreen : 0;
        if (newWindowAnimations != wlp.windowAnimations) {
            if (DEBUG) Log.d(TAG, "setting windowAnimations to: " + newWindowAnimations);
            wlp.windowAnimations = newWindowAnimations;
            mWindowManager.updateViewLayout(root, wlp);
        }
    }
}
