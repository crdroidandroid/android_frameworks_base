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
import android.graphics.RectF
import android.graphics.BlurMaskFilter
import kotlin.math.max
import kotlin.random.Random

internal class SparkleStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var viewW = 0
    private var viewH = 0
    private var barCount = 0
    private var spacing = 0f
    private var baseY = 0f

    private var currentHeights = FloatArray(0)
    private var targetHeights = FloatArray(0)

    private var sparkles: Array<Sparkle> = emptyArray()
    private var poolTop = 0

    private var lastColor = 0
    private var rgbR = 255
    private var rgbG = 255
    private var rgbB = 255

    private val smoothing = 0.25f
    private val maxSparklesPerBar = 10
    private val sparkleBaseSize = 2.5f
    private val sparkleVarSize = 3.5f
    private val sparkleMinVy = 60f
    private val sparkleVarVy = 220f
    private val sparkleGravity = -20f
    private val sparkleFade = 1.4f
    private val densityScale = 1.0f

    private var lastDrawUptimeMs: Long = 0L

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        viewW = viewWidth
        viewH = viewHeight
        baseY = viewH.toFloat()
        barCount = settings.getBarCount()
        spacing = computeSpacing(viewW, barCount)

        ensureArrays(barCount)

        val desiredPool = (barCount * maxSparklesPerBar * 3).coerceAtLeast(128)
        if (sparkles.size != desiredPool) {
            sparkles = Array(desiredPool) { Sparkle() }
            poolTop = 0
        }
    }

    override fun onColor(color: Int) {
        if (color != lastColor) {
            lastColor = color
            rgbR = Color.red(color)
            rgbG = Color.green(color)
            rgbB = Color.blue(color)
        }
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
            spacing = computeSpacing(viewW, barCount)
            ensureArrays(barCount)
        }
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        // Delta time
        val now = android.os.SystemClock.uptimeMillis()
        val dt = ((now - lastDrawUptimeMs).takeIf { lastDrawUptimeMs != 0L } ?: 16L) / 1000f
        lastDrawUptimeMs = now

        val count = minOf(barCount, currentHeights.size, targetHeights.size)
        for (i in 0 until count) {
            val target = targetHeights[i]
            val current = currentHeights[i]
            var h = current + smoothing * (target - current)
            if (h < 2f) h = 2f
            if (h > viewH) h = viewH.toFloat()
            currentHeights[i] = h
        }

        for (i in 0 until count) {
            val energy = currentHeights[i] / max(2f, viewH.toFloat()) // 0..1
            if (energy <= 0f) continue

            val emit = (energy * maxSparklesPerBar * densityScale)
            var toSpawn = emit.toInt()
            val fractional = emit - toSpawn
            if (Random.nextFloat() < fractional) toSpawn++

            if (toSpawn > 0) spawnFromBar(i, toSpawn, energy)
        }

        val pr = sparklePaint
        val tr = trailPaint
        val baseAlpha = (Color.alpha(lastColor)).coerceIn(0, 255)

        for (idx in sparkles.indices) {
            val p = sparkles[idx]
            if (!p.alive) continue

            p.vy += sparkleGravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= sparkleFade * dt

            if (p.life <= 0f || p.y + p.size < 0f || p.x < -8f || p.x > viewW + 8f) {
                p.alive = false
                poolTop = minOf(poolTop, idx)
                continue
            }

            val alpha = (baseAlpha * p.life).toInt().coerceIn(0, 255)
            pr.color = Color.argb(alpha, rgbR, rgbG, rgbB)
            tr.color = Color.argb((alpha * 0.6f).toInt().coerceIn(0, 255), rgbR, rgbG, rgbB)

            // Simple trail as vertical rect behind sparkle
            val tailH = (p.vy * -0.03f).coerceAtLeast(0f)
            if (tailH > 0f) {
                canvas.drawRect(p.x - p.size * 0.35f, p.y - tailH, p.x + p.size * 0.35f, p.y, tr)
            }
            canvas.drawCircle(p.x, p.y, p.size, pr)
        }
    }

    override fun cleanup() {
        sparkles = emptyArray()
        poolTop = 0
        currentHeights = FloatArray(0)
        targetHeights = FloatArray(0)
        sparklePaint.maskFilter = null
    }

    private fun ensureArrays(targetBars: Int) {
        if (currentHeights.size != targetBars) currentHeights = FloatArray(targetBars) { 2f }
        if (targetHeights.size != targetBars) targetHeights = FloatArray(targetBars) { 2f }
    }

    private fun computeSpacing(width: Int, bars: Int): Float {
        return if (bars > 0) width.toFloat() / bars else width.toFloat()
    }

    private fun spawnFromBar(barIndex: Int, count: Int, energy: Float) {
        val centerX = (barIndex + 0.5f) * spacing
        val halfSpan = spacing * 0.45f
        val speed = sparkleMinVy + sparkleVarVy * energy
        val sizeBase = sparkleBaseSize + sparkleVarSize * energy

        for (n in 0 until count) {
            val p = obtainSparkle() ?: return
            p.alive = true
            p.size = (sizeBase * (0.7f + 0.6f * Random.nextFloat()))
            p.x = centerX + (Random.nextFloat() - 0.5f) * halfSpan
            p.y = baseY - currentHeights.getOrElse(barIndex) { 2f }
            p.vx = (Random.nextFloat() - 0.5f) * (40f + 50f * energy)
            p.vy = -(speed * (0.6f + 0.8f * Random.nextFloat()))
            p.life = 0.65f + 0.7f * energy
        }
    }

    private fun obtainSparkle(): Sparkle? {
        for (i in poolTop until sparkles.size) {
            if (!sparkles[i].alive) {
                poolTop = i + 1
                return sparkles[i]
            }
        }
        for (i in 0 until poolTop.coerceAtMost(sparkles.size)) {
            if (!sparkles[i].alive) {
                poolTop = i + 1
                return sparkles[i]
            }
        }
        return null
    }

    private class Sparkle {
        var alive = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 0f
        var life = 0f
    }
}
