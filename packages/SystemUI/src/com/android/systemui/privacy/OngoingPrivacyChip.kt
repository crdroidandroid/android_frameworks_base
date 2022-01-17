/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.privacy

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity.CENTER_VERTICAL
import android.view.Gravity.END
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.Flags
import com.android.systemui.res.R
import com.android.systemui.statusbar.events.BackgroundAnimatableView
import java.time.Duration

class OngoingPrivacyChip
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
    defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes), BackgroundAnimatableView {

    private var configuration: Configuration
    private var iconMargin = 0
    private var iconSize = 0
    private var iconColor = 0
    private var chipDrawable: GradientDrawable? = null

    @VisibleForTesting val iconsContainer: LinearLayout
    val launchableContentView
        get() = iconsContainer

    private val locationIndicatorsEnabled
        get() = Settings.Secure.getIntForUser(context.contentResolver,
            Settings.Secure.ENABLE_LOCATION_PRIVACY_INDICATOR, 1,
            UserHandle.USER_CURRENT) == 1

    var privacyList = emptyList<PrivacyItem>()
        set(value) {
            field = value
            updateView(PrivacyChipBuilder(context, field))
            if (locationIndicatorsEnabled) {
                updateResources()
            }
        }

    private val locationOnly: Boolean
        private get() =
            if (locationIndicatorsEnabled) {
                PrivacyConfig.Companion.privacyItemsAreLocationOnly(privacyList)
            } else {
                false
            }

    init {
        inflate(context, R.layout.ongoing_privacy_chip, this)
        id = R.id.privacy_chip
        layoutParams = LayoutParams(WRAP_CONTENT, MATCH_PARENT, CENTER_VERTICAL or END)
        clipChildren = true
        clipToPadding = true
        iconsContainer = requireViewById(R.id.icons_container)
        configuration = Configuration(context.resources.configuration)
        updateResources()
    }

    /**
     * When animating as a chip in the status bar, we want to animate the width for the container of
     * the privacy items. We have to subtract our own top and left offset because the bounds come to
     * us as absolute on-screen bounds, and `iconsContainer` is laid out relative to the frame
     * layout's bounds.
     */
    override fun setBoundsForAnimation(l: Int, t: Int, r: Int, b: Int) {
        iconsContainer.setLeftTopRightBottom(l - left, t - top, r - left, b - top)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (Flags.privacyDotLiveRegion()) {
            info.setMinDurationBetweenContentChanges(Duration.ofSeconds(10L))
        }
    }

    // Should only be called if the builder icons or app changed
    private fun updateView(builder: PrivacyChipBuilder) {
        fun setIcons(chipBuilder: PrivacyChipBuilder, iconsContainer: ViewGroup) {
            iconsContainer.removeAllViews()
            chipBuilder.generateIcons().forEachIndexed { i, it ->
                it.mutate()
                it.setTint(iconColor)
                val image =
                    ImageView(context).apply {
                        setImageDrawable(it)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                iconsContainer.addView(image, iconSize, iconSize)
                if (i != 0) {
                    val lp = image.layoutParams as MarginLayoutParams
                    lp.marginStart = iconMargin
                    image.layoutParams = lp
                }
            }
        }
        if (!privacyList.isEmpty()) {
            if (Flags.privacyDotLiveRegion()) {
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            }
            generateContentDescription(builder)
            setIcons(builder, iconsContainer)
        } else {
            if (Flags.privacyDotLiveRegion()) {
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
            }
            iconsContainer.removeAllViews()
        }
        requestLayout()
    }

    private fun generateContentDescription(builder: PrivacyChipBuilder) {
        val typesText = builder.joinTypes()
        contentDescription =
            context.getString(R.string.ongoing_privacy_chip_content_multiple_apps, typesText)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (newConfig != null) {
            val diff = newConfig.diff(configuration)
            configuration.setTo(newConfig)
            if (diff.and(ActivityInfo.CONFIG_DENSITY.or(ActivityInfo.CONFIG_FONT_SCALE)) != 0) {
                updateResources()
            }
        }
    }

    private fun updateResources() {
        iconMargin =
            context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_icon_margin)
        iconSize = context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_icon_size)
        iconColor =
            Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.colorPrimary)

        val height = context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_height)
        val padding =
            context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_side_padding)
        iconsContainer.layoutParams.height = height
        iconsContainer.setPaddingRelative(padding, 0, padding, 0)
        if (Flags.fixShadeHeaderWrongIconSize()) {
            iconsContainer.minimumWidth =
                context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_min_width)
        }
        if (locationIndicatorsEnabled) {
            if (chipDrawable == null) {
                chipDrawable =
                    context.getDrawable(R.drawable.statusbar_privacy_chip_bg)?.mutate()
                        as? GradientDrawable
                iconsContainer.background = chipDrawable
            }
            chipDrawable?.let { drawable ->
                val color = context.getColor(PrivacyConfig.Companion.getPrivacyColor(locationOnly))
                drawable.setColor(color)
            }
        } else {
            iconsContainer.background = context.getDrawable(R.drawable.statusbar_privacy_chip_bg)
        }
    }
}
