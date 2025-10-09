/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.window.data.repository

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.view.CrossWindowBlurListeners
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.window.data.repository.WindowRootViewBlurRepository.Companion.isDisableBlurSysPropSet
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

typealias BlurAppliedListener = Consumer<Int>

/** Repository that maintains state for the window blur effect. */
interface WindowRootViewBlurRepository {
    val blurRequestedByShade: MutableStateFlow<Float>

    val scaleRequestedByShade: MutableStateFlow<Float>

    /** Is blur supported based on settings toggle and battery power saver mode. */
    val isBlurSupported: StateFlow<Boolean>

    /** Whether notification row translucency is enabled via user setting. */
    val isTranslucentSupported: StateFlow<Boolean>

    /** Whether lockscreen notification translucency is enabled via user setting. */
    val isLockscreenTranslucentSupported: StateFlow<Boolean>

    var blurAppliedListener: BlurAppliedListener?

    /** true when tracking shade motion that might lead to a shade expansion. */
    val trackingShadeMotion: MutableStateFlow<Boolean>

    companion object {
        /**
         * Whether the `persist.sysui.disableBlur` is set, this is used to disable blur for tests.
         */
        @JvmStatic
        fun isDisableBlurSysPropSet() = SystemProperties.getBoolean(DISABLE_BLUR_PROPERTY, false)

        // property that can be used to disable the cross window blur for tests
        private const val DISABLE_BLUR_PROPERTY = "persist.sysui.disableBlur"
    }
}

@SysUISingleton
class WindowRootViewBlurRepositoryImpl
@Inject
constructor(
    crossWindowBlurListeners: CrossWindowBlurListeners,
    @Main private val executor: Executor,
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
) : WindowRootViewBlurRepository {

    override val trackingShadeMotion = MutableStateFlow(false)

    override val blurRequestedByShade = MutableStateFlow(0.0f)

    override val scaleRequestedByShade = MutableStateFlow(1.0f)

    override val isBlurSupported: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val sendUpdate = { value: Boolean ->
                    trySendWithFailureLogging(
                        isBlurAllowed() && value,
                        TAG,
                        "unable to send blur enabled/disable state change",
                    )
                }
                crossWindowBlurListeners.addListener(executor, sendUpdate)
                sendUpdate(crossWindowBlurListeners.isCrossWindowBlurEnabled)

                awaitClose { crossWindowBlurListeners.removeListener(sendUpdate) }
            } // stateIn because this is backed by a binder call.
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isTranslucentSupported: StateFlow<Boolean> =
        conflatedCallbackFlow {
            val sendUpdate = {
                trySendWithFailureLogging(
                    isTranslucentEnabled(),
                    TAG,
                    "unable to send notificationRowTransparency state change",
                )
            }
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) = sendUpdate()
                }
            val resolver = context.contentResolver
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_ROW_TRANSPARENCY),
                true,
                observer,
            )
            sendUpdate()
            awaitClose { resolver.unregisterContentObserver(observer) }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), isTranslucentEnabled())

    override val isLockscreenTranslucentSupported: StateFlow<Boolean> =
        conflatedCallbackFlow {
            val sendUpdate = {
                trySendWithFailureLogging(
                    isLockscreenTranslucentEnabled(),
                    TAG,
                    "unable to send notificationRowTransparency lockscreen state change",
                )
            }
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) = sendUpdate()
                }
            val resolver = context.contentResolver
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(
                    Settings.Secure.NOTIFICATION_ROW_TRANSPARENCY_LOCKSCREEN,
                ),
                true,
                observer,
            )
            sendUpdate()
            awaitClose { resolver.unregisterContentObserver(observer) }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), isLockscreenTranslucentEnabled())

    override var blurAppliedListener: BlurAppliedListener? = null

    private fun isTranslucentEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.NOTIFICATION_ROW_TRANSPARENCY,
            1,
            UserHandle.USER_CURRENT,
        ) == 1

    private fun isLockscreenTranslucentEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.NOTIFICATION_ROW_TRANSPARENCY_LOCKSCREEN,
            1, UserHandle.USER_CURRENT) == 1

    private fun isBlurAllowed(): Boolean {
        return ActivityManager.isHighEndGfx() && !isDisableBlurSysPropSet()
    }

    companion object {
        const val TAG = "WindowRootViewBlurRepository"
    }
}
