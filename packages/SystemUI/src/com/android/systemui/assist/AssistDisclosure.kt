/*
 * Copyright 2020, Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.assist

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.view.WindowManager

/**
 * Visually discloses that contextual data was provided to an assistant.
 */
class AssistDisclosure(
    private val context: Context,
    private val handler: Handler
) {

    private val showRunnable = Runnable { show() }
    private val windowManager by lazy { context.getSystemService(WindowManager::class.java) }

    private var assistView: AssistDisclosureView? = null
    private var assistViewAdded = false

    fun postShow() {
        handler.removeCallbacks(showRunnable)
        handler.post(showRunnable)
    }

    private fun show() {
        assistView = assistView ?: AssistDisclosureView(context).apply {
            setOnFinishListener { hide() }
        }

        if (!assistViewAdded) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                title = "AssistDisclosure"
                setFitInsetsTypes(0)
            }

            windowManager.addView(assistView, lp)
            assistViewAdded = true
        }
    }

    private fun hide() {
        if (assistViewAdded) {
            windowManager.removeView(assistView)
            assistViewAdded = false
        }
    }
}
