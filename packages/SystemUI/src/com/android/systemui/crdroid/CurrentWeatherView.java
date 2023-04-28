/*
 * Copyright (C) 2023 crDroid Android Project
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
import com.android.systemui.R;

public class CurrentWeatherView extends FrameLayout implements OmniJawsClient.OmniJawsObserver {

    static final String TAG = "SystemUI:CurrentWeatherView";

    private ImageView mCurrentImage;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private TextView mLeftText;
    private TextView mRightText;

    private SettingsObserver mSettingsObserver;

    private boolean mShowWeatherLocation;

    public CurrentWeatherView(Context context) {
        this(context, null);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CurrentWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
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
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
            mSettingsObserver.observe();
        }
    }

    private void setErrorView() {
        mCurrentImage.setImageDrawable(null);
        mLeftText.setText("");
        mRightText.setText("");
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
                Drawable d = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);
                mCurrentImage.setImageDrawable(d);
                mRightText.setText(mWeatherInfo.temp + " " + mWeatherInfo.tempUnits);
                mLeftText.setText(mWeatherInfo.city);
                mLeftText.setVisibility(mShowWeatherLocation ? View.VISIBLE : View.GONE);
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
            getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCKSCREEN_WEATHER_LOCATION), false, this,
                    UserHandle.USER_ALL);
            updateWeatherSettings();
        }

        void unobserve() {
            getContext().getContentResolver().unregisterContentObserver(this);
        }

        void updateWeatherSettings() {
            mShowWeatherLocation = Settings.System.getIntForUser(getContext().getContentResolver(),
                    Settings.System.LOCKSCREEN_WEATHER_LOCATION,
                    0, UserHandle.USER_CURRENT) != 0;
            mLeftText.setVisibility(mShowWeatherLocation ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateWeatherSettings();
        }
    }
}
