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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import javax.inject.Inject

class AODStyleSection
@Inject
constructor(
    private val context: Context,
) : KeyguardSection() {
    
    private var aodStyleView: View? = null
    
    override fun addViews(constraintLayout: ConstraintLayout) {
        // Remove existing view with the same ID if it exists
        constraintLayout.findViewById<View?>(R.id.aod_ls)?.let { existingView ->
            (existingView.parent as? ViewGroup)?.removeView(existingView)
        }
        
        // Inflate the AOD style layout
        aodStyleView = LayoutInflater.from(context).inflate(
            R.layout.keyguard_aod_style,
            constraintLayout,
            false
        ).apply {
            id = R.id.aod_ls
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        constraintLayout.addView(aodStyleView)
    }
    
    override fun bindData(constraintLayout: ConstraintLayout) {
        // The AODStyle component handles its own data binding
        // through its TunerService integration and StatusBarStateController callbacks
    }
    
    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            // Position AOD style within the keyguard_status_area
            connect(
                R.id.aod_ls,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START
            )
            connect(
                R.id.aod_ls,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            
            // Position at the top of status area with proper margin to avoid status bar overlap
            val topMargin = (context.resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f).toInt()
            connect(
                R.id.aod_ls,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                topMargin
            )
            
            // Set dimensions
            constrainHeight(R.id.aod_ls, ConstraintSet.WRAP_CONTENT)
            constrainWidth(R.id.aod_ls, ConstraintSet.MATCH_CONSTRAINT)
            
            // Set appropriate margins matching the original XML structure
            setMargin(R.id.aod_ls, ConstraintSet.START, 
                context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start))
            setMargin(R.id.aod_ls, ConstraintSet.END, 
                context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start))
            
            // Ensure proper layering within the status area
            setElevation(R.id.aod_ls, 2f) // Higher elevation than info widgets
            
            // Update other elements to position below AOD style when it's visible
            if (constraintSet.getConstraint(R.id.lockscreen_clock_view) != null) {
                connect(
                    R.id.lockscreen_clock_view,
                    ConstraintSet.TOP,
                    R.id.aod_ls,
                    ConstraintSet.BOTTOM,
                    8
                )
            }
            
            // Apply consistent top margin to clock_ls whether AOD is visible or not
            if (constraintSet.getConstraint(R.id.clock_ls) != null) {
                // If AOD is present, position clock below it
                // If AOD is hidden/gone, ensure clock still has proper top margin
                val clockTopMargin = if (aodStyleView?.visibility == View.VISIBLE) {
                    8 // Small gap below AOD
                } else {
                    topMargin // Same margin as AOD would have had
                }
                
                if (aodStyleView?.visibility == View.VISIBLE) {
                    connect(
                        R.id.clock_ls,
                        ConstraintSet.TOP,
                        R.id.aod_ls,
                        ConstraintSet.BOTTOM,
                        clockTopMargin
                    )
                } else {
                    // When AOD is hidden, position clock at top with proper margin
                    connect(
                        R.id.clock_ls,
                        ConstraintSet.TOP,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.TOP,
                        clockTopMargin
                    )
                }
            }
            
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
                    R.id.keyguard_info_widgets,
                    R.id.keyguard_widgets,
                    R.id.lockscreen_clock_view,
                    sharedR.id.bc_smartspace_view,
                    sharedR.id.date_smartspace_view,
                )
            )

            // Position notifications below ALL status area content
            if (constraintSet.getConstraint(R.id.left_aligned_notification_icon_container) != null) {
                connect(
                    R.id.left_aligned_notification_icon_container,
                    ConstraintSet.TOP,
                    R.id.smart_space_barrier_bottom,
                    ConstraintSet.BOTTOM,
                    context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start_icons)
                )
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        aodStyleView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        aodStyleView = null
    }
}
