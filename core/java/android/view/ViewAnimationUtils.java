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

package android.view;

import android.animation.RevealAnimator;
import android.animation.ValueAnimator;

/**
 * Defines common utilities for working with View's animations.
 *
 */
public class ViewAnimationUtils {
    private ViewAnimationUtils() {}
    /**
     * Returns a ValueAnimator which can animate a clipping circle.
     *
     * Any shadow cast by the View will respect the circular clip from this animator.
     *
     * @param view The View will be clipped to the animating circle.
     * @param centerX The x coordinate of the center of the animating circle.
     * @param centerY The y coordinate of the center of the animating circle.
     * @param startRadius The starting radius of the animating circle.
     * @param endRadius The ending radius of the animating circle.
     */
    public static final ValueAnimator createCircularReveal(View view,
            int centerX,  int centerY, float startRadius, float endRadius) {
        return RevealAnimator.ofRevealCircle(view, centerX, centerY,
                startRadius, endRadius, false);
    }
}
