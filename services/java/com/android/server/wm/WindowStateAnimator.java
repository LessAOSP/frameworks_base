// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.server.wm.WindowManagerService.LayoutFields.CLEAR_ORIENTATION_CHANGE_COMPLETE;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;

/**
 * Keep track of animations and surface operations for a single WindowState.
 **/
class WindowStateAnimator {
    static final boolean DEBUG_VISIBILITY = WindowManagerService.DEBUG_VISIBILITY;
    static final boolean DEBUG_ANIM = WindowManagerService.DEBUG_ANIM;
    static final boolean DEBUG_LAYERS = WindowManagerService.DEBUG_LAYERS;
    static final boolean DEBUG_STARTING_WINDOW = WindowManagerService.DEBUG_STARTING_WINDOW;
    static final boolean SHOW_TRANSACTIONS = WindowManagerService.SHOW_TRANSACTIONS;
    static final boolean SHOW_LIGHT_TRANSACTIONS = WindowManagerService.SHOW_LIGHT_TRANSACTIONS;
    static final boolean SHOW_SURFACE_ALLOC = WindowManagerService.SHOW_SURFACE_ALLOC;
    static final boolean localLOGV = WindowManagerService.localLOGV;
    static final boolean DEBUG_ORIENTATION = WindowManagerService.DEBUG_ORIENTATION;

    static final String TAG = "WindowStateAnimator";

    final WindowManagerService mService;
    final WindowState mWin;
    final WindowState mAttachedWindow;
    final WindowAnimator mAnimator;
    final Session mSession;
    final WindowManagerPolicy mPolicy;
    final Context mContext;

    // Currently running animation.
    boolean mAnimating;
    boolean mLocalAnimating;
    Animation mAnimation;
    boolean mAnimationIsEntrance;
    boolean mHasTransformation;
    boolean mHasLocalTransformation;
    final Transformation mTransformation = new Transformation();
    boolean mWasAnimating;      // Were we animating going into the most recent animation step?
    int mAnimLayer;
    int mLastLayer;

    Surface mSurface;
    Surface mPendingDestroySurface;
    boolean mReportDestroySurface;
    boolean mSurfacePendingDestroy;

    /**
     * Set when we have changed the size of the surface, to know that
     * we must tell them application to resize (and thus redraw itself).
     */
    boolean mSurfaceResized;

    /**
     * Set if the client has asked that the destroy of its surface be delayed
     * until it explicitly says it is okay.
     */
    boolean mSurfaceDestroyDeferred;

    float mShownAlpha = 1;
    float mAlpha = 1;
    float mLastAlpha = 1;

    // Used to save animation distances between the time they are calculated and when they are
    // used.
    int mAnimDw;
    int mAnimDh;
    float mDsDx=1, mDtDx=0, mDsDy=0, mDtDy=1;
    float mLastDsDx=1, mLastDtDx=0, mLastDsDy=0, mLastDtDy=1;

    boolean mHaveMatrix;

    // For debugging, this is the last information given to the surface flinger.
    boolean mSurfaceShown;
    float mSurfaceX, mSurfaceY, mSurfaceW, mSurfaceH;
    int mSurfaceLayer;
    float mSurfaceAlpha;

    // Set to true if, when the window gets displayed, it should perform
    // an enter animation.
    boolean mEnterAnimationPending;

    /** This is set when there is no Surface */
    static final int NO_SURFACE = 0;
    /** This is set after the Surface has been created but before the window has been drawn. During
     * this time the surface is hidden. */
    static final int DRAW_PENDING = 1;
    /** This is set after the window has finished drawing for the first time but before its surface
     * is shown.  The surface will be displayed when the next layout is run. */
    static final int COMMIT_DRAW_PENDING = 2;
    /** This is set during the time after the window's drawing has been committed, and before its
     * surface is actually shown.  It is used to delay showing the surface until all windows in a
     * token are ready to be shown. */
    static final int READY_TO_SHOW = 3;
    /** Set when the window has been shown in the screen the first time. */
    static final int HAS_DRAWN = 4;
    int mDrawState;

    /** Was this window last hidden? */
    boolean mLastHidden;

    public WindowStateAnimator(final WindowManagerService service, final WindowState win,
                               final WindowState attachedWindow) {
        mService = service;
        mWin = win;
        mAttachedWindow = attachedWindow;
        mAnimator = mService.mAnimator;
        mSession = win.mSession;
        mPolicy = mService.mPolicy;
        mContext = mService.mContext;
    }

    public void setAnimation(Animation anim) {
        if (localLOGV) Slog.v(
            TAG, "Setting animation in " + this + ": " + anim);
        mAnimating = false;
        mLocalAnimating = false;
        mAnimation = anim;
        mAnimation.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        mAnimation.scaleCurrentDuration(mService.mWindowAnimationScale);
        // Start out animation gone if window is gone, or visible if window is visible.
        mTransformation.clear();
        mTransformation.setAlpha(mLastHidden ? 0 : 1);
        mHasLocalTransformation = true;
    }

    public void clearAnimation() {
        if (mAnimation != null) {
            mAnimating = true;
            mLocalAnimating = false;
            mAnimation.cancel();
            mAnimation = null;
        }
    }

    /** Is the window or its container currently animating? */
    boolean isAnimating() {
        final WindowState attached = mAttachedWindow;
        final AppWindowToken atoken = mWin.mAppToken;
        return mAnimation != null
                || (attached != null && attached.mWinAnimator.mAnimation != null)
                || (atoken != null &&
                        (atoken.animation != null
                                || atoken.inPendingTransaction));
    }

    /** Is this window currently animating? */
    boolean isWindowAnimating() {
        return mAnimation != null;
    }

    void cancelExitAnimationForNextAnimationLocked() {
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
            destroySurfaceLocked();
        }
    }

    private boolean stepAnimation(long currentTime) {
        if ((mAnimation == null) || !mLocalAnimating) {
            return false;
        }
        mTransformation.clear();
        final boolean more = mAnimation.getTransformation(currentTime, mTransformation);
        if (DEBUG_ANIM) Slog.v(
            TAG, "Stepped animation in " + this +
            ": more=" + more + ", xform=" + mTransformation);
        return more;
    }

    // This must be called while inside a transaction.  Returns true if
    // there is more animation to run.
    boolean stepAnimationLocked(long currentTime) {
        // Save the animation state as it was before this step so WindowManagerService can tell if
        // we just started or just stopped animating by comparing mWasAnimating with isAnimating().
        mWasAnimating = mAnimating;
        if (mService.okToDisplay()) {
            // We will run animations as long as the display isn't frozen.

            if (mWin.isDrawnLw() && mAnimation != null) {
                mHasTransformation = true;
                mHasLocalTransformation = true;
                if (!mLocalAnimating) {
                    if (DEBUG_ANIM) Slog.v(
                        TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": ww=" + mWin.mFrame.width() +
                        " wh=" + mWin.mFrame.height() +
                        " dw=" + mAnimDw + " dh=" + mAnimDh +
                        " scale=" + mService.mWindowAnimationScale);
                    mAnimation.initialize(mWin.mFrame.width(), mWin.mFrame.height(),
                            mAnimDw, mAnimDh);
                    mAnimation.setStartTime(currentTime);
                    mLocalAnimating = true;
                    mAnimating = true;
                }
                if ((mAnimation != null) && mLocalAnimating) {
                    if (stepAnimation(currentTime)) {
                        return true;
                    }
                }
                if (DEBUG_ANIM) Slog.v(
                    TAG, "Finished animation in " + this +
                    " @ " + currentTime);
                //WindowManagerService.this.dump();
            }
            mHasLocalTransformation = false;
            if ((!mLocalAnimating || mAnimationIsEntrance) && mWin.mAppToken != null
                    && mWin.mAppToken.animation != null) {
                // When our app token is animating, we kind-of pretend like
                // we are as well.  Note the mLocalAnimating mAnimationIsEntrance
                // part of this check means that we will only do this if
                // our window is not currently exiting, or it is not
                // locally animating itself.  The idea being that one that
                // is exiting and doing a local animation should be removed
                // once that animation is done.
                mAnimating = true;
                mHasTransformation = true;
                mTransformation.clear();
                return false;
            } else if (mHasTransformation) {
                // Little trick to get through the path below to act like
                // we have finished an animation.
                mAnimating = true;
            } else if (isAnimating()) {
                mAnimating = true;
            }
        } else if (mAnimation != null) {
            // If the display is frozen, and there is a pending animation,
            // clear it and make sure we run the cleanup code.
            mAnimating = true;
            mLocalAnimating = true;
            mAnimation.cancel();
            mAnimation = null;
        }

        if (!mAnimating && !mLocalAnimating) {
            return false;
        }

        // Done animating, clean up.
        if (DEBUG_ANIM) Slog.v(
            TAG, "Animation done in " + this + ": exiting=" + mWin.mExiting
            + ", reportedVisible="
            + (mWin.mAppToken != null ? mWin.mAppToken.reportedVisible : false));

        mAnimating = false;
        mLocalAnimating = false;
        if (mAnimation != null) {
            mAnimation.cancel();
            mAnimation = null;
        }
        if (mAnimator.mWindowDetachedWallpaper == mWin) {
            mAnimator.mWindowDetachedWallpaper = null;
        }
        mAnimLayer = mWin.mLayer;
        if (mWin.mIsImWindow) {
            mAnimLayer += mService.mInputMethodAnimLayerAdjustment;
        } else if (mWin.mIsWallpaper) {
            mAnimLayer += mService.mWallpaperAnimLayerAdjustment;
        }
        if (DEBUG_LAYERS) Slog.v(TAG, "Stepping win " + this
                + " anim layer: " + mAnimLayer);
        mHasTransformation = false;
        mHasLocalTransformation = false;
        if (mWin.mPolicyVisibility != mWin.mPolicyVisibilityAfterAnim) {
            if (WindowState.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility changing after anim in " + this + ": "
                        + mWin.mPolicyVisibilityAfterAnim);
            }
            mWin.mPolicyVisibility = mWin.mPolicyVisibilityAfterAnim;
            mService.mLayoutNeeded = true;
            if (!mWin.mPolicyVisibility) {
                if (mService.mCurrentFocus == mWin) {
                    mService.mFocusMayChange = true;
                }
                // Window is no longer visible -- make sure if we were waiting
                // for it to be displayed before enabling the display, that
                // we allow the display to be enabled now.
                mService.enableScreenIfNeededLocked();
            }
        }
        mTransformation.clear();
        if (mDrawState == HAS_DRAWN
                && mWin.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                && mWin.mAppToken != null
                && mWin.mAppToken.firstWindowDrawn
                && mWin.mAppToken.startingData != null) {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Finish starting "
                    + mWin.mToken + ": first real window done animating");
            mService.mFinishedStarting.add(mWin.mAppToken);
            mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
        }

        finishExit();
        mAnimator.mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) mService.debugLayoutRepeats(
                "WindowStateAnimator", mAnimator.mPendingLayoutChanges);

        if (mWin.mAppToken != null) {
            mWin.mAppToken.updateReportedVisibilityLocked();
        }

        return false;
    }

    void finishExit() {
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                TAG, "finishExit in " + this
                + ": exiting=" + mWin.mExiting
                + " remove=" + mWin.mRemoveOnExit
                + " windowAnimating=" + isWindowAnimating());

        final int N = mWin.mChildWindows.size();
        for (int i=0; i<N; i++) {
            mWin.mChildWindows.get(i).mWinAnimator.finishExit();
        }

        if (!mWin.mExiting) {
            return;
        }

        if (isWindowAnimating()) {
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
                TAG, "Exit animation finished in " + this
                + ": remove=" + mWin.mRemoveOnExit);
        if (mSurface != null) {
            mService.mDestroySurface.add(mWin);
            mWin.mDestroying = true;
            if (WindowState.SHOW_TRANSACTIONS) WindowManagerService.logSurface(
                mWin, "HIDE (finishExit)", null);
            mSurfaceShown = false;
            try {
                mSurface.hide();
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error hiding surface in " + this, e);
            }
            mLastHidden = true;
        }
        mWin.mExiting = false;
        if (mWin.mRemoveOnExit) {
            mService.mPendingRemove.add(mWin);
            mWin.mRemoveOnExit = false;
        }
    }

    boolean finishDrawingLocked() {
        if (mDrawState == DRAW_PENDING) {
            if (SHOW_TRANSACTIONS || DEBUG_ORIENTATION) Slog.v(
                TAG, "finishDrawingLocked: " + this + " in " + mSurface);
            mDrawState = COMMIT_DRAW_PENDING;
            return true;
        }
        return false;
    }

    // This must be called while inside a transaction.
    boolean commitFinishDrawingLocked(long currentTime) {
        //Slog.i(TAG, "commitFinishDrawingLocked: " + mSurface);
        if (mDrawState != COMMIT_DRAW_PENDING) {
            return false;
        }
        mDrawState = READY_TO_SHOW;
        final boolean starting = mWin.mAttrs.type == TYPE_APPLICATION_STARTING;
        final AppWindowToken atoken = mWin.mAppToken;
        if (atoken == null || atoken.allDrawn || starting) {
            performShowLocked();
        }
        return true;
    }

    Surface createSurfaceLocked() {
        if (mSurface == null) {
            mReportDestroySurface = false;
            mSurfacePendingDestroy = false;
            if (DEBUG_ORIENTATION) Slog.i(TAG,
                    "createSurface " + this + ": DRAW NOW PENDING");
            mDrawState = DRAW_PENDING;
            if (mWin.mAppToken != null) {
                mWin.mAppToken.allDrawn = false;
            }

            mService.makeWindowFreezingScreenIfNeededLocked(mWin);

            int flags = 0;
            final WindowManager.LayoutParams attrs = mWin.mAttrs;

            if ((attrs.flags&WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                flags |= Surface.SECURE;
            }
            if (WindowState.DEBUG_VISIBILITY) Slog.v(
                TAG, "Creating surface in session "
                + mSession.mSurfaceSession + " window " + this
                + " w=" + mWin.mCompatFrame.width()
                + " h=" + mWin.mCompatFrame.height() + " format="
                + attrs.format + " flags=" + flags);

            int w = mWin.mCompatFrame.width();
            int h = mWin.mCompatFrame.height();
            if ((attrs.flags & LayoutParams.FLAG_SCALED) != 0) {
                // for a scaled surface, we always want the requested
                // size.
                w = mWin.mRequestedWidth;
                h = mWin.mRequestedHeight;
            }

            // Something is wrong and SurfaceFlinger will not like this,
            // try to revert to sane values
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;

            mSurfaceShown = false;
            mSurfaceLayer = 0;
            mSurfaceAlpha = 1;
            mSurfaceX = 0;
            mSurfaceY = 0;
            mSurfaceW = w;
            mSurfaceH = h;
            try {
                final boolean isHwAccelerated = (attrs.flags &
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;
                final int format = isHwAccelerated ? PixelFormat.TRANSLUCENT : attrs.format;
                if (!PixelFormat.formatHasAlpha(attrs.format)) {
                    flags |= Surface.OPAQUE;
                }
                mSurface = new Surface(
                        mSession.mSurfaceSession, mSession.mPid,
                        attrs.getTitle().toString(),
                        0, w, h, format, flags);
                mWin.mHasSurface = true;
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) Slog.i(TAG,
                        "  CREATE SURFACE "
                        + mSurface + " IN SESSION "
                        + mSession.mSurfaceSession
                        + ": pid=" + mSession.mPid + " format="
                        + attrs.format + " flags=0x"
                        + Integer.toHexString(flags)
                        + " / " + this);
            } catch (Surface.OutOfResourcesException e) {
                mWin.mHasSurface = false;
                Slog.w(TAG, "OutOfResourcesException creating surface");
                mService.reclaimSomeSurfaceMemoryLocked(this, "create", true);
                mDrawState = NO_SURFACE;
                return null;
            } catch (Exception e) {
                mWin.mHasSurface = false;
                Slog.e(TAG, "Exception creating surface", e);
                mDrawState = NO_SURFACE;
                return null;
            }

            if (WindowManagerService.localLOGV) Slog.v(
                TAG, "Got surface: " + mSurface
                + ", set left=" + mWin.mFrame.left + " top=" + mWin.mFrame.top
                + ", animLayer=" + mAnimLayer);
            if (SHOW_LIGHT_TRANSACTIONS) {
                Slog.i(TAG, ">>> OPEN TRANSACTION createSurfaceLocked");
                WindowManagerService.logSurface(mWin, "CREATE pos=("
                        + mWin.mFrame.left + "," + mWin.mFrame.top + ") ("
                        + mWin.mCompatFrame.width() + "x" + mWin.mCompatFrame.height()
                        + "), layer=" + mAnimLayer + " HIDE", null);
            }
            Surface.openTransaction();
            try {
                try {
                    mSurfaceX = mWin.mFrame.left + mWin.mXOffset;
                    mSurfaceY = mWin.mFrame.top + mWin.mYOffset;
                    mSurface.setPosition(mSurfaceX, mSurfaceY);
                    mSurfaceLayer = mAnimLayer;
                    mSurface.setLayer(mAnimLayer);
                    mSurfaceShown = false;
                    mSurface.hide();
                    if ((mWin.mAttrs.flags&WindowManager.LayoutParams.FLAG_DITHER) != 0) {
                        if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin, "DITHER", null);
                        mSurface.setFlags(Surface.SURFACE_DITHER, Surface.SURFACE_DITHER);
                    }
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error creating surface in " + w, e);
                    mService.reclaimSomeSurfaceMemoryLocked(this, "create-init", true);
                }
                mLastHidden = true;
            } finally {
                Surface.closeTransaction();
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION createSurfaceLocked");
            }
            if (WindowManagerService.localLOGV) Slog.v(
                    TAG, "Created surface " + this);
        }
        return mSurface;
    }

    void destroySurfaceLocked() {
        if (mWin.mAppToken != null && mWin == mWin.mAppToken.startingWindow) {
            mWin.mAppToken.startingDisplayed = false;
        }

        mDrawState = NO_SURFACE;
        if (mSurface != null) {

            int i = mWin.mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mWin.mChildWindows.get(i);
                c.mAttachedHidden = true;
            }

            if (mReportDestroySurface) {
                mReportDestroySurface = false;
                mSurfacePendingDestroy = true;
                try {
                    mWin.mClient.dispatchGetNewSurface();
                    // We'll really destroy on the next time around.
                    return;
                } catch (RemoteException e) {
                }
            }

            try {
                if (DEBUG_VISIBILITY) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.w(TAG, "Window " + this + " destroying surface "
                            + mSurface + ", session " + mSession, e);
                }
                if (mSurfaceDestroyDeferred) {
                    if (mSurface != null && mPendingDestroySurface != mSurface) {
                        if (mPendingDestroySurface != null) {
                            if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                                RuntimeException e = null;
                                if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                                    e = new RuntimeException();
                                    e.fillInStackTrace();
                                }
                                WindowManagerService.logSurface(mWin, "DESTROY PENDING", e);
                            }
                            mPendingDestroySurface.destroy();
                        }
                        mPendingDestroySurface = mSurface;
                    }
                } else {
                    if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                        RuntimeException e = null;
                        if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                            e = new RuntimeException();
                            e.fillInStackTrace();
                        }
                        WindowManagerService.logSurface(mWin, "DESTROY", e);
                    }
                    mSurface.destroy();
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Exception thrown when destroying Window " + this
                    + " surface " + mSurface + " session " + mSession
                    + ": " + e.toString());
            }

            mSurfaceShown = false;
            mSurface = null;
            mWin.mHasSurface =false;
        }
    }

    void destroyDeferredSurfaceLocked() {
        try {
            if (mPendingDestroySurface != null) {
                if (SHOW_TRANSACTIONS || SHOW_SURFACE_ALLOC) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    WindowManagerService.logSurface(mWin, "DESTROY PENDING", e);
                }
                mPendingDestroySurface.destroy();
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception thrown when destroying Window "
                    + this + " surface " + mPendingDestroySurface
                    + " session " + mSession + ": " + e.toString());
        }
        mSurfaceDestroyDeferred = false;
        mPendingDestroySurface = null;
    }

    void computeShownFrameLocked() {
        final boolean selfTransformation = mHasLocalTransformation;
        Transformation attachedTransformation =
                (mAttachedWindow != null && mAttachedWindow.mWinAnimator.mHasLocalTransformation)
                ? mAttachedWindow.mWinAnimator.mTransformation : null;
        Transformation appTransformation =
                (mWin.mAppToken != null && mWin.mAppToken.hasTransformation)
                ? mWin.mAppToken.transformation : null;

        // Wallpapers are animated based on the "real" window they
        // are currently targeting.
        if (mWin.mAttrs.type == TYPE_WALLPAPER && mService.mLowerWallpaperTarget == null
                && mService.mWallpaperTarget != null) {
            if (mService.mWallpaperTarget.mWinAnimator.mHasLocalTransformation &&
                    mService.mWallpaperTarget.mWinAnimator.mAnimation != null &&
                    !mService.mWallpaperTarget.mWinAnimator.mAnimation.getDetachWallpaper()) {
                attachedTransformation = mService.mWallpaperTarget.mWinAnimator.mTransformation;
                if (WindowManagerService.DEBUG_WALLPAPER && attachedTransformation != null) {
                    Slog.v(TAG, "WP target attached xform: " + attachedTransformation);
                }
            }
            if (mService.mWallpaperTarget.mAppToken != null &&
                    mService.mWallpaperTarget.mAppToken.hasTransformation &&
                    mService.mWallpaperTarget.mAppToken.animation != null &&
                    !mService.mWallpaperTarget.mAppToken.animation.getDetachWallpaper()) {
                appTransformation = mService.mWallpaperTarget.mAppToken.transformation;
                if (WindowManagerService.DEBUG_WALLPAPER && appTransformation != null) {
                    Slog.v(TAG, "WP target app xform: " + appTransformation);
                }
            }
        }

        final boolean screenAnimation = mService.mAnimator.mScreenRotationAnimation != null
                && mService.mAnimator.mScreenRotationAnimation.isAnimating();
        if (selfTransformation || attachedTransformation != null
                || appTransformation != null || screenAnimation) {
            // cache often used attributes locally
            final Rect frame = mWin.mFrame;
            final float tmpFloats[] = mService.mTmpFloats;
            final Matrix tmpMatrix = mWin.mTmpMatrix;

            // Compute the desired transformation.
            if (screenAnimation) {
                // If we are doing a screen animation, the global rotation
                // applied to windows can result in windows that are carefully
                // aligned with each other to slightly separate, allowing you
                // to see what is behind them.  An unsightly mess.  This...
                // thing...  magically makes it call good: scale each window
                // slightly (two pixels larger in each dimension, from the
                // window's center).
                final float w = frame.width();
                final float h = frame.height();
                if (w>=1 && h>=1) {
                    tmpMatrix.setScale(1 + 2/w, 1 + 2/h, w/2, h/2);
                } else {
                    tmpMatrix.reset();
                }
            } else {
                tmpMatrix.reset();
            }
            tmpMatrix.postScale(mWin.mGlobalScale, mWin.mGlobalScale);
            if (selfTransformation) {
                tmpMatrix.postConcat(mTransformation.getMatrix());
            }
            tmpMatrix.postTranslate(frame.left + mWin.mXOffset, frame.top + mWin.mYOffset);
            if (attachedTransformation != null) {
                tmpMatrix.postConcat(attachedTransformation.getMatrix());
            }
            if (appTransformation != null) {
                tmpMatrix.postConcat(appTransformation.getMatrix());
            }
            if (screenAnimation) {
                tmpMatrix.postConcat(
                        mService.mAnimator.mScreenRotationAnimation.getEnterTransformation().getMatrix());
            }

            // "convert" it into SurfaceFlinger's format
            // (a 2x2 matrix + an offset)
            // Here we must not transform the position of the surface
            // since it is already included in the transformation.
            //Slog.i(TAG, "Transform: " + matrix);

            mHaveMatrix = true;
            tmpMatrix.getValues(tmpFloats);
            mDsDx = tmpFloats[Matrix.MSCALE_X];
            mDtDx = tmpFloats[Matrix.MSKEW_Y];
            mDsDy = tmpFloats[Matrix.MSKEW_X];
            mDtDy = tmpFloats[Matrix.MSCALE_Y];
            float x = tmpFloats[Matrix.MTRANS_X];
            float y = tmpFloats[Matrix.MTRANS_Y];
            int w = frame.width();
            int h = frame.height();
            mWin.mShownFrame.set(x, y, x+w, y+h);

            // Now set the alpha...  but because our current hardware
            // can't do alpha transformation on a non-opaque surface,
            // turn it off if we are running an animation that is also
            // transforming since it is more important to have that
            // animation be smooth.
            mShownAlpha = mAlpha;
            if (!mService.mLimitedAlphaCompositing
                    || (!PixelFormat.formatHasAlpha(mWin.mAttrs.format)
                    || (mWin.isIdentityMatrix(mDsDx, mDtDx, mDsDy, mDtDy)
                            && x == frame.left && y == frame.top))) {
                //Slog.i(TAG, "Applying alpha transform");
                if (selfTransformation) {
                    mShownAlpha *= mTransformation.getAlpha();
                }
                if (attachedTransformation != null) {
                    mShownAlpha *= attachedTransformation.getAlpha();
                }
                if (appTransformation != null) {
                    mShownAlpha *= appTransformation.getAlpha();
                }
                if (screenAnimation) {
                    mShownAlpha *=
                        mService.mAnimator.mScreenRotationAnimation.getEnterTransformation().getAlpha();
                }
            } else {
                //Slog.i(TAG, "Not applying alpha transform");
            }

            if (WindowManagerService.localLOGV) Slog.v(
                TAG, "computeShownFrameLocked: Animating " + this +
                ": " + mWin.mShownFrame +
                ", alpha=" + mTransformation.getAlpha() + ", mShownAlpha=" + mShownAlpha);
            return;
        }

        if (WindowManagerService.localLOGV) Slog.v(
            TAG, "computeShownFrameLocked: " + this +
            " not attached, mAlpha=" + mAlpha);
        mWin.mShownFrame.set(mWin.mFrame);
        if (mWin.mXOffset != 0 || mWin.mYOffset != 0) {
            mWin.mShownFrame.offset(mWin.mXOffset, mWin.mYOffset);
        }
        mShownAlpha = mAlpha;
        mHaveMatrix = false;
        mDsDx = mWin.mGlobalScale;
        mDtDx = 0;
        mDsDy = 0;
        mDtDy = mWin.mGlobalScale;
    }

    public void prepareSurfaceLocked(final boolean recoveringMemory) {
        final WindowState w = mWin;
        if (mSurface == null) {
            if (w.mOrientationChanging) {
                if (DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Orientation change skips hidden " + w);
                }
                w.mOrientationChanging = false;
            }
            return;
        }

        boolean displayed = false;

        computeShownFrameLocked();

        int width, height;
        if ((w.mAttrs.flags & LayoutParams.FLAG_SCALED) != 0) {
            // for a scaled surface, we just want to use
            // the requested size.
            width  = w.mRequestedWidth;
            height = w.mRequestedHeight;
        } else {
            width = w.mCompatFrame.width();
            height = w.mCompatFrame.height();
        }

        if (width < 1) {
            width = 1;
        }
        if (height < 1) {
            height = 1;
        }
        final boolean surfaceResized = mSurfaceW != width || mSurfaceH != height;
        if (surfaceResized) {
            mSurfaceW = width;
            mSurfaceH = height;
        }

        if (mSurfaceX != w.mShownFrame.left
                || mSurfaceY != w.mShownFrame.top) {
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "POS " + w.mShownFrame.left
                        + ", " + w.mShownFrame.top, null);
                mSurfaceX = w.mShownFrame.left;
                mSurfaceY = w.mShownFrame.top;
                mSurface.setPosition(w.mShownFrame.left, w.mShownFrame.top);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error positioning surface of " + w
                        + " pos=(" + w.mShownFrame.left
                        + "," + w.mShownFrame.top + ")", e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(this, "position", true);
                }
            }
        }

        if (surfaceResized) {
            try {
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "SIZE " + width + "x" + height, null);
                mSurfaceResized = true;
                mSurface.setSize(width, height);
            } catch (RuntimeException e) {
                // If something goes wrong with the surface (such
                // as running out of memory), don't take down the
                // entire system.
                Slog.e(TAG, "Error resizing surface of " + w
                        + " size=(" + width + "x" + height + ")", e);
                if (!recoveringMemory) {
                    mService.reclaimSomeSurfaceMemoryLocked(this, "size", true);
                }
            }
        }

        if (w.mAttachedHidden || !w.isReadyForDisplay()) {
            if (!mLastHidden) {
                //dump();
                mLastHidden = true;
                if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                        "HIDE (performLayout)", null);
                if (mSurface != null) {
                    mSurfaceShown = false;
                    try {
                        mSurface.hide();
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Exception hiding surface in " + w);
                    }
                }
            }
            // If we are waiting for this window to handle an
            // orientation change, well, it is hidden, so
            // doesn't really matter.  Note that this does
            // introduce a potential glitch if the window
            // becomes unhidden before it has drawn for the
            // new orientation.
            if (w.mOrientationChanging) {
                w.mOrientationChanging = false;
                if (DEBUG_ORIENTATION) Slog.v(TAG,
                        "Orientation change skips hidden " + w);
            }
        } else if (mLastLayer != mAnimLayer
                || mLastAlpha != mShownAlpha
                || mLastDsDx != mDsDx
                || mLastDtDx != mDtDx
                || mLastDsDy != mDsDy
                || mLastDtDy != mDtDy
                || w.mLastHScale != w.mHScale
                || w.mLastVScale != w.mVScale
                || mLastHidden) {
            displayed = true;
            mLastAlpha = mShownAlpha;
            mLastLayer = mAnimLayer;
            mLastDsDx = mDsDx;
            mLastDtDx = mDtDx;
            mLastDsDy = mDsDy;
            mLastDtDy = mDtDy;
            w.mLastHScale = w.mHScale;
            w.mLastVScale = w.mVScale;
            if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                    "alpha=" + mShownAlpha + " layer=" + mAnimLayer
                    + " matrix=[" + (mDsDx*w.mHScale)
                    + "," + (mDtDx*w.mVScale)
                    + "][" + (mDsDy*w.mHScale)
                    + "," + (mDtDy*w.mVScale) + "]", null);
            if (mSurface != null) {
                try {
                    mSurfaceAlpha = mShownAlpha;
                    mSurface.setAlpha(mShownAlpha);
                    mSurfaceLayer = w.mWinAnimator.mAnimLayer;
                    mSurface.setLayer(w.mWinAnimator.mAnimLayer);
                    mSurface.setMatrix(
                        mDsDx*w.mHScale, mDtDx*w.mVScale,
                        mDsDy*w.mHScale, mDtDy*w.mVScale);

                    if (mLastHidden && mDrawState == HAS_DRAWN) {
                        if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(w,
                                "SHOW (performLayout)", null);
                        if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + w
                                + " during relayout");
                        if (showSurfaceRobustlyLocked()) {
                            mLastHidden = false;
                        } else {
                            w.mOrientationChanging = false;
                        }
                    }
                    if (mSurface != null) {
                        w.mToken.hasVisible = true;
                    }
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error updating surface in " + w, e);
                    if (!recoveringMemory) {
                        mService.reclaimSomeSurfaceMemoryLocked(this, "update", true);
                    }
                }
            }
        } else {
            displayed = true;
        }

        if (displayed) {
            if (w.mOrientationChanging) {
                if (!w.isDrawnLw()) {
                    mAnimator.mBulkUpdateParams |= CLEAR_ORIENTATION_CHANGE_COMPLETE;
                    if (DEBUG_ORIENTATION) Slog.v(TAG,
                            "Orientation continue waiting for draw in " + w);
                } else {
                    w.mOrientationChanging = false;
                    if (DEBUG_ORIENTATION) Slog.v(TAG, "Orientation change complete in " + w);
                }
            }
            w.mToken.hasVisible = true;
        }
    }

    void setTransparentRegionHint(final Region region) {
        if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
            ">>> OPEN TRANSACTION setTransparentRegion");
        Surface.openTransaction();
        try {
            if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(mWin,
                    "transparentRegionHint=" + region, null);
            mSurface.setTransparentRegionHint(region);
        } finally {
            Surface.closeTransaction();
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    "<<< CLOSE TRANSACTION setTransparentRegion");
        }
    }

    void setWallpaperOffset(int left, int top) {
        Surface.openTransaction();
        try {
            mSurfaceX = left;
            mSurfaceY = top;
            mSurface.setPosition(left, top);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error positioning surface of " + mWin
                    + " pos=(" + left + "," + top + ")", e);
        }
        Surface.closeTransaction();
    }

    // This must be called while inside a transaction.
    boolean performShowLocked() {
        if (DEBUG_VISIBILITY) {
            RuntimeException e = null;
            if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                e = new RuntimeException();
                e.fillInStackTrace();
            }
            Slog.v(TAG, "performShow on " + this
                    + ": mDrawState=" + mDrawState + " readyForDisplay="
                    + mWin.isReadyForDisplay()
                    + " starting=" + (mWin.mAttrs.type == TYPE_APPLICATION_STARTING), e);
        }
        if (mDrawState == READY_TO_SHOW && mWin.isReadyForDisplay()) {
            if (SHOW_TRANSACTIONS || DEBUG_ORIENTATION)
                WindowManagerService.logSurface(mWin, "SHOW (performShowLocked)", null);
            if (DEBUG_VISIBILITY) Slog.v(TAG, "Showing " + this
                    + " during animation: policyVis=" + mWin.mPolicyVisibility
                    + " attHidden=" + mWin.mAttachedHidden
                    + " tok.hiddenRequested="
                    + (mWin.mAppToken != null ? mWin.mAppToken.hiddenRequested : false)
                    + " tok.hidden="
                    + (mWin.mAppToken != null ? mWin.mAppToken.hidden : false)
                    + " animating=" + mAnimating
                    + " tok animating="
                    + (mWin.mAppToken != null ? mWin.mAppToken.animating : false));
            if (!showSurfaceRobustlyLocked()) {
                return false;
            }

            mService.enableScreenIfNeededLocked();

            applyEnterAnimationLocked();

            mLastAlpha = -1;
            mLastHidden = false;
            mDrawState = HAS_DRAWN;

            int i = mWin.mChildWindows.size();
            while (i > 0) {
                i--;
                WindowState c = mWin.mChildWindows.get(i);
                if (c.mAttachedHidden) {
                    c.mAttachedHidden = false;
                    if (c.mWinAnimator.mSurface != null) {
                        c.mWinAnimator.performShowLocked();
                        // It hadn't been shown, which means layout not
                        // performed on it, so now we want to make sure to
                        // do a layout.  If called from within the transaction
                        // loop, this will cause it to restart with a new
                        // layout.
                        mService.mLayoutNeeded = true;
                    }
                }
            }

            if (mWin.mAttrs.type != TYPE_APPLICATION_STARTING
                    && mWin.mAppToken != null) {
                mWin.mAppToken.firstWindowDrawn = true;

                if (mWin.mAppToken.startingData != null) {
                    if (WindowManagerService.DEBUG_STARTING_WINDOW ||
                            WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                            "Finish starting " + mWin.mToken
                            + ": first real window is shown, no animation");
                    // If this initial window is animating, stop it -- we
                    // will do an animation to reveal it from behind the
                    // starting window, so there is no need for it to also
                    // be doing its own stuff.
                    if (mAnimation != null) {
                        mAnimation.cancel();
                        mAnimation = null;
                        // Make sure we clean up the animation.
                        mAnimating = true;
                    }
                    mService.mFinishedStarting.add(mWin.mAppToken);
                    mService.mH.sendEmptyMessage(H.FINISHED_STARTING);
                }
                mWin.mAppToken.updateReportedVisibilityLocked();
            }

            return true;
        }

        return false;
    }

    /**
     * Have the surface flinger show a surface, robustly dealing with
     * error conditions.  In particular, if there is not enough memory
     * to show the surface, then we will try to get rid of other surfaces
     * in order to succeed.
     *
     * @return Returns true if the surface was successfully shown.
     */
    boolean showSurfaceRobustlyLocked() {
        try {
            if (mSurface != null) {
                mSurfaceShown = true;
                mSurface.show();
                if (mWin.mTurnOnScreen) {
                    if (DEBUG_VISIBILITY) Slog.v(TAG,
                            "Show surface turning screen on: " + mWin);
                    mWin.mTurnOnScreen = false;
                    mService.mTurnOnScreen = true;
                }
            }
            return true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failure showing surface " + mSurface + " in " + mWin, e);
        }

        mService.reclaimSomeSurfaceMemoryLocked(this, "show", true);

        return false;
    }

    void applyEnterAnimationLocked() {
        final int transit;
        if (mEnterAnimationPending) {
            mEnterAnimationPending = false;
            transit = WindowManagerPolicy.TRANSIT_ENTER;
        } else {
            transit = WindowManagerPolicy.TRANSIT_SHOW;
        }

        applyAnimationLocked(transit, true);
    }

    // TODO(cmautner): Move back to WindowState?
    /**
     * Choose the correct animation and set it to the passed WindowState.
     * @param transit If WindowManagerPolicy.TRANSIT_PREVIEW_DONE and the app window has been drawn
     *      then the animation will be app_starting_exit. Any other value loads the animation from
     *      the switch statement below.
     * @param isEntrance The animation type the last time this was called. Used to keep from
     *      loading the same animation twice.
     * @return true if an animation has been loaded.
     */
    boolean applyAnimationLocked(int transit, boolean isEntrance) {
        if (mLocalAnimating && mAnimationIsEntrance == isEntrance) {
            // If we are trying to apply an animation, but already running
            // an animation of the same type, then just leave that one alone.
            return true;
        }

        // Only apply an animation if the display isn't frozen.  If it is
        // frozen, there is no reason to animate and it can cause strange
        // artifacts when we unfreeze the display if some different animation
        // is running.
        if (mService.okToDisplay()) {
            int anim = mPolicy.selectAnimationLw(mWin, transit);
            int attr = -1;
            Animation a = null;
            if (anim != 0) {
                a = AnimationUtils.loadAnimation(mContext, anim);
            } else {
                switch (transit) {
                    case WindowManagerPolicy.TRANSIT_ENTER:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowEnterAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_EXIT:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowExitAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_SHOW:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowShowAnimation;
                        break;
                    case WindowManagerPolicy.TRANSIT_HIDE:
                        attr = com.android.internal.R.styleable.WindowAnimation_windowHideAnimation;
                        break;
                }
                if (attr >= 0) {
                    a = mService.loadAnimation(mWin.mAttrs, attr);
                }
            }
            if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                    "applyAnimation: win=" + this
                    + " anim=" + anim + " attr=0x" + Integer.toHexString(attr)
                    + " mAnimation=" + mAnimation
                    + " isEntrance=" + isEntrance);
            if (a != null) {
                if (WindowManagerService.DEBUG_ANIM) {
                    RuntimeException e = null;
                    if (!WindowManagerService.HIDE_STACK_CRAWLS) {
                        e = new RuntimeException();
                        e.fillInStackTrace();
                    }
                    Slog.v(TAG, "Loaded animation " + a + " for " + this, e);
                }
                setAnimation(a);
                mAnimationIsEntrance = isEntrance;
            }
        } else {
            clearAnimation();
        }

        return mAnimation != null;
    }

    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mAnimating || mLocalAnimating || mAnimationIsEntrance
                || mAnimation != null) {
            pw.print(prefix); pw.print("mAnimating="); pw.print(mAnimating);
                    pw.print(" mLocalAnimating="); pw.print(mLocalAnimating);
                    pw.print(" mAnimationIsEntrance="); pw.print(mAnimationIsEntrance);
                    pw.print(" mAnimation="); pw.println(mAnimation);
        }
        if (mHasTransformation || mHasLocalTransformation) {
            pw.print(prefix); pw.print("XForm: has=");
                    pw.print(mHasTransformation);
                    pw.print(" hasLocal="); pw.print(mHasLocalTransformation);
                    pw.print(" "); mTransformation.printShortString(pw);
                    pw.println();
        }
        if (mSurface != null) {
            if (dumpAll) {
                pw.print(prefix); pw.print("mSurface="); pw.println(mSurface);
                pw.print(prefix); pw.print("mDrawState="); pw.print(mDrawState);
                pw.print(" mLastHidden="); pw.println(mLastHidden);
            }
            pw.print(prefix); pw.print("Surface: shown="); pw.print(mSurfaceShown);
                    pw.print(" layer="); pw.print(mSurfaceLayer);
                    pw.print(" alpha="); pw.print(mSurfaceAlpha);
                    pw.print(" rect=("); pw.print(mSurfaceX);
                    pw.print(","); pw.print(mSurfaceY);
                    pw.print(") "); pw.print(mSurfaceW);
                    pw.print(" x "); pw.println(mSurfaceH);
        }
        if (mPendingDestroySurface != null) {
            pw.print(prefix); pw.print("mPendingDestroySurface=");
                    pw.println(mPendingDestroySurface);
        }
        if (mSurfaceResized || mSurfaceDestroyDeferred) {
            pw.print(prefix); pw.print("mSurfaceResized="); pw.print(mSurfaceResized);
                    pw.print(" mSurfaceDestroyDeferred="); pw.println(mSurfaceDestroyDeferred);
        }
        if (mShownAlpha != 1 || mAlpha != 1 || mLastAlpha != 1) {
            pw.print(prefix); pw.print("mShownAlpha="); pw.print(mShownAlpha);
                    pw.print(" mAlpha="); pw.print(mAlpha);
                    pw.print(" mLastAlpha="); pw.println(mLastAlpha);
        }
        if (mHaveMatrix || mWin.mGlobalScale != 1) {
            pw.print(prefix); pw.print("mGlobalScale="); pw.print(mWin.mGlobalScale);
                    pw.print(" mDsDx="); pw.print(mDsDx);
                    pw.print(" mDtDx="); pw.print(mDtDx);
                    pw.print(" mDsDy="); pw.print(mDsDy);
                    pw.print(" mDtDy="); pw.println(mDtDy);
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("WindowStateAnimator (");
        sb.append(mWin.mLastTitle + "): ");
        sb.append("mSurface " + mSurface);
        sb.append(", mAnimation " + mAnimation);
        return sb.toString();
    }
}
