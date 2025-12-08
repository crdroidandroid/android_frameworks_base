/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.haptics.ui

import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter

object VolumeHapticsConfigsProvider {

    fun discreteConfigs(stepSize: Float, filter: SliderHapticFeedbackFilter): VolumeHapticsConfigs =
        provideConfigs(stepSize, filter)

    fun continuousConfigs(filter: SliderHapticFeedbackFilter): VolumeHapticsConfigs =
        provideConfigs(stepSize = 0f, filter)

    private fun provideConfigs(
        stepSize: Float,
        filter: SliderHapticFeedbackFilter,
    ): VolumeHapticsConfigs {
        val hapticFeedbackConfig: SliderHapticFeedbackConfig
        val trackerConfig: SeekableSliderTrackerConfig
        if (stepSize == 0f) {
            // Create a set of continuous configs
            hapticFeedbackConfig =
                SliderHapticFeedbackConfig(
                    progressBasedDragMinScale = 0.1f,
                    progressBasedDragMaxScale = 0.85f,
                    additionalVelocityMaxBump = 0.25f,
                    deltaProgressForDragThreshold = 0.02f,
                    numberOfLowTicks = 4,
                    maxVelocityToScale = 0.5f, /* slider progress(from 0 to 1) per sec */
                    filter = filter,
                )
            trackerConfig =
                SeekableSliderTrackerConfig(
                    lowerBookendThreshold = 0.01f,
                    upperBookendThreshold = 0.99f,
                )
        } else {
            // Create a set of discrete configs
            hapticFeedbackConfig =
                SliderHapticFeedbackConfig(
                    lowerBookendScale = 0.2f,
                    progressBasedDragMinScale = 0.2f,
                    progressBasedDragMaxScale = 0.85f,
                    deltaProgressForDragThreshold = 0f,
                    additionalVelocityMaxBump = 0.25f,
                    maxVelocityToScale = 0.1f, /* slider progress(from 0 to 1) per sec */
                    sliderStepSize = stepSize,
                    filter = filter,
                )
            trackerConfig =
                SeekableSliderTrackerConfig(lowerBookendThreshold = 0f, upperBookendThreshold = 1f)
        }
        return VolumeHapticsConfigs(hapticFeedbackConfig, trackerConfig)
    }
}

// A collection of configuration parameters for the haptics in the slider
data class VolumeHapticsConfigs(
    val hapticFeedbackConfig: SliderHapticFeedbackConfig,
    val sliderTrackerConfig: SeekableSliderTrackerConfig,
)
