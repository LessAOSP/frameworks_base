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

#define LOG_TAG "Minikin"
#include <cutils/log.h>
#include <string>

#include "SkPathMeasure.h"
#include "minikin/Layout.h"
#include "Paint.h"
#include "TypefaceImpl.h"
#include "MinikinSkia.h"

#include "MinikinUtils.h"

namespace android {

// Do an sprintf starting at offset n, abort on overflow
static int snprintfcat(char* buf, int off, int size, const char* format, ...)
        __attribute__((__format__(__printf__, 4, 5)));
static int snprintfcat(char* buf, int off, int size, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int n = vsnprintf(buf + off, size - off, format, args);
    LOG_ALWAYS_FATAL_IF(n >= size - off, "String overflow in setting layout properties");
    va_end(args);
    return off + n;
}

std::string MinikinUtils::setLayoutProperties(Layout* layout, const Paint* paint, int bidiFlags,
        TypefaceImpl* typeface) {
    TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(typeface);
    layout->setFontCollection(resolvedFace->fFontCollection);
    FontStyle style = resolvedFace->fStyle;
    char css[512];
    int off = snprintfcat(css, 0, sizeof(css),
        "font-size: %d; font-scale-x: %f; font-skew-x: %f; -paint-flags: %d;"
        " font-weight: %d; font-style: %s; -minikin-bidi: %d; letter-spacing: %f;",
        (int)paint->getTextSize(),
        paint->getTextScaleX(),
        paint->getTextSkewX(),
        MinikinFontSkia::packPaintFlags(paint),
        style.getWeight() * 100,
        style.getItalic() ? "italic" : "normal",
        bidiFlags,
        paint->getLetterSpacing());
    SkString langString = paint->getPaintOptionsAndroid().getLanguage().getTag();
    off = snprintfcat(css, off, sizeof(css), " lang: %s;", langString.c_str());
    SkPaintOptionsAndroid::FontVariant var = paint->getPaintOptionsAndroid().getFontVariant();
    const char* varstr = var == SkPaintOptionsAndroid::kElegant_Variant ? "elegant" : "compact";
    off = snprintfcat(css, off, sizeof(css), " -minikin-variant: %s;", varstr);
    return std::string(css);
}

float MinikinUtils::xOffsetForTextAlign(Paint* paint, const Layout& layout) {
    switch (paint->getTextAlign()) {
        case Paint::kCenter_Align:
            return layout.getAdvance() * -0.5f;
            break;
        case Paint::kRight_Align:
            return -layout.getAdvance();
            break;
        default:
            break;
    }
    return 0;
}

float MinikinUtils::hOffsetForTextAlign(Paint* paint, const Layout& layout, const SkPath& path) {
    float align = 0;
    switch (paint->getTextAlign()) {
        case Paint::kCenter_Align:
            align = -0.5f;
            break;
        case Paint::kRight_Align:
            align = -1;
            break;
        default:
            return 0;
    }
    SkPathMeasure measure(path, false);
    return align * (layout.getAdvance() - measure.getLength());
}

}
