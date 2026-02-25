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

package com.android.systemui.gradient

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import com.android.internal.R as internalR
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Resolved gradient for Compose surfaces.
 *
 * [angle] is in degrees, counter-clockwise, 0 = start -> end along the X axis, 90 = bottom -> top
 * (same convention as the status bar clock chip gradient).
 */
@Immutable
data class GradientColors(
    val startColor: Color,
    val endColor: Color,
    val angle: Float = GradientSettings.DEFAULT_ANGLE.toFloat(),
)

/** Resolved gradient for View based (non Compose) surfaces. Colors are ARGB ints. */
data class ArgbGradient(val startColor: Int, val endColor: Int, val angle: Float) {

    /**
     * Paints [shape] with this gradient. [GradientDrawable] only supports 45 degree steps, so the
     * angle is snapped to the nearest one.
     */
    fun applyTo(shape: GradientDrawable, isRtl: Boolean = false) {
        shape.orientation = GradientSettings.orientationFor(angle, isRtl)
        shape.colors = intArrayOf(startColor, endColor)
    }
}

object GradientSettings {

    /** Follow the Material accent palette (primary -> secondary). */
    const val MODE_ACCENT = 0
    /** Use the user picked colors. */
    const val MODE_CUSTOM = 1

    /** Default gradient angle, start -> end along the X axis. */
    const val DEFAULT_ANGLE = 0

    /** Settings shared by every gradient surface. */
    private val SHARED_KEYS =
        listOf(
            Settings.System.CUSTOM_GRADIENT_COLOR_MODE,
            Settings.System.CUSTOM_GRADIENT_START_COLOR,
            Settings.System.CUSTOM_GRADIENT_END_COLOR,
            Settings.System.CUSTOM_GRADIENT_ANGLE,
        )

    // Counter-clockwise, in 45 degree steps starting at 0 (left -> right).
    private val ORIENTATIONS =
        arrayOf(
            GradientDrawable.Orientation.LEFT_RIGHT,
            GradientDrawable.Orientation.BL_TR,
            GradientDrawable.Orientation.BOTTOM_TOP,
            GradientDrawable.Orientation.BR_TL,
            GradientDrawable.Orientation.RIGHT_LEFT,
            GradientDrawable.Orientation.TR_BL,
            GradientDrawable.Orientation.TOP_BOTTOM,
            GradientDrawable.Orientation.TL_BR,
        )

    /** Reads an int setting for the current user, never throwing. */
    @JvmStatic
    @JvmOverloads
    fun read(context: Context, key: String, defaultValue: Int = 0): Int =
        read(context.contentResolver, key, defaultValue)

    @JvmStatic
    @JvmOverloads
    fun read(resolver: ContentResolver, key: String, defaultValue: Int = 0): Int =
        try {
            Settings.System.getIntForUser(resolver, key, defaultValue, UserHandle.USER_CURRENT)
        } catch (_: Throwable) {
            defaultValue
        }

    /** Whether the gradient is enabled for the surface owning [key]. */
    @JvmStatic
    fun isEnabled(context: Context, key: String): Boolean = read(context, key, 0) != 0

    /** User picked angle, normalized to [0, 360). */
    @JvmStatic
    fun readAngle(resolver: ContentResolver): Float =
        normalizeAngle(
            read(resolver, Settings.System.CUSTOM_GRADIENT_ANGLE, DEFAULT_ANGLE).toFloat()
        )

    @JvmStatic fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

    /**
     * Final angle of a surface: the user angle rotated by [baseAngle] (the direction the surface
     * "flows", e.g. 90 for a bottom-up vertical slider) and mirrored horizontally for RTL.
     */
    @JvmStatic
    fun resolveAngle(angle: Float, baseAngle: Float = 0f, mirrored: Boolean = false): Float {
        val rotated = angle + baseAngle
        return normalizeAngle(if (mirrored) 180f - rotated else rotated)
    }

    /** Closest [GradientDrawable.Orientation] to [angle]. */
    @JvmStatic
    fun orientationFor(angle: Float, isRtl: Boolean = false): GradientDrawable.Orientation {
        val resolved = resolveAngle(angle, mirrored = isRtl)
        return ORIENTATIONS[(resolved / 45f).roundToInt() % ORIENTATIONS.size]
    }

    /** Custom colors when enabled and picked, the accent ones otherwise. */
    @JvmStatic
    fun <T> resolveColors(
        mode: Int,
        customStart: Int,
        customEnd: Int,
        accent: Pair<T, T>,
        convert: (Int) -> T,
    ): Pair<T, T> {
        if (mode != MODE_CUSTOM) return accent
        return (if (customStart != 0) convert(customStart) else accent.first) to
            (if (customEnd != 0) convert(customEnd) else accent.second)
    }

    /**
     * Resolved gradient for the View based surface owning [key], or null when it is disabled.
     * Falls back to the Material accent colors when the user hasn't picked custom ones.
     */
    @JvmStatic
    fun argbGradient(context: Context, key: String): ArgbGradient? {
        if (!isEnabled(context, key)) return null
        val resolver = context.contentResolver
        val (start, end) =
            resolveColors(
                mode = read(resolver, Settings.System.CUSTOM_GRADIENT_COLOR_MODE, MODE_ACCENT),
                customStart = read(resolver, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0),
                customEnd = read(resolver, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0),
                accent =
                    context.getColor(internalR.color.materialColorPrimary) to
                        context.getColor(internalR.color.materialColorSecondary),
                convert = { it },
            )
        return ArgbGradient(start, end, readAngle(resolver))
    }

    /** Every setting uri that can change the gradient of the surface owning [key]. */
    @JvmStatic
    fun urisFor(key: String): List<Uri> =
        (listOf(key) + SHARED_KEYS).map { Settings.System.getUriFor(it) }

    /**
     * Observes every uri returned by [urisFor]. [onChanged] is always dispatched on the main
     * thread. The caller owns the returned observer and must pass it to [unregisterObserver].
     */
    @JvmStatic
    fun registerObserver(context: Context, key: String, onChanged: () -> Unit): ContentObserver {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChanged()
            }
        urisFor(key).forEach { uri ->
            context.contentResolver.registerContentObserver(
                uri,
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
        return observer
    }

    @JvmStatic
    fun unregisterObserver(context: Context, observer: ContentObserver) {
        context.contentResolver.unregisterContentObserver(observer)
    }
}

/** Raw (theme independent) gradient settings of one surface. */
private data class GradientConfig(
    val enabled: Boolean,
    val mode: Int,
    val startArgb: Int,
    val endArgb: Int,
    val angle: Float,
) {
    companion object {
        fun read(resolver: ContentResolver, key: String) =
            GradientConfig(
                enabled = GradientSettings.read(resolver, key, 0) != 0,
                mode =
                    GradientSettings.read(
                        resolver,
                        Settings.System.CUSTOM_GRADIENT_COLOR_MODE,
                        GradientSettings.MODE_ACCENT,
                    ),
                startArgb =
                    GradientSettings.read(resolver, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0),
                endArgb =
                    GradientSettings.read(resolver, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0),
                angle = GradientSettings.readAngle(resolver),
            )
    }
}

/**
 * Live gradient of the surface owning [settingKey], already resolved against the current Material
 * theme, or null when the surface has the gradient disabled. A single observer covers the switch,
 * the color mode, both colors and the angle.
 */
@Composable
fun rememberGradient(settingKey: String): GradientColors? {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    var config by
        remember(context, settingKey) {
            mutableStateOf(GradientConfig.read(context.contentResolver, settingKey))
        }

    DisposableEffect(context, settingKey) {
        val observer =
            GradientSettings.registerObserver(context, settingKey) {
                config = GradientConfig.read(context.contentResolver, settingKey)
            }
        onDispose { GradientSettings.unregisterObserver(context, observer) }
    }

    val current = config
    return remember(current, primary, secondary) {
        if (current.enabled) {
            val (start, end) =
                GradientSettings.resolveColors(
                    mode = current.mode,
                    customStart = current.startArgb,
                    customEnd = current.endArgb,
                    accent = primary to secondary,
                    convert = { Color(it) },
                )
            GradientColors(start, end, current.angle)
        } else {
            null
        }
    }
}

/**
 * Brush painting this gradient at its angle over the whole drawn area. [baseAngle] rotates the
 * user angle (e.g. 90 for bottom-up vertical sliders, so 0 always follows the fill direction) and
 * [mirrored] flips it horizontally for RTL layouts.
 */
fun GradientColors.brush(baseAngle: Float = 0f, mirrored: Boolean = false): Brush =
    AngledGradientBrush(
        startColor,
        endColor,
        GradientSettings.resolveAngle(angle, baseAngle, mirrored),
    )

/**
 * Linear gradient whose axis goes through the center at [angleDeg] and is long enough for both
 * ends to reach the corners, so the full color range is always visible whatever the aspect ratio
 * (mirrors ClockChipGradientDrawable).
 */
@Immutable
private class AngledGradientBrush(
    private val startColor: Color,
    private val endColor: Color,
    private val angleDeg: Float,
) : ShaderBrush() {

    override fun createShader(size: Size): Shader {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = -sin(rad).toFloat()
        val half = max((abs(size.width * dx) + abs(size.height * dy)) / 2f, 0.5f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        return LinearGradientShader(
            from = Offset(cx - dx * half, cy - dy * half),
            to = Offset(cx + dx * half, cy + dy * half),
            colors = listOf(startColor, endColor),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AngledGradientBrush) return false
        return startColor == other.startColor &&
            endColor == other.endColor &&
            angleDeg == other.angleDeg
    }

    override fun hashCode(): Int {
        var result = startColor.hashCode()
        result = 31 * result + endColor.hashCode()
        result = 31 * result + angleDeg.hashCode()
        return result
    }

    override fun toString(): String =
        "AngledGradientBrush(startColor=$startColor, endColor=$endColor, angle=$angleDeg)"
}

/**
 * Paints [brush] over the active part of a slider track, clipped to [trackPath]. The brush spans
 * the whole track so the gradient is revealed while dragging instead of being squeezed into the
 * active part. Vertical tracks fill bottom -> top, horizontal ones start -> end.
 */
fun DrawScope.drawActiveTrackGradient(
    brush: Brush,
    trackPath: Path,
    fraction: Float,
    gapPx: Float,
    isVertical: Boolean = false,
    isRtl: Boolean = false,
) {
    val length = if (isVertical) size.height else size.width
    val active = (length * fraction - gapPx).coerceIn(0f, length)
    if (active <= 0f) return

    val topLeft =
        when {
            isVertical -> Offset(0f, size.height - active)
            isRtl -> Offset(size.width - active, 0f)
            else -> Offset.Zero
        }
    val activeSize = if (isVertical) Size(size.width, active) else Size(active, size.height)
    clipPath(trackPath) { drawRect(brush = brush, topLeft = topLeft, size = activeSize) }
}

/** [Outline] as a clippable [Path]. */
fun Outline.toPath(): Path =
    when (this) {
        is Outline.Generic -> path
        is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(rect) }
    }
