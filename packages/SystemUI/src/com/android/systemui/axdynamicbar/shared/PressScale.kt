/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest

internal const val PRESSED_SCALE = 0.90f

private val PressDownSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val ReleaseSpring =
    spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

// Shrinks while the source reports a press; clicks, ripple and semantics stay with the caller.
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = PRESSED_SCALE,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scale.animateTo(pressedScale, PressDownSpring)
                is PressInteraction.Release,
                is PressInteraction.Cancel -> scale.animateTo(1f, ReleaseSpring)
            }
        }
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
