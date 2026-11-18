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
import android.content.res.Resources
import android.util.LayoutDirection
import androidx.constraintlayout.helper.widget.Layer
import com.android.keyguard.ClockEventController
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.KeyguardLargeClockLog
import com.android.systemui.log.dagger.KeyguardSmallClockLog
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.res.R as SysuiR
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.ui.SystemBarUtilsProxy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardClockViewModel
@Inject
constructor(
    private val context: Context,
    keyguardClockInteractor: KeyguardClockInteractor,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundScope: CoroutineScope,
    aodNotificationIconViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val systemBarUtils: SystemBarUtilsProxy,
    private val zenModeInteractor: ZenModeInteractor,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    // TODO: b/374267505 - Use ShadeDisplayAware resources here.
    @Main private val resources: Resources,
    @KeyguardSmallClockLog private val smallClockLogBuffer: LogBuffer,
    @KeyguardLargeClockLog private val largeClockLogBuffer: LogBuffer,
) {
    var burnInLayer: Layer? = null

    val clockSize: StateFlow<ClockSize> = keyguardClockInteractor.clockSize

    /** Whether a custom lockscreen clock style replaces the clock plugin views. */
    val isCustomClockEnabled: StateFlow<Boolean> = keyguardClockInteractor.isCustomClockEnabled

    val isLargeClockVisible: StateFlow<Boolean> =
        clockSize
            .map { it == ClockSize.LARGE }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = true,
            )

    val clockEventController: ClockEventController = keyguardClockInteractor.clockEventController
    val currentClock = keyguardClockInteractor.currentClock

    val hasCustomWeatherDataDisplay =
        combine(isLargeClockVisible, currentClock) { isLargeClock, currentClock ->
                currentClock?.let { clock ->
                    val face = if (isLargeClock) clock.largeClock else clock.smallClock
                    face.config.hasCustomWeatherDataDisplay
                } ?: false
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    currentClock.value?.largeClock?.config?.hasCustomWeatherDataDisplay ?: false,
            )

    val clockShouldBeCentered: StateFlow<Boolean> =
        keyguardClockInteractor.clockShouldBeCentered.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = true,
        )

    // To translate elements below smartspace in weather clock to avoid overlapping between date
    // element in weather clock and aod icons
    val hasAodIcons: StateFlow<Boolean> =
        aodNotificationIconViewModel.icons
            .map { it.visibleIcons.isNotEmpty() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    val currentClockLayout: StateFlow<ClockLayout> =
        combine(
                isLargeClockVisible,
                clockShouldBeCentered,
                shadeModeInteractor.isFullWidthShade,
                currentClock,
            ) { isLargeClockVisible, clockShouldBeCentered, isFullWidthShade, currentClock ->
                val isShadeLayoutWide = !isFullWidthShade
                if (currentClock?.config?.useCustomClockScene == true) {
                    when {
                        isShadeLayoutWide && clockShouldBeCentered ->
                            ClockLayout.WEATHER_LARGE_CLOCK
                        isShadeLayoutWide && isLargeClockVisible ->
                            ClockLayout.SPLIT_SHADE_WEATHER_LARGE_CLOCK
                        isShadeLayoutWide -> ClockLayout.SPLIT_SHADE_SMALL_CLOCK
                        isLargeClockVisible -> ClockLayout.WEATHER_LARGE_CLOCK
                        else -> ClockLayout.SMALL_CLOCK
                    }
                } else {
                    when {
                        isShadeLayoutWide && clockShouldBeCentered -> ClockLayout.LARGE_CLOCK
                        isShadeLayoutWide && isLargeClockVisible ->
                            ClockLayout.SPLIT_SHADE_LARGE_CLOCK
                        isShadeLayoutWide -> ClockLayout.SPLIT_SHADE_SMALL_CLOCK
                        isLargeClockVisible -> ClockLayout.LARGE_CLOCK
                        else -> ClockLayout.SMALL_CLOCK
                    }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ClockLayout.SMALL_CLOCK,
            )

    val hasCustomPositionUpdatedAnimation: StateFlow<Boolean> =
        combine(currentClock, isLargeClockVisible) { currentClock, isLargeClockVisible ->
                isLargeClockVisible &&
                    currentClock?.largeClock?.config?.hasCustomPositionUpdatedAnimation == true
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Calculates the top margin for the small clock. */
    fun getSmallClockTopMargin(): Int {
        return ClockPreviewConfig(
                isFullWidthShade = shadeModeInteractor.isFullWidthShade.value,
                isSceneContainerFlagEnabled = SceneContainerFlag.isEnabled,
                statusBarHeight = systemBarUtils.getStatusBarHeaderHeightKeyguard(),
                splitShadeTopMargin =
                    context.resources.getDimensionPixelSize(
                        SysuiR.dimen.keyguard_split_shade_top_margin
                    ),
                clockTopMargin =
                    context.resources.getDimensionPixelSize(SysuiR.dimen.keyguard_clock_top_margin),
                statusViewMarginHorizontal =
                    context.resources.getDimensionPixelSize(
                        clocksR.dimen.status_view_margin_horizontal
                    ),
            )
            .getSmallClockTopPadding()
    }

    val smallClockTopMargin =
        combine(
            configurationInteractor.onAnyConfigurationChange,
            shadeModeInteractor.isFullWidthShade,
        ) { _, _ ->
            getSmallClockTopMargin()
        }

    /** Calculates the top margin for the large clock. */
    fun getLargeClockTopMargin(): Int {
        return systemBarUtils.getStatusBarHeight() / 2 +
            resources.getDimensionPixelSize(clocksR.dimen.keyguard_smartspace_top_offset)
    }

    val largeClockTextSize: Flow<Int> =
        configurationInteractor.dimensionPixelSize(clocksR.dimen.large_clock_text_size)

    val shouldDateWeatherBeBelowLargeClock: StateFlow<Boolean> =
        combine(
                shadeModeInteractor.isFullWidthShade,
                configurationInteractor.configurationValues,
                keyguardClockInteractor.currentClock,
            ) { isFullWidthShade, configurationValues, currentClock ->
                val screenWidthDp = configurationValues.screenWidthDp
                val fontScale = configurationValues.fontScale

                var belowLargeClock =
                    !isFontAndDisplaySizeBreaking(
                        currentClock = currentClock,
                        screenWidthDp = screenWidthDp,
                        fontScale = fontScale,
                        isFullWidthShade = isFullWidthShade,
                    )
                largeClockLogBuffer.log(
                    TAG,
                    LogLevel.INFO,
                    {
                        int1 = screenWidthDp
                        double1 = fontScale.toDouble()
                        bool1 = belowLargeClock
                    },
                    { "belowLargeClock:$bool1, Width:$int1, FontScale:$double1" },
                )
                belowLargeClock
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    val shouldDateWeatherBeBelowSmallClock: StateFlow<Boolean> =
        combine(
                hasCustomWeatherDataDisplay,
                shadeModeInteractor.isFullWidthShade,
                configurationInteractor.configurationValues,
                keyguardClockInteractor.currentClock,
                zenModeInteractor.hasNextAlarm,
            ) {
                hasCustomWeatherDataDisplay,
                isFullWidthShade,
                configurationValues,
                currentClock,
                hasNextAlarm ->
                val isRtlLayout = configurationValues.layoutDirection == LayoutDirection.RTL

                if (hasCustomWeatherDataDisplay || isRtlLayout || hasNextAlarm) {
                    return@combine true
                }

                keyguardClockInteractor.currentClockFontAxesWidth?.let { fontWidth ->
                    if (fontWidth >= FONT_WIDTH_MAX_CUTOFF) {
                        smallClockLogBuffer.log(
                            TAG,
                            LogLevel.INFO,
                            { int1 = FONT_WIDTH_MAX_CUTOFF },
                            { "fallBelowClock:true, FontAxesWidth:$int1" },
                        )
                        return@combine true
                    }
                }

                val screenWidthDp = configurationValues.screenWidthDp
                val fontScale = configurationValues.fontScale
                var fallBelow =
                    isFontAndDisplaySizeBreaking(
                        currentClock = currentClock,
                        screenWidthDp = screenWidthDp,
                        fontScale = fontScale,
                        isFullWidthShade = isFullWidthShade,
                    )
                smallClockLogBuffer.log(
                    TAG,
                    LogLevel.INFO,
                    {
                        int1 = screenWidthDp
                        double1 = fontScale.toDouble()
                        bool1 = fallBelow
                        bool2 = !isFullWidthShade
                    },
                    {
                        "fallBelowClock:$bool1, isShadeWide:$bool2, " +
                            "Width:$int1, FontScale:$double1"
                    },
                )
                fallBelow
            }
            .stateIn(scope = backgroundScope, started = SharingStarted.Eagerly, initialValue = true)

    private fun isFontAndDisplaySizeBreaking(
        currentClock: ClockController?,
        screenWidthDp: Int,
        fontScale: Float,
        isFullWidthShade: Boolean,
    ): Boolean {
        val breakingPairs: List<Pair<Float, Int>> =
            when (currentClock?.config?.id) {
                CALLIGRAPHY_CLOCK_ID,
                METRO_CLOCK_ID,
                NUMBER_OVERLAP_CLOCK_ID -> OVERSIZED_CLOCK_BREAKING_PAIRS
                else -> DEFAULT_BREAKING_PAIRS
            }

        // if the shade is wide, we should account for the possibility of date/weather going past
        // the halfway point
        val adjustedScreenWidth = if (isFullWidthShade) screenWidthDp else screenWidthDp / 2
        return breakingPairs.any { (font, width) ->
            fontScale >= font && adjustedScreenWidth <= width
        }
    }

    enum class ClockLayout {
        LARGE_CLOCK,
        SMALL_CLOCK,
        SPLIT_SHADE_LARGE_CLOCK,
        SPLIT_SHADE_SMALL_CLOCK,
        WEATHER_LARGE_CLOCK,
        SPLIT_SHADE_WEATHER_LARGE_CLOCK,
    }

    companion object {
        const val TAG = "KeyguardClockViewModel"

        // font size to display size
        // These values come from changing the font size and display size on a non-foldable.
        // Visually looked at which configs cause the date/weather to push off of the screen
        private val DEFAULT_BREAKING_PAIRS =
            listOf(
                0.85f to 320, // tiny font size but large display size
                1f to 346,
                1.15f to 346,
                1.5f to 411, // large font size but tiny display size
            )

        private const val NUMBER_OVERLAP_CLOCK_ID = "DIGITAL_CLOCK_NUMBEROVERLAP"
        private const val METRO_CLOCK_ID = "DIGITAL_CLOCK_METRO"

        private const val CALLIGRAPHY_CLOCK_ID = "DIGITAL_CLOCK_CALLIGRAPHY"

        // This set of breaking pairs are for "oversized" clocks.
        // These clocks, small or large, are larger than expected and need to have this fallback
        // happen sooner due to size constraints.
        private val OVERSIZED_CLOCK_BREAKING_PAIRS =
            listOf(
                0.85f to 376, // tiny font size but large display size
                1f to 376,
                1.15f to 411,
                1.3f to 411, // large font size but tiny display size
            )

        // Font axes width max cutoff
        // A font with a wider font axes than this is at risk of being pushed off screen
        // Value determined by the very robust and scientific process of eye-balling a few devices
        private const val FONT_WIDTH_MAX_CUTOFF = 110
    }
}
