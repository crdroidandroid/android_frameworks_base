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
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider
import com.android.systemui.statusbar.pipeline.dagger.StackedMobileIconTableLog
import com.android.systemui.statusbar.pipeline.mobile.ui.model.DualSim
import com.android.systemui.statusbar.pipeline.mobile.ui.model.logDualSimDiff
import com.android.systemui.statusbar.pipeline.mobile.ui.model.tryParseDualSim
import com.android.systemui.util.kotlin.pairwiseBy
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface StackedMobileIconViewModel {
    val dualSim: DualSim?
    val contentDescription: String?
    val networkTypeIcon: Icon.Resource?
    val activityInVisible: Boolean
    val activityOutVisible: Boolean
    val activityContainerVisible: Boolean
    /** [Context] to use when loading the [networkTypeIcon] */
    val mobileContext: Context?
    val isRoamingVisible: Boolean
    val isIconVisible: Boolean
}

@OptIn(ExperimentalCoroutinesApi::class)
class StackedMobileIconViewModelImpl
@AssistedInject
constructor(
    mobileIconsViewModel: MobileIconsViewModel,
    @StackedMobileIconTableLog private val tableLogger: TableLogBuffer,
    @ShadeDisplayAware private val context: Context,
    private val mobileContextProvider: MobileContextProvider,
) : ExclusiveActivatable(), StackedMobileIconViewModel {
    private val hydrator = Hydrator("StackedMobileIconViewModel")

    private val iconViewModelFlow: Flow<List<MobileIconViewModelCommon>> =
        combine(
            mobileIconsViewModel.mobileSubViewModels,
            mobileIconsViewModel.activeMobileDataSubscriptionId,
        ) { viewModels, activeSubId ->
            // Sort to get the active subscription first, if it's set
            viewModels.sortedByDescending { it.subscriptionId == activeSubId }
        }

    private val _dualSim: Flow<DualSim?> =
        iconViewModelFlow.flatMapLatest { viewModels ->
            // Map subIds to icons
            combine(viewModels.map { vm -> vm.icon.map { vm.subscriptionId to it } }) { icons ->
                tryParseDualSim(icons.toList())
            }
        }

    private val _isIconVisible: Flow<Boolean> =
        combine(_dualSim, mobileIconsViewModel.isStackable) { dualSim, isStackable ->
            dualSim != null && isStackable
        }

    override val dualSim: DualSim? by
        hydrator.hydratedStateOf(
            traceName = "dualSim",
            source =
                _dualSim.pairwiseBy(initialValue = null) { old: DualSim?, new: DualSim? ->
                    // _dualSim is nullable, meaning logDiffsForTable can't be used here. Instead,
                    // we do the comparison manually and return the new value
                    logDualSimDiff(old, new, tableLogger)
                    new
                },
            initialValue = null,
        )

    /** Content description of both icons, starting with the active connection. */
    override val contentDescription: String? by
        hydrator.hydratedStateOf(
            traceName = "contentDescription",
            source =
                flowIfIconIsVisible(
                    iconViewModelFlow.flatMapLatest { viewModels ->
                        combine(viewModels.map { it.contentDescription }) { contentDescriptions ->
                                contentDescriptions.map { it?.loadContentDescription(context) }
                            }
                            .map { loadedStrings ->
                                // Only provide the content description if both icons have one
                                if (loadedStrings.any { it == null }) {
                                    null
                                } else {
                                    // The content description of each icon has the format:
                                    // "[Carrier name], N bars."
                                    // To combine, we simply join them with a space
                                    loadedStrings.joinToString(" ")
                                }
                            }
                    }
                ),
            initialValue = null,
        )

    override val networkTypeIcon: Icon.Resource? by
        hydrator.hydratedStateOf(
            traceName = "networkTypeIcon",
            source =
                flowIfIconIsVisible(
                    iconViewModelFlow.flatMapLatest { viewModels ->
                        viewModels.firstOrNull()?.networkTypeIcon ?: flowOf(null)
                    }
                ),
            initialValue = null,
        )

    override val activityInVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "activityInVisible",
            source =
                flowIfIconIsVisible(
                        iconViewModelFlow.flatMapLatest { viewModels ->
                            viewModels.firstOrNull()?.activityInVisible ?: flowOf(false)
                        }
                    )
                    .map { it == true },
            initialValue = false,
        )

    override val activityOutVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "activityOutVisible",
            source =
                flowIfIconIsVisible(
                        iconViewModelFlow.flatMapLatest { viewModels ->
                            viewModels.firstOrNull()?.activityOutVisible ?: flowOf(false)
                        }
                    )
                    .map { it == true },
            initialValue = false,
        )

    override val activityContainerVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "activityContainerVisible",
            source =
                flowIfIconIsVisible(
                        iconViewModelFlow.flatMapLatest { viewModels ->
                            viewModels.firstOrNull()?.activityContainerVisible ?: flowOf(false)
                        }
                    )
                    .map { it == true },
            initialValue = false,
        )

    override val mobileContext: Context? by
        hydrator.hydratedStateOf(
            traceName = "mobileContext",
            source =
                flowIfIconIsVisible(
                    iconViewModelFlow.map { viewModels ->
                        // Get mobile context of primary connection
                        viewModels.firstOrNull()?.let {
                            mobileContextProvider.getMobileContextForSub(it.subscriptionId, context)
                        }
                    }
                ),
            initialValue = null,
        )

    private val roaming: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isRoaming",
            source =
                _isIconVisible.flatMapLatest { isVisible ->
                    if (isVisible) {
                            iconViewModelFlow.flatMapLatest { viewModels ->
                                viewModels.firstOrNull()?.roaming ?: flowOf(false)
                            }
                        } else {
                            flowOf(false)
                        }
                        .logDiffsForTable(
                            tableLogBuffer = tableLogger,
                            columnName = COL_ROAMING,
                            initialValue = false,
                        )
                },
            initialValue = false,
        )

    override val isRoamingVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isRoamingVisible",
            source =
                iconViewModelFlow.flatMapLatest { viewModels ->
                    viewModels.firstOrNull()?.isRoamingVisible ?: flowOf(false)
                }
                .logDiffsForTable(
                    tableLogBuffer = tableLogger,
                    columnName = COL_ROAMING_VISIBLE,
                    initialValue = false,
                ),
            initialValue = false,
        )

    override val isIconVisible: Boolean by
        hydrator.hydratedStateOf(
            traceName = "isIconVisible",
            source =
                _isIconVisible.logDiffsForTable(
                    tableLogger,
                    columnName = COL_IS_ICON_VISIBLE,
                    initialValue = false,
                ),
            initialValue = false,
        )

    private fun <T> flowIfIconIsVisible(flow: Flow<T>): Flow<T?> {
        return _isIconVisible.flatMapLatest { isVisible ->
            if (isVisible) {
                flow
            } else {
                flowOf(null)
            }
        }
    }

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(): StackedMobileIconViewModelImpl
    }

    private companion object {
        const val COL_IS_ICON_VISIBLE = "isIconVisible"
        const val COL_ROAMING = "roam"
        const val COL_ROAMING_VISIBLE = "roamVisible"
    }
}
