/*
 * Copyright (C) 2025 Neoteric OS
             (C) 2024-2025 The Clover Project
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
package com.android.systemui.qs.tiles.dialog

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.FrameLayout
import android.view.ContextThemeWrapper
import android.view.Gravity
import androidx.annotation.MainThread
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.google.android.material.slider.Slider
import javax.inject.Inject

class FlashlightDialogDelegate @Inject constructor(
    private val context: Context,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @Main private val mainExecutor: DelayableExecutor,
    @Background private val backgroundExecutor: DelayableExecutor,
    private val flashlightController: FlashlightController
) : SystemUIDialog.Delegate {

    private lateinit var slider: Slider

    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val flashlightMoveHaptic: VibrationEffect =
        VibrationEffect.get(VibrationEffect.EFFECT_TICK)

    override fun createDialog(): SystemUIDialog = systemUIDialogFactory.create(this)

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.setTitle(R.string.flashlight_strength_title)
        
        val container = FrameLayout(dialog.context)

        val themedContext = ContextThemeWrapper(dialog.context, 
                com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight)
        slider = Slider(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        container.addView(slider)
        dialog.setView(container)
        dialog.setPositiveButton(R.string.quick_settings_done, null, true)
        dialog.setNeutralButton(
            if (flashlightController.isEnabled())
                R.string.flashlight_strength_turn_off
            else
                R.string.flashlight_strength_turn_on,
            { _, _ ->
                val newState = !flashlightController.isEnabled()
                flashlightController.setFlashlight(newState)
                slider.isEnabled = newState
                dialog.getButton(SystemUIDialog.BUTTON_NEUTRAL)?.text =
                    if (newState)
                        dialog.context.getString(R.string.flashlight_strength_turn_off)
                    else
                        dialog.context.getString(R.string.flashlight_strength_turn_on)
            },
            false
        )
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        val maxLevel = flashlightController.getMaxLevel()
        val currentPercent = flashlightController.getCurrentPercent()

        slider.isEnabled = flashlightController.isEnabled()
        slider.valueFrom = 1f
        slider.valueTo = 100f
        slider.value = (currentPercent * 100f).coerceAtLeast(1f)
        slider.setLabelFormatter { value -> value.toInt().toString() }

        var last = -1
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val percent = value / 100f
                val p = Math.round(percent * 100)
                if (p != last) {
                    vibrator?.vibrate(flashlightMoveHaptic)
                    last = p
                }
                updateFlashlightStrength(percent, maxLevel)
            }
        }
    }

    @MainThread
    private fun updateFlashlightStrength(percent: Float, maxLevel: Int) {
        val level = (percent * maxLevel).toInt().coerceAtLeast(1)
        flashlightController.setFlashlightStrengthLevel(level)
    }
}
