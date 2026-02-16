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

package com.android.systemui.statusbar.pipeline.battery.ui.viewmodel

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.Charging
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.Defend
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.PowerSave
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryAttributionModel.Unknown
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryFrame
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.text.NumberFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
sealed class BatteryViewModel(
    val interactor: BatteryInteractor,
    shouldShowPercent: Flow<Boolean>,
    @Application context: Context,
) : ExclusiveActivatable() {
    protected val hydrator: Hydrator = Hydrator("BatteryViewModel.hydrator")

    val batteryFrame = BatteryFrame.pathSpec
    val innerWidth = BatteryFrame.innerWidth
    val innerHeight = BatteryFrame.innerHeight

    val level by
        hydrator.hydratedStateOf(traceName = "level", initialValue = 0, source = interactor.level)

    val isFull by
        hydrator.hydratedStateOf(
            traceName = "isFull",
            initialValue = false,
            source = interactor.isFull,
        )

    val isCharging: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isCharging",
            initialValue = false,
            source = interactor.isCharging,
        )

    val isBatteryPercentSettingEnabled: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isBatteryPercentSettingEnabled",
            initialValue = interactor.showPercentNextToIcon.value,
            source = interactor.showPercentNextToIcon,
        )

    val isBatteryPercentInsideIconSettingEnabled: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isBatteryPercentInsideIconSettingEnabled",
            initialValue = interactor.showPercentInsideIcon.value,
            source = interactor.showPercentInsideIcon,
        )

    val shouldShowBoltInTextMode: Boolean by
    hydrator.hydratedStateOf(
        traceName = "shouldShowBoltInTextMode",
        initialValue = false,
        source = combine(
            interactor.batteryIconStyle.map { it == BatteryRepository.ICON_STYLE_TEXT },
            interactor.isCharging
        ) { isTextMode, charging ->
            isTextMode && charging
        }
    )

    /** A [List<BatteryGlyph>] representation of the current [level] */
    private val levelGlyphs: Flow<List<BatteryGlyph>> =
        interactor.level.map { it?.glyphRepresentation() ?: emptyList() }

    private val _glyphList: Flow<List<BatteryGlyph>> =
        shouldShowPercent.flatMapLatest {
            if (it) {
                levelGlyphs
            } else {
                flowOf(emptyList())
            }
        }

    /**
     * For everything except the BatteryNextToPercentViewModel, this is the glyphs of the battery
     * percent
     */
    open val glyphList: List<BatteryGlyph> by
        hydrator.hydratedStateOf(
            traceName = "glyphList",
            initialValue = emptyList(),
            source = _glyphList,
        )

    /** The current attribution, if any */
    protected val attributionGlyph: Flow<BatteryGlyph?> =
        interactor.batteryAttributionType.map {
            when (it) {
                Charging -> BatteryGlyph.Bolt

                PowerSave -> BatteryGlyph.Plus

                Defend -> BatteryGlyph.Defend

                Unknown -> BatteryGlyph.Question

                else -> null
            }
        }

    val attribution: BatteryGlyph? by
        hydrator.hydratedStateOf(
            traceName = "attribution",
            initialValue = null,
            source = attributionGlyph,
        )

    private val _colorProfile: Flow<ColorProfile> =
        combine(interactor.batteryAttributionType, interactor.isCritical) { attr, isCritical ->
            when (attr) {
                Charging,
                Defend ->
                    ColorProfile(
                        dark = BatteryColors.DarkTheme.Charging,
                        light = BatteryColors.LightTheme.Charging,
                    )

                PowerSave ->
                    ColorProfile(
                        dark = BatteryColors.DarkTheme.PowerSave,
                        light = BatteryColors.LightTheme.PowerSave,
                    )

                else ->
                    if (isCritical) {
                        ColorProfile(
                            dark = BatteryColors.DarkTheme.Error,
                            light = BatteryColors.LightTheme.Error,
                        )
                    } else {
                        ColorProfile(
                            dark = BatteryColors.DarkTheme.Default,
                            light = BatteryColors.LightTheme.Default,
                        )
                    }
            }
        }

    /** For the current battery state, what is the relevant color profile to use */
    val colorProfile: ColorProfile by
        hydrator.hydratedStateOf(
            traceName = "colorProfile",
            initialValue =
                ColorProfile(
                    dark = BatteryColors.DarkTheme.Default,
                    light = BatteryColors.LightTheme.Default,
                ),
            source = _colorProfile,
        )

    val contentDescription: ContentDescription by
        hydrator.hydratedStateOf(
            traceName = "contentDescription",
            initialValue = ContentDescription.Loaded(null),
            source =
                combine(interactor.batteryAttributionType, interactor.level) { attr, level ->
                    when (attr) {
                        Defend -> {
                            val descr =
                                context.getString(
                                    R.string.accessibility_battery_level_charging_paused,
                                    level,
                                )

                            ContentDescription.Loaded(descr)
                        }
                        Charging -> {
                            val descr =
                                context.getString(
                                    R.string.accessibility_battery_level_charging,
                                    level,
                                )
                            ContentDescription.Loaded(descr)
                        }
                        PowerSave -> {
                            val descr =
                                context.getString(
                                    R.string.accessibility_battery_level_battery_saver_with_percent,
                                    level,
                                )
                            ContentDescription.Loaded(descr)
                        }
                        Unknown -> {
                            val descr = context.getString(R.string.accessibility_battery_unknown)
                            ContentDescription.Loaded(descr)
                        }
                        else -> {
                            val descr =
                                context.getString(R.string.accessibility_battery_level, level)
                            ContentDescription.Loaded(descr)
                        }
                    }
                },
        )

    val batteryIconStyle: Int by
        hydrator.hydratedStateOf(
            traceName = "batteryIconStyle",
            initialValue = BatteryRepository.ICON_STYLE_DEFAULT,
            source = interactor.batteryIconStyle,
        )

    /** For use in the shade, where we might need to show an estimate */
    val batteryTimeRemainingEstimate: String? by
        hydrator.hydratedStateOf(
            traceName = "timeRemainingEstimate",
            initialValue = null,
            source =
                interactor.isCharging.flatMapLatest { charging ->
                    if (charging) {
                        flowOf(null)
                    } else {
                        interactor.batteryTimeRemainingEstimate
                    }
                },
        )

    val batteryPercent: String? by
        hydrator.hydratedStateOf(
            traceName = "batteryPercent",
            initialValue = null,
            source =
                interactor.level.map { level ->
                    if (level == null) {
                        null
                    } else {
                        NumberFormat.getPercentInstance().format(level / 100f)
                    }
                },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    /** Base factory class so implementations can take any kind of view model */
    interface Factory {
        fun create(): BatteryViewModel
    }

    /** View model that shows the percentage based on the percent setting */
    class BasedOnUserSetting
    @AssistedInject
    constructor(interactor: BatteryInteractor, @Application context: Context) :
        BatteryViewModel(
            interactor = interactor,
            shouldShowPercent = interactor.showPercentInsideIcon,
            context = context,
        ) {

        @AssistedFactory
        fun interface Factory : BatteryViewModel.Factory {
            override fun create(): BasedOnUserSetting
        }
    }

    /**
     * BatteryViewModel that shows percentage when the device is charging, or when the setting is
     * enabled
     */
    class ShowPercentWhenChargingOrSetting
    @AssistedInject
    constructor(interactor: BatteryInteractor, @Application context: Context) :
        BatteryViewModel(
            interactor = interactor,
            shouldShowPercent =
                combine(interactor.isCharging, interactor.showPercentNextToIcon) {
                    charging,
                    settingEnabled ->
                    charging || settingEnabled
                },
            context = context,
        ) {

        @AssistedFactory
        fun interface Factory : BatteryViewModel.Factory {
            override fun create(): ShowPercentWhenChargingOrSetting
        }
    }

    /** BatteryViewModel that always shows the percentage */
    class AlwaysShowPercent
    @AssistedInject
    constructor(interactor: BatteryInteractor, @Application context: Context) :
        BatteryViewModel(
            interactor = interactor,
            shouldShowPercent = flowOf(true),
            context = context,
        ) {

        @AssistedFactory
        fun interface Factory : BatteryViewModel.Factory {
            override fun create(): AlwaysShowPercent
        }
    }

    companion object {
        /**
         * Status bar battery height, based on a 26.5x13 base canvas. Defined in [sp] so that the
         * icon properly scales when the font size changes (consistent with other status bar icons)
         */
        fun getStatusBarBatteryHeight(context: Context): TextUnit {
            return if (StatusBarConnectedDisplays.isEnabled) {
                (13 * getScaleFactor(context)).sp
            } else {
                13.sp
            }
        }

        /**
         * [TextStyle] for status bar battery text [Composable]s. The size of this text will scale
         * consistent with display size changes
         */
        @OptIn(ExperimentalMaterial3ExpressiveApi::class) // Required for bodyMediumEmphasized style
        @Composable
        fun getStatusBarBatteryTextStyle(context: Context): TextStyle {
            val baseStyle = MaterialTheme.typography.bodyMediumEmphasized
            if (!Flags.fixShadeHeaderWrongIconSize()) {
                return baseStyle
            }

            val customStyle =
                baseStyle.copy(fontSize = baseStyle.fontSize * getScaleFactor(context))
            return customStyle
        }

        private fun getScaleFactor(context: Context): Float {
            return context.resources.getFloat(R.dimen.status_bar_icon_scale_factor)
        }

        /** Resource id used to identify battery composable view in SysUI tests */
        const val TEST_TAG = "battery"

        fun Int.glyphRepresentation(): List<BatteryGlyph> = toString().map { it.toGlyph() }

        private fun Char.toGlyph(): BatteryGlyph =
            when (this) {
                '0' -> BatteryGlyph.Zero
                '1' -> BatteryGlyph.One
                '2' -> BatteryGlyph.Two
                '3' -> BatteryGlyph.Three
                '4' -> BatteryGlyph.Four
                '5' -> BatteryGlyph.Five
                '6' -> BatteryGlyph.Six
                '7' -> BatteryGlyph.Seven
                '8' -> BatteryGlyph.Eight
                '9' -> BatteryGlyph.Nine
                else -> throw IllegalArgumentException("cannot make glyph from char ($this)")
            }
    }
}

/**
 * BatteryViewModel that only exposes the attribution as a glyph. The percentage is expected to be
 * displayed next to it in a text view
 */
class BatteryNextToPercentViewModel
@AssistedInject
constructor(interactor: BatteryInteractor, @Application context: Context) :
    BatteryViewModel(interactor = interactor, shouldShowPercent = flowOf(true), context = context) {

    private val _attributionAsList: Flow<List<BatteryGlyph>> =
        attributionGlyph.map { it?.let { listOf(it) } ?: emptyList() }

    override val glyphList: List<BatteryGlyph> by
        hydrator.hydratedStateOf(
            traceName = "glyphList",
            initialValue = emptyList(),
            source = _attributionAsList,
        )

    @AssistedFactory
    interface Factory : BatteryViewModel.Factory {
        override fun create(): BatteryNextToPercentViewModel
    }
}

/** Wrap the light and dark color into a single object so the view can decide which one it needs */
data class ColorProfile(val dark: BatteryColors.DarkTheme, val light: BatteryColors.LightTheme)
