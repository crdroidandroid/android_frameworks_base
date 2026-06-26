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
package com.android.server.wm.sandbox;

import java.util.HashMap;
import java.util.Map;

public class SettingsSpoofController {

    private static final Map<String, String> SPOOFED_SETTINGS = new HashMap<>();

    static {
        SPOOFED_SETTINGS.put("adb_enabled", "0");
        SPOOFED_SETTINGS.put("development_settings_enabled", "0");
        SPOOFED_SETTINGS.put("adb_wifi_enabled", "0");
        SPOOFED_SETTINGS.put("package_verifier_user_consent", "0");
        SPOOFED_SETTINGS.put("verify_apps_over_usb", "0");
        SPOOFED_SETTINGS.put("accessibility_enabled", "0");
        SPOOFED_SETTINGS.put("enabled_accessibility_services", "");
        SPOOFED_SETTINGS.put("accessibility_display_inversion_enabled", "0");
    }

    public static String getSpoofedValue(String settingName) {
        if (settingName == null) return null;
        return SPOOFED_SETTINGS.get(settingName);
    }
}
