/*
 * Copyright (C) 2017-2018 crDroid Android Project
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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.Color;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.crdroid.omnijaws.OmniJawsClient;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.tuner.TunerService;

public class StatusBarWeatherImageRight extends ImageView implements
        OmniJawsClient.OmniJawsObserver, TunerService.Tunable {

    private String TAG = StatusBarWeatherImageRight.class.getSimpleName();

    private Context mContext;

    private boolean mAttached;
    private Drawable mWeatherImage;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private int mStatusBarWeatherEnabled;
    private int mWeatherTempStyle;
    private int mWeatherImageColor;
    private int mTintColor = Color.WHITE;

    private static final String STATUS_BAR_SHOW_WEATHER_TEMP =
            "system:" + Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP;
    private static final String STATUS_BAR_WEATHER_TEMP_STYLE =
            "system:" + Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE;
    private static final String STATUS_BAR_WEATHER_IMAGE_COLOR =
            "system:" + Settings.System.STATUS_BAR_WEATHER_IMAGE_COLOR;
    private static final String OMNIJAWS_WEATHER_ICON_PACK =
            "system:" + Settings.System.OMNIJAWS_WEATHER_ICON_PACK;

    public StatusBarWeatherImageRight(Context context) {
        this(context, null);
    }

    public StatusBarWeatherImageRight(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeatherImageRight(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mWeatherClient = new OmniJawsClient(mContext);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached)
            return;

        mAttached = true;
        mWeatherClient.addObserver(this);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        Dependency.get(TunerService.class).addTunable(this,
                STATUS_BAR_SHOW_WEATHER_TEMP,
                STATUS_BAR_WEATHER_TEMP_STYLE,
                STATUS_BAR_WEATHER_IMAGE_COLOR,
                OMNIJAWS_WEATHER_ICON_PACK);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached)
            return;

        mAttached = false;
        Dependency.get(TunerService.class).removeTunable(this);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
    }

    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(area, this, tint);
        if (mWeatherImageColor == 0xFFFFFFFF && mWeatherImage != null &&
                mWeatherImage instanceof VectorDrawable)
            updateattributes();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
       switch (key) {
            case STATUS_BAR_SHOW_WEATHER_TEMP:
                mStatusBarWeatherEnabled =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                queryAndUpdateWeather();
                break;
            case STATUS_BAR_WEATHER_TEMP_STYLE:
                mWeatherTempStyle =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                updateWeather();
                break;
            case STATUS_BAR_WEATHER_IMAGE_COLOR:
                mWeatherImageColor =
                        newValue == null ? 0xFFFFFFFF : Integer.parseInt(newValue);
                updateattributes();
                break;
            case OMNIJAWS_WEATHER_ICON_PACK:
                updateWeather();
                break;
            default:
                break;
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (mWeatherData == null) {
            setImageDrawable(null);
            setVisibility(View.GONE);
        } else {
            updateattributes();
        }
    }

    private boolean allowVisibility() {
        if (mWeatherTempStyle == 0 || mStatusBarWeatherEnabled == 0 ||
                mStatusBarWeatherEnabled == 3 || mStatusBarWeatherEnabled == 4 ||
                !mWeatherClient.isOmniJawsEnabled()) {
            setImageDrawable(null);
            setVisibility(View.GONE);
            return false;
        }
        return true;
    }

    private void queryAndUpdateWeather() {
        try {
            mWeatherClient.queryWeather();
            mWeatherData = mWeatherClient.getWeatherInfo();
            // Weather data not available. Try later.
            if (mWeatherData == null) {
                return;
            }
            mWeatherImage = mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode);
            setImageDrawable(mWeatherImage);
            updateattributes();
            if (allowVisibility()) {
                setVisibility(View.VISIBLE);
            }
        } catch(Exception e) {
            // Do nothing
        }
    }

    private void updateWeather() {
        try {
            // Check for latest cached info
            if (mWeatherClient.getWeatherInfo() != null)
                mWeatherData = mWeatherClient.getWeatherInfo();
            if (mWeatherData == null) {
                // No weather data available. Try querying.
                queryAndUpdateWeather();
                return;
            }
            mWeatherImage = mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode);
            setImageDrawable(mWeatherImage);
            updateattributes();
            if (allowVisibility()) {
                setVisibility(View.VISIBLE);
            }
        } catch(Exception e) {
            // Do nothing
        }
    }

    private void updateattributes() {
        try {
            if (mWeatherImage != null && mWeatherImage instanceof VectorDrawable) {
                clearColorFilter();
                if (mWeatherImageColor == 0xFFFFFFFF) {
                    mWeatherImage.setTint(mTintColor);
                } else {
                    setColorFilter(mWeatherImageColor);
                }
            }
            if (mWeatherImage != null)
                setImageDrawable(mWeatherImage);
        } catch(Exception e) {
            // Do nothing
        }
    }
}
