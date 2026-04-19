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

package com.android.systemui.axdynamicbar.domain

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsRefiner
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class AxDynamicBarChipsRefiner @Inject constructor(
    private val settings: AxDynamicBarSettings,
) : OngoingActivityChipsRefiner {

    private val _chipsFlow = MutableStateFlow(MultipleOngoingActivityChipsModel())
    val chipsFlow: StateFlow<MultipleOngoingActivityChipsModel> = _chipsFlow.asStateFlow()

    override fun transform(input: MultipleOngoingActivityChipsModel): MultipleOngoingActivityChipsModel {
        _chipsFlow.value = input
        if (!settings.isEnabled.value) return input

        return input.copy(
            active = input.active.map { chip -> chip.copy(isHidden = true) },
        )
    }
}
