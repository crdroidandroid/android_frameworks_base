/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.Drawable
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BAR_COUNT = 4
private const val MIN_LEVEL = 0.08f
private const val CAPTURE_TIMEOUT_MS = 1200L
private const val ACCENT_BLEND = 0.30f

private val RestLevels = floatArrayOf(0.20f, 0.35f, 0.35f, 0.20f)
private val BeatSpring = spring<Float>(dampingRatio = 0.50f, stiffness = 280f)

private class BarWave(val min: Float, val max: Float, val durationMs: Int, val delayMs: Int)

private val FallbackWaves = arrayOf(
    BarWave(0.22f, 0.88f, 380, 0),
    BarWave(0.35f, 1.00f, 470, 140),
    BarWave(0.28f, 0.95f, 340, 70),
    BarWave(0.18f, 0.82f, 430, 210),
)

// Follows the output mix; loops a canned wave while no capture data arrives.
@Composable
internal fun AudioReactiveBars(
    color: Color,
    modifier: Modifier = Modifier,
    artwork: Drawable? = null,
    barWidth: Dp = 2.5.dp,
    maxBarHeight: Dp = 13.dp,
    spacing: Dp = 1.6.dp,
) {
    val artworkAccent by rememberArtworkAccent(artwork)
    val accent by animateColorAsState(
        artworkAccent?.let { lerp(color, it, ACCENT_BLEND) } ?: color,
        tween(600),
        label = "bars_accent",
    )
    val targets = remember { mutableStateListOf(*RestLevels.toTypedArray()) }
    val levels = remember { List(BAR_COUNT) { Animatable(RestLevels[it]) } }
    var capturing by remember { mutableStateOf(false) }
    var lastCaptureMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val listener = object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {
                val data = waveform?.takeIf { it.isNotEmpty() } ?: return
                val half = data.size / 2
                val outer = data.windowEnergy(0, half / 2)
                val inner = data.windowEnergy(half / 2, half)
                val bands = floatArrayOf(outer, inner, inner, outer)
                handler.post {
                    lastCaptureMs = SystemClock.elapsedRealtime()
                    capturing = true
                    bands.forEachIndexed { i, band ->
                        targets[i] = (targets[i] * 0.55f + band * 0.45f).coerceIn(MIN_LEVEL, 1f)
                    }
                }
            }

            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) = Unit
        }
        val visualizer = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(256)
                setDataCaptureListener(listener, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        }.getOrNull()
        onDispose {
            handler.removeCallbacksAndMessages(null)
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { targets.toList() }.collect { values ->
            values.forEachIndexed { i, v -> launch { levels[i].animateTo(v, BeatSpring) } }
        }
    }

    LaunchedEffect(capturing) {
        while (capturing) {
            delay(CAPTURE_TIMEOUT_MS)
            if (SystemClock.elapsedRealtime() - lastCaptureMs >= CAPTURE_TIMEOUT_MS) {
                capturing = false
            }
        }
    }

    val fallback = rememberFallbackWave(active = !capturing)

    Canvas(modifier.size(barWidth * BAR_COUNT + spacing * (BAR_COUNT - 1), maxBarHeight)) {
        val barPx = barWidth.toPx()
        val stepPx = barPx + spacing.toPx()
        val maxH = size.height
        val radius = CornerRadius(barPx / 2f)
        for (i in 0 until BAR_COUNT) {
            val level = (fallback?.get(i)?.value ?: levels[i].value).coerceIn(MIN_LEVEL, 1f)
            val barH = maxH * level
            val top = maxH - barH
            val brush = Brush.verticalGradient(
                0f to accent.copy(alpha = (0.65f + level * 0.35f) * accent.alpha),
                1f to color.copy(alpha = (0.22f + level * 0.18f) * color.alpha),
                startY = top,
                endY = maxH,
            )
            drawRoundRect(brush, Offset(i * stepPx, top), Size(barPx, barH), radius)
        }
    }
}

@Composable
private fun rememberFallbackWave(active: Boolean): List<State<Float>>? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "bars_fallback")
    return FallbackWaves.mapIndexed { i, wave ->
        val rising = i % 2 == 0
        transition.animateFloat(
            initialValue = if (rising) wave.min else wave.max,
            targetValue = if (rising) wave.max else wave.min,
            animationSpec = infiniteRepeatable(
                tween(wave.durationMs, wave.delayMs, FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "bars_fallback_$i",
        )
    }
}

@Composable
private fun rememberArtworkAccent(artwork: Drawable?): State<Color?> =
    produceState<Color?>(null, artwork) {
        value = null
        if (artwork == null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val palette = Palette.from(artwork.toBitmap(64, 64)).generate()
                (palette.vibrantSwatch ?: palette.lightVibrantSwatch
                    ?: palette.mutedSwatch ?: palette.dominantSwatch)?.rgb?.let { Color(it) }
            }.getOrNull()
        }
    }

private fun ByteArray.windowEnergy(start: Int, end: Int): Float {
    if (start < 0 || end > size || start >= end) return MIN_LEVEL
    var sum = 0f
    for (i in start until end) {
        val n = abs((this[i].toInt() and 0xFF) - 128) / 128f
        sum += n * n
    }
    return sqrt(sqrt(sum / (end - start))).coerceIn(MIN_LEVEL, 1f)
}
