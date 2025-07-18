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
            setOnSettingsChangedListener { onSettingsChanged() }
        }

    private val pulseView: PulseView =
        PulseView(context).apply {
            initialize(settingsRepository)
            setVisibility(false)
        }

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    init {
        INSTANCE = this

        ScrimUtils.get().addListener(this)
        MediaSessionManager.get().addListener(this)
    }

    fun getPulseView(): PulseView = pulseView

    private fun updatePulseState() {
        if (shouldShowPulse) {
            if (!isRunning) start() 
        } else {
            if (isRunning) stop()
        }
    }

    private fun start() {
        mainScope.launch {
            pulseView.setVisibility(true)
            audioProcessor.startCapture()
        }
    }

    private fun stop() {
        mainScope.launch {
            pulseView.setVisibility(false)
            audioProcessor.stopCapture()
        }
    }

    private fun onSettingsChanged() {
        mainScope.launch { updatePulseState() }
    }

    val isRunning: Boolean
        get() = audioProcessor.isCapturing()

    val shouldShowPulse: Boolean
        get() = settingsRepository.isPulseEnabled() &&
            ScrimUtils.get().isKeyguardShowing() &&
            MediaSessionManager.get().isMediaPlaying

    override fun onDataUpdate(data: PulseData) {
        if (settingsRepository.isPulseEnabled()) {
            mainScope.launch { pulseView.updateVisualizerData(data) }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        mainScope.launch { updatePulseState() }
    }

    override fun onMediaColorsChanged(color: Int) {
        pulseView.onMediaColorsChanged(color)
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        mainScope.launch { updatePulseState() }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        stop()
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        stop()
    }

    override fun onScreenTurnedOff() {
        stop()
    }

    override fun onStartedWakingUp() {
        mainScope.launch { updatePulseState() }
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
