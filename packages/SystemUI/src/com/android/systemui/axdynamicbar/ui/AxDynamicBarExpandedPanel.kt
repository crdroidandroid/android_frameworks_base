package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
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
import com.android.systemui.axdynamicbar.ui.compose.ExpandedIslandContent
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.view.WindowInsets
import androidx.compose.ui.input.pointer.PointerEvent

private const val EXIT_ANIM_DURATION = 300L

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
    private var overlayView: ComposeView? = null
    private var panelLifecycleOwner: PanelLifecycleOwner? = null
    private var hideOverlayJob: Job? = null

    fun init() {
        viewModel.interactor.onCollapseRequested = { viewModel.statusBarExpansion.collapse() }
        viewModel.interactor.onFocusableRequested = { focusable -> setOverlayFocusable(focusable) }

        val needsOverlay =
            combine(
                viewModel.isExpanded,
                viewModel.isOnKeyguard,
            ) { expanded, onKeyguard ->
                !onKeyguard && expanded
            }

        needsOverlay
            .onEach { needed ->
                if (needed) {
                    hideOverlayJob?.cancel()
                    hideOverlayJob = null
                    showOverlay()
                } else {
                    
                    hideOverlayJob?.cancel()
                    hideOverlayJob =
                        applicationScope.launch {
                            delay(400)
                            hideOverlay()
                        }
                }
            }
            .launchIn(applicationScope)

        viewModel.isExpanded
            .onEach { expanded ->
                updateOverlay(expanded)
            }
            .launchIn(applicationScope)
    }

    private fun ensureMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private fun showOverlay() {
        ensureMainThread { showOverlayInternal() }
    }

    private fun showOverlayInternal() {
        if (overlayView != null) return

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

        val view =
            ComposeView(context).apply {
                setContent { PlatformTheme { OverlayContent(viewModel, statusBarTop, hasCutout) } }
            }

        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        view.setViewTreeOnBackPressedDispatcherOwner(lifecycleOwner)
        panelLifecycleOwner = lifecycleOwner

        val isCurrentlyExpanded = viewModel.isExpanded.value
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            if (isCurrentlyExpanded) 0
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                if (isCurrentlyExpanded) WindowManager.LayoutParams.MATCH_PARENT
                else WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        params.title = "AxDynamicBarExpanded"

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun hideOverlay() {
        ensureMainThread { hideOverlayInternal() }
    }

    private fun hideOverlayInternal() {
        shrinkRunnable?.let { mainHandler.removeCallbacks(it) }
        shrinkRunnable = null
        overlayView?.let { view ->
            panelLifecycleOwner?.apply {
                handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            windowManager.removeViewImmediate(view)
        }
        overlayView = null
        panelLifecycleOwner = null
    }

    private var shrinkRunnable: Runnable? = null

    private fun updateOverlay(expanded: Boolean) = ensureMainThread {
        shrinkRunnable?.let { mainHandler.removeCallbacks(it) }
        shrinkRunnable = null
        val view = overlayView ?: return@ensureMainThread
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return@ensureMainThread
        if (expanded) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            windowManager.updateViewLayout(view, params)
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(view, params)
            val runnable = Runnable {
                val v = overlayView ?: return@Runnable
                val p = v.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
                windowManager.updateViewLayout(v, p)
            }
            shrinkRunnable = runnable
            mainHandler.postDelayed(runnable, EXIT_ANIM_DURATION)
        }
    }

    private fun setOverlayFocusable(focusable: Boolean) = ensureMainThread {
        val view = overlayView ?: return@ensureMainThread
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return@ensureMainThread
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(view, params)
    }
}

@Composable
private fun OverlayContent(viewModel: AxDynamicBarChipViewModel, statusBarHeightPx: Int, hasCutout: Boolean) {
    val density = LocalDensity.current
    val isLargeScreen = Utilities.isLargeScreen(LocalContext.current)

    val largeScreenExtra = if (isLargeScreen) 4.dp else 0.dp
    val baseTopPad = 4.dp
    val topPad = baseTopPad + if (hasCutout) largeScreenExtra
        else with(density) { statusBarHeightPx.toDp() } + largeScreenExtra
    val chipState by viewModel.chipState.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val chipX by viewModel.chipCenterXFraction.collectAsStateWithLifecycle()

    val expandedVisible = remember { MutableTransitionState(false) }

    LaunchedEffect(isExpanded) {
        expandedVisible.targetState = isExpanded
    }

    val originX = chipX
    val origin = TransformOrigin(originX, 0f)
    
    val chipAlignment = BiasAlignment(
        horizontalBias = originX * 2f - 1f,  
        verticalBias = -1f,                    
    )

    AnimatedVisibility(
        visibleState = expandedVisible,
        enter = fadeIn(tween(250)) + scaleIn(
            animationSpec = tween(350),
            initialScale = 0.4f,
            transformOrigin = origin,
        ),
        exit = fadeOut(tween(200)) + scaleOut(
            animationSpec = tween(250),
            targetScale = 0.4f,
            transformOrigin = origin,
        ),
    ) {
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        
                        var ev: PointerEvent
                        do {
                            ev = awaitPointerEvent(PointerEventPass.Final)
                        } while (!ev.changes.any { it.changedToDownIgnoreConsumed() })
                        val downPos = ev.changes[0].position
                        
                        val downConsumed = ev.changes[0].isConsumed
                        
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (!downConsumed && !change.isConsumed) {
                                    val dx = change.position.x - downPos.x
                                    val dy = change.position.y - downPos.y
                                    if (dx * dx + dy * dy <= slop * slop) {
                                        viewModel.statusBarExpansion.collapse()
                                    }
                                }
                                break
                            }
                        }
                    }
                }
                .padding(top = topPad),
            contentAlignment = chipAlignment,
        ) {
            chipState?.let { state ->
                val filtered = state.allEvents.filter { it !is IslandEvent.AospChip }
                if (filtered.isEmpty()) return@let
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

