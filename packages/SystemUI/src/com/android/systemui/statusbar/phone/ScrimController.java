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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.systemui.R;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
public class ScrimController implements ViewTreeObserver.OnPreDrawListener {
    private static final String TAG = "ScrimController";
    private static final boolean DEBUG = false;

    private static final float SCRIM_BEHIND_ALPHA = 0.62f;
    private static final float SCRIM_BEHIND_ALPHA_KEYGUARD = 0.5f;
    private static final float SCRIM_IN_FRONT_ALPHA = 0.75f;
    private static final long ANIMATION_DURATION = 220;
    private static final int TAG_KEY_ANIM = R.id.scrim;

    private static final int NUM_TEASES = 3;
    private static final long TEASE_IN_ANIMATION_DURATION = 1000;
    private static final long TEASE_VISIBLE_DURATION = 2000;
    private static final long TEASE_OUT_ANIMATION_DURATION = 1000;
    private static final long TEASE_INVISIBLE_DURATION = 1000;
    private static final long TEASE_DURATION = TEASE_IN_ANIMATION_DURATION
            + TEASE_VISIBLE_DURATION + TEASE_OUT_ANIMATION_DURATION + TEASE_INVISIBLE_DURATION;
    private static final long PRE_TEASE_DELAY = 1000;

    private final View mScrimBehind;
    private final View mScrimInFront;
    private final UnlockMethodCache mUnlockMethodCache;

    private boolean mKeyguardShowing;
    private float mFraction;

    private boolean mDarkenWhileDragging;
    private boolean mBouncerShowing;
    private boolean mAnimateChange;
    private boolean mUpdatePending;
    private boolean mExpanding;
    private boolean mAnimateKeyguardFadingOut;
    private long mDurationOverride = -1;
    private long mAnimationDelay;
    private Runnable mOnAnimationFinished;
    private boolean mAnimationStarted;
    private boolean mDozing;
    private int mTeasesRemaining;
    private final Interpolator mInterpolator = new DecelerateInterpolator();

    public ScrimController(View scrimBehind, View scrimInFront) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        mUnlockMethodCache = UnlockMethodCache.getInstance(scrimBehind.getContext());
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
        scheduleUpdate();
    }

    public void onTrackingStarted() {
        mExpanding = true;
        mDarkenWhileDragging = !mUnlockMethodCache.isMethodInsecure();
    }

    public void onExpandingFinished() {
        mExpanding = false;
    }

    public void setPanelExpansion(float fraction) {
        if (mFraction != fraction) {
            mFraction = fraction;
            scheduleUpdate();
        }
    }

    public void setBouncerShowing(boolean showing) {
        mBouncerShowing = showing;
        mAnimateChange = !mExpanding;
        scheduleUpdate();
    }

    public void animateKeyguardFadingOut(long delay, long duration, Runnable onAnimationFinished) {
        mAnimateKeyguardFadingOut = true;
        mDurationOverride = duration;
        mAnimationDelay = delay;
        mAnimateChange = true;
        mOnAnimationFinished = onAnimationFinished;
        scheduleUpdate();
    }

    public void animateGoingToFullShade(long delay, long duration) {
        mDurationOverride = duration;
        mAnimationDelay = delay;
        mAnimateChange = true;
        scheduleUpdate();
    }

    public void setDozing(boolean dozing) {
        if (mDozing == dozing) return;
        mDozing = dozing;
        if (!mDozing) {
            cancelTeasing();
        }
        scheduleUpdate();
    }

    /** When dozing, fade screen contents in and out a few times using the front scrim. */
    public long tease() {
        if (!mDozing) return 0;
        mTeasesRemaining = NUM_TEASES;
        mScrimInFront.postDelayed(mTeaseIn, PRE_TEASE_DELAY);
        return PRE_TEASE_DELAY + NUM_TEASES * TEASE_DURATION;
    }

    private void cancelTeasing() {
        mTeasesRemaining = 0;
        mScrimInFront.removeCallbacks(mTeaseIn);
        mScrimInFront.removeCallbacks(mTeaseOut);
    }

    private void scheduleUpdate() {
        if (mUpdatePending) return;

        // Make sure that a frame gets scheduled.
        mScrimBehind.invalidate();
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    private void updateScrims() {
        if (mAnimateKeyguardFadingOut) {
            setScrimInFrontColor(0f);
            setScrimBehindColor(0f);
        }else if (!mKeyguardShowing && !mBouncerShowing) {
            updateScrimNormal();
            setScrimInFrontColor(0);
        } else {
            updateScrimKeyguard();
        }
        mAnimateChange = false;
    }

    private void updateScrimKeyguard() {
        if (mExpanding && mDarkenWhileDragging) {
            float behindFraction = Math.max(0, Math.min(mFraction, 1));
            float fraction = 1 - behindFraction;
            setScrimInFrontColor(fraction * SCRIM_IN_FRONT_ALPHA);
            setScrimBehindColor(behindFraction * SCRIM_BEHIND_ALPHA_KEYGUARD);
        } else if (mBouncerShowing) {
            setScrimInFrontColor(SCRIM_IN_FRONT_ALPHA);
            setScrimBehindColor(0f);
        } else if (mDozing) {
            setScrimInFrontColor(1);
        } else {
            setScrimInFrontColor(0f);
            setScrimBehindColor(SCRIM_BEHIND_ALPHA_KEYGUARD);
        }
    }

    private void updateScrimNormal() {
        float frac = mFraction;
        // let's start this 20% of the way down the screen
        frac = frac * 1.2f - 0.2f;
        if (frac <= 0) {
            setScrimBehindColor(0);
        } else {
            // woo, special effects
            final float k = (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2f))));
            setScrimBehindColor(k * SCRIM_BEHIND_ALPHA);
        }
    }

    private void setScrimBehindColor(float alpha) {
        setScrimColor(mScrimBehind, alpha);
    }

    private void setScrimInFrontColor(float alpha) {
        setScrimColor(mScrimInFront, alpha);
        if (alpha == 0f) {
            mScrimInFront.setClickable(false);
        } else {

            // Eat touch events.
            mScrimInFront.setClickable(true);
        }
    }

    private void setScrimColor(View scrim, float alpha) {
        int color = Color.argb((int) (alpha * 255), 0, 0, 0);
        if (mAnimateChange) {
            startScrimAnimation(scrim, color);
        } else {
            scrim.setBackgroundColor(color);
        }
    }

    private void startScrimAnimation(final View scrim, int targetColor) {
        int current = getBackgroundAlpha(scrim);
        int target = Color.alpha(targetColor);
        if (current == targetColor) {
            return;
        }
        Object runningAnim = scrim.getTag(TAG_KEY_ANIM);
        if (runningAnim instanceof ValueAnimator) {
            ((ValueAnimator) runningAnim).cancel();
        }
        ValueAnimator anim = ValueAnimator.ofInt(current, target);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int value = (int) animation.getAnimatedValue();
                scrim.setBackgroundColor(Color.argb(value, 0, 0, 0));
            }
        });
        anim.setInterpolator(mInterpolator);
        anim.setStartDelay(mAnimationDelay);
        anim.setDuration(mDurationOverride != -1 ? mDurationOverride : ANIMATION_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mOnAnimationFinished != null) {
                    mOnAnimationFinished.run();
                    mOnAnimationFinished = null;
                }
                scrim.setTag(TAG_KEY_ANIM, null);
            }
        });
        anim.start();
        scrim.setTag(TAG_KEY_ANIM, anim);
        mAnimationStarted = true;
    }

    private int getBackgroundAlpha(View scrim) {
        if (scrim.getBackground() instanceof ColorDrawable) {
            ColorDrawable drawable = (ColorDrawable) scrim.getBackground();
            return Color.alpha(drawable.getColor());
        } else {
            return 0;
        }
    }

    @Override
    public boolean onPreDraw() {
        mScrimBehind.getViewTreeObserver().removeOnPreDrawListener(this);
        mUpdatePending = false;
        updateScrims();
        mAnimateKeyguardFadingOut = false;
        mDurationOverride = -1;
        mAnimationDelay = 0;

        // Make sure that we always call the listener even if we didn't start an animation.
        if (!mAnimationStarted && mOnAnimationFinished != null) {
            mOnAnimationFinished.run();
            mOnAnimationFinished = null;
        }
        mAnimationStarted = false;
        return true;
    }

    private final Runnable mTeaseIn = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Tease in, mDozing=" + mDozing
                    + " mTeasesRemaining=" + mTeasesRemaining);
            if (!mDozing || mTeasesRemaining == 0) return;
            mTeasesRemaining--;
            mDurationOverride = TEASE_IN_ANIMATION_DURATION;
            mAnimationDelay = 0;
            mAnimateChange = true;
            mOnAnimationFinished = mTeaseInFinished;
            setScrimColor(mScrimInFront, 0);
        }
    };

    private final Runnable mTeaseInFinished = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Tease in finished, mDozing=" + mDozing);
            if (!mDozing) return;
            mScrimInFront.postDelayed(mTeaseOut, TEASE_VISIBLE_DURATION);
        }
    };

    private final Runnable mTeaseOut = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Tease in finished, mDozing=" + mDozing);
            if (!mDozing) return;
            mDurationOverride = TEASE_OUT_ANIMATION_DURATION;
            mAnimationDelay = 0;
            mAnimateChange = true;
            mOnAnimationFinished = mTeaseOutFinished;
            setScrimColor(mScrimInFront, 1);
        }
    };

    private final Runnable mTeaseOutFinished = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "Tease out finished, mTeasesRemaining=" + mTeasesRemaining);
            if (mTeasesRemaining > 0) {
                mScrimInFront.postDelayed(mTeaseIn, TEASE_INVISIBLE_DURATION);
            }
        }
    };
}
