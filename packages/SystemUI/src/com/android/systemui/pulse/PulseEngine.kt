/*
 * Copyright (C) 2025 The AxionAOSP Project
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
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.roundToInt

class PulseEngine(
    private val context: Context,
    private val settingsRepo: PulseSettingsRepository,
    private val onDataProcessed: (FloatArray) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var fftAverage: Array<FFTAverage>? = null
    private val fudgeFactor = 20

    fun processFFT(data: ByteArray) {
        scope.launch {
            val barCount = settingsRepo.getBarCount()
            if (fftAverage == null || fftAverage!!.size != barCount) {
                fftAverage = Array(barCount) { FFTAverage() }
            }
            val output = FloatArray(barCount)
            for (i in 0 until barCount) {
                val realIndex = i * 2 + 2
                val imagIndex = i * 2 + 3
                if (realIndex >= data.size || imagIndex >= data.size) continue
                val rfk = data[realIndex].toInt()
                val ifk = data[imagIndex].toInt()
                val magnitude = (rfk * rfk + ifk * ifk).toFloat()
                var dbValue = if (magnitude > 0) (10 * log10(magnitude.toDouble())).toInt() else 0
                dbValue = fftAverage!![i].average(dbValue)
                output[i] = dbValue * fudgeFactor.toFloat()
            }
            withContext(Dispatchers.Main) {
                onDataProcessed(output)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private class FFTAverage {
        companion object {
            private const val WINDOW_LENGTH = 2
        }

        private val window = ArrayDeque<Float>(WINDOW_LENGTH)
        private var average = 0f

        fun average(db: Int): Int {
            if (window.size >= WINDOW_LENGTH) {
                val removed = window.removeFirst()
                average -= removed
            }

            val newVal = db / WINDOW_LENGTH.toFloat()
            average += newVal
            window.addLast(newVal)

            return average.roundToInt()
        }
    }
}
