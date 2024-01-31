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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
import android.telephony.CellSignalStrengthCdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.ERI_FLASH
import android.telephony.TelephonyManager.ERI_ON
import android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID
import android.telephony.satellite.NtnSignalStrength
import com.android.settingslib.Utils
import com.android.systemui.KairosBuilder
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.statusbar.pipeline.ims.data.repository.ImsRepository
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.ROAMING_INDICATOR_VIA_DISPLAY_INFO
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State
import com.android.systemui.kairos.Transactional
import com.android.systemui.kairos.awaitClose
import com.android.systemui.kairos.coalescingEvents
import com.android.systemui.kairos.conflatedEvents
import com.android.systemui.kairos.map
import com.android.systemui.kairos.mapNotNull
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.transactionally
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Disconnected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.toDataConnectionType
import com.android.systemui.statusbar.pipeline.mobile.data.model.toNetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.withContext

/**
 * A repository implementation for a typical mobile connection (as opposed to a carrier merged
 * connection -- see [CarrierMergedConnectionRepository]).
 */
@ExperimentalKairosApi
class MobileConnectionRepositoryKairosImpl
@AssistedInject
constructor(
    @Assisted override val subId: Int,
    private val context: Context,
    @Assisted subscriptionModel: State<SubscriptionModel?>,
    @Assisted defaultNetworkName: NetworkNameModel,
    @Assisted networkNameSeparator: String,
    connectivityManager: ConnectivityManager,
    @Assisted private val telephonyManager: TelephonyManager,
    @Assisted systemUiCarrierConfig: SystemUiCarrierConfig,
    broadcastDispatcher: BroadcastDispatcher,
    private val mobileMappingsProxy: MobileMappingsProxy,
    @Background private val bgDispatcher: CoroutineDispatcher,
    logger: MobileInputLogger,
    @Assisted override val tableLogBuffer: TableLogBuffer,
    @Assisted private val imsRepo: ImsRepository,
    flags: FeatureFlagsClassic,
) : MobileConnectionRepositoryKairos, KairosBuilder by kairosBuilder() {

    init {
        if (telephonyManager.subscriptionId != subId) {
            throw IllegalStateException(
                "MobileRepo: TelephonyManager should be created with subId($subId). " +
                    "Found ${telephonyManager.subscriptionId} instead."
            )
        }
    }

    /**
     * This flow defines the single shared connection to system_server via TelephonyCallback. Any
     * new callback should be added to this listener and funneled through callbackEvents via a data
     * class. See [CallbackEvent] for defining new callbacks.
     *
     * The reason we need to do this is because TelephonyManager limits the number of registered
     * listeners per-process, so we don't want to create a new listener for every callback.
     *
     * A note on the design for back pressure here: We don't control _which_ telephony callback
     * comes in first, since we register every relevant bit of information as a batch. E.g., if a
     * downstream starts collecting on a field which is backed by
     * [TelephonyCallback.ServiceStateListener], it's not possible for us to guarantee that _that_
     * callback comes in -- the first callback could very well be
     * [TelephonyCallback.DataActivityListener], which would promptly be dropped if we didn't keep
     * it tracked. We use the [scan] operator here to track the most recent callback of _each type_
     * here. See [TelephonyCallbackState] to see how the callbacks are stored.
     */
    private val callbackEvents: Events<TelephonyCallbackState> = buildEvents {
        coalescingEvents(
            initialValue = TelephonyCallbackState(),
            coalesce = TelephonyCallbackState::applyEvent,
        ) {
            val callback =
                object :
                    TelephonyCallback(),
                    TelephonyCallback.CarrierNetworkListener,
                    TelephonyCallback.CarrierRoamingNtnListener,
                    TelephonyCallback.DataActivityListener,
                    TelephonyCallback.DataConnectionStateListener,
                    TelephonyCallback.DataEnabledListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.SignalStrengthsListener,
                    TelephonyCallback.EmergencyCallbackModeListener {

                    override fun onCarrierNetworkChange(active: Boolean) {
                        logger.logOnCarrierNetworkChange(active, subId)
                        emit(CallbackEvent.OnCarrierNetworkChange(active))
                    }

                    override fun onCarrierRoamingNtnModeChanged(active: Boolean) {
                        logger.logOnCarrierRoamingNtnModeChanged(active, subId)
                        emit(CallbackEvent.OnCarrierRoamingNtnModeChanged(active))
                    }

                    override fun onDataActivity(direction: Int) {
                        logger.logOnDataActivity(direction, subId)
                        emit(CallbackEvent.OnDataActivity(direction))
                    }

                    override fun onDataEnabledChanged(enabled: Boolean, reason: Int) {
                        logger.logOnDataEnabledChanged(enabled, subId)
                        emit(CallbackEvent.OnDataEnabledChanged(enabled))
                    }

                    override fun onDataConnectionStateChanged(dataState: Int, networkType: Int) {
                        logger.logOnDataConnectionStateChanged(dataState, networkType, subId)
                        emit(CallbackEvent.OnDataConnectionStateChanged(dataState))
                    }

                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        logger.logOnDisplayInfoChanged(telephonyDisplayInfo, subId)
                        emit(CallbackEvent.OnDisplayInfoChanged(telephonyDisplayInfo))
                    }

                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        logger.logOnServiceStateChanged(serviceState, subId)
                        emit(CallbackEvent.OnServiceStateChanged(serviceState))
                    }

                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        logger.logOnSignalStrengthsChanged(signalStrength, subId)
                        emit(CallbackEvent.OnSignalStrengthChanged(signalStrength))
                    }

                    override fun onCarrierRoamingNtnSignalStrengthChanged(
                        signalStrength: NtnSignalStrength
                    ) {
                        logger.logNtnSignalStrengthChanged(signalStrength, subId)
                        emit(CallbackEvent.OnCarrierRoamingNtnSignalStrengthChanged(signalStrength))
                    }

                    override fun onCallbackModeStarted(
                        type: Int,
                        timerDuration: Duration,
                        subId: Int,
                    ) {
                        // logger.logOnCallBackModeStarted(type, subId)
                        emit(CallbackEvent.OnCallBackModeStarted(type))
                    }

                    override fun onCallbackModeRestarted(
                        type: Int,
                        timerDuration: Duration,
                        subId: Int,
                    ) {
                        // no-op
                    }

                    override fun onCallbackModeStopped(type: Int, reason: Int, subId: Int) {
                        // logger.logOnCallBackModeStopped(type, reason, subId)
                        emit(CallbackEvent.OnCallBackModeStopped(type))
                    }
                }
            withContext(bgDispatcher) {
                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
            }
            awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
        }
    }

    private val serviceState: State<ServiceState?> = buildState {
        callbackEvents
            .mapNotNull { it.onServiceStateChanged?.serviceState }
            .holdState(null, nameTag("MobileConnectionRepositoryKairosImpl.serviceState"))
    }

    override val isEmergencyOnly: State<Boolean> = serviceState.map { it?.isEmergencyOnly == true }

    private val displayInfo: State<TelephonyDisplayInfo?> = buildState {
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged?.telephonyDisplayInfo }
            .holdState(null, nameTag("MobileConnectionRepositoryKairosImpl.displayInfo"))
    }

    override val isRoaming: State<Boolean> =
        if (flags.isEnabled(ROAMING_INDICATOR_VIA_DISPLAY_INFO)) {
            displayInfo.map { it?.isRoaming == true }
        } else {
            serviceState.map { it?.roaming == true }
        }

    override val operatorAlphaShort: State<String?> = serviceState.map { it?.operatorAlphaShort }

    override val isInService: State<Boolean> =
        serviceState.map { it?.let(Utils::isInService) == true }

    private val carrierRoamingNtnActive: State<Boolean> = buildState {
        callbackEvents
            .mapNotNull { it.onCarrierRoamingNtnModeChanged?.active }
            .holdState(
                false,
                nameTag("MobileConnectionRepositoryKairosImpl.carrierRoamingNtnActive"),
            )
    }

    override val isNonTerrestrial: State<Boolean>
        get() = carrierRoamingNtnActive

    private val signalStrength: State<SignalStrength?> = buildState {
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged?.signalStrength }
            .holdState(null, nameTag("MobileConnectionRepositoryKairosImpl.signalStrength"))
    }

    override val isGsm: State<Boolean> = signalStrength.map { it?.isGsm == true }

    override val cdmaLevel: State<Int> =
        signalStrength.map {
            it?.getCellSignalStrengths(CellSignalStrengthCdma::class.java)?.firstOrNull()?.level
                ?: SIGNAL_STRENGTH_NONE_OR_UNKNOWN
        }

    override val primaryLevel: State<Int> =
        signalStrength.map { it?.level ?: SIGNAL_STRENGTH_NONE_OR_UNKNOWN }

    override val satelliteLevel: State<Int> = buildState {
        callbackEvents
            .mapNotNull { it.onCarrierRoamingNtnSignalStrengthChanged?.signalStrength?.level }
            .holdState(0, nameTag("MobileConnectionRepositoryKairosImpl.satelliteLevel"))
    }

    override val dataConnectionState: State<DataConnectionState> = buildState {
        callbackEvents
            .mapNotNull { it.onDataConnectionStateChanged?.dataState?.toDataConnectionType() }
            .holdState(
                Disconnected,
                nameTag("MobileConnectionRepositoryKairosImpl.dataConnectionState"),
            )
    }

    override val dataActivityDirection: State<DataActivityModel> = buildState {
        callbackEvents
            .mapNotNull { it.onDataActivity?.direction?.toMobileDataActivityModel() }
            .holdState(
                DataActivityModel(hasActivityIn = false, hasActivityOut = false),
                nameTag("MobileConnectionRepositoryKairosImpl.dataActivityDirection"),
            )
    }

    override val carrierNetworkChangeActive: State<Boolean> = buildState {
        callbackEvents
            .mapNotNull { it.onCarrierNetworkChange?.active }
            .holdState(
                false,
                nameTag("MobileConnectionRepositoryKairosImpl.carrierNetworkChangeActive"),
            )
    }

    private val telephonyDisplayInfo: State<TelephonyDisplayInfo?> = buildState {
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged?.telephonyDisplayInfo }
            .holdState(null, nameTag("MobileConnectionRepositoryKairosImpl.telephonyDisplayInfo"))
    }

    override val resolvedNetworkType: State<ResolvedNetworkType> =
        telephonyDisplayInfo.map { displayInfo ->
            displayInfo
                ?.overrideNetworkType
                ?.takeIf { it != OVERRIDE_NETWORK_TYPE_NONE }
                ?.let { OverrideNetworkType(mobileMappingsProxy.toIconKeyOverride(it)) }
                ?: displayInfo
                    ?.networkType
                    ?.takeIf { it != NETWORK_TYPE_UNKNOWN }
                    ?.let { DefaultNetworkType(mobileMappingsProxy.toIconKey(it)) }
                ?: UnknownNetworkType
        }

    override val inflateSignalStrength: State<Boolean> = buildState {
        systemUiCarrierConfig.shouldInflateSignalStrength.toState(
            nameTag("MobileConnectionRepositoryKairosImpl.inflateSignalStrength")
        )
    }

    override val allowNetworkSliceIndicator: State<Boolean> = buildState {
        systemUiCarrierConfig.allowNetworkSliceIndicator.toState(
            nameTag("MobileConnectionRepositoryKairosImpl.allowNetworkSliceIndicator")
        )
    }

    override val numberOfLevels: State<Int> =
        inflateSignalStrength.map { shouldInflate ->
            if (shouldInflate) {
                DEFAULT_NUM_LEVELS + 1
            } else {
                DEFAULT_NUM_LEVELS
            }
        }

    override val carrierName: State<NetworkNameModel> =
        subscriptionModel.map {
            it?.let { model -> NetworkNameModel.SubscriptionDerived(model.carrierName) }
                ?: defaultNetworkName
        }

    /**
     * There are a few cases where we will need to poll [TelephonyManager] so we can update some
     * internal state where callbacks aren't provided. Any of those events should be merged into
     * this flow, which can be used to trigger the polling.
     */
    private val telephonyPollingEvent: Events<Unit> = callbackEvents.map {}

    private val cdmaEnhancedRoamingIndicatorDisplayNumber: Transactional<Int?> = transactionally {
        try {
            telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber
        } catch (e: UnsupportedOperationException) {
            // Handles the same as a function call failure
            null
        }
    }

    override val cdmaRoaming: State<Boolean> = buildState {
        telephonyPollingEvent
            .map {
                val cdmaEri = cdmaEnhancedRoamingIndicatorDisplayNumber.sample()
                cdmaEri == ERI_ON || cdmaEri == ERI_FLASH
            }
            .holdState(false, nameTag("MobileConnectionRepositoryKairosImpl.cdmaRoaming"))
    }

    override val carrierId: State<Int> = buildState {
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter(TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED),
                map = { intent, _ -> intent },
            )
            .filter { intent ->
                intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, INVALID_SUBSCRIPTION_ID) == subId
            }
            .map { it.carrierId() }
            .toState(
                telephonyManager.simCarrierId,
                nameTag("MobileConnectionRepositoryKairosImpl.carrierId"),
            )
    }

    /**
     * BroadcastDispatcher does not handle sticky broadcasts, so we can't use it here. Note that we
     * now use the [SharingStarted.Eagerly] strategy, because there have been cases where the sticky
     * broadcast does not represent the correct state.
     *
     * See b/322432056 for context.
     */
    @SuppressLint("RegisterReceiverViaContext")
    override val networkName: State<NetworkNameModel> = buildState {
        conflatedEvents {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (
                                intent.getIntExtra(
                                    EXTRA_SUBSCRIPTION_INDEX,
                                    INVALID_SUBSCRIPTION_ID,
                                ) == subId
                            ) {
                                logger.logServiceProvidersUpdatedBroadcast(intent)
                                emit(
                                    intent.toNetworkNameModel(networkNameSeparator)
                                        ?: defaultNetworkName
                                )
                            }
                        }
                    }

                context.registerReceiver(
                    receiver,
                    IntentFilter(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED),
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .holdState(
                defaultNetworkName,
                nameTag("MobileConnectionRepositoryKairosImpl.networkName"),
            )
    }

    override val dataEnabled: State<Boolean> = buildState {
        callbackEvents
            .mapNotNull { it.onDataEnabledChanged?.enabled }
            .holdState(
                telephonyManager.isDataConnectionAllowed,
                nameTag("MobileConnectionRepositoryKairosImpl.dataEnabled"),
            )
    }

    override fun setDataEnabled(enabled: Boolean) {
        telephonyManager.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, enabled)
    }

    override val isInEcmMode: State<Boolean> = buildState {
        callbackEvents
            .mapNotNull {
                (it.addedCallbackModes to it.removedCallbackModes).takeIf { (added, removed) ->
                    added.isNotEmpty() || removed.isNotEmpty()
                }
            }
            .foldState(
                emptySet<Int>(),
                nameTag("MobileConnectionRepositoryKairosImpl.isInEcmMode"),
            ) { (added, removed), acc ->
                acc - removed + added
            }
            .map { it.isNotEmpty() }
    }

    /** Typical mobile connections aren't available during airplane mode. */
    override val isAllowedDuringAirplaneMode: State<Boolean> = stateOf(false)

    /**
     * Currently, a network with NET_CAPABILITY_PRIORITIZE_LATENCY is the only type of network that
     * we consider to be a "network slice". _PRIORITIZE_BANDWIDTH may be added in the future. Any of
     * these capabilities that are used here must also be represented in the
     * self_certified_network_capabilities.xml config file
     */
    @SuppressLint("WrongConstant")
    private val networkSliceRequest: NetworkRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)
            .setSubscriptionIds(setOf(subId))
            .build()

    @SuppressLint("MissingPermission")
    override val hasPrioritizedNetworkCapabilities: State<Boolean> = buildState {
        conflatedEvents {
                // Our network callback listens only for this.subId && net_cap_prioritize_latency
                // therefore our state is a simple mapping of whether or not that network exists
                val callback =
                    object : NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            logger.logPrioritizedNetworkAvailable(network.netId)
                            emit(true)
                        }

                        override fun onLost(network: Network) {
                            logger.logPrioritizedNetworkLost(network.netId)
                            emit(false)
                        }
                    }

                connectivityManager.registerNetworkCallback(networkSliceRequest, callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .holdState(
                false,
                nameTag("MobileConnectionRepositoryKairosImpl.hasPrioritizedNetworkCapabilities"),
            )
    }

    override val imsState: State<ImsStateModel> = buildState { imsRepo.imsState.toState() }

    @AssistedFactory
    fun interface Factory {
        fun create(
            subId: Int,
            mobileLogger: TableLogBuffer,
            subscriptionModel: State<SubscriptionModel?>,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
            systemUiCarrierConfig: SystemUiCarrierConfig,
            telephonyManager: TelephonyManager,
            imsRepo: ImsRepository,
        ): MobileConnectionRepositoryKairosImpl
    }
}

private fun Intent.carrierId(): Int =
    getIntExtra(TelephonyManager.EXTRA_CARRIER_ID, UNKNOWN_CARRIER_ID)
