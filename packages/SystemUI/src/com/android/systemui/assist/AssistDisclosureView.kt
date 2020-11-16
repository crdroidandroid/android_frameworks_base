/*
 * Copyright 2020, Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.assist

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityEvent

import com.android.systemui.Interpolators
import com.android.systemui.R
import com.android.systemui.util.leak.RotationUtils

/**
 * View that is responsible for showing the "screen flash" effect when triggering the assistant
 */
class AssistDisclosureView(context: Context) : View(context), ValueAnimator.AnimatorUpdateListener {

    var onFinishedListener: OnFinishedListener? = null

    private var paintAlpha = 0

    private val thickness = resources.getDimension(R.dimen.assist_disclosure_thickness)
    private val shadowThickness = resources.getDimension(R.dimen.assist_disclosure_shadow_thickness)

    private val radius = resources.getDimension(com.android.internal.R.dimen.rounded_corner_radius)
    private val radiusTop = resources.getDimension(com.android.internal.R.dimen.rounded_corner_radius_top)
    private val radiusBottom = resources.getDimension(com.android.internal.R.dimen.rounded_corner_radius_bottom)

    private val srcMode = PorterDuffXfermode(PorterDuff.Mode.SRC)

    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = thickness

        xfermode = srcMode
        setAntiAlias(true)
    }
    private val shadowPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = shadowThickness

        xfermode = srcMode
        setAntiAlias(true)
    }

    private val path by lazy { createPath(thickness) }
    private val shadowPath by lazy { createPath(thickness + shadowThickness) }

    private val alphaInAnimator: ValueAnimator = ValueAnimator
        .ofInt(0, FULL_ALPHA)
        .setDuration(ALPHA_IN_ANIMATION_DURATION).apply {
            addUpdateListener(this@AssistDisclosureView)
            setInterpolator(Interpolators.CUSTOM_40_40)
        }
    private val alphaOutAnimator: ValueAnimator = ValueAnimator
        .ofInt(FULL_ALPHA, 0)
        .setDuration(ALPHA_OUT_ANIMATION_DURATION).apply {
            addUpdateListener(this@AssistDisclosureView)
            setInterpolator(Interpolators.CUSTOM_40_40)
        }

    private val animator = AnimatorSet().apply {
        play(alphaInAnimator).before(alphaOutAnimator)
        addListener(object : AnimatorListenerAdapter() {
            var cancelled: Boolean = false

            override fun onAnimationStart(animation: Animator) {
                cancelled = false
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled != true) onFinishedListener?.onFinished()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        startAnimation()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        animator.cancel()
        paintAlpha = 0
    }

    override fun onDraw(canvas: Canvas) {
        shadowPaint.setAlpha(paintAlpha / 4)
        paint.setAlpha(paintAlpha)

        canvas.drawPath(shadowPath, shadowPaint)
        canvas.drawPath(path, paint)
    }

    override fun onAnimationUpdate(animation: ValueAnimator) {
        if (animation === alphaOutAnimator) {
            paintAlpha = alphaOutAnimator.animatedValue as Int
        } else if (animation === alphaInAnimator) {
            paintAlpha = alphaInAnimator.animatedValue as Int
        }

        invalidate()
    }

    inline fun setOnFinishListener(crossinline listener: () -> Unit) {
        onFinishedListener = object : OnFinishedListener {
            override fun onFinished() = listener()
        }
    }

    private fun startAnimation() {
        animator.cancel()
        animator.start()
    }

    private fun createPath(thickness: Float): Path {
        val inset = thickness / 2F
        val rect = RectF(inset, inset, width - inset, height - inset)

        return Path().apply {
            addRoundRect(rect, resolveCorners(), Path.Direction.CW)
        }
    }

    private fun resolveCorners(): FloatArray {
        var topL = radiusTop.fallbackTo(radius)
        var topR = radiusTop.fallbackTo(radius)
        var bottomL = radiusBottom.fallbackTo(radius)
        var bottomR = radiusBottom.fallbackTo(radius)

        when (RotationUtils.getExactRotation(context)) {
            RotationUtils.ROTATION_LANDSCAPE -> {
                topR = radiusBottom.fallbackTo(radius)
                bottomL = radiusTop.fallbackTo(radius)
            }
            RotationUtils.ROTATION_UPSIDE_DOWN -> {
                topL = radiusBottom.fallbackTo(radius)
                topR = radiusBottom.fallbackTo(radius)
                bottomL = radiusTop.fallbackTo(radius)
                bottomR = radiusTop.fallbackTo(radius)
            }
            RotationUtils.ROTATION_SEASCAPE -> {
                topL = radiusBottom.fallbackTo(radius)
                bottomR = radiusTop.fallbackTo(radius)
            }
        }

        return floatArrayOf(
            topL, topL,
            topR, topR,
            bottomR, bottomR,
            bottomL, bottomL
        )
    }

    companion object {
        const val FULL_ALPHA = 222 // 87%
        const val ALPHA_IN_ANIMATION_DURATION = 400L
        const val ALPHA_OUT_ANIMATION_DURATION = 300L

        private fun Float.fallbackTo(`val`: Float): Float = takeUnless { it == 0F } ?: `val`
    }

    interface OnFinishedListener {
        fun onFinished()
    }

}
