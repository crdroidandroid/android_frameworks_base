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

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.android.axion.platform.IAxPlatformCallback
import com.android.axion.platform.IAxPlatformService
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class AxPlatformServiceImpl @Inject constructor(
    private val context: Context,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val stateManager: AxPlatformStateManager,
    private val featureController: AxPlatformFeatureController,
    private val featureMapper: AxPlatformFeatureMapper,
    private val observers: AxPlatformObservers
) : CoreStartable {

    private val binderStub = object : IAxPlatformService.Stub() {

        override fun toggle(feature: String?) {
            enforceSystemCaller()
            if (feature.isNullOrEmpty()) return
            scope.launch(mainDispatcher) { featureController.toggle(feature) }
        }

        override fun setEnabled(feature: String?, enabled: Boolean) {
            enforceSystemCaller()
            if (feature.isNullOrEmpty()) return
            scope.launch(mainDispatcher) { featureController.setEnabled(feature, enabled) }
        }

        override fun setValue(feature: String?, value: Int) {
            enforceSystemCaller()
            if (feature.isNullOrEmpty()) return
            scope.launch(mainDispatcher) { featureController.setValue(feature, value) }
        }

        override fun performAction(feature: String?, param: String?) {
            enforceSystemCaller()
            if (feature.isNullOrEmpty() || param.isNullOrEmpty()) return
            scope.launch(mainDispatcher) { featureController.performAction(feature, param) }
        }

        override fun getState(feature: String?): Bundle =
            if (feature != null) stateManager.getState(feature) else Bundle.EMPTY

        override fun getAllStates(): Bundle = stateManager.getAllStates()

        override fun getSupportedFeatures(): Array<String> = featureController.supportedFeatures

        override fun registerCallback(callback: IAxPlatformCallback?) {
            callback?.let { stateManager.registerCallback(it) }
        }

        override fun unregisterCallback(callback: IAxPlatformCallback?) {
            callback?.let { stateManager.unregisterCallback(it) }
        }
    }

    override fun start() {
        stateManager.supportedFeatures = featureController.supportedFeatures
        stateManager.labelProvider = featureMapper
        sBinder = binderStub
        Log.i(TAG, "AxPlatform service ready")
        observers.registerAll()
    }

    private fun enforceSystemCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return
        val token = Binder.clearCallingIdentity()
        try {
            val packages = context.packageManager.getPackagesForUid(uid)
            val allowed = packages?.any {
                it.startsWith("com.android.axion.") ||
                    it == "com.android.systemui" ||
                    it == "io.chaldeaprjkt.gamespace"
            } == true
            if (!allowed) {
                throw SecurityException("AxPlatformService: caller uid=$uid not permitted")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    companion object {
        private const val TAG = "AxPlatformService"
        @Volatile
        internal var sBinder: IBinder? = null
    }
}
