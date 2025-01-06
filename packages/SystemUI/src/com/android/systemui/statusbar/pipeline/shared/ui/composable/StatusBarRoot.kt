/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.composable

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.theme.PlatformTheme
import com.android.compose.theme.colorAttr
import com.android.keyguard.AlphaOptimizedLinearLayout
import com.android.systemui.Flags
import com.android.systemui.clock.ui.viewmodel.AmPmStyle
import com.android.systemui.clock.ui.viewmodel.ClockViewModel
import com.android.systemui.compose.modifiers.sysUiResTagContainer
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule.POPUP
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ui.composable.VariableDayDate
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.OngoingActionProgress
import com.android.systemui.statusbar.StatusBarAlwaysUseRegionSampling
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.chips.ui.compose.OngoingActivityChips
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.core.RudimentaryBattery
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.core.StatusBarForDesktop
import com.android.systemui.statusbar.events.domain.interactor.SystemStatusEventAnimationInteractor
import com.android.systemui.statusbar.featurepods.popups.StatusBarPopupChips
import com.android.systemui.statusbar.featurepods.popups.ui.compose.StatusBarPopupChipsContainer
import com.android.systemui.statusbar.layout.ui.viewmodel.AppHandlesViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.ConnectedDisplaysStatusBarNotificationIconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.PhoneStatusBarView
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.phone.ui.DarkIconManager
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithChargeStatus
import com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithPercent
import com.android.systemui.statusbar.pipeline.battery.ui.composable.ShowPercentMode
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarTouchExclusionRegionBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.view.SystemStatusIconsLayoutHelper
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel.HomeStatusBarViewModelFactory
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIcons
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconsViewModel
import com.android.systemui.statusbar.ui.viewmodel.StatusBarRegionSamplingViewModel
import com.android.systemui.util.boundsOnScreen
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect

/** Factory to simplify the dependency management for [StatusBarRoot] */
@PerDisplaySingleton
class StatusBarRootFactory
@Inject
constructor(
    private val notificationIconsBinder: NotificationIconContainerStatusBarViewBinder,
    private val iconViewStoreFactory: ConnectedDisplaysStatusBarNotificationIconViewStore.Factory,
    private val clockViewModelFactory: ClockViewModel.Factory,
    private val darkIconManagerFactory: DarkIconManager.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val iconController: StatusBarIconController,
    private val ongoingCallController: OngoingCallController,
    private val eventAnimationInteractor: SystemStatusEventAnimationInteractor,
    private val mediaHierarchyManager: MediaHierarchyManager,
    @Named(POPUP) private val mediaHost: MediaHost,
    private val mediaViewModelFactory: MediaViewModel.Factory,
    @DisplayAware private val darkIconDispatcher: DarkIconDispatcher,
    @DisplayAware private val homeStatusBarViewBinder: HomeStatusBarViewBinder,
    @DisplayAware private val homeStatusBarViewModelFactory: HomeStatusBarViewModelFactory,
    private val statusBarRegionSamplingViewModelFactory: StatusBarRegionSamplingViewModel.Factory,
    private val notificationListener: NotificationListener,
    private val keyguardStateController: KeyguardStateController,
    private val headsUpManager: HeadsUpManager,
    private val vibrator: VibratorHelper,
) {
    fun create(root: ViewGroup, andThen: (ViewGroup) -> Unit): ComposeView {
        val composeView = ComposeView(root.context)
        composeView.apply {
            setContent {
                PlatformTheme {
                    StatusBarRoot(
                        parent = root,
                        statusBarViewModelFactory = homeStatusBarViewModelFactory,
                        statusBarViewBinder = homeStatusBarViewBinder,
                        notificationIconsBinder = notificationIconsBinder,
                        iconViewStoreFactory = iconViewStoreFactory,
                        clockViewModelFactory = clockViewModelFactory,
                        darkIconManagerFactory = darkIconManagerFactory,
                        tintedIconManagerFactory = tintedIconManagerFactory,
                        iconController = iconController,
                        ongoingCallController = ongoingCallController,
                        darkIconDispatcher = darkIconDispatcher,
                        eventAnimationInteractor = eventAnimationInteractor,
                        mediaHierarchyManager = mediaHierarchyManager,
                        mediaHost = mediaHost,
                        mediaViewModelFactory = mediaViewModelFactory,
                        statusBarRegionSamplingViewModelFactory =
                            statusBarRegionSamplingViewModelFactory,
                        onViewCreated = andThen,
                        notificationListener = notificationListener,
                        keyguardStateController = keyguardStateController,
                        headsUpManager = headsUpManager,
                        vibrator = vibrator,
                        modifier = Modifier.sysUiResTagContainer(),
                    )
                }
            }
        }

        return composeView
    }
}

/**
 * For now, this class exists only to replace the former CollapsedStatusBarFragment. We simply stand
 * up the PhoneStatusBarView here (allowing the component to be initialized from the [init] block).
 * This is the place, for now, where we can manually set up lingering dependencies that came from
 * the fragment until we can move them to recommended-arch style repos.
 *
 * @param onViewCreated called immediately after the view is inflated, and takes as a parameter the
 *   newly-inflated PhoneStatusBarView. This lambda is useful for tying together old initialization
 *   logic until it can be replaced.
 */
@Composable
fun StatusBarRoot(
    parent: ViewGroup,
    statusBarViewModelFactory: HomeStatusBarViewModelFactory,
    statusBarViewBinder: HomeStatusBarViewBinder,
    notificationIconsBinder: NotificationIconContainerStatusBarViewBinder,
    iconViewStoreFactory: ConnectedDisplaysStatusBarNotificationIconViewStore.Factory,
    clockViewModelFactory: ClockViewModel.Factory,
    darkIconManagerFactory: DarkIconManager.Factory,
    tintedIconManagerFactory: TintedIconManager.Factory,
    iconController: StatusBarIconController,
    ongoingCallController: OngoingCallController,
    darkIconDispatcher: DarkIconDispatcher,
    eventAnimationInteractor: SystemStatusEventAnimationInteractor,
    mediaHierarchyManager: MediaHierarchyManager,
    mediaHost: MediaHost,
    mediaViewModelFactory: MediaViewModel.Factory,
    statusBarRegionSamplingViewModelFactory: StatusBarRegionSamplingViewModel.Factory,
    onViewCreated: (ViewGroup) -> Unit,
    notificationListener: NotificationListener,
    keyguardStateController: KeyguardStateController,
    headsUpManager: HeadsUpManager,
    vibrator: VibratorHelper,
    modifier: Modifier = Modifier,
) {
    val displayId = parent.context.displayId
    val statusBarViewModel =
        rememberViewModel("HomeStatusBar") { statusBarViewModelFactory.create() }
    val iconViewStore: NotificationIconContainerViewBinder.IconViewStore? =
        if (StatusBarConnectedDisplays.isEnabled) {
            rememberViewModel("HomeStatusBar.IconViewStore[$displayId]") {
                iconViewStoreFactory.create(displayId)
            }
        } else {
            null
        }
    val appHandlesViewModel =
        rememberViewModel("AppHandleBounds") {
            statusBarViewModel.appHandlesViewModelFactory.create(displayId)
        }
    var touchableExclusionRegionDisposableHandle: DisposableHandle? = null

    if (StatusBarPopupChips.isEnabled) {
        with(mediaHost) {
            expansion = MediaHostState.EXPANDED
            expandedMatchesParentHeight = true
            showsOnlyActiveMedia = true
            falsingProtectionNeeded = false
            disableScrolling = true
            init(MediaHierarchyManager.LOCATION_STATUS_BAR_POPUP)
        }
    }

    // Let the DesktopStatusBar compose all the UI if [useDesktopStatusBar] is true.
    if (StatusBarForDesktop.isEnabled && statusBarViewModel.useDesktopStatusBar) {
        DesktopStatusBar(
            viewModel = statusBarViewModel,
            clockViewModelFactory = clockViewModelFactory,
            statusBarIconController = iconController,
            iconManagerFactory = tintedIconManagerFactory,
            mediaHierarchyManager = mediaHierarchyManager,
            mediaViewModelFactory = mediaViewModelFactory,
            mediaHost = mediaHost,
            iconViewStore = iconViewStore,
        )
        return
    }

    Box { // TODO(b/433578931): Remove this Box once the full solution for b/433578931 is settled.
        AndroidView(
            factory = { context ->
                val inflater = LayoutInflater.from(context)
                val phoneStatusBarView =
                    inflater.inflate(R.layout.status_bar, parent, false) as PhoneStatusBarView

                if (StatusBarChipsModernization.isEnabled) {
                    addStartSideComposable(
                        phoneStatusBarView = phoneStatusBarView,
                        clockViewModelFactory = clockViewModelFactory,
                        statusBarViewModel = statusBarViewModel,
                        iconViewStore = iconViewStore,
                        appHandlesViewModel = appHandlesViewModel,
                        notificationListener = notificationListener,
                        keyguardStateController = keyguardStateController,
                        headsUpManager = headsUpManager,
                        vibrator = vibrator,
                        context = context,
                    )
                }

                touchableExclusionRegionDisposableHandle =
                    HomeStatusBarTouchExclusionRegionBinder.bind(
                        phoneStatusBarView,
                        appHandlesViewModel,
                    )

                if (StatusBarChipsModernization.isEnabled) {
                    // Make sure the primary chip is hidden when StatusBarChipsModernization is
                    // enabled. OngoingActivityChips will be shown in a composable container
                    // when this flag is enabled.
                    phoneStatusBarView
                        .requireViewById<View>(R.id.ongoing_activity_chip_primary)
                        .visibility = View.GONE
                } else {
                    ongoingCallController.setChipView(
                        phoneStatusBarView.requireViewById(R.id.ongoing_activity_chip_primary)
                    )
                }

                // For notifications, first inflate the [NotificationIconContainer]
                val notificationIconArea =
                    phoneStatusBarView.requireViewById<ViewGroup>(R.id.notification_icon_area)
                inflater.inflate(R.layout.notification_icon_area, notificationIconArea, true)
                // Then bind it using the icons binder
                val notificationIconContainer =
                    phoneStatusBarView.requireViewById<NotificationIconContainer>(
                        R.id.notificationIcons
                    )

                // Add a composable container for `StatusBarPopupChip`s
                if (StatusBarPopupChips.isEnabled) {
                    val endSideContent =
                        phoneStatusBarView.requireViewById<AlphaOptimizedLinearLayout>(
                            R.id.status_bar_end_side_content
                        )

                    val composeView =
                        ComposeView(context).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                )

                            setViewCompositionStrategy(
                                if (SceneContainerFlag.isEnabled) {
                                    ViewCompositionStrategy.Default
                                } else {
                                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                                }
                            )

                            setContent {
                                StatusBarPopupChipsContainer(
                                    chips = statusBarViewModel.popupChips,
                                    mediaViewModelFactory = mediaViewModelFactory,
                                    mediaHost = mediaHost,
                                    onMediaControlPopupVisibilityChanged = { popupShowing ->
                                        mediaHierarchyManager.isMediaControlPopupShowing =
                                            popupShowing
                                    },
                                )
                            }
                        }
                    endSideContent.addView(composeView, 0)
                }

                // If the flag is enabled, create and add a compose section to the end
                // of the system_icons container
                if (SystemStatusIconsInCompose.isEnabled) {
                    phoneStatusBarView.requireViewById<View>(R.id.system_icons).visibility =
                        View.GONE
                    addEndSideComposable(phoneStatusBarView, statusBarViewModel)
                } else {
                    val statusIconContainer =
                        phoneStatusBarView.requireViewById<StatusIconContainer>(R.id.statusIcons)
                    val darkIconManager =
                        darkIconManagerFactory.create(
                            statusIconContainer,
                            StatusBarLocation.HOME,
                            darkIconDispatcher,
                        )
                    iconController.addIconGroup(darkIconManager)

                    HomeStatusBarIconBlockListBinder.bind(
                        statusIconContainer,
                        darkIconManager,
                        statusBarViewModel.iconBlockList,
                    )

                    if (NewStatusBarIcons.isEnabled) {
                        addBatteryComposable(phoneStatusBarView, statusBarViewModel)
                        // Also adjust the paddings :)
                        SystemStatusIconsLayoutHelper.configurePaddingForNewStatusBarIcons(
                            phoneStatusBarView.requireViewById(R.id.statusIcons)
                        )
                    }
                }

                notificationIconsBinder.bindWhileAttached(
                    notificationIconContainer,
                    context.displayId,
                )

                if (StatusBarAlwaysUseRegionSampling.isAnyRegionSamplingEnabled) {
                    bindRegionSamplingViewModel(
                        context.displayId,
                        phoneStatusBarView,
                        statusBarRegionSamplingViewModelFactory,
                    )
                }

                // This binder handles everything else
                statusBarViewBinder.bind(
                    context.displayId,
                    phoneStatusBarView,
                    statusBarViewModel,
                    eventAnimationInteractor::animateStatusBarContentForChipEnter,
                    eventAnimationInteractor::animateStatusBarContentForChipExit,
                    listener = null,
                )
                onViewCreated(phoneStatusBarView)
                phoneStatusBarView
            },
            modifier = modifier,
            onRelease = { touchableExclusionRegionDisposableHandle?.dispose() },
        )
    }
}

/** Adds the composable chips shown on the start side of the status bar. */
private fun addStartSideComposable(
    phoneStatusBarView: PhoneStatusBarView,
    clockViewModelFactory: ClockViewModel.Factory,
    statusBarViewModel: HomeStatusBarViewModel,
    iconViewStore: NotificationIconContainerViewBinder.IconViewStore?,
    appHandlesViewModel: AppHandlesViewModel,
    notificationListener: NotificationListener,
    keyguardStateController: KeyguardStateController,
    headsUpManager: HeadsUpManager,
    vibrator: VibratorHelper,
    context: Context,
) {
    val startSideExceptHeadsUp =
        phoneStatusBarView.requireViewById<LinearLayout>(R.id.status_bar_start_side_except_heads_up)
    val startSideContainerView =
        phoneStatusBarView.requireViewById<View>(R.id.status_bar_start_side_container)
    val clockView = phoneStatusBarView.requireViewById<Clock>(R.id.clock)

    val composeView =
        ComposeView(context).apply {
            val showDate = Flags.statusBarDate() && statusBarViewModel.useDesktopStatusBar

            layoutParams =
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }

            setContent {
                val statusBarBoundsViewModel =
                    rememberViewModel("HomeStatusBar.Bounds") {
                        statusBarViewModel.statusBarBoundsViewModelFactory.create(
                            startSideContainerView = startSideContainerView,
                            clockView = clockView,
                        )
                    }

                if (showDate) {
                    val clockViewModel =
                        rememberViewModel("HomeStatusBar.Clock") {
                            clockViewModelFactory.create(AmPmStyle.Gone)
                        }
                    VariableDayDate(
                        longerDateText = clockViewModel.longerDateText,
                        shorterDateText = clockViewModel.shorterDateText,
                        textColor = colorAttr(R.attr.wallpaperTextColor),
                        modifier =
                            Modifier.padding(horizontal = 4.dp)
                                .wrapContentSize()
                                .onGloballyPositioned { coordinates ->
                                    val boundsInWindow = coordinates.boundsInWindow()
                                    val bounds =
                                        Rect(
                                            boundsInWindow.left.toInt(),
                                            boundsInWindow.top.toInt(),
                                            boundsInWindow.right.toInt(),
                                            boundsInWindow.bottom.toInt(),
                                        )
                                    statusBarBoundsViewModel.updateDateBounds(bounds)
                                },
                    )
                }

                val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val density = context.resources.displayMetrics.density

                val chipsMaxWidth: Dp =
                    remember(
                        appHandlesViewModel.appHandleBounds,
                        statusBarBoundsViewModel.startSideContainerBounds,
                        statusBarBoundsViewModel.dateBounds,
                        statusBarBoundsViewModel.clockBounds,
                        isRtl,
                        density,
                    ) {
                        chipsMaxWidth(
                            appHandles = appHandlesViewModel.appHandleBounds,
                            startSideContainerBounds =
                                statusBarBoundsViewModel.startSideContainerBounds,
                            dateBounds = statusBarBoundsViewModel.dateBounds,
                            clockBounds = statusBarBoundsViewModel.clockBounds,
                            isRtl = isRtl,
                            density = density,
                        )
                    }

                val progressController = remember {
                    com.android.systemui.statusbar.OnGoingActionProgressComposeController(
                        context,
                        notificationListener,
                        keyguardStateController,
                        headsUpManager,
                        vibrator
                    )
                }

                val chipsVisibilityModel = statusBarViewModel.ongoingActivityChips
                val hasSystemChips = chipsVisibilityModel.chips.active.isNotEmpty()
                progressController.setSystemChipVisible(hasSystemChips)

                OngoingActionProgress(controller = progressController)

                if (chipsVisibilityModel.areChipsAllowed) {
                    OngoingActivityChips(
                        chips = chipsVisibilityModel.chips,
                        iconViewStore = iconViewStore,
                        onChipBoundsChanged = statusBarViewModel::onChipBoundsChanged,
                        // TODO(b/393581408): Now that we always enforce a max width on the chips,
                        //  we should be able to convert the chips to a LazyRow and get some
                        //  animations for free.
                        modifier = Modifier.sysUiResTagContainer().widthIn(max = chipsMaxWidth),
                    )
                }
            }
        }

    // Add the composable container for ongoingActivityChips before the
    // notification_icon_area to maintain the same ordering for ongoing activity
    // chips in the status bar layout.
    val notificationIconAreaIndex =
        startSideExceptHeadsUp.indexOfChild(
            startSideExceptHeadsUp.findViewById(R.id.notification_icon_area)
        )
    startSideExceptHeadsUp.addView(composeView, notificationIconAreaIndex)
}

@VisibleForTesting
fun chipsMaxWidth(
    appHandles: List<Rect>,
    startSideContainerBounds: Rect,
    dateBounds: Rect,
    clockBounds: Rect,
    isRtl: Boolean,
    density: Float,
): Dp {
    val relevantAppHandles =
        appHandles
            .filterNot { it.isEmpty }
            // Only care about app handles in the same possible region as the chips
            .filter { Rect.intersects(it, startSideContainerBounds) }

    // The chips should be next to the date if it is showing, otherwise they should be next to the
    // clock.
    val clockOrDateBounds = if (dateBounds.isEmpty) clockBounds else dateBounds

    val widthInPx =
        if (isRtl) {
                val chipsLeftBasedOnAppHandles =
                    relevantAppHandles.maxOfOrNull { it.right } ?: Int.MIN_VALUE
                val chipsLeftBasedOnContainer = startSideContainerBounds.left
                val chipsLeft = maxOf(chipsLeftBasedOnAppHandles, chipsLeftBasedOnContainer)
                /* width= */ clockOrDateBounds.left - chipsLeft
            } else { // LTR
                val chipsRightBasedOnAppHandles =
                    relevantAppHandles.minOfOrNull { it.left } ?: Int.MAX_VALUE
                val chipsRightBasedOnContainer = startSideContainerBounds.right
                val chipsRight = minOf(chipsRightBasedOnAppHandles, chipsRightBasedOnContainer)
                /* width= */ chipsRight - clockOrDateBounds.right
            }
            .coerceAtLeast(0)

    return (widthInPx / density).dp
}

private const val SLOT_BATTERY = "battery"

/** Create a new [UnifiedBattery] and add it to the end of the system_icons container */
private fun addBatteryComposable(
    phoneStatusBarView: PhoneStatusBarView,
    statusBarViewModel: HomeStatusBarViewModel,
) {
    val batteryComposeView =
        ComposeView(phoneStatusBarView.context).apply {
            setContent {
                if (RudimentaryBattery.isEnabled) {
                    BatteryWithChargeStatus(
                        viewModelFactory = statusBarViewModel.batteryNextToPercentViewModel,
                        isDarkProvider = { statusBarViewModel.areaDark },
                        showPercentMode = ShowPercentMode.FollowSetting,
                        modifier = Modifier.sysUiResTagContainer().wrapContentSize(),
                    )
                } else {
                    val height =
                        with(LocalDensity.current) {
                            BatteryViewModel.getStatusBarBatteryHeight(LocalContext.current).toDp()
                        }
                    val viewModel =
                        rememberViewModel(traceName = "UnifiedBattery") {
                            statusBarViewModel.unifiedBatteryViewModel.create()
                        }
                    BatteryWithPercent(
                        modifier =
                            Modifier.sysUiResTagContainer().wrapContentWidth(),
                        viewModel = viewModel,
                        isDarkProvider = { statusBarViewModel.areaDark },
                        showPercent = viewModel.isBatteryPercentSettingEnabled,
                    )
                }
            }
        }
    phoneStatusBarView.findViewById<ViewGroup>(R.id.system_icons).apply {
        addView(batteryComposeView, -1)
    }

    batteryComposeView.repeatWhenAttached {
        statusBarViewModel.iconBlockList
            .map { blocked -> blocked.contains(SLOT_BATTERY) }
            .distinctUntilChanged()
            .collect { isBlocked ->
                batteryComposeView.visibility = if (isBlocked) View.GONE else View.VISIBLE
            }
    }
}

/**
 * Create a composable that will replace the status_bar_end_side_content. This is added to the end
 * of the status_bar_end_side_container
 */
private fun addEndSideComposable(
    phoneStatusBarView: PhoneStatusBarView,
    statusBarViewModel: HomeStatusBarViewModel,
) {
    val endSideContainerView =
        phoneStatusBarView.requireViewById<View>(R.id.status_bar_end_side_container)
    val systemStatusIconsComposeView =
        ComposeView(phoneStatusBarView.context).apply {
            setContent {
                val endSideWidth by rememberViewWidthAsState(endSideContainerView)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier.widthIn(max = with(LocalDensity.current) { endSideWidth.toDp() }),
                ) {
                    SystemStatusIconsContainer(
                        viewModelFactory = statusBarViewModel.systemStatusIconsViewModelFactory,
                        isDark = statusBarViewModel.areaDark,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (RudimentaryBattery.isEnabled) {
                        BatteryWithChargeStatus(
                            viewModelFactory = statusBarViewModel.batteryNextToPercentViewModel,
                            isDarkProvider = { statusBarViewModel.areaDark },
                            showPercentMode = ShowPercentMode.FollowSetting,
                            modifier = Modifier.sysUiResTagContainer().wrapContentSize(),
                        )
                    } else {
                        val height =
                            with(LocalDensity.current) {
                                BatteryViewModel.getStatusBarBatteryHeight(LocalContext.current)
                                    .toDp()
                            }
                        val viewModel =
                            rememberViewModel(traceName = "UnifiedBattery") {
                                statusBarViewModel.unifiedBatteryViewModel.create()
                            }
                        BatteryWithPercent(
                            viewModel = viewModel,
                            isDarkProvider = { statusBarViewModel.areaDark },
                            modifier =
                                Modifier.sysUiResTagContainer().wrapContentWidth(),
                            showPercent = viewModel.isBatteryPercentSettingEnabled,
                        )
                    }
                }
            }
        }

    phoneStatusBarView.findViewById<ViewGroup>(R.id.status_bar_end_side_content).apply {
        addView(systemStatusIconsComposeView)
    }
}

@Composable
private fun SystemStatusIconsContainer(
    viewModelFactory: SystemStatusIconsViewModel.Factory,
    isDark: IsAreaDark,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(Rect()) }
    val tint = if (isDark.isDarkTheme(bounds)) Color.White else Color.Black
    SystemStatusIcons(
        viewModelFactory = viewModelFactory,
        tint = tint,
        modifier =
            modifier.onLayoutRectChanged { relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
    )
}

private fun bindRegionSamplingViewModel(
    displayId: Int,
    phoneStatusBarView: PhoneStatusBarView,
    statusBarRegionSamplingViewModelFactory: StatusBarRegionSamplingViewModel.Factory,
) {
    phoneStatusBarView.repeatWhenAttached {
        phoneStatusBarView.viewModel(
            traceName = "StatusBarRegionSamplingViewModel",
            minWindowLifecycleState = WindowLifecycleState.ATTACHED,
            factory = {
                statusBarRegionSamplingViewModelFactory.create(
                    displayId = displayId,
                    attachStateView = phoneStatusBarView,
                    startSideContainerView =
                        phoneStatusBarView.requireViewById(R.id.status_bar_start_side_container),
                    startSideIconView = phoneStatusBarView.requireViewById(R.id.clock),
                    endSideContainerView =
                        phoneStatusBarView.requireViewById(R.id.status_bar_end_side_container),
                    endSideIconView = phoneStatusBarView.requireViewById(R.id.system_icons),
                )
            },
        ) {
            awaitCancellation()
        }
    }
}

/**
 * Tracks the width of a given [view] in pixels and returns it as a [MutableIntState]. The state is
 * updated whenever the view's layout changes.
 */
@Composable
private fun rememberViewWidthAsState(view: View): MutableIntState {
    val viewWidth = remember(view) { mutableIntStateOf(view.boundsOnScreen.width()) }

    DisposableEffect(view) {
        val layoutListener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                viewWidth.intValue = view.boundsOnScreen.width()
            }
        view.addOnLayoutChangeListener(layoutListener)

        onDispose { view.removeOnLayoutChangeListener(layoutListener) }
    }
    return viewWidth
}
