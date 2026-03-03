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

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.internal.R as internalR
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Background
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

        val contentResolver = captionsButton.context.contentResolver
        var gradientEnabled = isVolumeGradientEnabled(captionsButton.context)
        var gradientColorsForRinger = getGradientColorsForRinger(captionsButton.context)

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

                    if (isEnabled && gradientEnabled) {
                        applyGradientSelectionBackground(this, gradientColorsForRinger)
                    }

                    val transition = background as TransitionDrawable
                    transition.isCrossFadeEnabled = true
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
        button: CaptionsToggleImageButton,
        gradientColorsForRinger: Pair<Int, Int>,
    ) {
        val (startColor, endColor) = gradientColorsForRinger
        val shape = button.backgroundShape()
        shape.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        shape.colors = intArrayOf(startColor, endColor)
        button.background.invalidateSelf()
    }

    private companion object {
        const val DURATION_MILLIS = 500
    }
}

private fun CaptionsToggleImageButton.backgroundShape(): GradientDrawable {
    val td = background as TransitionDrawable

    fun unwrap(d: Drawable): GradientDrawable? {
        return when (d) {
            is GradientDrawable -> d
            is InsetDrawable -> d.drawable as? GradientDrawable
            else -> null
        }
    }

    return unwrap(td.getDrawable(1))
        ?: unwrap(td.getDrawable(0))
        ?: throw IllegalStateException("Captions button background is not a GradientDrawable")
}
