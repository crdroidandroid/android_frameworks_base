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

package com.android.systemui.keyguard.ui.viewmodel

import android.util.MathUtils
import com.android.internal.util.crdroid.Utils.ambientAod
import com.android.systemui.communal.ui.compose.TransitionDuration
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.dagger.GlanceableHubBlurComponent
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.GlanceableHubTransition
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

@SysUISingleton
class AodToGlanceableHubTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
    blurFactory: GlanceableHubBlurComponent.Factory,
) : DeviceEntryIconTransition, GlanceableHubTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TransitionDuration.TO_GLANCEABLE_HUB_DURATION_MS.milliseconds,
                edge = Edge.create(AOD, Scenes.Communal),
            )
            .setupWithoutSceneContainer(edge = Edge.create(AOD, GLANCEABLE_HUB))

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)

    /** Fade out the lockscreen during a transition to GLANCEABLE_HUB. */
    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var currentAlpha = 0f
        return transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            startTime =
                if (ambientAod()) {
                    100.milliseconds // Wait for the light reveal to "hit" the LS elements.
                } else {
                    0.milliseconds
                },
            onStart = {
                currentAlpha =
                    if (ambientAod()) {
                        viewState.alpha()
                    } else {
                        0f
                    }
            },
            onStep = { MathUtils.lerp(currentAlpha, 0f, it) },
        )
    }

    override val windowBlurRadius: Flow<Float> =
        blurFactory.create(transitionAnimation).getBlurProvider().enterBlurRadius
}
