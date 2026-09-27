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

import com.android.systemui.axdynamicbar.domain.AospChipAbsorptionPolicy
import com.android.systemui.axdynamicbar.domain.AxDynamicBarChipsRefiner
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the island absorbed from AOSP chips, plus what other sources must now stay quiet about. */
data class AospChipSnapshot(
    /** Chips to show in the island. */
    val events: List<IslandEvent.AospChip> = emptyList(),
    /** Notification keys owned by absorbed notification chips (hidden ones included). */
    val notificationKeys: Set<String> = emptySet(),
    /** Packages owning absorbed notification chips (hidden ones included). */
    val packages: Set<String> = emptySet(),
    /** True if any promoted chronometer notification (e.g. Clock timer/stopwatch) is absorbed. */
    val hasChronometerChip: Boolean = false,
)

@SysUISingleton
class AospChipIslandManager @Inject constructor(
    private val refiner: AxDynamicBarChipsRefiner,
    private val policy: AospChipAbsorptionPolicy,
) {
    val snapshot: Flow<AospChipSnapshot> =
        refiner.chipsFlow
            .map { model ->
                val all = model.active
                policy.prune(all.mapTo(HashSet()) { it.key })

                val absorbed = all.filter { policy.isAbsorbed(it) }
                val notifChips = absorbed.filter { policy.isNotificationChip(it) }

                AospChipSnapshot(
                    events =
                        absorbed
                            .filterNot { policy.isNotificationChip(it) && it.isHidden }
                            .map { IslandEvent.AospChip(active = it) },
                    notificationKeys = notifChips.mapNotNullTo(HashSet()) { it.notificationKey },
                    packages = notifChips.mapNotNullTo(HashSet()) { it.managingPackageName },
                    hasChronometerChip = notifChips.isNotEmpty(),
                )
            }
            .distinctUntilChanged()

    /** Kept for existing callers. */
    val aospChipEvents: Flow<List<IslandEvent.AospChip>> =
        snapshot.map { it.events }.distinctUntilChanged()
}
