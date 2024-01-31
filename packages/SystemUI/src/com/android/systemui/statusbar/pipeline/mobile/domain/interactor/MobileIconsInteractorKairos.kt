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
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.PROFILE_CLASS_PROVISIONING
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.systemui.KairosActivatable
import com.android.systemui.KairosBuilder
import com.android.systemui.activated
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS
import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.Incremental
import com.android.systemui.kairos.State
import com.android.systemui.kairos.asyncEvent
import com.android.systemui.kairos.buildSpec
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.filter
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.flatten
import com.android.systemui.kairos.map
import com.android.systemui.kairos.mapValues
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.pipeline.dagger.MobileSummaryLog
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.ims.data.repository.CommonImsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.util.CarrierConfigTracker
import dagger.Binds
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import javax.inject.Inject
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Business layer logic for the set of mobile subscription icons.
 *
 * This interactor represents known set of mobile subscriptions (represented by [SubscriptionInfo]).
 * The list of subscriptions is filtered based on the opportunistic flags on the infos.
 *
 * It provides the default mapping between the telephony display info and the icon group that
 * represents each RAT (LTE, 3G, etc.), as well as can produce an interactor for each individual
 * icon
 */
@ExperimentalKairosApi
interface MobileIconsInteractorKairos {
    /** See [MobileConnectionsRepository.mobileIsDefault]. */
    val mobileIsDefault: State<Boolean>

    /** List of subscriptions, potentially filtered for CBRS */
    val filteredSubscriptions: State<List<SubscriptionModel>>

    /**
     * The current list of [MobileIconInteractor]s associated with the current list of
     * [filteredSubscriptions]
     */
    val icons: Incremental<Int, MobileIconInteractorKairos>

    /** Whether the mobile icons can be stacked vertically. */
    val isStackable: State<Boolean>

    /** True if the active mobile data subscription has data enabled */
    val activeDataConnectionHasDataEnabled: State<Boolean>

    /**
     * Flow providing a reference to the Interactor for the active data subId. This represents the
     * [MobileIconInteractorKairos] responsible for the active data connection, if any.
     */
    val activeDataIconInteractor: State<MobileIconInteractorKairos?>

    /** True if the RAT icon should always be displayed and false otherwise. */
    val alwaysShowDataRatIcon: State<Boolean>

    /** True if the CDMA level should be preferred over the primary level. */
    val alwaysUseCdmaLevel: State<Boolean>

    /** True if there is only one active subscription. */
    val isSingleCarrier: State<Boolean>

    /** The icon mapping from network type to [MobileIconGroup] for the default subscription */
    val defaultMobileIconMapping: State<Map<String, MobileIconGroup>>

    /** Fallback [MobileIconGroup] in the case where there is no icon in the mapping */
    val defaultMobileIconGroup: State<MobileIconGroup>

    /** True only if the default network is mobile, and validation also failed */
    val isDefaultConnectionFailed: State<Boolean>

    /** True once the user has been set up */
    val isUserSetUp: State<Boolean>

    /** True if we're configured to force-hide the mobile icons and false otherwise. */
    val isForceHidden: State<Boolean>

    /** True if we're configured to force-hide the roaming icon and false otherwise. */
    val isRoamingForceHidden: State<Boolean>

    /** True if we're configured to force-hide the hd (VoLTE/VoNR) icon and false otherwise. */
    val isMobileHdForceHidden: State<Boolean>

    /** True if we're configured to force-hide the VoWifi icon and false otherwise. */
    val isVoWifiForceHidden: State<Boolean>

    /**
     * True if the device-level service state (with -1 subscription id) reports emergency calls
     * only. This value is only useful when there are no other subscriptions OR all existing
     * subscriptions report that they are not in service.
     */
    val isDeviceInEmergencyCallsOnlyMode: State<Boolean>
}

@ExperimentalKairosApi
@SysUISingleton
class MobileIconsInteractorKairosImpl
@Inject
constructor(
    private val mobileConnectionsRepo: MobileConnectionsRepositoryKairos,
    private val carrierConfigTracker: CarrierConfigTracker,
    @MobileSummaryLog private val tableLogger: TableLogBuffer,
    connectivityRepository: ConnectivityRepository,
    userSetupRepo: UserSetupRepository,
    private val context: Context,
    commonImsRepo: CommonImsRepository,
    private val featureFlagsClassic: FeatureFlagsClassic,
) : MobileIconsInteractorKairos, KairosBuilder by kairosBuilder() {

    override val mobileIsDefault: State<Boolean> =
        combine(
                mobileConnectionsRepo.mobileIsDefault,
                mobileConnectionsRepo.hasCarrierMergedConnection,
            ) { mobileIsDefault, hasCarrierMergedConnection ->
                // Because carrier merged networks are displayed as mobile networks, they're part of
                // the `isDefault` calculation. See b/272586234.
                mobileIsDefault || hasCarrierMergedConnection
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        nameTag("MobileIconsInteractorKairosImpl.mobileIsDefault"),
                        it,
                        tableLogger,
                        LOGGING_PREFIX,
                        columnName = "mobileIsDefault",
                    )
                }
            }

    override val activeDataConnectionHasDataEnabled: State<Boolean> =
        mobileConnectionsRepo.activeMobileDataRepository.flatMap {
            it?.dataEnabled ?: stateOf(false)
        }

    private val unfilteredSubscriptions: State<Collection<SubscriptionModel>>
        get() = mobileConnectionsRepo.subscriptions

    /** Any filtering that we can do based purely on the info of each subscription individually. */
    private val subscriptionsBasedFilteredSubs: State<List<SubscriptionModel>> =
        unfilteredSubscriptions.map {
            it.asSequence().filterBasedOnProvisioning().filterBasedOnNtn().toList()
        }

    private fun Sequence<SubscriptionModel>.filterBasedOnProvisioning() =
        if (!featureFlagsClassic.isEnabled(FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS)) {
            this
        } else {
            filter { it.profileClass != PROFILE_CLASS_PROVISIONING }
        }

    /**
     * Subscriptions that exclusively support non-terrestrial networks should **never** directly
     * show any iconography in the status bar. These subscriptions only exist to provide a backing
     * for the device-based satellite connections, and the iconography for those connections are
     * already being handled in
     * [com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository]. We
     * need to filter out those subscriptions here so we guarantee the subscription never turns into
     * an icon. See b/336881301.
     */
    private fun Sequence<SubscriptionModel>.filterBasedOnNtn(): Sequence<SubscriptionModel> =
        filter {
            !it.isExclusivelyNonTerrestrial
        }

    /**
     * Generally, SystemUI wants to show iconography for each subscription that is listed by
     * [SubscriptionManager]. However, in the case of opportunistic subscriptions, we want to only
     * show a single representation of the pair of subscriptions. The docs define opportunistic as:
     *
     * "A subscription is opportunistic (if) the network it connects to has limited coverage"
     * https://developer.android.com/reference/android/telephony/SubscriptionManager#setOpportunistic(boolean,%20int)
     *
     * In the case of opportunistic networks (typically CBRS), we will filter out one of the
     * subscriptions based on
     * [CarrierConfigManager.KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN],
     * and by checking which subscription is opportunistic, or which one is active.
     */
    override val filteredSubscriptions: State<List<SubscriptionModel>> = buildState {
        combine(
                subscriptionsBasedFilteredSubs,
                mobileConnectionsRepo.activeMobileDataSubscriptionId,
                connectivityRepository.vcnSubId.toState(
                    nameTag("MobileIconsInteractorKairosImpl.vcnSubId")
                ),
            ) { preFilteredSubs, activeId, vcnSubId ->
                filterSubsBasedOnOpportunistic(preFilteredSubs, activeId, vcnSubId)
            }
            .also {
                logDiffsForTable(
                    nameTag("MobileIconsInteractorKairosImpl.filteredSubscriptions"),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "filteredSubscriptions",
                )
            }
    }

    private fun filterSubsBasedOnOpportunistic(
        subList: List<SubscriptionModel>,
        activeId: Int?,
        vcnSubId: Int?,
    ): List<SubscriptionModel> {
        // Based on the old logic,
        if (subList.size != 2) {
            return subList
        }

        val info1 = subList[0]
        val info2 = subList[1]

        // Filtering only applies to subscriptions in the same group
        if (info1.groupUuid == null || info1.groupUuid != info2.groupUuid) {
            return subList
        }

        // If both subscriptions are primary, show both
        if (!info1.isOpportunistic && !info2.isOpportunistic) {
            return subList
        }

        // NOTE: at this point, we are now returning a single SubscriptionInfo

        // If carrier required, always show the icon of the primary subscription.
        // Otherwise, show whichever subscription is currently active for internet.
        if (carrierConfigTracker.alwaysShowPrimarySignalBarInOpportunisticNetworkDefault) {
            // return the non-opportunistic info
            return if (info1.isOpportunistic) listOf(info2) else listOf(info1)
        } else {
            // It's possible for the subId of the VCN to disagree with the active subId in
            // cases where the system has tried to switch but found no connection. In these
            // scenarios, VCN will always have the subId that we want to use, so use that
            // value instead of the activeId reported by telephony
            val subIdToKeep = vcnSubId ?: activeId

            return if (info1.subscriptionId == subIdToKeep) {
                listOf(info1)
            } else {
                listOf(info2)
            }
        }
    }

    override val icons: Incremental<Int, MobileIconInteractorKairos> = buildIncremental {
        val filteredSubIds =
            filteredSubscriptions.map { it.asSequence().map { sub -> sub.subscriptionId }.toSet() }
        mobileConnectionsRepo.mobileConnectionsBySubId
            .filterIncrementally { (subId, _) ->
                // Filter out repo if subId is not present in the filtered set
                filteredSubIds.map { subId in it }
            }
            // Just map the repos to interactors
            .mapValues { (subId, repo) -> buildSpec { mobileConnection(repo) } }
            .applyLatestSpecForKey(name = nameTag("MobileIconsInteractorKairosImpl.icons"))
    }

    override val isStackable: State<Boolean> =
        if (NewStatusBarIcons.isEnabled && StatusBarRootModernization.isEnabled) {
            icons.flatMap { iconsBySubId: Map<Int, MobileIconInteractorKairos> ->
                iconsBySubId.values
                    .map { it.signalLevelIcon }
                    .combine { signalLevelIcons ->
                        // These are only stackable if:
                        // - They are cellular
                        // - There's exactly two
                        // - They have the same number of levels
                        signalLevelIcons.filterIsInstance<SignalIconModel.Cellular>().let {
                            it.size == 2 && it[0].numberOfLevels == it[1].numberOfLevels
                        }
                    }
            }
        } else {
            stateOf(false)
        }

    override val activeDataIconInteractor: State<MobileIconInteractorKairos?> =
        combine(mobileConnectionsRepo.activeMobileDataSubscriptionId, icons) { activeSubId, icons ->
            activeSubId?.let { icons[activeSubId] }
        }

    /**
     * Copied from the old pipeline. We maintain a 2s period of time where we will keep the
     * validated bit from the old active network (A) while data is changing to the new one (B).
     *
     * This condition only applies if
     * 1. A and B are in the same subscription group (e.g. for CBRS data switching) and
     * 2. A was validated before the switch
     *
     * The goal of this is to minimize the flickering in the UI of the cellular indicator
     */
    private val forcingCellularValidation: State<Boolean> = buildState {
        mobileConnectionsRepo.activeSubChangedInGroupEvent
            .filter(mobileConnectionsRepo.defaultConnectionIsValidated)
            .mapLatestBuild(
                name = nameTag("MobileIconsInteractorKairos.forcingCellularValidationNewInnerState")
            ) {
                asyncEvent(nameTag("MobileIconsInteractorKairos.delayForcingCellularValidation")) {
                        delay(2.seconds)
                        false
                    }
                    .holdState(
                        true,
                        nameTag("MobileIconsInteractorKairos.forcingCellularValidationInnerState"),
                    )
            }
            .holdState(
                stateOf(false),
                nameTag("MobileIconsInteractorKairos.forcingCellularValidation"),
            )
            .flatten()
            .also {
                logDiffsForTable(
                    nameTag("MobileIconsInteractorKairos.forcingCellularValidation::logDiffs"),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "forcingValidation",
                )
            }
    }

    /**
     * Mapping from network type to [MobileIconGroup] using the config generated for the default
     * subscription Id. This mapping is the same for every subscription.
     */
    override val defaultMobileIconMapping: State<Map<String, MobileIconGroup>>
        get() = mobileConnectionsRepo.defaultMobileIconMapping

    override val alwaysShowDataRatIcon: State<Boolean> =
        mobileConnectionsRepo.defaultDataSubRatConfig.map { it.alwaysShowDataRatIcon }

    override val alwaysUseCdmaLevel: State<Boolean> =
        mobileConnectionsRepo.defaultDataSubRatConfig.map { it.alwaysShowCdmaRssi }

    override val isSingleCarrier: State<Boolean> =
        filteredSubscriptions
            .map { it.size == 1 }
            .also {
                onActivated {
                    logDiffsForTable(
                        nameTag("MobileIconsInteractorKairosImpl.isSingleCarrier"),
                        it,
                        tableLogger,
                        columnPrefix = LOGGING_PREFIX,
                        columnName = "isSingleCarrier",
                    )
                }
            }

    /** If there is no mapping in [defaultMobileIconMapping], then use this default icon group */
    override val defaultMobileIconGroup: State<MobileIconGroup>
        get() = mobileConnectionsRepo.defaultMobileIconGroup

    /**
     * We want to show an error state when cellular has actually failed to validate, but not if some
     * other transport type is active, because then we expect there not to be validation.
     */
    override val isDefaultConnectionFailed: State<Boolean> =
        combine(
                mobileIsDefault,
                mobileConnectionsRepo.defaultConnectionIsValidated,
                forcingCellularValidation,
            ) { mobileIsDefault, defaultConnectionIsValidated, forcingCellularValidation ->
                when {
                    !mobileIsDefault -> false
                    forcingCellularValidation -> false
                    else -> !defaultConnectionIsValidated
                }
            }
            .also {
                onActivated {
                    logDiffsForTable(
                        nameTag("MobileIconsInteractorKairosImpl.isDefaultConnectionFailed"),
                        it,
                        tableLogger,
                        LOGGING_PREFIX,
                        columnName = "isDefaultConnectionFailed",
                    )
                }
            }

    override val isUserSetUp: State<Boolean> = buildState {
        userSetupRepo.isUserSetUp.toState(nameTag("MobileIconsInteractorKairosImpl.isUserSetUp"))
    }

    override val isForceHidden: State<Boolean> = buildState {
        connectivityRepository.forceHiddenSlots
            .toState(nameTag("MobileIconsInteractorKairosImpl.isForceHidden"))
            .map { it.contains(ConnectivitySlot.MOBILE) }
    }

    override val isRoamingForceHidden: State<Boolean> = buildState {
        connectivityRepository.forceHiddenSlots.toState().map {
            it.contains(ConnectivitySlot.ROAMING)
        }
    }

    override val isMobileHdForceHidden: State<Boolean> = buildState {
        commonImsRepo.imsIconState.toState().map { !it.showHdIcon }
    }

    override val isVoWifiForceHidden: State<Boolean> = buildState {
        commonImsRepo.imsIconState.toState().map { !it.showVowifiIcon }
    }

    override val isDeviceInEmergencyCallsOnlyMode: State<Boolean>
        get() = mobileConnectionsRepo.isDeviceEmergencyCallCapable

    /** Vends out a new [MobileIconInteractorKairos] for a particular subId */
    private fun BuildScope.mobileConnection(
        repo: MobileConnectionRepositoryKairos
    ): MobileIconInteractorKairos = activated {
        MobileIconInteractorKairosImpl(
            activeDataConnectionHasDataEnabled,
            alwaysShowDataRatIcon,
            alwaysUseCdmaLevel,
            isSingleCarrier,
            mobileIsDefault,
            defaultMobileIconMapping,
            defaultMobileIconGroup,
            isDefaultConnectionFailed,
            isForceHidden,
            isRoamingForceHidden,
            isMobileHdForceHidden,
            isVoWifiForceHidden,
            repo,
            context,
        )
    }

    companion object {
        private const val LOGGING_PREFIX = "Intr"
    }

    @dagger.Module
    interface Module {

        @Binds fun bindImpl(impl: MobileIconsInteractorKairosImpl): MobileIconsInteractorKairos

        companion object {
            @Provides
            @ElementsIntoSet
            fun kairosActivatable(
                impl: Provider<MobileIconsInteractorKairosImpl>
            ): Set<@JvmSuppressWildcards KairosActivatable> =
                if (StatusBarMobileIconKairos.isEnabled) setOf(impl.get()) else emptySet()
        }
    }
}
