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
import android.media.session.MediaSession
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
import com.android.systemui.plugins.ActivityStarter
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
    private val activityStarter: ActivityStarter,
) {
    companion object {
        private const val TAG = "MediaIslandManager"
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L

        private const val UNKNOWN_DURATION = 0L

        // Scores used to pick the session that matches what NotificationMediaManager reports.
        private const val SCORE_TRACK_MATCH = 4
        private const val SCORE_IN_MOTION = 2
        private const val SCORE_PREFERRED_PKG = 1
    }

    private val _mediaEvent = MutableStateFlow<IslandEvent.Media?>(null)
    val mediaEvent: StateFlow<IslandEvent.Media?> = _mediaEvent.asStateFlow()

    var activeMediaPackage: String? = null
        private set

    var onMediaSessionLost: (() -> Unit)? = null

    @Volatile private var listening = false
    @Volatile private var sessionAlbumArt: Drawable? = null
    @Volatile private var sessionAppIcon: Drawable? = null
    private val systemMediaSessionManager: SystemMediaSessionManager by lazy {
        context.getSystemService(SystemMediaSessionManager::class.java)
    }

    @Volatile private var activeMediaController: MediaController? = null
    @Volatile private var activeControllerCallback: ControllerCallback? = null

    private inner class ControllerCallback(private val token: MediaSession.Token) :
        MediaController.Callback() {

        private fun isCurrent(): Boolean = activeMediaController?.sessionToken == token

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state == null || !isCurrent()) return
            updatePosition(state, activeMediaController?.packageName)
        }

        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
            if (!isCurrent()) return
            _mediaEvent.update { event -> event?.copy(outputDeviceName = getOutputDeviceName()) }
        }

        override fun onSessionDestroyed() {
            if (!isCurrent()) return
            val destroyedPkg = activeMediaController?.packageName
            setActiveController(null)
            val shown = _mediaEvent.value
            setActiveController(findController(shown?.track, shown?.artist, shown?.packageName))
            val shownPkg = shown?.packageName
            if (shown != null && (shownPkg.isNullOrEmpty() || shownPkg == destroyedPkg)) {
                onMediaSessionLost?.invoke()
            }
        }
    }

    private val sessionChangedListener =
        SystemMediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onActiveSessionsChanged(controllers.orEmpty())
        }

    private val mediaSessionListener = object : MediaSessionManager.MediaDataListener {
        override fun onMediaColorsChanged(color: Int) {}

        override fun onAlbumArtChanged(drawable: Drawable?) {
            if (sessionAlbumArt === drawable) return
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

    private fun activeSessions(): List<MediaController> =
        try {
            systemMediaSessionManager.getActiveSessions(null).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query active media sessions", e)
            emptyList()
        }

    private fun matchesTrack(controller: MediaController, track: String?, artist: String?): Boolean {
        if (track.isNullOrEmpty()) return false
        val md = controller.metadata ?: return false
        val title =
            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title != track) return false
        val sessionArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return artist.isNullOrEmpty() || sessionArtist.isNullOrEmpty() || sessionArtist == artist
    }

    private fun pickController(
        sessions: List<MediaController>,
        track: String?,
        artist: String?,
        preferredPkg: String?,
    ): MediaController? {
        if (sessions.isEmpty()) return null
        return sessions.maxByOrNull { c ->
            var score = 0
            if (matchesTrack(c, track, artist)) score += SCORE_TRACK_MATCH
            if (c.playbackState?.let { isInMotion(it) } == true) score += SCORE_IN_MOTION
            if (!preferredPkg.isNullOrEmpty() && c.packageName == preferredPkg) {
                score += SCORE_PREFERRED_PKG
            }
            score
        }
    }

    private fun findController(track: String?, artist: String?, preferredPkg: String?) =
        pickController(activeSessions(), track, artist, preferredPkg)

    private fun setActiveController(controller: MediaController?) {
        val current = activeMediaController
        if (current?.sessionToken == controller?.sessionToken) return
        activeControllerCallback?.let { cb ->
            try {
                current?.unregisterCallback(cb)
            } catch (_: Exception) {}
        }
        activeControllerCallback = null
        activeMediaController = controller
        if (controller != null) {
            val cb = ControllerCallback(controller.sessionToken)
            try {
                controller.registerCallback(cb, mainHandler)
                activeControllerCallback = cb
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register media controller callback", e)
            }
        }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>) {
        val event = _mediaEvent.value
        if (event == null) {
            setActiveController(
                pickController(controllers, null, null, activeMediaController?.packageName)
            )
            return
        }
        if (controllers.isEmpty()) {
            setActiveController(null)
            onMediaSessionLost?.invoke()
            return
        }
        setActiveController(pickController(controllers, event.track, event.artist, event.packageName))
        if (event.packageName.isNotEmpty() && controllers.none { it.packageName == event.packageName }) {
            onMediaSessionLost?.invoke()
        }
    }

    private fun getActiveController(): MediaController? {
        val event = _mediaEvent.value
        val bound = activeMediaController
        if (bound != null &&
            (event == null || event.packageName.isEmpty() || bound.packageName == event.packageName)
        ) {
            return bound
        }
        return findController(event?.track, event?.artist, event?.packageName) ?: bound
    }

    private fun isInMotion(state: PlaybackState): Boolean =
        state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_FAST_FORWARDING ||
            state.state == PlaybackState.STATE_REWINDING

    /** [duration] <= 0 means unknown; the position is then not clamped. */
    private fun computeAccuratePosition(state: PlaybackState, duration: Long): Long {
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

    private fun updatePosition(state: PlaybackState, pkg: String?) {
        val now = SystemClock.elapsedRealtime()
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        val playing = isInMotion(state)
        _mediaEvent.update { event ->
            if (event == null) return@update null
            // Never let one app's playback state move another app's card.
            if (!pkg.isNullOrEmpty() && event.packageName.isNotEmpty() && event.packageName != pkg) {
                return@update event
            }
            val duration = event.duration
            if (duration <= 0L) {
                return@update event.copy(
                    isPlaying = playing,
                    playbackSpeed = speed,
                    positionUpdateTime = now,
                )
            }
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

    private fun buildCustomActions(
        ps: PlaybackState?,
        pkg: String?,
    ): List<IslandEvent.MediaCustomAction> =
        ps?.customActions?.take(2)?.mapNotNull { ca ->
            val lbl = ca.name?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val act = ca.action?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val icon = try {
                if (ca.icon != 0 && pkg != null) {
                    DrawableIcon.createWithResource(pkg, ca.icon).loadDrawable(context)
                } else null
            } catch (_: Exception) { null }
            IslandEvent.MediaCustomAction(label = lbl, action = act, icon = icon)
        } ?: emptyList()

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

                val controller =
                    if (metadata != null) {
                        findController(track, artist, null).also { setActiveController(it) }
                    } else {
                        activeMediaController
                    }
                val ps = controller?.playbackState
                val pkg = controller?.packageName

                val current = _mediaEvent.value
                val sameSource = current != null && pkg != null && current.packageName == pkg
                val sameTrack =
                    sameSource && current!!.track == track && current.artist == artist

                val effectiveDuration =
                    when {
                        duration > 0L -> duration
                        sameTrack -> current!!.duration
                        else -> UNKNOWN_DURATION
                    }
                val posMs =
                    ps?.let { computeAccuratePosition(it, effectiveDuration) }
                        ?: if (sameTrack) current!!.position else 0L
                val progress =
                    if (effectiveDuration > 0L) {
                        (posMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                val metadataArt =
                    (metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART))
                        ?.let { BitmapDrawable(context.resources, it) }
                val albumArt =
                    if (sameTrack) current?.albumArt ?: sessionAlbumArt ?: metadataArt
                    else metadataArt ?: sessionAlbumArt
                val appIcon = if (sameSource) current?.appIcon ?: sessionAppIcon else sessionAppIcon

                val outputDevice = getOutputDeviceName()
                val customActions = buildCustomActions(ps, pkg)
                val speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f
                val updateTime = SystemClock.elapsedRealtime()

                if (isPlaying) {
                    activeMediaPackage = pkg
                    _mediaEvent.value =
                        IslandEvent.Media(
                            track = track,
                            artist = artist,
                            isPlaying = true,
                            albumArt = albumArt,
                            progress = progress,
                            duration = effectiveDuration,
                            position = posMs,
                            playbackSpeed = speed,
                            positionUpdateTime = updateTime,
                            outputDeviceName = outputDevice,
                            customActions = customActions,
                            appIcon = appIcon,
                            packageName = pkg ?: "",
                        )
                } else {
                    _mediaEvent.update { event ->
                        when {
                            event == null -> null
                            metadata == null -> event.copy(isPlaying = false)
                            else ->
                                event.copy(
                                    track = track,
                                    artist = artist,
                                    isPlaying = false,
                                    albumArt = albumArt ?: if (sameTrack) event.albumArt else null,
                                    progress = progress,
                                    duration = effectiveDuration,
                                    position = posMs,
                                    playbackSpeed = speed,
                                    positionUpdateTime = updateTime,
                                    outputDeviceName = outputDevice,
                                    customActions = customActions,
                                    appIcon = appIcon ?: event.appIcon,
                                    packageName = pkg ?: event.packageName,
                                )
                        }
                    }
                    if (pkg != null && _mediaEvent.value != null) activeMediaPackage = pkg
                }
            }
        }

    fun startListening() {
        if (listening) return
        listening = true
        notificationMediaManager.addCallback(mediaListener)
        MediaSessionManager.get().addListener(mediaSessionListener)
        try {
            setActiveController(pickController(activeSessions(), null, null, null))
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
        } catch (_: Exception) {}
        setActiveController(null)
        _mediaEvent.value = null
        activeMediaPackage = null
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
        val playing = playbackState?.let { isInMotion(it) } == true
        if (playing) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
        val now = SystemClock.elapsedRealtime()
        _mediaEvent.update { event ->
            if (event == null) return@update null
            val position =
                playbackState?.let { computeAccuratePosition(it, event.duration) } ?: event.position
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
        val pkg =
            getActiveController()?.packageName
                ?: _mediaEvent.value?.packageName?.takeIf { it.isNotEmpty() }
                ?: return null
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun openMediaApp() {
        val sessionActivity = getActiveController()?.sessionActivity
        if (sessionActivity != null) {
            try {
                activityStarter.postStartActivityDismissingKeyguard(sessionActivity)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open media session activity", e)
            }
        }
        val intent = getMediaAppIntent() ?: return
        try {
            activityStarter.postStartActivityDismissingKeyguard(intent, 0)
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
                mediaOutputDialogManager.createAndShow(
                    packageName = pkg,
                    aboveStatusBar = true,
                    useSystemColors = true,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open media output switcher", e)
            }
        }
    }
}
