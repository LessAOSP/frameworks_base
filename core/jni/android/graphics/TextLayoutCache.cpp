/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "TextLayoutCache"

#include "TextLayoutCache.h"
#include "TextLayout.h"
#include "SkFontHost.h"

extern "C" {
  #include "harfbuzz-unicode.h"
}

namespace android {

//--------------------------------------------------------------------------------------------------
#define TYPEFACE_ARABIC "/system/fonts/DroidNaskh-Regular.ttf"
#define TYPE_FACE_HEBREW_REGULAR "/system/fonts/DroidSansHebrew-Regular.ttf"
#define TYPE_FACE_HEBREW_BOLD "/system/fonts/DroidSansHebrew-Bold.ttf"

#if USE_TEXT_LAYOUT_CACHE

    ANDROID_SINGLETON_STATIC_INSTANCE(TextLayoutCache);
    ANDROID_SINGLETON_STATIC_INSTANCE(TextLayoutEngine);

#endif

//--------------------------------------------------------------------------------------------------

TextLayoutCache::TextLayoutCache() :
        mCache(GenerationCache<TextLayoutCacheKey, sp<TextLayoutCacheValue> >::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB)),
        mCacheHitCount(0), mNanosecondsSaved(0) {
    init();
}

TextLayoutCache::~TextLayoutCache() {
    mCache.clear();
}

void TextLayoutCache::init() {
    mCache.setOnEntryRemovedListener(this);

    mDebugLevel = readRtlDebugLevel();
    mDebugEnabled = mDebugLevel & kRtlDebugCaches;
    LOGD("Using debug level: %d - Debug Enabled: %d", mDebugLevel, mDebugEnabled);

    mCacheStartTime = systemTime(SYSTEM_TIME_MONOTONIC);

    if (mDebugEnabled) {
        LOGD("Initialization is done - Start time: %lld", mCacheStartTime);
    }

    mInitialized = true;
}

/**
 *  Callbacks
 */
void TextLayoutCache::operator()(TextLayoutCacheKey& text, sp<TextLayoutCacheValue>& desc) {
    size_t totalSizeToDelete = text.getSize() + desc->getSize();
    mSize -= totalSizeToDelete;
    if (mDebugEnabled) {
        LOGD("Cache value %p deleted, size = %d", desc.get(), totalSizeToDelete);
    }
}

/*
 * Cache clearing
 */
void TextLayoutCache::clear() {
    mCache.clear();
}

/*
 * Caching
 */
sp<TextLayoutCacheValue> TextLayoutCache::getValue(SkPaint* paint,
            const jchar* text, jint start, jint count, jint contextCount, jint dirFlags) {
    AutoMutex _l(mLock);
    nsecs_t startTime = 0;
    if (mDebugEnabled) {
        startTime = systemTime(SYSTEM_TIME_MONOTONIC);
    }

    // Create the key
    TextLayoutCacheKey key(paint, text, start, count, contextCount, dirFlags);

    // Get value from cache if possible
    sp<TextLayoutCacheValue> value = mCache.get(key);

    // Value not found for the key, we need to add a new value in the cache
    if (value == NULL) {
        if (mDebugEnabled) {
            startTime = systemTime(SYSTEM_TIME_MONOTONIC);
        }

        value = new TextLayoutCacheValue(contextCount);

        // Compute advances and store them
        TextLayoutEngine::getInstance().computeValues(value.get(), paint,
                reinterpret_cast<const UChar*>(text), start, count,
                size_t(contextCount), int(dirFlags));

        if (mDebugEnabled) {
            value->setElapsedTime(systemTime(SYSTEM_TIME_MONOTONIC) - startTime);
        }

        // Don't bother to add in the cache if the entry is too big
        size_t size = key.getSize() + value->getSize();
        if (size <= mMaxSize) {
            // Cleanup to make some room if needed
            if (mSize + size > mMaxSize) {
                if (mDebugEnabled) {
                    LOGD("Need to clean some entries for making some room for a new entry");
                }
                while (mSize + size > mMaxSize) {
                    // This will call the callback
                    bool removedOne = mCache.removeOldest();
                    LOG_ALWAYS_FATAL_IF(!removedOne, "The cache is non-empty but we "
                            "failed to remove the oldest entry.  "
                            "mSize=%u, size=%u, mMaxSize=%u, mCache.size()=%u",
                            mSize, size, mMaxSize, mCache.size());
                }
            }

            // Update current cache size
            mSize += size;

            // Copy the text when we insert the new entry
            key.internalTextCopy();

            bool putOne = mCache.put(key, value);
            LOG_ALWAYS_FATAL_IF(!putOne, "Failed to put an entry into the cache.  "
                    "This indicates that the cache already has an entry with the "
                    "same key but it should not since we checked earlier!"
                    " - start=%d count=%d contextCount=%d - Text='%s'",
                    start, count, contextCount, String8(text + start, count).string());

            if (mDebugEnabled) {
                nsecs_t totalTime = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
                LOGD("CACHE MISS: Added entry %p "
                        "with start=%d count=%d contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time %0.6f ms - Put time %0.6f ms - Text='%s'",
                        value.get(), start, count, contextCount, size, mMaxSize - mSize,
                        value->getElapsedTime() * 0.000001f,
                        (totalTime - value->getElapsedTime()) * 0.000001f,
                        String8(text + start, count).string());
            }
        } else {
            if (mDebugEnabled) {
                LOGD("CACHE MISS: Calculated but not storing entry because it is too big "
                        "with start=%d count=%d contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time %0.6f ms - Text='%s'",
                        start, count, contextCount, size, mMaxSize - mSize,
                        value->getElapsedTime() * 0.000001f,
                        String8(text + start, count).string());
            }
            value.clear();
        }
    } else {
        // This is a cache hit, just log timestamp and user infos
        if (mDebugEnabled) {
            nsecs_t elapsedTimeThruCacheGet = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
            mNanosecondsSaved += (value->getElapsedTime() - elapsedTimeThruCacheGet);
            ++mCacheHitCount;

            if (value->getElapsedTime() > 0) {
                float deltaPercent = 100 * ((value->getElapsedTime() - elapsedTimeThruCacheGet)
                        / ((float)value->getElapsedTime()));
                LOGD("CACHE HIT #%d with start=%d count=%d contextCount=%d"
                        "- Compute time %0.6f ms - "
                        "Cache get time %0.6f ms - Gain in percent: %2.2f - Text='%s' ",
                        mCacheHitCount, start, count, contextCount,
                        value->getElapsedTime() * 0.000001f,
                        elapsedTimeThruCacheGet * 0.000001f,
                        deltaPercent,
                        String8(text + start, count).string());
            }
            if (mCacheHitCount % DEFAULT_DUMP_STATS_CACHE_HIT_INTERVAL == 0) {
                dumpCacheStats();
            }
        }
    }
    return value;
}

void TextLayoutCache::dumpCacheStats() {
    float remainingPercent = 100 * ((mMaxSize - mSize) / ((float)mMaxSize));
    float timeRunningInSec = (systemTime(SYSTEM_TIME_MONOTONIC) - mCacheStartTime) / 1000000000;

    size_t bytes = 0;
    size_t cacheSize = mCache.size();
    for (size_t i = 0; i < cacheSize; i++) {
        bytes += mCache.getKeyAt(i).getSize() + mCache.getValueAt(i)->getSize();
    }

    LOGD("------------------------------------------------");
    LOGD("Cache stats");
    LOGD("------------------------------------------------");
    LOGD("pid       : %d", getpid());
    LOGD("running   : %.0f seconds", timeRunningInSec);
    LOGD("entries   : %d", cacheSize);
    LOGD("max size  : %d bytes", mMaxSize);
    LOGD("used      : %d bytes according to mSize, %d bytes actual", mSize, bytes);
    LOGD("remaining : %d bytes or %2.2f percent", mMaxSize - mSize, remainingPercent);
    LOGD("hits      : %d", mCacheHitCount);
    LOGD("saved     : %0.6f ms", mNanosecondsSaved * 0.000001f);
    LOGD("------------------------------------------------");
}

/**
 * TextLayoutCacheKey
 */
TextLayoutCacheKey::TextLayoutCacheKey(): text(NULL), start(0), count(0), contextCount(0),
        dirFlags(0), typeface(NULL), textSize(0), textSkewX(0), textScaleX(0), flags(0),
        hinting(SkPaint::kNo_Hinting)  {
}

TextLayoutCacheKey::TextLayoutCacheKey(const SkPaint* paint, const UChar* text,
        size_t start, size_t count, size_t contextCount, int dirFlags) :
            text(text), start(start), count(count), contextCount(contextCount),
            dirFlags(dirFlags) {
    typeface = paint->getTypeface();
    textSize = paint->getTextSize();
    textSkewX = paint->getTextSkewX();
    textScaleX = paint->getTextScaleX();
    flags = paint->getFlags();
    hinting = paint->getHinting();
}

TextLayoutCacheKey::TextLayoutCacheKey(const TextLayoutCacheKey& other) :
        text(NULL),
        textCopy(other.textCopy),
        start(other.start),
        count(other.count),
        contextCount(other.contextCount),
        dirFlags(other.dirFlags),
        typeface(other.typeface),
        textSize(other.textSize),
        textSkewX(other.textSkewX),
        textScaleX(other.textScaleX),
        flags(other.flags),
        hinting(other.hinting) {
    if (other.text) {
        textCopy.setTo(other.text, other.contextCount);
    }
}

int TextLayoutCacheKey::compare(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs) {
    int deltaInt = lhs.start - rhs.start;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.count - rhs.count;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.contextCount - rhs.contextCount;
    if (deltaInt != 0) return (deltaInt);

    if (lhs.typeface < rhs.typeface) return -1;
    if (lhs.typeface > rhs.typeface) return +1;

    if (lhs.textSize < rhs.textSize) return -1;
    if (lhs.textSize > rhs.textSize) return +1;

    if (lhs.textSkewX < rhs.textSkewX) return -1;
    if (lhs.textSkewX > rhs.textSkewX) return +1;

    if (lhs.textScaleX < rhs.textScaleX) return -1;
    if (lhs.textScaleX > rhs.textScaleX) return +1;

    deltaInt = lhs.flags - rhs.flags;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.hinting - rhs.hinting;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.dirFlags - rhs.dirFlags;
    if (deltaInt) return (deltaInt);

    return memcmp(lhs.getText(), rhs.getText(), lhs.contextCount * sizeof(UChar));
}

void TextLayoutCacheKey::internalTextCopy() {
    textCopy.setTo(text, contextCount);
    text = NULL;
}

size_t TextLayoutCacheKey::getSize() const {
    return sizeof(TextLayoutCacheKey) + sizeof(UChar) * contextCount;
}

/**
 * TextLayoutCacheValue
 */
TextLayoutCacheValue::TextLayoutCacheValue(size_t contextCount) :
        mTotalAdvance(0), mElapsedTime(0) {
    // Give a hint for advances and glyphs vectors size
    mAdvances.setCapacity(contextCount);
    mGlyphs.setCapacity(contextCount);
}

size_t TextLayoutCacheValue::getSize() const {
    return sizeof(TextLayoutCacheValue) + sizeof(jfloat) * mAdvances.capacity() +
            sizeof(jchar) * mGlyphs.capacity();
}

void TextLayoutCacheValue::setElapsedTime(uint32_t time) {
    mElapsedTime = time;
}

uint32_t TextLayoutCacheValue::getElapsedTime() {
    return mElapsedTime;
}

//HB_ShaperItem TextLayoutEngine::mShaperItem;
//HB_FontRec TextLayoutEngine::mFontRec;
//SkPaint TextLayoutEngine::mShapingPaint;

TextLayoutEngine::TextLayoutEngine() : mShaperItemGlyphArraySize(0),
        mShaperItemLogClustersArraySize(0) {
    mDefaultTypeface = SkFontHost::CreateTypeface(NULL, NULL, NULL, 0, SkTypeface::kNormal);
    mArabicTypeface = NULL;
    mHebrewRegularTypeface = NULL;
    mHebrewBoldTypeface = NULL;

    mFontRec.klass = &harfbuzzSkiaClass;
    mFontRec.userData = 0;

    // The values which harfbuzzSkiaClass returns are already scaled to
    // pixel units, so we just set all these to one to disable further
    // scaling.
    mFontRec.x_ppem = 1;
    mFontRec.y_ppem = 1;
    mFontRec.x_scale = 1;
    mFontRec.y_scale = 1;

    memset(&mShaperItem, 0, sizeof(mShaperItem));

    mShaperItem.font = &mFontRec;
    mShaperItem.font->userData = &mShapingPaint;
}

TextLayoutEngine::~TextLayoutEngine() {
    // FIXME should free fonts and caches but since this class is a singleton,
    // we don't bother at the moment
}

void TextLayoutEngine::computeValues(TextLayoutCacheValue* value, SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags) {

    computeValuesWithHarfbuzz(paint, chars, start, count, contextCount, dirFlags,
            &value->mAdvances, &value->mTotalAdvance, &value->mGlyphs);
#if DEBUG_ADVANCES
    LOGD("Advances - start=%d, count=%d, contextCount=%d, totalAdvance=%f", start, count,
            contextCount, mTotalAdvance);
#endif
}

void TextLayoutEngine::computeValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
        Vector<jchar>* const outGlyphs) {

        UBiDiLevel bidiReq = 0;
        bool forceLTR = false;
        bool forceRTL = false;

        switch (dirFlags) {
            case kBidi_LTR: bidiReq = 0; break; // no ICU constant, canonical LTR level
            case kBidi_RTL: bidiReq = 1; break; // no ICU constant, canonical RTL level
            case kBidi_Default_LTR: bidiReq = UBIDI_DEFAULT_LTR; break;
            case kBidi_Default_RTL: bidiReq = UBIDI_DEFAULT_RTL; break;
            case kBidi_Force_LTR: forceLTR = true; break; // every char is LTR
            case kBidi_Force_RTL: forceRTL = true; break; // every char is RTL
        }

        bool useSingleRun = false;
        bool isRTL = forceRTL;
        if (forceLTR || forceRTL) {
            useSingleRun = true;
        } else {
            UBiDi* bidi = ubidi_open();
            if (bidi) {
                UErrorCode status = U_ZERO_ERROR;
#if DEBUG_GLYPHS
                LOGD("computeValuesWithHarfbuzz -- bidiReq=%d", bidiReq);
#endif
                ubidi_setPara(bidi, chars, contextCount, bidiReq, NULL, &status);
                if (U_SUCCESS(status)) {
                    int paraDir = ubidi_getParaLevel(bidi) & kDirection_Mask; // 0 if ltr, 1 if rtl
                    ssize_t rc = ubidi_countRuns(bidi, &status);
#if DEBUG_GLYPHS
                    LOGD("computeValuesWithHarfbuzz -- dirFlags=%d run-count=%d paraDir=%d",
                            dirFlags, rc, paraDir);
#endif
                    if (U_SUCCESS(status) && rc == 1) {
                        // Normal case: one run, status is ok
                        isRTL = (paraDir == 1);
                        useSingleRun = true;
                    } else if (!U_SUCCESS(status) || rc < 1) {
                        LOGW("computeValuesWithHarfbuzz -- need to force to single run");
                        isRTL = (paraDir == 1);
                        useSingleRun = true;
                    } else {
                        int32_t end = start + count;
                        for (size_t i = 0; i < size_t(rc); ++i) {
                            int32_t startRun = -1;
                            int32_t lengthRun = -1;
                            UBiDiDirection runDir = ubidi_getVisualRun(bidi, i, &startRun, &lengthRun);

                            if (startRun == -1 || lengthRun == -1) {
                                // Something went wrong when getting the visual run, need to clear
                                // already computed data before doing a single run pass
                                LOGW("computeValuesWithHarfbuzz -- visual run is not valid");
                                outGlyphs->clear();
                                outAdvances->clear();
                                *outTotalAdvance = 0;
                                isRTL = (paraDir == 1);
                                useSingleRun = true;
                                break;
                            }

                            if (startRun >= end) {
                                continue;
                            }
                            int32_t endRun = startRun + lengthRun;
                            if (endRun <= int32_t(start)) {
                                continue;
                            }
                            if (startRun < int32_t(start)) {
                                startRun = int32_t(start);
                            }
                            if (endRun > end) {
                                endRun = end;
                            }

                            lengthRun = endRun - startRun;
                            isRTL = (runDir == UBIDI_RTL);
                            jfloat runTotalAdvance = 0;
#if DEBUG_GLYPHS
                            LOGD("computeValuesWithHarfbuzz -- run-start=%d run-len=%d isRTL=%d",
                                    startRun, lengthRun, isRTL);
#endif
                            computeRunValuesWithHarfbuzz(paint, chars + startRun, lengthRun, isRTL,
                                    outAdvances, &runTotalAdvance, outGlyphs);

                            *outTotalAdvance += runTotalAdvance;
                        }
                    }
                } else {
                    LOGW("computeValuesWithHarfbuzz -- cannot set Para");
                    useSingleRun = true;
                    isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
                }
                ubidi_close(bidi);
            } else {
                LOGW("computeValuesWithHarfbuzz -- cannot ubidi_open()");
                useSingleRun = true;
                isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
            }
        }

        // Default single run case
        if (useSingleRun){
#if DEBUG_GLYPHS
            LOGD("computeValuesWithHarfbuzz -- Using a SINGLE Run "
                    "-- run-start=%d run-len=%d isRTL=%d", start, count, isRTL);
#endif
            computeRunValuesWithHarfbuzz(paint, chars + start, count, isRTL,
                    outAdvances, outTotalAdvance, outGlyphs);
        }

#if DEBUG_GLYPHS
        LOGD("computeValuesWithHarfbuzz -- total-glyphs-count=%d", outGlyphs->size());
#endif
}

static void logGlyphs(HB_ShaperItem shaperItem) {
    LOGD("Got glyphs - count=%d", shaperItem.num_glyphs);
    for (size_t i = 0; i < shaperItem.num_glyphs; i++) {
        LOGD("      glyph[%d]=%d - offset.x=%f offset.y=%f", i, shaperItem.glyphs[i],
                HBFixedToFloat(shaperItem.offsets[i].x),
                HBFixedToFloat(shaperItem.offsets[i].y));
    }
}

void TextLayoutEngine::computeRunValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t count, bool isRTL,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
        Vector<jchar>* const outGlyphs) {

    *outTotalAdvance = 0;
    jfloat totalAdvance = 0;

    // Set the string properties
    mShaperItem.string = chars;
    mShaperItem.stringLength = count;

    // Define shaping paint properties
    mShapingPaint.setTextSize(paint->getTextSize());
    mShapingPaint.setTextSkewX(paint->getTextSkewX());
    mShapingPaint.setTextScaleX(paint->getTextScaleX());
    mShapingPaint.setFlags(paint->getFlags());
    mShapingPaint.setHinting(paint->getHinting());

    // Split the BiDi run into Script runs. Harfbuzz will populate the pos, length and script
    // into the shaperItem
    ssize_t indexFontRun = isRTL ? count - 1 : 0;
    unsigned numCodePoints = 0;
    while ((isRTL) ?
            hb_utf16_script_run_prev(&numCodePoints, &mShaperItem.item, chars,
                    count, &indexFontRun):
            hb_utf16_script_run_next(&numCodePoints, &mShaperItem.item, chars,
                    count, &indexFontRun)) {

        ssize_t startFontRun = mShaperItem.item.pos;
        size_t countFontRun = mShaperItem.item.length;
        ssize_t endFontRun = startFontRun + countFontRun;

#if DEBUG_GLYPHS
        LOGD("Shaping Font Run with");
        LOGD("         -- isRTL=%d", isRTL);
        LOGD("         -- HB script=%d", mShaperItem.item.script);
        LOGD("         -- startFontRun=%d", startFontRun);
        LOGD("         -- endFontRun=%d", endFontRun);
        LOGD("         -- countFontRun=%d", countFontRun);
        LOGD("         -- run='%s'", String8(chars + startFontRun, countFontRun).string());
        LOGD("         -- string='%s'", String8(chars, count).string());
#endif

        // Initialize Harfbuzz Shaper and get the base glyph count for offsetting the glyphIDs
        // and shape the Font run
        size_t glyphBaseCount = shapeFontRun(paint, isRTL);

#if DEBUG_GLYPHS
        LOGD("HARFBUZZ -- num_glypth=%d - kerning_applied=%d", mShaperItem.num_glyphs,
                mShaperItem.kerning_applied);
        LOGD("         -- isDevKernText=%d", paint->isDevKernText());
        LOGD("         -- glyphBaseCount=%d", glyphBaseCount);

        logGlyphs(mShaperItem);
#endif
        if (isRTL) {
            endFontRun = startFontRun;
#if DEBUG_GLYPHS
            LOGD("         -- updated endFontRun=%d", endFontRun);
#endif
        } else {
            startFontRun = endFontRun;
#if DEBUG_GLYPHS
            LOGD("         -- updated startFontRun=%d", startFontRun);
#endif
        }

        if (mShaperItem.advances == NULL || mShaperItem.num_glyphs == 0) {
#if DEBUG_GLYPHS
            LOGD("HARFBUZZ -- advances array is empty or num_glypth = 0");
#endif
            outAdvances->insertAt(0, outAdvances->size(), countFontRun);
            continue;
        }

        // Get Advances and their total
        jfloat currentAdvance = HBFixedToFloat(mShaperItem.advances[mShaperItem.log_clusters[0]]);
        jfloat totalFontRunAdvance = currentAdvance;
        outAdvances->add(currentAdvance);
        for (size_t i = 1; i < countFontRun; i++) {
            size_t clusterPrevious = mShaperItem.log_clusters[i - 1];
            size_t cluster = mShaperItem.log_clusters[i];
            if (cluster == clusterPrevious) {
                outAdvances->add(0);
            } else {
                currentAdvance = HBFixedToFloat(mShaperItem.advances[mShaperItem.log_clusters[i]]);
                totalFontRunAdvance += currentAdvance;
                outAdvances->add(currentAdvance);
            }
        }
        totalAdvance += totalFontRunAdvance;

#if DEBUG_ADVANCES
        for (size_t i = 0; i < countFontRun; i++) {
            LOGD("hb-adv[%d] = %f - log_clusters = %d - total = %f", i,
                    (*outAdvances)[i], shaperItem.log_clusters[i], totalFontRunAdvance);
        }
#endif

        // Get Glyphs and reverse them in place if RTL
        if (outGlyphs) {
            size_t countGlyphs = mShaperItem.num_glyphs;
            for (size_t i = 0; i < countGlyphs; i++) {
                jchar glyph = glyphBaseCount +
                        (jchar) mShaperItem.glyphs[(!isRTL) ? i : countGlyphs - 1 - i];
#if DEBUG_GLYPHS
                LOGD("HARFBUZZ  -- glyph[%d]=%d", i, glyph);
#endif
                outGlyphs->add(glyph);
            }
        }
    }
    *outTotalAdvance = totalAdvance;
}


size_t TextLayoutEngine::shapeFontRun(SkPaint* paint, bool isRTL) {
    // Reset kerning
    mShaperItem.kerning_applied = false;

    // Update Harfbuzz Shaper
    mShaperItem.item.bidiLevel = isRTL;

    SkTypeface* typeface = paint->getTypeface();

    // Set the correct Typeface depending on the script
    switch (mShaperItem.item.script) {
    case HB_Script_Arabic:
        typeface = getCachedTypeface(&mArabicTypeface, TYPEFACE_ARABIC);
#if DEBUG_GLYPHS
        LOGD("Using Arabic Typeface");
#endif
        break;

    case HB_Script_Hebrew:
        if (typeface) {
            switch (typeface->style()) {
            case SkTypeface::kBold:
            case SkTypeface::kBoldItalic:
                typeface = getCachedTypeface(&mHebrewBoldTypeface, TYPE_FACE_HEBREW_BOLD);
#if DEBUG_GLYPHS
                LOGD("Using Hebrew Bold/BoldItalic Typeface");
#endif
                break;

            case SkTypeface::kNormal:
            case SkTypeface::kItalic:
            default:
                typeface = getCachedTypeface(&mHebrewRegularTypeface, TYPE_FACE_HEBREW_REGULAR);
#if DEBUG_GLYPHS
                LOGD("Using Hebrew Regular/Italic Typeface");
#endif
                break;
            }
        } else {
            typeface = getCachedTypeface(&mHebrewRegularTypeface, TYPE_FACE_HEBREW_REGULAR);
#if DEBUG_GLYPHS
            LOGD("Using Hebrew Regular Typeface");
#endif
        }
        break;

    default:
        if (!typeface) {
            typeface = mDefaultTypeface;
#if DEBUG_GLYPHS
            LOGD("Using Default Typeface");
#endif
        } else {
#if DEBUG_GLYPHS
            LOGD("Using Paint Typeface");
#endif
        }
        break;
    }

    mShapingPaint.setTypeface(typeface);
    mShaperItem.face = getCachedHBFace(typeface);

#if DEBUG_GLYPHS
    LOGD("Run typeFace = %p, uniqueID = %d, hb_face = %p",
            typeface, typeface->uniqueID(), mShaperItem.face);
#endif

    // Get the glyphs base count for offsetting the glyphIDs returned by Harfbuzz
    // This is needed as the Typeface used for shaping can be not the default one
    // when we are shaping any script that needs to use a fallback Font.
    // If we are a "common" script we dont need to shift
    size_t baseGlyphCount = 0;
    switch (mShaperItem.item.script) {
    case HB_Script_Arabic:
    case HB_Script_Hebrew: {
        const uint16_t* text16 = (const uint16_t*)mShaperItem.string;
        SkUnichar firstUnichar = SkUTF16_NextUnichar(&text16);
        baseGlyphCount = paint->getBaseGlyphCount(firstUnichar);
        break;
    }
    default:
        break;
    }

    // Shape
    ensureShaperItemLogClustersArray(mShaperItem.item.length);
    ensureShaperItemGlyphArrays(mShaperItem.item.length * 3 / 2);
    mShaperItem.num_glyphs = mShaperItemGlyphArraySize;
    while (!HB_ShapeItem(&mShaperItem)) {
        // We overflowed our glyph arrays. Resize and retry.
        // HB_ShapeItem fills in shaperItem.num_glyphs with the needed size.
        ensureShaperItemGlyphArrays(mShaperItem.num_glyphs * 2);
        mShaperItem.num_glyphs = mShaperItemGlyphArraySize;
    }
    return baseGlyphCount;
}

void TextLayoutEngine::ensureShaperItemGlyphArrays(size_t size) {
    if (size > mShaperItemGlyphArraySize) {
        deleteShaperItemGlyphArrays();
        createShaperItemGlyphArrays(size);
    }
}

void TextLayoutEngine::createShaperItemGlyphArrays(size_t size) {
#if DEBUG_GLYPHS
    LOGD("createGlyphArrays  -- size=%d", size);
#endif
    mShaperItemGlyphArraySize = size;
    mShaperItem.glyphs = new HB_Glyph[size];
    mShaperItem.attributes = new HB_GlyphAttributes[size];
    mShaperItem.advances = new HB_Fixed[size];
    mShaperItem.offsets = new HB_FixedPoint[size];
}

void TextLayoutEngine::deleteShaperItemGlyphArrays() {
    delete[] mShaperItem.glyphs;
    delete[] mShaperItem.attributes;
    delete[] mShaperItem.advances;
    delete[] mShaperItem.offsets;
}

void TextLayoutEngine::ensureShaperItemLogClustersArray(size_t size) {
    if (size > mShaperItemLogClustersArraySize) {
        deleteShaperItemLogClustersArray();
        createShaperItemLogClustersArray(size);
    }
}

void TextLayoutEngine::createShaperItemLogClustersArray(size_t size) {
#if DEBUG_GLYPHS
    LOGD("createLogClustersArray  -- size=%d", size);
#endif
    mShaperItemLogClustersArraySize = size;
    mShaperItem.log_clusters = new unsigned short[size];
}

void TextLayoutEngine::deleteShaperItemLogClustersArray() {
    delete[] mShaperItem.log_clusters;
}

SkTypeface* TextLayoutEngine::getCachedTypeface(SkTypeface** typeface, const char path[]) {
    if (!*typeface) {
        *typeface = SkTypeface::CreateFromFile(path);
        (*typeface)->ref();
#if DEBUG_GLYPHS
        LOGD("Created SkTypeface from file: %s", path);
#endif
    }
    return *typeface;
}

HB_Face TextLayoutEngine::getCachedHBFace(SkTypeface* typeface) {
    SkFontID fontId = typeface->uniqueID();
    ssize_t index = mCachedHBFaces.indexOfKey(fontId);
    if (index >= 0) {
        return mCachedHBFaces.valueAt(index);
    }
    HB_Face face = HB_NewFace(typeface, harfbuzzSkiaGetTable);
    if (face) {
#if DEBUG_GLYPHS
        LOGD("Created HB_NewFace %p from paint typeface: %p", face, typeface);
#endif
        mCachedHBFaces.add(fontId, face);
    }
    return face;
}

} // namespace android
