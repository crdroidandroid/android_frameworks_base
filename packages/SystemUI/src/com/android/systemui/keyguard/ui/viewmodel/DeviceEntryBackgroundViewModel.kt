/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import com.android.settingslib.Utils
import com.android.systemui.Flags.enableLockscreenBlur
import com.android.systemui.common.shared.colors.SurfaceEffectColors
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Models the UI state for the device entry icon background view. */
@Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
@SysUISingleton
class DeviceEntryBackgroundViewModel
@Inject
constructor(
    @ShadeDisplayAware val context: Context,
    val deviceEntryIconViewModel: DeviceEntryIconViewModel,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    alternateBouncerToAodTransitionViewModel: AlternateBouncerToAodTransitionViewModel,
    alternateBouncerToDozingTransitionViewModel: AlternateBouncerToDozingTransitionViewModel,
    aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    dreamingToAodTransitionViewModel: DreamingToAodTransitionViewModel,
    dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel,
    lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel,
    lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    occludedToAodTransitionViewModel: OccludedToAodTransitionViewModel,
    occludedToDozingTransitionViewModel: OccludedToDozingTransitionViewModel,
    occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    offToLockscreenTransitionViewModel: OffToLockscreenTransitionViewModel,
    primaryBouncerToAodTransitionViewModel: PrimaryBouncerToAodTransitionViewModel,
    primaryBouncerToDozingTransitionViewModel: PrimaryBouncerToDozingTransitionViewModel,
    primaryBouncerToLockscreenTransitionViewModel: PrimaryBouncerToLockscreenTransitionViewModel,
    lockscreenToDozingTransitionViewModel: LockscreenToDozingTransitionViewModel,
    lockscreenToGlanceableHubTransitionViewModel: LockscreenToGlanceableHubTransitionViewModel,
    glanceableHubToAodTransitionViewModel: GlanceableHubToAodTransitionViewModel,
    glanceableHubToLockscreenTransitionViewModel: GlanceableHubToLockscreenTransitionViewModel,
    toLockscreenEndStateTransitionViewModel: ToLockscreenEndStateTransitionViewModel,
    toAodEndStateTransitionViewModel: ToAodEndStateTransitionViewModel,
    toDozingEndStateTransitionViewModel: ToDozingEndStateTransitionViewModel,
    windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
    private val sceneInteractor: Lazy<SceneInteractor>,
) {
    val color: Flow<Int> =
        deviceEntryIconViewModel.useBackgroundProtection.flatMapLatest { useBackground ->
            if (useBackground) {
                if (!enableLockscreenBlur()) {
                    configurationInteractor.onAnyConfigurationChange
                        .map {
                            Utils.getColorAttrDefaultColor(
                                context,
                                com.android.internal.R.attr.colorSurface,
                            )
                        }
                        .onStart {
                            emit(
                                Utils.getColorAttrDefaultColor(
                                    context,
                                    com.android.internal.R.attr.colorSurface,
                                )
                            )
                        }
                } else {
                    configurationInteractor.onAnyConfigurationChange.combine(
                        windowRootViewBlurInteractor.isBlurCurrentlySupported
                    ) { config, isSupported ->
                        if (isSupported) {
                            SurfaceEffectColors.surfaceEffect1(context)
                        } else {
                            Utils.getColorAttrDefaultColor(
                                context,
                                com.android.internal.R.attr.colorSurface,
                            )
                        }
                    }
                }
            } else {
                flowOf(0)
            }
        }
    val alpha: Flow<Float> =
        deviceEntryIconViewModel.useBackgroundProtection.flatMapLatest { useBackground ->
            if (useBackground) {
                setOf(
                        alternateBouncerToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        alternateBouncerToDozingTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        aodToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        dozingToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        dreamingToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        dreamingToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        goneToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        goneToDozingTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        goneToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        lockscreenToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        lockscreenToGoneTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        occludedToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        occludedToDozingTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        occludedToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        offToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        primaryBouncerToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        primaryBouncerToDozingTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        primaryBouncerToLockscreenTransitionViewModel
                            .deviceEntryBackgroundViewAlpha,
                        lockscreenToDozingTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        lockscreenToGlanceableHubTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        glanceableHubToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        glanceableHubToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        toLockscreenEndStateTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        toAodEndStateTransitionViewModel.deviceEntryBackgroundViewAlpha,
                        toDozingEndStateTransitionViewModel.deviceEntryBackgroundViewAlpha,
                    )
                    .merge()
                    .onStart {
                        emit(
                            calculateDeviceEntryIconBackgroundAlpha(
                                keyguardTransitionInteractor.currentKeyguardState.replayCache.last()
                            )
                        )
                    }
            } else {
                flowOf(0f)
            }
        }

    private fun calculateDeviceEntryIconBackgroundAlpha(state: KeyguardState): Float {
        return when (state) {
            KeyguardState.GLANCEABLE_HUB,
            KeyguardState.GONE,
            KeyguardState.OCCLUDED,
            KeyguardState.OFF,
            KeyguardState.DOZING,
            KeyguardState.DREAMING,
            KeyguardState.PRIMARY_BOUNCER,
            KeyguardState.AOD -> 0f
            KeyguardState.ALTERNATE_BOUNCER,
            KeyguardState.LOCKSCREEN -> 1f
            KeyguardState.UNDEFINED ->
                if (SceneContainerFlag.isEnabled) {
                    when (sceneInteractor.get().currentScene.value) {
                        Scenes.Shade,
                        Scenes.QuickSettings -> 1f
                        else -> 0f
                    }
                } else {
                    1f
                }
        }
    }
}
