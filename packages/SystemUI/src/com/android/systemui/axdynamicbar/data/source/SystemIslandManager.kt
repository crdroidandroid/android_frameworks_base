package com.android.systemui.axdynamicbar.data.source

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.PersistableBundle
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.util.Size
import androidx.core.content.FileProvider
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@SysUISingleton
class SystemIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main private val mainHandler: Handler,
    private val batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    private val userTracker: UserTracker,
    private val userFileManager: UserFileManager,
) {
    companion object {
        private const val TAG = "SystemIslandManager"
        private const val MAX_CLIPBOARD_HISTORY = 10
        private const val PREFS_NAME = "ax_dynamic_bar_prefs"
        private const val KEY_CLIPBOARD_STASH = "clipboard_stash"

        private const val CLIPBOARD_CACHE_DIR = "clipboard_cache"
        private const val CLIPBOARD_USER_DIR_PREFIX = "user_"
        private const val ACTIVE_CLIPBOARD_DIR = "active"
        private const val FILE_PROVIDER_AUTHORITY = "com.android.systemui.fileprovider"

        private const val EXTRA_DYNAMIC_BAR_SELF_COPY =
            "com.android.systemui.axdynamicbar.SELF_COPY"
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        private const val MAX_CLIPBOARD_IMAGE_WIDTH_PX = 512
        private const val MAX_CLIPBOARD_IMAGE_HEIGHT_PX = 2048
        private const val CLIPBOARD_IMAGE_TIMEOUT_MS = 2_000L

        private const val MAX_STASH_TEXT_CHARS = 8_192

        private const val CLIPBOARD_HISTORY_TTL_MS = 60L * 60L * 1000L
    }

    private val _chargingEvent = MutableStateFlow<IslandEvent.Charging?>(null)
    val chargingEvent: StateFlow<IslandEvent.Charging?> = _chargingEvent.asStateFlow()

    private val _ringerEvent = MutableStateFlow<IslandEvent.RingerMode?>(null)
    val ringerEvent: StateFlow<IslandEvent.RingerMode?> = _ringerEvent.asStateFlow()

    private val _clipboardEvent = MutableStateFlow<IslandEvent.Clipboard?>(null)
    val clipboardEvent: StateFlow<IslandEvent.Clipboard?> = _clipboardEvent.asStateFlow()

    var onChargingStarted: ((IslandEvent.Charging) -> Unit)? = null

    var onRingerChanged: ((IslandEvent.RingerMode) -> Unit)? = null

    var onClipboardCopied: ((IslandEvent.Clipboard) -> Unit)? = null

    private data class ClipboardUserState(
        val userId: Int,
        val userContext: Context,
        val manager: ClipboardManager,
    )

    private data class ActiveClipboardLease(
        val uri: Uri,
        val file: File,
    )

    @Volatile
    private var clipboardUserState =
        createClipboardUserState(userTracker.userId, userTracker.userContext)
    private var clipboardUserCallbackRegistered = false

    private var wasCharging = false
    @Volatile var chargingDismissed = false
    private var lastRingerMode = -1
    private var batteryJob: Job? = null

    private var listening = false
    @Volatile private var lastClipboardToken: String? = null

    private val clipboardHistory = mutableListOf<IslandEvent.ClipboardItem>()
    private var clipboardGeneration = 0L
    private var historyLoadedForUser = UserHandle.USER_NULL
    private var persistJob: Job? = null

    private val lastItemId = AtomicLong(0L)

    private val clipboardUserCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                switchClipboardUser(newUser, userContext)
            }
        }

    private val ringerReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                if (mode < 0) return
                if (lastRingerMode != -1 && lastRingerMode != mode) {
                    val label =
                        when (mode) {
                            AudioManager.RINGER_MODE_SILENT -> context.getString(R.string.ax_dynamic_bar_silent)
                            AudioManager.RINGER_MODE_VIBRATE -> context.getString(R.string.ax_dynamic_bar_vibrate)
                            AudioManager.RINGER_MODE_NORMAL -> context.getString(R.string.ax_dynamic_bar_ring)
                            else -> return
                        }
                    val event = IslandEvent.RingerMode(mode = mode, label = label)
                    _ringerEvent.value = event
                    onRingerChanged?.invoke(event)
                }
                lastRingerMode = mode
            }
        }

    private val clipboardListener =
        ClipboardManager.OnPrimaryClipChangedListener { onPrimaryClipChanged(clipboardUserState) }


    private fun onPrimaryClipChanged(state: ClipboardUserState) {
        val clipboardManager = state.manager

        if (!clipboardManager.hasPrimaryClip()) {
            cleanupActiveClipboardLeases(state.userId)
            lastClipboardToken = null
            invalidatePendingClipboardWork(state)
            _clipboardEvent.value = null
            return
        }

        val clipSource =
            try {
                clipboardManager.primaryClipSource
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to resolve clipboard source", e)
                null
            }

        val clip =
            try {
                clipboardManager.primaryClip
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to read clipboard", e)
                null
            } ?: return

        if (clip.itemCount <= 0) return
        val item = clip.getItemAt(0)
        val desc = clip.description
        val isImage = desc.hasMimeType("image/*") && item.uri != null

        if (
            clipSource == context.packageName &&
                desc.extras?.getBoolean(EXTRA_DYNAMIC_BAR_SELF_COPY, false) == true
        ) {
            if (!isImage) cleanupActiveClipboardLeases(state.userId)
            invalidatePendingClipboardWork(state)
            return
        }

        cleanupActiveClipboardLeases(state.userId)

        if (desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
            invalidatePendingClipboardWork(state)
            _clipboardEvent.value = null
            return
        }

        val preview = item.text?.toString()?.trim().orEmpty()
        if (preview.isEmpty() && !isImage) {
            invalidatePendingClipboardWork(state)
            _clipboardEvent.value = null
            return
        }

        val sourceUri = if (isImage) item.uri else null
        val label = desc.label?.toString().orEmpty()
        val isUrl = !isImage && looksLikeUrl(preview)

        val clipTimestamp = desc.timestamp
        val token =
            if (clipTimestamp > 0L) {
                buildString {
                    append(clipTimestamp)
                    append('\u0000')
                    append(preview)
                    append('\u0000')
                    append(label)
                    append('\u0000')
                    append(sourceUri?.toString().orEmpty())
                }
            } else {
                null
            }
        if (token != null && token == lastClipboardToken) return
        lastClipboardToken = token

        val generation = nextClipboardGeneration()
        val itemId = newClipboardItemId()

        if (isImage && sourceUri != null) {
            applicationScope.launch(backgroundDispatcher) {
                val cachedUri = cacheClipboardImage(state, sourceUri, itemId)
                val committed =
                    commitClipboardEvent(
                        state = state,
                        itemId = itemId,
                        preview = preview,
                        label = label,
                        isUrl = false,
                        isImage = true,
                        imageUri = cachedUri,
                        generation = generation,
                        stash = cachedUri != null,
                        callbackOnMainThread = true,
                    )
                if (!committed && cachedUri != null) cleanupCachedImage(state.userId, itemId)
            }
        } else {
            commitClipboardEvent(
                state = state,
                itemId = itemId,
                preview = preview,
                label = label,
                isUrl = isUrl,
                isImage = false,
                imageUri = null,
                generation = generation,
                stash = preview.length <= MAX_STASH_TEXT_CHARS,
                callbackOnMainThread = false,
            )
        }
    }

    private fun looksLikeUrl(text: String): Boolean =
        text.none { it.isWhitespace() } &&
            (text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) ||
                text.startsWith("www.", ignoreCase = true))

    private fun newClipboardItemId(): Long {
        while (true) {
            val prev = lastItemId.get()
            val next = maxOf(System.currentTimeMillis(), prev + 1)
            if (lastItemId.compareAndSet(prev, next)) return next
        }
    }

    private fun cacheClipboardImage(
        state: ClipboardUserState,
        sourceUri: Uri,
        itemId: Long,
    ): Uri? {
        val signal = CancellationSignal()
        val timeout = Runnable { signal.cancel() }
        mainHandler.postDelayed(timeout, CLIPBOARD_IMAGE_TIMEOUT_MS)
        val file = File(clipboardCacheDir(state.userId), "clip_$itemId.webp")
        return try {
            val bitmap =
                state.userContext.contentResolver.loadThumbnail(
                    sourceUri,
                    Size(MAX_CLIPBOARD_IMAGE_WIDTH_PX, MAX_CLIPBOARD_IMAGE_HEIGHT_PX),
                    signal,
                )
            try {
                val ok =
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                    }
                if (!ok) throw IOException("Bitmap compression failed")
            } finally {
                bitmap.recycle()
            }
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        } catch (e: Exception) {
            file.delete()
            Log.w(TAG, "Failed to cache clipboard image", e)
            null
        } finally {
            mainHandler.removeCallbacks(timeout)
        }
    }

    private fun commitClipboardEvent(
        state: ClipboardUserState,
        itemId: Long,
        preview: String,
        label: String,
        isUrl: Boolean,
        isImage: Boolean,
        imageUri: Uri?,
        generation: Long,
        stash: Boolean,
        callbackOnMainThread: Boolean,
    ): Boolean {
        val event: IslandEvent.Clipboard
        synchronized(clipboardHistory) {
            if (state.userId != clipboardUserState.userId || generation != clipboardGeneration) {
                return false
            }

            if (stash) {
                pruneExpiredLocked(state.userId)
                // Text-only de-duplication. Never touch image entries here.
                if (!isImage) clipboardHistory.removeAll { !it.isImage && it.preview == preview }
                clipboardHistory.add(
                    0,
                    IslandEvent.ClipboardItem(
                        id = itemId,
                        preview = preview,
                        label = label,
                        isUrl = isUrl,
                        isImage = isImage,
                        imageUri = imageUri,
                        timestamp = itemId,
                    ),
                )
                while (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) {
                    val removed = clipboardHistory.removeAt(clipboardHistory.lastIndex)
                    if (removed.isImage) cleanupCachedImage(state.userId, removed.id)
                }
            }

            event =
                IslandEvent.Clipboard(
                    label = label,
                    preview = preview,
                    isUrl = isUrl,
                    isImage = isImage,
                    imageUri = imageUri,
                    items = clipboardHistory.toList(),
                )
            // Publish under the same lock that generation invalidation uses.
            _clipboardEvent.value = event
        }

        if (stash) schedulePersist(state)

        // Callbacks run outside the lock so the interactor can never re-enter while it is held.
        if (callbackOnMainThread) {
            mainHandler.post {
                val stillCurrent =
                    synchronized(clipboardHistory) {
                        state.userId == clipboardUserState.userId &&
                            generation == clipboardGeneration
                    }
                if (stillCurrent) onClipboardCopied?.invoke(event)
            }
        } else {
            onClipboardCopied?.invoke(event)
        }
        return true
    }

    private fun pruneExpiredLocked(userId: Int): Boolean {
        val cutoff = System.currentTimeMillis() - CLIPBOARD_HISTORY_TTL_MS
        var changed = false
        val iterator = clipboardHistory.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.timestamp < cutoff) {
                iterator.remove()
                if (item.isImage) cleanupCachedImage(userId, item.id)
                changed = true
            }
        }
        return changed
    }

    private fun nextClipboardGeneration(): Long =
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
            clipboardGeneration
        }

    private fun invalidatePendingClipboardWork(state: ClipboardUserState) {
        synchronized(clipboardHistory) {
            if (state.userId != clipboardUserState.userId) return
            clipboardGeneration += 1L
        }
    }


    private fun createClipboardUserState(userId: Int, userContext: Context): ClipboardUserState =
        ClipboardUserState(
            userId = userId,
            userContext = userContext,
            manager = requireNotNull(userContext.getSystemService(ClipboardManager::class.java)),
        )

    private fun switchClipboardUser(userId: Int, userContext: Context) {
        val oldState = clipboardUserState
        if (oldState.userId == userId) return

        val wasListening = clipboardListening
        if (wasListening) {
            try {
                oldState.manager.removePrimaryClipChangedListener(clipboardListener)
            } catch (_: Exception) {}
        }

        val newState = createClipboardUserState(userId, userContext)
        synchronized(clipboardHistory) {
            persistJob?.cancel()
            persistJob = null
            // Flush the outgoing user's stash synchronously (≤10 entries; apply() is async on
            // disk). Only if it was actually loaded — otherwise we would overwrite the stored
            // stash with an empty list.
            if (historyLoadedForUser == oldState.userId) {
                writeClipboardHistoryLocked(oldState.userId)
            }
            clipboardGeneration += 1L
            clipboardHistory.clear()
            historyLoadedForUser = UserHandle.USER_NULL
            lastClipboardToken = null
            _clipboardEvent.value = null
            clipboardUserState = newState
        }

        loadClipboardHistory(newState)

        if (wasListening) {
            newState.manager.addPrimaryClipChangedListener(clipboardListener)
        }
    }

    private fun ensureClipboardUserBinding() {
        val userId = userTracker.userId
        if (clipboardUserState.userId != userId) {
            switchClipboardUser(userId, userTracker.userContext)
        }
    }


    private fun clipboardPrefs(userId: Int): SharedPreferences =
        userFileManager.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE, userId)

    /** User 0 keeps the legacy location so existing cached images stay valid. */
    private fun clipboardCacheDir(userId: Int): File {
        val base = File(context.cacheDir, CLIPBOARD_CACHE_DIR)
        val dir =
            if (userId == UserHandle.USER_SYSTEM) base
            else File(base, "$CLIPBOARD_USER_DIR_PREFIX$userId")
        return dir.apply { mkdirs() }
    }

    private fun activeClipboardDir(userId: Int): File =
        File(clipboardCacheDir(userId), ACTIVE_CLIPBOARD_DIR).apply { mkdirs() }

    private fun cachedImageUri(userId: Int, itemId: Long): Uri? {
        val dir = clipboardCacheDir(userId)
        val file =
            listOf(File(dir, "clip_$itemId.webp"), File(dir, "clip_$itemId.png"))
                .firstOrNull { it.exists() } ?: return null
        return try {
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Clipboard cache is not exposed by the FileProvider", e)
            null
        }
    }

    private fun cleanupCachedImage(userId: Int, itemId: Long) {
        try {
            val dir = clipboardCacheDir(userId)
            File(dir, "clip_$itemId.webp").delete()
            File(dir, "clip_$itemId.png").delete()
        } catch (_: Exception) {}
    }

    private fun cleanupHistoryCache(userId: Int) {
        try {
            clipboardCacheDir(userId).listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("clip_")) file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun cleanupActiveClipboardLeases(userId: Int, keep: File? = null) {
        try {
            activeClipboardDir(userId).listFiles()?.forEach { file ->
                if (file != keep) file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun createActiveClipboardLease(userId: Int, sourceUri: Uri): ActiveClipboardLease? {
        var file: File? = null
        return try {
            val extension =
                sourceUri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                    ?: "bin"
            val target =
                File(activeClipboardDir(userId), "active_${newClipboardItemId()}.$extension")
            file = target
            val input = context.contentResolver.openInputStream(sourceUri) ?: return null
            input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            ActiveClipboardLease(
                uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, target),
                file = target,
            )
        } catch (e: Exception) {
            file?.delete()
            Log.w(TAG, "Failed to create active clipboard image lease", e)
            null
        }
    }

    private fun uriForClipboardOfUser(uri: Uri, clipboardUserId: Int): Uri {
        val ownUserId = Process.myUserHandle().identifier
        return if (clipboardUserId == ownUserId) uri
        else ContentProvider.maybeAddUserId(uri, ownUserId)
    }

    private var chargingListening = false
    private var ringerListening = false
    private var clipboardListening = false

    fun startCharging() {
        if (chargingListening) return
        chargingListening = true
        wasCharging = batteryController.isPluggedIn
        batteryJob?.cancel()
        batteryJob =
            applicationScope.launch(backgroundDispatcher) {
                combine(
                        batteryInteractor.isCharging,
                        batteryInteractor.level,
                        batteryInteractor.powerSave,
                        batteryInteractor.batteryTimeRemainingEstimate,
                    ) { isCharging: Boolean, level: Int?, isPowerSave: Boolean, timeEst: String? ->
                        ChargingSnapshot(isCharging, level, isPowerSave, timeEst)
                    }
                    .distinctUntilChanged()
                    .collect { snap ->
                        val wasChargingBefore = wasCharging
                        wasCharging = snap.isCharging
                        if (snap.isCharging && !wasChargingBefore && snap.level != null) {
                            chargingDismissed = false
                            val event =
                                IslandEvent.Charging(
                                    level = snap.level,
                                    isWireless = batteryController.isWirelessCharging,
                                    isPowerSave = snap.isPowerSave,
                                    timeRemaining = snap.timeEst,
                                )
                            _chargingEvent.value = event
                            onChargingStarted?.invoke(event)
                        } else if (snap.isCharging && wasChargingBefore && !chargingDismissed) {
                            _chargingEvent.value =
                                _chargingEvent.value?.copy(
                                    level = snap.level ?: _chargingEvent.value?.level ?: 0,
                                    isPowerSave = snap.isPowerSave,
                                    timeRemaining = snap.timeEst,
                                )
                        } else if (!snap.isCharging && wasChargingBefore) {
                            chargingDismissed = false
                            _chargingEvent.value = null
                        }
                    }
            }
    }

    fun stopCharging() {
        if (!chargingListening) return
        chargingListening = false
        batteryJob?.cancel()
        batteryJob = null
        _chargingEvent.value = null
    }

    fun startRinger() {
        if (ringerListening) return
        ringerListening = true
        lastRingerMode =
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode
        context.registerReceiver(
            ringerReceiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            null,
            mainHandler,
        )
    }

    fun stopRinger() {
        if (!ringerListening) return
        ringerListening = false
        try { context.unregisterReceiver(ringerReceiver) } catch (_: Exception) {}
        _ringerEvent.value = null
    }

    fun startClipboard() {
        if (clipboardListening) return
        if (!clipboardUserCallbackRegistered) {
            userTracker.addCallback(clipboardUserCallback, context.mainExecutor)
            clipboardUserCallbackRegistered = true
        }
        // Rebind before flagging as listening so the switch doesn't attach the listener itself.
        ensureClipboardUserBinding()
        clipboardListening = true
        val state = clipboardUserState
        loadClipboardHistory(state)
        state.manager.addPrimaryClipChangedListener(clipboardListener)
    }

    fun stopClipboard() {
        if (!clipboardListening) return
        clipboardListening = false
        val state = clipboardUserState
        try {
            state.manager.removePrimaryClipChangedListener(clipboardListener)
        } catch (_: Exception) {}
        if (clipboardUserCallbackRegistered) {
            userTracker.removeCallback(clipboardUserCallback)
            clipboardUserCallbackRegistered = false
        }
        lastClipboardToken = null
        invalidatePendingClipboardWork(state)
        _clipboardEvent.value = null
    }

    fun startListening() {
        if (listening) return
        listening = true
        startCharging()
        startRinger()
        startClipboard()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        stopCharging()
        stopRinger()
        stopClipboard()
    }

    fun clearRinger() {
        _ringerEvent.value = null
    }

    fun setRingerMode(mode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = mode
    }

    fun getRingerMode(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.ringerMode
    }

    fun emitRingerEvent(mode: Int) {
        val label =
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> context.getString(R.string.ax_dynamic_bar_silent)
                AudioManager.RINGER_MODE_VIBRATE -> context.getString(R.string.ax_dynamic_bar_vibrate)
                else -> context.getString(R.string.ax_dynamic_bar_ring)
            }
        val event = IslandEvent.RingerMode(mode = mode, label = label)
        _ringerEvent.value = event
        onRingerChanged?.invoke(event)
    }

    fun dismissClipboardEvent() {
        synchronized(clipboardHistory) { _clipboardEvent.value = null }
    }

    /** Wipes the whole stash for the current user: memory, cached images and persisted copy. */
    fun clearClipboard() {
        val state = clipboardUserState
        synchronized(clipboardHistory) {
            persistJob?.cancel()
            persistJob = null
            clipboardGeneration += 1L
            _clipboardEvent.value = null
            clipboardHistory.clear()
            cleanupHistoryCache(state.userId)
            clipboardPrefs(state.userId).edit().remove(KEY_CLIPBOARD_STASH).apply()
            historyLoadedForUser = state.userId
        }
    }

    fun clearCharging() {
        chargingDismissed = true
        _chargingEvent.value = null
    }

    fun removeClipboardItem(id: Long) {
        val state = clipboardUserState
        synchronized(clipboardHistory) {
            val removed = clipboardHistory.firstOrNull { it.id == id } ?: return
            clipboardHistory.remove(removed)
            if (removed.isImage) cleanupCachedImage(state.userId, removed.id)

            val current = _clipboardEvent.value
            _clipboardEvent.value =
                when {
                    current == null || clipboardHistory.isEmpty() -> null
                    removed.represents(current) -> {
                        val latest = clipboardHistory.first()
                        current.copy(
                            label = latest.label,
                            preview = latest.preview,
                            isUrl = latest.isUrl,
                            isImage = latest.isImage,
                            imageUri = latest.imageUri,
                            items = clipboardHistory.toList(),
                        )
                    }
                    else -> current.copy(items = clipboardHistory.toList())
                }
        }
        schedulePersist(state)
    }

    private fun IslandEvent.ClipboardItem.represents(event: IslandEvent.Clipboard): Boolean =
        isImage == event.isImage &&
            if (isImage) imageUri != null && imageUri == event.imageUri
            else preview == event.preview

    fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        ensureClipboardUserBinding()
        val state = clipboardUserState
        try {
            state.manager.setPrimaryClip(
                markDynamicBarSelfCopy(ClipData.newPlainText("Copied", text))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy text to clipboard", e)
        }
    }

    fun copyUriToClipboard(uri: Uri) {
        ensureClipboardUserBinding()
        val state = clipboardUserState
        applicationScope.launch(backgroundDispatcher) {
            val lease = createActiveClipboardLease(state.userId, uri)
            if (lease == null) {
                Log.w(TAG, "Unable to lease clipboard image; leaving system clipboard unchanged")
                return@launch
            }
            if (state.userId != clipboardUserState.userId) {
                lease.file.delete()
                return@launch
            }
            val mimeType =
                try {
                    context.contentResolver.getType(lease.uri)
                } catch (_: Exception) {
                    null
                } ?: "image/*"
            try {
                state.manager.setPrimaryClip(
                    markDynamicBarSelfCopy(
                        ClipData(
                            "Copied",
                            arrayOf(mimeType),
                            ClipData.Item(uriForClipboardOfUser(lease.uri, state.userId)),
                        )
                    )
                )
                cleanupActiveClipboardLeases(state.userId, keep = lease.file)
            } catch (e: Exception) {
                lease.file.delete()
                Log.w(TAG, "Failed to copy leased image to clipboard", e)
            }
        }
    }

    private fun markDynamicBarSelfCopy(clip: ClipData): ClipData {
        val extras = clip.description.extras ?: PersistableBundle()
        extras.putBoolean(EXTRA_DYNAMIC_BAR_SELF_COPY, true)
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    fun openUrl(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        val uri =
            Uri.parse(if (target.startsWith("www.", ignoreCase = true)) "https://$target" else target)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "Refusing to open non-web URL from clipboard")
            return
        }
        try {
            context.startActivityAsUser(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                userTracker.userHandle,
            )
            dismissClipboardEvent()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open URL", e)
        }
    }

    private fun schedulePersist(state: ClipboardUserState) {
        synchronized(clipboardHistory) {
            persistJob?.cancel()
            persistJob =
                applicationScope.launch(backgroundDispatcher) {
                    synchronized(clipboardHistory) {
                        if (
                            state.userId != clipboardUserState.userId ||
                                historyLoadedForUser != state.userId
                        ) {
                            return@launch
                        }
                        writeClipboardHistoryLocked(state.userId)
                    }
                }
        }
    }

    private fun writeClipboardHistoryLocked(userId: Int) {
        try {
            val arr = JSONArray()
            clipboardHistory.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("preview", item.preview)
                        put("label", item.label)
                        put("isUrl", item.isUrl)
                        put("isImage", item.isImage)
                        put("ts", item.timestamp)
                    }
                )
            }
            clipboardPrefs(userId).edit().putString(KEY_CLIPBOARD_STASH, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist clipboard history for user $userId", e)
        }
    }

    private fun loadClipboardHistory(state: ClipboardUserState) {
        var needsPersist = false
        synchronized(clipboardHistory) {
            if (state.userId != clipboardUserState.userId) return
            if (historyLoadedForUser == state.userId) return
            clipboardHistory.clear()
            try {
                val json = clipboardPrefs(state.userId).getString(KEY_CLIPBOARD_STASH, null)
                if (json != null) {
                    val cutoff = System.currentTimeMillis() - CLIPBOARD_HISTORY_TTL_MS
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        if (clipboardHistory.size >= MAX_CLIPBOARD_HISTORY) {
                            needsPersist = true
                            break
                        }
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optLong("id", 0L)
                        val ts = obj.optLong("ts", id)
                        val isImage = obj.optBoolean("isImage", false)

                        if (id <= 0L || ts < cutoff) {
                            if (isImage && id > 0L) cleanupCachedImage(state.userId, id)
                            needsPersist = true
                            continue
                        }

                        val imageUri = if (isImage) cachedImageUri(state.userId, id) else null
                        if (isImage && imageUri == null) {
                            needsPersist = true
                            continue
                        }

                        clipboardHistory.add(
                            IslandEvent.ClipboardItem(
                                id = id,
                                preview = obj.optString("preview", ""),
                                label = obj.optString("label", ""),
                                isUrl = obj.optBoolean("isUrl", false),
                                isImage = isImage,
                                imageUri = imageUri,
                                timestamp = ts,
                            )
                        )
                        lastItemId.accumulateAndGet(id) { a, b -> maxOf(a, b) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load clipboard history for user ${state.userId}", e)
            }
            historyLoadedForUser = state.userId
        }
        if (needsPersist) schedulePersist(state)
    }

    private data class ChargingSnapshot(
        val isCharging: Boolean,
        val level: Int?,
        val isPowerSave: Boolean,
        val timeEst: String?,
    )
}
