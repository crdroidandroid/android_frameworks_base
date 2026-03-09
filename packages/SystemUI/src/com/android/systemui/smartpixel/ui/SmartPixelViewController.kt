/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.smartpixel.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.android.systemui.smartpixel.domain.SmartPixelSettings.Companion.MAX_PERCENT
import com.android.systemui.smartpixel.domain.SmartPixelSettings.Companion.MIN_PERCENT
import com.android.systemui.util.ViewController

class SmartPixelViewController(
    view: SmartPixelView,
) : ViewController<SmartPixelView>(view) {

    companion object {
        private const val TILE_SIZE = 8
        private const val TOTAL_PIXELS = TILE_SIZE * TILE_SIZE

        private val BAYER_MATRIX = intArrayOf(
             0, 32,  8, 40,  2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44,  4, 36, 14, 46,  6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
             3, 35, 11, 43,  1, 33,  9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47,  7, 39, 13, 45,  5, 37,
            63, 31, 55, 23, 61, 29, 53, 21,
        )
    }

    fun updateConfig(percent: Int) {
        rebuildPattern(percent.coerceIn(MIN_PERCENT, MAX_PERCENT))
    }

    private fun rebuildPattern(percent: Int) {
        val threshold = (TOTAL_PIXELS * percent / 100f).toInt().coerceIn(1, TOTAL_PIXELS - 1)
        val bmp = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)

        for (y in 0 until TILE_SIZE) {
            for (x in 0 until TILE_SIZE) {
                if (BAYER_MATRIX[y * TILE_SIZE + x] < threshold) {
                    bmp.setPixel(x, y, Color.BLACK)
                }
            }
        }

        mView.updatePattern(bmp)
    }

    override fun onViewAttached() {}

    override fun onViewDetached() {}
}
