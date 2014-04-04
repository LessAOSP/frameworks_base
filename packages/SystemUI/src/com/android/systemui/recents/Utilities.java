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

package com.android.systemui.recents;

import android.graphics.Rect;

/* Common code */
public class Utilities {
    /**
     * Calculates a consistent animation duration (ms) for all animations depending on the movement
     * of the object being animated.
     */
    public static int calculateTranslationAnimationDuration(int distancePx) {
        return calculateTranslationAnimationDuration(distancePx, 100);
    }
    public static int calculateTranslationAnimationDuration(int distancePx, int minDuration) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        return Math.max(minDuration,
            (int) (Math.abs(distancePx) / config.animationDpsMovementPerSecond) * 1000 /* ms/s */);
    }

    /** Scales a rect about its centroid */
    public static void scaleRectAboutCenter(Rect r, float scale) {
        if (scale != 1.0f) {
            int cx = r.centerX();
            int cy = r.centerY();
            r.offset(-cx, -cy);
            r.left = (int) (r.left * scale + 0.5f);
            r.top = (int) (r.top * scale + 0.5f);
            r.right = (int) (r.right * scale + 0.5f);
            r.bottom = (int) (r.bottom * scale + 0.5f);
            r.offset(cx, cy);
        }
    }
}
