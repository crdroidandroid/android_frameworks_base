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

package com.android.systemui.haptics.msdl.qs

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.service.quicksettings.Tile
import androidx.compose.runtime.Stable
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.kotlin.pairwise
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform

/** A view-model to trigger haptic feedback on Quick Settings tiles */
class TileHapticsViewModel
@AssistedInject
constructor(
    private val msdlPlayer: MSDLPlayer,
    private val vibratorHelper: VibratorHelper,
    @Assisted private val tileViewModel: TileViewModel,
) : ExclusiveActivatable() {

    private val tileInteractionState = MutableStateFlow(TileInteractionState.IDLE)
    private val tileAnimationState = MutableStateFlow(TileAnimationState.IDLE)
    private val canPlayToggleHaptics: Boolean
        get() =
            tileAnimationState.value == TileAnimationState.IDLE &&
                tileInteractionState.value == TileInteractionState.CLICKED

    val isIdle: Boolean
        get() =
            tileAnimationState.value == TileAnimationState.IDLE &&
                tileInteractionState.value == TileInteractionState.IDLE

    private val toggleHapticsState: Flow<TileHapticsState> =
        tileViewModel.state
            .mapLatest { it.state }
            .pairwise()
            .transform { (previous, current) ->
                val toggleState =
                    when {
                        !canPlayToggleHaptics -> TileHapticsState.NO_HAPTICS
                        previous == Tile.STATE_INACTIVE && current == Tile.STATE_ACTIVE ->
                            TileHapticsState.TOGGLE_ON
                        previous == Tile.STATE_ACTIVE && current == Tile.STATE_INACTIVE ->
                            TileHapticsState.TOGGLE_OFF
                        else -> TileHapticsState.NO_HAPTICS
                    }
                emit(toggleState)
            }
            .distinctUntilChanged()

    private val interactionHapticsState: Flow<TileHapticsState> =
        combine(tileInteractionState, tileAnimationState) { interactionState, animationState ->
                when {
                    interactionState == TileInteractionState.LONG_CLICKED &&
                        animationState == TileAnimationState.ACTIVITY_LAUNCH ->
                        TileHapticsState.LONG_PRESS
                    else -> TileHapticsState.NO_HAPTICS
                }
            }
            .distinctUntilChanged()

    private val hapticsState: Flow<TileHapticsState> =
        merge(toggleHapticsState, interactionHapticsState)

    private val vibrationAttrs =
        VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH)
            .setFlags(VibrationAttributes.FLAG_PIPELINED_EFFECT)
            .build()

    private val clickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val heavyClick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    private val areAllPrimitivesSupported by lazy {
        vibratorHelper.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
        ) ?: false
    }

    private val composedHeavyClick by lazy {
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f)
            .compose()
    }

    private val composedClick by lazy {
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
            .compose()
    }

    private fun vibrateToggle() = vibratorHelper.vibrate(composedClick, vibrationAttrs)

    private fun vibrateLongPress() = vibratorHelper.vibrate(composedHeavyClick, vibrationAttrs)

    override suspend fun onActivated(): Nothing {
        try {
            hapticsState.collect { state ->
                if (state == TileHapticsState.NO_HAPTICS) return@collect

                if (Flags.msdlFeedback()) {
                    val token =
                        when (state) {
                            TileHapticsState.TOGGLE_ON -> MSDLToken.SWITCH_ON
                            TileHapticsState.TOGGLE_OFF -> MSDLToken.SWITCH_OFF
                            TileHapticsState.LONG_PRESS -> MSDLToken.LONG_PRESS
                            else -> null
                        }
                    token?.let { msdlPlayer.playToken(it) }
                } else {
                    if (areAllPrimitivesSupported) {
                        when (state) {
                            TileHapticsState.TOGGLE_ON,
                            TileHapticsState.TOGGLE_OFF -> vibrateToggle()
                            TileHapticsState.LONG_PRESS -> vibrateLongPress()
                            else -> Unit
                        }
                    } else {
                        when (state) {
                            TileHapticsState.TOGGLE_ON,
                            TileHapticsState.TOGGLE_OFF ->
                                vibratorHelper.vibrate(clickEffect, vibrationAttrs)
                            TileHapticsState.LONG_PRESS ->
                                vibratorHelper.vibrate(heavyClick, vibrationAttrs)
                            else -> Unit
                        }
                    }
                }
                resetStates()
            }
            awaitCancellation()
        } finally {
            resetStates()
        }
    }

    private fun resetStates() {
        tileInteractionState.value = TileInteractionState.IDLE
        tileAnimationState.value = TileAnimationState.IDLE
    }

    fun onDialogDrawingStart() {
        tileAnimationState.value = TileAnimationState.DIALOG_LAUNCH
    }

    fun onDialogDrawingEnd() {
        tileAnimationState.value = TileAnimationState.IDLE
    }

    fun onActivityLaunchTransitionStart() {
        tileAnimationState.value = TileAnimationState.ACTIVITY_LAUNCH
    }

    fun onActivityLaunchTransitionEnd() {
        tileAnimationState.value = TileAnimationState.IDLE
    }

    fun setTileInteractionState(actionState: TileInteractionState) {
        tileInteractionState.value = actionState
    }

    fun createStateAwareExpandable(baseExpandable: Expandable): Expandable =
        baseExpandable.withStateAwareness(
            onDialogDrawingStart = ::onDialogDrawingStart,
            onDialogDrawingEnd = ::onDialogDrawingEnd,
            onActivityLaunchTransitionStart = ::onActivityLaunchTransitionStart,
            onActivityLaunchTransitionEnd = ::onActivityLaunchTransitionEnd,
        )

    /** Models the state of haptics to play */
    enum class TileHapticsState {
        TOGGLE_ON,
        TOGGLE_OFF,
        LONG_PRESS,
        NO_HAPTICS,
    }

    /** Models the interaction that took place on the tile */
    enum class TileInteractionState {
        IDLE,
        CLICKED,
        LONG_CLICKED,
    }

    /** Models the animation state of dialogs and activity launches from a tile */
    enum class TileAnimationState {
        IDLE,
        DIALOG_LAUNCH,
        ACTIVITY_LAUNCH,
    }

    @AssistedFactory
    interface Factory {
        fun create(tileViewModel: TileViewModel): TileHapticsViewModel
    }
}

@SysUISingleton
@Stable
class TileHapticsViewModelFactoryProvider
@Inject
constructor(private val tileHapticsViewModelFactory: TileHapticsViewModel.Factory) {
    fun getHapticsViewModelFactory(): TileHapticsViewModel.Factory? = tileHapticsViewModelFactory
}
