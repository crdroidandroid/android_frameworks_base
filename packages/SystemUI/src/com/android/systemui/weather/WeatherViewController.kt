/*
 * SPDX-FileCopyrightText: The AxionAOSP Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.weather

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.android.internal.util.crdroid.OmniJawsClient

import com.android.systemui.res.R

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

class WeatherViewController(
    private val context: Context,
    private val weatherIcon: ImageView,
    private val weatherTemp: TextView,
    private val weatherInfoView: View,
) : OmniJawsClient.OmniJawsObserver {

    private var weatherInfo: OmniJawsClient.WeatherInfo? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val weatherSettingsFlow = MutableStateFlow(getWeatherSettings())

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            weatherSettingsFlow.value = getWeatherSettings()
        }
    }

    fun init() {
        registerSettingsObservers()
        scope.launch {
            weatherSettingsFlow.collectLatest { applyWeatherSettings(it) }
        }
    }

    private fun registerSettingsObservers() {
        val resolver = context.contentResolver
        WEATHER_SETTING_KEYS.forEach { key ->
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                settingsObserver,
                UserHandle.USER_ALL
            )
        }
    }

    private fun unregisterSettingsObservers() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
    }

    private fun getConditionText(condition: String): String {
        val locale = context.resources.configuration.locales[0]
        val isEnglish = locale.language.startsWith("en", ignoreCase = true)

        if (!isEnglish) {
            for ((key, value) in WEATHER_CONDITIONS) {
                if (condition.contains(key)) {
                    return context.resources.getString(value)
                }
            }
        }
        return condition.split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercaseChar() } }
    }

    private fun getWeatherSettings() = WeatherSettings(
        weatherEnabled = getSystemSetting(LOCKSCREEN_WEATHER_ENABLED),
        showWeatherLocation = getSystemSetting(LOCKSCREEN_WEATHER_LOCATION),
        showWeatherText = getSystemSetting(LOCKSCREEN_WEATHER_TEXT, defaultValue = 1),
        showWindInfo = getSystemSetting(LOCKSCREEN_WEATHER_WIND_INFO),
        showHumidityInfo = getSystemSetting(LOCKSCREEN_WEATHER_HUMIDITY_INFO)
    )

    private fun getSystemSetting(setting: String, defaultValue: Int = 0) =
        Settings.System.getIntForUser(context.contentResolver, setting, defaultValue, UserHandle.USER_CURRENT) != 0

    private fun applyWeatherSettings(settings: WeatherSettings) {
        if (!settings.weatherEnabled) {
            hideAllViews()
            OmniJawsClient.get().removeObserver(context, this@WeatherViewController)
        } else {
            OmniJawsClient.get().addObserver(context, this@WeatherViewController)
            updateWeather()
            showAllViews()
        }
    }

    override fun weatherUpdated() = updateWeather()

    private fun updateWeather() {
        if (!weatherSettingsFlow.value.weatherEnabled) {
            hideAllViews()
            return
        }

        try {
            OmniJawsClient.get().queryWeather(context)
            weatherInfo = OmniJawsClient.get().weatherInfo
            weatherInfo?.let { info ->
                weatherIcon.setImageDrawable(
                    OmniJawsClient.get().getWeatherConditionImage(context,
                    info.conditionCode))
                weatherTemp.text = buildWeatherText(info)
                weatherTemp.isSelected = true
            }
        } catch (e: Exception) {}
    }

    private fun hideAllViews() {
        scope.launch {
            listOf(weatherInfoView, weatherIcon, weatherTemp).forEach {
                updateViewVisibility(it, false)
            }
        }
    }

    private fun showAllViews() {
        scope.launch {
            listOf(weatherInfoView, weatherIcon, weatherTemp).forEach {
                updateViewVisibility(it, true)
            }
        }
    }

    private fun buildWeatherText(info: OmniJawsClient.WeatherInfo): String {
        val settings = weatherSettingsFlow.value
        val conditionText = getConditionText(info.condition.lowercase())

        val locationText = if (settings.showWeatherLocation) " • ${info.city}" else ""
        val conditionDisplay = if (settings.showWeatherText) " • $conditionText" else ""
        val windDisplay = if (settings.showWindInfo) " • ${info.windSpeed} ${info.windUnits} ${info.pinWheel}" else ""
        val humidityDisplay = if (settings.showHumidityInfo) " • ${info.humidity}" else ""

        return "${info.temp}${info.tempUnits}$locationText$conditionDisplay$windDisplay$humidityDisplay"
    }

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            weatherInfo = null
            weatherIcon.setImageDrawable(null)
            weatherTemp.text = ""
            hideAllViews()
        }
    }

    fun removeObserver() {
        unregisterSettingsObservers()
        scope.cancel()
        OmniJawsClient.get().removeObserver(context, this)
    }

    private suspend fun updateViewVisibility(view: View, visible: Boolean) {
        withContext(Dispatchers.Main) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    data class WeatherSettings(
        val weatherEnabled: Boolean,
        val showWeatherLocation: Boolean,
        val showWeatherText: Boolean,
        val showWindInfo: Boolean,
        val showHumidityInfo: Boolean
    )

    companion object {
        private const val LOCKSCREEN_WEATHER_ENABLED = "lockscreen_weather_enabled"
        private const val LOCKSCREEN_WEATHER_LOCATION = "lockscreen_weather_location"
        private const val LOCKSCREEN_WEATHER_TEXT = "lockscreen_weather_text"
        private const val LOCKSCREEN_WEATHER_WIND_INFO = "lockscreen_weather_wind_info"
        private const val LOCKSCREEN_WEATHER_HUMIDITY_INFO = "lockscreen_weather_humidity_info"

        private val WEATHER_SETTING_KEYS = listOf(
            LOCKSCREEN_WEATHER_ENABLED,
            LOCKSCREEN_WEATHER_LOCATION,
            LOCKSCREEN_WEATHER_TEXT,
            LOCKSCREEN_WEATHER_WIND_INFO,
            LOCKSCREEN_WEATHER_HUMIDITY_INFO
        )

        private val WEATHER_CONDITIONS = mapOf(
            "clouds" to R.string.weather_condition_clouds,
            "rain" to R.string.weather_condition_rain,
            "clear" to R.string.weather_condition_clear,
            "storm" to R.string.weather_condition_storm,
            "snow" to R.string.weather_condition_snow,
            "wind" to R.string.weather_condition_wind,
            "mist" to R.string.weather_condition_mist
        )
    }
}
