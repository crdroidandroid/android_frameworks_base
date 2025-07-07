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

import com.android.systemui.statusbar.phone.StatusBarIconHolder
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState

class NetworkSpeedIconHolder : StatusBarIconHolder() {

    companion object {
        const val TYPE_NETWORK_SPEED = 6

        fun fromNetworkIconState(state: NetworkSpeedIconState): NetworkSpeedIconHolder {
            val holder = NetworkSpeedIconHolder()
            holder.mNetworkSpeedIconState = state
            holder.type = TYPE_NETWORK_SPEED
            return holder
        }
    }

    private var mNetworkSpeedIconState: NetworkSpeedIconState? = null

    fun setNetworkSpeedIconState(state: NetworkSpeedIconState) {
        mNetworkSpeedIconState = state
    }

    fun getNetworkSpeedIconState(): NetworkSpeedIconState? {
        return mNetworkSpeedIconState
    }

    override var isVisible: Boolean
        get() = mNetworkSpeedIconState?.isVisible() ?: false
        set(value) {
            if (mNetworkSpeedIconState == null || isVisible == value) return
            mNetworkSpeedIconState?.setVisible(value)
        }

    override fun toString(): String {
        return mNetworkSpeedIconState?.toString() ?: "null"
    }
}
