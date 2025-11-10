/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2025 crDroid Android Project
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
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.os.Looper
import android.provider.Settings

class PulseSettingsRepository(private val context: Context) {

    companion object {
        private const val PULSE_ENABLED = Settings.Secure.LOCKSCREEN_PULSE_ENABLED
        private const val PULSE_AMBIENT_ENABLED = Settings.Secure.AMBIENT_PULSE_ENABLED
        private const val PULSE_BAR_COUNT = Settings.Secure.PULSE_BAR_COUNT
        private const val PULSE_ROUNDED_BARS = Settings.Secure.PULSE_ROUNDED_BARS
        private const val PULSE_COLOR = Settings.Secure.PULSE_COLOR
        private const val PULSE_RENDERER = Settings.Secure.PULSE_RENDERER

        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_AMBIENT_ENABLED = true
        private const val DEFAULT_BAR_COUNT = 32
        private const val DEFAULT_ROUNDED_BARS = false
        private const val DEFAULT_COLOR = "lavalamp"
        private const val DEFAULT_RENDERER = "solid"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var settingsObserver: SettingsObserver? = null
    private var onSettingsChangedListener: (() -> Unit)? = null

    private var cachedEnabled: Boolean? = null
    private var cachedAmbientEnabled: Boolean? = null
    private var cachedBarCount: Int? = null
    private var cachedRoundedBars: Boolean? = null
    private var cachedColorMode: String? = null
    private var cachedRenderer: String? = null

    fun startObserving() {
        if (settingsObserver != null) return

        settingsObserver = SettingsObserver(handler) { invalidateCache() }

        listOf(
            Settings.Secure.getUriFor(PULSE_ENABLED),
            Settings.Secure.getUriFor(PULSE_AMBIENT_ENABLED),
            Settings.Secure.getUriFor(PULSE_BAR_COUNT),
            Settings.Secure.getUriFor(PULSE_ROUNDED_BARS),
            Settings.Secure.getUriFor(PULSE_COLOR),
            Settings.Secure.getUriFor(PULSE_RENDERER)
        ).forEach { uri ->
            context.contentResolver.registerContentObserver(uri, false, settingsObserver!!)
        }
    }

    fun stopObserving() {
        settingsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            settingsObserver = null
        }
    }

    fun setOnSettingsChangedListener(listener: () -> Unit) {
        onSettingsChangedListener = listener
    }

    fun isPulseEnabled(): Boolean {
        if (cachedEnabled == null) {
            cachedEnabled = getSecureSetting(PULSE_ENABLED, DEFAULT_ENABLED)
        }
        return cachedEnabled!!
    }

    fun isPulseAmbientEnabled(): Boolean {
        if (cachedAmbientEnabled == null) {
            cachedAmbientEnabled = getSecureSetting(PULSE_AMBIENT_ENABLED, DEFAULT_AMBIENT_ENABLED)
        }
        return cachedAmbientEnabled!!
    }

    fun getBarCount(): Int {
        if (cachedBarCount == null) {
            cachedBarCount = getSecureSetting(PULSE_BAR_COUNT, DEFAULT_BAR_COUNT).coerceIn(8, 64)
        }
        return cachedBarCount!!
    }

    fun isRoundedBarsEnabled(): Boolean {
        if (cachedRoundedBars == null) {
            cachedRoundedBars = getSecureSetting(PULSE_ROUNDED_BARS, DEFAULT_ROUNDED_BARS)
        }
        return cachedRoundedBars!!
    }

    fun getColorMode(): String {
        if (cachedColorMode == null) {
            cachedColorMode = getSecureStringSetting(PULSE_COLOR, DEFAULT_COLOR)
        }
        return cachedColorMode!!
    }

    fun getStyleMode(): String {
        // Valid values: "solid", "fading", "neon", "retro", "minimal", "sparkle"
        if (cachedRenderer == null) {
            val raw = getSecureStringSetting(PULSE_RENDERER, DEFAULT_RENDERER)
            cachedRenderer = when (raw) {
                "solid", "fading", "neon", "retro", "minimal", "sparkle" -> raw
                else -> DEFAULT_RENDERER
            }
        }
        return cachedRenderer!!
    }

    private fun invalidateCache() {
        cachedEnabled = null
        cachedAmbientEnabled = null
        cachedBarCount = null
        cachedRoundedBars = null
        cachedColorMode = null
        cachedRenderer = null
        onSettingsChangedListener?.invoke()
    }

    private fun getSecureSetting(key: String, defaultValue: Boolean): Boolean {
        return Settings.Secure.getIntForUser(context.contentResolver, key,
            if (defaultValue) 1 else 0, UserHandle.USER_CURRENT) == 1
    }

    private fun getSecureSetting(key: String, defaultValue: Int): Int {
        return Settings.Secure.getIntForUser(context.contentResolver, key,
            defaultValue, UserHandle.USER_CURRENT)
    }

    private fun getSecureStringSetting(key: String, defaultValue: String): String {
        return Settings.Secure.getStringForUser(context.contentResolver, key,
            UserHandle.USER_CURRENT) ?: defaultValue
    }

    private inner class SettingsObserver(
        handler: Handler,
        private val onChange: () -> Unit
    ) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            onChange()
        }
    }

    fun getBarGapPx(): Float = 2f * context.resources.displayMetrics.density
    fun getDivisions(): Int = 16
    fun getBlockStrokePx(): Float = 4f * context.resources.displayMetrics.density
    fun getFilledBlockSizePx(): Float = 4f * context.resources.displayMetrics.density
    fun getEmptyBlockSizePx(): Float = 1f * context.resources.displayMetrics.density
}
