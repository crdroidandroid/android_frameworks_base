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

package com.android.systemui.statusbar.connectivity

import android.content.Context
import android.content.res.ThemeEngine
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.android.systemui.qs.tileimpl.QSTileImpl
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeIconController {

    private const val NEW_SIGNAL_WIDTH_DP = 17f
    private const val NEW_SIGNAL_HEIGHT_DP = 12f
    private const val NEW_WIFI_WIDTH_DP = 17f
    private const val NEW_WIFI_HEIGHT_DP = 12.58f

    private val SIGNAL_4BAR_NAMES = arrayOf(
        "ic_signal_cellular_0_4_bar",
        "ic_signal_cellular_1_4_bar",
        "ic_signal_cellular_2_4_bar",
        "ic_signal_cellular_3_4_bar",
        "ic_signal_cellular_4_4_bar",
    )

    private val SIGNAL_5BAR_NAMES = arrayOf(
        "ic_signal_cellular_0_5_bar",
        "ic_signal_cellular_1_5_bar",
        "ic_signal_cellular_2_5_bar",
        "ic_signal_cellular_3_5_bar",
        "ic_signal_cellular_4_5_bar",
        "ic_signal_cellular_5_5_bar",
    )

    private val WIFI_ICON_NAMES = arrayOf(
        "ic_wifi_signal_0",
        "ic_wifi_signal_1",
        "ic_wifi_signal_2",
        "ic_wifi_signal_3",
        "ic_wifi_signal_4",
    )

    private val _themeVersion = MutableStateFlow(0L)
    val themeVersion: StateFlow<Long> = _themeVersion.asStateFlow()

    private val refreshCallbacks = CopyOnWriteArrayList<Runnable>()
    @Volatile private var wifiResIds: IntArray? = null

    @JvmStatic
    fun registerRefreshCallback(callback: Runnable) {
        refreshCallbacks.add(callback)
    }

    @JvmStatic
    fun unregisterRefreshCallback(callback: Runnable) {
        refreshCallbacks.remove(callback)
    }

    @JvmStatic
    fun onThemeChanged(tiles: Collection<com.android.systemui.plugins.qs.QSTile>) {
        QSTileImpl.ResourceIcon.clearCache()
        for (tile in tiles) {
            tile.refreshState()
        }

        _themeVersion.value++

        for (cb in refreshCallbacks) {
            cb.run()
        }
    }

    @JvmStatic
    fun getThemedSignalIcon(context: Context, level: Int, numLevels: Int): Drawable? {
        val engine = ThemeEngine.getInstance(context) ?: return null
        val names = if (numLevels > 5) SIGNAL_5BAR_NAMES else SIGNAL_4BAR_NAMES
        if (level < 0 || level >= names.size) return null
        val d = engine.getSystemThemeIconDrawable(names[level]) ?: return null
        return scaleDrawable(context, d, NEW_SIGNAL_WIDTH_DP, NEW_SIGNAL_HEIGHT_DP)
    }

    @JvmStatic
    fun hasThemedSignalIcons(context: Context): Boolean {
        val engine = ThemeEngine.getInstance(context) ?: return false
        return engine.isTargetedResource(SIGNAL_4BAR_NAMES[0])
    }

    @JvmStatic
    fun getThemedWifiIcon(context: Context, resId: Int): Drawable? {
        val level = mapWifiResIdToLevel(context, resId)
        if (level < 0) return null
        val engine = ThemeEngine.getInstance(context) ?: return null
        val d = engine.getSystemThemeIconDrawable(WIFI_ICON_NAMES[level]) ?: return null
        return scaleDrawable(context, d, NEW_WIFI_WIDTH_DP, NEW_WIFI_HEIGHT_DP)
    }

    @JvmStatic
    fun applyThemedSignalIcon(
        context: Context, iconView: ImageView, parentGroup: android.view.ViewGroup,
        level: Int, numLevels: Int, fallback: Runnable
    ) {
        val themed = getThemedSignalIcon(context, level, numLevels)
        if (themed != null) {
            iconView.setImageDrawable(themed)
        } else {
            fallback.run()
        }
        parentGroup.invalidate()
    }

    private fun mapWifiResIdToLevel(context: Context, resId: Int): Int {
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_0) return 0
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_1) return 1
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_2) return 2
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_3) return 3
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_4) return 4

        if (wifiResIds == null) {
            val res = context.resources
            val pkg = context.packageName
            wifiResIds = intArrayOf(
                res.getIdentifier("ic_wifi_0", "drawable", pkg),
                res.getIdentifier("ic_wifi_1", "drawable", pkg),
                res.getIdentifier("ic_wifi_2", "drawable", pkg),
                res.getIdentifier("ic_wifi_3", "drawable", pkg),
            )
        }
        val ids = wifiResIds ?: return -1
        for (i in ids.indices) {
            if (ids[i] == resId) return i
        }
        return -1
    }

    private fun scaleDrawable(
        context: Context, d: Drawable, widthDp: Float, heightDp: Float
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }
}
