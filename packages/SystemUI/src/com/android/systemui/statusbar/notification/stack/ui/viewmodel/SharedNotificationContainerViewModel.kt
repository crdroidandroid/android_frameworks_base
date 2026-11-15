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
 *
 */

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.WindowInsets.Type.defaultVisible
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.Alignment
import com.android.app.tracing.coroutines.flow.flowName
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.Scale
import com.android.systemui.Flags.bouncerUiRevamp
import com.android.systemui.Flags.glanceableHubV2
import com.android.systemui.Flags.notificationShadeBlur
import com.android.systemui.biometrics.Utils.getInsetsOf
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE_LOCKED
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OffToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ToAodEndStateTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ToDozingEndStateTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ToLockscreenEndStateTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.notifications.ui.NotificationPlaceholderStateStorage
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode.Dual
import com.android.systemui.shade.shared.model.ShadeMode.Single
import com.android.systemui.shade.shared.model.ShadeMode.Split
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.logging.dagger.NotificationAlphaTableLog
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.state.ObservableState
import com.android.systemui.util.state.SynchronouslyObservableState
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import com.android.systemui.utils.coroutines.flow.transformLatestConflated
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive

/** View-model for the shared notification container, used by both the shade and keyguard spaces. */
@SuppressLint("FlowExposedFromViewModel") // because all flows from this class are bound to Views
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class SharedNotificationContainerViewModel
@Inject
constructor(
    private val interactor: SharedNotificationContainerInteractor,
    dumpManager: DumpManager,
    @Application applicationScope: CoroutineScope,
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    communalInteractor: CommunalInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val sceneInteractor: SceneInteractor,
    bouncerInteractor: BouncerInteractor,
    shadeModeInteractor: ShadeModeInteractor,
    notificationStackAppearanceInteractor: NotificationStackAppearanceInteractor,
    private val alternateBouncerToGoneTransitionViewModel:
        AlternateBouncerToGoneTransitionViewModel,
    private val alternateBouncerToPrimaryBouncerTransitionViewModel:
        AlternateBouncerToPrimaryBouncerTransitionViewModel,
    private val aodToGoneTransitionViewModel: AodToGoneTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val aodToOccludedTransitionViewModel: AodToOccludedTransitionViewModel,
    private val aodToGlanceableHubTransitionViewModel: AodToGlanceableHubTransitionViewModel,
    private val aodToPrimaryBouncerTransitionViewModel: AodToPrimaryBouncerTransitionViewModel,
    dozingToGlanceableHubTransitionViewModel: DozingToGlanceableHubTransitionViewModel,
    private val dozingToGoneTransitionViewModel: DozingToGoneTransitionViewModel,
    private val dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    private val dozingToOccludedTransitionViewModel: DozingToOccludedTransitionViewModel,
    private val dozingToPrimaryBouncerTransitionViewModel:
        DozingToPrimaryBouncerTransitionViewModel,
    private val dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val glanceableHubToLockscreenTransitionViewModel:
        GlanceableHubToLockscreenTransitionViewModel,
    private val glanceableHubToAodTransitionViewModel: GlanceableHubToAodTransitionViewModel,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    private val goneToDreamingTransitionViewModel: GoneToDreamingTransitionViewModel,
    private val goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel,
    private val lockscreenToDreamingTransitionViewModel: LockscreenToDreamingTransitionViewModel,
    private val lockscreenToGlanceableHubTransitionViewModel:
        LockscreenToGlanceableHubTransitionViewModel,
    private val lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    private val lockscreenToPrimaryBouncerTransitionViewModel:
        LockscreenToPrimaryBouncerTransitionViewModel,
    private val lockscreenToOccludedTransitionViewModel: LockscreenToOccludedTransitionViewModel,
    private val occludedToAodTransitionViewModel: OccludedToAodTransitionViewModel,
    private val occludedToGoneTransitionViewModel: OccludedToGoneTransitionViewModel,
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    private val offToLockscreenTransitionViewModel: OffToLockscreenTransitionViewModel,
    private val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    private val primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel,
    private val toLockscreenEndStateTransitionViewModel: ToLockscreenEndStateTransitionViewModel,
    private val toAodEndStateTransitionViewModel: ToAodEndStateTransitionViewModel,
    private val toDozingEndStateTransitionViewModel: ToDozingEndStateTransitionViewModel,
    private val primaryBouncerTransitions: Set<@JvmSuppressWildcards PrimaryBouncerTransition>,
    aodBurnInViewModel: AodBurnInViewModel,
    private val communalSceneInteractor: CommunalSceneInteractor,
    // Lazy because it's only used in the SceneContainer + Dual Shade configuration.
    headsUpNotificationInteractor: Lazy<HeadsUpNotificationInteractor>,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
    unfoldTransitionInteractor: UnfoldTransitionInteractor,
    val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val mediaDataManager: MediaDataManager,
    notificationPlaceholderStateStorage: NotificationPlaceholderStateStorage,
    @NotificationAlphaTableLog private val alphaTableLogger: TableLogBuffer,
    windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
    private val blurConfig: BlurConfig,
) : FlowDumperImpl(dumpManager) {

    /**
     * Is either shade/qs expanded? This intentionally does not use the [ShadeInteractor] version,
     * as the legacy implementation has extra logic that produces incorrect results.
     */
    private val isAnyExpanded =
        combine(
                shadeInteractor.shadeExpansion.map { it > 0f }.distinctUntilChanged(),
                shadeInteractor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
            ) { shadeExpansion, qsExpansion ->
                shadeExpansion || qsExpansion
            }
            .flowName("isAnyExpanded")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Shade locked is a legacy concept, but necessary to mimic current functionality. Listen for
     * both SHADE_LOCKED and shade/qs expansion in order to determine lock state, as one can arrive
     * before the other.
     */
    private val isShadeLocked: Flow<Boolean> =
        combine(keyguardInteractor.statusBarState.map { it == SHADE_LOCKED }, isAnyExpanded) {
                isShadeLocked,
                isAnyExpanded ->
                isShadeLocked && isAnyExpanded
            }
            .flowName("isShadeLocked")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpWhileCollecting("isShadeLocked")

    @VisibleForTesting
    val paddingTopDimen: Flow<Int> =
        if (SceneContainerFlag.isEnabled) {
                configurationInteractor.onAnyConfigurationChange.map {
                    with(context.resources) {
                        val useLargeScreenHeader =
                            getBoolean(R.bool.config_use_large_screen_shade_header)
                        if (useLargeScreenHeader) {
                            largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
                        } else {
                            getDimensionPixelSize(R.dimen.notification_panel_margin_top)
                        }
                    }
                }
            } else {
                interactor.configurationBasedDimensions.map {
                    when {
                        it.useLargeScreenHeader -> it.marginTopLargeScreen
                        else -> it.marginTop
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("paddingTopDimen")

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        if (SceneContainerFlag.isEnabled) {
                combine(
                    shadeModeInteractor.notificationStackHorizontalAlignment,
                    shadeModeInteractor.shadeMode,
                    shadeModeInteractor.isFullWidthShade,
                    configurationInteractor.onAnyConfigurationChange,
                ) { horizontalAlignment, shadeMode, isFullWidthShade, _ ->
                    with(context.resources) {
                        val marginHorizontal =
                            getDimensionPixelSize(
                                if (shadeMode is Dual) {
                                    R.dimen.shade_panel_margin_horizontal
                                } else {
                                    R.dimen.notification_panel_margin_horizontal
                                }
                            )

                        val (insetStart, insetEnd) =
                            if (shadeMode is Dual && !isFullWidthShade) {
                                // No need to add insets in the "floating" shade design, since
                                // they are already applied to the shade panel (container).
                                0 to 0
                            } else {
                                val isRtl =
                                    configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                                // All inset types combined, except the IME.
                                with(getInsetsOf(context, defaultVisible())) {
                                    if (isRtl) right to left else left to right
                                }
                            }

                        val (marginStart, marginEnd) =
                            if (shadeMode is Single) {
                                marginHorizontal to marginHorizontal
                            } else {
                                when (horizontalAlignment) {
                                    Alignment.Start ->
                                        marginHorizontal.coerceAtLeast(insetStart) to 0

                                    Alignment.End -> 0 to marginHorizontal.coerceAtLeast(insetEnd)
                                    else -> 0 to 0
                                }
                            }

                        val maxWidth =
                            if (shadeMode is Dual) {
                                getDimensionPixelSize(R.dimen.shade_panel_width)
                            } else {
                                Int.MAX_VALUE
                            }

                        val horizontalPosition =
                            when (horizontalAlignment) {
                                Alignment.Start -> HorizontalPosition.EdgeToMiddle(maxWidth)
                                Alignment.End -> HorizontalPosition.MiddleToEdge(maxWidth)
                                else -> HorizontalPosition.EdgeToEdge
                            }

                        ConfigurationBasedDimensions(
                            horizontalPosition = horizontalPosition,
                            marginStart = marginStart,
                            // y position of the NSSL in the window needs to be 0 under scene
                            // container
                            marginTop = 0,
                            marginEnd = marginEnd,
                            marginBottom =
                                getDimensionPixelSize(R.dimen.notification_panel_margin_bottom),
                        )
                    }
                }
            } else {
                interactor.configurationBasedDimensions.map {
                    ConfigurationBasedDimensions(
                        horizontalPosition =
                            if (it.useSplitShade) HorizontalPosition.MiddleToEdge()
                            else HorizontalPosition.EdgeToEdge,
                        marginStart = if (it.useSplitShade) 0 else it.marginHorizontal,
                        marginEnd = it.marginHorizontal,
                        marginBottom = it.marginBottom,
                        marginTop =
                            if (it.useLargeScreenHeader) it.marginTopLargeScreen else it.marginTop,
                    )
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("configurationBasedDimensions")

    private val isOnAnyBouncer: Flow<Boolean> =
        anyOf(
            keyguardTransitionInteractor.transitionValue(ALTERNATE_BOUNCER).map { it > 0f },
            keyguardTransitionInteractor
                .transitionValue(
                    content = Overlays.Bouncer,
                    stateWithoutSceneContainer = PRIMARY_BOUNCER,
                )
                .map { it > 0f },
        )

    /** If the user is visually on one of the unoccluded lockscreen states. */
    val isOnLockscreen: Flow<Boolean> =
        if (glanceableHubV2()) {
                anyOf(
                    keyguardTransitionInteractor.transitionValue(AOD).map { it > 0f },
                    keyguardTransitionInteractor.transitionValue(DOZING).map { it > 0f },
                    keyguardTransitionInteractor.transitionValue(LOCKSCREEN).map { it > 0f },
                    allOf(
                        // Exclude bouncer showing over communal hub, as this should not be
                        // considered
                        // "lockscreen"
                        not(communalSceneInteractor.isCommunalVisible),
                        isOnAnyBouncer,
                    ),
                )
            } else {
                anyOf(
                    keyguardTransitionInteractor.transitionValue(AOD).map { it > 0f },
                    keyguardTransitionInteractor.transitionValue(DOZING).map { it > 0f },
                    keyguardTransitionInteractor.transitionValue(LOCKSCREEN).map { it > 0f },
                    isOnAnyBouncer,
                )
            }
            .flowName("isOnLockscreen")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnLockscreen")

    /** Are we purely on the keyguard without the shade/qs? */
    val isOnLockscreenWithoutShade: Flow<Boolean> =
        combine(isOnLockscreen, isAnyExpanded) { isKeyguard, isAnyExpanded ->
                isKeyguard && !isAnyExpanded
            }
            .flowName("isOnLockscreenWithoutShade")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnLockscreenWithoutShade")

    /** If the user is visually on the glanceable hub or transitioning to/from it */
    private val isOnGlanceableHub: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.isFinishedIn(
                    content = Scenes.Communal,
                    stateWithoutSceneContainer = GLANCEABLE_HUB,
                ),
                anyOf(
                    keyguardTransitionInteractor.isInTransition(
                        edge = Edge.create(to = Scenes.Communal),
                        edgeWithoutSceneContainer = Edge.create(to = GLANCEABLE_HUB),
                    ),
                    keyguardTransitionInteractor.isInTransition(
                        edge = Edge.create(from = Scenes.Communal),
                        edgeWithoutSceneContainer = Edge.create(from = GLANCEABLE_HUB),
                    ),
                ),
            ) { isOnGlanceableHub, transitioningToOrFromHub ->
                isOnGlanceableHub || transitioningToOrFromHub
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isOnGlanceableHub")

    /** Are we purely on the glanceable hub without the shade/qs? */
    val isOnGlanceableHubWithoutShade: Flow<Boolean> =
        combine(isOnGlanceableHub, isAnyExpanded) { isGlanceableHub, isAnyExpanded ->
                isGlanceableHub && !isAnyExpanded
            }
            .flowName("isOnGlanceableHubWithoutShade")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnGlanceableHubWithoutShade")

    /** Are we on the dream without the shade/qs? */
    private val isDreamingWithoutShade: Flow<Boolean> =
        combine(keyguardTransitionInteractor.isFinishedIn(Scenes.Dream, DREAMING), isAnyExpanded) {
                isDreaming,
                isAnyExpanded ->
                isDreaming && !isAnyExpanded
            }
            .flowName("isDreamingWithoutShade")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isDreamingWithoutShade")

    /**
     * Fade in if the user swipes the shade back up, not if collapsed by going to AOD or DREAMING.
     * This is needed due to the lack of a SHADE state with existing keyguard transitions.
     */
    private fun awaitCollapse(): Flow<Boolean> {
        var aodTransitionIsComplete = true
        return combine(
                isOnLockscreenWithoutShade,
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = LOCKSCREEN, to = AOD)
                ),
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = LOCKSCREEN, to = DREAMING)
                ),
                ::Triple,
            )
            .transformWhile {
                (isOnLockscreenWithoutShade, aodTransitionIsRunning, dreamTransitionIsRunning) ->
                // Wait until the AOD transition is complete before terminating
                if (!aodTransitionIsComplete && !aodTransitionIsRunning) {
                    aodTransitionIsComplete = true
                    emit(false) // do not fade in
                    false
                } else if (aodTransitionIsRunning) {
                    aodTransitionIsComplete = false
                    true
                } else if (isOnLockscreenWithoutShade) {
                    // Shade is closed, fade in and terminate
                    emit(true)
                    false
                } else if (dreamTransitionIsRunning) {
                    emit(false)
                    false
                } else {
                    true
                }
            }
    }

    /** Fade in only for use after the shade collapses */
    val shadeCollapseFadeIn: Flow<Boolean> =
        flow {
                while (currentCoroutineContext().isActive) {
                    // Ensure shade is collapsed
                    isShadeLocked.first { !it }
                    emit(false)
                    // Wait for shade to be fully expanded
                    isShadeLocked.first { it }
                    // ... and then for it to be collapsed OR a transition to AOD or DREAMING
                    // begins.
                    // If AOD or DREAMING, do not fade in (a fade out occurs instead).
                    awaitCollapse().collect { doFadeIn ->
                        if (doFadeIn) {
                            emit(true)
                        }
                    }
                }
            }
            .flowName("shadeCollapseFadeIn")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )
            .dumpValue("shadeCollapseFadeIn")

    /**
     * The container occupies the entire screen, and must be positioned relative to other elements.
     *
     * On keyguard, this generally fits below the clock and above the lock icon, or in split shade,
     * the top of the screen to the lock icon.
     *
     * When the shade is expanding, the position is controlled by... the shade.
     */
    val bounds: StateFlow<NotificationContainerBounds> by lazy {
        SceneContainerFlag.assertInLegacyMode()
        combine(
                isOnLockscreenWithoutShade,
                keyguardInteractor.notificationContainerBounds,
                paddingTopDimen,
                interactor.topPosition
                    .sampleCombine(
                        keyguardTransitionInteractor.isInTransition,
                        shadeInteractor.qsExpansion,
                    )
                    .onStart { emit(Triple(0f, false, 0f)) },
            ) { onLockscreen, bounds, paddingTop, (top, isInTransitionToAnyState, qsExpansion) ->
                if (onLockscreen) {
                    bounds.copy(top = bounds.top - paddingTop)
                } else {
                    // When QS expansion > 0, it should directly set the top padding so do not
                    // animate it
                    val animate = qsExpansion == 0f && !isInTransitionToAnyState
                    bounds.copy(top = top, isAnimated = animate)
                }
            }
            .flowName("bounds")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                initialValue = NotificationContainerBounds(),
            )
            .dumpValue("bounds")
    }

    /**
     * Alpha of the container, driven by shade and QS expansion.
     *
     * This is 1f when the shade or QS is expanded, with a few exceptions:
     * - In Dual Shade, notifications fade out as QS expands, unless a HUN is visible.
     * - When transitioning to dream with the shade open, alpha is 0f to prevent visual glitches.
     */
    private val alphaForShadeAndQsExpansion: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
                shadeModeInteractor.shadeMode.flatMapLatest { shadeMode ->
                    @Suppress("DEPRECATION") // to handle split shade
                    when (shadeMode) {
                        Single,
                        Split ->
                            isAnyExpanded.transform { isAnyExpanded ->
                                if (isAnyExpanded) {
                                    emit(1f)
                                }
                            }
                        Dual ->
                            headsUpNotificationInteractor
                                .get()
                                .isHeadsUpOrAnimatingAway
                                .transformLatestConflated { isHeadsUpOrAnimatingAway ->
                                    if (isHeadsUpOrAnimatingAway) {
                                        // Ensure HUNs will be visible in QS shade (at least
                                        // while unlocked)
                                        emit(1f)
                                    } else {
                                        // On a narrow screen, the QS shade overlaps with
                                        // lockscreen notifications. Fade them out as the QS
                                        // shade expands.
                                        emitAll(shadeInteractor.qsExpansion.map { 1f - it })
                                    }
                                }
                    }
                }
            } else {
                interactor.configurationBasedDimensions.flatMapLatest { configurationBasedDimensions
                    ->
                    combineTransform(
                        shadeInteractor.shadeExpansion,
                        shadeInteractor.qsExpansion,
                        keyguardTransitionInteractor.isInTransition(
                            // This branch is never triggered when scene container is enabled, the
                            // edge param is unused.
                            edge = Edge.create(from = LOCKSCREEN, to = Scenes.Dream),
                            edgeWithoutSceneContainer =
                                Edge.create(from = LOCKSCREEN, to = DREAMING),
                        ),
                    ) { shadeExpansion, qsExpansion, inLockscreenToDreamTransition ->
                        if (shadeExpansion > 0f || qsExpansion > 0f) {
                            if (inLockscreenToDreamTransition) {
                                // Don't show lock screen when transitioning to dream with the shade
                                // open. The shade is collapsed by ACTION_CLOSE_SYSTEM_DIALOGS that
                                // the system server sends when starting the dream. Since the shade
                                // collapse isn't synced with the dream starting, if the collapse
                                // animation finishes after the LOCKSCREN -> DREAMING transition
                                // starts, it can cause keyguard to show up again briefly during the
                                // transition.
                                emit(0f)
                            } else {
                                emit(1f)
                            }
                        }
                    }
                }
            }
            .onStart { emit(1f) }
            .logAlphaForTable(columnName = "alphaForShadeAndQsExpansion")
            .dumpWhileCollecting("alphaForShadeAndQsExpansion")

    private fun alphaForBouncerExpansion(
        bouncerExpansion: Float,
        transitioningToBouncer: Boolean,
    ): Float {
        // The shade content fades out faster than the bouncer comes in.
        // See lockscreenToOverlayTransition for the definition of how
        // the rest of the content behaves during the transition.
        val multiplier = if (transitioningToBouncer) 20f else 5f
        return maxOf(0f, 1f - bouncerExpansion * multiplier)
    }

    val panelAlpha = keyguardInteractor.panelAlpha

    private fun bouncerToGoneNotificationAlpha(viewState: ViewStateAccessor): Flow<Float> =
        merge(
                primaryBouncerToGoneTransitionViewModel.notificationAlpha(viewState),
                alternateBouncerToGoneTransitionViewModel.notificationAlpha(viewState),
            )
            .sample(communalSceneInteractor.isCommunalVisible) { alpha, isCommunalVisible ->
                // when glanceable hub is visible, hide notifications during the transition to GONE
                if (isCommunalVisible) 0f else alpha
            }
            .dumpWhileCollecting("bouncerToGoneNotificationAlpha")

    private val bouncerOverlayNotificationAlpha: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            combineTransform(
                    bouncerInteractor.bouncerExpansion,
                    shadeInteractor.isNotificationsExpanded,
                    keyguardTransitionInteractor
                        .transitionValue(LOCKSCREEN)
                        .map { it > 0f }
                        .distinctUntilChanged(),
                    sceneInteractor.transitionStateFlow
                        .map { it is ObservableTransitionState.Idle }
                        .distinctUntilChanged(),
                    sceneInteractor.transitionStateFlow
                        .map { state ->
                            state is ObservableTransitionState.Transition &&
                                state.toContent == Overlays.Bouncer
                        }
                        .distinctUntilChanged(),
                ) {
                    bouncerExpansion,
                    isNotificationsExpanded,
                    inLockscreenTransition,
                    isIdle,
                    transitioningToBouncer,
                    ->
                    if (isNotificationsExpanded) {
                        // While the notifications Shade is expanded, always keep the shade opaque.
                        // This is expected during the transition to bouncer and as long the bouncer
                        // is open; the notifications are blurred underneath the bouncer.
                        // If the notifications need to be hidden for other reasons, let other
                        // relevant transition values declare the alpha.
                        emit(1f)
                    } else if (bouncerExpansion > 0f) {
                        // If bouncerExpansion is nonzero, we are currently transitioning to or from
                        // bouncer. In this case, only emit when going to/from lockscreen
                        // (specifically the keyguard state, to distinguish from AOD or other
                        // keyguard states on the lockscreen scene). Otherwise, let any other
                        // relevant transition values declare the alpha if necessary.
                        if (inLockscreenTransition) {
                            emit(alphaForBouncerExpansion(bouncerExpansion, transitioningToBouncer))
                        }
                    } else if (isIdle) {
                        // Once bouncer is fully gone *and* the system is at rest, emit 1 to make
                        // sure we leave the alpha in a correct state when idle. This needs to
                        // wait until the system is at rest in the case of transitions of the form
                        // bouncer -> A -> B, where bouncer is fully gone by the time we reach scene
                        // A but the system is still transitioning, so emitting an alpha of 1 at
                        // that time would be premature.
                        emit(1f)
                    }
                }
                .distinctUntilChanged()
                .dumpWhileCollecting("bouncerOverlayNotificationAlpha")
        } else {
            flowOf(1f)
        }

    private fun alphaForTransitions(viewState: ViewStateAccessor): Flow<Float> {
        return mergeAndLogAlphas(
            "alphaForTransitions",
            keyguardInteractor.dismissAlpha.dumpWhileCollecting(
                "keyguardInteractor.dismissAlpha"
            ) to "keyguardDismiss",
            // All transition view models are mutually exclusive, and safe to merge
            bouncerToGoneNotificationAlpha(viewState) to "bouncerToGone",
            bouncerOverlayNotificationAlpha to "bouncerOverlay",
            aodToGoneTransitionViewModel.notificationAlpha(viewState) to "aodToGone",
            aodToLockscreenTransitionViewModel.notificationAlpha to "aodToLockscreen",
            aodToOccludedTransitionViewModel.lockscreenAlpha(viewState) to "aodToOccluded",
            aodToGlanceableHubTransitionViewModel.lockscreenAlpha(viewState) to
                "aodToGlanceableHub",
            aodToPrimaryBouncerTransitionViewModel.notificationAlpha to "aodToPrimaryBouncer",
            dozingToLockscreenTransitionViewModel.lockscreenAlpha to "dozingToLockscreen",
            dozingToOccludedTransitionViewModel.lockscreenAlpha(viewState) to "dozingToOccluded",
            dozingToPrimaryBouncerTransitionViewModel.notificationAlpha to "dozingToPrimaryBouncer",
            dreamingToLockscreenTransitionViewModel.lockscreenAlpha to "bouncerToGone",
            goneToAodTransitionViewModel.notificationAlpha to "goneToAod",
            goneToDreamingTransitionViewModel.lockscreenAlpha() to "goneToDreaming",
            goneToDozingTransitionViewModel.notificationAlpha to "goneToDozing",
            goneToLockscreenTransitionViewModel.lockscreenAlpha to "goneToLockscreen",
            lockscreenToDreamingTransitionViewModel.lockscreenAlpha to "lockscreenToDreaming",
            lockscreenToGoneTransitionViewModel.notificationAlpha(viewState) to "lockscreenToGone",
            lockscreenToOccludedTransitionViewModel.lockscreenAlpha(viewState) to
                "lockscreenToOccluded",
            lockscreenToPrimaryBouncerTransitionViewModel.notificationAlpha to
                "lockscreenToPrimaryBouncer",
            alternateBouncerToPrimaryBouncerTransitionViewModel.notificationAlpha to
                "alternateBouncerToPrimaryBouncer",
            occludedToAodTransitionViewModel.lockscreenAlpha to "occludedToAod",
            occludedToGoneTransitionViewModel.notificationAlpha(viewState) to "occludedToGone",
            occludedToLockscreenTransitionViewModel.lockscreenAlpha to "occludedToLockscreen",
            offToLockscreenTransitionViewModel.lockscreenAlpha to "offToLockscreen",
            primaryBouncerToLockscreenTransitionViewModel.lockscreenAlpha(viewState) to
                "primaryBouncerToLockscreen",
            glanceableHubToLockscreenTransitionViewModel.keyguardAlpha to
                "glanceableHubToLockscreen",
            glanceableHubToAodTransitionViewModel.lockscreenAlpha to "glanceableHubToAod",
            lockscreenToGlanceableHubTransitionViewModel.keyguardAlpha to
                "lockscreenToGlanceableHub",
            toLockscreenEndStateTransitionViewModel.lockscreenAlpha to "toLockscreenEndState",
            toAodEndStateTransitionViewModel.lockscreenAlpha to "toAodEndState",
            toDozingEndStateTransitionViewModel.notificationAlpha to "toDozingEndState",
            if (SceneContainerFlag.isEnabled) {
                dozingToGoneTransitionViewModel.lockscreenAlpha(viewState)
            } else {
                emptyFlow()
            } to "dozingToGone",
        )
    }

    fun keyguardAlpha(viewState: ViewStateAccessor, scope: CoroutineScope): Flow<Float> {
        val isKeyguardNotVisibleInState =
            if (SceneContainerFlag.isEnabled) {
                sceneInteractor.currentScene.map { it == Scenes.Occluded }
            } else {
                anyOf(
                    keyguardTransitionInteractor.transitionValue(OCCLUDED).map { it == 1f },
                    keyguardTransitionInteractor
                        .transitionValue(content = Scenes.Gone, stateWithoutSceneContainer = GONE)
                        .map { it == 1f },
                )
            }

        // Transitions are not (yet) authoritative for NSSL; they still rely on StatusBarState to
        // help determine when the device has fully moved to GONE or OCCLUDED state. Once SHADE
        // state has been set, let shade alpha take over
        val isKeyguardNotVisible =
            combine(isKeyguardNotVisibleInState, keyguardInteractor.statusBarState) {
                    isKeyguardNotVisibleInState,
                    statusBarState ->
                    isKeyguardNotVisibleInState && statusBarState == SHADE
                }
                .logDiffsForTable(
                    alphaTableLogger,
                    columnName = "isKeyguardNotVisible",
                    initialValue = false,
                )

        // This needs to continue collecting the current value so that when it is selected in the
        // flatMapLatest below, the last value gets emitted, to avoid the randomness of `merge`.
        val alphaForTransitionsAndShade =
            mergeAndLogAlphas(
                    "alphaForTransitionsAndShade",
                    alphaForTransitions(viewState) to "transitions",
                    alphaForShadeAndQsExpansion to "shadeAndQs",
                )
                .flowName("alphaForTransitionsAndShade")
                .stateIn(
                    // Use view-level scope instead of ApplicationScope, to prevent collection that
                    // never stops
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = 1f,
                )
                .dumpValue("alphaForTransitionsAndShade")

        return isKeyguardNotVisible
            .flatMapLatest { isKeyguardNotVisible ->
                if (isKeyguardNotVisible) {
                    alphaForShadeAndQsExpansion
                } else {
                    alphaForTransitionsAndShade
                }
            }
            .distinctUntilChanged()
            .logAlphaForTable(columnName = "keyguardAlpha")
            .dumpWhileCollecting("keyguardAlpha")
    }

    val blurRadius =
        if (SceneContainerFlag.isEnabled && bouncerUiRevamp() && notificationShadeBlur()) {
            windowRootViewBlurInteractor.isBlurCurrentlySupported
                .flatMapLatest { isBlurSupported ->
                    if (isBlurSupported) {
                        bouncerInteractor.bouncerExpansion.map { it * blurConfig.maxBlurRadiusPx }
                    } else {
                        flowOf(0f)
                    }
                }
                .distinctUntilChanged()
                .dumpWhileCollecting("blurRadius")
        } else {
            primaryBouncerTransitions
                .map { transition -> transition.notificationBlurRadius }
                .merge()
                .dumpWhileCollecting("blurRadius")
        }
    /**
     * Flow of view scale values for the zoom animation between the lockscreen and glanceable hub.
     * 1.0f means no visual change to the view.
     */
    val viewScale: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            /** @see containerScale for the SceneContainer implementation. */
            flowOf(1f)
        } else {
            // Use flatMapLatestConflated so the animation flows aren't collected at all when
            // communal
            // is not visible.
            communalInteractor.isCommunalVisible
                .flatMapLatestConflated { isCommunalVisible ->
                    if (!isCommunalVisible) {
                        flowOf(1f)
                    } else {
                        merge(
                                lockscreenToGlanceableHubTransitionViewModel.zoomOut,
                                glanceableHubToLockscreenTransitionViewModel.zoomOut,
                                toLockscreenEndStateTransitionViewModel.zoomOut,
                            )
                            .map {
                                // Rate limit the zoom out by 5% step to avoid jank.
                                val limited = (round(it * 20) / 20f).coerceIn(0f, 1f)
                                1 - limited * PUSHBACK_SCALE
                            }
                    }
                }
                .distinctUntilChanged()
                .dumpWhileCollecting("viewScale")
        }

    /** Draw scale requested by the Notification Stack placeholder STL element. */
    val containerScale: ObservableState<Scale> =
        if (SceneContainerFlag.isEnabled) {
            notificationPlaceholderStateStorage.stackScale
        } else {
            SynchronouslyObservableState(Scale.Unspecified)
        }

    /**
     * Returns a flow of the expected alpha while running a LOCKSCREEN<->GLANCEABLE_HUB or
     * DREAMING<->GLANCEABLE_HUB transition or idle on the hub.
     *
     * Must return 1.0f when not controlling the alpha since notifications does a min of all the
     * alpha sources.
     */
    val glanceableHubAlpha: Flow<Float> =
        combineTransform(
                isOnGlanceableHubWithoutShade,
                isOnLockscreen,
                isDreamingWithoutShade,
                merge(
                        lockscreenToGlanceableHubTransitionViewModel.notificationAlpha,
                        glanceableHubToLockscreenTransitionViewModel.notificationAlpha,
                        dozingToGlanceableHubTransitionViewModel.notificationAlpha,
                    )
                    // Manually emit on start because [notificationAlpha] only starts emitting
                    // when transitions start.
                    .onStart { emit(1f) },
            ) { isOnGlanceableHubWithoutShade, isOnLockscreen, isDreamingWithoutShade, alpha ->
                if ((isOnGlanceableHubWithoutShade || isDreamingWithoutShade) && !isOnLockscreen) {
                    // Notifications should not be visible on the glanceable hub.
                    // TODO(b/321075734): implement a way to actually set the notifications to
                    // gone while on the hub instead of just adjusting alpha
                    emit(0f)
                } else if (isOnGlanceableHubWithoutShade) {
                    // We are transitioning between hub and lockscreen, so set the alpha for the
                    // transition animation.
                    emit(alpha)
                } else {
                    // Not on the hub and no transitions running, return full visibility so we
                    // don't block the notifications from showing.
                    emit(1f)
                }
            }
            .distinctUntilChanged()
            .logAlphaForTable(columnName = "glanceableHubAlpha")
            .dumpWhileCollecting("glanceableHubAlpha")

    /**
     * Under certain scenarios, such as swiping up on the lockscreen, the container will need to be
     * translated as the keyguard fades out.
     */
    val translationY: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
            // with SceneContainer, x translation is handled by views, y is handled by compose
            flowOf(0f)
        } else
            combine(
                    aodBurnInViewModel.movement
                        .map { it.translationY.toFloat() }
                        .onStart { emit(0f) },
                    isOnLockscreenWithoutShade,
                    merge(
                        keyguardInteractor.keyguardTranslationY,
                        occludedToLockscreenTransitionViewModel.lockscreenTranslationY,
                    ),
                ) { burnInY, isOnLockscreenWithoutShade, translationY ->
                    if (isOnLockscreenWithoutShade) {
                        burnInY + translationY
                    } else {
                        0f
                    }
                }
                .dumpWhileCollecting("translationY")

    /** Horizontal translation to apply to the container. */
    val translationX: Flow<Float> =
        merge(
                // The container may need to be translated along the X axis as the keyguard fades
                // out, such as when swiping open the glanceable hub from the lockscreen.
                lockscreenToGlanceableHubTransitionViewModel.notificationTranslationX,
                glanceableHubToLockscreenTransitionViewModel.notificationTranslationX,
                if (SceneContainerFlag.isEnabled) {
                    // The container may need to be translated along the X axis as the unfolded
                    // foldable is folded slightly.
                    unfoldTransitionInteractor.unfoldTranslationX(isOnStartSide = false)
                } else {
                    emptyFlow()
                },
            )
            .dumpWhileCollecting("translationX")

    val hasActiveMedia: Flow<Boolean>
        get() {
            SceneContainerFlag.assertInLegacyMode()
            return conflatedCallbackFlow {
                val listener =
                    object : MediaDataManager.Listener {
                        override fun onMediaDataLoaded(
                            key: String,
                            oldKey: String?,
                            data: MediaData,
                            immediately: Boolean,
                        ) {
                            trySend(mediaDataManager.hasActiveMedia())
                        }

                        override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
                            trySend(mediaDataManager.hasActiveMedia())
                        }

                        override fun onCurrentActiveMediaChanged(key: String?, data: MediaData?) {
                            trySend(mediaDataManager.hasActiveMedia())
                        }
                    }

                mediaDataManager.addListener(listener)

                trySend(mediaDataManager.hasActiveMedia())

                awaitClose { mediaDataManager.removeListener(listener) }
            }
        }

    private val availableHeight: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
                notificationStackAppearanceInteractor.constrainedAvailableSpace.map { it.toFloat() }
            } else {
                bounds.map { it.bottom - it.top }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("availableHeight")

    /**
     * When on keyguard, there is limited space to display notifications so calculate how many could
     * be shown. Otherwise, there is no limit since the vertical space will be scrollable.
     *
     * When expanding or when the user is interacting with the shade, keep the count stable; do not
     * emit a value.
     */
    fun getMaxNotifications(calculateSpace: (Float, Boolean) -> Int): Flow<Int> {
        val showLimitedNotifications = isOnLockscreenWithoutShade
        val showUnlimitedNotifications =
            combine(
                    isOnLockscreen,
                    keyguardInteractor.statusBarState,
                    merge(
                            primaryBouncerToGoneTransitionViewModel.showAllNotifications,
                            alternateBouncerToGoneTransitionViewModel.showAllNotifications,
                        )
                        .onStart { emit(false) },
                ) { isOnLockscreen, statusBarState, showAllNotifications ->
                    statusBarState == SHADE_LOCKED || !isOnLockscreen || showAllNotifications
                }
                .dumpWhileCollecting("showUnlimitedNotifications")

        @Suppress("UNCHECKED_CAST")
        return combineTransform<Any, Int>(
                showLimitedNotifications,
                showUnlimitedNotifications,
                shadeInteractor.isUserInteracting.dumpWhileCollecting("isUserInteracting"),
                availableHeight,
                interactor.useExtraShelfSpace,
                interactor.notificationStackChanged,
            ) { flows ->
                val showLimitedNotifications = flows[0] as Boolean
                val showUnlimitedNotifications = flows[1] as Boolean
                val isUserInteracting = flows[2] as Boolean
                val availableHeight = flows[3] as Float
                val useExtraShelfSpace = flows[4] as Boolean

                if (!isUserInteracting) {
                    if (showLimitedNotifications) {
                        emit(calculateSpace(availableHeight, useExtraShelfSpace))
                    } else if (showUnlimitedNotifications) {
                        // -1 means no limit
                        emit(-1)
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("getLockscreenDisplayConfig")
    }

    /**
     * Wallpaper focal area needs the absolute bottom of notification stack to avoid occlusion. It
     * should not change with notifications in shade.
     *
     * @param calculateMaxNotifications is required by getMaxNotifications as calculateSpace by
     *   calling computeMaxKeyguardNotifications in NotificationStackSizeCalculator
     * @param calculateHeight is calling computeHeight in NotificationStackSizeCalculator The edge
     *   case is that when maxNotifications is 0, we won't take shelfHeight into account
     */
    fun getNotificationStackAbsoluteBottomOnLockscreen(
        calculateMaxNotifications: (Float, Boolean) -> Int,
        calculateHeight: (Int) -> Float,
    ): Flow<Float> {
        SceneContainerFlag.assertInLegacyMode()
        return combine(
                activeNotificationsInteractor.areAnyNotificationsPresent,
                isOnLockscreen,
                hasActiveMedia,
                ::Triple,
            )
            .flatMapLatest { (hasNotifications, isOnLockscreen, hasActiveMedia) ->
                if ((hasNotifications || hasActiveMedia) && isOnLockscreen) {
                    combine(
                            getMaxNotifications(calculateMaxNotifications),
                            bounds.map { it.top },
                            isOnLockscreenWithoutShade,
                            interactor.notificationStackChanged,
                        ) { maxNotifications, top, isOnLockscreenWithoutShade, _ ->
                            if (isOnLockscreenWithoutShade && maxNotifications != -1) {
                                val height = calculateHeight(maxNotifications)
                                top + height
                            } else {
                                null
                            }
                        }
                        .filterNotNull()
                } else {
                    flowOf(0f)
                }
            }
    }

    fun notificationStackChanged() {
        interactor.notificationStackChanged()
    }

    fun notificationStackChangedInstant() {
        interactor.notificationsInStackChangedInstant()
    }

    private class DiffableAlpha(val alpha: Float, val columnName: String, val source: String) :
        Diffable<DiffableAlpha> {
        private val description
            get() = if (source != "") "$alpha [$source]" else "$alpha"

        override fun logDiffs(prevVal: DiffableAlpha, row: TableRowLogger) {
            if (shouldLogAlpha(prevVal.alpha) || source != prevVal.source) {
                row.logChange(columnName, description)
            }
        }

        // Only log alpha diffs that go to or from 0 and 1: this captures when any transition
        // begins or ends but avoids spamming logs with all of the intermediate alpha values that
        // each flow emits.
        private fun shouldLogAlpha(prevAlpha: Float): Boolean {
            return if (alpha != prevAlpha) {
                alpha == 0f || alpha == 1f || prevAlpha == 0f || prevAlpha == 1f
            } else {
                false
            }
        }
    }

    /**
     * Logs to the alpha table only when there is a sufficient diff. We use this rather than the
     * default logDiffsForTable for Float to restrict the frequency of emits given rapidly changing
     * alpha values.
     *
     * Note that this method intentionally does not include a source parameter for DiffableAlpha, as
     * any values coming through this flow are inherently from the same source, making it invalid to
     * use as a parameter for comparison.
     */
    private fun Flow<Float>.logAlphaForTable(columnName: String): Flow<Float> {
        return this.map { DiffableAlpha(it, columnName, "") }
            .logDiffsForTable(
                alphaTableLogger,
                initialValue = DiffableAlpha(1.0f, columnName, "initial"),
            )
            .map { it.alpha }
    }

    /**
     * Merge the given alpha flows, logging each one to the alpha table with the associated label.
     *
     * @param columnName the column name used to output to the alpha table log.
     * @param flows a collection pairs of each input flow along with its associated label.
     * @return the merged result of all input flows, equivalent to merge(flows).
     */
    private fun mergeAndLogAlphas(
        columnName: String,
        vararg flows: Pair<Flow<Float>, String>,
    ): Flow<Float> {
        return flows
            .map { (flow, label) -> flow.map { DiffableAlpha(it, columnName, label) } }
            .asIterable()
            .merge()
            .logDiffsForTable(
                alphaTableLogger,
                initialValue = DiffableAlpha(1.0f, columnName, "initial"),
            )
            .map { it.alpha }
    }

    data class ConfigurationBasedDimensions(
        val horizontalPosition: HorizontalPosition,
        val marginStart: Int,
        val marginTop: Int,
        val marginEnd: Int,
        val marginBottom: Int,
    )

    /** Specifies the horizontal layout constraints for the notification container. */
    sealed interface HorizontalPosition {
        /** The container is using the full width of the screen (minus any margins). */
        data object EdgeToEdge : HorizontalPosition

        /**
         * The container is laid out from the start edge to the middle of the screen width, or to
         * [maxWidth], whichever dimension is smaller.
         */
        data class EdgeToMiddle(val maxWidth: Int) : HorizontalPosition

        /**
         * The container is laid out from the middle of the screen width to the end edge, or to
         * [maxWidth], whichever dimension is smaller.
         */
        data class MiddleToEdge(val maxWidth: Int = Int.MAX_VALUE) : HorizontalPosition
    }

    companion object {
        @VisibleForTesting const val PUSHBACK_SCALE = 0.05f
    }
}
