/*
 * Copyright (C) 2024 The LibreMobileOS Foundation
 * Copyright (C) 2025 crDroid Android Project
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
package com.android.systemui.statusbar.pipeline.ims.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.RegistrationManager.RegistrationCallback
import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

interface ImsRepository {
    val subId: Int
    val imsState: StateFlow<ImsStateModel>
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImsRepositoryImpl(
    override val subId: Int,
    imsManager: ImsManager,
    subscriptionManager: SubscriptionManager,
    bgDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
) : ImsRepository {

    private val imsCallback: StateFlow<ImsCallbackState> = run {
        val initial = ImsCallbackState()

        if (imsManager == null) {
            return@run kotlinx.coroutines.flow.MutableStateFlow(initial)
        }

        if (!SubscriptionManager.isValidSubscriptionId(subId) ||
            subscriptionManager == null ||
            subscriptionManager.activeSubscriptionInfoCount == 0
        ) {
            return@run kotlinx.coroutines.flow.MutableStateFlow(initial)
        }

        val imsMmTelManager = runCatching { imsManager.getImsMmTelManager(subId) }.getOrNull()
            ?: return@run kotlinx.coroutines.flow.MutableStateFlow(initial)

        val imsEvents: Flow<CallbackEvent> = callbackFlow<CallbackEvent> {
            val registrationCallback = object : RegistrationCallback() {
                override fun onRegistered(attributes: ImsRegistrationAttributes) {
                    trySend(CallbackEvent.OnImsRegistrationChanged(true, attributes))
                }
                override fun onUnregistered(info: ImsReasonInfo) {
                    trySend(CallbackEvent.OnImsRegistrationChanged(false, null))
                }
            }

            val capabilityCallback = object : ImsMmTelManager.CapabilityCallback() {
                override fun onCapabilitiesStatusChanged(caps: MmTelFeature.MmTelCapabilities) {
                    trySend(CallbackEvent.OnImsCapabilitiesStatusChanged(caps))
                }
            }

            val stateCallback = object : ImsStateCallback() {
                var registered = false
                override fun onAvailable() {
                    if (registered) return
                    runCatching<Unit> {
                        imsMmTelManager.registerImsRegistrationCallback(
                            bgDispatcher.asExecutor(), registrationCallback
                        )
                        imsMmTelManager.registerMmTelCapabilityCallback(
                            bgDispatcher.asExecutor(), capabilityCallback
                        )
                        registered = true
                    }.onFailure { close(it) }
                }
                override fun onUnavailable(reason: Int) {
                    if (!registered) return
                    runCatching<Unit> {
                        imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                        imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                    }
                    registered = false
                }
                override fun onError() {
                    if (!registered) return
                    runCatching<Unit> {
                        imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                        imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                    }
                    registered = false
                }
            }

            val regResult: Result<Unit> = runCatching<Unit> {
                imsMmTelManager.registerImsStateCallback(bgDispatcher.asExecutor(), stateCallback)
            }
            if (regResult.isFailure) {
                close(regResult.exceptionOrNull())
                return@callbackFlow
            }

            awaitClose {
                runCatching<Unit> {
                    imsMmTelManager.unregisterImsStateCallback(stateCallback)
                    imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                    imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                }
            }
        }
        imsEvents
            .retryWhen { cause: Throwable, _: Long ->
                // Retry the flow with 1 second delay
                // only if service not available.
                // This state is temporary and service may be available after sometime.
                delay(1000)
                cause is ImsException && cause.code == ImsException.CODE_ERROR_SERVICE_UNAVAILABLE
            }
            .catch { _: Throwable ->
                // Nothing
            }
            .scan(initial = initial) { state: ImsCallbackState, event: CallbackEvent ->
                state.applyEvent(event)
            }
            .stateIn(scope = scope, started = SharingStarted.WhileSubscribed(), initial)
    }

    override val imsState: StateFlow<ImsStateModel> =
        imsCallback
            .map { callbackState ->
                val registrationChanged = callbackState.onImsRegistrationChanged
                val capabilitiesChanged = callbackState.onImsCapabilitiesStatusChanged
                val registered = registrationChanged?.registered ?: false
                val capabilities = capabilitiesChanged?.capabilities
                val slotIndex = if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    SubscriptionManager.getSlotIndex(subId)
                } else {
                    SubscriptionManager.INVALID_SIM_SLOT_INDEX
                }
                ImsStateModel(
                    subId = subId,
                    slotIndex = slotIndex,
                    activeSubCount = subscriptionManager.activeSubscriptionInfoCount,
                    registered = registered,
                    capabilities = capabilities,
                    registrationTech = registrationChanged?.attributes?.registrationTechnology
                        ?: REGISTRATION_TECH_NONE
                )
            }
            .catch { emit(ImsStateModel()) /* on exception, just return default value */ }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ImsStateModel())

    private class NoOpImsRepository(override val subId: Int) : ImsRepository {
        override val imsState: StateFlow<ImsStateModel> =
            kotlinx.coroutines.flow.MutableStateFlow(ImsStateModel())
    }

    class Factory
    @Inject
    constructor(
        private val subscriptionManager: SubscriptionManager,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
        @Application private val context: Context,
    ) {
        fun build(subId: Int): ImsRepository {
            val pm = context.packageManager
            val hasTelephony =
                pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            val hasValidSub = SubscriptionManager.isValidSubscriptionId(subId)
            val hasActiveSubs = subscriptionManager.activeSubscriptionInfoCount > 0

            val imsManager: ImsManager? =
                context.getSystemService(ImsManager::class.java)

            if (!hasTelephony || imsManager == null || !hasValidSub || !hasActiveSubs) {
                return NoOpImsRepository(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            }

            return ImsRepositoryImpl(
                subId = subId,
                imsManager = imsManager,
                subscriptionManager = subscriptionManager,
                bgDispatcher = bgDispatcher,
                scope = scope,
            )
        }
    }
}

sealed interface CallbackEvent {
    data class OnImsRegistrationChanged(
        val registered: Boolean,
        val attributes: ImsRegistrationAttributes?
    ) : CallbackEvent

    data class OnImsCapabilitiesStatusChanged(
        val capabilities: MmTelFeature.MmTelCapabilities
    ) : CallbackEvent
}

data class ImsCallbackState(
    val onImsRegistrationChanged: CallbackEvent.OnImsRegistrationChanged? = null,
    val onImsCapabilitiesStatusChanged: CallbackEvent.OnImsCapabilitiesStatusChanged? = null
) {
    fun applyEvent(event: CallbackEvent): ImsCallbackState {
        return when (event) {
            is CallbackEvent.OnImsRegistrationChanged -> copy(onImsRegistrationChanged = event)
            is CallbackEvent.OnImsCapabilitiesStatusChanged -> copy(onImsCapabilitiesStatusChanged = event)
        }
    }
}
