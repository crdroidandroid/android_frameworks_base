/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.combine

data class EdgeLightSettings(
    val isEnabled: Boolean,
    val colorMode: String,
    val customColor: Int
)

class EdgeLightSettingsRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    val settingsFlow: Flow<EdgeLightSettings> = combine(
        observeSettingInt(SETTING_ENABLED, 0),
        observeSettingString(SETTING_COLOR_MODE, "accent"),
        observeSettingInt(SETTING_CUSTOM_COLOR, -1)
    ) { enabled, mode, color ->
        EdgeLightSettings(enabled == 1, mode, color)
    }.distinctUntilChanged()

    fun currentSettings(): EdgeLightSettings = EdgeLightSettings(
        isEnabled = Settings.System.getIntForUser(resolver, SETTING_ENABLED, 0, UserHandle.USER_CURRENT) == 1,
        colorMode = Settings.System.getStringForUser(resolver, SETTING_COLOR_MODE, UserHandle.USER_CURRENT) ?: "accent",
        customColor = Settings.System.getIntForUser(resolver, SETTING_CUSTOM_COLOR, Color.WHITE, UserHandle.USER_CURRENT)
    )

    private fun observeSettingInt(key: String, default: Int): Flow<Int> = callbackFlow {
        val uri = Settings.System.getUriFor(key)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT))
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        trySend(Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT))
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun observeSettingString(key: String, default: String): Flow<String> = callbackFlow {
        val uri = Settings.System.getUriFor(key)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: default)
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        trySend(Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: default)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    companion object {
        private const val SETTING_ENABLED = Settings.System.EDGE_LIGHT_ENABLED
        private const val SETTING_COLOR_MODE = Settings.System.EDGE_LIGHT_COLOR_MODE
        private const val SETTING_CUSTOM_COLOR = Settings.System.EDGE_LIGHT_CUSTOM_COLOR
    }
}
