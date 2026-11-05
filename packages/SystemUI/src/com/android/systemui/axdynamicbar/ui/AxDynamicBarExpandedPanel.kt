@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.compose.theme.PlatformTheme
import com.android.systemui.shared.recents.utilities.Utilities
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.ExpandedMaxWidth
import com.android.systemui.axdynamicbar.shared.LocalBlurAlpha
import com.android.systemui.axdynamicbar.shared.SpaceLg
import com.android.systemui.axdynamicbar.ui.compose.ExpandedIslandContent
import com.android.systemui.axdynamicbar.ui.compose.NotificationAlertCard
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import android.os.Handler
import android.os.Looper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.view.WindowInsets
import androidx.compose.ui.input.pointer.PointerEvent

private const val OVERLAY_HIDE_DELAY_MS = 400L
private const val TAG = "AxDynamicBarExpanded"

@SysUISingleton
class AxDynamicBarExpandedPanel
@Inject
constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val viewModel: AxDynamicBarChipViewModel,
) {
    private data class OverlayState(
        val needed: Boolean = false,
        val expanded: Boolean = false,
        val allowExitAnimation: Boolean = false,
    )

    private var initialized = false
    @Volatile private var desiredState = OverlayState()
    private var overlayView: ComposeView? = null
    private var panelLifecycleOwner: PanelLifecycleOwner? = null
    private var hideOverlayJob: Job? = null

    private var explicitFocusRequested = false
    private var replyCaptureActive = false
    private var imeVisible = false
    private var imeHeight = 0

    private val touchableInsetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            if (!replyCaptureActive) return@OnComputeInternalInsetsListener
            val v = overlayView ?: return@OnComputeInternalInsetsListener
            val touchableBottom = when {
                !imeVisible -> v.height
                imeHeight > 0 -> v.height - imeHeight
                else -> v.height / 2 // IME height unknown: never cover the keyboard
            }
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(0, 0, v.width, touchableBottom.coerceAtLeast(0))
        }

    fun init() {
        if (initialized) return
        initialized = true

        viewModel.interactor.onCollapseRequested = { viewModel.statusBarExpansion.collapse() }
        viewModel.interactor.onFocusableRequested = { focusable -> setOverlayFocusable(focusable) }
        viewModel.interactor.onAlertReplyCaptureRequested = { enabled -> setReplyCapture(enabled) }

        combine(
            viewModel.isExpanded,
            viewModel.interactor.uiState.map { it.notificationAlert != null }
                .distinctUntilChanged(),
            viewModel.isOnKeyguard,
            viewModel.interactor.settings.isEnabled,
        ) { expanded, hasAlert, onKeyguard, enabled ->
            val allowed = enabled && !onKeyguard
            OverlayState(
                needed = allowed && (expanded || hasAlert),
                expanded = allowed && expanded,
                allowExitAnimation = allowed,
            )
        }
            .distinctUntilChanged()
            .onEach { state ->
                desiredState = state
                ensureMainThread {
                    if (state != desiredState) return@ensureMainThread
                    hideOverlayJob?.cancel()
                    hideOverlayJob = null
                    if (state.needed) {
                        showOverlayInternal()
                        applyWindowState()
                    } else {
                        applyWindowState()
                        if (!state.allowExitAnimation) {
                            hideOverlayInternal()
                        } else {
                            hideOverlayJob = applicationScope.launch {
                                delay(OVERLAY_HIDE_DELAY_MS)
                                ensureMainThread {
                                    if (!desiredState.needed) hideOverlayInternal()
                                }
                            }
                        }
                    }
                }
            }
            .launchIn(applicationScope)
    }

    private fun ensureMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private fun showOverlayInternal() {
        if (overlayView != null || !desiredState.needed) return

        val lifecycleOwner = PanelLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val windowMetrics = windowManager.currentWindowMetrics
        val statusBarTop = windowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars())
            .top
        val hasCutout = windowMetrics.windowInsets
            .getInsets(WindowInsets.Type.displayCutout())
            .top > 0

        val view = ComposeView(context).apply {
            setContent { PlatformTheme { OverlayContent(viewModel, statusBarTop, hasCutout) } }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        viewModel.collapseFromOutsideTouch(event.downTime)
                        true
                    }
                    MotionEvent.ACTION_DOWN ->
                        if (replyCaptureActive) {
                            viewModel.interactor.onOverlayTouchOutsideCard()
                            true
                        } else false
                    else -> false
                }
            }
            setOnApplyWindowInsetsListener { v, insets ->
                val visible = insets.isVisible(WindowInsets.Type.ime())
                val wasImeVisible = imeVisible
                val height = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (visible != imeVisible || height != imeHeight) {
                    imeVisible = visible
                    imeHeight = height
                    if (replyCaptureActive) v.requestLayout() // recompute touchable region
                }
                if (visible != wasImeVisible) {
                    viewModel.interactor.onOverlayImeVisibilityChanged(visible)
                }
                v.onApplyWindowInsets(insets)
            }
            viewTreeObserver.addOnComputeInternalInsetsListener(touchableInsetsListener)
        }

        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        view.setViewTreeOnBackPressedDispatcherOwner(lifecycleOwner)
        panelLifecycleOwner = lifecycleOwner

        // This overlay is only shown when unlocked. SHOW_WHEN_LOCKED can surface a stale
        // window during keyguard transitions and is not needed for a status-bar sub-panel.
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            if (desiredState.expanded || explicitFocusRequested) 0
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                if (replyCaptureActive) WindowManager.LayoutParams.MATCH_PARENT
                else WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        params.title = "AxDynamicBarExpanded"

        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            view.viewTreeObserver.removeOnComputeInternalInsetsListener(touchableInsetsListener)
            view.setOnTouchListener(null)
            view.setOnApplyWindowInsetsListener(null)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            view.disposeComposition()
            panelLifecycleOwner = null
            Log.w(TAG, "Unable to add overlay", e)
            return
        }
        overlayView = view
    }

    private fun hideOverlayInternal() {
        // Clear this reference first: disposal may synchronously dispatch reply/focus callbacks.
        val view = overlayView
        overlayView = null
        if (view != null) {
            view.viewTreeObserver.removeOnComputeInternalInsetsListener(touchableInsetsListener)
            view.setOnTouchListener(null)
            view.setOnApplyWindowInsetsListener(null)
            panelLifecycleOwner?.apply {
                handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // A display teardown may have removed the window already.
            } finally {
                view.disposeComposition()
            }
        }
        panelLifecycleOwner = null
        replyCaptureActive = false
        explicitFocusRequested = false
        imeVisible = false
        imeHeight = 0
        // An abandoned reply must not leave the interactor waiting for input from a dead view.
        viewModel.interactor.onAlertReplyActiveChanged(false)
    }

    private fun applyWindowState() {
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val focusable = desiredState.needed && (desiredState.expanded || explicitFocusRequested)
        val flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        val height = if (replyCaptureActive && desiredState.needed) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        if (params.flags == flags && params.height == height) return
        params.flags = flags
        params.height = height
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // A pending display/window teardown can race with an IME or expansion change.
            Log.w(TAG, "Unable to update overlay", e)
            hideOverlayInternal()
        }
    }

    private fun setReplyCapture(enabled: Boolean) = ensureMainThread {
        if (replyCaptureActive == enabled) return@ensureMainThread
        replyCaptureActive = enabled && desiredState.needed
        applyWindowState()
        overlayView?.requestLayout() // refresh the touchable region around the IME
    }

    private fun setOverlayFocusable(focusable: Boolean) = ensureMainThread {
        if (explicitFocusRequested == focusable) return@ensureMainThread
        explicitFocusRequested = focusable && desiredState.needed
        applyWindowState()
    }

}

@Composable
private fun OverlayContent(viewModel: AxDynamicBarChipViewModel, statusBarHeightPx: Int, hasCutout: Boolean) {
    val density = LocalDensity.current
    val isLargeScreen = Utilities.isLargeScreen(LocalContext.current)

    val largeScreenExtra = if (isLargeScreen) 4.dp else 0.dp
    val topPad = if (hasCutout) largeScreenExtra
        else with(density) { statusBarHeightPx.toDp() } + largeScreenExtra
    val chipState by viewModel.chipState.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val alertFlow = remember(viewModel) {
        viewModel.interactor.uiState.map { it.notificationAlert }.distinctUntilChanged()
    }
    val notifAlert by alertFlow.collectAsStateWithLifecycle(
        initialValue = viewModel.interactor.uiState.value.notificationAlert
    )
    val compactNotifs by viewModel.interactor.settings.compactNotifications.collectAsStateWithLifecycle()

    val lastAlert = remember { mutableStateOf(notifAlert) }
    LaunchedEffect(notifAlert) {
        if (notifAlert != null) lastAlert.value = notifAlert
    }

    val expandedVisible = remember { MutableTransitionState(false) }
    val showNotif = !isExpanded && notifAlert != null
    val notifVisible = remember { MutableTransitionState(false) }

    LaunchedEffect(isExpanded) {
        expandedVisible.targetState = isExpanded
    }

    LaunchedEffect(showNotif) {
        notifVisible.targetState = showNotif
    }

    LaunchedEffect(notifAlert, notifVisible.targetState, notifVisible.currentState, notifVisible.isIdle) {
        if (notifAlert == null && !notifVisible.targetState && notifVisible.isIdle &&
            !notifVisible.currentState
        ) {
            lastAlert.value = null
        }
    }

    val expandableEvents by remember {
        derivedStateOf { chipState?.allEvents?.filter { it !is IslandEvent.AospChip }.orEmpty() }
    }
    val hasExpandableEvents = expandableEvents.isNotEmpty()
    LaunchedEffect(hasExpandableEvents, isExpanded) {
        if (!hasExpandableEvents && isExpanded) {
            viewModel.statusBarExpansion.collapse()
        }
    }

    val origin = TransformOrigin(0.5f, 0f)
    val motionScheme = MaterialTheme.motionScheme

    val expandedBlurAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "expanded_blur_alpha",
    )
    val alertBlurAlpha by animateFloatAsState(
        targetValue = if (showNotif) 1f else 0f,
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "alert_blur_alpha",
    )

    CompositionLocalProvider(LocalBlurAlpha provides expandedBlurAlpha) {
        AnimatedVisibility(
            visibleState = expandedVisible,
            enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
                animationSpec = motionScheme.defaultSpatialSpec(),
                initialScale = 0.4f,
                transformOrigin = origin,
            ),
            exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                animationSpec = motionScheme.fastSpatialSpec(),
                targetScale = 0.4f,
                transformOrigin = origin,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            var ev: PointerEvent
                            do {
                                ev = awaitPointerEvent(PointerEventPass.Final)
                            } while (!ev.changes.any { it.changedToDownIgnoreConsumed() })

                            val downChange =
                                ev.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                                    ?: ev.changes.firstOrNull()
                                    ?: return@awaitEachGesture

                            val pointerId = downChange.id
                            val downPos = downChange.position
                            val downConsumed = downChange.isConsumed
                            var hasExceededSlop = false

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change =
                                    event.changes.firstOrNull { it.id == pointerId }
                                        ?: event.changes.firstOrNull()
                                        ?: break

                                val dx = change.position.x - downPos.x
                                val dy = change.position.y - downPos.y
                                if (dx * dx + dy * dy > slop * slop) {
                                    hasExceededSlop = true
                                }

                                if (!change.pressed) {
                                    if (!downConsumed && !change.isConsumed && !hasExceededSlop) {
                                        change.consume()
                                        viewModel.statusBarExpansion.collapse()
                                    }
                                    break
                                }
                            }
                        }
                    }
                    .padding(top = topPad),
                contentAlignment = Alignment.TopCenter,
            ) {
                chipState?.let { state ->
                    val filtered = expandableEvents
                    if (filtered.isEmpty()) return@let
                    Box(
                        modifier =
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                // A tap on a card's own surface (not on one of its controls)
                                // minimises the island, matching a tap on the chip. Previously
                                // this was an inert clickable that silently swallowed the tap.
                                onClick = { viewModel.statusBarExpansion.collapse() },
                            )
                    ) {
                        ExpandedIslandContent(
                            events = filtered,
                            interactor = viewModel.interactor,
                            onCollapse = { viewModel.statusBarExpansion.collapse() },
                            pinnedEventId = state.event.id,
                            hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                        )
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalBlurAlpha provides alertBlurAlpha) {
        AnimatedVisibility(
            visibleState = notifVisible,
            enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
                animationSpec = motionScheme.defaultSpatialSpec(),
                initialScale = 0.4f,
                transformOrigin = origin,
            ),
            exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                animationSpec = motionScheme.fastSpatialSpec(),
                targetScale = 0.4f,
                transformOrigin = origin,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPad),
                contentAlignment = Alignment.TopCenter,
            ) {
                val alert = lastAlert.value
                if (alert != null) {
                    NotificationAlertCard(
                        notification = alert,
                        interactor = viewModel.interactor,
                        onDismiss = { viewModel.interactor.dismissNotificationAlert() },
                        initiallyCompact = compactNotifs,
                        modifier =
                            Modifier.widthIn(max = ExpandedMaxWidth)
                                .fillMaxWidth()
                                .padding(horizontal = SpaceLg),
                    )
                }
            }
        }
    }
}

private class PanelLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = backDispatcher

    init {
        savedStateRegistryController.performRestore(null)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
