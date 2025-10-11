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
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R

class EdgeLightView(context: Context) : FrameLayout(context) {

    var userStrokeWidth: Int = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = field * resources.displayMetrics.density
            invalidate()
        }

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

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

    var paintColor: Int
        get() = edgePaint.color
        set(value) {
            edgePaint.color = value
            invalidate()
        }

    var pulseRunning: Boolean
        get() = pulseAnimator?.isRunning == true
        set(value) {
            if (value && !pulseRunning) startPulse()
            else if (!value) {
                pulseAnimator?.cancel()
                visible = false
            }
        }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visible = false
    }

    private fun startPulse() {
        visible = true

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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.BUTT
        edgePaint.alpha = 255
        edgePaint.maskFilter = null
        canvas.drawLine(
            halfStroke, 0f,
            halfStroke, height.toFloat(),
            edgePaint
        )
        canvas.drawLine(
            width - halfStroke, 0f,
            width - halfStroke, height.toFloat(),
            edgePaint
        )
    }
}
