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

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.volume.ui.compose.slider

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigs
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DefaultAnimationSpec =
    spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

/**
 * Filters out values if they update more often that the [debounceDuration] after the drag finishes.
 * The actual [incomingValue] will be eventually emitted to keep the slider aligned with the
 * upcoming values.
 *
 * This helps with the rapid slider changes when the incoming values appear with a slight delay.
 * This allows slider to smoothly catchup later.
 */
@Composable
private fun debouncedValueState(
    incomingValue: Float,
    sliderState: SliderState,
    debounceDuration: Duration,
    interactionSource: InteractionSource,
): FloatState {
    val valueState = remember { mutableFloatStateOf(incomingValue) }
    var debounceStartTimestamp by remember { mutableLongStateOf(0) }
    var debouncedValue by remember { mutableFloatStateOf(incomingValue) }
    val currentTimestamp = System.currentTimeMillis()
    val shouldDebounce =
        currentTimestamp - debounceStartTimestamp < debounceDuration.inWholeMilliseconds
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction: Interaction ->
            when (interaction) {
                is DragInteraction.Stop -> {
                    debounceStartTimestamp = System.currentTimeMillis()
                    debouncedValue = sliderState.value
                }
            }
        }
    }
    LaunchedEffect(shouldDebounce) {
        if (shouldDebounce) {
            delay(debounceDuration)
            valueState.floatValue = incomingValue
        }
    }
    valueState.floatValue =
        if (shouldDebounce) {
            debouncedValue
        } else {
            incomingValue
        }
    return valueState
}

@Composable
fun Slider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: ((Float) -> Unit)?,
    isEnabled: Boolean,
    accessibilityParams: AccessibilityParams,
    modifier: Modifier = Modifier,
    stepDistance: Float = 0f,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    animationSpec: AnimationSpec<Float> = DefaultAnimationSpec,
    haptics: Haptics = Haptics.Disabled,
    isVertical: Boolean = false,
    isReverseDirection: Boolean = false,
    track: (@Composable (SliderState) -> Unit) = { SliderDefaults.Track(it) },
    thumb: (@Composable (SliderState, MutableInteractionSource) -> Unit) = { _, _ ->
        SliderDefaults.Thumb(
            interactionSource = interactionSource,
            colors = colors,
            enabled = isEnabled,
        )
    },
) {
    require(stepDistance >= 0f) { "stepDistance must not be negative" }
    val sliderState = rememberSliderState(value = value, valueRange = valueRange)
    val hapticsViewModel =
        haptics.rememberViewModel(
            sliderState.value,
            valueRange,
            interactionSource,
            sliderState.isDragging,
        )
    val debouncedValue by
        debouncedValueState(
            incomingValue = value,
            sliderState = sliderState,
            interactionSource = interactionSource,
            debounceDuration = 100.milliseconds,
        )

    // We use Animatable for the slider animation to preserve animation velocity when receiving
    // consecutive value updates
    val animatable = remember { Animatable(debouncedValue) }
    val coroutineScope = rememberCoroutineScope()

    SideEffect {
        if (sliderState.isDragging) return@SideEffect
        if (animatable.targetValue != debouncedValue && sliderState.value != debouncedValue) {
            coroutineScope.launchTraced("Slider#animateValue") {
                if (!animatable.isRunning) {
                    // Set initial value. sliderState.value should equal to the current
                    // animation value otherwise, so there is no need to update it
                    animatable.snapTo(sliderState.value)
                }
                animatable.animateTo(targetValue = debouncedValue, animationSpec = animationSpec) {
                    sliderState.value = this.value
                    if (
                        haptics is Haptics.Enabled &&
                            !haptics.isDiscrete() &&
                            sliderState.isDragging
                    ) {
                        hapticsViewModel?.onValueChange(this.value)
                    }
                }
            }
        }
    }

    val valueChange: (Float) -> Unit = { newValue ->
        if (sliderState.isDragging) {
            sliderState.value = newValue
            coroutineScope.launch { animatable.snapTo(newValue) }
        }
        hapticsViewModel?.addVelocityDataPoint(newValue)
        if (
            haptics is Haptics.Enabled &&
                !haptics.isDiscrete() &&
                sliderState.isDragging
        ) {
            hapticsViewModel?.onValueChange(newValue)
        }
        onValueChanged(newValue)
    }
    val semantics =
        createSemantics(
            accessibilityParams,
            debouncedValue,
            valueRange,
            valueChange,
            isEnabled,
            stepDistance,
        )

    sliderState.onValueChangeFinished = {
        hapticsViewModel?.onValueChangeEnded()
        onValueChangeFinished?.invoke(sliderState.value)
    }
    sliderState.onValueChange = valueChange

    if (isVertical) {
        VerticalSlider(
            state = sliderState,
            enabled = isEnabled,
            reverseDirection = isReverseDirection,
            interactionSource = interactionSource,
            colors = colors,
            track = track,
            thumb = { thumb(it, interactionSource) },
            modifier = modifier.clearAndSetSemantics(semantics),
        )
    } else {
        Slider(
            state = sliderState,
            enabled = isEnabled,
            interactionSource = interactionSource,
            colors = colors,
            track = track,
            thumb = { thumb(it, interactionSource) },
            modifier = modifier.clearAndSetSemantics(semantics),
        )
    }
}

private fun createSemantics(
    params: AccessibilityParams,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChanged: (Float) -> Unit,
    isEnabled: Boolean,
    stepDistance: Float,
): SemanticsPropertyReceiver.() -> Unit {
    return {
        contentDescription = params.contentDescription
        if (isEnabled) {
            params.stateDescription?.let { stateDescription = it }
            progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
        } else {
            disabled()
        }
        setProgress { targetValue ->
            val targetDirection =
                when {
                    targetValue > value -> 1f
                    targetValue < value -> -1f
                    else -> 0f
                }
            val offset =
                if (stepDistance > 0) {
                    // advance to the next step when stepDistance is > 0
                    targetDirection * stepDistance
                } else {
                    // advance to the desired value otherwise
                    targetValue - value
                }

            val newValue = (value + offset).coerceIn(valueRange.start, valueRange.endInclusive)
            onValueChanged(newValue)
            true
        }
    }
}

@Composable
private fun Haptics.rememberViewModel(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    interactionSource: MutableInteractionSource,
    isDragging: Boolean,
): SliderHapticsViewModel? {
    return when (this) {
        is Haptics.Disabled -> null
        is Haptics.Enabled -> {
            hapticsViewModelFactory.let {
                rememberViewModel(traceName = "SliderHapticsViewModel") {
                        it.create(
                            interactionSource,
                            valueRange,
                            orientation,
                            hapticConfigs.hapticFeedbackConfig,
                            hapticConfigs.sliderTrackerConfig,
                        )
                    }
                    .also { hapticsViewModel ->
                        if (isDiscrete()) {
                            var lastValue by remember { mutableFloatStateOf(value) }
                            LaunchedEffect(value, isDragging) {
                                val roundedValue = round(value)
                                if (roundedValue != lastValue) {
                                    lastValue = roundedValue
                                    if (isDragging) {
                                        hapticsViewModel.onValueChange(roundedValue)
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}

data class AccessibilityParams(
    val contentDescription: String,
    val stateDescription: String? = null,
)

sealed interface Haptics {
    data object Disabled : Haptics

    data class Enabled(
        val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
        val hapticConfigs: VolumeHapticsConfigs,
        val orientation: Orientation,
    ) : Haptics {
        fun isDiscrete(): Boolean = hapticConfigs.hapticFeedbackConfig.sliderStepSize != 0f
    }
}
