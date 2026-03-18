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

package com.android.systemui.qs.tiles.impl.wifi.domain.interactor

import android.annotation.StringRes
import android.content.Context
import android.os.UserHandle
import android.text.Html
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.ui.model.WifiToggleState
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiTileIconModel
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Observes wifi tile state changes providing the [WifiTileModel]. */
class WifiTileDataInteractor
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @Application private val scope: CoroutineScope,
    airplaneModeRepository: AirplaneModeRepository,
    private val connectivityRepository: ConnectivityRepository,
    mobileIconsInteractor: MobileIconsInteractor,
    mobileContextProvider: MobileContextProvider,
    val wifiInteractor: WifiInteractor,
) : QSTileDataInteractor<WifiTileModel> {

    private val wifiIconFlow: Flow<WifiTileModel> =
        wifiInteractor.wifiNetwork.flatMapLatest {
            val wifiIcon = WifiIcon.fromModel(it, context, showHotspotInfo = true)
            if (it is WifiNetworkModel.Active && wifiIcon is WifiIcon.Visible) {
                val secondary = removeDoubleQuotes(it.ssid)
                flowOf(
                    WifiTileModel.Active(
                        icon = WifiTileIconModel(wifiIcon.icon.resId),
                        secondaryLabel = secondary,
                    )
                )
            } else {
                flowOf(
                    WifiTileModel.Inactive(
                        icon = WifiTileIconModel(WifiIcons.WIFI_NO_SIGNAL),
                        secondaryLabel = null,
                    )
                )
            }
        }

    private val mobileDataContentName: Flow<CharSequence?> =
        mobileIconsInteractor.activeDataIconInteractor.flatMapLatest {
            if (it == null) {
                flowOf(null)
            } else {
                combine(it.isRoaming, it.networkTypeIconGroup) { isRoaming, networkTypeIconGroup ->
                    val mobileContext =
                        mobileContextProvider.getMobileContextForSub(it.subscriptionId, context)
                    val cd = loadString(networkTypeIconGroup.contentDescription, mobileContext)
                    if (isRoaming) {
                        val roaming = mobileContext.getString(R.string.data_connection_roaming)
                        if (cd != null) {
                            mobileContext.getString(R.string.mobile_data_text_format, roaming, cd)
                        } else {
                            roaming
                        }
                    } else {
                        cd
                    }
                }
            }
        }

    private val mobileDescriptionFlow: Flow<CharSequence> =
        mobileIconsInteractor.activeDataIconInteractor.flatMapLatest {
            if (it == null) {
                notConnectedDescriptionFlow
            } else {
                it.isDataConnected.flatMapLatest { isConnected ->
                    if (!isConnected) {
                        notConnectedDescriptionFlow
                    } else {
                        combine(it.networkName, it.signalLevelIcon, mobileDataContentName) {
                                networkNameModel,
                                signalIcon,
                                dataContentDescription ->
                                Triple(networkNameModel, signalIcon, dataContentDescription)
                            }
                            .mapLatestConflated {
                                (networkNameModel, signalIcon, dataContentDescription) ->
                                when (signalIcon) {
                                    is SignalIconModel.Cellular -> {
                                        mobileDataContentConcat(
                                            networkNameModel.name,
                                            dataContentDescription,
                                        )
                                    }
                                    is SignalIconModel.Satellite -> {
                                        val satelliteDescription =
                                            signalIcon.icon.contentDescription
                                                ?.loadContentDescription(context)
                                        if (satelliteDescription.isNullOrBlank()) {
                                            notConnectedDescriptionFlow.value
                                        } else {
                                            satelliteDescription
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }

    private fun mobileDataContentConcat(
        networkName: String?,
        dataContentDescription: CharSequence?,
    ): CharSequence {
        if (dataContentDescription == null) {
            return networkName ?: ""
        }
        if (networkName == null) {
            return Html.fromHtml(dataContentDescription.toString(), 0)
        }

        return Html.fromHtml(
            context.getString(
                R.string.mobile_carrier_text_format,
                networkName,
                dataContentDescription,
            ),
            0,
        )
    }

    private fun loadString(@StringRes resId: Int, context: Context): CharSequence? =
        if (resId != 0) {
            context.getString(resId)
        } else {
            null
        }

    private val notConnectedDescriptionFlow: StateFlow<CharSequence> =
        combine(wifiInteractor.areNetworksAvailable, airplaneModeRepository.isAirplaneMode) {
                networksAvailable,
                isAirplaneMode ->
                when {
                    isAirplaneMode -> {
                        context.getString(R.string.status_bar_airplane)
                    }
                    networksAvailable -> {
                        context.getString(R.string.quick_settings_networks_available)
                    }
                    else -> {
                        context.getString(R.string.quick_settings_networks_unavailable)
                    }
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                context.getString(R.string.quick_settings_networks_unavailable),
            )

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<WifiTileModel> = tileData()

    fun tileData(): Flow<WifiTileModel> =
        combine(
            connectivityRepository.defaultConnections,
            wifiInteractor.wifiToggleState,
            wifiInteractor.isEnabled,
            wifiIconFlow,
            notConnectedDescriptionFlow,
        ) { defaultConnections, toggleState, isEnabled, wifiModel, notConnectedDescription ->
            if (defaultConnections.ethernet.isDefault) {
                return@combine if (defaultConnections.isValidated) {
                    WifiTileModel.Active(
                        icon = WifiTileIconModel(R.drawable.stat_sys_ethernet_fully),
                        secondaryLabel =
                            context.getString(
                                com.android.settingslib.R.string.accessibility_ethernet_connected
                            ),
                    )
                } else {
                    WifiTileModel.Active(
                        icon = WifiTileIconModel(R.drawable.stat_sys_ethernet),
                        secondaryLabel =
                            context.getString(
                                com.android.settingslib.R.string.accessibility_ethernet_connected
                            ),
                    )
                }
            }

            if (toggleState == WifiToggleState.Pausing) {
                return@combine WifiTileModel.Inactive(
                    icon = WifiTileIconModel(WifiIcons.WIFI_NO_SIGNAL),
                    secondaryLabel = notConnectedDescription,
                )
            } else if (toggleState == WifiToggleState.Scanning) {
                return@combine WifiTileModel.Active(
                    icon = WifiTileIconModel(WifiIcons.WIFI_NO_SIGNAL),
                    secondaryLabel = context.getString(R.string.quick_settings_scanning_for_wifi),
                )
            }

            if (defaultConnections.wifi.isDefault) {
                return@combine WifiTileModel.Active(
                    icon = wifiModel.icon,
                    secondaryLabel = wifiModel.secondaryLabel,
                )
            }

            WifiTileModel.Inactive(
                icon =
                    WifiTileIconModel(
                        if (isEnabled) {
                            WifiIcons.WIFI_NO_SIGNAL
                        } else {
                            R.drawable.ic_signal_wifi_off
                        }
                    ),
                secondaryLabel = notConnectedDescription,
            )
        }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(isAvailable())

    fun isAvailable(): Boolean = AconfigFlags.qsSplitInternetTile()

    private companion object {
        fun removeDoubleQuotes(string: String?): String? {
            if (string == null) return null
            return if (string.firstOrNull() == '"' && string.lastOrNull() == '"') {
                string.substring(1, string.length - 1)
            } else string
        }

        fun ContentDescription.toText(): Text =
            when (this) {
                is ContentDescription.Loaded -> Text.Loaded(this.description)
                is ContentDescription.Resource -> Text.Resource(this.res)
            }
    }
}
