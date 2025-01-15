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

package com.android.systemui.biometrics.ui.binder

import android.animation.Animator
import android.animation.AnimatorSet
import android.graphics.Outline
import android.graphics.Rect
import android.hardware.biometrics.Flags
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import androidx.core.animation.addListener
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.domain.interactor.BiometricPromptView
import com.android.systemui.biometrics.ui.BiometricPromptLayoutState
import com.android.systemui.biometrics.ui.PromptPosition
import com.android.systemui.biometrics.ui.PromptSize
import com.android.systemui.biometrics.ui.isLarge
import com.android.systemui.biometrics.ui.isLeft
import com.android.systemui.biometrics.ui.isMedium
import com.android.systemui.biometrics.ui.isSmall
import com.android.systemui.biometrics.ui.isTop
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.utils.windowmanager.WindowManagerUtils
import com.android.systemui.util.kotlin.Quint
import kotlin.math.abs
import kotlinx.coroutines.flow.combine

/** Helper for [BiometricViewBinder] to handle resize transitions. */
object BiometricViewSizeBinder {

    private const val ANIMATE_SMALL_TO_MEDIUM_DURATION_MS = 150

    // TODO(b/201510778): make private when related misuse is fixed
    const val ANIMATE_MEDIUM_TO_LARGE_DURATION_MS = 450

    /** Resizes [BiometricPromptLayout] and the [panelViewController] via the [PromptViewModel]. */
    fun bind(
        view: View,
        viewModel: PromptViewModel,
        viewsToHideWhenSmall: List<View>,
        jankListener: BiometricJankListener,
    ) {
        val windowManager = WindowManagerUtils.getWindowManager(view.context)
        val accessibilityManager =
            requireNotNull(view.context.getSystemService(AccessibilityManager::class.java))

        fun notifyAccessibilityChanged() {
            Utils.notifyAccessibilityContentChanged(accessibilityManager, view as ViewGroup)
        }

        fun startMonitoredAnimation(animators: List<Animator>) {
            with(AnimatorSet()) {
                addListener(jankListener)
                addListener(onEnd = { notifyAccessibilityChanged() })
                play(animators.first()).apply { animators.drop(1).forEach { next -> with(next) } }
                start()
            }
        }

        val leftGuideline = view.requireViewById<Guideline>(R.id.leftGuideline)
        val topGuideline = view.requireViewById<Guideline>(R.id.topGuideline)
        val rightGuideline = view.requireViewById<Guideline>(R.id.rightGuideline)
        val midGuideline = view.findViewById<Guideline>(R.id.midGuideline)

        val indicatorView = view.requireViewById<View>(R.id.indicator)
        val scrollView = view.requireViewById<View>(R.id.scrollView)
        val iconHolderView = view.requireViewById<View>(R.id.biometric_icon)
        val panelView = view.requireViewById<View>(R.id.panel)
        val cornerRadius = view.resources.getDimension(R.dimen.biometric_dialog_corner_size)
        val pxToDp =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                1f,
                view.resources.displayMetrics,
            )
        val cornerRadiusPx = (pxToDp * cornerRadius).toInt()

        var currentSize: PromptSize? = null
        var currentPosition: PromptPosition = PromptPosition.Bottom
        var currentView: BiometricPromptView? = null
        var previousLayoutState: BiometricPromptLayoutState? = null
        panelView.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (
                        Flags.bpFallbackOptions() && currentView == BiometricPromptView.CREDENTIAL
                    ) {
                        outline.setRect(0, 0, view.width, view.height)
                        return
                    }

                    when (currentPosition) {
                        PromptPosition.Right -> {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width + cornerRadiusPx,
                                view.height,
                                cornerRadiusPx.toFloat(),
                            )
                        }

                        PromptPosition.Left -> {
                            outline.setRoundRect(
                                -cornerRadiusPx,
                                0,
                                view.width,
                                view.height,
                                cornerRadiusPx.toFloat(),
                            )
                        }

                        PromptPosition.Bottom,
                        PromptPosition.Top -> {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width,
                                view.height + cornerRadiusPx,
                                cornerRadiusPx.toFloat(),
                            )
                        }
                    }
                }
            }

        // ConstraintSets for animating between prompt sizes
        val baseConstraintSet = ConstraintSet()
        baseConstraintSet.clone(view as ConstraintLayout)

        val mediumConstraintSet = ConstraintSet()
        mediumConstraintSet.clone(view as ConstraintLayout)

        val smallConstraintSet = ConstraintSet()
        smallConstraintSet.clone(mediumConstraintSet)

        val largeConstraintSet = ConstraintSet()
        largeConstraintSet.clone(mediumConstraintSet)
        largeConstraintSet.constrainMaxWidth(R.id.panel, 0)
        largeConstraintSet.setGuidelineBegin(R.id.leftGuideline, 0)
        largeConstraintSet.setGuidelineEnd(R.id.rightGuideline, 0)
        if (Flags.bpFallbackOptions()) {
            largeConstraintSet.setVisibility(R.id.auth_screen, View.GONE)
            largeConstraintSet.setVisibility(R.id.credential_view, View.VISIBLE)
            largeConstraintSet.setVisibility(R.id.fallback_view, View.GONE)
        }

        // TODO: Investigate better way to handle 180 rotations
        val flipConstraintSet = ConstraintSet()

        fun setVisibilities(hideSensorIcon: Boolean, size: PromptSize) {
            viewsToHideWhenSmall.forEach { it.showContentOrHide(forceHide = size.isSmall) }
            largeConstraintSet.setVisibility(iconHolderView.id, View.GONE)
            largeConstraintSet.setVisibility(R.id.auth_screen, View.GONE)
            largeConstraintSet.setVisibility(R.id.fallback_view, View.GONE)

            if (hideSensorIcon) {
                smallConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                smallConstraintSet.setVisibility(R.id.indicator, View.GONE)
                mediumConstraintSet.setVisibility(iconHolderView.id, View.GONE)
                mediumConstraintSet.setVisibility(R.id.indicator, View.GONE)
            }
        }

        view.repeatWhenAttached {
            if (Flags.bpFallbackOptions()) {
                lifecycleScope.launch {
                    viewModel.layoutState.collect { currentState ->
                        val nextConstraintSet = ConstraintSet()
                        nextConstraintSet.clone(baseConstraintSet)

                        // Handle guidelines
                        val bottomInset =
                            windowManager.maximumWindowMetrics.windowInsets
                                .getInsets(WindowInsets.Type.navigationBars())
                                .bottom
                        currentState.guidelineBounds.let { bounds ->
                            nextConstraintSet.setGuidelineEnd(R.id.bottomGuideline, bottomInset)

                            if (bounds.left >= 0) {
                                nextConstraintSet.setGuidelineBegin(R.id.leftGuideline, bounds.left)
                            } else if (bounds.left < 0) {
                                nextConstraintSet.setGuidelineEnd(
                                    R.id.leftGuideline,
                                    abs(bounds.left),
                                )
                            }
                            if (bounds.right >= 0) {
                                nextConstraintSet.setGuidelineEnd(R.id.rightGuideline, bounds.right)
                            } else if (bounds.right < 0) {
                                nextConstraintSet.setGuidelineBegin(
                                    R.id.rightGuideline,
                                    abs(bounds.right),
                                )
                            }
                            if (bounds.top >= 0) {
                                nextConstraintSet.setGuidelineBegin(R.id.topGuideline, bounds.top)
                            } else if (bounds.top < 0) {
                                nextConstraintSet.setGuidelineEnd(
                                    R.id.topGuideline,
                                    abs(bounds.top),
                                )
                            }

                            if (view.findViewById<View>(R.id.midGuideline) != null) {
                                val left =
                                    if (bounds.left >= 0) {
                                        abs(bounds.left)
                                    } else {
                                        view.width - abs(bounds.left)
                                    }
                                val right =
                                    if (bounds.right >= 0) {
                                        view.width - abs(bounds.right)
                                    } else {
                                        abs(bounds.right)
                                    }
                                val mid = (left + right) / 2
                                nextConstraintSet.setGuidelineBegin(R.id.midGuideline, mid)
                            }
                        }

                        // Handle content visibility
                        val isSmall = currentState.size.isSmall
                        val isCredential = currentState.activeView == BiometricPromptView.CREDENTIAL
                        val isFallback = currentState.activeView == BiometricPromptView.FALLBACK
                        val isBiometric = currentState.activeView == BiometricPromptView.BIOMETRIC

                        // Handle hidden views for small prompt
                        viewsToHideWhenSmall.forEach { view -> view.showContentOrHide(isSmall) }

                        // Handle fullscreen credential
                        if (isCredential) {
                            nextConstraintSet.constrainMaxWidth(R.id.panel, 0)
                            nextConstraintSet.setGuidelineBegin(R.id.leftGuideline, 0)
                            nextConstraintSet.setGuidelineBegin(R.id.topGuideline, 0)
                            nextConstraintSet.setGuidelineEnd(R.id.rightGuideline, 0)
                        }

                        // Handle fallback max width
                        if (isFallback) {
                            nextConstraintSet.constrainMaxWidth(
                                R.id.panel,
                                view.resources.getDimensionPixelSize(
                                    R.dimen.biometric_prompt_panel_max_width
                                ),
                            )
                        }

                        // Handle current active view
                        nextConstraintSet.setVisibility(
                            R.id.credential_view,
                            if (isCredential) View.VISIBLE else View.GONE,
                        )
                        nextConstraintSet.setVisibility(
                            R.id.fallback_view,
                            if (isFallback) View.VISIBLE else View.GONE,
                        )
                        nextConstraintSet.setVisibility(
                            R.id.auth_screen,
                            if (isBiometric) View.VISIBLE else View.GONE,
                        )

                        // Handle icon visibility
                        val showIcon = isBiometric && !currentState.hideSensorIcon
                        nextConstraintSet.setVisibility(
                            R.id.biometric_icon,
                            if (showIcon) View.VISIBLE else View.GONE,
                        )

                        // Handle icon position and size
                        nextConstraintSet.constrainWidth(
                            R.id.biometric_icon,
                            currentState.iconSize.first,
                        )
                        nextConstraintSet.constrainHeight(
                            R.id.biometric_icon,
                            currentState.iconSize.second,
                        )
                        currentState.iconPosition.let { iconPosition ->
                            nextConstraintSet.applyMarginConstraint(
                                R.id.biometric_icon,
                                ConstraintSet.LEFT,
                                iconPosition.left,
                                ConstraintSet.RIGHT,
                            )
                            nextConstraintSet.applyMarginConstraint(
                                R.id.biometric_icon,
                                ConstraintSet.RIGHT,
                                iconPosition.right,
                                ConstraintSet.LEFT,
                            )
                            nextConstraintSet.applyMarginConstraint(
                                R.id.biometric_icon,
                                ConstraintSet.TOP,
                                iconPosition.top,
                                ConstraintSet.BOTTOM,
                            )
                            nextConstraintSet.applyMarginConstraint(
                                R.id.biometric_icon,
                                ConstraintSet.BOTTOM,
                                iconPosition.bottom,
                                ConstraintSet.TOP,
                            )
                        }

                        // Handle landscape flip logic
                        if (currentState.position.isLeft) {
                            nextConstraintSet.clear(R.id.scrollView, ConstraintSet.LEFT)
                            nextConstraintSet.clear(R.id.scrollView, ConstraintSet.RIGHT)
                            nextConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.LEFT,
                                R.id.midGuideline,
                                ConstraintSet.LEFT,
                            )
                            nextConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.RIGHT,
                                R.id.rightGuideline,
                                ConstraintSet.RIGHT,
                            )
                        }

                        // Handle animation
                        if (previousLayoutState != null && previousLayoutState != currentState) {
                            val duration =
                                if (
                                    previousLayoutState!!.size.isMedium && currentState.size.isLarge
                                )
                                    ANIMATE_MEDIUM_TO_LARGE_DURATION_MS
                                else ANIMATE_SMALL_TO_MEDIUM_DURATION_MS
                            val transition = AutoTransition().setDuration(duration.toLong())
                            TransitionManager.beginDelayedTransition(view, transition)
                        }

                        nextConstraintSet.applyTo(view)

                        previousLayoutState = currentState
                        currentPosition = currentState.position
                        currentView = currentState.activeView
                        panelView.invalidateOutline()
                    }
                }
            } else {
                lifecycleScope.launch {
                    viewModel.iconViewModel.iconPosition.collect { position ->
                        if (position != Rect()) {
                            val iconParams =
                                iconHolderView.layoutParams as ConstraintLayout.LayoutParams

                            if (position.left != 0) {
                                iconParams.endToEnd = ConstraintSet.UNSET
                                iconParams.leftMargin = position.left
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.RIGHT)
                                mediumConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.LEFT,
                                )
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    position.left,
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.RIGHT)
                                smallConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.LEFT,
                                )
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.LEFT,
                                    position.left,
                                )
                            }
                            if (position.top != 0) {
                                iconParams.bottomToBottom = ConstraintSet.UNSET
                                iconParams.topMargin = position.top
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.BOTTOM)
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.TOP,
                                    position.top,
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.BOTTOM)
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.TOP,
                                    position.top,
                                )
                            }
                            if (position.right != 0) {
                                iconParams.startToStart = ConstraintSet.UNSET
                                iconParams.rightMargin = position.right
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.LEFT)
                                mediumConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.RIGHT,
                                )
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    position.right,
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.LEFT)
                                smallConstraintSet.connect(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    ConstraintSet.PARENT_ID,
                                    ConstraintSet.RIGHT,
                                )
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.RIGHT,
                                    position.right,
                                )
                            }
                            if (position.bottom != 0) {
                                iconParams.topToTop = ConstraintSet.UNSET
                                iconParams.bottomMargin = position.bottom
                                mediumConstraintSet.clear(R.id.biometric_icon, ConstraintSet.TOP)
                                mediumConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.BOTTOM,
                                    position.bottom,
                                )
                                smallConstraintSet.clear(R.id.biometric_icon, ConstraintSet.TOP)
                                smallConstraintSet.setMargin(
                                    R.id.biometric_icon,
                                    ConstraintSet.BOTTOM,
                                    position.bottom,
                                )
                            }
                            iconHolderView.layoutParams = iconParams
                        }
                    }
                }

                lifecycleScope.launch {
                    combine(
                        viewModel.isUdfpsIndicating,
                        viewModel.position,
                        viewModel.iconViewModel.iconPosition,
                        viewModel.iconViewModel.iconSize,
                        viewModel.indicatorMessageWidthPx,
                        ::Quint
                    )
                        .collect {
                            (
                                isUdfpsIndicating,
                                position,
                                iconPosition,
                                iconSize,
                                indicatorMessageWidthPx
                            ) ->
                            if (!isUdfpsIndicating) return@collect
                            when (position) {
                                PromptPosition.Bottom -> {
                                    val indicatorMarginTop =
                                        (indicatorView.layoutParams as MarginLayoutParams).topMargin
                                    val indicatorHeight = indicatorView.measuredHeight +
                                        indicatorMarginTop
                                    val bottomInset = Utils.getNavbarInsets(view.context).bottom
                                    val marginBottom = view.resources.getDimensionPixelSize(
                                        R.dimen.biometric_indicator_above_margin_bottom
                                    )
                                    // move the indicator text above udfps icon if we don't have
                                    // enough space
                                    if (indicatorHeight + bottomInset > iconPosition.bottom) {
                                        mediumConstraintSet.apply {
                                            clear(
                                                R.id.indicator,
                                                ConstraintSet.TOP
                                            )
                                            setVerticalBias(
                                                R.id.indicator,
                                                1.0f
                                            )
                                            setVerticalBias(
                                                R.id.scrollView,
                                                0f
                                            )
                                            connect(
                                                R.id.indicator,
                                                ConstraintSet.BOTTOM,
                                                R.id.biometric_icon,
                                                ConstraintSet.TOP
                                            )
                                            setMargin(
                                                R.id.indicator,
                                                ConstraintSet.BOTTOM,
                                                marginBottom
                                            )
                                            setMargin(
                                                R.id.scrollView,
                                                ConstraintSet.BOTTOM,
                                                indicatorMarginTop
                                            )
                                        }
                                        indicatorView
                                            .updateLayoutParams<ConstraintLayout.LayoutParams> {
                                                topToBottom = ConstraintLayout.LayoutParams.UNSET
                                                bottomToTop = R.id.biometric_icon
                                                bottomMargin = marginBottom
                                            }
                                        scrollView
                                            .updateLayoutParams<ConstraintLayout.LayoutParams> {
                                                bottomMargin = indicatorMarginTop
                                            }
                                    }
                                }
                                // prevent the text from going out of screen bounds as it is
                                // centered below the udfps icon in landscape positions
                                PromptPosition.Right -> {
                                    val rightInset = Utils.getNavbarInsets(view.context).right
                                    val rightMargin = iconPosition.right - (
                                        (indicatorMessageWidthPx - iconSize.first) / 2
                                    ) - rightInset
                                    if (rightMargin < 0) {
                                        indicatorView.setPadding(
                                            0,
                                            indicatorView.paddingTop,
                                            -rightMargin,
                                            indicatorView.paddingBottom
                                        )
                                    }
                                }
                                PromptPosition.Left -> {
                                    val leftInset = Utils.getNavbarInsets(view.context).left
                                    val leftMargin = iconPosition.left - (
                                        (indicatorMessageWidthPx - iconSize.first) / 2
                                    ) - leftInset
                                    if (leftMargin < 0) {
                                        indicatorView.setPadding(
                                            -leftMargin,
                                            indicatorView.paddingTop,
                                            0,
                                            indicatorView.paddingBottom
                                        )
                                    }
                                }
                                // ignore top position as we typically don't allow 180 degree
                                // rotation on udfps devices
                                else -> return@collect
                            }
                        }
                }

                lifecycleScope.launch {
                    viewModel.iconViewModel.iconSize.collect { iconSize ->
                        iconHolderView.layoutParams.width = iconSize.first
                        iconHolderView.layoutParams.height = iconSize.second
                        mediumConstraintSet.constrainWidth(R.id.biometric_icon, iconSize.first)
                        mediumConstraintSet.constrainHeight(R.id.biometric_icon, iconSize.second)
                    }
                }

                lifecycleScope.launch {
                    viewModel.guidelineBounds.collect { bounds ->
                        val bottomInset =
                            windowManager.maximumWindowMetrics.windowInsets
                                .getInsets(WindowInsets.Type.navigationBars())
                                .bottom
                        mediumConstraintSet.setGuidelineEnd(R.id.bottomGuideline, bottomInset)

                        if (bounds.left >= 0) {
                            mediumConstraintSet.setGuidelineBegin(leftGuideline.id, bounds.left)
                            smallConstraintSet.setGuidelineBegin(leftGuideline.id, bounds.left)
                        } else if (bounds.left < 0) {
                            mediumConstraintSet.setGuidelineEnd(leftGuideline.id, abs(bounds.left))
                            smallConstraintSet.setGuidelineEnd(leftGuideline.id, abs(bounds.left))
                        }

                        if (bounds.right >= 0) {
                            mediumConstraintSet.setGuidelineEnd(rightGuideline.id, bounds.right)
                            smallConstraintSet.setGuidelineEnd(rightGuideline.id, bounds.right)
                        } else if (bounds.right < 0) {
                            mediumConstraintSet.setGuidelineBegin(
                                rightGuideline.id,
                                abs(bounds.right),
                            )
                            smallConstraintSet.setGuidelineBegin(
                                rightGuideline.id,
                                abs(bounds.right),
                            )
                        }

                        if (bounds.top >= 0) {
                            mediumConstraintSet.setGuidelineBegin(topGuideline.id, bounds.top)
                            smallConstraintSet.setGuidelineBegin(topGuideline.id, bounds.top)
                        } else if (bounds.top < 0) {
                            mediumConstraintSet.setGuidelineEnd(topGuideline.id, abs(bounds.top))
                            smallConstraintSet.setGuidelineEnd(topGuideline.id, abs(bounds.top))
                        }

                        if (midGuideline != null) {
                            val left =
                                if (bounds.left >= 0) {
                                    abs(bounds.left)
                                } else {
                                    view.width - abs(bounds.left)
                                }
                            val right =
                                if (bounds.right >= 0) {
                                    view.width - abs(bounds.right)
                                } else {
                                    abs(bounds.right)
                                }
                            val mid = (left + right) / 2
                            mediumConstraintSet.setGuidelineBegin(midGuideline.id, mid)
                        }
                    }
                }

                if (!Flags.bpFallbackOptions()) {
                    lifecycleScope.launch {
                        combine(viewModel.hideSensorIcon, viewModel.size, ::Pair).collect {
                            (hideSensorIcon, size) ->
                            setVisibilities(hideSensorIcon, size)
                        }
                    }
                }

                lifecycleScope.launch {
                    combine(viewModel.position, viewModel.size, ::Pair).collect { (position, size)
                        ->
                        if (position.isLeft) {
                            if (size.isSmall) {
                                flipConstraintSet.clone(smallConstraintSet)
                            } else {
                                flipConstraintSet.clone(mediumConstraintSet)
                            }

                            // Move all content to other panel
                            flipConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.LEFT,
                                R.id.midGuideline,
                                ConstraintSet.LEFT,
                            )
                            flipConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.RIGHT,
                                R.id.rightGuideline,
                                ConstraintSet.RIGHT,
                            )
                        } else if (position.isTop) {
                            // Top position is only used for 180 rotation Udfps
                            // Requires repositioning due to sensor location at top of screen
                            mediumConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.TOP,
                                R.id.indicator,
                                ConstraintSet.BOTTOM,
                            )
                            mediumConstraintSet.connect(
                                R.id.scrollView,
                                ConstraintSet.BOTTOM,
                                R.id.button_bar,
                                ConstraintSet.TOP,
                            )
                            mediumConstraintSet.connect(
                                R.id.panel,
                                ConstraintSet.TOP,
                                R.id.biometric_icon,
                                ConstraintSet.TOP,
                            )
                            mediumConstraintSet.setMargin(
                                R.id.panel,
                                ConstraintSet.TOP,
                                (-24 * pxToDp).toInt(),
                            )
                            mediumConstraintSet.setVerticalBias(R.id.scrollView, 0f)
                        }

                        when {
                            size.isSmall -> {
                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else {
                                    smallConstraintSet.applyTo(view)
                                }
                            }

                            size.isMedium && currentSize.isSmall -> {
                                val autoTransition = AutoTransition()
                                autoTransition.setDuration(
                                    ANIMATE_SMALL_TO_MEDIUM_DURATION_MS.toLong()
                                )

                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else {
                                    mediumConstraintSet.applyTo(view)
                                }
                                TransitionManager.beginDelayedTransition(view, autoTransition)
                            }

                            size.isMedium -> {
                                if (position.isLeft) {
                                    flipConstraintSet.applyTo(view)
                                } else {
                                    mediumConstraintSet.applyTo(view)
                                }

                                val autoTransition = AutoTransition()
                                autoTransition.setDuration(
                                    ANIMATE_SMALL_TO_MEDIUM_DURATION_MS.toLong()
                                )
                                TransitionManager.beginDelayedTransition(view, autoTransition)
                            }

                            size.isLarge -> {
                                val autoTransition = AutoTransition()
                                autoTransition.setDuration(
                                    if (currentSize.isSmall) {
                                        ANIMATE_SMALL_TO_MEDIUM_DURATION_MS.toLong()
                                    } else {
                                        ANIMATE_MEDIUM_TO_LARGE_DURATION_MS.toLong()
                                    }
                                )

                                largeConstraintSet.applyTo(view)
                                TransitionManager.beginDelayedTransition(view, autoTransition)
                            }
                        }

                        currentSize = size
                        currentPosition = position
                        notifyAccessibilityChanged()

                        panelView.invalidateOutline()
                        view.invalidate()
                        view.requestLayout()
                    }
                }
            }
        }
    }
}

private fun View.showContentOrHide(forceHide: Boolean = false) {
    val isTextViewWithBlankText = this is TextView && this.text.isBlank()
    val isImageViewWithoutImage = this is ImageView && this.drawable == null
    visibility =
        if (forceHide || isTextViewWithBlankText || isImageViewWithoutImage) {
            View.GONE
        } else {
            View.VISIBLE
        }
}

private fun ConstraintSet.applyMarginConstraint(
    viewId: Int,
    side: Int,
    margin: Int,
    oppositeSide: Int,
) {
    if (margin == 0) return
    clear(viewId, oppositeSide)
    if (side == ConstraintSet.LEFT || side == ConstraintSet.RIGHT) {
        connect(viewId, side, ConstraintSet.PARENT_ID, side)
    }
    setMargin(viewId, side, margin)
}
