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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import android.util.Log
import com.android.systemui.KairosBuilder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.map
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.createNumberOfLevelsState
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import javax.inject.Inject

/**
 * A repository implementation for a carrier merged (aka VCN) network. A carrier merged network is
 * delivered to SysUI as a wifi network (see [WifiNetworkModel.CarrierMerged], but is visually
 * displayed as a mobile network triangle.
 *
 * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
 *
 * See [MobileConnectionRepositoryImpl] for a repository implementation of a typical mobile
 * connection.
 */
@ExperimentalKairosApi
class CarrierMergedConnectionRepositoryKairos(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
    private val telephonyManager: TelephonyManager,
    systemUiCarrierConfig: SystemUiCarrierConfig,
    val wifiRepository: WifiRepository,
    override val isInEcmMode: State<Boolean>,
) : MobileConnectionRepositoryKairos, KairosBuilder by kairosBuilder() {
    init {
        if (telephonyManager.subscriptionId != subId) {
            error(
                """CarrierMergedRepo: TelephonyManager should be created with subId($subId).
                    | Found ${telephonyManager.subscriptionId} instead."""
                    .trimMargin()
            )
        }
    }

    private val isWifiEnabled: State<Boolean> = buildState {
        wifiRepository.isWifiEnabled.toState(
            nameTag("CarrierMergedConnectionRepositoryKairos.isWifiEnabled")
        )
    }
    private val isWifiDefault: State<Boolean> = buildState {
        wifiRepository.isWifiDefault.toState(
            nameTag("CarrierMergedConnectionRepositoryKairos.isWifiDefault")
        )
    }
    private val wifiNetwork: State<WifiNetworkModel> = buildState {
        wifiRepository.wifiNetwork.toState(
            nameTag("CarrierMergedConnectionRepositoryKairos.wifiNetwork")
        )
    }

    /**
     * Outputs the carrier merged network to use, or null if we don't have a valid carrier merged
     * network.
     */
    private val network: State<WifiNetworkModel.CarrierMerged?> =
        combine(isWifiEnabled, isWifiDefault, wifiNetwork) { isEnabled, isDefault, network ->
            when {
                !isEnabled -> null
                !isDefault -> null
                network !is WifiNetworkModel.CarrierMerged -> null
                network.subscriptionId != subId -> {
                    Log.w(
                        TAG,
                        """Connection repo subId=$subId does not equal wifi repo
                            | subId=${network.subscriptionId}; not showing carrier merged"""
                            .trimMargin(),
                    )
                    null
                }
                else -> network
            }
        }

    override val cdmaRoaming: State<Boolean> = stateOf(ROAMING)

    override val networkName: State<NetworkNameModel> =
        // The SIM operator name should be the same throughout the lifetime of a subId, **but**
        // it may not be available when this repo is created because it takes time to load. To
        // be safe, we re-fetch it each time the network has changed.
        network.map { NetworkNameModel.SimDerived(telephonyManager.simOperatorName) }

    override val carrierName: State<NetworkNameModel>
        get() = networkName

    private val defaultNumberOfLevels: State<Int> =
        wifiNetwork.map {
            if (it is WifiNetworkModel.CarrierMerged) {
                it.numberOfLevels
            } else {
                DEFAULT_NUM_LEVELS
            }
        }

    override val inflateSignalStrength: State<Boolean> = buildState {
        systemUiCarrierConfig.shouldInflateSignalStrength.toState(
            nameTag("CarrierMergedConnectionRepositoryKairos.inflateSignalStrength")
        )
    }

    override val numberOfLevels: State<Int> =
        createNumberOfLevelsState(inflateSignalStrength, defaultNumberOfLevels)

    override val primaryLevel: State<Int> =
        network.map { it?.level ?: SIGNAL_STRENGTH_NONE_OR_UNKNOWN }

    override val cdmaLevel: State<Int> =
        network.map { it?.level ?: SIGNAL_STRENGTH_NONE_OR_UNKNOWN }

    override val dataActivityDirection: State<DataActivityModel> = buildState {
        wifiRepository.wifiActivity.toState(
            nameTag("CarrierMergedConnectionRepositoryKairos.dataActivityDirection")
        )
    }

    override val resolvedNetworkType: State<ResolvedNetworkType> =
        network.map {
            if (it != null) {
                ResolvedNetworkType.CarrierMergedNetworkType
            } else {
                ResolvedNetworkType.UnknownNetworkType
            }
        }

    override val dataConnectionState: State<DataConnectionState> =
        network.map {
            if (it != null) {
                DataConnectionState.Connected
            } else {
                DataConnectionState.Disconnected
            }
        }

    override val isRoaming: State<Boolean> = stateOf(false)
    override val carrierId: State<Int> = stateOf(INVALID_SUBSCRIPTION_ID)
    override val allowNetworkSliceIndicator: State<Boolean> = stateOf(false)
    override val isEmergencyOnly: State<Boolean> = stateOf(false)
    override val operatorAlphaShort: State<String?> = stateOf(null)
    override val isInService: State<Boolean> = stateOf(true)
    override val isNonTerrestrial: State<Boolean> = stateOf(false)
    override val isGsm: State<Boolean> = stateOf(false)
    override val carrierNetworkChangeActive: State<Boolean> = stateOf(false)
    override val satelliteLevel: State<Int> = stateOf(0)

    /**
     * Carrier merged connections happen over wifi but are displayed as a mobile triangle. Because
     * they occur over wifi, it's possible to have a valid carrier merged connection even during
     * airplane mode. See b/291993542.
     */
    override val isAllowedDuringAirplaneMode: State<Boolean> = stateOf(true)

    /**
     * It's not currently considered possible that a carrier merged network can have these
     * prioritized capabilities. If we need to track them, we can add the same check as is in
     * [MobileConnectionRepositoryImpl].
     */
    override val hasPrioritizedNetworkCapabilities: State<Boolean> = stateOf(false)

    override val imsState: State<ImsStateModel> = stateOf(ImsStateModel())

    override val dataEnabled: State<Boolean>
        get() = isWifiEnabled

    override fun setDataEnabled(enabled: Boolean) {
        telephonyManager.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, enabled)
    }

    companion object {
        // Carrier merged is never roaming
        private const val ROAMING = false
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        private val telephonyManager: TelephonyManager,
        private val carrierConfigRepository: CarrierConfigRepository,
        private val wifiRepository: WifiRepository,
    ) {
        fun build(
            subId: Int,
            mobileLogger: TableLogBuffer,
            mobileRepo: MobileConnectionRepositoryKairos,
        ): CarrierMergedConnectionRepositoryKairos {
            return CarrierMergedConnectionRepositoryKairos(
                subId,
                mobileLogger,
                telephonyManager.createForSubscriptionId(subId),
                carrierConfigRepository.getOrCreateConfigForSubId(subId),
                wifiRepository,
                mobileRepo.isInEcmMode,
            )
        }
    }
}

private const val TAG = "CarrierMergedConnectionRepository"
