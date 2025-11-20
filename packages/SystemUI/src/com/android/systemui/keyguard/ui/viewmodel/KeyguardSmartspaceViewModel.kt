/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardSmartspaceInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardSmartspaceViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val smartspaceController: LockscreenSmartspaceController,
    keyguardClockViewModel: KeyguardClockViewModel,
    private val smartspaceInteractor: KeyguardSmartspaceInteractor,
    shadeModeInteractor: ShadeModeInteractor,
) {
    /** Whether the smartspace section is available in the build. */
    val isSmartspaceEnabled: Boolean
        get() = smartspaceController.isEnabled

    /** Whether the weather area is available and enabled. */
    val isWeatherEnabled: Flow<Boolean>
        get() = smartspaceInteractor.isWeatherEnabled

    /** Whether the data and weather areas are decoupled in the build. */
    val isDateWeatherDecoupled: Boolean
        get() = smartspaceController.isDateWeatherDecoupled

    @Deprecated("Remove after flexiglass ships")
    /** Whether the date area should be visible. */
    val isDateVisible: StateFlow<Boolean> =
        combine(
                keyguardClockViewModel.hasCustomWeatherDataDisplay,
                keyguardClockViewModel.isLargeClockVisible,
            ) { hasCustomWeatherDataDisplay, isLargeClockVisible ->
                !hasCustomWeatherDataDisplay || !isLargeClockVisible
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    !keyguardClockViewModel.hasCustomWeatherDataDisplay.value ||
                        !keyguardClockViewModel.isLargeClockVisible.value,
            )

    @Deprecated("Remove after flexiglass ships")
    /** Whether the weather area should be visible. */
    val isWeatherVisible: StateFlow<Boolean> =
        combine(
                isWeatherEnabled,
                keyguardClockViewModel.hasCustomWeatherDataDisplay,
                keyguardClockViewModel.isLargeClockVisible,
            ) { isWeatherEnabled, clockIncludesCustomWeatherDisplay, isLargeClockVisible ->
                isWeatherVisible(
                    clockIncludesCustomWeatherDisplay = clockIncludesCustomWeatherDisplay,
                    isWeatherEnabled = isWeatherEnabled,
                    isLargeClockVisible = isLargeClockVisible,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    isWeatherVisible(
                        clockIncludesCustomWeatherDisplay =
                            keyguardClockViewModel.hasCustomWeatherDataDisplay.value,
                        isWeatherEnabled = smartspaceInteractor.isWeatherEnabled.value,
                        isLargeClockVisible = keyguardClockViewModel.isLargeClockVisible.value,
                    ),
            )

    @Deprecated("Remove after flexiglass ships")
    private fun isWeatherVisible(
        clockIncludesCustomWeatherDisplay: Boolean,
        isWeatherEnabled: Boolean,
        isLargeClockVisible: Boolean,
    ): Boolean {
        return (!clockIncludesCustomWeatherDisplay || !isLargeClockVisible) && isWeatherEnabled
    }

    /* trigger clock and smartspace constraints change when smartspace appears */
    val bcSmartspaceVisibility: StateFlow<Int> = smartspaceInteractor.bcSmartspaceVisibility

    val isFullWidthShade: StateFlow<Boolean> = shadeModeInteractor.isFullWidthShade

    companion object {
        fun getDateWeatherStartMargin(context: Context): Int {
            return context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start) +
                context.resources.getDimensionPixelSize(clocksR.dimen.status_view_margin_horizontal)
        }

        fun getDateWeatherEndMargin(context: Context): Int {
            return context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_end) +
                context.resources.getDimensionPixelSize(clocksR.dimen.status_view_margin_horizontal)
        }

        fun getSmartspaceHorizontalMargin(context: Context): Int {
            return context.resources.getDimensionPixelSize(R.dimen.smartspace_padding_horizontal) +
                context.resources.getDimensionPixelSize(clocksR.dimen.status_view_margin_horizontal)
        }
    }
}
