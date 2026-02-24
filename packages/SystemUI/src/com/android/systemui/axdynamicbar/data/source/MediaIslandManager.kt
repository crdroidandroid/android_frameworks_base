package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.dialog.MediaOutputDialogManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class MediaIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    private val notificationMediaManager: NotificationMediaManager,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
) {
    companion object {
        private const val TAG = "MediaIslandManager"
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
    private var activeMediaController: MediaController? = null

    private val mediaControllerCallback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state != null) {
                    updatePosition(state)
                }
            }

            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {

                val current = _mediaEvent.value ?: return
                _mediaEvent.value = current.copy(outputDeviceName = getOutputDeviceName())
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
            val current = _mediaEvent.value ?: return
            _mediaEvent.value = current.copy(mediaColor = color)
        }

        override fun onAlbumArtChanged(drawable: Drawable) {
            sessionAlbumArt = drawable
            val current = _mediaEvent.value ?: return
            _mediaEvent.value = current.copy(albumArt = drawable)
        }

        override fun onAppIconChanged(drawable: Drawable) {
            sessionAppIcon = drawable
            val current = _mediaEvent.value ?: return
            _mediaEvent.value = current.copy(appIcon = drawable)
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

    private fun computeAccuratePosition(state: PlaybackState): Long {
        val basePos = state.position.coerceAtLeast(0L)
        if (!isInMotion(state)) return basePos
        val updateTime = state.lastPositionUpdateTime
        if (updateTime <= 0) return basePos
        val elapsed = SystemClock.elapsedRealtime() - updateTime
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        val duration = _mediaEvent.value?.duration ?: Long.MAX_VALUE
        return (basePos + (elapsed * speed).toLong()).coerceIn(0L, duration)
    }

    private fun updatePosition(state: PlaybackState) {
        val current = _mediaEvent.value ?: return
        val duration = current.duration.takeIf { it > 0L } ?: return
        val posMs = computeAccuratePosition(state)
        val progress = (posMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        _mediaEvent.value = current.copy(
            position = posMs,
            progress = progress,
            playbackSpeed = speed,
            positionUpdateTime = state.lastPositionUpdateTime,
        )
    }

    private fun getActiveController(): MediaController? =
        try {
            systemMediaSessionManager.getActiveSessions(null).firstOrNull()
        } catch (_: Exception) {
            null
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
                val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                
                val albumArt = sessionAlbumArt ?: run {
                    val bmp = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    bmp?.let { BitmapDrawable(context.resources, it) }
                }

                val controller = getActiveController()
                val ps = controller?.playbackState
                val existingPos = _mediaEvent.value?.position ?: 0L
                val posMs = if (ps != null) computeAccuratePosition(ps) else existingPos
                val progress =
                    if (duration > 0L) (posMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    else 0f
                val outputDevice = getOutputDeviceName()
                val customActions =
                    ps?.customActions?.take(2)?.mapNotNull { ca ->
                        val lbl =
                            ca.name?.toString()?.takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null
                        val act = ca.action?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        IslandEvent.MediaCustomAction(label = lbl, action = act)
                    } ?: emptyList()

                val pkg = controller?.packageName
                val appIcon = sessionAppIcon

                val speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
                val updateTime = ps?.lastPositionUpdateTime ?: 0L

                if (isPlaying) {
                    activeMediaPackage = pkg
                    _mediaEvent.value =
                        IslandEvent.Media(
                            track = track,
                            artist = artist,
                            isPlaying = true,
                            albumArt = albumArt,
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
                    val current = _mediaEvent.value
                    if (current != null) {
                        _mediaEvent.value =
                            current.copy(
                                isPlaying = false,
                                albumArt = albumArt ?: current.albumArt,
                                progress = progress,
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
        } catch (_: Exception) {}

    }

    fun stopListening() {
        if (!listening) return
        listening = false
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
        _mediaEvent.value = null
    }

    fun togglePlayPause() {
        val c = getActiveController() ?: return
        when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> c.transportControls.pause()
            else -> c.transportControls.play()
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

    fun openMediaApp() {
        val pkg = getActiveController()?.packageName ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open media app: $pkg", e)
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

