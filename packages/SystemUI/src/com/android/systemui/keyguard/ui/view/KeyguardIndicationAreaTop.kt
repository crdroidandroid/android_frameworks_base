/*
 * Copyright (C) 2025 crDroid Android Project
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

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.FaceUnlockImageView

class KeyguardIndicationAreaTop(
    context: Context,
    private val attrs: AttributeSet?,
) :
    LinearLayout(
        context,
        attrs,
    ) {

    init {
        setId(R.id.keyguard_indication_area_top)
        orientation = LinearLayout.VERTICAL

        addView(
            faceUnlockIcon(),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = R.dimen.face_unlock_icon_margin_top.dp()
            }
        )
    }

    private fun faceUnlockIcon(): FaceUnlockImageView {
        return FaceUnlockImageView(context, attrs).apply {
            id = R.id.face_unlock_icon
            gravity = Gravity.CENTER
            setAlpha(0.8f)
            setVisibility(View.GONE)
        }
    }

    private fun Int.dp(): Int {
        return context.resources.getDimensionPixelSize(this)
    }
}
