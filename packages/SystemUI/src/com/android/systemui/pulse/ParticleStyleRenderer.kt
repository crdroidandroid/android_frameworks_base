/*
 * Copyright (C) 2026 crDroid Android Project
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

internal class ParticleStyleRenderer(
    private val settings: PulseSettingsRepository
) : PulseStyleRenderer {

    internal class Particle {
        @JvmField var x: Float = 0f
        @JvmField var y: Float = 0f
        @JvmField var vx: Float = 0f
        @JvmField var vy: Float = 0f
        @JvmField var size: Float = 0f
        @JvmField var life: Float = 1f
        @JvmField var color: Int = Color.WHITE
        @JvmField var bassReactive: Boolean = false
        @JvmField var midReactive: Boolean = false
        @JvmField var trebleReactive: Boolean = false
        @JvmField var alpha: Int = 0xFF
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1f
    }

    private val particles = ArrayList<Particle>()
    private val random = Random.Default

    private var baseColorArgb: Int = Color.WHITE

    private var bassIntensity: Float = 0f
    private var midIntensity: Float = 0f
    private var trebleIntensity: Float = 0f
    private var audioIntensity: Float = 0f

    private val gravity: Float = 0.04f
    private val damping: Float = 0.99f
    private var showTrails: Boolean = true

    private var viewW: Int = 0
    private var viewH: Int = 0

    override fun onSizeChanged(viewWidth: Int, viewHeight: Int) {
        viewW = viewWidth
        viewH = viewHeight

        particles.clear()
        repeat(INITIAL_PARTICLES) { spawnParticle() }

        trailPaint.strokeWidth = settings.displayDensity() * 1.5f
    }

    override fun onColor(color: Int) {
        baseColorArgb = color

        // Recolor every existing particle so the swap is immediate
        for (i in particles.indices) {
            val p = particles[i]
            p.color = particleColorFromBase(p)
        }
    }

    override fun onData(heights: FloatArray) {
        if (heights.isEmpty()) return

        if (heights.size >= 4) {
            val bassEnd = max(1, min(heights.size / 4, heights.size))
            val midEnd = max(bassEnd + 1, min((heights.size * 3) / 4, heights.size))

            // Peak across the whole array — used to normalize each band's mean.
            var peak = 1f
            for (h in heights) if (h > peak) peak = h

            bassIntensity =
                bandMean(heights, peak, 0, bassEnd).coerceIn(0f, 2f).coerceAtMost(1f)
            midIntensity =
                bandMean(heights, peak, bassEnd, midEnd).coerceIn(0f, 2f).coerceAtMost(1f)
            trebleIntensity =
                bandMean(heights, peak, midEnd, heights.size).coerceIn(0f, 2f).coerceAtMost(1f)
            audioIntensity =
                ((bassIntensity + midIntensity + trebleIntensity) / 3f).coerceIn(0f, 1f)
        }

        if (audioIntensity > AUDIO_GATE && particles.size < MAX_PARTICLES) {
            val spawnFactor = SPAWN_FACTOR_DEFAULT
            if (random.nextFloat() < spawnFactor * audioIntensity) {
                spawnParticle()
            }
            val burstCount = (audioIntensity * BURST_SPAWN_MAX).toInt()
            var s = 0
            while (s < burstCount && particles.size < MAX_PARTICLES) {
                spawnParticle()
                s++
            }
        }
    }

    override fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        if (particles.isEmpty()) return

        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()

        val n = particles.size
        var i = 0
        while (i < n) {
            val p = particles[i]
            i++

            p.vy += gravity

            if (p.bassReactive && bassIntensity > AUDIO_GATE) {
                p.vy -= bassIntensity * 2f
                p.vx += (random.nextFloat() - 0.5f) * bassIntensity
            }

            if (p.midReactive && midIntensity > AUDIO_GATE) {
                p.vx += (random.nextFloat() - 0.5f) * midIntensity
                p.vy += (random.nextFloat() - 0.5f) * midIntensity
            }

            if (p.trebleReactive && trebleIntensity > AUDIO_GATE) {
                val boost = 1f + 0.1f * trebleIntensity
                p.vx *= boost
                p.vy *= boost
            }

            p.vx *= damping
            p.vy *= damping
            p.x += p.vx
            p.y += p.vy

            p.life -= LIFE_DECAY
            p.alpha = (p.life * 255f).toInt().coerceIn(0, 255)

            if (p.x < 0f || p.x > w) {
                p.vx *= -0.8f
                p.x = p.x.coerceIn(0f, w)
            }
            if (p.y < 0f || p.y > h) {
                p.vy *= -0.8f
                p.y = p.y.coerceIn(0f, h)
            }
        }

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.life <= 0f || p.alpha <= 0) it.remove()
        }

        if (showTrails) {
            for (j in particles.indices) {
                val p = particles[j]
                if (p.life <= 0f || p.alpha <= 0) continue

                val velMagSq = p.vx * p.vx + p.vy * p.vy
                if (velMagSq < 0.04f) continue  // skip near-stationary particles

                val velMag = sqrt(velMagSq)
                val trailLen = min(TRAIL_MAX_LEN, velMag * TRAIL_FACTOR)
                if (trailLen < 1f) continue

                val invMag = 1f / velMag
                val tx = p.x - p.vx * trailLen * invMag
                val ty = p.y - p.vy * trailLen * invMag

                val trailAlpha = (p.alpha / 2).coerceIn(0, 0xC0)
                val r = Color.red(p.color)
                val g = Color.green(p.color)
                val b = Color.blue(p.color)
                trailPaint.color = Color.argb(trailAlpha, r, g, b)
                canvas.drawLine(p.x, p.y, tx, ty, trailPaint)
            }
        }

        for (j in particles.indices) {
            val p = particles[j]
            if (p.life <= 0f || p.alpha <= 0) continue

            val a = p.alpha.coerceIn(0, 255)
            particlePaint.color = p.color

            particlePaint.alpha = (a / 5).coerceAtLeast(0)
            canvas.drawCircle(p.x, p.y, p.size * 1.9f, particlePaint)

            particlePaint.alpha = (a / 2).coerceAtLeast(0)
            canvas.drawCircle(p.x, p.y, p.size * 1.3f, particlePaint)

            particlePaint.alpha = a
            canvas.drawCircle(p.x, p.y, p.size, particlePaint)
        }
        particlePaint.alpha = 0xFF
    }

    override fun cleanup() {
        particles.clear()
    }

    private fun spawnParticle() {
        if (viewW <= 0 || viewH <= 0) return
        if (particles.size >= MAX_PARTICLES) return

        val density = settings.displayDensity()
        val sizeBase = density * 3f

        val p = Particle().apply {
            x = random.nextFloat() * viewW.toFloat()
            y = random.nextFloat() * viewH.toFloat()
            vx = (random.nextFloat() - 0.5f) * 4f
            vy = (random.nextFloat() - 0.5f) * 4f
            size = random.nextFloat() * 2f + sizeBase
            life = 1f
            // Each particle gets randomly assigned to react to one or more bands
            bassReactive = random.nextFloat() < 0.3f
            midReactive = random.nextFloat() < 0.4f
            trebleReactive = random.nextFloat() < 0.3f
            alpha = 0xFF
        }
        p.color = particleColorFromBase(p)
        particles.add(p)
    }

    private fun particleColorFromBase(p: Particle): Int {
        val r = Color.red(baseColorArgb)
        val g = Color.green(baseColorArgb)
        val b = Color.blue(baseColorArgb)
        return when {
            p.bassReactive -> Color.argb(
                0xFF,
                min(0xFF, r + 0x32),
                g,
                max(0, b - 0x1E)
            )
            p.midReactive -> Color.argb(
                0xFF,
                r,
                min(0xFF, g + 0x1E),
                b
            )
            p.trebleReactive -> Color.argb(
                0xFF,
                max(0, r - 0x1E),
                g,
                min(0xFF, b + 0x32)
            )
            else -> baseColorArgb
        }
    }

    private fun bandMean(data: FloatArray, peak: Float, from: Int, to: Int): Float {
        val end = min(to, data.size)
        var sum = 0f
        var count = 0
        var i = from
        while (i < end) {
            sum += data[i]
            count++
            i++
        }
        if (count <= 0 || peak <= 0f) return 0f
        return (sum / count.toFloat()) / peak
    }

    companion object {
        private const val INITIAL_PARTICLES = 150
        private const val MAX_PARTICLES = 300
        private const val LIFE_DECAY = 0.012f
        private const val AUDIO_GATE = 0.08f
        private const val SPAWN_FACTOR_DEFAULT = 0.42f
        private const val BURST_SPAWN_MAX = 5
        private const val TRAIL_FACTOR = 12f
        private const val TRAIL_MAX_LEN = 35f
    }
}
