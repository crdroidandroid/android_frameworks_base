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
package com.android.systemui.pulse

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.sqrt

internal class PulseBassHaptics(
    context: Context
) {

    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var smoothedBass: Float = 0f
    private var primed: Boolean = false
    private var lastVibrateUptimeMs: Long = 0L

    fun process(fft: ByteArray?) {
        if (fft == null || fft.size < MIN_FFT_BYTES) return

        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        var sum = 0f
        var i = 0
        while (i < BASS_BIN_COUNT) {
            val realIdx = i * 2 + 2
            val imagIdx = realIdx + 1
            if (imagIdx >= fft.size) break
            val re = fft[realIdx].toInt()
            val im = fft[imagIdx].toInt()
            sum += sqrt((re * re + im * im).toFloat())
            i++
        }
        val mean = sum / BASS_BIN_COUNT.toFloat()

        if (!primed) {
            smoothedBass = mean
            primed = true
            return
        }

        val now = SystemClock.uptimeMillis()
        val sinceLast = now - lastVibrateUptimeMs

        val prev = smoothedBass
        smoothedBass = prev * SMOOTH_ALPHA + mean * SMOOTH_BETA

        if (sinceLast < COOLDOWN_MS) return

        if (mean > prev * SPIKE_RATIO && mean > ABSOLUTE_FLOOR) {
            lastVibrateUptimeMs = now
            try {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } catch (_: Exception) {
                // No-op
            }
        }
    }

    fun reset() {
        smoothedBass = 0f
        primed = false
        lastVibrateUptimeMs = 0L
    }

    companion object {
        private const val BASS_BIN_COUNT = 8
        private const val MIN_FFT_BYTES = 18
        private const val COOLDOWN_MS = 220L
        private const val SPIKE_RATIO = 1.42f
        private const val ABSOLUTE_FLOOR = 18.0f
        private const val SMOOTH_ALPHA = 0.88f
        private const val SMOOTH_BETA = 0.12f
    }
}
