/*
 * Copyright (C) 2017 The LineageOS Project
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
package com.android.systemui.tuner;

import android.content.Context;
import android.os.Bundle;
import android.telephony.TelephonyManager;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.systemui.res.R;

public class StatusBarTuner extends SettingsBasePreferenceFragment {

    private MetricsLogger mMetricsLogger;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.status_bar_prefs, rootKey);
        if (!isVoiceCapable(requireContext())) {
            removeMobilePreferences();
        }
    }

    public static boolean isVoiceCapable(Context context) {
        TelephonyManager telephony =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return telephony != null && telephony.isVoiceCapable();
    }

    private void removeMobilePreferences() {
        String[] mobileKeys = new String[] {
                "mobile",
                "system:data_disabled_icon",
                "call_strength",
                "roaming",
                "system:show_fourg_icon",
                "status_bar_show_hd_calling",
                "status_bar_show_vowifi"
        };

        PreferenceScreen screen = getPreferenceScreen();
        for (String key : mobileKeys) {
            Preference pref = findPreference(key);
            if (pref != null) {
                screen.removePreference(pref);
            }
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMetricsLogger = new MetricsLogger();
    }

    @Override
    public void onResume() {
        super.onResume();
        mMetricsLogger.visibility(MetricsEvent.TUNER, true);
    }

    @Override
    public void onPause() {
        super.onPause();
        mMetricsLogger.visibility(MetricsEvent.TUNER, false);
    }
}
