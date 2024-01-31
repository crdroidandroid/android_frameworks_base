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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.demo

import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import com.android.settingslib.SignalIcon
import com.android.systemui.KairosBuilder
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State
import com.android.systemui.kairos.TransactionScope
import com.android.systemui.kairos.map
import com.android.systemui.kairos.mapCheap
import com.android.systemui.kairos.mergeLeft
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.util.Either
import com.android.systemui.kairos.util.Either.First
import com.android.systemui.kairos.util.Either.Second
import com.android.systemui.kairos.util.firstOrNull
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.createNumberOfLevelsState
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.Mobile as FakeMobileEvent
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_CARRIER_ID
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_CARRIER_NETWORK_CHANGE
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_CDMA_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_IS_IN_SERVICE
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_IS_NTN
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_OPERATOR
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_PRIMARY_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_ROAMING
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepositoryKairos.Companion.COL_SATELLITE_LEVEL
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel.CarrierMerged as FakeCarrierMergedEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Demo version of [MobileConnectionRepository]. Note that this class shares all of its flows using
 * [SharingStarted.WhileSubscribed()] to give the same semantics as using a regular
 * [MutableStateFlow] while still logging all of the inputs in the same manor as the production
 * repos.
 */
@ExperimentalKairosApi
class DemoMobileConnectionRepositoryKairos(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
    mobileEvents: Events<FakeMobileEvent>,
    carrierMergedResetEvents: Events<Any?>,
    wifiEvents: Events<FakeCarrierMergedEvent>,
    private val mobileMappingsReverseLookup: State<Map<SignalIcon.MobileIconGroup, String>>,
) : MobileConnectionRepositoryKairos, KairosBuilder by kairosBuilder() {

    private val initialState =
        FakeMobileEvent(
            level = null,
            dataType = null,
            subId = subId,
            carrierId = null,
            activity = null,
            carrierNetworkChange = false,
            roaming = false,
            name = DEMO_CARRIER_NAME,
        )

    private val lastMobileEvent: State<FakeMobileEvent> = buildState {
        mobileEvents.holdState(
            initialState,
            nameTag { "DemoMobileConnectionRepositoryKairos(subId=$subId).lastMobileEvent" },
        )
    }

    private val lastEvent: State<Either<FakeMobileEvent, FakeCarrierMergedEvent>> = buildState {
        mergeLeft(
                mobileEvents.mapCheap { First(it) },
                wifiEvents.mapCheap { Second(it) },
                carrierMergedResetEvents.mapCheap { First(lastMobileEvent.sample()) },
            )
            .holdState(
                First(initialState),
                nameTag { "DemoMobileConnectionRepositoryKairos(subId=$subId).lastEvent" },
            )
    }

    override val carrierId: State<Int> =
        lastEvent
            .map { it.firstOrNull()?.carrierId ?: INVALID_SUBSCRIPTION_ID }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).carrierId"
                            },
                        intState = it,
                        tableLogBuffer = tableLogBuffer,
                        columnName = COL_CARRIER_ID,
                    )
                }
            }

    override val inflateSignalStrength: State<Boolean> = buildState {
        lastEvent
            .map {
                when (it) {
                    is First -> it.value.inflateStrength
                    is Second -> it.value.inflateSignalStrength
                }
            }
            .also {
                logDiffsForTable(
                    name =
                        nameTag {
                            "DemoMobileConnectionRepositoryKairos(subId=$subId).inflateSignalStrength::logDiffs"
                        },
                    it,
                    tableLogBuffer,
                    "",
                    columnName = "inflate",
                )
            }
    }

    // I don't see a reason why we would turn the config off for demo mode.
    override val allowNetworkSliceIndicator: State<Boolean> = stateOf(true)

    // TODO(b/261029387): not yet supported
    override val isEmergencyOnly: State<Boolean> = stateOf(false)

    override val isRoaming: State<Boolean> =
        lastEvent
            .map { it.firstOrNull()?.roaming ?: false }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).isRoaming"
                            },
                        it,
                        tableLogBuffer,
                        columnName = COL_ROAMING,
                    )
                }
            }

    override val operatorAlphaShort: State<String?> =
        lastEvent
            .map { it.firstOrNull()?.name }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).operatorAlphaShort"
                            },
                        it,
                        tableLogBuffer,
                        columnName = COL_OPERATOR,
                    )
                }
            }

    override val isInService: State<Boolean> =
        lastEvent
            .map {
                when (it) {
                    is First -> it.value.level?.let { level -> level > 0 } ?: false
                    is Second -> true
                }
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).isInService"
                            },
                        it,
                        tableLogBuffer,
                        columnName = COL_IS_IN_SERVICE,
                    )
                }
            }

    override val isNonTerrestrial: State<Boolean> = buildState {
        mobileEvents
            .map { it.ntn }
            .holdState(
                false,
                nameTag { "DemoMobileConnectionRepositoryKairos(subId=$subId).isNonTerrestrial" },
            )
            .also {
                logDiffsForTable(
                    name =
                        nameTag {
                            "DemoMobileConnectionRepositoryKairos(subId=$subId).isNonTerrestrial::logDiffs"
                        },
                    it,
                    tableLogBuffer,
                    columnName = COL_IS_NTN,
                )
            }
    }

    // TODO(b/261029387): not yet supported
    override val isGsm: State<Boolean> = stateOf(false)

    override val cdmaLevel: State<Int> =
        lastEvent
            .map {
                when (it) {
                    is First -> it.value.level ?: 0
                    is Second -> it.value.level
                }
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).cdmaLevel"
                            },
                        it,
                        tableLogBuffer,
                        columnName = COL_CDMA_LEVEL,
                    )
                }
            }

    override val primaryLevel: State<Int> =
        lastEvent
            .map {
                when (it) {
                    is First -> it.value.level ?: 0
                    is Second -> it.value.level
                }
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).primaryLevel"
                            },
                        it,
                        tableLogBuffer,
                        columnName = COL_PRIMARY_LEVEL,
                    )
                }
            }

    override val satelliteLevel: State<Int> =
        stateOf(0).also {
            onActivated {
                logDiffsForTable(
                    name =
                        nameTag {
                            "DemoMobileConnectionRepositoryKairos(subId=$subId).satelliteLevel"
                        },
                    it,
                    tableLogBuffer,
                    columnName = COL_SATELLITE_LEVEL,
                )
            }
        }

    // TODO(b/261029387): not yet supported
    override val dataConnectionState: State<DataConnectionState> =
        buildState {
                mergeLeft(mobileEvents, wifiEvents)
                    .map { DataConnectionState.Connected }
                    .holdState(
                        DataConnectionState.Disconnected,
                        nameTag {
                            "DemoMobileConnectionRepositoryKairos(subId=$subId).dataConnectionState"
                        },
                    )
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).dataConnectionState::logDiffs"
                            },
                        diffableState = it,
                        tableLogBuffer = tableLogBuffer,
                    )
                }
            }

    override val dataActivityDirection: State<DataActivityModel> =
        lastEvent
            .map {
                val activity =
                    when (it) {
                        is First -> it.value.activity ?: TelephonyManager.DATA_ACTIVITY_NONE
                        is Second -> it.value.activity
                    }
                activity.toMobileDataActivityModel()
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).dataActivityDirection"
                            },
                        it,
                        tableLogBuffer,
                        columnPrefix = "",
                    )
                }
            }

    override val carrierNetworkChangeActive: State<Boolean> =
        lastEvent
            .map { it.firstOrNull()?.carrierNetworkChange ?: false }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag {
                                "DemoMobileConnectionRepositoryKairos(subId=$subId).carrierNetworkChangeActive"
                            },
                        it,
                        tableLogBuffer,
                        columnName = COL_CARRIER_NETWORK_CHANGE,
                    )
                }
            }

    override val resolvedNetworkType: State<ResolvedNetworkType> = buildState {
        lastEvent
            .mapTransactionally {
                it.firstOrNull()?.dataType?.let { resolvedNetworkTypeForIconGroup(it) }
                    ?: ResolvedNetworkType.CarrierMergedNetworkType
            }
            .also {
                logDiffsForTable(
                    name =
                        nameTag {
                            "DemoMobileConnectionRepositoryKairos(subId=$subId).resolvedNetworkType"
                        },
                    it,
                    tableLogBuffer,
                    columnPrefix = "",
                )
            }
    }

    private val defaultNumberOfLevels: State<Int> = buildState {
        lastEvent
            .map {
                when (it) {
                    is First -> DEFAULT_NUM_LEVELS
                    is Second -> it.value.numberOfLevels
                }
            }
            .also {
                logDiffsForTable(
                    name =
                        nameTag {
                            "DemoMobileConnectionRepositoryKairos(subId=$subId).defaultNumberOfLevels"
                        },
                    it,
                    tableLogBuffer,
                    columnName = "defaultNumberOfLevels",
                )
            }
    }

    override val numberOfLevels: State<Int> =
        createNumberOfLevelsState(inflateSignalStrength, defaultNumberOfLevels)

    override val dataEnabled: State<Boolean> = stateOf(true)

    override fun setDataEnabled(enabled: Boolean) {
        // Unused.
    }

    override val cdmaRoaming: State<Boolean> = lastEvent.map { it.firstOrNull()?.roaming ?: false }

    override val networkName: State<NetworkNameModel.IntentDerived> =
        lastEvent.map {
            NetworkNameModel.IntentDerived(it.firstOrNull()?.name ?: CARRIER_MERGED_NAME)
        }

    override val carrierName: State<NetworkNameModel.SubscriptionDerived> =
        lastEvent.map {
            NetworkNameModel.SubscriptionDerived(
                it.firstOrNull()?.let { event -> "${event.name} ${event.subId}" }
                    ?: CARRIER_MERGED_NAME
            )
        }

    override val isAllowedDuringAirplaneMode: State<Boolean> = lastEvent.map { it is Second }

    override val hasPrioritizedNetworkCapabilities: State<Boolean> =
        lastEvent.map { it.firstOrNull()?.slice ?: false }

    override val isInEcmMode: State<Boolean> = stateOf(false)

    override val imsState: State<ImsStateModel> = stateOf(ImsStateModel())

    private fun TransactionScope.resolvedNetworkTypeForIconGroup(
        iconGroup: SignalIcon.MobileIconGroup?
    ) = DefaultNetworkType(mobileMappingsReverseLookup.sample()[iconGroup] ?: "dis")

    companion object {
        private const val DEMO_CARRIER_NAME = "Demo Carrier"
        private const val CARRIER_MERGED_NAME = "Carrier Merged Network"
    }
}
