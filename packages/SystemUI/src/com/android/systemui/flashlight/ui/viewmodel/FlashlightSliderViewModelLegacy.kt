/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.flashlight.ui.viewmodel

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.compose.runtime.getValue
import com.android.internal.logging.UiEventLogger
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.flashlight.shared.logger.FlashlightLogger
import com.android.systemui.flashlight.shared.logger.FlashlightUiEvent
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.FlashlightController
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel adapter that bridges the old FlashlightController to work with the new vertical
 * slider UI. This provides backward compatibility when the new FlashlightRepository doesn't
 * support adjustable levels but the old FlashlightController does.
 */
class FlashlightSliderViewModelLegacy
@AssistedInject
constructor(
    override val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val flashlightController: FlashlightController,
    private val logger: FlashlightLogger,
    private val uiEventLogger: UiEventLogger,
    private val imageLoader: ImageLoader,
) : ExclusiveActivatable(), FlashlightSliderViewModelInterface {
    private val hydrator = Hydrator("FlashlightSliderViewModelLegacy.hydrator")

    private val _currentFlashlightLevel = MutableStateFlow<FlashlightModel.Available.Level?>(
        if (flashlightController.isStrengthControlSupported() && flashlightController.isAvailable()) {
            val maxLevel = flashlightController.getMaxLevel().coerceAtLeast(1)
            val currentLevel = flashlightController.getCurrentLevel().coerceIn(1, maxLevel)
            FlashlightModel.Available.Level(
                enabled = flashlightController.isEnabled(),
                level = currentLevel,
                max = maxLevel
            )
        } else {
            null
        }
    )

    override val currentFlashlightLevel: FlashlightModel.Available.Level? by
        hydrator.hydratedStateOf(
            "currentFlashlightLevel",
            _currentFlashlightLevel.value,
            _currentFlashlightLevel.asStateFlow(),
        )

    override val isFlashlightAdjustable: Boolean by
        hydrator.hydratedStateOf(
            "isFlashlightAdjustable",
            flashlightController.isStrengthControlSupported(),
            // Note: FlashlightController doesn't expose a Flow, so we use a static value
            // In practice, this should be checked before creating this ViewModel
            kotlinx.coroutines.flow.flowOf(flashlightController.isStrengthControlSupported()),
        )

    private val listener = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            updateCurrentLevel(enabled)
        }

        override fun onFlashlightError() {
            // Handle error if needed
        }

        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            if (available && flashlightController.isStrengthControlSupported()) {
                updateCurrentLevel(flashlightController.isEnabled())
            } else {
                _currentFlashlightLevel.value = null
            }
        }

        override fun onFlashlightStrengthChanged(level: Int) {
            updateCurrentLevel(flashlightController.isEnabled())
        }
    }

    private fun updateCurrentLevel(enabled: Boolean) {
        if (flashlightController.isStrengthControlSupported() && flashlightController.isAvailable()) {
            val maxLevel = flashlightController.getMaxLevel().coerceAtLeast(1)
            val currentLevel = if (enabled) {
                flashlightController.getCurrentLevel().coerceIn(1, maxLevel)
            } else {
                flashlightController.getDefaultLevel().coerceIn(1, maxLevel)
            }
            _currentFlashlightLevel.value = FlashlightModel.Available.Level(
                enabled = enabled,
                level = currentLevel,
                max = maxLevel
            )
        } else {
            _currentFlashlightLevel.value = null
        }
    }

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
        flashlightController.addCallback(listener)
        // Update initial state
        updateCurrentLevel(flashlightController.isEnabled())
        // Note: Callback cleanup should be handled by the lifecycle of the component
        // using this ViewModel. FlashlightController uses WeakReference for callbacks.
    }

    override fun setFlashlightLevel(value: Int) {
        setFlashlightLevel(value, false)
    }

    override fun setFlashlightLevelTemporary(value: Int) {
        setFlashlightLevel(value, true)
    }

    private fun setFlashlightLevel(value: Int, temporary: Boolean) {
        if (!flashlightController.isStrengthControlSupported()) {
            logger.w(
                "FlashlightSliderViewModelLegacy attempted to set level to $value when strength control not supported"
            )
            return
        }

        uiEventLogger.logWithPosition(FlashlightUiEvent.FLASHLIGHT_SLIDER_SET_LEVEL, 0, null, value)

        if (value == 0) {
            flashlightController.setFlashlight(false)
        } else {
            val maxLevel = flashlightController.getMaxLevel().coerceAtLeast(1)
            val clampedLevel = value.coerceIn(1, maxLevel)
            if (!flashlightController.isEnabled()) {
                flashlightController.setFlashlight(true)
            }
            flashlightController.setFlashlightStrengthLevel(clampedLevel)
            // For temporary changes, we don't persist - the old controller handles persistence
            // automatically via Settings.System
        }
    }

    override suspend fun loadImage(@DrawableRes resId: Int, context: Context): Icon.Loaded {
        return imageLoader
            .loadDrawable(
                android.graphics.drawable.Icon.createWithResource(context, resId),
                maxHeight = 200,
                maxWidth = 200,
            )!!
            .asIcon(null, resId)
    }

    @AssistedFactory
    interface Factory {
        fun create(): FlashlightSliderViewModelLegacy
    }

    companion object {
        @DrawableRes
        fun getIconForPercentage(@FloatRange(0.0, 100.0) percentage: Float): Int {
            return when {
                percentage == 0f -> R.drawable.vd_flashlight_off
                else -> R.drawable.vd_flashlight_on
            }
        }
    }
}
