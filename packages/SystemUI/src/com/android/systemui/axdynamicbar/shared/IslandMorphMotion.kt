/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlin.math.PI
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Springs are described by response (seconds) and damping ratio.
internal object IslandMorphMotion {
    private const val SCALE_THRESHOLD = 0.001f

    private fun springFor(
        response: Float,
        damping: Float,
        threshold: Float = Spring.DefaultDisplacementThreshold,
    ): SpringSpec<Float> {
        val omega = 2.0 * PI / response
        return spring(
            dampingRatio = damping,
            stiffness = (omega * omega).toFloat(),
            visibilityThreshold = threshold,
        )
    }

    val OpenWidth = springFor(0.40f, 0.76f, SCALE_THRESHOLD)
    val OpenHeight = springFor(0.48f, 0.78f, SCALE_THRESHOLD)
    val OpenPosition = springFor(0.46f, 0.74f)
    val CloseWidth = springFor(0.30f, 0.92f, SCALE_THRESHOLD)
    val CloseHeight = springFor(0.34f, 0.90f, SCALE_THRESHOLD)
    val ClosePosition = springFor(0.40f, 0.90f)
    val PillShrink = springFor(0.34f, 0.86f, SCALE_THRESHOLD)

    const val OpenFadeMillis = 180
    const val CloseFadeMillis = 160

    // Starts from the current value, so an interrupted shrink restores without a jump.
    val PillRestore: KeyframesSpec<Float> = keyframes {
        durationMillis = 380
        1.08f at 240 using FastOutSlowInEasing
        0.96f at 320
        1f at 380
    }
}

internal fun LayoutCoordinates.boundsOnScreen(view: View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

private const val PILL_FADE_SCALE = 0.7f
private const val MORPH_FALLBACK_SCALE = 0.4f

internal fun pillMorphAlpha(scale: Float): Float = (scale / PILL_FADE_SCALE).coerceIn(0f, 1f)

@Composable
internal fun rememberChipMorphScale(hidden: Boolean): Animatable<Float, AnimationVector1D> {
    val scale = remember { Animatable(1f, visibilityThreshold = 0.001f) }
    LaunchedEffect(hidden) {
        if (hidden) {
            scale.animateTo(0f, IslandMorphMotion.PillShrink)
        } else if (scale.value < 1f) {
            scale.animateTo(1f, IslandMorphMotion.PillRestore)
        }
    }
    return scale
}

// Grows the panel out of the chip's on-screen bounds and shrinks it back into them.
// onHidden fires once the panel has fully left the composition.
@Composable
internal fun ChipMorphContainer(
    visible: Boolean,
    chipBounds: Rect?,
    onHidden: () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val view = LocalView.current
    val currentChipBounds by rememberUpdatedState(chipBounds)
    val currentOnHidden by rememberUpdatedState(onHidden)
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    val measured by remember { derivedStateOf { panelBounds != null } }
    val scaleX = remember { Animatable(MORPH_FALLBACK_SCALE) }
    val scaleY = remember { Animatable(MORPH_FALLBACK_SCALE) }
    val shiftX = remember { Animatable(0f) }
    val shiftY = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible, measured) {
        val panel = panelBounds
        if (panel == null) {
            alpha.snapTo(0f)
            if (!visible) currentOnHidden()
            return@LaunchedEffect
        }
        val from = morphSourceFor(panel, currentChipBounds)
        if (visible) {
            if (alpha.value == 0f) {
                scaleX.snapTo(from.width / panel.width)
                scaleY.snapTo(from.height / panel.height)
                shiftX.snapTo(from.left - panel.left)
                shiftY.snapTo(from.top - panel.top)
            }
            coroutineScope {
                launch { scaleX.animateTo(1f, IslandMorphMotion.OpenWidth) }
                launch { scaleY.animateTo(1f, IslandMorphMotion.OpenHeight) }
                launch { shiftX.animateTo(0f, IslandMorphMotion.OpenPosition) }
                launch { shiftY.animateTo(0f, IslandMorphMotion.OpenPosition) }
                launch { alpha.animateTo(1f, tween(IslandMorphMotion.OpenFadeMillis)) }
            }
        } else if (alpha.value > 0f) {
            coroutineScope {
                launch { scaleX.animateTo(from.width / panel.width, IslandMorphMotion.CloseWidth) }
                launch { scaleY.animateTo(from.height / panel.height, IslandMorphMotion.CloseHeight) }
                launch { shiftX.animateTo(from.left - panel.left, IslandMorphMotion.ClosePosition) }
                launch { shiftY.animateTo(from.top - panel.top, IslandMorphMotion.ClosePosition) }
                launch { alpha.animateTo(0f, tween(IslandMorphMotion.CloseFadeMillis)) }
            }
        }
    }

    val morphModifier = remember(view) {
        Modifier.onGloballyPositioned {
            if (it.size.width > 0 && it.size.height > 0) panelBounds = it.boundsOnScreen(view)
        }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                this.scaleX = scaleX.value
                this.scaleY = scaleY.value
                translationX = shiftX.value
                translationY = shiftY.value
                this.alpha = alpha.value
            }
    }

    if (!visible && alpha.value == 0f) return

    DisposableEffect(Unit) { onDispose { panelBounds = null } }
    CompositionLocalProvider(LocalBlurAlpha provides alpha.value) {
        content(morphModifier)
    }
}

private fun morphSourceFor(panel: Rect, chip: Rect?): Rect {
    if (chip != null && chip.width > 0f && chip.height > 0f) {
        val width = chip.width.coerceIn(panel.width * 0.1f, panel.width)
        val height = chip.height.coerceIn(panel.height * 0.05f, panel.height)
        return Rect(Offset(chip.center.x - width / 2f, chip.center.y - height / 2f), Size(width, height))
    }
    val width = panel.width * MORPH_FALLBACK_SCALE
    val height = panel.height * MORPH_FALLBACK_SCALE
    return Rect(Offset(panel.center.x - width / 2f, panel.top), Size(width, height))
}
