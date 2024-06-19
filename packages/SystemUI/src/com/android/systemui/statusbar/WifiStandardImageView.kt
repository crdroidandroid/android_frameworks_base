/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.ImageView
import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService

import android.view.ViewGroup.MarginLayoutParams

class WifiStandardImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val tunerService: TunerService by lazy { Dependency.get(TunerService::class.java) }
    private val connectivityManager: ConnectivityManager by lazy { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val wifiManager: WifiManager by lazy { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var wifiStandardEnabled = false
    private var isTunerRegistered = false
    private var currWifiStandard: Int = -1
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (wifiStandardEnabled) {
                val wifiStandard = getWifiStandard()
                if (currWifiStandard != wifiStandard) {
                    updateWifiStandard(wifiStandard)
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    init {
        registerTunerService()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(updateRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(updateRunnable)
    }

    private fun registerTunerService() {
        if (isTunerRegistered) return
        tunerService.addTunable({ key, value ->
            wifiStandardEnabled = TunerService.parseIntegerSwitch(value, false)
            if (wifiStandardEnabled) {
                handler.post(updateRunnable)
            } else {
                handler.removeCallbacks(updateRunnable)
                visibility = GONE
            }
        }, "system:" + Settings.System.WIFI_STANDARD_ICON)
        isTunerRegistered = true
    }

    private fun getWifiStandard(): Int {
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return if (networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo.wifiStandard
        } else {
            -1
        }
    }

    private fun updateWifiStandard(wifiStandard: Int) {
        currWifiStandard = wifiStandard
        updateIcon(wifiStandard)
    }

    private fun updateIcon(wifiStandard: Int) {
        post {
            if (!wifiStandardEnabled || wifiStandard < 4) {
                visibility = GONE
                layoutParams = (layoutParams as MarginLayoutParams).apply {
                    marginEnd = 0
                }
            } else {
                val drawableId = getDrawableForWifiStandard(wifiStandard)
                if (drawableId > 0) {
                    setImageResource(drawableId)
                    visibility = VISIBLE
                    layoutParams = (layoutParams as MarginLayoutParams).apply {
                        marginEnd = resources.getDimensionPixelSize(R.dimen.status_bar_airplane_spacer_width)
                    }
                }
            }
        }
    }

    private fun getDrawableForWifiStandard(wifiStandard: Int): Int {
        return when (wifiStandard) {
            4 -> R.drawable.ic_wifi_standard_4
            5 -> R.drawable.ic_wifi_standard_5
            6 -> R.drawable.ic_wifi_standard_6
            7 -> R.drawable.ic_wifi_standard_7
            else -> 0
        }
    }
}
