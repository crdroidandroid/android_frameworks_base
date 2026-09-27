/*
 * Copyright 2026 crDroid Android Project
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
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@SysUISingleton
class AospChipAbsorptionPolicy @Inject constructor() {

    private val chronometerChipKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isAbsorbed(chip: OngoingActivityChipModel.Active): Boolean =
        isSystemChip(chip) || isChronometerNotificationChip(chip)

    /** True for chips created by NotifChipsViewModel (promoted ongoing notifications). */
    fun isNotificationChip(chip: OngoingActivityChipModel.Active): Boolean =
        chip.notificationKey != null &&
            chip.key == chip.notificationKey &&
            !chip.isScreenShareNotification

    /** Forget keys whose chips no longer exist. Call once per chip-list emission. */
    fun prune(presentKeys: Set<String>) {
        chronometerChipKeys.retainAll(presentKeys)
    }

    private fun isSystemChip(chip: OngoingActivityChipModel.Active): Boolean {
        val key = chip.key
        return key.startsWith("callChip-") ||
            key == "ShareToApp" ||
            key == "ScreenRecord" ||
            key == "CastToOtherDevice"
    }

    private fun isChronometerNotificationChip(chip: OngoingActivityChipModel.Active): Boolean {
        if (!isNotificationChip(chip)) return false
        if (chip.content is OngoingActivityChipModel.Content.Timer) {
            chronometerChipKeys.add(chip.key)
        }
        return chip.key in chronometerChipKeys
    }
}
