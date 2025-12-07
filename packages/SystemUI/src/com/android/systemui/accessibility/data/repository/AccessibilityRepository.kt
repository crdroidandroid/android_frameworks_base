/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.data.repository

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener
import com.android.app.tracing.FlowTracing.tracedAwaitClose
import com.android.app.tracing.FlowTracing.tracedConflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** Exposes accessibility-related state. */
interface AccessibilityRepository {
    /** @see [AccessibilityManager.isTouchExplorationEnabled] */
    val isTouchExplorationEnabled: Flow<Boolean>
    /** @see [AccessibilityManager.isEnabled] */
    val isEnabled: Flow<Boolean>

    /** Returns whether a filtered set of [AccessibilityServiceInfo]s are enabled. */
    val isEnabledFiltered: StateFlow<Boolean>

    fun getRecommendedTimeout(originalTimeout: Duration, uiFlags: Int): Duration

    companion object {
        operator fun invoke(
            a11yManager: AccessibilityManager,
            @Background backgroundExecutor: Executor,
            @Background backgroundHandler: Handler,
            @Background backgroundScope: CoroutineScope,
        ): AccessibilityRepository =
            AccessibilityRepositoryImpl(
                a11yManager,
                backgroundExecutor,
                backgroundHandler,
                backgroundScope,
            )
    }
}

private const val TAG = "AccessibilityRepository"

private class AccessibilityRepositoryImpl(
    private val manager: AccessibilityManager,
    @Background private val bgExecutor: Executor,
    @Background private val bgHandler: Handler,
    @Background private val bgScope: CoroutineScope,
) : AccessibilityRepository {
    override val isTouchExplorationEnabled: Flow<Boolean> =
        tracedConflatedCallbackFlow(TAG) {
                val listener = TouchExplorationStateChangeListener(::trySend)
                manager.addTouchExplorationStateChangeListener(listener, bgHandler)
                trySend(manager.isTouchExplorationEnabled)
                tracedAwaitClose(TAG) {
                    manager.removeTouchExplorationStateChangeListener(listener)
                }
            }
            .distinctUntilChanged()

    override val isEnabled: Flow<Boolean> =
        tracedConflatedCallbackFlow(TAG) {
                val listener = AccessibilityManager.AccessibilityStateChangeListener(::trySend)
                manager.addAccessibilityStateChangeListener(listener, bgHandler)
                trySend(manager.isEnabled)
                tracedAwaitClose(TAG) { manager.removeAccessibilityStateChangeListener(listener) }
            }
            .distinctUntilChanged()

    override val isEnabledFiltered: StateFlow<Boolean> =
        tracedConflatedCallbackFlow(TAG) {
                val listener =
                    AccessibilityManager.AccessibilityServicesStateChangeListener {
                        accessibilityManager ->
                        trySend(
                            accessibilityManager
                                .getEnabledAccessibilityServiceList(
                                    AccessibilityServiceInfo.FEEDBACK_AUDIBLE or
                                        AccessibilityServiceInfo.FEEDBACK_SPOKEN or
                                        AccessibilityServiceInfo.FEEDBACK_VISUAL or
                                        AccessibilityServiceInfo.FEEDBACK_HAPTIC or
                                        AccessibilityServiceInfo.FEEDBACK_BRAILLE
                                )
                                .isNotEmpty()
                        )
                    }
                manager.addAccessibilityServicesStateChangeListener(bgExecutor, listener)
                tracedAwaitClose(TAG) {
                    manager.removeAccessibilityServicesStateChangeListener(listener)
                }
            }
            .stateIn(scope = bgScope, started = SharingStarted.Eagerly, initialValue = false)

    override fun getRecommendedTimeout(originalTimeout: Duration, uiFlags: Int): Duration {
        return manager
            .getRecommendedTimeoutMillis(originalTimeout.inWholeMilliseconds.toInt(), uiFlags)
            .milliseconds
    }
}

@Module
object AccessibilityRepositoryModule {
    @Provides
    fun provideRepo(
        manager: AccessibilityManager,
        @Background backgroundExecutor: Executor,
        @Background backgroundHandler: Handler,
        @Background backgroundScope: CoroutineScope,
    ) = AccessibilityRepository(manager, backgroundExecutor, backgroundHandler, backgroundScope)
}
