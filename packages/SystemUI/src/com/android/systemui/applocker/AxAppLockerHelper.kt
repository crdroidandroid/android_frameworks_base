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
package com.android.systemui.applocker

import android.app.AxSandboxManager
import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.android.internal.app.IAppLockStateListener
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.QuickSettingsControllerImpl
import com.android.systemui.util.TaskWorkerManager
import java.util.concurrent.ConcurrentHashMap
import com.android.systemui.shade.ShadeController
import javax.inject.Inject
import dagger.Lazy

@SysUISingleton
class AxAppLockerHelper @Inject constructor(
    private val context: Context,
    private val qsController: Lazy<QuickSettingsControllerImpl>,
    private val sandboxManager: AxSandboxManager?,
    private val shadeController: Lazy<ShadeController>
): CoreStartable {

    companion object {
        private const val TAG = "AxAppLockerHelper"
    }

    private val appLockCache = ConcurrentHashMap<String, Boolean>()
    private var listenerRegistered = false

    private val listener = object : IAppLockStateListener.Stub() {
        override fun onAppLockStateChanged(packageName: String, locked: Boolean) {
            appLockCache[packageName] = locked
            TaskWorkerManager.instance.taskWorker.postDelayed({
                qsController.get().onAppLockerUpdated(packageName)
            }, 500L)
        }
    }

    override fun start() {
        sandboxManager?.registerAppLockStateListener(listener)
    }

    fun isAppLocked(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        appLockCache[packageName]?.let { return it }
        val locked = try {
            sandboxManager?.isAppLocked(packageName) ?: false
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in isAppLocked: ${e.message}")
            false
        }
        appLockCache[packageName] = locked
        return locked
    }

    fun isAppLockedWithoutCache(packageName: String): Boolean {
        return try {
            sandboxManager?.isAppLocked(packageName) ?: false
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in isAppLockedWithoutCache: ${e.message}")
            false
        }
    }

    fun promptUnlock(packageName: String, userId: Int) {
        shadeController.get().collapseShade()
        try {
            sandboxManager?.promptUnlock(packageName, userId)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to prompt unlock", e)
        }
    }
}
