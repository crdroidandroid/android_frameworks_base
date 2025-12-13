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
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ScrimUtils
import javax.inject.Inject

@SysUISingleton
class NTForbiddenSwipeDownQSController @Inject constructor(
    private val context: Context
) : ScrimUtils.ScrimEventListener {

    private val enableSwipeDownQS 
        get() = Settings.Secure.getIntForUser(
            context.contentResolver, 
            Settings.Secure.ENABLE_LOCKSCREEN_QUICK_SETTINGS, 1,
            UserHandle.USER_CURRENT) == 1

    private val keyguardShowing get() = ScrimUtils.get().isKeyguardShowing()
    private val dozing get() = ScrimUtils.get().isDozing()

    fun getForbiddenSwipeDownQS(): Boolean = (keyguardShowing || dozing) && !enableSwipeDownQS

    companion object {
        @Volatile
        private var INSTANCE: NTForbiddenSwipeDownQSController? = null

        @JvmStatic
        fun get(context: Context): NTForbiddenSwipeDownQSController {
            return INSTANCE ?: throw IllegalStateException(
                "NTForbiddenSwipeDownQSController not initialized"
            )
        }
    }

    init {
        INSTANCE = this
    }
}
