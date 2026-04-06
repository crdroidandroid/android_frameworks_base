/*
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2025-2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.battery

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.PathParser
import android.util.TypedValue
import androidx.compose.ui.graphics.asAndroidPath
import com.android.settingslib.R
import com.android.settingslib.Utils
import com.android.systemui.res.R as SysUiR
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import kotlin.math.min

open class ThemedBatteryDrawable(private val context: Context, frameColor: Int) : Drawable() {

    private val perimeterPath = Path()
    private val scaledPerimeter = Path()
    private val errorPerimeterPath = Path()
    private val scaledErrorPerimeter = Path()
    private val fillMask = Path()
    private val scaledFill = Path()
    private val fillRect = RectF()
    private val levelRect = RectF()
    private val levelPath = Path()
    private val scaleMatrix = Matrix()
    private val padding = Rect()
    private val unifiedPath = Path()

    private val boltPath = Path()
    private val scaledBolt = Path()

    private val plusPath = Path()
    private val scaledPlus = Path()

    private var intrinsicHeight: Int
    private var intrinsicWidth: Int

    private var invertFillIcon = false

    private var colorLevels: IntArray

    private var fillColor: Int = Color.WHITE
    private var backgroundColor: Int = Color.WHITE
    private var levelColor: Int = Color.WHITE
    private var glyphColor: Int = Color.WHITE

    private var dualTone = false

    private var batteryLevel = 0

    private val invalidateRunnable: () -> Unit = {
        invalidateSelf()
    }

    open var criticalLevel: Int = run {
        val overrideLevel = context.resources.getInteger(
                SysUiR.integer.config_batterymeterCriticalLevel)
        if (overrideLevel >= 0) overrideLevel
        else context.resources.getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel)
    }

    var charging = false
        set(value) {
            field = value
            postInvalidate()
        }

    var powerSaveEnabled = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val fillColorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.SRC
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillColorStrokeProtection = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.CLEAR
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = Utils.getColorStateListDefaultColor(context, R.color.batterymeter_saver_color)
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
        p.blendMode = BlendMode.SRC
    }

    private val dualToneBackgroundFill = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 85
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private var width: Float
    private var height: Float
    private var horizontalFill: Boolean
    private var reverseFill: Boolean = false
    private var levelCornerRadius: Float = 0f
    private var powerSaveDrawError: Boolean = false
    private val levelCornerRadii = FloatArray(8)
    private var chargingFillColorOverride: Int = 0
    private var powerSaveFillColorOverride: Int = 0
    private var textStyleMode: Int = TEXT_STYLE_TWO_TONE_CENTERED
    private var boltKnockout: Boolean = false
    private var textSizeOverride: Int = 0
    private var percentWhileChargingAllowed: Boolean = false
    private var glyphFollowFrame: Boolean = false
    private val knockoutXfermode =
            PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    var showPercent: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.style = Paint.Style.FILL
    }

    private val digitGlyphs: List<BatteryGlyph> = listOf(
            BatteryGlyph.Zero, BatteryGlyph.One, BatteryGlyph.Two,
            BatteryGlyph.Three, BatteryGlyph.Four, BatteryGlyph.Five,
            BatteryGlyph.Six, BatteryGlyph.Seven, BatteryGlyph.Eight,
            BatteryGlyph.Nine,
    )

    private val perimeterBoundsF = RectF()
    private val boltBoundsF = RectF()
    private val tmpRect = RectF()

    init {
        val res = context.resources
        width = res.getInteger(SysUiR.integer.config_batterymeterWidth).toFloat()
        height = res.getInteger(SysUiR.integer.config_batterymeterHeight).toFloat()
        horizontalFill = res.getBoolean(SysUiR.bool.config_batterymeterHorizontalFill)

        val density = res.displayMetrics.density
        intrinsicHeight = (height * density).toInt()
        intrinsicWidth = (width * density).toInt()

        val levels = res.obtainTypedArray(R.array.batterymeter_color_levels)
        val colors = res.obtainTypedArray(R.array.batterymeter_color_values)
        val N = levels.length()
        colorLevels = IntArray(2 * N)
        for (i in 0 until N) {
            colorLevels[2 * i] = levels.getInt(i, 0)
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colorLevels[2 * i + 1] = Utils.getColorAttrDefaultColor(context,
                        colors.getThemeAttributeId(i, 0))
            } else {
                colorLevels[2 * i + 1] = colors.getColor(i, 0)
            }
        }
        levels.recycle()
        colors.recycle()

        loadPaths()
    }

    override fun draw(c: Canvas) {
        c.saveLayer(null, null)
        unifiedPath.reset()
        levelPath.reset()
        levelRect.set(fillRect)
        val fillFraction = batteryLevel / 100f
        if (horizontalFill) {
            if (reverseFill) {
                val fillLeft =
                        if (batteryLevel >= FULL_LEVEL_THRESHOLD)
                            fillRect.left
                        else
                            fillRect.right - (fillRect.width() * fillFraction)
                levelRect.left = Math.floor(fillLeft.toDouble()).toFloat()
            } else {
                val fillRight =
                        if (batteryLevel >= FULL_LEVEL_THRESHOLD)
                            fillRect.right
                        else
                            fillRect.left + (fillRect.width() * fillFraction)
                levelRect.right = Math.ceil(fillRight.toDouble()).toFloat()
            }
        } else {
            val fillTop =
                    if (batteryLevel >= FULL_LEVEL_THRESHOLD)
                        fillRect.top
                    else
                        fillRect.top + (fillRect.height() * (1 - fillFraction))
            levelRect.top = Math.floor(fillTop.toDouble()).toFloat()
        }
        if (levelCornerRadius > 0f) {
            levelPath.addRoundRect(levelRect, levelCornerRadii, Path.Direction.CCW)
        } else {
            levelPath.addRect(levelRect, Path.Direction.CCW)
        }

        unifiedPath.addPath(scaledPerimeter)
        if (!dualTone) {
            unifiedPath.op(levelPath, Path.Op.UNION)
        }

        val wantsText = showPercent && batteryLevel < 100 &&
                (!charging || percentWhileChargingAllowed)
        val isKnockoutText = textStyleMode == TEXT_STYLE_KNOCKOUT_CENTERED ||
                textStyleMode == TEXT_STYLE_KNOCKOUT_OFFSET

        fillPaint.color = levelColor

        if (charging) {
            unifiedPath.op(scaledBolt, Path.Op.DIFFERENCE)
            if (!boltKnockout && !invertFillIcon) {
                c.drawPath(scaledBolt, fillPaint)
            }
        }

        if (dualTone) {
            c.drawPath(unifiedPath, dualToneBackgroundFill)
            c.save()
            if (horizontalFill) {
                if (reverseFill) {
                    c.clipRect(
                            bounds.right - bounds.width() * fillFraction,
                            0f,
                            bounds.right.toFloat(),
                            bounds.bottom.toFloat())
                } else {
                    c.clipRect(0f, 0f,
                            bounds.width() * fillFraction,
                            bounds.bottom.toFloat())
                }
            } else {
                c.clipRect(0f,
                        bounds.bottom - bounds.height() * fillFraction,
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat())
            }
            fillPaint.color = levelColor
            c.drawPath(unifiedPath, fillPaint)
            c.restore()
        } else {
            if (showPercent) {
                c.drawPath(scaledFill, dualToneBackgroundFill)
            }
            fillPaint.color = fillColor
            c.drawPath(unifiedPath, fillPaint)
            fillPaint.color = levelColor

            if ((charging && (chargingFillColorOverride ushr 24) != 0) ||
                    (powerSaveEnabled && (powerSaveFillColorOverride ushr 24) != 0) ||
                    (batteryLevel <= criticalLevel && !charging)) {
                c.save()
                c.clipPath(scaledFill)
                c.drawPath(levelPath, fillPaint)
                c.restore()
            }
        }

        if (charging && !boltKnockout) {
            c.clipOutPath(scaledBolt)
            if (invertFillIcon) {
                c.drawPath(scaledBolt, fillColorStrokePaint)
            } else {
                c.drawPath(scaledBolt, fillColorStrokeProtection)
            }
        } else if (powerSaveEnabled) {
            if (powerSaveDrawError) {
                c.drawPath(scaledErrorPerimeter, errorPaint)
                if (!showPercent) {
                    c.drawPath(scaledPlus, errorPaint)
                }
            } else {
                c.drawPath(levelPath, errorPaint)
                if (!showPercent) {
                    fillPaint.color = fillColor
                    c.drawPath(scaledPlus, fillPaint)
                }
            }
        }

        if (wantsText && isKnockoutText) {
            drawPercentGlyphs(c, knockout = true)
        }

        c.restore()

        if (wantsText && !isKnockoutText && textStyleMode != TEXT_STYLE_NONE) {
            drawPercentGlyphs(c, knockout = false)
        }
    }

    private fun drawPercentGlyphs(c: Canvas, knockout: Boolean) {
        val label = batteryLevel.toString()
        if (label.isEmpty()) return

        var totalGlyphWidth = 0f
        var maxGlyphHeight = 0f
        for (i in label.indices) {
            val d = label[i] - '0'
            if (d !in 0..9) continue
            val g = digitGlyphs[d]
            if (i > 0) totalGlyphWidth += INTER_GLYPH_PADDING
            totalGlyphWidth += g.width
            if (g.height > maxGlyphHeight) maxGlyphHeight = g.height
        }
        if (maxGlyphHeight <= 0f) return

        val pb = when {
            !fillRect.isEmpty -> fillRect
            !perimeterBoundsF.isEmpty -> perimeterBoundsF
            else -> {
                tmpRect.set(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
                tmpRect
            }
        }
        val pbHeight = pb.height()

        val hasBolt = charging && !scaledBolt.isEmpty
        if (hasBolt) scaledBolt.computeBounds(boltBoundsF, true)

        val availableLeft: Float
        val availableRight: Float
        if (hasBolt) {
            val gap = pbHeight * GLYPH_BOLT_GAP_FRACTION
            when {
                boltBoundsF.centerX() > pb.centerX() -> {
                    availableLeft = pb.left
                    availableRight = boltBoundsF.left - gap
                }
                boltBoundsF.centerX() < pb.centerX() -> {
                    availableLeft = boltBoundsF.right + gap
                    availableRight = pb.right
                }
                else -> {
                    availableLeft = pb.left
                    availableRight = pb.right
                }
            }
        } else {
            availableLeft = pb.left
            availableRight = pb.right
        }
        val availableWidth = (availableRight - availableLeft).coerceAtLeast(1f)

        val sizeFraction =
                if (textSizeOverride > 0) textSizeOverride / 100f
                else DEFAULT_GLYPH_SIZE_FRACTION
        val targetH = pbHeight * sizeFraction
        val targetWBudget = availableWidth * GLYPH_WIDTH_BUDGET_FRACTION
        val scale = min(targetH / maxGlyphHeight, targetWBudget / totalGlyphWidth)

        val scaledW = totalGlyphWidth * scale
        val scaledH = maxGlyphHeight * scale

        val cx = when {
            !hasBolt -> (availableLeft + availableRight) / 2f
            boltBoundsF.centerX() > pb.centerX() -> availableRight - scaledW / 2f
            boltBoundsF.centerX() < pb.centerX() -> availableLeft + scaledW / 2f
            else -> (availableLeft + availableRight) / 2f
        }
        val cy = pb.centerY()
        val startX = cx - scaledW / 2f
        val topY = cy - scaledH / 2f

        if (knockout) {
            glyphPaint.xfermode = knockoutXfermode
            glyphPaint.color = Color.BLACK
        } else {
            glyphPaint.xfermode = null
            glyphPaint.color = if (glyphFollowFrame) fillColor else glyphColor
        }

        c.save()
        c.translate(startX, topY)
        c.scale(scale, scale)
        var xCursor = 0f
        for (i in label.indices) {
            val d = label[i] - '0'
            if (d !in 0..9) continue
            val g = digitGlyphs[d]
            val vOff = (maxGlyphHeight - g.height) / 2f
            c.save()
            c.translate(xCursor, vOff)
            c.drawPath(g.path.asAndroidPath(), glyphPaint)
            c.restore()
            xCursor += g.width + INTER_GLYPH_PADDING
        }
        c.restore()
        glyphPaint.xfermode = null
    }

    private fun batteryColorForLevel(level: Int): Int {
        return when {
            charging && (chargingFillColorOverride ushr 24) != 0 -> chargingFillColorOverride
            powerSaveEnabled && (powerSaveFillColorOverride ushr 24) != 0 ->
                    powerSaveFillColorOverride
            charging || powerSaveEnabled -> fillColor
            else -> getColorForLevel(level)
        }
    }

    private fun getColorForLevel(level: Int): Int {
        var thresh: Int
        var color = 0
        var i = 0
        while (i < colorLevels.size) {
            thresh = colorLevels[i]
            color = colorLevels[i + 1]
            if (level <= thresh) {
                return if (i == colorLevels.size - 2) {
                    fillColor
                } else {
                    color
                }
            }
            i += 2
        }
        return color
    }

    override fun setAlpha(alpha: Int) {
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        fillColorStrokePaint.colorFilter = colorFilter
        dualToneBackgroundFill.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }

    override fun getIntrinsicHeight(): Int {
        return intrinsicHeight
    }

    override fun getIntrinsicWidth(): Int {
        return intrinsicWidth
    }

    public open fun setBatteryLevel(l: Int) {
        invertFillIcon = when {
            l >= HYSTERESIS_INVERT_HIGH -> true
            l <= HYSTERESIS_INVERT_LOW -> false
            else -> invertFillIcon
        }
        batteryLevel = l
        levelColor = batteryColorForLevel(batteryLevel)
        invalidateSelf()
    }

    public fun getBatteryLevel(): Int {
        return batteryLevel
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateSize()
    }

    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padding.left = left
        padding.top = top
        padding.right = right
        padding.bottom = bottom

        updateSize()
    }

    fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        fillColor = if (dualTone) fgColor else singleToneColor

        fillPaint.color = fillColor
        fillColorStrokePaint.color = fillColor

        backgroundColor = bgColor
        dualToneBackgroundFill.color = bgColor

        levelColor = batteryColorForLevel(batteryLevel)

        invalidateSelf()
    }

    fun setGlyphColor(color: Int) {
        if (glyphColor != color) {
            glyphColor = color
            invalidateSelf()
        }
    }

    private fun postInvalidate() {
        unscheduleSelf(invalidateRunnable)
        scheduleSelf(invalidateRunnable, 0)
    }

    private fun updateSize() {
        val b = bounds
        if (b.isEmpty) {
            scaleMatrix.setScale(1f, 1f)
        } else {
            scaleMatrix.setScale(b.right / width, b.bottom / height)
        }

        perimeterPath.transform(scaleMatrix, scaledPerimeter)
        scaledPerimeter.computeBounds(perimeterBoundsF, true)
        errorPerimeterPath.transform(scaleMatrix, scaledErrorPerimeter)
        fillMask.transform(scaleMatrix, scaledFill)
        scaledFill.computeBounds(fillRect, true)
        boltPath.transform(scaleMatrix, scaledBolt)
        plusPath.transform(scaleMatrix, scaledPlus)

        val scaledStrokeWidth =
                Math.max(b.right / width * PROTECTION_STROKE_WIDTH, PROTECTION_MIN_STROKE_WIDTH)

        fillColorStrokePaint.strokeWidth = scaledStrokeWidth
        fillColorStrokeProtection.strokeWidth = scaledStrokeWidth
    }

    private fun loadPaths() {
        val pathString = context.resources.getString(
                SysUiR.string.config_batterymeterPerimeterPath)
        perimeterPath.set(PathParser.createPathFromPathData(pathString))
        perimeterPath.computeBounds(RectF(), true)

        val errorPathString = context.resources.getString(
                SysUiR.string.config_batterymeterErrorPerimeterPath)
        errorPerimeterPath.set(PathParser.createPathFromPathData(errorPathString))
        errorPerimeterPath.computeBounds(RectF(), true)

        val fillMaskString = context.resources.getString(
                SysUiR.string.config_batterymeterFillMask)
        fillMask.set(PathParser.createPathFromPathData(fillMaskString))
        fillMask.computeBounds(fillRect, true)

        val boltPathString = context.resources.getString(
                SysUiR.string.config_batterymeterBoltPath)
        boltPath.set(PathParser.createPathFromPathData(boltPathString))

        val plusPathString = context.resources.getString(
                SysUiR.string.config_batterymeterPowersavePath)
        plusPath.set(PathParser.createPathFromPathData(plusPathString))

        dualTone = context.resources.getBoolean(
                SysUiR.bool.config_batterymeterDualTone)

        val res = context.resources
        width = res.getInteger(SysUiR.integer.config_batterymeterWidth).toFloat()
        height = res.getInteger(SysUiR.integer.config_batterymeterHeight).toFloat()
        horizontalFill = res.getBoolean(SysUiR.bool.config_batterymeterHorizontalFill)
        reverseFill = res.getBoolean(SysUiR.bool.config_batterymeterReverseFill)
        powerSaveDrawError = res.getBoolean(SysUiR.bool.config_batterymeterPowerSaveDrawError)
        levelCornerRadius =
                res.getInteger(SysUiR.integer.config_batterymeterLevelCornerRadius).toFloat()
        for (i in 0 until LEVEL_CORNER_COUNT) levelCornerRadii[i] = levelCornerRadius
        chargingFillColorOverride =
                res.getColor(SysUiR.color.config_batterymeterChargingFillColor, null)
        powerSaveFillColorOverride =
                res.getColor(SysUiR.color.config_batterymeterPowerSaveFillColor, null)
        textStyleMode =
                res.getInteger(SysUiR.integer.config_batterymeterTextStyle)
        boltKnockout =
                res.getBoolean(SysUiR.bool.config_batterymeterBoltKnockout)
        textSizeOverride =
                res.getInteger(SysUiR.integer.config_batterymeterTextSizePct)
        percentWhileChargingAllowed =
                res.getBoolean(SysUiR.bool.config_themedBatteryPercentWhileCharging)
        glyphFollowFrame =
                res.getBoolean(SysUiR.bool.config_batterymeterGlyphFollowFrame)
        val density = res.displayMetrics.density
        intrinsicWidth = (width * density).toInt()
        intrinsicHeight = (height * density).toInt()
        updateSize()
    }

    companion object {
        const val DEFAULT_WIDTH = 12f
        const val DEFAULT_HEIGHT = 20f
        private const val PROTECTION_STROKE_WIDTH = 3f
        const val PROTECTION_MIN_STROKE_WIDTH = 6f

        const val TEXT_STYLE_NONE = 0
        const val TEXT_STYLE_TWO_TONE_CENTERED = 1
        const val TEXT_STYLE_TWO_TONE_OFFSET = 2
        const val TEXT_STYLE_KNOCKOUT_CENTERED = 3
        const val TEXT_STYLE_KNOCKOUT_OFFSET = 4

        private const val FULL_LEVEL_THRESHOLD = 95
        private const val HYSTERESIS_INVERT_HIGH = 67
        private const val HYSTERESIS_INVERT_LOW = 33
        private const val LEVEL_CORNER_COUNT = 8

        private const val DEFAULT_GLYPH_SIZE_FRACTION = 0.60f
        private const val GLYPH_WIDTH_BUDGET_FRACTION = 0.90f
        private const val GLYPH_BOLT_GAP_FRACTION = 0.06f
        private const val INTER_GLYPH_PADDING = 1f
    }
}
