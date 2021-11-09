package org.pixelexperience.systemui.qs.tiles;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Temperature;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import androidx.lifecycle.Lifecycle;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;

import javax.inject.Inject;

public class ReverseChargingTile extends QSTileImpl<QSTile.BooleanState> implements BatteryController.BatteryStateChangeCallback {
    private static final boolean DEBUG = Log.isLoggable("ReverseChargingTile", 3);
    private final BatteryController mBatteryController;
    private int mBatteryLevel;
    private boolean mListening;
    private boolean mOverHeat;
    private boolean mPowerSave;
    private boolean mReverse;
    private final IThermalService mThermalService;
    private int mThresholdLevel;
    private final QSTile.Icon mIcon = QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_reverse_charging);
    private final IThermalEventListener mThermalEventListener = new IThermalEventListener.Stub() {
        @Override
        public void notifyThrottling(Temperature temperature) {
            int status = temperature.getStatus();
            mOverHeat = status >= 5;
            if (ReverseChargingTile.DEBUG) {
                Log.d("ReverseChargingTile", "notifyThrottling(): status=" + status);
            }
        }
    };
    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean z) {
            updateThresholdLevel();
        }
    };

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Inject
    public ReverseChargingTile(QSHost qSHost, @Background Looper looper, @Main Handler handler, FalsingManager falsingManager, MetricsLogger metricsLogger, StatusBarStateController statusBarStateController, ActivityStarter activityStarter, QSLogger qSLogger, BatteryController batteryController, IThermalService iThermalService) {
        super(qSHost, looper, handler, falsingManager, metricsLogger, statusBarStateController, activityStarter, qSLogger);
        mBatteryController = batteryController;
        batteryController.observe(getLifecycle(), this);
        mThermalService = iThermalService;
    }

    public QSTile.BooleanState newTileState() {
        return new QSTile.BooleanState();
    }

    @Override
    public void handleDestroy() {
        super.handleDestroy();
    }

    @Override
    public void handleSetListening(boolean z) {
        super.handleSetListening(z);
        if (mListening != z) {
            mListening = z;
            if (z) {
                updateThresholdLevel();
                mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("advanced_battery_usage_amount"), false, mSettingsObserver);
                try {
                    mThermalService.registerThermalEventListenerWithType(mThermalEventListener, 3);
                } catch (RemoteException e) {
                    Log.e("ReverseChargingTile", "Could not register thermal event listener, exception: " + e);
                }
                mOverHeat = isOverHeat();
            } else {
                mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
                try {
                    mThermalService.unregisterThermalEventListener(mThermalEventListener);
                } catch (RemoteException e2) {
                    Log.e("ReverseChargingTile", "Could not unregister thermal event listener, exception: " + e2);
                }
            }
            if (DEBUG) {
                Log.d("ReverseChargingTile", "handleSetListening(): rtx=" + (mReverse ? 1 : 0) + ",level=" + mBatteryLevel + ",threshold=" + mThresholdLevel + ",listening=" + z);
            }
        }
    }

    public boolean isAvailable() {
        return mBatteryController.isReverseSupported();
    }

    @Override
    public Intent getLongClickIntent() {
        Intent intent = new Intent("android.settings.REVERSE_CHARGING_SETTINGS");
        intent.setPackage("com.android.settings");
        return intent;
    }

    @Override
    protected void handleClick(View view) {
        if (getState().state != 0) {
            mReverse = !mReverse;
            if (DEBUG) {
                Log.d("ReverseChargingTile", "handleClick(): rtx=" + (mReverse ? 1 : 0) + ",this=" + this);
            }
            mBatteryController.setReverseState(mReverse);
            showBottomSheetIfNecessary();
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.reverse_charging_title);
    }

    @Override
    protected void handleUpdateState(QSTile.BooleanState booleanState, Object obj) {
        String str;
        boolean isWirelessCharging = mBatteryController.isWirelessCharging();
        int i = 1;
        int i2 = mBatteryLevel <= mThresholdLevel ? 1 : 0;
        boolean z = mOverHeat;
        booleanState.value = !z && !mPowerSave && !isWirelessCharging && i2 == 0 && mReverse;
        if (z || mPowerSave || isWirelessCharging || i2 != 0) {
            i = 0;
        } else if (mReverse) {
            i = 2;
        }
        booleanState.state = i;
        booleanState.icon = mIcon;
        CharSequence tileLabel = getTileLabel();
        booleanState.label = tileLabel;
        booleanState.contentDescription = tileLabel;
        booleanState.expandedAccessibilityClassName = Switch.class.getName();
        if (mOverHeat) {
            str = mContext.getString(R.string.too_hot_label);
        } else if (mPowerSave) {
            str = mContext.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver);
        } else if (isWirelessCharging) {
            str = mContext.getString(R.string.wireless_charging_label);
        } else {
            str = i2 != 0 ? mContext.getString(R.string.low_battery_label) : null;
        }
        booleanState.secondaryLabel = str;
        if (DEBUG) {
            Log.d("ReverseChargingTile", "handleUpdateState(): ps=" + (mPowerSave ? 1 : 0) + ",wlc=" + (isWirelessCharging ? 1 : 0) + ",low=" + i2 + ",over=" + (mOverHeat ? 1 : 0) + ",rtx=" + (mReverse ? 1 : 0) + ",this=" + this);
        }
    }

    @Override
    public void onBatteryLevelChanged(int i, boolean z, boolean z2) {
        mBatteryLevel = i;
        mReverse = mBatteryController.isReverseOn();
        if (DEBUG) {
            Log.d("ReverseChargingTile", "onBatteryLevelChanged(): rtx=" + (mReverse ? 1 : 0) + ",level=" + mBatteryLevel + ",threshold=" + mThresholdLevel);
        }
        refreshState(null);
    }

    @Override
    public void onPowerSaveChanged(boolean z) {
        mPowerSave = z;
        refreshState(null);
    }

    @Override
    public void onReverseChanged(boolean z, int i, String str) {
        if (DEBUG) {
            Log.d("ReverseChargingTile", "onReverseChanged(): rtx=" + (z ? 1 : 0) + ",level=" + i + ",name=" + str + ",this=" + this);
        }
        mReverse = z;
        refreshState(null);
    }

    private void showBottomSheetIfNecessary() {
        if (!Prefs.getBoolean(mHost.getUserContext(), "HasSeenReverseBottomSheet", false)) {
            Intent intent = new Intent("android.settings.REVERSE_CHARGING_BOTTOM_SHEET");
            intent.setPackage("com.android.settings");
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
            Prefs.putBoolean(mHost.getUserContext(), "HasSeenReverseBottomSheet", true);
        }
    }

    private void updateThresholdLevel() {
        mThresholdLevel = Settings.Global.getInt(mContext.getContentResolver(), "advanced_battery_usage_amount", 2) * 5;
        if (DEBUG) {
            Log.d("ReverseChargingTile", "updateThresholdLevel(): rtx=" + (mReverse ? 1 : 0) + ",level=" + mBatteryLevel + ",threshold=" + mThresholdLevel);
        }
    }

    private boolean isOverHeat() {
        try {
            Temperature[] currentTemperaturesWithType = mThermalService.getCurrentTemperaturesWithType(3);
            for (Temperature temperature : currentTemperaturesWithType) {
                if (temperature.getStatus() >= 5) {
                    Log.w("ReverseChargingTile", "isOverHeat(): current skin status = " + temperature.getStatus() + ", temperature = " + temperature.getValue());
                    return true;
                }
            }
        } catch (RemoteException e) {
            Log.w("ReverseChargingTile", "isOverHeat(): " + e);
        }
        return false;
    }
}