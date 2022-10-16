/*
 * Copyright (C) 2017 The OmniROM project
 * Copyright (C) 2022-2023 crDroid Android project
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

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.crdroid.OmniJawsClient;
import com.android.internal.util.crdroid.Utils;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

public class WeatherTile extends QSTileImpl<BooleanState> implements OmniJawsClient.OmniJawsObserver {

    public static final String TILE_SPEC = "weather";

    private static final String TAG = "WeatherTile";
    private static final boolean DEBUG = false;
    private OmniJawsClient mWeatherClient;
    private Drawable mWeatherImage;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mEnabled;
    private final ActivityStarter mActivityStarter;
    private String mFormattedCondition; 

    private static final String[] ALTERNATIVE_WEATHER_APPS = {
            "cz.martykan.forecastie",
            "com.accuweather.android",
            "com.wunderground.android.weather",
            "com.samruston.weather",
            "jp.miyavi.androiod.gnws",
    };

    @Inject
    public WeatherTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mWeatherClient = new OmniJawsClient(mContext);
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        mActivityStarter = activityStarter;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mWeatherClient == null) {
            return;
        }
        if (DEBUG) Log.d(TAG, "setListening " + listening);
        mEnabled = mWeatherClient.isOmniJawsEnabled();

        if (listening) {
            mWeatherClient.addObserver(this);
            queryAndUpdateWeather();
        } else {
            mWeatherClient.removeObserver(this);
        }
    }

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
        if (errorReason != OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherData = null;
            refreshState();
        }
    }

    @Override
    protected void handleDestroy() {
        // make sure we dont left one
        mWeatherClient.removeObserver(this);
        super.handleDestroy();
    }

    @Override
    public boolean isAvailable() {
        return mWeatherClient.isOmniJawsServiceInstalled();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (DEBUG) Log.d(TAG, "handleClick");
        if (!mState.value || mWeatherData == null) {
            mActivityStarter.postStartActivityDismissingKeyguard(mWeatherClient.getSettingsIntent(), 0);
        } else {
            PackageManager pm = mContext.getPackageManager();
            for (String app: ALTERNATIVE_WEATHER_APPS) {
                if (Utils.isPackageInstalled(mContext, app)) {
                    Intent intent = pm.getLaunchIntentForPackage(app);
                    if (intent != null) {
                        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
                    }
                }
            }
            if (Utils.isPackageInstalled(mContext, "com.google.android.googlequicksearchbox")) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("dynact://velour/weather/ProxyActivity"));
                intent.setComponent(new ComponentName("com.google.android.googlequicksearchbox",
                        "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"));
                mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
            }
        }
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        if (DEBUG) Log.d(TAG, "getLongClickIntent");
        return mWeatherClient.getSettingsIntent();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (DEBUG) Log.d(TAG, "handleUpdateState " + mEnabled);
        state.value = mEnabled;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = ResourceIcon.get(R.drawable.ic_qs_weather);
        state.label = mContext.getResources().getString(R.string.omnijaws_label_default);
        state.secondaryLabel = mContext.getResources().getString(R.string.omnijaws_service_unknown);
        if (mEnabled) {
            if (mWeatherData == null || mWeatherImage == null) {
                state.label = mContext.getResources().getString(R.string.omnijaws_label_default);
                state.secondaryLabel = mContext.getResources().getString(R.string.omnijaws_service_error);
            } else {
                state.icon = new DrawableIcon(mWeatherImage);
                state.label = mWeatherData.city;
                state.secondaryLabel = mWeatherData.temp + mWeatherData.tempUnits +
                    " · " + mFormattedCondition;
            }
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getResources().getString(R.string.omnijaws_label_default);
    }

    private void queryAndUpdateWeather() {
        if (DEBUG) Log.d(TAG, "queryAndUpdateWeather " + mEnabled);
        try {
            mWeatherData = null;
            if (mEnabled) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                mFormattedCondition = mWeatherData.condition;
                if (mFormattedCondition.toLowerCase().contains("clouds")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_clouds);
                } else if (mFormattedCondition.toLowerCase().contains("rain")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_rain);
                } else if (mFormattedCondition.toLowerCase().contains("clear")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_clear);
                } else if (mFormattedCondition.toLowerCase().contains("storm")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_storm);
                } else if (mFormattedCondition.toLowerCase().contains("snow")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_snow);
                } else if (mFormattedCondition.toLowerCase().contains("wind")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_wind);
                } else if (mFormattedCondition.toLowerCase().contains("mist")) {
                    mFormattedCondition = mContext.getResources().getString(R.string.weather_condition_mist);
                }
                if (mWeatherData != null) {
                    mWeatherImage = mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode);
                    mWeatherImage = mWeatherImage.mutate();
                }
            }
        } catch(Exception e) {
            // Do nothing
        }
        refreshState();
    }
}
