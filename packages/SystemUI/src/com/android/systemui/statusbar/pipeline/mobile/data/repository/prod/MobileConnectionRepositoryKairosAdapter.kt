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
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.util.kotlin.Producer
import kotlinx.coroutines.flow.StateFlow

@ExperimentalKairosApi
fun BuildScope.MobileConnectionRepositoryKairosAdapter(
    kairosRepo: MobileConnectionRepositoryKairos,
    carrierConfig: SystemUiCarrierConfig,
) =
    MobileConnectionRepositoryKairosAdapter(
        subId = kairosRepo.subId,
        carrierId = kairosRepo.carrierId.toStateFlow(),
        inflateSignalStrength = carrierConfig.shouldInflateSignalStrength,
        allowNetworkSliceIndicator = carrierConfig.allowNetworkSliceIndicator,
        tableLogBuffer = kairosRepo.tableLogBuffer,
        isEmergencyOnly = kairosRepo.isEmergencyOnly.toStateFlow(),
        isRoaming = kairosRepo.isRoaming.toStateFlow(),
        operatorAlphaShort = kairosRepo.operatorAlphaShort.toStateFlow(),
        isInService = kairosRepo.isInService.toStateFlow(),
        isNonTerrestrial = kairosRepo.isNonTerrestrial.toStateFlow(),
        isGsm = kairosRepo.isGsm.toStateFlow(),
        cdmaLevel = kairosRepo.cdmaLevel.toStateFlow(),
        primaryLevel = kairosRepo.primaryLevel.toStateFlow(),
        satelliteLevel = kairosRepo.satelliteLevel.toStateFlow(),
        dataConnectionState = kairosRepo.dataConnectionState.toStateFlow(),
        dataActivityDirection = kairosRepo.dataActivityDirection.toStateFlow(),
        carrierNetworkChangeActive = kairosRepo.carrierNetworkChangeActive.toStateFlow(),
        resolvedNetworkType = kairosRepo.resolvedNetworkType.toStateFlow(),
        numberOfLevels = kairosRepo.numberOfLevels.toStateFlow(),
        dataEnabled = kairosRepo.dataEnabled.toStateFlow(),
        cdmaRoaming = kairosRepo.cdmaRoaming.toStateFlow(),
        networkName = kairosRepo.networkName.toStateFlow(),
        carrierName = kairosRepo.carrierName.toStateFlow(),
        isAllowedDuringAirplaneMode = kairosRepo.isAllowedDuringAirplaneMode.toStateFlow(),
	imsState = kairosRepo.imsState.toStateFlow(),
        hasPrioritizedNetworkCapabilities =
            kairosRepo.hasPrioritizedNetworkCapabilities.toStateFlow(),
        isInEcmMode = { kairosNetwork.transact { kairosRepo.isInEcmMode.sample() } },
    )

@ExperimentalKairosApi
class MobileConnectionRepositoryKairosAdapter(
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
    override suspend fun isInEcmMode(): Boolean = isInEcmMode.get()
}
