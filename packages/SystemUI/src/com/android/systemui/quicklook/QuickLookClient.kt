/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.quicklook

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.android.axion.quicklook.IAxQuickLookService
import com.android.axion.quicklook.IQuickLookCallback
import com.android.axion.quicklook.QuickLookTarget
import com.android.axion.quicklook.SportsData
import com.android.axion.quicklook.nowPlayingData
import com.android.axion.quicklook.sportsData
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.WeakListenerManager
import javax.inject.Inject

@SysUISingleton
class QuickLookClient @Inject constructor(
    private val context: Context
) {

    interface Callback {
        fun onNowPlayingUpdate(nowPlayingText: String, tapAction: PendingIntent?) {}
        fun onSportsUpdate(sports: List<SportsData>) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var service: IAxQuickLookService? = null
    private var isBound = false
    private var rebindAttempts = 0

    private var cachedNowPlaying: String = ""
    private var cachedNowPlayingAction: PendingIntent? = null
    private var cachedSports: List<SportsData> = emptyList()

    private val callbacks = WeakListenerManager<Callback>()

    private val quickLookCallback = object : IQuickLookCallback.Stub() {
        override fun onTargetsUpdated(targets: MutableList<QuickLookTarget>) {
            handler.post { processTargets(targets) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IAxQuickLookService.Stub.asInterface(binder)
            rebindAttempts = 0
            try {
                service?.registerCallback(quickLookCallback)
                service?.getCurrentTargets()?.let { targets ->
                    handler.post { processTargets(targets) }
                }
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to register callback", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            scheduleRebind()
        }
    }

    fun addCallback(callback: Callback) {
        callbacks.addListener(callback)
        if (!isBound) {
            bindService()
        }
        deliverCachedData(callback)
    }

    fun removeCallback(callback: Callback) {
        callbacks.removeListener(callback)
        if (callbacks.isEmpty()) {
            unbindService()
        }
    }

    private fun deliverCachedData(callback: Callback) {
        if (cachedNowPlaying.isNotEmpty()) {
            callback.onNowPlayingUpdate(cachedNowPlaying, cachedNowPlayingAction)
        }
        if (cachedSports.isNotEmpty()) {
            callback.onSportsUpdate(cachedSports)
        }
    }

    private fun processTargets(targets: List<QuickLookTarget>) {
        var hasNowPlaying = false
        val sportsTargets = mutableListOf<SportsData>()

        for (target in targets) {
            when (target.targetType) {
                QuickLookTarget.TYPE_NOW_PLAYING -> {
                    hasNowPlaying = true
                    val np = target.nowPlayingData ?: continue
                    val npAction = target.primaryAction?.pendingIntent
                    if (np.title != cachedNowPlaying || npAction != cachedNowPlayingAction) {
                        cachedNowPlaying = np.title
                        cachedNowPlayingAction = npAction
                        callbacks.notify { it.onNowPlayingUpdate(np.title, npAction) }
                    }
                }
                QuickLookTarget.TYPE_SPORTS -> {
                    val s = target.sportsData ?: continue
                    sportsTargets.add(s)
                    val sportTitle = when {
                        s.score1.isNotEmpty() && s.score2.isNotEmpty() ->
                            "${s.team1Name} ${s.score1} - ${s.score2} ${s.team2Name}"
                        s.team1Name.isNotEmpty() && s.team2Name.isNotEmpty() ->
                            "${s.team1Name} vs ${s.team2Name}"
                        else -> target.title ?: ""
                    }
                }
            }
        }

        if (sportsTargets != cachedSports) {
            cachedSports = sportsTargets
            callbacks.notify { it.onSportsUpdate(sportsTargets) }
        }

        if (!hasNowPlaying && cachedNowPlaying.isNotEmpty()) {
            cachedNowPlaying = ""
            cachedNowPlayingAction = null
            callbacks.notify { it.onNowPlayingUpdate("", null) }
        }
    }

    private fun bindService() {
        if (isBound) return
        val intent = Intent(SERVICE_ACTION).apply {
            setPackage(SERVICE_PACKAGE)
        }
        try {
            isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to bind to AxQuickLook service", e)
        }
    }

    private fun unbindService() {
        if (!isBound) return
        try {
            service?.unregisterCallback(quickLookCallback)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to unregister callback", e)
        }
        try {
            context.unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service not registered", e)
        }
        service = null
        isBound = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleRebind() {
        if (!isBound || callbacks.isEmpty()) return
        rebindAttempts++
        val delay = (REBIND_DELAY_MS * rebindAttempts).coerceAtMost(MAX_REBIND_DELAY_MS)
        handler.postDelayed({ bindService() }, delay)
    }

    companion object {
        private const val TAG = "QuickLookClient"
        private const val SERVICE_ACTION = "com.android.axion.quicklook.SERVICE"
        private const val SERVICE_PACKAGE = "com.android.axion.quicklook"
        private const val REBIND_DELAY_MS = 5000L
        private const val MAX_REBIND_DELAY_MS = 30000L
    }
}
