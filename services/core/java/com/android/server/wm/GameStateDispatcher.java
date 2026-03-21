/*
 * Copyright (C) 2025-2026 AxionOS Project
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

import android.content.Context;
import android.os.BatteryManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import lineageos.health.HealthInterface;

import com.android.internal.app.IGameSpaceCallback;

import java.util.List;

class GameStateDispatcher {

    private static final String TAG = "GameStateDispatcher";
    private static final String KEY_GAMING_MODE_ACTIVE = "ax_gaming_mode_active";
    private static final String KEY_BYPASS_CHARGE_ENABLED = "bypass_charge_enabled";

    private final Context mContext;
    private final List<IGameSpaceCallback> mCallbacks;

    private int mChargeControlLimit = 100;
    private boolean mWasChargingControlEnabled = false;

    GameStateDispatcher(Context context, List<IGameSpaceCallback> callbacks) {
        mContext = context;
        mCallbacks = callbacks;
    }

    void dispatchGameState(boolean active, String packageName) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                KEY_GAMING_MODE_ACTIVE, active ? 1 : 0, UserHandle.USER_CURRENT);

        for (IGameSpaceCallback callback : mCallbacks) {
            try {
                if (active && packageName != null) {
                    callback.onGameStart(packageName);
                } else {
                    callback.onGameLeave();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Removing dead callback", e);
                mCallbacks.remove(callback);
            }
        }

        if (active) {
            if (bypassChargeEnabled()) {
                mChargeControlLimit = getChargingLimit();
                setBypassActive(true);
                setSmartChargeLvl(battLevel());
            }
        } else {
            if (bypassChargeEnabled()) {
                setBypassActive(false);
                setSmartChargeLvl(mChargeControlLimit);
            }
        }
    }

    void boostGame(boolean enable) {
        int perfByUser = Settings.System.getIntForUser(
                mContext.getContentResolver(), "power_mode_perf_by_user", 0,
                UserHandle.USER_CURRENT);
        if (perfByUser == 1) return;

        Settings.System.putIntForUser(mContext.getContentResolver(),
                "persist.sys.power_mode_perf", enable ? 1 : 0,
                UserHandle.USER_CURRENT);
        SystemProperties.set("persist.sys.power_mode_perf", enable ? "1" : "0");
    }

    void setBypassCharge(boolean enable) {
        if (!bypassChargeEnabled()) return;

        if (enable) {
            mChargeControlLimit = getChargingLimit();
        }

        setBypassActive(enable);
        setSmartChargeLvl(enable ? battLevel() : mChargeControlLimit);
    }

    private boolean bypassChargeEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_BYPASS_CHARGE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    private int getChargingLimit() {
        try {
            HealthInterface health = HealthInterface.getInstance(mContext);
            mWasChargingControlEnabled = health.getEnabled();
            if (mWasChargingControlEnabled) {
                return health.getLimit();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get charging limit", e);
        }
        return 100;
    }

    private int battLevel() {
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    private void setSmartChargeLvl(int value) {
        try {
            HealthInterface health = HealthInterface.getInstance(mContext);
            health.setMode(HealthInterface.MODE_LIMIT);
            health.setLimit(value);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set charging limit", e);
        }
    }

    private void setBypassActive(boolean value) {
        try {
            HealthInterface health = HealthInterface.getInstance(mContext);
            health.setEnabled(value || mWasChargingControlEnabled);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set charging bypass", e);
        }
    }
}
