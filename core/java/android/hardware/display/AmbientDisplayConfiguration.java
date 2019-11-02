/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.hardware.display;

import android.annotation.TestApi;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

/**
 * AmbientDisplayConfiguration encapsulates reading access to the configuration of ambient display.
 *
 * {@hide}
 */
@TestApi
public class AmbientDisplayConfiguration {
    private static final String TAG = "AmbientDisplayConfig";
    private final Context mContext;
    private final boolean mAlwaysOnByDefault;
    private final boolean mDozeEnabledByDefault;

    /** {@hide} */
    @TestApi
    public AmbientDisplayConfiguration(Context context) {
        mContext = context;
        mAlwaysOnByDefault = mContext.getResources().getBoolean(R.bool.config_dozeAlwaysOnEnabled);
        mDozeEnabledByDefault = mContext.getResources().getBoolean(R.bool.config_doze_enabled_by_default);
    }

    /** {@hide} */
    public boolean enabled(int user) {
        return pulseOnNotificationEnabled(user)
                || pulseOnLongPressEnabled(user)
                || alwaysOnEnabled(user)
                || isAmbientTickerEnabled(user)
                || wakeLockScreenGestureEnabled(user)
                || wakeDisplayGestureEnabled(user)
                || pickupGestureEnabled(user)
                || tiltGestureEnabled(user)
                || handwaveGestureEnabled(user)
                || pocketGestureEnabled(user)
                || tapGestureEnabled(user)
                || doubleTapGestureEnabled(user)
                || quickPickupSensorEnabled(user)
                || screenOffUdfpsEnabled(user);
    }

    /** {@hide} */
    public boolean pulseOnNotificationEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_ENABLED, user, mDozeEnabledByDefault ? 1 : 0)
                && pulseOnNotificationAvailable();
    }

    /** {@hide} */
    public boolean pulseOnNotificationAvailable() {
        return ambientDisplayAvailable();
    }

    /** {@hide} */
    public boolean isAmbientTickerEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.PULSE_ON_NEW_TRACKS, user);
    }

    /** {@hide} */
    public boolean pickupGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_PICK_UP_GESTURE, user)
                && dozePickupSensorAvailable();
    }

    /** {@hide} */
    public boolean dozePickupSensorAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulsePickup);
    }


    /** {@hide} */
    public boolean tiltGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_TILT_GESTURE, user)
                && dozeTiltSensorAvailable();
    }

    /** {@hide} */
    public boolean dozeTiltSensorAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulseTilt);
    }

    /** {@hide} */
    public boolean handwaveGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_HANDWAVE_GESTURE, user)
                && dozeProximitySensorAvailable();
    }

    /** {@hide} */
    public boolean pocketGestureEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.DOZE_POCKET_GESTURE, user)
                && dozeProximitySensorAvailable();
    }

    /** {@hide} */
    public boolean dozeProximitySensorAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozePulseProximity);
    }

    /** {@hide} */
    public boolean tapGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_TAP_SCREEN_GESTURE, user)
                && tapSensorAvailable();
    }

    /** {@hide} */
    public boolean tapSensorAvailable() {
        for (String tapType : tapSensorTypeMapping()) {
            if (!TextUtils.isEmpty(tapType)) {
                return true;
            }
        }
        return false;
    }

    /** {@hide} */
    public boolean doubleTapGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_DOUBLE_TAP_GESTURE, user)
                && doubleTapSensorAvailable();
    }

    /** {@hide} */
    public boolean doubleTapSensorAvailable() {
        return !TextUtils.isEmpty(doubleTapSensorType());
    }

    /** {@hide} */
    public boolean quickPickupSensorEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_QUICK_PICKUP_GESTURE, user)
                && !TextUtils.isEmpty(quickPickupSensorType())
                && pickupGestureEnabled(user)
                && !alwaysOnEnabled(user);
    }

    /** {@hide} */
    public boolean screenOffUdfpsEnabled(int user) {
        return !TextUtils.isEmpty(udfpsLongPressSensorType())
            && boolSettingDefaultOff("screen_off_udfps_enabled", user);
    }

    /** {@hide} */
    public boolean wakeScreenGestureAvailable() {
        return mContext.getResources()
                .getBoolean(R.bool.config_dozeWakeLockScreenSensorAvailable);
    }

    /** {@hide} */
    public boolean wakeLockScreenGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_WAKE_LOCK_SCREEN_GESTURE, user)
                && wakeScreenGestureAvailable();
    }

    /** {@hide} */
    public boolean wakeDisplayGestureEnabled(int user) {
        return boolSettingDefaultOn(Settings.Secure.DOZE_WAKE_DISPLAY_GESTURE, user)
                && wakeScreenGestureAvailable();
    }

    /** {@hide} */
    public long getWakeLockScreenDebounce() {
        return mContext.getResources().getInteger(R.integer.config_dozeWakeLockScreenDebounce);
    }

    /** {@hide} */
    public String doubleTapSensorType() {
        return mContext.getResources().getString(R.string.config_dozeDoubleTapSensorType);
    }

    /** {@hide}
     * May support multiple postures.
     */
    public String[] tapSensorTypeMapping() {
        String[] postureMapping =
                mContext.getResources().getStringArray(R.array.config_dozeTapSensorPostureMapping);
        if (ArrayUtils.isEmpty(postureMapping)) {
            return new String[] {
                    mContext.getResources().getString(R.string.config_dozeTapSensorType)
            };
        }
        return postureMapping;
    }

    /** {@hide} */
    public String longPressSensorType() {
        return mContext.getResources().getString(R.string.config_dozeLongPressSensorType);
    }

    /** {@hide} */
    public String udfpsLongPressSensorType() {
        return mContext.getResources().getString(R.string.config_dozeUdfpsLongPressSensorType);
    }

    /** {@hide} */
    public String quickPickupSensorType() {
        return mContext.getResources().getString(R.string.config_quickPickupSensorType);
    }

    /** {@hide} */
    public boolean pulseOnLongPressEnabled(int user) {
        return pulseOnLongPressAvailable() && boolSettingDefaultOff(
                Settings.Secure.DOZE_PULSE_ON_LONG_PRESS, user);
    }

    private boolean pulseOnLongPressAvailable() {
        return !TextUtils.isEmpty(longPressSensorType());
    }

    /**
     * Returns if Always-on-Display functionality is enabled on the display for a specified user.
     *
     * {@hide}
     */
    @TestApi
    public boolean alwaysOnEnabled(int user) {
        return boolSetting(Settings.Secure.DOZE_ALWAYS_ON, user, mAlwaysOnByDefault ? 1 : 0)
                && alwaysOnAvailable() && !accessibilityInversionEnabled(user);
    }

    /**
     * Returns if Always-on-Display functionality is available on the display.
     *
     * {@hide}
     */
    @TestApi
    public boolean alwaysOnAvailable() {
        return (alwaysOnDisplayDebuggingEnabled() || alwaysOnDisplayAvailable())
                && ambientDisplayAvailable();
    }

    /**
     * Returns if Always-on-Display functionality is available on the display for a specified user.
     *
     *  {@hide}
     */
    @TestApi
    public boolean alwaysOnAvailableForUser(int user) {
        return alwaysOnAvailable() && !accessibilityInversionEnabled(user);
    }

    /** {@hide} */
    public String ambientDisplayComponent() {
        return mContext.getResources().getString(R.string.config_dozeComponent);
    }

    /** {@hide} */
    public boolean accessibilityInversionEnabled(int user) {
        return boolSettingDefaultOff(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, user);
    }

    /** {@hide} */
    public boolean ambientDisplayAvailable() {
        return !TextUtils.isEmpty(ambientDisplayComponent());
    }

    /** {@hide} */
    public boolean dozeSuppressed(int user) {
        return boolSettingDefaultOff(Settings.Secure.SUPPRESS_DOZE, user);
    }

    private boolean alwaysOnDisplayAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_dozeAlwaysOnDisplayAvailable);
    }

    private boolean alwaysOnDisplayDebuggingEnabled() {
        return SystemProperties.getBoolean("debug.doze.aod", false) && Build.IS_DEBUGGABLE;
    }

    private boolean boolSettingDefaultOn(String name, int user) {
        return boolSetting(name, user, 1);
    }

    private boolean boolSettingDefaultOff(String name, int user) {
        return boolSetting(name, user, 0);
    }

    private boolean boolSetting(String name, int user, int def) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(), name, def, user) != 0;
    }
}
