/*
 * SPDX-FileCopyrightText: VoltageOS
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar

import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
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
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
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
import kotlinx.coroutines.async
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
    private var lastProgressUpdateTime = 0L
    private var isEnabled = false
    private var isCompactModeEnabled = false

    private var currentProgress = 0
    private var currentProgressMax = 0
    private var currentIcon: Drawable? = null

    private var isMenuVisible = false
    private var isSystemChipVisible = false

    private var trackedNotificationKey: String? = null
    private var trackedPackageName: String? = null

    private var needsFullUiUpdate = true
    private var isViewAttached = false
    private var isExpanded = false

    private var lastUpdateTime = 0L
    private var uiUpdateJob: Job? = null

    private var mediaProgressJob: Job? = null
    private var staleCheckerJob: Job? = null
    private var compactCollapseJob: Job? = null
    private var menuCollapseJob: Job? = null

    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    private val settingsObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri == null) return
                val enabledUri = Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED)
                val mediaUri = Settings.System.getUriFor(ONGOING_MEDIA_PROGRESS)
                val compactUri = Settings.System.getUriFor(ONGOING_COMPACT_MODE_ENABLED)

                if (uri == enabledUri || uri == mediaUri || uri == compactUri) {
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
                updateSettings()
            }

            fun unregister() {
                contentResolver.unregisterContentObserver(this)
            }
        }

    private val mediaMetadataListener = object : MediaSessionManagerHelper.MediaMetadataListener {
        override fun onMediaMetadataChanged() {
            needsFullUiUpdate = true
            requestUiUpdate()
        }

        override fun onPlaybackStateChanged() {
            needsFullUiUpdate = true
            requestUiUpdate()
        }
    }

    init {
        requireNotNull(notificationListener) { "notificationListener cannot be null" }

        keyguardStateController.addCallback(this)
        headsUpManager.addListener(this)
        notificationListener.addNotificationHandler(this)

        settingsObserver.register()
        mediaSessionHelper.addMediaMetadataListener(mediaMetadataListener)

        isViewAttached = true
        updateSettings()

        staleCheckerJob = mainScope.launch {
            while (isActive && isViewAttached) {
                delay(STALE_PROGRESS_CHECK_INTERVAL_MS)
                checkForStaleProgress()
            }
        }
    }

    private fun publish(state: ProgressState) {
        _state.value = state
    }

    fun expandCompactView() {
        isExpanded = true
        compactCollapseJob?.cancel()
        compactCollapseJob = mainScope.launch {
            delay(5000L)
            if (isCompactModeEnabled && isExpanded) {
                isExpanded = false
                requestUiUpdate()
            }
        }
        updateProgressState()
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

    private fun updateProgressState() {
        var isVisible = !isForceHidden && !headsUpPinned && !isSystemChipVisible
        val isMediaPlaying = showMediaProgress && mediaSessionHelper.isMediaPlaying()
        val hasNotificationProgress = isEnabled && isTrackingProgress

        isVisible = isVisible && (isMediaPlaying || hasNotificationProgress)

        if (!isVisible) {
            val update = ProgressState(
                isVisible = false,
                progress = 0,
                maxProgress = 0,
                iconBitmap = null,
                packageName = null,
                isCompactMode = false,
                showMediaControls = false
            )
            publish(update)
            return
        }

        val isCompact = isCompactModeEnabled && !isExpanded

        val iconSizePx = if (isCompact) {
            (14f * context.resources.displayMetrics.density).toInt() * 2
        } else {
            (16f * context.resources.displayMetrics.density).toInt() * 2
        }

        val currentIconBitmap = try {
            currentIcon?.let { drawable ->
                drawable.toBitmap(
                    width = iconSizePx,
                    height = iconSizePx,
                    config = Bitmap.Config.ARGB_8888
                ).asImageBitmap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert icon to bitmap", e)
            null
        }

        val update = ProgressState(
            isVisible = true,
            progress = currentProgress,
            maxProgress = currentProgressMax,
            iconBitmap = currentIconBitmap,
            packageName = trackedPackageName,
            isCompactMode = isCompact,
            showMediaControls = isMenuVisible
        )
        publish(update)
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

        val isMediaPlaying = showMediaProgress && mediaSessionHelper.isMediaPlaying()

        if (isCompactModeEnabled && !isExpanded) {
            if (!isEnabled && !isMediaPlaying) {
                stopMediaLoop()
                updateProgressState()
                return
            }
            if (isMediaPlaying) updateMediaProgressCompact() else updateNotificationProgressCompact()
        } else {
            if (isMediaPlaying) {
                if (needsFullUiUpdate) {
                    updateMediaProgressFull()
                    needsFullUiUpdate = false
                } else {
                    updateMediaProgressOnly()
                }
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
                updateMediaProgressOnly()
                delay(MEDIA_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopMediaLoop() {
        mediaProgressJob?.cancel()
        mediaProgressJob = null
    }

    private fun updateMediaProgressOnly() {
        val totalDuration = mediaSessionHelper.getTotalDuration()
        val playbackState = mediaSessionHelper.getMediaControllerPlaybackState()
        val pos = playbackState?.position ?: 0L

        currentProgress = pos.toInt()
        currentProgressMax = totalDuration.toInt().takeIf { it > 0 } ?: 100

        updateProgressState()
    }

    private fun updateMediaProgressFull() {
        ensureMediaLoopRunning()

        val mediaAppIcon = mediaSessionHelper.getMediaAppIcon()
        if (mediaAppIcon != null) {
            currentIcon = mediaAppIcon
            updateMediaProgressOnly()
            return
        }

        val playbackState = mediaSessionHelper.getMediaControllerPlaybackState()
        val pkg = playbackState?.extras?.getString("package")

        if (pkg.isNullOrEmpty()) {
            setDefaultMediaIcon()
            updateMediaProgressOnly()
            return
        }

        loadIcon(pkg) { drawable ->
            if (drawable != null) {
                currentIcon = drawable
            } else {
                setDefaultMediaIcon()
            }
            updateProgressState()
        }

        updateMediaProgressOnly()
    }

    private fun updateMediaProgressCompact() {
        ensureMediaLoopRunning()

        val totalDuration = mediaSessionHelper.getTotalDuration()
        val playbackState = mediaSessionHelper.getMediaControllerPlaybackState()
        val pos = playbackState?.position ?: 0L

        currentProgress = pos.toInt()
        currentProgressMax = totalDuration.toInt().takeIf { it > 0 } ?: 100

        val mediaAppIcon = mediaSessionHelper.getMediaAppIcon()
        if (mediaAppIcon != null) {
            currentIcon = mediaAppIcon
            return
        }

        val pkg = playbackState?.extras?.getString("package")
        if (pkg.isNullOrEmpty()) {
            setDefaultMediaIconCompact()
            return
        }

        loadIcon(pkg) { drawable ->
            if (drawable != null) {
                currentIcon = drawable
            } else {
                setDefaultMediaIconCompact()
            }
            updateProgressState()
        }
    }

    private fun setDefaultMediaIcon() {
        currentIcon = context.resources.getDrawable(R.drawable.ic_default_music_icon, context.theme)
    }

    private fun setDefaultMediaIconCompact() {
        currentIcon = context.resources.getDrawable(R.drawable.ic_default_music_icon, context.theme)
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

    private fun updateNotificationProgressCompact() {
        updateNotificationProgress()
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

    private fun trackProgress(sbn: StatusBarNotification) {
        isTrackingProgress = true
        trackedNotificationKey = sbn.key
        trackedPackageName = sbn.packageName
        lastProgressUpdateTime = System.currentTimeMillis()
        extractProgress(sbn.notification)
        requestUiUpdate()
    }

    private fun clearProgressTracking() {
        isTrackingProgress = false
        trackedNotificationKey = null
        trackedPackageName = null
        currentProgress = 0
        currentProgressMax = 0
        lastProgressUpdateTime = 0L
        requestUiUpdate()
    }

    private fun checkForStaleProgress() {
        if (!isTrackingProgress) return
        val key = trackedNotificationKey ?: return

        val sbn = findNotificationByKey(key)
        if (sbn == null || !hasProgress(sbn.notification)) {
            clearProgressTracking()
            return
        }

        if (lastProgressUpdateTime == 0L) {
            lastProgressUpdateTime = System.currentTimeMillis()
            return
        }

        val timedOut = System.currentTimeMillis() - lastProgressUpdateTime > PROGRESS_TIMEOUT_MS
        val finished = currentProgressMax > 0 && currentProgress >= currentProgressMax

        if (timedOut && finished) {
            clearProgressTracking()
        }
    }

    private fun updateProgressIfNeeded(sbn: StatusBarNotification) {
        if (!isTrackingProgress) return
        if (sbn.key != trackedNotificationKey) return

        if (!hasProgress(sbn.notification)) {
            clearProgressTracking()
            return
        }

        lastProgressUpdateTime = System.currentTimeMillis()
        extractProgress(sbn.notification)
        requestUiUpdate()
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

    fun onInteraction() {
        if (showMediaProgress && mediaSessionHelper.isMediaPlaying()) {
            isMenuVisible = !isMenuVisible
            updateProgressState()
            if (isMenuVisible) {
                menuCollapseJob?.cancel()
                menuCollapseJob = mainScope.launch {
                    delay(5000L)
                    isMenuVisible = false
                    updateProgressState()
                }
            }
        } else {
            openTrackedApp()
        }
        vibrator.vibrate(VIBRATION_EFFECT)
    }

    fun onLongPress() {
        if (showMediaProgress && mediaSessionHelper.isMediaPlaying()) openMediaApp() else openTrackedApp()
        vibrator.vibrate(VIBRATION_EFFECT)
    }

    fun onDoubleTap() {
        if (showMediaProgress && mediaSessionHelper.isMediaPlaying()) {
            toggleMediaPlaybackState()
            vibrator.vibrate(VIBRATION_EFFECT)
        }
    }

    fun onSwipe(isNext: Boolean) {
        if (isNext) skipToNextTrack() else skipToPreviousTrack()
    }

    fun onMediaAction(action: Int) {
        when (action) {
            0 -> skipToPreviousTrack()
            1 -> toggleMediaPlaybackState()
            2 -> skipToNextTrack()
        }

        menuCollapseJob?.cancel()
        menuCollapseJob = mainScope.launch {
            delay(5000L)
            isMenuVisible = false
            updateProgressState()
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
    }

    private fun skipToPreviousTrack() {
        mediaSessionHelper.prevSong()
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
        val wasEnabled = isEnabled
        val wasShowingMedia = showMediaProgress
        val wasCompactMode = isCompactModeEnabled

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

        if (wasEnabled != isEnabled || wasShowingMedia != showMediaProgress || wasCompactMode != isCompactModeEnabled) {
            needsFullUiUpdate = true
            isExpanded = false
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
        staleCheckerJob?.cancel()
        compactCollapseJob?.cancel()
        menuCollapseJob?.cancel()

        iconCache.clear()
        inFlightIconLoads.values.forEach { it.cancel() }
        inFlightIconLoads.clear()

        currentIcon = null
        mainScope.cancel()
    }

    companion object {
        private const val TAG = "OngoingActionProgressController"

        private const val ONGOING_ACTION_CHIP_ENABLED = Settings.System.ONGOING_ACTION_CHIP
        private const val ONGOING_MEDIA_PROGRESS = Settings.System.ONGOING_MEDIA_PROGRESS
        private const val ONGOING_COMPACT_MODE_ENABLED = Settings.System.ONGOING_COMPACT_MODE

        private const val MEDIA_UPDATE_INTERVAL_MS = 1000L
        private const val DEBOUNCE_DELAY_MS = 150L
        private const val STALE_PROGRESS_CHECK_INTERVAL_MS = 5000L
        private const val PROGRESS_TIMEOUT_MS = 30000L

        private val VIBRATION_EFFECT: VibrationEffect =
            VibrationEffect.get(VibrationEffect.EFFECT_CLICK)
    }
}

@Immutable
data class ProgressState(
    val isVisible: Boolean = false,
    val progress: Int = 0,
    val maxProgress: Int = 0,
    val iconBitmap: ImageBitmap? = null,
    val packageName: String? = null,
    val isCompactMode: Boolean = false,
    val showMediaControls: Boolean = false,
)
