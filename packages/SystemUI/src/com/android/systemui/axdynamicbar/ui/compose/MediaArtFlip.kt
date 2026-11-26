/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val FlipInSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 260f)
private const val CLEARED_SETTLE_MS = 300L

// Turns the artwork like a card when the image itself changes, not when the track does, so a
// track that briefly keeps the previous cover flips once, to its own.
@Composable
internal fun MediaArtFlip(
    artwork: Drawable?,
    modifier: Modifier = Modifier,
    content: @Composable (Drawable?) -> Unit,
) {
    val angle = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(artwork) }

    LaunchedEffect(artwork) {
        if (artwork === shown) return@LaunchedEffect
        // Cleared art often comes back a moment later; wait so that is a single flip.
        if (artwork == null) delay(CLEARED_SETTLE_MS)
        if (isSameImage(shown, artwork)) {
            shown = artwork
            return@LaunchedEffect
        }
        angle.animateTo(90f, tween(160, easing = FastOutLinearInEasing))
        shown = artwork
        angle.snapTo(-90f)
        angle.animateTo(0f, FlipInSpring)
    }

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

private suspend fun isSameImage(a: Drawable?, b: Drawable?): Boolean {
    if (a === b) return true
    val first = (a as? BitmapDrawable)?.bitmap ?: return false
    val second = (b as? BitmapDrawable)?.bitmap ?: return false
    if (first === second) return true
    if (first.width != second.width || first.height != second.height) return false
    return withContext(Dispatchers.Default) {
        runCatching { first.sameAs(second) }.getOrDefault(false)
    }
}
