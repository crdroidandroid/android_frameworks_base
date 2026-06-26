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
package com.android.server.wm;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import lineageos.health.HealthInterface;

class GameStateDispatcher {
    private static final String TAG = "GameStateDispatcher";
    private static final String GAME_SPACE_PACKAGE = "io.chaldeaprjkt.gamespace";
    private static final String GAME_SPACE_SESSION_SERVICE =
            "io.chaldeaprjkt.gamespace.gamebar.SessionService";
    private static final ComponentName GAME_SPACE_SESSION_COMPONENT =
            new ComponentName(GAME_SPACE_PACKAGE, GAME_SPACE_SESSION_SERVICE);
    private static final String ACTION_GAME_START = "game_start";
    private static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final String KEY_GAMING_MODE_ACTIVE = "ax_gaming_mode_active";
    private static final String KEY_BYPASS_CHARGE_ENABLED = "bypass_charge_enabled";
    private static final String KEY_POWER_MODE_PERF_BY_USER = "power_mode_perf_by_user";
    private static final String KEY_POWER_MODE_PERF = "persist.sys.power_mode_perf";
    private static final int DEFAULT_CHARGE_LIMIT = 100;

    private final Context mContext;
    private final IActivityManager mActivityManager;

    private int mChargeControlLimit = DEFAULT_CHARGE_LIMIT;
    private boolean mWasChargingControlEnabled;
    private boolean mBypassChargeActive;

    GameStateDispatcher(Context context) {
        mContext = context;
        mActivityManager = ActivityManager.getService();
    }

    void dispatchGameState(boolean active, String packageName) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(), KEY_GAMING_MODE_ACTIVE,
                active ? 1 : 0, UserHandle.USER_CURRENT);
        updateGameSession(active, packageName);
        updateBypassCharge(active);
    }

    void boostGame(boolean enable) {
        final int perfByUser = Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_POWER_MODE_PERF_BY_USER, 0, UserHandle.USER_CURRENT);
        if (perfByUser == 1) {
            return;
        }

        Settings.System.putIntForUser(mContext.getContentResolver(), KEY_POWER_MODE_PERF,
                enable ? 1 : 0, UserHandle.USER_CURRENT);
        SystemProperties.set(KEY_POWER_MODE_PERF, enable ? "1" : "0");
    }

    private void updateGameSession(boolean active, String packageName) {
        if (active) {
            startGameSession(packageName);
            return;
        }
        stopGameSession();
    }

    private void startGameSession(String packageName) {
        if (packageName == null) {
            return;
        }
        final Intent intent = new Intent(ACTION_GAME_START)
                .setComponent(GAME_SPACE_SESSION_COMPONENT)
                .putExtra(EXTRA_PACKAGE_NAME, packageName);
        try {
            final ComponentName result = mActivityManager.startService(null, intent, null, false,
                    mContext.getOpPackageName(), mContext.getAttributionTag(),
                    UserHandle.USER_CURRENT);
            if (result == null) {
                Slog.w(TAG, "GameSpace session service not found");
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Failed to start GameSpace session", e);
        }
    }

    private void stopGameSession() {
        final Intent intent = new Intent().setComponent(GAME_SPACE_SESSION_COMPONENT);
        try {
            mActivityManager.stopService(null, intent, null, UserHandle.USER_CURRENT);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Failed to stop GameSpace session", e);
        }
    }

    private void updateBypassCharge(boolean active) {
        if (active) {
            if (!bypassChargeEnabled()) {
                return;
            }
            mChargeControlLimit = getChargingLimit();
            setBypassActive(true);
            setSmartChargeLevel(batteryLevel());
            mBypassChargeActive = true;
            return;
        }

        if (!mBypassChargeActive) {
            return;
        }
        setBypassActive(false);
        setSmartChargeLevel(mChargeControlLimit);
        mBypassChargeActive = false;
    }

    private boolean bypassChargeEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_BYPASS_CHARGE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    private int getChargingLimit() {
        try {
            final HealthInterface health = HealthInterface.getInstance(mContext);
            mWasChargingControlEnabled = health.getEnabled();
            if (mWasChargingControlEnabled) {
                return health.getLimit();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get charging limit", e);
        }
        return DEFAULT_CHARGE_LIMIT;
    }

    private int batteryLevel() {
        final BatteryManager batteryManager = mContext.getSystemService(BatteryManager.class);
        if (batteryManager == null) {
            return -1;
        }
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private void setSmartChargeLevel(int value) {
        if (value < 0) {
            return;
        }
        try {
            final HealthInterface health = HealthInterface.getInstance(mContext);
            health.setMode(HealthInterface.MODE_LIMIT);
            health.setLimit(value);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set charging limit", e);
        }
    }

    private void setBypassActive(boolean value) {
        try {
            final HealthInterface health = HealthInterface.getInstance(mContext);
            health.setEnabled(value || mWasChargingControlEnabled);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set charging bypass", e);
        }
    }
}
