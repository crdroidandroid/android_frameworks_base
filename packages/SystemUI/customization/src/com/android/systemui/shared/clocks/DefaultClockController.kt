/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.shared.clocks

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.icu.text.NumberFormat
import android.icu.util.TimeZone
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import com.android.systemui.customization.R as customR
import com.android.systemui.customization.clocks.DefaultClockFaceLayout
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEventListeners
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import com.android.systemui.shared.Flags.ambientAod
import java.io.PrintWriter
import java.util.Locale

/**
 * Controls the default clock visuals.
 *
 * This serves as an adapter between the clock interface and the AnimatableClockView used by the
 * existing lockscreen clock.
 */
class DefaultClockController(
    private val ctx: Context,
    private val layoutInflater: LayoutInflater,
    private val resources: Resources,
    private val settings: ClockSettings?,
    messageBuffers: ClockMessageBuffers? = null,
) : ClockController {
    override val smallClock: DefaultClockFaceController
    override val largeClock: LargeClockFaceController
    private val clocks: List<AnimatableClockView>

    private val burmeseNf = NumberFormat.getInstance(Locale.forLanguageTag("my"))
    private val burmeseNumerals = burmeseNf.format(FORMAT_NUMBER.toLong())
    private val burmeseLineSpacing =
        resources.getFloat(clocksR.dimen.keyguard_clock_line_spacing_scale_burmese)
    private val defaultLineSpacing =
        resources.getFloat(clocksR.dimen.keyguard_clock_line_spacing_scale)

    override val events: DefaultClockEvents
    override val config: ClockConfig by lazy {
        ClockConfig(
            DEFAULT_CLOCK_ID,
            resources.getString(customR.string.clock_default_name),
            resources.getString(customR.string.clock_default_description),
        )
    }

    init {
        val parent = FrameLayout(ctx)
        smallClock =
            DefaultClockFaceController(
                layoutInflater.inflate(customR.layout.clock_default_small, parent, false)
                    as AnimatableClockView,
                settings?.seedColor,
                messageBuffers?.smallClockMessageBuffer,
            )
        largeClock =
            LargeClockFaceController(
                layoutInflater.inflate(customR.layout.clock_default_large, parent, false)
                    as AnimatableClockView,
                settings?.seedColor,
                messageBuffers?.largeClockMessageBuffer,
            )
        clocks = listOf(smallClock.view, largeClock.view)

        events = DefaultClockEvents()
        events.onLocaleChanged(Locale.getDefault())
    }

    override val eventListeners = ClockEventListeners()

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        largeClock.recomputePadding(null)

        largeClock.animations = LargeClockAnimations(largeClock.view, dozeFraction, foldFraction)
        smallClock.animations = DefaultClockAnimations(smallClock.view, dozeFraction, foldFraction)

        largeClock.events.onThemeChanged(largeClock.theme.copy(isDarkTheme = isDarkTheme))
        smallClock.events.onThemeChanged(smallClock.theme.copy(isDarkTheme = isDarkTheme))
        events.onTimeZoneChanged(TimeZone.getDefault())

        smallClock.events.onTimeTick()
        largeClock.events.onTimeTick()
    }

    open inner class DefaultClockFaceController(
        override val view: AnimatableClockView,
        seedColor: Int?,
        messageBuffer: MessageBuffer?,
    ) : ClockFaceController {
        // MAGENTA is a placeholder, and will be assigned correctly in initialize
        private var currentColor = seedColor ?: Color.MAGENTA
        protected var targetRegion: Rect? = null

        override val config = ClockFaceConfig()
        override var theme = ThemeConfig(true, seedColor)
        override val layout = DefaultClockFaceLayout(view)

        override var animations: DefaultClockAnimations = DefaultClockAnimations(view, 0f, 0f)
            internal set

        init {
            view.id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
            view.setColors(getAodColor(), currentColor)
            messageBuffer?.let { view.messageBuffer = it }
        }

        override val events =
            object : ClockFaceEvents {
                override fun onTimeTick() = view.refreshTime()

                override fun onThemeChanged(theme: ThemeConfig) {
                    this@DefaultClockFaceController.theme = theme

                    val color = theme.getDefaultColor(ctx)
                    if (currentColor == color) {
                        return
                    }

                    currentColor = color

                    view.setColors(getAodColor(), color)
                    if (!animations.dozeState.isActive) {
                        view.animateColorChange()
                    }
                }

                override fun onTargetRegionChanged(targetRegion: Rect?) {
                    this@DefaultClockFaceController.targetRegion = targetRegion
                    recomputePadding(targetRegion)
                }

                override fun onFontSettingChanged(fontSizePx: Float) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                    recomputePadding(targetRegion)
                }

                override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
            }

        open fun recomputePadding(targetRegion: Rect?) {
            view.setFontFeatureSettings("pnum")
        }

        private fun getAodColor(): Int {
            return if (ambientAod()) {
                ctx.resources.getColor(android.R.color.system_accent1_100)
            } else {
                DOZE_COLOR
            }
        }
    }

    inner class LargeClockFaceController(
        view: AnimatableClockView,
        seedColor: Int?,
        messageBuffer: MessageBuffer?,
    ) : DefaultClockFaceController(view, seedColor, messageBuffer) {
        override val layout = DefaultClockFaceLayout(view)
        override val config = ClockFaceConfig(hasCustomPositionUpdatedAnimation = true)

        init {
            view.id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
            view.hasCustomPositionUpdatedAnimation = true
            animations = LargeClockAnimations(view, 0f, 0f)
        }

        override fun recomputePadding(targetRegion: Rect?) {
            view.setFontFeatureSettings("tnum")
        }

        /** See documentation at [AnimatableClockView.offsetGlyphsForStepClockAnimation]. */
        fun offsetGlyphsForStepClockAnimation(args: ClockPositionAnimationArgs) {
            view.offsetGlyphsForStepClockAnimation(args.fromLeft, args.direction, args.fraction)
        }
    }

    inner class DefaultClockEvents : ClockEvents {
        override var isReactiveTouchInteractionEnabled: Boolean = false

        override fun onTimeFormatChanged(formatKind: TimeFormatKind) =
            clocks.forEach { it.refreshFormat(formatKind == TimeFormatKind.FULL_DAY) }

        override fun onTimeZoneChanged(timeZone: TimeZone) {
            val legacyTimezone = java.util.TimeZone.getTimeZone(timeZone.getID())
            clocks.forEach { it.onTimeZoneChanged(legacyTimezone) }
        }

        override fun onLocaleChanged(locale: Locale) {
            val nf = NumberFormat.getInstance(locale)
            if (nf.format(FORMAT_NUMBER.toLong()) == burmeseNumerals) {
                clocks.forEach { it.setLineSpacingScale(burmeseLineSpacing) }
            } else {
                clocks.forEach { it.setLineSpacingScale(defaultLineSpacing) }
            }

            clocks.forEach { it.refreshFormat() }
        }

        override fun onWeatherDataChanged(data: WeatherData) {}

        override fun onAlarmDataChanged(data: AlarmData) {}

        override fun onZenDataChanged(data: ZenData) {}
    }

    open inner class DefaultClockAnimations(
        val view: AnimatableClockView,
        dozeFraction: Float,
        foldFraction: Float,
    ) : ClockAnimations {
        internal val dozeState = AnimationState(dozeFraction)
        private val foldState = AnimationState(foldFraction)

        init {
            if (foldState.isActive) {
                view.animateFoldAppear(false)
            } else {
                view.animateDoze(dozeState.isActive, false)
            }
        }

        override fun enter() {
            if (!dozeState.isActive) {
                view.animateAppearOnLockscreen()
            }
        }

        override fun charge() = view.animateCharge { dozeState.isActive }

        override fun fold(fraction: Float) {
            val (hasChanged, hasJumped) = foldState.update(fraction)
            if (hasChanged) {
                view.animateFoldAppear(!hasJumped)
            }
        }

        override fun doze(fraction: Float) {
            val (hasChanged, hasJumped) = dozeState.update(fraction)
            if (hasChanged) {
                view.animateDoze(dozeState.isActive, !hasJumped)
            }
        }

        override fun onPickerCarouselSwiping(swipingFraction: Float) {
            // TODO(b/278936436): refactor this part when we change recomputePadding
            // when on the side, swipingFraction = 0, translationY should offset
            // the top margin change in recomputePadding to make clock be centered
            view.translationY = 0.5f * view.bottom * (1 - swipingFraction)
        }

        override fun onPositionAnimated(args: ClockPositionAnimationArgs) {}

        override fun onFidgetTap(x: Float, y: Float) {}

        override fun onFontAxesChanged(style: ClockAxisStyle) {}
    }

    inner class LargeClockAnimations(
        view: AnimatableClockView,
        dozeFraction: Float,
        foldFraction: Float,
    ) : DefaultClockAnimations(view, dozeFraction, foldFraction) {
        override fun onPositionAnimated(args: ClockPositionAnimationArgs) {
            largeClock.offsetGlyphsForStepClockAnimation(args)
        }
    }

    class AnimationState(var fraction: Float) {
        var isActive: Boolean = fraction > 0.5f

        fun update(newFraction: Float): Pair<Boolean, Boolean> {
            if (newFraction == fraction) {
                return Pair(isActive, false)
            }
            val wasActive = isActive
            val hasJumped =
                (fraction == 0f && newFraction == 1f) || (fraction == 1f && newFraction == 0f)
            isActive = newFraction > fraction
            fraction = newFraction
            return Pair(wasActive != isActive, hasJumped)
        }
    }

    override fun dump(pw: PrintWriter) {
        pw.print("smallClock=")
        smallClock.view.dump(pw)

        pw.print("largeClock=")
        largeClock.view.dump(pw)
    }

    companion object {
        @VisibleForTesting const val DOZE_COLOR = Color.WHITE
        private const val FORMAT_NUMBER = 1234567890
    }
}
