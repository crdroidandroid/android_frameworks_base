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

package com.android.systemui.shared.clocks.view

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.VibrationEffect
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.MathUtils.lerp
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec.EXACTLY
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.android.app.animation.Interpolators
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.util.crdroid.Utils.ambientAod
import com.android.systemui.animation.AxisDefinition
import com.android.systemui.animation.GSFAxes
import com.android.systemui.animation.TextAnimator
import com.android.systemui.animation.TextAnimatorListener
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.ClockAxisStyle
import com.android.systemui.plugins.clocks.ClockLogger
import com.android.systemui.plugins.clocks.VPoint
import com.android.systemui.plugins.clocks.VPointF
import com.android.systemui.plugins.clocks.VPointF.Companion.size
import com.android.systemui.plugins.clocks.VRectF
import com.android.systemui.shared.clocks.CanvasUtil.translate
import com.android.systemui.shared.clocks.CanvasUtil.use
import com.android.systemui.shared.clocks.ClockContext
import com.android.systemui.shared.clocks.DigitTranslateAnimator
import com.android.systemui.shared.clocks.DimensionParser
import com.android.systemui.shared.clocks.FLEX_CLOCK_ID
import com.android.systemui.shared.clocks.FontTextStyle
import com.android.systemui.shared.clocks.FontUtils.set
import com.android.systemui.shared.clocks.ViewUtils.measuredSize
import com.android.systemui.shared.clocks.ViewUtils.size
import java.lang.Thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val TAG = SimpleDigitalClockTextView::class.simpleName!!

private val tempRect = Rect()

private fun Paint.getTextBounds(text: CharSequence): VRectF {
    this.getTextBounds(text, 0, text.length, tempRect)
    return VRectF(tempRect)
}

enum class VerticalAlignment {
    TOP,
    BOTTOM,
    BASELINE,
    CENTER,
}

enum class HorizontalAlignment {
    LEFT {
        override fun resolveXAlignment(view: View) = XAlignment.LEFT
    },
    RIGHT {
        override fun resolveXAlignment(view: View) = XAlignment.RIGHT
    },
    START {
        override fun resolveXAlignment(view: View): XAlignment {
            return if (view.isLayoutRtl()) XAlignment.RIGHT else XAlignment.LEFT
        }
    },
    END {
        override fun resolveXAlignment(view: View): XAlignment {
            return if (view.isLayoutRtl()) XAlignment.LEFT else XAlignment.RIGHT
        }
    },
    CENTER {
        override fun resolveXAlignment(view: View) = XAlignment.CENTER
    };

    abstract fun resolveXAlignment(view: View): XAlignment
}

enum class XAlignment {
    LEFT,
    RIGHT,
    CENTER,
}

@SuppressLint("AppCompatCustomView")
open class SimpleDigitalClockTextView(
    val clockCtx: ClockContext,
    isLargeClock: Boolean,
    attrs: AttributeSet? = null,
) : TextView(clockCtx.context, attrs) {
    val lockScreenPaint = TextPaint()
    lateinit var textStyle: FontTextStyle
    lateinit var aodStyle: FontTextStyle

    private val isLegacyFlex = clockCtx.settings.clockId == FLEX_CLOCK_ID
    private val fixedAodAxes =
        when {
            !isLegacyFlex -> fromAxes(AOD_WEIGHT_AXIS, WIDTH_AXIS)
            isLargeClock -> fromAxes(FLEX_AOD_LARGE_WEIGHT_AXIS, FLEX_AOD_WIDTH_AXIS)
            else -> fromAxes(FLEX_AOD_SMALL_WEIGHT_AXIS, FLEX_AOD_WIDTH_AXIS)
        }

    private var lsFontVariation: String
    private var aodFontVariation: String
    private var fidgetFontVariation: String

    init {
        val roundAxis = if (!isLegacyFlex) ROUND_AXIS else FLEX_ROUND_AXIS
        val lsFontAxes =
            if (!isLegacyFlex) fromAxes(LS_WEIGHT_AXIS, WIDTH_AXIS, ROUND_AXIS, SLANT_AXIS)
            else fromAxes(FLEX_LS_WEIGHT_AXIS, FLEX_LS_WIDTH_AXIS, FLEX_ROUND_AXIS, SLANT_AXIS)

        lsFontVariation = lsFontAxes.toFVar()
        aodFontVariation = fixedAodAxes.copyWith(fromAxes(roundAxis, SLANT_AXIS)).toFVar()
        fidgetFontVariation = buildFidgetVariation(lsFontAxes).toFVar()
    }

    var onViewBoundsChanged: ((VRectF) -> Unit)? = null
    private val parser = DimensionParser(clockCtx.context)
    var maxSingleDigitHeight = -1f
    var maxSingleDigitWidth = -1f
    var digitTranslateAnimator: DigitTranslateAnimator? = null
    var aodFontSizePx = -1f

    // Store the font size when there's no height constraint as a reference when adjusting font size
    private var lastUnconstrainedTextSize = Float.MAX_VALUE
    // Calculated by height of styled text view / text size
    // Used as a factor to calculate a smaller font size when text height is constrained
    @VisibleForTesting var fontSizeAdjustFactor = 1f

    private val initThread = Thread.currentThread()

    // textBounds is the size of text in LS, which only measures current text in lockscreen style
    var textBounds = VRectF.ZERO
    // prevTextBounds and targetTextBounds are to deal with dozing animation between LS and AOD
    // especially for the textView which has different bounds during the animation
    // prevTextBounds holds the state we are transitioning from
    private var prevTextBounds = VRectF.ZERO
    // targetTextBounds holds the state we are interpolating to
    private var targetTextBounds = VRectF.ZERO
    protected val logger = ClockLogger(this, clockCtx.messageBuffer, this::class.simpleName!!)
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

    fun updateColor(color: Int) {
        lockscreenColor = color
        lockScreenPaint.color = lockscreenColor
        if (dozeFraction < 1f) {
            textAnimator.setTextStyle(TextAnimator.Style(color = lockscreenColor))
        }
        invalidate()
    }

    fun updateAxes(lsAxes: ClockAxisStyle, isAnimated: Boolean) {
        lsFontVariation = lsAxes.toFVar()
        aodFontVariation = lsAxes.copyWith(fixedAodAxes).toFVar()
        fidgetFontVariation = buildFidgetVariation(lsAxes).toFVar()
        logger.updateAxes(lsFontVariation, aodFontVariation, isAnimated)

        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(lsFontVariation)
        typeface = lockScreenPaint.typeface

        updateTextBounds()

        textAnimator.setTextStyle(
            TextAnimator.Style(fVar = lsFontVariation),
            TextAnimator.Animation(
                animate = isAnimated && isAnimationEnabled,
                duration = AXIS_CHANGE_ANIMATION_DURATION,
                interpolator = aodDozingInterpolator,
            ),
        )

        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        recomputeMaxSingleDigitSizes()
        requestLayout()
        invalidate()
    }

    fun buildFidgetVariation(axes: ClockAxisStyle): ClockAxisStyle {
        return ClockAxisStyle(
            axes.items
                .map { (key, value) ->
                    FIDGET_DISTS.get(key)?.let { (dist, midpoint) ->
                        key to value + dist * if (value > midpoint) -1 else 1
                    } ?: (key to value)
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
                textAnimator =
                    TextAnimator(
                        layout,
                        typefaceCache,
                        object : TextAnimatorListener {
                            override fun onInvalidate() = invalidate()

                            override fun onRebased() = updateAnimationTextBounds()

                            override fun onPaintModified() = updateAnimationTextBounds()
                        },
                    )
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
            (parent as? FlexClockView)?.run {
                updateMeasuredSize()
                updateLocation()
            } ?: setInterpolatedLocation(measureSize)
        }

        canvas.use {
            digitTranslateAnimator?.apply { canvas.translate(currentTranslation) }
            canvas.translate(getDrawTranslation(interpBounds))
            if (isLayoutRtl()) canvas.translate(interpBounds.width - textBounds.width, 0f)
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
        (parent as? FlexClockView)?.invalidate()
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
                fVar = if (isDozing) aodFontVariation else lsFontVariation,
                color = if (isDozing && !ambientAod()) AOD_COLOR else lockscreenColor,
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
            (parent as? FlexClockView)?.requestLayout()
        }
    }

    fun animateCharge() {
        if (!this::textAnimator.isInitialized || textAnimator.isRunning) {
            // Skip charge animation if dozing animation is already playing.
            return
        }
        logger.animateCharge()

        val lsStyle = TextAnimator.Style(fVar = lsFontVariation)
        val aodStyle = TextAnimator.Style(fVar = aodFontVariation)

        textAnimator.setTextStyle(
            if (dozeFraction == 0f) aodStyle else lsStyle,
            TextAnimator.Animation(
                animate = isAnimationEnabled,
                duration = CHARGE_ANIMATION_DURATION,
                onAnimationEnd = {
                    textAnimator.setTextStyle(
                        if (dozeFraction == 0f) lsStyle else aodStyle,
                        TextAnimator.Animation(
                            animate = isAnimationEnabled,
                            duration = CHARGE_ANIMATION_DURATION,
                        ),
                    )
                },
            ),
        )
    }

    fun animateFidget(x: Float, y: Float) = animateFidget(0L)

    fun animateFidget(delay: Long) {
        if (!this::textAnimator.isInitialized || textAnimator.isRunning) {
            // Skip fidget animation if other animation is already playing.
            return
        }

        logger.animateFidget(x, y)
        clockCtx.vibrator?.vibrate(FIDGET_HAPTICS)

        textAnimator.setTextStyle(
            TextAnimator.Style(fVar = fidgetFontVariation),
            TextAnimator.Animation(
                animate = isAnimationEnabled,
                duration = FIDGET_ANIMATION_DURATION,
                interpolator = FIDGET_INTERPOLATOR,
                startDelay = delay,
                onAnimationEnd = {
                    textAnimator.setTextStyle(
                        TextAnimator.Style(fVar = lsFontVariation),
                        TextAnimator.Animation(
                            animate = isAnimationEnabled,
                            duration = FIDGET_ANIMATION_DURATION,
                            interpolator = FIDGET_INTERPOLATOR,
                        ),
                    )
                },
            ),
        )
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
        return id == R.id.HOUR_FIRST_DIGIT ||
            id == R.id.HOUR_SECOND_DIGIT ||
            id == R.id.MINUTE_FIRST_DIGIT ||
            id == R.id.MINUTE_SECOND_DIGIT
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
        lockScreenPaint.typeface = typefaceCache.getTypefaceForVariant(lsFontVariation)
        typeface = lockScreenPaint.typeface
        textStyle.lineHeight?.let { lineHeight = it.roundToInt() }

        this.aodStyle = aodStyle ?: textStyle.copy()
        aodDozingInterpolator = this.aodStyle.transitionInterpolator ?: Interpolators.LINEAR
        lockScreenPaint.strokeWidth = textBorderWidth
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        setInterpolatorPaint()
        recomputeMaxSingleDigitSizes()
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
        recomputeMaxSingleDigitSizes()

        if (this::textAnimator.isInitialized) {
            textAnimator.setTextStyle(TextAnimator.Style(textSize = lockScreenPaint.textSize))
        }
    }

    private fun recomputeMaxSingleDigitSizes() {
        maxSingleDigitHeight = 0f
        maxSingleDigitWidth = 0f

        for (i in 0..9) {
            val rectForCalculate = lockScreenPaint.getTextBounds("$i")
            maxSingleDigitHeight = max(maxSingleDigitHeight, rectForCalculate.height)
            maxSingleDigitWidth = max(maxSingleDigitWidth, rectForCalculate.width)
        }
        maxSingleDigitWidth += 2 * lockScreenPaint.strokeWidth
        maxSingleDigitHeight += 2 * lockScreenPaint.strokeWidth
    }

    /** Called without animation, can be used to set the initial state of animator */
    private fun setInterpolatorPaint() {
        if (this::textAnimator.isInitialized) {
            // set initial style
            textAnimator.textInterpolator.targetPaint.set(lockScreenPaint)
            textAnimator.textInterpolator.onTargetPaintModified()
            textAnimator.setTextStyle(
                TextAnimator.Style(
                    fVar = lsFontVariation,
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
        private val PORTER_DUFF_XFER_MODE_PAINT =
            Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

        val FIDGET_HAPTICS =
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 0)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f, 43)
                .compose()

        val CHARGE_ANIMATION_DURATION = 500L
        val AXIS_CHANGE_ANIMATION_DURATION = 400L
        val FIDGET_ANIMATION_DURATION = 250L
        val FIDGET_INTERPOLATOR = PathInterpolator(0.26873f, 0f, 0.45042f, 1f)
        val FIDGET_DISTS =
            mapOf(
                GSFAxes.WEIGHT.tag to Pair(200f, 500f),
                GSFAxes.WIDTH.tag to Pair(30f, 75f),
                GSFAxes.ROUND.tag to Pair(0f, 50f),
                GSFAxes.SLANT.tag to Pair(0f, -5f),
            )

        val AOD_COLOR = Color.WHITE
        private val LS_WEIGHT_AXIS = GSFAxes.WEIGHT to 400f
        private val AOD_WEIGHT_AXIS = GSFAxes.WEIGHT to 200f
        private val WIDTH_AXIS = GSFAxes.WIDTH to 85f
        private val ROUND_AXIS = GSFAxes.ROUND to 0f
        private val SLANT_AXIS = GSFAxes.SLANT to 0f

        // Axes for Legacy version of the Flex Clock
        private val FLEX_LS_WEIGHT_AXIS = GSFAxes.WEIGHT to 600f
        private val FLEX_AOD_LARGE_WEIGHT_AXIS = GSFAxes.WEIGHT to 74f
        private val FLEX_AOD_SMALL_WEIGHT_AXIS = GSFAxes.WEIGHT to 133f
        private val FLEX_LS_WIDTH_AXIS = GSFAxes.WIDTH to 100f
        private val FLEX_AOD_WIDTH_AXIS = GSFAxes.WIDTH to 43f
        private val FLEX_ROUND_AXIS = GSFAxes.ROUND to 100f

        private fun fromAxes(vararg axes: Pair<AxisDefinition, Float>): ClockAxisStyle {
            return ClockAxisStyle(axes.map { (def, value) -> def.tag to value }.toMap())
        }
    }
}
