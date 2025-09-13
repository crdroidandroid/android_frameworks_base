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

package com.android.systemui.flashlight.ui.viewmodel

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.compose.runtime.getValue
import com.android.internal.logging.UiEventLogger
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.flashlight.domain.interactor.FlashlightInteractor
import com.android.systemui.flashlight.shared.logger.FlashlightLogger
import com.android.systemui.flashlight.shared.logger.FlashlightUiEvent
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.res.R
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Common interface for flashlight slider view models */
interface FlashlightSliderViewModelInterface {
    val currentFlashlightLevel: FlashlightModel.Available.Level?
    val isFlashlightAdjustable: Boolean
    val hapticsViewModelFactory: SliderHapticsViewModel.Factory
    fun setFlashlightLevel(value: Int)
    fun setFlashlightLevelTemporary(value: Int)
    suspend fun loadImage(@DrawableRes resId: Int, context: Context): Icon.Loaded
}

/** View Model for a flashlight slider. Only used when flashlight supports levels. */
class FlashlightSliderViewModel
@AssistedInject
constructor(
    override val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val flashlightInteractor: FlashlightInteractor,
    private val logger: FlashlightLogger,
    private val uiEventLogger: UiEventLogger,
    private val imageLoader: ImageLoader,
) : ExclusiveActivatable(), FlashlightSliderViewModelInterface {
    private val hydrator = Hydrator("FlashlightSliderViewModel.hydrator")

    override val currentFlashlightLevel: FlashlightModel.Available.Level? by
        hydrator.hydratedStateOf(
            "currentFlashlightLevel",
            flashlightInteractor.state.value as? FlashlightModel.Available.Level,
            flashlightInteractor.state.filterIsInstance(FlashlightModel.Available.Level::class),
        )

    override val isFlashlightAdjustable: Boolean by
        hydrator.hydratedStateOf(
            "isFlashlightAdjustable",
            flashlightInteractor.state.value is FlashlightModel.Available.Level,
            flashlightInteractor.state.map { it is FlashlightModel.Available.Level },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    override fun setFlashlightLevel(value: Int) {
        setFlashlightLevel(value, false)
    }

    override fun setFlashlightLevelTemporary(value: Int) {
        setFlashlightLevel(value, true)
    }

    private fun setFlashlightLevel(value: Int, temporary: Boolean) {
        if (!isFlashlightAdjustable) {
            logger.w(
                "FlashlightSliderViewModel attempted to set level to $value when state was not adjustable"
            )
            return
        }

        uiEventLogger.logWithPosition(FlashlightUiEvent.FLASHLIGHT_SLIDER_SET_LEVEL, 0, null, value)

        if (value == 0) {
            flashlightInteractor.setEnabled(false)
        } else {
            try {
                if (temporary) flashlightInteractor.setTemporaryLevel(value)
                else flashlightInteractor.setLevel(value)
            } catch (ex: IllegalArgumentException) {
                logger.w("FlashlightSliderViewModel#setFlashlightLevel: $ex")
            }
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
        fun create(): FlashlightSliderViewModel
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
