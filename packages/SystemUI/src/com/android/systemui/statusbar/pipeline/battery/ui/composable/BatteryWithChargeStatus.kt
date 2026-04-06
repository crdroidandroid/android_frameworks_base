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

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import android.graphics.Rect
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.load
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryNextToPercentViewModel
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel

enum class ShowPercentMode {
    Always,
    FollowSetting,
    WhenCharging,
    PreferEstimate,
}

/**
 * Render the new [BatteryCanvas] icon, and optionally add a percentage or charging estimate text
 * alongside it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatteryWithChargeStatus(
    viewModelFactory: BatteryNextToPercentViewModel.Factory,
    isDarkProvider: () -> IsAreaDark,
    showPercentMode: ShowPercentMode,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        rememberViewModel(traceName = "BatteryWithPercent") { viewModelFactory.create() }

    val ctx = LocalContext.current
    val themedIconHeightPx =
        ctx.resources.getDimensionPixelSize(
            com.android.systemui.res.R.dimen.config_batterymeterIconHeight)
    val batteryHeight =
        with(LocalDensity.current) {
            if (themedIconHeightPx > 0) themedIconHeightPx.toDp()
            else BatteryViewModel.getStatusBarBatteryHeight(ctx).toDp()
        }

    val textStyle =
        with(LocalDensity.current) {
            BatteryViewModel.getStatusBarBatteryTextStyle(LocalContext.current)
        }

    var bounds by remember { mutableStateOf(Rect()) }

    Row(
        modifier =
            modifier.onLayoutRectChanged { relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val path = viewModel.batteryFrame

        val colorProvider = {
            if (isDarkProvider().isDarkTheme(bounds)) {
                viewModel.colorProfile.dark
            } else {
                viewModel.colorProfile.light
            }
        }

        BatteryCanvas(
            path = path,
            innerWidth = viewModel.innerWidth,
            innerHeight = viewModel.innerHeight,
            glyphs = viewModel.glyphList,
            level = viewModel.level,
            isFull = viewModel.isFull,
            colorsProvider = colorProvider,
            modifier =
                Modifier.height(batteryHeight).align(Alignment.CenterVertically).wrapContentWidth(),
            contentDescription = viewModel.contentDescription.load() ?: "",
        )
        if (shouldShowPercent(showPercentMode, viewModel)) {
            // The text can just use the Default.fill color, since we don't want to colorize it
            val colorProducer = {
                if (isDarkProvider().isDarkTheme(bounds)) {
                    BatteryColors.DarkTheme.Default.fill
                } else {
                    BatteryColors.LightTheme.Default.fill
                }
            }
            val textToShow =
                if (showPercentMode == ShowPercentMode.PreferEstimate && !viewModel.isCharging) {
                    viewModel.batteryTimeRemainingEstimate ?: viewModel.batteryPercent
                } else {
                    viewModel.batteryPercent
                }

            textToShow?.let {
                Spacer(modifier.width(4.dp))
                BasicText(text = it, color = colorProducer, style = textStyle)
            }
        }
    }
}

private fun shouldShowPercent(
    percentMode: ShowPercentMode,
    viewModel: BatteryNextToPercentViewModel,
): Boolean {
    return when (percentMode) {
        ShowPercentMode.Always -> true
        ShowPercentMode.PreferEstimate -> true
        ShowPercentMode.FollowSetting -> viewModel.isBatteryPercentSettingEnabled
        ShowPercentMode.WhenCharging -> viewModel.isCharging
    }
}
