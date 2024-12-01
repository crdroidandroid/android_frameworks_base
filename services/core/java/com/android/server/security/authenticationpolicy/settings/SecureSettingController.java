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

package com.android.server.security.authenticationpolicy.settings;

import static android.provider.Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED_DEFAULT;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Map;

/** Settings controller for {@link Settings.Secure} settings. */
class SecureSettingController implements SettingController<Integer> {
    /**
     * Default values to use as original value if not found in secure settings at the time secure
     * lock device is enabled.
     */
    private final Map<String, Integer> mSecureSettingsDefaultValues;
    private final int mDoubleTapPowerButtonGestureEnabledDefault;
    private final int mLockScreenShowQrCodeScannerDefault;
    private final int mGlanceableHubEnabledDefault;

    @NonNull private final ContentResolver mContentResolver;


    SecureSettingController(@NonNull Context context) {
        mLockScreenShowQrCodeScannerDefault = context.getResources().getBoolean(
                android.R.bool.config_enableQrCodeScannerOnLockScreen) ? 1 : 0;
        mDoubleTapPowerButtonGestureEnabledDefault = context.getResources().getInteger(
                com.android.internal.R.integer.config_doubleTapPowerGestureMultiTargetDefaultAction
        );
        if (context.getResources().getBoolean(
                com.android.internal.R.bool.config_glanceableHubEnabledByDefault)) {
            mGlanceableHubEnabledDefault = 1;
        } else {
            mGlanceableHubEnabledDefault = 0;
        }

        mSecureSettingsDefaultValues = initializeDefaultValues();
        mContentResolver = context.getContentResolver();
    }

    @VisibleForTesting
    Map<String, Integer> initializeDefaultValues() {
        return Map.of(
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1,
                Settings.Secure.DOUBLE_TAP_POWER_BUTTON_GESTURE_ENABLED,
                mDoubleTapPowerButtonGestureEnabledDefault,
                Settings.Secure.CAMERA_GESTURE_DISABLED, 0,
                Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED, 0,
                Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED, CAMERA_LIFT_TRIGGER_ENABLED_DEFAULT,
                Settings.Secure.LOCKSCREEN_SMARTSPACE_ENABLED, 1,
                Settings.Secure.LOCKSCREEN_SHOW_CONTROLS, 0,
                Settings.Secure.LOCKSCREEN_SHOW_WALLET, 0,
                Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER,
                mLockScreenShowQrCodeScannerDefault,
                Settings.Secure.GLANCEABLE_HUB_ENABLED, mGlanceableHubEnabledDefault
        );
    }

    @Override
    public void storeOriginalValue(@NonNull SettingState<Integer> state, int userId)
            throws Exception {
        int defaultValue = mSecureSettingsDefaultValues.get(state.getSettingKey());
        int originalValue = Settings.Secure.getIntForUser(mContentResolver,
                state.getSettingKey(), defaultValue, userId);
        state.setOriginalValue(originalValue);
    }

    @Override
    public void applySecureLockDeviceValue(@NonNull SettingState<Integer> state, int userId)
            throws Exception {
        int secureLockDeviceValue = state.getSecureLockDeviceValue();
        Settings.Secure.putIntForUser(mContentResolver, state.getSettingKey(),
                secureLockDeviceValue, userId);
    }

    @Override
    public void restoreFromOriginalValue(@NonNull SettingState<Integer> state, int userId)
            throws Exception {
        Integer originalValue = state.getOriginalValue();
        if (originalValue != null) {
            Settings.Secure.putIntForUser(mContentResolver, state.getSettingKey(), originalValue,
                    userId);
        }
    }

    @Override
    public void serializeOriginalValue(@NonNull String settingKey, @NonNull Integer originalValue,
            @NonNull TypedXmlSerializer serializer) throws IOException {
        serializer.text(Integer.toString(originalValue));
    }

    @Override
    public Integer deserializeOriginalValue(@NonNull TypedXmlPullParser parser,
            @NonNull String settingKey) throws IOException, XmlPullParserException {
        return Integer.parseInt(parser.nextText());
    }
}
