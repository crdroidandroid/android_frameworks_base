/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.sliders.ui

import android.os.UserHandle
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.domain.interactor.DesktopAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberGradientColorMode
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberGradientCustomColors
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberVolumeGradientEnabled
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@VolumeDialogSliderScope
class VolumeDialogSliderViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSliderViewModel,
    private val overscrollViewModel: VolumeDialogOverscrollViewModel,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val desktopAudioTileDetailsFeatureInteractor: DesktopAudioTileDetailsFeatureInteractor,
) {
    fun bind(view: View) {
        // Use horizontal volume dialog if the audio tile details view is enabled
        val isVolumeDialogVertical = !desktopAudioTileDetailsFeatureInteractor.isEnabled()
        val sliderComposeView: ComposeView = view.requireViewById(R.id.volume_dialog_slider)
        sliderComposeView.setContent {
            PlatformTheme {
                VolumeDialogSlider(
                    viewModel = viewModel,
                    overscrollViewModel = overscrollViewModel,
                    hapticsViewModelFactory = hapticsViewModelFactory,
                    isVolumeDialogVertical = isVolumeDialogVertical,
                )
            }
        }
    }
}

@Composable
private fun rememberVolumeHapticsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
            1,
            UserHandle.USER_CURRENT,
        ) != 0
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeDialogSlider(
    viewModel: VolumeDialogSliderViewModel,
    overscrollViewModel: VolumeDialogOverscrollViewModel,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    isVolumeDialogVertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors =
        SliderDefaults.colors(
            activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    val collectedSliderStateModel by viewModel.state.collectAsStateWithLifecycle(null)
    val sliderStateModel = collectedSliderStateModel ?: return
    val interactionSource = remember { MutableInteractionSource() }
    val isHapticsEnabled = rememberVolumeHapticsEnabled()

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> viewModel.onSliderDragStarted()
                is DragInteraction.Cancel -> viewModel.onSliderDragFinished()
                is DragInteraction.Stop -> viewModel.onSliderDragFinished()
            }
        }
    }

    val thumbColorOverride: Color? =
        if (!rememberVolumeGradientEnabled()) {
            null
        } else if (rememberGradientColorMode() == 1) {
            val (customStart, _) = rememberGradientCustomColors()
            customStart
        } else {
            MaterialTheme.colorScheme.primary
        }

    Slider(
        value = sliderStateModel.value,
        valueRange = sliderStateModel.valueRange,
        onValueChanged = { value ->
            overscrollViewModel.setSlider(
                value = value,
                min = sliderStateModel.valueRange.start,
                max = sliderStateModel.valueRange.endInclusive,
            )
            viewModel.setStreamVolume(value, true)
        },
        onValueChangeFinished = { viewModel.onSliderChangeFinished(it) },
        isEnabled = !sliderStateModel.isDisabled,
        isReverseDirection = true,
        isVertical = isVolumeDialogVertical,
        colors = colors,
        interactionSource = interactionSource,
        haptics = if (isHapticsEnabled) {
            Haptics.Enabled(
                hapticsViewModelFactory = hapticsViewModelFactory,
                hapticConfigs =
                    VolumeHapticsConfigsProvider.continuousConfigs(SliderHapticFeedbackFilter()),
                orientation =
                    if (isVolumeDialogVertical) {
                        Orientation.Vertical
                    } else {
                        Orientation.Horizontal
                    },
            )
        } else {
            Haptics.Disabled
        },
        stepDistance = 1f,
        track = { sliderState ->
            SliderTrack(
                sliderState,
                colors = colors,
                isEnabled = !sliderStateModel.isDisabled,
                isVertical = isVolumeDialogVertical,
                activeTrackEndIcon = { iconsState ->
                    SliderIcon(
                        icon = {
                            Icon(
                                icon = sliderStateModel.icon,
                                tint = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        isVisible = !iconsState.isInactiveTrackEndIconVisible,
                    )
                },
                inactiveTrackEndIcon = { iconsState ->
                    SliderIcon(
                        icon = {
                            Icon(
                                icon = sliderStateModel.icon,
                                tint = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        isVisible = iconsState.isInactiveTrackEndIconVisible,
                    )
                },
                ignoreGradient = false,
            )
        },
        thumb = { sliderState, interactions ->
            SliderDefaults.Thumb(
                sliderState = sliderState,
                interactionSource = interactions,
                enabled = !sliderStateModel.isDisabled,
                colors = SliderDefaults.colors(
                    thumbColor = thumbColorOverride ?: SliderDefaults.colors().thumbColor
                ),
                thumbSize =
                    if (isVolumeDialogVertical) {
                        DpSize(52.dp, 4.dp)
                    } else {
                        DpSize(4.dp, 52.dp)
                    },
            )
        },
        accessibilityParams = AccessibilityParams(contentDescription = sliderStateModel.label),
        modifier =
            modifier.pointerInput(Unit) {
                coroutineScope {
                    val currentContext = currentCoroutineContext()
                    awaitPointerEventScope {
                        while (currentContext.isActive) {
                            viewModel.onTouchEvent(awaitPointerEvent())
                        }
                    }
                }
            },
    )
}
