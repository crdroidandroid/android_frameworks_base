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

package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.util.MathUtils
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator

import com.android.app.animation.Interpolators

import com.android.systemui.res.R
import com.android.systemui.assist.ui.CircularCornerPathRenderer
import com.android.systemui.assist.ui.DisplayUtils
import com.android.systemui.assist.ui.EdgeLight
import com.android.systemui.assist.ui.PerimeterPathGuide

import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AXRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "AXRippleView"
        private const val DECODE_BITMAP_MAX_THREAD_POOL = 2
        private const val RES_END_INDEX = 40
        // list the res ids since SystemUI optimization breaks getIdentifier
        private val RIPPLE_RES_IDS = intArrayOf(
            R.drawable.nt_charge_ripple_01,
            R.drawable.nt_charge_ripple_02,
            R.drawable.nt_charge_ripple_03,
            R.drawable.nt_charge_ripple_03,
            R.drawable.nt_charge_ripple_04,
            R.drawable.nt_charge_ripple_05,
            R.drawable.nt_charge_ripple_06,
            R.drawable.nt_charge_ripple_07,
            R.drawable.nt_charge_ripple_08,
            R.drawable.nt_charge_ripple_09,
            R.drawable.nt_charge_ripple_10,
            R.drawable.nt_charge_ripple_11,
            R.drawable.nt_charge_ripple_12,
            R.drawable.nt_charge_ripple_13,
            R.drawable.nt_charge_ripple_14,
            R.drawable.nt_charge_ripple_15,
            R.drawable.nt_charge_ripple_16,
            R.drawable.nt_charge_ripple_17,
            R.drawable.nt_charge_ripple_18,
            R.drawable.nt_charge_ripple_19,
            R.drawable.nt_charge_ripple_20,
            R.drawable.nt_charge_ripple_21,
            R.drawable.nt_charge_ripple_22,
            R.drawable.nt_charge_ripple_23,
            R.drawable.nt_charge_ripple_24,
            R.drawable.nt_charge_ripple_25,
            R.drawable.nt_charge_ripple_26,
            R.drawable.nt_charge_ripple_27,
            R.drawable.nt_charge_ripple_28,
            R.drawable.nt_charge_ripple_29,
            R.drawable.nt_charge_ripple_30,
            R.drawable.nt_charge_ripple_31,
            R.drawable.nt_charge_ripple_32,
            R.drawable.nt_charge_ripple_33,
            R.drawable.nt_charge_ripple_34,
            R.drawable.nt_charge_ripple_35,
            R.drawable.nt_charge_ripple_36,
            R.drawable.nt_charge_ripple_37,
            R.drawable.nt_charge_ripple_38,
            R.drawable.nt_charge_ripple_39,
            R.drawable.nt_charge_ripple_40,
            R.drawable.nt_charge_ripple_41
        )
    }

    private val ripplePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
    }

    private val animator = ValueAnimator.ofInt(0, RES_END_INDEX).apply {
        duration = 800L
        interpolator = Interpolators.LINEAR
    }

    private val darkOverlayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300L
        interpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
    }

    private val darkOverlayReverseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 800L
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    private val outlineStep1Animator = ValueAnimator.ofFloat(0f, 0.1f).apply {
        duration = 300L
        interpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
    }

    private val outlineStep2Animator = ValueAnimator.ofFloat(0.1f, 1f).apply {
        duration = 800L
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    private val invocationLights = ArrayList<EdgeLight>().apply {
        repeat(2) { add(EdgeLight(0, 0f, 0f)) }
    }

    private var currentAlpha = 0f
    private var currentIndex = 0
    private var strokeWidth = DisplayUtils.convertDpToPx(4f, context)
    private var images: MutableList<Bitmap?> = ArrayList()
    private var glare: Bitmap? = null
    private var executorService: ExecutorService? = null
    private val path = Path()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var guide: PerimeterPathGuide

    init {
        ripplePaint.strokeWidth = strokeWidth.toFloat()
        
        val width = DisplayUtils.getWidth(context)
        val height = DisplayUtils.getHeight(context)
        guide = PerimeterPathGuide(
            context,
            CircularCornerPathRenderer(context),
            strokeWidth / 2,
            width,
            height
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
    }

    fun preloadRes() {
        images = ArrayList<Bitmap?>(RES_END_INDEX + 1).apply {
            repeat(RES_END_INDEX + 1) { add(null) }
        }
        startLoadExecutor()
        glare = BitmapFactory.decodeResource(resources, R.drawable.nt_charge_glare)
    }

    private fun releaseRes() {
        images.forEachIndexed { index, bitmap ->
            bitmap?.recycle()
            images[index] = null
        }
        images.clear()
        glare?.recycle()
        glare = null
        executorService?.shutdown()
        executorService = null
    }

    fun startRipple(onAnimationEnd: Runnable? = null) {
        if (animator.isRunning) return

        animator.addUpdateListener { 
            currentIndex = it.animatedValue as Int
            invalidate()
            if (currentIndex > 0) {
                images[currentIndex - 1]?.recycle()
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onAnimationEnd?.run()
                releaseRes()
            }

            override fun onAnimationCancel(animation: Animator) {
                onAnimationEnd?.run()
                releaseRes()
            }
        })

        val updateListener = ValueAnimator.AnimatorUpdateListener { 
            onInvocationProgress(it.animatedValue as Float)
            invalidate()
        }

        outlineStep1Animator.addUpdateListener(updateListener)
        outlineStep2Animator.addUpdateListener(updateListener)

        val alphaUpdateListener = ValueAnimator.AnimatorUpdateListener {
            currentAlpha = it.animatedValue as Float
            invalidate()
        }

        darkOverlayAnimator.addUpdateListener(alphaUpdateListener)
        darkOverlayReverseAnimator.addUpdateListener(alphaUpdateListener)

        currentIndex = 0
        currentAlpha = 0f

        AnimatorSet().apply {
            playTogether(
                AnimatorSet().apply { playTogether(outlineStep1Animator, darkOverlayAnimator) },
                AnimatorSet().apply { playTogether(animator, outlineStep2Animator, darkOverlayReverseAnimator) }
            )
            start()
        }
    }

    fun rippleInProgress(): Boolean {
        return animator.isRunning
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val min = MathUtils.min(width, height).toFloat()
        val max = MathUtils.max(width, height).toFloat()

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val display: Display? = windowManager.defaultDisplay
            Log.d(TAG, "rotate ${display?.rotation}")
            canvas.rotate(-90f, min / 2, min / 2)
            display?.rotation?.takeIf { it == 3 }?.let {
                canvas.scale(1f, -1f, height / 2f, width / 2f)
            }
        }

        if (currentIndex >= images.size) return

        canvas.drawARGB((currentAlpha * 255 * 0.2f).toInt(), 0, 0, 0)
        
        images.getOrNull(currentIndex)?.let { bitmap ->
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, min.toInt(), max.toInt()), null)
            }
        }

        ripplePaint.alpha = (currentAlpha * 255).toInt()
        invocationLights.forEach { renderLight(it, canvas) }

        glare?.takeIf { !it.isRecycled }?.let {
            canvas.drawBitmap(
                it,
                (min - it.width) / 2,
                max - (it.height / 2),
                ripplePaint
            )
        }
    }

    private fun onInvocationProgress(progress: Float) {
        val totalWidth = guide.run {
            getRegionWidth(PerimeterPathGuide.Region.BOTTOM) +
            getRegionWidth(PerimeterPathGuide.Region.LEFT) +
            getRegionWidth(PerimeterPathGuide.Region.BOTTOM_LEFT) * 2
        }
        
        val lerp = MathUtils.lerp(0f, totalWidth, progress)
        val regionWidth = guide.getRegionWidth(PerimeterPathGuide.Region.BOTTOM) / 2
        
        setLight(0, regionWidth, regionWidth + lerp)
        setLight(1, regionWidth - lerp, regionWidth)
    }

    private fun renderLight(light: EdgeLight, canvas: Canvas) {
        if (light.length > 0) {
            guide.strokeSegment(path, light.start, light.start + light.length)
            canvas.drawPath(path, ripplePaint)
        }
    }

    private fun setLight(index: Int, start: Float, end: Float) {
        invocationLights[index].setEndpoints(start, end)
    }

    private fun startLoadExecutor() {
        if (executorService != null) return
        
        executorService = Executors.newFixedThreadPool(DECODE_BITMAP_MAX_THREAD_POOL)
        
        (0..RES_END_INDEX).forEach { i ->
            executorService?.execute(DecodeBitmapTask(images, resources, i))
        }
    }

    private inner class DecodeBitmapTask(
        private val images: MutableList<Bitmap?>,
        private val resources: Resources,
        private val index: Int
    ) : Runnable {

        override fun run() {
            try {
                if (index in RIPPLE_RES_IDS.indices) {
                    val resId = RIPPLE_RES_IDS[index]
                    resources.openRawResource(resId).use { stream ->
                        images[index] = BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
