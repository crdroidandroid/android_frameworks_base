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
package com.axion.applocker

import android.app.AxSandboxManager
import android.app.AxSandboxManager.AppLockState
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import com.android.internal.app.IAppLockStateListener
import com.android.internal.app.IAppSessionListener
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shade.QuickSettingsControllerImpl
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.Lazy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class AxAppLockerHelper @Inject constructor(
    private val qsController: Lazy<QuickSettingsControllerImpl>,
    private val shadeController: Lazy<ShadeController>,
    private val keyguardStateController: KeyguardStateController,
    private val sandboxManager: AxSandboxManager?,
    @Main private val mainExecutor: Executor,
) : CoreStartable {

    companion object {
        private const val TAG = "AxAppLockerHelper"
        private const val PENDING_EXPIRY_MS = 15_000L
        private fun sessionKey(userId: Int, packageName: String): String = "$userId:$packageName"
    }

    @Volatile private var listenersRegistered = false
    private val hasLockCache = ConcurrentHashMap<String, Boolean>()
    private val sessionAuthCache = ConcurrentHashMap<String, Boolean>()
    private val refreshListeners = CopyOnWriteArrayList<Runnable>()
    private val notifUnlocks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingNotifOnly = ConcurrentHashMap<String, Long>()

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            if (keyguardStateController.isShowing) {
                notifUnlocks.clear()
                pendingNotifOnly.clear()
                sessionAuthCache.clear()
                mainExecutor.execute { refreshState() }
            }
        }
    }

    private val lockStateListener = object : IAppLockStateListener.Stub() {
        override fun onAppLockStateChanged(packageName: String, locked: Boolean) {
            if (packageName.isBlank()) return
            hasLockCache[packageName] = locked
            clearSessionCacheFor(packageName)
            if (!locked) {
                val suffix = ":$packageName"
                notifUnlocks.removeAll { it.endsWith(suffix) }
                pendingNotifOnly.keys.removeAll { it.endsWith(suffix) }
            }
            mainExecutor.execute {
                qsController.get().onAppLockerUpdated(packageName)
                refreshState()
            }
        }
    }

    private val sessionListener = object : IAppSessionListener.Stub() {
        override fun onAppUnlocked(packageName: String, userId: Int) {
            if (packageName.isBlank()) return
            val key = sessionKey(userId, packageName)
            sessionAuthCache[key] = false
            val pendingTs = pendingNotifOnly.remove(key)
            val now = SystemClock.elapsedRealtime()
            val notifOnly = pendingTs != null && now - pendingTs < PENDING_EXPIRY_MS
            if (notifOnly) notifUnlocks.add(key)
            mainExecutor.execute {
                if (notifOnly) {
                    shadeController.get().animateExpandShade()
                }
                qsController.get().onAppLockerUpdated(packageName)
                refreshState()
            }
        }

        override fun onAppLocked(packageName: String, userId: Int) {
            if (packageName.isBlank()) return
            val key = sessionKey(userId, packageName)
            pendingNotifOnly.remove(key)
            if (!notifUnlocks.contains(key)) {
                sessionAuthCache[key] = true
            }
            mainExecutor.execute {
                qsController.get().onAppLockerUpdated(packageName)
                refreshState()
            }
        }
    }

    fun addRefreshListener(listener: Runnable) {
        refreshListeners.addIfAbsent(listener)
    }

    fun removeRefreshListener(listener: Runnable) {
        refreshListeners.remove(listener)
    }

    private fun refreshState() {
        refreshListeners.forEach { it.run() }
    }

    private fun clearSessionCacheFor(packageName: String) {
        sessionAuthCache.keys.removeAll { it.endsWith(":$packageName") }
    }

    private fun registerListeners() {
        if (listenersRegistered) return
        val manager = sandboxManager ?: return
        try {
            manager.registerAppLockStateListener(lockStateListener)
            manager.registerAppSessionListener(sessionListener)
            listenersRegistered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to register listeners", e)
        }
    }

    override fun start() {
        keyguardStateController.addCallback(keyguardCallback)
        registerListeners()
    }

    fun getState(packageName: String): AppLockState = getState(packageName, UserHandle.USER_SYSTEM)

    private fun getState(packageName: String, userId: Int): AppLockState {
        if (packageName.isBlank()) return AppLockState.NONE
        val manager = sandboxManager ?: return AppLockState.NONE
        return try {
            manager.getAppLockStateForUser(packageName, userId)
        } catch (e: RuntimeException) {
            Log.w(TAG, "getState failed", e)
            AppLockState.NONE
        }
    }

    fun hasAppLock(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        hasLockCache[packageName]?.let { return it }
        val hasLock = getState(packageName).hasAppLock()
        hasLockCache[packageName] = hasLock
        return hasLock
    }

    fun needsAuth(packageName: String, userId: Int): Boolean {
        if (packageName.isBlank()) return false
        if (!hasAppLock(packageName)) return false
        val key = sessionKey(userId, packageName)
        if (notifUnlocks.contains(key)) return false
        sessionAuthCache[key]?.let { return it }
        val needsAuth = getState(packageName, userId).needsAuth()
        sessionAuthCache[key] = needsAuth
        return needsAuth
    }

    fun promptUnlock(packageName: String, userId: Int) {
        val manager = sandboxManager ?: return
        val key = sessionKey(userId, packageName)
        pendingNotifOnly[key] = SystemClock.elapsedRealtime()
        shadeController.get().collapseShade()
        try {
            manager.promptUnlock(packageName, userId)
        } catch (e: RuntimeException) {
            pendingNotifOnly.remove(key)
            Log.e(TAG, "Failed to prompt unlock", e)
        }
    }
}
