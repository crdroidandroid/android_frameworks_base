package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import com.android.axion.compose.host.AxComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.util.ScrimUtils
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val CHIP_ABOVE_LOCK_MARGIN_DP = 12f
private const val EXPANDED_BOTTOM_PROTECTION_DP = 16f
private const val UNSET = -1

private val HIDDEN_VIEW_IDS = listOf(
    R.id.shared_notification_container,
    R.id.keyguard_widgets_area,
    R.id.notificationShelf,
)


private fun Float.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density + 0.5f).toInt()

class AxDynamicBarKeyguardChipSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
    private val indicationController: KeyguardIndicationController,
    private val clockInteractor: KeyguardClockInteractor,
) : KeyguardSection() {

    private val chipViewId = R.id.ax_dynamic_bar_keyguard_chip
    private var bindHandle: DisposableHandle? = null
    private var expansionHandle: DisposableHandle? = null
    private var enforceAction: Runnable? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        val composeView = AxComposeView(context).apply { id = chipViewId }
        constraintLayout.addView(composeView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val composeView: AxComposeView = constraintLayout.requireViewById(chipViewId)

        if (viewModel.isEnabled.value && viewModel.isKeyguardEnabled.value && viewModel.isOnKeyguard.value) {
            indicationController.setSuppressIndication(true)
        }

        composeView.setContent {
            PlatformTheme {
                AxDynamicBarKeyguardChip(viewModel = viewModel)
            }
        }

        bindHandle = composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(viewModel.isOnKeyguard, viewModel.isEnabled, viewModel.isKeyguardEnabled) { onKg, enabled, kgEnabled ->
                    onKg && enabled && kgEnabled
                }.collect { indicationController.setSuppressIndication(it) }
            }
        }

        expansionHandle = composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val scope = this
                scope.launch {
                    viewModel.keyguardExpansion.collapseSettled.collect {
                        if (!viewModel.keyguardExpansion.isExpanded.value) {
                            applyCollapsedLp(composeView, viewModel.isLowUdfps.value)
                            setHiddenViewsVisibility(constraintLayout, View.VISIBLE)
                        }
                    }
                }
                scope.launch {
                    viewModel.isKeyguardExpanded.collectLatest { expanded ->
                        if (expanded) {
                            clockInteractor.clockSize.collect { size ->
                                if (size != ClockSize.SMALL) {
                                    clockInteractor.setClockSize(ClockSize.SMALL)
                                }
                            }
                        }
                    }
                }
                combine(viewModel.isKeyguardExpanded, viewModel.isLowUdfps) { expanded, lowUdfps ->
                    expanded to lowUdfps
                }.collect { (expanded, lowUdfps) ->
                    onExpandedStateChanged(constraintLayout, composeView, expanded, lowUdfps)
                }
            }
        }
    }

    private fun onExpandedStateChanged(
        constraintLayout: ConstraintLayout,
        composeView: View,
        expanded: Boolean,
        lowUdfps: Boolean,
    ) {
        rebindPreDrawAction(constraintLayout, expanded)
        TransitionManager.endTransitions(constraintLayout)
        if (expanded) {
            setHiddenViewsVisibility(constraintLayout, View.INVISIBLE)
            applyExpandedLp(composeView)
        }
    }

    private fun rebindPreDrawAction(constraintLayout: ConstraintLayout, expanded: Boolean) {
        enforceAction?.let { ScrimUtils.get().removeKeyguardPreDrawAction(it) }
        enforceAction = if (expanded) {
            Runnable { enforceHidden(constraintLayout) }.also {
                ScrimUtils.get().addKeyguardPreDrawAction(it)
            }
        } else null
    }

    private fun hiddenTargets(constraintLayout: ConstraintLayout): List<View> =
        HIDDEN_VIEW_IDS.mapNotNull { constraintLayout.rootView.findViewById<View>(it) }

    private fun setHiddenViewsVisibility(constraintLayout: ConstraintLayout, visibility: Int) {
        hiddenTargets(constraintLayout).forEach { v ->
            v.alpha = 1f
            if (v.visibility != visibility) v.visibility = visibility
        }
    }

    private fun enforceHidden(constraintLayout: ConstraintLayout) {
        hiddenTargets(constraintLayout).forEach { v ->
            if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val expanded = viewModel.isKeyguardExpanded.value
        val lowUdfps = viewModel.isLowUdfps.value
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        val chipAboveLockPx = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        constraintSet.apply {
            when {
                expanded -> {
                    constrainWidth(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                    constrainHeight(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                    connect(chipViewId, ConstraintSet.TOP, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.BOTTOM)
                    connect(chipViewId, ConstraintSet.BOTTOM, R.id.device_entry_icon_view, ConstraintSet.TOP, bottomProtectionPx)
                    connect(chipViewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    connect(chipViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                lowUdfps -> {
                    constrainWidth(chipViewId, wrap)
                    constrainHeight(chipViewId, wrap)
                    connect(chipViewId, ConstraintSet.BOTTOM, R.id.device_entry_icon_view, ConstraintSet.TOP, chipAboveLockPx)
                    connect(chipViewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    connect(chipViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                else -> {
                    constrainWidth(chipViewId, wrap)
                    constrainHeight(chipViewId, wrap)
                    connect(chipViewId, ConstraintSet.BOTTOM, R.id.start_button, ConstraintSet.BOTTOM)
                    connect(chipViewId, ConstraintSet.START, R.id.start_button, ConstraintSet.END)
                    connect(chipViewId, ConstraintSet.END, R.id.end_button, ConstraintSet.START)
                }
            }
        }
    }

    private fun applyExpandedLp(composeView: View) {
        val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        lp.height = 0
        lp.topToBottom = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
        lp.bottomToTop = R.id.device_entry_icon_view
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        lp.topMargin = 0
        lp.bottomMargin = bottomProtectionPx
        lp.topToTop = UNSET
        lp.bottomToBottom = UNSET
        lp.startToEnd = UNSET
        lp.endToStart = UNSET
        composeView.layoutParams = lp
    }

    private fun applyCollapsedLp(composeView: View, lowUdfps: Boolean) {
        val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.topMargin = 0
        lp.topToTop = UNSET
        lp.topToBottom = UNSET
        if (lowUdfps) {
            lp.bottomToTop = R.id.device_entry_icon_view
            lp.bottomToBottom = UNSET
            lp.bottomMargin = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context)
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToEnd = UNSET
            lp.endToStart = UNSET
        } else {
            lp.bottomToBottom = R.id.start_button
            lp.bottomToTop = UNSET
            lp.bottomMargin = 0
            lp.startToEnd = R.id.start_button
            lp.endToStart = R.id.end_button
            lp.startToStart = UNSET
            lp.endToEnd = UNSET
        }
        composeView.layoutParams = lp
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        TransitionManager.endTransitions(constraintLayout)
        enforceAction?.let { ScrimUtils.get().removeKeyguardPreDrawAction(it) }
        enforceAction = null
        expansionHandle?.dispose()
        expansionHandle = null
        bindHandle?.dispose()
        bindHandle = null
        indicationController.setSuppressIndication(false)
        constraintLayout.removeView(chipViewId)
    }
}
