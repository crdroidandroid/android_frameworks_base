package com.android.systemui.power;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.settingslib.utils.PowerUtil;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.power.EnhancedEstimates;

import java.time.Duration;
import javax.inject.Inject;

@SysUISingleton
public class EnhancedEstimatesImpl implements EnhancedEstimates {

    public static final String TAG = "EnhancedEstimates";

    private Context mContext;
    private final KeyValueListParser mParser;

    @Inject
    public EnhancedEstimatesImpl(Context context) {
        mContext = context;
        mParser = new KeyValueListParser(',');
    }

    @Override
    public boolean isHybridNotificationEnabled() {
        try {
            if (!mContext.getPackageManager().getPackageInfo(
                        "com.google.android.apps.turbo",
                        PackageManager.MATCH_DISABLED_COMPONENTS).applicationInfo.enabled) {
                return false;
            }
            updateFlags();
            return mParser.getBoolean("hybrid_enabled", true);
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    @Override
    public Estimate getEstimate() {
        Uri build = new Uri.Builder()
                            .scheme("content")
                            .authority("com.google.android.apps.turbo.estimated_time_remaining")
                            .appendPath("time_remaining").build();
        try {
            Cursor query = mContext.getContentResolver().query(build, null, null, null, null);
            if (query != null) {
                try {
                    if (query.moveToFirst()) {
                        long timeRemaining = -1L;
                        boolean isBasedOnUsage = true;
                        if (query.getColumnIndex("is_based_on_usage") != -1 &&
                                query.getInt(query.getColumnIndex("is_based_on_usage")) == 0) {
                            isBasedOnUsage = false;
                        }
                        int columnIndex = query.getColumnIndex("average_battery_life");
                        if (columnIndex != -1) {
                            long averageBatteryLife = query.getLong(columnIndex);
                            if (averageBatteryLife != -1L) {
                                long duration = Duration.ofMinutes(15L).toMillis();
                                if (Duration.ofMillis(averageBatteryLife)
                                        .compareTo(Duration.ofDays(1L)) >= 0) {
                                    duration = Duration.ofHours(1L).toMillis();
                                }
                                timeRemaining = PowerUtil.roundTimeToNearestThreshold(averageBatteryLife, duration);
                            }
                        }
                        Estimate estimate = new Estimate(query.getLong(query.getColumnIndex(
                                "battery_estimate")), isBasedOnUsage, timeRemaining);
                        query.close();
                        return estimate;
                    }
                } catch (Exception ex) {
                    // Catch and release
                }
            }
            if (query != null) {
                query.close();
            }
        } catch (Exception exception) {
            Log.d(TAG, "Something went wrong when getting an estimate from Turbo", exception);
        }
        return new Estimate(-1L, false, -1L);
    }

    @Override
    public long getLowWarningThreshold() {
        updateFlags();
        return mParser.getLong("low_threshold", Duration.ofHours(3L).toMillis());
    }

    @Override
    public long getSevereWarningThreshold() {
        updateFlags();
        return mParser.getLong("severe_threshold", Duration.ofHours(1L).toMillis());
    }

    @Override
    public boolean getLowWarningEnabled() {
        updateFlags();
        return mParser.getBoolean("low_warning_enabled", false);
    }

    protected void updateFlags() {
        String string = Settings.Global.getString(
                mContext.getContentResolver(), "hybrid_sysui_battery_warning_flags");
        try {
            mParser.setString(string);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Bad hybrid sysui warning flags");
        }
    }
}
