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

package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.Flags
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.buildSpec
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView.getVisibleStateString
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModelKairos
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import kotlin.math.roundToInt

class ModernStatusBarMobileView(context: Context, attrs: AttributeSet?) :
    ModernStatusBarView(context, attrs) {

    var subId: Int = -1

    override fun toString(): String {
        return "ModernStatusBarMobileView(" +
            "slot='$slot', " +
            "subId=$subId, " +
            "isCollecting=${binding.isCollecting()}, " +
            "visibleState=${getVisibleStateString(visibleState)}); " +
            "viewString=${super.toString()}"
    }

    public override fun onConfigurationChanged(newConfig: Configuration?) {
        if (Flags.fixShadeHeaderWrongIconSize()) {
            configureLayoutForNewStatusBarIcons()
        }
    }

    /**
     * When [NewStatusBarIcons] is enabled, we have to tweak the layout. This can be moved directly
     * into the layout xml when the flag is rolled out fully
     */
    fun configureLayoutForNewStatusBarIcons() {
        // Margins around the entire container
        requireViewById<AlphaOptimizedLinearLayout>(R.id.mobile_group).apply {
            val lp = layoutParams as MarginLayoutParams
            lp.marginStart =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_container_margin_start
                )
            lp.marginEnd =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_container_margin_end
                )
        }

        // triangle
        requireViewById<ImageView>(R.id.mobile_signal).apply {
            layoutParams.height =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_signal_size_updated
                )
        }

        // RAT indicator container
        requireViewById<FrameLayout>(R.id.mobile_type_container).apply {
            // marginStart is moved to the outer group
            (layoutParams as MarginLayoutParams).marginStart = 0
            (layoutParams as MarginLayoutParams).marginEnd =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_type_container_margin_end
                )
            layoutParams.height =
                context.resources.getDimensionPixelSize(
                    R.dimen.status_bar_mobile_container_height_updated
                )
        }

        // RAT indicator
        requireViewById<ImageView>(R.id.mobile_type).apply {
            layoutParams.height =
                context.resources.getDimensionPixelSize(R.dimen.status_bar_mobile_type_size_updated)
        }
    }

    override fun initView(slot: String, bindingCreator: () -> ModernStatusBarViewBinding) {
        super.initView(slot, bindingCreator)
        // Resize HD icon to make fit into the mobile view
        val signalSize = context.resources.getDimensionPixelSize(
                com.android.settingslib.R.dimen.signal_icon_size
        )
        val viewportSize = context.resources.getDimensionPixelSize(
                R.dimen.signal_icon_viewport_size
        )
        val mobileHd = requireViewById<ImageView>(R.id.mobile_hd)
        val lp = mobileHd.layoutParams
        lp.height = (lp.height * (signalSize / viewportSize.toFloat())).roundToInt()
        lp.width = (lp.width * (signalSize / viewportSize.toFloat())).roundToInt()
        mobileHd.layoutParams = lp
    }

    companion object {

        /**
         * Inflates a new instance of [ModernStatusBarMobileView], binds it to [viewModel], and
         * returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: LocationBasedMobileViewModel,
        ): ModernStatusBarMobileView {
            return (LayoutInflater.from(context)
                    .inflate(R.layout.status_bar_mobile_signal_group_new, null)
                    as ModernStatusBarMobileView)
                .apply {
                    // Flag-specific configuration
                    if (NewStatusBarIcons.isEnabled) {
                        configureLayoutForNewStatusBarIcons()
                    }

                    subId = viewModel.subscriptionId
                    initView(slot) {
                        MobileIconBinder.bind(view = this, viewModel = viewModel, logger = logger)
                    }
                    logger.logNewViewBinding(this, viewModel)
                }
        }

        /**
         * Inflates a new instance of [ModernStatusBarMobileView], binds it to [viewModel], and
         * returns it.
         */
        @ExperimentalKairosApi
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: LocationBasedMobileViewModelKairos,
            scope: CoroutineScope,
            subscriptionId: Int,
            location: StatusBarLocation,
            kairosNetwork: KairosNetwork,
        ): Pair<ModernStatusBarMobileView, Job> {
            val view =
                (LayoutInflater.from(context)
                        .inflate(R.layout.status_bar_mobile_signal_group_new, null)
                        as ModernStatusBarMobileView)
                    .apply {
                        // Flag-specific configuration
                        if (NewStatusBarIcons.isEnabled) {
                            configureLayoutForNewStatusBarIcons()
                        }

                        subId = subscriptionId
                    }

            lateinit var jobResult: Job
            view.initView(slot) {
                val (binding, job) =
                    MobileIconBinderKairos.bind(
                        view = view,
                        viewModel = buildSpec { viewModel },
                        logger = logger,
                        scope = scope,
                        kairosNetwork = kairosNetwork,
                        subId = subscriptionId,
                    )
                jobResult = job
                binding
            }
            logger.logNewViewBinding(view, viewModel, location.name)
            return view to jobResult
        }
    }
}
