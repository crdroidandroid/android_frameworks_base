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

package com.android.systemui.axdynamicbar.data.source

import com.android.systemui.axdynamicbar.domain.AxDynamicBarChipsRefiner
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@SysUISingleton
class AospChipIslandManager @Inject constructor(
    private val refiner: AxDynamicBarChipsRefiner,
) {
    val aospChipEvents: Flow<List<IslandEvent.AospChip>> =
        refiner.chipsFlow.map { model ->
            model.active
                .filter { isAbsorbed(it) }
                .map { IslandEvent.AospChip(active = it) }
        }

    private fun isAbsorbed(chip: OngoingActivityChipModel.Active): Boolean {
        val key = chip.key
        return key.startsWith("callChip-") ||
            key == "ShareToApp" ||
            key == "ScreenRecord" ||
            key == "CastToOtherDevice"
    }
}
