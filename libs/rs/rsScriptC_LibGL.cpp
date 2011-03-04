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
#include "rsScriptC.h"
#include "rsMatrix.h"

#include "utils/Timers.h"

#define GL_GLEXT_PROTOTYPES

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <time.h>

using namespace android;
using namespace android::renderscript;

#define GET_TLS()  Context::ScriptTLSStruct * tls = \
    (Context::ScriptTLSStruct *)pthread_getspecific(Context::gThreadTLSKey); \
    Context * rsc = tls->mContext; \
    ScriptC * sc = (ScriptC *) tls->mScript


//////////////////////////////////////////////////////////////////////////////
// Context
//////////////////////////////////////////////////////////////////////////////

static void SC_bindTexture(RsProgramFragment vpf, uint32_t slot, RsAllocation va) {
    CHECK_OBJ_OR_NULL(va);
    CHECK_OBJ(vpf);
    GET_TLS();
    rsi_ProgramBindTexture(rsc,
                           static_cast<ProgramFragment *>(vpf),
                           slot,
                           static_cast<Allocation *>(va));
}

static void SC_bindSampler(RsProgramFragment vpf, uint32_t slot, RsSampler vs) {
    CHECK_OBJ_OR_NULL(vs);
    CHECK_OBJ(vpf);
    GET_TLS();
    rsi_ProgramBindSampler(rsc,
                           static_cast<ProgramFragment *>(vpf),
                           slot,
                           static_cast<Sampler *>(vs));
}

static void SC_bindProgramStore(RsProgramStore pfs) {
    CHECK_OBJ_OR_NULL(pfs);
    GET_TLS();
    rsi_ContextBindProgramStore(rsc, pfs);
}

static void SC_bindProgramFragment(RsProgramFragment pf) {
    CHECK_OBJ_OR_NULL(pf);
    GET_TLS();
    rsi_ContextBindProgramFragment(rsc, pf);
}

static void SC_bindProgramVertex(RsProgramVertex pv) {
    CHECK_OBJ_OR_NULL(pv);
    GET_TLS();
    rsi_ContextBindProgramVertex(rsc, pv);
}

static void SC_bindProgramRaster(RsProgramRaster pv) {
    CHECK_OBJ_OR_NULL(pv);
    GET_TLS();
    rsi_ContextBindProgramRaster(rsc, pv);
}

//////////////////////////////////////////////////////////////////////////////
// VP
//////////////////////////////////////////////////////////////////////////////

static void SC_vpLoadProjectionMatrix(const rsc_Matrix *m) {
    GET_TLS();
    rsc->getProgramVertex()->setProjectionMatrix(rsc, m);
}

static void SC_vpLoadModelMatrix(const rsc_Matrix *m) {
    GET_TLS();
    rsc->getProgramVertex()->setModelviewMatrix(rsc, m);
}

static void SC_vpLoadTextureMatrix(const rsc_Matrix *m) {
    GET_TLS();
    rsc->getProgramVertex()->setTextureMatrix(rsc, m);
}

static void SC_pfConstantColor(RsProgramFragment vpf, float r, float g, float b, float a) {
    GET_TLS();
    CHECK_OBJ(vpf);
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    pf->setConstantColor(rsc, r, g, b, a);
}

static void SC_vpGetProjectionMatrix(rsc_Matrix *m) {
    GET_TLS();
    rsc->getProgramVertex()->getProjectionMatrix(rsc, m);
}

//////////////////////////////////////////////////////////////////////////////
// Drawing
//////////////////////////////////////////////////////////////////////////////

static void SC_drawQuadTexCoords(float x1, float y1, float z1,
                                 float u1, float v1,
                                 float x2, float y2, float z2,
                                 float u2, float v2,
                                 float x3, float y3, float z3,
                                 float u3, float v3,
                                 float x4, float y4, float z4,
                                 float u4, float v4) {
    GET_TLS();
    if (!rsc->setupCheck()) {
        return;
    }

    //LOGE("Quad");
    //LOGE("%4.2f, %4.2f, %4.2f", x1, y1, z1);
    //LOGE("%4.2f, %4.2f, %4.2f", x2, y2, z2);
    //LOGE("%4.2f, %4.2f, %4.2f", x3, y3, z3);
    //LOGE("%4.2f, %4.2f, %4.2f", x4, y4, z4);

    float vtx[] = {x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4};
    const float tex[] = {u1,v1, u2,v2, u3,v3, u4,v4};

    VertexArray::Attrib attribs[2];
    attribs[0].set(GL_FLOAT, 3, 12, false, (uint32_t)vtx, "ATTRIB_position");
    attribs[1].set(GL_FLOAT, 2, 8, false, (uint32_t)tex, "ATTRIB_texture0");

    VertexArray va(attribs, 2);
    va.setupGL2(rsc, &rsc->mStateVertexArray, &rsc->mShaderCache);

    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

static void SC_drawQuad(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4) {
    SC_drawQuadTexCoords(x1, y1, z1, 0, 1,
                         x2, y2, z2, 1, 1,
                         x3, y3, z3, 1, 0,
                         x4, y4, z4, 0, 0);
}

static void SC_drawSpriteScreenspace(float x, float y, float z, float w, float h) {
    GET_TLS();
    ObjectBaseRef<const ProgramVertex> tmp(rsc->getProgramVertex());
    rsc->setProgramVertex(rsc->getDefaultProgramVertex());
    //rsc->setupCheck();

    //GLint crop[4] = {0, h, w, -h};

    float sh = rsc->getHeight();

    SC_drawQuad(x,   sh - y,     z,
                x+w, sh - y,     z,
                x+w, sh - (y+h), z,
                x,   sh - (y+h), z);
    rsc->setProgramVertex((ProgramVertex *)tmp.get());
}
/*
static void SC_drawSprite(float x, float y, float z, float w, float h)
{
    GET_TLS();
    float vin[3] = {x, y, z};
    float vout[4];

    //LOGE("ds  in %f %f %f", x, y, z);
    rsc->getVertex()->transformToScreen(rsc, vout, vin);
    //LOGE("ds  out %f %f %f %f", vout[0], vout[1], vout[2], vout[3]);
    vout[0] /= vout[3];
    vout[1] /= vout[3];
    vout[2] /= vout[3];

    vout[0] *= rsc->getWidth() / 2;
    vout[1] *= rsc->getHeight() / 2;
    vout[0] += rsc->getWidth() / 2;
    vout[1] += rsc->getHeight() / 2;

    vout[0] -= w/2;
    vout[1] -= h/2;

    //LOGE("ds  out2 %f %f %f", vout[0], vout[1], vout[2]);

    // U, V, W, H
    SC_drawSpriteScreenspace(vout[0], vout[1], z, h, w);
    //rsc->setupCheck();
}
*/

static void SC_drawRect(float x1, float y1,
                        float x2, float y2, float z) {
    //LOGE("SC_drawRect %f,%f  %f,%f  %f", x1, y1, x2, y2, z);
    SC_drawQuad(x1, y2, z,
                x2, y2, z,
                x2, y1, z,
                x1, y1, z);
}

static void SC_drawMesh(RsMesh vsm) {
    CHECK_OBJ(vsm);
    GET_TLS();
    Mesh *sm = static_cast<Mesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->render(rsc);
}

static void SC_drawMeshPrimitive(RsMesh vsm, uint32_t primIndex) {
    CHECK_OBJ(vsm);
    GET_TLS();
    Mesh *sm = static_cast<Mesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->renderPrimitive(rsc, primIndex);
}

static void SC_drawMeshPrimitiveRange(RsMesh vsm, uint32_t primIndex, uint32_t start, uint32_t len) {
    CHECK_OBJ(vsm);
    GET_TLS();
    Mesh *sm = static_cast<Mesh *>(vsm);
    if (!rsc->setupCheck()) {
        return;
    }
    sm->renderPrimitiveRange(rsc, primIndex, start, len);
}

static void SC_meshComputeBoundingBox(RsMesh vsm,
                                      float *minX, float *minY, float *minZ,
                                      float *maxX, float *maxY, float *maxZ) {
    CHECK_OBJ(vsm);
    GET_TLS();
    Mesh *sm = static_cast<Mesh *>(vsm);
    sm->computeBBox();
    *minX = sm->mBBoxMin[0];
    *minY = sm->mBBoxMin[1];
    *minZ = sm->mBBoxMin[2];
    *maxX = sm->mBBoxMax[0];
    *maxY = sm->mBBoxMax[1];
    *maxZ = sm->mBBoxMax[2];
}


//////////////////////////////////////////////////////////////////////////////
//
//////////////////////////////////////////////////////////////////////////////


static void SC_color(float r, float g, float b, float a) {
    GET_TLS();
    ProgramFragment *pf = (ProgramFragment *)rsc->getProgramFragment();
    pf->setConstantColor(rsc, r, g, b, a);
}

static void SC_allocationSyncAll(RsAllocation va) {
    CHECK_OBJ(va);
    GET_TLS();
    static_cast<Allocation *>(va)->syncAll(rsc, RS_ALLOCATION_USAGE_SCRIPT);
}

#if 0
static void SC_allocationSyncAll2(RsAllocation va, RsAllocationUsageType source) {
    CHECK_OBJ(va);
    GET_TLS();
    static_cast<Allocation *>(va)->syncAll(rsc, source);
}
#endif

static void SC_ClearColor(float r, float g, float b, float a) {
    GET_TLS();
    rsc->setupProgramStore();

    glClearColor(r, g, b, a);
    glClear(GL_COLOR_BUFFER_BIT);
}

static void SC_ClearDepth(float v) {
    GET_TLS();
    rsc->setupProgramStore();

    glClearDepthf(v);
    glClear(GL_DEPTH_BUFFER_BIT);
}

static uint32_t SC_getWidth() {
    GET_TLS();
    return rsc->getWidth();
}

static uint32_t SC_getHeight() {
    GET_TLS();
    return rsc->getHeight();
}

static void SC_DrawTextAlloc(RsAllocation va, int x, int y) {
    CHECK_OBJ(va);
    GET_TLS();
    Allocation *alloc = static_cast<Allocation *>(va);
    const char *text = (const char *)alloc->getPtr();
    size_t allocSize = alloc->getType()->getSizeBytes();
    rsc->mStateFont.renderText(text, allocSize, x, y);
}

static void SC_DrawText(const char *text, int x, int y) {
    GET_TLS();
    size_t textLen = strlen(text);
    rsc->mStateFont.renderText(text, textLen, x, y);
}

static void SC_setMetrics(Font::Rect *metrics,
                          int32_t *left, int32_t *right,
                          int32_t *top, int32_t *bottom) {
    if (left) {
        *left = metrics->left;
    }
    if (right) {
        *right = metrics->right;
    }
    if (top) {
        *top = metrics->top;
    }
    if (bottom) {
        *bottom = metrics->bottom;
    }
}

static void SC_MeasureTextAlloc(RsAllocation va,
                                int32_t *left, int32_t *right,
                                int32_t *top, int32_t *bottom) {
    CHECK_OBJ(va);
    GET_TLS();
    Allocation *alloc = static_cast<Allocation *>(va);
    const char *text = (const char *)alloc->getPtr();
    size_t textLen = alloc->getType()->getSizeBytes();
    Font::Rect metrics;
    rsc->mStateFont.measureText(text, textLen, &metrics);
    SC_setMetrics(&metrics, left, right, top, bottom);
}

static void SC_MeasureText(const char *text,
                           int32_t *left, int32_t *right,
                           int32_t *top, int32_t *bottom) {
    GET_TLS();
    size_t textLen = strlen(text);
    Font::Rect metrics;
    rsc->mStateFont.measureText(text, textLen, &metrics);
    SC_setMetrics(&metrics, left, right, top, bottom);
}

static void SC_BindFont(RsFont font) {
    CHECK_OBJ(font);
    GET_TLS();
    rsi_ContextBindFont(rsc, font);
}

static void SC_FontColor(float r, float g, float b, float a) {
    GET_TLS();
    rsc->mStateFont.setFontColor(r, g, b, a);
}

//////////////////////////////////////////////////////////////////////////////
// Class implementation
//////////////////////////////////////////////////////////////////////////////

// llvm name mangling ref
//  <builtin-type> ::= v  # void
//                 ::= b  # bool
//                 ::= c  # char
//                 ::= a  # signed char
//                 ::= h  # unsigned char
//                 ::= s  # short
//                 ::= t  # unsigned short
//                 ::= i  # int
//                 ::= j  # unsigned int
//                 ::= l  # long
//                 ::= m  # unsigned long
//                 ::= x  # long long, __int64
//                 ::= y  # unsigned long long, __int64
//                 ::= f  # float
//                 ::= d  # double

static ScriptCState::SymbolTable_t gSyms[] = {
    { "_Z22rsgBindProgramFragment19rs_program_fragment", (void *)&SC_bindProgramFragment, false },
    { "_Z19rsgBindProgramStore16rs_program_store", (void *)&SC_bindProgramStore, false },
    { "_Z20rsgBindProgramVertex17rs_program_vertex", (void *)&SC_bindProgramVertex, false },
    { "_Z20rsgBindProgramRaster17rs_program_raster", (void *)&SC_bindProgramRaster, false },
    { "_Z14rsgBindSampler19rs_program_fragmentj10rs_sampler", (void *)&SC_bindSampler, false },
    { "_Z14rsgBindTexture19rs_program_fragmentj13rs_allocation", (void *)&SC_bindTexture, false },

    { "_Z36rsgProgramVertexLoadProjectionMatrixPK12rs_matrix4x4", (void *)&SC_vpLoadProjectionMatrix, false },
    { "_Z31rsgProgramVertexLoadModelMatrixPK12rs_matrix4x4", (void *)&SC_vpLoadModelMatrix, false },
    { "_Z33rsgProgramVertexLoadTextureMatrixPK12rs_matrix4x4", (void *)&SC_vpLoadTextureMatrix, false },

    { "_Z35rsgProgramVertexGetProjectionMatrixP12rs_matrix4x4", (void *)&SC_vpGetProjectionMatrix, false },

    { "_Z31rsgProgramFragmentConstantColor19rs_program_fragmentffff", (void *)&SC_pfConstantColor, false },

    { "_Z11rsgGetWidthv", (void *)&SC_getWidth, false },
    { "_Z12rsgGetHeightv", (void *)&SC_getHeight, false },

    { "_Z20rsgAllocationSyncAll13rs_allocation", (void *)&SC_allocationSyncAll, false },

    { "_Z11rsgDrawRectfffff", (void *)&SC_drawRect, false },
    { "_Z11rsgDrawQuadffffffffffff", (void *)&SC_drawQuad, false },
    { "_Z20rsgDrawQuadTexCoordsffffffffffffffffffff", (void *)&SC_drawQuadTexCoords, false },
    { "_Z24rsgDrawSpriteScreenspacefffff", (void *)&SC_drawSpriteScreenspace, false },

    { "_Z11rsgDrawMesh7rs_mesh", (void *)&SC_drawMesh, false },
    { "_Z11rsgDrawMesh7rs_meshj", (void *)&SC_drawMeshPrimitive, false },
    { "_Z11rsgDrawMesh7rs_meshjjj", (void *)&SC_drawMeshPrimitiveRange, false },
    { "_Z25rsgMeshComputeBoundingBox7rs_meshPfS0_S0_S0_S0_S0_", (void *)&SC_meshComputeBoundingBox, false },

    { "_Z13rsgClearColorffff", (void *)&SC_ClearColor, false },
    { "_Z13rsgClearDepthf", (void *)&SC_ClearDepth, false },

    { "_Z11rsgDrawTextPKcii", (void *)&SC_DrawText, false },
    { "_Z11rsgDrawText13rs_allocationii", (void *)&SC_DrawTextAlloc, false },
    { "_Z14rsgMeasureTextPKcPiS1_S1_S1_", (void *)&SC_MeasureText, false },
    { "_Z14rsgMeasureText13rs_allocationPiS0_S0_S0_", (void *)&SC_MeasureTextAlloc, false },

    { "_Z11rsgBindFont7rs_font", (void *)&SC_BindFont, false },
    { "_Z12rsgFontColorffff", (void *)&SC_FontColor, false },

    // misc
    { "_Z5colorffff", (void *)&SC_color, false },

    { NULL, NULL, false }
};

const ScriptCState::SymbolTable_t * ScriptCState::lookupSymbolGL(const char *sym) {
    ScriptCState::SymbolTable_t *syms = gSyms;

    while (syms->mPtr) {
        if (!strcmp(syms->mName, sym)) {
            return syms;
        }
        syms++;
    }
    return NULL;
}

