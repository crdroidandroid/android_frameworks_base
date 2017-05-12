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

import android.content.Context;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.crdroid.omnijaws.OmniJawsClient;
import com.android.systemui.tuner.TunerService;

public class StatusBarWeatherImage extends ImageView implements
        OmniJawsClient.OmniJawsObserver, TunerService.Tunable {

    private String TAG = StatusBarWeatherImage.class.getSimpleName();

    private Context mContext;

    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private int mStatusBarWeatherEnabled;
    private int mWeatherTempStyle;
    private int mWeatherImageColor;

    private static final String STATUS_BAR_SHOW_WEATHER_TEMP =
            "system:" + Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP;
    private static final String STATUS_BAR_WEATHER_TEMP_STYLE =
            "system:" + Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE;
    private static final String STATUS_BAR_WEATHER_IMAGE_COLOR =
            "system:" + Settings.System.STATUS_BAR_WEATHER_IMAGE_COLOR;
    private static final String OMNIJAWS_WEATHER_ICON_PACK =
            "system:" + Settings.System.OMNIJAWS_WEATHER_ICON_PACK;

    public StatusBarWeatherImage(Context context) {
        this(context, null);
    }

    public StatusBarWeatherImage(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeatherImage(Context context, AttributeSet attrs, int defStyle) {
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
        mWeatherClient.addObserver(this);
        TunerService.get(mContext).addTunable(this,
                STATUS_BAR_SHOW_WEATHER_TEMP,
                STATUS_BAR_WEATHER_TEMP_STYLE,
                STATUS_BAR_WEATHER_IMAGE_COLOR,
                OMNIJAWS_WEATHER_ICON_PACK);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        TunerService.get(mContext).removeTunable(this);
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
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
                queryAndUpdateWeather();
                break;
            case STATUS_BAR_WEATHER_IMAGE_COLOR:
                mWeatherImageColor =
                        newValue == null ? 0xFFFFFFFF : Integer.parseInt(newValue);
                updateattributes();
                break;
            case OMNIJAWS_WEATHER_ICON_PACK:
                queryAndUpdateWeather();
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
            setVisibility(View.GONE);
        }
    }

    private void queryAndUpdateWeather() {
        if (mWeatherTempStyle == 0 || mStatusBarWeatherEnabled == 0 ||
                mStatusBarWeatherEnabled == 3 || mStatusBarWeatherEnabled == 4) {
            setVisibility(View.GONE);
            return;
        }

        if (!mWeatherClient.isOmniJawsEnabled())
            mWeatherClient.setOmniJawsEnabled(true);

        updateattributes();
        try {
            //setImageDrawable(mWeatherClient.getDefaultWeatherConditionImage());
            mWeatherClient.queryWeather();
            mWeatherData = mWeatherClient.getWeatherInfo();
            if (mWeatherData == null) {
                setVisibility(View.GONE);
                return;
            }
            setImageDrawable(mWeatherClient.getWeatherConditionImage(
                  mWeatherData.conditionCode));
            setVisibility(View.VISIBLE);
        } catch(Exception e) {
            // Do nothing
        }
    }

    public void updateattributes() {
        try {
            if (mWeatherImageColor != 0xFFFFFFFF) {
                setColorFilter(mWeatherImageColor);
            } else {
                clearColorFilter();
            }
        } catch(Exception e) {
            // Do nothing
        }
    }
}
