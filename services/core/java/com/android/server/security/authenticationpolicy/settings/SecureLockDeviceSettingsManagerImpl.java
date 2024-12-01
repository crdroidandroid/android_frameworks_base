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

import static com.android.server.security.authenticationpolicy.settings.DevicePolicyRestrictionsController.DEVICE_POLICY_RESTRICTIONS_KEY;

import android.app.ActivityTaskManager;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.usb.IUsbManagerInternal;
import android.hardware.usb.UsbManager;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.security.authenticationpolicy.settings.SettingState.SettingType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Secure lock device settings manager implementation.
 */
public class SecureLockDeviceSettingsManagerImpl implements SecureLockDeviceSettingsManager {
    private static final String TAG = "SLDSettingsManagerImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int DISABLE_FLAGS =
            // Flag to make the status bar not expandable
            (StatusBarManager.DISABLE_EXPAND
                    // Flag to hide notification icons and scrolling ticker text.
                    | StatusBarManager.DISABLE_NOTIFICATION_ICONS
                    // Flag to disable incoming notification alerts.  This will not block icons,
                    // but it will block sound, vibrating and other visual or aural notifications.
                    | StatusBarManager.DISABLE_NOTIFICATION_ALERTS
                    // Flag to hide only the home button.
                    | StatusBarManager.DISABLE_HOME
                    // Flag to hide only the back button.
                    | StatusBarManager.DISABLE_BACK
                    // Flag to disable the global search gesture.
                    | StatusBarManager.DISABLE_SEARCH
                    // Flag to disable the ongoing call chip.
                    | StatusBarManager.DISABLE_ONGOING_CALL_CHIP);

    private static final int DISABLE2_FLAGS =
            // Flag to disable quick settings
            (StatusBarManager.DISABLE2_QUICK_SETTINGS
                    // Flag to hide system icons.
                    | StatusBarManager.DISABLE2_SYSTEM_ICONS
                    // Flag to disable notification shade
                    | StatusBarManager.DISABLE2_NOTIFICATION_SHADE);

    private static final Set<String> DEVICE_POLICY_RESTRICTIONS =
            Set.of(
                    UserManager.DISALLOW_USB_FILE_TRANSFER,
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    UserManager.DISALLOW_CHANGE_WIFI_STATE,
                    UserManager.DISALLOW_CONFIG_WIFI,
                    UserManager.DISALLOW_SMS,
                    UserManager.DISALLOW_USER_SWITCH);

    private static final Map<String, Integer> SECURE_SETTINGS_SECURE_LOCK_DEVICE_VALUES =
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

    private static final Map<String, Integer> SYSTEM_SETTINGS_SECURE_LOCK_DEVICE_VALUES =
            Map.of(
                    Settings.System.BLUETOOTH_DISCOVERABILITY, 0,
                    Settings.System.LOCK_TO_APP_ENABLED, 0);

    private static final Set<String> GLOBAL_SETTINGS =
            Set.of(
                    Settings.Global.ADB_ENABLED,
                    Settings.Global.ADB_WIFI_ENABLED,
                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                    Settings.Global.USER_SWITCHER_ENABLED,
                    Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED);

    private static final String DISABLE_FLAGS_KEY = "disable_flags";
    private static final String USB_ENABLED_KEY = "usb_enabled";
    private static final String NFC_ENABLED_KEY = "nfc_enabled";

    private final ActivityTaskManager mActivityTaskManager;
    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;
    private final Object mManagedSettingsLock = new Object();
    @NonNull
    @GuardedBy("mManagedSettingsLock")
    private final Map<String, ManagedSetting<?>> mManagedSettings = new HashMap<>();
    private final DevicePolicyRestrictionsController mDevicePolicyRestrictionsController =
            new DevicePolicyRestrictionsController();
    private final NfcSettingController mNfcSettingController;
    private final UsbSettingController mUsbSettingController = new UsbSettingController();
    private final DisableFlagsController mDisableFlagsController;
    private final SecureSettingController mSecureSettingController;
    private final SystemSettingController mSystemSettingController;
    private final GlobalSettingController mGlobalSettingController;

    public SecureLockDeviceSettingsManagerImpl(@NonNull Context context,
            @NonNull ActivityTaskManager activityTaskManager,
            @Nullable IStatusBarService statusBarService,
            @Nullable IVoiceInteractionManagerService voiceInteractionManagerService
    ) {
        mActivityTaskManager = activityTaskManager;
        mVoiceInteractionManagerService = voiceInteractionManagerService;
        mDisableFlagsController = new DisableFlagsController(context.getPackageName(),
                statusBarService);
        mNfcSettingController = new NfcSettingController(context);
        ContentResolver contentResolver = context.getContentResolver();
        mSecureSettingController = new SecureSettingController(context);
        mSystemSettingController = new SystemSettingController(context);
        mGlobalSettingController = new GlobalSettingController(contentResolver);
        resetManagedSettings();
    }

    @NonNull
    @Override
    public Map<String, ManagedSetting<?>> getManagedSettings() {
        synchronized (mManagedSettingsLock) {
            return new HashMap<>(mManagedSettings);
        }
    }

    @Override
    public void initSettingsControllerDependencies(
            @Nullable DevicePolicyManager devicePolicyManager, @Nullable UsbManager usbManager,
            @Nullable IUsbManagerInternal usbManagerInternal) {
        mDevicePolicyRestrictionsController.setDevicePolicyManager(devicePolicyManager);
        mUsbSettingController.setUsbManager(usbManager);
        mUsbSettingController.setUsbManagerInternal(usbManagerInternal);
    }

    @Override
    public void resetManagedSettings() {
        Slog.d(TAG, "resetManagedSettings");

        synchronized (mManagedSettingsLock) {
            mManagedSettings.clear();
            mManagedSettings.put(NFC_ENABLED_KEY, new ManagedSetting<>(
                    new SettingState<>(NFC_ENABLED_KEY, SettingType.BOOLEAN, false),
                    mNfcSettingController
            ));

            mManagedSettings.put(USB_ENABLED_KEY, new ManagedSetting<>(
                    new SettingState<>(USB_ENABLED_KEY, SettingType.USB_PORT_MAP, new HashMap<>()),
                    mUsbSettingController
            ));

            for (String settingKey : GLOBAL_SETTINGS) {
                mManagedSettings.put(settingKey, new ManagedSetting<>(
                        new SettingState<>(settingKey, SettingType.INTEGER, 0),
                        mGlobalSettingController
                ));
            }

            mManagedSettings.put(DEVICE_POLICY_RESTRICTIONS_KEY, new ManagedSetting<>(
                    new SettingState<>(DEVICE_POLICY_RESTRICTIONS_KEY, SettingType.STRING_SET,
                            DEVICE_POLICY_RESTRICTIONS),
                    mDevicePolicyRestrictionsController
            ));

            for (Map.Entry<String, Integer> entry :
                    SECURE_SETTINGS_SECURE_LOCK_DEVICE_VALUES.entrySet()) {
                mManagedSettings.put(entry.getKey(), new ManagedSetting<>(
                        new SettingState<>(entry.getKey(), SettingType.INTEGER, entry.getValue()),
                        mSecureSettingController
                ));
            }
            for (Map.Entry<String, Integer> entry :
                    SYSTEM_SETTINGS_SECURE_LOCK_DEVICE_VALUES.entrySet()) {
                mManagedSettings.put(entry.getKey(), new ManagedSetting<>(
                        new SettingState<>(entry.getKey(), SettingType.INTEGER, entry.getValue()),
                        mSystemSettingController
                ));
            }
            mManagedSettings.put(DISABLE_FLAGS_KEY, new ManagedSetting<>(
                    new SettingState<>(DISABLE_FLAGS_KEY, SettingType.INTEGER_PAIR,
                            new Pair<>(DISABLE_FLAGS, DISABLE2_FLAGS)),
                    mDisableFlagsController
            ));
        }
    }

    @Override
    public void enableSecurityFeaturesFromBoot(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "enableSecurityFeaturesFromBoot: enabling from boot, original settings "
                    + "values already loaded from file. Applying secure lock device values to "
                    + "settings.");
        }
        applySecureLockDeviceSettings(userId);
    }

    @Override
    public void enableSecurityFeatures(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "enableSecurityFeatures: not enabling from boot, initialize managed "
                    + "settings and store current state of settings.");
        }
        // Store current value to original value, apply secure lock device values on settings
        resetManagedSettings();
        storeOriginalSettings(userId);

        if (DEBUG) {
            Slog.d(TAG, "enableSecurityFeatures: applying secure lock device values to "
                    + "settings.");
        }
        applySecureLockDeviceSettings(userId);
    }

    @Override
    public void restoreOriginalSettings(int userId) {
        synchronized (mManagedSettingsLock) {
            mManagedSettings.forEach((settingKey, setting) -> {
                setting.restoreFromOriginalValue(userId);
            });
        }
    }

    /**
     * Applies secure lock device values to all managed settings.
     * @param userId The user ID to apply the secure lock device values to.
     */
    private void applySecureLockDeviceSettings(int userId) {
        // Stop app pinning via ActivityTaskManager
        try {
            mActivityTaskManager.stopSystemLockTaskMode();
        } catch (Exception e) {
            Slog.e(TAG, "Error stopping system lock task mode", e);
        }

        // Temporarily disable assistant access
        try {
            mVoiceInteractionManagerService.setDisabled(true);
        } catch (Exception e) {
            Slog.e(TAG, "Error disabling assistant access", e);
        }

        synchronized (mManagedSettingsLock) {
            for (ManagedSetting<?> setting : mManagedSettings.values()) {
                if (DEBUG) {
                    Slog.d(TAG, "Applying secure lock device value to " + setting);
                }
                setting.applySecureLockDeviceValue(userId);
            }
        }
    }

    /**
     * Stores the current value of each setting to managed settings.
     * @param userId The user ID to store the settings for.
     */
    private void storeOriginalSettings(int userId) {
        synchronized (mManagedSettingsLock) {
            for (ManagedSetting<?> setting : mManagedSettings.values()) {
                setting.storeOriginalValue(userId);
            }
        }
    }

    @Override
    public void setSkipSecurityFeaturesForTest(boolean skipSecurityFeaturesForTest) {
        mDevicePolicyRestrictionsController.setSkipSecurityFeaturesForTest(
                skipSecurityFeaturesForTest);
        mGlobalSettingController.setSkipSecurityFeaturesForTest(skipSecurityFeaturesForTest);
        mNfcSettingController.setSkipSecurityFeaturesForTest(skipSecurityFeaturesForTest);
        mUsbSettingController.setSkipSecurityFeaturesForTest(skipSecurityFeaturesForTest);
    }
}
