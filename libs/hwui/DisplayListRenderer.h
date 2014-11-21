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

#include <SkDrawFilter.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRegion.h>
#include <SkTLazy.h>
#include <cutils/compiler.h>

#include "CanvasState.h"
#include "DisplayList.h"
#include "DisplayListLogBuffer.h"
#include "RenderNode.h"
#include "Renderer.h"
#include "ResourceCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

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
class DeferredLayerUpdater;
class DisplayListRenderer;
class DisplayListOp;
class DisplayListRenderer;
class DrawOp;
class RenderNode;
class StateOp;

/**
 * Records drawing commands in a display list for later playback into an OpenGLRenderer.
 */
class ANDROID_API DisplayListRenderer: public Renderer, public CanvasStateClient {
public:
    DisplayListRenderer();
    virtual ~DisplayListRenderer();

    void insertReorderBarrier(bool enableReorder);

    DisplayListData* finishRecording();

// ----------------------------------------------------------------------------
// Frame state operations
// ----------------------------------------------------------------------------
    virtual void prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void prepare(bool opaque) {
        prepareDirty(0.0f, 0.0f, mState.getWidth(), mState.getHeight(), opaque);
    }
    virtual bool finish();
    virtual void interrupt();
    virtual void resume();

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------
    virtual void setViewport(int width, int height) { mState.setViewport(width, height); }

    // Save (layer)
    virtual int getSaveCount() const { return mState.getSaveCount(); }
    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);
    virtual int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags);

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const { mState.getMatrix(outMatrix); }

    virtual void translate(float dx, float dy, float dz = 0.0f);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    virtual void setMatrix(const SkMatrix& matrix);
    virtual void concatMatrix(const SkMatrix& matrix);

    // Clip
    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(const SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op);

    // Misc
    virtual void setDrawFilter(SkDrawFilter* filter);
    virtual const Rect& getLocalClipBounds() const { return mState.getLocalClipBounds(); }
    const Rect& getRenderTargetClipBounds() const { return mState.getRenderTargetClipBounds(); }
    virtual bool quickRejectConservative(float left, float top, float right, float bottom) const {
        return mState.quickRejectConservative(left, top, right, bottom);
    }

    bool isCurrentTransformSimple() {
        return mState.currentTransform()->isSimple();
    }

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------
    virtual void drawColor(int color, SkXfermode::Mode mode);

    // Bitmap-based
    virtual void drawBitmap(const SkBitmap* bitmap, const SkPaint* paint);
    virtual void drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    virtual void drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint);
    virtual void drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint);
    virtual void drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint);

    // Shapes
    virtual void drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual void drawRects(const float* rects, int count, const SkPaint* paint);
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint);
    virtual void drawRoundRect(CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
                CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
                CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
                CanvasPropertyPaint* paint);
    virtual void drawCircle(float x, float y, float radius, const SkPaint* paint);
    virtual void drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
                CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint);
    virtual void drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint);
    virtual void drawPath(const SkPath* path, const SkPaint* paint);
    virtual void drawLines(const float* points, int count, const SkPaint* paint);
    virtual void drawPoints(const float* points, int count, const SkPaint* paint);

    // Text
    virtual void drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate);
    virtual void drawTextOnPath(const char* text, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint);
    virtual void drawPosText(const char* text, int bytesCount, int count,
            const float* positions, const SkPaint* paint);

// ----------------------------------------------------------------------------
// Canvas draw operations - special
// ----------------------------------------------------------------------------
    virtual void drawLayer(DeferredLayerUpdater* layerHandle, float x, float y);
    virtual void drawRenderNode(RenderNode* renderNode, Rect& dirty, int32_t replayFlags);

    // TODO: rename for consistency
    virtual void callDrawGLFunction(Functor* functor, Rect& dirty);

    void setHighContrastText(bool highContrastText) {
        mHighContrastText = highContrastText;
    }

// ----------------------------------------------------------------------------
// CanvasState callbacks
// ----------------------------------------------------------------------------
    virtual void onViewportInitialized() { }
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) { }
    virtual GLuint onGetTargetFbo() const { return -1; }

private:

    CanvasState mState;

    enum DeferredBarrierType {
        kBarrier_None,
        kBarrier_InOrder,
        kBarrier_OutOfOrder,
    };

    void flushRestoreToCount();
    void flushTranslate();
    void flushReorderBarrier();

    LinearAllocator& alloc() { return mDisplayListData->allocator; }

    // Each method returns final index of op
    size_t addOpAndUpdateChunk(DisplayListOp* op);
    // flushes any deferred operations, and appends the op
    size_t flushAndAddOp(DisplayListOp* op);

    size_t addStateOp(StateOp* op);
    size_t addDrawOp(DrawOp* op);
    size_t addRenderNodeOp(DrawRenderNodeOp* op);


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
            mDisplayListData->paths.add(pathCopy);
        }
        if (mDisplayListData->sourcePaths.indexOf(path) < 0) {
            mResourceCache.incrementRefcount(path);
            mDisplayListData->sourcePaths.add(path);
        }
        return pathCopy;
    }

    inline const SkPaint* refPaint(const SkPaint* paint) {
        if (!paint) return NULL;

        // If there is a draw filter apply it here and store the modified paint
        // so that we don't need to modify the paint every time we access it.
        SkTLazy<SkPaint> filteredPaint;
        if (mDrawFilter.get()) {
           paint = filteredPaint.init();
           mDrawFilter->filter(filteredPaint.get(), SkDrawFilter::kPaint_Type);
        }

        // compute the hash key for the paint and check the cache.
        const uint32_t key = paint->getHash();
        const SkPaint* cachedPaint = mPaintMap.valueFor(key);
        // In the unlikely event that 2 unique paints have the same hash we do a
        // object equality check to ensure we don't erroneously dedup them.
        if (cachedPaint == NULL || *cachedPaint != *paint) {
            cachedPaint =  new SkPaint(*paint);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(key, cachedPaint);
            mDisplayListData->paints.add(cachedPaint);
        }

        return cachedPaint;
    }

    inline SkPaint* copyPaint(const SkPaint* paint) {
        if (!paint) return NULL;
        SkPaint* paintCopy = new SkPaint(*paint);
        mDisplayListData->paints.add(paintCopy);

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
            mDisplayListData->regions.add(regionCopy);
        }

        return regionCopy;
    }

    inline const SkBitmap* refBitmap(const SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        mDisplayListData->bitmapResources.add(bitmap);
        mResourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const SkBitmap* refBitmapData(const SkBitmap* bitmap) {
        mDisplayListData->ownedBitmapResources.add(bitmap);
        mResourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const Res_png_9patch* refPatch(const Res_png_9patch* patch) {
        mDisplayListData->patchResources.add(patch);
        mResourceCache.incrementRefcount(patch);
        return patch;
    }

    DefaultKeyedVector<uint32_t, const SkPaint*> mPaintMap;
    DefaultKeyedVector<const SkPath*, const SkPath*> mPathMap;
    DefaultKeyedVector<const SkRegion*, const SkRegion*> mRegionMap;

    ResourceCache& mResourceCache;
    DisplayListData* mDisplayListData;

    float mTranslateX;
    float mTranslateY;
    bool mHasDeferredTranslate;
    DeferredBarrierType mDeferredBarrierType;
    bool mHighContrastText;

    int mRestoreSaveCount;

    SkAutoTUnref<SkDrawFilter> mDrawFilter;

    friend class RenderNode;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
