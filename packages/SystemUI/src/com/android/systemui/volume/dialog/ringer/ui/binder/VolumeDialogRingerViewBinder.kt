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
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
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
import javax.inject.Inject
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.Job
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
        volumeDialogBackgroundView.background = volumeDialogBackgroundView.background.mutate()
        ringerBackgroundView.background = ringerBackgroundView.background.mutate()
        launchTraced("VDRVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(ringerBackgroundView)
        }

        val contentResolver = view.context.contentResolver
        var gradientEnabled = isVolumeGradientEnabled(view.context)
        var gradientColorsForRinger = getGradientColorsForRinger(view.context)

        val gradientObserver =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    gradientEnabled = isVolumeGradientEnabled(view.context)
                    gradientColorsForRinger = getGradientColorsForRinger(view.context)
                }
            }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.VOLUME_SLIDER_GRADIENT),
            false, gradientObserver, UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_COLOR_MODE),
            false, gradientObserver, UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_START_COLOR),
            false, gradientObserver, UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_END_COLOR),
            false, gradientObserver, UserHandle.USER_ALL,
        )

        coroutineContext.job.invokeOnCompletion {
            contentResolver.unregisterContentObserver(gradientObserver)
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
                        (volumeDialogBackgroundView.background as GradientDrawable).cornerRadii =
                            bottomCornerRadii
                        when (uiModel.drawerState) {
                            is RingerDrawerState.Initial -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                    gradientEnabled,
                                    gradientColorsForRinger,
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
                                        gradientColorsForRinger,
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
                                    gradientColorsForRinger,
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
                        volumeDialogBackgroundView.setBackgroundResource(
                            R.drawable.volume_dialog_background
                        )
                    }
                }
            }
            .launchInTraced("VDRVB#ringerViewModel", this)
    }

    private suspend fun MotionLayout.animateAndBindDrawerButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        selectedButtonUiModel: RingerButtonUiModel,
        unselectedButtonUiModel: RingerButtonUiModel,
        gradientEnabled: Boolean,
        gradientColorsForRinger: Pair<Int, Int>,
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
            val unselectedButton = getChildAt(count - previousIndex) as ImageButton
            // We only need to execute on roundness animation end and volume dialog background
            // progress update once because these changes should be applied once on volume dialog
            // background and ringer drawer views.
            coroutineScope {
                val jobs = ArrayList<Job>(2)

                val selectedCornerRadius = selectedButton.backgroundShape().cornerRadius
                if (selectedCornerRadius.toInt() != selectedButtonUiModel.cornerRadius) {
                    jobs += launchTraced("VDRVB#selectedButtonAnimation") {
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

                val unselectedCornerRadius = unselectedButton.backgroundShape().cornerRadius
                if (unselectedCornerRadius.toInt() != unselectedButtonUiModel.cornerRadius) {
                    jobs += launchTraced("VDRVB#unselectedButtonAnimation") {
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

                launchTraced("VDRVB#bindButtonsAnimated") {
                    delay(CLOSE_DRAWER_DELAY)
                    bindButtons(
                        viewModel,
                        uiModel,
                        gradientEnabled,
                        gradientColorsForRinger,
                        onAnimationEnd = null,
                        isAnimated = true,
                    )
                }

                jobs.joinAll()

                launchTraced("VDRVB#bindButtonsFinal") {
                    bindButtons(
                        viewModel,
                        uiModel,
                        gradientEnabled,
                        gradientColorsForRinger,
                        onAnimationEnd,
                        isAnimated = false,
                    )
                }
            }
        } else {
            bindButtons(
                viewModel,
                uiModel,
                gradientEnabled,
                gradientColorsForRinger,
                onAnimationEnd
            )
        }
    }

    private fun MotionLayout.bindButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        gradientEnabled: Boolean,
        gradientColorsForRinger: Pair<Int, Int>,
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
                    gradientColorsForRinger,
                    isSelected = true,
                    isAnimated = isAnimated,
                )
            } else {
                view.bindDrawerButton(
                    ringerButton,
                    viewModel,
                    isOpen,
                    gradientEnabled,
                    gradientColorsForRinger,
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
        gradientColorsForRinger: Pair<Int, Int>,
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
            imageTintList = ColorStateList.valueOf(context.getColor(internalR.color.materialColorOnPrimary))
            background = background.mutate()
            if (gradientEnabled) {
                applyGradientSelectionBackground(this, gradientColorsForRinger)
            }
        } else if (!isAnimated) {
            setBackgroundResource(R.drawable.volume_ringer_item_bg)
            imageTintList = ColorStateList.valueOf(context.getColor(internalR.color.materialColorOnSurface))
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
                    val t = value.coerceIn(0F, 1F)

                    val startIconColor = imageTintList?.defaultColor ?: ringerButtonUiModel.tintColor
                    val endIconColor = ringerButtonUiModel.tintColor

                    val startBgColor = backgroundShape().firstColorOrNull() ?: ringerButtonUiModel.backgroundColor
                    val endBgColor = ringerButtonUiModel.backgroundColor

                    val currentIconColor = rgbEvaluator.evaluate(t, startIconColor, endIconColor) as Int
                    val currentBgColor = rgbEvaluator.evaluate(t, startBgColor, endBgColor) as Int

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

    private fun View.applyCorners(fullRadius: Int, diff: Int, progress: Float) {
        val radius = fullRadius - progress * diff
        (background as GradientDrawable).cornerRadius = radius
        background.invalidateSelf()
    }

    private fun isVolumeGradientEnabled(context: Context): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver, Settings.System.VOLUME_SLIDER_GRADIENT, 0,
            UserHandle.USER_CURRENT) != 0
    }

    private fun getGradientColorsForRinger(context: Context): Pair<Int, Int> {
        val resolver = context.contentResolver

        val mode = Settings.System.getIntForUser(
                resolver, Settings.System.CUSTOM_GRADIENT_COLOR_MODE, 0,
                UserHandle.USER_CURRENT,
            )

        val primary = context.getColor(internalR.color.materialColorPrimary)
        val secondary = context.getColor(internalR.color.materialColorSecondary)

        if (mode == 1) {
            val start = Settings.System.getIntForUser(
                    resolver, Settings.System.CUSTOM_GRADIENT_START_COLOR, 0,
                    UserHandle.USER_CURRENT,
                )
            val end = Settings.System.getIntForUser(
                    resolver, Settings.System.CUSTOM_GRADIENT_END_COLOR, 0,
                    UserHandle.USER_CURRENT,
                )

            val startColor = if (start != 0) start else primary
            val endColor = if (end != 0) end else secondary

            return startColor to endColor
        }

        return primary to secondary
    }

    private fun applyGradientSelectionBackground(
        button: ImageButton,
        gradientColorsForRinger: Pair<Int, Int>,
    ) {
        val (startColor, endColor) = gradientColorsForRinger
        val shape = button.backgroundShape()
        shape.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        shape.colors = intArrayOf(startColor, endColor)
        button.background.invalidateSelf()
    }
}

private fun ImageButton.backgroundShape(): GradientDrawable =
    (background as InsetDrawable).drawable as GradientDrawable

private fun GradientDrawable.firstColorOrNull(): Int? {
    val gradient = colors
    if (gradient != null && gradient.isNotEmpty()) return gradient[0]

    val solid = color
    if (solid != null) return solid.defaultColor

    return null
}
