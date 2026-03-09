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

package com.android.systemui.smartpixel.domain

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class SmartPixelSettings @Inject constructor(
    private val secureSettings: SecureSettings,
    @Main private val mainExecutor: Executor,
) {
    companion object {
        const val KEY_ENABLED = Settings.Secure.SMART_PIXEL_FILTER_ENABLED
        const val KEY_PERCENT = Settings.Secure.SMART_PIXEL_FILTER_PERCENT

        const val DEFAULT_PERCENT = 25
        const val MIN_PERCENT = 10
        const val MAX_PERCENT = 75
    }

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _percent = MutableStateFlow(DEFAULT_PERCENT)
    val percent: StateFlow<Int> = _percent.asStateFlow()

    private val settingsObserver =
        object : ContentObserver(mainExecutor, 0) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        refresh()
        secureSettings.registerContentObserverForUserSync(
            KEY_ENABLED, false, settingsObserver, UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_PERCENT, false, settingsObserver, UserHandle.USER_ALL,
        )
    }

    fun setEnabled(enabled: Boolean) {
        secureSettings.putIntForUser(KEY_ENABLED, if (enabled) 1 else 0, UserHandle.USER_CURRENT)
    }

    fun setPercent(value: Int) {
        secureSettings.putIntForUser(
            KEY_PERCENT, value.coerceIn(MIN_PERCENT, MAX_PERCENT), UserHandle.USER_CURRENT,
        )
    }

    private fun refresh() {
        _isEnabled.value =
            secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _percent.value =
            secureSettings.getIntForUser(KEY_PERCENT, DEFAULT_PERCENT, UserHandle.USER_CURRENT)
                .coerceIn(MIN_PERCENT, MAX_PERCENT)
    }
}
