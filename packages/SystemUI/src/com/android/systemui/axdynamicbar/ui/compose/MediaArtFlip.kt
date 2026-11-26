/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private val FlipInSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 260f)

// Turns the artwork like a card when the track changes. The old artwork stays until it is edge
// on, so artwork arriving after the title never swaps in mid-turn.
@Composable
internal fun <T> MediaArtFlip(
    track: Any?,
    artwork: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val angle = remember { Animatable(0f) }
    var shownTrack by remember { mutableStateOf(track) }
    var holding by remember { mutableStateOf(false) }
    val lastShown = remember { arrayOf<Any?>(artwork) }
    var held by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(track) {
        if (track == shownTrack) return@LaunchedEffect
        shownTrack = track
        if (!holding) held = lastShown[0]
        holding = true
        angle.animateTo(90f, tween(160, easing = FastOutLinearInEasing))
        holding = false
        angle.snapTo(-90f)
        angle.animateTo(0f, FlipInSpring)
    }

    val pending = track != shownTrack
    @Suppress("UNCHECKED_CAST")
    val shown =
        when {
            holding -> held as T
            pending -> lastShown[0] as T
            else -> artwork
        }
    SideEffect { if (!holding && !pending) lastShown[0] = artwork }

    Box(
        modifier.graphicsLayer {
            rotationY = angle.value
            cameraDistance = 12f * density
        },
        contentAlignment = Alignment.Center,
    ) {
        content(shown)
    }
}
