/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.binder

import android.view.View
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.keyguard.VRectF
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine

object KeyguardSmartspaceViewBinder {
    @JvmStatic
    fun bind(
        keyguardRootView: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
        blueprintInteractor: KeyguardBlueprintInteractor,
    ): DisposableHandle {
        return keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#clockViewModel.hasCustomWeatherDataDisplay") {
                    combine(
                            smartspaceViewModel.isWeatherVisible,
                            clockViewModel.hasCustomWeatherDataDisplay,
                            ::Pair,
                        )
                        .collect {
                            blueprintInteractor.refreshBlueprint(
                                Config(
                                    Type.SmartspaceVisibility,
                                    checkPriority = false,
                                    terminatePrevious = false,
                                )
                            )
                        }
                }

                launch("$TAG#smartspaceViewModel.bcSmartspaceVisibility") {
                    smartspaceViewModel.bcSmartspaceVisibility.collect {
                        updateBCSmartspaceInBurnInLayer(keyguardRootView, clockViewModel)
                        blueprintInteractor.refreshBlueprint(
                            Config(
                                Type.SmartspaceVisibility,
                                checkPriority = false,
                                terminatePrevious = false,
                            )
                        )
                    }
                }

                launch("$TAG#weatherClockBcGate") {
                    combine(
                            clockViewModel.hasCustomWeatherDataDisplay,
                            smartspaceViewModel.areAnyNotificationsPresent,
                            ::Pair,
                        )
                        .collect { (hasCustomWeather, anyNotifications) ->
                            // Weather clock: hide the tomorrow-forecast at-a-glance while there
                            // are notifications. The plugin honours this instead of re-showing
                            // itself, so the card collapses cleanly.
                            (keyguardRootView.findViewById<View>(sharedR.id.bc_smartspace_view)
                                    as? BcSmartspaceDataPlugin.SmartspaceView)
                                ?.setHiddenByPolicy(hasCustomWeather && anyNotifications)
                        }
                }

                val xBuffer =
                    keyguardRootView.context.resources.getDimensionPixelSize(
                        R.dimen.smartspace_padding_horizontal
                    )
                val yBuffer =
                    keyguardRootView.context.resources.getDimensionPixelSize(
                        R.dimen.smartspace_padding_vertical
                    )

                val smallViewId = sharedR.id.date_smartspace_view
                val largeViewId = sharedR.id.date_smartspace_view_large

                launch("$TAG#smartspaceViewModel.isLargeClockVisible") {
                    combine(
                            clockViewModel.isLargeClockVisible,
                            clockViewModel.shouldDateWeatherBeBelowLargeClock,
                            clockViewModel.hasCustomWeatherDataDisplay,
                            ::Triple,
                        )
                        .collect { (isLargeClock, belowLargeClock, hasCustomWeather) ->
                            if (isLargeClock && (belowLargeClock || hasCustomWeather)) {
                                // hide small clock date/weather
                                keyguardRootView.findViewById<View>(smallViewId)?.let {
                                    it.visibility = View.GONE
                                }
                                removeDateWeatherFromBurnInLayer(
                                    keyguardRootView,
                                    smartspaceViewModel,
                                )
                            } else {
                                addDateWeatherToBurnInLayer(keyguardRootView, smartspaceViewModel)
                            }
                            clockViewModel.burnInLayer?.updatePostLayout(keyguardRootView)
                        }
                }

                launch("$TAG#clockEventController.largeClockBounds") {
                    // Whenever the doze amount changes, the clock may update it's view bounds.
                    // We need to update our layout position as a result. We could do this via
                    // `requestLayout`, but that's quite expensive when enclosed in since this
                    // recomputes the entire ConstraintLayout, so instead we do it manually. We
                    // would use translationX/Y for this, but that's used by burnin.
                    combine(
                            clockViewModel.isLargeClockVisible,
                            clockViewModel.shouldDateWeatherBeBelowLargeClock,
                            clockViewModel.clockEventController.largeClockBounds,
                            ::Triple,
                        )
                        .collect { (isLargeClock, belowLarge, largeBounds) ->
                            if (!isLargeClock) return@collect
                            keyguardRootView.findViewById<View>(smallViewId)?.let {
                                it.visibility = View.GONE
                            }

                            if (!belowLarge) return@collect
                            if (largeBounds == VRectF.ZERO) return@collect
                            val largeDateHeight =
                                keyguardRootView
                                    .findViewById<View>(sharedR.id.date_smartspace_view_large)
                                    ?.height ?: 0

                            keyguardRootView.findViewById<View>(largeViewId)?.let { view ->
                                val viewHeight = view.height
                                val offset = (largeDateHeight - viewHeight) / 2
                                view.top = (largeBounds.bottom + yBuffer + offset).toInt()
                                view.bottom = view.top + viewHeight
                            }
                        }
                }

                launch("$TAG#clockEventController.smallClockBounds") {
                    combine(
                            clockViewModel.isLargeClockVisible,
                            clockViewModel.shouldDateWeatherBeBelowSmallClock,
                            clockViewModel.clockEventController.smallClockBounds,
                            ::Triple,
                        )
                        .collect { (isLargeClock, belowSmall, smallBounds) ->
                            if (isLargeClock) return@collect
                            keyguardRootView.findViewById<View>(largeViewId)?.let {
                                it.visibility = View.GONE
                            }

                            if (belowSmall) return@collect
                            if (smallBounds == VRectF.ZERO) return@collect
                            keyguardRootView.findViewById<View>(smallViewId)?.let { view ->
                                val viewWidth = view.width
                                if (view.isLayoutRtl()) {
                                    view.right = (smallBounds.left - xBuffer).toInt()
                                    view.left = view.right - viewWidth
                                } else {
                                    view.left = (smallBounds.right + xBuffer).toInt()
                                    view.right = view.left + viewWidth
                                }
                            }
                        }
                }
            }
        }
    }

    private fun updateBCSmartspaceInBurnInLayer(
        keyguardRootView: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
    ) {
        // Visibility is controlled by updateTargetVisibility in CardPagerAdapter
        keyguardRootView.findViewById<Layer>(R.id.burn_in_layer)?.apply {
            val smartspaceView =
                keyguardRootView.requireViewById<View>(sharedR.id.bc_smartspace_view)
            if (smartspaceView.visibility == View.VISIBLE) {
                addView(smartspaceView)
            } else {
                removeView(smartspaceView)
            }
        }
        clockViewModel.burnInLayer?.updatePostLayout(keyguardRootView)
    }

    private fun updateDateWeatherToBurnInLayer(
        keyguardRootView: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) {
        if (clockViewModel.hasCustomWeatherDataDisplay.value) {
            removeDateWeatherFromBurnInLayer(keyguardRootView, smartspaceViewModel)
        } else {
            addDateWeatherToBurnInLayer(keyguardRootView, smartspaceViewModel)
        }
        clockViewModel.burnInLayer?.updatePostLayout(keyguardRootView)
    }

    private fun addDateWeatherToBurnInLayer(
        constraintLayout: ConstraintLayout,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) {
        constraintLayout.findViewById<Layer>(R.id.burn_in_layer)?.apply {
            if (smartspaceViewModel.isSmartspaceEnabled) {
                val dateView =
                    constraintLayout.requireViewById<View>(sharedR.id.date_smartspace_view)
                addView(dateView)
            }
        }
    }

    private fun removeDateWeatherFromBurnInLayer(
        constraintLayout: ConstraintLayout,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) {
        constraintLayout.findViewById<Layer>(R.id.burn_in_layer)?.apply {
            if (smartspaceViewModel.isSmartspaceEnabled) {
                val dateView =
                    constraintLayout.requireViewById<View>(sharedR.id.date_smartspace_view)
                removeView(dateView)
            }
        }
    }

    private const val TAG = "KeyguardSmartspaceViewBinder"
}
