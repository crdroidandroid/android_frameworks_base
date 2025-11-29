/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2025 crDroid Android Project
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
package com.android.systemui.pulse

import android.content.Context
import android.graphics.*
import kotlin.math.max

class PulseRenderer(
    context: Context,
    private val settingsRepo: PulseSettingsRepository
) {

    private val context = context.applicationContext

    private val accentColor = context.resources.getColor(android.R.color.system_accent1_100)
    private var mediaColor = accentColor

    private var style: PulseStyleRenderer = createStyle(settingsRepo.getStyleMode())
    private var lastViewW = 0
    private var lastViewH = 0
    private var lastColor = 0
    private var lastBarCount = -1
    private var lastDataSize = -1

    fun updateHeights(newHeights: FloatArray) {
        ensureStyleUpToDate()
        val currentBarCount = settingsRepo.getBarCount()
        val dataSizeChanged = newHeights.size != lastDataSize
        val barCountChanged = currentBarCount != lastBarCount
        if ((dataSizeChanged || barCountChanged) && lastViewW > 0 && lastViewH > 0) {
            style.onSizeChanged(lastViewW, lastViewH)
            lastBarCount = currentBarCount
            lastDataSize = newHeights.size
        }
        style.onData(newHeights)
    }

    fun onDraw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        if (!settingsRepo.isPulseEnabled()) return
        if (lastViewW != viewWidth || lastViewH != viewHeight) {
            style.onSizeChanged(viewWidth, viewHeight)
            lastViewW = viewWidth
            lastViewH = viewHeight
            lastBarCount = settingsRepo.getBarCount()
        }
        style.onColor(getPulseColor())
        style.draw(canvas, viewWidth, viewHeight)
    }

    private fun ensureStyleUpToDate() {
        val want = settingsRepo.getStyleMode()
        val needsSwap = when (want) {
            "solid" -> style !is SolidLineStyleRenderer
            "fading" -> style !is FadingBlockStyleRenderer
            "neon" -> style !is NeonStyleRenderer
            "retro" -> style !is RetroVUStyleRenderer
            "minimal" -> style !is MinimalStyleRenderer
            "sparkle" -> style !is SparkleStyleRenderer
            "matrix" -> style !is MatrixStyleRenderer
            else -> false
        }

        if (needsSwap) {
            val prevW = lastViewW
            val prevH = lastViewH
            val prevColor = lastColor.takeIf { it != 0 } ?: getPulseColor()
            style.cleanup()
            style = createStyle(want)
            if (prevW > 0 && prevH > 0) style.onSizeChanged(prevW, prevH)
            style.onColor(prevColor)
        }
    }

    private fun createStyle(mode: String): PulseStyleRenderer {
        return when (mode) {
            "fading" -> FadingBlockStyleRenderer(settingsRepo)
            "neon" -> NeonStyleRenderer(settingsRepo)
            "retro" -> RetroVUStyleRenderer(settingsRepo)
            "minimal" -> MinimalStyleRenderer(settingsRepo)
            "sparkle" -> SparkleStyleRenderer(settingsRepo)
            "matrix" -> MatrixStyleRenderer(settingsRepo)
            else -> SolidLineStyleRenderer(settingsRepo)
        }
    }

    private fun getPulseColor(): Int {
        val alpha = (0.88f * 255).toInt()
        val mode = settingsRepo.getColorMode()
        val color = when (mode) {
            "album" -> mediaColor
            "lavalamp" -> {
                val time = System.currentTimeMillis()
                val hue = (time / 50) % 360
                Color.HSVToColor(alpha, floatArrayOf(hue.toFloat(), 1f, 1f))
            }
            "accent" -> accentColor
            else -> Color.WHITE
        }
        return if (mode == "lavalamp") {
            color
        } else {
            (alpha shl 24) or (color and 0x00FFFFFF)
        }
    }

    fun onMediaColorsChanged(color: Int) {
        mediaColor = color
    }

    fun cleanup() {
        style.cleanup()
    }
}
