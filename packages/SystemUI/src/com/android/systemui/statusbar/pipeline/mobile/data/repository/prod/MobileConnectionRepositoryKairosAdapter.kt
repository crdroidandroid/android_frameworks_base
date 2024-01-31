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

import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.util.kotlin.Producer
import kotlinx.coroutines.flow.StateFlow

@ExperimentalKairosApi
fun BuildScope.MobileConnectionRepositoryKairosAdapter(
    kairosRepo: MobileConnectionRepositoryKairos
): MobileConnectionRepositoryKairosAdapter =
    MobileConnectionRepositoryKairosAdapter(
        underlyingRepo = kairosRepo,
        subId = kairosRepo.subId,
        carrierId =
            kairosRepo.carrierId.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).carrierId"
                }
            ),
        inflateSignalStrength =
            kairosRepo.inflateSignalStrength.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).inflateSignalStrength"
                }
            ),
        allowNetworkSliceIndicator =
            kairosRepo.allowNetworkSliceIndicator.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).allowNetworkSliceIndicator"
                }
            ),
        tableLogBuffer = kairosRepo.tableLogBuffer,
        isEmergencyOnly =
            kairosRepo.isEmergencyOnly.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).isEmergencyOnly"
                }
            ),
        isRoaming =
            kairosRepo.isRoaming.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).isRoaming"
                }
            ),
        operatorAlphaShort =
            kairosRepo.operatorAlphaShort.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).operatorAlphaShort"
                }
            ),
        isInService =
            kairosRepo.isInService.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).isInService"
                }
            ),
        isNonTerrestrial =
            kairosRepo.isNonTerrestrial.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).isNonTerrestrial"
                }
            ),
        isGsm =
            kairosRepo.isGsm.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).isGsm"
                }
            ),
        cdmaLevel =
            kairosRepo.cdmaLevel.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).cdmaLevel"
                }
            ),
        primaryLevel =
            kairosRepo.primaryLevel.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).primaryLevel"
                }
            ),
        satelliteLevel =
            kairosRepo.satelliteLevel.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).satelliteLevel"
                }
            ),
        dataConnectionState =
            kairosRepo.dataConnectionState.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).dataConnectionState"
                }
            ),
        dataActivityDirection =
            kairosRepo.dataActivityDirection.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).dataActivityDirection"
                }
            ),
        carrierNetworkChangeActive =
            kairosRepo.carrierNetworkChangeActive.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).carrierNetworkChangeActive"
                }
            ),
        resolvedNetworkType =
            kairosRepo.resolvedNetworkType.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).resolvedNetworkType"
                }
            ),
        numberOfLevels =
            kairosRepo.numberOfLevels.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).numberOfLevels"
                }
            ),
        dataEnabled =
            kairosRepo.dataEnabled.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).dataEnabled"
                }
            ),
        cdmaRoaming =
            kairosRepo.cdmaRoaming.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).cdmaRoaming"
                }
            ),
        networkName =
            kairosRepo.networkName.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).networkName"
                }
            ),
        carrierName =
            kairosRepo.carrierName.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).carrierName"
                }
            ),
        isAllowedDuringAirplaneMode =
            kairosRepo.isAllowedDuringAirplaneMode.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).isAllowedDuringAirplaneMode"
                }
            ),
        hasPrioritizedNetworkCapabilities =
            kairosRepo.hasPrioritizedNetworkCapabilities.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).hasPrioritizedNetworkCapabilities"
                }
            ),
        imsState =
            kairosRepo.imsState.toStateFlow(
                nameTag {
                    "MobileConnectionRepositoryKairosAdapter(subId=${kairosRepo.subId}).imsState"
                }
            ),
        isInEcmMode = { kairosNetwork.transact { kairosRepo.isInEcmMode.sample() } },
    )

@ExperimentalKairosApi
class MobileConnectionRepositoryKairosAdapter(
    private val underlyingRepo: MobileConnectionRepositoryKairos,
    override val subId: Int,
    override val carrierId: StateFlow<Int>,
    override val inflateSignalStrength: StateFlow<Boolean>,
    override val allowNetworkSliceIndicator: StateFlow<Boolean>,
    override val tableLogBuffer: TableLogBuffer,
    override val isEmergencyOnly: StateFlow<Boolean>,
    override val isRoaming: StateFlow<Boolean>,
    override val operatorAlphaShort: StateFlow<String?>,
    override val isInService: StateFlow<Boolean>,
    override val isNonTerrestrial: StateFlow<Boolean>,
    override val isGsm: StateFlow<Boolean>,
    override val cdmaLevel: StateFlow<Int>,
    override val primaryLevel: StateFlow<Int>,
    override val satelliteLevel: StateFlow<Int>,
    override val dataConnectionState: StateFlow<DataConnectionState>,
    override val dataActivityDirection: StateFlow<DataActivityModel>,
    override val carrierNetworkChangeActive: StateFlow<Boolean>,
    override val resolvedNetworkType: StateFlow<ResolvedNetworkType>,
    override val numberOfLevels: StateFlow<Int>,
    override val dataEnabled: StateFlow<Boolean>,
    override val cdmaRoaming: StateFlow<Boolean>,
    override val networkName: StateFlow<NetworkNameModel>,
    override val carrierName: StateFlow<NetworkNameModel>,
    override val isAllowedDuringAirplaneMode: StateFlow<Boolean>,
    override val imsState: StateFlow<ImsStateModel>,
    override val hasPrioritizedNetworkCapabilities: StateFlow<Boolean>,
    private val isInEcmMode: Producer<Boolean>,
) : MobileConnectionRepository {
    override fun setDataEnabled(enabled: Boolean) {
        underlyingRepo.setDataEnabled(enabled)
    }

    override suspend fun isInEcmMode(): Boolean = isInEcmMode.get()
}
