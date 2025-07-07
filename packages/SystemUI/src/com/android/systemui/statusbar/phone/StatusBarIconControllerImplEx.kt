/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.systemui.statusbar.phone

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl
import com.android.systemui.statusbar.phone.ui.StatusBarIconList
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedView

class StatusBarIconControllerImplEx private constructor(
    private val context: Context,
    private val iconController: StatusBarIconControllerImpl,
    private val iconList: StatusBarIconList
) {

    fun setNetworkSpeedIcon(slot: String, state: NetworkSpeedIconState?) {
        if (state == null || !state.isVisible()) {
            iconController.removeIcon(slot, 0)
            return
        }

        val existingHolder = iconList.getIconHolder(slot, 0)

        if (existingHolder == null) {
            val newHolder = NetworkSpeedIconHolder.fromNetworkIconState(state)
            iconController.setIcon(slot, newHolder)
        } else if (existingHolder is NetworkSpeedIconHolder) {
            existingHolder.setNetworkSpeedIconState(state)
            iconController.handleSet(slot, existingHolder)
        }
    }

    fun addHolder(
        index: Int,
        slot: String,
        rootGroup: ViewGroup,
        holder: StatusBarIconHolder,
        blocked: Boolean
    ): StatusIconDisplayable? {
        return if (holder.type == 6 && holder is NetworkSpeedIconHolder) {
            addNetworkSpeedIcon(index, slot, holder.getNetworkSpeedIconState(), rootGroup, blocked)
        } else {
            null
        }
    }

    private fun addNetworkSpeedIcon(
        index: Int,
        slot: String,
        state: NetworkSpeedIconState?,
        rootGroup: ViewGroup,
        blocked: Boolean
    ): NetworkSpeedView {
        val view = createNetworkSpeedView(slot, blocked)
        view.applyNetworkState(state)
        rootGroup.addView(view, index, createLayoutParams())
        return view
    }

    private fun updateNetworkSpeedIcon(viewIndex: Int, state: NetworkSpeedIconState?, rootGroup: ViewGroup) {
        val view = rootGroup.getChildAt(viewIndex) as? NetworkSpeedView ?: return
        view.applyNetworkState(state)
    }

    private fun createNetworkSpeedView(slot: String, blocked: Boolean): NetworkSpeedView {
        return NetworkSpeedView.fromContext(context, slot, blocked)
    }

    private fun createLayoutParams(): LinearLayout.LayoutParams {
        val width = context.resources.getDimensionPixelSize(R.dimen.network_speed_view_width)
        return LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    fun onSetIconHolder(viewIndex: Int, holder: StatusBarIconHolder, rootGroup: ViewGroup) {
        if (holder.type == 6 && holder is NetworkSpeedIconHolder) {
            updateNetworkSpeedIcon(viewIndex, holder.getNetworkSpeedIconState(), rootGroup)
        }
    }
    
    companion object {
        @Volatile
        private var instance: StatusBarIconControllerImplEx? = null

        fun init(
            context: Context,
            iconController: StatusBarIconControllerImpl,
            iconList: StatusBarIconList
        ) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = StatusBarIconControllerImplEx(context, iconController, iconList)
                    }
                }
            }
        }

        fun get(): StatusBarIconControllerImplEx {
            return instance ?: throw IllegalStateException("init must be called first!!!")
        }
    }
}
