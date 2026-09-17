/*
 * Copyright (C) 2021 The OmniROM project
 * Copyright (C) 2022-2026 crDroid Android project
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class OmniJawsClient {

    private static final String TAG = "OmniJawsClient";
    private static final boolean DEBUG = false;

    public static final String SERVICE_PACKAGE = "org.omnirom.omnijaws";
    public static final Uri WEATHER_URI = Uri.parse("content://org.omnirom.omnijaws.provider/weather");
    public static final Uri SETTINGS_URI = Uri.parse("content://org.omnirom.omnijaws.provider/settings");
    public static final Uri HOURLY_URI = Uri.parse("content://org.omnirom.omnijaws.provider/hourly");
    public static final String WEATHER_UPDATE = SERVICE_PACKAGE + ".WEATHER_UPDATE";
    public static final String WEATHER_ERROR = SERVICE_PACKAGE + ".WEATHER_ERROR";

    private static final String ICON_PACKAGE_DEFAULT = "org.omnirom.omnijaws";
    private static final String ICON_PREFIX_DEFAULT = "google_new";
    private static final String EXTRA_ERROR = "error";
    public static final int EXTRA_ERROR_NETWORK = 0;
    public static final int EXTRA_ERROR_LOCATION = 1;
    public static final int EXTRA_ERROR_DISABLED = 2;

    private static final String COL_CITY = "city";
    private static final String COL_WIND_SPEED = "wind_speed";
    private static final String COL_WIND_DIRECTION = "wind_direction";
    private static final String COL_CONDITION_CODE = "condition_code";
    private static final String COL_TEMPERATURE = "temperature";
    private static final String COL_HUMIDITY = "humidity";
    private static final String COL_CONDITION = "condition";
    private static final String COL_FORECAST_LOW = "forecast_low";
    private static final String COL_FORECAST_HIGH = "forecast_high";
    private static final String COL_FORECAST_CONDITION = "forecast_condition";
    private static final String COL_FORECAST_CONDITION_CODE = "forecast_condition_code";
    private static final String COL_TIME_STAMP = "time_stamp";
    private static final String COL_FORECAST_DATE = "forecast_date";
    private static final String COL_PIN_WHEEL = "pin_wheel";
    private static final String COL_FEELS_LIKE = "feels_like";
    private static final String COL_PRESSURE = "pressure";
    private static final String COL_UVI = "uvi";
    private static final String COL_VISIBILITY = "visibility";
    private static final String COL_DEW_POINT = "dew_point";
    private static final String COL_SUNRISE = "sunrise";
    private static final String COL_SUNSET = "sunset";
    private static final String COL_CITY_ID = "city_id";

    private static final String COL_ENABLED = "enabled";
    private static final String COL_UNITS = "units";
    private static final String COL_PROVIDER = "provider";
    private static final String COL_SETUP = "setup";
    private static final String COL_ICON_PACK = "icon_pack";

    private static final String COL_HOURLY_TEMPERATURE = "hourly_temperature";
    private static final String COL_HOURLY_CONDITION_CODE = "hourly_condition_code";
    private static final String COL_HOURLY_CONDITION = "hourly_condition";
    private static final String COL_HOURLY_TIMESTAMP = "hourly_timestamp";
    private static final String COL_HOURLY_HUMIDITY = "hourly_humidity";
    private static final String COL_HOURLY_WIND_SPEED = "hourly_wind_speed";

    public static final String[] WEATHER_PROJECTION = {
            COL_CITY, COL_WIND_SPEED, COL_WIND_DIRECTION, COL_CONDITION_CODE, COL_TEMPERATURE,
            COL_HUMIDITY, COL_CONDITION, COL_FORECAST_LOW, COL_FORECAST_HIGH, COL_FORECAST_CONDITION,
            COL_FORECAST_CONDITION_CODE, COL_TIME_STAMP, COL_FORECAST_DATE, COL_PIN_WHEEL,
            COL_FEELS_LIKE, COL_PRESSURE, COL_UVI, COL_VISIBILITY, COL_DEW_POINT,
            COL_SUNRISE, COL_SUNSET, COL_CITY_ID
    };

    public static final String[] SETTINGS_PROJECTION = {
            COL_ENABLED, COL_UNITS, COL_PROVIDER, COL_SETUP, COL_ICON_PACK
    };

    public static final String[] HOURLY_PROJECTION = {
            COL_HOURLY_TEMPERATURE, COL_HOURLY_CONDITION_CODE, COL_HOURLY_CONDITION,
            COL_HOURLY_TIMESTAMP, COL_HOURLY_HUMIDITY, COL_HOURLY_WIND_SPEED
    };

    private static final DecimalFormat sNoDigitsFormat = new DecimalFormat("0");

    private static OmniJawsClient sInstance;

    private volatile WeatherInfo mCachedInfo;
    private volatile Resources mRes;
    private volatile String mPackageName;
    private volatile String mIconPrefix;
    private volatile String mSettingIconPackage;
    private volatile boolean mMetric = true;

    private final List<WeakReference<OmniJawsObserver>> mObservers = new CopyOnWriteArrayList<>();
    private WeatherUpdateReceiver mReceiver;
    private Context mReceiverContext;
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
                try {
                    if (WEATHER_UPDATE.equals(action)) {
                        obs.weatherUpdated();
                    } else if (WEATHER_ERROR.equals(action)) {
                        obs.weatherError(intent.getIntExtra(EXTRA_ERROR, 0));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "observer threw on " + action, e);
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

        WeatherInfo info = null;

        try (Cursor weatherCursor = context.getContentResolver().query(
                WEATHER_URI, WEATHER_PROJECTION, null, null, null)) {

            if (weatherCursor != null && weatherCursor.getCount() > 0) {
                info = new WeatherInfo();
                info.tempUnits = getTemperatureUnit();
                info.windUnits = getWindUnit();
                List<DayForecast> forecasts = new ArrayList<>();

                final int colCity = weatherCursor.getColumnIndex(COL_CITY);
                final int colWindSpeed = weatherCursor.getColumnIndex(COL_WIND_SPEED);
                final int colWindDir = weatherCursor.getColumnIndex(COL_WIND_DIRECTION);
                final int colConditionCode = weatherCursor.getColumnIndex(COL_CONDITION_CODE);
                final int colTemperature = weatherCursor.getColumnIndex(COL_TEMPERATURE);
                final int colHumidity = weatherCursor.getColumnIndex(COL_HUMIDITY);
                final int colCondition = weatherCursor.getColumnIndex(COL_CONDITION);
                final int colTimeStamp = weatherCursor.getColumnIndex(COL_TIME_STAMP);
                final int colPinWheel = weatherCursor.getColumnIndex(COL_PIN_WHEEL);
                final int colFeelsLike = weatherCursor.getColumnIndex(COL_FEELS_LIKE);
                final int colPressure = weatherCursor.getColumnIndex(COL_PRESSURE);
                final int colUvi = weatherCursor.getColumnIndex(COL_UVI);
                final int colVisibility = weatherCursor.getColumnIndex(COL_VISIBILITY);
                final int colDewPoint = weatherCursor.getColumnIndex(COL_DEW_POINT);
                final int colSunrise = weatherCursor.getColumnIndex(COL_SUNRISE);
                final int colSunset = weatherCursor.getColumnIndex(COL_SUNSET);
                final int colCityId = weatherCursor.getColumnIndex(COL_CITY_ID);

                final int colForecastLow = weatherCursor.getColumnIndex(COL_FORECAST_LOW);
                final int colForecastHigh = weatherCursor.getColumnIndex(COL_FORECAST_HIGH);
                final int colForecastCondition = weatherCursor.getColumnIndex(COL_FORECAST_CONDITION);
                final int colForecastConditionCode =
                        weatherCursor.getColumnIndex(COL_FORECAST_CONDITION_CODE);
                final int colForecastDate = weatherCursor.getColumnIndex(COL_FORECAST_DATE);

                for (int i = 0; i < weatherCursor.getCount(); i++) {
                    weatherCursor.moveToPosition(i);
                    if (i == 0) {
                        info.city = getString(weatherCursor, colCity);
                        info.cityId = getString(weatherCursor, colCityId);
                        info.windSpeed = getFormattedValue(getFloat(weatherCursor, colWindSpeed));
                        info.windDirection = getInt(weatherCursor, colWindDir, 0) + "\u00b0";
                        info.conditionCode = getInt(weatherCursor, colConditionCode, -1);
                        info.temp = getFormattedValue(getFloat(weatherCursor, colTemperature));
                        info.humidity = getString(weatherCursor, colHumidity);
                        info.condition = getString(weatherCursor, colCondition);
                        info.timeStamp = getLong(weatherCursor, colTimeStamp, 0L);
                        info.pinWheel = getString(weatherCursor, colPinWheel);

                        info.feelsLike = getFloat(weatherCursor, colFeelsLike);
                        info.pressure = getFloat(weatherCursor, colPressure);
                        info.uvi = getFloat(weatherCursor, colUvi);
                        info.visibility = getFloat(weatherCursor, colVisibility);
                        info.dewPoint = getFloat(weatherCursor, colDewPoint);
                        info.sunrise = getLong(weatherCursor, colSunrise, 0L);
                        info.sunset = getLong(weatherCursor, colSunset, 0L);
                    } else {
                        DayForecast day = new DayForecast();
                        day.low = getFormattedValue(getFloat(weatherCursor, colForecastLow));
                        day.high = getFormattedValue(getFloat(weatherCursor, colForecastHigh));
                        day.condition = getString(weatherCursor, colForecastCondition);
                        day.conditionCode = getInt(weatherCursor, colForecastConditionCode, -1);
                        day.date = getString(weatherCursor, colForecastDate);
                        forecasts.add(day);
                    }
                }
                info.forecasts = forecasts;
            }
        } catch (Exception e) {
            Log.e(TAG, "queryWeather: weather", e);
            info = null;
        }

        try (Cursor settingsCursor = context.getContentResolver().query(
                SETTINGS_URI, SETTINGS_PROJECTION, null, null, null)) {

            if (settingsCursor != null && settingsCursor.moveToFirst()) {
                mMetric = getInt(settingsCursor,
                        settingsCursor.getColumnIndex(COL_UNITS), 0) == 0;
                if (info != null) {
                    info.tempUnits = getTemperatureUnit();
                    info.windUnits = getWindUnit();
                    info.provider = getString(settingsCursor,
                            settingsCursor.getColumnIndex(COL_PROVIDER));
                    info.iconPack = getString(settingsCursor,
                            settingsCursor.getColumnIndex(COL_ICON_PACK));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "queryWeather: settings", e);
        }

        if (info != null) {
            try (Cursor hourlyCursor = context.getContentResolver().query(
                    HOURLY_URI, HOURLY_PROJECTION, null, null, null)) {
                if (hourlyCursor != null && hourlyCursor.getCount() > 0) {
                    final int colTemp = hourlyCursor.getColumnIndex(COL_HOURLY_TEMPERATURE);
                    final int colCode = hourlyCursor.getColumnIndex(COL_HOURLY_CONDITION_CODE);
                    final int colCondition = hourlyCursor.getColumnIndex(COL_HOURLY_CONDITION);
                    final int colTimestamp = hourlyCursor.getColumnIndex(COL_HOURLY_TIMESTAMP);
                    final int colHumidity = hourlyCursor.getColumnIndex(COL_HOURLY_HUMIDITY);
                    final int colWindSpeed = hourlyCursor.getColumnIndex(COL_HOURLY_WIND_SPEED);

                    if (colTemp == -1 || colCode == -1 || colCondition == -1
                            || colTimestamp == -1 || colHumidity == -1 || colWindSpeed == -1) {
                        Log.w(TAG, "queryWeather: hourly columns missing, skipping hourly forecasts");
                    } else {
                        List<HourlyForecast> hourly = new ArrayList<>();
                        while (hourlyCursor.moveToNext()) {
                            HourlyForecast h = new HourlyForecast();
                            h.temperature = hourlyCursor.getFloat(colTemp);
                            h.conditionCode = hourlyCursor.getInt(colCode);
                            h.condition = hourlyCursor.getString(colCondition);
                            h.timestamp = hourlyCursor.getLong(colTimestamp);
                            h.humidity = hourlyCursor.getFloat(colHumidity);
                            h.windSpeed = hourlyCursor.getFloat(colWindSpeed);
                            hourly.add(h);
                        }
                        info.hourlyForecasts = hourly;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "queryWeather: hourly", e);
            }
        }

        mCachedInfo = info;

        updateSettings(context);
    }

    private static String getString(Cursor c, int col) {
        if (col == -1) return null;
        try {
            return c.getString(col);
        } catch (Exception e) {
            return null;
        }
    }

    private static int getInt(Cursor c, int col, int def) {
        if (col == -1) return def;
        try {
            return c.getInt(col);
        } catch (Exception e) {
            return def;
        }
    }

    private static float getFloat(Cursor c, int col) {
        if (col == -1) return Float.NaN;
        try {
            return c.getFloat(col);
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private static long getLong(Cursor c, int col, long def) {
        if (col == -1) return def;
        try {
            return c.getLong(col);
        } catch (Exception e) {
            return def;
        }
    }

    private void updateSettings(Context context) {
        WeatherInfo info = mCachedInfo;
        String iconPack = (info != null) ? info.iconPack : null;
        if (TextUtils.isEmpty(iconPack)) {
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
            if (c == null || !c.moveToFirst()) return false;
            return getInt(c, c.getColumnIndex(COL_ENABLED), 0) == 1;
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
        removeObserverInternal(observer);
        mObservers.add(new WeakReference<>(observer));
        registerReceiverIfNeeded(context);
    }

    public void removeObserver(Context context, OmniJawsObserver observer) {
        if (observer == null) return;
        removeObserverInternal(observer);
        if (mObservers.isEmpty()) {
            unregisterReceiver();
        }
    }

    private void removeObserverInternal(OmniJawsObserver observer) {
        mObservers.removeIf(ref -> {
            OmniJawsObserver o = ref.get();
            return o == null || o == observer;
        });
    }

    private void pruneDeadObservers() {
        try {
            mObservers.removeIf(ref -> ref.get() == null);
        } catch (Exception e) {
            Log.w(TAG, "Exception occured while pruning, ignoring");
        }
    }

    private synchronized void registerReceiverIfNeeded(Context context) {
        if (mWeatherReceiverRegistered || mObservers.isEmpty()) {
            return;
        }
        mReceiverContext = context.getApplicationContext();
        mReceiver = new WeatherUpdateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WEATHER_UPDATE);
        filter.addAction(WEATHER_ERROR);
        try {
            mReceiverContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
            mWeatherReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "registerReceiver failed", e);
            mReceiver = null;
            mReceiverContext = null;
        }
    }

    private synchronized void unregisterReceiver() {
        if (mWeatherReceiverRegistered && mReceiver != null && mReceiverContext != null) {
            try {
                mReceiverContext.unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
        }
        mReceiver = null;
        mReceiverContext = null;
        mWeatherReceiverRegistered = false;
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
        if (mRes == null) {
            return getDefaultConditionImage(context);
        }
        try {
            int resId = mRes.getIdentifier(mIconPrefix + "_" + conditionCode, "drawable", mPackageName);
            if (resId == 0) {
                return getDefaultConditionImage(context);
            }
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
            if (resId == 0) {
                return new ColorDrawable(Color.TRANSPARENT);
            }
            Drawable d = res.getDrawable(resId, null);
            return d != null ? d : new ColorDrawable(Color.TRANSPARENT);
        } catch (Exception e) {
            return new ColorDrawable(Color.TRANSPARENT);
        }
    }

    public Drawable getResOmni(Context context, String iconOmni) {
        if (mRes == null) loadDefaultIconsPackage(context);
        if (mRes == null) return new ColorDrawable(Color.TRANSPARENT);
        try {
            int resId = mRes.getIdentifier(iconOmni, "drawable", mPackageName);
            if (resId == 0) {
                return new ColorDrawable(Color.TRANSPARENT);
            }
            Drawable d = mRes.getDrawable(resId, null);
            return d != null ? d : new ColorDrawable(Color.TRANSPARENT);
        } catch (Exception e) {
            Log.e(TAG, "getResOmni", e);
            return new ColorDrawable(Color.TRANSPARENT);
        }
    }

    public static class WeatherInfo {
        public String city;
        public String cityId;
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

        public float feelsLike = Float.NaN;
        public float pressure = Float.NaN;
        public float uvi = Float.NaN;
        public float visibility = Float.NaN;
        public float dewPoint = Float.NaN;
        public long sunrise;
        public long sunset;
        public List<HourlyForecast> hourlyForecasts;

        public String getLastUpdateTime() {
            if (timeStamp == null || timeStamp == 0L) {
                return "";
            }
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(timeStamp));
        }

        @Override
        public String toString() {
            return city + " @ " + (timeStamp != null ? new Date(timeStamp) : "never")
                    + " | " + condition + " | " + temp;
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

    public static class HourlyForecast {
        public float temperature;
        public int conditionCode;
        public String condition;
        public long timestamp;
        public float humidity;
        public float windSpeed;

        @Override
        public String toString() {
            return "[" + new Date(timestamp) + " - " + temperature + " - " + condition + "]";
        }
    }
}
