/*
 * Copyright (C) 2026 crDroid Android Project
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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

fun Modifier.tileToggleAnimation(animationStyle: Int, state: Int): Modifier =
    composed {
        if (animationStyle == 0) return@composed this

        val trackedState = remember { mutableIntStateOf(state) }
        val lastToggleState = remember { mutableIntStateOf(state) }
        val toggleCount = remember { mutableIntStateOf(0) }

        if (trackedState.intValue != state) {
            val isToggleTransition =
                (state == STATE_ACTIVE || state == STATE_INACTIVE) &&
                    (trackedState.intValue == STATE_ACTIVE ||
                        trackedState.intValue == STATE_INACTIVE)
            if (isToggleTransition) {
                lastToggleState.intValue = state
                trackedState.intValue = state
                toggleCount.intValue += 1
            } else {
                trackedState.intValue = state
            }
        }

        when (animationStyle) {
            1 -> bounceAnimation(toggleCount.intValue)
            2 -> rippleAnimation(toggleCount.intValue, lastToggleState.intValue == STATE_ACTIVE)
            3 -> flipAnimation(toggleCount.intValue)
            4 -> fadeAnimation(toggleCount.intValue)
            5 -> pulseAnimation(toggleCount.intValue, lastToggleState.intValue == STATE_ACTIVE)
            6 -> shakeAnimation(toggleCount.intValue)
            7 -> wobbleAnimation(toggleCount.intValue)
            8 -> spinAnimation(toggleCount.intValue)
            9 -> squishAnimation(toggleCount.intValue)
            10 -> tiltAnimation(toggleCount.intValue)
            11 -> heartbeatAnimation(toggleCount.intValue)
            12 -> swingAnimation(toggleCount.intValue)
            else -> this
        }
    }

@Composable
private fun Modifier.bounceAnimation(toggleCount: Int): Modifier {
    val scale = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                scale.snapTo(1f)
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = keyframes {
                        durationMillis = 500
                        1.18f at 160 using FastOutSlowInEasing
                        0.92f at 320 using FastOutSlowInEasing
                        1f at 500
                    },
                )
            }
    }

    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
private fun Modifier.rippleAnimation(toggleCount: Int, toActive: Boolean): Modifier {
    val progress = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                progress.snapTo(0f)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                )
            }
    }

    val rippleColor = if (toActive) MaterialTheme.colorScheme.primary
                        else LocalAndroidColorScheme.current.surfaceEffect1

    return this.drawWithContent {
        drawContent()
        val p = progress.value
        if (p > 0f) {
            val alpha =
                if (p in 0.01f..0.99f) (1f - p).coerceIn(0f, 0.5f) else 0f
            if (alpha > 0f) {
                val maxDim = kotlin.math.max(size.width, size.height)
                drawCircle(
                    color = rippleColor.copy(alpha = alpha),
                    radius = maxDim * p,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
    }
}

@Composable
private fun Modifier.flipAnimation(toggleCount: Int): Modifier {
    val rotation = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val density = LocalDensity.current
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                )
                rotation.snapTo(0f)
            }
    }

    return this.graphicsLayer {
        val r = rotation.value
        rotationY = r
        alpha = if (r < 80f || r > 280f) 1f else 0f
        cameraDistance = 12f * density.density
    }
}

@Composable
private fun Modifier.fadeAnimation(toggleCount: Int): Modifier {
    val alpha = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scale = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                alpha.snapTo(1f)
                scale.snapTo(1f)
                alpha.animateTo(0f, tween(durationMillis = 200))
                scale.snapTo(0.93f)
                alpha.animateTo(1f, tween(durationMillis = 200))
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = 1500f,
                        ),
                )
            }
    }

    return this.graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
private fun Modifier.pulseAnimation(toggleCount: Int, toActive: Boolean): Modifier {
    val glowProgress = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                glowProgress.snapTo(0f)
                glowProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                )
            }
    }

    val glowColor = if (toActive) Color(0xFF4FC3F7) else Color(0xFFEF5350)

    return this.drawWithContent {
        drawContent()
        val p = glowProgress.value
        if (p > 0f) {
            val alpha =
                if (p in 0.01f..0.99f) {
                    val ramp = if (p < 0.4f) p / 0.4f else (1f - p) / 0.6f
                    ramp * 0.45f
                } else 0f
            if (alpha > 0f) {
                val maxDim = kotlin.math.max(size.width, size.height)
                val radius = maxDim * 0.6f * (p * 0.4f + 0.8f)
                drawCircle(
                    color = glowColor.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
    }
}

@Composable
private fun Modifier.shakeAnimation(toggleCount: Int): Modifier {
    val offsetX = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val density = LocalDensity.current
    val shakePx = with(density) { 4.dp.toPx() }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                offsetX.snapTo(0f)
                offsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 450
                        shakePx at 75
                        -shakePx at 150
                        shakePx at 225
                        -shakePx at 300
                        (shakePx * 0.5f) at 375
                        0f at 450
                    },
                )
            }
    }

    return this.graphicsLayer { translationX = offsetX.value }
}

@Composable
private fun Modifier.wobbleAnimation(toggleCount: Int): Modifier {
    val rotation = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 450
                        8f at 75 using FastOutSlowInEasing
                        -8f at 150
                        6f at 225
                        -6f at 300
                        3f at 375
                        0f at 450
                    },
                )
            }
    }

    return this.graphicsLayer { rotationZ = rotation.value }
}

@Composable
private fun Modifier.spinAnimation(toggleCount: Int): Modifier {
    val rotation = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                )
                rotation.snapTo(0f)
            }
    }

    return this.graphicsLayer { rotationZ = rotation.value }
}

@Composable
private fun Modifier.squishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                // Animate both axes in parallel — squash horizontally, stretch vertically.
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 500
                                1.18f at 150 using FastOutSlowInEasing
                                0.92f at 300
                                1f at 500
                            },
                        )
                    }
                    scaleY.animateTo(
                        targetValue = 1f,
                        animationSpec = keyframes {
                            durationMillis = 500
                            0.85f at 150 using FastOutSlowInEasing
                            1.08f at 300
                            1f at 500
                        },
                    )
                }
            }
    }

    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}

@Composable
private fun Modifier.tiltAnimation(toggleCount: Int): Modifier {
    val rotation = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val density = LocalDensity.current
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 500
                        25f at 150 using FastOutSlowInEasing
                        -10f at 300
                        0f at 500
                    },
                )
            }
    }

    return this.graphicsLayer {
        rotationX = rotation.value
        cameraDistance = 12f * density.density
    }
}

@Composable
private fun Modifier.heartbeatAnimation(toggleCount: Int): Modifier {
    val scale = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                scale.snapTo(1f)
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = keyframes {
                        durationMillis = 600
                        // First beat (lub)
                        1.15f at 100 using FastOutSlowInEasing
                        1f at 200
                        // Second beat (dub) — slightly smaller, classic heartbeat rhythm
                        1.12f at 320 using FastOutSlowInEasing
                        1f at 450
                        1f at 600
                    },
                )
            }
    }

    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
private fun Modifier.swingAnimation(toggleCount: Int): Modifier {
    val rotation = remember { Animatable(0f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)

    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 600
                        15f at 120 using FastOutSlowInEasing
                        -12f at 240 using FastOutSlowInEasing
                        8f at 360
                        -5f at 480
                        0f at 600
                    },
                )
            }
    }

    return this.graphicsLayer {
        rotationZ = rotation.value
        // Pivot from the top-center so the tile swings like a pendant.
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

@Composable
fun rememberQSTileAnimationStyle(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readAnimationStyle(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver,
                Settings.System.QS_TILE_ANIMATION_STYLE,
                0,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            0
        }
    }

    var animationStyle by remember { mutableIntStateOf(readAnimationStyle()) }

    DisposableEffect(contentResolver) {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { animationStyle = readAnimationStyle() }
                }
            }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_ANIMATION_STYLE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return animationStyle
}
