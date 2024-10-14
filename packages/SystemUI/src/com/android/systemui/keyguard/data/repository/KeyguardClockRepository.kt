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

package com.android.systemui.keyguard.data.repository

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.keyguard.ClockEventController
import com.android.systemui.animation.GSFAxes
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockId
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

interface KeyguardClockRepository {
    /**
     * clock size determined by notificationPanelViewController, LARGE or SMALL
     *
     * @deprecated When scene container flag is on use clockSize from domain level.
     */
    val clockSize: StateFlow<ClockSize>

    /** clock size selected in picker, DYNAMIC or SMALL */
    val selectedClockSize: StateFlow<ClockSizeSetting>

    /** clock id, selected from clock carousel in wallpaper picker */
    val currentClockId: Flow<ClockId>

    val currentClockFontAxesWidth: Float?

    val currentClock: StateFlow<ClockController?>

    val clockEventController: ClockEventController

    val forcedClockSize: Flow<ClockSize?>

    fun setClockSize(size: ClockSize)
}

@SysUISingleton
class KeyguardClockRepositoryImpl
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val clockRegistry: ClockRegistry,
    override val clockEventController: ClockEventController,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val applicationScope: CoroutineScope,
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware configurationRepository: ConfigurationRepository,
    private val featureFlags: FeatureFlagsClassic,
) : KeyguardClockRepository {
    /** Receive SMALL or LARGE clock should be displayed on keyguard. */
    private val _clockSize: MutableStateFlow<ClockSize> = MutableStateFlow(ClockSize.LARGE)
    override val clockSize: StateFlow<ClockSize> = _clockSize.asStateFlow()
    override val forcedClockSize: Flow<ClockSize?> =
        if (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)) {
            configurationRepository.onAnyConfigurationChange.map {
                if (
                    context.resources.getBoolean(R.bool.force_small_clock_on_lockscreen) ||
                    secureSettings.getIntForUser(
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                        0, // Default value
                        UserHandle.USER_CURRENT
                    ) != 0
                ) {
                    ClockSize.SMALL
                } else {
                    null
                }
            }
        } else {
            flowOf<ClockSize?>(null)
        }

    override fun setClockSize(size: ClockSize) {
        SceneContainerFlag.assertInLegacyMode()
        _clockSize.value = size
    }

    override val selectedClockSize: StateFlow<ClockSizeSetting> =
        secureSettings
            .observerFlow(
                names = arrayOf(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                userId = UserHandle.USER_ALL,
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { withContext(backgroundDispatcher) { getClockSize() } }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = getClockSize(),
            )

    override val currentClockId: Flow<ClockId> =
        callbackFlow {
                fun send() {
                    trySend(clockRegistry.currentClockId)
                }

                val listener =
                    object : ClockRegistry.ClockChangeListener {
                        override fun onCurrentClockChanged() {
                            send()
                        }
                    }
                clockRegistry.registerClockChangeListener(listener)
                send()
                awaitClose { clockRegistry.unregisterClockChangeListener(listener) }
            }
            .mapNotNull { it }

    override val currentClockFontAxesWidth: Float?
        get() = clockRegistry.settings?.axes?.get(GSFAxes.WIDTH.tag)

    override val currentClock: StateFlow<ClockController?> =
        currentClockId
            .map {
                clockEventController.clock = clockRegistry.createCurrentClock(context)
                clockEventController.clock
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )

    private fun getClockSize(): ClockSizeSetting {
        val isDoubleLineClock = secureSettings.getIntForUser(
            Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK,
            context.resources.getInteger(
                com.android.internal.R.integer.config_doublelineClockDefault
            ),
            UserHandle.USER_CURRENT
        )
        val clockStyleEnabled = secureSettings.getIntForUser(
            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
            0, // Default value
            UserHandle.USER_CURRENT
        ) != 0
        val clockSettingValue = if (clockStyleEnabled) {
            0 
        } else {
            isDoubleLineClock
        }
        return ClockSizeSetting.fromSettingValue(clockSettingValue)
    }
}
