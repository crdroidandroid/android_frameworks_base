/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.KairosBuilder
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.State as KairosState
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.flatMap
import com.android.systemui.kairos.map
import com.android.systemui.kairos.stateOf
import com.android.systemui.kairosBuilder
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider
import com.android.systemui.statusbar.pipeline.mobile.ui.model.DualSim
import com.android.systemui.statusbar.pipeline.mobile.ui.model.MobileContentDescription
import com.android.systemui.statusbar.pipeline.mobile.ui.model.tryParseDualSim
import com.android.systemui.util.composable.kairos.hydratedComposeStateOf
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

@OptIn(ExperimentalKairosApi::class)
class StackedMobileIconViewModelKairos
@AssistedInject
constructor(
    mobileIcons: MobileIconsViewModelKairos,
    @ShadeDisplayAware private val context: Context,
    private val mobileContextProvider: MobileContextProvider,
) : KairosBuilder by kairosBuilder(), StackedMobileIconViewModel {

    private val isStackable: Boolean by
        hydratedComposeStateOf(
            "StackedMobileIconViewModelKairos.isStackable",
            mobileIcons.isStackable,
            initialValue = false,
        )

    private val iconList: KairosState<List<MobileIconViewModelKairos>> =
        combine(mobileIcons.icons, mobileIcons.activeSubscriptionId) { iconsBySubId, activeSubId ->
            buildList {
                activeSubId?.let { iconsBySubId[activeSubId]?.let { add(it) } }
                addAll(iconsBySubId.values.asSequence().filter { it.subscriptionId != activeSubId })
            }
        }

    override val dualSim: DualSim? by
        hydratedComposeStateOf(
            "StackedMobileIconViewModelKairos.dualSim",
            iconList.flatMap { icons ->
                icons
                    .map { vm -> vm.icon.map { vm.subscriptionId to it } } // Map subId to icon
                    .combine { signalIcons -> tryParseDualSim(signalIcons) }
            },
            initialValue = null,
        )

    override val contentDescription: String? by
        hydratedComposeStateOf(
            "StackedMobileIconViewModelKairos.contentDescription",
            iconList.flatMap { icons ->
                icons
                    .map { it.contentDescription }
                    .combine { contentDescriptions ->
                        tryParseContentDescriptions(contentDescriptions)
                    }
            },
            initialValue = null,
        )

    override val networkTypeIcon: Icon.Resource? by
        hydratedComposeStateOf(
            "StackedMobileIconViewModelKairos.networkTypeIcon",
            iconList.flatMap { icons -> icons.firstOrNull()?.networkTypeIcon ?: stateOf(null) },
            initialValue = null,
        )

    override val activityInVisible: Boolean by
        hydratedComposeStateOf(
            name = "activityInVisible",
            source =
                iconList.flatMap { icons ->
                    icons.firstOrNull()?.activityInVisible ?: stateOf(false)
                },
            initialValue = false,
        )

    override val activityOutVisible: Boolean by
        hydratedComposeStateOf(
            name = "activityOutVisible",
            source =
                iconList.flatMap { icons ->
                    icons.firstOrNull()?.activityOutVisible ?: stateOf(false)
                },
            initialValue = false,
        )

    override val activityContainerVisible: Boolean by
        hydratedComposeStateOf(
            name = "activityContainerVisible",
            source =
                iconList.flatMap { icons ->
                    icons.firstOrNull()?.activityContainerVisible ?: stateOf(false)
                },
            initialValue = false,
        )

    override val mobileContext: Context? by
        hydratedComposeStateOf(
            "StackedMobileIconViewModelKairos.mobileContext",
            iconList.map { icons ->
                icons.firstOrNull()?.let {
                    mobileContextProvider.getMobileContextForSub(it.subscriptionId, context)
                }
            },
            initialValue = null,
        )

    private val roaming: Boolean by
        hydratedComposeStateOf(
            name = "roaming",
            source = iconList.flatMap { icons -> icons.firstOrNull()?.roaming ?: stateOf(false) },
            initialValue = false,
        )

    override val isRoamingVisible: Boolean by
        hydratedComposeStateOf(
            name = "isRoamingVisible",
            source = iconList.flatMap { icons -> 
                icons.firstOrNull()?.isRoamingVisible ?: stateOf(false) 
            },
            initialValue = false,
        )

    override val isIconVisible: Boolean
        get() = isStackable && dualSim != null

    private fun tryParseContentDescriptions(
        contentDescriptions: List<MobileContentDescription?>
    ): String? {
        if (contentDescriptions.size != 2 || null in contentDescriptions) return null

        return contentDescriptions.joinToString(" ") { it?.loadContentDescription(context) ?: "" }
    }

    @AssistedFactory
    interface Factory {
        fun create(): StackedMobileIconViewModelKairos
    }
}
