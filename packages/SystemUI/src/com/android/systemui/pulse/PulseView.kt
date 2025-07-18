/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.pulse

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var renderer: PulseRenderer? = null
    private var engine: PulseEngine? = null
    private var isAttached = false
    private var settingsRepo: PulseSettingsRepository? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    fun initialize(settingsRepo: PulseSettingsRepository) {
        this.settingsRepo = settingsRepo

        renderer = PulseRenderer(context, settingsRepo)
        engine = PulseEngine(context, settingsRepo) { processedHeights ->
            renderer?.updateHeights(processedHeights)
            postInvalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        engine?.stop()
        renderer?.cleanup()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isAttached) {
            renderer?.onDraw(canvas, width, height)
            postInvalidateOnAnimation()
        }
    }

    fun updateVisualizerData(data: PulseData) {
        if (isAttached && data.isDataValid) {
            engine?.processFFT(data.fftBytes!!)
        }
    }

    fun onMediaColorsChanged(color: Int) {
        renderer?.onMediaColorsChanged(color)
    }

    fun setVisibility(visible: Boolean) {
        visibility = if (visible) VISIBLE else GONE
    }
}
