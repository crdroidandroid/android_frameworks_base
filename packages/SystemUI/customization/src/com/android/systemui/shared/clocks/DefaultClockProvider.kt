/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Vibrator
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.customization.clocks.ClockContext
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.customization.clocks.TimeKeeper
import com.android.systemui.customization.clocks.TimeKeeperImpl
import com.android.systemui.customization.clocks.TypefaceCache
import com.android.systemui.plugins.keyguard.ui.clocks.AxisPresetConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFontAxis.Companion.merge
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProvider
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.shared.clocks.FlexClockController.Companion.buildPresetGroup
import com.android.systemui.shared.clocks.FlexClockController.Companion.getDefaultAxes

private val TAG = DefaultClockProvider::class.simpleName
const val DEFAULT_CLOCK_ID = "DEFAULT"
const val FLEX_CLOCK_ID = "DIGITAL_CLOCK_FLEX"

/** Provides the default system clock */
class DefaultClockProvider
@JvmOverloads
constructor(
    val layoutInflater: LayoutInflater,
    val resources: Resources,
    private val isClockReactiveVariantsEnabled: Boolean = false,
    private val vibrator: Vibrator?,
    private val timeKeeperFactory: () -> TimeKeeper = { TimeKeeperImpl() },
) : ClockProvider {
    private var messageBuffers: ClockMessageBuffers? = null

    override fun initialize(buffers: ClockMessageBuffers?) {
        messageBuffers = buffers
    }

    override fun getClocks(): List<ClockMetadata> {
        var clocks = listOf(ClockMetadata(DEFAULT_CLOCK_ID))
        if (isClockReactiveVariantsEnabled) {
            clocks +=
                ClockMetadata(
                    FLEX_CLOCK_ID,
                    isDeprecated = true,
                    replacementTarget = DEFAULT_CLOCK_ID,
                )
        }
        return clocks
    }

    override fun createClock(ctx: Context, settings: ClockSettings): ClockController {
        if (getClocks().all { it.clockId != settings.clockId }) {
            throw IllegalArgumentException("${settings.clockId} is unsupported by $TAG")
        }

        return if (isClockReactiveVariantsEnabled) {
            val buffers = messageBuffers ?: ClockMessageBuffers(ClockLogger.DEFAULT_MESSAGE_BUFFER)
            val fontAxes = getDefaultAxes(settings).merge(settings.axes)
            val clockSettings = settings.copy(axes = ClockAxisStyle(fontAxes))
            val typefaceCache =
                TypefaceCache(buffers.infraMessageBuffer, NUM_CLOCK_FONT_ANIMATION_STEPS) {
                    getDefaultClockFontFamily()
                }
            FlexClockController(
                ClockContext(
                    ctx,
                    resources,
                    clockSettings,
                    typefaceCache,
                    buffers.infraMessageBuffer,
                    vibrator,
                    timeKeeperFactory(),
                ),
                buffers,
            )
        } else {
            DefaultClockController(ctx, layoutInflater, resources, settings, messageBuffers)
        }
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        if (getClocks().all { it.clockId != settings.clockId }) {
            throw IllegalArgumentException("${settings.clockId} is unsupported by $TAG")
        }

        if (!isClockReactiveVariantsEnabled) {
            return ClockPickerConfig(
                settings.clockId ?: DEFAULT_CLOCK_ID,
                resources.getString(R.string.clock_default_name),
                resources.getString(R.string.clock_default_description),
                resources.getDrawable(R.drawable.clock_default_thumbnail, null),
                isReactiveToTone = true,
                axes = emptyList(),
                presetConfig = null,
            )
        } else {
            val fontAxes = getDefaultAxes(settings).merge(settings.axes)
            return ClockPickerConfig(
                settings.clockId ?: DEFAULT_CLOCK_ID,
                resources.getString(R.string.clock_default_name),
                resources.getString(R.string.clock_default_description),
                resources.getDrawable(R.drawable.clock_default_thumbnail, null),
                isReactiveToTone = true,
                axes = fontAxes,
                presetConfig =
                    AxisPresetConfig(
                            listOf(
                                buildPresetGroup(resources, isRound = true),
                                buildPresetGroup(resources, isRound = false),
                            )
                        )
                        .let { cfg -> cfg.copy(current = cfg.findStyle(ClockAxisStyle(fontAxes))) },
            )
        }
    }

    private fun getDefaultClockFontFamily(): Typeface {
        val resId = resources.getIdentifier(
            "config_clockFontFamily",
            "string",
            "android"
        )
        val family = if (resId != 0) {
            resources.getString(resId)
        } else {
            "google-sans-flex-clock"
        }

        return Typeface.create(family, Typeface.NORMAL)
    } 

    companion object {
        // 750ms @ 120hz -> 90 frames of animation
        // In practice, 30 looks good enough and limits our memory usage
        const val NUM_CLOCK_FONT_ANIMATION_STEPS = 30
    }
}
