/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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

import android.graphics.*
import kotlin.math.max

internal class NeonStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var barRects: Array<RectF> = emptyArray()
    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var lastColor = 0
    private val smoothing = 0.25f
    private var barWidth = 0f
    private var viewH = 0

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        viewH = viewHeight
        val count = settings.getBarCount()

        if (barRects.size != count) {
            barRects = Array(count) { RectF() }
            currentHeights = FloatArray(count) { 2f }
            targetHeights = FloatArray(count) { 2f }
        }

        val gap = settings.getBarGapPx()
        val totalGap = (count - 1) * gap
        barWidth = if (count > 0) max(1f, (viewWidth - totalGap) / count) else 0f
        val fullBarWidth = barWidth + gap

        val coreWidth = (barWidth * 0.3f).coerceAtLeast(2f)
        val glowWidth = (barWidth * 0.8f).coerceAtLeast(4f)

        corePaint.strokeWidth = coreWidth
        glowPaint.strokeWidth = glowWidth

        val baseY = viewHeight.toFloat()
        for (i in 0 until count) {
            val centerX = i * fullBarWidth + barWidth * 0.5f
            barRects[i].set(centerX, baseY, centerX, baseY)
        }
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            lastColor = color

            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            corePaint.color = Color.argb(255, 255, 255, 255)

            glowPaint.color = Color.argb(180, r, g, b)
            glowPaint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        }
    }

    override fun onData(heights: FloatArray) {
        if (heights.size != targetHeights.size) {
            targetHeights = FloatArray(heights.size)
            currentHeights = FloatArray(heights.size) { 2f }
        }
        System.arraycopy(heights, 0, targetHeights, 0, heights.size)
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        val count = barRects.size

        for (i in 0 until count) {
            val rect = barRects[i]
            val target = targetHeights.getOrElse(i) { 2f }
            val current = currentHeights.getOrElse(i) { 2f }

            var h = current + smoothing * (target - current)
            if (h < 2f) h = 2f
            val maxH = rect.bottom
            if (h > maxH) h = maxH

            currentHeights[i] = h

            val x = rect.left
            val y1 = rect.bottom
            val y0 = y1 - h

            // Draw glow layer first (outer)
            canvas.drawLine(x, y1, x, y0, glowPaint)

            // Draw bright core on top
            canvas.drawLine(x, y1, x, y0, corePaint)
        }
    }

    override fun cleanup() {
        barRects = emptyArray()
        currentHeights = FloatArray(0)
        targetHeights = FloatArray(0)
    }
}
