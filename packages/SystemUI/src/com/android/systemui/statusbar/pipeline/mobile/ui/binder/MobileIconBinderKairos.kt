/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui.binder

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Space
import androidx.core.view.isVisible
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.Flags
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.MutableState
import com.android.systemui.kairos.combine
import com.android.systemui.kairos.effect
import com.android.systemui.kairos.launchEffect
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.connectivity.ThemeIconController
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModelKairos
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewVisibilityHelper
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarViewBinderConstants
import com.android.systemui.util.lifecycle.kairos.repeatWhenAttachedToWindow
import com.android.systemui.util.lifecycle.kairos.repeatWhenWindowIsVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

object MobileIconBinderKairos {

    @ExperimentalKairosApi
    fun bind(
        subId: Int,
        view: ViewGroup,
        viewModel: BuildSpec<LocationBasedMobileViewModelKairos>,
        @StatusBarIconView.VisibleState
        initialVisibilityState: Int = StatusBarIconView.STATE_HIDDEN,
        logger: MobileViewLogger,
        scope: CoroutineScope,
        kairosNetwork: KairosNetwork,
    ): Pair<ModernStatusBarViewBinding, Job> {
        val binding =
            ModernStatusBarViewBindingKairosImpl(subId, kairosNetwork, initialVisibilityState)
        val mobileDrawable = SignalDrawable(view.context)
        return binding to
            scope.launch {
                kairosNetwork.activateSpec(
                    nameTag { "MobileIconBinderKairos.bind(subId=$subId)" }
                ) {
                    bind(
                        view = view,
                        mobileDrawable = mobileDrawable,
                        viewModel = viewModel.applySpec(),
                        logger = logger,
                        binding = binding,
                    )
                }
            }
    }

    @ExperimentalKairosApi
    private class ModernStatusBarViewBindingKairosImpl(
        subId: Int,
        kairosNetwork: KairosNetwork,
        initialVisibilityState: Int,
    ) : ModernStatusBarViewBinding {

        @JvmField var shouldIconBeVisible: Boolean = false
        @JvmField var isCollecting: Boolean = false

        // TODO(b/238425913): We should log this visibility state.
        val visibility =
            MutableState(
                kairosNetwork,
                initialVisibilityState,
                nameTag { "ModernStatusBarViewBindingKairosImpl(subId=$subId).visibility" },
            )
        val iconTint =
            MutableState(
                kairosNetwork,
                MobileIconColors(
                    tint = DarkIconDispatcher.DEFAULT_ICON_TINT,
                    contrast = DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT,
                ),
                nameTag { "ModernStatusBarViewBindingKairosImpl(subId=$subId).iconTint" },
            )
        val decorTint =
            MutableState(
                kairosNetwork,
                Color.WHITE,
                nameTag { "ModernStatusBarViewBindingKairosImpl(subId=$subId).decorTint" },
            )

        override fun getShouldIconBeVisible(): Boolean = shouldIconBeVisible

        override fun onVisibilityStateChanged(state: Int) {
            visibility.setValue(state)
        }

        override fun onIconTintChanged(newTint: Int, contrastTint: Int) {
            iconTint.setValue(MobileIconColors(tint = newTint, contrast = contrastTint))
        }

        override fun onDecorTintChanged(newTint: Int) {
            decorTint.setValue(newTint)
        }

        override fun isCollecting(): Boolean = isCollecting
    }

    @ExperimentalKairosApi
    private fun BuildScope.bind(
        view: ViewGroup,
        mobileDrawable: SignalDrawable,
        viewModel: LocationBasedMobileViewModelKairos,
        logger: MobileViewLogger,
        binding: ModernStatusBarViewBindingKairosImpl,
    ) {
        viewModel.isVisible.observe(
            name = nameTag("MobileIconBinderKairos.bindingShouldIconBeVisible")
        ) {
            binding.shouldIconBeVisible = it
        }

        val mobileGroupView = view.requireViewById<ViewGroup>(R.id.mobile_group)
        val activityContainer = view.requireViewById<View>(R.id.inout_container)
        val activityIn = view.requireViewById<ImageView>(R.id.mobile_in)
        val activityOut = view.requireViewById<ImageView>(R.id.mobile_out)
        val networkTypeView = view.requireViewById<ImageView>(R.id.mobile_type)
        val networkTypeContainer = view.requireViewById<FrameLayout>(R.id.mobile_type_container)
        val iconView = view.requireViewById<ImageView>(R.id.mobile_signal)
        val roamingView = view.requireViewById<ImageView>(R.id.mobile_roaming)
        val roamingSpace = view.requireViewById<Space>(R.id.mobile_roaming_space)
        val dotView = view.requireViewById<StatusBarIconView>(R.id.status_bar_dot)

        val isVisible = viewModel.isVisible.sample()

        var lastCellularIconKairos: SignalIconModel.Cellular? = null
        val refreshCallbackKairos = Runnable {
            val icon = lastCellularIconKairos ?: return@Runnable
            val themed = ThemeIconController
                .getThemedSignalIcon(view.context, icon.level, icon.numberOfLevels)
            if (themed != null) {
                iconView.setImageDrawable(themed)
            } else {
                iconView.setImageDrawable(mobileDrawable)
                mobileDrawable.level = icon.toSignalDrawableState()
            }
            mobileGroupView.invalidate()
        }
        ThemeIconController.registerRefreshCallback(refreshCallbackKairos)

        effect(name = nameTag("MobileIconBinderKairos.viewIsVisibleInitEffect")) {
            view.isVisible = isVisible
            iconView.isVisible = true
        }

        repeatWhenAttachedToWindow(
            view,
            nameTag { "MobileIconBinderKairos.repeatWhenAttachedToWindow" },
        ) {
            // isVisible controls the visibility state of the outer group, and thus it needs
            // to run in the CREATED lifecycle so it can continue to watch while invisible
            // See (b/291031862) for details
            viewModel.isVisible.observe(name = nameTag { "MobileIconBinderKairos.isVisible" }) {
                isVisible ->
                viewModel.verboseLogger?.logBinderReceivedVisibility(
                    view,
                    viewModel.subscriptionId,
                    isVisible,
                )
                view.isVisible = isVisible
                // [StatusIconContainer] can get out of sync sometimes. Make sure to
                // request another layout when this changes.
                view.requestLayout()
            }
        }

        repeatWhenWindowIsVisible(
            view,
            nameTag { "MobileIconBinderKairos.repeatWhenWindowIsVisible" },
        ) {
            logger.logCollectionStarted(view, viewModel)
            binding.isCollecting = true
            launchEffect(name = nameTag { "MobileIconBinderKairos.bindingIsCollectingEffect" }) {
                try {
                    awaitCancellation()
                } finally {
                    binding.isCollecting = false
                    logger.logCollectionStopped(view, viewModel)
                    ThemeIconController.unregisterRefreshCallback(
                        refreshCallbackKairos)
                }
            }

            binding.visibility.observe(
                name = nameTag { "MobileIconBinderKairos.setVisibilityState" }
            ) { state ->
                ModernStatusBarViewVisibilityHelper.setVisibilityState(
                    state,
                    mobileGroupView,
                    dotView,
                )
                view.requestLayout()
            }

            // Set the icon for the triangle
            viewModel.icon.pairwise(initialPreviousValue = null).observe(
                name = nameTag { "MobileIconBinderKairos.setIcon" }
            ) { (oldIcon, newIcon) ->
                val shouldRequestLayout =
                    when {
                        oldIcon == null -> true
                        oldIcon is SignalIconModel.Cellular &&
                            newIcon is SignalIconModel.Cellular ->
                            oldIcon.numberOfLevels != newIcon.numberOfLevels

                        else -> false
                    }
                if (newIcon is SignalIconModel.Cellular) {
                    lastCellularIconKairos = newIcon
                    val packedSignalDrawableState = newIcon.toSignalDrawableState()
                    viewModel.verboseLogger?.logBinderReceivedSignalCellularIcon(
                        parentView = view,
                        subId = viewModel.subscriptionId,
                        icon = newIcon,
                        packedSignalDrawableState = packedSignalDrawableState,
                        shouldRequestLayout = shouldRequestLayout,
                    )
                    val themedDrawable = ThemeIconController
                        .getThemedSignalIcon(
                            view.context,
                            newIcon.level,
                            newIcon.numberOfLevels
                        )
                    if (themedDrawable != null) {
                        iconView.setImageDrawable(themedDrawable)
                    } else {
                        iconView.setImageDrawable(mobileDrawable)
                        mobileDrawable.level = packedSignalDrawableState
                    }
                    viewModel.verboseLogger?.logBinderSignalIconResult(
                        parentView = view,
                        subId = viewModel.subscriptionId,
                        unpackedLevel = mobileDrawable.unpackLevel(),
                    )
                } else if (newIcon is SignalIconModel.Satellite) {
                    viewModel.verboseLogger?.logBinderReceivedSignalSatelliteIcon(
                        parentView = view,
                        subId = viewModel.subscriptionId,
                        icon = newIcon,
                    )
                    IconViewBinder.bind(newIcon.icon, iconView)
                }
                if (shouldRequestLayout) {
                    iconView.requestLayout()
                }
            }

            viewModel.contentDescription.observe(
                name = nameTag { "MobileIconBinderKairos.bindContentDescription" }
            ) {
                MobileContentDescriptionViewBinder.bind(it, view)
            }

            // Set the network type icon
            viewModel.networkTypeIcon.observe(
                name = nameTag { "MobileIconBinderKairos.networkTypeIcon" }
            ) { dataTypeId ->
                viewModel.verboseLogger?.logBinderReceivedNetworkTypeIcon(
                    view,
                    viewModel.subscriptionId,
                    dataTypeId,
                )
                dataTypeId?.let { IconViewBinder.bind(dataTypeId, networkTypeView) }
                val prevVis = networkTypeContainer.visibility
                networkTypeContainer.visibility =
                    if (dataTypeId != null) View.VISIBLE else View.GONE

                if (prevVis != networkTypeContainer.visibility) {
                    view.requestLayout()
                }
            }

            // Set the network type background and tint
            viewModel.networkTypeBackground.observe(
                name = nameTag { "MobileIconBinderKairos.networkTypeBackground" }
            ) { background ->
                networkTypeContainer.setBackgroundResource(background?.resId ?: 0)
            }

            combine(viewModel.networkTypeBackground, binding.iconTint) { background, colors ->
                    Pair(background != null, colors)
                }
                .observe(name = nameTag { "MobileIconBinderKairos.networkTypeTint" }) {
                    (hasBackground, colors) ->
                    val tint = ColorStateList.valueOf(colors.tint)
                    val contrast = ColorStateList.valueOf(colors.contrast)
                    iconView.imageTintList = tint
                    // Tint will invert when this bit changes
                    if (hasBackground) {
                        networkTypeContainer.backgroundTintList = tint
                        networkTypeView.imageTintList = contrast
                    } else {
                        networkTypeView.imageTintList = tint
                    }
                    roamingView.imageTintList = tint
                    activityIn.imageTintList = tint
                    activityOut.imageTintList = tint
                    dotView.setDecorColor(colors.tint)
                }

            // Set the roaming indicator
            viewModel.roaming.observe(name = nameTag { "MobileIconBinderKairos.roaming" }) {
                isRoaming ->
                roamingView.isVisible = isRoaming
                roamingSpace.isVisible = isRoaming
            }

            if (Flags.statusBarStaticInoutIndicators()) {
                // Set the opacity of the activity indicators
                viewModel.activityInVisible.observe(
                    name = nameTag { "MobileIconBinderKairos.activityInVisible" }
                ) { visible ->
                    activityIn.imageAlpha =
                        (if (visible) StatusBarViewBinderConstants.ALPHA_ACTIVE
                        else StatusBarViewBinderConstants.ALPHA_INACTIVE)
                }
                viewModel.activityOutVisible.observe(
                    name = nameTag { "MobileIconBinderKairos.activityOutVisible" }
                ) { visible ->
                    activityOut.imageAlpha =
                        (if (visible) StatusBarViewBinderConstants.ALPHA_ACTIVE
                        else StatusBarViewBinderConstants.ALPHA_INACTIVE)
                }
            } else {
                // Set the activity indicators
                viewModel.activityInVisible.observe(
                    name = nameTag { "MobileIconBinderKairos.activityInVisible" }
                ) {
                    activityIn.isVisible = it
                }
                viewModel.activityOutVisible.observe(
                    name = nameTag { "MobileIconBinderKairos.activityOutVisible" }
                ) {
                    activityOut.isVisible = it
                }
            }

            viewModel.activityContainerVisible.observe(
                name = nameTag { "MobileIconBinderKairos.activityContainerVisible" }
            ) {
                activityContainer.isVisible = it
            }

            binding.decorTint.observe(name = nameTag { "MobileIconBinderKairos.decorTint" }) { tint
                ->
                dotView.setDecorColor(tint)
            }
        }
    }
}
