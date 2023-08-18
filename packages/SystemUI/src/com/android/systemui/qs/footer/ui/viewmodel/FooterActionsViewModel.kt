/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.footer.ui.viewmodel

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags.hsuQsChanges
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.dagger.QSFlagsModule.PM_LITE_ENABLED
import com.android.systemui.qs.footer.data.model.UserSwitcherStatusModel
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel.PowerActionViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel.SettingsActionViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel.UserSwitcherViewModel
import com.android.systemui.qs.panels.domain.interactor.TextFeedbackInteractor
import com.android.systemui.qs.panels.domain.model.TextFeedbackModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel.Companion.load
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.icuMessageFormat
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlin.math.max
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

private const val TAG = "FooterActionsViewModel"

/** A ViewModel for the footer actions. */
class FooterActionsViewModel(
    /** The model for the security button. */
    val security: Flow<FooterActionsSecurityButtonViewModel?>,

    /** The model for the foreground services button. */
    val foregroundServices: Flow<FooterActionsForegroundServicesButtonViewModel?>,

    /** The model for the user switcher button. */
    val userSwitcher: Flow<FooterActionsButtonViewModel?>,

    /** The model for the settings button. */
    val settings: Flow<FooterActionsButtonViewModel?>,

    /** The model for the power button. */
    val power: FooterActionsButtonViewModel?,

    /** The model for the text feedback. */
    val textFeedback: Flow<TextFeedbackViewModel>,

    /**
     * Observe the device monitoring dialog requests and show the dialog accordingly. This function
     * will suspend indefinitely and will need to be cancelled to stop observing.
     *
     * Important: [quickSettingsContext] must be the [Context] associated to the
     * [Quick Settings fragment][com.android.systemui.qs.QSFragmentLegacy], and the call to this
     * function must be cancelled when that fragment is destroyed.
     */
    val observeDeviceMonitoringDialogRequests: suspend (quickSettingsContext: Context) -> Unit,
) {
    /** The alpha the UI rendering this ViewModel should have. */
    private val _alpha = MutableStateFlow(1f)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()

    /** The alpha the background of the UI rendering this ViewModel should have. */
    private val _backgroundAlpha = MutableStateFlow(1f)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** Called when the expansion of the Quick Settings changed. */
    fun onQuickSettingsExpansionChanged(expansion: Float, isInSplitShade: Boolean) {
        if (isInSplitShade) {
            // In split shade, we want to fade in the background when the QS background starts to
            // show.
            val delay = 0.15f
            _alpha.value = expansion
            _backgroundAlpha.value = max(0f, expansion - delay) / (1f - delay)
        } else {
            // Only start fading in the footer actions when we are at least 90% expanded.
            val delay = 0.9f
            _alpha.value = max(0f, expansion - delay) / (1 - delay)
            _backgroundAlpha.value = 1f
        }
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        @ShadeDisplayAware private val context: Context,
        private val falsingManager: FalsingManager,
        private val footerActionsInteractor: FooterActionsInteractor,
        private val globalActionsDialogLiteProvider: Provider<GlobalActionsDialogLite>,
        private val activityStarter: ActivityStarter,
        private val textFeedbackInteractor: TextFeedbackInteractor,
        private val selectedUserInteractor: SelectedUserInteractor,
        private val hsum: HeadlessSystemUserMode,
        @Named(PM_LITE_ENABLED) private val showPowerButton: Boolean,
        private val keyguardStateController: KeyguardStateController
    ) {
        /** Create a [FooterActionsViewModel] bound to the lifecycle of [lifecycleOwner]. */
        fun create(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
            val globalActionsDialogLite = globalActionsDialogLiteProvider.get()
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                // This should usually not happen, but let's make sure we already destroy
                // globalActionsDialogLite.
                globalActionsDialogLite.destroy()
            } else {
                // Destroy globalActionsDialogLite when the lifecycle is destroyed.
                lifecycleOwner.lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) {
                            globalActionsDialogLite.destroy()
                        }
                    }
                )
            }

            return createFooterActionsViewModel(
                context,
                footerActionsInteractor,
                textFeedbackInteractor,
                falsingManager,
                globalActionsDialogLite,
                activityStarter,
                showPowerButton,
                selectedUserInteractor,
                hsum,
                keyguardStateController,
            )
        }

        fun create(lifecycleCoroutineScope: LifecycleCoroutineScope): FooterActionsViewModel {
            val globalActionsDialogLite = globalActionsDialogLiteProvider.get()
            if (lifecycleCoroutineScope.isActive) {
                lifecycleCoroutineScope.launch(start = CoroutineStart.ATOMIC) {
                    try {
                        awaitCancellation()
                    } finally {
                        globalActionsDialogLite.destroy()
                    }
                }
            } else {
                globalActionsDialogLite.destroy()
            }

            return createFooterActionsViewModel(
                context,
                footerActionsInteractor,
                textFeedbackInteractor,
                falsingManager,
                globalActionsDialogLite,
                activityStarter,
                showPowerButton,
                selectedUserInteractor,
                hsum,
                keyguardStateController,
            )
        }
    }
}

fun createFooterActionsViewModel(
    @ShadeDisplayAware appContext: Context,
    footerActionsInteractor: FooterActionsInteractor,
    textFeedbackInteractor: TextFeedbackInteractor,
    falsingManager: FalsingManager,
    globalActionsDialogLite: GlobalActionsDialogLite,
    activityStarter: ActivityStarter,
    showPowerButton: Boolean,
    selectedUserInteractor: SelectedUserInteractor,
    hsum: HeadlessSystemUserMode,
    keyguardStateController: KeyguardStateController
): FooterActionsViewModel {
    suspend fun observeDeviceMonitoringDialogRequests(quickSettingsContext: Context) {
        footerActionsInteractor.deviceMonitoringDialogRequests.collect {
            footerActionsInteractor.showDeviceMonitoringDialog(
                quickSettingsContext,
                expandable = null,
            )
        }
    }

    fun onSecurityButtonClicked(quickSettingsContext: Context, expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showDeviceMonitoringDialog(quickSettingsContext, expandable)
    }

    fun onForegroundServiceButtonClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        activityStarter.dismissKeyguardThenExecute(
            {
                footerActionsInteractor.showForegroundServicesDialog(expandable)
                false /* if the dismiss should be deferred */
            },
            null /* cancelAction */,
            true, /* afterKeyguardGone */
        )
    }

    fun onUserSwitcherClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showUserSwitcher(expandable)
    }

    fun onSettingsButtonClicked(expandable: Expandable) {
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showSettings(expandable)
    }

    fun onPowerButtonClicked(expandable: Expandable) {
        if (keyguardStateController.isShowing() && keyguardStateController.isMethodSecure() 
                && Settings.System.getIntForUser(appContext.getContentResolver(),
                Settings.System.LOCKSCREEN_ENABLE_POWER_MENU, 1, UserHandle.USER_CURRENT) == 0) {
            return
        }
        if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return
        }

        footerActionsInteractor.showPowerMenuDialog(globalActionsDialogLite, expandable)
    }

    val qsThemedContext = ContextThemeWrapper(appContext, R.style.Theme_SystemUI_QuickSettings)

    val security =
        footerActionsInteractor.securityButtonConfig
            .map { config ->
                config?.let { securityButtonViewModel(it, ::onSecurityButtonClicked) }
            }
            .distinctUntilChanged()

    val foregroundServices =
        combine(
                footerActionsInteractor.foregroundServicesCount,
                footerActionsInteractor.hasNewForegroundServices,
                security,
                textFeedbackInteractor.textFeedback,
            ) { foregroundServicesCount, hasNewChanges, securityModel, textFeedbackModel ->
                if (foregroundServicesCount <= 0) {
                    return@combine null
                }

                foregroundServicesButtonViewModel(
                    qsThemedContext,
                    foregroundServicesCount,
                    securityModel,
                    textFeedbackModel,
                    hasNewChanges,
                    ::onForegroundServiceButtonClicked,
                )
            }
            .distinctUntilChanged()

    val userSwitcher =
        userSwitcherViewModel(qsThemedContext, footerActionsInteractor, ::onUserSwitcherClicked)

    val settings =
        selectedUserInteractor.selectedUser
            .map { selectedUserId ->
                SettingsActionViewModel(qsThemedContext, ::onSettingsButtonClicked).takeUnless {
                    hsuQsChanges() && hsum.isHeadlessSystemUser(selectedUserId)
                }
            }
            .distinctUntilChanged()

    val power =
        if (showPowerButton) {
            PowerActionViewModel(qsThemedContext, ::onPowerButtonClicked)
        } else {
            null
        }

    val textFeedback = textFeedbackInteractor.textFeedback.map { it.load(qsThemedContext) }

    return FooterActionsViewModel(
        security = security,
        foregroundServices = foregroundServices,
        userSwitcher = userSwitcher,
        settings = settings,
        power = power,
        observeDeviceMonitoringDialogRequests = ::observeDeviceMonitoringDialogRequests,
        textFeedback = textFeedback,
    )
}

fun userSwitcherViewModel(
    themedContext: Context,
    footerActionsInteractor: FooterActionsInteractor,
    onUserSwitcherClicked: (Expandable) -> Unit,
): Flow<FooterActionsButtonViewModel?> {
    return footerActionsInteractor.userSwitcherStatus
        .map { userSwitcherStatus ->
            when (userSwitcherStatus) {
                UserSwitcherStatusModel.Disabled -> null
                is UserSwitcherStatusModel.Enabled -> {
                    if (userSwitcherStatus.currentUserImage == null) {
                        Log.e(
                            TAG,
                            "Skipped the addition of user switcher button because " +
                                "currentUserImage is missing",
                        )
                        return@map null
                    }

                    userSwitcherButtonViewModel(
                        themedContext,
                        userSwitcherStatus,
                        onUserSwitcherClicked,
                    )
                }
            }
        }
        .distinctUntilChanged()
}

fun securityButtonViewModel(
    config: SecurityButtonConfig,
    onSecurityButtonClicked: (Context, Expandable) -> Unit,
): FooterActionsSecurityButtonViewModel {
    val (icon, text, isClickable) = config
    return FooterActionsSecurityButtonViewModel(
        icon,
        text,
        if (isClickable) onSecurityButtonClicked else null,
    )
}

fun foregroundServicesButtonViewModel(
    qsThemedContext: Context,
    foregroundServicesCount: Int,
    securityModel: FooterActionsSecurityButtonViewModel?,
    textFeedbackModel: TextFeedbackModel,
    hasNewChanges: Boolean,
    onForegroundServiceButtonClicked: (Expandable) -> Unit,
): FooterActionsForegroundServicesButtonViewModel {
    val text =
        icuMessageFormat(
            qsThemedContext.resources,
            R.string.fgs_manager_footer_label,
            foregroundServicesCount,
        )

    return FooterActionsForegroundServicesButtonViewModel(
        foregroundServicesCount,
        text = text,
        displayText = securityModel == null && textFeedbackModel == TextFeedbackModel.NoFeedback,
        hasNewChanges = hasNewChanges,
        onClick = { _, expandable -> onForegroundServiceButtonClicked(expandable) },
    )
}

fun userSwitcherButtonViewModel(
    qsThemedContext: Context,
    status: UserSwitcherStatusModel.Enabled,
    onUserSwitcherClicked: (Expandable) -> Unit,
): FooterActionsButtonViewModel {
    val icon = status.currentUserImage!!
    val contentDescription =
        status.currentUserName?.let { user ->
            qsThemedContext.getString(R.string.accessibility_quick_settings_user, user)
        }
    return UserSwitcherViewModel(
        icon = Icon.Loaded(icon, ContentDescription.Loaded(contentDescription)),
        onClick = onUserSwitcherClicked,
    )
}
