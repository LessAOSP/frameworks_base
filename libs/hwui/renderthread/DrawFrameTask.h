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
#ifndef DRAWFRAMETASK_H
#define DRAWFRAMETASK_H

#include <utils/Condition.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include "RenderTask.h"

#include "../Rect.h"

namespace android {
namespace uirenderer {

class DeferredLayerUpdater;
class DisplayListData;
class RenderNode;

namespace renderthread {

class CanvasContext;
class RenderThread;

/*
 * This is a special Super Task. It is re-used multiple times by RenderProxy,
 * and contains state (such as layer updaters & new DisplayListDatas) that is
 * tracked across many frames not just a single frame.
 * It is the sync-state task, and will kick off the post-sync draw
 */
class DrawFrameTask : public RenderTask {
public:
    DrawFrameTask();
    virtual ~DrawFrameTask();

    void setContext(CanvasContext* context);

    void addLayer(DeferredLayerUpdater* layer);
    void removeLayer(DeferredLayerUpdater* layer);

    void setRenderNode(RenderNode* renderNode);
    void setDirty(int left, int top, int right, int bottom);
    void drawFrame(RenderThread* renderThread);
    void flushStateChanges(RenderThread* renderThread);

    virtual void run();

private:
    enum TaskMode {
        MODE_INVALID,
        MODE_FULL,
        MODE_STATE_ONLY,
    };

    void postAndWait(RenderThread* renderThread, TaskMode mode);
    bool syncFrameState();
    void unblockUiThread();
    static void drawRenderNode(CanvasContext* context, RenderNode* renderNode, Rect* dirty);

    Mutex mLock;
    Condition mSignal;

    CanvasContext* mContext;

    /*********************************************
     *  Single frame data
     *********************************************/
    TaskMode mTaskMode;
    sp<RenderNode> mRenderNode;
    Rect mDirty;

    /*********************************************
     *  Multi frame data
     *********************************************/
    Vector<DeferredLayerUpdater*> mLayers;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* DRAWFRAMETASK_H */
