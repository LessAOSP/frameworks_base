/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.graphics.drawable;

import com.android.internal.R;

import android.annotation.NonNull;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.graphics.*;
import android.graphics.PorterDuff.Mode;
import android.util.AttributeSet;
import android.util.Log;

import java.io.IOException;

/**
 * A Drawable that insets another Drawable by a specified distance.
 * This is used when a View needs a background that is smaller than
 * the View's actual bounds.
 *
 * <p>It can be defined in an XML file with the <code>&lt;inset></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#InsetDrawable_visible
 * @attr ref android.R.styleable#InsetDrawable_drawable
 * @attr ref android.R.styleable#InsetDrawable_insetLeft
 * @attr ref android.R.styleable#InsetDrawable_insetRight
 * @attr ref android.R.styleable#InsetDrawable_insetTop
 * @attr ref android.R.styleable#InsetDrawable_insetBottom
 */
public class InsetDrawable extends Drawable implements Drawable.Callback {
    private static final String LOG_TAG = "InsetDrawable";

    private final Rect mTmpRect = new Rect();

    private InsetState mInsetState;
    private boolean mMutated;

    /*package*/ InsetDrawable() {
        this(null, null);
    }

    public InsetDrawable(Drawable drawable, int inset) {
        this(drawable, inset, inset, inset, inset);
    }

    public InsetDrawable(Drawable drawable, int insetLeft, int insetTop,
                         int insetRight, int insetBottom) {
        this(null, null);

        mInsetState.mDrawable = drawable;
        mInsetState.mInsetLeft = insetLeft;
        mInsetState.mInsetTop = insetTop;
        mInsetState.mInsetRight = insetRight;
        mInsetState.mInsetBottom = insetBottom;

        if (drawable != null) {
            drawable.setCallback(this);
        }
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = r.obtainAttributes(attrs, R.styleable.InsetDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.InsetDrawable_visible);
        updateStateFromTypedArray(a);
        a.recycle();

        // Load inner XML elements.
        if (mInsetState.mDrawable == null) {
            int type;
            while ((type=parser.next()) == XmlPullParser.TEXT) {
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException(
                        parser.getPositionDescription()
                                + ": <inset> tag requires a 'drawable' attribute or "
                                + "child tag defining a drawable");
            }
            final Drawable dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
            mInsetState.mDrawable = dr;
            dr.setCallback(this);
        }

        // Verify state.
        if (mInsetState.mDrawable == null) {
            Log.w(LOG_TAG, "No drawable specified for <inset>");
        }
    }

    private void updateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final InsetState state = mInsetState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        final Drawable dr = a.getDrawable(R.styleable.InsetDrawable_drawable);
        if (dr != null) {
            state.mDrawable = dr;
            dr.setCallback(this);
        }

        state.mInsetLeft = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetLeft, state.mInsetLeft);
        state.mInsetTop = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetTop, state.mInsetTop);
        state.mInsetRight = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetRight, state.mInsetRight);
        state.mInsetBottom = a.getDimensionPixelOffset(
                R.styleable.InsetDrawable_insetBottom, state.mInsetBottom);
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final InsetState state = mInsetState;
        if (state == null || state.mThemeAttrs == null) {
            return;
        }

        final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.InsetDrawable);
        try {
            updateStateFromTypedArray(a);
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } finally {
            a.recycle();
        }
    }

    @Override
    public boolean canApplyTheme() {
        return mInsetState != null && mInsetState.mThemeAttrs != null;
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateDrawable(this);
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.scheduleDrawable(this, what, when);
        }
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.unscheduleDrawable(this, what);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        mInsetState.mDrawable.draw(canvas);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mInsetState.mChangingConfigurations
                | mInsetState.mDrawable.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(Rect padding) {
        boolean pad = mInsetState.mDrawable.getPadding(padding);

        padding.left += mInsetState.mInsetLeft;
        padding.right += mInsetState.mInsetRight;
        padding.top += mInsetState.mInsetTop;
        padding.bottom += mInsetState.mInsetBottom;

        if (pad || (mInsetState.mInsetLeft | mInsetState.mInsetRight |
                    mInsetState.mInsetTop | mInsetState.mInsetBottom) != 0) {
            return true;
        } else {
            return false;
        }
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        final Insets contentInsets = super.getOpticalInsets();
        return Insets.of(contentInsets.left + mInsetState.mInsetLeft,
                contentInsets.top + mInsetState.mInsetTop,
                contentInsets.right + mInsetState.mInsetRight,
                contentInsets.bottom + mInsetState.mInsetBottom);
    }

    @Override
    public void setHotspot(float x, float y) {
        mInsetState.mDrawable.setHotspot(x, y);
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mInsetState.mDrawable.setHotspotBounds(left, top, right, bottom);
    }

    /** @hide */
    @Override
    public void getHotspotBounds(Rect outRect) {
        mInsetState.mDrawable.getHotspotBounds(outRect);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mInsetState.mDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public void setAlpha(int alpha) {
        mInsetState.mDrawable.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mInsetState.mDrawable.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mInsetState.mDrawable.setColorFilter(cf);
    }

    @Override
    public void setTint(ColorStateList tint, Mode tintMode) {
        mInsetState.mDrawable.setTint(tint, tintMode);
    }

    /** {@hide} */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        mInsetState.mDrawable.setLayoutDirection(layoutDirection);
    }

    @Override
    public int getOpacity() {
        return mInsetState.mDrawable.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return mInsetState.mDrawable.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = mInsetState.mDrawable.setState(state);
        onBoundsChange(getBounds());
        return changed;
    }

    @Override
    protected boolean onLevelChange(int level) {
        return mInsetState.mDrawable.setLevel(level);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final Rect r = mTmpRect;
        r.set(bounds);

        r.left += mInsetState.mInsetLeft;
        r.top += mInsetState.mInsetTop;
        r.right -= mInsetState.mInsetRight;
        r.bottom -= mInsetState.mInsetBottom;

        mInsetState.mDrawable.setBounds(r.left, r.top, r.right, r.bottom);
    }

    @Override
    public int getIntrinsicWidth() {
        return mInsetState.mDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mInsetState.mDrawable.getIntrinsicHeight();
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        mInsetState.mDrawable.getOutline(outline);
    }

    @Override
    public ConstantState getConstantState() {
        if (mInsetState.canConstantState()) {
            mInsetState.mChangingConfigurations = getChangingConfigurations();
            return mInsetState;
        }
        return null;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mInsetState.mDrawable.mutate();
            mMutated = true;
        }
        return this;
    }

    /**
     * Returns the drawable wrapped by this InsetDrawable. May be null.
     */
    public Drawable getDrawable() {
        return mInsetState.mDrawable;
    }

    final static class InsetState extends ConstantState {
        int[] mThemeAttrs;
        int mChangingConfigurations;

        Drawable mDrawable;

        int mInsetLeft;
        int mInsetTop;
        int mInsetRight;
        int mInsetBottom;

        boolean mCheckedConstantState;
        boolean mCanConstantState;

        InsetState(InsetState orig, InsetDrawable owner, Resources res) {
            if (orig != null) {
                mThemeAttrs = orig.mThemeAttrs;
                mChangingConfigurations = orig.mChangingConfigurations;
                if (res != null) {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable(res);
                } else {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable();
                }
                mDrawable.setCallback(owner);
                mDrawable.setLayoutDirection(orig.mDrawable.getLayoutDirection());
                mInsetLeft = orig.mInsetLeft;
                mInsetTop = orig.mInsetTop;
                mInsetRight = orig.mInsetRight;
                mInsetBottom = orig.mInsetBottom;
                mCheckedConstantState = mCanConstantState = true;
            }
        }

        @Override
        public Drawable newDrawable() {
            return new InsetDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new InsetDrawable(this, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        boolean canConstantState() {
            if (!mCheckedConstantState) {
                mCanConstantState = mDrawable.getConstantState() != null;
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }
    }

    private InsetDrawable(InsetState state, Resources res) {
        mInsetState = new InsetState(state, this, res);
    }
}

