/*
 * Copyright (C) 2025 The AxionAOSP Project
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var barRects: Array<RectF> = emptyArray()
    private var barHeights: FloatArray = floatArrayOf()
    private var currentHeights: FloatArray = floatArrayOf()
    private var targetHeights: FloatArray = floatArrayOf()
    private val smoothingFactor = 0.2f

    private val accentColor = context.resources.getColor(android.R.color.system_accent1_100)
    private var mediaColor = accentColor

    private var roundedBarPath: Path? = null
    private var cornerRadii: FloatArray? = null

    private var lastPaintColor: Int = -1

    fun updateHeights(newHeights: FloatArray) {
        if (targetHeights.size != newHeights.size) {
            targetHeights = FloatArray(newHeights.size)
            currentHeights = FloatArray(newHeights.size)
        }
        for (i in newHeights.indices) {
            targetHeights[i] = newHeights[i]
        }
    }

    fun onDraw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        if (!settingsRepo.isPulseEnabled()) return

        val barCount = settingsRepo.getBarCount()
        setupBarsIfNeeded(barCount, viewWidth, viewHeight)
        drawBars(canvas, barCount)
    }

    private fun setupBarsIfNeeded(barCount: Int, viewWidth: Int, viewHeight: Int) {
        if (barRects.size != barCount) {
            barRects = Array(barCount) { RectF() }
            barHeights = FloatArray(barCount)

            currentHeights = FloatArray(barCount) { 2f }
            targetHeights = FloatArray(barCount) { 2f }

            if (settingsRepo.isRoundedBarsEnabled()) {
                if (roundedBarPath == null) roundedBarPath = Path()
                if (cornerRadii == null) {
                    cornerRadii = floatArrayOf(32f, 32f, 32f, 32f, 0f, 0f, 0f, 0f)
                }
            }
        }

        val density = context.resources.displayMetrics.density
        val gap = 2f * density
        val totalGap = (barCount - 1) * gap
        val barWidth = if (barCount > 0) max(0f, viewWidth - totalGap) / barCount else 0f
        val fullBarWidth = barWidth + gap
        val baseY = viewHeight.toFloat()

        for (i in 0 until barCount) {
            val left = i * fullBarWidth
            val right = left + barWidth
            barRects[i].set(left, baseY, right, baseY)
        }
    }

    private fun drawBars(canvas: Canvas, barCount: Int) {
        if (targetHeights.size != barCount 
                || currentHeights.size != barCount) {
            return
        }
        val newColor = getPulseColor()
        if (newColor != lastPaintColor) {
            paint.color = newColor
            lastPaintColor = newColor
        }

        val useRounded = settingsRepo.isRoundedBarsEnabled()
        val path = roundedBarPath
        val radii = cornerRadii

        for (i in 0 until barCount) {
            val rect = barRects[i]
            val target = if (i < targetHeights.size) targetHeights[i] else 2f
            val current = if (i < currentHeights.size) currentHeights[i] else 2f

            val smoothedHeight = current + smoothingFactor * (target - current)
            currentHeights[i] = smoothedHeight
            rect.top = rect.bottom - smoothedHeight

            if (useRounded && path != null && radii != null) {
                path.reset()
                path.addRoundRect(rect, radii, Path.Direction.CW)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawRect(rect, paint)
            }
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
        barRects = emptyArray()
        barHeights = floatArrayOf()
        roundedBarPath = null
        cornerRadii = null
    }
}
