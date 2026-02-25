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
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import com.android.internal.R as internalR

/** Resolved gradient endpoints. */
data class GradientColors(val startColor: Color, val endColor: Color)

object GradientSettings {

    /** Follow the Material accent palette (primary -> secondary). */
    const val MODE_ACCENT = 0
    /** Use the user picked colors. */
    const val MODE_CUSTOM = 1

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

    /**
     * Resolved gradient endpoints as ARGB ints, for the View based (non Compose) surfaces. Falls
     * back to the Material accent colors when the user hasn't picked custom ones.
     */
    @JvmStatic
    fun argbColors(context: Context): Pair<Int, Int> {
        val primary = context.getColor(internalR.color.materialColorPrimary)
        val secondary = context.getColor(internalR.color.materialColorSecondary)

        if (read(context, Settings.System.CUSTOM_GRADIENT_COLOR_MODE, MODE_ACCENT) != MODE_CUSTOM) {
            return primary to secondary
        }

        val start = read(context, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0)
        val end = read(context, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0)
        return (if (start != 0) start else primary) to (if (end != 0) end else secondary)
    }

    /** Every setting uri that can change the gradient of the surface owning [key]. */
    @JvmStatic
    fun urisFor(key: String): List<Uri> =
        listOf(
            Settings.System.getUriFor(key),
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_COLOR_MODE),
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_START_COLOR),
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_END_COLOR),
        )

    /**
     * Observes every uri returned by [urisFor]. [onChanged] is always dispatched on the main
     * thread. The caller owns the returned observer and must pass it to [unregisterObserver].
     */
    fun registerObserver(
        context: Context,
        key: String,
        onChanged: () -> Unit,
    ): ContentObserver {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute(onChanged)
                }
            }
        urisFor(key).forEach { uri ->
            resolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL)
        }
        return observer
    }

    fun unregisterObserver(context: Context, observer: ContentObserver) {
        context.contentResolver.unregisterContentObserver(observer)
    }
}

/** Live value of the gradient switch owning [settingKey]. */
@Composable
fun rememberGradientEnabled(settingKey: String): Boolean {
    val context = LocalContext.current
    var enabled by
        remember(context, settingKey) {
            mutableIntStateOf(GradientSettings.read(context, settingKey, 0))
        }

    DisposableEffect(context, settingKey) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute {
                        enabled = GradientSettings.read(context, settingKey, 0)
                    }
                }
            }
        resolver.registerContentObserver(
            Settings.System.getUriFor(settingKey),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return enabled != 0
}

/** Live gradient endpoints, already resolved against the current Material theme. */
@Composable
fun rememberGradientColors(): GradientColors {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    var mode by
        remember(context) {
            mutableIntStateOf(
                GradientSettings.read(
                    context,
                    Settings.System.CUSTOM_GRADIENT_COLOR_MODE,
                    GradientSettings.MODE_ACCENT,
                )
            )
        }
    var startArgb by
        remember(context) {
            mutableIntStateOf(
                GradientSettings.read(context, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0)
            )
        }
    var endArgb by
        remember(context) {
            mutableIntStateOf(
                GradientSettings.read(context, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0)
            )
        }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute {
                        mode =
                            GradientSettings.read(
                                context,
                                Settings.System.CUSTOM_GRADIENT_COLOR_MODE,
                                GradientSettings.MODE_ACCENT,
                            )
                        startArgb =
                            GradientSettings.read(
                                context,
                                Settings.System.CUSTOM_GRADIENT_START_COLOR,
                                0,
                            )
                        endArgb =
                            GradientSettings.read(
                                context,
                                Settings.System.CUSTOM_GRADIENT_END_COLOR,
                                0,
                            )
                    }
                }
            }
        listOf(
                Settings.System.CUSTOM_GRADIENT_COLOR_MODE,
                Settings.System.CUSTOM_GRADIENT_START_COLOR,
                Settings.System.CUSTOM_GRADIENT_END_COLOR,
            )
            .forEach { key ->
                resolver.registerContentObserver(
                    Settings.System.getUriFor(key),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
            }
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return if (mode != GradientSettings.MODE_CUSTOM) {
        GradientColors(primary, secondary)
    } else {
        GradientColors(
            startColor = if (startArgb != 0) Color(startArgb) else primary,
            endColor = if (endArgb != 0) Color(endArgb) else secondary,
        )
    }
}

/** Left to right gradient. Pass [reversed] for RTL layouts. */
fun GradientColors.horizontal(reversed: Boolean = false): Brush =
    Brush.horizontalGradient(
        if (reversed) listOf(endColor, startColor) else listOf(startColor, endColor)
    )

/** Top to bottom gradient, or bottom to top when [bottomUp] is set (vertical sliders). */
fun GradientColors.vertical(bottomUp: Boolean = false): Brush =
    Brush.verticalGradient(
        if (bottomUp) listOf(endColor, startColor) else listOf(startColor, endColor)
    )

/** Top-start to bottom-end gradient, used for square-ish surfaces such as QS tiles. */
fun GradientColors.diagonal(): Brush =
    Brush.linearGradient(
        colors = listOf(startColor, endColor),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

/** [Outline] as a clippable [Path]. */
fun Outline.toPath(): Path =
    when (this) {
        is Outline.Generic -> path
        is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(rect) }
    }
