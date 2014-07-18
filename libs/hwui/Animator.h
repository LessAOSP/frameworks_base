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
#ifndef ANIMATOR_H
#define ANIMATOR_H

#include <cutils/compiler.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include "CanvasProperty.h"
#include "Interpolator.h"
#include "TreeInfo.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class RenderNode;
class RenderProperties;

class AnimationListener : public VirtualLightRefBase {
public:
    ANDROID_API virtual void onAnimationFinished(BaseRenderNodeAnimator*) = 0;
protected:
    ANDROID_API virtual ~AnimationListener() {}
};

class BaseRenderNodeAnimator : public VirtualLightRefBase {
    PREVENT_COPY_AND_ASSIGN(BaseRenderNodeAnimator);
public:
    ANDROID_API void setStartValue(float value);
    ANDROID_API void setInterpolator(Interpolator* interpolator);
    ANDROID_API void setDuration(nsecs_t durationInMs);
    ANDROID_API nsecs_t duration() { return mDuration; }
    ANDROID_API void setStartDelay(nsecs_t startDelayInMs);
    ANDROID_API nsecs_t startDelay() { return mStartDelay; }
    ANDROID_API void setListener(AnimationListener* listener) {
        mListener = listener;
    }
    ANDROID_API void start() { mStagingPlayState = RUNNING; onStagingPlayStateChanged(); }
    ANDROID_API void end() { mStagingPlayState = FINISHED; onStagingPlayStateChanged(); }

    void attach(RenderNode* target);
    virtual void onAttached() {}
    void detach() { mTarget = 0; }
    void pushStaging(TreeInfo& info);
    bool animate(TreeInfo& info);

    bool isFinished() { return mPlayState == FINISHED; }
    float finalValue() { return mFinalValue; }

    ANDROID_API virtual uint32_t dirtyMask() { return 0; }

protected:
    BaseRenderNodeAnimator(float finalValue);
    virtual ~BaseRenderNodeAnimator();

    virtual float getValue(RenderNode* target) const = 0;
    virtual void setValue(RenderNode* target, float value) = 0;
    RenderNode* target() { return mTarget; }

    void callOnFinishedListener(TreeInfo& info);

    virtual void onStagingPlayStateChanged() {}

    enum PlayState {
        NOT_STARTED,
        RUNNING,
        FINISHED,
    };

    RenderNode* mTarget;

    float mFinalValue;
    float mDeltaValue;
    float mFromValue;

    Interpolator* mInterpolator;
    PlayState mStagingPlayState;
    PlayState mPlayState;
    bool mHasStartValue;
    nsecs_t mStartTime;
    nsecs_t mDuration;
    nsecs_t mStartDelay;

    sp<AnimationListener> mListener;

private:
    inline void checkMutable();
    virtual void transitionToRunning(TreeInfo& info);
    void doSetStartValue(float value);
};

class RenderPropertyAnimator : public BaseRenderNodeAnimator {
public:
    enum RenderProperty {
        TRANSLATION_X = 0,
        TRANSLATION_Y,
        TRANSLATION_Z,
        SCALE_X,
        SCALE_Y,
        ROTATION,
        ROTATION_X,
        ROTATION_Y,
        X,
        Y,
        Z,
        ALPHA,
    };

    ANDROID_API RenderPropertyAnimator(RenderProperty property, float finalValue);

    ANDROID_API virtual uint32_t dirtyMask();

protected:
    virtual float getValue(RenderNode* target) const;
    virtual void setValue(RenderNode* target, float value);
    virtual void onAttached();
    virtual void onStagingPlayStateChanged();

private:
    typedef bool (RenderProperties::*SetFloatProperty)(float value);
    typedef float (RenderProperties::*GetFloatProperty)() const;

    struct PropertyAccessors;
    const PropertyAccessors* mPropertyAccess;

    static const PropertyAccessors PROPERTY_ACCESSOR_LUT[];
};

class CanvasPropertyPrimitiveAnimator : public BaseRenderNodeAnimator {
public:
    ANDROID_API CanvasPropertyPrimitiveAnimator(CanvasPropertyPrimitive* property,
            float finalValue);
protected:
    virtual float getValue(RenderNode* target) const;
    virtual void setValue(RenderNode* target, float value);
private:
    sp<CanvasPropertyPrimitive> mProperty;
};

class CanvasPropertyPaintAnimator : public BaseRenderNodeAnimator {
public:
    enum PaintField {
        STROKE_WIDTH = 0,
        ALPHA,
    };

    ANDROID_API CanvasPropertyPaintAnimator(CanvasPropertyPaint* property,
            PaintField field, float finalValue);
protected:
    virtual float getValue(RenderNode* target) const;
    virtual void setValue(RenderNode* target, float value);
private:
    sp<CanvasPropertyPaint> mProperty;
    PaintField mField;
};

class RevealAnimator : public BaseRenderNodeAnimator {
public:
    ANDROID_API RevealAnimator(int centerX, int centerY, bool inverseClip,
            float startValue, float finalValue);
protected:
    virtual float getValue(RenderNode* target) const;
    virtual void setValue(RenderNode* target, float value);

private:
    int mCenterX, mCenterY;
    bool mInverseClip;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* ANIMATOR_H */
