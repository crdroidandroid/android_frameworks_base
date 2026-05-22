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

package com.android.systemui.statusbar.pipeline.shared.domain.interactor

import android.provider.Settings
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayId
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardOcclusionInteractor
import com.android.systemui.log.table.EnumDiffable
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import com.android.systemui.statusbar.disableflags.domain.interactor.DisableFlagsInteractor
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.model.StatusBarDisableFlagsVisibilityModel
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStatePerDisplayRepository
import com.android.systemui.statusbar.window.shared.model.StatusBarWindowState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for the home screen status bar (aka
 * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment]).
 */
@PerDisplaySingleton
class HomeStatusBarInteractor
@Inject
constructor(
    @DisplayId thisDisplayId: Int,
    @DisplayAware backgroundScope: CoroutineScope,
    @DisplayAware statusBarWindowStateRepository: StatusBarWindowStatePerDisplayRepository,
    airplaneModeInteractor: AirplaneModeInteractor,
    @DisplayAware disableFlagsInteractor: DisableFlagsInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
    tableLoggerFactory: TableLogBufferFactory,
    settingsRepository: SystemSettingsRepository,
) {
    private val tableLogger =
        tableLoggerFactory.getOrCreate("HomeStatusBarInteractor[$thisDisplayId]", 100)

    /**
     * The visibilities of various status bar child views, based only on the information we received
     * from disable flags.
     */
    val visibilityViaDisableFlags: Flow<StatusBarDisableFlagsVisibilityModel> =
        disableFlagsInteractor.disableFlags.map {
            StatusBarDisableFlagsVisibilityModel(
                isClockAllowed = it.isClockEnabled,
                areNotificationIconsAllowed = it.areNotificationIconsEnabled,
                isSystemInfoAllowed = it.isSystemInfoEnabled,
                animate = it.animate,
            )
        }

    private val showCarrierInStatusBar: Flow<Boolean> =
        settingsRepository
            .intSetting(name = Settings.System.LOCKSCREEN_SHOW_CARRIER, defaultValue = 1)
            .map { it == 2 || it == 3 }

    /**
     * True if the user enabled the operator name for the home status bar and the device is not in
     * airplane mode.
     */
    val shouldShowOperatorName: Flow<Boolean> =
        combine(airplaneModeInteractor.isAirplaneMode, showCarrierInStatusBar) {
            isAirplaneMode,
            showCarrier ->
            showCarrier && !isAirplaneMode
        }

    private val windowState =
        statusBarWindowStateRepository.windowState
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                initialValue = StatusBarWindowState.Hidden,
            )
            .stateIn(
                backgroundScope,
                SharingStarted.WhileSubscribed(),
                initialValue = StatusBarWindowState.Hidden,
            )

    private enum class SecureCameraEventType : EnumDiffable<SecureCameraEventType> {
        SecureCameraActive,
        SecureCameraInactive,
        StatusBarWindowChanged;

        override val columnName = "secureCameraEventType"
        override val valueFetcher = { this.name }
    }

    private val isSecureCameraLaunchingOverKeyguard =
        merge(
                keyguardInteractor.isSecureCameraActive.map {
                    if (it) {
                        SecureCameraEventType.SecureCameraActive
                    } else {
                        SecureCameraEventType.SecureCameraInactive
                    }
                },
                windowState.map { SecureCameraEventType.StatusBarWindowChanged },
            )
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                initialValue = SecureCameraEventType.StatusBarWindowChanged,
            )
            .map {
                when (it) {
                    SecureCameraEventType.SecureCameraActive -> true
                    SecureCameraEventType.SecureCameraInactive -> false
                    // Once we've received an update for the status bar window state, we assume the
                    // secure camera app has finished launching and
                    // `isSecureCameraShowingAndNoStatusBar` will handle visibility.
                    SecureCameraEventType.StatusBarWindowChanged -> false
                }
            }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), initialValue = false)

    /**
     * Emits true if the camera app is showing over keyguard *and* the status bar window isn't
     * showing.
     */
    private val isSecureCameraShowingAndNoStatusBar =
        combine(
            keyguardInteractor.isSecureCameraActive,
            keyguardOcclusionInteractor.isKeyguardOccluded,
            windowState,
        ) { isSecureCameraActive, isKeyguardOccluded, windowState ->
            isSecureCameraActive &&
                isKeyguardOccluded &&
                // While in the camera app, users can still swipe down to see the status bar
                // content. We use windowState == Shown as a proxy for "user has swiped down to
                // see the status bar", so that we still show the status bar icons in secure camera
                // for that case.
                // Note that this *does* cause a jump-cut where the status bar icons hide as soon as
                // the status bar window starts animating *away*. But, that's better than not
                // showing the icons at all.
                windowState == StatusBarWindowState.Hidden
        }

    /**
     * Emits true if the camera app is being launched on top of lockscreen.
     *
     * This flow helps ensure there's no status bar flicker during the camera launch, which is a
     * critical animation.
     *
     * Without a custom flow, the status bar would temporarily show while the camera app is
     * launching and then hide again once the app notifies SysUI that the status bar should hide.
     */
    val shouldHideStatusBarForSecureCamera =
        // - For the first half of the camera animation, `isSecureCameraLaunching` is true ->
        //   status bar icons hide.
        // - For the second half of the camera animation, `isSecureCameraShowingAndNoStatusBar` is
        //   true (camera is showing && windowState=Hidden) -> status bar icons hide.
        // - If the user swipes down to show the status bar while in secure camera,
        //   `isSecureCameraLaunching` is false (camera app has finished launching) and
        //   `isSecureCameraShowingAndNoStatusBar` is `false` (windowState=Shown) -> status bar
        //   icons show.
        // See b/257292822 and b/435653753.
        combine(isSecureCameraLaunchingOverKeyguard, isSecureCameraShowingAndNoStatusBar) { a, b ->
            a || b
        }
}
