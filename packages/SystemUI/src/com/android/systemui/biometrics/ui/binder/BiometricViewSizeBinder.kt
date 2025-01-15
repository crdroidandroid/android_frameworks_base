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

import android.content.pm.PackageManager
import android.graphics.Outline
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.domain.interactor.BiometricPromptView
import com.android.systemui.biometrics.ui.BiometricPromptLayoutState
import com.android.systemui.biometrics.ui.PromptPosition
import com.android.systemui.biometrics.ui.isCenter
import com.android.systemui.biometrics.ui.isLarge
import com.android.systemui.biometrics.ui.isLeft
import com.android.systemui.biometrics.ui.isMedium
import com.android.systemui.biometrics.ui.isSmall
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.Quint
import com.android.systemui.utils.windowmanager.WindowManagerUtils
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
        val isLargeScreen =
            Flags.largeScreenBp() &&
                !view.context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) &&
                !view.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val windowManager = WindowManagerUtils.getWindowManager(view.context)

        val indicatorView = view.requireViewById<View>(R.id.indicator)
        val scrollView = view.requireViewById<View>(R.id.scrollView)
        val panelView = view.requireViewById<View>(R.id.panel)
        val cornerRadius = view.resources.getDimension(R.dimen.biometric_dialog_corner_size)
        val pxToDp =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                1f,
                view.resources.displayMetrics,
            )
        val cornerRadiusPx = (pxToDp * cornerRadius).toInt()

        var currentPosition: PromptPosition = PromptPosition.Bottom
        var currentView: BiometricPromptView? = null
        var previousLayoutState: BiometricPromptLayoutState? = null
        panelView.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (!isLargeScreen && currentView == BiometricPromptView.CREDENTIAL) {
                        outline.setRect(0, 0, view.width, view.height)
                        return
                    }

                    when (currentPosition) {
                        PromptPosition.Center -> {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width,
                                view.height,
                                cornerRadiusPx.toFloat(),
                            )
                        }

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
        largeConstraintSet.setVisibility(R.id.auth_screen, View.GONE)
        largeConstraintSet.setVisibility(R.id.credential_view, View.VISIBLE)
        largeConstraintSet.setVisibility(R.id.fallback_view, View.GONE)

        view.repeatWhenAttached {
            lifecycleScope.launch {
                combine(
                    viewModel.isUdfpsIndicating,
                    viewModel.position,
                    viewModel.iconViewModel.iconPosition,
                    viewModel.iconViewModel.iconSize,
                    viewModel.indicatorMessageWidthPx,
                    ::Quint,
                )
                    .collect {
                        (
                            isUdfpsIndicating,
                            position,
                            iconPosition,
                            iconSize,
                            indicatorMessageWidthPx,
                        ) ->
                        if (!isUdfpsIndicating) return@collect
                        when (position) {
                            PromptPosition.Bottom -> {
                                val indicatorMarginTop =
                                    (indicatorView.layoutParams as MarginLayoutParams).topMargin
                                val indicatorHeight =
                                    indicatorView.measuredHeight + indicatorMarginTop
                                val bottomInset = Utils.getNavbarInsets(view.context).bottom
                                val marginBottom =
                                    view.resources.getDimensionPixelSize(
                                        R.dimen.biometric_indicator_above_margin_bottom
                                    )
                                // move the indicator text above udfps icon if we don't have
                                // enough space
                                if (indicatorHeight + bottomInset > iconPosition.bottom) {
                                    mediumConstraintSet.apply {
                                        clear(R.id.indicator, ConstraintSet.TOP)
                                        setVerticalBias(R.id.indicator, 1.0f)
                                        setVerticalBias(R.id.scrollView, 0f)
                                        connect(
                                            R.id.indicator,
                                            ConstraintSet.BOTTOM,
                                            R.id.biometric_icon,
                                            ConstraintSet.TOP,
                                        )
                                        setMargin(
                                            R.id.indicator,
                                            ConstraintSet.BOTTOM,
                                            marginBottom,
                                        )
                                        setMargin(
                                            R.id.scrollView,
                                            ConstraintSet.BOTTOM,
                                            indicatorMarginTop,
                                        )
                                    }
                                    indicatorView.updateLayoutParams<
                                        ConstraintLayout.LayoutParams
                                    > {
                                        topToBottom = ConstraintLayout.LayoutParams.UNSET
                                        bottomToTop = R.id.biometric_icon
                                        bottomMargin = marginBottom
                                    }
                                    scrollView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                                        bottomMargin = indicatorMarginTop
                                    }
                                }
                            }
                            // prevent the text from going out of screen bounds as it is
                            // centered below the udfps icon in landscape positions
                            PromptPosition.Right -> {
                                val rightInset = Utils.getNavbarInsets(view.context).right
                                val rightMargin =
                                    iconPosition.right -
                                        ((indicatorMessageWidthPx - iconSize.first) / 2) -
                                        rightInset
                                if (rightMargin < 0) {
                                    indicatorView.setPadding(
                                        0,
                                        indicatorView.paddingTop,
                                        -rightMargin,
                                        indicatorView.paddingBottom,
                                    )
                                }
                            }
                            PromptPosition.Left -> {
                                val leftInset = Utils.getNavbarInsets(view.context).left
                                val leftMargin =
                                    iconPosition.left -
                                        ((indicatorMessageWidthPx - iconSize.first) / 2) -
                                        leftInset
                                if (leftMargin < 0) {
                                    indicatorView.setPadding(
                                        -leftMargin,
                                        indicatorView.paddingTop,
                                        0,
                                        indicatorView.paddingBottom,
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
                viewModel.layoutState.collect { currentState ->
                    val nextConstraintSet = ConstraintSet()
                    nextConstraintSet.clone(baseConstraintSet)

                    // Handle guidelines
                    val bottomInset =
                        windowManager.maximumWindowMetrics.windowInsets
                            .getInsets(WindowInsets.Type.navigationBars())
                            .bottom
                    val topInset =
                        windowManager.maximumWindowMetrics.windowInsets
                            .getInsets(WindowInsets.Type.statusBars())
                            .top
                    currentState.guidelineBounds.let { bounds ->
                        nextConstraintSet.setGuidelineEnd(R.id.bottomGuideline, bottomInset)

                        if (bounds.left >= 0) {
                            nextConstraintSet.setGuidelineBegin(R.id.leftGuideline, bounds.left)
                        } else if (bounds.left < 0) {
                            nextConstraintSet.setGuidelineEnd(R.id.leftGuideline, abs(bounds.left))
                        }
                        if (bounds.right >= 0) {
                            nextConstraintSet.setGuidelineEnd(R.id.rightGuideline, bounds.right)
                        } else if (bounds.right < 0) {
                            nextConstraintSet.setGuidelineBegin(
                                R.id.rightGuideline,
                                abs(bounds.right),
                            )
                        }

                        if (currentState.position == PromptPosition.Bottom) {
                            nextConstraintSet.setGuidelineBegin(R.id.topGuideline, topInset)
                        } else if (bounds.top >= 0) {
                            nextConstraintSet.setGuidelineBegin(R.id.topGuideline, bounds.top)
                        } else if (bounds.top < 0) {
                            nextConstraintSet.setGuidelineEnd(R.id.topGuideline, abs(bounds.top))
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

                    // Handle center positioning for large screen
                    if (currentState.position.isCenter && isLargeScreen) {
                        val activeViewId =
                            when {
                                isFallback -> R.id.fallback_view
                                isCredential -> R.id.compose_credential_view
                                else -> R.id.biometric_flow
                            }

                        nextConstraintSet.clear(R.id.panel)
                        nextConstraintSet.clear(activeViewId)

                        if (activeViewId == R.id.biometric_flow) {
                            nextConstraintSet.setVisibility(R.id.biometric_flow, View.VISIBLE)
                            val flow = view.requireViewById<Flow>(R.id.biometric_flow)
                            flow.referencedIds =
                                intArrayOf(
                                    R.id.scrollView,
                                    R.id.biometric_icon,
                                    R.id.indicator,
                                    R.id.button_bar,
                                )
                        }

                        nextConstraintSet.connect(
                            activeViewId,
                            ConstraintSet.TOP,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.TOP,
                        )
                        nextConstraintSet.connect(
                            activeViewId,
                            ConstraintSet.BOTTOM,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.BOTTOM,
                        )
                        nextConstraintSet.connect(
                            activeViewId,
                            ConstraintSet.START,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.START,
                        )
                        nextConstraintSet.connect(
                            activeViewId,
                            ConstraintSet.END,
                            ConstraintSet.PARENT_ID,
                            ConstraintSet.END,
                        )

                        nextConstraintSet.constrainHeight(activeViewId, ConstraintSet.WRAP_CONTENT)
                        nextConstraintSet.constrainWidth(
                            activeViewId,
                            ConstraintSet.MATCH_CONSTRAINT,
                        )
                        nextConstraintSet.setVerticalBias(activeViewId, 0.5f)

                        nextConstraintSet.connect(
                            R.id.panel,
                            ConstraintSet.TOP,
                            activeViewId,
                            ConstraintSet.TOP,
                        )
                        nextConstraintSet.connect(
                            R.id.panel,
                            ConstraintSet.BOTTOM,
                            activeViewId,
                            ConstraintSet.BOTTOM,
                        )
                        nextConstraintSet.connect(
                            R.id.panel,
                            ConstraintSet.START,
                            activeViewId,
                            ConstraintSet.START,
                        )
                        nextConstraintSet.connect(
                            R.id.panel,
                            ConstraintSet.END,
                            activeViewId,
                            ConstraintSet.END,
                        )

                        // Set the max width and height
                        val maxPanelWidth =
                            view.resources.getDimensionPixelSize(
                                R.dimen.biometric_prompt_panel_max_width
                            )
                        val verticalPaddingPx =
                            view.resources.getDimensionPixelSize(
                                R.dimen.biometric_prompt_center_vertical_padding
                            )
                        val maxPanelHeight =
                            view.resources.displayMetrics.heightPixels - (verticalPaddingPx * 2)
                        nextConstraintSet.constrainMaxWidth(activeViewId, maxPanelWidth)
                        nextConstraintSet.constrainMaxHeight(activeViewId, maxPanelHeight)
                        nextConstraintSet.constrainHeight(R.id.panel, 0)
                        nextConstraintSet.constrainWidth(R.id.panel, 0)
                    }

                    // Handle hidden views for small prompt
                    viewsToHideWhenSmall.forEach { view -> view.showContentOrHide(isSmall) }

                    // Handle fullscreen credential
                    if (!isLargeScreen && isCredential) {
                        nextConstraintSet.constrainMaxWidth(R.id.panel, 0)
                        nextConstraintSet.setGuidelineBegin(R.id.leftGuideline, 0)
                        nextConstraintSet.setGuidelineBegin(R.id.topGuideline, 0)
                        nextConstraintSet.setGuidelineEnd(R.id.rightGuideline, 0)
                    }

                    // Handle fallback max width
                    if (isFallback || (isCredential && isLargeScreen)) {
                        nextConstraintSet.constrainMaxWidth(
                            R.id.panel,
                            view.resources.getDimensionPixelSize(
                                R.dimen.biometric_prompt_panel_max_width
                            ),
                        )
                    }

                    // Handle current active view
                    nextConstraintSet.setVisibility(
                        if (isLargeScreen) {
                            R.id.compose_credential_view
                        } else {
                            R.id.credential_view
                        },
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

                    // Handle icon position and size - center icon is positioned relative to view
                    nextConstraintSet.constrainWidth(
                        R.id.biometric_icon,
                        currentState.iconSize.first,
                    )
                    nextConstraintSet.constrainHeight(
                        R.id.biometric_icon,
                        currentState.iconSize.second,
                    )
                    if (!currentState.position.isCenter) {
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

                        // Reattach close button
                        nextConstraintSet.clear(R.id.close_button, ConstraintSet.END)
                        nextConstraintSet.clear(R.id.close_button, ConstraintSet.RIGHT)
                        nextConstraintSet.connect(
                            R.id.close_button,
                            ConstraintSet.END,
                            R.id.scrollView,
                            ConstraintSet.END,
                            (24 * pxToDp).toInt(),
                        )
                    }

                    // Handle animation
                    if (previousLayoutState != null && previousLayoutState != currentState) {
                        val duration =
                            if (previousLayoutState!!.size.isMedium && currentState.size.isLarge)
                                ANIMATE_MEDIUM_TO_LARGE_DURATION_MS
                            else ANIMATE_SMALL_TO_MEDIUM_DURATION_MS
                        val transition = AutoTransition().setDuration(duration.toLong())
                        TransitionManager.beginDelayedTransition(view, transition)
                    }

                    nextConstraintSet.applyTo(view)

                    previousLayoutState = currentState
                    currentPosition = currentState.position
                    currentView = currentState.activeView

                    panelView.visibility = View.VISIBLE
                    panelView.invalidateOutline()
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
