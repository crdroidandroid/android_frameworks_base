/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.customize

import android.content.Context
import android.text.TextUtils

import android.os.UserHandle
import android.provider.Settings

import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.QSTileViewImpl

/**
 * Class for displaying tiles in [QSCustomizer] with the new design (labels on the side).
 */
class CustomizeTileView(
    context: Context,
    icon: QSIconView
) : QSTileViewImpl(context, icon, collapsed = false) {

    var showAppLabel = false
        set(value) {
            field = value
            secondaryLabel.visibility = getVisibilityState(secondaryLabel.text)
        }

    var showSideView = true
        set(value) {
            field = value
            if (!showSideView) sideView.visibility = GONE
        }

    private fun isA11Style(): Boolean = Settings.System.getIntForUser(context.contentResolver, 
    Settings.System.QS_TILE_UI_STYLE, 0,  UserHandle.USER_CURRENT) != 0

    override fun handleStateChanged(state: QSTile.State) {
        super.handleStateChanged(state)
        showRippleEffect = false
        secondaryLabel.visibility = getVisibilityState(state.secondaryLabel)
        if (!showSideView) sideView.visibility = GONE
    }

    private fun getVisibilityState(text: CharSequence?): Int {
        return if (showAppLabel && !TextUtils.isEmpty(text)) {
            VISIBLE
        } else {
            if (isA11Style()) INVISIBLE else GONE
        }
    }

    override fun animationsEnabled(): Boolean {
        return false
    }

    override fun isLongClickable(): Boolean {
        return false
    }

    fun changeState(state: QSTile.State) {
        handleStateChanged(state)
    }
}