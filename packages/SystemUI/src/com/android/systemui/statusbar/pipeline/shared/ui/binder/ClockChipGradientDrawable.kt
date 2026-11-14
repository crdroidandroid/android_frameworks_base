/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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
package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ClockChipGradientDrawable(
    private val startColor: Int,
    private val endColor: Int,
    angleDeg: Float = 0f,
    private val cornerRadiusPx: Float = CORNER_RADIUS_PILL,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val angleDeg: Float = ((angleDeg % 360f) + 360f) % 360f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        rebuildShader()
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val radius =
            if (cornerRadiusPx < 0f) min(rect.width(), rect.height()) / 2f else cornerRadiusPx
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha == alpha) return
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rebuildShader() {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) {
            paint.shader = null
            return
        }

        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = -sin(rad).toFloat()

        val half = (abs(w * dx) + abs(h * dy)) / 2f

        val cx = rect.centerX()
        val cy = rect.centerY()

        paint.shader =
            LinearGradient(
                cx - dx * half,
                cy - dy * half,
                cx + dx * half,
                cy + dy * half,
                startColor,
                endColor,
                Shader.TileMode.CLAMP,
            )
    }

    companion object {
        const val CORNER_RADIUS_PILL = -1f
    }
}
