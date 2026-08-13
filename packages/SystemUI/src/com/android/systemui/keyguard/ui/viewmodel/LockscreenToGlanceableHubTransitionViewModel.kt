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

package com.android.systemui.keyguard.ui.viewmodel

import android.util.LayoutDirection
import com.android.app.animation.Interpolators.EMPHASIZED
import com.android.systemui.Flags
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.dagger.GlanceableHubBlurComponent
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.StateToValue
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.GlanceableHubTransition
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Breaks down LOCKSCREEN->GLANCEABLE_HUB transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class LockscreenToGlanceableHubTransitionViewModel
@Inject
constructor(
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
    private val blurFactory: GlanceableHubBlurComponent.Factory,
) : GlanceableHubTransition, DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
                edge = Edge.create(from = LOCKSCREEN, to = Scenes.Communal),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = LOCKSCREEN, to = GLANCEABLE_HUB))

    override val windowBlurRadius: Flow<Float> =
        blurFactory.create(transitionAnimation).getBlurProvider().enterBlurRadius

    val keyguardAlpha: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            // Fading keyguard elements during this transition is controlled by SceneContainer.
            emptyFlow()
        } else {
            transitionAnimation.sharedFlow(
                duration =
                    if (Flags.gestureBetweenHubAndLockscreenMotion()) 500.milliseconds
                    else 167.milliseconds,
                onStep = { 1f - it },
                onFinish = { 0f },
                onCancel = { 1f },
                name = "LOCKSCREEN->GLANCEABLE_HUB: keyguardAlpha",
            )
        }

    val deviceEntryBackgroundViewAlpha: Flow<Float> = keyguardAlpha

    // Show UMO as long as keyguard is not visible.
    val showUmo: Flow<Boolean> = keyguardAlpha.map { alpha -> alpha == 0f }

    val keyguardTranslationX: Flow<StateToValue> =
        if (Flags.gestureBetweenHubAndLockscreenMotion()) {
            emptyFlow()
        } else {
            configurationInteractor
                .directionalDimensionPixelSize(
                    LayoutDirection.LTR,
                    R.dimen.lockscreen_to_hub_transition_lockscreen_translation_x,
                )
                .flatMapLatest { translatePx: Int ->
                    transitionAnimation.sharedFlowWithState(
                        duration = FromLockscreenTransitionInteractor.TO_GLANCEABLE_HUB_DURATION,
                        onStep = { value -> value * translatePx },
                        // Move notifications back to their original position since they can be
                        // accessed from the shade, and also keyguard elements in case the animation
                        // is cancelled.
                        onFinish = { 0f },
                        onCancel = { 0f },
                        interpolator = EMPHASIZED,
                        name = "LOCKSCREEN->GLANCEABLE_HUB: keyguardTranslationX",
                    )
                }
        }

    val notificationAlpha: Flow<Float> = keyguardAlpha

    val shortcutsAlpha: Flow<Float> = keyguardAlpha

    val statusBarAlpha: Flow<Float> = keyguardAlpha

    val notificationTranslationX: Flow<Float> =
        keyguardTranslationX.map { it.value }.filterNotNull()

    override val deviceEntryParentViewAlpha: Flow<Float> =
        if (Flags.gestureBetweenHubAndLockscreenMotion()) {
            keyguardAlpha
        } else {
            emptyFlow()
        }

    override val zoomOut: Flow<Float> =
        transitionAnimation.sharedFlow(
            onStep = { it },
            onFinish = { 1f },
            onCancel = { 0f },
            name = "LOCKSCREEN->GLANCEABLE_HUB: zoomOut",
        )
}
