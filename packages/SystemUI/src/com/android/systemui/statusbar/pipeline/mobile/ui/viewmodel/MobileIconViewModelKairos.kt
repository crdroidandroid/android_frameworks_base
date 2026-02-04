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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import com.android.systemui.Flags.statusBarStaticInoutIndicators
import com.android.systemui.KairosBuilder
import com.android.systemui.activated
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State as KairosState
import com.android.systemui.kairos.State
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.map
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractorKairos
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.model.MobileContentDescription
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel

/** Common interface for all of the location-based mobile icon view models. */
@ExperimentalKairosApi
interface MobileIconViewModelKairosCommon {
    val subscriptionId: Int
    val iconInteractor: MobileIconInteractorKairos
    /** True if this view should be visible at all. */
    val isVisible: KairosState<Boolean>
    val icon: KairosState<SignalIconModel>
    val contentDescription: KairosState<MobileContentDescription?>
    val roaming: KairosState<Boolean>
    val isRoamingVisible: KairosState<Boolean>
    /** The RAT icon (LTE, 3G, 5G, etc) to be displayed. Null if we shouldn't show anything */
    val networkTypeIcon: KairosState<Icon.Resource?>
    /** The slice attribution. Drawn as a background layer */
    val networkTypeBackground: KairosState<Icon.Resource?>
    val activityInVisible: KairosState<Boolean>
    val activityOutVisible: KairosState<Boolean>
    val activityContainerVisible: KairosState<Boolean>
}

/**
 * View model for the state of a single mobile icon. Each [MobileIconViewModelKairos] will keep
 * watch over a single line of service via [MobileIconInteractorKairos] and update the UI based on
 * that subscription's information.
 *
 * There will be exactly one [MobileIconViewModelKairos] per filtered subscription offered from
 * [MobileIconsInteractorKairos.filteredSubscriptions].
 */
@ExperimentalKairosApi
class MobileIconViewModelKairos(
    override val subscriptionId: Int,
    override val iconInteractor: MobileIconInteractorKairos,
    private val airplaneModeInteractor: AirplaneModeInteractor,
    private val constants: ConnectivityConstants,
) : MobileIconViewModelKairosCommon, KairosBuilder by kairosBuilder() {

    private val isAirplaneMode: State<Boolean> = buildState {
        airplaneModeInteractor.isAirplaneMode.toState(
            nameTag("MobileIconViewModelKairos.isAirplaneMode")
        )
    }

    private val satelliteProvider by lazy {
        CarrierBasedSatelliteViewModelKairosImpl(subscriptionId, iconInteractor, isAirplaneMode)
    }

    /**
     * Similar to repository switching, this allows us to split up the logic of satellite/cellular
     * states, since they are different by nature
     */
    private val vmProvider: KairosState<MobileIconViewModelKairosCommon> = buildState {
        iconInteractor.isNonTerrestrial.mapLatestBuild(
            nameTag("MobileIconViewModelKairos.vmProvider")
        ) { nonTerrestrial ->
            if (nonTerrestrial) {
                satelliteProvider
            } else {
                activated {
                    CellularIconViewModelKairos(
                        subscriptionId,
                        iconInteractor,
                        airplaneModeInteractor,
                        constants,
                    )
                }
            }
        }
    }

    override val isVisible: KairosState<Boolean> = vmProvider.flatMap { it.isVisible }

    override val icon: KairosState<SignalIconModel> = vmProvider.flatMap { it.icon }

    override val contentDescription: KairosState<MobileContentDescription?> =
        vmProvider.flatMap { it.contentDescription }

    override val roaming: KairosState<Boolean> = vmProvider.flatMap { it.roaming }

    override val isRoamingVisible: KairosState<Boolean> = vmProvider.flatMap { it.isRoamingVisible }

    override val networkTypeIcon: KairosState<Icon.Resource?> =
        vmProvider.flatMap { it.networkTypeIcon }

    override val networkTypeBackground: KairosState<Icon.Resource?> =
        vmProvider.flatMap { it.networkTypeBackground }

    override val activityInVisible: KairosState<Boolean> =
        vmProvider.flatMap { it.activityInVisible }

    override val activityOutVisible: KairosState<Boolean> =
        vmProvider.flatMap { it.activityOutVisible }

    override val activityContainerVisible: KairosState<Boolean> =
        vmProvider.flatMap { it.activityContainerVisible }
}

/** Representation of this network when it is non-terrestrial (e.g., satellite) */
@ExperimentalKairosApi
private class CarrierBasedSatelliteViewModelKairosImpl(
    override val subscriptionId: Int,
    override val iconInteractor: MobileIconInteractorKairos,
    isAirplaneMode: KairosState<Boolean>,
) : MobileIconViewModelKairosCommon {
    override val isVisible: KairosState<Boolean> = isAirplaneMode.map { !it }
    override val icon: KairosState<SignalIconModel>
        get() = iconInteractor.signalLevelIcon

    override val contentDescription: KairosState<MobileContentDescription?> = stateOf(null)

    /** These fields are not used for satellite icons currently */
    override val roaming: KairosState<Boolean> = stateOf(false)
    override val isRoamingVisible: KairosState<Boolean> = stateOf(false)
    override val networkTypeIcon: KairosState<Icon.Resource?> = stateOf(null)
    override val networkTypeBackground: KairosState<Icon.Resource?> = stateOf(null)
    override val activityInVisible: KairosState<Boolean> = stateOf(false)
    override val activityOutVisible: KairosState<Boolean> = stateOf(false)
    override val activityContainerVisible: KairosState<Boolean> = stateOf(false)
}

/** Terrestrial (cellular) icon. */
@ExperimentalKairosApi
private class CellularIconViewModelKairos(
    override val subscriptionId: Int,
    override val iconInteractor: MobileIconInteractorKairos,
    airplaneModeInteractor: AirplaneModeInteractor,
    constants: ConnectivityConstants,
) : MobileIconViewModelKairosCommon, KairosBuilder by kairosBuilder() {

    override val isVisible: KairosState<Boolean> =
        if (!constants.hasDataCapabilities) {
            stateOf(false)
        } else {
            buildState {
                combine(
                        airplaneModeInteractor.isAirplaneMode.toState(
                            nameTag {
                                "CellularIconViewModelKairos(subId=$subscriptionId).isAirplaneMode"
                            }
                        ),
                        iconInteractor.isAllowedDuringAirplaneMode,
                        iconInteractor.isForceHidden,
                    ) { isAirplaneMode, isAllowedDuringAirplaneMode, isForceHidden ->
                        if (isForceHidden) {
                            false
                        } else if (isAirplaneMode) {
                            isAllowedDuringAirplaneMode
                        } else {
                            true
                        }
                    }
                    .also {
                        logDiffsForTable(
                            name =
                                nameTag {
                                    "CellularIconViewModelKairos(subId=$subscriptionId).filteredSubscriptions"
                                },
                            it,
                            iconInteractor.tableLogBuffer,
                            columnName = "visible",
                        )
                    }
            }
        }

    override val icon: KairosState<SignalIconModel>
        get() = iconInteractor.signalLevelIcon

    override val contentDescription: KairosState<MobileContentDescription?> =
        combine(iconInteractor.signalLevelIcon, iconInteractor.networkName) { icon, nameModel ->
            when (icon) {
                is SignalIconModel.Cellular ->
                    MobileContentDescription.Cellular(nameModel.name, icon.levelDescriptionRes())
                else -> null
            }
        }

    private fun SignalIconModel.Cellular.levelDescriptionRes() =
        when (level) {
            0 -> R.string.accessibility_no_signal
            1 -> R.string.accessibility_one_bar
            2 -> R.string.accessibility_two_bars
            3 -> R.string.accessibility_three_bars
            4 -> {
                if (numberOfLevels == 6) {
                    R.string.accessibility_four_bars
                } else {
                    R.string.accessibility_signal_full
                }
            }
            5 -> {
                if (numberOfLevels == 6) {
                    R.string.accessibility_signal_full
                } else {
                    R.string.accessibility_no_signal
                }
            }
            else -> R.string.accessibility_no_signal
        }

    private val showNetworkTypeIcon: KairosState<Boolean> =
        combine(
                iconInteractor.isDataConnected,
                iconInteractor.isDataEnabled,
                iconInteractor.alwaysShowDataRatIcon,
                iconInteractor.mobileIsDefault,
                iconInteractor.carrierNetworkChangeActive,
            ) { dataConnected, dataEnabled, alwaysShow, mobileIsDefault, carrierNetworkChange ->
                alwaysShow ||
                    (!carrierNetworkChange && (dataEnabled && dataConnected && mobileIsDefault))
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag(
                                "CellularIconViewModelKairos(subId=$subscriptionId).showNetworkTypeIcon"
                            ),
                        it,
                        iconInteractor.tableLogBuffer,
                        columnName = "showNetworkTypeIcon",
                    )
                }
            }

    override val networkTypeIcon: KairosState<Icon.Resource?> =
        combine(iconInteractor.networkTypeIconGroup, showNetworkTypeIcon) {
            networkTypeIconGroup,
            shouldShow ->
            val desc =
                if (networkTypeIconGroup.contentDescription != 0) {
                    ContentDescription.Resource(networkTypeIconGroup.contentDescription)
                } else {
                    null
                }
            val icon =
                if (networkTypeIconGroup.iconId != 0) {
                    Icon.Resource(networkTypeIconGroup.iconId, desc)
                } else {
                    null
                }
            when {
                !shouldShow -> null
                else -> icon
            }
        }

    override val networkTypeBackground: KairosState<Icon.Resource?> =
        iconInteractor.showSliceAttribution.map {
            when {
                it && NewStatusBarIcons.isEnabled ->
                    Icon.Resource(R.drawable.mobile_network_type_background_updated, null)
                it -> Icon.Resource(R.drawable.mobile_network_type_background, null)
                else -> null
            }
        }

    override val roaming: KairosState<Boolean> =
        iconInteractor.isRoaming.also {
            onActivated {
                logDiffsForTable(
                    name = nameTag { "CellularIconViewModelKairos(subId=$subscriptionId).roaming" },
                    it,
                    iconInteractor.tableLogBuffer,
                    columnName = "roaming",
                )
            }
        }

    override val isRoamingVisible: KairosState<Boolean> =
        combine(
            iconInteractor.isRoaming,
            iconInteractor.isRoamingForceHidden
        ) { isRoaming, isHidden ->
            isRoaming && !isHidden
        }.also {
            onActivated {
                logDiffsForTable(
                    name = nameTag { "CellularIconViewModelKairos(subId=$subscriptionId).isRoamingVisible" },
                    it,
                    iconInteractor.tableLogBuffer,
                    columnName = "roamingVisible",
                )
            }
        }

    private val activity: KairosState<DataActivityModel?> =
        if (!constants.shouldShowActivityConfig) {
            stateOf(null)
        } else {
            iconInteractor.activity
        }

    override val activityInVisible: KairosState<Boolean> =
        activity.map { it?.hasActivityIn ?: false }

    override val activityOutVisible: KairosState<Boolean> =
        activity.map { it?.hasActivityOut ?: false }

    override val activityContainerVisible: KairosState<Boolean> =
        if (statusBarStaticInoutIndicators()) {
            stateOf(constants.shouldShowActivityConfig)
        } else {
            activity.map { it != null && (it.hasActivityIn || it.hasActivityOut) }
        }
}
