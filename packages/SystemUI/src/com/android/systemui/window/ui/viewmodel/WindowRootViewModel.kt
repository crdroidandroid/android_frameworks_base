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

package com.android.systemui.window.ui.viewmodel

import android.os.Build
import android.util.Log
import com.android.systemui.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.transitions.GlanceableHubTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/** View model for window root view. */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowRootViewModel
@AssistedInject
constructor(
    primaryBouncerTransitions: Set<@JvmSuppressWildcards PrimaryBouncerTransition>,
    glanceableHubTransitions: Set<@JvmSuppressWildcards GlanceableHubTransition>,
    private val blurInteractor: WindowRootViewBlurInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeInteractor: ShadeInteractor,
) {

    private val bouncerBlurRadiusFlows =
        if (Flags.bouncerUiRevamp())
            primaryBouncerTransitions.map { it.windowBlurRadius.logIfPossible(it.javaClass.name) }
        else emptyList()

    private val glanceableHubBlurRadiusFlows =
        if (Flags.glanceableHubBlurredBackground())
            glanceableHubTransitions.map { it.windowBlurRadius.logIfPossible(it.javaClass.name) }
        else emptyList()

    private val _blurRadius =
        listOf(
                *bouncerBlurRadiusFlows.toTypedArray(),
                *glanceableHubBlurRadiusFlows.toTypedArray(),
                blurInteractor.blurRadiusRequestedByShade
                    .map { it.toFloat() }
                    .logIfPossible("ShadeBlur"),
            )
            .merge()

    val blurRadius: Flow<Float> =
        blurInteractor.isBlurCurrentlySupported.flatMapLatest { blurSupported ->
            if (blurSupported) {
                _blurRadius
            } else {
                flowOf(0f)
            }
        }

    val isPersistentEarlyWakeupRequired =
        blurInteractor.isBlurCurrentlySupported
            .flatMapLatest { blurSupported ->
                if (blurSupported) {
                    combine(
                        keyguardInteractor.isKeyguardShowing,
                        shadeInteractor.isUserInteracting,
                        shadeInteractor.isAnyExpanded,
                    ) { keyguardShowing, userDraggingShade, anyExpanded ->
                        keyguardShowing || userDraggingShade || anyExpanded
                    }
                } else {
                    flowOf(false)
                }
            }
            .distinctUntilChanged()
            .logIfPossible("isPersistentEarlyWakeupRequired")

    val isBlurOpaque =
        blurInteractor.isBlurCurrentlySupported.flatMapLatest { blurSupported ->
            if (blurSupported) {
                blurInteractor.isBlurOpaque.distinctUntilChanged().logIfPossible("isBlurOpaque")
            } else {
                flowOf(false)
            }
        }

    fun onBlurApplied(blurRadius: Int) {
        if (DEBUG_BLUR) {
            Log.d(TAG, "blur applied for radius $blurRadius")
        }
        blurInteractor.onBlurApplied(blurRadius)
    }

    @AssistedFactory
    interface Factory {
        fun create(): WindowRootViewModel
    }

    private companion object {
        const val TAG = "WindowRootViewModel"
        val isLoggable = Log.isLoggable(TAG, Log.VERBOSE) || Build.isDebuggable()
        const val DEBUG_BLUR = false

        fun <T> Flow<T>.logIfPossible(loggingInfo: String): Flow<T> {
            return onEach { if (isLoggable) Log.v(TAG, "$loggingInfo $it") }
        }
    }
}
