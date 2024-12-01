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

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Map;

public class SecureSettingControllerTest {
    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    private final Map<String, Integer> mSecureSettingsOriginalOrDefaultValues =
            new HashMap<>();
    private final Map<String, Integer> mSecureSettingsSecureLockDeviceValues =
            Map.of(
                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0,
                    Settings.Secure.DOUBLE_TAP_POWER_BUTTON_GESTURE_ENABLED, 0,
                    Settings.Secure.CAMERA_GESTURE_DISABLED, 1,
                    Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED, 1,
                    Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED, 0,
                    Settings.Secure.LOCKSCREEN_SMARTSPACE_ENABLED, 0,
                    Settings.Secure.LOCKSCREEN_SHOW_CONTROLS, 0,
                    Settings.Secure.LOCKSCREEN_SHOW_WALLET, 0,
                    Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER, 0,
                    Settings.Secure.GLANCEABLE_HUB_ENABLED, 0);

    private Map<String, Integer> mSecureSettingsDefaultValues;
    private ContentResolver mContentResolver;
    private SecureSettingController mSecureSettingController;
    private UserManager mUserManager;
    private UserInfo mTestUser;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        // Creates test user for applying settings
        mTestUser = mUserManager.createUser("TestUser1", UserInfo.FLAG_GUEST);
        mContentResolver = context.getContentResolver();
        mSecureSettingController = new SecureSettingController(context);
        mSecureSettingsDefaultValues = mSecureSettingController.initializeDefaultValues();
        mSecureSettingsSecureLockDeviceValues.keySet().forEach(settingKey -> {
            int originalValue = Settings.Secure.getIntForUser(mContentResolver, settingKey,
                    mSecureSettingsDefaultValues.get(settingKey), mTestUser.id);
            mSecureSettingsOriginalOrDefaultValues.put(settingKey, originalValue);
        });
    }

    @After
    public void tearDown() {
        mUserManager.removeUser(mTestUser.id);
    }


    @Test
    public void testStoreOriginalValue_successfullyStoresOriginalOrDefaultValue() {
        mSecureSettingsSecureLockDeviceValues.forEach((settingKey, secureLockDeviceValue) -> {
            int originalOrDefaultValue = mSecureSettingsOriginalOrDefaultValues.get(settingKey);
            SettingState<Integer> settingState = new SettingState<>(settingKey,
                    SettingState.SettingType.INTEGER, secureLockDeviceValue);

            try {
                mSecureSettingController.storeOriginalValue(settingState, mTestUser.id);

                assertThat(settingState.getOriginalValue()).isEqualTo(originalOrDefaultValue);
            } catch (Exception e) {
                throw new RuntimeException("Failed to store original value for " + settingKey, e);
            }
        });
    }

    @Test
    public void testApplySecureLockDeviceValue_successfullyAppliesSecureLockDeviceValue() {
        mSecureSettingsSecureLockDeviceValues.forEach((settingKey, secureLockDeviceValue) -> {
            SettingState<Integer> settingState = new SettingState<>(settingKey,
                    SettingState.SettingType.INTEGER, secureLockDeviceValue);

            try {
                mSecureSettingController.applySecureLockDeviceValue(settingState, mTestUser.id);
                assertThat(Settings.Secure.getIntForUser(mContentResolver, settingKey,
                        mTestUser.id)).isEqualTo(secureLockDeviceValue);
            } catch (Exception e) {
                throw new RuntimeException("Failed to apply secure lock device value for "
                        + settingKey, e);
            }
        });
    }

    @Test
    public void testRestoreFromOriginalValue_successfullyRestoresOriginalOrDefaultValue() {
        mSecureSettingsSecureLockDeviceValues.forEach((settingKey, secureLockDeviceValue) -> {
            int originalOrDefaultValue = mSecureSettingsOriginalOrDefaultValues.get(settingKey);
            SettingState<Integer> settingState = new SettingState<>(settingKey,
                    SettingState.SettingType.INTEGER, secureLockDeviceValue);

            try {
                mSecureSettingController.storeOriginalValue(settingState, mTestUser.id);
                mSecureSettingController.applySecureLockDeviceValue(settingState, mTestUser.id);
                mSecureSettingController.restoreFromOriginalValue(settingState, mTestUser.id);
                assertThat(Settings.Secure.getIntForUser(mContentResolver, settingKey,
                        mTestUser.id)).isEqualTo(originalOrDefaultValue);
            } catch (Exception e) {
                throw new RuntimeException("Failed to restore original or default value for "
                        + settingKey, e);
            }
        });
    }
}
