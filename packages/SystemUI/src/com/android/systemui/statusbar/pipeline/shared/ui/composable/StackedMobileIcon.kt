/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.android.systemui.common.ui.compose.load
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.mobile.ui.compose.ActivityIndicators
import com.android.systemui.statusbar.pipeline.mobile.ui.model.DualSim
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.StackedMobileIconViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.BarBaseHeightFiveBarsSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.BarBaseHeightFourBarsSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.BarsLevelIncrementSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.BarsVerticalPaddingSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.ExclamationCutoutRadiusSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.ExclamationDiameterSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.ExclamationHeightSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.ExclamationHorizontalOffset
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.ExclamationVerticalSpacing
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.HorizontalPaddingFiveBarsSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.HorizontalPaddingFourBarsSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.IconHeightSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.IconPaddingSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.IconSpacingSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.IconWidthFiveBarsSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.IconWidthFourBarsSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.RatIndicatorPaddingSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.RoamingIconHeightSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.RoamingIconPaddingTopSp
import com.android.systemui.statusbar.pipeline.shared.ui.composable.StackedMobileIconDimensions.SecondaryBarHeightSp
import kotlin.math.max

/**
 * The dual sim icon that shows both connections stacked vertically with the active connection on
 * top
 */
@Composable
fun StackedMobileIcon(viewModel: StackedMobileIconViewModel, modifier: Modifier = Modifier) {
    val dualSim = viewModel.dualSim ?: return

    val contentColor = LocalContentColor.current
    val padding = with(LocalDensity.current) { IconPaddingSp.toDp() }
    val horizontalArrangement = with(LocalDensity.current) { spacedBy(IconSpacingSp.toDp()) }

    Row(
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = padding),
    ) {
        if (viewModel.activityContainerVisible) {
            ActivityIndicators(
                activityInVisible = viewModel.activityInVisible,
                activityOutVisible = viewModel.activityOutVisible,
                color = contentColor,
            )
        }

        viewModel.networkTypeIcon?.let {
            // Provide the RAT context needed for the resource overlays
            val ratContext = viewModel.mobileContext ?: LocalContext.current
            CompositionLocalProvider(LocalContext provides ratContext) {
                val height = with(LocalDensity.current) { IconHeightSp.toDp() }
                val paddingEnd = with(LocalDensity.current) { RatIndicatorPaddingSp.toDp() }
                Image(
                    painter = painterResource(it.resId),
                    contentDescription = it.contentDescription?.load(),
                    modifier = Modifier.height(height).padding(end = paddingEnd),
                    colorFilter = ColorFilter.tint(contentColor, BlendMode.SrcIn),
                    contentScale = ContentScale.FillHeight,
                )
            }
        }

        StackedMobileIcon(
            viewModel = dualSim,
            color = contentColor,
            contentDescription = viewModel.contentDescription,
        )

        if (viewModel.isRoamingVisible) {
            val height = with(LocalDensity.current) { RoamingIconHeightSp.toDp() }
            val paddingTop = with(LocalDensity.current) { RoamingIconPaddingTopSp.toDp() }
            Image(
                painter = painterResource(R.drawable.stat_sys_roaming_updated),
                contentDescription = stringResource(R.string.data_connection_roaming),
                modifier = Modifier.height(height).offset(y = paddingTop),
                colorFilter = ColorFilter.tint(contentColor, BlendMode.SrcIn),
                contentScale = ContentScale.FillHeight,
            )
        }
    }
}

@Composable
private fun StackedMobileIcon(
    viewModel: DualSim,
    color: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    // Removing 1 to get the real number of bars
    val numberOfBars = max(viewModel.primary.numberOfLevels, viewModel.secondary.numberOfLevels) - 1
    val dimensions = if (numberOfBars == 5) FiveBarsDimensions else FourBarsDimensions
    val iconSize =
        with(LocalDensity.current) { dimensions.totalWidth.toDp() to IconHeightSp.toDp() }

    Canvas(
        modifier
            .width(iconSize.first)
            .height(iconSize.second)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        scale(if (rtl) -1f else 1f, 1f) {
            val verticalPaddingPx = BarsVerticalPaddingSp.roundToPx()
            val horizontalPaddingPx = dimensions.barsHorizontalPadding.roundToPx()
            val totalPaddingWidthPx = horizontalPaddingPx * (numberOfBars - 1)

            val barWidthPx = (size.width - totalPaddingWidthPx) / numberOfBars
            val dotHeightPx = SecondaryBarHeightSp.toPx()
            val baseBarHeightPx = dimensions.barBaseHeight.toPx()

            var xOffsetPx = 0f
            for (bar in 1..numberOfBars) {
                // Bottom dots representing secondary sim
                val dotYOffsetPx = size.height - dotHeightPx
                if (bar <= viewModel.secondary.numberOfLevels) {
                    drawMobileIconBar(
                        level = viewModel.secondary.level,
                        bar = bar,
                        topLeft = Offset(xOffsetPx, dotYOffsetPx),
                        size = Size(barWidthPx, dotHeightPx),
                        activeColor = color,
                    )
                }

                // Top bars representing primary sim
                if (bar <= viewModel.primary.numberOfLevels) {
                    val barHeightPx = baseBarHeightPx + (BarsLevelIncrementSp.toPx() * (bar - 1))
                    val barYOffsetPx = dotYOffsetPx - verticalPaddingPx - barHeightPx
                    drawMobileIconBar(
                        level = viewModel.primary.level,
                        bar = bar,
                        topLeft = Offset(xOffsetPx, barYOffsetPx),
                        size = Size(barWidthPx, barHeightPx),
                        activeColor = color,
                    )
                }

                xOffsetPx += barWidthPx + horizontalPaddingPx
            }

            if (viewModel.primary.showExclamationMark) {
                drawExclamationCutout(color)
            }
        }
    }
}

private fun DrawScope.drawMobileIconBar(
    level: Int,
    bar: Int,
    topLeft: Offset,
    size: Size,
    activeColor: Color,
    inactiveColor: Color = activeColor.copy(alpha = .3f),
    cornerRadius: CornerRadius = CornerRadius(size.width / 2),
) {
    drawRoundRect(
        color = if (level >= bar) activeColor else inactiveColor,
        topLeft = topLeft,
        size = size,
        cornerRadius = cornerRadius,
    )
}

private fun DrawScope.drawExclamationCutout(color: Color) {
    // Exclamation mark is bottom aligned with the canvas
    val exclamationDiameterPx = ExclamationDiameterSp.toPx()
    val exclamationRadiusPx = ExclamationDiameterSp.toPx() / 2
    val exclamationTotalHeight =
        ExclamationHeightSp.toPx() + ExclamationVerticalSpacing.toPx() + exclamationDiameterPx
    val exclamationDotCenter =
        Offset(size.width - ExclamationHorizontalOffset.toPx(), size.height - exclamationRadiusPx)
    val exclamationMarkTopLeft =
        Offset(exclamationDotCenter.x - exclamationRadiusPx, size.height - exclamationTotalHeight)
    val exclamationCornerRadius = CornerRadius(exclamationRadiusPx)
    val cutoutCenter = Offset(exclamationDotCenter.x, size.height - (exclamationTotalHeight / 2))

    // Transparent cutout
    drawCircle(
        color = Color.Transparent,
        radius = ExclamationCutoutRadiusSp.toPx(),
        center = cutoutCenter,
        blendMode = BlendMode.SrcIn,
    )

    // Top bar for the exclamation mark
    drawRoundRect(
        color = color,
        topLeft = exclamationMarkTopLeft,
        size = Size(exclamationDiameterPx, ExclamationHeightSp.toPx()),
        cornerRadius = exclamationCornerRadius,
    )

    // Bottom circle for the exclamation mark
    drawCircle(color = color, center = exclamationDotCenter, radius = exclamationRadiusPx)
}

private abstract class BarsDependentDimensions(
    val totalWidth: TextUnit,
    val barsHorizontalPadding: TextUnit,
    val barBaseHeight: TextUnit,
)

private object FourBarsDimensions :
    BarsDependentDimensions(
        IconWidthFourBarsSp,
        HorizontalPaddingFourBarsSp,
        BarBaseHeightFourBarsSp,
    )

private object FiveBarsDimensions :
    BarsDependentDimensions(
        IconWidthFiveBarsSp,
        HorizontalPaddingFiveBarsSp,
        BarBaseHeightFiveBarsSp,
    )

private object StackedMobileIconDimensions {
    // Common dimensions
    val IconHeightSp = 12.sp
    val IconPaddingSp = 4.sp
    val IconSpacingSp = 2.sp
    val BarsVerticalPaddingSp = 1.5.sp
    val BarsLevelIncrementSp = 1.sp
    val SecondaryBarHeightSp = 3.sp
    val RatIndicatorPaddingSp = 4.sp // 6.sp total between RAT and bars
    val RoamingIconHeightSp = 10.sp
    val RoamingIconPaddingTopSp = 1.sp

    // Exclamation cutout dimensions
    val ExclamationCutoutRadiusSp = 5.sp
    val ExclamationDiameterSp = 1.5.sp
    val ExclamationHeightSp = 4.5.sp
    val ExclamationVerticalSpacing = 1.sp
    val ExclamationHorizontalOffset = 1.sp

    // Dimensions dependant on the number of total bars
    val IconWidthFiveBarsSp = 18.5.sp
    val IconWidthFourBarsSp = 16.sp
    val HorizontalPaddingFiveBarsSp = 1.5.sp
    val HorizontalPaddingFourBarsSp = 2.sp
    val BarBaseHeightFiveBarsSp = 3.5.sp
    val BarBaseHeightFourBarsSp = 4.5.sp
}
