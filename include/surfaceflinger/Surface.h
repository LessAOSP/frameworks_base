/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_SF_SURFACE_H
#define ANDROID_SF_SURFACE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

#include <ui/PixelFormat.h>
#include <ui/Region.h>
#include <ui/egl/android_natives.h>

#include <gui/SurfaceTextureClient.h>

#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/ISurfaceComposerClient.h>

#define ANDROID_VIEW_SURFACE_JNI_ID    "mNativeSurface"

namespace android {

// ---------------------------------------------------------------------------

class ISurfaceTexture;
class Surface;
class SurfaceComposerClient;

// ---------------------------------------------------------------------------

class SurfaceControl : public RefBase
{
public:
    static bool isValid(const sp<SurfaceControl>& surface) {
        return (surface != 0) && surface->isValid();
    }
    bool isValid() {
        return mToken>=0 && mClient!=0;
    }
    static bool isSameSurface(
            const sp<SurfaceControl>& lhs, const sp<SurfaceControl>& rhs);
        
    uint32_t    getFlags() const { return mFlags; }
    uint32_t    getIdentity() const { return mIdentity; }

    // release surface data from java
    void        clear();
    
    status_t    setLayer(int32_t layer);
    status_t    setPosition(int32_t x, int32_t y);
    status_t    setSize(uint32_t w, uint32_t h);
    status_t    hide();
    status_t    show(int32_t layer = -1);
    status_t    freeze();
    status_t    unfreeze();
    status_t    setFlags(uint32_t flags, uint32_t mask);
    status_t    setTransparentRegionHint(const Region& transparent);
    status_t    setAlpha(float alpha=1.0f);
    status_t    setMatrix(float dsdx, float dtdx, float dsdy, float dtdy);
    status_t    setFreezeTint(uint32_t tint);

    static status_t writeSurfaceToParcel(
            const sp<SurfaceControl>& control, Parcel* parcel);

    sp<Surface> getSurface() const;

private:
    // can't be copied
    SurfaceControl& operator = (SurfaceControl& rhs);
    SurfaceControl(const SurfaceControl& rhs);

    
    friend class SurfaceComposerClient;

    // camera and camcorder need access to the ISurface binder interface for preview
    friend class CameraService;
    friend class MediaRecorder;
    // mediaplayer needs access to ISurface for display
    friend class MediaPlayer;
    // for testing
    friend class Test;
    // videoEditor preview classes
    friend class VideoEditorPreviewController;
    friend class Surface;

    SurfaceControl(
            const sp<SurfaceComposerClient>& client,
            const sp<ISurface>& surface,
            const ISurfaceComposerClient::surface_data_t& data,
            uint32_t w, uint32_t h, PixelFormat format, uint32_t flags);

    ~SurfaceControl();

    status_t validate() const;
    void destroy();
    
    sp<SurfaceComposerClient>   mClient;
    sp<ISurface>                mSurface;
    SurfaceID                   mToken;
    uint32_t                    mIdentity;
    uint32_t                    mWidth;
    uint32_t                    mHeight;
    PixelFormat                 mFormat;
    uint32_t                    mFlags;
    mutable Mutex               mLock;
    
    mutable sp<Surface>         mSurfaceData;
};
    
// ---------------------------------------------------------------------------

class Surface : public SurfaceTextureClient
{
public:
    struct SurfaceInfo {
        uint32_t    w;
        uint32_t    h;
        uint32_t    s;
        uint32_t    usage;
        PixelFormat format;
        void*       bits;
        uint32_t    reserved[2];
    };

    static status_t writeToParcel(
            const sp<Surface>& control, Parcel* parcel);

    static sp<Surface> readFromParcel(const Parcel& data);

    static bool isValid(const sp<Surface>& surface) {
        return (surface != 0) && surface->isValid();
    }

    bool        isValid();
    uint32_t    getFlags() const    { return mFlags; }
    uint32_t    getIdentity() const { return mIdentity; }
    sp<ISurfaceTexture> getSurfaceTexture();

    // the lock/unlock APIs must be used from the same thread
    status_t    lock(SurfaceInfo* info, Region* dirty = NULL);
    status_t    unlockAndPost();

    sp<IBinder> asBinder() const;

private:
    // this is just to be able to write some unit tests
    friend class Test;
    friend class SurfaceControl;

    // can't be copied
    Surface& operator = (Surface& rhs);
    Surface(const Surface& rhs);

    Surface(const sp<SurfaceControl>& control);
    Surface(const Parcel& data, const sp<IBinder>& ref);
    ~Surface();

    /*
     *  private stuff...
     */
    void init();
    status_t validate(bool inCancelBuffer = false) const;

    static void cleanCachedSurfacesLocked();

    virtual int query(int what, int* value) const;

    // constants
    status_t                    mInitCheck;
    sp<ISurface>                mSurface;
    uint32_t                    mIdentity;
    PixelFormat                 mFormat;
    uint32_t                    mFlags;

    // query() must be called from dequeueBuffer() thread
    uint32_t                    mWidth;
    uint32_t                    mHeight;

    // A cache of Surface objects that have been deserialized into this process.
    static Mutex sCachedSurfacesLock;
    static DefaultKeyedVector<wp<IBinder>, wp<Surface> > sCachedSurfaces;
};

}; // namespace android

#endif // ANDROID_SF_SURFACE_H
