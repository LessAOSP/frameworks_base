/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
#define ANDROID_HWUI_DISPLAY_LIST_RENDERER_H

#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <cutils/compiler.h>

#include "DisplayList.h"
#include "DisplayListLogBuffer.h"
#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MIN_WRITER_SIZE 4096
#define OP_MAY_BE_SKIPPED_MASK 0xff000000

// Debug
#if DEBUG_DISPLAY_LIST
    #define DISPLAY_LIST_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define DISPLAY_LIST_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Display list
///////////////////////////////////////////////////////////////////////////////

class DeferredDisplayList;
class DisplayListRenderer;
class DisplayListOp;
class DrawOp;
class StateOp;

/**
 * Records drawing commands in a display list for later playback into an OpenGLRenderer.
 */
class DisplayListRenderer: public OpenGLRenderer {
public:
    ANDROID_API DisplayListRenderer();
    virtual ~DisplayListRenderer();

    ANDROID_API DisplayList* getDisplayList(DisplayList* displayList);

    virtual bool isRecording() const { return true; }

// ----------------------------------------------------------------------------
// Frame state operations
// ----------------------------------------------------------------------------
    virtual void setViewport(int width, int height);
    virtual status_t prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();
    virtual void interrupt();
    virtual void resume();

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------
    // Save (layer)
    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);
    virtual int saveLayer(float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode, int flags);

    // Matrix
    virtual void translate(float dx, float dy, float dz);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    virtual void setMatrix(const SkMatrix* matrix);
    virtual void concatMatrix(const SkMatrix* matrix);

    // Clip
    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(const SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op);

    // Misc - should be implemented with SkPaint inspection
    virtual void resetShader();
    virtual void setupShader(SkiaShader* shader);

    virtual void resetColorFilter();
    virtual void setupColorFilter(SkiaColorFilter* filter);

    virtual void resetShadow();
    virtual void setupShadow(float radius, float dx, float dy, int color);

    virtual void resetPaintFilter();
    virtual void setupPaintFilter(int clearBits, int setBits);

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------
    virtual status_t drawColor(int color, SkXfermode::Mode mode);

    // Bitmap-based
    virtual status_t drawBitmap(const SkBitmap* bitmap, float left, float top,
            const SkPaint* paint);
    virtual status_t drawBitmap(const SkBitmap* bitmap, const SkMatrix* matrix,
            const SkPaint* paint);
    virtual status_t drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    virtual status_t drawBitmapData(const SkBitmap* bitmap, float left, float top,
            const SkPaint* paint);
    virtual status_t drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint);
    virtual status_t drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint);

    // Shapes
    virtual status_t drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual status_t drawRects(const float* rects, int count, const SkPaint* paint);
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint);
    virtual status_t drawCircle(float x, float y, float radius, const SkPaint* paint);
    virtual status_t drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint);
    virtual status_t drawPath(const SkPath* path, const SkPaint* paint);
    virtual status_t drawLines(const float* points, int count, const SkPaint* paint);
    virtual status_t drawPoints(const float* points, int count, const SkPaint* paint);

    // Text
    virtual status_t drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate);
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint);
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, const SkPaint* paint);

// ----------------------------------------------------------------------------
// Canvas draw operations - special
// ----------------------------------------------------------------------------
    virtual status_t drawLayer(Layer* layer, float x, float y);
    virtual status_t drawDisplayList(DisplayList* displayList, Rect& dirty,
            int32_t replayFlags);

    // TODO: rename for consistency
    virtual status_t callDrawGLFunction(Functor* functor, Rect& dirty);

// ----------------------------------------------------------------------------
// DisplayList / resource management
// ----------------------------------------------------------------------------
    ANDROID_API void reset();

    sp<DisplayListData> getDisplayListData() const {
        return mDisplayListData;
    }

    const Vector<const SkBitmap*>& getBitmapResources() const {
        return mBitmapResources;
    }

    const Vector<const SkBitmap*>& getOwnedBitmapResources() const {
        return mOwnedBitmapResources;
    }

    const Vector<SkiaColorFilter*>& getFilterResources() const {
        return mFilterResources;
    }

    const Vector<const Res_png_9patch*>& getPatchResources() const {
        return mPatchResources;
    }

    const Vector<SkiaShader*>& getShaders() const {
        return mShaders;
    }

    const Vector<const SkPaint*>& getPaints() const {
        return mPaints;
    }

    const Vector<const SkPath*>& getPaths() const {
        return mPaths;
    }

    const SortedVector<const SkPath*>& getSourcePaths() const {
        return mSourcePaths;
    }

    const Vector<const SkRegion*>& getRegions() const {
        return mRegions;
    }

    const Vector<Layer*>& getLayers() const {
        return mLayers;
    }

    const Vector<const SkMatrix*>& getMatrices() const {
        return mMatrices;
    }

    uint32_t getFunctorCount() const {
        return mFunctorCount;
    }
protected:
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored);

private:
    void insertRestoreToCount();
    void insertTranslate();

    LinearAllocator& alloc() { return mDisplayListData->allocator; }
    void addStateOp(StateOp* op);
    void addDrawOp(DrawOp* op);
    void addOpInternal(DisplayListOp* op) {
        insertRestoreToCount();
        insertTranslate();
        mDisplayListData->displayListOps.add(op);
    }

    template<class T>
    inline const T* refBuffer(const T* srcBuffer, int32_t count) {
        if (!srcBuffer) return NULL;

        T* dstBuffer = (T*) mDisplayListData->allocator.alloc(count * sizeof(T));
        memcpy(dstBuffer, srcBuffer, count * sizeof(T));
        return dstBuffer;
    }

    inline char* refText(const char* text, size_t byteLength) {
        return (char*) refBuffer<uint8_t>((uint8_t*)text, byteLength);
    }

    inline const SkPath* refPath(const SkPath* path) {
        if (!path) return NULL;

        const SkPath* pathCopy = mPathMap.valueFor(path);
        if (pathCopy == NULL || pathCopy->getGenerationID() != path->getGenerationID()) {
            SkPath* newPathCopy = new SkPath(*path);
            newPathCopy->setSourcePath(path);

            pathCopy = newPathCopy;
            // replaceValueFor() performs an add if the entry doesn't exist
            mPathMap.replaceValueFor(path, pathCopy);
            mPaths.add(pathCopy);
        }
        if (mSourcePaths.indexOf(path) < 0) {
            mCaches.resourceCache.incrementRefcount(path);
            mSourcePaths.add(path);
        }
        return pathCopy;
    }

    inline const SkPaint* refPaint(const SkPaint* paint) {
        if (!paint) {
            return paint;
        }

        const SkPaint* paintCopy = mPaintMap.valueFor(paint);
        if (paintCopy == NULL || paintCopy->getGenerationID() != paint->getGenerationID()) {
            paintCopy = new SkPaint(*paint);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(paint, paintCopy);
            mPaints.add(paintCopy);
        }

        return paintCopy;
    }

    inline const SkRegion* refRegion(const SkRegion* region) {
        if (!region) {
            return region;
        }

        const SkRegion* regionCopy = mRegionMap.valueFor(region);
        // TODO: Add generation ID to SkRegion
        if (regionCopy == NULL) {
            regionCopy = new SkRegion(*region);
            // replaceValueFor() performs an add if the entry doesn't exist
            mRegionMap.replaceValueFor(region, regionCopy);
            mRegions.add(regionCopy);
        }

        return regionCopy;
    }

    inline const SkMatrix* refMatrix(const SkMatrix* matrix) {
        if (matrix) {
            // Copying the matrix is cheap and prevents against the user changing
            // the original matrix before the operation that uses it
            const SkMatrix* copy = new SkMatrix(*matrix);
            mMatrices.add(copy);
            return copy;
        }
        return matrix;
    }

    inline Layer* refLayer(Layer* layer) {
        mLayers.add(layer);
        mCaches.resourceCache.incrementRefcount(layer);
        return layer;
    }

    inline const SkBitmap* refBitmap(const SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        mBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const SkBitmap* refBitmapData(const SkBitmap* bitmap) {
        mOwnedBitmapResources.add(bitmap);
        mCaches.resourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline SkiaShader* refShader(SkiaShader* shader) {
        if (!shader) return NULL;

        SkiaShader* shaderCopy = mShaderMap.valueFor(shader);
        // TODO: We also need to handle generation ID changes in compose shaders
        if (shaderCopy == NULL || shaderCopy->getGenerationId() != shader->getGenerationId()) {
            shaderCopy = shader->copy();
            // replaceValueFor() performs an add if the entry doesn't exist
            mShaderMap.replaceValueFor(shader, shaderCopy);
            mShaders.add(shaderCopy);
            mCaches.resourceCache.incrementRefcount(shaderCopy);
        }
        return shaderCopy;
    }

    inline SkiaColorFilter* refColorFilter(SkiaColorFilter* colorFilter) {
        mFilterResources.add(colorFilter);
        mCaches.resourceCache.incrementRefcount(colorFilter);
        return colorFilter;
    }

    inline const Res_png_9patch* refPatch(const Res_png_9patch* patch) {
        mPatchResources.add(patch);
        mCaches.resourceCache.incrementRefcount(patch);
        return patch;
    }

    // TODO: move these to DisplayListData
    Vector<const SkBitmap*> mBitmapResources;
    Vector<const SkBitmap*> mOwnedBitmapResources;
    Vector<SkiaColorFilter*> mFilterResources;
    Vector<const Res_png_9patch*> mPatchResources;

    Vector<const SkPaint*> mPaints;
    DefaultKeyedVector<const SkPaint*, const SkPaint*> mPaintMap;

    Vector<const SkPath*> mPaths;
    DefaultKeyedVector<const SkPath*, const SkPath*> mPathMap;

    SortedVector<const SkPath*> mSourcePaths;

    Vector<const SkRegion*> mRegions;
    DefaultKeyedVector<const SkRegion*, const SkRegion*> mRegionMap;

    Vector<SkiaShader*> mShaders;
    DefaultKeyedVector<SkiaShader*, SkiaShader*> mShaderMap;

    Vector<const SkMatrix*> mMatrices;

    Vector<Layer*> mLayers;

    int mRestoreSaveCount;

    Caches& mCaches;
    sp<DisplayListData> mDisplayListData;

    float mTranslateX;
    float mTranslateY;
    bool mHasTranslate;
    bool mHasDrawOps;

    uint32_t mFunctorCount;

    friend class DisplayList;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
