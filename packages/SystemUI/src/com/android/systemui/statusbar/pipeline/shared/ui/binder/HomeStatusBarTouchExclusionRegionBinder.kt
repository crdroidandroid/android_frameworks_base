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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.graphics.Rect
import android.graphics.Region
import android.window.DesktopExperienceFlags
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.setSnapshotBinding
import com.android.systemui.statusbar.layout.ui.viewmodel.AppHandlesViewModel
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation

/** Binds a [PhoneStatusBarView] to [AppHandlesViewModel]'s touch exclusion region. */
object HomeStatusBarTouchExclusionRegionBinder {

    /**
     * Reports the updated touchable region to the [PhoneStatusBarView] calculated from the touch
     * exclusion region provided.
     */
    fun bind(view: PhoneStatusBarView, appHandlesViewModel: AppHandlesViewModel): DisposableHandle {
        if (!DesktopExperienceFlags.ENABLE_REMOVE_STATUS_BAR_INPUT_LAYER.isTrue) {
            return DisposableHandle {}
        }

        // Update touchable regions when touchableExclusionRegion changes
        view.repeatWhenAttached {
            view.setSnapshotBinding {
                updateTouchableRegion(view, appHandlesViewModel.touchableExclusionRegion)
            }
            awaitCancellation()
        }

        // Update touchable region when status bar bounds change
        return view.onLayoutChanged {
            _,
            left,
            top,
            right,
            bottom,
            oldLeft,
            oldTop,
            oldRight,
            oldBottom ->
            if (top == oldTop && left == oldLeft && right == oldRight && bottom == oldBottom) {
                return@onLayoutChanged
            }
            updateTouchableRegion(view, appHandlesViewModel.touchableExclusionRegion)
        }
    }

    private fun updateTouchableRegion(view: PhoneStatusBarView, touchableExclusionRegion: Region) {
        val touchableRegion = calculateTouchableRegion(view, touchableExclusionRegion)
        view.updateTouchableRegion(touchableRegion)
        touchableRegion.recycle()
    }

    private fun calculateTouchableRegion(
        view: PhoneStatusBarView,
        touchExclusionRegion: Region,
    ): Region {
        val outBounds = Rect()
        view.getBoundsOnScreen(outBounds)

        if (view.getBrightnessControlEnabled()) {
            return Region.obtain().apply { set(outBounds) }
        }

        val touchableRegion =
            Region.obtain().apply {
                set(outBounds)
                op(touchExclusionRegion, Region.Op.DIFFERENCE)
            }

        if (touchableRegion.isEmpty) {
            touchableRegion.set(outBounds)
        }

        return touchableRegion
    }
}
