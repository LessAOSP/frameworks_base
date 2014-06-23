/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.PorterDuff.Mode;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.PathParser;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Stack;

/**
 * This lets you create a drawable based on an XML vector graphic It can be
 * defined in an XML file with the <code>&lt;vector></code> element.
 * <p/>
 * The vector drawable has the following elements:
 * <p/>
 * <dl>
 * <dt><code>&lt;vector></code></dt>
 * <dd>Used to defined a vector drawable</dd>
 * <dt><code>&lt;size></code></dt>
 * <dd>Used to defined the intrinsic Width Height size of the drawable using
 * <code>android:width</code> and <code>android:height</code></dd>
 * <dt><code>&lt;viewport></code></dt>
 * <dd>Used to defined the size of the virtual canvas the paths are drawn on.
 * The size is defined using the attributes <code>android:viewportHeight</code>
 * <code>android:viewportWidth</code></dd>
 * <dt><code>&lt;group></code></dt>
 * <dd>Defines a group of paths or subgroups, plus transformation information.
 * The transformations are defined in the same coordinates as the viewport.
 * And the transformations are applied in the order of scale, rotate then translate. </dd>
 * <dt><code>android:rotation</code>
 * <dd>The degrees of rotation of the group.</dd></dt>
 * <dt><code>android:pivotX</code>
 * <dd>The X coordinate of the pivot for the scale and rotation of the group</dd></dt>
 * <dt><code>android:pivotY</code>
 * <dd>The Y coordinate of the pivot for the scale and rotation of the group</dd></dt>
 * <dt><code>android:scaleX</code>
 * <dd>The amount of scale on the X Coordinate</dd></dt>
 * <dt><code>android:scaleY</code>
 * <dd>The amount of scale on the Y coordinate</dd></dt>
 * <dt><code>android:translateX</code>
 * <dd>The amount of translation on the X coordinate</dd></dt>
 * <dt><code>android:translateY</code>
 * <dd>The amount of translation on the Y coordinate</dd></dt>
 * <dt><code>&lt;path></code></dt>
 * <dd>Defines paths to be drawn.
 * <dl>
 * <dt><code>android:name</code>
 * <dd>Defines the name of the path.</dd></dt>
 * <dt><code>android:pathData</code>
 * <dd>Defines path string. This is using exactly same format as "d" attribute
 * in the SVG's path data</dd></dt>
 * <dt><code>android:fill</code>
 * <dd>Defines the color to fill the path (none if not present).</dd></dt>
 * <dt><code>android:stroke</code>
 * <dd>Defines the color to draw the path outline (none if not present).</dd>
 * </dt>
 * <dt><code>android:strokeWidth</code>
 * <dd>The width a path stroke</dd></dt>
 * <dt><code>android:strokeOpacity</code>
 * <dd>The opacity of a path stroke</dd></dt>
 * <dt><code>android:fillOpacity</code>
 * <dd>The opacity to fill the path with</dd></dt>
 * <dt><code>android:trimPathStart</code>
 * <dd>The fraction of the path to trim from the start from 0 to 1</dd></dt>
 * <dt><code>android:trimPathEnd</code>
 * <dd>The fraction of the path to trim from the end from 0 to 1</dd></dt>
 * <dt><code>android:trimPathOffset</code>
 * <dd>Shift trim region (allows showed region to include the start and end)
 * from 0 to 1</dd></dt>
 * <dt><code>android:clipToPath</code>
 * <dd>Path will set the clip path</dd></dt>
 * <dt><code>android:strokeLineCap</code>
 * <dd>Sets the linecap for a stroked path: butt, round, square</dd></dt>
 * <dt><code>android:strokeLineJoin</code>
 * <dd>Sets the lineJoin for a stroked path: miter,round,bevel</dd></dt>
 * <dt><code>android:strokeMiterLimit</code>
 * <dd>Sets the Miter limit for a stroked path</dd></dt>
 * </dl>
 * </dd>
 */
public class VectorDrawable extends Drawable {
    private static final String LOGTAG = VectorDrawable.class.getSimpleName();

    private static final String SHAPE_SIZE = "size";
    private static final String SHAPE_VIEWPORT = "viewport";
    private static final String SHAPE_GROUP = "group";
    private static final String SHAPE_PATH = "path";
    private static final String SHAPE_VECTOR = "vector";

    private static final int LINECAP_BUTT = 0;
    private static final int LINECAP_ROUND = 1;
    private static final int LINECAP_SQUARE = 2;

    private static final int LINEJOIN_MITER = 0;
    private static final int LINEJOIN_ROUND = 1;
    private static final int LINEJOIN_BEVEL = 2;

    private static final boolean DBG_VECTOR_DRAWABLE = false;

    private final VectorDrawableState mVectorState;

    private final ArrayMap<String, Object> mVGTargetsMap = new ArrayMap<String, Object>();

    private PorterDuffColorFilter mTintFilter;

    public VectorDrawable() {
        mVectorState = new VectorDrawableState(null);
    }

    private VectorDrawable(VectorDrawableState state, Resources res, Theme theme) {
        if (theme != null && state.canApplyTheme()) {
            // If we need to apply a theme, implicitly mutate.
            mVectorState = new VectorDrawableState(state);
            applyTheme(theme);
        } else {
            mVectorState = state;
        }

        mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
        mVectorState.mVPathRenderer.setColorFilter(mTintFilter);
    }

    Object getTargetByName(String name) {
        return mVGTargetsMap.get(name);
    }

    @Override
    public ConstantState getConstantState() {
        return mVectorState;
    }

    @Override
    public void draw(Canvas canvas) {
        final int saveCount = canvas.save();
        final Rect bounds = getBounds();
        canvas.translate(bounds.left, bounds.top);
        mVectorState.mVPathRenderer.draw(canvas, bounds.width(), bounds.height());
        canvas.restoreToCount(saveCount);
    }

    @Override
    public int getAlpha() {
        return mVectorState.mVPathRenderer.getRootAlpha();
    }

    @Override
    public void setAlpha(int alpha) {
        if (mVectorState.mVPathRenderer.getRootAlpha() != alpha) {
            mVectorState.mVPathRenderer.setRootAlpha(alpha);
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        final VectorDrawableState state = mVectorState;
        if (colorFilter != null) {
            // Color filter overrides tint.
            mTintFilter = null;
        } else if (state.mTint != null && state.mTintMode != null) {
            // Restore the tint filter, if we need one.
            final int color = state.mTint.getColorForState(getState(), Color.TRANSPARENT);
            mTintFilter = new PorterDuffColorFilter(color, state.mTintMode);
            colorFilter = mTintFilter;
        }

        state.mVPathRenderer.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public void setTint(ColorStateList tint, Mode tintMode) {
        final VectorDrawableState state = mVectorState;
        if (state.mTint != tint || state.mTintMode != tintMode) {
            state.mTint = tint;
            state.mTintMode = tintMode;

            mTintFilter = updateTintFilter(mTintFilter, tint, tintMode);
            mVectorState.mVPathRenderer.setColorFilter(mTintFilter);
            invalidateSelf();
        }
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final VectorDrawableState state = mVectorState;
        if (state.mTint != null && state.mTintMode != null) {
            mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
            mVectorState.mVPathRenderer.setColorFilter(mTintFilter);
            return true;
        }
        return false;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) mVectorState.mVPathRenderer.mBaseWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) mVectorState.mVPathRenderer.mBaseHeight;
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final VPathRenderer p = inflateInternal(res, parser, attrs, theme);
        setPathRenderer(p);
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mVectorState != null && mVectorState.canApplyTheme();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final VectorDrawableState state = mVectorState;
        final VPathRenderer path = state.mVPathRenderer;
        if (path != null && path.canApplyTheme()) {
            path.applyTheme(t);
        }
    }

    /** @hide */
    public static VectorDrawable create(Resources resources, int rid) {
        try {
            final XmlPullParser xpp = resources.getXml(rid);
            final AttributeSet attrs = Xml.asAttributeSet(xpp);
            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);

            final VectorDrawable drawable = new VectorDrawable();
            drawable.inflate(resources, xpp, attrs);

            return drawable;
        } catch (XmlPullParserException e) {
            Log.e(LOGTAG, "parser error", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "parser error", e);
        }
        return null;
    }

    private static int applyAlpha(int color, float alpha) {
        int alphaBytes = Color.alpha(color);
        color &= 0x00FFFFFF;
        color |= ((int) (alphaBytes * alpha)) << 24;
        return color;
    }

    private VPathRenderer inflateInternal(Resources res, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        final VPathRenderer pathRenderer = new VPathRenderer();

        boolean noSizeTag = true;
        boolean noViewportTag = true;
        boolean noGroupTag = true;
        boolean noPathTag = true;

        // Use a stack to help to build the group tree.
        // The top of the stack is always the current group.
        final Stack<VGroup> groupStack = new Stack<VGroup>();
        groupStack.push(pathRenderer.mRootGroup);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                final String tagName = parser.getName();
                final VGroup currentGroup = groupStack.peek();

                if (SHAPE_PATH.equals(tagName)) {
                    final VPath path = new VPath();
                    path.inflate(res, attrs, theme);
                    currentGroup.add(path);
                    if (path.getPathName() != null) {
                        mVGTargetsMap.put(path.getPathName(), path);
                    }
                    noPathTag = false;
                } else if (SHAPE_SIZE.equals(tagName)) {
                    pathRenderer.parseSize(res, attrs);
                    noSizeTag = false;
                } else if (SHAPE_VIEWPORT.equals(tagName)) {
                    pathRenderer.parseViewport(res, attrs);
                    noViewportTag = false;
                } else if (SHAPE_GROUP.equals(tagName)) {
                    VGroup newChildGroup = new VGroup();
                    newChildGroup.inflate(res, attrs, theme);
                    currentGroup.mChildGroupList.add(newChildGroup);
                    groupStack.push(newChildGroup);
                    if (newChildGroup.getGroupName() != null) {
                        mVGTargetsMap.put(newChildGroup.getGroupName(), newChildGroup);
                    }
                    noGroupTag = false;
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                final String tagName = parser.getName();
                if (SHAPE_GROUP.equals(tagName)) {
                    groupStack.pop();
                }
            }
            eventType = parser.next();
        }

        // Print the tree out for debug.
        if (DBG_VECTOR_DRAWABLE) {
            printGroupTree(pathRenderer.mRootGroup, 0);
        }

        if (noSizeTag || noViewportTag || noPathTag) {
            final StringBuffer tag = new StringBuffer();

            if (noSizeTag) {
                tag.append(SHAPE_SIZE);
            }

            if (noViewportTag) {
                if (tag.length() > 0) {
                    tag.append(" & ");
                }
                tag.append(SHAPE_SIZE);
            }

            if (noPathTag) {
                if (tag.length() > 0) {
                    tag.append(" or ");
                }
                tag.append(SHAPE_PATH);
            }

            throw new XmlPullParserException("no " + tag + " defined");
        }

        return pathRenderer;
    }

    private void printGroupTree(VGroup currentGroup, int level) {
        String indent = "";
        for (int i = 0 ; i < level ; i++) {
            indent += "    ";
        }
        // Print the current node
        Log.v(LOGTAG, indent + "current group is :" +  currentGroup.getGroupName()
                + " rotation is " + currentGroup.mRotate);
        Log.v(LOGTAG, indent + "matrix is :" +  currentGroup.getLocalMatrix().toString());
        // Then print all the children
        for (int i = 0 ; i < currentGroup.mChildGroupList.size(); i++) {
            printGroupTree(currentGroup.mChildGroupList.get(i), level + 1);
        }
    }

    private void setPathRenderer(VPathRenderer pathRenderer) {
        mVectorState.mVPathRenderer = pathRenderer;
    }

    private static class VectorDrawableState extends ConstantState {
        int mChangingConfigurations;
        VPathRenderer mVPathRenderer;
        ColorStateList mTint;
        Mode mTintMode;

        public VectorDrawableState(VectorDrawableState copy) {
            if (copy != null) {
                mChangingConfigurations = copy.mChangingConfigurations;
                // TODO: Make sure the constant state are handled correctly.
                mVPathRenderer = new VPathRenderer(copy.mVPathRenderer);
                mTint = copy.mTint;
                mTintMode = copy.mTintMode;
            }
        }

        @Override
        public Drawable newDrawable() {
            return new VectorDrawable(this, null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new VectorDrawable(this, res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new VectorDrawable(this, res, theme);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    private static class VPathRenderer {
        /* Right now the internal data structure is organized as a tree.
         * Each node can be a group node, or a path.
         * A group node can have groups or paths as children, but a path node has
         * no children.
         * One example can be:
         *                 Root Group
         *                /    |     \
         *           Group    Path    Group
         *          /     \             |
         *         Path   Path         Path
         *
         */
        private final VGroup mRootGroup;

        private final Path mPath = new Path();
        private final Path mRenderPath = new Path();
        private static final Matrix IDENTITY_MATRIX = new Matrix();

        private Paint mStrokePaint;
        private Paint mFillPaint;
        private ColorFilter mColorFilter;
        private PathMeasure mPathMeasure;

        private float mBaseWidth = 0;
        private float mBaseHeight = 0;
        private float mViewportWidth = 0;
        private float mViewportHeight = 0;
        private int mRootAlpha = 0xFF;

        private final Matrix mFinalPathMatrix = new Matrix();

        public VPathRenderer() {
            mRootGroup = new VGroup();
        }

        public void setRootAlpha(int alpha) {
            mRootAlpha = alpha;
        }

        public int getRootAlpha() {
            return mRootAlpha;
        }

        public VPathRenderer(VPathRenderer copy) {
            mRootGroup = copy.mRootGroup;
            mBaseWidth = copy.mBaseWidth;
            mBaseHeight = copy.mBaseHeight;
            mViewportWidth = copy.mViewportHeight;
            mViewportHeight = copy.mViewportHeight;
        }

        public boolean canApplyTheme() {
            // If one of the paths can apply theme, then return true;
            return recursiveCanApplyTheme(mRootGroup);
        }

        private boolean recursiveCanApplyTheme(VGroup currentGroup) {
            // We can do a tree traverse here, if there is one path return true,
            // then we return true for the whole tree.
            final ArrayList<VPath> paths = currentGroup.mPathList;
            for (int j = paths.size() - 1; j >= 0; j--) {
                final VPath path = paths.get(j);
                if (path.canApplyTheme()) {
                    return true;
                }
            }

            final ArrayList<VGroup> childGroups = currentGroup.mChildGroupList;

            for (int i = 0; i < childGroups.size(); i++) {
                VGroup childGroup = childGroups.get(i);
                if (childGroup.canApplyTheme()
                        || recursiveCanApplyTheme(childGroup)) {
                    return true;
                }
            }
            return false;
        }

        public void applyTheme(Theme t) {
            // Apply theme to every path of the tree.
            recursiveApplyTheme(mRootGroup, t);
        }

        private void recursiveApplyTheme(VGroup currentGroup, Theme t) {
            // We can do a tree traverse here, apply theme to all paths which
            // can apply theme.
            final ArrayList<VPath> paths = currentGroup.mPathList;
            for (int j = paths.size() - 1; j >= 0; j--) {
                final VPath path = paths.get(j);
                if (path.canApplyTheme()) {
                    path.applyTheme(t);
                }
            }

            final ArrayList<VGroup> childGroups = currentGroup.mChildGroupList;

            for (int i = 0; i < childGroups.size(); i++) {
                VGroup childGroup = childGroups.get(i);
                if (childGroup.canApplyTheme()) {
                    childGroup.applyTheme(t);
                }
                recursiveApplyTheme(childGroup, t);
            }

        }

        public void setColorFilter(ColorFilter colorFilter) {
            mColorFilter = colorFilter;

            if (mFillPaint != null) {
                mFillPaint.setColorFilter(colorFilter);
            }

            if (mStrokePaint != null) {
                mStrokePaint.setColorFilter(colorFilter);
            }

        }

        private void drawGroupTree(VGroup currentGroup, Matrix currentMatrix,
                float currentAlpha, Canvas canvas, int w, int h) {
            // Calculate current group's matrix by preConcat the parent's and
            // and the current one on the top of the stack.
            // Basically the Mfinal = Mviewport * M0 * M1 * M2;
            // Mi the local matrix at level i of the group tree.
            currentGroup.mStackedMatrix.set(currentMatrix);

            currentGroup.mStackedMatrix.preConcat(currentGroup.mLocalMatrix);

            float stackedAlpha = currentAlpha * currentGroup.mGroupAlpha;
            drawPath(currentGroup, stackedAlpha, canvas, w, h);
            // Draw the group tree in post order.
            for (int i = 0 ; i < currentGroup.mChildGroupList.size(); i++) {
                drawGroupTree(currentGroup.mChildGroupList.get(i),
                        currentGroup.mStackedMatrix, stackedAlpha, canvas, w, h);
            }
        }

        public void draw(Canvas canvas, int w, int h) {
            // Travese the tree in pre-order to draw.
            drawGroupTree(mRootGroup, IDENTITY_MATRIX, ((float) mRootAlpha) / 0xFF, canvas, w, h);
        }

        private void drawPath(VGroup vGroup, float stackedAlpha, Canvas canvas, int w, int h) {
            final float scale = Math.min(h / mViewportHeight, w / mViewportWidth);

            mFinalPathMatrix.set(vGroup.mStackedMatrix);
            mFinalPathMatrix.postScale(scale, scale, mViewportWidth / 2f, mViewportHeight / 2f);
            mFinalPathMatrix.postTranslate(w / 2f - mViewportWidth / 2f, h / 2f - mViewportHeight / 2f);

            ArrayList<VPath> paths = vGroup.getPaths();
            for (int i = 0; i < paths.size(); i++) {
                VPath vPath = paths.get(i);
                vPath.toPath(mPath);
                final Path path = mPath;

                if (vPath.mTrimPathStart != 0.0f || vPath.mTrimPathEnd != 1.0f) {
                    float start = (vPath.mTrimPathStart + vPath.mTrimPathOffset) % 1.0f;
                    float end = (vPath.mTrimPathEnd + vPath.mTrimPathOffset) % 1.0f;

                    if (mPathMeasure == null) {
                        mPathMeasure = new PathMeasure();
                    }
                    mPathMeasure.setPath(mPath, false);

                    float len = mPathMeasure.getLength();
                    start = start * len;
                    end = end * len;
                    path.reset();
                    if (start > end) {
                        mPathMeasure.getSegment(start, len, path, true);
                        mPathMeasure.getSegment(0f, end, path, true);
                    } else {
                        mPathMeasure.getSegment(start, end, path, true);
                    }
                    path.rLineTo(0, 0); // fix bug in measure
                }

                mRenderPath.reset();

                mRenderPath.addPath(path, mFinalPathMatrix);

                if (vPath.mClip) {
                    canvas.clipPath(mRenderPath, Region.Op.REPLACE);
                } else {
                   if (vPath.mFillColor != 0) {
                        if (mFillPaint == null) {
                            mFillPaint = new Paint();
                            mFillPaint.setColorFilter(mColorFilter);
                            mFillPaint.setStyle(Paint.Style.FILL);
                            mFillPaint.setAntiAlias(true);
                        }
                        mFillPaint.setColor(applyAlpha(vPath.mFillColor, stackedAlpha));
                        canvas.drawPath(mRenderPath, mFillPaint);
                    }

                    if (vPath.mStrokeColor != 0) {
                        if (mStrokePaint == null) {
                            mStrokePaint = new Paint();
                            mStrokePaint.setColorFilter(mColorFilter);
                            mStrokePaint.setStyle(Paint.Style.STROKE);
                            mStrokePaint.setAntiAlias(true);
                        }

                        final Paint strokePaint = mStrokePaint;
                        if (vPath.mStrokeLineJoin != null) {
                            strokePaint.setStrokeJoin(vPath.mStrokeLineJoin);
                        }

                        if (vPath.mStrokeLineCap != null) {
                            strokePaint.setStrokeCap(vPath.mStrokeLineCap);
                        }

                        strokePaint.setStrokeMiter(vPath.mStrokeMiterlimit * scale);

                        strokePaint.setColor(applyAlpha(vPath.mStrokeColor, stackedAlpha));
                        strokePaint.setStrokeWidth(vPath.mStrokeWidth * scale);
                        canvas.drawPath(mRenderPath, strokePaint);
                    }
                }
            }
        }

        private void parseViewport(Resources r, AttributeSet attrs)
                throws XmlPullParserException {
            final TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableViewport);
            mViewportWidth = a.getFloat(R.styleable.VectorDrawableViewport_viewportWidth, mViewportWidth);
            mViewportHeight = a.getFloat(R.styleable.VectorDrawableViewport_viewportHeight, mViewportHeight);

            if (mViewportWidth <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<viewport> tag requires viewportWidth > 0");
            } else if (mViewportHeight <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<viewport> tag requires viewportHeight > 0");
            }

            a.recycle();
        }

        private void parseSize(Resources r, AttributeSet attrs)
                throws XmlPullParserException  {
            final TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableSize);
            mBaseWidth = a.getDimension(R.styleable.VectorDrawableSize_width, mBaseWidth);
            mBaseHeight = a.getDimension(R.styleable.VectorDrawableSize_height, mBaseHeight);

            if (mBaseWidth <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<size> tag requires width > 0");
            } else if (mBaseHeight <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<size> tag requires height > 0");
            }

            a.recycle();
        }

    }

    static class VGroup {
        private final ArrayList<VPath> mPathList = new ArrayList<VPath>();
        private final ArrayList<VGroup> mChildGroupList = new ArrayList<VGroup>();

        private float mRotate = 0;
        private float mPivotX = 0;
        private float mPivotY = 0;
        private float mScaleX = 1;
        private float mScaleY = 1;
        private float mTranslateX = 0;
        private float mTranslateY = 0;
        private float mGroupAlpha = 1;

        // mLocalMatrix is parsed from the XML.
        private final Matrix mLocalMatrix = new Matrix();
        // mStackedMatrix is only used when drawing, it combines all the
        // parents' local matrices with the current one.
        private final Matrix mStackedMatrix = new Matrix();

        private int[] mThemeAttrs;

        private String mGroupName = null;

        /* Getter and Setter */
        public float getRotation() {
            return mRotate;
        }

        public void setRotation(float rotation) {
            if (rotation != mRotate) {
                mRotate = rotation;
                updateLocalMatrix();
            }
        }

        public float getPivotX() {
            return mPivotX;
        }

        public void setPivotX(float pivotX) {
            if (pivotX != mPivotX) {
                mPivotX = pivotX;
                updateLocalMatrix();
            }
        }

        public float getPivotY() {
            return mPivotY;
        }

        public void setPivotY(float pivotY) {
            if (pivotY != mPivotY) {
                mPivotY = pivotY;
                updateLocalMatrix();
            }
        }

        public float getScaleX() {
            return mScaleX;
        }

        public void setScaleX(float scaleX) {
            if (scaleX != mScaleX) {
                mScaleX = scaleX;
                updateLocalMatrix();
            }
        }

        public float getScaleY() {
            return mScaleY;
        }

        public void setScaleY(float scaleY) {
            if (scaleY != mScaleY) {
                mScaleY = scaleY;
                updateLocalMatrix();
            }
        }

        public float getTranslateX() {
            return mTranslateX;
        }

        public void setTranslateX(float translateX) {
            if (translateX != mTranslateX) {
                mTranslateX = translateX;
                updateLocalMatrix();
            }
        }

        public float getTranslateY() {
            return mTranslateY;
        }

        public void setTranslateY(float translateY) {
            if (translateY != mTranslateY) {
                mTranslateY = translateY;
                updateLocalMatrix();
            }
        }

        public float getAlpha() {
            return mGroupAlpha;
        }

        public void setAlpha(float groupAlpha) {
            if (groupAlpha != mGroupAlpha) {
                mGroupAlpha = groupAlpha;
            }
        }

        public String getGroupName() {
            return mGroupName;
        }

        public Matrix getLocalMatrix() {
            return mLocalMatrix;
        }

        public void add(VPath path) {
            mPathList.add(path);
         }

        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(
                    mThemeAttrs, R.styleable.VectorDrawablePath);

            mRotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation, mRotate);
            mPivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX, mPivotX);
            mPivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY, mPivotY);
            mScaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX, mScaleX);
            mScaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY, mScaleY);
            mTranslateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX, mTranslateX);
            mTranslateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY, mTranslateY);
            mGroupAlpha = a.getFloat(R.styleable.VectorDrawableGroup_alpha, mGroupAlpha);
            updateLocalMatrix();
            if (a.hasValue(R.styleable.VectorDrawableGroup_name)) {
                mGroupName = a.getString(R.styleable.VectorDrawableGroup_name);
            }
            a.recycle();
        }

        public void inflate(Resources res, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(res, theme, attrs, R.styleable.VectorDrawableGroup);
            final int[] themeAttrs = a.extractThemeAttrs();

            mThemeAttrs = themeAttrs;
            // NOTE: The set of attributes loaded here MUST match the
            // set of attributes loaded in applyTheme.

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_rotation] == 0) {
                mRotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation, mRotate);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_pivotX] == 0) {
                mPivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX, mPivotX);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_pivotY] == 0) {
                mPivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY, mPivotY);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_scaleX] == 0) {
                mScaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX, mScaleX);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_scaleY] == 0) {
                mScaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY, mScaleY);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_translateX] == 0) {
                mTranslateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX, mTranslateX);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_translateY] == 0) {
                mTranslateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY, mTranslateY);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_name] == 0) {
                mGroupName = a.getString(R.styleable.VectorDrawableGroup_name);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_alpha] == 0) {
                mGroupAlpha = a.getFloat(R.styleable.VectorDrawableGroup_alpha, mGroupAlpha);
            }

            updateLocalMatrix();
            a.recycle();
        }

        private void updateLocalMatrix() {
            // The order we apply is the same as the
            // RenderNode.cpp::applyViewPropertyTransforms().
            mLocalMatrix.reset();
            mLocalMatrix.postTranslate(-mPivotX, -mPivotY);
            mLocalMatrix.postScale(mScaleX, mScaleY);
            mLocalMatrix.postRotate(mRotate, 0, 0);
            mLocalMatrix.postTranslate(mTranslateX + mPivotX, mTranslateY + mPivotY);
        }

        /**
         * Must return in order of adding
         * @return ordered list of paths
         */
        public ArrayList<VPath> getPaths() {
            return mPathList;
        }

    }

    private static class VPath {
        private int[] mThemeAttrs;

        int mStrokeColor = 0;
        float mStrokeWidth = 0;
        float mStrokeOpacity = Float.NaN;
        int mFillColor = Color.BLACK;
        int mFillRule;
        float mFillOpacity = Float.NaN;
        float mTrimPathStart = 0;
        float mTrimPathEnd = 1;
        float mTrimPathOffset = 0;

        boolean mClip = false;
        Paint.Cap mStrokeLineCap = Paint.Cap.BUTT;
        Paint.Join mStrokeLineJoin = Paint.Join.MITER;
        float mStrokeMiterlimit = 4;

        private PathParser.PathDataNode[] mNode = null;
        private String mPathName;

        public VPath() {
            // Empty constructor.
        }

        public void toPath(Path path) {
            path.reset();
            if (mNode != null) {
                PathParser.PathDataNode.nodesToPath(mNode, path);
            }
        }

        public String getPathName() {
            return mPathName;
        }

        private Paint.Cap getStrokeLineCap(int id, Paint.Cap defValue) {
            switch (id) {
                case LINECAP_BUTT:
                    return Paint.Cap.BUTT;
                case LINECAP_ROUND:
                    return Paint.Cap.ROUND;
                case LINECAP_SQUARE:
                    return Paint.Cap.SQUARE;
                default:
                    return defValue;
            }
        }

        private Paint.Join getStrokeLineJoin(int id, Paint.Join defValue) {
            switch (id) {
                case LINEJOIN_MITER:
                    return Paint.Join.MITER;
                case LINEJOIN_ROUND:
                    return Paint.Join.ROUND;
                case LINEJOIN_BEVEL:
                    return Paint.Join.BEVEL;
                default:
                    return defValue;
            }
        }

        /* Setters and Getters, mostly used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        public PathParser.PathDataNode[] getPathData() {
            return mNode;
        }

        @SuppressWarnings("unused")
        public void setPathData(PathParser.PathDataNode[] node) {
            if (!PathParser.canMorph(mNode, node)) {
                // This should not happen in the middle of animation.
                mNode = PathParser.deepCopyNodes(node);
            } else {
                PathParser.updateNodes(mNode, node);
            }
        }

        @SuppressWarnings("unused")
        int getStroke() {
            return mStrokeColor;
        }

        @SuppressWarnings("unused")
        void setStroke(int strokeColor) {
            mStrokeColor = strokeColor;
        }

        @SuppressWarnings("unused")
        float getStrokeWidth() {
            return mStrokeWidth;
        }

        @SuppressWarnings("unused")
        void setStrokeWidth(float strokeWidth) {
            mStrokeWidth = strokeWidth;
        }

        @SuppressWarnings("unused")
        float getStrokeOpacity() {
            return mStrokeOpacity;
        }

        @SuppressWarnings("unused")
        void setStrokeOpacity(float strokeOpacity) {
            mStrokeOpacity = strokeOpacity;
        }

        @SuppressWarnings("unused")
        int getFill() {
            return mFillColor;
        }

        @SuppressWarnings("unused")
        void setFill(int fillColor) {
            mFillColor = fillColor;
        }

        @SuppressWarnings("unused")
        float getFillOpacity() {
            return mFillOpacity;
        }

        @SuppressWarnings("unused")
        void setFillOpacity(float fillOpacity) {
            mFillOpacity = fillOpacity;
        }

        @SuppressWarnings("unused")
        float getTrimPathStart() {
            return mTrimPathStart;
        }

        @SuppressWarnings("unused")
        void setTrimPathStart(float trimPathStart) {
            mTrimPathStart = trimPathStart;
        }

        @SuppressWarnings("unused")
        float getTrimPathEnd() {
            return mTrimPathEnd;
        }

        @SuppressWarnings("unused")
        void setTrimPathEnd(float trimPathEnd) {
            mTrimPathEnd = trimPathEnd;
        }

        @SuppressWarnings("unused")
        float getTrimPathOffset() {
            return mTrimPathOffset;
        }

        @SuppressWarnings("unused")
        void setTrimPathOffset(float trimPathOffset) {
            mTrimPathOffset = trimPathOffset;
        }

        public void inflate(Resources r, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.VectorDrawablePath);
            final int[] themeAttrs = a.extractThemeAttrs();
            mThemeAttrs = themeAttrs;

            // NOTE: The set of attributes loaded here MUST match the
            // set of attributes loaded in applyTheme.
            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_clipToPath] == 0) {
                mClip = a.getBoolean(R.styleable.VectorDrawablePath_clipToPath, mClip);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_name] == 0) {
                mPathName = a.getString(R.styleable.VectorDrawablePath_name);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_pathData] == 0) {
                mNode = PathParser.createNodesFromPathData(a.getString(
                        R.styleable.VectorDrawablePath_pathData));
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_fill] == 0) {
                mFillColor = a.getColor(R.styleable.VectorDrawablePath_fill, mFillColor);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_fillOpacity] == 0) {
                mFillOpacity = a.getFloat(R.styleable.VectorDrawablePath_fillOpacity, mFillOpacity);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeLineCap] == 0) {
                mStrokeLineCap = getStrokeLineCap(
                        a.getInt(R.styleable.VectorDrawablePath_strokeLineCap, -1), mStrokeLineCap);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeLineJoin] == 0) {
                mStrokeLineJoin = getStrokeLineJoin(
                        a.getInt(R.styleable.VectorDrawablePath_strokeLineJoin, -1), mStrokeLineJoin);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeMiterLimit] == 0) {
                mStrokeMiterlimit = a.getFloat(
                        R.styleable.VectorDrawablePath_strokeMiterLimit, mStrokeMiterlimit);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_stroke] == 0) {
                mStrokeColor = a.getColor(R.styleable.VectorDrawablePath_stroke, mStrokeColor);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeOpacity] == 0) {
                mStrokeOpacity = a.getFloat(
                        R.styleable.VectorDrawablePath_strokeOpacity, mStrokeOpacity);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_strokeWidth] == 0) {
                mStrokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth, mStrokeWidth);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_trimPathEnd] == 0) {
                mTrimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd, mTrimPathEnd);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_trimPathOffset] == 0) {
                mTrimPathOffset = a.getFloat(
                        R.styleable.VectorDrawablePath_trimPathOffset, mTrimPathOffset);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_trimPathStart] == 0) {
                mTrimPathStart = a.getFloat(
                        R.styleable.VectorDrawablePath_trimPathStart, mTrimPathStart);
            }

            updateColorAlphas();

            a.recycle();
        }

        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(
                    mThemeAttrs, R.styleable.VectorDrawablePath);

            mClip = a.getBoolean(R.styleable.VectorDrawablePath_clipToPath, mClip);

            if (a.hasValue(R.styleable.VectorDrawablePath_name)) {
                mPathName = a.getString(R.styleable.VectorDrawablePath_name);
            }

            if (a.hasValue(R.styleable.VectorDrawablePath_pathData)) {
                mNode = PathParser.createNodesFromPathData(a.getString(
                        R.styleable.VectorDrawablePath_pathData));
            }

            mFillColor = a.getColor(R.styleable.VectorDrawablePath_fill, mFillColor);
            mFillOpacity = a.getFloat(R.styleable.VectorDrawablePath_fillOpacity, mFillOpacity);

            mStrokeLineCap = getStrokeLineCap(a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineCap, -1), mStrokeLineCap);
            mStrokeLineJoin = getStrokeLineJoin(a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineJoin, -1), mStrokeLineJoin);
            mStrokeMiterlimit = a.getFloat(
                    R.styleable.VectorDrawablePath_strokeMiterLimit, mStrokeMiterlimit);
            mStrokeColor = a.getColor(R.styleable.VectorDrawablePath_stroke, mStrokeColor);
            mStrokeOpacity = a.getFloat(
                    R.styleable.VectorDrawablePath_strokeOpacity, mStrokeOpacity);
            mStrokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth, mStrokeWidth);

            mTrimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd, mTrimPathEnd);
            mTrimPathOffset = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathOffset, mTrimPathOffset);
            mTrimPathStart = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathStart, mTrimPathStart);

            updateColorAlphas();
            a.recycle();
        }

        private void updateColorAlphas() {
            if (!Float.isNaN(mFillOpacity)) {
                mFillColor = applyAlpha(mFillColor, mFillOpacity);
            }

            if (!Float.isNaN(mStrokeOpacity)) {
                mStrokeColor = applyAlpha(mStrokeColor, mStrokeOpacity);
            }
        }
    }
}
