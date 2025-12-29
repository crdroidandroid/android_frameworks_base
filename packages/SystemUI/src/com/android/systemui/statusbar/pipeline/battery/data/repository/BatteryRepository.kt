/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.battery.data.repository

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import lineageos.providers.LineageSettings

/** Repository-style state for battery information. */
interface BatteryRepository {
    /**
     * True if the phone is plugged in. Note that this does not always mean the device is charging
     */
    val isPluggedIn: Flow<Boolean>

    /** Is power saver enabled */
    val isPowerSaveEnabled: Flow<Boolean>

    /** Battery defender means the device is plugged in but not charging to protect the battery */
    val isBatteryDefenderEnabled: Flow<Boolean>

    /** True if the system has detected an incompatible charger (and thus is not charging) */
    val isIncompatibleCharging: Flow<Boolean>

    /** The current level [0-100] */
    val level: Flow<Int?>

    /** State unknown means that we can't detect a battery */
    val isStateUnknown: Flow<Boolean>

    /**
     * [LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT]. A user setting to indicate whether
     * we should show the battery percentage in the home screen status bar
     */
    val showBatteryPercentMode: StateFlow<Int>

    companion object {
        const val SHOW_PERCENT_HIDDEN = 0
        const val SHOW_PERCENT_INSIDE = 1
        const val SHOW_PERCENT_NEXT_TO = 2
    }

    /**
     * If available, this flow yields a string that describes the approximate time remaining for the
     * current battery charge and usage information. While subscribed, the estimate is updated every
     * 2 minutes.
     */
    val batteryTimeRemainingEstimate: Flow<String?>
}

/**
 * Currently we just use the [BatteryController] as our source of truth, but we could (should?)
 * migrate away from that eventually.
 */
@SysUISingleton
class BatteryRepositoryImpl
@Inject
constructor(
    @Application context: Context,
    @Background scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    private val controller: BatteryController,
    settingsRepository: SystemSettingsRepository,
) : BatteryRepository {
    private val batteryState: StateFlow<BatteryCallbackState> =
        conflatedCallbackFlow<(BatteryCallbackState) -> BatteryCallbackState> {
                val callback =
                    object : BatteryController.BatteryStateChangeCallback {
                        override fun onBatteryLevelChanged(
                            level: Int,
                            pluggedIn: Boolean,
                            charging: Boolean,
                        ) {
                            trySend { prev -> prev.copy(level = level, isPluggedIn = pluggedIn) }
                        }

                        override fun onPowerSaveChanged(isPowerSave: Boolean) {
                            trySend { prev -> prev.copy(isPowerSaveEnabled = isPowerSave) }
                        }

                        override fun onIsBatteryDefenderChanged(isBatteryDefender: Boolean) {
                            trySend { prev ->
                                prev.copy(isBatteryDefenderEnabled = isBatteryDefender)
                            }
                        }

                        override fun onIsIncompatibleChargingChanged(
                            isIncompatibleCharging: Boolean
                        ) {
                            trySend { prev ->
                                prev.copy(isIncompatibleCharging = isIncompatibleCharging)
                            }
                        }

                        override fun onBatteryUnknownStateChanged(isUnknown: Boolean) {
                            // If the state is unknown, then all other fields are invalid
                            trySend { prev ->
                                if (isUnknown) {
                                    // Forget everything before now
                                    BatteryCallbackState(isStateUnknown = true)
                                } else {
                                    prev.copy(isStateUnknown = false)
                                }
                            }
                        }
                    }

                controller.addCallback(callback)
                awaitClose { controller.removeCallback(callback) }
            }
            .scan(initial = BatteryCallbackState()) { state, eventF -> eventF(state) }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.Lazily, BatteryCallbackState())

    override val isPluggedIn = batteryState.map { it.isPluggedIn }

    override val isPowerSaveEnabled = batteryState.map { it.isPowerSaveEnabled }

    override val isBatteryDefenderEnabled = batteryState.map { it.isBatteryDefenderEnabled }

    override val isIncompatibleCharging = batteryState.map { it.isIncompatibleCharging }

    override val level = batteryState.map { it.level }

    override val isStateUnknown = batteryState.map { it.isStateUnknown }

    override val showBatteryPercentMode =
        callbackFlow {
                val resolver = context.contentResolver
                val uri =
                    LineageSettings.System.getUriFor(
                        LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT
                    )

                fun readMode(): Int {
                    return LineageSettings.System.getIntForUser(
                        resolver,
                        LineageSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT,
                        BatteryRepository.SHOW_PERCENT_HIDDEN,
                        UserHandle.USER_CURRENT,
                    )
                }

                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(readMode())
                        }
                    }

                resolver.registerContentObserver(
                    uri,
                    /* notifyForDescendants = */ false,
                    observer,
                    UserHandle.USER_ALL,
                )

                // Emit current value immediately
                trySend(readMode())

                awaitClose { resolver.unregisterContentObserver(observer) }
            }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)
            .stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = BatteryRepository.SHOW_PERCENT_HIDDEN,
            )

    /** Get and re-fetch the estimate every 2 minutes while active */
    private val estimate: Flow<String?> = flow {
        while (true) {
            val estimate = fetchEstimate()
            emit(estimate)
            delay(2.minutes)
        }
    }

    override val batteryTimeRemainingEstimate: Flow<String?> = estimate.flowOn(bgDispatcher)

    private suspend fun fetchEstimate() = suspendCancellableCoroutine { continuation ->
        val callback =
            BatteryController.EstimateFetchCompletion { estimate -> continuation.resume(estimate) }

        controller.getEstimatedTimeRemainingString(callback)
    }
}

/** Data object to track the current battery callback state */
private data class BatteryCallbackState(
    val level: Int? = null,
    val isPluggedIn: Boolean = false,
    val isPowerSaveEnabled: Boolean = false,
    val isBatteryDefenderEnabled: Boolean = false,
    val isStateUnknown: Boolean = false,
    val isIncompatibleCharging: Boolean = false,
)
