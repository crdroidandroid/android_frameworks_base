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
import kotlin.math.min

internal class RetroVUStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val segmentPaints = mutableListOf<Paint>()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 100, 100, 100)
    }

    private var barRects: Array<RectF> = emptyArray()
    private var segmentRects: Array<Array<RectF>> = emptyArray()
    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var lastColor = 0
    private val smoothing = 0.15f
    private var barWidth = 0f
    private var viewH = 0
    private var segmentCount = 12
    private var segmentHeight = 0f
    private var segmentGap = 0f

    init {
        createSegmentColors()
    }

    private fun createSegmentColors() {
        segmentPaints.clear()

        val greenCount = (segmentCount * 0.6f).toInt()
        for (i in 0 until greenCount) {
            segmentPaints.add(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(255, 50, 220, 80)
            })
        }

        val yellowCount = (segmentCount * 0.25f).toInt()
        for (i in 0 until yellowCount) {
            segmentPaints.add(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(255, 255, 200, 50)
            })
        }

        val remaining = segmentCount - greenCount - yellowCount
        for (i in 0 until remaining) {
            segmentPaints.add(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(255, 255, 50, 50)
            })
        }
    }

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        viewH = viewHeight
        val count = settings.getBarCount()

        if (barRects.size != count) {
            barRects = Array(count) { RectF() }
            currentHeights = FloatArray(count) { 2f }
            targetHeights = FloatArray(count) { 2f }
            segmentRects = Array(count) { Array(segmentCount) { RectF() } }
        }

        val gap = settings.getBarGapPx()
        val totalGap = (count - 1) * gap
        barWidth = if (count > 0) max(1f, (viewWidth - totalGap) / count) else 0f
        val fullBarWidth = barWidth + gap

        segmentGap = 2f
        val totalSegmentGap = (segmentCount - 1) * segmentGap
        segmentHeight = if (segmentCount > 0) {
            max(3f, (viewHeight - totalSegmentGap) / segmentCount)
        } else 0f

        val baseY = viewHeight.toFloat()

        for (i in 0 until count) {
            val left = i * fullBarWidth
            val right = left + barWidth
            barRects[i].set(left, 0f, right, baseY)

            for (seg in 0 until segmentCount) {
                val segTop = baseY - (seg + 1) * (segmentHeight + segmentGap) + segmentGap
                val segBottom = segTop + segmentHeight
                segmentRects[i][seg].set(left, segTop, right, segBottom)
            }
        }
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            lastColor = color
            val alpha = Color.alpha(color)
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

            val heightPercent = h / maxH
            val litSegments = (heightPercent * segmentCount).toInt()

            for (seg in 0 until segmentCount) {
                val segRect = segmentRects[i][seg]

                if (seg < litSegments) {
                    canvas.drawRect(segRect, segmentPaints[seg])
                } else {
                    canvas.drawRect(segRect, backgroundPaint)
                }
            }
        }
    }

    override fun cleanup() {
        barRects = emptyArray()
        segmentRects = emptyArray()
        currentHeights = FloatArray(0)
        targetHeights = FloatArray(0)
        segmentPaints.clear()
    }
}
