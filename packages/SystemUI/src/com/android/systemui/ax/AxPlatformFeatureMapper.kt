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

package com.android.systemui.ax

import android.content.Context
import android.os.Bundle
import com.android.axion.platform.AxPlatformClient
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

@SysUISingleton
class AxPlatformFeatureMapper @Inject constructor(
    private val context: Context,
    private val batteryController: BatteryController,
    configurationController: ConfigurationController
) : AxPlatformStateManager.LabelProvider {

    private val labelCache = mutableMapOf<String, String>()

    init {
        configurationController.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onLocaleListChanged() {
                labelCache.clear()
            }
        })
    }

    override fun getLabel(feature: String): String? {
        labelCache[feature]?.let { return it }
        val label = when {
            FEATURE_LABEL_RES.containsKey(feature) -> context.getString(FEATURE_LABEL_RES[feature]!!)
            feature == AxPlatformClient.FEATURE_SMART_PIXELS -> LABEL_SMART_PIXELS
            else -> return null
        }
        labelCache[feature] = label
        return label
    }

    override fun getSecondaryLabel(feature: String, state: Bundle): String? = when (feature) {
        AxPlatformClient.FEATURE_WIFI -> {
            val ssid = state.getString("ssid")
            when {
                state.getBoolean("connected") && !ssid.isNullOrEmpty() -> ssid
                else -> null
            }
        }
        AxPlatformClient.FEATURE_BLUETOOTH -> {
            @Suppress("DEPRECATION")
            val devices = state.getParcelableArrayList<Bundle>("devices")
            devices?.firstOrNull { it.getBoolean("isConnected") }?.getString("name")
        }
        AxPlatformClient.FEATURE_HOTSPOT -> {
            val num = state.getInt("numDevices", 0)
            if (num > 0) "$num ${if (num == 1) "device" else "devices"}" else null
        }
        AxPlatformClient.FEATURE_MOBILE_DATA -> {
            state.getString("description")?.takeIf { it.isNotEmpty() }
        }
        AxPlatformClient.FEATURE_ZEN -> {
            val mode = state.getInt("mode", 0)
            if (mode != 0) context.getString(R.string.zen_mode_on) else null
        }
        AxPlatformClient.FEATURE_POWER_SHARE -> {
            if (batteryController.isAodPowerSave)
                context.getString(R.string.quick_settings_powershare_off_powersave_label)
            else null
        }
        AxPlatformClient.FEATURE_VPN -> {
            state.getString("name")?.takeIf { it.isNotEmpty() }
        }
        AxPlatformClient.FEATURE_CAST -> {
            state.getString("deviceName")?.takeIf { it.isNotEmpty() }
        }
        AxPlatformClient.FEATURE_PROFILES -> {
            state.getString("profileName")?.takeIf { it.isNotEmpty() }
        }
        AxPlatformClient.FEATURE_SCREEN_RECORD -> {
            when {
                state.getBoolean("active") -> context.getString(R.string.quick_settings_screen_record_stop)
                state.getBoolean("starting") -> context.getString(R.string.quick_settings_screen_record_start)
                else -> null
            }
        }
        else -> null
    }

    companion object {
        private const val LABEL_SMART_PIXELS = "Smart Pixels"

        private val FEATURE_LABEL_RES = mapOf(
            AxPlatformClient.FEATURE_WIFI to R.string.quick_settings_wifi_label,
            AxPlatformClient.FEATURE_MOBILE_DATA to R.string.quick_settings_internet_label,
            AxPlatformClient.FEATURE_BLUETOOTH to R.string.quick_settings_bluetooth_label,
            AxPlatformClient.FEATURE_HOTSPOT to R.string.quick_settings_hotspot_label,
            AxPlatformClient.FEATURE_FLASHLIGHT to R.string.quick_settings_flashlight_label,
            AxPlatformClient.FEATURE_LOCATION to R.string.quick_settings_location_label,
            AxPlatformClient.FEATURE_ROTATION to R.string.quick_settings_rotation_unlocked_label,
            AxPlatformClient.FEATURE_BATTERY_SAVER to R.string.battery_detail_switch_title,
            AxPlatformClient.FEATURE_ZEN to R.string.quick_settings_dnd_label,
            AxPlatformClient.FEATURE_AOD to R.string.quick_settings_aod_label,
            AxPlatformClient.FEATURE_DATA_SAVER to R.string.data_saver,
            AxPlatformClient.FEATURE_AIRPLANE_MODE to R.string.airplane_mode,
            AxPlatformClient.FEATURE_NFC to R.string.quick_settings_nfc_label,
            AxPlatformClient.FEATURE_DARK_MODE to R.string.quick_settings_ui_mode_night_label,
            AxPlatformClient.FEATURE_NIGHT_LIGHT to R.string.quick_settings_night_display_label,
            AxPlatformClient.FEATURE_COLOR_INVERSION to R.string.quick_settings_inversion_label,
            AxPlatformClient.FEATURE_COLOR_CORRECTION to R.string.quick_settings_color_correction_label,
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS to com.android.internal.R.string.reduce_bright_colors_feature_name,
            AxPlatformClient.FEATURE_ONE_HANDED_MODE to R.string.quick_settings_onehanded_label,
            AxPlatformClient.FEATURE_HEADS_UP to R.string.quick_settings_heads_up_label,
            AxPlatformClient.FEATURE_AUTO_SYNC to R.string.quick_settings_sync_label,
            AxPlatformClient.FEATURE_CAMERA_PRIVACY to R.string.quick_settings_camera_label,
            AxPlatformClient.FEATURE_MIC_PRIVACY to R.string.quick_settings_mic_label,
            AxPlatformClient.FEATURE_WORK_PROFILE to R.string.quick_settings_work_mode_label,
            AxPlatformClient.FEATURE_USB_TETHER to R.string.quick_settings_usb_tether_label,
            AxPlatformClient.FEATURE_DREAM to R.string.quick_settings_screensaver_label,
            AxPlatformClient.FEATURE_READING_MODE to R.string.quick_settings_reading_mode,
            AxPlatformClient.FEATURE_POWER_SHARE to R.string.quick_settings_powershare_label,
            AxPlatformClient.FEATURE_CAFFEINE to R.string.quick_settings_caffeine_label,
            AxPlatformClient.FEATURE_VPN to R.string.quick_settings_vpn_label,
            AxPlatformClient.FEATURE_CAST to R.string.quick_settings_cast_title,
            AxPlatformClient.FEATURE_PROFILES to R.string.quick_settings_profiles_label,
            AxPlatformClient.FEATURE_SCREEN_RECORD to R.string.quick_settings_screen_record_label,
            AxPlatformClient.FEATURE_SCREENSHOT to R.string.quick_settings_screenshot_label
        )
    }
}
