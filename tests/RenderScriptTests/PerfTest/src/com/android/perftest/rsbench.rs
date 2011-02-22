// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)

#pragma rs java_package_name(com.android.perftest)

#include "rs_graphics.rsh"
#include "shader_def.rsh"

const int gMaxModes = 26;

rs_program_vertex gProgVertex;
rs_program_fragment gProgFragmentColor;
rs_program_fragment gProgFragmentTexture;

rs_program_store gProgStoreBlendNoneDepth;
rs_program_store gProgStoreBlendNone;
rs_program_store gProgStoreBlendAlpha;
rs_program_store gProgStoreBlendAdd;

rs_allocation gTexOpaque;
rs_allocation gTexTorus;
rs_allocation gTexTransparent;
rs_allocation gTexChecker;

rs_mesh g10by10Mesh;
rs_mesh g100by100Mesh;
rs_mesh gWbyHMesh;
rs_mesh gTorusMesh;

rs_font gFontSans;
rs_font gFontSerif;
rs_font gFontSerifBold;
rs_font gFontSerifItalic;
rs_font gFontSerifBoldItalic;
rs_font gFontMono;
rs_allocation gTextAlloc;

int gDisplayMode;

rs_sampler gLinearClamp;
rs_sampler gLinearWrap;
rs_sampler gMipLinearWrap;
rs_sampler gMipLinearAniso8;
rs_sampler gMipLinearAniso15;
rs_sampler gNearestClamp;

rs_program_raster gCullBack;
rs_program_raster gCullFront;
rs_program_raster gCullNone;

// Custom vertex shader compunents
VertexShaderConstants *gVSConstants;
FragentShaderConstants *gFSConstants;
VertexShaderConstants3 *gVSConstPixel;
FragentShaderConstants3 *gFSConstPixel;
// Export these out to easily set the inputs to shader
VertexShaderInputs *gVSInputs;
// Custom shaders we use for lighting
rs_program_vertex gProgVertexCustom;
rs_program_fragment gProgFragmentCustom;
rs_program_vertex gProgVertexPixelLight;
rs_program_vertex gProgVertexPixelLightMove;
rs_program_fragment gProgFragmentPixelLight;
rs_program_fragment gProgFragmentMultitex;

float gDt = 0;

void init() {
}

static const char *sampleText = "This is a sample of small text for performace";
// Offsets for multiple layer of text
static int textOffsets[] = { 0,  0, -5, -5, 5,  5, -8, -8, 8,  8};
static float textColors[] = {1.0f, 1.0f, 1.0f, 1.0f,
                             0.5f, 0.7f, 0.5f, 1.0f,
                             0.7f, 0.5f, 0.5f, 1.0f,
                             0.5f, 0.5f, 0.7f, 1.0f,
                             0.5f, 0.6f, 0.7f, 1.0f,
};

static void displayFontSamples(int fillNum) {

    rs_font fonts[5];
    rsSetObject(&fonts[0], gFontSans);
    rsSetObject(&fonts[1], gFontSerif);
    rsSetObject(&fonts[2], gFontSerifBold);
    rsSetObject(&fonts[3], gFontSerifBoldItalic);
    rsSetObject(&fonts[4], gFontSans);

    uint width = rsgGetWidth();
    uint height = rsgGetHeight();
    int left = 0, right = 0, top = 0, bottom = 0;
    rsgMeasureText(sampleText, &left, &right, &top, &bottom);

    int textHeight = top - bottom;
    int textWidth = right - left;
    int numVerticalLines = height / textHeight;
    int yPos = top;

    int xOffset = 0, yOffset = 0;
    for(int fillI = 0; fillI < fillNum; fillI ++) {
        rsgBindFont(fonts[fillI]);
        xOffset = textOffsets[fillI * 2];
        yOffset = textOffsets[fillI * 2 + 1];
        float *colPtr = textColors + fillI * 4;
        rsgFontColor(colPtr[0], colPtr[1], colPtr[2], colPtr[3]);
        for (int h = 0; h < 4; h ++) {
            yPos = top + yOffset;
            for (int v = 0; v < numVerticalLines; v ++) {
                rsgDrawText(sampleText, xOffset + textWidth * h, yPos);
                yPos += textHeight;
            }
        }
    }

    for (int i = 0; i < 5; i ++) {
        rsClearObject(&fonts[i]);
    }
}

static void bindProgramVertexOrtho() {
    // Default vertex sahder
    rsgBindProgramVertex(gProgVertex);
    // Setup the projection matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, rsgGetWidth(), rsgGetHeight(), 0, -500, 500);
    rsgProgramVertexLoadProjectionMatrix(&proj);
}

static void displaySingletexFill(bool blend, int quadCount) {
    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    if (!blend) {
        rsgBindProgramStore(gProgStoreBlendNone);
    } else {
        rsgBindProgramStore(gProgStoreBlendAlpha);
    }
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    for (int i = 0; i < quadCount; i ++) {
        float startX = 10 * i, startY = 10 * i;
        float width = rsgGetWidth() - startX, height = rsgGetHeight() - startY;
        rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                             startX, startY + height, 0, 0, 1,
                             startX + width, startY + height, 0, 1, 1,
                             startX + width, startY, 0, 1, 0);
    }
}

static void displayBlendingSamples() {
    int i;

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    rsgBindProgramFragment(gProgFragmentColor);

    rsgBindProgramStore(gProgStoreBlendNone);
    for (i = 0; i < 3; i ++) {
        float iPlusOne = (float)(i + 1);
        rsgProgramFragmentConstantColor(gProgFragmentColor,
                                        0.1f*iPlusOne, 0.2f*iPlusOne, 0.3f*iPlusOne, 1);
        float yPos = 150 * (float)i;
        rsgDrawRect(0, yPos, 200, yPos + 200, 0);
    }

    rsgBindProgramStore(gProgStoreBlendAlpha);
    for (i = 0; i < 3; i ++) {
        float iPlusOne = (float)(i + 1);
        rsgProgramFragmentConstantColor(gProgFragmentColor,
                                        0.2f*iPlusOne, 0.3f*iPlusOne, 0.1f*iPlusOne, 0.5);
        float yPos = 150 * (float)i;
        rsgDrawRect(150, yPos, 350, yPos + 200, 0);
    }

    rsgBindProgramStore(gProgStoreBlendAdd);
    for (i = 0; i < 3; i ++) {
        float iPlusOne = (float)(i + 1);
        rsgProgramFragmentConstantColor(gProgFragmentColor,
                                        0.3f*iPlusOne, 0.1f*iPlusOne, 0.2f*iPlusOne, 0.5);
        float yPos = 150 * (float)i;
        rsgDrawRect(300, yPos, 500, yPos + 200, 0);
    }


    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("No Blending", 10, 50);
    rsgDrawText("Alpha Blending", 160, 150);
    rsgDrawText("Additive Blending", 320, 250);

}

static void displayMeshSamples(int meshNum) {

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadTranslate(&matrix, rsgGetWidth()/2, rsgGetHeight()/2, 0);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    if (meshNum == 0) {
        rsgDrawMesh(g10by10Mesh);
    } else if (meshNum == 1) {
        rsgDrawMesh(g100by100Mesh);
    } else if (meshNum == 2) {
        rsgDrawMesh(gWbyHMesh);
    }
}

static void displayTextureSamplers() {

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindTexture(gProgFragmentTexture, 0, gTexOpaque);

    // Linear clamp
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    float startX = 0, startY = 0;
    float width = 300, height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.1,
                         startX + width, startY + height, 0, 1.1, 1.1,
                         startX + width, startY, 0, 1.1, 0);

    // Linear Wrap
    rsgBindSampler(gProgFragmentTexture, 0, gLinearWrap);
    startX = 0; startY = 300;
    width = 300; height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.1,
                         startX + width, startY + height, 0, 1.1, 1.1,
                         startX + width, startY, 0, 1.1, 0);

    // Nearest
    rsgBindSampler(gProgFragmentTexture, 0, gNearestClamp);
    startX = 300; startY = 0;
    width = 300; height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.1,
                         startX + width, startY + height, 0, 1.1, 1.1,
                         startX + width, startY, 0, 1.1, 0);

    rsgBindSampler(gProgFragmentTexture, 0, gMipLinearWrap);
    startX = 300; startY = 300;
    width = 300; height = 300;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 1.5,
                         startX + width, startY + height, 0, 1.5, 1.5,
                         startX + width, startY, 0, 1.5, 0);

    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    rsgDrawText("Filtering: linear clamp", 10, 290);
    rsgDrawText("Filtering: linear wrap", 10, 590);
    rsgDrawText("Filtering: nearest clamp", 310, 290);
    rsgDrawText("Filtering: miplinear wrap", 310, 590);
}

static float gTorusRotation = 0;
static void updateModelMatrix(rs_matrix4x4 *matrix, void *buffer) {
    if (buffer == 0) {
        rsgProgramVertexLoadModelMatrix(matrix);
    } else {
        rsgAllocationSyncAll(rsGetAllocation(buffer));
    }
}

static void drawToruses(int numMeshes, rs_matrix4x4 *matrix, void *buffer) {

    if (numMeshes == 1) {
        rsMatrixLoadTranslate(matrix, 0.0f, 0.0f, -7.5f);
        rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
        updateModelMatrix(matrix, buffer);
        rsgDrawMesh(gTorusMesh);
        return;
    }

    if (numMeshes == 2) {
        rsMatrixLoadTranslate(matrix, -1.6f, 0.0f, -7.5f);
        rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
        updateModelMatrix(matrix, buffer);
        rsgDrawMesh(gTorusMesh);

        rsMatrixLoadTranslate(matrix, 1.6f, 0.0f, -7.5f);
        rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
        updateModelMatrix(matrix, buffer);
        rsgDrawMesh(gTorusMesh);
        return;
    }

    float startX = -5.0f;
    float startY = -1.5f;
    float startZ = -15.0f;
    float dist = 3.2f;

    for (int h = 0; h < 4; h ++) {
        for (int v = 0; v < 2; v ++) {
            // Position our model on the screen
            rsMatrixLoadTranslate(matrix, startX + dist * h, startY + dist * v, startZ);
            rsMatrixRotate(matrix, gTorusRotation, 1.0f, 0.0f, 0.0f);
            updateModelMatrix(matrix, buffer);
            rsgDrawMesh(gTorusMesh);
        }
    }
}


// Quick hack to get some geometry numbers
static void displaySimpleGeoSamples(bool useTexture, int numMeshes) {
    rsgBindProgramVertex(gProgVertex);
    rsgBindProgramRaster(gCullBack);
    // Setup the projection matrix with 30 degree field of view
    rs_matrix4x4 proj;
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 0.1f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    if (useTexture) {
        rsgBindProgramFragment(gProgFragmentTexture);
    } else {
        rsgBindProgramFragment(gProgFragmentColor);
        rsgProgramFragmentConstantColor(gProgFragmentColor, 0.1, 0.7, 0.1, 1);
    }
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gTexTorus);

    // Apply a rotation to our mesh
    gTorusRotation += 50.0f * gDt;
    if (gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    rs_matrix4x4 matrix;
    drawToruses(numMeshes, &matrix, 0);
}

float gLight0Rotation = 0;
float gLight1Rotation = 0;

static void setupCustomShaderLights() {
    float4 light0Pos = {-5.0f, 5.0f, -10.0f, 1.0f};
    float4 light1Pos = {2.0f, 5.0f, 15.0f, 1.0f};
    float4 light0DiffCol = {0.9f, 0.7f, 0.7f, 1.0f};
    float4 light0SpecCol = {0.9f, 0.6f, 0.6f, 1.0f};
    float4 light1DiffCol = {0.5f, 0.5f, 0.9f, 1.0f};
    float4 light1SpecCol = {0.5f, 0.5f, 0.9f, 1.0f};

    gLight0Rotation += 50.0f * gDt;
    if (gLight0Rotation > 360.0f) {
        gLight0Rotation -= 360.0f;
    }
    gLight1Rotation -= 50.0f * gDt;
    if (gLight1Rotation > 360.0f) {
        gLight1Rotation -= 360.0f;
    }

    rs_matrix4x4 l0Mat;
    rsMatrixLoadRotate(&l0Mat, gLight0Rotation, 1.0f, 0.0f, 0.0f);
    light0Pos = rsMatrixMultiply(&l0Mat, light0Pos);
    rs_matrix4x4 l1Mat;
    rsMatrixLoadRotate(&l1Mat, gLight1Rotation, 0.0f, 0.0f, 1.0f);
    light1Pos = rsMatrixMultiply(&l1Mat, light1Pos);

    // Set light 0 properties
    gVSConstants->light0_Posision = light0Pos;
    gVSConstants->light0_Diffuse = 1.0f;
    gVSConstants->light0_Specular = 0.5f;
    gVSConstants->light0_CosinePower = 10.0f;
    // Set light 1 properties
    gVSConstants->light1_Posision = light1Pos;
    gVSConstants->light1_Diffuse = 1.0f;
    gVSConstants->light1_Specular = 0.7f;
    gVSConstants->light1_CosinePower = 25.0f;
    rsgAllocationSyncAll(rsGetAllocation(gVSConstants));

    // Update fragment shader constants
    // Set light 0 colors
    gFSConstants->light0_DiffuseColor = light0DiffCol;
    gFSConstants->light0_SpecularColor = light0SpecCol;
    // Set light 1 colors
    gFSConstants->light1_DiffuseColor = light1DiffCol;
    gFSConstants->light1_SpecularColor = light1SpecCol;
    rsgAllocationSyncAll(rsGetAllocation(gFSConstants));

    // Set light 0 properties for per pixel lighting
    gFSConstPixel->light0_Posision = light0Pos;
    gFSConstPixel->light0_Diffuse = 1.0f;
    gFSConstPixel->light0_Specular = 0.5f;
    gFSConstPixel->light0_CosinePower = 10.0f;
    gFSConstPixel->light0_DiffuseColor = light0DiffCol;
    gFSConstPixel->light0_SpecularColor = light0SpecCol;
    // Set light 1 properties
    gFSConstPixel->light1_Posision = light1Pos;
    gFSConstPixel->light1_Diffuse = 1.0f;
    gFSConstPixel->light1_Specular = 0.7f;
    gFSConstPixel->light1_CosinePower = 25.0f;
    gFSConstPixel->light1_DiffuseColor = light1DiffCol;
    gFSConstPixel->light1_SpecularColor = light1SpecCol;
    rsgAllocationSyncAll(rsGetAllocation(gFSConstPixel));
}

static void displayCustomShaderSamples(int numMeshes) {

    // Update vertex shader constants
    // Load model matrix
    // Apply a rotation to our mesh
    gTorusRotation += 50.0f * gDt;
    if (gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    // Setup the projection matrix
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&gVSConstants->proj, 30.0f, aspect, 0.1f, 100.0f);
    setupCustomShaderLights();

    rsgBindProgramVertex(gProgVertexCustom);

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    rsgBindProgramFragment(gProgFragmentCustom);
    rsgBindSampler(gProgFragmentCustom, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentCustom, 0, gTexTorus);

    // Use back face culling
    rsgBindProgramRaster(gCullBack);

    drawToruses(numMeshes, &gVSConstants->model, gVSConstants);
}

static void displayPixelLightSamples(int numMeshes, bool heavyVertex) {

    // Update vertex shader constants
    // Load model matrix
    // Apply a rotation to our mesh
    gTorusRotation += 30.0f * gDt;
    if (gTorusRotation > 360.0f) {
        gTorusRotation -= 360.0f;
    }

    gVSConstPixel->time = rsUptimeMillis()*0.005;

    // Setup the projection matrix
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsMatrixLoadPerspective(&gVSConstPixel->proj, 30.0f, aspect, 0.1f, 100.0f);
    setupCustomShaderLights();

    if (heavyVertex) {
        rsgBindProgramVertex(gProgVertexPixelLightMove);
    } else {
        rsgBindProgramVertex(gProgVertexPixelLight);
    }

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNoneDepth);
    rsgBindProgramFragment(gProgFragmentPixelLight);
    rsgBindSampler(gProgFragmentPixelLight, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentPixelLight, 0, gTexTorus);

    // Use back face culling
    rsgBindProgramRaster(gCullBack);

    drawToruses(numMeshes, &gVSConstPixel->model, gVSConstPixel);
}

static void displayMultitextureSample(bool blend, int quadCount) {
    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    // Fragment shader with texture
    if (!blend) {
        rsgBindProgramStore(gProgStoreBlendNone);
    } else {
        rsgBindProgramStore(gProgStoreBlendAlpha);
    }
    rsgBindProgramFragment(gProgFragmentMultitex);
    rsgBindSampler(gProgFragmentMultitex, 0, gLinearClamp);
    rsgBindSampler(gProgFragmentMultitex, 1, gLinearWrap);
    rsgBindSampler(gProgFragmentMultitex, 2, gLinearClamp);
    rsgBindTexture(gProgFragmentMultitex, 0, gTexChecker);
    rsgBindTexture(gProgFragmentMultitex, 1, gTexTorus);
    rsgBindTexture(gProgFragmentMultitex, 2, gTexTransparent);

    for (int i = 0; i < quadCount; i ++) {
        float startX = 10 * i, startY = 10 * i;
        float width = rsgGetWidth() - startX, height = rsgGetHeight() - startY;
        rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                             startX, startY + height, 0, 0, 1,
                             startX + width, startY + height, 0, 1, 1,
                             startX + width, startY, 0, 1, 0);
    }
}

static float gAnisoTime = 0.0f;
static uint anisoMode = 0;
static void displayAnisoSample() {

    gAnisoTime += gDt;

    rsgBindProgramVertex(gProgVertex);
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rs_matrix4x4 proj;
    rsMatrixLoadPerspective(&proj, 30.0f, aspect, 0.1f, 100.0f);
    rsgProgramVertexLoadProjectionMatrix(&proj);

    rs_matrix4x4 matrix;
    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendNone);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsMatrixLoadTranslate(&matrix, 0.0f, 0.0f, -10.0f);
    rsMatrixRotate(&matrix, -80, 1.0f, 0.0f, 0.0f);
    rsgProgramVertexLoadModelMatrix(&matrix);

    rsgBindProgramRaster(gCullNone);

    rsgBindTexture(gProgFragmentTexture, 0, gTexChecker);

    if (gAnisoTime >= 5.0f) {
        gAnisoTime = 0.0f;
        anisoMode ++;
        anisoMode = anisoMode % 3;
    }

    if (anisoMode == 0) {
        rsgBindSampler(gProgFragmentTexture, 0, gMipLinearAniso8);
    } else if (anisoMode == 1) {
        rsgBindSampler(gProgFragmentTexture, 0, gMipLinearAniso15);
    } else {
        rsgBindSampler(gProgFragmentTexture, 0, gMipLinearWrap);
    }

    float startX = -15;
    float startY = -15;
    float width = 30;
    float height = 30;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                         startX, startY + height, 0, 0, 10,
                         startX + width, startY + height, 0, 10, 10,
                         startX + width, startY, 0, 10, 0);

    rsgBindProgramRaster(gCullBack);

    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgBindFont(gFontMono);
    if (anisoMode == 0) {
        rsgDrawText("Anisotropic filtering 8", 10, 40);
    } else if (anisoMode == 1) {
        rsgDrawText("Anisotropic filtering 15", 10, 40);
    } else {
        rsgDrawText("Miplinear filtering", 10, 40);
    }
}

static bool checkInit() {

    static int countdown = 5;

    if (countdown == 0) {
        gDt = 0;
        countdown --;
    }
    // Perform all the uploads so we only measure rendered time
    if(countdown > 1) {
        displayFontSamples(5);
        displaySingletexFill(true, 3);
        displayBlendingSamples();
        displayMeshSamples(0);
        displayMeshSamples(1);
        displayMeshSamples(2);
        displayTextureSamplers();
        displayMultitextureSample(true, 5);
        displayAnisoSample();
        displayPixelLightSamples(1, false);
        displayPixelLightSamples(1, true);
        countdown --;
        rsgClearColor(0.2f, 0.2f, 0.2f, 0.0f);

        // Now use text metrics to center the text
        uint width = rsgGetWidth();
        uint height = rsgGetHeight();
        int left = 0, right = 0, top = 0, bottom = 0;

        rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
        rsgBindFont(gFontSerifBoldItalic);

        const char* text = "Initializing";
        rsgMeasureText(text, &left, &right, &top, &bottom);
        int centeredPosX = width / 2 - (right - left) / 2;
        int centeredPosY = height / 2 - (top - bottom) / 2;
        rsgDrawText(text, centeredPosX, centeredPosY);

        return false;
    }

    return true;
}

static int frameCount = 0;
static int totalFramesRendered = 0;
static int benchMode = 0;

#define testTime 5.0f
static float curTestTime = testTime;

static const char *testNames[] = {
    "Finished text fill 1",
    "Finished text fill 2",
    "Finished text fill 3",
    "Finished text fill 4",
    "Finished text fill 5",
    "Finished 25.6k geo flat color",
    "Finished 51.2k geo flat color",
    "Finished 204.8k geo raster load flat color",
    "Finished 25.6k geo texture",
    "Finished 51.2k geo texture",
    "Finished 204.8k geo raster load texture",
    "Finished full screen mesh 10 by 10",
    "Finished full screen mesh 100 by 100",
    "Finished full screen mesh W / 4 by H / 4",
    "Finished 25.6k geo heavy vertex",
    "Finished 51.2k geo heavy vertex",
    "Finished 204.8k geo raster load heavy vertex",
    "Finished singletexture 5x fill",
    "Finished 3tex multitexture 5x fill",
    "Finished blend singletexture 5x fill",
    "Finished blend 3tex multitexture 5x fill",
    "Finished 25.6k geo heavy fragment",
    "Finished 51.2k geo heavy fragment",
    "Finished 204.8k geo raster load heavy fragment",
    "Finished 25.6k geo heavy fragment, heavy vertex",
    "Finished 51.2k geo heavy fragment, heavy vertex",
    "Finished 204.8k geo raster load heavy fragment, heavy vertex",
};

int root(int launchID) {

    gDt = rsGetDt();

    rsgClearColor(0.2f, 0.2f, 0.2f, 0.0f);
    rsgClearDepth(1.0f);

    if(!checkInit()) {
        return 1;
    }

    curTestTime -= gDt;
    if(curTestTime < 0.0f) {
        float fps = (float)(frameCount) / (testTime - curTestTime);
        rsDebug(testNames[benchMode], fps);
        benchMode ++;
        curTestTime = testTime;
        totalFramesRendered += frameCount;
        frameCount = 0;
        gTorusRotation = 0;

        if (benchMode > gMaxModes) {
            benchMode = 0;
        }
    }

    switch (benchMode) {
    case 0:
        displayFontSamples(1);
        break;
    case 1:
        displayFontSamples(2);
        break;
    case 2:
        displayFontSamples(3);
        break;
    case 3:
        displayFontSamples(4);
        break;
    case 4:
        displayFontSamples(5);
        break;
    case 5:
        displaySimpleGeoSamples(false, 1);
        break;
    case 6:
        displaySimpleGeoSamples(false, 2);
        break;
    case 7:
        displaySimpleGeoSamples(false, 8);
        break;
    case 8:
        displaySimpleGeoSamples(true, 1);
        break;
    case 9:
        displaySimpleGeoSamples(true, 2);
        break;
    case 10:
        displaySimpleGeoSamples(true, 8);
        break;
    case 11:
        displayMeshSamples(0);
        break;
    case 12:
        displayMeshSamples(1);
        break;
    case 13:
        displayMeshSamples(2);
        break;
    case 14:
        displayCustomShaderSamples(1);
        break;
    case 15:
        displayCustomShaderSamples(2);
        break;
    case 16:
        displayCustomShaderSamples(8);
        break;
    case 17:
        displaySingletexFill(false, 5);
        break;
    case 18:
        displayMultitextureSample(false, 5);
        break;
    case 19:
        displaySingletexFill(true, 5);
        break;
    case 20:
        displayMultitextureSample(true, 5);
        break;
    case 21:
        displayPixelLightSamples(1, false);
        break;
    case 22:
        displayPixelLightSamples(2, false);
        break;
    case 23:
        displayPixelLightSamples(8, false);
        break;
    case 24:
        displayPixelLightSamples(1, true);
        break;
    case 25:
        displayPixelLightSamples(2, true);
        break;
    case 26:
        displayPixelLightSamples(8, true);
        break;

    }

    frameCount ++;

    return 1;
}
