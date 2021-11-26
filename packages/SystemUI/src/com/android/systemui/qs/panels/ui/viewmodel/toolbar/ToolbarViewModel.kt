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

package com.android.systemui.qs.panels.ui.viewmodel.toolbar

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.Flags.hsuQsChanges
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.classifier.domain.interactor.runIfNotFalseTap
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel.PowerActionViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel.SettingsActionViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsSecurityButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.securityButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.userSwitcherViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ToolbarViewModel
@AssistedInject
constructor(
    val editModeButtonViewModelFactory: EditModeButtonViewModel.Factory,
    val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val footerActionsInteractor: FooterActionsInteractor,
    private val globalActionsDialogLiteProvider: Provider<GlobalActionsDialogLite>,
    private val falsingInteractor: FalsingInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val hsum: HeadlessSystemUserMode,
    @ShadeDisplayAware appContext: Context,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : ExclusiveActivatable() {
    private val qsThemedContext =
        ContextThemeWrapper(appContext, R.style.Theme_SystemUI_QuickSettings)
    private val hydrator = Hydrator("ToolbarViewModel.hydrator")

    val powerButtonViewModel: FooterActionsButtonViewModel =
        PowerActionViewModel(context = qsThemedContext, onClick = ::onPowerButtonClicked)

    val userSwitcherViewModel: FooterActionsButtonViewModel? by
        hydrator.hydratedStateOf(
            traceName = "userSwitcherViewModel",
            initialValue = null,
            source =
                userSwitcherViewModel(
                    qsThemedContext,
                    footerActionsInteractor,
                    ::onUserSwitcherClicked,
                ),
        )

    val settingsButtonViewModel: FooterActionsButtonViewModel? by
        hydrator.hydratedStateOf(
            traceName = "settingsButtonViewModel",
            initialValue = null,
            source =
                selectedUserInteractor.selectedUser.map { selectedUserId ->
                    SettingsActionViewModel(qsThemedContext, ::onSettingsButtonClicked, ::onSettingsButtonLongClicked).takeUnless {
                        hsuQsChanges() && hsum.isHeadlessSystemUser(selectedUserId)
                    }
                },
        )

    var securityInfoViewModel: FooterActionsSecurityButtonViewModel? by mutableStateOf(null)
        private set

    /**
     * Whether the security info text should be shown. When this is `true`, only the icon should be
     * shown.
     *
     * If there's no security info to show, this will also be `true`.
     */
    var securityInfoShowCollapsed: Boolean by mutableStateOf(true)
        private set

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch(context = mainDispatcher) {
                try {
                    globalActionsDialogLite = globalActionsDialogLiteProvider.get()
                    awaitCancellation()
                } finally {
                    globalActionsDialogLite?.destroy()
                }
            }
            launch { hydrator.activate() }
            launch {
                footerActionsInteractor.securityButtonConfig
                    .map { it?.let { securityButtonViewModel(it, ::onSecurityButtonClicked) } }
                    .distinctUntilChanged()
                    .collectLatest {
                        securityInfoShowCollapsed = it == null
                        securityInfoViewModel = it
                        delay(COLLAPSED_SECURITY_INFO_DELAY)
                        securityInfoShowCollapsed = true
                    }
            }
            awaitCancellation()
        }
    }

    private var globalActionsDialogLite: GlobalActionsDialogLite? by mutableStateOf(null)

    private fun onPowerButtonClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap {
            globalActionsDialogLite?.let {
                footerActionsInteractor.showPowerMenuDialog(it, expandable)
            }
        }
    }

    private fun onUserSwitcherClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap { footerActionsInteractor.showUserSwitcher(expandable) }
    }

    private fun onSettingsButtonClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap { footerActionsInteractor.showSettings(expandable) }
    }

    private fun onSettingsButtonLongClicked(expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap { footerActionsInteractor.showCustomSettings(expandable) }
    }

    fun onSecurityButtonClicked(quickSettingsContext: Context, expandable: Expandable) {
        falsingInteractor.runIfNotFalseTap {
            footerActionsInteractor.showDeviceMonitoringDialog(quickSettingsContext, expandable)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): ToolbarViewModel
    }

    private companion object {
        val COLLAPSED_SECURITY_INFO_DELAY = 5.seconds
    }
}
