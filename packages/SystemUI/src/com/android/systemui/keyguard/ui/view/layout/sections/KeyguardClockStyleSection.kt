/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
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
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.clocks.ClockStyle
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject

class KeyguardClockStyleSection
@Inject
constructor(
    private val context: Context,
    private val secureSettings: SecureSettings,
) : KeyguardSection() {
    
    private var clockStyleView: ClockStyle? = null
    private var isCustomClockEnabled: Boolean = false
    
    override fun addViews(constraintLayout: ConstraintLayout) {
        
        val clockStyle = secureSettings.getIntForUser(
            ClockStyle.CLOCK_STYLE_KEY, 0, UserHandle.USER_CURRENT
        )
        isCustomClockEnabled = clockStyle != 0
        
        if (!isCustomClockEnabled) return
        
        constraintLayout.findViewById<View?>(R.id.clock_ls)?.let { existingView ->
            (existingView.parent as? ViewGroup)?.removeView(existingView)
        }
        
        val inflater = android.view.LayoutInflater.from(context)
        clockStyleView = inflater.inflate(R.layout.keyguard_clock_style, null) as ClockStyle
        clockStyleView?.apply {
            id = R.id.clock_ls
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.VISIBLE
        }
        
        clockStyleView?.let { constraintLayout.addView(it) }
    }
    
    override fun bindData(constraintLayout: ConstraintLayout) {
        clockStyleView?.let { clockView ->
            clockView.onTimeChanged()
            clockView.requestLayout()
        }
    }
    
    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!isCustomClockEnabled) return
        
        constraintSet.apply {
            // Clock positioning - TOP of hierarchy
            connect(R.id.clock_ls, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(R.id.clock_ls, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            
            val topMargin = (context.resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f).toInt()
            connect(R.id.clock_ls, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, topMargin)
            
            constrainHeight(R.id.clock_ls, ConstraintSet.WRAP_CONTENT)
            constrainWidth(R.id.clock_ls, ConstraintSet.MATCH_CONSTRAINT)
            setMargin(R.id.clock_ls, ConstraintSet.START, 0)
            setMargin(R.id.clock_ls, ConstraintSet.END, 0)
            setElevation(R.id.clock_ls, 1f)
            
            // UNIFIED BARRIER - Create barrier in every section that could be last
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
        clockStyleView?.let { clockView ->
            (clockView.parent as? ViewGroup)?.removeView(clockView)
        }
        clockStyleView = null
    }
}
