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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference

class PulseAudioDataProcessor(private val context: Context) {

    companion object {
        private const val TAG = "PulseAudioProcessor"
        private const val INVALID_SESSION = Int.MIN_VALUE
    }

    private var visualizer: Visualizer? = null
    private var dataListener: WeakReference<DataListener>? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isProcessing = false
    private var attachedSessionId: Int = INVALID_SESSION

    private val pulseData = PulseFFTData()
    private var lastUpdateTime = 0L
    private var updateThrottle = 16L
    private var lastKnownRefreshRateHz: Float = 60f

    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    interface DataListener {
        fun onDataUpdate(data: PulseData)
    }

    fun setDataListener(listener: DataListener) {
        dataListener = WeakReference(listener)
    }

    fun startCapture() {
        if (isProcessing) return

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerPlaybackCallback()

        val session = preferredAudioSessionId()
        if (!attachVisualizer(session) && session != 0) {
            attachVisualizer(0)
        }

        if (visualizer != null) {
            isProcessing = true
        } else {
            unregisterPlaybackCallback()
        }
    }

    fun stopCapture() {
        unregisterPlaybackCallback()

        if (!isProcessing && visualizer == null) {
            attachedSessionId = INVALID_SESSION
            return
        }

        releaseVisualizer()
        attachedSessionId = INVALID_SESSION
        isProcessing = false
        pulseData.reset()
    }

    fun cleanup() {
        stopCapture()
        dataListener?.clear()
        dataListener = null
    }

    private fun registerPlaybackCallback() {
        if (playbackCallback != null) return
        val am = audioManager ?: return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                handler.post { maybeRetargetVisualizer(configs) }
            }
        }
        playbackCallback = cb
        try {
            am.registerAudioPlaybackCallback(cb, handler)
        } catch (e: Exception) {
            Log.w(TAG, "registerAudioPlaybackCallback", e)
            playbackCallback = null
        }
    }

    private fun unregisterPlaybackCallback() {
        val am = audioManager
        val cb = playbackCallback
        if (am == null || cb == null) {
            if (cb == null) audioManager = null
            return
        }
        try {
            am.unregisterAudioPlaybackCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterAudioPlaybackCallback", e)
        }
        playbackCallback = null
        audioManager = null
    }

    private fun maybeRetargetVisualizer(configs: MutableList<AudioPlaybackConfiguration>) {
        if (!isProcessing) return
        val want = pickSessionIdFromConfigs(configs)
        val target = if (want > 0) want else 0
        if (target == attachedSessionId) return

        releaseVisualizer()
        attachedSessionId = INVALID_SESSION

        if (!attachVisualizer(target) && target != 0) {
            attachVisualizer(0)
        }
        if (visualizer == null) {
            isProcessing = false
        }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.apply {
                enabled = false
                setDataCaptureListener(null, 0, false, false)
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "release visualizer", e)
        }
        visualizer = null
    }

    private fun preferredAudioSessionId(): Int {
        val am = audioManager
            ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?: return 0
        return try {
            val configs = am.activePlaybackConfigurations ?: return 0
            pickSessionIdFromConfigs(configs)
        } catch (e: SecurityException) {
            Log.w(TAG, "activePlaybackConfigurations", e)
            0
        }
    }

    private fun pickSessionIdFromConfigs(
        configs: List<AudioPlaybackConfiguration>
    ): Int {
        if (configs.isEmpty()) return 0

        val targetPkg = activeLocalPlayingMediaPackage()

        if (targetPkg != null) {
            for (c in configs) {
                val sid = c.sessionId
                if (sid <= 0) continue
                val uid = c.clientUid
                if (uid <= 0) continue
                val pkgs = context.packageManager.getPackagesForUid(uid)
                if (pkgs != null && pkgs.any { it == targetPkg }) {
                    return sid
                }
            }
        }

        for (c in configs) {
            val sid = c.sessionId
            if (sid > 0 && isLikelyMusicPlayback(c.audioAttributes)) return sid
        }

        for (c in configs) {
            val sid = c.sessionId
            if (sid > 0) return sid
        }
        return 0
    }

    private fun activeLocalPlayingMediaPackage(): String? {
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val controllers: List<MediaController> = try {
            msm.getActiveSessions(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions", e)
            return null
        }
        if (controllers.isEmpty()) return null

        val playing = controllers.filter {
            val s = it.playbackState?.state
            s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
        }
        val pool = if (playing.isNotEmpty()) playing else controllers

        val local = pool.filter {
            it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        }.ifEmpty { pool }

        val best = local.maxByOrNull {
            it.playbackState?.lastPositionUpdateTime ?: 0L
        } ?: return null

        return best.packageName
    }

    private fun isLikelyMusicPlayback(attrs: AudioAttributes): Boolean {
        return when (attrs.usage) {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN -> true
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            AudioAttributes.USAGE_ALARM -> false
            else -> false
        }
    }

    private fun attachVisualizer(sessionId: Int): Boolean {
        return try {
            val v = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (fft != null && fft.isNotEmpty()) {
                                updateThrottle()
                                processFFTData(fft)
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
                )
                enabled = true
            }
            visualizer = v
            attachedSessionId = sessionId
            true
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer attach failed session=$sessionId", e)
            false
        }
    }

    private fun processFFTData(fftBytes: ByteArray) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < updateThrottle) {
            return
        }
        lastUpdateTime = currentTime
        pulseData.updateFFTData(fftBytes)
        handler.post {
            dataListener?.get()?.onDataUpdate(pulseData)
        }
    }

    private fun updateThrottle() {
        val refreshRate = currentRefreshRateHz()
        if (refreshRate > 0f && refreshRate != lastKnownRefreshRateHz) {
            lastKnownRefreshRateHz = refreshRate
            updateThrottle = (1000f / refreshRate).toLong().coerceAtLeast(1L)
        }
    }

    private fun currentRefreshRateHz(): Float {
        return try {
            context.display?.refreshRate ?: lastKnownRefreshRateHz
        } catch (e: UnsupportedOperationException) {
            lastKnownRefreshRateHz
        }
    }

    fun isCapturing(): Boolean = isProcessing
}
