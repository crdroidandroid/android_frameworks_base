/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.footer.ui.viewmodel

import android.annotation.AttrRes
import android.annotation.ColorInt
import android.content.Context
import androidx.compose.runtime.Stable
import com.android.settingslib.Utils
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R

/**
 * A ViewModel for a simple footer actions button. This is used for the user switcher, settings and
 * power buttons.
 */
@Stable
sealed interface FooterActionsButtonViewModel {
    val id: Int
    val icon: Icon
    val iconTintFallback: Int?
    val backgroundColorFallback: Int
    val onClick: (Expandable) -> Unit
    val onLongClick: ((Expandable) -> Unit)?

    data class UserSwitcherViewModel(
        override val icon: Icon,
        override val onClick: (Expandable) -> Unit,
    ) : FooterActionsButtonViewModel {
        override val id: Int = R.id.multi_user_switch
        @ColorInt override val iconTintFallback: Int? = null
        @AttrRes override val backgroundColorFallback: Int = R.attr.shadeInactive
        override val onLongClick: ((Expandable) -> Unit)? = null
    }

    data class SettingsActionViewModel(
        private val context: Context,
        override val onClick: (Expandable) -> Unit,
        override val onLongClick: (Expandable) -> Unit,
    ) : FooterActionsButtonViewModel {
        override val id: Int = R.id.settings_button_container
        override val icon: Icon =
            Icon.Resource(
                R.drawable.ic_qs_footer_settings,
                ContentDescription.Resource(R.string.accessibility_quick_settings_settings),
            )
        @ColorInt
        override val iconTintFallback: Int =
            Utils.getColorAttrDefaultColor(context, R.attr.onShadeInactiveVariant)
        @AttrRes override val backgroundColorFallback: Int = R.attr.shadeInactive
    }

    data class PowerActionViewModel(
        private val context: Context,
        override val onClick: (Expandable) -> Unit,
    ) : FooterActionsButtonViewModel {
        override val id: Int = R.id.pm_lite
        override val icon: Icon =
            Icon.Resource(
                R.drawable.ic_qs_footer_power,
                ContentDescription.Resource(R.string.accessibility_quick_settings_power_menu),
            )
        @ColorInt
        override val iconTintFallback: Int =
            Utils.getColorAttrDefaultColor(context, R.attr.onShadeActive)
        @AttrRes override val backgroundColorFallback: Int = R.attr.shadeActive
        override val onLongClick: ((Expandable) -> Unit)? = null
    }
}
