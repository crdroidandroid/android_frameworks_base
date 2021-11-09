/*
 * Copyright (C) 2021 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pixelexperience.systemui.statusbar.phone;

import android.content.Context;
import android.hardware.display.NightDisplayListener;
import android.os.Build;
import android.os.Handler;

import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceControlsController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.WalletController;
import com.android.systemui.util.settings.SecureSettings;

public class AutoTileManagerGoogle extends AutoTileManager {
    private final BatteryController mBatteryController;
    private final BatteryController.BatteryStateChangeCallback mBatteryControllerCallback = new BatteryController.BatteryStateChangeCallback() {
        @Override
        public void onReverseChanged(boolean z, int i, String str) {
            if (!mAutoTracker.isAdded("reverse") && z) {
                mHost.addTile("reverse");
                mAutoTracker.setTileAdded("reverse");
                mHandler.post(() -> mBatteryController.removeCallback(mBatteryControllerCallback));
            }
        }
    };

    public AutoTileManagerGoogle(Context context, AutoAddTracker.Builder builder, QSTileHost qSTileHost, Handler handler, SecureSettings secureSettings, HotspotController hotspotController, DataSaverController dataSaverController, ManagedProfileController managedProfileController, NightDisplayListener nightDisplayListener, CastController castController, BatteryController batteryController, ReduceBrightColorsController reduceBrightColorsController, DeviceControlsController deviceControlsController, WalletController walletController, boolean z) {
        super(context, builder, qSTileHost, handler, secureSettings, hotspotController, dataSaverController, managedProfileController, nightDisplayListener, castController, reduceBrightColorsController, deviceControlsController, walletController, z);
        mBatteryController = batteryController;
    }

    @Override
    public void init() {
        super.init();
        if (!mAutoTracker.isAdded("ott") && Build.IS_DEBUGGABLE) {
            mAutoTracker.setTileAdded("ott");
            mHost.addTile("ott");
        }
    }

    @Override
    public void startControllersAndSettingsListeners() {
        super.startControllersAndSettingsListeners();
        if (!mAutoTracker.isAdded("reverse")) {
            mBatteryController.addCallback(mBatteryControllerCallback);
        }
    }

    @Override
    public void stopListening() {
        super.stopListening();
        mBatteryController.removeCallback(mBatteryControllerCallback);
    }
}
