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
package com.android.systemui.edgelight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import kotlin.random.Random

class EdgeLightView(context: Context) : FrameLayout(context) {

    var userStrokeWidth: Int = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = field * resources.displayMetrics.density
            invalidate()
        }

    var edgeStyle: String = STYLE_DEFAULT
        set(value) {
            field = value
            if (useRainbowGradient) updateRainbowGradient()
            invalidate()
        }

    var animationEffect: String = EFFECT_NONE

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val totalPulseDuration =
        resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()

    var userPulseCount: Int = 3
        set(value) {
            field = value.coerceIn(1, 5)
        }

    private val fadeFraction = 0.2f
    private val MIN_SEGMENTS = 3

    private var pulseAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var effectAnimator: ValueAnimator? = null
    private var rainbowRotation: Float = 0f
    private var effectProgress: Float = 0f

    private val sparkles = mutableListOf<Sparkle>()

    private val roundedPath = Path()
    private val roundedRect = RectF()
    private var cornerRadius: Float = 0f
    private var pathLength: Float = 0f

    private var useRainbowGradient = false

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

    var paintColor: Int
        get() = edgePaint.color
        set(value) {
            if (value != COLOR_RAINBOW) {
                useRainbowGradient = false
                edgePaint.shader = null
                edgePaint.color = value
                edgePaint.alpha = 255
            } else {
                useRainbowGradient = true
                updateRainbowGradient()
            }
            invalidate()
        }

    var pulseRunning: Boolean
        get() = pulseAnimator?.isRunning == true
        set(value) {
            if (value && !pulseRunning) {
                startPulse()
                startEffectAnimation()
                startRainbowAnimation()
            } else if (!value) {
                stopRainbowAnimation()
                stopEffectAnimation()
                stopPulse()
                visible = false
            }
        }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visible = false

        cornerRadius = try {
            resources.getDimension(
                resources.getIdentifier("rounded_corner_radius", "dimen", "android")
            )
        } catch (e: Exception) {
            32f * resources.displayMetrics.density
        }

        edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
    }

    private fun startPulse() {
        visible = true
        alpha = 0f

        val totalSegments = maxOf(userPulseCount, MIN_SEGMENTS)
        val active = BooleanArray(totalSegments)

        for (i in 0 until userPulseCount) {
            val idx = (((i + 0.5f) * totalSegments) / userPulseCount).toInt()
                .coerceIn(0, totalSegments - 1)
            active[idx] = true
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, totalSegments.toFloat()).apply {
            duration = totalPulseDuration
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val segmentIndex = progress.toInt().coerceIn(0, totalSegments - 1)
                val fraction = progress - segmentIndex

                if (active[segmentIndex]) {
                    alpha = when {
                        fraction < fadeFraction -> fraction / fadeFraction
                        fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                        else -> 1f
                    }
                } else {
                    alpha = 0f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visible = false
                    pulseAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    visible = false
                    pulseAnimator = null
                }
            })
            repeatCount = 0
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (edgeStyle) {
            STYLE_ROUNDED -> drawRoundedEdges(canvas)
            else -> drawDefaultEdges(canvas)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (useRainbowGradient) {
            updateRainbowGradient()
            invalidate()
        }
    }

    private fun drawDefaultEdges(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.BUTT

        when (animationEffect) {
            EFFECT_BREATHING -> applyBreathingEffect()
            EFFECT_WAVE -> drawWaveEffect(canvas)
            EFFECT_SPARKLE -> drawSparkleEffect(canvas)
            EFFECT_CHASE -> drawChaseEffect(canvas)
            EFFECT_COMET -> drawCometEffect(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
                canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)
            }
        }
    }

    private fun drawRoundedEdges(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.ROUND

        roundedRect.set(
            halfStroke,
            halfStroke,
            width.toFloat() - halfStroke,
            height.toFloat() - halfStroke
        )

        roundedPath.reset()
        roundedPath.addRoundRect(
            roundedRect,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                canvas.drawPath(roundedPath, edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffectRounded(canvas)
            EFFECT_SPARKLE -> drawSparkleEffectRounded(canvas)
            EFFECT_CHASE -> drawChaseEffectRounded(canvas)
            EFFECT_COMET -> drawCometEffectRounded(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                canvas.drawPath(roundedPath, edgePaint)
            }
        }
    }

    private fun applyBreathingEffect() {
        val cycle = sin(effectProgress * 2 * PI).toFloat()
        val normalizedCycle = (cycle + 1f) / 2f

        edgePaint.alpha = (155 + (normalizedCycle * 100)).toInt().coerceIn(0, 255)
        edgePaint.strokeWidth = (userStrokeWidth * resources.displayMetrics.density) * (0.7f + normalizedCycle * 0.6f)
        edgePaint.maskFilter = null
    }

    private fun drawWaveEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val segments = 50
        val waveHeight = width * 0.05f

        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        for (i in 0 until segments) {
            val y1 = (i.toFloat() / segments) * height
            val y2 = ((i + 1).toFloat() / segments) * height
            val offset1 = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 2 * PI)).toFloat() * waveHeight
            val offset2 = sin((effectProgress * 2 * PI + ((i + 1).toFloat() / segments) * 2 * PI)).toFloat() * waveHeight

            canvas.drawLine(halfStroke + offset1, y1, halfStroke + offset2, y2, edgePaint)
        }

        for (i in 0 until segments) {
            val y1 = (i.toFloat() / segments) * height
            val y2 = ((i + 1).toFloat() / segments) * height
            val offset1 = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 2 * PI)).toFloat() * waveHeight
            val offset2 = sin((effectProgress * 2 * PI + ((i + 1).toFloat() / segments) * 2 * PI)).toFloat() * waveHeight

            canvas.drawLine(width - halfStroke + offset1, y1, width - halfStroke + offset2, y2, edgePaint)
        }
    }

    private fun drawWaveEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length
        val segments = 100
        val waveAmplitude = edgePaint.strokeWidth * 0.5f

        val wavePath = Path()
        for (i in 0..segments) {
            val distance = (i.toFloat() / segments) * pathLength
            val pos = FloatArray(2)
            val tan = FloatArray(2)
            measure.getPosTan(distance, pos, tan)

            val waveOffset = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 4 * PI)).toFloat() * waveAmplitude
            val normalX = -tan[1]
            val normalY = tan[0]

            val x = pos[0] + normalX * waveOffset
            val y = pos[1] + normalY * waveOffset

            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }

        canvas.drawPath(wavePath, edgePaint)
    }

    private fun drawSparkleEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.alpha = 100
        edgePaint.maskFilter = null

        canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
        canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)

        sparkles.removeAll { it.lifetime <= 0f }

        if (sparkles.size < 15 && Random.nextFloat() < 0.3f) {
            val isLeft = Random.nextBoolean()
            sparkles.add(
                Sparkle(
                    x = if (isLeft) halfStroke else width - halfStroke,
                    y = Random.nextFloat() * height,
                    lifetime = 1f,
                    maxSize = edgePaint.strokeWidth * (2f + Random.nextFloat() * 2f)
                )
            )
        }

        val sparklePaint = Paint(edgePaint).apply { strokeCap = Paint.Cap.ROUND }
        sparkles.forEach { sparkle ->
            sparkle.lifetime -= 0.03f
            val alpha = (sparkle.lifetime * 255).toInt().coerceIn(0, 255)
            val size = sparkle.maxSize * sin(sparkle.lifetime * PI).toFloat()

            sparklePaint.alpha = alpha
            sparklePaint.strokeWidth = size
            canvas.drawPoint(sparkle.x, sparkle.y, sparklePaint)
        }
    }

    private fun drawSparkleEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 100
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length

        sparkles.removeAll { it.lifetime <= 0f }

        if (sparkles.size < 20 && Random.nextFloat() < 0.3f) {
            val distance = Random.nextFloat() * pathLength
            val pos = FloatArray(2)
            measure.getPosTan(distance, pos, null)

            sparkles.add(
                Sparkle(
                    x = pos[0],
                    y = pos[1],
                    lifetime = 1f,
                    maxSize = edgePaint.strokeWidth * (2f + Random.nextFloat() * 2f)
                )
            )
        }

        val sparklePaint = Paint(edgePaint).apply { strokeCap = Paint.Cap.ROUND }
        sparkles.forEach { sparkle ->
            sparkle.lifetime -= 0.03f
            val alpha = (sparkle.lifetime * 255).toInt().coerceIn(0, 255)
            val size = sparkle.maxSize * sin(sparkle.lifetime * PI).toFloat()

            sparklePaint.alpha = alpha
            sparklePaint.strokeWidth = size
            canvas.drawPoint(sparkle.x, sparkle.y, sparklePaint)
        }
    }

    private fun drawChaseEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val segmentLength = height * 0.15f
        val numChasers = 3

        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
        canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)

        for (i in 0 until numChasers) {
            val offset = (effectProgress + i.toFloat() / numChasers) % 1f
            val centerY = offset * height

            val gradient = LinearGradient(
                0f, centerY - segmentLength,
                0f, centerY + segmentLength,
                intArrayOf(
                    0x00000000 or (edgePaint.color and 0x00FFFFFF),
                    edgePaint.color,
                    0x00000000 or (edgePaint.color and 0x00FFFFFF)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            val chaserPaint = Paint(edgePaint).apply {
                shader = gradient
                alpha = 255
            }

            canvas.drawLine(
                halfStroke,
                (centerY - segmentLength).coerceAtLeast(0f),
                halfStroke,
                (centerY + segmentLength).coerceAtMost(height.toFloat()),
                chaserPaint
            )
            canvas.drawLine(
                width - halfStroke,
                (centerY - segmentLength).coerceAtLeast(0f),
                width - halfStroke,
                (centerY + segmentLength).coerceAtMost(height.toFloat()),
                chaserPaint
            )
        }
    }

    private fun drawChaseEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length
        val segmentLength = pathLength * 0.15f
        val numChasers = 3

        for (i in 0 until numChasers) {
            val offset = (effectProgress + i.toFloat() / numChasers) % 1f
            val centerDistance = offset * pathLength

            val chaserPath = Path()
            val startDist = (centerDistance - segmentLength).coerceAtLeast(0f)
            val endDist = (centerDistance + segmentLength).coerceAtMost(pathLength)

            measure.getSegment(startDist, endDist, chaserPath, true)

            val chaserPaint = Paint(edgePaint).apply { alpha = 255 }
            canvas.drawPath(chaserPath, chaserPaint)
        }
    }

    private fun drawCometEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val tailLength = height * 0.25f

        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
        canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)

        val cometY = effectProgress * height

        val gradient = LinearGradient(
            0f,
            (cometY - tailLength).coerceAtLeast(0f),
            0f,
            cometY,
            intArrayOf(0x00000000 or (edgePaint.color and 0x00FFFFFF), edgePaint.color),
            null,
            Shader.TileMode.CLAMP
        )

        val cometPaint = Paint(edgePaint).apply {
            shader = gradient
            alpha = 255
            strokeWidth = edgePaint.strokeWidth * 1.5f
        }

        canvas.drawLine(
            halfStroke,
            (cometY - tailLength).coerceAtLeast(0f),
            halfStroke,
            cometY,
            cometPaint
        )
        canvas.drawLine(
            width - halfStroke,
            (cometY - tailLength).coerceAtLeast(0f),
            width - halfStroke,
            cometY,
            cometPaint
        )
    }

    private fun drawCometEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        val measure = PathMeasure(roundedPath, false)
        pathLength = measure.length
        val tailLength = pathLength * 0.2f
        val cometDistance = effectProgress * pathLength

        val cometPath = Path()
        val startDist = (cometDistance - tailLength).coerceAtLeast(0f)
        measure.getSegment(startDist, cometDistance, cometPath, true)

        val cometPaint = Paint(edgePaint).apply {
            alpha = 255
            strokeWidth = edgePaint.strokeWidth * 1.5f
        }
        canvas.drawPath(cometPath, cometPaint)
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            post { _updateRainbowGradient() }
        } else {
            _updateRainbowGradient()
        }
    }

    private fun _updateRainbowGradient() {
        edgePaint.shader = when (edgeStyle) {
            STYLE_ROUNDED -> {
                val matrix = Matrix()
                matrix.postRotate(rainbowRotation, width / 2f, height / 2f)
                SweepGradient(width / 2f, height / 2f, RAINBOW, null).also {
                    it.setLocalMatrix(matrix)
                }
            }
            else -> {
                val offset = (rainbowRotation / 360f) * height
                LinearGradient(
                    0f, -offset, 0f, height.toFloat() - offset,
                    RAINBOW, null, Shader.TileMode.REPEAT
                )
            }
        }
    }

    private fun startRainbowAnimation() {
        if (!useRainbowGradient || edgePaint.shader == null) return
        if (animationEffect in MOVING_EFFECT) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                rainbowRotation = animator.animatedValue as Float
                _updateRainbowGradient()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }

                override fun onAnimationCancel(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }
            })
            start()
        }
    }

    private fun stopRainbowAnimation() {
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        rainbowRotation = 0f
    }

    private fun startEffectAnimation() {
        if (animationEffect == EFFECT_NONE) return
        if (effectAnimator?.isRunning == true) return

        val duration = when (animationEffect) {
            EFFECT_BREATHING -> 3000L
            EFFECT_WAVE -> 2000L
            EFFECT_SPARKLE -> 100L
            EFFECT_CHASE -> 2500L
            EFFECT_COMET -> 2000L
            else -> 2000L
        }

        effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                effectProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    effectAnimator = null
                    effectProgress = 0f
                    sparkles.clear()
                }

                override fun onAnimationCancel(animation: Animator) {
                    effectAnimator = null
                    effectProgress = 0f
                    sparkles.clear()
                }
            })
            start()
        }
    }

    private fun stopEffectAnimation() {
        effectAnimator?.cancel()
        effectAnimator = null
        effectProgress = 0f
        sparkles.clear()
    }

    private data class Sparkle(
        var x: Float,
        var y: Float,
        var lifetime: Float,
        val maxSize: Float
    )

    companion object {
        const val STYLE_DEFAULT = "default"
        const val STYLE_ROUNDED = "rounded"
        const val COLOR_RAINBOW = -1
        const val EFFECT_NONE = "none"
        const val EFFECT_BREATHING = "breathing"
        const val EFFECT_WAVE = "wave"
        const val EFFECT_SPARKLE = "sparkle"
        const val EFFECT_CHASE = "chase"
        const val EFFECT_COMET = "comet"
        private val MOVING_EFFECT = arrayOf(EFFECT_WAVE, EFFECT_SPARKLE, EFFECT_CHASE, EFFECT_COMET)
        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(),
            0xFF9400D3.toInt(), 0xFFFF0000.toInt()
        )
    }
}
