/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

internal const val PRESSED_SCALE = 0.90f

internal val LocalIslandHaptics = compositionLocalOf { false }

@Composable
internal fun ProvideIslandHaptics(enabled: StateFlow<Boolean>, content: @Composable () -> Unit) {
    val hapticsEnabled by enabled.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalIslandHaptics provides hapticsEnabled, content = content)
}

private val PressDownSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val ReleaseSpring =
    spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

// Shrinks while pressed and ticks on release; clicks, ripple and semantics stay with the caller.
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = PRESSED_SCALE,
): Modifier = composed {
    val view = LocalView.current
    val haptics by rememberUpdatedState(LocalIslandHaptics.current)
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scale.animateTo(pressedScale, PressDownSpring)
                is PressInteraction.Release -> {
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    scale.animateTo(1f, ReleaseSpring)
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, ReleaseSpring)
            }
        }
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
