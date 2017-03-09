/*
 * Copyright (C) 2017 AICP
 *           (C) 2017 crDroid Android Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.crdroid.statusbarweather;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.crdroid.DetailedWeatherView;
import com.android.systemui.crdroid.OmniJawsClient;

public class StatusBarWeatherImageLeft extends ImageView implements
        OmniJawsClient.OmniJawsObserver {

    private String TAG = StatusBarWeatherImageLeft.class.getSimpleName();

    private static final boolean DEBUG = false;

    private Context mContext;

    private int mStatusBarWeatherEnabled;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mEnabled;
    private int mWeatherTempStyle;
    private int mWeatherImageColor;

    Handler mHandler;

    public StatusBarWeatherImageLeft(Context context) {
        this(context, null);
    }

    public StatusBarWeatherImageLeft(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeatherImageLeft(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mHandler = new Handler();
        mWeatherClient = new OmniJawsClient(mContext);
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        mWeatherClient.addObserver(this);
        queryAndUpdateWeather();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_WEATHER_IMAGE_COLOR),
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        queryAndUpdateWeather();
    }

    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mStatusBarWeatherEnabled = Settings.System.getIntForUser(
                resolver, Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT);
        mWeatherTempStyle = Settings.System.getIntForUser(mContext.getContentResolver(), 
                Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE, 0,
                UserHandle.USER_CURRENT);
        mWeatherImageColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_WEATHER_IMAGE_COLOR, 0xFFFFFFFF, UserHandle.USER_CURRENT);
        if(mWeatherTempStyle == 0) {
            setVisibility(View.GONE);
            return;
        }
        if (mStatusBarWeatherEnabled == 1
                || mStatusBarWeatherEnabled == 2
                || mStatusBarWeatherEnabled == 5) {
            mWeatherClient.setOmniJawsEnabled(true);
            queryAndUpdateWeather();
        } else {
            setVisibility(View.GONE);
        }
    }

    private void queryAndUpdateWeather() {
        try {
            if (DEBUG) Log.d(TAG, "queryAndUpdateWeather " + mEnabled);
            setImageDrawable(mWeatherClient.getDefaultWeatherConditionImage());
            if (mEnabled) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                if (mWeatherData != null) {
                    if (mStatusBarWeatherEnabled == 1
                            || mStatusBarWeatherEnabled == 2
                            || mStatusBarWeatherEnabled == 5) {
                        setImageDrawable(mWeatherClient.getWeatherConditionImage(
                                mWeatherData.conditionCode));
                        setVisibility(View.VISIBLE);
                        if(mWeatherImageColor != 0xFFFFFFFF) {
                           setColorFilter(mWeatherImageColor);
                        } else {
                          clearColorFilter();
                        }
                    }
                } else {
                    setVisibility(View.GONE);
                }
            } else {
                setVisibility(View.GONE);
            }
        } catch(Exception e) {
            // Do nothing
        }
       if(mWeatherTempStyle == 0) {
          setVisibility(View.GONE);
       }
    }
}
