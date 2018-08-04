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

package com.android.systemui.screenrecord.data.repository

import android.annotation.SuppressLint
import android.app.Service.MODE_PRIVATE
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit

// This repository might be used in a separate process where we don't have access to the System UI
// dagger graph.
@SuppressLint("StaticSettingsProvider")
class ScreenRecordingPreferenceRepository(
    private val getSharedPreferences: () -> SharedPreferences,
    private val secureSettingsPutInt: (String, Int) -> Unit,
    private val secureSettingsGetInt: (String) -> Int,
    private val systemSettingsPutInt: (String, Int) -> Unit,
    private val systemSettingsGetInt: (String) -> Int,
) {

    constructor(
        context: Context
    ) : this(
        getSharedPreferences = {
            context.getSharedPreferences(SHARED_PREFERENCES_NAME, MODE_PRIVATE)
        },
        secureSettingsPutInt = { name, value ->
            Settings.Secure.putInt(context.contentResolver, name, value)
        },
        secureSettingsGetInt = { name -> Settings.Secure.getInt(context.contentResolver, name, 0) },
        systemSettingsPutInt = { name, value ->
            Settings.System.putInt(context.contentResolver, name, value)
        },
        systemSettingsGetInt = { name -> Settings.System.getInt(context.contentResolver, name, 0) },
    )

    private val sharedPreferences by lazy { getSharedPreferences() }

    fun setShouldShowTaps(showTaps: Boolean) {
        val originalShowTapsSetting = getShowTaps()
        setShowTaps(showTaps)
        val hasTapsToRestore = sharedPreferences.getBoolean(UPDATE_SHOW_TAPS, false)
        if (!hasTapsToRestore && showTaps != originalShowTapsSetting) {
            sharedPreferences.edit {
                putBoolean(STORED_SHOW_TAPS_VALUE, originalShowTapsSetting)
                putBoolean(UPDATE_SHOW_TAPS, true)
            }
        }
    }

    fun setShouldShowSeconds(showSeconds: Boolean) {
        val hasSecondsToRestore = sharedPreferences.getBoolean(UPDATE_SHOW_SECONDS, false)
        val originalShowSecondsSetting = getShowSeconds()
        setShowSeconds(showSeconds)
        if (!hasSecondsToRestore && showSeconds != originalShowSecondsSetting) {
            sharedPreferences.edit {
                putBoolean(STORED_SHOW_SECONDS_VALUE, originalShowSecondsSetting)
                putBoolean(UPDATE_SHOW_SECONDS, true)
            }
        }
    }

    fun maybeRestoreSetting(): ScreenRecordingPreferenceRepository = apply {
        if (sharedPreferences.getBoolean(UPDATE_SHOW_TAPS, false)) {
            restoreShowTapsSetting()
        }
        if (sharedPreferences.getBoolean(UPDATE_SHOW_SECONDS, false)) {
            restoreShowSecondsSetting()
        }
    }

    private fun restoreShowTapsSetting() {
        setShowTaps(sharedPreferences.getBoolean(STORED_SHOW_TAPS_VALUE, false))
        sharedPreferences.edit { putBoolean(UPDATE_SHOW_TAPS, false) }
    }

    private fun setShowTaps(isOn: Boolean) {
        systemSettingsPutInt(Settings.System.SHOW_TOUCHES, if (isOn) 1 else 0)
    }

    private fun getShowTaps(): Boolean {
        return systemSettingsGetInt(Settings.System.SHOW_TOUCHES) != 0
    }

    private fun restoreShowSecondsSetting() {
        setShowSeconds(sharedPreferences.getBoolean(STORED_SHOW_SECONDS_VALUE, false))
        sharedPreferences.edit { putBoolean(UPDATE_SHOW_SECONDS, false) }
    }

    private fun setShowSeconds(isOn: Boolean) {
        systemSettingsPutInt(CLOCK_SECONDS, if (isOn) 1 else 0)
    }

    private fun getShowSeconds(): Boolean {
        return systemSettingsGetInt(CLOCK_SECONDS) != 0
    }

    private companion object {
        const val SHARED_PREFERENCES_NAME = "com.android.systemui.screenrecord"
        const val STORED_SHOW_TAPS_VALUE = "stored_show_taps_value"
        const val UPDATE_SHOW_TAPS = "update_show_taps"
        const val STORED_SHOW_SECONDS_VALUE = "stored_show_seconds_value"
        const val UPDATE_SHOW_SECONDS = "update_show_seconds"
        const val CLOCK_SECONDS = "status_bar_clock_seconds"
    }
}
