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

#ifndef CANVASCONTEXT_H_
#define CANVASCONTEXT_H_

#include <cutils/compiler.h>
#include <EGL/egl.h>
#include <SkBitmap.h>
#include <utils/Functor.h>
#include <utils/Vector.h>

#include "../RenderNode.h"
#include "RenderTask.h"
#include "RenderThread.h"

#define FUNCTOR_PROCESS_DELAY 4

namespace android {
namespace uirenderer {

class DeferredLayerUpdater;
class OpenGLRenderer;
class Rect;
class Layer;

namespace renderthread {

class GlobalContext;

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
class CanvasContext : public IFrameCallback {
public:
    CanvasContext(bool translucent, RenderNode* rootRenderNode);
    virtual ~CanvasContext();

    bool initialize(ANativeWindow* window);
    void updateSurface(ANativeWindow* window);
    void pauseSurface(ANativeWindow* window);
    void setup(int width, int height, const Vector3& lightCenter, float lightRadius);
    void setOpaque(bool opaque);
    void makeCurrent();
    void prepareDraw(const Vector<DeferredLayerUpdater*>* layerUpdaters, TreeInfo& info);
    void draw(Rect* dirty);
    void destroyCanvasAndSurface();

    // IFrameCallback, Chroreographer-driven frame callback entry point
    virtual void doFrame();

    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);

    void invokeFunctor(Functor* functor);

    void runWithGlContext(RenderTask* task);

    Layer* createRenderLayer(int width, int height);
    Layer* createTextureLayer();

    ANDROID_API static void setTextureAtlas(const sp<GraphicBuffer>& buffer,
            int64_t* map, size_t mapSize);

    void notifyFramePending();

private:
    friend class RegisterFrameCallbackTask;

    void processLayerUpdates(const Vector<DeferredLayerUpdater*>* layerUpdaters, TreeInfo& info);
    void prepareTree(TreeInfo& info);

    void setSurface(ANativeWindow* window);
    void swapBuffers();
    void requireSurface();

    void requireGlContext();

    GlobalContext* mGlobalContext;
    RenderThread& mRenderThread;
    sp<ANativeWindow> mNativeWindow;
    EGLSurface mEglSurface;
    bool mDirtyRegionsEnabled;

    bool mOpaque;
    OpenGLRenderer* mCanvas;
    bool mHaveNewSurface;

    const sp<RenderNode> mRootRenderNode;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* CANVASCONTEXT_H_ */
