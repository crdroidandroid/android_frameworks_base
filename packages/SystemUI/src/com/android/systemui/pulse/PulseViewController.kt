/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.pulse

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context
) : PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    private val mainScope = MainScope()

    private val settingsRepository: PulseSettingsRepository =
        PulseSettingsRepository(context).apply {
            startObserving()
            setOnSettingsChangedListener { updateState() }
        }

    private val view: PulseView =
        PulseView(context).apply {
            initialize(settingsRepository)
            setVisibility(false)
        }

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    val pulseEnabled: Boolean
        get() = settingsRepository.isPulseEnabled()

    val ambientEnabled: Boolean
        get() = settingsRepository.isPulseAmbientEnabled()

    val keyguardShowing: Boolean
        get() = ScrimUtils.get().isKeyguardShowing()

    val dozing: Boolean
        get() = ScrimUtils.get().isDozing()

    var pulseRunning: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            updatePulse(value)
        }

    val mediaPlaying: Boolean
        get() = MediaSessionManager.get().isMediaPlaying

    val showPulse: Boolean
        get() = pulseEnabled && mediaPlaying 
                && ((keyguardShowing && !dozing)
                || (dozing && ambientEnabled))

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        MediaSessionManager.get().addListener(this)
    }

    fun getPulseView(): PulseView = view

    private fun updateState() {
        pulseRunning = showPulse
    }

    private fun updatePulse(show: Boolean) {
        mainScope.launch {
            view.setVisibility(show)
            if (show) audioProcessor.startCapture()
            else audioProcessor.stopCapture()
        }
    }

    override fun onDataUpdate(data: PulseData) {
        if (showPulse) {
            mainScope.launch { 
                view.updateVisualizerData(data) 
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        updateState()
    }

    override fun onMediaColorsChanged(color: Int) {
        if (pulseEnabled) view.onMediaColorsChanged(color)
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        updateState()
    }

    override fun onDozingChanged() {
        updateState()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        pulseRunning = false
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        pulseRunning = false
    }

    override fun onScreenTurnedOff() {
        if (!dozing || !ambientEnabled) pulseRunning = false
    }

    override fun onStartedWakingUp() {
        updateState()
    }

    fun destroy() {
        mainScope.cancel()
    }

    companion object {
        private const val TAG = "PulseViewController"

        @Volatile
        private var INSTANCE: PulseViewController? = null

        @JvmStatic
        fun get(context: Context): PulseViewController {
            return INSTANCE ?: throw IllegalStateException(
                "PulseViewController not initialized"
            )
        }
    }
}
