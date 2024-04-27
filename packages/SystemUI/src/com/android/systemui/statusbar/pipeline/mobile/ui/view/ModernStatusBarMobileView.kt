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
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.android.settingslib.flags.Flags.newStatusBarIcons
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
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

class ModernStatusBarMobileView(context: Context, attrs: AttributeSet?) :
    ModernStatusBarView(context, attrs) {

    var subId: Int = -1
    private lateinit var mobileGroup: LinearLayout

    override fun toString(): String {
        return "ModernStatusBarMobileView(" +
            "slot='$slot', " +
            "subId=$subId, " +
            "isCollecting=${binding.isCollecting()}, " +
            "visibleState=${getVisibleStateString(visibleState)}); " +
            "viewString=${super.toString()}"
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        mobileGroup = requireViewById(R.id.mobile_group)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        mobileGroup.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(mobileGroup.measuredWidth, mobileGroup.measuredHeight)
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
                .also {
                    // Flag-specific configuration
                    if (NewStatusBarIcons.isEnabled) {
                        // triangle
                        it.requireViewById<ImageView>(R.id.mobile_signal).apply {
                            layoutParams.height =
                                context.resources.getDimensionPixelSize(
                                    R.dimen.status_bar_mobile_signal_size_updated
                                )
                        }

                        // RAT indicator container
                        it.requireViewById<FrameLayout>(R.id.mobile_type_container).apply {
                            (layoutParams as MarginLayoutParams).marginEnd =
                                context.resources.getDimensionPixelSize(
                                    R.dimen.status_bar_mobile_container_margin_end
                                )
                            layoutParams.height =
                                context.resources.getDimensionPixelSize(
                                    R.dimen.status_bar_mobile_container_height_updated
                                )
                        }

                        // RAT indicator
                        it.requireViewById<ImageView>(R.id.mobile_type).apply {
                            layoutParams.height =
                                context.resources.getDimensionPixelSize(
                                    R.dimen.status_bar_mobile_type_size_updated
                                )
                        }
                    }

                    it.subId = viewModel.subscriptionId
                    it.initView(slot) {
                        MobileIconBinder.bind(view = it, viewModel = viewModel, logger = logger)
                    }
                    logger.logNewViewBinding(it, viewModel)
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
            viewModel: BuildSpec<LocationBasedMobileViewModelKairos>,
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
                        if (newStatusBarIcons()) {
                            // New icon (with no embedded whitespace) is slightly shorter
                            // (but actually taller)
                            val iconView = requireViewById<ImageView>(R.id.mobile_signal)
                            val lp = iconView.layoutParams
                            lp.height =
                                context.resources.getDimensionPixelSize(
                                    R.dimen.status_bar_mobile_signal_size_updated
                                )
                        }

                        subId = subscriptionId
                    }

            lateinit var jobResult: Job
            view.initView(slot) {
                val (binding, job) =
                    MobileIconBinderKairos.bind(
                        view = view,
                        viewModel = viewModel,
                        logger = logger,
                        scope = scope,
                        kairosNetwork = kairosNetwork,
                    )
                jobResult = job
                binding
            }
            logger.logNewViewBinding(view, viewModel, location.name)
            return view to jobResult
        }
    }
}
