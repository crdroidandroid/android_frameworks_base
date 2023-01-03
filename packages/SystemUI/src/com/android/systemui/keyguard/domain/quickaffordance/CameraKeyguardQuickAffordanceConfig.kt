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

import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.camera.CameraGestureHelper
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Camera quick affordance data source. */
@SysUISingleton
class CameraKeyguardQuickAffordanceConfig
@Inject
constructor(
    private val cameraGestureHelper: CameraGestureHelper,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) : KeyguardQuickAffordanceConfig {

    override val state: Flow<KeyguardQuickAffordanceConfig.State> = conflatedCallbackFlow {
        val callback =
            object : KeyguardUpdateMonitorCallback() {
                override fun onKeyguardVisibilityChanged(showing: Boolean) {
                    trySendWithFailureLogging(state(), TAG)
                }
            }

        keyguardUpdateMonitor.registerCallback(callback)

        awaitClose {
            keyguardUpdateMonitor.removeCallback(callback)
        }
    }

    override fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?,
    ): KeyguardQuickAffordanceConfig.OnClickedResult {
        cameraGestureHelper.launchCamera(-1)
        return KeyguardQuickAffordanceConfig.OnClickedResult.Handled
    }

    private fun state(): KeyguardQuickAffordanceConfig.State {
        return if (cameraGestureHelper.canCameraGestureBeLaunched(StatusBarState.KEYGUARD)) {
            KeyguardQuickAffordanceConfig.State.Visible(
                icon = ContainedDrawable.WithResource(R.drawable.ic_camera_alt_24dp),
                contentDescriptionResourceId = R.string.accessibility_camera_button,
            )
        } else {
            KeyguardQuickAffordanceConfig.State.Hidden
        }
    }

    companion object {
        private const val TAG = "CameraKeyguardQuickAffordanceConfig"
    }
}
