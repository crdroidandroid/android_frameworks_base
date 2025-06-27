/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util

import android.content.Context
import android.content.res.Configuration
import android.os.SystemProperties
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.compose.theme.colorAttr

class CustomAndroidColorScheme(private val context: Context) {

    val shadeTileColor: Color
        get() {
            val blurEnabled = isBlurEnabled(context)
            val colorRes = if (blurEnabled) R.color.shade_tile_color else R.color.shade_tile_color_fallback
            val tileColor = context.resources.getColor(colorRes, context.theme)
            val alpha = if (blurEnabled) 0.5f else 1f
            val tileColorAlpha = ColorUtils.setAlphaComponent(tileColor, (alpha * 255).toInt())
            return Color(tileColorAlpha)
        }

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    companion object {
        val current: CustomAndroidColorScheme
            @Composable
            @ReadOnlyComposable
            get() = CustomAndroidColorScheme(LocalContext.current)

        fun isBlurEnabled(context: Context): Boolean {
            val blurEnabledByDefault = SystemProperties.getBoolean("ro.custom.blur.enable", false)
            return Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DISABLE_WINDOW_BLURS,
                if (blurEnabledByDefault) 0 else 1
            ) != 1
        }

        @Composable
        @ReadOnlyComposable
        fun isBlurEnabled(): Boolean {
            return isBlurEnabled(LocalContext.current)
        }
    }
}
