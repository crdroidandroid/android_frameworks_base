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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

internal class WaveformStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val waveformPath = Path()
    private val fillPath = Path()

    private var barCenters: FloatArray = FloatArray(0)
    private var currentHeights: FloatArray = FloatArray(0)
    private var targetHeights: FloatArray = FloatArray(0)

    private var lastColor: Int = 0
    private val smoothing: Float = 0.25f

    private var showFill: Boolean = true
    private var showOutline: Boolean = true

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        val barCount = settings.getBarCount()

        barCenters = FloatArray(barCount)

        if (currentHeights.size != barCount) {
            currentHeights = FloatArray(barCount) { 2f }
            targetHeights = FloatArray(barCount) { 2f }
        }

        val gap = settings.getBarGapPx()
        val totalGap = max(0, barCount - 1).toFloat() * gap
        val barWidth: Float = if (barCount > 0) {
            max(1f, (viewWidth.toFloat() - totalGap) / barCount.toFloat())
        } else {
            0f
        }
        val step = gap + barWidth

        waveformPaint.strokeCap =
            if (settings.isRoundedBarsEnabled()) Paint.Cap.ROUND else Paint.Cap.BUTT

        val density = settings.displayDensity()
        waveformPaint.strokeWidth = max(density * 3f, viewHeight.toFloat() * 0.003f)

        for (i in 0 until barCount) {
            barCenters[i] = i.toFloat() * step + 0.5f * barWidth
        }

        waveformPaint.color = lastColor
        fillPaint.color = fadeFillFromOutline(lastColor)
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            lastColor = color
            waveformPaint.color = color
            fillPaint.color = fadeFillFromOutline(color)
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
        val n = barCenters.size
        if (n == 0 || viewHeight <= 0) return

        val viewH = viewHeight.toFloat()

        waveformPath.reset()
        fillPath.reset()

        val lift0 = smoothedLift(viewH, 0)
        val top0 = viewH - lift0

        waveformPath.moveTo(barCenters[0], top0)

        fillPath.moveTo(barCenters[0], viewH)
        fillPath.lineTo(barCenters[0], top0)

        for (i in 1 until n) {
            val lift = smoothedLift(viewH, i)
            val top = viewH - lift
            waveformPath.lineTo(barCenters[i], top)
            fillPath.lineTo(barCenters[i], top)
        }

        fillPath.lineTo(barCenters[n - 1], viewH)
        fillPath.close()

        if (showFill) {
            canvas.drawPath(fillPath, fillPaint)
        }

        if (showOutline && n >= 2) {
            canvas.drawPath(waveformPath, waveformPaint)
        } else if (showOutline && n == 1) {
            canvas.drawLine(
                barCenters[0], viewH,
                barCenters[0], top0,
                waveformPaint
            )
        }
    }

    override fun cleanup() {
        barCenters = FloatArray(0)
        currentHeights = FloatArray(0)
        targetHeights = FloatArray(0)
        waveformPath.reset()
        fillPath.reset()
        lastColor = 0
    }

    private fun smoothedLift(viewH: Float, index: Int): Float {
        val target = if (index in targetHeights.indices) targetHeights[index] else 2f
        val current = if (index in currentHeights.indices) currentHeights[index] else 2f

        var result = current + smoothing * (target - current)
        if (result < 2f) result = 2f
        if (result > viewH) result = viewH

        if (index in currentHeights.indices) {
            currentHeights[index] = result
        }
        return result
    }

    companion object {
        @JvmStatic
        fun fadeFillFromOutline(color: Int): Int {
            val srcAlpha = Color.alpha(color)
            val scaled = (srcAlpha.toFloat() / 2.5f).toInt()
            val clampedAlpha = max(0x24, min(0x6E, scaled))
            return (clampedAlpha shl 24) or (color and 0x00FFFFFF)
        }
    }
}
