/*
 * SPDX-FileCopyrightText: The risingOS Android Project
 * SPDX-FileCopyrightText: The AxionAOSP Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionLegacyHelper
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaSessionManagerHelper private constructor(ctx: Context) {

    interface MediaMetadataListener {
        fun onMediaMetadataChanged() {}
        fun onPlaybackStateChanged() {}
    }

    private val context: Context = ctx.applicationContext

    private val _mediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val mediaMetadata: StateFlow<MediaMetadata?> = _mediaMetadata

    private val _playbackState = MutableStateFlow<PlaybackState?>(null)
    val playbackState: StateFlow<PlaybackState?> = _playbackState

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private var collectJob: Job? = null
    private var sessionsUpdateJob: Job? = null

    private var lastSavedPackageName: String? = null

    private val mediaSessionManager: MediaSessionManager =
        context.getSystemService(MediaSessionManager::class.java)!!

    private var activeController: MediaController? = null
    private val listeners = LinkedHashSet<MediaMetadataListener>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionsListening = false

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            _mediaMetadata.value = metadata
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            _playbackState.value = state
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            // Avoid doing any work if nobody is listening.
            if (listeners.isEmpty()) return@OnActiveSessionsChangedListener

            val safeControllers = controllers.orEmpty()

            sessionsUpdateJob?.cancel()
            sessionsUpdateJob = scope.launch {
                val picked = withContext(Dispatchers.IO) { pickBestController(safeControllers) }
                if (sameSessions(activeController, picked)) return@launch

                activeController?.unregisterCallback(mediaControllerCallback)
                activeController = picked
                picked?.registerCallback(mediaControllerCallback)

                _mediaMetadata.value = picked?.metadata
                _playbackState.value = picked?.playbackState
                saveLastNonNullPackageName()
            }
        }

    fun addMediaMetadataListener(listener: MediaMetadataListener) {
        val wasEmpty = listeners.isEmpty()
        listeners.add(listener)

        if (wasEmpty) {
            startTracking()
        }

        // Push current state immediately.
        listener.onMediaMetadataChanged()
        listener.onPlaybackStateChanged()
    }

    fun removeMediaMetadataListener(listener: MediaMetadataListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopTracking()
        }
    }

    private fun startTracking() {
        if (!sessionsListening) {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                null,
                mainHandler
            )
            sessionsListening = true

            // Force an initial refresh.
            sessionsChangedListener.onActiveSessionsChanged(
                mediaSessionManager.getActiveSessions(null)
            )
        }

        if (collectJob == null) {
            collectJob = scope.launch {
                launch {
                    mediaMetadata.collect { notifyListeners { onMediaMetadataChanged() } }
                }
                launch {
                    playbackState.collect { notifyListeners { onPlaybackStateChanged() } }
                }
            }
        }
    }

    private fun stopTracking() {
        collectJob?.cancel()
        collectJob = null

        sessionsUpdateJob?.cancel()
        sessionsUpdateJob = null

        if (sessionsListening) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            sessionsListening = false
        }

        activeController?.unregisterCallback(mediaControllerCallback)
        activeController = null

        _mediaMetadata.value = null
        _playbackState.value = null
    }

    private fun notifyListeners(action: MediaMetadataListener.() -> Unit) {
        listeners.forEach { it.action() }
    }

    private fun isEligibleState(state: Int?): Boolean {
        return when (state) {
            null,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR -> false
            else -> true
        }
    }

    private fun pickBestController(controllers: List<MediaController>): MediaController? {
        var localController: MediaController? = null
        val remoteSessions = HashSet<String>()

        controllers
            .asSequence()
            .filter { controller ->
                isEligibleState(controller.playbackState?.state) && controller.playbackInfo != null
            }
            .sortedWith(
                compareByDescending<MediaController> { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    .thenByDescending { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
                    .thenByDescending { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            )
            .forEach { controller ->
                when (controller.playbackInfo?.playbackType) {
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> {
                        remoteSessions.add(controller.packageName)
                        if (localController?.packageName == controller.packageName) {
                            localController = null
                        }
                    }
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> {
                        if (!remoteSessions.contains(controller.packageName)) {
                            localController = localController ?: controller
                        }
                    }
                }
            }

        return localController
    }

    fun seekTo(time: Long) {
        activeController?.transportControls?.seekTo(time)
    }

    fun getTotalDuration(): Long =
        mediaMetadata.value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    private fun saveLastNonNullPackageName() {
        activeController?.packageName?.takeIf { it.isNotEmpty() }?.let { pkg ->
            if (pkg != lastSavedPackageName) {
                lastSavedPackageName = pkg
            }
        }
    }

    fun getCurrentMediaMetadata(): MediaMetadata? = mediaMetadata.value

    fun getMediaAppIcon(): Drawable? {
        val packageName = activeController?.packageName ?: return null
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isMediaControllerAvailable(): Boolean =
        activeController?.packageName?.isNotEmpty() ?: false

    fun isMediaSessionActive(): Boolean {
        val controller = activeController ?: return false
        val st = controller.playbackState?.state
        return st != null &&
            st != PlaybackState.STATE_NONE &&
            st != PlaybackState.STATE_STOPPED &&
            st != PlaybackState.STATE_ERROR
    }

    fun isMediaPaused(): Boolean = playbackState.value?.state == PlaybackState.STATE_PAUSED

    fun isMediaPlaying(): Boolean = playbackState.value?.state == PlaybackState.STATE_PLAYING

    fun getMediaControllerPlaybackState(): PlaybackState? = activeController?.playbackState

    private fun sameSessions(a: MediaController?, b: MediaController?): Boolean {
        if (a == b) return true
        if (a == null) return false
        return a.controlsSameSession(b)
    }

    private fun dispatchMediaKeyWithWakeLockToMediaSession(keycode: Int) {
        val helper = MediaSessionLegacyHelper.getHelper(context) ?: return
        var event = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            keycode,
            0
        )
        helper.sendMediaButtonEvent(event, true)
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP)
        helper.sendMediaButtonEvent(event, true)
    }

    fun prevSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun nextSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun toggleMediaPlaybackState() {
        if (isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun launchMediaApp() {
        lastSavedPackageName?.takeIf { it.isNotEmpty() }?.let {
            launchMediaPlayerApp(it)
        }
    }

    fun launchMediaPlayerApp(packageName: String) {
        if (packageName.isEmpty()) return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        context.startActivity(launchIntent)
    }

    companion object {
        @Volatile
        private var instance: MediaSessionManagerHelper? = null

        fun getInstance(context: Context): MediaSessionManagerHelper =
            instance ?: synchronized(this) {
                instance ?: MediaSessionManagerHelper(context).also { instance = it }
            }
    }
}
