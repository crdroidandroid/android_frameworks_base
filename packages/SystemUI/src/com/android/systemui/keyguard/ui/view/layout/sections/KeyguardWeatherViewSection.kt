/*
 * Copyright (C) 2024-2026 crDroid Android Project
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject

import com.android.systemui.weather.WeatherInfoView

class KeyguardWeatherViewSection
@Inject
constructor(
    private val context: Context,
    val layoutInflater: LayoutInflater,
    val smartspaceController: LockscreenSmartspaceController,
) : KeyguardSection() {
    private lateinit var weatherView: WeatherInfoView

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!smartspaceController.isOmniWeatherEnabled || smartspaceController.isEnabled) return

        weatherView =
            layoutInflater.inflate(R.layout.keyguard_weather_area, null, false) as WeatherInfoView
        constraintLayout.addView(weatherView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!smartspaceController.isOmniWeatherEnabled || smartspaceController.isEnabled) return

        weatherView.init()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!smartspaceController.isOmniWeatherEnabled || smartspaceController.isEnabled) return

        constraintSet.apply {
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                context.resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start) +
                    context.resources.getDimensionPixelSize(clocksR.dimen.status_view_margin_horizontal),
            )
            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            constrainHeight(R.id.keyguard_weather_area, ConstraintSet.WRAP_CONTENT)

            connect(
                R.id.keyguard_weather_area,
                ConstraintSet.TOP,
                R.id.keyguard_slice_view,
                ConstraintSet.BOTTOM
            )

            // UNIFIED BARRIER - Include ALL status area elements
            createUnifiedBarrierAndNotificationConstraints(constraintSet)
        }
    }

    private fun createUnifiedBarrierAndNotificationConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            // UNIFIED BARRIER - Include ALL status area elements
            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(
                    R.id.keyguard_slice_view,
                    R.id.keyguard_weather_area,
                    R.id.clock_ls,
                    sharedR.id.bc_smartspace_view,
                    sharedR.id.date_smartspace_view,
                )
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (!smartspaceController.isOmniWeatherEnabled || smartspaceController.isEnabled) return

        constraintLayout.findViewById<WeatherInfoView?>(R.id.keyguard_weather_area)?.let { weatherArea ->
            weatherArea.cleanup()
            constraintLayout.removeView(weatherArea)
        }
    }
}
