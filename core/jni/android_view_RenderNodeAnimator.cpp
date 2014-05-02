/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <Animator.h>
#include <Interpolator.h>
#include <RenderProperties.h>

namespace android {

using namespace uirenderer;

static struct {
    jclass clazz;

    jmethodID callOnFinished;
} gRenderNodeAnimatorClassInfo;

#ifdef USE_OPENGL_RENDERER

static JNIEnv* getEnv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }
    return env;
}

class AnimationListenerBridge : public AnimationListener {
public:
    // This holds a strong reference to a Java WeakReference<T> object. This avoids
    // cyclic-references-of-doom. If you think "I know, just use NewWeakGlobalRef!"
    // then you end up with basically a PhantomReference, which is totally not
    // what we want.
    AnimationListenerBridge(JNIEnv* env, jobject weakThis) {
        mWeakThis = env->NewGlobalRef(weakThis);
        env->GetJavaVM(&mJvm);
    }

    virtual ~AnimationListenerBridge() {
        JNIEnv* env = getEnv(mJvm);
        env->DeleteGlobalRef(mWeakThis);
        mWeakThis = NULL;
    }

    virtual void onAnimationFinished(BaseAnimator*) {
        JNIEnv* env = getEnv(mJvm);
        env->CallStaticVoidMethod(
                gRenderNodeAnimatorClassInfo.clazz,
                gRenderNodeAnimatorClassInfo.callOnFinished,
                mWeakThis);
    }

private:
    JavaVM* mJvm;
    jobject mWeakThis;
};

static inline RenderPropertyAnimator::RenderProperty toRenderProperty(jint property) {
    LOG_ALWAYS_FATAL_IF(property < 0 || property > RenderPropertyAnimator::ALPHA,
            "Invalid property %d", property);
    return static_cast<RenderPropertyAnimator::RenderProperty>(property);
}

static inline RenderPropertyAnimator::DeltaValueType toDeltaType(jint deltaType) {
    LOG_ALWAYS_FATAL_IF(deltaType != RenderPropertyAnimator::DELTA
            && deltaType != RenderPropertyAnimator::ABSOLUTE,
            "Invalid delta type %d", deltaType);
    return static_cast<RenderPropertyAnimator::DeltaValueType>(deltaType);
}

static inline CanvasPropertyPaintAnimator::PaintField toPaintField(jint field) {
    LOG_ALWAYS_FATAL_IF(field < 0
            || field > CanvasPropertyPaintAnimator::ALPHA,
            "Invalid paint field %d", field);
    return static_cast<CanvasPropertyPaintAnimator::PaintField>(field);
}

static jlong createAnimator(JNIEnv* env, jobject clazz, jobject weakThis,
        jint propertyRaw, jint deltaTypeRaw, jfloat deltaValue) {
    RenderPropertyAnimator::RenderProperty property = toRenderProperty(propertyRaw);
    RenderPropertyAnimator::DeltaValueType deltaType = toDeltaType(deltaTypeRaw);

    BaseAnimator* animator = new RenderPropertyAnimator(property, deltaType, deltaValue);
    animator->incStrong(0);
    animator->setListener(new AnimationListenerBridge(env, weakThis));
    return reinterpret_cast<jlong>( animator );
}

static jlong createCanvasPropertyFloatAnimator(JNIEnv* env, jobject clazz,
        jobject weakThis, jlong canvasPropertyPtr, jint deltaTypeRaw, jfloat deltaValue) {
    RenderPropertyAnimator::DeltaValueType deltaType = toDeltaType(deltaTypeRaw);
    CanvasPropertyPrimitive* canvasProperty = reinterpret_cast<CanvasPropertyPrimitive*>(canvasPropertyPtr);
    BaseAnimator* animator = new CanvasPropertyPrimitiveAnimator(canvasProperty, deltaType, deltaValue);
    animator->incStrong(0);
    animator->setListener(new AnimationListenerBridge(env, weakThis));
    return reinterpret_cast<jlong>( animator );
}

static jlong createCanvasPropertyPaintAnimator(JNIEnv* env, jobject clazz,
        jobject weakThis, jlong canvasPropertyPtr, jint paintFieldRaw,
        jint deltaTypeRaw, jfloat deltaValue) {
    RenderPropertyAnimator::DeltaValueType deltaType = toDeltaType(deltaTypeRaw);
    CanvasPropertyPaint* canvasProperty = reinterpret_cast<CanvasPropertyPaint*>(canvasPropertyPtr);
    CanvasPropertyPaintAnimator::PaintField paintField = toPaintField(paintFieldRaw);
    BaseAnimator* animator = new CanvasPropertyPaintAnimator(
            canvasProperty, paintField, deltaType, deltaValue);
    animator->incStrong(0);
    animator->setListener(new AnimationListenerBridge(env, weakThis));
    return reinterpret_cast<jlong>( animator );
}

static void setDuration(JNIEnv* env, jobject clazz, jlong animatorPtr, jint duration) {
    LOG_ALWAYS_FATAL_IF(duration < 0, "Duration cannot be negative");
    BaseAnimator* animator = reinterpret_cast<BaseAnimator*>(animatorPtr);
    animator->setDuration(duration);
}

static void unref(JNIEnv* env, jobject clazz, jlong objPtr) {
    VirtualLightRefBase* obj = reinterpret_cast<VirtualLightRefBase*>(objPtr);
    obj->decStrong(0);
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RenderNodeAnimator";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreateAnimator", "(Ljava/lang/ref/WeakReference;IIF)J", (void*) createAnimator },
    { "nCreateCanvasPropertyFloatAnimator", "(Ljava/lang/ref/WeakReference;JIF)J", (void*) createCanvasPropertyFloatAnimator },
    { "nCreateCanvasPropertyPaintAnimator", "(Ljava/lang/ref/WeakReference;JIIF)J", (void*) createCanvasPropertyPaintAnimator },
    { "nSetDuration", "(JI)V", (void*) setDuration },
    { "nUnref", "(J)V", (void*) unref },
#endif
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_STATIC_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_RenderNodeAnimator(JNIEnv* env) {
    FIND_CLASS(gRenderNodeAnimatorClassInfo.clazz, kClassPathName);
    gRenderNodeAnimatorClassInfo.clazz = jclass(env->NewGlobalRef(gRenderNodeAnimatorClassInfo.clazz));

    GET_STATIC_METHOD_ID(gRenderNodeAnimatorClassInfo.callOnFinished, gRenderNodeAnimatorClassInfo.clazz,
            "callOnFinished", "(Ljava/lang/ref/WeakReference;)V");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}


} // namespace android
