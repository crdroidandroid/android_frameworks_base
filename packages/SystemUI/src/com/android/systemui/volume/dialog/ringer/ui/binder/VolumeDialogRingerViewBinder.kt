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

package com.android.systemui.volume.dialog.ringer.ui.binder

import android.animation.ArgbEvaluator
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.gui.EarlyWakeupInfo
import android.os.Binder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.View
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.internal.R as internalR
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.gradient.GradientSettings
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.util.VolumeDialogRingerDrawerTransitionListener
import com.android.systemui.volume.dialog.ringer.ui.util.updateCloseState
import com.android.systemui.volume.dialog.ringer.ui.util.updateOpenState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonUiModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerDrawerState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModelState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.VolumeDialogRingerDrawerViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import javax.inject.Inject
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll

private const val CLOSE_DRAWER_DELAY = 300L
// Ensure roundness and color of button is updated when progress is changed by a minimum fraction.
private const val BUTTON_MIN_VISIBLE_CHANGE = 0.05F

@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogScope
class VolumeDialogRingerViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogRingerDrawerViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
) : ViewBinder {

    private val roundnessSpringForce =
        SpringForce(1F).apply {
            stiffness = 800F
            dampingRatio = 0.6F
        }
    private val colorSpringForce =
        SpringForce(1F).apply {
            stiffness = 3800F
            dampingRatio = 1F
        }
    private val rgbEvaluator = ArgbEvaluator()
    private val transaction = SurfaceControl.Transaction()
    private var isInEarlyWakeUp = false
    private val earlyWakeupInfo =
        EarlyWakeupInfo().apply {
            token = Binder()
            trace = TAG
        }

    private val onAttachStateChangeListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
                    startEarlyWakeup()
                }
            }

            override fun onViewDetachedFromWindow(view: View) {
                if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
                    endEarlyWakeup()
                }
            }
        }

    override fun CoroutineScope.bind(view: View) {
        val volumeDialogBackgroundView = view.requireViewById<View>(R.id.volume_dialog_background)
        val ringerBackgroundView = view.requireViewById<View>(R.id.ringer_buttons_background)
        val drawerContainer = view.requireViewById<MotionLayout>(R.id.volume_ringer_drawer)

        val unselectedButtonUiModel = RingerButtonUiModel.getUnselectedButton(view.context)
        val selectedButtonUiModel = RingerButtonUiModel.getSelectedButton(view.context)
        val volumeDialogBgSmallRadius =
            view.context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_background_square_corner_radius
            )
        val volumeDialogBgFullRadius =
            view.context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_background_corner_radius
            )
        val bottomDefaultRadius = volumeDialogBgFullRadius.toFloat()
        val bottomCornerRadii =
            floatArrayOf(
                0F,
                0F,
                0F,
                0F,
                bottomDefaultRadius,
                bottomDefaultRadius,
                bottomDefaultRadius,
                bottomDefaultRadius,
            )
        var backgroundAnimationProgress: Float by
            Delegates.observable(0F) { _, _, progress ->
                ringerBackgroundView.applyCorners(
                    fullRadius = volumeDialogBgFullRadius,
                    diff = volumeDialogBgFullRadius - volumeDialogBgSmallRadius,
                    progress,
                )
            }
        val ringerDrawerTransitionListener = VolumeDialogRingerDrawerTransitionListener {
            backgroundAnimationProgress = it
        }
        drawerContainer.setTransitionListener(ringerDrawerTransitionListener)

        volumeDialogBackgroundView.updateBackground()
        ringerBackgroundView.updateBackground()
        launchTraced("VDRVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(ringerBackgroundView)
        }

        var gradientEnabled =
            GradientSettings.isEnabled(view.context, Settings.System.VOLUME_SLIDER_GRADIENT)
        var gradientColors = GradientSettings.argbColors(view.context)
        val gradientObserver =
            GradientSettings.registerObserver(
                view.context,
                Settings.System.VOLUME_SLIDER_GRADIENT,
            ) {
                gradientEnabled =
                    GradientSettings.isEnabled(view.context, Settings.System.VOLUME_SLIDER_GRADIENT)
                gradientColors = GradientSettings.argbColors(view.context)
            }
        coroutineContext.job.invokeOnCompletion {
            GradientSettings.unregisterObserver(view.context, gradientObserver)
        }

        if (viewModel.showBlur) {
            launchTraced("VDRVB#isBlurCurrentlySupported") {
                windowRootViewBlurInteractor.isBlurCurrentlySupported.collect { supported ->
                    if (view.isAttachedToWindow) {
                        if (supported) {
                            startEarlyWakeup()
                        } else {
                            endEarlyWakeup()
                        }
                    }

                    volumeDialogBackgroundView.setIsBlurSupported(supported)
                    ringerBackgroundView.setIsBlurSupported(supported)
                }
            }

            view.addOnAttachStateChangeListener(onAttachStateChangeListener)
        }

        viewModel.ringerViewModel
            .mapLatest { ringerState ->
                when (ringerState) {
                    is RingerViewModelState.Available -> {
                        val uiModel = ringerState.uiModel
                        val orientation =
                            if (
                                view.context.resources.getBoolean(
                                    R.bool.volume_dialog_ringer_drawer_should_open_to_the_side
                                )
                            ) {
                                ringerState.orientation
                            } else {
                                Configuration.ORIENTATION_PORTRAIT
                            }

                        // Set up view background and visibility
                        drawerContainer.visibility = View.VISIBLE
                        if (viewModel.showBlur) {
                            val layers = (volumeDialogBackgroundView.background as LayerDrawable)
                            val blurDrawable = layers.getDrawable(0) as BackgroundBlurDrawable
                            blurDrawable.setCornerRadius(
                                0f,
                                0f,
                                bottomDefaultRadius,
                                bottomDefaultRadius,
                            )
                            (layers.getDrawable(1) as GradientDrawable).cornerRadii =
                                bottomCornerRadii
                        } else {
                            (volumeDialogBackgroundView.background as GradientDrawable)
                                .cornerRadii = bottomCornerRadii
                        }
                        when (uiModel.drawerState) {
                            is RingerDrawerState.Initial -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                    gradientEnabled,
                                    gradientColors,
                                )
                                ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                drawerContainer.closeDrawer(
                                    ringerBackgroundView,
                                    uiModel.currentButtonIndex,
                                    orientation,
                                )
                            }
                            is RingerDrawerState.Closed -> {
                                if (
                                    uiModel.selectedButton.ringerMode ==
                                        uiModel.drawerState.currentMode
                                ) {
                                    drawerContainer.animateAndBindDrawerButtons(
                                        viewModel,
                                        uiModel,
                                        selectedButtonUiModel,
                                        unselectedButtonUiModel,
                                        gradientEnabled,
                                        gradientColors,
                                        onProgressChanged = { progress, isReverse ->
                                            // Let's make button progress when switching matches
                                            // motionLayout transition progress. When full
                                            // radius,
                                            // progress is 0.0. When small radius, progress is
                                            // 1.0.
                                            backgroundAnimationProgress =
                                                if (isReverse) {
                                                    1F - progress
                                                } else {
                                                    progress
                                                }
                                        },
                                    ) {
                                        if (
                                            uiModel.currentButtonIndex ==
                                                uiModel.availableButtons.size - 1
                                        ) {
                                            ringerDrawerTransitionListener.setProgressChangeEnabled(
                                                false
                                            )
                                        } else {
                                            ringerDrawerTransitionListener.setProgressChangeEnabled(
                                                true
                                            )
                                        }
                                        drawerContainer.closeDrawer(
                                            ringerBackgroundView,
                                            uiModel.currentButtonIndex,
                                            orientation,
                                        )
                                    }
                                }
                            }
                            is RingerDrawerState.Open -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                    gradientEnabled,
                                    gradientColors,
                                )
                                // Open drawer
                                if (
                                    uiModel.currentButtonIndex == uiModel.availableButtons.size - 1
                                ) {
                                    ringerDrawerTransitionListener.setProgressChangeEnabled(false)
                                } else {
                                    ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                }
                                updateOpenState(drawerContainer, orientation, ringerBackgroundView)
                                drawerContainer
                                    .getTransition(R.id.close_to_open_transition)
                                    .setInterpolatorInfo(
                                        MotionScene.Transition.INTERPOLATE_REFERENCE_ID,
                                        null,
                                        R.anim.volume_dialog_ringer_open,
                                    )
                                drawerContainer.transitionToState(
                                    R.id.volume_dialog_ringer_drawer_open
                                )
                                ringerBackgroundView.background =
                                    ringerBackgroundView.background.mutate()
                            }
                        }
                    }
                    is RingerViewModelState.Unavailable -> {
                        drawerContainer.visibility = View.GONE
                        if (viewModel.showBlur) {
                            val layers = (volumeDialogBackgroundView.background as LayerDrawable)
                            val blurDrawable = layers.getDrawable(0) as BackgroundBlurDrawable
                            blurDrawable.setCornerRadius(volumeDialogBgFullRadius.toFloat())
                            (layers.getDrawable(1) as GradientDrawable).cornerRadius =
                                volumeDialogBgFullRadius.toFloat()
                        } else {
                            if (viewModel.isVolumeDialogVertical) {
                                volumeDialogBackgroundView.setBackgroundResource(
                                    R.drawable.volume_dialog_background
                                )
                            } else {
                                volumeDialogBackgroundView.setBackgroundResource(
                                    R.drawable.volume_dialog_background_horizontal
                                )
                            }
                        }
                    }
                }
            }
            .launchInTraced("VDRVB#ringerViewModel", this)
    }

    private fun View.setIsBlurSupported(supported: Boolean) {
        if (viewModel.showBlur) {
            val layers = (background as LayerDrawable)
            (layers.getDrawable(0) as BackgroundBlurDrawable).setBlurRadius(
                if (supported) {
                    context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_background_surface_blur_radius
                    )
                } else {
                    0
                }
            )
            (layers.getDrawable(1) as GradientDrawable).setColor(
                context.getColor(
                    if (supported) R.color.volume_dialog_view_background_blur
                    else R.color.volume_dialog_view_background_blur_fallback
                )
            )
        }
    }

    private fun startEarlyWakeup() {
        if (!isInEarlyWakeUp) {
            transaction.setEarlyWakeupStart(earlyWakeupInfo)
            transaction.apply()
            isInEarlyWakeUp = true
        }
    }

    private fun endEarlyWakeup() {
        if (isInEarlyWakeUp) {
            transaction.setEarlyWakeupEnd(earlyWakeupInfo)
            transaction.apply()
            isInEarlyWakeUp = false
        }
    }

    private suspend fun MotionLayout.animateAndBindDrawerButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        selectedButtonUiModel: RingerButtonUiModel,
        unselectedButtonUiModel: RingerButtonUiModel,
        gradientEnabled: Boolean,
        gradientColors: Pair<Int, Int>,
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
        onAnimationEnd: Runnable? = null,
    ) {
        ensureChildCount(R.layout.volume_ringer_button, uiModel.availableButtons.size)
        if (
            uiModel.drawerState is RingerDrawerState.Closed &&
                uiModel.drawerState.currentMode != uiModel.drawerState.previousMode
        ) {
            val count = uiModel.availableButtons.size
            val selectedButton = getChildAt(count - uiModel.currentButtonIndex) as ImageButton
            val previousIndex =
                uiModel.availableButtons.indexOfFirst {
                    it.ringerMode == uiModel.drawerState.previousMode
                }
            // We only need to execute on roundness animation end and volume dialog background
            // progress update once because these changes should be applied once on volume dialog
            // background and ringer drawer views.
            coroutineScope {
                val animations = ArrayList<Job>(2)

                val selectedCornerRadius = selectedButton.backgroundShape().cornerRadius
                if (selectedCornerRadius.toInt() != selectedButtonUiModel.cornerRadius) {
                    animations +=
                        launchTraced("VDRVB#selectedButtonAnimation") {
                            selectedButton.animateTo(
                                selectedButtonUiModel,
                                if (uiModel.currentButtonIndex == count - 1) {
                                    onProgressChanged
                                } else {
                                    { _, _ -> }
                                },
                            )
                        }
                }
                if (previousIndex >= 0) {
                    val unselectedButton = getChildAt(count - previousIndex) as ImageButton

                    val unselectedCornerRadius = unselectedButton.backgroundShape().cornerRadius
                    if (unselectedCornerRadius.toInt() != unselectedButtonUiModel.cornerRadius) {
                        animations +=
                            launchTraced("VDRVB#unselectedButtonAnimation") {
                                unselectedButton.animateTo(
                                    unselectedButtonUiModel,
                                    if (previousIndex == count - 1) {
                                        onProgressChanged
                                    } else {
                                        { _, _ -> }
                                    },
                                )
                            }
                    }
                }

                val animatedBind =
                    launchTraced("VDRVB#bindButtons") {
                        delay(CLOSE_DRAWER_DELAY)
                        bindButtons(
                            viewModel,
                            uiModel,
                            gradientEnabled,
                            gradientColors,
                            onAnimationEnd = null,
                            isAnimated = true,
                        )
                    }

                launchTraced("VDRVB#bindButtonsFinal") {
                    animations.joinAll()
                    animatedBind.join()
                    bindButtons(
                        viewModel,
                        uiModel,
                        gradientEnabled,
                        gradientColors,
                        onAnimationEnd,
                        isAnimated = false,
                    )
                }
            }
        } else {
            bindButtons(viewModel, uiModel, gradientEnabled, gradientColors, onAnimationEnd)
        }
    }

    private fun MotionLayout.bindButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        gradientEnabled: Boolean,
        gradientColors: Pair<Int, Int>,
        onAnimationEnd: Runnable? = null,
        isAnimated: Boolean = false,
    ) {
        val count = uiModel.availableButtons.size
        uiModel.availableButtons.fastForEachIndexed { index, ringerButton ->
            val view = getChildAt(count - index) as ImageButton
            val isOpen = uiModel.drawerState is RingerDrawerState.Open
            if (index == uiModel.currentButtonIndex) {
                view.bindDrawerButton(
                    if (isOpen) ringerButton else uiModel.selectedButton,
                    viewModel,
                    isOpen,
                    gradientEnabled,
                    gradientColors,
                    isSelected = true,
                    isAnimated = isAnimated,
                )
            } else {
                view.bindDrawerButton(
                    ringerButton,
                    viewModel,
                    isOpen,
                    gradientEnabled,
                    gradientColors,
                    isAnimated = isAnimated,
                )
            }
        }
        onAnimationEnd?.run()
    }

    private fun ImageButton.bindDrawerButton(
        buttonViewModel: RingerButtonViewModel,
        viewModel: VolumeDialogRingerDrawerViewModel,
        isOpen: Boolean,
        gradientEnabled: Boolean,
        gradientColors: Pair<Int, Int>,
        isSelected: Boolean = false,
        isAnimated: Boolean = false,
    ) {
        // id = buttonViewModel.viewId
        setSelected(isSelected)
        val ringerContentDesc = context.getString(buttonViewModel.contentDescriptionResId)
        setImageResource(buttonViewModel.imageResId)
        contentDescription =
            if (isSelected && !isOpen) {
                context.getString(
                    R.string.volume_ringer_drawer_closed_content_description,
                    ringerContentDesc,
                )
            } else {
                ringerContentDesc
            }
        if (isSelected && !isAnimated) {
            setBackgroundResource(R.drawable.volume_drawer_selection_bg)
            imageTintList =
                ColorStateList.valueOf(context.getColor(internalR.color.materialColorOnPrimary))
            background = background.mutate()
            if (gradientEnabled) {
                applyGradientSelectionBackground(this, gradientColors)
            }
        } else if (!isAnimated) {
            setBackgroundResource(R.drawable.volume_ringer_item_bg)
            imageTintList =
                ColorStateList.valueOf(context.getColor(internalR.color.materialColorOnSurface))
            background = background.mutate()
        }
        setOnClickListener {
            viewModel.onRingerButtonClicked(buttonViewModel.ringerMode, isSelected)
        }
    }

    private fun MotionLayout.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
        val childCountDelta = childCount - count - 1
        when {
            childCountDelta > 0 -> {
                removeViews(0, childCountDelta)
            }
            childCountDelta < 0 -> {
                val inflater = LayoutInflater.from(context)
                repeat(-childCountDelta) {
                    inflater.inflate(viewLayoutId, this, true)
                    getChildAt(childCount - 1).id = View.generateViewId()
                }
            }
        }
    }

    private fun MotionLayout.closeDrawer(
        ringerBackground: View,
        selectedIndex: Int,
        orientation: Int,
    ) {
        setTransition(R.id.close_to_open_transition)
        getTransition(R.id.close_to_open_transition)
            .setInterpolatorInfo(
                MotionScene.Transition.INTERPOLATE_REFERENCE_ID,
                null,
                R.anim.volume_dialog_ringer_close,
            )
        updateCloseState(this, selectedIndex, orientation, ringerBackground)
        transitionToState(R.id.volume_dialog_ringer_drawer_close)
    }

    private suspend fun ImageButton.animateTo(
        ringerButtonUiModel: RingerButtonUiModel,
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
    ) {
        val roundnessAnimation =
            SpringAnimation(FloatValueHolder(0F), 1F).setSpring(roundnessSpringForce)
        val colorAnimation = SpringAnimation(FloatValueHolder(0F), 1F).setSpring(colorSpringForce)
        val radius = backgroundShape().cornerRadius
        val cornerRadiusDiff = ringerButtonUiModel.cornerRadius - backgroundShape().cornerRadius

        roundnessAnimation.minimumVisibleChange = BUTTON_MIN_VISIBLE_CHANGE
        colorAnimation.minimumVisibleChange = BUTTON_MIN_VISIBLE_CHANGE
        coroutineScope {
            launchTraced("VDRVB#colorAnimation") {
                colorAnimation.suspendAnimate { value ->
                    val fraction = value.coerceIn(0F, 1F)

                    val startIconColor =
                        imageTintList?.defaultColor ?: ringerButtonUiModel.tintColor
                    val startBgColor =
                        backgroundShape().firstColorOrNull()
                            ?: ringerButtonUiModel.backgroundColor

                    val currentIconColor =
                        rgbEvaluator.evaluate(
                            fraction,
                            startIconColor,
                            ringerButtonUiModel.tintColor,
                        ) as Int
                    val currentBgColor =
                        rgbEvaluator.evaluate(
                            fraction,
                            startBgColor,
                            ringerButtonUiModel.backgroundColor,
                        ) as Int

                    backgroundShape().setColor(currentBgColor)
                    background.invalidateSelf()
                    imageTintList = ColorStateList.valueOf(currentIconColor)
                }
            }
            roundnessAnimation.suspendAnimate { value ->
                onProgressChanged(value, cornerRadiusDiff > 0F)
                backgroundShape().cornerRadius = radius + value * cornerRadiusDiff
                background.invalidateSelf()
            }
        }
    }

    private fun applyGradientSelectionBackground(
        button: ImageButton,
        gradientColors: Pair<Int, Int>,
    ) {
        val (startColor, endColor) = gradientColors
        val shape = button.backgroundShape()
        shape.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        shape.colors = intArrayOf(startColor, endColor)
        button.background.invalidateSelf()
    }

    private fun View.applyCorners(fullRadius: Int, diff: Int, progress: Float) {
        val radius = fullRadius - progress * diff
        if (viewModel.showBlur) {
            val layers = (background as LayerDrawable)
            (layers.getDrawable(0) as BackgroundBlurDrawable).setCornerRadius(radius)
            (layers.getDrawable(1) as GradientDrawable).cornerRadius = radius
        } else {
            (background as GradientDrawable).cornerRadius = radius
        }
        background.invalidateSelf()
    }

    private fun View.updateBackground() {
        if (viewModel.showBlur && background is GradientDrawable) {
            val surfaceEffect = background as GradientDrawable

            val blurDrawable = viewRootImpl.createBackgroundBlurDrawable()
            val dialogCornerRadius: Int =
                context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_background_corner_radius
                )
            blurDrawable.setCornerRadius(dialogCornerRadius.toFloat())
            blurDrawable.setBlurRadius(0)
            setBackgroundDrawable(LayerDrawable(arrayOf<Drawable>(blurDrawable, surfaceEffect)))

            setIsBlurSupported(windowRootViewBlurInteractor.isBlurCurrentlySupported.value)
        } else {
            background = background.mutate()
        }
    }

    companion object {
        private const val TAG = "VolumeDialogRingerViewBinder"
    }
}

private fun ImageButton.backgroundShape(): GradientDrawable =
    (background as InsetDrawable).drawable as GradientDrawable

private fun GradientDrawable.firstColorOrNull(): Int? {
    colors?.let { if (it.isNotEmpty()) return it[0] }
    return color?.defaultColor
}
