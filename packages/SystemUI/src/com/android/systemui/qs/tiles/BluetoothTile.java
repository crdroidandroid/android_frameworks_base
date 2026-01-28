/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import static com.android.settingslib.flags.Flags.refactorBatteryLevelDisplay;
import static com.android.settingslib.satellite.SatelliteDialogUtils.TYPE_IS_BLUETOOTH;
import static com.android.systemui.Flags.iconRefresh2025;
import static com.android.systemui.util.PluralMessageFormaterKt.icuMessageFormat;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.BatteryLevelsInfo;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.satellite.SatelliteDialogUtils;
import com.android.systemui.animation.Expandable;
import com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel;
import com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsViewModel;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.qs.TileDetailsViewModel;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BluetoothController;

import dagger.Lazy;

import kotlinx.coroutines.Job;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

/** Quick settings tile: Bluetooth **/
public class BluetoothTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "bt";

    private static final Intent BLUETOOTH_SETTINGS = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);

    private static final String TAG = BluetoothTile.class.getSimpleName();

    private final BluetoothController mController;

    private CachedBluetoothDevice mMetadataRegisteredDevice = null;
    private CachedBluetoothDevice mBatteryCallbackRegisteredDevice = null;

    private final Executor mExecutor;

    private final Lazy<BluetoothDetailsContentViewModel> mDetailsContentViewModel;

    private final FeatureFlags mFeatureFlags;
    @Nullable
    @VisibleForTesting
    Job mClickJob;

    @Inject
    public BluetoothTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BluetoothController bluetoothController,
            FeatureFlags featureFlags,
            Lazy<BluetoothDetailsContentViewModel> detailsContentViewModel
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = bluetoothController;
        mController.observe(getLifecycle(), mCallback);
        mExecutor = new HandlerExecutor(mainHandler);
        mFeatureFlags = featureFlags;
        mDetailsContentViewModel = detailsContentViewModel;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState s = new BooleanState();
        s.handlesSecondaryClick = true;
        return s;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        handleClickWithSatelliteCheck(() -> handleClickEvent(expandable));
    }

    @Override
    public boolean getDetailsViewModel(Consumer<TileDetailsViewModel> callback) {
        handleClickWithSatelliteCheck(() ->
                callback.accept(new BluetoothDetailsViewModel(() -> {
                    longClick(null);
                    return null;
                }, mDetailsContentViewModel.get()))
        );
        return true;
    }

    private void handleClickWithSatelliteCheck(Runnable clickCallback) {
        if (mClickJob != null && !mClickJob.isCompleted()) {
            return;
        }
        mClickJob = SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                mContext, this, TYPE_IS_BLUETOOTH, isAllowClick -> {
                    if (!isAllowClick) {
                        return null;
                    }
                    clickCallback.run();
                    return null;
                });
    }

    private void handleClickEvent(@Nullable Expandable expandable) {
        if (mFeatureFlags.isEnabled(Flags.BLUETOOTH_QS_TILE_DIALOG)) {
            mDetailsContentViewModel.get().showDialog(expandable);
        } else {
            // Secondary clicks are header clicks, just toggle.
            toggleBluetooth();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    @Override
    protected void handleSecondaryClick(@Nullable Expandable expandable) {
        handleClickWithSatelliteCheck(() -> {
            if (!mController.canConfigBluetooth()) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0);
            } else {
                toggleBluetooth();
            }
        });
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_bluetooth_label);
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);

        if (!listening) {
            if (refactorBatteryLevelDisplay()) {
                unregisterBatteryChangedCallback();
            } else {
                stopListeningToStaleDeviceMetadata();
            }
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_BLUETOOTH);
        final boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;
        final boolean transientDisabling = arg == ARG_SHOW_TRANSIENT_DISABLING;
        final boolean enabled =
                transientEnabling || (mController.isBluetoothEnabled() && !transientDisabling);
        final boolean connected = mController.isBluetoothConnected();
        final boolean connecting = mController.isBluetoothConnecting();
        state.isTransient = transientEnabling || transientDisabling || connecting
                || mController.getBluetoothState() == BluetoothAdapter.STATE_TURNING_ON
                || mController.getBluetoothState() == BluetoothAdapter.STATE_TURNING_OFF;

        if (!enabled || !connected || state.isTransient) {
            if (refactorBatteryLevelDisplay()) {
                unregisterBatteryChangedCallback();
            } else {
                stopListeningToStaleDeviceMetadata();
            }
        }
        state.dualTarget = true;
        state.value = enabled;
        state.label = mContext.getString(R.string.quick_settings_bluetooth_label);
        state.secondaryLabel = TextUtils.emptyIfNull(
                getSecondaryLabel(enabled, connecting, connected,
                        state.isTransient && transientEnabling));
        state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_bluetooth);
        state.stateDescription = "";

        if (enabled) {
            if (connected) {
                state.icon = maybeLoadResourceIcon(R.drawable.qs_bluetooth_icon_on);
                if (!TextUtils.isEmpty(mController.getConnectedDeviceName())) {
                    state.label = mController.getConnectedDeviceName();
                }
                state.stateDescription =
                        mContext.getString(R.string.accessibility_bluetooth_name, state.label)
                                + ", " + state.secondaryLabel;
            } else if (state.isTransient) {
                state.icon = maybeLoadResourceIcon(R.drawable.qs_bluetooth_icon_search);
                state.stateDescription = state.secondaryLabel;
            } else {
                state.icon = maybeLoadResourceIcon(
                        iconRefresh2025() ? R.drawable.qs_bluetooth_icon_disconnected
                                : R.drawable.qs_bluetooth_icon_off);
                state.stateDescription = mContext.getString(R.string.accessibility_not_connected);
            }
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.icon = maybeLoadResourceIcon(R.drawable.qs_bluetooth_icon_off);
            state.state = Tile.STATE_INACTIVE;
        }

        state.expandedAccessibilityClassName = Button.class.getName();
        state.forceExpandIcon = mFeatureFlags.isEnabled(Flags.BLUETOOTH_QS_TILE_DIALOG);
    }

    private void toggleBluetooth() {
        final boolean isEnabled = mState.value;
        // Immediately enter transient enabling state when toggling bluetooth state.
        refreshState(isEnabled ? ARG_SHOW_TRANSIENT_DISABLING : ARG_SHOW_TRANSIENT_ENABLING);
        mController.setBluetoothEnabled(!isEnabled);
    }

    /**
     * Returns the secondary label to use for the given bluetooth connection in the form of the
     * battery level or bluetooth profile name. If the bluetooth is disabled, there's no connected
     * devices, or we can't map the bluetooth class to a profile, this instead returns {@code null}.
     * @param enabled whether bluetooth is enabled
     * @param connecting whether bluetooth is connecting to a device
     * @param connected whether there's a device connected via bluetooth
     * @param isTransient whether bluetooth is currently in a transient state turning on
     */
    @Nullable
    private String getSecondaryLabel(boolean enabled, boolean connecting, boolean connected,
            boolean isTransient) {
        if (connecting) {
            return mContext.getString(R.string.quick_settings_connecting);
        }
        if (isTransient) {
            return mContext.getString(R.string.quick_settings_bluetooth_secondary_label_transient);
        }

        List<CachedBluetoothDevice> connectedDevices = mController.getConnectedDevices();
        if (enabled && connected && !connectedDevices.isEmpty()) {
            if (connectedDevices.size() > 1) {
                if (refactorBatteryLevelDisplay()) {
                    unregisterBatteryChangedCallback();
                } else {
                    stopListeningToStaleDeviceMetadata();
                }
                return icuMessageFormat(mContext.getResources(),
                        R.string.quick_settings_hotspot_secondary_label_num_devices,
                        connectedDevices.size());
            }

            CachedBluetoothDevice device = connectedDevices.get(0);

            int batteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;
            if (refactorBatteryLevelDisplay()) {
                BatteryLevelsInfo batteryLevelsInfo = device.getBatteryLevelsInfo();
                if (batteryLevelsInfo != null) {
                    batteryLevel = batteryLevelsInfo.getOverallBatteryLevel();
                    registerBatteryChangedCallback(device);
                } else {
                    unregisterBatteryChangedCallback();
                }
            } else {
                // Use battery level provided by FastPair metadata if available.
                // If not, fallback to the default battery level from bluetooth.
                batteryLevel = getMetadataBatteryLevel(device);
                if (batteryLevel > BluetoothUtils.META_INT_ERROR) {
                    listenToMetadata(device);
                } else {
                    stopListeningToStaleDeviceMetadata();
                    batteryLevel = device.getMinBatteryLevelWithMemberDevices();
                }
            }

            if (batteryLevel > BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
                return mContext.getString(
                        R.string.quick_settings_bluetooth_secondary_label_battery_level,
                        Utils.formatPercentage(batteryLevel));
            } else {
                final BluetoothClass bluetoothClass = device.getBtClass();
                if (bluetoothClass != null) {
                    if (device.isHearingDevice()) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_hearing_aids);
                    } else if (bluetoothClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_audio);
                    } else if (bluetoothClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET)) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_headset);
                    } else if (bluetoothClass.doesClassMatch(BluetoothClass.PROFILE_HID)) {
                        return mContext.getString(
                                R.string.quick_settings_bluetooth_secondary_label_input);
                    }
                }
            }
        }

        return null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_BLUETOOTH;
    }

    @Override
    public boolean isAvailable() {
        return mController.isBluetoothSupported();
    }

    private int getMetadataBatteryLevel(CachedBluetoothDevice device) {
        return BluetoothUtils.getIntMetaData(device.getDevice(),
                BluetoothDevice.METADATA_MAIN_BATTERY);
    }

    private void listenToMetadata(CachedBluetoothDevice cachedDevice) {
        if (cachedDevice == mMetadataRegisteredDevice) return;
        stopListeningToStaleDeviceMetadata();
        try {
            mController.addOnMetadataChangedListener(cachedDevice,
                    mExecutor,
                    mMetadataChangedListener);
            mMetadataRegisteredDevice = cachedDevice;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Battery metadata listener already registered for device.");
        }
    }

    private void stopListeningToStaleDeviceMetadata() {
        if (mMetadataRegisteredDevice == null) return;
        try {
            mController.removeOnMetadataChangedListener(
                    mMetadataRegisteredDevice,
                    mMetadataChangedListener);
            mMetadataRegisteredDevice = null;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Battery metadata listener already unregistered for device.");
        }
    }

    private void registerBatteryChangedCallback(CachedBluetoothDevice cachedDevice) {
        if (cachedDevice.equals(mBatteryCallbackRegisteredDevice)) return;
        unregisterBatteryChangedCallback();
        cachedDevice.registerCallback(mExecutor, mBatteryChangedCallback);
        mBatteryCallbackRegisteredDevice = cachedDevice;
    }

    private void unregisterBatteryChangedCallback() {
        if (mBatteryCallbackRegisteredDevice == null) return;
        mBatteryCallbackRegisteredDevice.unregisterCallback(mBatteryChangedCallback);
        mBatteryCallbackRegisteredDevice = null;
    }

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            refreshState();
        }

        @Override
        public void onBluetoothDevicesChanged() {
            refreshState();
        }
    };

    private final BluetoothAdapter.OnMetadataChangedListener mMetadataChangedListener =
            (device, key, value) -> {
                if (key == BluetoothDevice.METADATA_MAIN_BATTERY) refreshState();
            };

    private final CachedBluetoothDevice.Callback mBatteryChangedCallback = this::refreshState;
}
