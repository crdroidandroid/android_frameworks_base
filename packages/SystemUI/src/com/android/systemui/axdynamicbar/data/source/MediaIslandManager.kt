package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon as DrawableIcon
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager as SystemMediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.util.concurrency.RepeatableExecutor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@SysUISingleton
class MediaIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    @Background private val backgroundExecutor: RepeatableExecutor,
    private val notificationMediaManager: NotificationMediaManager,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
) {
    companion object {
        private const val TAG = "MediaIslandManager"
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L

        private const val UNKNOWN_DURATION = 0L
    }

    private val _mediaEvent = MutableStateFlow<IslandEvent.Media?>(null)
    val mediaEvent: StateFlow<IslandEvent.Media?> = _mediaEvent.asStateFlow()

    var activeMediaPackage: String? = null
        private set

    var onMediaSessionLost: (() -> Unit)? = null

    @Volatile private var listening = false
    @Volatile private var sessionMediaColor: Int = 0
    @Volatile private var sessionAlbumArt: Drawable? = null
    @Volatile private var sessionAppIcon: Drawable? = null
    private val systemMediaSessionManager: SystemMediaSessionManager by lazy {
        context.getSystemService(SystemMediaSessionManager::class.java)
    }
    @Volatile private var activeMediaController: MediaController? = null

    private val mediaControllerCallback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state != null) {
                    updatePosition(state)
                }
            }

            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
                _mediaEvent.update { event -> event?.copy(outputDeviceName = getOutputDeviceName()) }
            }

            override fun onSessionDestroyed() {
                activeMediaController = null
                onMediaSessionLost?.invoke()
            }
        }

    private val sessionChangedListener =
        SystemMediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindController(controllers)
        }

    private val mediaSessionListener = object : MediaSessionManager.MediaDataListener {
        override fun onMediaColorsChanged(color: Int) {
            sessionMediaColor = color
            _mediaEvent.update { event -> event?.copy(mediaColor = color) }
        }

        override fun onAlbumArtChanged(drawable: Drawable) {
            sessionAlbumArt = drawable
            _mediaEvent.update { event -> event?.copy(albumArt = drawable) }
        }

        override fun onAppIconChanged(drawable: Drawable) {
            sessionAppIcon = drawable
            _mediaEvent.update { event -> event?.copy(appIcon = drawable) }
        }

        override fun onMetadataChanged(track: String, artist: String) {
            _mediaEvent.update { event ->
                if (event == null) return@update event
                val normalizedTrack = track.takeUnless { it == "Unknown" } ?: event.track
                val normalizedArtist = artist.takeUnless { it == "Unknown" } ?: event.artist
                if (event.track == normalizedTrack && event.artist == normalizedArtist) {
                    event
                } else {
                    event.copy(track = normalizedTrack, artist = normalizedArtist)
                }
            }
        }
    }

    private fun bindController(controllers: List<MediaController>?) {
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        activeMediaController = controllers?.firstOrNull()
        activeMediaController?.registerCallback(mediaControllerCallback, mainHandler)
        activeMediaController?.playbackState?.let {
            updatePosition(it)
        }

        if (controllers.isNullOrEmpty() && _mediaEvent.value != null) {
            onMediaSessionLost?.invoke()
        }
    }

    private fun isInMotion(state: PlaybackState): Boolean =
        state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_FAST_FORWARDING ||
            state.state == PlaybackState.STATE_REWINDING

    private fun computeAccuratePosition(
        state: PlaybackState,
        duration: Long = _mediaEvent.value?.duration?.takeIf { it > 0L } ?: Long.MAX_VALUE,
    ): Long {
        val maxPosition = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val basePos = state.position.coerceAtLeast(0L)
        if (!isInMotion(state)) return basePos.coerceAtMost(maxPosition)
        val updateTime = state.lastPositionUpdateTime
        if (updateTime <= 0) return basePos.coerceAtMost(maxPosition)
        val elapsed = (SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L)
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        return (basePos + (elapsed * speed).toLong()).coerceIn(0L, maxPosition)
    }

    private var cancelProgressPolling: Runnable? = null

    private fun tickProgress() {
        _mediaEvent.update { event ->
            if (event == null || !event.isPlaying || event.duration <= 0L) return@update event
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - event.positionUpdateTime
            val position =
                (event.position + (elapsed * event.playbackSpeed).toLong())
                    .coerceIn(0L, event.duration)
            val progress = (position.toFloat() / event.duration).coerceIn(0f, 1f)
            event.copy(progress = progress, position = position, positionUpdateTime = now)
        }
    }

    fun startProgressPolling() {
        if (cancelProgressPolling != null) return
        cancelProgressPolling = backgroundExecutor.executeRepeatedly(
            ::tickProgress, 0L, POSITION_UPDATE_INTERVAL_MS,
        )
    }

    fun stopProgressPolling() {
        cancelProgressPolling?.run()
        cancelProgressPolling = null
    }

    private fun updatePosition(state: PlaybackState) {
        val now = SystemClock.elapsedRealtime()
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        val playing = isInMotion(state)
        _mediaEvent.update { event ->
            val duration = event?.duration?.takeIf { it > 0L } ?: return@update event
            val posMs = computeAccuratePosition(state, duration)
            val progress = (posMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            event.copy(
                isPlaying = playing,
                position = posMs,
                progress = progress,
                playbackSpeed = speed,
                positionUpdateTime = now,
            )
        }
    }

    private fun getActiveController(): MediaController? =
        activeMediaController ?: try {
            systemMediaSessionManager.getActiveSessions(null).firstOrNull()
        } catch (_: Exception) {
            null
        }

    private fun resolveAlbumArt(track: String, artist: String, albumArt: Drawable?): Drawable? {
        val current = _mediaEvent.value ?: return albumArt
        return if (current.track == track && current.artist == artist) {
            current.albumArt ?: albumArt
        } else {
            albumArt
        }
    }

    private val mediaListener =
        object : NotificationMediaManager.MediaListener {
            override fun onPrimaryMetadataOrStateChanged(
                metadata: MediaMetadata?,
                @PlaybackState.State state: Int,
            ) {
                val isPlaying = state == PlaybackState.STATE_PLAYING
                val track =
                    metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: ""
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val rawDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val duration = if (rawDuration > 0L) rawDuration else UNKNOWN_DURATION

                val albumArt = sessionAlbumArt ?: run {
                    val bmp = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    bmp?.let { BitmapDrawable(context.resources, it) }
                }
                val resolvedAlbumArt = resolveAlbumArt(track, artist, albumArt)

                val controller = getActiveController()
                val ps = controller?.playbackState
                val existingPos = _mediaEvent.value?.position ?: 0L
                val posMs = if (ps != null) computeAccuratePosition(ps, duration) else existingPos
                val progress =
                    if (duration > 0L) (posMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    else 0f
                val outputDevice = getOutputDeviceName()
                val pkg = controller?.packageName
                val customActions =
                    ps?.customActions?.take(2)?.mapNotNull { ca ->
                        val lbl =
                            ca.name?.toString()?.takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null
                        val act = ca.action?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val icon = try {
                            if (ca.icon != 0 && pkg != null) {
                                DrawableIcon.createWithResource(pkg, ca.icon)
                                    .loadDrawable(context)
                            } else null
                        } catch (_: Exception) { null }
                        IslandEvent.MediaCustomAction(label = lbl, action = act, icon = icon)
                    } ?: emptyList()
                val appIcon = sessionAppIcon

                val speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
                val updateTime = SystemClock.elapsedRealtime()

                if (isPlaying) {
                    activeMediaPackage = pkg
                    _mediaEvent.value =
                        IslandEvent.Media(
                            track = track,
                            artist = artist,
                            isPlaying = true,
                            albumArt = resolvedAlbumArt,
                            progress = progress,
                            duration = duration,
                            position = posMs,
                            playbackSpeed = speed,
                            positionUpdateTime = updateTime,
                            outputDeviceName = outputDevice,
                            customActions = customActions,
                            appIcon = appIcon,
                            packageName = pkg ?: "",
                            mediaColor = sessionMediaColor,
                        )
                } else {
                    _mediaEvent.update { event ->
                        event?.copy(
                            isPlaying = false,
                            albumArt = resolvedAlbumArt ?: event.albumArt,
                            progress = progress,
                            duration = duration.takeIf { it > 0L } ?: event.duration,
                            position = posMs,
                            playbackSpeed = speed,
                            positionUpdateTime = updateTime,
                        )
                    }
                }
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        notificationMediaManager.addCallback(mediaListener)
        MediaSessionManager.get().addListener(mediaSessionListener)
        try {
            bindController(systemMediaSessionManager.getActiveSessions(null))
            systemMediaSessionManager.addOnActiveSessionsChangedListener(
                sessionChangedListener,
                null,
                mainHandler,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind initial media sessions", e)
        }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        stopProgressPolling()
        notificationMediaManager.removeCallback(mediaListener)
        MediaSessionManager.get().removeListener(mediaSessionListener)
        try {
            systemMediaSessionManager.removeOnActiveSessionsChangedListener(sessionChangedListener)
            activeMediaController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = null
        } catch (_: Exception) {}
        _mediaEvent.value = null
        activeMediaPackage = null
        sessionMediaColor = 0
        sessionAlbumArt = null
        sessionAppIcon = null
    }

    fun clear() {
        stopProgressPolling()
        _mediaEvent.value = null
        activeMediaPackage = null
    }

    fun togglePlayPause() {
        val c = getActiveController() ?: return
        val playbackState = c.playbackState
        val playing = playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
        val now = SystemClock.elapsedRealtime()
        _mediaEvent.update { event ->
            if (event == null) return@update null
            val duration = event.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            val position =
                playbackState?.let { computeAccuratePosition(it, duration) } ?: event.position
            event.copy(isPlaying = !playing, position = position, positionUpdateTime = now)
        }
    }

    fun skipNext() {
        getActiveController()?.transportControls?.skipToNext()
    }

    fun skipPrev() {
        getActiveController()?.transportControls?.skipToPrevious()
    }

    fun seekTo(position: Long) {
        getActiveController()?.transportControls?.seekTo(position)
    }

    fun sendCustomAction(action: String) {
        getActiveController()?.transportControls?.sendCustomAction(action, null)
    }

    fun getMediaAppIntent(): Intent? {
        val pkg = getActiveController()?.packageName ?: return null
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun openMediaApp() {
        val intent = getMediaAppIntent() ?: return
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open media app", e)
        }
    }

    private fun getOutputDeviceName(): String =
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            val primary =
                outputs.firstOrNull {
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                        it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                } ?: outputs.firstOrNull()
            when (primary?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    primary.productName?.toString()?.takeIf { it.isNotEmpty() } ?: "Bluetooth"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                null -> "Speaker"
                else -> primary.productName?.toString()?.takeIf { it.isNotEmpty() } ?: "Speaker"
            }
        } catch (_: Exception) {
            "Speaker"
        }

    fun openMediaOutputSwitcher() {
        val pkg = getActiveController()?.packageName ?: return
        mainHandler.post {
            try {
                mediaOutputDialogManager.createAndShow(packageName = pkg, aboveStatusBar = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open media output switcher", e)
            }
        }
    }
}
