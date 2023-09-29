/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.wmshell

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.android.systemui.shared.wmshell.IWMShellProxy
import com.android.wm.shell.sysui.ExternalInterfaceProvider

/** This service acts as a proxy between apps and WM Shell. */
class WMShellProxyService : Service() {

    private lateinit var mExternalInterfaceProvider: ExternalInterfaceProvider

    // Binder for calls within the SystemUI process.
    private inner class LocalBinder : Binder() {
        fun getService() = this@WMShellProxyService
    }

    private val mLocalBinder = LocalBinder()

    private val mRemoteBinder =
        object : IWMShellProxy.Stub() {
            override fun createExternalInterface(key: String): IBinder {
                Log.d(TAG, "createExternalInterface")
                enforceCallingPermission(
                    Manifest.permission.MANAGE_ACTIVITY_TASKS,
                    "createExternalInterface"
                )
                return mExternalInterfaceProvider.createExternalInterface(key)
            }
        }

    override fun onBind(intent: Intent) =
        if (intent.action?.equals(ACTION_BIND_LOCAL) == true) {
            mLocalBinder
        } else {
            mRemoteBinder
        }

    private fun initialize(externalInterfaceProvider: ExternalInterfaceProvider) {
        Log.d(TAG, "initialize")
        mExternalInterfaceProvider = externalInterfaceProvider
    }

    companion object {
        const val TAG = "WMShellProxyService"
        private const val ACTION_BIND_LOCAL = "action_bind_local"

        fun initialize(
            context: Context,
            externalInterfaceProvider: ExternalInterfaceProvider,
        ) {
            context.bindService(
                Intent(context, WMShellProxyService::class.java).apply {
                    action = ACTION_BIND_LOCAL
                },
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                        with((binder as LocalBinder)) {
                            getService().initialize(externalInterfaceProvider)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                },
                Context.BIND_AUTO_CREATE or Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
            )
        }
    }
}
