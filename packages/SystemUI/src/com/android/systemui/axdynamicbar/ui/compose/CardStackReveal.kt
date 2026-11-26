/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
import com.android.systemui.axdynamicbar.shared.LocalBlurAlpha
import kotlinx.coroutines.launch

private val CardGrow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
private val CardEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private const val STAGGER_BASE_MS = 90f
private const val STAGGER_MS = 45f
private const val CARD_IN_MS = 380
private const val CARD_OUT_MS = 260
private const val MAX_STAGGERED = 6
private const val TUCKED_SCALE = 0.9f
private const val END_MS = STAGGER_BASE_MS + MAX_STAGGERED * STAGGER_MS + CARD_IN_MS

// One clock for the whole stack, so cards scrolled back into view do not replay their reveal.
@Stable
internal class CardStackReveal {
    val elapsed = Animatable(0f)
    val fold = Animatable(1f)
    var firstTop by mutableFloatStateOf(0f)

    fun progress(index: Int): Float {
        if (index == 0) return 1f
        val start = STAGGER_BASE_MS + index.coerceAtMost(MAX_STAGGERED) * STAGGER_MS
        val t = ((elapsed.value - start) / CARD_IN_MS).coerceIn(0f, 1f)
        return minOf(CardGrow.transform(t), fold.value)
    }

    fun alpha(index: Int): Float = (progress(index) * 1.6f).coerceAtMost(1f)
}

@Composable
internal fun rememberCardStackReveal(visible: Boolean): CardStackReveal {
    val reveal = remember { CardStackReveal() }
    LaunchedEffect(visible) {
        if (visible) {
            launch { reveal.fold.animateTo(1f, tween(CARD_IN_MS, easing = CardGrow)) }
            val left = (END_MS - reveal.elapsed.value).toInt()
            if (left > 0) reveal.elapsed.animateTo(END_MS, tween(left, easing = LinearEasing))
        } else {
            reveal.fold.animateTo(0f, tween(CARD_OUT_MS, easing = CardEase))
        }
    }
    return reveal
}

// Cards below the first slide out from under it one after another, and tuck back on close.
@Composable
internal fun StackedCard(
    reveal: CardStackReveal,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var top by remember { mutableFloatStateOf(0f) }
    val cardAlpha by remember(reveal, index) { derivedStateOf { reveal.alpha(index) } }
    val parentBlur = LocalBlurAlpha.current
    Box(
        modifier
            .zIndex(-index.toFloat())
            .onPlaced {
                top = it.positionInParent().y
                if (index == 0) reveal.firstTop = top
            }
            .graphicsLayer {
                val f = reveal.progress(index)
                translationY = -(top - reveal.firstTop).coerceAtLeast(0f) * (1f - f)
                val scale = TUCKED_SCALE + (1f - TUCKED_SCALE) * f
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
                alpha = reveal.alpha(index)
            }
    ) {
        CompositionLocalProvider(LocalBlurAlpha provides parentBlur * cardAlpha, content = content)
    }
}
