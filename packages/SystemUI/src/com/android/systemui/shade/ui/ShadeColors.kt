/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    @JvmStatic
    fun Resources.shadePanel(blurSupported: Boolean): Int {
        return if (blurSupported) {
            shadePanelStandard()
        } else {
            shadePanelFallback()
        }
    }

    @JvmStatic
    fun Resources.notificationScrim(blurSupported: Boolean): Int {
        return if (blurSupported) {
            notificationScrimStandard()
        } else {
            notificationScrimFallback()
        }
    }

    private fun Resources.isNightModeActive(): Boolean {
        return (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    @JvmStatic
    private fun Resources.shadePanelStandard(): Int {
        return if (isNightModeActive()) {
            shadePanelStandardDark()
        } else {
            shadePanelStandardLight()
        }
    }

    private fun Resources.shadePanelStandardLight(): Int {
        val layerAbove = ColorUtils.setAlphaComponent(
            getColor(R.color.shade_panel_base, null),
            (0.65f * 255).toInt()
        )
        val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.15f * 255).toInt())
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    private fun Resources.shadePanelStandardDark(): Int {
        val layerAbove = ColorUtils.setAlphaComponent(
            getColor(R.color.shade_panel_base, null),
            (0.7f * 255).toInt()
        )
        val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.13f * 255).toInt())
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun Resources.shadePanelFallback(): Int {
        return ColorUtils.blendARGB(getColor(R.color.nt_scrim_behind_1), getColor(R.color.nt_scrim_behind_2), 0.3f)
    }

    @JvmStatic
    private fun Resources.notificationScrimStandard(): Int {
        return if (isNightModeActive()) {
            notificationScrimStandardDark()
        } else {
            notificationScrimStandardLight()
        }
    }

    private fun Resources.notificationScrimStandardLight(): Int {
        val layerAbove = ColorUtils.setAlphaComponent(
            getColor(R.color.notification_scrim_base, null),
            (0.54f * 255).toInt()
        )
        val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.2f * 255).toInt())
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    private fun Resources.notificationScrimStandardDark(): Int {
        val layerAbove = ColorUtils.setAlphaComponent(
            getColor(R.color.notification_scrim_base, null),
            (0.58f * 255).toInt()
        )
        val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.21f * 255).toInt())
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun Resources.notificationScrimFallback(): Int {
        return getColor(R.color.notification_scrim_fallback, null)
    }
}
