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
#ifndef ANDROID_RS_SERIALIZE
#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>
#endif

using namespace android;
using namespace android::renderscript;

Mesh::Mesh(Context *rsc) : ObjectBase(rsc) {
    mPrimitives = NULL;
    mPrimitivesCount = 0;
    mVertexBuffers = NULL;
    mVertexBufferCount = 0;

#ifndef ANDROID_RS_SERIALIZE
    mAttribs = NULL;
    mAttribAllocationIndex = NULL;

    mAttribCount = 0;
#endif
}

Mesh::~Mesh() {
    if (mVertexBuffers) {
        delete[] mVertexBuffers;
    }

    if (mPrimitives) {
        for (uint32_t i = 0; i < mPrimitivesCount; i ++) {
            delete mPrimitives[i];
        }
        delete[] mPrimitives;
    }

#ifndef ANDROID_RS_SERIALIZE
    if (mAttribs) {
        delete[] mAttribs;
        delete[] mAttribAllocationIndex;
    }
#endif
}

void Mesh::serialize(OStream *stream) const {
    // Need to identify ourselves
    stream->addU32((uint32_t)getClassId());

    String8 name(getName());
    stream->addString(&name);

    // Store number of vertex streams
    stream->addU32(mVertexBufferCount);
    for (uint32_t vCount = 0; vCount < mVertexBufferCount; vCount ++) {
        mVertexBuffers[vCount]->serialize(stream);
    }

    stream->addU32(mPrimitivesCount);
    // Store the primitives
    for (uint32_t pCount = 0; pCount < mPrimitivesCount; pCount ++) {
        Primitive_t * prim = mPrimitives[pCount];

        stream->addU8((uint8_t)prim->mPrimitive);

        if (prim->mIndexBuffer.get()) {
            stream->addU32(1);
            prim->mIndexBuffer->serialize(stream);
        } else {
            stream->addU32(0);
        }
    }
}

Mesh *Mesh::createFromStream(Context *rsc, IStream *stream) {
    // First make sure we are reading the correct object
    RsA3DClassID classID = (RsA3DClassID)stream->loadU32();
    if (classID != RS_A3D_CLASS_ID_MESH) {
        LOGE("mesh loading skipped due to invalid class id");
        return NULL;
    }

    Mesh * mesh = new Mesh(rsc);

    String8 name;
    stream->loadString(&name);
    mesh->setName(name.string(), name.size());

    mesh->mVertexBufferCount = stream->loadU32();
    if (mesh->mVertexBufferCount) {
        mesh->mVertexBuffers = new ObjectBaseRef<Allocation>[mesh->mVertexBufferCount];

        for (uint32_t vCount = 0; vCount < mesh->mVertexBufferCount; vCount ++) {
            Allocation *vertexAlloc = Allocation::createFromStream(rsc, stream);
            mesh->mVertexBuffers[vCount].set(vertexAlloc);
        }
    }

    mesh->mPrimitivesCount = stream->loadU32();
    if (mesh->mPrimitivesCount) {
        mesh->mPrimitives = new Primitive_t *[mesh->mPrimitivesCount];

        // load all primitives
        for (uint32_t pCount = 0; pCount < mesh->mPrimitivesCount; pCount ++) {
            Primitive_t * prim = new Primitive_t;
            mesh->mPrimitives[pCount] = prim;

            prim->mPrimitive = (RsPrimitive)stream->loadU8();

            // Check to see if the index buffer was stored
            uint32_t isIndexPresent = stream->loadU32();
            if (isIndexPresent) {
                Allocation *indexAlloc = Allocation::createFromStream(rsc, stream);
                prim->mIndexBuffer.set(indexAlloc);
            }
        }
    }

#ifndef ANDROID_RS_SERIALIZE
    mesh->updateGLPrimitives();
    mesh->initVertexAttribs();
    mesh->uploadAll(rsc);
#endif
    return mesh;
}

#ifndef ANDROID_RS_SERIALIZE

bool Mesh::isValidGLComponent(const Element *elem, uint32_t fieldIdx) {
    // Do not create attribs for padding
    if (elem->getFieldName(fieldIdx)[0] == '#') {
        return false;
    }

    // Only GL_BYTE, GL_UNSIGNED_BYTE, GL_SHORT, GL_UNSIGNED_SHORT, GL_FIXED, GL_FLOAT are accepted.
    // Filter rs types accordingly
    RsDataType dt = elem->getField(fieldIdx)->getComponent().getType();
    if (dt != RS_TYPE_FLOAT_32 && dt != RS_TYPE_UNSIGNED_8 &&
       dt != RS_TYPE_UNSIGNED_16 && dt != RS_TYPE_SIGNED_8 &&
       dt != RS_TYPE_SIGNED_16) {
        return false;
    }

    // Now make sure they are not arrays
    uint32_t arraySize = elem->getFieldArraySize(fieldIdx);
    if (arraySize != 1) {
        return false;
    }

    return true;
}

void Mesh::initVertexAttribs() {
    // Count the number of gl attrs to initialize
    mAttribCount = 0;
    for (uint32_t ct=0; ct < mVertexBufferCount; ct++) {
        const Element *elem = mVertexBuffers[ct]->getType()->getElement();
        for (uint32_t ct=0; ct < elem->getFieldCount(); ct++) {
            if (isValidGLComponent(elem, ct)) {
                mAttribCount ++;
            }
        }
    }

    if (mAttribs) {
        delete [] mAttribs;
        delete [] mAttribAllocationIndex;
        mAttribs = NULL;
        mAttribAllocationIndex = NULL;
    }
    if (!mAttribCount) {
        return;
    }

    mAttribs = new VertexArray::Attrib[mAttribCount];
    mAttribAllocationIndex = new uint32_t[mAttribCount];

    uint32_t userNum = 0;
    for (uint32_t ct=0; ct < mVertexBufferCount; ct++) {
        const Element *elem = mVertexBuffers[ct]->getType()->getElement();
        uint32_t stride = elem->getSizeBytes();
        for (uint32_t fieldI=0; fieldI < elem->getFieldCount(); fieldI++) {
            const Component &c = elem->getField(fieldI)->getComponent();

            if (!isValidGLComponent(elem, fieldI)) {
                continue;
            }

            mAttribs[userNum].size = c.getVectorSize();
            mAttribs[userNum].offset = elem->getFieldOffsetBytes(fieldI);
            mAttribs[userNum].type = c.getGLType();
            mAttribs[userNum].normalized = c.getType() != RS_TYPE_FLOAT_32;//c.getIsNormalized();
            mAttribs[userNum].stride = stride;
            String8 tmp(RS_SHADER_ATTR);
            tmp.append(elem->getFieldName(fieldI));
            mAttribs[userNum].name.setTo(tmp.string());

            // Remember which allocation this attribute came from
            mAttribAllocationIndex[userNum] = ct;
            userNum ++;
        }
    }
}

void Mesh::render(Context *rsc) const {
    for (uint32_t ct = 0; ct < mPrimitivesCount; ct ++) {
        renderPrimitive(rsc, ct);
    }
}

void Mesh::renderPrimitive(Context *rsc, uint32_t primIndex) const {
    if (primIndex >= mPrimitivesCount) {
        LOGE("Invalid primitive index");
        return;
    }

    Primitive_t *prim = mPrimitives[primIndex];

    if (prim->mIndexBuffer.get()) {
        renderPrimitiveRange(rsc, primIndex, 0, prim->mIndexBuffer->getType()->getDimX());
        return;
    }

    renderPrimitiveRange(rsc, primIndex, 0, mVertexBuffers[0]->getType()->getDimX());
}

void Mesh::renderPrimitiveRange(Context *rsc, uint32_t primIndex, uint32_t start, uint32_t len) const {
    if (len < 1 || primIndex >= mPrimitivesCount || mAttribCount == 0) {
        LOGE("Invalid mesh or parameters");
        return;
    }

    rsc->checkError("Mesh::renderPrimitiveRange 1");
    for (uint32_t ct=0; ct < mVertexBufferCount; ct++) {
        mVertexBuffers[ct]->uploadCheck(rsc);
    }
    // update attributes with either buffer information or data ptr based on their current state
    for (uint32_t ct=0; ct < mAttribCount; ct++) {
        uint32_t allocIndex = mAttribAllocationIndex[ct];
        Allocation *alloc = mVertexBuffers[allocIndex].get();
        if (alloc->getIsBufferObject()) {
            mAttribs[ct].buffer = alloc->getBufferObjectID();
            mAttribs[ct].ptr = NULL;
        } else {
            mAttribs[ct].buffer = 0;
            mAttribs[ct].ptr = (const uint8_t*)alloc->getPtr();
        }
    }

    VertexArray va(mAttribs, mAttribCount);
    va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);

    rsc->checkError("Mesh::renderPrimitiveRange 2");
    Primitive_t *prim = mPrimitives[primIndex];
    if (prim->mIndexBuffer.get()) {
        prim->mIndexBuffer->uploadCheck(rsc);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, prim->mIndexBuffer->getBufferObjectID());
        glDrawElements(prim->mGLPrimitive, len, GL_UNSIGNED_SHORT, (uint16_t *)(start * 2));
    } else {
        glDrawArrays(prim->mGLPrimitive, start, len);
    }

    rsc->checkError("Mesh::renderPrimitiveRange");
}


void Mesh::uploadAll(Context *rsc) {
    for (uint32_t ct = 0; ct < mVertexBufferCount; ct ++) {
        if (mVertexBuffers[ct].get()) {
            mVertexBuffers[ct]->deferredUploadToBufferObject(rsc);
        }
    }

    for (uint32_t ct = 0; ct < mPrimitivesCount; ct ++) {
        if (mPrimitives[ct]->mIndexBuffer.get()) {
            mPrimitives[ct]->mIndexBuffer->deferredUploadToBufferObject(rsc);
        }
    }
}

void Mesh::updateGLPrimitives() {
    for (uint32_t i = 0; i < mPrimitivesCount; i ++) {
        switch (mPrimitives[i]->mPrimitive) {
            case RS_PRIMITIVE_POINT:          mPrimitives[i]->mGLPrimitive = GL_POINTS; break;
            case RS_PRIMITIVE_LINE:           mPrimitives[i]->mGLPrimitive = GL_LINES; break;
            case RS_PRIMITIVE_LINE_STRIP:     mPrimitives[i]->mGLPrimitive = GL_LINE_STRIP; break;
            case RS_PRIMITIVE_TRIANGLE:       mPrimitives[i]->mGLPrimitive = GL_TRIANGLES; break;
            case RS_PRIMITIVE_TRIANGLE_STRIP: mPrimitives[i]->mGLPrimitive = GL_TRIANGLE_STRIP; break;
            case RS_PRIMITIVE_TRIANGLE_FAN:   mPrimitives[i]->mGLPrimitive = GL_TRIANGLE_FAN; break;
        }
    }
}

void Mesh::computeBBox() {
    float *posPtr = NULL;
    uint32_t vectorSize = 0;
    uint32_t stride = 0;
    uint32_t numVerts = 0;
    // First we need to find the position ptr and stride
    for (uint32_t ct=0; ct < mVertexBufferCount; ct++) {
        const Type *bufferType = mVertexBuffers[ct]->getType();
        const Element *bufferElem = bufferType->getElement();

        for (uint32_t ct=0; ct < bufferElem->getFieldCount(); ct++) {
            if (strcmp(bufferElem->getFieldName(ct), "position") == 0) {
                vectorSize = bufferElem->getField(ct)->getComponent().getVectorSize();
                stride = bufferElem->getSizeBytes() / sizeof(float);
                uint32_t offset = bufferElem->getFieldOffsetBytes(ct);
                posPtr = (float*)((uint8_t*)mVertexBuffers[ct]->getPtr() + offset);
                numVerts = bufferType->getDimX();
                break;
            }
        }
        if (posPtr) {
            break;
        }
    }

    mBBoxMin[0] = mBBoxMin[1] = mBBoxMin[2] = 1e6;
    mBBoxMax[0] = mBBoxMax[1] = mBBoxMax[2] = -1e6;
    if (!posPtr) {
        LOGE("Unable to compute bounding box");
        mBBoxMin[0] = mBBoxMin[1] = mBBoxMin[2] = 0.0f;
        mBBoxMax[0] = mBBoxMax[1] = mBBoxMax[2] = 0.0f;
        return;
    }

    for (uint32_t i = 0; i < numVerts; i ++) {
        for (uint32_t v = 0; v < vectorSize; v ++) {
            mBBoxMin[v] = rsMin(mBBoxMin[v], posPtr[v]);
            mBBoxMax[v] = rsMax(mBBoxMax[v], posPtr[v]);
        }
        posPtr += stride;
    }
}

namespace android {
namespace renderscript {

RsMesh rsi_MeshCreate(Context *rsc, uint32_t vtxCount, uint32_t idxCount) {
    Mesh *sm = new Mesh(rsc);
    sm->incUserRef();

    sm->mPrimitivesCount = idxCount;
    sm->mPrimitives = new Mesh::Primitive_t *[sm->mPrimitivesCount];
    for (uint32_t ct = 0; ct < idxCount; ct ++) {
        sm->mPrimitives[ct] = new Mesh::Primitive_t;
    }

    sm->mVertexBufferCount = vtxCount;
    sm->mVertexBuffers = new ObjectBaseRef<Allocation>[vtxCount];

    return sm;
}

void rsi_MeshBindVertex(Context *rsc, RsMesh mv, RsAllocation va, uint32_t slot) {
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(slot < sm->mVertexBufferCount);

    sm->mVertexBuffers[slot].set((Allocation *)va);
}

void rsi_MeshBindIndex(Context *rsc, RsMesh mv, RsAllocation va, uint32_t primType, uint32_t slot) {
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(slot < sm->mPrimitivesCount);

    sm->mPrimitives[slot]->mIndexBuffer.set((Allocation *)va);
    sm->mPrimitives[slot]->mPrimitive = (RsPrimitive)primType;
    sm->updateGLPrimitives();
}

void rsi_MeshInitVertexAttribs(Context *rsc, RsMesh mv) {
    Mesh *sm = static_cast<Mesh *>(mv);
    sm->initVertexAttribs();
}

}}

void rsaMeshGetVertexBufferCount(RsContext con, RsMesh mv, int32_t *numVtx) {
    Mesh *sm = static_cast<Mesh *>(mv);
    *numVtx = sm->mVertexBufferCount;
}

void rsaMeshGetIndexCount(RsContext con, RsMesh mv, int32_t *numIdx) {
    Mesh *sm = static_cast<Mesh *>(mv);
    *numIdx = sm->mPrimitivesCount;
}

void rsaMeshGetVertices(RsContext con, RsMesh mv, RsAllocation *vtxData, uint32_t vtxDataCount) {
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(vtxDataCount == sm->mVertexBufferCount);

    for (uint32_t ct = 0; ct < vtxDataCount; ct ++) {
        vtxData[ct] = sm->mVertexBuffers[ct].get();
        sm->mVertexBuffers[ct]->incUserRef();
    }
}

void rsaMeshGetIndices(RsContext con, RsMesh mv, RsAllocation *va, uint32_t *primType, uint32_t idxDataCount) {
    Mesh *sm = static_cast<Mesh *>(mv);
    rsAssert(idxDataCount == sm->mPrimitivesCount);

    for (uint32_t ct = 0; ct < idxDataCount; ct ++) {
        va[ct] = sm->mPrimitives[ct]->mIndexBuffer.get();
        primType[ct] = sm->mPrimitives[ct]->mPrimitive;
        if (sm->mPrimitives[ct]->mIndexBuffer.get()) {
            sm->mPrimitives[ct]->mIndexBuffer->incUserRef();
        }
    }
}

#endif
