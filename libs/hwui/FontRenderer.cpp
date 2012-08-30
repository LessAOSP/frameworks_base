/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <SkUtils.h>

#include <cutils/properties.h>

#include <utils/Log.h>

#include "Caches.h"
#include "Debug.h"
#include "FontRenderer.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define DEFAULT_TEXT_CACHE_WIDTH 1024
#define DEFAULT_TEXT_CACHE_HEIGHT 256
#define MAX_TEXT_CACHE_WIDTH 2048
#define CACHE_BLOCK_ROUNDING_SIZE 4

#define AUTO_KERN(prev, next) (((next) - (prev) + 32) >> 6 << 16)

///////////////////////////////////////////////////////////////////////////////
// CacheBlock
///////////////////////////////////////////////////////////////////////////////

/**
 * Insert new block into existing linked list of blocks. Blocks are sorted in increasing-width
 * order, except for the final block (the remainder space at the right, since we fill from the
 * left).
 */
CacheBlock* CacheBlock::insertBlock(CacheBlock* head, CacheBlock *newBlock) {
#if DEBUG_FONT_RENDERER
    ALOGD("insertBlock: this, x, y, w, h = %p, %d, %d, %d, %d",
            newBlock, newBlock->mX, newBlock->mY,
            newBlock->mWidth, newBlock->mHeight);
#endif
    CacheBlock *currBlock = head;
    CacheBlock *prevBlock = NULL;
    while (currBlock && currBlock->mY != TEXTURE_BORDER_SIZE) {
        if (newBlock->mWidth < currBlock->mWidth) {
            newBlock->mNext = currBlock;
            newBlock->mPrev = prevBlock;
            currBlock->mPrev = newBlock;
            if (prevBlock) {
                prevBlock->mNext = newBlock;
                return head;
            } else {
                return newBlock;
            }
        }
        prevBlock = currBlock;
        currBlock = currBlock->mNext;
    }
    // new block larger than all others - insert at end (but before the remainder space, if there)
    newBlock->mNext = currBlock;
    newBlock->mPrev = prevBlock;
    if (currBlock) {
        currBlock->mPrev = newBlock;
    }
    if (prevBlock) {
        prevBlock->mNext = newBlock;
        return head;
    } else {
        return newBlock;
    }
}

CacheBlock* CacheBlock::removeBlock(CacheBlock* head, CacheBlock *blockToRemove) {
#if DEBUG_FONT_RENDERER
    ALOGD("removeBlock: this, x, y, w, h = %p, %d, %d, %d, %d",
            blockToRemove, blockToRemove->mX, blockToRemove->mY,
            blockToRemove->mWidth, blockToRemove->mHeight);
#endif
    CacheBlock* newHead = head;
    CacheBlock* nextBlock = blockToRemove->mNext;
    CacheBlock* prevBlock = blockToRemove->mPrev;
    if (prevBlock) {
        prevBlock->mNext = nextBlock;
    } else {
        newHead = nextBlock;
    }
    if (nextBlock) {
        nextBlock->mPrev = prevBlock;
    }
    delete blockToRemove;
    return newHead;
}

///////////////////////////////////////////////////////////////////////////////
// CacheTexture
///////////////////////////////////////////////////////////////////////////////

bool CacheTexture::fitBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY) {
    if (glyph.fHeight + TEXTURE_BORDER_SIZE > mHeight) {
        return false;
    }

    uint16_t glyphW = glyph.fWidth + TEXTURE_BORDER_SIZE;
    uint16_t glyphH = glyph.fHeight + TEXTURE_BORDER_SIZE;
    // roundedUpW equals glyphW to the next multiple of CACHE_BLOCK_ROUNDING_SIZE.
    // This columns for glyphs that are close but not necessarily exactly the same size. It trades
    // off the loss of a few pixels for some glyphs against the ability to store more glyphs
    // of varying sizes in one block.
    uint16_t roundedUpW =
            (glyphW + CACHE_BLOCK_ROUNDING_SIZE - 1) & -CACHE_BLOCK_ROUNDING_SIZE;
    CacheBlock *cacheBlock = mCacheBlocks;
    while (cacheBlock) {
        // Store glyph in this block iff: it fits the block's remaining space and:
        // it's the remainder space (mY == 0) or there's only enough height for this one glyph
        // or it's within ROUNDING_SIZE of the block width
        if (roundedUpW <= cacheBlock->mWidth && glyphH <= cacheBlock->mHeight &&
                (cacheBlock->mY == TEXTURE_BORDER_SIZE ||
                        (cacheBlock->mWidth - roundedUpW < CACHE_BLOCK_ROUNDING_SIZE))) {
            if (cacheBlock->mHeight - glyphH < glyphH) {
                // Only enough space for this glyph - don't bother rounding up the width
                roundedUpW = glyphW;
            }
            *retOriginX = cacheBlock->mX;
            *retOriginY = cacheBlock->mY;
            // If this is the remainder space, create a new cache block for this column. Otherwise,
            // adjust the info about this column.
            if (cacheBlock->mY == TEXTURE_BORDER_SIZE) {
                uint16_t oldX = cacheBlock->mX;
                // Adjust remainder space dimensions
                cacheBlock->mWidth -= roundedUpW;
                cacheBlock->mX += roundedUpW;
                if (mHeight - glyphH >= glyphH) {
                    // There's enough height left over to create a new CacheBlock
                    CacheBlock *newBlock = new CacheBlock(oldX, glyphH + TEXTURE_BORDER_SIZE,
                            roundedUpW, mHeight - glyphH - TEXTURE_BORDER_SIZE);
#if DEBUG_FONT_RENDERER
                    ALOGD("fitBitmap: Created new block: this, x, y, w, h = %p, %d, %d, %d, %d",
                            newBlock, newBlock->mX, newBlock->mY,
                            newBlock->mWidth, newBlock->mHeight);
#endif
                    mCacheBlocks = CacheBlock::insertBlock(mCacheBlocks, newBlock);
                }
            } else {
                // Insert into current column and adjust column dimensions
                cacheBlock->mY += glyphH;
                cacheBlock->mHeight -= glyphH;
#if DEBUG_FONT_RENDERER
                ALOGD("fitBitmap: Added to existing block: this, x, y, w, h = %p, %d, %d, %d, %d",
                        cacheBlock, cacheBlock->mX, cacheBlock->mY,
                        cacheBlock->mWidth, cacheBlock->mHeight);
#endif
            }
            if (cacheBlock->mHeight < fmin(glyphH, glyphW)) {
                // If remaining space in this block is too small to be useful, remove it
                mCacheBlocks = CacheBlock::removeBlock(mCacheBlocks, cacheBlock);
            }
            mDirty = true;
#if DEBUG_FONT_RENDERER
            ALOGD("fitBitmap: current block list:");
            mCacheBlocks->output();
#endif
            ++mNumGlyphs;
            return true;
        }
        cacheBlock = cacheBlock->mNext;
    }
#if DEBUG_FONT_RENDERER
    ALOGD("fitBitmap: returning false for glyph of size %d, %d", glyphW, glyphH);
#endif
    return false;
}

///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

Font::Font(FontRenderer* state, uint32_t fontId, float fontSize,
        int flags, uint32_t italicStyle, uint32_t scaleX,
        SkPaint::Style style, uint32_t strokeWidth) :
        mState(state), mFontId(fontId), mFontSize(fontSize),
        mFlags(flags), mItalicStyle(italicStyle), mScaleX(scaleX),
        mStyle(style), mStrokeWidth(mStrokeWidth) {
}


Font::~Font() {
    for (uint32_t ct = 0; ct < mState->mActiveFonts.size(); ct++) {
        if (mState->mActiveFonts[ct] == this) {
            mState->mActiveFonts.removeAt(ct);
            break;
        }
    }

    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        delete mCachedGlyphs.valueAt(i);
    }
}

void Font::invalidateTextureCache(CacheTexture *cacheTexture) {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        CachedGlyphInfo* cachedGlyph = mCachedGlyphs.valueAt(i);
        if (cacheTexture == NULL || cachedGlyph->mCacheTexture == cacheTexture) {
            cachedGlyph->mIsValid = false;
        }
    }
}

void Font::measureCachedGlyph(CachedGlyphInfo *glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    int width = (int) glyph->mBitmapWidth;
    int height = (int) glyph->mBitmapHeight;

    if (bounds->bottom > nPenY) {
        bounds->bottom = nPenY;
    }
    if (bounds->left > nPenX) {
        bounds->left = nPenX;
    }
    if (bounds->right < nPenX + width) {
        bounds->right = nPenX + width;
    }
    if (bounds->top < nPenY + height) {
        bounds->top = nPenY + height;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop + glyph->mBitmapHeight;

    float u1 = glyph->mBitmapMinU;
    float u2 = glyph->mBitmapMaxU;
    float v1 = glyph->mBitmapMinV;
    float v2 = glyph->mBitmapMaxV;

    int width = (int) glyph->mBitmapWidth;
    int height = (int) glyph->mBitmapHeight;

    mState->appendMeshQuad(nPenX, nPenY, u1, v2,
            nPenX + width, nPenY, u2, v2,
            nPenX + width, nPenY - height, u2, v1,
            nPenX, nPenY - height, u1, v1, glyph->mCacheTexture);
}

void Font::drawCachedGlyphBitmap(CachedGlyphInfo* glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    uint32_t endX = glyph->mStartX + glyph->mBitmapWidth;
    uint32_t endY = glyph->mStartY + glyph->mBitmapHeight;

    CacheTexture *cacheTexture = glyph->mCacheTexture;
    uint32_t cacheWidth = cacheTexture->mWidth;
    const uint8_t* cacheBuffer = cacheTexture->mTexture;

    uint32_t cacheX = 0, cacheY = 0;
    int32_t bX = 0, bY = 0;
    for (cacheX = glyph->mStartX, bX = nPenX; cacheX < endX; cacheX++, bX++) {
        for (cacheY = glyph->mStartY, bY = nPenY; cacheY < endY; cacheY++, bY++) {
#if DEBUG_FONT_RENDERER
            if (bX < 0 || bY < 0 || bX >= (int32_t) bitmapW || bY >= (int32_t) bitmapH) {
                ALOGE("Skipping invalid index");
                continue;
            }
#endif
            uint8_t tempCol = cacheBuffer[cacheY * cacheWidth + cacheX];
            bitmap[bY * bitmapW + bX] = tempCol;
        }
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, float x, float hOffset, float vOffset,
        SkPathMeasure& measure, SkPoint* position, SkVector* tangent) {
    const float halfWidth = glyph->mBitmapWidth * 0.5f;
    const float height = glyph->mBitmapHeight;

    vOffset += glyph->mBitmapTop + height;

    SkPoint destination[4];
    measure.getPosTan(x + hOffset +  glyph->mBitmapLeft + halfWidth, position, tangent);

    // Move along the tangent and offset by the normal
    destination[0].set(-tangent->fX * halfWidth - tangent->fY * vOffset,
            -tangent->fY * halfWidth + tangent->fX * vOffset);
    destination[1].set(tangent->fX * halfWidth - tangent->fY * vOffset,
            tangent->fY * halfWidth + tangent->fX * vOffset);
    destination[2].set(destination[1].fX + tangent->fY * height,
            destination[1].fY - tangent->fX * height);
    destination[3].set(destination[0].fX + tangent->fY * height,
            destination[0].fY - tangent->fX * height);

    const float u1 = glyph->mBitmapMinU;
    const float u2 = glyph->mBitmapMaxU;
    const float v1 = glyph->mBitmapMinV;
    const float v2 = glyph->mBitmapMaxV;

    mState->appendRotatedMeshQuad(
            position->fX + destination[0].fX,
            position->fY + destination[0].fY, u1, v2,
            position->fX + destination[1].fX,
            position->fY + destination[1].fY, u2, v2,
            position->fX + destination[2].fX,
            position->fY + destination[2].fY, u2, v1,
            position->fX + destination[3].fX,
            position->fY + destination[3].fY, u1, v1,
            glyph->mCacheTexture);
}

CachedGlyphInfo* Font::getCachedGlyph(SkPaint* paint, glyph_t textUnit, bool precaching) {
    CachedGlyphInfo* cachedGlyph = NULL;
    ssize_t index = mCachedGlyphs.indexOfKey(textUnit);
    if (index >= 0) {
        cachedGlyph = mCachedGlyphs.valueAt(index);
    } else {
        cachedGlyph = cacheGlyph(paint, textUnit, precaching);
    }

    // Is the glyph still in texture cache?
    if (!cachedGlyph->mIsValid) {
        const SkGlyph& skiaGlyph = GET_METRICS(paint, textUnit);
        updateGlyphCache(paint, skiaGlyph, cachedGlyph, precaching);
    }

    return cachedGlyph;
}

void Font::render(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y, uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    if (bitmap != NULL && bitmapW > 0 && bitmapH > 0) {
        render(paint, text, start, len, numGlyphs, x, y, BITMAP, bitmap,
                bitmapW, bitmapH, NULL, NULL);
    } else {
        render(paint, text, start, len, numGlyphs, x, y, FRAMEBUFFER, NULL,
                0, 0, NULL, NULL);
    }
}

void Font::render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, const float* positions) {
    render(paint, text, start, len, numGlyphs, x, y, FRAMEBUFFER, NULL,
            0, 0, NULL, positions);
}

void Font::render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
        int numGlyphs, SkPath* path, float hOffset, float vOffset) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    text += start;

    int glyphsCount = 0;
    SkFixed prevRsbDelta = 0;

    float penX = 0.0f;

    SkPoint position;
    SkVector tangent;

    SkPathMeasure measure(*path, false);
    float pathLength = SkScalarToFloat(measure.getLength());

    if (paint->getTextAlign() != SkPaint::kLeft_Align) {
        float textWidth = SkScalarToFloat(paint->measureText(text, len));
        float pathOffset = pathLength;
        if (paint->getTextAlign() == SkPaint::kCenter_Align) {
            textWidth *= 0.5f;
            pathOffset *= 0.5f;
        }
        penX += pathOffset - textWidth;
    }

    while (glyphsCount < numGlyphs && penX < pathLength) {
        glyph_t glyph = GET_GLYPH(text);

        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
        penX += SkFixedToFloat(AUTO_KERN(prevRsbDelta, cachedGlyph->mLsbDelta));
        prevRsbDelta = cachedGlyph->mRsbDelta;

        if (cachedGlyph->mIsValid) {
            drawCachedGlyph(cachedGlyph, penX, hOffset, vOffset, measure, &position, &tangent);
        }

        penX += SkFixedToFloat(cachedGlyph->mAdvanceX);

        glyphsCount++;
    }
}

void Font::measure(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, Rect *bounds, const float* positions) {
    if (bounds == NULL) {
        ALOGE("No return rectangle provided to measure text");
        return;
    }
    bounds->set(1e6, -1e6, -1e6, 1e6);
    render(paint, text, start, len, numGlyphs, 0, 0, MEASURE, NULL, 0, 0, bounds, positions);
}

void Font::precache(SkPaint* paint, const char* text, int numGlyphs) {

    if (numGlyphs == 0 || text == NULL) {
        return;
    }
    int glyphsCount = 0;

    while (glyphsCount < numGlyphs) {
        glyph_t glyph = GET_GLYPH(text);

        // Reached the end of the string
        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph, true);

        glyphsCount++;
    }
}

void Font::render(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
        uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* positions) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    static RenderGlyph gRenderGlyph[] = {
            &android::uirenderer::Font::drawCachedGlyph,
            &android::uirenderer::Font::drawCachedGlyphBitmap,
            &android::uirenderer::Font::measureCachedGlyph
    };
    RenderGlyph render = gRenderGlyph[mode];

    text += start;
    int glyphsCount = 0;

    if (CC_LIKELY(positions == NULL)) {
        SkFixed prevRsbDelta = 0;

        float penX = x + 0.5f;
        int penY = y;

        while (glyphsCount < numGlyphs) {
            glyph_t glyph = GET_GLYPH(text);

            // Reached the end of the string
            if (IS_END_OF_STRING(glyph)) {
                break;
            }

            CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
            penX += SkFixedToFloat(AUTO_KERN(prevRsbDelta, cachedGlyph->mLsbDelta));
            prevRsbDelta = cachedGlyph->mRsbDelta;

            // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
            if (cachedGlyph->mIsValid) {
                (*this.*render)(cachedGlyph, (int) floorf(penX), penY,
                        bitmap, bitmapW, bitmapH, bounds, positions);
            }

            penX += SkFixedToFloat(cachedGlyph->mAdvanceX);

            glyphsCount++;
        }
    } else {
        const SkPaint::Align align = paint->getTextAlign();

        // This is for renderPosText()
        while (glyphsCount < numGlyphs) {
            glyph_t glyph = GET_GLYPH(text);

            // Reached the end of the string
            if (IS_END_OF_STRING(glyph)) {
                break;
            }

            CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);

            // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
            if (cachedGlyph->mIsValid) {
                int penX = x + positions[(glyphsCount << 1)];
                int penY = y + positions[(glyphsCount << 1) + 1];

                switch (align) {
                    case SkPaint::kRight_Align:
                        penX -= SkFixedToFloat(cachedGlyph->mAdvanceX);
                        penY -= SkFixedToFloat(cachedGlyph->mAdvanceY);
                        break;
                    case SkPaint::kCenter_Align:
                        penX -= SkFixedToFloat(cachedGlyph->mAdvanceX >> 1);
                        penY -= SkFixedToFloat(cachedGlyph->mAdvanceY >> 1);
                    default:
                        break;
                }

                (*this.*render)(cachedGlyph, penX, penY,
                        bitmap, bitmapW, bitmapH, bounds, positions);
            }

            glyphsCount++;
        }
    }
}

void Font::updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo* glyph,
        bool precaching) {
    glyph->mAdvanceX = skiaGlyph.fAdvanceX;
    glyph->mAdvanceY = skiaGlyph.fAdvanceY;
    glyph->mBitmapLeft = skiaGlyph.fLeft;
    glyph->mBitmapTop = skiaGlyph.fTop;
    glyph->mLsbDelta = skiaGlyph.fLsbDelta;
    glyph->mRsbDelta = skiaGlyph.fRsbDelta;

    uint32_t startX = 0;
    uint32_t startY = 0;

    // Get the bitmap for the glyph
    paint->findImage(skiaGlyph);
    mState->cacheBitmap(skiaGlyph, glyph, &startX, &startY, precaching);

    if (!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + skiaGlyph.fWidth;
    uint32_t endY = startY + skiaGlyph.fHeight;

    glyph->mStartX = startX;
    glyph->mStartY = startY;
    glyph->mBitmapWidth = skiaGlyph.fWidth;
    glyph->mBitmapHeight = skiaGlyph.fHeight;

    uint32_t cacheWidth = glyph->mCacheTexture->mWidth;
    uint32_t cacheHeight = glyph->mCacheTexture->mHeight;

    glyph->mBitmapMinU = startX / (float) cacheWidth;
    glyph->mBitmapMinV = startY / (float) cacheHeight;
    glyph->mBitmapMaxU = endX / (float) cacheWidth;
    glyph->mBitmapMaxV = endY / (float) cacheHeight;

    mState->mUploadTexture = true;
}

CachedGlyphInfo* Font::cacheGlyph(SkPaint* paint, glyph_t glyph, bool precaching) {
    CachedGlyphInfo* newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);

    const SkGlyph& skiaGlyph = GET_METRICS(paint, glyph);
    newGlyph->mGlyphIndex = skiaGlyph.fID;
    newGlyph->mIsValid = false;

    updateGlyphCache(paint, skiaGlyph, newGlyph, precaching);

    return newGlyph;
}

Font* Font::create(FontRenderer* state, uint32_t fontId, float fontSize,
        int flags, uint32_t italicStyle, uint32_t scaleX,
        SkPaint::Style style, uint32_t strokeWidth) {
    Vector<Font*> &activeFonts = state->mActiveFonts;

    for (uint32_t i = 0; i < activeFonts.size(); i++) {
        Font* font = activeFonts[i];
        if (font->mFontId == fontId && font->mFontSize == fontSize &&
                font->mFlags == flags && font->mItalicStyle == italicStyle &&
                font->mScaleX == scaleX && font->mStyle == style &&
                (style == SkPaint::kFill_Style || font->mStrokeWidth == strokeWidth)) {
            return font;
        }
    }

    Font* newFont = new Font(state, fontId, fontSize, flags, italicStyle,
            scaleX, style, strokeWidth);
    activeFonts.push(newFont);
    return newFont;
}

///////////////////////////////////////////////////////////////////////////////
// FontRenderer
///////////////////////////////////////////////////////////////////////////////

static bool sLogFontRendererCreate = true;

FontRenderer::FontRenderer() {
    if (sLogFontRendererCreate) {
        INIT_LOGD("Creating FontRenderer");
    }

    mGammaTable = NULL;
    mInitialized = false;
    mMaxNumberOfQuads = 1024;
    mCurrentQuadIndex = 0;

    mTextMeshPtr = NULL;
    mCurrentCacheTexture = NULL;
    mLastCacheTexture = NULL;

    mLinearFiltering = false;

    mIndexBufferID = 0;

    mSmallCacheWidth = DEFAULT_TEXT_CACHE_WIDTH;
    mSmallCacheHeight = DEFAULT_TEXT_CACHE_HEIGHT;

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXT_CACHE_WIDTH, property, NULL) > 0) {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Setting text cache width to %s pixels", property);
        }
        mSmallCacheWidth = atoi(property);
    } else {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Using default text cache width of %i pixels", mSmallCacheWidth);
        }
    }

    if (property_get(PROPERTY_TEXT_CACHE_HEIGHT, property, NULL) > 0) {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Setting text cache width to %s pixels", property);
        }
        mSmallCacheHeight = atoi(property);
    } else {
        if (sLogFontRendererCreate) {
            INIT_LOGD("  Using default text cache height of %i pixels", mSmallCacheHeight);
        }
    }

    sLogFontRendererCreate = false;
}

FontRenderer::~FontRenderer() {
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        delete mCacheTextures[i];
    }
    mCacheTextures.clear();

    if (mInitialized) {
        // Unbinding the buffer shouldn't be necessary but it crashes with some drivers
        Caches::getInstance().unbindIndicesBuffer();
        glDeleteBuffers(1, &mIndexBufferID);

        delete[] mTextMeshPtr;
    }

    Vector<Font*> fontsToDereference = mActiveFonts;
    for (uint32_t i = 0; i < fontsToDereference.size(); i++) {
        delete fontsToDereference[i];
    }
}

void FontRenderer::flushAllAndInvalidate() {
    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }

    for (uint32_t i = 0; i < mActiveFonts.size(); i++) {
        mActiveFonts[i]->invalidateTextureCache();
    }

    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        mCacheTextures[i]->init();
    }

    #if DEBUG_FONT_RENDERER
    uint16_t totalGlyphs = 0;
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        totalGlyphs += mCacheTextures[i]->mNumGlyphs;
        // Erase caches, just as a debugging facility
        if (mCacheTextures[i]->mTexture) {
            memset(mCacheTextures[i]->mTexture, 0,
                    mCacheTextures[i]->mWidth * mCacheTextures[i]->mHeight);
        }
    }
    ALOGD("Flushing caches: glyphs cached = %d", totalGlyphs);
#endif
}

void FontRenderer::deallocateTextureMemory(CacheTexture *cacheTexture) {
    if (cacheTexture && cacheTexture->mTexture) {
        glDeleteTextures(1, &cacheTexture->mTextureId);
        delete[] cacheTexture->mTexture;
        cacheTexture->mTexture = NULL;
        cacheTexture->mTextureId = 0;
    }
}

void FontRenderer::flushLargeCaches() {
    // Start from 1; don't deallocate smallest/default texture
    for (uint32_t i = 1; i < mCacheTextures.size(); i++) {
        CacheTexture* cacheTexture = mCacheTextures[i];
        if (cacheTexture->mTexture != NULL) {
            cacheTexture->init();
            for (uint32_t j = 0; j < mActiveFonts.size(); j++) {
                mActiveFonts[j]->invalidateTextureCache(cacheTexture);
            }
            deallocateTextureMemory(cacheTexture);
        }
    }
}

void FontRenderer::allocateTextureMemory(CacheTexture* cacheTexture) {
    int width = cacheTexture->mWidth;
    int height = cacheTexture->mHeight;

    cacheTexture->mTexture = new uint8_t[width * height];

    if (!cacheTexture->mTextureId) {
        glGenTextures(1, &cacheTexture->mTextureId);
    }

    Caches::getInstance().activeTexture(0);
    glBindTexture(GL_TEXTURE_2D, cacheTexture->mTextureId);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    // Initialize texture dimensions
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, width, height, 0,
            GL_ALPHA, GL_UNSIGNED_BYTE, 0);

    const GLenum filtering = cacheTexture->mLinearFiltering ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

CacheTexture* FontRenderer::cacheBitmapInTexture(const SkGlyph& glyph,
        uint32_t* startX, uint32_t* startY) {
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        if (mCacheTextures[i]->fitBitmap(glyph, startX, startY)) {
            return mCacheTextures[i];
        }
    }
    // Could not fit glyph into current cache textures
    return NULL;
}

void FontRenderer::cacheBitmap(const SkGlyph& glyph, CachedGlyphInfo* cachedGlyph,
        uint32_t* retOriginX, uint32_t* retOriginY, bool precaching) {
    checkInit();
    cachedGlyph->mIsValid = false;
    // If the glyph is too tall, don't cache it
    if (glyph.fHeight + TEXTURE_BORDER_SIZE * 2 >
                mCacheTextures[mCacheTextures.size() - 1]->mHeight) {
        ALOGE("Font size too large to fit in cache. width, height = %i, %i",
                (int) glyph.fWidth, (int) glyph.fHeight);
        return;
    }

    // Now copy the bitmap into the cache texture
    uint32_t startX = 0;
    uint32_t startY = 0;

    CacheTexture* cacheTexture = cacheBitmapInTexture(glyph, &startX, &startY);

    if (!cacheTexture) {
        if (!precaching) {
            // If the new glyph didn't fit and we are not just trying to precache it,
            // clear out the cache and try again
            flushAllAndInvalidate();
            cacheTexture = cacheBitmapInTexture(glyph, &startX, &startY);
        }

        if (!cacheTexture) {
            // either the glyph didn't fit or we're precaching and will cache it when we draw
            return;
        }
    }

    cachedGlyph->mCacheTexture = cacheTexture;

    *retOriginX = startX;
    *retOriginY = startY;

    uint32_t endX = startX + glyph.fWidth;
    uint32_t endY = startY + glyph.fHeight;

    uint32_t cacheWidth = cacheTexture->mWidth;

    if (!cacheTexture->mTexture) {
        // Large-glyph texture memory is allocated only as needed
        allocateTextureMemory(cacheTexture);
    }

    uint8_t* cacheBuffer = cacheTexture->mTexture;
    uint8_t* bitmapBuffer = (uint8_t*) glyph.fImage;
    unsigned int stride = glyph.rowBytes();

    uint32_t cacheX = 0, bX = 0, cacheY = 0, bY = 0;

    for (cacheX = startX - TEXTURE_BORDER_SIZE; cacheX < endX + TEXTURE_BORDER_SIZE; cacheX++) {
        cacheBuffer[(startY - TEXTURE_BORDER_SIZE) * cacheWidth + cacheX] = 0;
        cacheBuffer[(endY + TEXTURE_BORDER_SIZE - 1) * cacheWidth + cacheX] = 0;
    }

    for (cacheY = startY - TEXTURE_BORDER_SIZE + 1;
            cacheY < endY + TEXTURE_BORDER_SIZE - 1; cacheY++) {
        cacheBuffer[cacheY * cacheWidth + startX - TEXTURE_BORDER_SIZE] = 0;
        cacheBuffer[cacheY * cacheWidth + endX + TEXTURE_BORDER_SIZE - 1] = 0;
    }

    if (mGammaTable) {
        for (cacheX = startX, bX = 0; cacheX < endX; cacheX++, bX++) {
            for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY++) {
                uint8_t tempCol = bitmapBuffer[bY * stride + bX];
                cacheBuffer[cacheY * cacheWidth + cacheX] = mGammaTable[tempCol];
            }
        }
    } else {
        for (cacheX = startX, bX = 0; cacheX < endX; cacheX++, bX++) {
            for (cacheY = startY, bY = 0; cacheY < endY; cacheY++, bY++) {
                uint8_t tempCol = bitmapBuffer[bY * stride + bX];
                cacheBuffer[cacheY * cacheWidth + cacheX] = tempCol;
            }
        }
    }

    cachedGlyph->mIsValid = true;
}

CacheTexture* FontRenderer::createCacheTexture(int width, int height, bool allocate) {
    CacheTexture* cacheTexture = new CacheTexture(width, height);

    if (allocate) {
        allocateTextureMemory(cacheTexture);
    }

    return cacheTexture;
}

void FontRenderer::initTextTexture() {
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        delete mCacheTextures[i];
    }
    mCacheTextures.clear();

    // Next, use other, separate caches for large glyphs.
    uint16_t maxWidth = 0;
    if (Caches::hasInstance()) {
        maxWidth = Caches::getInstance().maxTextureSize;
    }

    if (maxWidth > MAX_TEXT_CACHE_WIDTH || maxWidth == 0) {
        maxWidth = MAX_TEXT_CACHE_WIDTH;
    }

    mUploadTexture = false;
    mCacheTextures.push(createCacheTexture(mSmallCacheWidth, mSmallCacheHeight, true));
    mCacheTextures.push(createCacheTexture(maxWidth, 256, false));
    mCacheTextures.push(createCacheTexture(maxWidth, 256, false));
    mCacheTextures.push(createCacheTexture(maxWidth, 512, false));
    mCurrentCacheTexture = mCacheTextures[0];
}

// Avoid having to reallocate memory and render quad by quad
void FontRenderer::initVertexArrayBuffers() {
    uint32_t numIndices = mMaxNumberOfQuads * 6;
    uint32_t indexBufferSizeBytes = numIndices * sizeof(uint16_t);
    uint16_t* indexBufferData = (uint16_t*) malloc(indexBufferSizeBytes);

    // Four verts, two triangles , six indices per quad
    for (uint32_t i = 0; i < mMaxNumberOfQuads; i++) {
        int i6 = i * 6;
        int i4 = i * 4;

        indexBufferData[i6 + 0] = i4 + 0;
        indexBufferData[i6 + 1] = i4 + 1;
        indexBufferData[i6 + 2] = i4 + 2;

        indexBufferData[i6 + 3] = i4 + 0;
        indexBufferData[i6 + 4] = i4 + 2;
        indexBufferData[i6 + 5] = i4 + 3;
    }

    glGenBuffers(1, &mIndexBufferID);
    Caches::getInstance().bindIndicesBuffer(mIndexBufferID);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBufferSizeBytes, indexBufferData, GL_STATIC_DRAW);

    free(indexBufferData);

    uint32_t coordSize = 2;
    uint32_t uvSize = 2;
    uint32_t vertsPerQuad = 4;
    uint32_t vertexBufferSize = mMaxNumberOfQuads * vertsPerQuad * coordSize * uvSize;
    mTextMeshPtr = new float[vertexBufferSize];
}

// We don't want to allocate anything unless we actually draw text
void FontRenderer::checkInit() {
    if (mInitialized) {
        return;
    }

    initTextTexture();
    initVertexArrayBuffers();

    mInitialized = true;
}

void FontRenderer::checkTextureUpdate() {
    if (!mUploadTexture && mLastCacheTexture == mCurrentCacheTexture) {
        return;
    }

    Caches& caches = Caches::getInstance();
    GLuint lastTextureId = 0;
    // Iterate over all the cache textures and see which ones need to be updated
    for (uint32_t i = 0; i < mCacheTextures.size(); i++) {
        CacheTexture* cacheTexture = mCacheTextures[i];
        if (cacheTexture->mDirty && cacheTexture->mTexture != NULL) {
            uint32_t xOffset = 0;
            uint32_t width   = cacheTexture->mWidth;
            uint32_t height  = cacheTexture->mHeight;
            void* textureData = cacheTexture->mTexture;

            if (cacheTexture->mTextureId != lastTextureId) {
                caches.activeTexture(0);
                glBindTexture(GL_TEXTURE_2D, cacheTexture->mTextureId);
                lastTextureId = cacheTexture->mTextureId;
            }
#if DEBUG_FONT_RENDERER
            ALOGD("glTextSubimage for cacheTexture %d: xOff, width height = %d, %d, %d",
                    i, xOffset, width, height);
#endif
            glTexSubImage2D(GL_TEXTURE_2D, 0, xOffset, 0, width, height,
                    GL_ALPHA, GL_UNSIGNED_BYTE, textureData);

            cacheTexture->mDirty = false;
        }
    }

    caches.activeTexture(0);
    glBindTexture(GL_TEXTURE_2D, mCurrentCacheTexture->mTextureId);
    if (mLinearFiltering != mCurrentCacheTexture->mLinearFiltering) {
        const GLenum filtering = mLinearFiltering ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);
        mCurrentCacheTexture->mLinearFiltering = mLinearFiltering;
    }
    mLastCacheTexture = mCurrentCacheTexture;

    mUploadTexture = false;
}

void FontRenderer::issueDrawCommand() {
    checkTextureUpdate();

    Caches& caches = Caches::getInstance();
    caches.bindIndicesBuffer(mIndexBufferID);
    if (!mDrawn) {
        float* buffer = mTextMeshPtr;
        int offset = 2;

        bool force = caches.unbindMeshBuffer();
        caches.bindPositionVertexPointer(force, caches.currentProgram->position, buffer);
        caches.bindTexCoordsVertexPointer(force, caches.currentProgram->texCoords,
                buffer + offset);
    }

    glDrawElements(GL_TRIANGLES, mCurrentQuadIndex * 6, GL_UNSIGNED_SHORT, NULL);

    mDrawn = true;
}

void FontRenderer::appendMeshQuadNoClip(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {
    if (texture != mCurrentCacheTexture) {
        if (mCurrentQuadIndex != 0) {
            // First, draw everything stored already which uses the previous texture
            issueDrawCommand();
            mCurrentQuadIndex = 0;
        }
        // Now use the new texture id
        mCurrentCacheTexture = texture;
    }

    const uint32_t vertsPerQuad = 4;
    const uint32_t floatsPerVert = 4;
    float* currentPos = mTextMeshPtr + mCurrentQuadIndex * vertsPerQuad * floatsPerVert;

    (*currentPos++) = x1;
    (*currentPos++) = y1;
    (*currentPos++) = u1;
    (*currentPos++) = v1;

    (*currentPos++) = x2;
    (*currentPos++) = y2;
    (*currentPos++) = u2;
    (*currentPos++) = v2;

    (*currentPos++) = x3;
    (*currentPos++) = y3;
    (*currentPos++) = u3;
    (*currentPos++) = v3;

    (*currentPos++) = x4;
    (*currentPos++) = y4;
    (*currentPos++) = u4;
    (*currentPos++) = v4;

    mCurrentQuadIndex++;
}

void FontRenderer::appendMeshQuad(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {

    if (mClip &&
            (x1 > mClip->right || y1 < mClip->top || x2 < mClip->left || y4 > mClip->bottom)) {
        return;
    }

    appendMeshQuadNoClip(x1, y1, u1, v1, x2, y2, u2, v2, x3, y3, u3, v3, x4, y4, u4, v4, texture);

    if (mBounds) {
        mBounds->left = fmin(mBounds->left, x1);
        mBounds->top = fmin(mBounds->top, y3);
        mBounds->right = fmax(mBounds->right, x3);
        mBounds->bottom = fmax(mBounds->bottom, y1);
    }

    if (mCurrentQuadIndex == mMaxNumberOfQuads) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontRenderer::appendRotatedMeshQuad(float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4, CacheTexture* texture) {

    appendMeshQuadNoClip(x1, y1, u1, v1, x2, y2, u2, v2, x3, y3, u3, v3, x4, y4, u4, v4, texture);

    if (mBounds) {
        mBounds->left = fmin(mBounds->left, fmin(x1, fmin(x2, fmin(x3, x4))));
        mBounds->top = fmin(mBounds->top, fmin(y1, fmin(y2, fmin(y3, y4))));
        mBounds->right = fmax(mBounds->right, fmax(x1, fmax(x2, fmax(x3, x4))));
        mBounds->bottom = fmax(mBounds->bottom, fmax(y1, fmax(y2, fmax(y3, y4))));
    }

    if (mCurrentQuadIndex == mMaxNumberOfQuads) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontRenderer::setFont(SkPaint* paint, uint32_t fontId, float fontSize) {
    int flags = 0;
    if (paint->isFakeBoldText()) {
        flags |= Font::kFakeBold;
    }

    const float skewX = paint->getTextSkewX();
    uint32_t italicStyle = *(uint32_t*) &skewX;
    const float scaleXFloat = paint->getTextScaleX();
    uint32_t scaleX = *(uint32_t*) &scaleXFloat;
    SkPaint::Style style = paint->getStyle();
    const float strokeWidthFloat = paint->getStrokeWidth();
    uint32_t strokeWidth = *(uint32_t*) &strokeWidthFloat;
    mCurrentFont = Font::create(this, fontId, fontSize, flags, italicStyle,
            scaleX, style, strokeWidth);

}

FontRenderer::DropShadow FontRenderer::renderDropShadow(SkPaint* paint, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, uint32_t radius, const float* positions) {
    checkInit();

    if (!mCurrentFont) {
        DropShadow image;
        image.width = 0;
        image.height = 0;
        image.image = NULL;
        image.penX = 0;
        image.penY = 0;
        return image;
    }

    mDrawn = false;
    mClip = NULL;
    mBounds = NULL;

    Rect bounds;
    mCurrentFont->measure(paint, text, startIndex, len, numGlyphs, &bounds, positions);

    uint32_t paddedWidth = (uint32_t) (bounds.right - bounds.left) + 2 * radius;
    uint32_t paddedHeight = (uint32_t) (bounds.top - bounds.bottom) + 2 * radius;
    uint8_t* dataBuffer = new uint8_t[paddedWidth * paddedHeight];

    for (uint32_t i = 0; i < paddedWidth * paddedHeight; i++) {
        dataBuffer[i] = 0;
    }

    int penX = radius - bounds.left;
    int penY = radius - bounds.bottom;

    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, penX, penY,
            Font::BITMAP, dataBuffer, paddedWidth, paddedHeight, NULL, positions);
    blurImage(dataBuffer, paddedWidth, paddedHeight, radius);

    DropShadow image;
    image.width = paddedWidth;
    image.height = paddedHeight;
    image.image = dataBuffer;
    image.penX = penX;
    image.penY = penY;

    return image;
}

void FontRenderer::initRender(const Rect* clip, Rect* bounds) {
    checkInit();

    mDrawn = false;
    mBounds = bounds;
    mClip = clip;
}

void FontRenderer::finishRender() {
    mBounds = NULL;
    mClip = NULL;

    if (mCurrentQuadIndex != 0) {
        issueDrawCommand();
        mCurrentQuadIndex = 0;
    }
}

void FontRenderer::precache(SkPaint* paint, const char* text, int numGlyphs) {
    int flags = 0;
    if (paint->isFakeBoldText()) {
        flags |= Font::kFakeBold;
    }
    const float skewX = paint->getTextSkewX();
    uint32_t italicStyle = *(uint32_t*) &skewX;
    const float scaleXFloat = paint->getTextScaleX();
    uint32_t scaleX = *(uint32_t*) &scaleXFloat;
    SkPaint::Style style = paint->getStyle();
    const float strokeWidthFloat = paint->getStrokeWidth();
    uint32_t strokeWidth = *(uint32_t*) &strokeWidthFloat;
    float fontSize = paint->getTextSize();
    Font* font = Font::create(this, SkTypeface::UniqueID(paint->getTypeface()),
            fontSize, flags, italicStyle, scaleX, style, strokeWidth);

    font->precache(paint, text, numGlyphs);
}

bool FontRenderer::renderText(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, int x, int y, Rect* bounds) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds);
    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, x, y);
    finishRender();

    return mDrawn;
}

bool FontRenderer::renderPosText(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, int x, int y,
        const float* positions, Rect* bounds) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds);
    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, x, y, positions);
    finishRender();

    return mDrawn;
}

bool FontRenderer::renderTextOnPath(SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, SkPath* path,
        float hOffset, float vOffset, Rect* bounds) {
    if (!mCurrentFont) {
        ALOGE("No font set");
        return false;
    }

    initRender(clip, bounds);
    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, path, hOffset, vOffset);
    finishRender();

    return mDrawn;
}

void FontRenderer::computeGaussianWeights(float* weights, int32_t radius) {
    // Compute gaussian weights for the blur
    // e is the euler's number
    float e = 2.718281828459045f;
    float pi = 3.1415926535897932f;
    // g(x) = ( 1 / sqrt( 2 * pi ) * sigma) * e ^ ( -x^2 / 2 * sigma^2 )
    // x is of the form [-radius .. 0 .. radius]
    // and sigma varies with radius.
    // Based on some experimental radius values and sigma's
    // we approximately fit sigma = f(radius) as
    // sigma = radius * 0.3  + 0.6
    // The larger the radius gets, the more our gaussian blur
    // will resemble a box blur since with large sigma
    // the gaussian curve begins to lose its shape
    float sigma = 0.3f * (float) radius + 0.6f;

    // Now compute the coefficints
    // We will store some redundant values to save some math during
    // the blur calculations
    // precompute some values
    float coeff1 = 1.0f / (sqrt( 2.0f * pi ) * sigma);
    float coeff2 = - 1.0f / (2.0f * sigma * sigma);

    float normalizeFactor = 0.0f;
    for (int32_t r = -radius; r <= radius; r ++) {
        float floatR = (float) r;
        weights[r + radius] = coeff1 * pow(e, floatR * floatR * coeff2);
        normalizeFactor += weights[r + radius];
    }

    //Now we need to normalize the weights because all our coefficients need to add up to one
    normalizeFactor = 1.0f / normalizeFactor;
    for (int32_t r = -radius; r <= radius; r ++) {
        weights[r + radius] *= normalizeFactor;
    }
}

void FontRenderer::horizontalBlur(float* weights, int32_t radius,
        const uint8_t* source, uint8_t* dest, int32_t width, int32_t height) {
    float blurredPixel = 0.0f;
    float currentPixel = 0.0f;

    for (int32_t y = 0; y < height; y ++) {

        const uint8_t* input = source + y * width;
        uint8_t* output = dest + y * width;

        for (int32_t x = 0; x < width; x ++) {
            blurredPixel = 0.0f;
            const float* gPtr = weights;
            // Optimization for non-border pixels
            if (x > radius && x < (width - radius)) {
                const uint8_t *i = input + (x - radius);
                for (int r = -radius; r <= radius; r ++) {
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                    i++;
                }
            } else {
                for (int32_t r = -radius; r <= radius; r ++) {
                    // Stepping left and right away from the pixel
                    int validW = x + r;
                    if (validW < 0) {
                        validW = 0;
                    }
                    if (validW > width - 1) {
                        validW = width - 1;
                    }

                    currentPixel = (float) input[validW];
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                }
            }
            *output = (uint8_t)blurredPixel;
            output ++;
        }
    }
}

void FontRenderer::verticalBlur(float* weights, int32_t radius,
        const uint8_t* source, uint8_t* dest, int32_t width, int32_t height) {
    float blurredPixel = 0.0f;
    float currentPixel = 0.0f;

    for (int32_t y = 0; y < height; y ++) {

        uint8_t* output = dest + y * width;

        for (int32_t x = 0; x < width; x ++) {
            blurredPixel = 0.0f;
            const float* gPtr = weights;
            const uint8_t* input = source + x;
            // Optimization for non-border pixels
            if (y > radius && y < (height - radius)) {
                const uint8_t *i = input + ((y - radius) * width);
                for (int32_t r = -radius; r <= radius; r ++) {
                    currentPixel = (float)(*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                    i += width;
                }
            } else {
                for (int32_t r = -radius; r <= radius; r ++) {
                    int validH = y + r;
                    // Clamp to zero and width
                    if (validH < 0) {
                        validH = 0;
                    }
                    if (validH > height - 1) {
                        validH = height - 1;
                    }

                    const uint8_t *i = input + validH * width;
                    currentPixel = (float) (*i);
                    blurredPixel += currentPixel * gPtr[0];
                    gPtr++;
                }
            }
            *output = (uint8_t) blurredPixel;
            output ++;
        }
    }
}


void FontRenderer::blurImage(uint8_t *image, int32_t width, int32_t height, int32_t radius) {
    float *gaussian = new float[2 * radius + 1];
    computeGaussianWeights(gaussian, radius);

    uint8_t* scratch = new uint8_t[width * height];

    horizontalBlur(gaussian, radius, image, scratch, width, height);
    verticalBlur(gaussian, radius, scratch, image, width, height);

    delete[] gaussian;
    delete[] scratch;
}

}; // namespace uirenderer
}; // namespace android
