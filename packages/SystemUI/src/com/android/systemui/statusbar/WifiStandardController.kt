/*
 * Copyright (C) 2025 The AxionAOSP Android Project
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
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.provider.Settings

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

object WifiStandardController {

    private var view: WifiStandardImageView? = null
    private var context: Context? = null
    private var coroutineScope: CoroutineScope? = null

    fun INSTANCE(context: Context): WifiStandardController {
        this.context = context.applicationContext
        return this
    }

    fun attachView(view: WifiStandardImageView) {
        this.view = view
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startCollecting()
    }

    fun detachView() {
        coroutineScope?.cancel()
        coroutineScope = null
        view = null
    }

    private fun startCollecting() {
        coroutineScope?.launch {
            combine(wifiStandardFlow(), wifiStandardEnabledFlow()) { standard, enabled ->
                Pair(standard, enabled)
            }.collect { (standard, enabled) ->
                view?.updateWifiStatus(standard, enabled)
            }
        }
    }

    private fun wifiStandardFlow(): Flow<Int> = flow {
        val connectivityManager = context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context!!.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        while (true) {
            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val wifiStandard = if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                wifiManager.connectionInfo.wifiStandard
            } else {
                -1
            }
            emit(wifiStandard)
            delay(1000)
        }
    }.distinctUntilChanged()

    private fun wifiStandardEnabledFlow(): Flow<Boolean> = callbackFlow {
        val contentResolver = context!!.contentResolver
        val settingUri = Settings.System.getUriFor(Settings.System.WIFI_STANDARD_ICON)

        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(isWifiStandardEnabled())
            }
        }

        contentResolver.registerContentObserver(settingUri, false, observer)
        send(isWifiStandardEnabled())

        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun isWifiStandardEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context!!.contentResolver,
            Settings.System.WIFI_STANDARD_ICON,
            0,
            UserHandle.USER_CURRENT
        ) == 1
    }
}
