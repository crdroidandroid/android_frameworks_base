/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.util

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

import com.android.systemui.res.R

class ProgressImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private var progressType: ProgressType = ProgressType.UNKNOWN
    private var progressPercent = -1
    private var batteryLevel = -1
    private var batteryTemperature = -1
    private var updateJob: Job? = null
    private var receiverRegistered = false
    private var typeface: String? = null
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                batteryLevel = batteryLevel.coerceIn(0, 100)
                batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
                updateProgress()
            }
        }
    }

    private val settingsObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            updateVisibility()
        }
    }

    enum class ProgressType(val iconRes: Int) {
        BATTERY(R.drawable.ic_battery),
        MEMORY(R.drawable.ic_memory),
        TEMPERATURE(R.drawable.ic_temperature),
        VOLUME(R.drawable.ic_volume_eq),
        UNKNOWN(-1)
    }

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ProgressImageView, defStyleAttr, 0)
        typeface = typedArray.getString(R.styleable.ProgressImageView_typeface)
        typedArray.recycle()
        when (id) {
            R.id.battery_progress -> progressType = ProgressType.BATTERY
            R.id.memory_progress -> progressType = ProgressType.MEMORY
            R.id.temperature_progress -> progressType = ProgressType.TEMPERATURE
            R.id.volume_progress -> progressType = ProgressType.VOLUME
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor("lockscreen_info_widgets_enabled"),
            false,
            settingsObserver
        )
        if (!receiverRegistered) {
            if (progressType == ProgressType.BATTERY || progressType == ProgressType.TEMPERATURE) {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
        }
        startProgressUpdates()
        updateVisibility()
        updateProgress()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.contentResolver.unregisterContentObserver(settingsObserver)
        if (receiverRegistered) {
            context.unregisterReceiver(batteryReceiver)
        }
        stopProgressUpdates()
    }

    private fun startProgressUpdates() {
        if (progressType == ProgressType.MEMORY || progressType == ProgressType.VOLUME) {
            updateJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    updateProgress()
                    delay(1000L)
                }
            }
        }
    }

    private fun stopProgressUpdates() {
        updateJob?.cancel()
    }

    private fun updateProgress() {
        val newProgressPercent = when (progressType) {
            ProgressType.BATTERY -> batteryLevel
            ProgressType.MEMORY -> getMemoryLevel()
            ProgressType.TEMPERATURE -> batteryTemperature
            ProgressType.VOLUME -> getVolumeLevel()
            ProgressType.UNKNOWN -> -1
        }
        if (newProgressPercent != progressPercent) {
            progressPercent = newProgressPercent
            updateImageView()
        }
    }

    private fun updateImageView() {
        val degree = "\u2103"
        val progressText = if (progressType == ProgressType.TEMPERATURE) {
            if (progressPercent != -1) "$progressPercent$degree" else "N/A"
        } else {
            if (progressPercent == -1) "..." else "$progressPercent%"
        }
        val icon: Drawable? = ContextCompat.getDrawable(context, progressType.iconRes)
        val widgetBitmap: Bitmap = ArcProgressWidget.generateBitmap(
            context,
            if (progressPercent == -1) 0 else progressPercent,
            progressText,
            40,
            icon,
            36,
            typeface
        )
        setImageBitmap(widgetBitmap)
    }

    private fun getMemoryLevel(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val usedMemory = memoryInfo.totalMem - memoryInfo.availMem
        val usedMemoryPercentage = ((usedMemory * 100) / memoryInfo.totalMem).toInt()
        return usedMemoryPercentage.coerceIn(0, 100)
    }

    private fun getVolumeLevel(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        return ((currentVolume * 100) / maxVolume).coerceIn(0, 100)
    }
    
    private fun updateVisibility() {
        val enabled = Settings.System.getInt(
            context.contentResolver,
            "lockscreen_info_widgets_enabled", 0
        ) == 1
        visibility = if (enabled) VISIBLE else GONE
    }
}
