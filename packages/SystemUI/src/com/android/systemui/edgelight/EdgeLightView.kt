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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R

class EdgeLightView(context: Context) : FrameLayout(context) {

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EDGE_WIDTH.toFloat()
        strokeCap = Paint.Cap.BUTT
    }

    private val totalPulseDuration = resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()
    private val pulseCount = 3
    private val singlePulseDuration = totalPulseDuration / pulseCount

    private val fadeFraction = 0.2f
    private val holdFraction = 1f - 2 * fadeFraction
    private val fadeDuration = (singlePulseDuration * fadeFraction).toLong()
    private val holdDuration = (singlePulseDuration * holdFraction).toLong()

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
        setWillNotDraw(false)
        visible = false
    }

    private fun startPulse() {
        visible = true
        pulseAnimator = ValueAnimator.ofFloat(0f, pulseCount.toFloat()).apply {
            duration = totalPulseDuration
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val pulseIndex = progress.toInt()
                val fraction = progress - pulseIndex
                alpha = when {
                    fraction < fadeFraction -> fraction / fadeFraction
                    fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                    else -> 1f
                }
            }
            repeatCount = 0
            start()
        }
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

    companion object {
        private const val EDGE_WIDTH = 16
    }
}
