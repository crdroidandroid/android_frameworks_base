/*
 * Copyright (C) 2025-2026 AxionOS Project
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
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.MathUtils
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.android.app.animation.Interpolators
import com.android.systemui.res.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AXRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "AXRippleView"
        private const val DECODE_BITMAP_MAX_THREAD_POOL = 2
    }

    private val animator = ValueAnimator.ofInt(0, 40).apply {
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

    private var currentAlpha = 0f
    private var currentIndex = 0
    private var images: MutableList<Bitmap?> = ArrayList()
    private var glare: Bitmap? = null
    private var activeFrameResIds: IntArray = IntArray(0)
    private var executorService: ExecutorService? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
    }

    fun preloadRes() {
        val ta = resources.obtainTypedArray(R.array.config_chargingAnimFrames)
        val frameCount = ta.length()
        activeFrameResIds = IntArray(frameCount) { ta.getResourceId(it, 0) }
        ta.recycle()

        animator.setIntValues(0, (frameCount - 1).coerceAtLeast(0))

        images = ArrayList<Bitmap?>(frameCount).apply {
            repeat(frameCount) { add(null) }
        }
        startLoadExecutor()

        val glareTa = resources.obtainTypedArray(R.array.config_chargingAnimGlare)
        val glareResId = if (glareTa.length() > 0) glareTa.getResourceId(0, 0) else 0
        glareTa.recycle()
        glare = if (glareResId != 0) BitmapFactory.decodeResource(resources, glareResId) else null
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
                images.getOrNull(currentIndex - 1)?.recycle()
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

        val alphaUpdateListener = ValueAnimator.AnimatorUpdateListener {
            currentAlpha = it.animatedValue as Float
            invalidate()
        }

        darkOverlayAnimator.addUpdateListener(alphaUpdateListener)
        darkOverlayReverseAnimator.addUpdateListener(alphaUpdateListener)

        currentIndex = 0
        currentAlpha = 0f

        AnimatorSet().apply {
            playSequentially(
                darkOverlayAnimator,
                AnimatorSet().apply { playTogether(animator, darkOverlayReverseAnimator) }
            )
            start()
        }
    }

    fun rippleInProgress(): Boolean = animator.isRunning

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val min = MathUtils.min(width, height).toFloat()
        val max = MathUtils.max(width, height).toFloat()

        val display: Display? = windowManager.defaultDisplay
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "rotate ${display?.rotation}")
            canvas.rotate(-90f, min / 2, min / 2)
            display?.rotation?.takeIf { it == 3 }?.let {
                canvas.scale(1f, -1f, height / 2f, width / 2f)
            }
        }

        canvas.drawARGB((currentAlpha * (255 * 0.2f)).toInt(), 0, 0, 0)

        if (currentIndex < images.size) {
            images.getOrNull(currentIndex)?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    val scale = min / bitmap.width
                    val destH = bitmap.height * scale
                    val destTop = (max - destH) / 2f
                    canvas.drawBitmap(bitmap, null, RectF(0f, destTop, min, destTop + destH), null)
                }
            }
        }

        glare?.takeIf { !it.isRecycled }?.let {
            val alpha = (currentAlpha * 255).toInt()
            it.let { bmp ->
                val paint = android.graphics.Paint().apply { this.alpha = alpha }
                canvas.drawBitmap(
                    bmp,
                    (min - bmp.width) / 2,
                    max - (bmp.height / 2),
                    paint
                )
            }
        }
    }

    private fun startLoadExecutor() {
        if (executorService != null) return
        executorService = Executors.newFixedThreadPool(DECODE_BITMAP_MAX_THREAD_POOL)
        activeFrameResIds.indices.forEach { i ->
            executorService?.execute(DecodeBitmapTask(images, resources, i, activeFrameResIds[i]))
        }
    }

    private inner class DecodeBitmapTask(
        private val images: MutableList<Bitmap?>,
        private val resources: Resources,
        private val index: Int,
        private val resId: Int
    ) : Runnable {

        override fun run() {
            try {
                if (resId != 0) {
                    resources.openRawResource(resId).use { stream ->
                        images[index] = BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
