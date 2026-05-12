/*
 * Copyright (C) 2024 The Android Open Source Project
 *           (C) 2026 The LibreMobileOS Foundation
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

package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import android.content.Context
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** Models a particular slider state. */
class AppVolumeSliderViewModel
@AssistedInject
constructor(
    @Assisted private val packageName: String,
    @Assisted private val coroutineScope: CoroutineScope,
    @UiBackground private val uiBackgroundContext: CoroutineContext,
    private val context: Context,
    private val audioVolumeInteractor: AudioVolumeInteractor,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) : SliderViewModel {

    private val volumeChanges = MutableStateFlow<Float?>(null)
    private val muteChanges = MutableStateFlow<Boolean?>(null)

    private val appLabel: String by lazy { resolveAppLabel() }
    private val appIcon: Icon.Loaded? by lazy { resolveAppIcon() }

    override val slider = MutableStateFlow<SliderState>(SliderState.Empty)

    init {
        volumeChanges
            .filterNotNull()
            .onEach {
                slider.value = (slider.value as State).copy(value = it)
                audioVolumeInteractor.setAppVolume(packageName, it)
            }
            .launchIn(coroutineScope)

        muteChanges
            .filterNotNull()
            .onEach {
                slider.value = (slider.value as State).copy(isMuted = it)
                audioVolumeInteractor.setAppMuted(packageName, it)
            }
            .launchIn(coroutineScope)

        audioVolumeInteractor.getAppVolume(packageName)
            .filterNotNull()
            .onEach {
                withContext(uiBackgroundContext) {
                    slider.value = State(
                        value = if (it.isMuted) 0f else it.volume,
                        isMuted = it.isMuted,
                        icon = appIcon,
                        label = appLabel,
                    )
                }
            }
            .launchIn(coroutineScope)
    }

    override fun onValueChanged(state: SliderState, newValue: Float) {
        volumeChanges.tryEmit(newValue)
        if (newValue > 0f && (state as? State)?.isMuted == true) {
            muteChanges.tryEmit(false)
        }
    }

    override fun onValueChangeFinished() {}

    override fun toggleMuted(state: SliderState) {
        val appState = state as? State ?: return
        muteChanges.tryEmit(!appState.isMuted)
    }

    override fun getSliderHapticsViewModelFactory(): SliderHapticsViewModel.Factory? =
        if (slider.value != SliderState.Empty) hapticsViewModelFactory else null

    private fun resolveAppLabel(): String =
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            )
                .toString()
        }
            .getOrElse { packageName }

    private fun resolveAppIcon(): Icon.Loaded =
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrElse { context.packageManager.getDefaultActivityIcon() }
            .asIcon(
                contentDescription = ContentDescription.Loaded(appLabel),
                isBitmapImage = true
            )

    private data class State(
        override val value: Float,
        override val icon: Icon.Loaded?,
        override val label: String,
        val isMuted: Boolean,
    ) : SliderState {
        override val valueRange: ClosedFloatingPointRange<Float> = 0f..1f
        override val step: Float = 0.05f // 5%
        override val hapticFilter: SliderHapticFeedbackFilter = SliderHapticFeedbackFilter()
        override val isEnabled: Boolean = true
        override val disabledMessage: String? = null
        override val isMutable: Boolean = true
        override val a11yContentDescription: String = label
        override val a11yClickDescription: String? = null
        override val a11yStateDescription: String? = null
    }

    @AssistedFactory
    interface Factory {
        fun create(packageName: String, coroutineScope: CoroutineScope): AppVolumeSliderViewModel
    }
}
