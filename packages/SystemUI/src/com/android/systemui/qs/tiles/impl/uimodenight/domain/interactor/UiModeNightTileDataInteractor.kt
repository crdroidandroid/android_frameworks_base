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

package com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.model.UiModeNightTileModel
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.util.time.DateFormatUtil
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Observes ui mode night state changes providing the [UiModeNightTileModel]. */
class UiModeNightTileDataInteractor
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val uiModeManager: UiModeManager,
    private val batteryController: BatteryController,
    private val locationController: LocationController,
    private val dateFormatUtil: DateFormatUtil,
) : QSTileDataInteractor<UiModeNightTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<UiModeNightTileModel> =
        conflatedCallbackFlow {
            // send initial state
            trySend(createModel(user))

            val configurationCallback =
                object : ConfigurationController.ConfigurationListener {
                    override fun onUiModeChanged() {
                        trySend(createModel(user))
                    }
                }
            configurationController.addCallback(configurationCallback)

            val batteryCallback =
                object : BatteryController.BatteryStateChangeCallback {
                    override fun onPowerSaveChanged(isPowerSave: Boolean) {
                        trySend(createModel(user))
                    }
                }
            batteryController.addCallback(batteryCallback)

            val locationCallback =
                object : LocationController.LocationChangeCallback {
                    override fun onLocationSettingsChanged(locationEnabled: Boolean) {
                        trySend(createModel(user))
                    }
                }
            locationController.addCallback(locationCallback)

            val batterySaverDarkThemeObserver =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(createModel(user))
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_BATTERY_SAVER_DARK_THEME),
                false,
                batterySaverDarkThemeObserver,
            )

            awaitClose {
                configurationController.removeCallback(configurationCallback)
                batteryController.removeCallback(batteryCallback)
                locationController.removeCallback(locationCallback)
                context.contentResolver.unregisterContentObserver(batterySaverDarkThemeObserver)
            }
        }

    private fun createModel(user: UserHandle): UiModeNightTileModel {
        val uiMode = uiModeManager.nightMode
        val nightMode =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val powerSave = batteryController.isPowerSave && isBatterySaverDarkThemeEnabled(user)
        val locationEnabled = locationController.isLocationEnabled
        val nightModeCustomType = uiModeManager.nightModeCustomType
        val use24HourFormat = dateFormatUtil.is24HourFormat
        val customNightModeEnd = uiModeManager.customNightModeEnd
        val customNightModeStart = uiModeManager.customNightModeStart

        return UiModeNightTileModel(
            uiMode,
            nightMode,
            powerSave,
            locationEnabled,
            nightModeCustomType,
            use24HourFormat,
            customNightModeEnd,
            customNightModeStart,
        )
    }

    private fun isBatterySaverDarkThemeEnabled(user: UserHandle): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver, SETTING_BATTERY_SAVER_DARK_THEME, 1, user.identifier) != 0

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)

    companion object {
        private const val SETTING_BATTERY_SAVER_DARK_THEME = "battery_saver_dark_theme"
    }
}
