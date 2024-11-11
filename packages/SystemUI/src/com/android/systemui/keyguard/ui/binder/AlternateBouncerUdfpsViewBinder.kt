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

import android.content.res.ColorStateList
import android.provider.Settings
import android.os.UserHandle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.Dependency
import com.android.systemui.biometrics.UdfpsIconDrawable
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerUdfpsIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.tuner.TunerService
import com.android.internal.util.crdroid.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object AlternateBouncerUdfpsViewBinder {

    private final val UDFPS_ICON: String =
            "system:" + Settings.System.UDFPS_ICON

    /** Updates UI for the UDFPS icon on the alternate bouncer. */
    @JvmStatic
    fun bind(
        applicationScope: CoroutineScope,
        view: DeviceEntryIconView,
        viewModel: AlternateBouncerUdfpsIconViewModel,
    ) {
        if (DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()) {
            return
        }
        val fgIconView = view.iconView
        val bgView = view.bgView

        val packageInstalled = Utils.isPackageInstalled(
            view.context, "com.crdroid.udfps.icons"
        )

        val shouldUseCustomUdfpsIcon: StateFlow<Boolean> = callbackFlow {
            val callback = object : TunerService.Tunable {
                override fun onTuningChanged(key: String, newValue: String?) {
                    if (key == UDFPS_ICON) {
                        trySend(TunerService.parseIntegerSwitch(newValue, false)).isSuccess
                    }
                }
            }
            Dependency.get(TunerService::class.java).addTunable(callback, UDFPS_ICON)

            awaitClose { Dependency.get(TunerService::class.java).removeTunable(callback) }
        }.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                view.alpha = 0f
                launch("$TAG#viewModel.accessibilityDelegateHint") {
                    viewModel.accessibilityDelegateHint.collect { hint ->
                        view.accessibilityHintType = hint
                    }
                }

                if (SceneContainerFlag.isEnabled) {
                    view.alpha = 1f
                } else {
                    launch("$TAG#viewModel.alpha") { viewModel.alpha.collect { view.alpha = it } }
                }
            }
        }

        fgIconView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fgViewModel.collect { fgViewModel ->
                    fgIconView.setImageState(
                        view.getIconState(fgViewModel.type, fgViewModel.useAodVariant),
                        /* merge */ false
                    )
                    fgIconView.imageTintList = ColorStateList.valueOf(fgViewModel.tint)
                    if (fgIconView.drawable.current !is UdfpsIconDrawable) {
                        fgIconView.setPadding(
                            fgViewModel.padding,
                            fgViewModel.padding,
                            fgViewModel.padding,
                            fgViewModel.padding
                        )
                    } else {
                        fgIconView.setPadding(0, 0, 0, 0)
                    }
                }
            }
        }

        bgView.visibility = View.VISIBLE
        bgView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.bgColor") {
                    if (!shouldUseCustomUdfpsIcon.value || !packageInstalled) {
                        viewModel.bgColor.collect { color ->
                            bgView.imageTintList = ColorStateList.valueOf(color)
                        }
                    } else {
                        viewModel.bgColor.collect { color ->
                            bgView.imageTintList = null
                        }
                    }
                }
                launch("$TAG#viewModel.bgAlpha") {
                    viewModel.bgAlpha.collect { alpha -> bgView.alpha = alpha }
                }
            }
        }
    }

    private const val TAG = "AlternateBouncerUdfpsViewBinder"
}
