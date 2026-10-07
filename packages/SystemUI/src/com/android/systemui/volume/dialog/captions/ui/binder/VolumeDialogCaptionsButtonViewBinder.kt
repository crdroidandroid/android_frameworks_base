/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.dialog.captions.ui.binder

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.provider.Settings
import android.view.View
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.gradient.GradientSettings
import com.android.systemui.res.R
import com.android.systemui.volume.CaptionsToggleImageButton
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.captions.ui.viewmodel.VolumeDialogCaptionsButtonViewModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.job

/** Binds the captions button view. */
@VolumeDialogScope
class VolumeDialogCaptionsButtonViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogCaptionsButtonViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    @Background private val bgHandler: Handler,
) : ViewBinder {
    override fun CoroutineScope.bind(view: View) {
        if (!Flags.captionsToggleInVolumeDialogV1()) {
            return
        }

        val captionsButton = view.requireViewById<CaptionsToggleImageButton>(R.id.odi_captions_icon)

        launchTraced("VDCBVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(captionsButton)
        }

        val transition = captionsButton.prepareTransitionBackground()
        val selectionShape = transition?.selectionShape()
        val originalSelectionFill = selectionShape?.let { SelectionFill.capture(it) }

        val context = captionsButton.context
        var gradientEnabled =
            GradientSettings.isEnabled(context, Settings.System.VOLUME_SLIDER_GRADIENT)
        var gradientColors = GradientSettings.argbColors(context)
        val gradientObserver =
            GradientSettings.registerObserver(context, Settings.System.VOLUME_SLIDER_GRADIENT) {
                gradientEnabled =
                    GradientSettings.isEnabled(context, Settings.System.VOLUME_SLIDER_GRADIENT)
                gradientColors = GradientSettings.argbColors(context)
            }
        coroutineContext.job.invokeOnCompletion {
            GradientSettings.unregisterObserver(context, gradientObserver)
        }

        viewModel.isVisible
            .onEach { isVisible ->
                captionsButton.visibility =
                    if (isVisible) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
            .launchInTraced("VDCBVB#isVisible", this)

        viewModel.isEnable
            .withIndex()
            .onEach { (index, isEnabled) ->
                captionsButton.apply {
                    setImageResource(
                        if (isEnabled) {
                            R.drawable.ic_volume_odi_captions
                        } else {
                            R.drawable.ic_volume_odi_captions_disabled
                        }
                    )

                    setColorFilter(
                        captionsButton.context.getColor(
                            if (isEnabled) {
                                com.android.internal.R.color.materialColorOnPrimary
                            } else {
                                com.android.internal.R.color.materialColorOnSurface
                            }
                        )
                    )

                    if (isEnabled && selectionShape != null) {
                        if (gradientEnabled) {
                            applyGradient(selectionShape, gradientColors)
                        } else {
                            originalSelectionFill?.restore(selectionShape)
                        }
                        transition.invalidateSelf()
                    }

                    if (transition != null) {
                        if (index == 0) {
                            if (isEnabled) {
                                transition.startTransition(0)
                            }
                        } else {
                            if (isEnabled) {
                                transition.startTransition(DURATION_MILLIS)
                            } else {
                                transition.reverseTransition(DURATION_MILLIS)
                            }
                        }
                    }

                    setCaptionsEnabled(isEnabled)
                }
            }
            .launchInTraced("VDCBVB#isEnabled", this)

        captionsButton.setOnConfirmedTapListener(
            {
                viewModel.onButtonClicked()
                Events.writeEvent(Events.EVENT_ODI_CAPTIONS_CLICK)
            },
            bgHandler,
        )
    }

    private fun applyGradient(shape: GradientDrawable, gradientColors: Pair<Int, Int>) {
        val (startColor, endColor) = gradientColors
        shape.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        shape.colors = intArrayOf(startColor, endColor)
    }

    private class SelectionFill(
        private val color: ColorStateList?,
        private val colors: IntArray?,
        private val orientation: GradientDrawable.Orientation,
    ) {
        fun restore(shape: GradientDrawable) {
            shape.orientation = orientation
            when {
                colors != null -> shape.colors = colors
                color != null -> shape.color = color
            }
        }

        companion object {
            fun capture(shape: GradientDrawable) =
                SelectionFill(shape.color, shape.colors?.copyOf(), shape.orientation)
        }
    }

    private companion object {
        const val DURATION_MILLIS = 500
    }
}

private fun CaptionsToggleImageButton.prepareTransitionBackground(): TransitionDrawable? {
    val transition = background?.mutate() as? TransitionDrawable ?: return null
    background = transition
    transition.isCrossFadeEnabled = true

    val offShape = unwrapShape(transition.getDrawable(0))
    if (offShape != null) {
        offShape.setColor(Color.TRANSPARENT)
    } else {
        transition.setDrawable(0, ColorDrawable(Color.TRANSPARENT))
    }
    transition.invalidateSelf()
    return transition
}

private fun TransitionDrawable.selectionShape(): GradientDrawable? =
    if (numberOfLayers > 1) unwrapShape(getDrawable(1)) else null

private fun unwrapShape(drawable: Drawable?): GradientDrawable? =
    when (drawable) {
        is GradientDrawable -> drawable
        is InsetDrawable -> drawable.drawable as? GradientDrawable
        else -> null
    }
