/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsDevice.h"
#include "rsContext.h"
#include "rsThreadIO.h"
#include <ui/FramebufferNativeWindow.h>
#include <ui/PixelFormat.h>
#include <ui/EGLUtils.h>
#include <ui/egl/android_natives.h>

#include <sys/types.h>
#include <sys/resource.h>
#include <sched.h>

#include <cutils/properties.h>

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cutils/sched_policy.h>
#include <sys/syscall.h>
#include <string.h>

using namespace android;
using namespace android::renderscript;

pthread_key_t Context::gThreadTLSKey = 0;
uint32_t Context::gThreadTLSKeyCount = 0;
uint32_t Context::gGLContextCount = 0;
pthread_mutex_t Context::gInitMutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t Context::gLibMutex = PTHREAD_MUTEX_INITIALIZER;

static void checkEglError(const char* op, EGLBoolean returnVal = EGL_TRUE) {
    if (returnVal != EGL_TRUE) {
        fprintf(stderr, "%s() returned %d\n", op, returnVal);
    }

    for (EGLint error = eglGetError(); error != EGL_SUCCESS; error
            = eglGetError()) {
        fprintf(stderr, "after %s() eglError %s (0x%x)\n", op, EGLUtils::strerror(error),
                error);
    }
}

void printEGLConfiguration(EGLDisplay dpy, EGLConfig config) {

#define X(VAL) {VAL, #VAL}
    struct {EGLint attribute; const char* name;} names[] = {
    X(EGL_BUFFER_SIZE),
    X(EGL_ALPHA_SIZE),
    X(EGL_BLUE_SIZE),
    X(EGL_GREEN_SIZE),
    X(EGL_RED_SIZE),
    X(EGL_DEPTH_SIZE),
    X(EGL_STENCIL_SIZE),
    X(EGL_CONFIG_CAVEAT),
    X(EGL_CONFIG_ID),
    X(EGL_LEVEL),
    X(EGL_MAX_PBUFFER_HEIGHT),
    X(EGL_MAX_PBUFFER_PIXELS),
    X(EGL_MAX_PBUFFER_WIDTH),
    X(EGL_NATIVE_RENDERABLE),
    X(EGL_NATIVE_VISUAL_ID),
    X(EGL_NATIVE_VISUAL_TYPE),
    X(EGL_SAMPLES),
    X(EGL_SAMPLE_BUFFERS),
    X(EGL_SURFACE_TYPE),
    X(EGL_TRANSPARENT_TYPE),
    X(EGL_TRANSPARENT_RED_VALUE),
    X(EGL_TRANSPARENT_GREEN_VALUE),
    X(EGL_TRANSPARENT_BLUE_VALUE),
    X(EGL_BIND_TO_TEXTURE_RGB),
    X(EGL_BIND_TO_TEXTURE_RGBA),
    X(EGL_MIN_SWAP_INTERVAL),
    X(EGL_MAX_SWAP_INTERVAL),
    X(EGL_LUMINANCE_SIZE),
    X(EGL_ALPHA_MASK_SIZE),
    X(EGL_COLOR_BUFFER_TYPE),
    X(EGL_RENDERABLE_TYPE),
    X(EGL_CONFORMANT),
   };
#undef X

    for (size_t j = 0; j < sizeof(names) / sizeof(names[0]); j++) {
        EGLint value = -1;
        EGLint returnVal = eglGetConfigAttrib(dpy, config, names[j].attribute, &value);
        EGLint error = eglGetError();
        if (returnVal && error == EGL_SUCCESS) {
            LOGV(" %s: %d (0x%x)", names[j].name, value, value);
        }
    }
}


bool Context::initGLThread() {
    pthread_mutex_lock(&gInitMutex);
    LOGV("initGLThread start %p", this);

    mEGL.mNumConfigs = -1;
    EGLint configAttribs[128];
    EGLint *configAttribsPtr = configAttribs;
    EGLint context_attribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };

    memset(configAttribs, 0, sizeof(configAttribs));

    configAttribsPtr[0] = EGL_SURFACE_TYPE;
    configAttribsPtr[1] = EGL_WINDOW_BIT;
    configAttribsPtr += 2;

    configAttribsPtr[0] = EGL_RENDERABLE_TYPE;
    configAttribsPtr[1] = EGL_OPENGL_ES2_BIT;
    configAttribsPtr += 2;

    if (mUserSurfaceConfig.depthMin > 0) {
        configAttribsPtr[0] = EGL_DEPTH_SIZE;
        configAttribsPtr[1] = mUserSurfaceConfig.depthMin;
        configAttribsPtr += 2;
    }

    if (mDev->mForceSW) {
        configAttribsPtr[0] = EGL_CONFIG_CAVEAT;
        configAttribsPtr[1] = EGL_SLOW_CONFIG;
        configAttribsPtr += 2;
    }

    configAttribsPtr[0] = EGL_NONE;
    rsAssert(configAttribsPtr < (configAttribs + (sizeof(configAttribs) / sizeof(EGLint))));

    LOGV("%p initEGL start", this);
    mEGL.mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    checkEglError("eglGetDisplay");

    eglInitialize(mEGL.mDisplay, &mEGL.mMajorVersion, &mEGL.mMinorVersion);
    checkEglError("eglInitialize");

#if 1
    PixelFormat pf = PIXEL_FORMAT_RGBA_8888;
    if (mUserSurfaceConfig.alphaMin == 0) {
        pf = PIXEL_FORMAT_RGBX_8888;
    }

    status_t err = EGLUtils::selectConfigForPixelFormat(mEGL.mDisplay, configAttribs, pf, &mEGL.mConfig);
    if (err) {
       LOGE("%p, couldn't find an EGLConfig matching the screen format\n", this);
    }
    if (props.mLogVisual) {
        printEGLConfiguration(mEGL.mDisplay, mEGL.mConfig);
    }
#else
    eglChooseConfig(mEGL.mDisplay, configAttribs, &mEGL.mConfig, 1, &mEGL.mNumConfigs);
#endif

    mEGL.mContext = eglCreateContext(mEGL.mDisplay, mEGL.mConfig, EGL_NO_CONTEXT, context_attribs2);
    checkEglError("eglCreateContext");
    if (mEGL.mContext == EGL_NO_CONTEXT) {
        pthread_mutex_unlock(&gInitMutex);
        LOGE("%p, eglCreateContext returned EGL_NO_CONTEXT", this);
        return false;
    }
    gGLContextCount++;


    EGLint pbuffer_attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    mEGL.mSurfaceDefault = eglCreatePbufferSurface(mEGL.mDisplay, mEGL.mConfig, pbuffer_attribs);
    checkEglError("eglCreatePbufferSurface");
    if (mEGL.mSurfaceDefault == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface returned EGL_NO_SURFACE");
        pthread_mutex_unlock(&gInitMutex);
        deinitEGL();
        return false;
    }

    EGLBoolean ret = eglMakeCurrent(mEGL.mDisplay, mEGL.mSurfaceDefault, mEGL.mSurfaceDefault, mEGL.mContext);
    if (ret == EGL_FALSE) {
        LOGE("eglMakeCurrent returned EGL_FALSE");
        checkEglError("eglMakeCurrent", ret);
        pthread_mutex_unlock(&gInitMutex);
        deinitEGL();
        return false;
    }

    mGL.mVersion = glGetString(GL_VERSION);
    mGL.mVendor = glGetString(GL_VENDOR);
    mGL.mRenderer = glGetString(GL_RENDERER);
    mGL.mExtensions = glGetString(GL_EXTENSIONS);

    //LOGV("EGL Version %i %i", mEGL.mMajorVersion, mEGL.mMinorVersion);
    //LOGV("GL Version %s", mGL.mVersion);
    //LOGV("GL Vendor %s", mGL.mVendor);
    //LOGV("GL Renderer %s", mGL.mRenderer);
    //LOGV("GL Extensions %s", mGL.mExtensions);

    const char *verptr = NULL;
    if (strlen((const char *)mGL.mVersion) > 9) {
        if (!memcmp(mGL.mVersion, "OpenGL ES-CM", 12)) {
            verptr = (const char *)mGL.mVersion + 12;
        }
        if (!memcmp(mGL.mVersion, "OpenGL ES ", 10)) {
            verptr = (const char *)mGL.mVersion + 9;
        }
    }

    if (!verptr) {
        LOGE("Error, OpenGL ES Lite not supported");
        pthread_mutex_unlock(&gInitMutex);
        deinitEGL();
        return false;
    } else {
        sscanf(verptr, " %i.%i", &mGL.mMajorVersion, &mGL.mMinorVersion);
    }

    glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &mGL.mMaxVertexAttribs);
    glGetIntegerv(GL_MAX_VERTEX_UNIFORM_VECTORS, &mGL.mMaxVertexUniformVectors);
    glGetIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, &mGL.mMaxVertexTextureUnits);

    glGetIntegerv(GL_MAX_VARYING_VECTORS, &mGL.mMaxVaryingVectors);
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &mGL.mMaxTextureImageUnits);

    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &mGL.mMaxFragmentTextureImageUnits);
    glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &mGL.mMaxFragmentUniformVectors);

    mGL.OES_texture_npot = NULL != strstr((const char *)mGL.mExtensions, "GL_OES_texture_npot");
    mGL.GL_IMG_texture_npot = NULL != strstr((const char *)mGL.mExtensions, "GL_IMG_texture_npot");
    mGL.GL_NV_texture_npot_2D_mipmap = NULL != strstr((const char *)mGL.mExtensions, "GL_NV_texture_npot_2D_mipmap");
    mGL.EXT_texture_max_aniso = 1.0f;
    bool hasAniso = NULL != strstr((const char *)mGL.mExtensions, "GL_EXT_texture_filter_anisotropic");
    if (hasAniso) {
        glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, &mGL.EXT_texture_max_aniso);
    }

    LOGV("initGLThread end %p", this);
    pthread_mutex_unlock(&gInitMutex);
    return true;
}

void Context::deinitEGL() {
    LOGV("%p, deinitEGL", this);

    if (mEGL.mContext != EGL_NO_CONTEXT) {
        eglMakeCurrent(mEGL.mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(mEGL.mDisplay, mEGL.mSurfaceDefault);
        if (mEGL.mSurface != EGL_NO_SURFACE) {
            eglDestroySurface(mEGL.mDisplay, mEGL.mSurface);
        }
        eglDestroyContext(mEGL.mDisplay, mEGL.mContext);
        checkEglError("eglDestroyContext");
    }

    gGLContextCount--;
    if (!gGLContextCount) {
        eglTerminate(mEGL.mDisplay);
    }
}

Context::PushState::PushState(Context *con) {
    mRsc = con;
    if (con->mIsGraphicsContext) {
        mFragment.set(con->getProgramFragment());
        mVertex.set(con->getProgramVertex());
        mStore.set(con->getProgramStore());
        mRaster.set(con->getProgramRaster());
        mFont.set(con->getFont());
    }
}

Context::PushState::~PushState() {
    if (mRsc->mIsGraphicsContext) {
        mRsc->setProgramFragment(mFragment.get());
        mRsc->setProgramVertex(mVertex.get());
        mRsc->setProgramStore(mStore.get());
        mRsc->setProgramRaster(mRaster.get());
        mRsc->setFont(mFont.get());
    }
}


uint32_t Context::runScript(Script *s) {
    PushState(this);

    uint32_t ret = s->run(this);
    return ret;
}

void Context::checkError(const char *msg, bool isFatal) const {

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        char buf[1024];
        snprintf(buf, sizeof(buf), "GL Error = 0x%08x, from: %s", err, msg);

        if (isFatal) {
            setError(RS_ERROR_FATAL_DRIVER, buf);
        } else {
            switch (err) {
            case GL_OUT_OF_MEMORY:
                setError(RS_ERROR_OUT_OF_MEMORY, buf);
                break;
            default:
                setError(RS_ERROR_DRIVER, buf);
                break;
            }
        }

        LOGE("%p, %s", this, buf);
    }
}

uint32_t Context::runRootScript() {
    glViewport(0, 0, mWidth, mHeight);

    timerSet(RS_TIMER_SCRIPT);
    mStateFragmentStore.mLast.clear();
    uint32_t ret = runScript(mRootScript.get());

    checkError("runRootScript");
    return ret;
}

uint64_t Context::getTime() const {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_nsec + ((uint64_t)t.tv_sec * 1000 * 1000 * 1000);
}

void Context::timerReset() {
    for (int ct=0; ct < _RS_TIMER_TOTAL; ct++) {
        mTimers[ct] = 0;
    }
}

void Context::timerInit() {
    mTimeLast = getTime();
    mTimeFrame = mTimeLast;
    mTimeLastFrame = mTimeLast;
    mTimerActive = RS_TIMER_INTERNAL;
    mAverageFPSFrameCount = 0;
    mAverageFPSStartTime = mTimeLast;
    mAverageFPS = 0;
    timerReset();
}

void Context::timerFrame() {
    mTimeLastFrame = mTimeFrame;
    mTimeFrame = getTime();
    // Update average fps
    const uint64_t averageFramerateInterval = 1000 * 1000000;
    mAverageFPSFrameCount ++;
    uint64_t inverval = mTimeFrame - mAverageFPSStartTime;
    if (inverval >= averageFramerateInterval) {
        inverval = inverval / 1000000;
        mAverageFPS = (mAverageFPSFrameCount * 1000) / inverval;
        mAverageFPSFrameCount = 0;
        mAverageFPSStartTime = mTimeFrame;
    }
}

void Context::timerSet(Timers tm) {
    uint64_t last = mTimeLast;
    mTimeLast = getTime();
    mTimers[mTimerActive] += mTimeLast - last;
    mTimerActive = tm;
}

void Context::timerPrint() {
    double total = 0;
    for (int ct = 0; ct < _RS_TIMER_TOTAL; ct++) {
        total += mTimers[ct];
    }
    uint64_t frame = mTimeFrame - mTimeLastFrame;
    mTimeMSLastFrame = frame / 1000000;
    mTimeMSLastScript = mTimers[RS_TIMER_SCRIPT] / 1000000;
    mTimeMSLastSwap = mTimers[RS_TIMER_CLEAR_SWAP] / 1000000;


    if (props.mLogTimes) {
        LOGV("RS: Frame (%i),   Script %2.1f%% (%i),  Swap %2.1f%% (%i),  Idle %2.1f%% (%lli),  Internal %2.1f%% (%lli), Avg fps: %u",
             mTimeMSLastFrame,
             100.0 * mTimers[RS_TIMER_SCRIPT] / total, mTimeMSLastScript,
             100.0 * mTimers[RS_TIMER_CLEAR_SWAP] / total, mTimeMSLastSwap,
             100.0 * mTimers[RS_TIMER_IDLE] / total, mTimers[RS_TIMER_IDLE] / 1000000,
             100.0 * mTimers[RS_TIMER_INTERNAL] / total, mTimers[RS_TIMER_INTERNAL] / 1000000,
             mAverageFPS);
    }
}

bool Context::setupCheck() {
    if (!mShaderCache.lookup(this, mVertex.get(), mFragment.get())) {
        LOGE("Context::setupCheck() 1 fail");
        return false;
    }

    mFragmentStore->setupGL2(this, &mStateFragmentStore);
    mFragment->setupGL2(this, &mStateFragment, &mShaderCache);
    mRaster->setupGL2(this, &mStateRaster);
    mVertex->setupGL2(this, &mStateVertex, &mShaderCache);
    return true;
}

void Context::setupProgramStore() {
    mFragmentStore->setupGL2(this, &mStateFragmentStore);
}

static bool getProp(const char *str) {
    char buf[PROPERTY_VALUE_MAX];
    property_get(str, buf, "0");
    return 0 != strcmp(buf, "0");
}

void Context::displayDebugStats() {
    char buffer[128];
    sprintf(buffer, "Avg fps %u, Frame %i ms, Script %i ms", mAverageFPS, mTimeMSLastFrame, mTimeMSLastScript);
    float oldR, oldG, oldB, oldA;
    mStateFont.getFontColor(&oldR, &oldG, &oldB, &oldA);
    uint32_t bufferLen = strlen(buffer);

    float shadowCol = 0.1f;
    mStateFont.setFontColor(shadowCol, shadowCol, shadowCol, 1.0f);
    mStateFont.renderText(buffer, bufferLen, 5, getHeight() - 6);

    mStateFont.setFontColor(1.0f, 0.7f, 0.0f, 1.0f);
    mStateFont.renderText(buffer, bufferLen, 4, getHeight() - 7);

    mStateFont.setFontColor(oldR, oldG, oldB, oldA);
}

void * Context::threadProc(void *vrsc) {
     Context *rsc = static_cast<Context *>(vrsc);
     rsc->mNativeThreadId = gettid();

     setpriority(PRIO_PROCESS, rsc->mNativeThreadId, ANDROID_PRIORITY_DISPLAY);
     rsc->mThreadPriority = ANDROID_PRIORITY_DISPLAY;

     rsc->props.mLogTimes = getProp("debug.rs.profile");
     rsc->props.mLogScripts = getProp("debug.rs.script");
     rsc->props.mLogObjects = getProp("debug.rs.object");
     rsc->props.mLogShaders = getProp("debug.rs.shader");
     rsc->props.mLogShadersAttr = getProp("debug.rs.shader.attributes");
     rsc->props.mLogShadersUniforms = getProp("debug.rs.shader.uniforms");
     rsc->props.mLogVisual = getProp("debug.rs.visual");

     rsc->mTlsStruct = new ScriptTLSStruct;
     if (!rsc->mTlsStruct) {
         LOGE("Error allocating tls storage");
         rsc->setError(RS_ERROR_OUT_OF_MEMORY, "Failed allocation for TLS");
         return NULL;
     }
     rsc->mTlsStruct->mContext = rsc;
     rsc->mTlsStruct->mScript = NULL;
     int status = pthread_setspecific(rsc->gThreadTLSKey, rsc->mTlsStruct);
     if (status) {
         LOGE("pthread_setspecific %i", status);
     }

     if (!rsc->initGLThread()) {
         rsc->setError(RS_ERROR_OUT_OF_MEMORY, "Failed initializing GL");
         return NULL;
     }

     if (rsc->mIsGraphicsContext) {
         rsc->mStateRaster.init(rsc);
         rsc->setProgramRaster(NULL);
         rsc->mStateVertex.init(rsc);
         rsc->setProgramVertex(NULL);
         rsc->mStateFragment.init(rsc);
         rsc->setProgramFragment(NULL);
         rsc->mStateFragmentStore.init(rsc);
         rsc->setProgramStore(NULL);
         rsc->mStateFont.init(rsc);
         rsc->setFont(NULL);
         rsc->mStateVertexArray.init(rsc);
     }

     rsc->mRunning = true;
     bool mDraw = true;
     while (!rsc->mExit) {
         mDraw |= rsc->mIO.playCoreCommands(rsc, !mDraw);
         mDraw &= (rsc->mRootScript.get() != NULL);
         mDraw &= (rsc->mWndSurface != NULL);

         uint32_t targetTime = 0;
         if (mDraw && rsc->mIsGraphicsContext) {
             targetTime = rsc->runRootScript();

             if (rsc->props.mLogVisual) {
                 rsc->displayDebugStats();
             }

             mDraw = targetTime && !rsc->mPaused;
             rsc->timerSet(RS_TIMER_CLEAR_SWAP);
             eglSwapBuffers(rsc->mEGL.mDisplay, rsc->mEGL.mSurface);
             rsc->timerFrame();
             rsc->timerSet(RS_TIMER_INTERNAL);
             rsc->timerPrint();
             rsc->timerReset();
         }
         if (targetTime > 1) {
             int32_t t = (targetTime - (int32_t)(rsc->mTimeMSLastScript + rsc->mTimeMSLastSwap)) * 1000;
             if (t > 0) {
                 usleep(t);
             }
         }
     }

     LOGV("%p, RS Thread exiting", rsc);

     if (rsc->mIsGraphicsContext) {
         pthread_mutex_lock(&gInitMutex);
         rsc->deinitEGL();
         pthread_mutex_unlock(&gInitMutex);
     }
     delete rsc->mTlsStruct;

     LOGV("%p, RS Thread exited", rsc);
     return NULL;
}

void Context::destroyWorkerThreadResources() {
    //LOGV("destroyWorkerThreadResources 1");
    ObjectBase::zeroAllUserRef(this);
    if (mIsGraphicsContext) {
         mRaster.clear();
         mFragment.clear();
         mVertex.clear();
         mFragmentStore.clear();
         mFont.clear();
         mRootScript.clear();
         mStateRaster.deinit(this);
         mStateVertex.deinit(this);
         mStateFragment.deinit(this);
         mStateFragmentStore.deinit(this);
         mStateFont.deinit(this);
         mShaderCache.cleanupAll();
    }
    //LOGV("destroyWorkerThreadResources 2");
    mExit = true;
}

void * Context::helperThreadProc(void *vrsc) {
     Context *rsc = static_cast<Context *>(vrsc);
     uint32_t idx = (uint32_t)android_atomic_inc(&rsc->mWorkers.mLaunchCount);

     //LOGV("RS helperThread starting %p idx=%i", rsc, idx);

     rsc->mWorkers.mLaunchSignals[idx].init();
     rsc->mWorkers.mNativeThreadId[idx] = gettid();

#if 0
     typedef struct {uint64_t bits[1024 / 64]; } cpu_set_t;
     cpu_set_t cpuset;
     memset(&cpuset, 0, sizeof(cpuset));
     cpuset.bits[idx / 64] |= 1ULL << (idx % 64);
     int ret = syscall(241, rsc->mWorkers.mNativeThreadId[idx],
               sizeof(cpuset), &cpuset);
     LOGE("SETAFFINITY ret = %i %s", ret, EGLUtils::strerror(ret));
#endif

     setpriority(PRIO_PROCESS, rsc->mWorkers.mNativeThreadId[idx], rsc->mThreadPriority);
     int status = pthread_setspecific(rsc->gThreadTLSKey, rsc->mTlsStruct);
     if (status) {
         LOGE("pthread_setspecific %i", status);
     }

     while (!rsc->mExit) {
         rsc->mWorkers.mLaunchSignals[idx].wait();
         if (rsc->mWorkers.mLaunchCallback) {
            rsc->mWorkers.mLaunchCallback(rsc->mWorkers.mLaunchData, idx);
         }
         android_atomic_dec(&rsc->mWorkers.mRunningCount);
         rsc->mWorkers.mCompleteSignal.set();
     }

     //LOGV("RS helperThread exited %p idx=%i", rsc, idx);
     return NULL;
}

void Context::launchThreads(WorkerCallback_t cbk, void *data) {
    mWorkers.mLaunchData = data;
    mWorkers.mLaunchCallback = cbk;
    android_atomic_release_store(mWorkers.mCount, &mWorkers.mRunningCount);
    for (uint32_t ct = 0; ct < mWorkers.mCount; ct++) {
        mWorkers.mLaunchSignals[ct].set();
    }
    while (android_atomic_acquire_load(&mWorkers.mRunningCount) != 0) {
        mWorkers.mCompleteSignal.wait();
    }
}

void Context::setPriority(int32_t p) {
    // Note: If we put this in the proper "background" policy
    // the wallpapers can become completly unresponsive at times.
    // This is probably not what we want for something the user is actively
    // looking at.
    mThreadPriority = p;
#if 0
    SchedPolicy pol = SP_FOREGROUND;
    if (p > 0) {
        pol = SP_BACKGROUND;
    }
    if (!set_sched_policy(mNativeThreadId, pol)) {
        // success; reset the priority as well
    }
#else
    setpriority(PRIO_PROCESS, mNativeThreadId, p);
    for (uint32_t ct=0; ct < mWorkers.mCount; ct++) {
        setpriority(PRIO_PROCESS, mWorkers.mNativeThreadId[ct], p);
    }
#endif
}

Context::Context() {
    mDev = NULL;
    mRunning = false;
    mExit = false;
    mPaused = false;
    mObjHead = NULL;
    mError = RS_ERROR_NONE;
}

Context * Context::createContext(Device *dev, const RsSurfaceConfig *sc) {
    Context * rsc = new Context();
    if (!rsc->initContext(dev, sc)) {
        delete rsc;
        return NULL;
    }
    return rsc;
}

bool Context::initContext(Device *dev, const RsSurfaceConfig *sc) {
    pthread_mutex_lock(&gInitMutex);

    dev->addContext(this);
    mDev = dev;
    if (sc) {
        mUserSurfaceConfig = *sc;
    } else {
        memset(&mUserSurfaceConfig, 0, sizeof(mUserSurfaceConfig));
    }

    memset(&mEGL, 0, sizeof(mEGL));
    memset(&mGL, 0, sizeof(mGL));
    mIsGraphicsContext = sc != NULL;

    int status;
    pthread_attr_t threadAttr;

    if (!gThreadTLSKeyCount) {
        status = pthread_key_create(&gThreadTLSKey, NULL);
        if (status) {
            LOGE("Failed to init thread tls key.");
            pthread_mutex_unlock(&gInitMutex);
            return false;
        }
    }
    gThreadTLSKeyCount++;

    pthread_mutex_unlock(&gInitMutex);

    // Global init done at this point.

    status = pthread_attr_init(&threadAttr);
    if (status) {
        LOGE("Failed to init thread attribute.");
        return false;
    }

    mWndSurface = NULL;

    timerInit();
    timerSet(RS_TIMER_INTERNAL);

    int cpu = sysconf(_SC_NPROCESSORS_ONLN);
    LOGV("RS Launching thread(s), reported CPU count %i", cpu);
    if (cpu < 2) cpu = 0;

    mWorkers.mCount = (uint32_t)cpu;
    mWorkers.mThreadId = (pthread_t *) calloc(mWorkers.mCount, sizeof(pthread_t));
    mWorkers.mNativeThreadId = (pid_t *) calloc(mWorkers.mCount, sizeof(pid_t));
    mWorkers.mLaunchSignals = new Signal[mWorkers.mCount];
    mWorkers.mLaunchCallback = NULL;
    status = pthread_create(&mThreadId, &threadAttr, threadProc, this);
    if (status) {
        LOGE("Failed to start rs context thread.");
        return false;
    }
    while (!mRunning && (mError == RS_ERROR_NONE)) {
        usleep(100);
    }

    if (mError != RS_ERROR_NONE) {
        return false;
    }

    mWorkers.mCompleteSignal.init();
    android_atomic_release_store(mWorkers.mCount, &mWorkers.mRunningCount);
    android_atomic_release_store(0, &mWorkers.mLaunchCount);
    for (uint32_t ct=0; ct < mWorkers.mCount; ct++) {
        status = pthread_create(&mWorkers.mThreadId[ct], &threadAttr, helperThreadProc, this);
        if (status) {
            mWorkers.mCount = ct;
            LOGE("Created fewer than expected number of RS threads.");
            break;
        }
    }
    while (android_atomic_acquire_load(&mWorkers.mRunningCount) != 0) {
        usleep(100);
    }
    pthread_attr_destroy(&threadAttr);
    return true;
}

Context::~Context() {
    LOGV("Context::~Context");

    mIO.mToCore.flush();
    rsAssert(mExit);
    mExit = true;
    mPaused = false;
    void *res;

    mIO.shutdown();
    int status = pthread_join(mThreadId, &res);

    // Cleanup compute threads.
    mWorkers.mLaunchData = NULL;
    mWorkers.mLaunchCallback = NULL;
    android_atomic_release_store(mWorkers.mCount, &mWorkers.mRunningCount);
    for (uint32_t ct = 0; ct < mWorkers.mCount; ct++) {
        mWorkers.mLaunchSignals[ct].set();
    }
    for (uint32_t ct = 0; ct < mWorkers.mCount; ct++) {
        status = pthread_join(mWorkers.mThreadId[ct], &res);
    }
    rsAssert(android_atomic_acquire_load(&mWorkers.mRunningCount) == 0);

    // Global structure cleanup.
    pthread_mutex_lock(&gInitMutex);
    if (mDev) {
        mDev->removeContext(this);
        --gThreadTLSKeyCount;
        if (!gThreadTLSKeyCount) {
            pthread_key_delete(gThreadTLSKey);
        }
        mDev = NULL;
    }
    pthread_mutex_unlock(&gInitMutex);
    LOGV("Context::~Context done");
}

void Context::setSurface(uint32_t w, uint32_t h, ANativeWindow *sur) {
    rsAssert(mIsGraphicsContext);

    EGLBoolean ret;
    // WAR: Some drivers fail to handle 0 size surfaces correcntly.
    // Use the pbuffer to avoid this pitfall.
    if ((mEGL.mSurface != NULL) || (w == 0) || (h == 0)) {
        ret = eglMakeCurrent(mEGL.mDisplay, mEGL.mSurfaceDefault, mEGL.mSurfaceDefault, mEGL.mContext);
        checkEglError("eglMakeCurrent", ret);

        ret = eglDestroySurface(mEGL.mDisplay, mEGL.mSurface);
        checkEglError("eglDestroySurface", ret);

        mEGL.mSurface = NULL;
        mWidth = 1;
        mHeight = 1;
    }

    mWndSurface = sur;
    if (mWndSurface != NULL) {
        mWidth = w;
        mHeight = h;

        mEGL.mSurface = eglCreateWindowSurface(mEGL.mDisplay, mEGL.mConfig, mWndSurface, NULL);
        checkEglError("eglCreateWindowSurface");
        if (mEGL.mSurface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface returned EGL_NO_SURFACE");
        }

        ret = eglMakeCurrent(mEGL.mDisplay, mEGL.mSurface, mEGL.mSurface, mEGL.mContext);
        checkEglError("eglMakeCurrent", ret);

        mStateVertex.updateSize(this);
    }
}

void Context::pause() {
    rsAssert(mIsGraphicsContext);
    mPaused = true;
}

void Context::resume() {
    rsAssert(mIsGraphicsContext);
    mPaused = false;
}

void Context::setRootScript(Script *s) {
    rsAssert(mIsGraphicsContext);
    mRootScript.set(s);
}

void Context::setProgramStore(ProgramStore *pfs) {
    rsAssert(mIsGraphicsContext);
    if (pfs == NULL) {
        mFragmentStore.set(mStateFragmentStore.mDefault);
    } else {
        mFragmentStore.set(pfs);
    }
}

void Context::setProgramFragment(ProgramFragment *pf) {
    rsAssert(mIsGraphicsContext);
    if (pf == NULL) {
        mFragment.set(mStateFragment.mDefault);
    } else {
        mFragment.set(pf);
    }
}

void Context::setProgramRaster(ProgramRaster *pr) {
    rsAssert(mIsGraphicsContext);
    if (pr == NULL) {
        mRaster.set(mStateRaster.mDefault);
    } else {
        mRaster.set(pr);
    }
}

void Context::setProgramVertex(ProgramVertex *pv) {
    rsAssert(mIsGraphicsContext);
    if (pv == NULL) {
        mVertex.set(mStateVertex.mDefault);
    } else {
        mVertex.set(pv);
    }
}

void Context::setFont(Font *f) {
    rsAssert(mIsGraphicsContext);
    if (f == NULL) {
        mFont.set(mStateFont.mDefault);
    } else {
        mFont.set(f);
    }
}

void Context::assignName(ObjectBase *obj, const char *name, uint32_t len) {
    rsAssert(!obj->getName());
    obj->setName(name, len);
    mNames.add(obj);
}

void Context::removeName(ObjectBase *obj) {
    for (size_t ct=0; ct < mNames.size(); ct++) {
        if (obj == mNames[ct]) {
            mNames.removeAt(ct);
            return;
        }
    }
}

RsMessageToClientType Context::peekMessageToClient(size_t *receiveLen, uint32_t *subID, bool wait) {
    *receiveLen = 0;
    if (!wait && mIO.mToClient.isEmpty()) {
        return RS_MESSAGE_TO_CLIENT_NONE;
    }

    uint32_t bytesData = 0;
    uint32_t commandID = 0;
    const uint32_t *d = (const uint32_t *)mIO.mToClient.get(&commandID, &bytesData);
    *receiveLen = bytesData - sizeof(uint32_t);
    if (bytesData) {
        *subID = d[0];
    }
    return (RsMessageToClientType)commandID;
}

RsMessageToClientType Context::getMessageToClient(void *data, size_t *receiveLen, uint32_t *subID, size_t bufferLen, bool wait) {
    //LOGE("getMessageToClient %i %i", bufferLen, wait);
    *receiveLen = 0;
    if (!wait && mIO.mToClient.isEmpty()) {
        return RS_MESSAGE_TO_CLIENT_NONE;
    }

    //LOGE("getMessageToClient 2 con=%p", this);
    uint32_t bytesData = 0;
    uint32_t commandID = 0;
    const uint32_t *d = (const uint32_t *)mIO.mToClient.get(&commandID, &bytesData);
    //LOGE("getMessageToClient 3    %i  %i", commandID, bytesData);

    *receiveLen = bytesData - sizeof(uint32_t);
    *subID = d[0];

    //LOGE("getMessageToClient  %i %i", commandID, *subID);
    if (bufferLen >= bytesData) {
        memcpy(data, d+1, *receiveLen);
        mIO.mToClient.next();
        return (RsMessageToClientType)commandID;
    }
    return RS_MESSAGE_TO_CLIENT_RESIZE;
}

bool Context::sendMessageToClient(const void *data, RsMessageToClientType cmdID,
                                  uint32_t subID, size_t len, bool waitForSpace) const {
    //LOGE("sendMessageToClient %i %i %i %i", cmdID, subID, len, waitForSpace);
    if (cmdID == 0) {
        LOGE("Attempting to send invalid command 0 to client.");
        return false;
    }
    if (!waitForSpace) {
        if (!mIO.mToClient.makeSpaceNonBlocking(len + 12)) {
            // Not enough room, and not waiting.
            return false;
        }
    }
    //LOGE("sendMessageToClient 2");
    uint32_t *p = (uint32_t *)mIO.mToClient.reserve(len + sizeof(subID));
    p[0] = subID;
    if (len > 0) {
        memcpy(p+1, data, len);
    }
    mIO.mToClient.commit(cmdID, len + sizeof(subID));
    //LOGE("sendMessageToClient 3");
    return true;
}

void Context::initToClient() {
    while (!mRunning) {
        usleep(100);
    }
}

void Context::deinitToClient() {
    mIO.mToClient.shutdown();
}

void Context::setError(RsError e, const char *msg) const {
    mError = e;
    sendMessageToClient(msg, RS_MESSAGE_TO_CLIENT_ERROR, e, strlen(msg) + 1, true);
}


void Context::dumpDebug() const {
    LOGE("RS Context debug %p", this);
    LOGE("RS Context debug");

    LOGE(" EGL ver %i %i", mEGL.mMajorVersion, mEGL.mMinorVersion);
    LOGE(" EGL context %p  surface %p,  Display=%p", mEGL.mContext, mEGL.mSurface, mEGL.mDisplay);
    LOGE(" GL vendor: %s", mGL.mVendor);
    LOGE(" GL renderer: %s", mGL.mRenderer);
    LOGE(" GL Version: %s", mGL.mVersion);
    LOGE(" GL Extensions: %s", mGL.mExtensions);
    LOGE(" GL int Versions %i %i", mGL.mMajorVersion, mGL.mMinorVersion);
    LOGE(" RS width %i, height %i", mWidth, mHeight);
    LOGE(" RS running %i, exit %i, paused %i", mRunning, mExit, mPaused);
    LOGE(" RS pThreadID %li, nativeThreadID %i", mThreadId, mNativeThreadId);

    LOGV("MAX Textures %i, %i  %i", mGL.mMaxVertexTextureUnits, mGL.mMaxFragmentTextureImageUnits, mGL.mMaxTextureImageUnits);
    LOGV("MAX Attribs %i", mGL.mMaxVertexAttribs);
    LOGV("MAX Uniforms %i, %i", mGL.mMaxVertexUniformVectors, mGL.mMaxFragmentUniformVectors);
    LOGV("MAX Varyings %i", mGL.mMaxVaryingVectors);
}

///////////////////////////////////////////////////////////////////////////////////////////
//

namespace android {
namespace renderscript {

void rsi_ContextFinish(Context *rsc) {
}

void rsi_ContextBindRootScript(Context *rsc, RsScript vs) {
    Script *s = static_cast<Script *>(vs);
    rsc->setRootScript(s);
}

void rsi_ContextBindSampler(Context *rsc, uint32_t slot, RsSampler vs) {
    Sampler *s = static_cast<Sampler *>(vs);

    if (slot > RS_MAX_SAMPLER_SLOT) {
        LOGE("Invalid sampler slot");
        return;
    }

    s->bindToContext(&rsc->mStateSampler, slot);
}

void rsi_ContextBindProgramStore(Context *rsc, RsProgramStore vpfs) {
    ProgramStore *pfs = static_cast<ProgramStore *>(vpfs);
    rsc->setProgramStore(pfs);
}

void rsi_ContextBindProgramFragment(Context *rsc, RsProgramFragment vpf) {
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    rsc->setProgramFragment(pf);
}

void rsi_ContextBindProgramRaster(Context *rsc, RsProgramRaster vpr) {
    ProgramRaster *pr = static_cast<ProgramRaster *>(vpr);
    rsc->setProgramRaster(pr);
}

void rsi_ContextBindProgramVertex(Context *rsc, RsProgramVertex vpv) {
    ProgramVertex *pv = static_cast<ProgramVertex *>(vpv);
    rsc->setProgramVertex(pv);
}

void rsi_ContextBindFont(Context *rsc, RsFont vfont) {
    Font *font = static_cast<Font *>(vfont);
    rsc->setFont(font);
}

void rsi_AssignName(Context *rsc, void * obj, const char *name, uint32_t len) {
    ObjectBase *ob = static_cast<ObjectBase *>(obj);
    rsc->assignName(ob, name, len);
}

void rsi_ObjDestroy(Context *rsc, void *optr) {
    ObjectBase *ob = static_cast<ObjectBase *>(optr);
    rsc->removeName(ob);
    ob->decUserRef();
}

void rsi_ContextPause(Context *rsc) {
    rsc->pause();
}

void rsi_ContextResume(Context *rsc) {
    rsc->resume();
}

void rsi_ContextSetSurface(Context *rsc, uint32_t w, uint32_t h, ANativeWindow *sur) {
    rsc->setSurface(w, h, sur);
}

void rsi_ContextSetPriority(Context *rsc, int32_t p) {
    rsc->setPriority(p);
}

void rsi_ContextDump(Context *rsc, int32_t bits) {
    ObjectBase::dumpAll(rsc);
}

void rsi_ContextDestroyWorker(Context *rsc) {
    rsc->destroyWorkerThreadResources();;
}

}
}

void rsContextDestroy(RsContext vcon) {
    LOGV("rsContextDestroy %p", vcon);
    Context *rsc = static_cast<Context *>(vcon);
    rsContextDestroyWorker(rsc);
    delete rsc;
    LOGV("rsContextDestroy 2 %p", vcon);
}

RsContext rsContextCreate(RsDevice vdev, uint32_t version) {
    LOGV("rsContextCreate %p", vdev);
    Device * dev = static_cast<Device *>(vdev);
    Context *rsc = Context::createContext(dev, NULL);
    return rsc;
}

RsContext rsContextCreateGL(RsDevice vdev, uint32_t version, RsSurfaceConfig sc) {
    LOGV("rsContextCreateGL %p", vdev);
    Device * dev = static_cast<Device *>(vdev);
    Context *rsc = Context::createContext(dev, &sc);
    LOGV("rsContextCreateGL ret %p ", rsc);
    return rsc;
}

RsMessageToClientType rsContextPeekMessage(RsContext vrsc, size_t *receiveLen, uint32_t *subID, bool wait) {
    Context * rsc = static_cast<Context *>(vrsc);
    return rsc->peekMessageToClient(receiveLen, subID, wait);
}

RsMessageToClientType rsContextGetMessage(RsContext vrsc, void *data, size_t *receiveLen, uint32_t *subID, size_t bufferLen, bool wait) {
    Context * rsc = static_cast<Context *>(vrsc);
    return rsc->getMessageToClient(data, receiveLen, subID, bufferLen, wait);
}

void rsContextInitToClient(RsContext vrsc) {
    Context * rsc = static_cast<Context *>(vrsc);
    rsc->initToClient();
}

void rsContextDeinitToClient(RsContext vrsc) {
    Context * rsc = static_cast<Context *>(vrsc);
    rsc->deinitToClient();
}

// Only to be called at a3d load time, before object is visible to user
// not thread safe
void rsaGetName(RsContext con, void * obj, const char **name) {
    ObjectBase *ob = static_cast<ObjectBase *>(obj);
    (*name) = ob->getName();
}
