/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.ax

import android.database.ExecutorContentObserver
import android.os.Binder
import android.os.Bundle
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.android.axion.platform.AxPlatformClient
import com.android.axion.platform.IAxPlatformCallback
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class AxPlatformStateManager @Inject constructor(
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings,
    @Main private val mainExecutor: Executor
) {

    interface LabelProvider {
        fun getLabel(feature: String): String?
        fun getSecondaryLabel(feature: String, state: Bundle): String?
    }

    private val callbacks = RemoteCallbackList<IAxPlatformCallback>()
    private val stateCache = ConcurrentHashMap<String, Bundle>()

    var labelProvider: LabelProvider? = null
    var supportedFeatures: Array<String> = emptyArray()
        internal set

    fun getState(feature: String): Bundle = stateCache[feature] ?: Bundle.EMPTY

    fun getAllStates(): Bundle = Bundle().also { result ->
        stateCache.forEach { (key, bundle) -> result.putBundle(key, bundle) }
    }

    fun registerCallback(callback: IAxPlatformCallback) {
        callbacks.register(callback)
        stateCache.forEach { (key, bundle) ->
            try {
                callback.onStateChanged(key, bundle)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to send initial state for key=$key", e)
            }
        }
    }

    fun unregisterCallback(callback: IAxPlatformCallback) {
        callbacks.unregister(callback)
    }

    fun broadcastState(key: String, state: Bundle) {
        enrichBundle(key, state)
        stateCache[key] = state
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).onStateChanged(key, state)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Callback failed for key=$key", e)
                }
            }
            callbacks.finishBroadcast()
        }
    }

    fun broadcastBool(feature: String, active: Boolean) {
        broadcastState(feature, Bundle().apply {
            putBoolean("enabled", active)
            putBoolean("active", active)
        })
    }

    private fun enrichBundle(key: String, state: Bundle) {
        if (state === Bundle.EMPTY) return
        if (!state.containsKey("tileState")) {
            state.putInt("tileState", when {
                !state.getBoolean("available", true) -> AxPlatformClient.TILE_STATE_UNAVAILABLE
                state.getBoolean("active", false) -> AxPlatformClient.TILE_STATE_ACTIVE
                else -> AxPlatformClient.TILE_STATE_INACTIVE
            })
        }
        labelProvider?.let { provider ->
            if (!state.containsKey("label")) {
                provider.getLabel(key)?.let { state.putString("label", it) }
            }
            if (!state.containsKey("secondaryLabel")) {
                provider.getSecondaryLabel(key, state)?.let {
                    state.putString("secondaryLabel", it)
                }
            }
        }
    }

    fun getSecureBool(key: String, def: Boolean = false): Boolean =
        secureSettings.getIntForUser(key, if (def) 1 else 0, UserHandle.USER_CURRENT) == 1

    fun setSecureBool(key: String, value: Boolean) {
        val token = Binder.clearCallingIdentity()
        try {
            secureSettings.putIntForUser(key, if (value) 1 else 0, UserHandle.USER_CURRENT)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun toggleSecure(key: String) = setSecureBool(key, !getSecureBool(key))

    fun getGlobalBool(key: String, def: Boolean = false): Boolean =
        globalSettings.getInt(key, if (def) 1 else 0) == 1

    fun setGlobalBool(key: String, value: Boolean) {
        val token = Binder.clearCallingIdentity()
        try {
            globalSettings.putInt(key, if (value) 1 else 0)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun toggleGlobal(key: String) = setGlobalBool(key, !getGlobalBool(key))

    fun observeSecure(key: String, feature: String) {
        secureSettings.registerContentObserverSync(
            key, object : ExecutorContentObserver(mainExecutor) {
                override fun onChange(selfChange: Boolean) {
                    broadcastBool(feature, getSecureBool(key))
                }
            }
        )
        broadcastBool(feature, getSecureBool(key))
    }

    fun observeGlobal(key: String, feature: String) {
        globalSettings.registerContentObserverSync(
            key, object : ExecutorContentObserver(mainExecutor) {
                override fun onChange(selfChange: Boolean) {
                    broadcastBool(feature, getGlobalBool(key))
                }
            }
        )
        broadcastBool(feature, getGlobalBool(key))
    }

    companion object {
        private const val TAG = "AxPlatformState"
    }
}
