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
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.abs

class ClockChipGradientDrawable(
    startColor: Int = Color.TRANSPARENT,
    endColor: Int = Color.TRANSPARENT,
    angleDeg: Float = 0f,
    cornerRadiusPx: Float = 0f,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var _startColor: Int = startColor
    private var _endColor: Int = endColor

    private var _angleDeg: Float = angleDeg.normalise()
    private var _cornerRadius: Float = cornerRadiusPx

    fun updateColors(startColor: Int, endColor: Int) {
        _startColor = startColor
        _endColor = endColor
        rebuildShader()
        invalidateSelf()
    }

    fun updateAngle(angleDeg: Float) {
        _angleDeg = angleDeg.normalise()
        rebuildShader()
        invalidateSelf()
    }

    fun updateCornerRadius(px: Float) {
        _cornerRadius = px
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        rebuildShader()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        path.reset()
        path.addRoundRect(rect, _cornerRadius, _cornerRadius, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rebuildShader() {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val rad = _angleDeg * (PI / 180.0)

        val len = (abs(w * cos(rad)) + abs(h * sin(rad))).toFloat() / 2f

        val cx = w / 2f
        val cy = h / 2f
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()

        paint.shader = LinearGradient(
            cx - dx * len, cy - dy * len,
            cx + dx * len, cy + dy * len,
            _startColor, _endColor,
            Shader.TileMode.CLAMP
        )
    }

    private fun Float.normalise(): Float = ((this % 360f) + 360f) % 360f
}
