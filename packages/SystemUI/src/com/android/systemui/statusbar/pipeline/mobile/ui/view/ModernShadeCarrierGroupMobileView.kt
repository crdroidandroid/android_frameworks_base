/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.widget.LinearLayout
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.ShadeCarrierBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.ShadeCarrierBinderKairos
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModelKairos
import com.android.systemui.util.AutoMarqueeTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewGroup containing a mobile carrier name and icon in the Shade Header. Can be multiple
 * instances as children under [ShadeCarrierGroup]
 */
class ModernShadeCarrierGroupMobileView(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs) {

    var subId: Int = -1

    override fun toString(): String {
        return "ModernShadeCarrierGroupMobileView(" +
            "subId=$subId, " +
            "viewString=${super.toString()}"
    }

    fun updateTextAppearance(resId: Int) {
        requireViewById<AutoMarqueeTextView>(R.id.mobile_carrier_text).setTextAppearance(resId)
    }

    companion object {

        /**
         * Inflates a new instance of [ModernShadeCarrierGroupMobileView], binds it to [viewModel],
         * and returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: ShadeCarrierGroupMobileIconViewModel,
        ): ModernShadeCarrierGroupMobileView {
            return (LayoutInflater.from(context).inflate(R.layout.shade_carrier_new, null)
                    as ModernShadeCarrierGroupMobileView)
                .also {
                    it.subId = viewModel.subscriptionId

                    val iconView = it.requireViewById<ModernStatusBarMobileView>(R.id.mobile_combo)
                    iconView.initView(slot) {
                        MobileIconBinder.bind(iconView, viewModel, STATE_ICON, logger)
                    }
                    logger.logNewViewBinding(it, viewModel)

                    val textView = it.requireViewById<AutoMarqueeTextView>(R.id.mobile_carrier_text)
                    ShadeCarrierBinder.bind(textView, viewModel)
                }
        }

        /**
         * Inflates a new instance of [ModernShadeCarrierGroupMobileView], binds it to [viewModel],
         * and returns it.
         */
        @ExperimentalKairosApi
        @JvmStatic
        fun constructAndBind(
            context: Context,
            logger: MobileViewLogger,
            slot: String,
            viewModel: BuildSpec<ShadeCarrierGroupMobileIconViewModelKairos>,
            scope: CoroutineScope,
            subscriptionId: Int,
            location: StatusBarLocation,
            kairosNetwork: KairosNetwork,
        ): Pair<ModernShadeCarrierGroupMobileView, Job> {
            val view =
                (LayoutInflater.from(context).inflate(R.layout.shade_carrier_new, null)
                        as ModernShadeCarrierGroupMobileView)
                    .apply { subId = subscriptionId }
            return view to
                scope.launch {
                    val iconView =
                        view.requireViewById<ModernStatusBarMobileView>(R.id.mobile_combo)
                    iconView.initView(slot) {
                        val (binding, _) =
                            MobileIconBinderKairos.bind(
                                view = iconView,
                                viewModel = viewModel,
                                initialVisibilityState = STATE_ICON,
                                logger = logger,
                                scope = this,
                                kairosNetwork = kairosNetwork,
                            )
                        binding
                    }
                    logger.logNewViewBinding(view, viewModel, location.name)

                    val textView =
                        view.requireViewById<AutoMarqueeTextView>(R.id.mobile_carrier_text)
                    launch { ShadeCarrierBinderKairos.bind(textView, viewModel, kairosNetwork) }
                }
        }
    }
}
