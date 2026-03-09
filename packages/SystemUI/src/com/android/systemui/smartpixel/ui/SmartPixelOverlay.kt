/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.smartpixel.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.systemui.smartpixel.domain.SmartPixelSettings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn

@SysUISingleton
class SmartPixelOverlay @Inject constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Application private val applicationScope: CoroutineScope,
    private val settings: SmartPixelSettings,
) {
    private var filterView: SmartPixelView? = null
    private var viewController: SmartPixelViewController? = null

    fun init() {
        settings.init()

        combine(
            settings.isEnabled,
            settings.percent,
        ) { enabled, percent ->
            if (enabled) {
                showOverlay()
                viewController?.updateConfig(percent)
            } else {
                hideOverlay()
            }
        }.launchIn(applicationScope)
    }

    private fun showOverlay() {
        if (filterView != null) return

        val view = SmartPixelView(context)
        val controller = SmartPixelViewController(view)

        val params = WindowManager.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                LayoutParams.FLAG_HARDWARE_ACCELERATED or
                LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        )
        params.title = "SmartPixelFilter"
        params.layoutInDisplayCutoutMode =
            LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.privateFlags = params.privateFlags or
            LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY

        windowManager.addView(view, params)
        controller.init()
        controller.updateConfig(settings.percent.value)

        filterView = view
        viewController = controller
    }

    private fun hideOverlay() {
        viewController?.destroy()
        filterView?.let { view ->
            windowManager.removeViewImmediate(view)
        }
        filterView = null
        viewController = null
    }
}
