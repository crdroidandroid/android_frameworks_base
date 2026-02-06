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
import kotlin.random.Random

internal class MatrixStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private var baseColor = Color.argb(255, 0, 255, 65)
    private var currentColorMode = "accent"
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    private val brightTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        style = Paint.Style.FILL
    }

    private val mediumTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        style = Paint.Style.FILL
    }

    private val darkTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        style = Paint.Style.FILL
    }

    private val textGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    private var barColumns: Array<MatrixColumn> = emptyArray()
    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var viewW = 0
    private var viewH = 0
    private var barCount = 0
    private var columnWidth = 0f
    private var charSize = 0f
    private var maxCharsPerColumn = 0

    private val smoothing = 0.22f
    private val numbers = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
    
    private var changeCounter = 0
    private val changeInterval = 3

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        viewW = viewWidth
        viewH = viewHeight
        barCount = settings.getBarCount()

        if (barCount <= 0) return

        val gap = settings.getBarGapPx()
        val totalGap = (barCount - 1) * gap
        columnWidth = if (barCount > 0) max(1f, (viewWidth - totalGap) / barCount) else 0f
        
        charSize = (columnWidth * 0.7f).coerceIn(16f, 36f)
        
        brightTextPaint.textSize = charSize
        mediumTextPaint.textSize = charSize
        darkTextPaint.textSize = charSize
        textGlowPaint.textSize = charSize

        maxCharsPerColumn = if (charSize > 0) (viewHeight / (charSize * 1.1f)).toInt().coerceAtLeast(1) else 1

        if (barColumns.size != barCount) {
            barColumns = Array(barCount) { MatrixColumn(maxCharsPerColumn) }
            currentHeights = FloatArray(barCount) { 2f }
            targetHeights = FloatArray(barCount) { 2f }
        } else {
            barColumns.forEach { it.ensureCapacity(maxCharsPerColumn) }
        }
    }

    override fun onColor(color: Int) {
        baseColor = color
        currentColorMode = settings.getColorMode()

        if (currentColorMode != "lavalamp") {
            updatePaintColors(color)
        }
    }
    
    private fun updatePaintColors(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        
        brightTextPaint.color = Color.argb(255, r, g, b)
        mediumTextPaint.color = Color.argb(200, (r * 0.86f).toInt(), (g * 0.86f).toInt(), (b * 0.86f).toInt())
        darkTextPaint.color = Color.argb(120, (r * 0.63f).toInt(), (g * 0.63f).toInt(), (b * 0.63f).toInt())
        textGlowPaint.color = Color.argb(150, r, g, b)
    }

    override fun onData(heights: FloatArray) {
        if (heights.size != targetHeights.size) {
            targetHeights = FloatArray(heights.size)
            currentHeights = FloatArray(heights.size) { 2f }
        }
        System.arraycopy(heights, 0, targetHeights, 0, heights.size)

        val wantBars = settings.getBarCount()
        if (wantBars != barCount && viewW > 0) {
            barCount = wantBars
            onSizeChanged(viewW, viewH)
        }
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        val count = minOf(barCount, barColumns.size, currentHeights.size, targetHeights.size)

        for (i in 0 until count) {
            val target = targetHeights[i]
            val current = currentHeights[i]
            var h = current + smoothing * (target - current)
            if (h < 2f) h = 2f
            if (h > viewHeight) h = viewHeight.toFloat()
            currentHeights[i] = h
        }

        changeCounter++
        val shouldChange = changeCounter >= changeInterval
        if (shouldChange) changeCounter = 0

        val gap = settings.getBarGapPx()
        val fullBarWidth = columnWidth + gap

        if (currentColorMode == "lavalamp") {
            val time = System.currentTimeMillis()
            val hue = (time / 50) % 360
            val lavaColor = Color.HSVToColor(255, floatArrayOf(hue.toFloat(), 1f, 1f))
            updatePaintColors(lavaColor)
        }

        for (i in 0 until count) {
            val column = barColumns[i]
            val height = currentHeights[i]
            
            if (height < 10f) continue

            val x = i * fullBarWidth + columnWidth * 0.5f
            val baseY = viewHeight.toFloat()
            
            if (shouldChange && Random.nextFloat() < 0.4f) {
                column.regenerateRandomChars()
            }

            val charSpacingWithGap = charSize * 1.15f
            val numChars = (height / charSpacingWithGap).toInt().coerceIn(1, maxCharsPerColumn)
            
            column.ensureCapacity(numChars)

            val glowWidth = columnWidth * 0.85f
            val heightRatio = height / viewHeight.toFloat()
            val glowAlpha = (160 * heightRatio).toInt().coerceIn(0, 160)
            
            val r = Color.red(brightTextPaint.color)
            val g = Color.green(brightTextPaint.color)
            val b = Color.blue(brightTextPaint.color)
            glowPaint.color = Color.argb(glowAlpha, (r * 0.78f).toInt(), (g * 0.78f).toInt(), (b * 0.78f).toInt())
            
            canvas.drawRect(
                x - glowWidth / 2f,
                baseY - height,
                x + glowWidth / 2f,
                baseY,
                glowPaint
            )

            for (j in 0 until numChars) {
                val y = baseY - (j * charSpacingWithGap) - charSize * 0.25f
                val char = column.chars[j % column.chars.size]
                
                val fadeRatio = j.toFloat() / numChars.coerceAtLeast(1)
                
                val textPaint = when {
                    fadeRatio < 0.25f -> brightTextPaint
                    fadeRatio < 0.6f -> mediumTextPaint
                    else -> darkTextPaint
                }

                if (fadeRatio < 0.4f) {
                    val glowStrength = ((1f - fadeRatio * 2.5f) * 200).toInt().coerceIn(0, 200)
                    textGlowPaint.color = Color.argb(glowStrength, r, g, b)
                    canvas.drawText(char, x, y, textGlowPaint)
                }
                
                canvas.drawText(char, x, y, textPaint)
            }
        }
    }

    override fun cleanup() {
        barColumns = emptyArray()
        currentHeights = FloatArray(0)
        targetHeights = FloatArray(0)
        glowPaint.maskFilter = null
        textGlowPaint.maskFilter = null
    }

    private inner class MatrixColumn(initialCapacity: Int) {
        var chars: Array<String> = Array(initialCapacity) { numbers.random() }

        fun ensureCapacity(needed: Int) {
            if (chars.size < needed) {
                val newChars = Array(needed) { idx ->
                    if (idx < chars.size) chars[idx] else numbers.random()
                }
                chars = newChars
            }
        }

        fun regenerateRandomChars() {
            val numToChange = Random.nextInt(1, 4).coerceAtMost(chars.size)
            repeat(numToChange) {
                val idx = Random.nextInt(chars.size)
                chars[idx] = numbers.random()
            }
        }
    }
}
