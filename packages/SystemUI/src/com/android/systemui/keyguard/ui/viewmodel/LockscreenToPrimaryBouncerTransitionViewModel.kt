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
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.util.MathUtils
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Breaks down LOCKSCREEN->PRIMARY BOUNCER transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class LockscreenToPrimaryBouncerTransitionViewModel
@Inject
constructor(private val blurConfig: BlurConfig, animationFlow: KeyguardTransitionAnimationFlow) :
    DeviceEntryIconTransition, PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.create(from = LOCKSCREEN, to = Overlays.Bouncer),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = LOCKSCREEN, to = PRIMARY_BOUNCER))

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStep = { step -> 1f - step },
            // Rapid swipes to bouncer, and may end up skipping intermediate values that would've
            // caused a complete fade out of lockscreen elements. Ensure it goes to 0f.
            onFinish = { 0f },
        )

    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        return if (SceneContainerFlag.isEnabled) {
            // Lockscreen -> Bouncer is a scene transition in Flexiglass.
            // SharedNotificationContainerViewModel#alphaForShadeAndQsExpansion might be relevant
            // instead.
            emptyFlow()
        } else {
            var startAlpha = 1f
            transitionAnimation.sharedFlow(
                duration = 200.milliseconds,
                onStart = { startAlpha = viewState.alpha() },
                onStep = { MathUtils.lerp(startAlpha, 0f, it) },
                // Rapid swipes to bouncer, and may end up skipping intermediate values that
                // would've caused a complete fade out of lockscreen elements. Ensure it goes to
                // 0f.
                onFinish = { 0f },
            )
        }
    }

    val notificationAlpha: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            // Lockscreen -> Bouncer is a scene transition in Flexiglass.
            // SharedNotificationContainerViewModel#alphaForShadeAndQsExpansion might be relevant
            // instead.
            emptyFlow()
        } else {
            transitionAnimation.sharedFlowWithShade(
                duration = 200.milliseconds,
                onStep = { step, isShadeExpanded -> if (isShadeExpanded) 1f else 1f - step },
                onFinish = { isShadeExpanded -> if (isShadeExpanded) 1f else 0f },
            )
        }

    override val notificationBlurRadius: Flow<Float> =
        transitionAnimation.sharedFlowWithShade(
            duration = 1.milliseconds,
            onStep = { _, isShadeExpanded ->
                if (isShadeExpanded) blurConfig.maxBlurRadiusPx else null
            },
        )

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.sharedFlowWithShade(
            duration = 250.milliseconds,
            onStep = { step, isShadeExpanded -> if (isShadeExpanded) 0f else 1f - step },
            onCancel = { 0f },
            onFinish = { 0f },
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> = shortcutsAlpha

    override val windowBlurRadius: Flow<Float> =
        transitionAnimation.sharedFlowWithShade(
            duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
            onStep = { step, isShadeExpanded ->
                if (isShadeExpanded) {
                    if (Flags.notificationShadeBlur()) {
                        blurConfig.maxBlurRadiusPx
                    } else {
                        null
                    }
                } else {
                    transitionProgressToBlurRadius(
                        startBlurRadius = blurConfig.minBlurRadiusPx,
                        endBlurRadius = blurConfig.maxBlurRadiusPx,
                        transitionProgress = step,
                    )
                }
            },
        )
}
