/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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

import android.graphics.*
import kotlin.math.max

internal class MinimalStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f
    }

    private var points = FloatArray(0)
    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var lastColor = 0
    private val smoothing = 0.3f
    private var spacing = 0f
    private var viewH = 0
    private var lastW = 0
    private var barCount = 0

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        viewH = viewHeight
        lastW = viewWidth
        barCount = settings.getBarCount()

        ensureArrays(barCount)
        spacing = computeSpacing(lastW, barCount)
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            lastColor = color

            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val subtleAlpha = 180 // More transparent

            linePaint.color = Color.argb(subtleAlpha, r, g, b)
        }
    }

    override fun onData(heights: FloatArray) {
        if (heights.size != targetHeights.size) {
            targetHeights = FloatArray(heights.size)
            currentHeights = FloatArray(heights.size) { 2f }
        }
        System.arraycopy(heights, 0, targetHeights, 0, heights.size)

        val desiredBars = settings.getBarCount()
        if (desiredBars != barCount) {
            barCount = desiredBars
            if (lastW > 0) {
                spacing = computeSpacing(lastW, barCount)
            }
            ensureArrays(barCount)
        }
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        val count = minOf(barCount, currentHeights.size, targetHeights.size)
        if (count <= 0) return

        if (points.size < count * 2) {
            points = FloatArray(count * 2)
        }

        for (i in 0 until count) {
            val target = targetHeights[i]
            val current = currentHeights[i]

            var h = current + smoothing * (target - current)
            if (h < 2f) h = 2f
            if (h > viewHeight) h = viewHeight.toFloat()

            currentHeights[i] = h

            val x = i * spacing
            val y = viewHeight - h

            val base = i * 2
            if (base + 1 < points.size) {
                points[base] = x
                points[base + 1] = y
            }
        }

        if (count >= 2 && points.size >= 4) {
            val path = Path()
            path.moveTo(points[0], points[1])

            for (i in 0 until count - 1) {
                val x1 = points[i * 2]
                val y1 = points[i * 2 + 1]
                val x2 = points[(i + 1) * 2]
                val y2 = points[(i + 1) * 2 + 1]

                val cx = (x1 + x2) / 2f
                val cy = (y1 + y2) / 2f

                path.quadTo(x1, y1, cx, cy)
            }

            path.lineTo(points[(count - 1) * 2], points[(count - 1) * 2 + 1])

            canvas.drawPath(path, linePaint)
        }
    }

    override fun cleanup() {
        points = FloatArray(0)
        currentHeights = FloatArray(0)
        targetHeights = FloatArray(0)
        barCount = 0
        lastW = 0
    }

    private fun ensureArrays(targetBars: Int) {
        if (currentHeights.size != targetBars) {
            currentHeights = FloatArray(targetBars) { 2f }
        }
        if (targetHeights.size != targetBars) {
            targetHeights = FloatArray(targetBars) { 2f }
        }
        if (points.size != targetBars * 2) {
            points = FloatArray(targetBars * 2)
        }
    }

    private fun computeSpacing(width: Int, bars: Int): Float {
        return if (bars > 1) width.toFloat() / (bars - 1) else width.toFloat()
    }
}
