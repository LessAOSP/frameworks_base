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

#define LOG_TAG "StaticLayout"

#include "ScopedIcuLocale.h"
#include "unicode/locid.h"
#include "unicode/brkiter.h"
#include "utils/misc.h"
#include "utils/Log.h"
#include "ScopedPrimitiveArray.h"
#include "JNIHelp.h"
#include "core_jni_helpers.h"
#include <cstdint>
#include <vector>
#include <list>
#include <algorithm>

#include "SkPaint.h"
#include "SkTypeface.h"
#include "MinikinSkia.h"
#include "MinikinUtils.h"
#include "Paint.h"

namespace android {

struct JLineBreaksID {
    jfieldID breaks;
    jfieldID widths;
    jfieldID flags;
};

static jclass gLineBreaks_class;
static JLineBreaksID gLineBreaks_fieldID;

class Builder {
    public:
        ~Builder() {
            utext_close(&mUText);
            delete mBreakIterator;
        }

        void setLocale(const icu::Locale& locale) {
            delete mBreakIterator;
            UErrorCode status = U_ZERO_ERROR;
            mBreakIterator = icu::BreakIterator::createLineInstance(locale, status);
            // TODO: check status
        }

        void resize(size_t size) {
            mTextBuf.resize(size);
            mWidthBuf.resize(size);
        }

        size_t size() const {
            return mTextBuf.size();
        }

        uint16_t* buffer() {
            return mTextBuf.data();
        }

        float* widths() {
            return mWidthBuf.data();
        }

        // set text to current contents of buffer
        void setText() {
            UErrorCode status = U_ZERO_ERROR;
            utext_openUChars(&mUText, mTextBuf.data(), mTextBuf.size(), &status);
            mBreakIterator->setText(&mUText, status);
        }

        void finish() {
            if (mTextBuf.size() > MAX_TEXT_BUF_RETAIN) {
                mTextBuf.clear();
                mTextBuf.shrink_to_fit();
                mWidthBuf.clear();
                mWidthBuf.shrink_to_fit();
            }
        }

        icu::BreakIterator* breakIterator() const {
            return mBreakIterator;
        }

        float measureStyleRun(Paint* paint, TypefaceImpl* typeface, size_t start, size_t end,
                bool isRtl);

        void addReplacement(size_t start, size_t end, float width);

    private:
        const size_t MAX_TEXT_BUF_RETAIN = 32678;
        icu::BreakIterator* mBreakIterator = nullptr;
        UText mUText = UTEXT_INITIALIZER;
        std::vector<uint16_t>mTextBuf;
        std::vector<float>mWidthBuf;
};

static const int CHAR_SPACE = 0x20;
static const int CHAR_TAB = 0x09;
static const int CHAR_NEWLINE = 0x0a;
static const int CHAR_ZWSP = 0x200b;

class TabStops {
    public:
        // specified stops must be a sorted array (allowed to be null)
        TabStops(JNIEnv* env, jintArray stops, jint defaultTabWidth) :
            mStops(env), mTabWidth(defaultTabWidth) {
                if (stops != nullptr) {
                    mStops.reset(stops);
                    mNumStops = mStops.size();
                } else {
                    mNumStops = 0;
                }
            }
        float width(float widthSoFar) const {
            const jint* mStopsArray = mStops.get();
            for (int i = 0; i < mNumStops; i++) {
                if (mStopsArray[i] > widthSoFar) {
                    return mStopsArray[i];
                }
            }
            // find the next tabstop after widthSoFar
            return static_cast<int>((widthSoFar + mTabWidth) / mTabWidth) * mTabWidth;
        }
    private:
        ScopedIntArrayRO mStops;
        const int mTabWidth;
        int mNumStops;

        // disable copying and assignment
        TabStops(const TabStops&);
        void operator=(const TabStops&);
};

enum PrimitiveType {
    kPrimitiveType_Box,
    kPrimitiveType_Glue,
    kPrimitiveType_Penalty,
    kPrimitiveType_Variable,
    kPrimitiveType_Wordbreak
};

static const float PENALTY_INFINITY = 1e7; // forced non-break, negative infinity is forced break

struct Primitive {
    PrimitiveType type;
    int location;
    // 'Box' has width
    // 'Glue' has width
    // 'Penalty' has width and penalty
    // 'Variable' has tabStop
    // 'Wordbreak' has penalty
    union {
        struct {
            float width;
            float penalty;
        };
        const TabStops* tabStop;
    };
};

class LineWidth {
    public:
        LineWidth(float firstWidth, int firstWidthLineCount, float restWidth) :
                mFirstWidth(firstWidth), mFirstWidthLineCount(firstWidthLineCount),
                mRestWidth(restWidth) {}
        float getLineWidth(int line) const {
            return (line < mFirstWidthLineCount) ? mFirstWidth : mRestWidth;
        }
    private:
        const float mFirstWidth;
        const int mFirstWidthLineCount;
        const float mRestWidth;
};

class LineBreaker {
    public:
        LineBreaker(const std::vector<Primitive>& primitives,
                    const LineWidth& lineWidth) :
                mPrimitives(primitives), mLineWidth(lineWidth) {}
        virtual ~LineBreaker() {}
        virtual void computeBreaks(std::vector<int>* breaks, std::vector<float>* widths,
                                   std::vector<unsigned char>* flags) const = 0;
    protected:
        const std::vector<Primitive>& mPrimitives;
        const LineWidth& mLineWidth;
};

class OptimizingLineBreaker : public LineBreaker {
    public:
        OptimizingLineBreaker(const std::vector<Primitive>& primitives, const LineWidth& lineWidth) :
                LineBreaker(primitives, lineWidth) {}
        void computeBreaks(std::vector<int>* breaks, std::vector<float>* widths,
                           std::vector<unsigned char>* flags) const {
            int numBreaks = mPrimitives.size();
            Node* opt = new Node[numBreaks];
            opt[0].prev = -1;
            opt[0].prevCount = 0;
            opt[0].width = 0;
            opt[0].demerits = 0;
            opt[0].flags = false;
            opt[numBreaks - 1].prev = -1;
            opt[numBreaks - 1].prevCount = 0;

            std::list<int> active;
            active.push_back(0);
            int lastBreak = 0;
            for (int i = 0; i < numBreaks; i++) {
                const Primitive& p = mPrimitives[i];
                if (p.type == kPrimitiveType_Penalty) {
                    const bool finalBreak = (i + 1 == numBreaks);
                    bool breakFound = false;
                    Node bestBreak;
                    for (std::list<int>::iterator it = active.begin(); it != active.end(); /* incrementing done in loop */) {
                        const int pos = *it;
                        bool flags;
                        float width, printedWidth;
                        const int lines = opt[pos].prevCount;
                        const float maxWidth = mLineWidth.getLineWidth(lines);
                        // we have to compute metrics every time --
                        // we can't really precompute this stuff and just deal with breaks
                        // because of the way tab characters work, this makes it computationally
                        // harder, but this way, we can still optimize while treating tab characters
                        // correctly
                        computeMetrics(pos, i, &width, &printedWidth, &flags);
                        if (printedWidth <= maxWidth) {
                            float demerits = computeDemerits(maxWidth, printedWidth,
                                    finalBreak, p.penalty) + opt[pos].demerits;
                            if (!breakFound || demerits < bestBreak.demerits) {
                                bestBreak.prev = pos;
                                bestBreak.prevCount = opt[pos].prevCount + 1;
                                bestBreak.demerits = demerits;
                                bestBreak.width = printedWidth;
                                bestBreak.flags = flags;
                                breakFound = true;
                            }
                            ++it;
                        } else {
                            active.erase(it++); // safe to delete like this
                        }
                    }
                    if (p.penalty == -PENALTY_INFINITY) {
                        active.clear();
                    }
                    if (breakFound) {
                        opt[i] = bestBreak;
                        active.push_back(i);
                        lastBreak = i;
                    }
                    if (active.empty()) {
                        // we can't give up!
                        float width, printedWidth;
                        bool flags;
                        const int lines = opt[lastBreak].prevCount;
                        const float maxWidth = mLineWidth.getLineWidth(lines);
                        const int breakIndex = desperateBreak(lastBreak, numBreaks, maxWidth, &width, &printedWidth, &flags);

                        opt[breakIndex].prev = lastBreak;
                        opt[breakIndex].prevCount = lines + 1;
                        opt[breakIndex].demerits = 0; // doesn't matter, it's the only one
                        opt[breakIndex].width = width;
                        opt[breakIndex].flags = flags;

                        active.push_back(breakIndex);
                        lastBreak = breakIndex;
                        i = breakIndex; // incremented by i++
                    }
                }
            }

            int idx = numBreaks - 1;
            int count = opt[idx].prevCount;
            breaks->resize(count);
            widths->resize(count);
            flags->resize(count);
            while (opt[idx].prev != -1) {
                --count;

                (*breaks)[count] = mPrimitives[idx].location;
                (*widths)[count] = opt[idx].width;
                (*flags)[count] = opt[idx].flags;

                idx = opt[idx].prev;
            }
            delete[] opt;
        }
    private:
        inline void computeMetrics(int start, int end, float* width, float* printedWidth, bool* flags) const {
            bool f = false;
            float w = 0, pw = 0;
            for (int i = start; i < end; i++) {
                const Primitive& p = mPrimitives[i];
                if (p.type == kPrimitiveType_Box || p.type == kPrimitiveType_Glue) {
                    w += p.width;
                    if (p.type == kPrimitiveType_Box) {
                        pw = w;
                    }
                } else if (p.type == kPrimitiveType_Variable) {
                    w = p.tabStop->width(w);
                    f = true;
                }
            }
            *width = w;
            *printedWidth = pw;
            *flags = f;
        }

        inline float computeDemerits(float maxWidth, float width, bool finalBreak, float penalty) const {
            float deviation = finalBreak ? 0 : maxWidth - width;
            return (deviation * deviation) + penalty;
        }

        // returns end pos (chosen break), -1 if fail
        inline int desperateBreak(int start, int limit, float maxWidth, float* width, float* printedWidth, bool* flags) const {
            float w = 0, pw = 0;
            bool breakFound = false;
            int breakIndex = 0, firstTabIndex = INT_MAX;
            for (int i = start; i < limit; i++) {
                const Primitive& p = mPrimitives[i];

                if (p.type == kPrimitiveType_Box || p.type == kPrimitiveType_Glue) {
                    w += p.width;
                    if (p.type == kPrimitiveType_Box) {
                        pw = w;
                    }
                } else if (p.type == kPrimitiveType_Variable) {
                    w = p.tabStop->width(w);
                    firstTabIndex = std::min(firstTabIndex, i);
                }

                if (pw > maxWidth) {
                    if (breakFound) {
                        break;
                    } else {
                        // no choice, keep going
                    }
                }

                // must make progress
                if (i > start && (p.type == kPrimitiveType_Penalty || p.type == kPrimitiveType_Wordbreak)) {
                    breakFound = true;
                    breakIndex = i;
                }
            }

            if (breakFound) {
                *width = w;
                *printedWidth = pw;
                *flags = (start <= firstTabIndex && firstTabIndex < breakIndex);
                return breakIndex;
            } else {
                return -1;
            }
        }

        struct Node {
            int prev; // set to sentinel value (-1) for initial node
            int prevCount; // number of breaks so far
            float demerits;
            float width;
            bool flags;
        };
};

class GreedyLineBreaker : public LineBreaker {
    public:
        GreedyLineBreaker(const std::vector<Primitive>& primitives, const LineWidth& lineWidth) :
            LineBreaker(primitives, lineWidth) {}
        void computeBreaks(std::vector<int>* breaks, std::vector<float>* widths,
                           std::vector<unsigned char>* flags) const {
            int lineNum = 0;
            float width = 0, printedWidth = 0;
            bool breakFound = false, goodBreakFound = false;
            int breakIndex = 0, goodBreakIndex = 0;
            float breakWidth = 0, goodBreakWidth = 0;
            int firstTabIndex = INT_MAX;

            float maxWidth = mLineWidth.getLineWidth(lineNum);

            const int numPrimitives = mPrimitives.size();
            // greedily fit as many characters as possible on each line
            // loop over all primitives, and choose the best break point
            // (if possible, a break point without splitting a word)
            // after going over the maximum length
            for (int i = 0; i < numPrimitives; i++) {
                const Primitive& p = mPrimitives[i];

                // update the current line width
                if (p.type == kPrimitiveType_Box || p.type == kPrimitiveType_Glue) {
                    width += p.width;
                    if (p.type == kPrimitiveType_Box) {
                        printedWidth = width;
                    }
                } else if (p.type == kPrimitiveType_Variable) {
                    width = p.tabStop->width(width);
                    // keep track of first tab character in the region we are examining
                    // so we can determine whether or not a line contains a tab
                    firstTabIndex = std::min(firstTabIndex, i);
                }

                // find the best break point for the characters examined so far
                if (printedWidth > maxWidth) {
                    if (breakFound || goodBreakFound) {
                        if (goodBreakFound) {
                            // a true line break opportunity existed in the characters examined so far,
                            // so there is no need to split a word
                            i = goodBreakIndex; // no +1 because of i++
                            lineNum++;
                            maxWidth = mLineWidth.getLineWidth(lineNum);
                            breaks->push_back(mPrimitives[goodBreakIndex].location);
                            widths->push_back(goodBreakWidth);
                            flags->push_back(firstTabIndex < goodBreakIndex);
                            firstTabIndex = INT_MAX;
                        } else {
                            // must split a word because there is no other option
                            i = breakIndex; // no +1 because of i++
                            lineNum++;
                            maxWidth = mLineWidth.getLineWidth(lineNum);
                            breaks->push_back(mPrimitives[breakIndex].location);
                            widths->push_back(breakWidth);
                            flags->push_back(firstTabIndex < breakIndex);
                            firstTabIndex = INT_MAX;
                        }
                        printedWidth = width = 0;
                        goodBreakFound = breakFound = false;
                        goodBreakWidth = breakWidth = 0;
                        continue;
                    } else {
                        // no choice, keep going... must make progress by putting at least one
                        // character on a line, even if part of that character is cut off --
                        // there is no other option
                    }
                }

                // update possible break points
                if (p.type == kPrimitiveType_Penalty && p.penalty < PENALTY_INFINITY) {
                    // this does not handle penalties with width

                    // handle forced line break
                    if (p.penalty == -PENALTY_INFINITY) {
                        lineNum++;
                        maxWidth = mLineWidth.getLineWidth(lineNum);
                        breaks->push_back(p.location);
                        widths->push_back(printedWidth);
                        flags->push_back(firstTabIndex < i);
                        firstTabIndex = INT_MAX;
                        printedWidth = width = 0;
                        goodBreakFound = breakFound = false;
                        goodBreakWidth = breakWidth = 0;
                        continue;
                    }
                    if (i > breakIndex && (printedWidth <= maxWidth || breakFound == false)) {
                        breakFound = true;
                        breakIndex = i;
                        breakWidth = printedWidth;
                    }
                    if (i > goodBreakIndex && printedWidth <= maxWidth) {
                        goodBreakFound = true;
                        goodBreakIndex = i;
                        goodBreakWidth = printedWidth;
                    }
                } else if (p.type == kPrimitiveType_Wordbreak) {
                    // only do this if necessary -- we don't want to break words
                    // when possible, but sometimes it is unavoidable
                    if (i > breakIndex && (printedWidth <= maxWidth || breakFound == false)) {
                        breakFound = true;
                        breakIndex = i;
                        breakWidth = printedWidth;
                    }
                }
            }

            if (breakFound || goodBreakFound) {
                // output last break if there are more characters to output
                if (goodBreakFound) {
                    breaks->push_back(mPrimitives[goodBreakIndex].location);
                    widths->push_back(goodBreakWidth);
                    flags->push_back(firstTabIndex < goodBreakIndex);
                } else {
                    breaks->push_back(mPrimitives[breakIndex].location);
                    widths->push_back(breakWidth);
                    flags->push_back(firstTabIndex < breakIndex);
                }
            }
        }
};

static jint recycleCopy(JNIEnv* env, jobject recycle, jintArray recycleBreaks,
                        jfloatArray recycleWidths, jbooleanArray recycleFlags,
                        jint recycleLength, const std::vector<jint>& breaks,
                        const std::vector<jfloat>& widths, const std::vector<jboolean>& flags) {
    int bufferLength = breaks.size();
    if (recycleLength < bufferLength) {
        // have to reallocate buffers
        recycleBreaks = env->NewIntArray(bufferLength);
        recycleWidths = env->NewFloatArray(bufferLength);
        recycleFlags = env->NewBooleanArray(bufferLength);

        env->SetObjectField(recycle, gLineBreaks_fieldID.breaks, recycleBreaks);
        env->SetObjectField(recycle, gLineBreaks_fieldID.widths, recycleWidths);
        env->SetObjectField(recycle, gLineBreaks_fieldID.flags, recycleFlags);
    }
    // copy data
    env->SetIntArrayRegion(recycleBreaks, 0, breaks.size(), &breaks.front());
    env->SetFloatArrayRegion(recycleWidths, 0, widths.size(), &widths.front());
    env->SetBooleanArrayRegion(recycleFlags, 0, flags.size(), &flags.front());

    return bufferLength;
}

void computePrimitives(const jchar* textArr, const jfloat* widthsArr, jint length, const std::vector<int>& breaks,
                       const TabStops& tabStopCalculator, std::vector<Primitive>* primitives) {
    int breaksSize = breaks.size();
    int breakIndex = 0;
    Primitive p;
    for (int i = 0; i < length; i++) {
        p.location = i;
        jchar c = textArr[i];
        if (c == CHAR_SPACE || c == CHAR_ZWSP) {
            p.type = kPrimitiveType_Glue;
            p.width = widthsArr[i];
            primitives->push_back(p);
        } else if (c == CHAR_TAB) {
            p.type = kPrimitiveType_Variable;
            p.tabStop = &tabStopCalculator; // shared between all variable primitives
            primitives->push_back(p);
        } else if (c != CHAR_NEWLINE) {
            while (breakIndex < breaksSize && breaks[breakIndex] < i) breakIndex++;
            p.width = 0;
            if (breakIndex < breaksSize && breaks[breakIndex] == i) {
                p.type = kPrimitiveType_Penalty;
                p.penalty = 0;
            } else {
                p.type = kPrimitiveType_Wordbreak;
            }
            if (widthsArr[i] != 0) {
                primitives->push_back(p);
            }

            p.type = kPrimitiveType_Box;
            p.width = widthsArr[i];
            primitives->push_back(p);
        }
    }
    // final break at end of everything
    p.location = length;
    p.type = kPrimitiveType_Penalty;
    p.width = 0;
    p.penalty = -PENALTY_INFINITY;
    primitives->push_back(p);
}

// sets the text on the builder
static void nSetText(JNIEnv* env, jclass, jlong nativePtr, jcharArray text, int length) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);
    b->resize(length);
    env->GetCharArrayRegion(text, 0, length, b->buffer());
    b->setText();
}

static jint nComputeLineBreaks(JNIEnv* env, jclass, jlong nativePtr,
                               jint length,
                               jfloat firstWidth, jint firstWidthLineLimit, jfloat restWidth,
                               jintArray variableTabStops, jint defaultTabStop, jboolean optimize,
                               jobject recycle, jintArray recycleBreaks,
                               jfloatArray recycleWidths, jbooleanArray recycleFlags,
                               jint recycleLength) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);

    std::vector<int> breaks;

    icu::BreakIterator* breakIterator = b->breakIterator();
    int loc = breakIterator->first();
    while ((loc = breakIterator->next()) != icu::BreakIterator::DONE) {
        breaks.push_back(loc);
    }

    // TODO: all these allocations can be moved into the builder
    std::vector<Primitive> primitives;
    TabStops tabStops(env, variableTabStops, defaultTabStop);
    computePrimitives(b->buffer(), b->widths(), length, breaks, tabStops, &primitives);

    LineWidth lineWidth(firstWidth, firstWidthLineLimit, restWidth);
    std::vector<int> computedBreaks;
    std::vector<float> computedWidths;
    std::vector<unsigned char> computedFlags;

    if (optimize) {
        OptimizingLineBreaker breaker(primitives, lineWidth);
        breaker.computeBreaks(&computedBreaks, &computedWidths, &computedFlags);
    } else {
        GreedyLineBreaker breaker(primitives, lineWidth);
        breaker.computeBreaks(&computedBreaks, &computedWidths, &computedFlags);
    }
    b->finish();

    return recycleCopy(env, recycle, recycleBreaks, recycleWidths, recycleFlags, recycleLength,
            computedBreaks, computedWidths, computedFlags);
}

static jlong nNewBuilder(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Builder);
}

static void nFreeBuilder(JNIEnv*, jclass, jlong nativePtr) {
    delete reinterpret_cast<Builder*>(nativePtr);
}

static void nFinishBuilder(JNIEnv*, jclass, jlong nativePtr) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);
    b->finish();
}

static void nSetLocale(JNIEnv* env, jclass, jlong nativePtr, jstring javaLocaleName) {
    ScopedIcuLocale icuLocale(env, javaLocaleName);
    Builder* b = reinterpret_cast<Builder*>(nativePtr);

    if (icuLocale.valid()) {
        b->setLocale(icuLocale.locale());
    }
}

float Builder::measureStyleRun(Paint* paint, TypefaceImpl* typeface, size_t start, size_t end,
        bool isRtl) {
    Layout layout;
    int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;
    // TODO: should we provide more context?
    MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, mTextBuf.data() + start, 0,
            end - start, end - start);
    layout.getAdvances(mWidthBuf.data() + start);
    return layout.getAdvance();
}

void Builder::addReplacement(size_t start, size_t end, float width) {
    mWidthBuf[start] = width;
    std::fill(&mWidthBuf[start + 1], &mWidthBuf[end], 0.0f);
}

// Basically similar to Paint.getTextRunAdvances but with C++ interface
static jfloat nAddStyleRun(JNIEnv* env, jclass, jlong nativePtr,
        jlong nativePaint, jlong nativeTypeface, jint start, jint end, jboolean isRtl) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);
    Paint* paint = reinterpret_cast<Paint*>(nativePaint);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(nativeTypeface);
    return b->measureStyleRun(paint, typeface, start, end, isRtl);
}

// Accept width measurements for the run, passed in from Java
static void nAddMeasuredRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloatArray widths) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);
    env->GetFloatArrayRegion(widths, start, end - start, b->widths() + start);
}

static void nAddReplacementRun(JNIEnv* env, jclass, jlong nativePtr,
        jint start, jint end, jfloat width) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);
    b->addReplacement(start, end, width);
}

static void nGetWidths(JNIEnv* env, jclass, jlong nativePtr, jfloatArray widths) {
    Builder* b = reinterpret_cast<Builder*>(nativePtr);
    env->SetFloatArrayRegion(widths, 0, b->size(), b->widths());
}

static JNINativeMethod gMethods[] = {
    // TODO performance: many of these are candidates for fast jni, awaiting guidance
    {"nNewBuilder", "()J", (void*) nNewBuilder},
    {"nFreeBuilder", "(J)V", (void*) nFreeBuilder},
    {"nFinishBuilder", "(J)V", (void*) nFinishBuilder},
    {"nSetLocale", "(JLjava/lang/String;)V", (void*) nSetLocale},
    {"nSetText", "(J[CI)V", (void*) nSetText},
    {"nAddStyleRun", "(JJJIIZ)F", (void*) nAddStyleRun},
    {"nAddMeasuredRun", "(JII[F)V", (void*) nAddMeasuredRun},
    {"nAddReplacementRun", "(JIIF)V", (void*) nAddReplacementRun},
    {"nGetWidths", "(J[F)V", (void*) nGetWidths},
    {"nComputeLineBreaks", "(JIFIF[IIZLandroid/text/StaticLayout$LineBreaks;[I[F[ZI)I",
        (void*) nComputeLineBreaks}
};

int register_android_text_StaticLayout(JNIEnv* env)
{
    gLineBreaks_class = MakeGlobalRefOrDie(env,
            FindClassOrDie(env, "android/text/StaticLayout$LineBreaks"));

    gLineBreaks_fieldID.breaks = GetFieldIDOrDie(env, gLineBreaks_class, "breaks", "[I");
    gLineBreaks_fieldID.widths = GetFieldIDOrDie(env, gLineBreaks_class, "widths", "[F");
    gLineBreaks_fieldID.flags = GetFieldIDOrDie(env, gLineBreaks_class, "flags", "[Z");

    return RegisterMethodsOrDie(env, "android/text/StaticLayout", gMethods, NELEM(gMethods));
}

}
