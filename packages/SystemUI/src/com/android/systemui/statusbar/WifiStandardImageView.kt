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
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.util.AttributeSet
import android.widget.ImageView
import com.android.systemui.res.R
import android.view.ViewGroup.MarginLayoutParams

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class WifiStandardImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val connectivityManager: ConnectivityManager by lazy { 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    private val wifiManager: WifiManager by lazy { 
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager 
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val wifiStandardFlow = flow {
        while (true) {
            emit(getWifiStandard())
            delay(1000)
        }
    }.distinctUntilChanged()

    private val wifiStandardEnabledFlow = callbackFlow {
        val settingObserver = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(getWifiStandardEnabled())
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.WIFI_STANDARD_ICON),
            false, settingObserver
        )

        send(getWifiStandardEnabled())

        awaitClose { context.contentResolver.unregisterContentObserver(settingObserver) }
    }.distinctUntilChanged()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        coroutineScope.launch {
            combine(wifiStandardFlow, wifiStandardEnabledFlow) { wifiStandard, wifiStandardEnabled ->
                Pair(wifiStandard, wifiStandardEnabled)
            }.collect { (wifiStandard, wifiStandardEnabled) ->
                updateWifiStatus(wifiStandard, wifiStandardEnabled)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coroutineScope.cancel()
    }

    private fun updateWifiStatus(wifiStandard: Int, wifiStandardEnabled: Boolean) {
        post {
            if (!wifiStandardEnabled || wifiStandard < 4) {
                visibility = GONE
                layoutParams = (layoutParams as MarginLayoutParams).apply { marginEnd = 0 }
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

    private fun getWifiStandard(): Int {
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return if (networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifiManager.connectionInfo.wifiStandard
        } else {
            -1
        }
    }

    private fun getWifiStandardEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.WIFI_STANDARD_ICON,
            0,
            UserHandle.USER_CURRENT
        ) == 1
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
