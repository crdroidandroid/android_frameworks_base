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

import android.graphics.*
import kotlin.math.max

internal class FadingBlockStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private var bufferBitmap: Bitmap? = null
    private var bufferCanvas: Canvas? = null
    private val fadePaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var fftPoints = FloatArray(0)
    private var divisions = 16
    private var lastW = 0
    private var lastH = 0
    private var lastColor = 0

    private var barCount = 0
    private var barWidthPx = 0f
    private var fullBarWidthPx = 0f
    private var gapPx = 0f

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (viewWidth != lastW || viewHeight != lastH) {
            bufferBitmap?.recycle()
            bufferBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            bufferCanvas = Canvas(bufferBitmap!!)
            lastW = viewWidth
            lastH = viewHeight
        }
        barCount = settings.getBarCount()
        gapPx = settings.getBarGapPx()
        val totalGap = (barCount - 1) * gapPx
        barWidthPx = if (barCount > 0) ((viewWidth - totalGap) / barCount.toFloat()).coerceAtLeast(1f) else 0f
        fullBarWidthPx = barWidthPx + gapPx

        divisions = validateDivision(settings.getDivisions())
        val filled = settings.getFilledBlockSizePx()
        val empty  = settings.getEmptyBlockSizePx()
        linePaint.pathEffect = DashPathEffect(floatArrayOf(filled, empty), 0f)
        linePaint.strokeWidth = barWidthPx
        linePaint.strokeCap = Paint.Cap.BUTT
        ensurePointsCapacity()
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            linePaint.color = color
            lastColor = color
        }
    }

    override fun onData(heights: FloatArray) {
        val c = bufferCanvas ?: return
        ensurePointsCapacity()
        val height = lastH
        val count = minOf(heights.size, barCount)
        if (count <= 0) return

        var x = barWidthPx * 0.5f
        var pi = 0
        for (i in 0 until count) {
            val y0 = height.toFloat()
            val y1 = y0 - heights[i]
            fftPoints[pi++] = x
            fftPoints[pi++] = y0
            fftPoints[pi++] = x
            fftPoints[pi++] = y1
            x += fullBarWidthPx
        }

        c.drawLines(fftPoints, 0, count * 4, linePaint)
        c.drawPaint(fadePaint)
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        bufferBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }
    }

    override fun cleanup() {
        bufferCanvas = null
        bufferBitmap?.recycle()
        bufferBitmap = null
        fftPoints = FloatArray(0)
    }

    private fun ensurePointsCapacity() {
        val need = max(0, barCount) * 4
        if (fftPoints.size != need) {
            fftPoints = FloatArray(need)
        }
    }

    private fun validateDivision(valIn: Int): Int {
        var v = valIn
        if (v % 2 != 0) v = 16
        return v.coerceIn(2, 44)
    }
}
