/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.customization.clocks.view

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.VibrationEffect
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.MathUtils.lerp
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec.EXACTLY
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.android.app.animation.Interpolators
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Flags.clockFidgetAnimation
import com.android.systemui.animation.AxisDefinition
import com.android.systemui.animation.GSFAxes
import com.android.systemui.animation.TextAnimator
import com.android.systemui.animation.TextAnimatorListener
import com.android.systemui.customization.clocks.ClockContext
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.customization.clocks.DigitTranslateAnimator
import com.android.systemui.customization.clocks.FontTextStyle
import com.android.systemui.customization.clocks.utils.CanvasUtils.translate
import com.android.systemui.customization.clocks.utils.CanvasUtils.use
import com.android.systemui.customization.clocks.utils.FontUtils.set
import com.android.systemui.customization.clocks.utils.PaintUtils.getTextBounds
import com.android.systemui.customization.clocks.utils.ViewUtils.measuredSize
import com.android.systemui.customization.clocks.utils.ViewUtils.size
import com.android.systemui.plugins.keyguard.VPoint
import com.android.systemui.plugins.keyguard.VPointF
import com.android.systemui.plugins.keyguard.VPointF.Companion.max
import com.android.systemui.plugins.keyguard.VPointF.Companion.size
import com.android.systemui.plugins.keyguard.VRectF
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.shared.Flags.ambientAod
import java.lang.Thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface DigitalClockTextViewParent {
    fun animateFidget(pt: VPointF, enforceBounds: Boolean): Boolean

    fun updateMeasuredSize()

    fun updateLocation()

    fun requestLayout()

    fun invalidate()
}

@SuppressLint("AppCompatCustomView")
abstract class DigitalClockTextView(
    val clockCtx: ClockContext,
    val isLargeClock: Boolean,
    attrs: AttributeSet? = null,
) : TextView(clockCtx.context, attrs) {
    val lockScreenPaint = TextPaint()
    lateinit var textStyle: FontTextStyle
    lateinit var aodStyle: FontTextStyle

    data class FontVariations(
        val lockscreen: String,
        val doze: String,
        val chargeLockscreen: String,
        val chargeDoze: String,
        val fidget: String,
    ) {
        fun getStandard(isDozing: Boolean): String = if (isDozing) doze else lockscreen

        fun getCharge(isDozing: Boolean): String = if (isDozing) chargeDoze else chargeLockscreen
    }

    private var fontVariations: FontVariations

    abstract fun initializeFontVariations(): FontVariations

    abstract fun updateFontVariations(lsAxes: ClockAxisStyle): FontVariations

    init {
        fontVariations = initializeFontVariations()
    }

    var onViewBoundsChanged: ((VRectF) -> Unit)? = null
    var onViewMaxSizeChanged: ((VPointF) -> Unit)? = null
    var digitTranslateAnimator: DigitTranslateAnimator? = null
    var aodFontSizePx = -1f

    var maxSingleDigitSize = VPointF(-1f)
        private set

    // Store the font size when there's no height constraint as a reference when adjusting font size
    private var lastUnconstrainedTextSize = Float.MAX_VALUE
    // Calculated by height of styled text view / text size
    // Used as a factor to calculate a smaller font size when text height is constrained
    @VisibleForTesting var fontSizeAdjustFactor = 1f

    private val initThread = Thread.currentThread()

    // Maximum size this view will be at any time, but in its current rendering configuration
    var maxSize = VPointF(-1f)
        private set

    // textBounds is the size of text in LS, which only measures current text in lockscreen style
    var textBounds = VRectF.ZERO
    // prevTextBounds and targetTextBounds are to deal with dozing animation between LS and AOD
    // especially for the textView which has different bounds during the animation
    // prevTextBounds holds the state we are transitioning from
    private var prevTextBounds = VRectF.ZERO
    // targetTextBounds holds the state we are interpolating to
    private var targetTextBounds = VRectF.ZERO
    protected val logger = ClockLogger(this, clockCtx.messageBuffer, TAG)
        get() = field ?: ClockLogger.INIT_LOGGER

    private var aodDozingInterpolator: Interpolator = Interpolators.LINEAR

    @VisibleForTesting lateinit var textAnimator: TextAnimator

    private val typefaceCache = clockCtx.typefaceCache.getVariantCache("")

    var verticalAlignment: VerticalAlignment = VerticalAlignment.BASELINE
    var horizontalAlignment: HorizontalAlignment = HorizontalAlignment.CENTER

    val xAlignment: XAlignment
        get() = horizontalAlignment.resolveXAlignment(this)

    var isAnimationEnabled = true
    var dozeFraction: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var textBorderWidth = 0f
    var measuredBaseline = 0
    var lockscreenColor = Color.WHITE
    var aodColor = Color.WHITE

    override fun onTouchEvent(evt: MotionEvent): Boolean {
        if (super.onTouchEvent(evt)) return true

        if (evt.action == MotionEvent.ACTION_DOWN) {
            val pt = VPointF(evt.x, evt.y)
            return (parent as? DigitalClockTextViewParent)?.animateFidget(pt, enforceBounds = false)
                ?: animateFidget(pt, enforceBounds = false)
        }

        return false
    }

    private val animatorListener =
        object : TextAnimatorListener {
            override fun onInvalidate() = invalidate()

            override fun onRebased(progress: Float) {
                updateAnimationTextBounds()
            }

            override fun onPaintModified(paint: Paint) {
                updateAnimationTextBounds()
            }
        }

    fun updateColor(lockscreenColor: Int, aodColor: Int = Color.WHITE) {
        this.lockscreenColor = lockscreenColor
        if (ambientAod()) {
            this.aodColor = aodColor
        }
        lockScreenPaint.color = lockscreenColor

        if (dozeFraction < 1f) {
            textAnimator.setTextStyle(TextAnimator.Style(color = lockscreenColor))
        }
        invalidate()
    }

    fun updateAxes(lsAxes: ClockAxisStyle, isAnimated: Boolean) {
        fontVariations = updateFontVariations(lsAxes)
        logger.updateAxes(fontVariations.lockscreen, fontVariations.doze, isAnimated)

        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(fontVariations.lockscreen)
        typeface = lockScreenPaint.typeface

        updateTextBounds()

        textAnimator.setTextStyle(
            TextAnimator.Style(fVar = fontVariations.lockscreen),
            TextAnimator.Animation(
                animate = isAnimated && isAnimationEnabled,
                duration = AXIS_CHANGE_ANIMATION_DURATION,
                interpolator = AXIS_CHANGE_INTERPOLATOR,
            ),
        )

        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        recomputeMaxTextSize()
        requestLayout()
        invalidate()
    }

    data class AxisAnimation(val axisKey: String, val distance: Float, val midpoint: Float) {
        constructor(
            axis: AxisDefinition,
            distance: Float,
            midpoint: Float = (axis.maxValue + axis.minValue) / 2f,
        ) : this(axis.tag, distance, midpoint)

        fun mutateValue(value: Float): Float {
            return value + distance * (if (value > midpoint) -1 else 1)
        }
    }

    fun buildAnimationTargetVariation(
        axes: ClockAxisStyle,
        params: List<AxisAnimation>,
    ): ClockAxisStyle {
        return ClockAxisStyle(
            axes.items
                .map { (key, value) ->
                    val axis = params.firstOrNull { it.axisKey == key }
                    key to (axis?.mutateValue(value) ?: value)
                }
                .toMap()
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        logger.onMeasure(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val layout = this.layout
        if (layout != null) {
            if (!this::textAnimator.isInitialized) {
                textAnimator = TextAnimator(layout, typefaceCache, animatorListener)
                setInterpolatorPaint()
            } else {
                textAnimator.updateLayout(layout)
            }
            measuredBaseline = layout.getLineBaseline(0)
        } else {
            val currentThread = Thread.currentThread()
            Log.wtf(
                TAG,
                "TextView.getLayout() is null after measure! " +
                    "currentThread=$currentThread; initThread=$initThread",
            )
        }

        val bounds = getInterpolatedTextBounds()
        val size = computeMeasuredSize(bounds, widthMeasureSpec, heightMeasureSpec)
        setInterpolatedSize(size, widthMeasureSpec, heightMeasureSpec)
    }

    private var drawnProgress: Float? = null

    override fun onDraw(canvas: Canvas) {
        logger.onDraw(textAnimator.textInterpolator.shapedText)

        val interpProgress = textAnimator.progress
        val interpBounds = getInterpolatedTextBounds(interpProgress)
        if (interpProgress != drawnProgress) {
            drawnProgress = interpProgress
            val measureSize = computeMeasuredSize(interpBounds)
            setInterpolatedSize(measureSize)
            (parent as? DigitalClockTextViewParent)?.run {
                updateMeasuredSize()
                updateLocation()
            } ?: setInterpolatedLocation(measureSize)
        }

        canvas.use {
            digitTranslateAnimator?.apply { canvas.translate(currentTranslation) }
            canvas.translate(getDrawTranslation(interpBounds))
            textAnimator.draw(canvas)
        }
    }

    override fun setVisibility(visibility: Int) {
        logger.setVisibility(visibility)
        super.setVisibility(visibility)
    }

    override fun setAlpha(alpha: Float) {
        logger.setAlpha(alpha)
        super.setAlpha(alpha)
    }

    private var layoutBounds = VRectF.ZERO

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        logger.onLayout(changed, left, top, right, bottom)
        layoutBounds = VRectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    override fun invalidate() {
        logger.invalidate()
        super.invalidate()
        (parent as? DigitalClockTextViewParent)?.invalidate()
    }

    fun refreshTime() {
        logger.refreshTime()
        refreshText()
    }

    fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        if (!this::textAnimator.isInitialized) return
        logger.animateDoze(isDozing, isAnimated)
        textAnimator.setTextStyle(
            TextAnimator.Style(
                fVar = fontVariations.getStandard(isDozing),
                color = if (isDozing) aodColor else lockscreenColor,
                textSize = if (isDozing) aodFontSizePx else lockScreenPaint.textSize,
            ),
            TextAnimator.Animation(
                animate = isAnimated && isAnimationEnabled,
                duration = aodStyle.transitionDuration,
                interpolator = aodDozingInterpolator,
            ),
        )

        if (!isAnimated) {
            requestLayout()
            (parent as? DigitalClockTextViewParent)?.requestLayout()
        }
    }

    fun animateCharge() {
        if (!this::textAnimator.isInitialized || textAnimator.isRunning) {
            // Skip charge animation if dozing animation is already playing.
            return
        }
        logger.animateCharge()

        textAnimator.setTextStyle(
            TextAnimator.Style(fVar = fontVariations.getCharge(dozeFraction != 0f)),
            TextAnimator.Animation(
                animate = isAnimationEnabled,
                duration = CHARGE_ANIMATION_DURATION,
                interpolator = CHARGE_INTERPOLATOR,
                onAnimationEnd = {
                    textAnimator.setTextStyle(
                        TextAnimator.Style(fVar = fontVariations.getStandard(dozeFraction != 0f)),
                        TextAnimator.Animation(
                            animate = isAnimationEnabled,
                            duration = CHARGE_ANIMATION_DURATION,
                            interpolator = CHARGE_INTERPOLATOR,
                        ),
                    )
                },
            ),
        )
    }

    fun animateFidget(pt: VPointF, enforceBounds: Boolean): Boolean {
        if (!this::textAnimator.isInitialized || textAnimator.isRunning) {
            // Skip fidget animation if other animation is already playing.
            return false
        }

        if (enforceBounds) {
            if (visibility != View.VISIBLE) {
                logger.animateFidget(pt, isSuppressed = true)
                return false
            }

            val bounds = getInterpolatedTextBounds()
            if (!bounds.contains(pt)) {
                logger.animateFidget(pt, isSuppressed = true)
                return false
            }
        }

        logger.animateFidget(pt, isSuppressed = false)
        clockCtx.vibrator?.vibrate(FIDGET_HAPTICS)

        textAnimator.setTextStyle(
            TextAnimator.Style(fVar = fontVariations.fidget),
            TextAnimator.Animation(
                animate = isAnimationEnabled,
                duration = FIDGET_ANIMATION_DURATION,
                interpolator = FIDGET_INTERPOLATOR,
                onAnimationEnd = {
                    textAnimator.setTextStyle(
                        TextAnimator.Style(fVar = fontVariations.lockscreen),
                        TextAnimator.Animation(
                            animate = isAnimationEnabled,
                            duration = FIDGET_ANIMATION_DURATION,
                            interpolator = FIDGET_INTERPOLATOR,
                        ),
                    )
                },
            ),
        )
        return true
    }

    fun refreshText() {
        updateTextBounds()

        if (layout == null) {
            requestLayout()
        } else {
            textAnimator.updateLayout(layout)
        }
    }

    private fun isSingleDigit(): Boolean {
        return id == ClockViewIds.HOUR_FIRST_DIGIT ||
            id == ClockViewIds.HOUR_SECOND_DIGIT ||
            id == ClockViewIds.MINUTE_FIRST_DIGIT ||
            id == ClockViewIds.MINUTE_SECOND_DIGIT
    }

    /** Returns the interpolated text bounding rect based on interpolation progress */
    private fun getInterpolatedTextBounds(progress: Float = textAnimator.progress): VRectF {
        if (progress <= 0f) {
            return prevTextBounds
        } else if (!textAnimator.isRunning || progress >= 1f) {
            return targetTextBounds
        }

        return VRectF(
            left = lerp(prevTextBounds.left, targetTextBounds.left, progress),
            right = lerp(prevTextBounds.right, targetTextBounds.right, progress),
            top = lerp(prevTextBounds.top, targetTextBounds.top, progress),
            bottom = lerp(prevTextBounds.bottom, targetTextBounds.bottom, progress),
        )
    }

    private fun computeMeasuredSize(
        interpBounds: VRectF,
        widthMeasureSpec: Int = measuredWidthAndState,
        heightMeasureSpec: Int = measuredHeightAndState,
    ): VPointF {
        val mode =
            VPoint(
                x = MeasureSpec.getMode(widthMeasureSpec),
                y = MeasureSpec.getMode(heightMeasureSpec),
            )

        return VPointF(
            when {
                mode.x == EXACTLY -> MeasureSpec.getSize(widthMeasureSpec).toFloat()
                else -> interpBounds.width + 2 * lockScreenPaint.strokeWidth
            },
            when {
                mode.y == EXACTLY -> MeasureSpec.getSize(heightMeasureSpec).toFloat()
                else -> interpBounds.height + 2 * lockScreenPaint.strokeWidth
            },
        )
    }

    /** Set the measured size of the view to match the interpolated text bounds */
    private fun setInterpolatedSize(
        measureBounds: VPointF,
        widthMeasureSpec: Int = measuredWidthAndState,
        heightMeasureSpec: Int = measuredHeightAndState,
    ) {
        val mode =
            VPoint(
                x = MeasureSpec.getMode(widthMeasureSpec),
                y = MeasureSpec.getMode(heightMeasureSpec),
            )

        setMeasuredDimension(
            MeasureSpec.makeMeasureSpec(measureBounds.x.roundToInt(), mode.x),
            MeasureSpec.makeMeasureSpec(measureBounds.y.roundToInt(), mode.y),
        )

        logger.d({
            val size = VPointF.fromLong(long1)
            val mode = VPoint.fromLong(long2)
            "setInterpolatedSize(size=$size, mode=$mode)"
        }) {
            long1 = measureBounds.toLong()
            long2 = mode.toLong()
        }
    }

    /** Set the location of the view to match the interpolated text bounds */
    private fun setInterpolatedLocation(measureSize: VPointF): VRectF {
        val pos =
            VPointF(
                when (xAlignment) {
                    XAlignment.LEFT -> layoutBounds.left
                    XAlignment.CENTER -> layoutBounds.center.x - measureSize.x / 2f
                    XAlignment.RIGHT -> layoutBounds.right - measureSize.x
                },
                when (verticalAlignment) {
                    VerticalAlignment.TOP -> layoutBounds.top
                    VerticalAlignment.CENTER -> layoutBounds.center.y - measureSize.y / 2f
                    VerticalAlignment.BOTTOM -> layoutBounds.bottom - measureSize.y
                    VerticalAlignment.BASELINE -> layoutBounds.center.y - measureSize.y / 2f
                },
            )

        val targetRect = VRectF.fromTopLeft(pos, measureSize)
        setFrame(
            targetRect.left.roundToInt(),
            targetRect.top.roundToInt(),
            targetRect.right.roundToInt(),
            targetRect.bottom.roundToInt(),
        )
        onViewBoundsChanged?.let { it(targetRect) }
        logger.d({ "setInterpolatedLocation(${VRectF.fromLong(long1)})" }) {
            long1 = targetRect.toLong()
        }
        return targetRect
    }

    private fun getDrawTranslation(interpBounds: VRectF): VPointF {
        val sizeDiff = this.measuredSize - interpBounds.size
        val alignment =
            VPointF(
                when (xAlignment) {
                    XAlignment.LEFT -> 0f
                    XAlignment.CENTER -> 0.5f
                    XAlignment.RIGHT -> 1f
                },
                when (verticalAlignment) {
                    VerticalAlignment.TOP -> 0f
                    VerticalAlignment.CENTER -> 0.5f
                    VerticalAlignment.BASELINE -> 0.5f
                    VerticalAlignment.BOTTOM -> 1f
                },
            )
        val renderCorrection =
            VPointF(
                x = -interpBounds.left,
                y = -interpBounds.top - (if (baseline != -1) baseline else measuredBaseline),
            )
        return sizeDiff * alignment + renderCorrection
    }

    fun applyStyles(textStyle: FontTextStyle, aodStyle: FontTextStyle?) {
        this.textStyle = textStyle
        lockScreenPaint.strokeJoin = Paint.Join.ROUND
        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(fontVariations.lockscreen)
        lockScreenPaint.fontFeatureSettings = if (isLargeClock) "tnum" else "pnum"
        typeface = lockScreenPaint.typeface
        textStyle.lineHeight?.let { lineHeight = it.roundToInt() }

        this.aodStyle = aodStyle ?: textStyle.copy()
        aodDozingInterpolator = this.aodStyle.transitionInterpolator ?: Interpolators.LINEAR
        lockScreenPaint.strokeWidth = textBorderWidth
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        setInterpolatorPaint()
        recomputeMaxTextSize()
        invalidate()
    }

    /** When constrainedByHeight is on, targetFontSizePx is the constrained height of textView */
    fun applyTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean = false) {
        val adjustedFontSizePx = adjustFontSize(targetFontSizePx, constrainedByHeight)
        val fontSizePx = adjustedFontSizePx * (textStyle.fontSizeScale ?: 1f)
        aodFontSizePx =
            adjustedFontSizePx * (aodStyle.fontSizeScale ?: textStyle.fontSizeScale ?: 1f)
        if (fontSizePx > 0) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            lockScreenPaint.textSize = textSize
            updateTextBounds()
        }

        if (!constrainedByHeight) {
            val lastUnconstrainedHeight = textBounds.height + lockScreenPaint.strokeWidth * 2
            fontSizeAdjustFactor = lastUnconstrainedHeight / lastUnconstrainedTextSize
        }

        lockScreenPaint.strokeWidth = textBorderWidth
        recomputeMaxTextSize()

        if (this::textAnimator.isInitialized) {
            textAnimator.setTextStyle(TextAnimator.Style(textSize = lockScreenPaint.textSize))
        }
    }

    /** Measures a maximal piece of text so that layout decisions can be consistent. */
    private fun recomputeMaxTextSize() {
        maxSingleDigitSize = VPointF(-1)

        for (i in 0..9) {
            val digitBounds = lockScreenPaint.getTextBounds("$i")
            maxSingleDigitSize = max(maxSingleDigitSize, digitBounds.size)
        }
        maxSingleDigitSize += 2 * lockScreenPaint.strokeWidth

        maxSize =
            when (id) {
                // Single digit values have already been computed
                ClockViewIds.HOUR_FIRST_DIGIT,
                ClockViewIds.HOUR_SECOND_DIGIT,
                ClockViewIds.MINUTE_FIRST_DIGIT,
                ClockViewIds.MINUTE_SECOND_DIGIT -> maxSingleDigitSize
                // Digit pairs should measure 00 as 88 is not a valid hour or minute pair
                ClockViewIds.HOUR_DIGIT_PAIR,
                ClockViewIds.MINUTE_DIGIT_PAIR -> lockScreenPaint.getTextBounds("00").size
                // Full format includes the colon. This overmeasures a bit for 12hr configurations.
                ClockViewIds.TIME_FULL_FORMAT -> lockScreenPaint.getTextBounds("00:00").size
                // DATE_FORMAT is difficult, and shouldn't be necessary
                else -> VPointF(-1)
            }

        onViewMaxSizeChanged?.let { it(maxSize) }
    }

    /** Called without animation, can be used to set the initial state of animator */
    private fun setInterpolatorPaint() {
        if (this::textAnimator.isInitialized) {
            // set initial style
            textAnimator.textInterpolator.targetPaint.set(lockScreenPaint)
            textAnimator.textInterpolator.onTargetPaintModified()
            textAnimator.setTextStyle(
                TextAnimator.Style(
                    fVar = fontVariations.lockscreen,
                    textSize = lockScreenPaint.textSize,
                    color = lockscreenColor,
                )
            )
        }
    }

    /** Updates both the lockscreen text bounds and animation text bounds */
    private fun updateTextBounds() {
        textBounds = lockScreenPaint.getTextBounds(text)
        updateAnimationTextBounds()
    }

    /**
     * Called after textAnimator.setTextStyle textAnimator.setTextStyle will update targetPaint, and
     * rebase if previous animator is canceled so basePaint will store the state we transition from
     * and targetPaint will store the state we transition to
     */
    private fun updateAnimationTextBounds() {
        drawnProgress = null
        if (this::textAnimator.isInitialized) {
            prevTextBounds = textAnimator.textInterpolator.basePaint.getTextBounds(text)
            targetTextBounds = textAnimator.textInterpolator.targetPaint.getTextBounds(text)
        } else {
            prevTextBounds = textBounds
            targetTextBounds = textBounds
        }
    }

    /**
     * Adjust text size to adapt to large display / font size where the text view will be
     * constrained by height
     */
    private fun adjustFontSize(targetFontSizePx: Float?, constrainedByHeight: Boolean): Float {
        return if (constrainedByHeight) {
            min((targetFontSizePx ?: 0F) / fontSizeAdjustFactor, lastUnconstrainedTextSize)
        } else {
            lastUnconstrainedTextSize = targetFontSizePx ?: 1F
            lastUnconstrainedTextSize
        }
    }

    companion object {
        private val TAG = DigitalClockTextView::class.simpleName!!

        private val PORTER_DUFF_XFER_MODE_PAINT =
            Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

        val FIDGET_HAPTICS =
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 0)
                .compose()

        val CHARGE_ANIMATION_DURATION = 400L
        val CHARGE_INTERPOLATOR = PathInterpolator(0.26873f, 0f, 0.45042f, 1f)
        val CHARGE_DISTS =
            listOf(
                AxisAnimation(GSFAxes.WEIGHT, 400f),
                AxisAnimation(GSFAxes.WIDTH, 0f),
                AxisAnimation(GSFAxes.ROUND, 0f),
                AxisAnimation(GSFAxes.SLANT, 0f),
            )

        val AXIS_CHANGE_ANIMATION_DURATION = 400L
        val AXIS_CHANGE_INTERPOLATOR = Interpolators.EMPHASIZED

        val FIDGET_ANIMATION_DURATION = 250L
        val FIDGET_INTERPOLATOR = PathInterpolator(0.26873f, 0f, 0.45042f, 1f)
        val FIDGET_DISTS =
            listOf(
                AxisAnimation(GSFAxes.WEIGHT, 200f),
                AxisAnimation(GSFAxes.WIDTH, 10f),
                AxisAnimation(GSFAxes.ROUND, 0f),
                AxisAnimation(GSFAxes.SLANT, 0f),
            )
    }
}
