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

#ifndef RENDER_SCRIPT_H
#define RENDER_SCRIPT_H

#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

//////////////////////////////////////////////////////
//

typedef void * RsAsyncVoidPtr;

typedef void * RsAdapter1D;
typedef void * RsAdapter2D;
typedef void * RsAllocation;
typedef void * RsAnimation;
typedef void * RsContext;
typedef void * RsDevice;
typedef void * RsElement;
typedef void * RsFile;
typedef void * RsFont;
typedef void * RsSampler;
typedef void * RsScript;
typedef void * RsMesh;
typedef void * RsType;
typedef void * RsObjectBase;

typedef void * RsProgram;
typedef void * RsProgramVertex;
typedef void * RsProgramFragment;
typedef void * RsProgramStore;
typedef void * RsProgramRaster;

typedef void (* RsBitmapCallback_t)(void *);

enum RsDeviceParam {
    RS_DEVICE_PARAM_FORCE_SOFTWARE_GL,
    RS_DEVICE_PARAM_COUNT
};

typedef struct {
    uint32_t colorMin;
    uint32_t colorPref;
    uint32_t alphaMin;
    uint32_t alphaPref;
    uint32_t depthMin;
    uint32_t depthPref;
    uint32_t stencilMin;
    uint32_t stencilPref;
    uint32_t samplesMin;
    uint32_t samplesPref;
    float samplesQ;
} RsSurfaceConfig;

RsDevice rsDeviceCreate();
void rsDeviceDestroy(RsDevice);
void rsDeviceSetConfig(RsDevice, RsDeviceParam, int32_t value);

RsContext rsContextCreate(RsDevice, uint32_t version);
RsContext rsContextCreateGL(RsDevice, uint32_t version,
                            RsSurfaceConfig sc, uint32_t dpi);
void rsContextDestroy(RsContext);

enum RsMessageToClientType {
    RS_MESSAGE_TO_CLIENT_NONE = 0,
    RS_MESSAGE_TO_CLIENT_EXCEPTION = 1,
    RS_MESSAGE_TO_CLIENT_RESIZE = 2,
    RS_MESSAGE_TO_CLIENT_ERROR = 3,
    RS_MESSAGE_TO_CLIENT_USER = 4
};

RsMessageToClientType rsContextGetMessage(RsContext vrsc, void *data, size_t *receiveLen, uint32_t *subID, size_t bufferLen, bool wait);
RsMessageToClientType rsContextPeekMessage(RsContext vrsc, size_t *receiveLen, uint32_t *subID, bool wait);
void rsContextInitToClient(RsContext);
void rsContextDeinitToClient(RsContext);

#define RS_MAX_TEXTURE 2
#define RS_MAX_ATTRIBS 16


enum RsAllocationUsageType {
    RS_ALLOCATION_USAGE_SCRIPT = 0x0001,
    RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE = 0x0002,
    RS_ALLOCATION_USAGE_GRAPHICS_VERTEX = 0x0004,
    RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS = 0x0008,

    RS_ALLOCATION_USAGE_ALL = 0x000F
};

enum RsAllocationMipmapControl {
    RS_ALLOCATION_MIPMAP_NONE = 0,
    RS_ALLOCATION_MIPMAP_FULL = 1,
    RS_ALLOCATION_MIPMAP_ON_SYNC_TO_TEXTURE = 2
};

enum RsAllocationCubemapFace {
    RS_ALLOCATION_CUBMAP_FACE_POSITVE_X = 0,
    RS_ALLOCATION_CUBMAP_FACE_NEGATIVE_X = 1,
    RS_ALLOCATION_CUBMAP_FACE_POSITVE_Y = 2,
    RS_ALLOCATION_CUBMAP_FACE_NEGATIVE_Y = 3,
    RS_ALLOCATION_CUBMAP_FACE_POSITVE_Z = 4,
    RS_ALLOCATION_CUBMAP_FACE_NEGATIVE_Z = 5
};

enum RsDataType {
    RS_TYPE_NONE,
    RS_TYPE_FLOAT_16,
    RS_TYPE_FLOAT_32,
    RS_TYPE_FLOAT_64,
    RS_TYPE_SIGNED_8,
    RS_TYPE_SIGNED_16,
    RS_TYPE_SIGNED_32,
    RS_TYPE_SIGNED_64,
    RS_TYPE_UNSIGNED_8,
    RS_TYPE_UNSIGNED_16,
    RS_TYPE_UNSIGNED_32,
    RS_TYPE_UNSIGNED_64,

    RS_TYPE_BOOLEAN,

    RS_TYPE_UNSIGNED_5_6_5,
    RS_TYPE_UNSIGNED_5_5_5_1,
    RS_TYPE_UNSIGNED_4_4_4_4,

    RS_TYPE_MATRIX_4X4,
    RS_TYPE_MATRIX_3X3,
    RS_TYPE_MATRIX_2X2,

    RS_TYPE_ELEMENT = 1000,
    RS_TYPE_TYPE,
    RS_TYPE_ALLOCATION,
    RS_TYPE_SAMPLER,
    RS_TYPE_SCRIPT,
    RS_TYPE_MESH,
    RS_TYPE_PROGRAM_FRAGMENT,
    RS_TYPE_PROGRAM_VERTEX,
    RS_TYPE_PROGRAM_RASTER,
    RS_TYPE_PROGRAM_STORE,
};

enum RsDataKind {
    RS_KIND_USER,

    RS_KIND_PIXEL_L = 7,
    RS_KIND_PIXEL_A,
    RS_KIND_PIXEL_LA,
    RS_KIND_PIXEL_RGB,
    RS_KIND_PIXEL_RGBA,
};

enum RsSamplerParam {
    RS_SAMPLER_MIN_FILTER,
    RS_SAMPLER_MAG_FILTER,
    RS_SAMPLER_WRAP_S,
    RS_SAMPLER_WRAP_T,
    RS_SAMPLER_WRAP_R,
    RS_SAMPLER_ANISO
};

enum RsSamplerValue {
    RS_SAMPLER_NEAREST,
    RS_SAMPLER_LINEAR,
    RS_SAMPLER_LINEAR_MIP_LINEAR,
    RS_SAMPLER_WRAP,
    RS_SAMPLER_CLAMP,
    RS_SAMPLER_LINEAR_MIP_NEAREST,
};

enum RsTextureTarget {
    RS_TEXTURE_2D,
    RS_TEXTURE_CUBE
};

enum RsDimension {
    RS_DIMENSION_X,
    RS_DIMENSION_Y,
    RS_DIMENSION_Z,
    RS_DIMENSION_LOD,
    RS_DIMENSION_FACE,

    RS_DIMENSION_ARRAY_0 = 100,
    RS_DIMENSION_ARRAY_1,
    RS_DIMENSION_ARRAY_2,
    RS_DIMENSION_ARRAY_3,
    RS_DIMENSION_MAX = RS_DIMENSION_ARRAY_3
};

enum RsDepthFunc {
    RS_DEPTH_FUNC_ALWAYS,
    RS_DEPTH_FUNC_LESS,
    RS_DEPTH_FUNC_LEQUAL,
    RS_DEPTH_FUNC_GREATER,
    RS_DEPTH_FUNC_GEQUAL,
    RS_DEPTH_FUNC_EQUAL,
    RS_DEPTH_FUNC_NOTEQUAL
};

enum RsBlendSrcFunc {
    RS_BLEND_SRC_ZERO,                  // 0
    RS_BLEND_SRC_ONE,                   // 1
    RS_BLEND_SRC_DST_COLOR,             // 2
    RS_BLEND_SRC_ONE_MINUS_DST_COLOR,   // 3
    RS_BLEND_SRC_SRC_ALPHA,             // 4
    RS_BLEND_SRC_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_SRC_DST_ALPHA,             // 6
    RS_BLEND_SRC_ONE_MINUS_DST_ALPHA,   // 7
    RS_BLEND_SRC_SRC_ALPHA_SATURATE     // 8
};

enum RsBlendDstFunc {
    RS_BLEND_DST_ZERO,                  // 0
    RS_BLEND_DST_ONE,                   // 1
    RS_BLEND_DST_SRC_COLOR,             // 2
    RS_BLEND_DST_ONE_MINUS_SRC_COLOR,   // 3
    RS_BLEND_DST_SRC_ALPHA,             // 4
    RS_BLEND_DST_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_DST_DST_ALPHA,             // 6
    RS_BLEND_DST_ONE_MINUS_DST_ALPHA    // 7
};

enum RsTexEnvMode {
    RS_TEX_ENV_MODE_NONE,
    RS_TEX_ENV_MODE_REPLACE,
    RS_TEX_ENV_MODE_MODULATE,
    RS_TEX_ENV_MODE_DECAL
};

enum RsProgramParam {
    RS_PROGRAM_PARAM_INPUT,
    RS_PROGRAM_PARAM_OUTPUT,
    RS_PROGRAM_PARAM_CONSTANT,
    RS_PROGRAM_PARAM_TEXTURE_TYPE,
};

enum RsPrimitive {
    RS_PRIMITIVE_POINT,
    RS_PRIMITIVE_LINE,
    RS_PRIMITIVE_LINE_STRIP,
    RS_PRIMITIVE_TRIANGLE,
    RS_PRIMITIVE_TRIANGLE_STRIP,
    RS_PRIMITIVE_TRIANGLE_FAN
};

enum RsError {
    RS_ERROR_NONE = 0,
    RS_ERROR_BAD_SHADER = 1,
    RS_ERROR_BAD_SCRIPT = 2,
    RS_ERROR_BAD_VALUE = 3,
    RS_ERROR_OUT_OF_MEMORY = 4,
    RS_ERROR_DRIVER = 5,

    RS_ERROR_FATAL_UNKNOWN = 0x1000,
    RS_ERROR_FATAL_DRIVER = 0x1001,
    RS_ERROR_FATAL_PROGRAM_LINK = 0x1002
};

enum RsAnimationInterpolation {
    RS_ANIMATION_INTERPOLATION_STEP,
    RS_ANIMATION_INTERPOLATION_LINEAR,
    RS_ANIMATION_INTERPOLATION_BEZIER,
    RS_ANIMATION_INTERPOLATION_CARDINAL,
    RS_ANIMATION_INTERPOLATION_HERMITE,
    RS_ANIMATION_INTERPOLATION_BSPLINE
};

enum RsAnimationEdge {
    RS_ANIMATION_EDGE_UNDEFINED,
    RS_ANIMATION_EDGE_CONSTANT,
    RS_ANIMATION_EDGE_GRADIENT,
    RS_ANIMATION_EDGE_CYCLE,
    RS_ANIMATION_EDGE_OSCILLATE,
    RS_ANIMATION_EDGE_CYLE_RELATIVE
};

enum RsA3DClassID {
    RS_A3D_CLASS_ID_UNKNOWN,
    RS_A3D_CLASS_ID_MESH,
    RS_A3D_CLASS_ID_TYPE,
    RS_A3D_CLASS_ID_ELEMENT,
    RS_A3D_CLASS_ID_ALLOCATION,
    RS_A3D_CLASS_ID_PROGRAM_VERTEX,
    RS_A3D_CLASS_ID_PROGRAM_RASTER,
    RS_A3D_CLASS_ID_PROGRAM_FRAGMENT,
    RS_A3D_CLASS_ID_PROGRAM_STORE,
    RS_A3D_CLASS_ID_SAMPLER,
    RS_A3D_CLASS_ID_ANIMATION,
    RS_A3D_CLASS_ID_ADAPTER_1D,
    RS_A3D_CLASS_ID_ADAPTER_2D,
    RS_A3D_CLASS_ID_SCRIPT_C
};

enum RsCullMode {
    RS_CULL_BACK,
    RS_CULL_FRONT,
    RS_CULL_NONE
};

typedef struct {
    RsA3DClassID classID;
    const char* objectName;
} RsFileIndexEntry;

// Script to Script
typedef struct {
    uint32_t xStart;
    uint32_t xEnd;
    uint32_t yStart;
    uint32_t yEnd;
    uint32_t zStart;
    uint32_t zEnd;
    uint32_t arrayStart;
    uint32_t arrayEnd;

} RsScriptCall;

// A3D loading and object update code.
// Should only be called at object creation, not thread safe
RsObjectBase rsaFileA3DGetEntryByIndex(RsContext, uint32_t idx, RsFile);
RsFile rsaFileA3DCreateFromMemory(RsContext, const void *data, uint32_t len);
RsFile rsaFileA3DCreateFromAsset(RsContext, void *asset);
RsFile rsaFileA3DCreateFromFile(RsContext, const char *path);
void rsaFileA3DGetNumIndexEntries(RsContext, int32_t *numEntries, RsFile);
void rsaFileA3DGetIndexEntries(RsContext, RsFileIndexEntry *fileEntries,uint32_t numEntries, RsFile);
void rsaGetName(RsContext, void * obj, const char **name);
// Mesh update functions
void rsaMeshGetVertexBufferCount(RsContext, RsMesh, int32_t *vtxCount);
void rsaMeshGetIndexCount(RsContext, RsMesh, int32_t *idxCount);
void rsaMeshGetVertices(RsContext, RsMesh, RsAllocation *vtxData, uint32_t vtxDataCount);
void rsaMeshGetIndices(RsContext, RsMesh, RsAllocation *va, uint32_t *primType, uint32_t idxDataCount);
// Allocation update
const void* rsaAllocationGetType(RsContext con, RsAllocation va);
// Type update
void rsaTypeGetNativeData(RsContext, RsType, uint32_t *typeData, uint32_t typeDataSize);
// Element update
void rsaElementGetNativeData(RsContext, RsElement, uint32_t *elemData, uint32_t elemDataSize);
void rsaElementGetSubElements(RsContext, RsElement, uint32_t *ids, const char **names, uint32_t dataSize);

// Async commands for returning new IDS
RsType rsaTypeCreate(RsContext, RsElement, uint32_t dimX, uint32_t dimY,
                     uint32_t dimZ, bool mips, bool faces);
RsAllocation rsaAllocationCreateTyped(RsContext rsc, RsType vtype,
                                      RsAllocationMipmapControl mips,
                                      uint32_t usages);
RsAllocation rsaAllocationCreateFromBitmap(RsContext con, RsType vtype,
                                           RsAllocationMipmapControl mips,
                                           const void *data, uint32_t usages);
RsAllocation rsaAllocationCubeCreateFromBitmap(RsContext con, RsType vtype,
                                               RsAllocationMipmapControl mips,
                                               const void *data, uint32_t usages);
#ifdef ANDROID_RS_SERIALIZE
#define NO_RS_FUNCS
#endif

#ifndef NO_RS_FUNCS
#include "rsgApiFuncDecl.h"
#endif

#ifdef __cplusplus
};
#endif

#endif // RENDER_SCRIPT_H



