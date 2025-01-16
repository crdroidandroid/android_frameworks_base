/*
 * Copyright (C) 2023-2024 crDroid Android Project
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
package com.android.systemui.crdroid;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.util.crdroid.OmniJawsClient;
import com.android.systemui.res.R;

public class CurrentWeatherView extends FrameLayout implements OmniJawsClient.OmniJawsObserver {

    static final String TAG = "SystemUI:CurrentWeatherView";

    private ImageView mCurrentImage;
    private ImageView mWindInfoImage;
    private ImageView mPinwheelImage;
    private ImageView mHumidityInfoImage;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private TextView mLeftText;
    private TextView mRightText;
    private TextView mWeatherText;
    private TextView mWeatherWindSpeedInfo;
    private TextView mWeatherWindDirectionInfo;
    private TextView mWeatherHumidityInfo;

    private SettingsObserver mSettingsObserver;

    private boolean mShowWeatherLocation;
    private boolean mShowWeatherText;
    private boolean mShowWindInfo;
    private boolean mShowHumidityInfo;

    private Context mContext;

    public CurrentWeatherView(Context context) {
        this(context, null);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        if (mWeatherClient == null) {
            mWeatherClient = new OmniJawsClient(context);
        }
    }

    public void enableUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.addObserver(this);
            queryAndUpdateWeather();
        }
    }

    public void disableUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(this);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCurrentImage  = (ImageView) findViewById(R.id.current_image);
        mLeftText = (TextView) findViewById(R.id.left_text);
        mRightText = (TextView) findViewById(R.id.right_text);
        mWeatherText = (TextView) findViewById(R.id.weather_text);
        mWindInfoImage  = (ImageView) findViewById(R.id.wind_info_image);
        mWeatherWindSpeedInfo = (TextView) findViewById(R.id.weather_wind_speed_info);
        mPinwheelImage  = (ImageView) findViewById(R.id.pinwheel_image);
        mWeatherWindDirectionInfo = (TextView) findViewById(R.id.weather_wind_direction_info);
        mHumidityInfoImage = (ImageView) findViewById(R.id.humidity_info_image);
        mWeatherHumidityInfo = (TextView) findViewById(R.id.weather_humidity_info);
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
            mSettingsObserver.observe();
        }
    }

    private void setErrorView() {
        mCurrentImage.setImageDrawable(null);
        mLeftText.setText("");
        mRightText.setText("");
        mWeatherText.setText("");
        mWindInfoImage.setImageDrawable(null);
        mWeatherWindSpeedInfo.setText("");
        mPinwheelImage.setImageDrawable(null);
        mWeatherWindDirectionInfo.setText("");
        mHumidityInfoImage.setImageDrawable(null);
        mWeatherHumidityInfo.setText("");
    }

    @Override
    public void weatherError(int errorReason) {
        // since this is shown in ambient and lock screen
        // it would look bad to show every error since the
        // screen-on revovery of the service had no chance
        // to run fast enough
        // so only show the disabled state
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            setErrorView();
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void updateSettings() {
        queryAndUpdateWeather();
    }

    private void queryAndUpdateWeather() {
        try {
            if (mWeatherClient == null || !mWeatherClient.isOmniJawsEnabled()) {
                return;
            }
            mWeatherClient.queryWeather();
            mWeatherInfo = mWeatherClient.getWeatherInfo();
            if (mWeatherInfo != null) {
                String formattedCondition = mWeatherInfo.condition;
                if (formattedCondition.toLowerCase().contains("clouds")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_clouds);
                } else if (formattedCondition.toLowerCase().contains("rain")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_rain);
                } else if (formattedCondition.toLowerCase().contains("clear")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_clear);
                } else if (formattedCondition.toLowerCase().contains("storm")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_storm);
                } else if (formattedCondition.toLowerCase().contains("snow")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_snow);
                } else if (formattedCondition.toLowerCase().contains("wind")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_wind);
                } else if (formattedCondition.toLowerCase().contains("mist")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_mist);
                }
                Drawable d = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);
                mCurrentImage.setImageDrawable(d);
                Drawable windImage = mWeatherClient.getResOmni("ic_wind_symbol");
                mWindInfoImage.setImageDrawable(windImage);
                mWindInfoImage.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
                Drawable pinWheel = mWeatherClient.getResOmni("ic_wind_direction_symbol");
                mPinwheelImage.setImageDrawable(pinWheel);
                mPinwheelImage.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
                Drawable humidityImage = mWeatherClient.getResOmni("ic_humidity_symbol");
                mHumidityInfoImage.setImageDrawable(humidityImage);
                mHumidityInfoImage.setVisibility(mShowHumidityInfo ? View.VISIBLE : View.GONE);
                mRightText.setText(mWeatherInfo.temp + " " + mWeatherInfo.tempUnits);
                mLeftText.setText(mWeatherInfo.city);
                mLeftText.setVisibility(mShowWeatherLocation ? View.VISIBLE : View.GONE);
                mWeatherText.setText(" · "  + formattedCondition);
                mWeatherText.setVisibility(mShowWeatherText ? View.VISIBLE : View.GONE);
                mWeatherWindSpeedInfo.setText(" " + mWeatherInfo.windSpeed + " " + mWeatherInfo.windUnits + " ");
                mWeatherWindSpeedInfo.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
                mWeatherWindDirectionInfo.setText(" " + mWeatherInfo.pinWheel + " ");
                mWeatherWindDirectionInfo.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
                mWeatherHumidityInfo.setText(mWeatherInfo.humidity);
                mWeatherHumidityInfo.setVisibility(mShowHumidityInfo ? View.VISIBLE : View.GONE);
            }
        } catch(Exception e) {
            // Do nothing
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_LOCATION), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_TEXT), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_WIND_INFO), false, this,
                    UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_HUMIDITY_INFO), false, this,
                    UserHandle.USER_ALL);
            updateWeatherSettings();
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        void updateWeatherSettings() {
            mShowWeatherLocation = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_LOCATION,
                    0, UserHandle.USER_CURRENT) != 0;
            mShowWeatherText = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_TEXT,
                    1, UserHandle.USER_CURRENT) != 0;
            mShowWindInfo = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_WIND_INFO,
                    1, UserHandle.USER_CURRENT) != 0;
            mShowHumidityInfo = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_HUMIDITY_INFO,
                    1, UserHandle.USER_CURRENT) != 0;
            mLeftText.setVisibility(mShowWeatherLocation ? View.VISIBLE : View.GONE);
            mWeatherText.setVisibility(mShowWeatherText ? View.VISIBLE : View.GONE);
            mWindInfoImage.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
            mWeatherWindSpeedInfo.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
            mPinwheelImage.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
            mWeatherWindDirectionInfo.setVisibility(mShowWindInfo ? View.VISIBLE : View.GONE);
            mHumidityInfoImage.setVisibility(mShowHumidityInfo ? View.VISIBLE : View.GONE);
            mWeatherHumidityInfo.setVisibility(mShowHumidityInfo ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateWeatherSettings();
        }
    }
}
