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

import android.content.Context
import androidx.compose.runtime.State as ComposeState
import androidx.compose.runtime.getValue
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.KairosActivatable
import com.android.systemui.KairosBuilder
import com.android.systemui.activated
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.Incremental
import com.android.systemui.kairos.State as KairosState
import com.android.systemui.kairos.State
import com.android.systemui.kairos.buildSpec
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.flatten
import com.android.systemui.kairos.map
import com.android.systemui.kairos.mapValues
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.MOBILE_CONNECTION_BUFFER_SIZE
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.tableBufferLogName
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractorKairos
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorKairos
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.VerboseMobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.model.MobileContentDescription
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.util.composable.kairos.toComposeState
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import javax.inject.Inject
import javax.inject.Provider

/**
 * View model for describing the system's current mobile cellular connections. The result is a list
 * of [MobileIconViewModelKairos]s which describe the individual icons and can be bound to
 * [ModernStatusBarMobileView].
 */
@ExperimentalKairosApi
@SysUISingleton
class MobileIconsViewModelKairos
@Inject
constructor(
    val logger: MobileViewLogger,
    private val verboseLogger: VerboseMobileViewLogger,
    private val interactor: MobileIconsInteractorKairos,
    private val airplaneModeInteractor: AirplaneModeInteractor,
    private val constants: ConnectivityConstants,
    private val logFactory: TableLogBufferFactory,
    private val context: Context,
) : KairosBuilder by kairosBuilder() {

    val activeSubscriptionId: State<Int?> =
        interactor.activeDataIconInteractor.map { it?.subscriptionId }

    val subscriptionIds: KairosState<List<Int>> =
        interactor.filteredSubscriptions.map { subscriptions ->
            subscriptions.map { it.subscriptionId }
        }

    val icons: Incremental<Int, MobileIconViewModelKairos> = buildIncremental {
        interactor.icons
            .mapValues { (subId, icon) -> buildSpec { commonViewModel(subId, icon) } }
            .applyLatestSpecForKey(name = nameTag("MobileIconsViewModelKairos.icons"))
    }

    /** Whether the mobile sub that's displayed first visually is showing its network type icon. */
    val firstMobileSubShowingNetworkTypeIcon: KairosState<Boolean> = buildState {
        combine(subscriptionIds.map { it.lastOrNull() }, icons) { lastId, icons ->
                icons[lastId]?.networkTypeIcon?.map { it != null } ?: stateOf(false)
            }
            .flatten()
    }

    val isStackable: KairosState<Boolean>
        get() = interactor.isStackable

    @Deprecated("Access view-models directly from \"icons\" property instead.")
    fun viewModelForSub(
        subId: Int,
        location: StatusBarLocation,
    ): LocationBasedMobileViewModelKairos {
        val commonImpl = trackedCommonViewModel(subId)
        return LocationBasedMobileViewModelKairos.viewModelForLocation(
            commonImpl,
            commonImpl.iconInteractor,
            verboseLogger,
            location,
        )
    }

    @Deprecated("Access view-models directly from \"icons\" property instead.")
    fun shadeCarrierGroupIcon(subId: Int): ShadeCarrierGroupMobileIconViewModelKairos {
        val commonImpl = trackedCommonViewModel(subId)
        return ShadeCarrierGroupMobileIconViewModelKairos(commonImpl, commonImpl.iconInteractor)
    }

    private fun trackedInteractor(subId: Int): MobileIconInteractorKairos =
        object : MobileIconInteractorKairos {
            val iconInteractorState: State<MobileIconInteractorKairos?> =
                interactor.icons.map { it[subId] }

            fun <T> latest(default: T, block: MobileIconInteractorKairos.() -> State<T>): State<T> =
                iconInteractorState.flatMap { it?.block() ?: stateOf(default) }

            override val tableLogBuffer: TableLogBuffer =
                logFactory.getOrCreate(tableBufferLogName(subId), MOBILE_CONNECTION_BUFFER_SIZE)
            override val subscriptionId: Int = subId
            override val activity: State<DataActivityModel> =
                latest(DataActivityModel(hasActivityIn = false, hasActivityOut = false)) {
                    activity
                }
            override val mobileIsDefault: State<Boolean> = latest(false) { mobileIsDefault }
            override val isDataConnected: State<Boolean> = latest(false) { isDataConnected }
            override val isInService: State<Boolean> = latest(false) { isInService }
            override val isEmergencyOnly: State<Boolean> = latest(false) { isEmergencyOnly }
            override val isDataEnabled: State<Boolean> = latest(false) { isDataEnabled }
            override val alwaysShowDataRatIcon: State<Boolean> =
                latest(false) { alwaysShowDataRatIcon }
            override val signalLevelIcon: State<SignalIconModel> =
                latest(SignalIconModel.DEFAULT) { signalLevelIcon }
            override val networkTypeIconGroup: State<NetworkTypeIconModel> =
                latest(NetworkTypeIconModel.DefaultIcon(TelephonyIcons.G)) { networkTypeIconGroup }
            override val showSliceAttribution: State<Boolean> =
                latest(false) { showSliceAttribution }
            override val isNonTerrestrial: State<Boolean> = latest(false) { isNonTerrestrial }
            val defaultName =
                context.getString(com.android.internal.R.string.lockscreen_carrier_default)
            override val networkName: State<NetworkNameModel> =
                latest(NetworkNameModel.Default(defaultName)) { networkName }
            override val carrierName: State<String> = latest(defaultName) { carrierName }
            override val isSingleCarrier: State<Boolean> = latest(true) { isSingleCarrier }
            override val isRoaming: State<Boolean> = latest(false) { isRoaming }
            override val isForceHidden: State<Boolean> = latest(false) { isForceHidden }
            override val isRoamingForceHidden: State<Boolean> = latest(false) { isRoamingForceHidden }
            override val isAllowedDuringAirplaneMode: State<Boolean> =
                latest(false) { isAllowedDuringAirplaneMode }
            override val carrierNetworkChangeActive: State<Boolean> =
                latest(false) { carrierNetworkChangeActive }
        }

    private fun trackedCommonViewModel(subId: Int) =
        object : MobileIconViewModelKairosCommon {
            override val iconInteractor: MobileIconInteractorKairos = trackedInteractor(subId)
            val commonViewModelState: State<MobileIconViewModelKairos?> = icons.map { it[subId] }

            fun <T> latest(
                default: T,
                block: MobileIconViewModelKairosCommon.() -> State<T>,
            ): State<T> = commonViewModelState.flatMap { it?.block() ?: stateOf(default) }

            override val subscriptionId: Int = subId
            override val isVisible: State<Boolean> = latest(false) { isVisible }
            override val icon: State<SignalIconModel> = latest(SignalIconModel.DEFAULT) { icon }
            override val contentDescription: State<MobileContentDescription?> =
                latest(null) { contentDescription }
            override val roaming: State<Boolean> = latest(false) { roaming }
            override val networkTypeIcon: State<Icon.Resource?> = latest(null) { networkTypeIcon }
            override val networkTypeBackground: State<Icon.Resource?> =
                latest(null) { networkTypeBackground }
            override val activityInVisible: State<Boolean> = latest(false) { activityInVisible }
            override val activityOutVisible: State<Boolean> = latest(false) { activityOutVisible }
            override val activityContainerVisible: State<Boolean> =
                latest(false) { activityContainerVisible }
        }

    private fun BuildScope.commonViewModel(subId: Int, iconInteractor: MobileIconInteractorKairos) =
        activated {
            MobileIconViewModelKairos(
                subscriptionId = subId,
                iconInteractor = iconInteractor,
                airplaneModeInteractor = airplaneModeInteractor,
                constants = constants,
            )
        }

    @dagger.Module
    object Module {
        @Provides
        @ElementsIntoSet
        fun bindKairosActivatable(
            impl: Provider<MobileIconsViewModelKairos>
        ): Set<@JvmSuppressWildcards KairosActivatable> =
            if (StatusBarMobileIconKairos.isEnabled) setOf(impl.get()) else emptySet()
    }
}

@ExperimentalKairosApi
class MobileIconsViewModelKairosComposeWrapper(
    icons: ComposeState<Map<Int, MobileIconViewModelKairos>>,
    val logger: MobileViewLogger,
) {
    val icons: Map<Int, MobileIconViewModelKairos> by icons
}

@ExperimentalKairosApi
fun MobileIconsViewModelKairos.composeWrapper():
    BuildSpec<MobileIconsViewModelKairosComposeWrapper> = buildSpec {
    MobileIconsViewModelKairosComposeWrapper(
        icons = toComposeState(icons, nameTag("MobileIconsViewModelKairosComposeWrapper.icons")),
        logger = logger,
    )
}
