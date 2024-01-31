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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyCallback.EmergencyCallbackModeListener
import android.telephony.TelephonyManager
import android.util.IndentingPrintWriter
import com.android.internal.telephony.PhoneConstants
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.Dumpable
import com.android.systemui.KairosActivatable
import com.android.systemui.KairosBuilder
import com.android.systemui.activated
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.Incremental
import com.android.systemui.kairos.State
import com.android.systemui.kairos.StateSelector
import com.android.systemui.kairos.asIncremental
import com.android.systemui.kairos.asyncEvent
import com.android.systemui.kairos.buildSpec
import com.android.systemui.kairos.changes
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.effect
import com.android.systemui.kairos.filterNotNull
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.map
import com.android.systemui.kairos.mapNotNull
import com.android.systemui.kairos.mapValues
import com.android.systemui.kairos.mergeLeft
import com.android.systemui.kairos.onEach
import com.android.systemui.kairos.rebuildOn
import com.android.systemui.kairos.selector
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairos.switchEvents
import com.android.systemui.kairos.transitions
import com.android.systemui.kairos.util.WithPrev
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.kairosBuilder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.dagger.MobileSummaryLog
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepositoryKairos
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepositoryKairos
import com.android.systemui.statusbar.pipeline.ims.data.repository.ImsRepositoryImpl
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.Binds
import dagger.Lazy
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import java.io.PrintWriter
import java.time.Duration
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

@ExperimentalKairosApi
@SysUISingleton
class MobileConnectionsRepositoryKairosImpl
@Inject
constructor(
    connectivityRepository: ConnectivityRepository,
    private val subscriptionManager: SubscriptionManager,
    private val subscriptionManagerProxy: SubscriptionManagerProxy,
    private val telephonyManager: TelephonyManager,
    private val logger: MobileInputLogger,
    @MobileSummaryLog private val tableLogger: TableLogBuffer,
    mobileMappingsProxy: MobileMappingsProxy,
    broadcastDispatcher: BroadcastDispatcher,
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Main private val mainDispatcher: CoroutineDispatcher,
    airplaneModeRepository: AirplaneModeRepository,
    // Some "wifi networks" should be rendered as a mobile connection, which is why the wifi
    // repository is an input to the mobile repository.
    // See [CarrierMergedConnectionRepositoryKairos] for details.
    wifiRepository: WifiRepository,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    dumpManager: DumpManager,
    private val mobileRepoFactory: Lazy<ConnectionRepoFactory>,
) : MobileConnectionsRepositoryKairos, Dumpable, KairosBuilder by kairosBuilder() {

    private var dumpCache: DumpCache? = null

    init {
        dumpManager.registerNormalDumpable("MobileConnectionsRepositoryKairos", this)
    }

    private val carrierMergedSubId: State<Int?> = buildState {
        combine(
                wifiRepository.wifiNetwork.toState(
                    nameTag("MobileConnectionsRepositoryKairos.wifiNetwork")
                ),
                defaultConnections,
                airplaneModeRepository.isAirplaneMode.toState(
                    nameTag("MobileConnectionsRepositoryKairos.isAirplaneMode")
                ),
            ) { wifiNetwork, defaultConnections, isAirplaneMode ->
                // The carrier merged connection should only be used if it's also the default
                // connection or mobile connections aren't available because of airplane mode.
                val defaultConnectionIsNonMobile =
                    defaultConnections.carrierMerged.isDefault ||
                        defaultConnections.wifi.isDefault ||
                        isAirplaneMode

                if (wifiNetwork is WifiNetworkModel.CarrierMerged && defaultConnectionIsNonMobile) {
                    wifiNetwork.subscriptionId
                } else {
                    null
                }
            }
            .also {
                logDiffsForTable(
                    name = nameTag("MobileConnectionsRepositoryKairosImpl.carrierMergedSubId"),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "carrierMergedSubId",
                )
            }
    }

    private val mobileSubscriptionsChangeEvent: Events<Unit> = buildEvents {
        conflatedCallbackFlow {
                val callback =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            logger.logOnSubscriptionsChanged()
                            trySend(Unit)
                        }
                    }
                subscriptionManager.addOnSubscriptionsChangedListener(Runnable::run, callback)
                awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(callback) }
            }
            .flowOn(bgDispatcher)
            .toEvents(
                nameTag("MobileConnectionsRepositoryKairosImpl.mobileSubscriptionsChangeEvent")
            )
    }

    /** Turn ACTION_SERVICE_STATE (for subId = -1) into an event */
    private val serviceStateChangedEvent: Events<Unit> = buildEvents {
        broadcastDispatcher
            .broadcastFlow(IntentFilter(Intent.ACTION_SERVICE_STATE)) { intent, _ ->
                val subId =
                    intent.getIntExtra(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        INVALID_SUBSCRIPTION_ID,
                    )

                // Only emit if the subId is not associated with an active subscription
                if (subId == INVALID_SUBSCRIPTION_ID) {
                    Unit
                }
            }
            .toEvents(nameTag("MobileConnectionsRepositoryKairosImpl.serviceStateChangedEvent"))
    }

    /** Eager flow to determine the device-based emergency calls only state */
    override val isDeviceEmergencyCallCapable: State<Boolean> = buildState {
        rebuildOn(
                serviceStateChangedEvent,
                nameTag(
                    "MobileConnectionsRepositoryKairosImpl.isDeviceEmergencyCallCapable::rebuildOn"
                ),
            ) {
                asyncEvent(
                    nameTag(
                        "MobileConnectionsRepositoryKairosImpl.doAnyModemsSupportEmergencyCalls"
                    )
                ) {
                    doAnyModemsSupportEmergencyCalls()
                }
            }
            .switchEvents()
            .holdState(
                false,
                nameTag("MobileConnectionsRepositoryKairosImpl.isDeviceEmergencyCallCapable"),
            )
            .also {
                logDiffsForTable(
                    name =
                        nameTag(
                            "MobileConnectionsRepositoryKairosImpl.isDeviceEmergencyCallCapable::logDiffs"
                        ),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "deviceEmergencyOnly",
                )
            }
    }

    private suspend fun doAnyModemsSupportEmergencyCalls(): Boolean =
        withContext(bgDispatcher) {
            val modems = telephonyManager.activeModemCount

            // Assume false for automotive devices which don't have the calling feature.
            // TODO: b/398045526 to revisit the below.
            val isAutomotive: Boolean =
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            val hasFeatureCalling: Boolean =
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
            if (isAutomotive && !hasFeatureCalling) {
                return@withContext false
            }

            // Check the service state for every modem. If any state reports emergency calling
            // capable, then consider the device to have emergency call capabilities
            (0..<modems)
                .map { telephonyManager.getServiceStateForSlot(it) }
                .any { it?.isEmergencyOnly == true }
        }

    /**
     * State flow that emits the set of mobile data subscriptions, each represented by its own
     * [SubscriptionModel].
     */
    override val subscriptions: State<List<SubscriptionModel>> = buildState {
        rebuildOn(
                mergeLeft(mobileSubscriptionsChangeEvent, carrierMergedSubId.changes),
                nameTag("MobileConnectionsRepositoryKairosImpl.subscriptions::rebuildOn"),
            ) {
                asyncEvent(
                    nameTag("MobileConnectionsRepositoryKairosImpl.fetchSubscriptionModels")
                ) {
                    fetchSubscriptionModels()
                }
            }
            .switchEvents()
            .holdState(emptyList(), nameTag("MobileConnectionsRepositoryKairosImpl.subscriptions"))
            .also {
                logDiffsForTable(
                    name = nameTag("MobileConnectionsRepositoryKairosImpl.subscriptions::logDiffs"),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "subscriptions",
                )
            }
    }

    val subscriptionsById: State<Map<Int, SubscriptionModel>> =
        subscriptions.map { subs -> subs.associateBy { it.subscriptionId } }

    override val mobileConnectionsBySubId: Incremental<Int, MobileConnectionRepositoryKairos> =
        buildIncremental {
            subscriptionsById
                .map { it.mapValues {} } // only base diffs off of keys
                .asIncremental()
                .mapValues { (subId, sub) -> mobileRepoFactory.get().create(subId) }
                .applyLatestSpecForKey(
                    name = nameTag("MobileConnectionsRepositoryKairosImpl.mobileConnectionsBySubId")
                )
                .apply {
                    observe(name = nameTag("MobileConnectionsRepositoryKairosImpl.dumpCache")) {
                        dumpCache = DumpCache(it)
                    }
                }
        }

    private val telephonyManagerState: State<Pair<Int?, Set<Int>>> = buildState {
        callbackFlow {
                val callback =
                    object :
                        TelephonyCallback(),
                        ActiveDataSubscriptionIdListener,
                        EmergencyCallbackModeListener {
                        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                            if (subId != INVALID_SUBSCRIPTION_ID) {
                                trySend { (_, set): Pair<Int?, Set<Int>> -> subId to set }
                            } else {
                                trySend { (_, set): Pair<Int?, Set<Int>> -> null to set }
                            }
                        }

                        override fun onCallbackModeStarted(
                            type: Int,
                            timerDuration: Duration,
                            subId: Int,
                        ) {
                            trySend { (id, set): Pair<Int?, Set<Int>> -> id to (set + type) }
                        }

                        override fun onCallbackModeRestarted(
                            type: Int,
                            timerDuration: Duration,
                            subId: Int,
                        ) {
                            // no-op
                        }

                        override fun onCallbackModeStopped(type: Int, reason: Int, subId: Int) {
                            trySend { (id, set): Pair<Int?, Set<Int>> -> id to (set - type) }
                        }
                    }
                telephonyManager.registerTelephonyCallback(Runnable::run, callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .flowOn(bgDispatcher)
            .scanToState(
                null to emptySet(),
                nameTag("MobileConnectionsRepositoryKairosImpl.telephonyManagerState"),
            )
    }

    override val activeMobileDataSubscriptionId: State<Int?> =
        telephonyManagerState
            .map { it.first }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag(
                                "MobileConnectionsRepositoryKairosImpl.activeMobileDataSubscriptionId"
                            ),
                        it,
                        tableLogger,
                        LOGGING_PREFIX,
                        columnName = "activeSubId",
                    )
                }
            }

    override val activeMobileDataRepository: State<MobileConnectionRepositoryKairos?> =
        combine(activeMobileDataSubscriptionId, mobileConnectionsBySubId) { id, cache -> cache[id] }

    override val defaultDataSubId: State<Int?> = buildState {
        broadcastDispatcher
            .broadcastFlow(
                IntentFilter(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
            ) { intent, _ ->
                intent
                    .getIntExtra(PhoneConstants.SUBSCRIPTION_KEY, INVALID_SUBSCRIPTION_ID)
                    .takeIf { it != INVALID_SUBSCRIPTION_ID }
            }
            .onStart {
                emit(
                    subscriptionManagerProxy.getDefaultDataSubscriptionId().takeIf {
                        it != INVALID_SUBSCRIPTION_ID
                    }
                )
            }
            .toState(
                initialValue = null,
                nameTag("MobileConnectionsRepositoryKairosImpl.defaultDataSubId"),
            )
            .also {
                logDiffsForTable(
                    name =
                        nameTag("MobileConnectionsRepositoryKairosImpl.defaultDataSubId::logDiffs"),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "defaultSubId",
                )
            }
    }

    private val carrierConfigChangedEvent: Events<Unit> =
        buildEvents {
                broadcastDispatcher
                    .broadcastFlow(IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED))
                    .toEvents(
                        nameTag("MobileConnectionsRepositoryKairosImpl.carrierConfigChangedEvent")
                    )
            }
            .onEach { logger.logActionCarrierConfigChanged() }

    override val defaultDataSubRatConfig: State<Config> = buildState {
        rebuildOn(
            rebuildSignal = mergeLeft(defaultDataSubId.changes, carrierConfigChangedEvent),
            nameTag("MobileConnectionsRepositoryKairosImpl.defaultDataSubRatConfig::rebuildOn"),
        ) {
            Config.readConfig(context).also {
                effect(
                    name = nameTag("MobileConnectionsRepositoryKairosImpl.defaultDataSubRatConfig")
                ) {
                    logger.logDefaultDataSubRatConfig(it)
                }
            }
        }
    }

    override val defaultMobileIconMapping: State<Map<String, MobileIconGroup>> = buildState {
        defaultDataSubRatConfig
            .map { mobileMappingsProxy.mapIconSets(it) }
            .apply {
                observe(
                    name = nameTag("MobileConnectionsRepositoryKairosImpl.defaultMobileIconMapping")
                ) {
                    logger.logDefaultMobileIconMapping(it)
                }
            }
    }

    override val defaultMobileIconGroup: State<MobileIconGroup> = buildState {
        defaultDataSubRatConfig
            .map { mobileMappingsProxy.getDefaultIcons(it) }
            .apply {
                observe(
                    name = nameTag("MobileConnectionsRepositoryKairosImpl.defaultMobileIconGroup")
                ) {
                    logger.logDefaultMobileIconGroup(it)
                }
            }
    }

    override val isAnySimSecure: State<Boolean> = buildState {
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onSimStateChanged(subId: Int, slotId: Int, simState: Int) {
                            logger.logOnSimStateChanged()
                            trySend(keyguardUpdateMonitor.isSimPinSecure)
                        }
                    }
                keyguardUpdateMonitor.registerCallback(callback)
                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .flowOn(mainDispatcher)
            .toState(false, nameTag("MobileConnectionsRepositoryKairosImpl.isAnySimSecure"))
            .also {
                logDiffsForTable(
                    name =
                        nameTag("MobileConnectionsRepositoryKairosImpl.isAnySimSecure::logDiffs"),
                    it,
                    tableLogger,
                    LOGGING_PREFIX,
                    columnName = "isAnySimSecure",
                )
            }
    }

    private val defaultConnections: State<DefaultConnectionModel> = buildState {
        connectivityRepository.defaultConnections.toState(
            nameTag("MobileConnectionsRepositoryKairos.defaultConnections")
        )
    }

    override val mobileIsDefault: State<Boolean> =
        defaultConnections
            .map { it.mobile.isDefault }
            .also {
                onActivated {
                    logDiffsForTable(
                        name = nameTag("MobileConnectionsRepositoryKairosImpl.mobileIsDefault"),
                        it,
                        tableLogger,
                        columnPrefix = LOGGING_PREFIX,
                        columnName = "mobileIsDefault",
                    )
                }
            }

    override val hasCarrierMergedConnection: State<Boolean> =
        carrierMergedSubId
            .map { it != null }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag(
                                "MobileConnectionsRepositoryKairosImpl.hasCarrierMergedConnection"
                            ),
                        it,
                        tableLogger,
                        columnPrefix = LOGGING_PREFIX,
                        columnName = "hasCarrierMergedConnection",
                    )
                }
            }

    override val defaultConnectionIsValidated: State<Boolean> =
        defaultConnections
            .map { it.isValidated }
            .also {
                onActivated {
                    logDiffsForTable(
                        name =
                            nameTag(
                                "MobileConnectionsRepositoryKairosImpl.defaultConnectionIsValidated"
                            ),
                        it,
                        tableLogger,
                        columnPrefix = LOGGING_PREFIX,
                        columnName = "defaultConnectionIsValidated",
                    )
                }
            }

    /**
     * Flow that tracks the active mobile data subscriptions. Emits `true` whenever the active data
     * subscription Id changes but the subscription group remains the same. In these cases, we want
     * to retain the previous subscription's validation status for up to 2s to avoid flickering the
     * icon.
     *
     * TODO(b/265164432): we should probably expose all change events, not just same group
     */
    @SuppressLint("MissingPermission")
    override val activeSubChangedInGroupEvent: Events<Unit> = buildEvents {
        activeMobileDataSubscriptionId.transitions
            .mapNotNull { (prevVal, newVal) ->
                prevVal?.let { newVal?.let { WithPrev(prevVal, newVal) } }
            }
            .mapAsyncLatest(
                nameTag("MobileConnectionsRepositoryKairosImpl.activeSubChangedInGroupEvent")
            ) { (prevVal, newVal) ->
                if (isActiveSubChangeInGroup(prevVal, newVal)) Unit else null
            }
            .filterNotNull()
    }

    private suspend fun isActiveSubChangeInGroup(prevId: Int, newId: Int): Boolean =
        withContext(bgDispatcher) {
            val prevSub = subscriptionManager.getActiveSubscriptionInfo(prevId)?.groupUuid
            val nextSub = subscriptionManager.getActiveSubscriptionInfo(newId)?.groupUuid
            prevSub != null && prevSub == nextSub
        }

    private val isInEcmModeTopLevel: State<Boolean> =
        telephonyManagerState.map { it.second.isNotEmpty() }

    override val isInEcmMode: State<Boolean> =
        isInEcmModeTopLevel.flatMap { isInEcm ->
            if (isInEcm) {
                stateOf(true)
            } else {
                mobileConnectionsBySubId.flatMap {
                    it.mapValues { it.value.isInEcmMode }.combine().map { it.values.any { it } }
                }
            }
        }

    /** Determines which subId is currently carrier-merged. */
    val carrierMergedSelector: StateSelector<Int?> = carrierMergedSubId.selector()

    private suspend fun fetchSubscriptionModels(): List<SubscriptionModel> =
        withContext(bgDispatcher) {
            subscriptionManager.completeActiveSubscriptionInfoList.map { it.toSubscriptionModel() }
        }

    private fun SubscriptionInfo.toSubscriptionModel(): SubscriptionModel =
        SubscriptionModel(
            subscriptionId = subscriptionId,
            isOpportunistic = isOpportunistic,
            isExclusivelyNonTerrestrial = isOnlyNonTerrestrialNetwork,
            groupUuid = groupUuid,
            carrierName = carrierName.toString(),
            profileClass = profileClass,
        )

    override fun dump(pw: PrintWriter, args: Array<String>) {
        val cache = dumpCache ?: return
        val ipw = IndentingPrintWriter(pw, " ")
        ipw.println("Connection cache:")

        ipw.increaseIndent()
        cache.repos.forEach { (subId, repo) -> ipw.println("$subId: $repo") }
        ipw.decreaseIndent()

        ipw.println("Connections (${cache.repos.size} total):")
        ipw.increaseIndent()
        cache.repos.values.forEach {
            if (it is FullMobileConnectionRepositoryKairos) {
                it.dump(ipw)
            }
        }
        ipw.decreaseIndent()
    }

    private data class DumpCache(val repos: Map<Int, MobileConnectionRepositoryKairos>)

    fun interface ConnectionRepoFactory {
        fun create(subId: Int): BuildSpec<MobileConnectionRepositoryKairos>
    }

    @dagger.Module
    object Module {
        @Provides
        @ElementsIntoSet
        fun kairosActivatable(
            impl: Provider<MobileConnectionsRepositoryKairosImpl>
        ): Set<@JvmSuppressWildcards KairosActivatable> =
            if (StatusBarMobileIconKairos.isEnabled) setOf(impl.get()) else emptySet()
    }

    companion object {
        private const val LOGGING_PREFIX = "Repo"
    }
}

@ExperimentalKairosApi
class MobileConnectionRepositoryKairosFactoryImpl
@Inject
constructor(
    context: Context,
    private val connectionsRepo: MobileConnectionsRepositoryKairosImpl,
    private val logFactory: TableLogBufferFactory,
    private val carrierConfigRepo: CarrierConfigRepository,
    private val telephonyManager: TelephonyManager,
    private val mobileRepoFactory: MobileConnectionRepositoryKairosImpl.Factory,
    private val mergedRepoFactory: CarrierMergedConnectionRepositoryKairos.Factory,
    private val imsRepoFactory: ImsRepositoryImpl.Factory,
) : MobileConnectionsRepositoryKairosImpl.ConnectionRepoFactory {

    private val networkNameSeparator: String =
        context.getString(R.string.status_bar_network_name_separator)

    private val defaultNetworkName =
        NetworkNameModel.Default(
            context.getString(com.android.internal.R.string.lockscreen_carrier_default)
        )

    override fun create(subId: Int): BuildSpec<MobileConnectionRepositoryKairos> = buildSpec {
        activated {
            val mobileLogger =
                logFactory.getOrCreate(tableBufferLogName(subId), MOBILE_CONNECTION_BUFFER_SIZE)
            val mobileRepo = activated {
                mobileRepoFactory.create(
                    subId,
                    mobileLogger,
                    connectionsRepo.subscriptionsById.map { subs -> subs[subId] },
                    defaultNetworkName,
                    networkNameSeparator,
                    carrierConfigRepo.getOrCreateConfigForSubId(subId),
                    telephonyManager.createForSubscriptionId(subId),
		    imsRepoFactory.build(subId),
                )
            }
            FullMobileConnectionRepositoryKairos(
                subId = subId,
                tableLogBuffer = mobileLogger,
                mobileRepo = mobileRepo,
                carrierMergedRepoSpec =
                    buildSpec {
                        activated { mergedRepoFactory.build(subId, mobileLogger, mobileRepo) }
                    },
                isCarrierMerged = connectionsRepo.carrierMergedSelector[subId],
            )
        }
    }

    companion object {
        /** The buffer size to use for logging. */
        private const val MOBILE_CONNECTION_BUFFER_SIZE = 100

        /** Returns a log buffer name for a mobile connection with the given [subId]. */
        fun tableBufferLogName(subId: Int): String = "MobileConnectionLog[$subId]"
    }

    @dagger.Module
    interface Module {
        @Binds
        fun bindImpl(
            impl: MobileConnectionRepositoryKairosFactoryImpl
        ): MobileConnectionsRepositoryKairosImpl.ConnectionRepoFactory
    }
}
