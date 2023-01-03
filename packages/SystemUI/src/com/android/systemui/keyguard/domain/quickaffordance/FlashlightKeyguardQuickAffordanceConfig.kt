/*
 *  Copyright (C) 2022 The Android Open Source Project
 *            (C) 2022 Paranoid Android
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.FlashlightController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Flashlight quick affordance data source. */
@SysUISingleton
class FlashlightKeyguardQuickAffordanceConfig
@Inject
constructor(
    private val controller: FlashlightController,
) : KeyguardQuickAffordanceConfig {

    override val state: Flow<KeyguardQuickAffordanceConfig.State> = conflatedCallbackFlow {
        val callback =
            object : FlashlightController.FlashlightListener {
                override fun onFlashlightChanged(enabled: Boolean) {
                    trySendWithFailureLogging(state(), TAG)
                }
                override fun onFlashlightError() {
                    trySendWithFailureLogging(state(), TAG)
                }
                override fun onFlashlightAvailabilityChanged(available: Boolean) {
                    trySendWithFailureLogging(state(), TAG)
                }
            }

        controller.addCallback(callback)

        awaitClose {
            controller.removeCallback(callback)
        }
    }

    override fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?,
    ): KeyguardQuickAffordanceConfig.OnClickedResult {
        controller.setFlashlight(!controller.isEnabled())
        return KeyguardQuickAffordanceConfig.OnClickedResult.Handled
    }

    private fun state(): KeyguardQuickAffordanceConfig.State {
        return if (controller.hasFlashlight() && controller.isAvailable()) {
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ContainedDrawable.WithResource(if (controller.isEnabled()) {
                    R.drawable.ic_flashlight_off
                } else {
                    R.drawable.ic_flashlight_on
                }),
                contentDescriptionResourceId = R.string.quick_settings_flashlight_label,
            )
        } else {
            KeyguardQuickAffordanceConfig.State.Hidden
        }
    }

    companion object {
        private const val TAG = "FlashlightKeyguardQuickAffordanceConfig"
    }
}
