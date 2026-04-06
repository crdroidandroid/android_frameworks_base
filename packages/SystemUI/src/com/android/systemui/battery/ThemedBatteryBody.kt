/*
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

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.android.systemui.FontStyles
import com.android.systemui.statusbar.connectivity.ThemeIconController
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryMeasurePolicy
import kotlin.math.min

@Composable
fun ThemedBatteryBody(
    levelProvider: () -> Int?,
    colorsProvider: () -> BatteryColors,
    showLevelProvider: () -> Boolean = { false },
    attr: BatteryGlyph? = null,
    contentDescription: String = "",
) {
    val context = LocalContext.current
    val themeVersion by ThemeIconController.themeVersion.collectAsStateWithLifecycle()
    val isPillStyle = remember(context, themeVersion) {
        context.resources.getBoolean(R.bool.config_themedBatteryPillStyle)
    }
    val drawable = remember(context, themeVersion) { ThemedBatteryDrawable(context, 0) }
    val tw = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
    val th = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
    val modifier = Modifier.layoutId(BatteryMeasurePolicy.LayoutId.FrameThemed(tw, th))

    if (isPillStyle) {
        PillBatteryBody(
            levelProvider = levelProvider,
            colorsProvider = colorsProvider,
            showLevelProvider = showLevelProvider,
            attr = attr,
            modifier = modifier,
            contentDescription = contentDescription,
        )
    } else {
        PathBatteryBody(
            drawable = drawable,
            levelProvider = levelProvider,
            colorsProvider = colorsProvider,
            showLevelProvider = showLevelProvider,
            attr = attr,
            modifier = modifier,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun PathBatteryBody(
    drawable: ThemedBatteryDrawable,
    levelProvider: () -> Int?,
    colorsProvider: () -> BatteryColors,
    showLevelProvider: () -> Boolean,
    attr: BatteryGlyph?,
    modifier: Modifier,
    contentDescription: String,
) {
    val isCharging = attr is BatteryGlyph.Bolt

    Canvas(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        contentDescription = contentDescription,
    ) {
        val level = levelProvider()
        val colors = when (val provided = colorsProvider()) {
            is BatteryColors.DarkTheme -> BatteryColors.DarkTheme.Default
            is BatteryColors.LightTheme -> BatteryColors.LightTheme.Default
            else -> provided
        }
        val showLevel = showLevelProvider()

        drawable.setBatteryLevel(level ?: 0)
        drawable.charging = isCharging
        drawable.powerSaveEnabled = attr is BatteryGlyph.Plus
        drawable.showPercent = showLevel
        drawable.setColors(colors.fill.toArgb(), colors.backgroundWithGlyph.toArgb(), colors.fill.toArgb())
        drawable.setGlyphColor(colors.glyph.toArgb())

        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        val dw: Int
        val dh: Int
        val left: Float
        val top: Float
        if (iw > 0 && ih > 0) {
            val s = min(size.width / iw, size.height / ih)
            dw = (iw * s).toInt()
            dh = (ih * s).toInt()
            left = (size.width - dw) / 2f
            top = (size.height - dh) / 2f
        } else {
            dw = size.width.toInt()
            dh = size.height.toInt()
            left = 0f
            top = 0f
        }
        drawable.setBounds(0, 0, dw, dh)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            native.translate(left, top)
            drawable.draw(native)
            native.restoreToCount(save)
        }
    }
}

@Composable
private fun PillBatteryBody(
    levelProvider: () -> Int?,
    colorsProvider: () -> BatteryColors,
    showLevelProvider: () -> Boolean,
    attr: BatteryGlyph?,
    modifier: Modifier,
    contentDescription: String,
) {
    val context = LocalContext.current
    val iconLeading = remember(context) {
        context.resources.getBoolean(R.bool.config_themedBatteryPillIconLeading)
    }
    val isOutline = remember(context) {
        context.resources.getBoolean(R.bool.config_themedBatteryPillOutline)
    }
    val hasNub = remember(context) {
        context.resources.getBoolean(R.bool.config_themedBatteryPillNub)
    }
    val typeface = remember { Typeface.create(FontStyles.GSF_LABEL_LARGE_EMPHASIZED, Typeface.NORMAL) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier,
        contentDescription = contentDescription,
    ) {
        val level = levelProvider()
        val colors = colorsProvider()
        val showLevel = showLevelProvider()
        val isCharging = attr is BatteryGlyph.Bolt
        val isPowerSave = attr is BatteryGlyph.Plus

        val accentColor = colors.fill
        val strokeWidth = size.height * 0.1f
        val cornerRadius = (size.height - strokeWidth) / 2f

        val nubW = size.height * 0.14f
        val nubH = size.height * 0.42f
        val nubR = nubW / 2f
        val bodyW = if (hasNub) size.width - nubW else size.width

        val bodyPath = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f, top = 0f,
                    right = bodyW, bottom = size.height,
                    cornerRadius = CornerRadius(cornerRadius),
                )
            )
        }
        val fullPath = if (hasNub) {
            Path().apply {
                addPath(bodyPath)
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = bodyW,
                        top = (size.height - nubH) / 2f,
                        right = bodyW + nubW,
                        bottom = (size.height + nubH) / 2f,
                        cornerRadius = CornerRadius(nubR),
                    )
                )
            }
        } else bodyPath

        if (isOutline) {
            drawPath(
                fullPath,
                color = accentColor,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        } else {
            drawPath(fullPath, color = accentColor, style = Fill)
        }

        val textColor = if (isOutline) accentColor else Color.White
        val textResult = if (showLevel && level != null) {
            textMeasurer.measure(
                text = level.toString(),
                style = TextStyle(fontSize = 6.sp, fontFamily = FontFamily(typeface), color = textColor),
            )
        } else null

        val iconGlyph = if (isCharging || isPowerSave) attr else null
        val iconPadding = 1.2.sp.toPx()
        val iconScale = if (iconGlyph != null) {
            min(size.height * 0.5f / iconGlyph.height, size.height * 0.5f / iconGlyph.width)
        } else 0f
        val iconW = if (iconGlyph != null) iconGlyph.width * iconScale else 0f
        val textW = textResult?.size?.width?.toFloat() ?: 0f
        val totalW = when {
            iconGlyph != null && textResult != null -> iconW + iconPadding + textW
            iconGlyph != null -> iconW
            else -> textW
        }

        val contentRight = if (hasNub) bodyW else size.width
        val startX = (contentRight - totalW) / 2f
        val centerY = size.height / 2f

        if (iconGlyph != null) {
            val iconX = if (iconLeading) startX else startX + textW + iconPadding
            val iconY = centerY - iconGlyph.height * iconScale / 2f
            scale(iconScale, Offset(iconX, iconY)) {
                drawPath(iconGlyph.path, textColor)
            }
        }
        if (textResult != null) {
            val textX = if (iconLeading && iconGlyph != null) startX + iconW + iconPadding else startX
            drawText(textResult, topLeft = Offset(textX, centerY - textResult.size.height / 2f))
        }
    }
}
