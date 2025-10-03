/*
 * Copyright (C) 2025 crDroid Android Project
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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import kotlin.math.max

internal class SolidLineStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var barRects: Array<RectF> = emptyArray()
    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var roundedPath: Path? = null
    private var cornerRadii: FloatArray? = null
    private var lastColor = 0
    private val smoothing = 0.2f

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        val count = settings.getBarCount()
        if (barRects.size != count) {
            barRects = Array(count) { RectF() }
            currentHeights = FloatArray(count) { 2f }
            targetHeights  = FloatArray(count) { 2f }

            if (settings.isRoundedBarsEnabled()) {
                if (roundedPath == null) roundedPath = Path()
                if (cornerRadii == null) cornerRadii = floatArrayOf(32f, 32f, 32f, 32f, 0f, 0f, 0f, 0f)
            }
        }

        val density = viewWidth.coerceAtLeast(1)
        val gap = settings.getBarGapPx()
        val totalGap = (count - 1) * gap
        val barWidth = if (count > 0) max(0f, viewWidth - totalGap) / count else 0f
        val fullBarWidth = barWidth + gap
        val baseY = viewHeight.toFloat()

        for (i in 0 until count) {
            val left = i * fullBarWidth
            val right = left + barWidth
            barRects[i].set(left, baseY, right, baseY)
        }
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            paint.color = color
            lastColor = color
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
        val useRounded = settings.isRoundedBarsEnabled()
        val path = roundedPath
        val radii = cornerRadii

        for (i in 0 until count) {
            val rect = barRects[i]
            val target = targetHeights.getOrElse(i) { 2f }
            val current = currentHeights.getOrElse(i) { 2f }
            var h = current + smoothing * (target - current)
            if (h < 2f) h = 2f
            val maxH = rect.bottom
            if (h > maxH) h = maxH
            currentHeights[i] = h
            rect.top = rect.bottom - h

            if (useRounded && path != null && radii != null) {
                path.reset()
                path.addRoundRect(rect, radii, Path.Direction.CW)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawRect(rect, paint)
            }
        }
    }

    override fun cleanup() {
        barRects = emptyArray()
        currentHeights = FloatArray(0)
        targetHeights  = FloatArray(0)
        roundedPath = null
        cornerRadii = null
    }
}
