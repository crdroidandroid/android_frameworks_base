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

package com.android.systemui.window.domain.interactor

import android.content.res.Resources
import com.android.systemui.Flags
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.res.R
import com.android.systemui.window.data.repository.BlurAppliedListener
import com.android.systemui.window.data.repository.WindowRootViewBlurRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor that provides the blur state for the window root view
 * [com.android.systemui.scene.ui.view.WindowRootView]
 */
@SysUISingleton
class WindowRootViewBlurInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Main private val resources: Resources,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val communalInteractor: CommunalInteractor,
    private val repository: WindowRootViewBlurRepository,
) {
    private var isPrimaryBouncerVisible: StateFlow<Boolean> =
        if (Flags.bouncerUiRevamp()) {
            combine(
                    keyguardInteractor.primaryBouncerShowing,
                    keyguardTransitionInteractor
                        .transitionValue(PRIMARY_BOUNCER)
                        .map { it > 0f }
                        .distinctUntilChanged(),
                ) { bouncerShowing, bouncerTransitionInProgress ->
                    // We need to check either of these because they are two different sources of
                    // truth, primaryBouncerShowing changes early to true/false, but blur is
                    // coordinated by the transition fraction.
                    bouncerShowing || bouncerTransitionInProgress
                }
                .stateIn(applicationScope, SharingStarted.Eagerly, false)
        } else {
            MutableStateFlow(false)
        }

    /**
     * Invoked by the view after blur of [appliedBlurRadius] was successfully applied on the window
     * root view.
     */
    fun onBlurApplied(appliedBlurRadius: Int) {
        repository.blurAppliedListener?.accept(appliedBlurRadius)
    }

    /**
     * Register a listener that gets invoked whenever blur is applied, clears the listener if the
     * passed in value is null
     */
    fun registerBlurAppliedListener(listener: BlurAppliedListener?) {
        repository.blurAppliedListener = listener
    }

    /**
     * Whether blur is enabled or not based on settings toggle, critical thermal state, battery save
     * state and multimedia tunneling state.
     */
    val isBlurCurrentlySupported: StateFlow<Boolean> = repository.isBlurSupported

    /** Whether notification row translucency is enabled via user setting. */
    val isTranslucentSupported: StateFlow<Boolean> = repository.isTranslucentSupported

    /** Whether the blurred wallpaper is supported. This feature is disabled on desktop. */
    val isBlurredWallpaperSupported: Boolean =
        resources.getBoolean(R.bool.config_supportBlurredWallpaper)

    /** Radius of blur to be applied on the window root view. */
    val blurRadiusRequestedByShade: StateFlow<Float> = repository.blurRequestedByShade.asStateFlow()

    /** Scale factor to apply to content underneath blurs on the window root view. */
    val blurScaleRequestedByShade: StateFlow<Float> = repository.scaleRequestedByShade.asStateFlow()

    /**
     * true when tracking shade motion that might lead to a shade expansion.
     *
     * This signal need not be implemented by all shade variants.
     */
    val isTrackingShadeMotion: StateFlow<Boolean> = repository.trackingShadeMotion.asStateFlow()

    /**
     * Requests blur to be applied on the window root view. It is applied only when other blurs are
     * not applied.
     *
     * This method is present to temporarily support the blur for notification shade, ideally shade
     * should expose state that is used by this interactor to determine the blur that has to be
     * applied.
     *
     * @return whether the request for blur was processed or not.
     */
    fun requestBlurForShade(blurRadius: Int, blurScale: Float): Boolean {
        if (isPrimaryBouncerVisible.value) {
            return false
        }
        if (communalInteractor.isCommunalBlurring.value) {
            return false
        }
        repository.blurRequestedByShade.value = blurRadius.toFloat()
        repository.scaleRequestedByShade.value = blurScale
        return true
    }

    /**
     * Set to true when shade motion is being tracked. This signal is used to make sure
     * surface-flinger is ready for expensive blur during shade expansion.
     */
    fun setTrackingShadeMotion(tracking: Boolean) {
        repository.trackingShadeMotion.value = tracking
    }

    companion object {
        const val TAG = "WindowRootViewBlurInteractor"
    }
}
