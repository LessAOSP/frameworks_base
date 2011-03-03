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

#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

Script::Script(Context *rsc) : ObjectBase(rsc) {
    memset(&mEnviroment, 0, sizeof(mEnviroment));

    mSlots = NULL;
    mTypes = NULL;
}

Script::~Script() {
    if (mSlots) {
        delete [] mSlots;
        mSlots = NULL;
    }
    if (mTypes) {
        delete [] mTypes;
        mTypes = NULL;
    }
}

void Script::initSlots() {
    if (mEnviroment.mFieldCount > 0) {
        mSlots = new ObjectBaseRef<Allocation>[mEnviroment.mFieldCount];
        mTypes = new ObjectBaseRef<const Type>[mEnviroment.mFieldCount];
    }
}

void Script::setSlot(uint32_t slot, Allocation *a) {
    if (slot >= mEnviroment.mFieldCount) {
        LOGE("Script::setSlot unable to set allocation, invalid slot index");
        return;
    }

    mSlots[slot].set(a);
}

void Script::setVar(uint32_t slot, const void *val, uint32_t len) {
    int32_t *destPtr = ((int32_t **)mEnviroment.mFieldAddress)[slot];
    if (destPtr) {
        //LOGE("setVar f1  %f", ((const float *)destPtr)[0]);
        //LOGE("setVar %p %i", destPtr, len);
        memcpy(destPtr, val, len);
        //LOGE("setVar f2  %f", ((const float *)destPtr)[0]);
    } else {
        //if (rsc->props.mLogScripts) {
            LOGV("Calling setVar on slot = %i which is null", slot);
        //}
    }
}

void Script::setVarObj(uint32_t slot, ObjectBase *val) {
    ObjectBase **destPtr = ((ObjectBase ***)mEnviroment.mFieldAddress)[slot];

    if (destPtr) {
        if (val != NULL) {
            val->incSysRef();
        }
        if (*destPtr) {
            (*destPtr)->decSysRef();
        }
        *destPtr = val;
    }
}

namespace android {
namespace renderscript {

void rsi_ScriptBindAllocation(Context * rsc, RsScript vs, RsAllocation va, uint32_t slot) {
    Script *s = static_cast<Script *>(vs);
    Allocation *a = static_cast<Allocation *>(va);
    s->setSlot(slot, a);
    //LOGE("rsi_ScriptBindAllocation %i  %p  %p", slot, a, a->getPtr());
}

void rsi_ScriptSetTimeZone(Context * rsc, RsScript vs, const char * timeZone, uint32_t length) {
    Script *s = static_cast<Script *>(vs);
    s->mEnviroment.mTimeZone = timeZone;
}

void rsi_ScriptInvoke(Context *rsc, RsScript vs, uint32_t slot) {
    Script *s = static_cast<Script *>(vs);
    s->Invoke(rsc, slot, NULL, 0);
}


void rsi_ScriptInvokeData(Context *rsc, RsScript vs, uint32_t slot, void *data) {
    Script *s = static_cast<Script *>(vs);
    s->Invoke(rsc, slot, NULL, 0);
}

void rsi_ScriptInvokeV(Context *rsc, RsScript vs, uint32_t slot, const void *data, uint32_t len) {
    Script *s = static_cast<Script *>(vs);
    s->Invoke(rsc, slot, data, len);
}

void rsi_ScriptSetVarI(Context *rsc, RsScript vs, uint32_t slot, int value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarObj(Context *rsc, RsScript vs, uint32_t slot, RsObjectBase value) {
    Script *s = static_cast<Script *>(vs);
    ObjectBase *o = static_cast<ObjectBase *>(value);
    s->setVarObj(slot, o);
}

void rsi_ScriptSetVarJ(Context *rsc, RsScript vs, uint32_t slot, long long value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarF(Context *rsc, RsScript vs, uint32_t slot, float value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarD(Context *rsc, RsScript vs, uint32_t slot, double value) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, &value, sizeof(value));
}

void rsi_ScriptSetVarV(Context *rsc, RsScript vs, uint32_t slot, const void *data, uint32_t len) {
    Script *s = static_cast<Script *>(vs);
    s->setVar(slot, data, len);
}

}
}

