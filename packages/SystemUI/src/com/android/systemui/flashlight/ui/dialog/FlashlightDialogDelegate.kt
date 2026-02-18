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

package com.android.systemui.flashlight.ui.dialog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.theme.PlatformTheme
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.flashlight.flags.FlashlightStrength
import com.android.systemui.flashlight.shared.logger.FlashlightLogger
import com.android.systemui.flashlight.ui.composable.FlashlightSliderContainer
import com.android.systemui.flashlight.ui.viewmodel.FlashlightSliderViewModel
import com.android.systemui.flashlight.ui.viewmodel.FlashlightSliderViewModelLegacy
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.util.Assert
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

@SysUISingleton
class FlashlightDialogDelegate
@Inject
constructor(
    @Main private val mainCoroutineContext: CoroutineContext,
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val viewModelFactory: FlashlightSliderViewModel.Factory,
    private val legacyViewModelFactory: FlashlightSliderViewModelLegacy.Factory,
    private val logger: FlashlightLogger,
) : SystemUIDialog.Delegate {
    enum class SliderBackend {
        REPOSITORY,
        LEGACY,
    }

    private var currentDialog: ComponentSystemUIDialog? = null
    private var currentSliderBackend: SliderBackend = SliderBackend.REPOSITORY

    init {
        if (FlashlightStrength.isUnexpectedlyInLegacyMode()) {
            logger.dialogW("UnexpectedlyInLegacyMode on init")
        }
    }

    override fun createDialog(): SystemUIDialog {
        if (FlashlightStrength.isUnexpectedlyInLegacyMode()) {
            logger.dialogW("UnexpectedlyInLegacyMode on create dialog")
        }

        Assert.isMainThread()
        if (currentDialog != null) {
            logger.dialogW("Already open when creating, dismissing it and creating a new one")
            currentDialog?.dismiss()
            return currentDialog!!
        }
        currentDialog =
            sysuiDialogFactory.create(context = shadeDialogContextInteractor.context) {
                FlashlightDialogContent(it, currentSliderBackend)
            }
        currentDialog
            ?.lifecycle
            ?.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        Assert.isMainThread()
                        currentDialog = null
                    }
                }
            )

        return currentDialog!!
    }

    @Composable
    private fun FlashlightDialogContent(dialog: SystemUIDialog, sliderBackend: SliderBackend) {
        // TODO(b/369376884): The composable does correctly update when the theme changes
        //  while the dialog is open, but the background (which we don't control here)
        //  doesn't, which causes us to show things like white text on a white background.
        //  as a workaround, we remember the original theme and keep it on recomposition.
        val isCurrentlyInDarkTheme = isSystemInDarkTheme()
        val cachedDarkTheme = remember { isCurrentlyInDarkTheme }
        val flashlightSliderViewModel =
            when (sliderBackend) {
                SliderBackend.LEGACY ->
                    rememberViewModel("FlashlightSliderViewModelLegacy") {
                        legacyViewModelFactory.create()
                    }
                SliderBackend.REPOSITORY ->
                    rememberViewModel("FlashlightSliderViewModel") { viewModelFactory.create() }
            }
        PlatformTheme(isDarkTheme = cachedDarkTheme) {
            AlertDialogContent(
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                title = {
                    Text(
                        modifier = Modifier.testTag(FLASHLIGHT_TITLE_TAG),
                        text = stringResource(R.string.flashlight_dialog_title),
                    )
                },
                content = { FlashlightSliderContainer(viewModel = flashlightSliderViewModel) },
                positiveButton = {
                    PlatformButton(
                        modifier = Modifier.testTag(FLASHLIGHT_DONE_TAG),
                        onClick = { dialog.dismiss() },
                    ) {
                        Text(stringResource(R.string.quick_settings_done))
                    }
                },
                neutralButton = {
                    PlatformOutlinedButton(
                        modifier = Modifier.testTag(FLASHLIGHT_OFF_TAG),
                        onClick = {
                            flashlightSliderViewModel.setFlashlightLevel(0)
                            dialog.dismiss()
                        },
                    ) {
                        Text(stringResource(R.string.flashlight_dialog_turn_off))
                    }
                },
                contentBottomPadding = 8.dp,
            )
        }
    }

    /** Runs on @Main CoroutineContext */
    suspend fun showDialog(expandable: Expandable? = null,sliderBackend: SliderBackend = SliderBackend.REPOSITORY): SystemUIDialog? {
        if (FlashlightStrength.isUnexpectedlyInLegacyMode()) {
            logger.dialogW("UnexpectedlyInLegacyMode on show")
            return null
        }

        // Dialogs shown by the DialogTransitionAnimator must be created and shown on the main
        // thread, so we post it to the UI handler.
        withContext(mainCoroutineContext) {
            currentSliderBackend = sliderBackend
            // Create the dialog if necessary
            currentDialog = createDialog() as ComponentSystemUIDialog

            expandable
                ?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                )
                ?.let { controller -> dialogTransitionAnimator.show(currentDialog!!, controller) }
                ?: currentDialog!!.show()
        }

        return currentDialog!!
    }

    companion object {
        private const val INTERACTION_JANK_TAG = "flashlight"
        private const val FLASHLIGHT_TITLE_TAG = "flashlight_title"
        private const val FLASHLIGHT_DONE_TAG = "flashlight_done"
        private const val FLASHLIGHT_OFF_TAG = "flashlight_off"
    }
}
