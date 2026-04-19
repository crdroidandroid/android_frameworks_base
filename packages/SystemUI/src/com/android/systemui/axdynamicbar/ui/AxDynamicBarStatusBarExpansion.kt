/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.ui

import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class AxDynamicBarStatusBarExpansion
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val interactor: AxDynamicBarInteractor,
) {
    private val _intent = MutableStateFlow(false)

    val isExpanded: StateFlow<Boolean> =
        combine(_intent, interactor.isOnKeyguard) { intent, onKg -> intent && !onKg }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    init {
        interactor.isOnKeyguard
            .onEach { if (it) collapse() }
            .launchIn(applicationScope)
    }

    fun expand() {
        if (interactor.uiState.value.topEvent == null) return
        _intent.value = true
    }

    fun collapse() {
        _intent.value = false
    }

    fun toggle() {
        if (_intent.value) collapse() else expand()
    }
}
