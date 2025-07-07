/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.statusbar

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.SystemUIApplication
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ScrimUtils
import javax.inject.Inject

@SysUISingleton
class NTForbiddenSwipeDownQSController @Inject constructor(
    private val context: Context
) : ScrimUtils.ScrimEventListener {

    private var enableSwipeDownQS: Int = ENABLE
    private var forbiddenSwipeDownQS: Boolean = false
    private var keyguardShowing: Boolean = false
    private var listening = false

    init {
        registerSettingsObserver()
        updateSettings()
    }

    fun getForbiddenSwipeDownQS(): Boolean = forbiddenSwipeDownQS
    fun setForbiddenSwipeDownQS(value: Boolean) {
        forbiddenSwipeDownQS = value
    }

    private fun registerSettingsObserver() {
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(
            Settings.Secure.ENABLE_LOCKSCREEN_QUICK_SETTINGS),
            false,
            object : ContentObserver(Handler()) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    updateSettings()
                }
            }, 
            UserHandle.USER_ALL)
    }

    private fun updateSettings() {
        enableSwipeDownQS = Settings.Secure.getIntForUser(context.contentResolver,
            Settings.Secure.ENABLE_LOCKSCREEN_QUICK_SETTINGS, ENABLE, UserHandle.USER_CURRENT)
        if (enableSwipeDownQS == DISABLE && !listening) {
            ScrimUtils.get().addListener(this)
            listening = true
        } else if (enableSwipeDownQS == ENABLE && listening) {
            ScrimUtils.get().removeListener(this)
            listening = false
        }
        updateForbiddenSwipeDownState()
    }

    private fun updateForbiddenSwipeDownState() {
        forbiddenSwipeDownQS = keyguardShowing && enableSwipeDownQS == DISABLE
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        keyguardShowing = showing
        updateForbiddenSwipeDownState()
    }

    companion object {
        private const val TAG = "ForbiddenSwipeDownQSController"
        private const val ENABLE = 1
        private const val DISABLE = 0
        @JvmStatic
        fun get(context: Context): NTForbiddenSwipeDownQSController {
            val app = context.applicationContext as SystemUIApplication
            return app.sysUIComponent.forbiddenSwipeDownQSController()
        }
    }
}
