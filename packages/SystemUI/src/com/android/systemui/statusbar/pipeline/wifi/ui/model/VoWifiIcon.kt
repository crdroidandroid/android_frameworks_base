/*
 * Copyright (C) 2024 The LibreMobileOS Foundation
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

package com.android.systemui.statusbar.pipeline.wifi.ui.model

import com.android.systemui.R
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.pipeline.wifi.shared.model.VoWifiState

sealed interface VoWifiIcon {
    data class Visible(val icon: Icon) : VoWifiIcon
    object Hidden : VoWifiIcon
}

val VoWifiState.icon: VoWifiIcon
    get() = when (this) {
        is VoWifiState.Enabled -> {
            val ic = if (activeSubCount == 2) {
                if (slots.size >= 2) {
                    R.drawable.ic_vowifi_dual
                } else {
                    val id = slots.firstOrNull() ?: -1
                    when (id) {
                        0 -> R.drawable.ic_vowifi_one // Sim 1
                        1 -> R.drawable.ic_vowifi_two // Sim 2
                        else -> 0
                    }
                }
            } else {
                R.drawable.ic_vowifi
            }
            if (ic == 0) {
                VoWifiIcon.Hidden
            } else {
                VoWifiIcon.Visible(
                    Icon.Resource(
                        ic, null /* Content description */
                    )
                )
            }
        }

        else -> VoWifiIcon.Hidden
    }
