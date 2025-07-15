/*
 * Copyright (C) 2021 The OmniROM project
 * Copyright (C) 2022-2025 crDroid Android project
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

package com.android.internal.util.crdroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class OmniJawsClient {

    private static final String TAG = "OmniJawsClient";
    private static final boolean DEBUG = false;

    public static final String SERVICE_PACKAGE = "org.omnirom.omnijaws";
    public static final Uri WEATHER_URI = Uri.parse("content://org.omnirom.omnijaws.provider/weather");
    public static final Uri SETTINGS_URI = Uri.parse("content://org.omnirom.omnijaws.provider/settings");
    public static final String WEATHER_UPDATE = SERVICE_PACKAGE + ".WEATHER_UPDATE";
    public static final String WEATHER_ERROR = SERVICE_PACKAGE + ".WEATHER_ERROR";

    private static final String ICON_PACKAGE_DEFAULT = "org.omnirom.omnijaws";
    private static final String ICON_PREFIX_DEFAULT = "google_new_light";
    private static final String EXTRA_ERROR = "error";
    public static final int EXTRA_ERROR_NETWORK = 0;
    public static final int EXTRA_ERROR_LOCATION = 1;
    public static final int EXTRA_ERROR_DISABLED = 2;

    public static final String[] WEATHER_PROJECTION = {
            "city", "wind_speed", "wind_direction", "condition_code", "temperature",
            "humidity", "condition", "forecast_low", "forecast_high", "forecast_condition",
            "forecast_condition_code", "time_stamp", "forecast_date", "pin_wheel"
    };

    public static final String[] SETTINGS_PROJECTION = {
            "enabled", "units", "provider", "setup", "icon_pack"
    };

    private static final DecimalFormat sNoDigitsFormat = new DecimalFormat("0");

    private static OmniJawsClient sInstance;

    private WeatherInfo mCachedInfo;
    private Resources mRes;
    private String mPackageName;
    private String mIconPrefix;
    private String mSettingIconPackage;
    private boolean mMetric;

    private final List<WeakReference<OmniJawsObserver>> mObservers = new ArrayList<>();
    private WeatherUpdateReceiver mReceiver;
    private boolean mWeatherReceiverRegistered = false;

    public static OmniJawsClient get() {
        if (sInstance == null) {
            synchronized (OmniJawsClient.class) {
                if (sInstance == null) {
                    sInstance = new OmniJawsClient();
                }
            }
        }
        return sInstance;
    }

    public interface OmniJawsObserver {
        void weatherUpdated();
        void weatherError(int errorReason);
        default void updateSettings() {}
    }

    private class WeatherUpdateReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            pruneDeadObservers();
            for (WeakReference<OmniJawsObserver> ref : mObservers) {
                OmniJawsObserver obs = ref.get();
                if (obs == null) continue;
                if (WEATHER_UPDATE.equals(action)) {
                    obs.weatherUpdated();
                } else if (WEATHER_ERROR.equals(action)) {
                    obs.weatherError(intent.getIntExtra(EXTRA_ERROR, 0));
                }
            }
        }
    }

    public Intent getSettingsIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .setClassName(SERVICE_PACKAGE, SERVICE_PACKAGE + ".SettingsActivity");
    }

    public Intent getWeatherActivityIntent(Context context) {
        if (isOmniJawsEnabled(context)) {
            return new Intent(Intent.ACTION_MAIN)
                    .setClassName(SERVICE_PACKAGE, SERVICE_PACKAGE + ".WeatherActivity");
        }
        return getSettingsIntent();
    }

    public WeatherInfo getWeatherInfo() {
        return mCachedInfo;
    }

    public void queryWeather(Context context) {
        if (!isOmniJawsEnabled(context)) {
            Log.w(TAG, "queryWeather while disabled");
            mCachedInfo = null;
            return;
        }

        try (Cursor weatherCursor = context.getContentResolver().query(
                WEATHER_URI, WEATHER_PROJECTION, null, null, null)) {

            if (weatherCursor != null && weatherCursor.getCount() > 0) {
                mCachedInfo = new WeatherInfo();
                List<DayForecast> forecasts = new ArrayList<>();

                for (int i = 0; i < weatherCursor.getCount(); i++) {
                    weatherCursor.moveToPosition(i);
                    if (i == 0) {
                        mCachedInfo.city = weatherCursor.getString(0);
                        mCachedInfo.windSpeed = getFormattedValue(weatherCursor.getFloat(1));
                        mCachedInfo.windDirection = weatherCursor.getInt(2) + "\u00b0";
                        mCachedInfo.conditionCode = weatherCursor.getInt(3);
                        mCachedInfo.temp = getFormattedValue(weatherCursor.getFloat(4));
                        mCachedInfo.humidity = weatherCursor.getString(5);
                        mCachedInfo.condition = weatherCursor.getString(6);
                        mCachedInfo.timeStamp = Long.parseLong(weatherCursor.getString(11));
                        mCachedInfo.pinWheel = weatherCursor.getString(13);
                    } else {
                        DayForecast day = new DayForecast();
                        day.low = getFormattedValue(weatherCursor.getFloat(7));
                        day.high = getFormattedValue(weatherCursor.getFloat(8));
                        day.condition = weatherCursor.getString(9);
                        day.conditionCode = weatherCursor.getInt(10);
                        day.date = weatherCursor.getString(12);
                        forecasts.add(day);
                    }
                }
                mCachedInfo.forecasts = forecasts;
            }
        } catch (Exception e) {
            Log.e(TAG, "queryWeather: weather", e);
        }

        try (Cursor settingsCursor = context.getContentResolver().query(
                SETTINGS_URI, SETTINGS_PROJECTION, null, null, null)) {

            if (settingsCursor != null && settingsCursor.moveToFirst()) {
                mMetric = settingsCursor.getInt(1) == 0;
                if (mCachedInfo != null) {
                    mCachedInfo.tempUnits = getTemperatureUnit();
                    mCachedInfo.windUnits = getWindUnit();
                    mCachedInfo.provider = settingsCursor.getString(2);
                    mCachedInfo.iconPack = settingsCursor.getString(4);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "queryWeather: settings", e);
        }

        updateSettings(context);
    }

    private void updateSettings(Context context) {
        String iconPack = (mCachedInfo != null) ? mCachedInfo.iconPack : null;
        if (iconPack == null || TextUtils.isEmpty(iconPack)) {
            loadDefaultIconsPackage(context);
        } else if (!iconPack.equals(mSettingIconPackage)) {
            mSettingIconPackage = iconPack;
            loadCustomIconPackage(context);
        }
    }

    private void loadDefaultIconsPackage(Context context) {
        mPackageName = ICON_PACKAGE_DEFAULT;
        mIconPrefix = ICON_PREFIX_DEFAULT;
        mSettingIconPackage = mPackageName + "." + mIconPrefix;
        try {
            mRes = context.getPackageManager().getResourcesForApplication(mPackageName);
        } catch (Exception e) {
            Log.w(TAG, "No default icon package found");
            mRes = null;
        }
    }

    private void loadCustomIconPackage(Context context) {
        int idx = mSettingIconPackage.lastIndexOf(".");
        if (idx == -1) {
            loadDefaultIconsPackage(context);
            return;
        }
        mPackageName = mSettingIconPackage.substring(0, idx);
        mIconPrefix = mSettingIconPackage.substring(idx + 1);
        try {
            mRes = context.getPackageManager().getResourcesForApplication(mPackageName);
        } catch (Exception e) {
            Log.w(TAG, "Icon pack loading failed, fallback to default");
            loadDefaultIconsPackage(context);
        }
    }

    private static String getFormattedValue(float value) {
        if (Float.isNaN(value)) return "-";
        String result = sNoDigitsFormat.format(value);
        return result.equals("-0") ? "0" : result;
    }

    public boolean isOmniJawsServiceInstalled(Context context) {
        return isAvailableApp(context, SERVICE_PACKAGE);
    }

    public boolean isOmniJawsEnabled(Context context) {
        if (!isOmniJawsServiceInstalled(context)) return false;

        try (Cursor c = context.getContentResolver().query(
                SETTINGS_URI, SETTINGS_PROJECTION, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 1;
        } catch (Exception e) {
            Log.e(TAG, "isOmniJawsEnabled", e);
            return false;
        }
    }

    private boolean isAvailableApp(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            int state = pm.getApplicationEnabledSetting(pkg);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    public void addObserver(Context context, OmniJawsObserver observer) {
        if (observer == null) return;
        removeObserver(context, observer);
        mObservers.add(new WeakReference<>(observer));
        registerReceiverIfNeeded(context);
    }

    public void removeObserver(Context context, OmniJawsObserver observer) {
        if (observer == null) return;
        Iterator<WeakReference<OmniJawsObserver>> it = mObservers.iterator();
        while (it.hasNext()) {
            OmniJawsObserver o = it.next().get();
            if (o == null || o == observer) {
                it.remove();
            }
        }
        if (mObservers.isEmpty()) {
            unregisterReceiver(context);
        }
    }

    private void pruneDeadObservers() {
        try {
            mObservers.removeIf(ref -> ref.get() == null);
        } catch (Exception e) {
            Log.w(TAG, "Exception occured while pruning, ignoring");
        }
    }

    private void registerReceiverIfNeeded(Context context) {
        if (!mWeatherReceiverRegistered && !mObservers.isEmpty()) {
            if (mReceiver != null) {
                try {
                    context.unregisterReceiver(mReceiver);
                } catch (Exception ignored) {}
            }
            mReceiver = new WeatherUpdateReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(WEATHER_UPDATE);
            filter.addAction(WEATHER_ERROR);
            context.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
            mWeatherReceiverRegistered = true;
        }
    }

    private void unregisterReceiver(Context context) {
        if (mWeatherReceiverRegistered && mReceiver != null) {
            try {
                context.unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
            mWeatherReceiverRegistered = false;
        }
    }

    private String getTemperatureUnit() {
        return mMetric ? "\u00b0C" : "\u00b0F";
    }

    private String getWindUnit() {
        return mMetric ? "km/h" : "mph";
    }

    public Drawable getWeatherConditionImage(Context context, int conditionCode) {
        if (mRes == null) {
            loadDefaultIconsPackage(context);
        }
        try {
            int resId = mRes.getIdentifier(mIconPrefix + "_" + conditionCode, "drawable", mPackageName);
            Drawable d = mRes.getDrawable(resId, null);
            return d != null ? d : getDefaultConditionImage(context);
        } catch (Exception e) {
            Log.e(TAG, "getWeatherConditionImage", e);
            return getDefaultConditionImage(context);
        }
    }

    private Drawable getDefaultConditionImage(Context context) {
        try {
            Resources res = context.getPackageManager().getResourcesForApplication(ICON_PACKAGE_DEFAULT);
            int resId = res.getIdentifier(ICON_PREFIX_DEFAULT + "_na", "drawable", ICON_PACKAGE_DEFAULT);
            Drawable d = res.getDrawable(resId, null);
            return d != null ? d : new ColorDrawable(Color.RED);
        } catch (Exception e) {
            return new ColorDrawable(Color.RED);
        }
    }

    public Drawable getResOmni(Context context, String iconOmni) {
        if (mRes == null) loadDefaultIconsPackage(context);
        try {
            int resId = mRes.getIdentifier(iconOmni, "drawable", mPackageName);
            Drawable d = mRes.getDrawable(resId, null);
            return d != null ? d : new ColorDrawable(Color.RED);
        } catch (Exception e) {
            Log.e(TAG, "getResOmni", e);
            return new ColorDrawable(Color.RED);
        }
    }

    public static class WeatherInfo {
        public String city;
        public String windSpeed;
        public String windDirection;
        public int conditionCode;
        public String temp;
        public String humidity;
        public String condition;
        public Long timeStamp;
        public List<DayForecast> forecasts;
        public String tempUnits;
        public String windUnits;
        public String provider;
        public String pinWheel;
        public String iconPack;

        public String getLastUpdateTime() {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(timeStamp));
        }

        @Override
        public String toString() {
            return city + " @ " + new Date(timeStamp) + " | " + condition + " | " + temp;
        }
    }

    public static class DayForecast {
        public String low;
        public String high;
        public int conditionCode;
        public String condition;
        public String date;

        @Override
        public String toString() {
            return "[" + date + " - " + low + "/" + high + " - " + condition + "]";
        }
    }
}
