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

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.R;

public class KeyguardWidgetFrame extends FrameLayout {
    private final static PorterDuffXfermode sAddBlendMode =
            new PorterDuffXfermode(PorterDuff.Mode.ADD);

    static final float OUTLINE_ALPHA_MULTIPLIER = 0.6f;

    private int mGradientColor;
    private LinearGradient mForegroundGradient;
    private LinearGradient mLeftToRightGradient;
    private LinearGradient mRightToLeftGradient;
    private Paint mGradientPaint = new Paint();
    boolean mLeftToRight = true;

    private float mOverScrollAmount = 0f;
    private final Rect mForegroundRect = new Rect();
    private int mForegroundAlpha = 0;
    private CheckLongPressHelper mLongPressHelper;
    private Animator mFrameFade;
    private boolean mIsSmall = false;

    private float mBackgroundAlpha;
    private float mContentAlpha;
    private float mBackgroundAlphaMultiplier = 1.0f;
    private Drawable mBackgroundDrawable;
    private Rect mBackgroundRect = new Rect();
    private int mSmallWidgetHeight;

    // Multiple callers may try and adjust the alpha of the frame. When a caller shows
    // the outlines, we give that caller control, and nobody else can fade them out.
    // This prevents animation conflicts.
    private Object mBgAlphaController;

    public KeyguardWidgetFrame(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetFrame(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mLongPressHelper = new CheckLongPressHelper(this);

        Resources res = context.getResources();
        // TODO: this padding should really correspond to the padding embedded in the background
        // drawable (ie. outlines).
        int padding = (int) (res.getDisplayMetrics().density * 8);
        setPadding(padding, padding, padding, padding);

        mBackgroundDrawable = res.getDrawable(R.drawable.kg_bouncer_bg_white);
        mGradientColor = res.getColor(com.android.internal.R.color.kg_widget_pager_gradient);
        mGradientPaint.setXfermode(sAddBlendMode);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelLongPress();
    }

    public void setMaxChallengeTop(int top) {
        mSmallWidgetHeight = top - getPaddingTop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Watch for longpress events at this level to make sure
        // users can always pick up this widget
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLongPressHelper.postCheckForLongPress(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                mLongPressHelper.onMove(ev);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLongPressHelper.cancelLongPress();
                break;
        }

        // Otherwise continue letting touch events fall through to children
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Watch for longpress events at this level to make sure
        // users can always pick up this widget
        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                mLongPressHelper.onMove(ev);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLongPressHelper.cancelLongPress();
                break;
        }

        // We return true here to ensure that we will get cancel / up signal
        // even if none of our children have requested touch.
        return true;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        cancelLongPress();
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();
        mLongPressHelper.cancelLongPress();
    }


    private void drawGradientOverlay(Canvas c) {
        mGradientPaint.setShader(mForegroundGradient);
        mGradientPaint.setAlpha(mForegroundAlpha);
        c.drawRect(mForegroundRect, mGradientPaint);
    }

    protected void drawBg(Canvas canvas) {
        if (mBackgroundAlpha > 0.0f) {
            Drawable bg = mBackgroundDrawable;

            bg.setAlpha((int) (mBackgroundAlpha * mBackgroundAlphaMultiplier * 255));
            bg.setBounds(mBackgroundRect);
            bg.draw(canvas);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawBg(canvas);
        super.dispatchDraw(canvas);
        drawGradientOverlay(canvas);
    }

    /**
     * Because this view has fading outlines, it is essential that we enable hardware
     * layers on the content (child) so that updating the alpha of the outlines doesn't
     * result in the content layer being recreated.
     */
    public void enableHardwareLayersForContent() {
        View widget = getContent();
        if (widget != null) {
            widget.setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }

    /**
     * Because this view has fading outlines, it is essential that we enable hardware
     * layers on the content (child) so that updating the alpha of the outlines doesn't
     * result in the content layer being recreated.
     */
    public void disableHardwareLayersForContent() {
        View widget = getContent();
        if (widget != null) {
            widget.setLayerType(LAYER_TYPE_NONE, null);
        }
    }

    public void enableHardwareLayers() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void disableHardwareLayers() {
        setLayerType(LAYER_TYPE_NONE, null);
    }

    public View getContent() {
        return getChildAt(0);
    }

    public int getContentAppWidgetId() {
        View content = getContent();
        if (content instanceof AppWidgetHostView) {
            return ((AppWidgetHostView) content).getAppWidgetId();
        } else if (content instanceof KeyguardStatusView) {
            return ((KeyguardStatusView) content).getAppWidgetId();
        } else {
            return AppWidgetManager.INVALID_APPWIDGET_ID;
        }
    }

    public float getBackgroundAlpha() {
        return mBackgroundAlpha;
    }

    public void setBackgroundAlphaMultiplier(float multiplier) {
        if (Float.compare(mBackgroundAlphaMultiplier, multiplier) != 0) {
            mBackgroundAlphaMultiplier = multiplier;
            invalidate();
        }
    }

    public float getBackgroundAlphaMultiplier() {
        return mBackgroundAlphaMultiplier;
    }

    public void setBackgroundAlpha(float alpha) {
        if (Float.compare(mBackgroundAlpha, alpha) != 0) {
            mBackgroundAlpha = alpha;
            invalidate();
        }
    }

    public float getContentAlpha() {
        return mContentAlpha;
    }

    public void setContentAlpha(float alpha) {
        mContentAlpha = alpha;
        View content = getContent();
        if (content != null) {
            content.setAlpha(alpha);
        }
    }

    /**
     * Set the top location of the challenge.
     *
     * @param top The top of the challenge, in _local_ coordinates, or -1 to indicate the challenge
     *              is down.
     */
    private void setChallengeTop(int top, boolean updateWidgetSize) {
        // The widget starts below the padding, and extends to the top of the challengs.
        int widgetHeight = top - getPaddingTop();
        int frameHeight = top + getPaddingBottom();
        setFrameHeight(frameHeight);
        if (updateWidgetSize) {
            setWidgetHeight(widgetHeight);
        }
    }

    /**
     * Depending on whether the security is up, the widget size needs to change
     * 
     * @param height The height of the widget, -1 for full height
     */
    private void setWidgetHeight(int height) {
        boolean needLayout = false;
        View widget = getContent();
        if (widget != null) {
            LayoutParams lp = (LayoutParams) widget.getLayoutParams();
            if (lp.height != height) {
                needLayout = true;
                lp.height = height;
            }
        }
        if (needLayout) {
            requestLayout();
        }
    }

    public boolean isSmall() {
        return mIsSmall;
    }

    public void adjustFrame(int challengeTop) {
        setChallengeTop(challengeTop, false);
    }

    public void shrinkWidget() {
        mIsSmall = true;
        setChallengeTop(mSmallWidgetHeight, true);
    }

    public void resetSize() {
        mIsSmall = false;
        setFrameHeight(getMeasuredHeight());
        setWidgetHeight(LayoutParams.MATCH_PARENT);
    }

    public void setFrameHeight(int height) {
        height = Math.min(height, getMeasuredHeight());
        mBackgroundRect.set(0, 0, getMeasuredWidth(), height);
        invalidate();
    }

    public void hideFrame(Object caller) {
        fadeFrame(caller, false, 0f, 150);
    }

    public void showFrame(Object caller) {
        fadeFrame(caller, true, OUTLINE_ALPHA_MULTIPLIER, 150);
    }

    public void fadeFrame(Object caller, boolean takeControl, float alpha, int duration) {
        if (takeControl) {
            mBgAlphaController = caller;
        }

        if (mBgAlphaController != caller) return;

        if (mFrameFade != null) {
            mFrameFade.cancel();
            mFrameFade = null;
        }
        PropertyValuesHolder bgAlpha = PropertyValuesHolder.ofFloat("backgroundAlpha", alpha);
        mFrameFade = ObjectAnimator.ofPropertyValuesHolder(this, bgAlpha);
        mFrameFade.setDuration(duration);
        mFrameFade.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mForegroundRect.set(getPaddingLeft(), getPaddingTop(),
                w - getPaddingRight(), h - getPaddingBottom());
        float x0 = mLeftToRight ? 0 : mForegroundRect.width();
        float x1 = mLeftToRight ? mForegroundRect.width(): 0;
        mLeftToRightGradient = new LinearGradient(x0, 0f, x1, 0f,
                mGradientColor, 0, Shader.TileMode.CLAMP);
        mRightToLeftGradient = new LinearGradient(x1, 0f, x0, 0f,
                mGradientColor, 0, Shader.TileMode.CLAMP);
        mBackgroundRect.set(0, 0, w, h);
        invalidate();
    }

    void setOverScrollAmount(float r, boolean left) {
        if (Float.compare(mOverScrollAmount, r) != 0) {
            mOverScrollAmount = r;
            mForegroundGradient = left ? mLeftToRightGradient : mRightToLeftGradient;
            mForegroundAlpha = (int) Math.round((0.85f * r * 255));
            invalidate();
        }
    }

    public void onActive(boolean isActive) {
        // hook for subclasses
    }

    public boolean onUserInteraction(int action) {
        // hook for subclasses
        return false;
    }

    public void onChallengeActive(boolean challengeActive) {
        // hook for subclasses
    }
}
