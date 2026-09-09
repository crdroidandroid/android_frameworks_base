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

package com.android.systemui.keyguard.ui.binder

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable2
import android.graphics.drawable.LayerDrawable
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.animation.CycleInterpolator
import androidx.core.animation.ObjectAnimator
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.keyguard.logging.KeyguardQuickAffordancesLogger
import com.android.systemui.Flags.enableLockscreenBlur
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.common.ui.view.BackgroundBlurAlphaSync
import com.android.systemui.common.ui.view.updateLongClickListener
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceHapticViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.doOnEnd
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import com.google.android.msdl.domain.MSDLPlayer
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** This is only for a SINGLE Quick affordance */
@SysUISingleton
class KeyguardQuickAffordanceViewBinder
@Inject
constructor(
    private val falsingManager: FalsingManager?,
    private val vibratorHelper: VibratorHelper?,
    private val msdlPlayer: MSDLPlayer,
    private val logger: KeyguardQuickAffordancesLogger,
    private val hapticsViewModelFactory: KeyguardQuickAffordanceHapticViewModel.Factory,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
) {

    private val EXIT_DOZE_BUTTON_REVEAL_ANIMATION_DURATION_MS = 250L
    private val SCALE_SELECTED_BUTTON = 1.23f
    private val DIM_ALPHA = 0.3f
    private val TAG = "KeyguardQuickAffordanceViewBinder"

    /**
     * Defines interface for an object that acts as the binding between the view and its view-model.
     */
    interface Binding {
        /** Notifies that device configuration has changed. */
        fun onConfigurationChanged()

        /** Destroys this binding, releases resources, and cancels any coroutines. */
        fun destroy()
    }

    fun bind(
        view: LaunchableImageView,
        viewModel: Flow<KeyguardQuickAffordanceViewModel>,
        alpha: Flow<Float>,
        messageDisplayer: (Int) -> Unit,
    ): Binding {
        val button = view as ImageView
        val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))
        val hapticsViewModel = hapticsViewModelFactory.create()
        val cornerRadius = view.resources.getDimension(R.dimen.keyguard_affordance_fixed_radius)
        val blurRadius =
            view.resources.getDimensionPixelSize(R.dimen.keyguard_shortcuts_blur_radius)

        val disposableHandle =
            view.repeatWhenAttached {
                if (enableLockscreenBlur() && view.background !is LayerDrawable) {
                    val blurDrawable =
                        view.viewRootImpl.createBackgroundBlurDrawable().apply {
                            setCornerRadius(cornerRadius)
                            setBlurRadius(blurRadius)
                            setVisible(false, false)
                        }
                    val surfaceDrawable = view.background
                    view.background = LayerDrawable(arrayOf(blurDrawable, surfaceDrawable))
                }
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.collect { buttonModel ->
                            updateButton(
                                view = button,
                                viewModel = buttonModel,
                                hapticsViewModel,
                                messageDisplayer = messageDisplayer,
                            )
                        }
                    }

                    launch {
                        updateButtonAlpha(view = button, viewModel = viewModel, alphaFlow = alpha)
                    }

                    launch {
                        configurationBasedDimensions.collect { dimensions ->
                            button.updateLayoutParams<ViewGroup.LayoutParams> {
                                width = dimensions.buttonSizePx.width
                                height = dimensions.buttonSizePx.height
                            }
                        }
                    }

                    if (enableLockscreenBlur()) {
                        launch {
                            combine(
                                    windowRootViewBlurInteractor.isBlurCurrentlySupported,
                                    viewModel,
                                    { isSupported, viewModel ->
                                        updateBackground(viewModel, view, isSupported)
                                    },
                                )
                                .collect {}
                        }

                        // The blur region only knows the drawable's own alpha: follow the alpha
                        // (and visibility) the button is really drawn with - its own/dimmed alpha,
                        // keyguard root fade on unlock, shade drag - so the blur does not outlive
                        // the button. This is the only writer of the blur drawable's alpha.
                        // Tied to this scope, so it also stops on destroy() while still attached.
                        launch {
                            val blurAlphaSync =
                                BackgroundBlurAlphaSync(view) {
                                        (view.background as? LayerDrawable)?.getDrawable(0)
                                            as? BackgroundBlurDrawable
                                    }
                                    .start()
                            try {
                                awaitCancellation()
                            } finally {
                                blurAlphaSync.dispose()
                            }
                        }
                    }
                }
            }

        return object : Binding {
            override fun onConfigurationChanged() {
                configurationBasedDimensions.value = loadFromResources(view)
            }

            override fun destroy() {
                view.setOnApplyWindowInsetsListener(null)
                disposableHandle.dispose()
            }
        }
    }

    private fun updateBackground(
        viewModel: KeyguardQuickAffordanceViewModel,
        view: View,
        isBlurSupported: Boolean,
    ) {
        if (enableLockscreenBlur() && view.background is LayerDrawable) {
            val blurDrawable =
                (view.background as LayerDrawable).getDrawable(0) as BackgroundBlurDrawable
            blurDrawable.setVisible(
                isBlurSupported && viewModel.isSelected && !viewModel.isActivated,
                false,
            )
            (view.background as LayerDrawable)
                .getDrawable(1)
                .setTintList(getBackgroundTintList(viewModel, view, isBlurSupported))
        } else {
            view.backgroundTintList = getBackgroundTintList(viewModel, view, isBlurSupported)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateButton(
        view: ImageView,
        viewModel: KeyguardQuickAffordanceViewModel,
        hapticsViewModel: KeyguardQuickAffordanceHapticViewModel,
        messageDisplayer: (Int) -> Unit,
    ) {
        hapticsViewModel.updateActivatedHistory(viewModel.isActivated)
        logger.logUpdate(viewModel)
        if (!viewModel.isVisible) {
            view.isInvisible = true
            return
        }

        if (!view.isVisible) {
            view.isVisible = true
        }

        IconViewBinder.bind(viewModel.icon, view)

        (view.drawable as? Animatable2)?.let { animatable ->
            (viewModel.icon as? Icon.Resource)?.resId?.let { iconResourceId ->
                // Always start the animation (we do call stop() below, if we need to skip it).
                animatable.start()

                if (view.tag != iconResourceId) {
                    // Here when we haven't run the animation on a previous update.
                    //
                    // Save the resource ID for next time, so we know not to re-animate the same
                    // animation again.
                    view.tag = iconResourceId
                } else {
                    // Here when we've already done this animation on a previous update and want to
                    // skip directly to the final frame of the animation to avoid running it.
                    //
                    // By calling stop after start, we go to the final frame of the animation.
                    animatable.stop()
                }
            }
        }

        view.isActivated = viewModel.isActivated
        view.drawable.setTint(
            view.context.getColor(
                if (viewModel.isActivated) {
                    com.android.internal.R.color.materialColorOnPrimaryFixed
                } else {
                    com.android.internal.R.color.materialColorOnSurface
                }
            )
        )
        updateBackground(
            viewModel,
            view,
            windowRootViewBlurInteractor.isBlurCurrentlySupported.value,
        )

        view
            .animate()
            .scaleX(if (viewModel.isSelected) SCALE_SELECTED_BUTTON else 1f)
            .scaleY(if (viewModel.isSelected) SCALE_SELECTED_BUTTON else 1f)
            .start()

        view.isClickable = viewModel.isClickable
        if (viewModel.isClickable) {
            if (viewModel.useLongPress) {
                val onTouchListener =
                    KeyguardQuickAffordanceOnTouchListener(
                        view,
                        viewModel,
                        messageDisplayer,
                        vibratorHelper,
                        falsingManager,
                    )
                view.setOnTouchListener(onTouchListener)
                view.setOnClickListener {
                    messageDisplayer.invoke(R.string.keyguard_affordance_press_too_short)
                    val amplitude =
                        view.context.resources
                            .getDimensionPixelSize(R.dimen.keyguard_affordance_shake_amplitude)
                            .toFloat()
                    val shakeAnimator =
                        ObjectAnimator.ofFloat(view, "translationX", -amplitude / 2, amplitude / 2)
                    shakeAnimator.duration =
                        KeyguardBottomAreaVibrations.ShakeAnimationDuration.inWholeMilliseconds
                    shakeAnimator.interpolator =
                        CycleInterpolator(KeyguardBottomAreaVibrations.ShakeAnimationCycles)
                    shakeAnimator.doOnEnd { view.translationX = 0f }
                    shakeAnimator.start()

                    hapticsViewModel.onQuickAffordanceClick()
                    logger.logQuickAffordanceTapped(viewModel.configKey)
                }
                view.onLongClickListener =
                    OnLongClickListener(
                        falsingManager,
                        viewModel,
                        hapticsViewModel,
                        onTouchListener,
                    )
            } else {
                view.setOnClickListener(OnClickListener(viewModel, checkNotNull(falsingManager)))
                view.updateLongClickListener(null)
            }
        } else {
            view.onLongClickListener = null
            view.setOnClickListener(null)
            view.setOnTouchListener(null)
        }

        view.isSelected = viewModel.isSelected
    }

    private suspend fun updateButtonAlpha(
        view: View,
        viewModel: Flow<KeyguardQuickAffordanceViewModel>,
        alphaFlow: Flow<Float>,
    ) {
        combine(viewModel.map { it.isDimmed }, alphaFlow) { isDimmed, alpha ->
                if (isDimmed) DIM_ALPHA else alpha
            }
            .collect {
                // With lockscreen blur, BackgroundBlurAlphaSync folds the view alpha into the
                // blur drawable on the next frame.
                view.alpha = it
            }
    }

    private fun getBackgroundTintList(
        viewModel: KeyguardQuickAffordanceViewModel,
        view: View,
        isBlurSupported: Boolean,
    ): ColorStateList? {
        return if (!viewModel.isSelected) {
            ColorStateList.valueOf(
                view.context.getColor(
                    if (viewModel.isActivated) {
                        com.android.internal.R.color.materialColorPrimaryFixed
                    } else if (enableLockscreenBlur() && isBlurSupported) {
                        com.android.internal.R.color.customColorSurfaceEffect1
                    } else {
                        com.android.internal.R.color.materialColorSurfaceContainerHigh
                    }
                )
            )
        } else {
            null
        }
    }

    private fun loadFromResources(view: View): ConfigurationBasedDimensions {
        return ConfigurationBasedDimensions(
            buttonSizePx =
                Size(
                    view.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width),
                    view.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height),
                )
        )
    }

    private class OnClickListener(
        private val viewModel: KeyguardQuickAffordanceViewModel,
        private val falsingManager: FalsingManager,
    ) : View.OnClickListener {
        override fun onClick(view: View) {
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return
            }

            if (viewModel.configKey != null) {
                viewModel.onClicked(
                    KeyguardQuickAffordanceViewModel.OnClickedParameters(
                        configKey = viewModel.configKey,
                        expandable = Expandable.fromView(view),
                        slotId = viewModel.slotId,
                    )
                )
            }
        }
    }

    private class OnLongClickListener(
        private val falsingManager: FalsingManager?,
        private val viewModel: KeyguardQuickAffordanceViewModel,
        private val hapticsViewModel: KeyguardQuickAffordanceHapticViewModel,
        private val onTouchListener: KeyguardQuickAffordanceOnTouchListener,
    ) : View.OnLongClickListener {
        override fun onLongClick(view: View): Boolean {
            if (falsingManager?.isFalseLongTap(FalsingManager.MODERATE_PENALTY) == true) {
                return true
            }

            if (viewModel.configKey != null) {
                hapticsViewModel.onQuickAffordanceLongPress(viewModel.isActivated)
                viewModel.onClicked(
                    KeyguardQuickAffordanceViewModel.OnClickedParameters(
                        configKey = viewModel.configKey,
                        expandable = Expandable.fromView(view),
                        slotId = viewModel.slotId,
                    )
                )
            }

            onTouchListener.cancel()
            return true
        }

        override fun onLongClickUseDefaultHapticFeedback(view: View) = false
    }

    private data class ConfigurationBasedDimensions(val buttonSizePx: Size)
}
