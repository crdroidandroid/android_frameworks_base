/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.systemui.statusbar.policy

import android.content.*
import android.database.ContentObserver
import android.net.*
import android.net.TrafficStats
import android.os.*
import android.provider.Settings
import android.view.ViewGroup
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dependency
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.phone.StatusBarIconHolder
import com.android.systemui.statusbar.phone.NetworkSpeedIconHolder
import com.android.systemui.statusbar.phone.StatusBarIconControllerImplEx
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedView
import kotlinx.coroutines.*

class NetworkSpeedController private constructor(
    private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val contentResolver = context.contentResolver

    private var isSwitchOn = false
    private var isConnected = false
    private var networkVisibility = false

    private var lastTime = 0L
    private var lastTotalBytes = 0L

    private var keyguardUpdateMonitor: KeyguardUpdateMonitor? = null
    private var keyguardCallback: KeyguardUpdateMonitorCallback? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var speedUpdateJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateConnectionState(true)
        }

        override fun onLost(network: Network) {
            updateConnectionState(false)
        }
    }

    private val networkSpeedObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reset()
                updateSwitchState()
                restartSpeedUpdates()
            }
        }

    fun init() {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(ICON_HIDE_LIST),
            true,
            networkSpeedObserver
        )
        networkSpeedObserver.onChange(true)

        keyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor::class.java)
        keyguardCallback = object : KeyguardUpdateMonitorCallback() {
            override fun onUserSwitchComplete(newUserId: Int) {
                networkSpeedObserver.onChange(true)
            }
        }
        keyguardUpdateMonitor?.registerCallback(keyguardCallback)
    }

    private fun updateSwitchState() {
        val iconHideList = Settings.Secure.getStringForUser(
            contentResolver,
            ICON_HIDE_LIST,
            UserHandle.USER_CURRENT
        )
        isSwitchOn = !iconHideList.isNullOrEmpty() 
            && !iconHideList.contains(SLOT_NETWORK_SPEED)
    }

    private fun updateConnectionState(connected: Boolean) {
        isConnected = connected
        restartSpeedUpdates()
    }

    private fun restartSpeedUpdates() {
        speedUpdateJob?.cancel()
        if (isConnected && isSwitchOn) {
            speedUpdateJob = scope.launch {
                while (isActive) {
                    updateNetworkSpeed()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        } else {
            scope.launch {
                updateNetworkSpeed()
            }
        }
    }

    private suspend fun updateNetworkSpeed() {
        val currentTime = System.currentTimeMillis()
        val totalBytes = getTotalBytes()

        var speed = 0L
        if (lastTime > 0 && lastTotalBytes > 0 &&
            totalBytes > lastTotalBytes && currentTime > lastTime
        ) {
            speed = (((totalBytes - lastTotalBytes) * 1000) / (currentTime - lastTime)).toLong()
        }

        val iconState = NetworkSpeedIconState().apply {
            setVisible(isConnected && isSwitchOn)
            setSpeedText(speed)
            setSlot(SLOT_NETWORK_SPEED)
        }

        withContext(Dispatchers.Main) {
            StatusBarIconControllerImplEx.get().setNetworkSpeedIcon(SLOT_NETWORK_SPEED, iconState)
            if (networkVisibility != iconState.isVisible()) {
                networkVisibility = iconState.isVisible()
            }
        }

        lastTime = currentTime
        lastTotalBytes = totalBytes
    }

    private fun getTotalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0 && tx >= 0) rx + tx else 0
    }

    private fun reset() {
        lastTime = 0
        lastTotalBytes = 0
    }

    fun isSwitchOn(): Boolean = isSwitchOn

    fun addHolder(
        index: Int,
        slot: String,
        rootGroup: ViewGroup,
        holder: StatusBarIconHolder,
        blocked: Boolean
    ): StatusIconDisplayable? {
        return StatusBarIconControllerImplEx.get()
            .addHolder(index, slot, rootGroup, holder, blocked)
    }

    fun onSetIconHolder(viewIndex: Int, holder: StatusBarIconHolder, rootGroup: ViewGroup) {
        if (holder.type == 6 && holder is NetworkSpeedIconHolder) {
            val view = rootGroup.getChildAt(viewIndex) as? NetworkSpeedView ?: return
            view.applyNetworkState(holder.getNetworkSpeedIconState())
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkSpeedController? = null

        fun init(context: Context): NetworkSpeedController {
            return instance ?: synchronized(this) {
                instance ?: NetworkSpeedController(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun get(): NetworkSpeedController {
            return instance ?: throw IllegalStateException("init must be called first!!!")
        }

        const val TAG = "NetworkSpeedController"
        const val SLOT_NETWORK_SPEED = "network_speed"
        const val ICON_HIDE_LIST = "icon_blacklist"
        const val REFRESH_INTERVAL_MS = 4000L
    }
}
