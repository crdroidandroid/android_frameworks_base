/*
 * Copyright (C) 2019-2024 crDroid Android Project
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

package com.android.systemui

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView

import androidx.core.graphics.ColorUtils

import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

import javax.inject.Inject

import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FPSInfoService @Inject constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    @Main private val handler: Handler
) : Service() {

    private lateinit var coroutineScope: CoroutineScope

    private lateinit var windowManager: WindowManager
    private lateinit var fpsInfoView: TextView
    private lateinit var configuration: Configuration
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private lateinit var fpsInfoNode: RandomAccessFile

    private var fpsReadJob: Job? = null

    private var observerRegistered = false
    private val wakefulnessObserver = object: WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            stopReading()
        }

        override fun onStartedWakingUp() {
            startReading()
        }
    }

    private var fpsReadInterval = FPS_MEASURE_INTERVAL_DEFAULT

    override fun onCreate() {
        super.onCreate()
        coroutineScope = CoroutineScope(Dispatchers.IO)

        windowManager = getSystemService(WindowManager::class.java)!!
        configuration = resources.configuration
        layoutParams.y = getTopInset()

        fpsInfoView = TextView(this).apply {
            text = getString(R.string.fps_text_placeholder, 0)
            setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, BACKGROUND_ALPHA))
            setTextColor(Color.WHITE)
            val padding = resources.getDimensionPixelSize(R.dimen.fps_info_text_padding)
            setPadding(padding, padding, padding, padding)
        }

        val nodePath = getString(R.string.config_fpsInfoSysNode)
        val file = File(nodePath)
        if (file.exists() && file.canRead()) {
            try {
                fpsInfoNode = RandomAccessFile(nodePath, "r")
            } catch (e: IOException) {
                Log.e(TAG, "Sysfs node $nodePath does not exist, stopping service")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Sysfs node $nodePath does not exist or is not readable")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, startId: Int, flags: Int): Int {
        if (!observerRegistered) {
            wakefulnessLifecycle.addObserver(wakefulnessObserver)
            observerRegistered = true
        }
        fpsReadInterval = resources.getInteger(R.integer.config_fpsReadInterval).toLong()
        startReading()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (configuration.orientation != newConfig.orientation) {
            layoutParams.y = getTopInset()
            if (fpsInfoView.parent != null)
                windowManager.updateViewLayout(fpsInfoView, layoutParams)
        }
        configuration = newConfig
    }

    private fun getTopInset(): Int = windowManager.currentWindowMetrics
        .windowInsets.getInsets(WindowInsets.Type.statusBars()).top

    private fun startReading() {
        if (fpsReadJob != null) return
        if (fpsInfoView.parent == null) windowManager.addView(fpsInfoView, layoutParams)
        fpsReadJob = coroutineScope.launch {
            do {
                val fps = measureFps()
                handler.post {
                    fpsInfoView.text = getString(R.string.fps_text_placeholder, fps)
                }
                delay(fpsReadInterval)
            } while (isActive)
        }
    }

    private fun stopReading() {
        if (fpsReadJob == null) return
        fpsReadJob?.cancel()
        fpsReadJob = null
        if (fpsInfoView.parent != null) windowManager.removeViewImmediate(fpsInfoView)
    }

    private fun measureFps(): Int {
        fpsInfoNode.seek(0L)
        val measuredFps: String
        try {
            measuredFps = fpsInfoNode.readLine()
        } catch (e: IOException) {
            Log.e(TAG, "IOException while reading from FPS node, ${e.message}")
            return -1
        }
        try {
            val fps: Float = measuredFps.trim().let {
                if (it.contains(": ")) it.split("\\s+".toRegex())[1] else it
            }.toFloat()
            return fps.roundToInt()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "NumberFormatException occurred while parsing FPS info, ${e.message}")
        }
        return -1
    }

    override fun onDestroy() {
        stopReading()
        coroutineScope.cancel()
        if (observerRegistered) {
            wakefulnessLifecycle.removeObserver(wakefulnessObserver)
            observerRegistered = false
        }
        if (fpsInfoView.parent != null)
            windowManager.removeViewImmediate(fpsInfoView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        private const val TAG = "FPSInfoService"
        private const val FPS_MEASURE_INTERVAL_DEFAULT = 1000L

        private const val BACKGROUND_ALPHA = 120
    }
}
