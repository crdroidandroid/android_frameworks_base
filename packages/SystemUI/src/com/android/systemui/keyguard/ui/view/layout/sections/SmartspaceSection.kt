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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.LinearLayout
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.GONE
import androidx.constraintlayout.widget.ConstraintSet.VISIBLE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardSmartspaceInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardSmartspaceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R as R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.R as sharedR
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

@SysUISingleton
open class SmartspaceSection
@Inject
constructor(
    @ShadeDisplayAware val context: Context,
    val keyguardClockViewModel: KeyguardClockViewModel,
    val keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel,
    private val keyguardSmartspaceInteractor: KeyguardSmartspaceInteractor,
    val smartspaceController: LockscreenSmartspaceController,
    val keyguardUnlockAnimationController: KeyguardUnlockAnimationController,
    private val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {
    private var smartspaceView: View? = null
    private var dateView: LinearLayout? = null
    private var dateViewLargeClock: ViewGroup? = null

    private val smartspaceVisibilityListener = OnGlobalLayoutListener {
        smartspaceView?.let {
            val newVisibility = it.visibility
            if (pastVisibility != newVisibility) {
                keyguardSmartspaceInteractor.setBcSmartspaceVisibility(newVisibility)
                pastVisibility = newVisibility
            }
        }
    }

    private var pastVisibility: Int = -1
    private var disposableHandle: DisposableHandle? = null

    override fun onRebuildBegin() {
        smartspaceController.suppressDisconnects = true
    }

    override fun onRebuildEnd() {
        smartspaceController.suppressDisconnects = false
        if (!keyguardSmartspaceViewModel.isSmartspaceEnabled) {
            // Smartspace was switched off: drop its stale visibility and end the session, which
            // stayed connected while disconnects were suppressed.
            keyguardSmartspaceInteractor.setBcSmartspaceVisibility(View.GONE)
            smartspaceController.disconnect()
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        keyguardSmartspaceViewModel.refreshSmartspaceEnabled()
        if (!keyguardSmartspaceViewModel.isSmartspaceEnabled) return

        smartspaceView =
            smartspaceController.buildAndConnectView(context)?.also {
                pastVisibility = it.visibility
                constraintLayout.addView(it)

                keyguardUnlockAnimationController.lockscreenSmartspace = it
                it.viewTreeObserver.addOnGlobalLayoutListener(smartspaceVisibilityListener)
            }

        dateViewLargeClock = setupDateWeather(constraintLayout, isLargeClock = true)
        dateView = setupDateWeather(constraintLayout, isLargeClock = false) as LinearLayout
    }

    private fun setupDateWeather(
        constraintLayout: ConstraintLayout,
        isLargeClock: Boolean,
    ): ViewGroup? {
        return (smartspaceController.buildAndConnectDateView(context, isLargeClock) as? ViewGroup)
            ?.also {
                it.visibility = View.GONE
                constraintLayout.addView(it)

                // Place weather right after the date, before the extras (alarm and dnd)
                val index = if (it.childCount == 0) 0 else 1
                it.addView(
                    smartspaceController.buildAndConnectWeatherView(context, isLargeClock),
                    index,
                )
            }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!keyguardSmartspaceViewModel.isSmartspaceEnabled) return
        disposableHandle?.dispose()
        disposableHandle =
            KeyguardSmartspaceViewBinder.bind(
                constraintLayout,
                keyguardClockViewModel,
                keyguardSmartspaceViewModel,
                blueprintInteractor.get(),
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!keyguardSmartspaceViewModel.isSmartspaceEnabled) return
        val dateWeatherPaddingStart = KeyguardSmartspaceViewModel.getDateWeatherStartMargin(context)
        val smartspaceHorizontalPadding =
            KeyguardSmartspaceViewModel.getSmartspaceHorizontalMargin(context)
        val dateWeatherBelowSmallClock =
            keyguardClockViewModel.shouldDateWeatherBeBelowSmallClock.value
        val dateWeatherBelowLargeClock =
            keyguardClockViewModel.shouldDateWeatherBeBelowLargeClock.value

        val isLargeClockVisible = keyguardClockViewModel.isLargeClockVisible.value

        if (dateWeatherBelowSmallClock) {
            dateView?.orientation = LinearLayout.HORIZONTAL
        } else {
            dateView?.orientation = LinearLayout.VERTICAL
        }
        constraintSet.apply {
            constrainHeight(sharedR.id.date_smartspace_view, ConstraintSet.WRAP_CONTENT)
            constrainWidth(sharedR.id.date_smartspace_view, ConstraintSet.WRAP_CONTENT)
            if (dateWeatherBelowSmallClock || !dateWeatherBelowLargeClock) {
                connect(
                    sharedR.id.date_smartspace_view,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    dateWeatherPaddingStart,
                )
            }

            constrainHeight(sharedR.id.bc_smartspace_view, ConstraintSet.WRAP_CONTENT)
            constrainWidth(sharedR.id.bc_smartspace_view, ConstraintSet.MATCH_CONSTRAINT)
            connect(
                sharedR.id.bc_smartspace_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                smartspaceHorizontalPadding,
            )
            connect(
                sharedR.id.bc_smartspace_view,
                ConstraintSet.END,
                if (keyguardSmartspaceViewModel.isFullWidthShade.value) {
                    ConstraintSet.PARENT_ID
                } else {
                    R.id.split_shade_guideline
                },
                ConstraintSet.END,
                smartspaceHorizontalPadding,
            )
            if (keyguardClockViewModel.hasCustomWeatherDataDisplay.value && isLargeClockVisible) {
                clear(sharedR.id.date_smartspace_view, ConstraintSet.TOP)
                connect(
                    sharedR.id.date_smartspace_view,
                    ConstraintSet.BOTTOM,
                    sharedR.id.bc_smartspace_view,
                    ConstraintSet.TOP,
                )
            } else {
                clear(sharedR.id.date_smartspace_view, ConstraintSet.BOTTOM)
                if (dateWeatherBelowSmallClock || !dateWeatherBelowLargeClock) {
                    connect(
                        sharedR.id.date_smartspace_view,
                        ConstraintSet.TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        ConstraintSet.BOTTOM,
                    )
                    connect(
                        sharedR.id.bc_smartspace_view,
                        ConstraintSet.TOP,
                        sharedR.id.date_smartspace_view,
                        ConstraintSet.BOTTOM,
                    )
                } else {
                    connect(
                        sharedR.id.bc_smartspace_view,
                        ConstraintSet.TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        ConstraintSet.BOTTOM,
                    )
                }
            }

            if (
                isLargeClockVisible &&
                    keyguardClockViewModel.shouldDateWeatherBeBelowLargeClock.value
            ) {
                setVisibility(sharedR.id.date_smartspace_view, GONE)
                constrainHeight(sharedR.id.date_smartspace_view_large, ConstraintSet.WRAP_CONTENT)
                constrainWidth(sharedR.id.date_smartspace_view_large, ConstraintSet.WRAP_CONTENT)
                constrainHeight(
                    sharedR.id.weather_smartspace_view_large,
                    ConstraintSet.WRAP_CONTENT,
                )
                constrainWidth(sharedR.id.weather_smartspace_view_large, ConstraintSet.WRAP_CONTENT)
                connect(
                    sharedR.id.date_smartspace_view_large,
                    ConstraintSet.TOP,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    ConstraintSet.BOTTOM,
                    context.resources.getDimensionPixelSize(R.dimen.smartspace_padding_vertical),
                )

                connect(
                    sharedR.id.date_smartspace_view_large,
                    ConstraintSet.START,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    ConstraintSet.START,
                )
                connect(
                    sharedR.id.date_smartspace_view_large,
                    ConstraintSet.END,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    ConstraintSet.END,
                )
                setHorizontalChainStyle(
                    sharedR.id.date_smartspace_view_large,
                    ConstraintSet.CHAIN_PACKED,
                )
            } else {
                if (dateWeatherBelowSmallClock || !dateWeatherBelowLargeClock) {
                    connect(
                        sharedR.id.date_smartspace_view,
                        ConstraintSet.START,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.START,
                        dateWeatherPaddingStart,
                    )
                } else {
                    setVisibility(sharedR.id.date_smartspace_view_large, GONE)
                    constrainHeight(sharedR.id.date_smartspace_view, ConstraintSet.WRAP_CONTENT)
                    constrainWidth(sharedR.id.date_smartspace_view, ConstraintSet.WRAP_CONTENT)
                    connect(
                        sharedR.id.date_smartspace_view,
                        ConstraintSet.START,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        ConstraintSet.END,
                        context.resources.getDimensionPixelSize(
                            R.dimen.smartspace_padding_horizontal
                        ),
                    )
                    connect(
                        sharedR.id.date_smartspace_view,
                        ConstraintSet.TOP,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        ConstraintSet.TOP,
                    )
                    connect(
                        sharedR.id.date_smartspace_view,
                        ConstraintSet.BOTTOM,
                        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        ConstraintSet.BOTTOM,
                    )
                }
            }

            if (dateWeatherBelowSmallClock || !dateWeatherBelowLargeClock) {
                createBarrier(
                    R.id.smart_space_barrier_bottom,
                    Barrier.BOTTOM,
                    0,
                    *intArrayOf(sharedR.id.bc_smartspace_view, sharedR.id.date_smartspace_view),
                )
                createBarrier(
                    R.id.smart_space_barrier_top,
                    Barrier.TOP,
                    0,
                    *intArrayOf(sharedR.id.bc_smartspace_view, sharedR.id.date_smartspace_view),
                )
            } else {
                createBarrier(
                    R.id.smart_space_barrier_bottom,
                    Barrier.BOTTOM,
                    0,
                    sharedR.id.bc_smartspace_view,
                )
                createBarrier(
                    R.id.smart_space_barrier_top,
                    Barrier.TOP,
                    0,
                    sharedR.id.bc_smartspace_view,
                )
            }
        }
        updateVisibility(constraintSet, isLargeClockVisible)
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        // Not gated on isSmartspaceEnabled: the setting may have flipped since the views were
        // added, and they still have to go.

        // The viewTreeObserver listener need to be removed before the view is detached, because the
        // viewTreeObserver is reset when the view is detached. See [View::getViewTreeObserver].
        smartspaceView?.viewTreeObserver?.removeOnGlobalLayoutListener(smartspaceVisibilityListener)

        val list = listOf(smartspaceView, dateView, dateViewLargeClock)
        list.forEach {
            it?.let {
                keyguardClockViewModel.burnInLayer?.removeView(it)
                if (it.parent == constraintLayout) {
                    constraintLayout.removeView(it)
                }
            }
        }
        smartspaceView = null
        dateView = null
        dateViewLargeClock = null
        keyguardUnlockAnimationController.lockscreenSmartspace = null

        disposableHandle?.dispose()
        disposableHandle = null
    }

    private fun updateVisibility(constraintSet: ConstraintSet, isLargeClockVisible: Boolean) {

        // This may update the visibility of the smartspace views
        smartspaceController.requestSmartspaceUpdate()
        val weatherId: Int
        val dateId: Int
        if (
            isLargeClockVisible && keyguardClockViewModel.shouldDateWeatherBeBelowLargeClock.value
        ) {
            weatherId = sharedR.id.weather_smartspace_view_large
            dateId = sharedR.id.date_smartspace_view_large
        } else {
            weatherId = sharedR.id.weather_smartspace_view
            dateId = sharedR.id.date_smartspace_view
        }

        constraintSet.apply {
            val showWeather = keyguardSmartspaceViewModel.isWeatherVisible.value
            setVisibility(weatherId, if (showWeather) VISIBLE else GONE)
            setAlpha(weatherId, if (showWeather) 1f else 0f)

            val showDateView =
                !keyguardClockViewModel.hasCustomWeatherDataDisplay.value || !isLargeClockVisible
            setVisibility(dateId, if (showDateView) VISIBLE else GONE)
            setAlpha(dateId, if (showDateView) 1f else 0f)

            if (
                isLargeClockVisible &&
                    keyguardClockViewModel.shouldDateWeatherBeBelowLargeClock.value
            ) {
                setVisibility(sharedR.id.date_smartspace_view, GONE)
            } else {
                setVisibility(sharedR.id.date_smartspace_view_large, GONE)
            }
        }
    }
}
