/*
 * Copyright (C) 2022 FlamingoOS Project
 * Copyright (C) 2023-2025 the risingOS Android Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.animation.AnimatorListenerAdapter
import android.animation.Animator

class EdgeLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleRes: Int = 0,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleRes, defStyleAttr) {

    private val edgeLightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var animating = false
    private var repeatCount = ValueAnimator.INFINITE
    private var animationDuration = 3000L

    private var animationProgress = 0f
    private var edgeAnimator: ValueAnimator? = null

    private val pathSegments = mutableListOf<Float>()
    private var totalPathLength = 0f
    
    private var edgeLightStyle = 0
    private var blinkAnimator: ValueAnimator? = null

    private var rainbowMode = false
    private val rainbowColors = listOf(
        Color.RED,
        Color.parseColor("#FF7F00"),
        Color.YELLOW,
        Color.GREEN,
        Color.BLUE,
        Color.parseColor("#4B0082"),
        Color.parseColor("#8A2BE2")
    )

    init {
        calculatePathSegments()
    }

    private fun calculatePathSegments() {
        pathSegments.clear()
        totalPathLength = 0f

        val width = width.toFloat()
        val height = height.toFloat()
        val padding = edgeLightPaint.strokeWidth / 2

        val segments = listOf(
            floatArrayOf(width - padding, padding, width - padding, height - padding), // Right
            floatArrayOf(width - padding, height - padding, padding, height - padding), // Bottom
            floatArrayOf(padding, height - padding, padding, padding), // Left
            floatArrayOf(padding, padding, width - padding, padding) // Top
        )

        segments.forEach { segment ->
            pathSegments.addAll(segment.toList())
            val length = calculateSegmentLength(segment[0], segment[1], segment[2], segment[3])
            totalPathLength += length
        }
    }

    private fun calculateSegmentLength(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculatePathSegments()
    }

    fun startAnimation() {
        if (animating) return
        if (edgeLightStyle == 0) {
            startEdgeLineAnimation()
        } else if (edgeLightStyle == 1) {
            startPulseAnimation()
        }
        animating = true
    }
    
    private fun startEdgeLineAnimation() {
        edgeAnimator = ValueAnimator.ofFloat(0f, totalPathLength).apply {
            duration = animationDuration
            repeatCount = this@EdgeLightView.repeatCount
            interpolator = LinearInterpolator()
            val animatorListener = object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    val totalProgress = (animation as ValueAnimator).animatedValue as Float
                    animationProgress = totalProgress % totalPathLength
                }
            }
            addListener(animatorListener)
            addUpdateListener { animator ->
                animationProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startPulseAnimation() {
        blinkAnimator = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = animationDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                edgeLightPaint.alpha = (alpha * 255).toInt()
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        edgeAnimator?.cancel()
        blinkAnimator?.cancel()
        edgeAnimator = null
        blinkAnimator = null
        animating = false
        animationProgress = 0f
        invalidate()
    }

    fun setEdgeLightStyle(style: Int) {
        edgeLightStyle = style
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!animating) return

        when (edgeLightStyle) {
            0 -> drawEdgeLight(canvas)
            1 -> drawFullEdgeLight(canvas)
        }
    }

    private fun drawFullEdgeLight(canvas: Canvas) {
        for (i in pathSegments.indices step 4) {
            val x1 = pathSegments[i]
            val y1 = pathSegments[i + 1]
            val x2 = pathSegments[i + 2]
            val y2 = pathSegments[i + 3]
            canvas.drawLine(x1, y1, x2, y2, edgeLightPaint)
        }
    }

    private fun drawEdgeLight(canvas: Canvas) {
        if (pathSegments.isEmpty() || totalPathLength == 0f) return

        var tracedLength = 0f
        var progressAccumulator = 0f

        if (rainbowMode) {
            val colorStops = mutableListOf<Float>()
            val colors = mutableListOf<Int>()
            var accumulatedLength = 0f
            for (i in pathSegments.indices step 4) {
                val x1 = pathSegments[i]
                val y1 = pathSegments[i + 1]
                val x2 = pathSegments[i + 2]
                val y2 = pathSegments[i + 3]
                
                val segmentLength = calculateSegmentLength(x1, y1, x2, y2)
                accumulatedLength += segmentLength

                val fraction = accumulatedLength / totalPathLength
                colorStops.add(fraction)
                colors.add(rainbowColors[(fraction * rainbowColors.size).toInt() % rainbowColors.size])
            }
        }

        for (i in pathSegments.indices step 4) {
            val x1 = pathSegments[i]
            val y1 = pathSegments[i + 1]
            val x2 = pathSegments[i + 2]
            val y2 = pathSegments[i + 3]

            val segmentLength = calculateSegmentLength(x1, y1, x2, y2)
            progressAccumulator += segmentLength

            if (animationProgress >= progressAccumulator) {
                canvas.drawLine(x1, y1, x2, y2, edgeLightPaint)
            } else {
                val progressWithinSegment = (animationProgress - (progressAccumulator - segmentLength)) / segmentLength
                val endX = x1 + (x2 - x1) * progressWithinSegment
                val endY = y1 + (y2 - y1) * progressWithinSegment
                canvas.drawLine(x1, y1, endX, endY, edgeLightPaint)
                break
            }
        }
    }

    fun show() {
        if (animating) return
        if (visibility == GONE) visibility = VISIBLE
        startAnimation()
    }

    fun hide() {
        if (visibility == VISIBLE) visibility = GONE
        if (!animating) return
        stopAnimation()
    }

    fun setColor(color: Int) {
        when {
            color == -1 -> {
                rainbowMode = true
                edgeLightPaint.shader = createGradientShader()
            }
            else -> {
                rainbowMode = false
                edgeLightPaint.color = color
                edgeLightPaint.shader = null
            }
        }
        invalidate()
    }

    fun setRepeatCount(repeatCount: Int) {
        this.repeatCount = repeatCount
    }

    fun setAnimationDuration(duration: Long) {
        this.animationDuration = duration
        if (animating) {
            stopAnimation()
            startAnimation()
        }
    }

    fun setStrokeWidth(width: Float) {
        edgeLightPaint.strokeWidth = width
        calculatePathSegments()
        invalidate()
    }

    private fun createGradientShader(): LinearGradient {
        return LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            rainbowColors.toIntArray(),
            null,
            Shader.TileMode.CLAMP
        )
    }
}
