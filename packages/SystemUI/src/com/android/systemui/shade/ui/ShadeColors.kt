/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui

import android.content.Context
import android.content.res.Configuration
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.cam.Cam
import com.android.systemui.res.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ShadeColors {

    enum class Style { AOSP, GLASS, TINTED }

    /**
     * All numbers are in CAM16/HCT space (tone = L*, 0..100), so the visual weight of each
     * style is the same for every wallpaper. sRGB blending (blendARGB) can't guarantee that.
     */
    private class Spec(
        // Blur path: translucent panel laid over blurred content
        val panelTone: Float,
        val panelChroma: Float,
        val panelAlpha: Float,
        val maxAlpha: Float,
        // No-blur path: opaque panel
        val fallbackTone: Float,
        val fallbackChroma: Float,
        // Notification card/scrim tone offset from the panel, keeps the two layers apart
        val cardToneDelta: Float,
        val cardAlpha: Float,
        val accentHue: Boolean,
        val compositeScrim: Boolean,
    )

    private fun spec(style: Style, night: Boolean): Spec? = when (style) {
        Style.AOSP -> null
        // Glass: near-neutral, low alpha, so the blurred wallpaper carries the color.
        Style.GLASS ->
            if (night) Spec(8f, 4f, 0.40f, 0.72f, 10f, 4f, 8f, 0.30f, false, false)
            else Spec(98f, 2f, 0.55f, 0.80f, 95f, 3f, -5f, 0.45f, false, false)
        // Tinted: deliberately chromatic accent surface with a stronger body.
        Style.TINTED ->
            if (night) Spec(16f, 26f, 0.58f, 0.85f, 13f, 16f, 7f, 0.40f, true, true)
            else Spec(93f, 22f, 0.62f, 0.88f, 92f, 14f, -6f, 0.50f, true, true)
    }

    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        val s = spec(style(context), isNight(context))
            ?: return aospShadePanel(context, blurSupported, withScrim)

        if (!blurSupported) return fallbackPanel(context, s)

        val panel = legible(context, hct(context, s, s.panelChroma, s.panelTone, s.panelAlpha), s)
        return if (withScrim && s.compositeScrim) {
            ColorUtils.compositeColors(panel, shadePanelScrimBehind(context))
        } else {
            panel
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        val s = spec(style(context), isNight(context))
            ?: return if (blurSupported) notificationScrimStandard(context)
            else notificationScrimFallback(context)

        val tone = (if (blurSupported) s.panelTone else s.fallbackTone) + s.cardToneDelta
        val chroma = if (blurSupported) s.panelChroma else s.fallbackChroma
        return if (blurSupported) {
            hct(context, s, chroma, tone, s.cardAlpha)
        } else {
            // Opaque, and always a visible step away from the panel
            hct(context, s, chroma, tone, 1f)
        }
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int =
        context.resources.getColor(com.android.internal.R.color.shade_panel_scrim, context.theme)

    @JvmStatic
    fun classicShadeNotificationScrimBg(
        context: Context,
        blurSupported: Boolean,
        composited: Boolean,
    ): Int {
        val s = spec(style(context), isNight(context))
        val card = if (s == null) {
            context.resources.getColor(
                com.android.internal.R.color.customColorSurfaceEffect0, context.theme)
        } else {
            notificationScrim(context, blurSupported)
        }
        if (!composited) return card
        return ColorUtils.compositeColors(card, shadePanel(context, blurSupported, true))
    }

    @JvmStatic
    fun glassEdge(context: Context): Int {
        val night = isNight(context)
        return when (style(context)) {
            Style.AOSP -> 0
            Style.GLASS -> ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), if (night) 38 else 140)
            Style.TINTED -> hct(context, spec(Style.TINTED, night)!!, 32f,
                if (night) 60f else 99f, if (night) 0.22f else 0.6f)
        }
    }

    private fun fallbackPanel(context: Context, s: Spec): Int =
        hct(context, s, s.fallbackChroma, s.fallbackTone, 1f)

    private fun legible(context: Context, panel: Int, s: Spec): Int {
        val night = isNight(context)
        val text = context.getColor(
            if (night) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900)
        val backdrop = Cam.getInt(neutralHue(context), 6f, if (night) 82f else 22f)
        var alpha = (panel ushr 24) / 255f
        val opaque = ColorUtils.setAlphaComponent(panel, 255)
        while (alpha < s.maxAlpha) {
            val c = ColorUtils.setAlphaComponent(opaque, (alpha * 255).roundToInt())
            if (ColorUtils.calculateContrast(text, ColorUtils.compositeColors(c, backdrop)) >= 4.5) {
                break
            }
            alpha += 0.03f
        }
        return ColorUtils.setAlphaComponent(opaque, (min(alpha, s.maxAlpha) * 255).roundToInt())
    }

    private fun hct(context: Context, s: Spec, chroma: Float, tone: Float, alpha: Float): Int {
        val hue = if (s.accentHue) accentHue(context) else neutralHue(context)
        val argb = Cam.getInt(hue, chroma, tone.coerceIn(0f, 100f))
        return ColorUtils.setAlphaComponent(argb, (max(0f, min(1f, alpha)) * 255).roundToInt())
    }

    private fun accentHue(context: Context): Float =
        Cam.fromInt(context.getColor(android.R.color.system_accent1_500)).hue

    private fun neutralHue(context: Context): Float =
        Cam.fromInt(context.getColor(android.R.color.system_neutral1_500)).hue

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun style(context: Context): Style {
        val v = Settings.Secure.getIntForUser(
            context.contentResolver, Settings.Secure.SHADE_COLOR_STYLE, 0, UserHandle.USER_CURRENT)
        return Style.entries.getOrElse(v) { Style.AOSP }
    }

    private fun aospShadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int =
        if (blurSupported) {
            if (withScrim) ColorUtils.compositeColors(
                shadePanelStandard(context), shadePanelScrimBehind(context))
            else shadePanelStandard(context)
        } else {
            context.getColor(R.color.shade_panel_fallback)
        }

    private fun shadePanelStandard(context: Context): Int {
        val above = context.resources.getColor(
            com.android.internal.R.color.shade_panel_fg, context.theme)
        val below = context.resources.getColor(
            com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(above, below)
    }

    private fun notificationScrimStandard(context: Context): Int =
        ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base), (0.5f * 255).toInt())

    private fun notificationScrimFallback(context: Context): Int =
        context.getColor(R.color.notification_scrim_fallback)
}
