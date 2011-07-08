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

#ifndef ANDROID_HWUI_TEXTURE_H
#define ANDROID_HWUI_TEXTURE_H

#include <GLES2/gl2.h>

namespace android {
namespace uirenderer {

/**
 * Represents an OpenGL texture.
 */
struct Texture {
    Texture() {
        cleanup = false;
        bitmapSize = 0;

        wrapS = GL_CLAMP_TO_EDGE;
        wrapT = GL_CLAMP_TO_EDGE;

        minFilter = GL_NEAREST;
        magFilter = GL_NEAREST;
    }

    void setWrap(GLenum wrapS, GLenum wrapT) {
        this->wrapS = wrapS;
        this->wrapT = wrapT;
    }

    void setFilter(GLenum min, GLenum mag) {
        minFilter = min;
        magFilter = mag;
    }

    /**
     * Name of the texture.
     */
    GLuint id;
    /**
     * Generation of the backing bitmap,
     */
    uint32_t generation;
    /**
     * Indicates whether the texture requires blending.
     */
    bool blend;
    /**
     * Width of the backing bitmap.
     */
    uint32_t width;
    /**
     * Height of the backing bitmap.
     */
    uint32_t height;
    /**
     * Indicates whether this texture should be cleaned up after use.
     */
    bool cleanup;
    /**
     * Optional, size of the original bitmap.
     */
    uint32_t bitmapSize;

    /**
     * Last wrap modes set on this texture. Defaults to GL_CLAMP_TO_EDGE.
     */
    GLenum wrapS;
    GLenum wrapT;

    /**
     * Last filters set on this texture. Defaults to GL_NEAREST.
     */
    GLenum minFilter;
    GLenum magFilter;
}; // struct Texture

class AutoTexture {
public:
    AutoTexture(const Texture* texture): mTexture(texture) { }
    ~AutoTexture() {
        if (mTexture && mTexture->cleanup) {
            glDeleteTextures(1, &mTexture->id);
            delete mTexture;
        }
    }

private:
    const Texture* mTexture;
}; // class AutoTexture

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TEXTURE_H
