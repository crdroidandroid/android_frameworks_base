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
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.UserHandle
import android.provider.Settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

object WifiStandardController {

    /** Standard of the connected Wi-Fi network, or WIFI_STANDARD_UNKNOWN while the icon is off. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun wifiStandard(context: Context): Flow<Int> =
        wifiStandardEnabledFlow(context)
            .flatMapLatest { enabled ->
                if (enabled) connectedWifiStandardFlow(context)
                else flowOf(ScanResult.WIFI_STANDARD_UNKNOWN)
            }
            .distinctUntilChanged()

    // Wi-Fi refreshes the network's WifiInfo, standard included, on every association and roam.
    private fun connectedWifiStandardFlow(context: Context): Flow<Int> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                current = network
                trySend(
                    (caps.transportInfo as? WifiInfo)?.wifiStandard
                        ?: ScanResult.WIFI_STANDARD_UNKNOWN
                )
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    trySend(ScanResult.WIFI_STANDARD_UNKNOWN)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        trySend(ScanResult.WIFI_STANDARD_UNKNOWN)
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun wifiStandardEnabledFlow(context: Context): Flow<Boolean> = callbackFlow {
        val contentResolver = context.contentResolver
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(isWifiStandardEnabled(context))
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.WIFI_STANDARD_ICON), false, observer
        )
        send(isWifiStandardEnabled(context))

        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun isWifiStandardEnabled(context: Context): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.WIFI_STANDARD_ICON,
            0,
            UserHandle.USER_CURRENT
        ) == 1
    }
}
