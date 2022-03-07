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

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import android.content.Context
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.graph.SignalDrawable
import com.android.settingslib.mobile.MobileIconCarrierIdOverrides
import com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl
import com.android.systemui.KairosBuilder
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.map
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Connected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel.DefaultIcon
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel.OverriddenIcon
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.satellite.ui.model.SatelliteIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel

@ExperimentalKairosApi
interface MobileIconInteractorKairos {
    /** The table log created for this connection */
    val tableLogBuffer: TableLogBuffer

    val subscriptionId: Int

    /** The current mobile data activity */
    val activity: State<DataActivityModel>

    /** See [MobileConnectionsRepository.mobileIsDefault]. */
    val mobileIsDefault: State<Boolean>

    /**
     * True when telephony tells us that the data state is CONNECTED. See
     * [android.telephony.TelephonyCallback.DataConnectionStateListener] for more details. We
     * consider this connection to be serving data, and thus want to show a network type icon, when
     * data is connected. Other data connection states would typically cause us not to show the icon
     */
    val isDataConnected: State<Boolean>

    /** True if we consider this connection to be in service, i.e. can make calls */
    val isInService: State<Boolean>

    /** True if this connection is emergency only */
    val isEmergencyOnly: State<Boolean>

    /** Observable for the data enabled state of this connection */
    val isDataEnabled: State<Boolean>

    /** True if the RAT icon should always be displayed and false otherwise. */
    val alwaysShowDataRatIcon: State<Boolean>

    /** Canonical representation of the current mobile signal strength as a triangle. */
    val signalLevelIcon: State<SignalIconModel>

    /** Observable for RAT type (network type) indicator */
    val networkTypeIconGroup: State<NetworkTypeIconModel>

    /** Whether or not to show the slice attribution */
    val showSliceAttribution: State<Boolean>

    /** True if this connection is satellite-based */
    val isNonTerrestrial: State<Boolean>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A derived name based off of the intent [ACTION_SERVICE_PROVIDERS_UPDATED]
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     *    override in [connectionInfo.operatorAlphaShort], a value that is derived from
     *    [ServiceState]
     */
    val networkName: State<NetworkNameModel>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A name provided by the [SubscriptionModel] of this network connection
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     *    override in [connectionInfo.operatorAlphaShort], a value that is derived from
     *    [ServiceState]
     *
     * TODO(b/296600321): De-duplicate this field with [networkName] after determining the data
     *   provided is identical
     */
    val carrierName: State<String>

    /** True if there is only one active subscription. */
    val isSingleCarrier: State<Boolean>

    /**
     * True if this connection is considered roaming. The roaming bit can come from [ServiceState],
     * or directly from the telephony manager's CDMA ERI number value. Note that we don't consider a
     * connection to be roaming while carrier network change is active
     */
    val isRoaming: State<Boolean>

    /** See [MobileIconsInteractor.isForceHidden]. */
    val isForceHidden: State<Boolean>

    /** See [MobileIconsInteractor.isRoamingForceHidden]. */
    val isRoamingForceHidden: State<Boolean>

    /** See [MobileConnectionRepository.isAllowedDuringAirplaneMode]. */
    val isAllowedDuringAirplaneMode: State<Boolean>

    /** True when in carrier network change mode */
    val carrierNetworkChangeActive: State<Boolean>
}

/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
@ExperimentalKairosApi
class MobileIconInteractorKairosImpl(
    defaultSubscriptionHasDataEnabled: State<Boolean>,
    override val alwaysShowDataRatIcon: State<Boolean>,
    alwaysUseCdmaLevel: State<Boolean>,
    override val isSingleCarrier: State<Boolean>,
    override val mobileIsDefault: State<Boolean>,
    defaultMobileIconMapping: State<Map<String, MobileIconGroup>>,
    defaultMobileIconGroup: State<MobileIconGroup>,
    isDefaultConnectionFailed: State<Boolean>,
    override val isForceHidden: State<Boolean>,
    override val isRoamingForceHidden: State<Boolean>,
    private val connectionRepository: MobileConnectionRepositoryKairos,
    private val context: Context,
    private val carrierIdOverrides: MobileIconCarrierIdOverrides =
        MobileIconCarrierIdOverridesImpl(),
) : MobileIconInteractorKairos, KairosBuilder by kairosBuilder() {
    override val subscriptionId: Int
        get() = connectionRepository.subId

    override val tableLogBuffer: TableLogBuffer
        get() = connectionRepository.tableLogBuffer

    override val activity: State<DataActivityModel>
        get() = connectionRepository.dataActivityDirection

    override val isDataEnabled: State<Boolean> = connectionRepository.dataEnabled

    override val carrierNetworkChangeActive: State<Boolean>
        get() = connectionRepository.carrierNetworkChangeActive

    override val networkName: State<NetworkNameModel> =
        combine(connectionRepository.operatorAlphaShort, connectionRepository.networkName) {
            operatorAlphaShort,
            networkName ->
            if (networkName is NetworkNameModel.Default && operatorAlphaShort != null) {
                NetworkNameModel.IntentDerived(operatorAlphaShort)
            } else {
                networkName
            }
        }

    override val carrierName: State<String> =
        combine(connectionRepository.operatorAlphaShort, connectionRepository.carrierName) {
            operatorAlphaShort,
            networkName ->
            if (networkName is NetworkNameModel.Default && operatorAlphaShort != null) {
                operatorAlphaShort
            } else {
                networkName.name
            }
        }

    /** What the mobile icon would be before carrierId overrides */
    private val defaultNetworkType: State<MobileIconGroup> =
        combine(
            connectionRepository.resolvedNetworkType,
            defaultMobileIconMapping,
            defaultMobileIconGroup,
        ) { resolvedNetworkType, mapping, defaultGroup ->
            when (resolvedNetworkType) {
                is ResolvedNetworkType.CarrierMergedNetworkType ->
                    resolvedNetworkType.iconGroupOverride
                else -> mapping[resolvedNetworkType.lookupKey] ?: defaultGroup
            }
        }

    override val networkTypeIconGroup: State<NetworkTypeIconModel> =
        combine(defaultNetworkType, connectionRepository.carrierId) { networkType, carrierId ->
                // DefaultIcon comes out of the icongroup lookup, we check for overrides here
                if (carrierIdOverrides.carrierIdEntryExists(carrierId)) {
                    val iconOverride =
                        carrierIdOverrides.getOverrideFor(
                            carrierId,
                            networkType.name,
                            context.resources,
                        )
                    if (iconOverride > 0) {
                        OverriddenIcon(networkType, iconOverride)
                    } else {
                        DefaultIcon(networkType)
                    }
                } else {
                    DefaultIcon(networkType)
                }
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "MobileIconInteractorKairosImpl(subId=$subscriptionId).networkTypeIconGroup"
                            },
                        it,
                        tableLogBuffer = tableLogBuffer,
                    )
                }
            }

    override val showSliceAttribution: State<Boolean> =
        combine(
            connectionRepository.allowNetworkSliceIndicator,
            connectionRepository.hasPrioritizedNetworkCapabilities,
        ) { allowed, hasPrioritizedNetworkCapabilities ->
            allowed && hasPrioritizedNetworkCapabilities
        }

    override val isNonTerrestrial: State<Boolean>
        get() = connectionRepository.isNonTerrestrial

    override val isRoaming: State<Boolean> =
        combine(
            connectionRepository.carrierNetworkChangeActive,
            connectionRepository.isGsm,
            connectionRepository.isRoaming,
            connectionRepository.cdmaRoaming,
        ) { carrierNetworkChangeActive, isGsm, isRoaming, cdmaRoaming ->
            when {
                carrierNetworkChangeActive -> false
                isGsm -> isRoaming
                else -> cdmaRoaming
            }
        }

    private val level: State<Int> =
        combine(
            connectionRepository.isGsm,
            connectionRepository.primaryLevel,
            connectionRepository.cdmaLevel,
            alwaysUseCdmaLevel,
        ) { isGsm, primaryLevel, cdmaLevel, alwaysUseCdmaLevel ->
            when {
                // GSM connections should never use the CDMA level
                isGsm -> primaryLevel
                alwaysUseCdmaLevel -> cdmaLevel
                else -> primaryLevel
            }
        }

    private val numberOfLevels: State<Int>
        get() = connectionRepository.numberOfLevels

    override val isDataConnected: State<Boolean> =
        connectionRepository.dataConnectionState
            .map { it == Connected }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "MobileIconInteractorKairosImpl(subId=$subscriptionId).isDataConnected"
                            },
                        it,
                        tableLogBuffer,
                        "icon",
                        "isDataConnected",
                    )
                }
            }

    override val isInService
        get() = connectionRepository.isInService

    override val isEmergencyOnly: State<Boolean>
        get() = connectionRepository.isEmergencyOnly

    override val isAllowedDuringAirplaneMode: State<Boolean>
        get() = connectionRepository.isAllowedDuringAirplaneMode

    /** Whether or not to show the error state of [SignalDrawable] */
    private val showExclamationMark: State<Boolean> =
        combine(defaultSubscriptionHasDataEnabled, isDefaultConnectionFailed, isInService) {
            isDefaultDataEnabled,
            isDefaultConnectionFailed,
            isInService ->
            !isDefaultDataEnabled || isDefaultConnectionFailed || !isInService
        }

    private val cellularShownLevel: State<Int> =
        combine(level, isInService, connectionRepository.inflateSignalStrength) {
            level,
            isInService,
            inflate ->
            when {
                !isInService -> 0
                inflate -> level + 1
                else -> level
            }
        }

    // Satellite level is unaffected by the inflateSignalStrength property
    // See b/346904529 for details
    private val satelliteShownLevel: State<Int> = connectionRepository.satelliteLevel

    private val cellularIcon: State<SignalIconModel.Cellular> =
        combine(
            cellularShownLevel,
            numberOfLevels,
            showExclamationMark,
            carrierNetworkChangeActive,
        ) { cellularShownLevel, numberOfLevels, showExclamationMark, carrierNetworkChange ->
            SignalIconModel.Cellular(
                cellularShownLevel,
                numberOfLevels,
                showExclamationMark,
                carrierNetworkChange,
            )
        }

    private val satelliteIcon: State<SignalIconModel.Satellite> =
        satelliteShownLevel.map {
            SignalIconModel.Satellite(
                level = it,
                icon =
                    SatelliteIconModel.fromSignalStrength(it)
                        ?: SatelliteIconModel.fromSignalStrength(0)!!,
            )
        }

    override val signalLevelIcon: State<SignalIconModel> =
        isNonTerrestrial
            .flatMap { ntn ->
                if (ntn) {
                    satelliteIcon
                } else {
                    cellularIcon
                }
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "MobileIconInteractorKairosImpl(subId=$subscriptionId).signalLevelIcon"
                            },
                        it,
                        tableLogBuffer,
                        columnPrefix = "icon",
                    )
                }
            }
}
