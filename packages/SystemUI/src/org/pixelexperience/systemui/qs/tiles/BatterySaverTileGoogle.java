package org.pixelexperience.systemui.qs.tiles;

import android.os.Handler;
import android.os.Looper;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.R;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tiles.BatterySaverTile;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

public class BatterySaverTileGoogle extends BatterySaverTile {
    private boolean mExtreme;

    @Inject
    public BatterySaverTileGoogle(QSHost qSHost, @Background Looper looper, @Main Handler handler, FalsingManager falsingManager, MetricsLogger metricsLogger, StatusBarStateController statusBarStateController, ActivityStarter activityStarter, QSLogger qSLogger, BatteryController batteryController, SecureSettings secureSettings) {
        super(qSHost, looper, handler, falsingManager, metricsLogger, statusBarStateController, activityStarter, qSLogger, batteryController, secureSettings);
    }

    @Override
    public void handleUpdateState(QSTile.BooleanState booleanState, Object arg) {
        super.handleUpdateState(booleanState, arg);
        if (booleanState.state != 2 || !mExtreme) {
            booleanState.secondaryLabel = "";
        } else {
            booleanState.secondaryLabel = mContext.getString(R.string.extreme_battery_saver_text);
        }
        booleanState.stateDescription = booleanState.secondaryLabel;
    }

    @Override
    public void onExtremeBatterySaverChanged(boolean isExtreme) {
        if (mExtreme != isExtreme) {
            mExtreme = isExtreme;
            refreshState();
        }
    }
}