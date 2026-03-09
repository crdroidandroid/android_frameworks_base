/*
 * SPDX-FileCopyrightText: VoltageOS
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar

import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.VibrationEffect
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.util.MediaSessionManagerHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class OnGoingActionProgressController(
    private val context: Context,
    private val notificationListener: NotificationListener,
    private val keyguardStateController: KeyguardStateController,
    private val headsUpManager: HeadsUpManager,
    private val vibrator: VibratorHelper,
    @VisibleForTesting
    private val bgDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : NotificationListener.NotificationHandler,
    KeyguardStateController.Callback,
    OnHeadsUpChangedListener {

    private val contentResolver: ContentResolver = context.contentResolver
    private val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mediaSessionHelper = MediaSessionManagerHelper.getInstance(context)

    private val iconCache = HashMap<String, Drawable>()
    private val inFlightIconLoads = ConcurrentHashMap<String, Job>()

    private var showMediaProgress = true
    private var isTrackingProgress = false
    private var isForceHidden = false
    private var headsUpPinned = false
    private var isEnabled = false
    private var isCompactModeEnabled = false
    private var chipColorMode = CHIP_COLOR_MODE_DEFAULT

    private var currentProgress = 0
    private var currentProgressMax = 0
    private var currentIcon: Drawable? = null

    private var currentMetadata: MediaMetadata? = null
    private var currentTrackTitle: String? = null
    private var currentArtistName: String? = null
    private var currentAlbumArt: Bitmap? = null

    private var currentChipBgColor: Int? = null
    private var lastColorExtractedIcon: Drawable? = null
    private var lastColorExtractedAlbumArt: Bitmap? = null
    private var chipIconColor: Int? = null
    private var chipAlbumColor: Int? = null

    private var isMenuVisible = false
    private var isSystemChipVisible = false

    private var trackedNotificationKey: String? = null
    private var trackedPackageName: String? = null

    private var isViewAttached = false
    private var isExpanded = false

    private var pauseStale = false
    private var pausedStaleJob: Job? = null

    private var lastUpdateTime = 0L
    private var uiUpdateJob: Job? = null

    private var mediaProgressJob: Job? = null
    private var finishedProgressTimeoutJob: Job? = null
    private var compactCollapseJob: Job? = null
    private var menuCollapseJob: Job? = null

    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    private val settingsObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri == null) return
                if (uri == Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED) ||
                    uri == Settings.System.getUriFor(ONGOING_MEDIA_PROGRESS) ||
                    uri == Settings.System.getUriFor(ONGOING_COMPACT_MODE_ENABLED) ||
                    uri == Settings.System.getUriFor(ONGOING_CHIP_COLOR_MODE)) {
                    updateSettings()
                }
            }

            fun register() {
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED),
                    false,
                    this,
                    UserHandle.USER_ALL
                )
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(ONGOING_MEDIA_PROGRESS),
                    false,
                    this,
                    UserHandle.USER_ALL
                )
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(ONGOING_COMPACT_MODE_ENABLED),
                    false,
                    this,
                    UserHandle.USER_ALL
                )
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(ONGOING_CHIP_COLOR_MODE),
                    false,
                    this,
                    UserHandle.USER_ALL
                )
                updateSettings()
            }

            fun unregister() {
                contentResolver.unregisterContentObserver(this)
            }
        }

    private val mediaMetadataListener = object : MediaSessionManagerHelper.MediaMetadataListener {
        override fun onMediaMetadataChanged() {
            currentAlbumArt = null
            pauseStaleCheck()
            requestUiUpdate()
        }

        override fun onPlaybackStateChanged() {
            pauseStaleCheck()
            requestUiUpdate()
        }
    }

    init {
        keyguardStateController.addCallback(this)
        headsUpManager.addListener(this)
        notificationListener.addNotificationHandler(this)

        settingsObserver.register()
        mediaSessionHelper.addMediaMetadataListener(mediaMetadataListener)

        isViewAttached = true
        updateSettings()
    }

    private fun updateMediaIcon() {
        val appIcon = mediaSessionHelper.getMediaAppIcon()
        if (currentIcon === appIcon && currentIcon != null) return
        if (appIcon != null) {
            currentIcon = appIcon
        } else {
            currentIcon = context.resources.getDrawable(R.drawable.ic_default_music_icon, context.theme)
        }
        if (chipColorMode == CHIP_COLOR_MODE_ICON && currentIcon != null) {
            invalidateChipBgColor()
            currentIcon?.let { extractAndApplyChipBgColorFromIcon(it) }
        }
    }

    private fun updateTrackMetadata() {
        val metadata = mediaSessionHelper.getCurrentMediaMetadata()
        if (currentMetadata === metadata && currentMetadata != null) return
        currentMetadata = metadata
        currentTrackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        currentArtistName = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }

        val art =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        if (art != null) {
            currentAlbumArt = art
            if (chipColorMode == CHIP_COLOR_MODE_ALBUM_ART) {
                invalidateChipBgColor()
                currentAlbumArt?.let { extractAndApplyChipBgColorFromAlbumArt(it) }
            }
        } else if (metadata != null) {  // Partial update, force refresh
            mediaSessionHelper.refreshActiveControllerMetadata()
        }
    }

    private fun publish(state: ProgressState) {
        _state.value = state
    }

    fun expandCompactView() {
        val wasExpanded = isExpanded
        isExpanded = true
        if (!wasExpanded) requestUiUpdate()
    }

    fun collapseExpandViewWithDelay() {
        if (!isExpanded) return
        compactCollapseJob?.cancel()
        compactCollapseJob = mainScope.launch {
            delay(COMPACT_COLLAPSE_TIMEOUT_MS)
            if (isCompactModeEnabled && isExpanded) {
                isExpanded = false
                requestUiUpdate()
            }
        }
    }

    private fun requestUiUpdate() {
        val now = System.currentTimeMillis()

        uiUpdateJob?.cancel()
        uiUpdateJob = mainScope.launch {
            val elapsed = now - lastUpdateTime
            if (elapsed <= DEBOUNCE_DELAY_MS) {
                delay(DEBOUNCE_DELAY_MS)
            }
            lastUpdateTime = System.currentTimeMillis()
            updateViews()
        }
    }

    private fun invalidateChipBgColor() {
        currentChipBgColor = null
        lastColorExtractedIcon = null
        lastColorExtractedAlbumArt = null
    }

    private suspend fun extractDominantColorFromBitmap(bitmap: Bitmap): Int? =
        withContext(bgDispatcher) {
            try {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@withContext null

                val palette = Palette.from(bitmap).generate()

                val candidates = listOfNotNull(
                    palette.vibrantSwatch,
                    palette.mutedSwatch,
                    palette.dominantSwatch,
                    palette.darkVibrantSwatch,
                    palette.darkMutedSwatch,
                )

                candidates
                    .map { it.rgb }
                    .firstOrNull { color ->
                        val alpha = Color.alpha(color)
                        val luminance = ColorUtils.calculateLuminance(color)
                        alpha > 200 && luminance > 0.05 && luminance < 0.95
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract dominant color from bitmap", e)
                null
            }
        }

    private fun extractAndApplyChipBgColorFromIcon(icon: Drawable) {
        if (!isMediaSessionActiveForChip()) return
        if (icon === lastColorExtractedIcon && chipIconColor != null) return

        mainScope.launch {
            val size = (48f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = try {
                withContext(bgDispatcher) {
                    icon.toBitmap(width = size, height = size, config = Bitmap.Config.ARGB_8888)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to rasterize icon for chip color extraction", e)
                null
            } ?: return@launch

            lastColorExtractedIcon = icon
            chipIconColor = extractDominantColorFromBitmap(bitmap)
            updateProgressState()
        }
    }

    private fun extractAndApplyChipBgColorFromAlbumArt(albumArt: Bitmap) {
        if (!isMediaSessionActiveForChip()) return
        if (albumArt === lastColorExtractedAlbumArt && chipAlbumColor != null) return

        mainScope.launch {
            lastColorExtractedAlbumArt = albumArt
            chipAlbumColor = extractDominantColorFromBitmap(albumArt)
            updateProgressState()
        }
    }

    private fun updateProgressState() {
        var isVisible = !isForceHidden && !headsUpPinned && !isSystemChipVisible
        val hasMediaSession = isMediaSessionActiveForChip()
        val hasNotificationProgress = isEnabled && isTrackingProgress
        val isCompact = isCompactModeEnabled && !isExpanded

        isVisible = isVisible && (hasMediaSession || hasNotificationProgress)

        if (!isVisible) {
            publish(
                ProgressState(
                    isVisible = false,
                    progress = 0,
                    maxProgress = 0,
                    icon = null,
                    albumArt = null,
                    packageName = null,
                    isCompactMode = isCompact,
                    showMediaControls = false,
                    isMediaPlaying = false,
                    trackTitle = null,
                    artistName = null,
                    chipBgColor = null,
                )
            )
            return
        }

        val albumArt: Bitmap? = if (hasMediaSession) currentAlbumArt else null
        val isMediaPlaying = showMediaProgress && mediaSessionHelper.isMediaPlaying()
        val trackTitle = if (hasMediaSession) currentTrackTitle else null
        val artistName = if (hasMediaSession) currentArtistName else null

        if (hasMediaSession) {
            if (chipColorMode == CHIP_COLOR_MODE_ICON &&
                    chipIconColor != null) {
                currentChipBgColor = chipIconColor
            } else if (chipColorMode == CHIP_COLOR_MODE_ALBUM_ART &&
                    chipAlbumColor != null) {
                currentChipBgColor = chipAlbumColor
            }
        }

        publish(
            ProgressState(
                isVisible = true,
                progress = currentProgress,
                maxProgress = currentProgressMax,
                icon = currentIcon,
                albumArt = albumArt,
                packageName = trackedPackageName,
                isCompactMode = isCompact,
                showMediaControls = isMenuVisible,
                isMediaPlaying = isMediaPlaying,
                trackTitle = trackTitle,
                artistName = artistName,
                chipBgColor = currentChipBgColor,
            )
        )
    }

    private fun updateViews() {
        if (!isViewAttached) {
            updateProgressState()
            return
        }

        if (isForceHidden || headsUpPinned) {
            updateProgressState()
            return
        }

        val hasMediaSession = isMediaSessionActiveForChip()

        if (isCompactModeEnabled && !isExpanded) {
            if (!isEnabled && !hasMediaSession) {
                stopMediaLoop()
                updateProgressState()
                return
            }
            if (hasMediaSession) {
                updateMediaIcon()
                ensureMediaLoopRunning()
            } else {
                stopMediaLoop()
                updateNotificationProgress()
            }
        } else {
            val isMediaPlaying = showMediaProgress && mediaSessionHelper.isMediaPlaying()
            if (isTrackingProgress && !isMediaPlaying && !isMenuVisible) {
                if (hasMediaSession) {
                    pauseStale = true
                }
                stopMediaLoop()
                updateNotificationProgress()
            } else if (hasMediaSession) {
                updateMediaIcon()
                updateTrackMetadata()
                ensureMediaLoopRunning()
            } else {
                stopMediaLoop()
                updateNotificationProgress()
            }
        }

        updateProgressState()
    }

    private fun ensureMediaLoopRunning() {
        if (mediaProgressJob?.isActive == true) return
        mediaProgressJob = mainScope.launch {
            while (isActive && showMediaProgress && mediaSessionHelper.isMediaPlaying()) {
                updateMediaProgress()
                delay(MEDIA_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopMediaLoop() {
        mediaProgressJob?.cancel()
        mediaProgressJob = null
    }

    private fun pauseStaleCheck() {
        pauseStale = false
        pausedStaleJob?.cancel()
        if (mediaSessionHelper.isMediaSessionActive() &&
                !mediaSessionHelper.isMediaPlaying()) {
            pausedStaleJob = mainScope.launch {
                delay(PAUSED_STALE_GRACE_MS)
                pauseStale = true
                requestUiUpdate()
            }
        }
    }

    private fun updateMediaProgress() {
        val totalDuration = mediaSessionHelper.getTotalDuration()
        val playbackState = mediaSessionHelper.getMediaControllerPlaybackState()
        val pos = playbackState?.position ?: 0L

        currentProgress = pos.toInt()
        currentProgressMax = totalDuration.toInt().takeIf { it > 0 } ?: 100

        updateProgressState()
    }

    private fun updateNotificationProgress() {
        if (!isEnabled || !isTrackingProgress) {
            stopMediaLoop()
            return
        }

        if (currentProgressMax <= 0) {
            Log.w(TAG, "Invalid max progress $currentProgressMax, using 100")
            currentProgressMax = 100
        }

        val pkg = trackedPackageName ?: return
        loadIcon(pkg) { drawable ->
            currentIcon = drawable
            updateProgressState()
        }
    }

    private fun fetchPackageIcon(packageName: String): Drawable {
        val pm = context.packageManager
        return try {
            pm.getApplicationIcon(packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load icon for $packageName", t)
            pm.defaultActivityIcon
        }
    }

    private fun loadIcon(packageName: String, onLoaded: (Drawable?) -> Unit) {
        iconCache[packageName]?.let {
            onLoaded(it)
            return
        }

        if (inFlightIconLoads.containsKey(packageName)) return

        val job = mainScope.launch {
            val drawable = withContext(bgDispatcher) {
                fetchPackageIcon(packageName)
            }

            val sizePx = (24f * context.resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, sizePx, sizePx)

            iconCache[packageName] = drawable
            onLoaded(drawable)
        }

        inFlightIconLoads[packageName] = job
        job.invokeOnCompletion { inFlightIconLoads.remove(packageName) }
    }

    private fun extractProgress(notification: Notification) {
        val extras = notification.extras
        currentProgressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100)
        currentProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
    }

    private fun cancelFinishedProgressTimeout() {
        finishedProgressTimeoutJob?.cancel()
        finishedProgressTimeoutJob = null
    }

    private fun scheduleFinishedProgressTimeoutIfNeeded() {
        if (!isTrackingProgress) {
            cancelFinishedProgressTimeout()
            return
        }
        val keyAtSchedule = trackedNotificationKey ?: run {
            cancelFinishedProgressTimeout()
            return
        }

        cancelFinishedProgressTimeout()
        finishedProgressTimeoutJob = mainScope.launch {
            delay(PROGRESS_TIMEOUT_MS)

            if (!isTrackingProgress) return@launch
            if (trackedNotificationKey != keyAtSchedule) return@launch

            val sbn = findNotificationByKey(keyAtSchedule)
            if (sbn == null || !hasProgress(sbn.notification)) {
                clearProgressTracking()
                return@launch
            }

            val stillFinished = currentProgressMax > 0 && currentProgress >= currentProgressMax
            if (!stillFinished) return@launch

            clearProgressTracking()
        }
    }

    private fun trackProgress(sbn: StatusBarNotification) {
        isTrackingProgress = true
        trackedNotificationKey = sbn.key
        trackedPackageName = sbn.packageName
        extractProgress(sbn.notification)
        requestUiUpdate()
        scheduleFinishedProgressTimeoutIfNeeded()
    }

    private fun clearProgressTracking() {
        isTrackingProgress = false
        trackedNotificationKey = null
        trackedPackageName = null
        currentProgress = 0
        currentProgressMax = 0
        cancelFinishedProgressTimeout()
        requestUiUpdate()
    }

    private fun updateProgressIfNeeded(sbn: StatusBarNotification) {
        if (!isTrackingProgress) return
        if (sbn.key != trackedNotificationKey) return

        if (!hasProgress(sbn.notification)) {
            clearProgressTracking()
            return
        }

        extractProgress(sbn.notification)
        requestUiUpdate()
        scheduleFinishedProgressTimeoutIfNeeded()
    }

    private fun findNotificationByKey(key: String): StatusBarNotification? {
        val actives = notificationListener.activeNotifications ?: return null
        for (n in actives) {
            if (n.key == key) return n
        }
        return null
    }

    private fun hasProgress(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val maxValid = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        return extras.containsKey(Notification.EXTRA_PROGRESS) &&
            extras.containsKey(Notification.EXTRA_PROGRESS_MAX) &&
            !indeterminate && maxValid
    }

    private fun isMediaSessionActiveForChip(): Boolean {
        if (!showMediaProgress) return false
        if (!mediaSessionHelper.isMediaSessionActive()) return false
        if (mediaSessionHelper.isMediaPlaying()) return true
        if (isMenuVisible) return true
        return !pauseStale
    }

    fun onInteraction() {
        vibrator.vibrate(HAPTIC_CLICK)
        if (isCompactModeEnabled) {
            expandCompactView()
            collapseExpandViewWithDelay()
        }
        if (isMediaSessionActiveForChip()) {
            isMenuVisible = !isMenuVisible
            pauseStaleCheck()
            collapseMediaControlsWithDelay()
        } else {
            openTrackedApp()
        }
        updateProgressState()
    }

    fun onLongPress() {
        vibrator.vibrate(HAPTIC_LONG)
        if (isMediaSessionActiveForChip()) openMediaApp() else openTrackedApp()
    }

    fun onDoubleTap() {
        if (isMediaSessionActiveForChip()) {
            vibrator.vibrate(HAPTIC_DOUBLE)
            toggleMediaPlaybackState()
        }
    }

    fun onSwipe(isNext: Boolean) {
        if (isNext) skipToNextTrack() else skipToPreviousTrack()
    }

    fun onMediaAction(action: Int) {
        vibrator.vibrate(HAPTIC_CLICK)

        when (action) {
            0 -> skipToPreviousTrack()
            1 -> toggleMediaPlaybackState()
            2 -> skipToNextTrack()
        }
        collapseExpandViewWithDelay()
        collapseMediaControlsWithDelay()
    }

    fun onSeek(fraction: Float) {
        val duration = mediaSessionHelper.getTotalDuration()
        if (duration <= 0) return
        mediaSessionHelper.seekTo((fraction * duration).toLong().coerceIn(0L, duration))
        collapseExpandViewWithDelay()
        collapseMediaControlsWithDelay()
    }

    fun collapseMediaControlsWithDelay() {
        if (!isMenuVisible) return
        menuCollapseJob?.cancel()
        menuCollapseJob = mainScope.launch {
            delay(MENU_COLLAPSE_TIMEOUT_MS)
            onMediaMenuDismiss()
        }
    }

    fun onMediaMenuDismiss() {
        isMenuVisible = false
        updateProgressState()
    }

    fun setSystemChipVisible(visible: Boolean) {
        if (isSystemChipVisible == visible) return
        isSystemChipVisible = visible
        updateProgressState()
        requestUiUpdate()
    }

    private fun openTrackedApp() {
        val pkg = trackedPackageName
        if (pkg.isNullOrEmpty()) {
            Log.w(TAG, "No tracked package available")
            return
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Log.w(TAG, "No launch intent for package: $pkg")
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }

    private fun toggleMediaPlaybackState() {
        mediaSessionHelper.toggleMediaPlaybackState()
    }

    private fun skipToNextTrack() {
        mediaSessionHelper.nextSong()
        // Update progress without delay
        updateMediaProgress()
    }

    private fun skipToPreviousTrack() {
        mediaSessionHelper.prevSong()
        // Update progress without delay
        updateMediaProgress()
    }

    private fun openMediaApp() {
        mediaSessionHelper.launchMediaApp()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?
    ) {
        if (sbn == null) return
        mainScope.launch { handleNotificationPosted(sbn) }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?
    ) {
        if (sbn == null) return
        mainScope.launch { handleNotificationRemoved(sbn) }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        if (sbn == null) return
        mainScope.launch { handleNotificationRemoved(sbn) }
    }

    override fun onNotificationRankingUpdate(rankingMap: NotificationListenerService.RankingMap?) = Unit
    override fun onNotificationsInitialized() = Unit

    private fun handleNotificationPosted(sbn: StatusBarNotification) {
        if (!isEnabled) return
        val notification = sbn.notification ?: return

        val hasValidProgress = hasProgress(notification)
        val currentKey = trackedNotificationKey

        if (!hasValidProgress) {
            if (currentKey != null && currentKey == sbn.key) clearProgressTracking()
            return
        }

        if (!isTrackingProgress) {
            trackProgress(sbn)
        } else if (sbn.key == currentKey) {
            updateProgressIfNeeded(sbn)
        }
    }

    private fun handleNotificationRemoved(sbn: StatusBarNotification) {
        if (!isTrackingProgress) return

        if (sbn.key == trackedNotificationKey) {
            clearProgressTracking()
            return
        }

        if (sbn.packageName == trackedPackageName) {
            val current = trackedNotificationKey?.let { findNotificationByKey(it) }
            if (current == null || !hasProgress(current.notification)) {
                clearProgressTracking()
            }
        }
    }

    override fun onHeadsUpPinnedModeChanged(inPinnedMode: Boolean) {
        headsUpPinned = inPinnedMode
        updateProgressState()
        requestUiUpdate()
    }

    override fun onKeyguardShowingChanged() {
        setForceHidden(keyguardStateController.isShowing)
    }

    fun setForceHidden(forceHidden: Boolean) {
        if (isForceHidden == forceHidden) return
        Log.d(TAG, "setForceHidden $forceHidden")
        isForceHidden = forceHidden
        updateProgressState()
        requestUiUpdate()
    }

    private fun updateSettings() {
        val wasChipColorMode = chipColorMode

        isEnabled = Settings.System.getIntForUser(
            contentResolver,
            ONGOING_ACTION_CHIP_ENABLED,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        showMediaProgress = Settings.System.getIntForUser(
            contentResolver,
            ONGOING_MEDIA_PROGRESS,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        isCompactModeEnabled = Settings.System.getIntForUser(
            contentResolver,
            ONGOING_COMPACT_MODE_ENABLED,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        chipColorMode = Settings.System.getIntForUser(
            contentResolver,
            ONGOING_CHIP_COLOR_MODE,
            CHIP_COLOR_MODE_DEFAULT,
            UserHandle.USER_CURRENT
        )

        if (!isCompactModeEnabled) {
            isExpanded = false
        }

        if (wasChipColorMode != chipColorMode) {
            invalidateChipBgColor()
            if (chipColorMode == CHIP_COLOR_MODE_ALBUM_ART && currentAlbumArt != null) {
                currentAlbumArt?.let { extractAndApplyChipBgColorFromAlbumArt(it) }
            } else if (chipColorMode == CHIP_COLOR_MODE_ICON && currentIcon != null) {
                currentIcon?.let { extractAndApplyChipBgColorFromIcon(it) }
            }
        }

        requestUiUpdate()
    }

    fun destroy() {
        isViewAttached = false

        settingsObserver.unregister()
        keyguardStateController.removeCallback(this)
        headsUpManager.removeListener(this)
        mediaSessionHelper.removeMediaMetadataListener(mediaMetadataListener)
        notificationListener.removeNotificationHandler(this)

        uiUpdateJob?.cancel()
        mediaProgressJob?.cancel()
        finishedProgressTimeoutJob?.cancel()
        compactCollapseJob?.cancel()
        menuCollapseJob?.cancel()
        pausedStaleJob?.cancel()

        iconCache.clear()
        inFlightIconLoads.values.forEach { it.cancel() }
        inFlightIconLoads.clear()

        currentIcon = null
        currentTrackTitle = null
        currentArtistName = null
        currentAlbumArt = null
        mainScope.cancel()
    }

    companion object {
        private const val TAG = "OngoingActionProgressController"

        private const val ONGOING_ACTION_CHIP_ENABLED = Settings.System.ONGOING_ACTION_CHIP
        private const val ONGOING_MEDIA_PROGRESS = Settings.System.ONGOING_MEDIA_PROGRESS
        private const val ONGOING_COMPACT_MODE_ENABLED = Settings.System.ONGOING_COMPACT_MODE
        private const val ONGOING_CHIP_COLOR_MODE = Settings.System.ONGOING_CHIP_COLOR_MODE

        private const val MEDIA_UPDATE_INTERVAL_MS = 1000L
        private const val DEBOUNCE_DELAY_MS = 150L
        private const val PROGRESS_TIMEOUT_MS = 30000L
        private const val COMPACT_COLLAPSE_TIMEOUT_MS = 10000L
        private const val MENU_COLLAPSE_TIMEOUT_MS = 5000L
        private const val PAUSED_STALE_GRACE_MS = 20000L

        const val CHIP_COLOR_MODE_DEFAULT = 0
        const val CHIP_COLOR_MODE_ICON = 1
        const val CHIP_COLOR_MODE_ALBUM_ART = 2

        private val HAPTIC_CLICK =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        private val HAPTIC_DOUBLE =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        private val HAPTIC_LONG =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }
}

@Immutable
data class ProgressState(
    val isVisible: Boolean = false,
    val progress: Int = 0,
    val maxProgress: Int = 0,
    val icon: Drawable? = null,
    val albumArt: Bitmap? = null,
    val packageName: String? = null,
    val isCompactMode: Boolean = false,
    val showMediaControls: Boolean = false,
    val isMediaPlaying: Boolean = false,
    val trackTitle: String? = null,
    val artistName: String? = null,
    val chipBgColor: Int? = null,
)
