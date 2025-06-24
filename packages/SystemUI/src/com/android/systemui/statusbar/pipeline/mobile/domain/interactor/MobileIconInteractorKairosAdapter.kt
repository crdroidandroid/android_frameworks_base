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

import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.toColdConflatedFlow
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@ExperimentalKairosApi
fun BuildScope.MobileIconInteractorKairosAdapter(
    kairosImpl: MobileIconInteractorKairos
): MobileIconInteractor =
    with(kairosImpl) {
        MobileIconInteractorKairosAdapter(
            tableLogBuffer = tableLogBuffer,
            activity = activity.toColdConflatedFlow(kairosNetwork),
            mobileIsDefault = mobileIsDefault.toColdConflatedFlow(kairosNetwork),
            isDataConnected = isDataConnected.toStateFlow(),
            isInService = isInService.toStateFlow(),
            isEmergencyOnly = isEmergencyOnly.toStateFlow(),
            isDataEnabled = isDataEnabled.toStateFlow(),
            alwaysShowDataRatIcon = alwaysShowDataRatIcon.toStateFlow(),
            signalLevelIcon = signalLevelIcon.toStateFlow(),
            networkTypeIconGroup = networkTypeIconGroup.toStateFlow(),
            showSliceAttribution = showSliceAttribution.toStateFlow(),
            isNonTerrestrial = isNonTerrestrial.toStateFlow(),
            networkName = networkName.toStateFlow(),
            carrierName = carrierName.toStateFlow(),
            isSingleCarrier = isSingleCarrier.toStateFlow(),
            isRoaming = isRoaming.toStateFlow(),
            isForceHidden = isForceHidden.toColdConflatedFlow(kairosNetwork),
	    isRoamingForceHidden = isRoamingForceHidden.toColdConflatedFlow(kairosNetwork),
            isMobileHd = isMobileHd.toStateFlow(),
            isMobileHdForceHidden = isMobileHdForceHidden.toColdConflatedFlow(kairosNetwork),
            isVoWifi = isVoWifi.toStateFlow(),
            isVoWifiForceHidden = isVoWifiForceHidden.toColdConflatedFlow(kairosNetwork),
            isAllowedDuringAirplaneMode = isAllowedDuringAirplaneMode.toStateFlow(),
            carrierNetworkChangeActive = carrierNetworkChangeActive.toStateFlow(),
        )
    }

private class MobileIconInteractorKairosAdapter(
    override val tableLogBuffer: TableLogBuffer,
    override val activity: Flow<DataActivityModel>,
    override val mobileIsDefault: Flow<Boolean>,
    override val isDataConnected: StateFlow<Boolean>,
    override val isInService: StateFlow<Boolean>,
    override val isEmergencyOnly: StateFlow<Boolean>,
    override val isDataEnabled: StateFlow<Boolean>,
    override val alwaysShowDataRatIcon: StateFlow<Boolean>,
    override val signalLevelIcon: StateFlow<SignalIconModel>,
    override val networkTypeIconGroup: StateFlow<NetworkTypeIconModel>,
    override val showSliceAttribution: StateFlow<Boolean>,
    override val isNonTerrestrial: StateFlow<Boolean>,
    override val networkName: StateFlow<NetworkNameModel>,
    override val carrierName: StateFlow<String>,
    override val isSingleCarrier: StateFlow<Boolean>,
    override val isRoaming: StateFlow<Boolean>,
    override val isForceHidden: Flow<Boolean>,
    override val isRoamingForceHidden: Flow<Boolean>,
    override val isMobileHd: StateFlow<Boolean>,
    override val isMobileHdForceHidden: Flow<Boolean>,
    override val isVoWifi: StateFlow<Boolean>,
    override val isVoWifiForceHidden: Flow<Boolean>,
    override val isAllowedDuringAirplaneMode: StateFlow<Boolean>,
    override val carrierNetworkChangeActive: StateFlow<Boolean>,
) : MobileIconInteractor
