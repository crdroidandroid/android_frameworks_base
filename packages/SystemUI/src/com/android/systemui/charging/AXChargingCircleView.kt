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
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class AXChargingCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val circleView = CircleProgressView(context)

    private val levelText = TextView(context).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    private val percentText = TextView(context).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    private val textRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private val boltIcon = ImageView(context).apply {
        setImageResource(R.drawable.charging_circle_bolt)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val turboLineView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = GONE
    }

    private var batteryLevel = 0
    private var dimAlpha = 180
    private var lowBatteryThreshold = 15
    private var lowBatteryColor = 0xFFFF3B30.toInt()
    private var percentColor = Color.WHITE

    private var turboLineFrames: MutableList<Bitmap?> = ArrayList()
    private var turboLineResIds: IntArray = IntArray(0)

    private var isAnimating = false

    init {
        setWillNotDraw(false)
        addView(circleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        textRow.addView(levelText)
        textRow.addView(percentText)
        addView(textRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addView(boltIcon, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        })
        addView(turboLineView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        })
    }

    fun setBatteryLevel(level: Int) {
        batteryLevel = level
    }

    fun preloadRes() {
        dimAlpha = resources.getInteger(R.integer.config_chargingAnimDimAlpha)
        lowBatteryThreshold = resources.getInteger(R.integer.config_chargingAnimLowBatteryThreshold)
        lowBatteryColor = resources.getColor(R.color.config_chargingAnimLowBatteryColor, null)
        percentColor = resources.getColor(R.color.config_chargingAnimPercentColor, null)

        val textColor = if (batteryLevel <= lowBatteryThreshold) lowBatteryColor else percentColor
        levelText.setTextColor(textColor)
        percentText.setTextColor(textColor)
        levelText.text = "$batteryLevel"
        percentText.text = "%"

        val dm = resources.displayMetrics
        val smallSide = min(dm.widthPixels, dm.heightPixels).toFloat()
        val scale = smallSide / BG_SIZE_PX
        levelText.setTextSize(TypedValue.COMPLEX_UNIT_PX, LEVEL_TEXT_SIZE_PX * scale)
        percentText.setTextSize(TypedValue.COMPLEX_UNIT_PX, PERCENT_TEXT_SIZE_PX * scale)

        circleView.thinCircleColor = if (batteryLevel <= lowBatteryThreshold)
            THIN_CIRCLE_LOW_COLOR else resources.getColor(R.color.config_chargingAnimLightColor, null)

        val largeSide = max(dm.widthPixels, dm.heightPixels)
        val thinCircleScaled = (THIN_CIRCLE_SIZE_PX * scale).toInt()
        val turboLineHeight = (largeSide - thinCircleScaled) / 2
        val tlp = turboLineView.layoutParams as LayoutParams
        tlp.height = turboLineHeight
        turboLineView.layoutParams = tlp

        val boltW = (resources.getInteger(R.integer.config_chargingAnimBoltWidth) * scale).toInt()
        val boltH = (resources.getInteger(R.integer.config_chargingAnimBoltHeight) * scale).toInt()
        val boltRadius = resources.getInteger(R.integer.config_chargingAnimBoltRadius) * scale
        val boltBottomMargin = (largeSide / 2f - boltRadius - boltH / 2f).toInt()
        val blp = boltIcon.layoutParams as LayoutParams
        blp.width = boltW
        blp.height = boltH
        blp.bottomMargin = boltBottomMargin
        boltIcon.layoutParams = blp

        val ta = resources.obtainTypedArray(R.array.config_chargingAnimTurboLineFrames)
        val frameCount = ta.length()
        turboLineResIds = IntArray(frameCount) { ta.getResourceId(it, 0) }
        ta.recycle()

        if (frameCount > 0) {
            turboLineFrames = ArrayList<Bitmap?>(frameCount)
            turboLineResIds.forEach { resId ->
                val bmp = if (resId != 0) BitmapFactory.decodeResource(resources, resId) else null
                turboLineFrames.add(bmp)
            }
        }
    }

    private fun releaseTurboLineRes() {
        turboLineFrames.forEach { it?.recycle() }
        turboLineFrames.clear()
    }

    fun startAnimation(onAnimationEnd: Runnable? = null) {
        if (isAnimating) return
        isAnimating = true

        val isLow = batteryLevel <= lowBatteryThreshold
        val hasBolt = resources.getInteger(R.integer.config_chargingAnimBoltWidth) > 0
        if (!isLow && hasBolt) {
            boltIcon.scaleX = 0f
            boltIcon.scaleY = 0f
            boltIcon.alpha = 0f
            boltIcon.visibility = VISIBLE
        } else {
            boltIcon.visibility = GONE
        }

        turboLineView.alpha = 1f
        turboLineView.visibility = GONE

        startCirclePhase(onAnimationEnd, isLow)

        if (turboLineResIds.isNotEmpty() && !isLow) {
            animateTurboLine()
        }
    }

    private fun animateTurboLine() {
        val frameCount = turboLineResIds.size
        val lineDuration = frameCount * TURBO_LINE_FRAME_DURATION_MS
        var lastIndex = -1
        ValueAnimator.ofInt(0, frameCount - 1).apply {
            duration = lineDuration
            startDelay = TURBO_LINE_START_DELAY_MS
            interpolator = null
            addUpdateListener {
                val idx = it.animatedValue as Int
                if (idx != lastIndex) {
                    lastIndex = idx
                    turboLineFrames.getOrNull(idx)?.takeIf { bmp -> !bmp.isRecycled }?.let { bmp ->
                        turboLineView.setImageBitmap(bmp)
                        turboLineView.visibility = VISIBLE
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ObjectAnimator.ofFloat(turboLineView, "alpha", 1f, 0f).apply {
                        duration = (batteryLevel * TURBO_LINE_FADE_MS_PER_LEVEL).toLong()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                turboLineView.visibility = GONE
                                releaseTurboLineRes()
                            }
                        })
                    }.start()
                }
            })
        }.start()
    }

    private fun startCirclePhase(onAnimationEnd: Runnable?, isLow: Boolean) {
        circleView.show(batteryLevel, isLow)

        circleView.scaleX = SCALE_IN_FROM
        circleView.scaleY = SCALE_IN_FROM
        circleView.alpha = 0f
        ObjectAnimator.ofPropertyValuesHolder(
            circleView,
            PropertyValuesHolder.ofFloat("scaleX", SCALE_IN_FROM, 1f),
            PropertyValuesHolder.ofFloat("scaleY", SCALE_IN_FROM, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f),
        ).apply {
            duration = SCALE_IN_DURATION_MS
            interpolator = OvershootInterpolator(1.2f)
        }.start()

        textRow.alpha = 0f
        textRow.scaleX = SCALE_IN_FROM
        textRow.scaleY = SCALE_IN_FROM
        ObjectAnimator.ofPropertyValuesHolder(
            textRow,
            PropertyValuesHolder.ofFloat("scaleX", SCALE_IN_FROM, 1f),
            PropertyValuesHolder.ofFloat("scaleY", SCALE_IN_FROM, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f),
        ).apply {
            duration = SCALE_IN_DURATION_MS
            startDelay = TEXT_SCALE_IN_DELAY_MS
            interpolator = OvershootInterpolator(1.2f)
        }.start()

        if (boltIcon.visibility == VISIBLE) {
            val progressDuration = (batteryLevel * PROGRESS_STEP_DELAY_MS).toLong()
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(boltIcon, "scaleX", 0f, 1f),
                    ObjectAnimator.ofFloat(boltIcon, "scaleY", 0f, 1f),
                    ObjectAnimator.ofFloat(boltIcon, "alpha", 0f, 1f),
                )
                duration = 300L
                startDelay = progressDuration
                interpolator = android.view.animation.OvershootInterpolator(2f)
            }.start()
        }

        val totalDuration = (batteryLevel * PROGRESS_STEP_DELAY_MS).toLong() + HOLD_BEFORE_DISMISS_MS
        ObjectAnimator.ofFloat(textRow, "alpha", 1f, 0f).apply {
            duration = 300L
            startDelay = totalDuration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    circleView.release()
                    onAnimationEnd?.run()
                }
                override fun onAnimationCancel(animation: Animator) {
                    isAnimating = false
                    circleView.release()
                    onAnimationEnd?.run()
                }
            })
        }.start()
    }

    fun animationInProgress(): Boolean = isAnimating

    override fun onDraw(canvas: Canvas) {
        canvas.drawARGB(dimAlpha, 0, 0, 0)
        super.onDraw(canvas)
    }

    private class CircleProgressView(context: Context) : View(context) {
        private var bgBitmap: Bitmap? = null
        private var circleProgressBitmap: Bitmap? = null
        private var fixedEndPointBitmap: Bitmap? = null
        private var bgRectSrc: Rect? = null
        private var bgRectDst = RectF()
        private var circleRectSrc: Rect? = null
        private var circleRectDst = RectF()
        private var fixedEndPointRectSrc: Rect? = null
        private var fixedEndPointRectDst = RectF()
        private var thinCircleRect = RectF()
        private var ringPath = Path()
        private var ringPathPaint: Paint? = null
        private var thinCirclePaint: Paint? = null
        private val duffXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

        private var circleX = 0f
        private var circleY = 0f
        private var ringPathCircleR = 0f
        private var endPointCircleR = 0f
        private var currentLevel = 0
        private var drawProgress = 0
        private var initFlag = false

        var thinCircleColor = 0xFF1C6AFF.toInt()

        private val progressRunnable = object : Runnable {
            override fun run() {
                if (currentLevel < 0 || currentLevel > 100) return
                if (drawProgress <= currentLevel) {
                    drawProgress++
                    invalidate()
                    if (initFlag) {
                        postDelayed(this, PROGRESS_STEP_DELAY_MS.toLong())
                    }
                }
            }
        }

        fun show(level: Int, isLow: Boolean) {
            if (initFlag) return
            currentLevel = level
            drawProgress = 0

            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inScaled = false
            }

            if (isLow) {
                bgBitmap = BitmapFactory.decodeResource(resources, R.drawable.charging_circle_bg_low, opts)
                circleProgressBitmap = BitmapFactory.decodeResource(resources, R.drawable.charging_circle_progress_low, opts)
                fixedEndPointBitmap = BitmapFactory.decodeResource(resources, R.drawable.charging_circle_endpoint_low, opts)
            } else {
                bgBitmap = BitmapFactory.decodeResource(resources, R.drawable.charging_circle_bg, opts)
                circleProgressBitmap = BitmapFactory.decodeResource(resources, R.drawable.charging_circle_progress, opts)
                fixedEndPointBitmap = BitmapFactory.decodeResource(resources, R.drawable.charging_circle_endpoint, opts)
            }

            ringPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 200f
                style = Paint.Style.STROKE
                maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
            }
            ringPath = Path()
            thinCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = THIN_CIRCLE_RING_SIZE_PX
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                color = thinCircleColor
            }
            initRects()
            initFlag = true
            invalidate()
            postDelayed(progressRunnable, PROGRESS_STEP_DELAY_MS.toLong())
        }

        fun release() {
            if (!initFlag) return
            initFlag = false
            removeCallbacks(progressRunnable)
            bgBitmap?.recycle()
            bgBitmap = null
            circleProgressBitmap?.recycle()
            circleProgressBitmap = null
            fixedEndPointBitmap?.recycle()
            fixedEndPointBitmap = null
            ringPath.reset()
            drawProgress = 0
        }

        private fun initRects() {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val smallSide = min(screenW, screenH)
            val scale = smallSide.toFloat() / BG_SIZE_PX

            val circleSize = (CIRCLE_SIZE_PX * scale).toInt()
            val circleF = circleSize.toFloat()
            circleRectSrc = Rect(0, 0, CIRCLE_SIZE_PX.toInt(), CIRCLE_SIZE_PX.toInt())
            val sw = screenW.toFloat()
            val sh = screenH.toFloat()
            circleRectDst = RectF(
                (sw - circleF) / 2f, (sh - circleF) / 2f,
                (sw + circleF) / 2f, (sh + circleF) / 2f
            )
            circleX = circleRectDst.centerX()
            circleY = circleRectDst.centerY()

            ringPathCircleR = circleRectDst.width() / 2f - (FIXED_END_EDGE_DIST_PX * scale)

            fixedEndPointBitmap?.let { bmp ->
                val w = bmp.width
                val h = bmp.height
                endPointCircleR = (w / 2.5f) / 2f
                fixedEndPointRectSrc = Rect(0, 0, w, h)
                fixedEndPointRectDst = RectF(0f, 0f, w.toFloat(), h.toFloat())
            }

            val thinSize = (THIN_CIRCLE_SIZE_PX * scale).toInt()
            thinCircleRect = RectF(
                (screenW - thinSize) / 2f, (screenH - thinSize) / 2f,
                (screenW + thinSize) / 2f, (screenH + thinSize) / 2f
            )

            val bgScaled = (BG_SIZE_PX * scale).toInt()
            val bgF = bgScaled.toFloat()
            bgRectSrc = Rect(0, 0, BG_SIZE_PX.toInt(), BG_SIZE_PX.toInt())
            bgRectDst = RectF(
                (sw - bgF) / 2f, (sh - bgF) / 2f,
                (sw + bgF) / 2f, (sh + bgF) / 2f
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!initFlag) return

            ringPath.reset()
            ringPath.moveTo(circleX, circleY + ringPathCircleR)

            for (i in 100 downTo drawProgress) {
                val degree = UNIT * i + 7f
                val radians = ((degree + 180f) * Math.PI / 180.0)
                val px = (sin(radians) * ringPathCircleR + circleX).toFloat()
                val py = (circleY - cos(radians) * ringPathCircleR).toFloat()
                ringPath.lineTo(px, py)

                if (i == drawProgress) {
                    val r = endPointCircleR
                    fixedEndPointRectDst.set(px - r, py - r, px + r, py + r)
                }
            }

            bgBitmap?.let { canvas.drawBitmap(it, bgRectSrc, bgRectDst, null) }

            thinCirclePaint?.let { canvas.drawArc(thinCircleRect, 97.5f, 345f, false, it) }

            if (currentLevel != lowBatteryThresholdForEndpoint && currentLevel != 100) {
                fixedEndPointBitmap?.let { bmp ->
                    fixedEndPointRectSrc?.let { src ->
                        canvas.drawBitmap(bmp, src, fixedEndPointRectDst, null)
                    }
                }
            }

            ringPathPaint?.let { paint ->
                canvas.saveLayer(null, paint)
                circleProgressBitmap?.let { bmp ->
                    circleRectSrc?.let { src -> canvas.drawBitmap(bmp, src, circleRectDst, null) }
                }
                paint.xfermode = duffXfermode
                canvas.drawPath(ringPath, paint)
                paint.xfermode = null
                canvas.restore()
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (changed && initFlag) {
                initRects()
            }
        }

        companion object {
            private const val lowBatteryThresholdForEndpoint = 15
        }
    }

    companion object {
        private const val BG_SIZE_PX = 1080f
        private const val CIRCLE_SIZE_PX = 800f
        private const val FIXED_END_EDGE_DIST_PX = 80f
        private const val THIN_CIRCLE_SIZE_PX = 644f
        private const val THIN_CIRCLE_RING_SIZE_PX = 2f
        private const val THIN_CIRCLE_LOW_COLOR = 0xFFBB2B3F.toInt()

        private const val LEVEL_TEXT_SIZE_PX = 230f
        private const val PERCENT_TEXT_SIZE_PX = 90f

        private const val UNIT = 3.45f
        private const val PROGRESS_STEP_DELAY_MS = 10f
        private const val HOLD_BEFORE_DISMISS_MS = 2000L
        private const val TURBO_LINE_FRAME_DURATION_MS = 40L
        private const val TURBO_LINE_FADE_MS_PER_LEVEL = 10f
        private const val TURBO_LINE_START_DELAY_MS = 350L
        private const val SCALE_IN_FROM = 0.7f
        private const val SCALE_IN_DURATION_MS = 400L
        private const val TEXT_SCALE_IN_DELAY_MS = 150L
    }
}
