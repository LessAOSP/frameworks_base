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

/* Message sent from script to renderscript */
const int RS_MSG_TEST_DONE = 100;
const int RS_MSG_RESULTS_READY = 101;

const int gMaxModes = 30;
int gMaxLoops;

// Allocation to send test names back to java
char *gStringBuffer = 0;
// Allocation to write the results into
static float gResultBuffer[gMaxModes];

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
rs_allocation gTexGlobe;

typedef struct ListAllocs_s {
    rs_allocation item;
} ListAllocs;

ListAllocs *gTexList100;
ListAllocs *gSampleTextList100;
ListAllocs *gListViewText;

rs_mesh g10by10Mesh;
rs_mesh g100by100Mesh;
rs_mesh gWbyHMesh;
rs_mesh gTorusMesh;
rs_mesh gSingleMesh;

rs_font gFontSans;
rs_font gFontSerif;

int gDisplayMode;

rs_sampler gLinearClamp;
rs_sampler gLinearWrap;
rs_sampler gMipLinearWrap;
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

rs_allocation gRenderBufferColor;
rs_allocation gRenderBufferDepth;

float gDt = 0;

void init() {
}

static int gRenderSurfaceW;
static int gRenderSurfaceH;

static const char *sampleText = "This is a sample of small text for performace";
// Offsets for multiple layer of text
static int textOffsets[] = { 0,  0, -5, -5, 5,  5, -8, -8, 8,  8};
static float textColors[] = {1.0f, 1.0f, 1.0f, 1.0f,
                             0.5f, 0.7f, 0.5f, 1.0f,
                             0.7f, 0.5f, 0.5f, 1.0f,
                             0.5f, 0.5f, 0.7f, 1.0f,
                             0.5f, 0.6f, 0.7f, 1.0f,
};

static void setupOffscreenTarget() {
    rsgBindColorTarget(gRenderBufferColor, 0);
    rsgBindDepthTarget(gRenderBufferDepth);
}

static void displayFontSamples(int fillNum) {

    rs_font fonts[5];
    fonts[0] = gFontSans;
    fonts[1] = gFontSerif;
    fonts[2] = gFontSans;
    fonts[3] = gFontSerif;
    fonts[4] = gFontSans;

    uint width = gRenderSurfaceW;
    uint height = gRenderSurfaceH;
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
}

static void bindProgramVertexOrtho() {
    // Default vertex shader
    rsgBindProgramVertex(gProgVertex);
    // Setup the projection matrix
    rs_matrix4x4 proj;
    rsMatrixLoadOrtho(&proj, 0, gRenderSurfaceW, gRenderSurfaceH, 0, -500, 500);
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
        float startX = 5 * i, startY = 5 * i;
        float width = gRenderSurfaceW - startX, height = gRenderSurfaceH - startY;
        rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                             startX, startY + height, 0, 0, 1,
                             startX + width, startY + height, 0, 1, 1,
                             startX + width, startY, 0, 1, 0);
    }
}

static void displayMeshSamples(int meshNum) {

    bindProgramVertexOrtho();
    rs_matrix4x4 matrix;
    rsMatrixLoadTranslate(&matrix, gRenderSurfaceW/2, gRenderSurfaceH/2, 0);
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

// Display sample images in a mesh with different texture
static void displayIcons(int meshMode) {
    bindProgramVertexOrtho();

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);

    int meshCount = (int)pow(10.0f, (float)(meshMode + 1));

    float size = 50.0;
    rs_matrix4x4 matrix;
    rsMatrixLoadScale(&matrix, size, size, 1.0);

    float yPos = 0;
    for (int y = 0; y < meshCount; y++) {
        yPos = (y + 1) * 50;
        float xPos = 0;
        for (int x = 0; x < meshCount; x++) {
            xPos = (x + 1) * 50;
            rs_matrix4x4 transMatrix;
            rsMatrixLoadTranslate(&transMatrix, xPos, yPos, 0);
            rsMatrixMultiply(&transMatrix, &matrix);
            rsgProgramVertexLoadModelMatrix(&transMatrix);
            int i = (x + y * meshCount) % 100;
            rsgBindTexture(gProgFragmentTexture, 0, gTexList100[i].item);
            rsgDrawMesh(gSingleMesh);
        }
    }
}

// Draw meshes in a single page with top left corner coordinates (xStart, yStart)
static void drawMeshInPage(float xStart, float yStart, int wResolution, int hResolution) {
    // Draw wResolution * hResolution meshes in one page
    float wMargin = 100.0f;
    float hMargin = 100.0f;
    float xPad = 50.0f;
    float yPad = 20.0f;
    float size = 100.0f;  // size of images

    // font info
    rs_font font = gFontSans;
    rsgBindFont(font);
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);

    // Measure text size
    int left = 0, right = 0, top = 0, bottom = 0;
    rsgMeasureText(gSampleTextList100[0].item, &left, &right, &top, &bottom);
    float textHeight = (float)(top - bottom);
    float textWidth = (float)(right - left);

    rs_matrix4x4 matrix;
    rsMatrixLoadScale(&matrix, size, size, 1.0);

    for (int y = 0; y < hResolution; y++) {
        float yPos = yStart + hMargin + y * size + y * yPad;
        for (int x = 0; x < wResolution; x++) {
            float xPos = xStart + wMargin + x * size + x * xPad;

            rs_matrix4x4 transMatrix;
            rsMatrixLoadTranslate(&transMatrix, xPos + size/2, yPos + size/2, 0);
            rsMatrixMultiply(&transMatrix, &matrix);  // scale the mesh
            rsgProgramVertexLoadModelMatrix(&transMatrix);

            int i = (y * wResolution + x) % 100;
            rsgBindTexture(gProgFragmentTexture, 0, gTexList100[i].item);
            rsgDrawMesh(gSingleMesh);
            rsgDrawText(gSampleTextList100[i].item, xPos, yPos + size + yPad/2 + textHeight);
        }
    }
}

// Display both images and text as shown in launcher and homepage
// meshMode will decide how many pages we draw
// meshMode = 0: draw 3 pages of meshes
// meshMode = 1: draw 5 pages of meshes
static void displayImageWithText(int wResolution, int hResolution, int meshMode) {
    bindProgramVertexOrtho();

    // Fragment shader with texture
    rsgBindProgramStore(gProgStoreBlendAlpha);
    rsgBindProgramFragment(gProgFragmentTexture);
    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);

    drawMeshInPage(0, 0, wResolution, hResolution);
    drawMeshInPage(-1.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    drawMeshInPage(1.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    if (meshMode == 1) {
        // draw another two pages of meshes
        drawMeshInPage(-2.0f*gRenderSurfaceW, 0, wResolution, hResolution);
        drawMeshInPage(2.0f*gRenderSurfaceW, 0, wResolution, hResolution);
    }
}

// Display a list of text as the list view
static void displayListView() {
    // set text color
    rsgFontColor(0.9f, 0.9f, 0.9f, 1.0f);
    rsgBindFont(gFontSans);

    // get the size of the list
    rs_allocation textAlloc;
    textAlloc = rsGetAllocation(gListViewText);
    int allocSize = rsAllocationGetDimX(textAlloc);

    int listItemHeight = 80;
    int yOffset = listItemHeight;

    // set the color for the list divider
    rsgBindProgramFragment(gProgFragmentColor);
    rsgProgramFragmentConstantColor(gProgFragmentColor, 1.0, 1.0, 1.0, 1);

    // draw the list with divider
    for (int i = 0; i < allocSize; i++) {
        if (yOffset - listItemHeight > gRenderSurfaceH) {
            break;
        }
        rsgDrawRect(0, yOffset - 1, gRenderSurfaceW, yOffset, 0);
        rsgDrawText(gListViewText[i].item, 20, yOffset - 10);
        yOffset += listItemHeight;
    }
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
    float aspect = (float)gRenderSurfaceW / (float)gRenderSurfaceH;
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
    float aspect = (float)gRenderSurfaceW / (float)gRenderSurfaceH;
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
    float aspect = (float)gRenderSurfaceW / (float)gRenderSurfaceH;
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
        float width = gRenderSurfaceW - startX, height = gRenderSurfaceH - startY;
        rsgDrawQuadTexCoords(startX, startY, 0, 0, 0,
                             startX, startY + height, 0, 0, 1,
                             startX + width, startY + height, 0, 1, 1,
                             startX + width, startY, 0, 1, 0);
    }
}

static bool checkInit() {

    static int countdown = 5;

    // Perform all the uploads so we only measure rendered time
    if(countdown > 1) {
        displayFontSamples(5);
        displaySingletexFill(true, 3);
        displayMeshSamples(0);
        displayMeshSamples(1);
        displayMeshSamples(2);
        displayMultitextureSample(true, 5);
        displayPixelLightSamples(1, false);
        displayPixelLightSamples(1, true);
        countdown --;
        rsgClearColor(0.2f, 0.2f, 0.2f, 0.0f);

        rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
        rsgBindFont(gFontSerif);
        if (countdown == 1) {
            rsgDrawText("Rendering", 50, 50);
        } else {
            rsgDrawText("Initializing", 50, 50);
        }

        return false;
    }

    return true;
}

static int benchMode = 0;
static int runningLoops = 0;
static bool sendMsgFlag = false;

static const char *testNames[] = {
    "Fill screen with text 1 time",
    "Fill screen with text 3 times",
    "Fill screen with text 5 times",
    "Geo test 25.6k flat color",
    "Geo test 51.2k flat color",
    "Geo test 204.8k small tries flat color",
    "Geo test 25.6k single texture",
    "Geo test 51.2k single texture",
    "Geo test 204.8k small tries single texture",
    "Full screen mesh 10 by 10",
    "Full screen mesh 100 by 100",
    "Full screen mesh W / 4 by H / 4",
    "Geo test 25.6k geo heavy vertex",
    "Geo test 51.2k geo heavy vertex",
    "Geo test 204.8k geo raster load heavy vertex",
    "Fill screen 10x singletexture",
    "Fill screen 10x 3tex multitexture",
    "Fill screen 10x blended singletexture",
    "Fill screen 10x blended 3tex multitexture",
    "Geo test 25.6k heavy fragment",
    "Geo test 51.2k heavy fragment",
    "Geo test 204.8k small tries heavy fragment",
    "Geo test 25.6k heavy fragment heavy vertex",
    "Geo test 51.2k heavy fragment heavy vertex",
    "Geo test 204.8k small tries heavy fragment heavy vertex",
    "UI test with icon display 10 by 10",
    "UI test with icon display 100 by 100",
    "UI test with image and text display 3 pages",
    "UI test with image and text display 5 pages",
    "UI test with list view",
};

void getTestName(int testIndex) {
    int bufferLen = rsAllocationGetDimX(rsGetAllocation(gStringBuffer));
    if (testIndex >= gMaxModes) {
        return;
    }
    uint charIndex = 0;
    while (testNames[testIndex][charIndex] != '\0' && charIndex < bufferLen) {
        gStringBuffer[charIndex] = testNames[testIndex][charIndex];
        charIndex ++;
    }
    gStringBuffer[charIndex] = '\0';
}

static void runTest(int index) {
    switch (index) {
    case 0:
        displayFontSamples(1);
        break;
    case 1:
        displayFontSamples(3);
        break;
    case 2:
        displayFontSamples(5);
        break;
    case 3:
        displaySimpleGeoSamples(false, 1);
        break;
    case 4:
        displaySimpleGeoSamples(false, 2);
        break;
    case 5:
        displaySimpleGeoSamples(false, 8);
        break;
    case 6:
        displaySimpleGeoSamples(true, 1);
        break;
    case 7:
        displaySimpleGeoSamples(true, 2);
        break;
    case 8:
        displaySimpleGeoSamples(true, 8);
        break;
    case 9:
        displayMeshSamples(0);
        break;
    case 10:
        displayMeshSamples(1);
        break;
    case 11:
        displayMeshSamples(2);
        break;
    case 12:
        displayCustomShaderSamples(1);
        break;
    case 13:
        displayCustomShaderSamples(2);
        break;
    case 14:
        displayCustomShaderSamples(10);
        break;
    case 15:
        displaySingletexFill(false, 10);
        break;
    case 16:
        displayMultitextureSample(false, 10);
        break;
    case 17:
        displaySingletexFill(true, 10);
        break;
    case 18:
        displayMultitextureSample(true, 8);
        break;
    case 19:
        displayPixelLightSamples(1, false);
        break;
    case 20:
        displayPixelLightSamples(2, false);
        break;
    case 21:
        displayPixelLightSamples(8, false);
        break;
    case 22:
        displayPixelLightSamples(1, true);
        break;
    case 23:
        displayPixelLightSamples(2, true);
        break;
    case 24:
        displayPixelLightSamples(8, true);
        break;
    case 25:
        displayIcons(0);
        break;
    case 26:
        displayIcons(1);
        break;
    case 27:
        displayImageWithText(7, 5, 0);
        break;
    case 28:
        displayImageWithText(7, 5, 1);
        break;
    case 29:
        displayListView();
        break;
    }
}

static void drawOffscreenResult(int posX, int posY, int width, int height) {
    bindProgramVertexOrtho();

    rs_matrix4x4 matrix;
    rsMatrixLoadIdentity(&matrix);
    rsgProgramVertexLoadModelMatrix(&matrix);

    rsgBindProgramFragment(gProgFragmentTexture);

    rsgBindSampler(gProgFragmentTexture, 0, gLinearClamp);
    rsgBindTexture(gProgFragmentTexture, 0, gRenderBufferColor);

    float startX = posX, startY = posY;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 1,
                         startX, startY + height, 0, 0, 0,
                         startX + width, startY + height, 0, 1, 0,
                         startX + width, startY, 0, 1, 1);
}

int root(void) {

    gRenderSurfaceW = rsgGetWidth();
    gRenderSurfaceH = rsgGetHeight();
    rsgClearColor(0.2f, 0.2f, 0.2f, 1.0f);
    rsgClearDepth(1.0f);
    if(!checkInit()) {
        return 1;
    }

    gDt = 1.0f / 60.0f;

    rsgFinish();
    int64_t start = rsUptimeMillis();

    int drawPos = 0;
    int frameCount = 100;
    for(int i = 0; i < frameCount; i ++) {
        setupOffscreenTarget();
        gRenderSurfaceW = rsAllocationGetDimX(gRenderBufferColor);
        gRenderSurfaceH = rsAllocationGetDimY(gRenderBufferColor);
        rsgClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        rsgClearDepth(1.0f);

        runTest(benchMode);
        rsgClearAllRenderTargets();
        gRenderSurfaceW = rsgGetWidth();
        gRenderSurfaceH = rsgGetHeight();
        int size = 8;
        // draw each frame at (8, 3/4 gRenderSurfaceH) with size
        drawOffscreenResult((drawPos+=size)%gRenderSurfaceW, (gRenderSurfaceH * 3) / 4, size, size);
    }

    rsgFinish();

    int64_t end = rsUptimeMillis();
    float fps = (float)(frameCount) / ((float)(end - start)*0.001f);
    rsDebug(testNames[benchMode], fps);
    gResultBuffer[benchMode] = fps;
    drawOffscreenResult(0, 0,
                        gRenderSurfaceW / 2,
                        gRenderSurfaceH / 2);
    const char* text = testNames[benchMode];
    int left = 0, right = 0, top = 0, bottom = 0;
    uint width = rsgGetWidth();
    uint height = rsgGetHeight();
    rsgFontColor(0.9f, 0.9f, 0.95f, 1.0f);
    rsgBindFont(gFontSerif);
    rsgMeasureText(text, &left, &right, &top, &bottom);
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    rsgDrawText(text, 2 -left, height - 2 + bottom);

    benchMode ++;

    gTorusRotation = 0;

    if (benchMode == gMaxModes) {
        rsSendToClientBlocking(RS_MSG_RESULTS_READY, gResultBuffer, gMaxModes*sizeof(float));
        benchMode = 0;
        runningLoops++;
        if ((gMaxLoops > 0) && (runningLoops > gMaxLoops) && !sendMsgFlag) {
            //Notifiy the test to stop and get results
            rsDebug("gMaxLoops and runningLoops: ", gMaxLoops, runningLoops);
            rsSendToClientBlocking(RS_MSG_TEST_DONE);
            sendMsgFlag = true;
        }
    }
    return 1;
}
