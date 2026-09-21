package com.android.systemui.axdynamicbar.data.source

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.PersistableBundle
import android.os.UserHandle
import android.util.Log
import android.util.Size
import androidx.core.content.FileProvider
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.user.utils.UserScopedService
import java.io.File
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val clipboardManagerProvider: UserScopedService<ClipboardManager>,
) {
    companion object {
        private const val TAG = "SystemIslandManager"
        private const val MAX_CLIPBOARD_HISTORY = 10
        private const val PREFS_NAME = "ax_dynamic_bar_prefs"
        private const val KEY_CLIPBOARD_STASH = "clipboard_stash"
        private const val CLIPBOARD_CACHE_DIR = "clipboard_cache"
        private const val ACTIVE_CLIPBOARD_DIR = "active"
        private const val FILE_PROVIDER_AUTHORITY = "com.android.systemui.fileprovider"
        private const val EXTRA_DYNAMIC_BAR_SELF_COPY =
            "com.android.systemui.axdynamicbar.SELF_COPY"
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
        private const val MAX_CLIPBOARD_IMAGE_WIDTH_PX = 512
        private const val MAX_CLIPBOARD_IMAGE_HEIGHT_PX = 2048
        private const val CLIPBOARD_IMAGE_TIMEOUT_MS = 300L
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
        val context: Context,
        val manager: ClipboardManager,
    )

    private data class ActiveClipboardLease(
        val uri: Uri,
        val file: File,
    )

    @Volatile
    private var clipboardUserState =
        createClipboardUserState(userTracker.userHandle, userTracker.userContext)
    private var clipboardUserCallbackRegistered = false

    private var wasCharging = false
    @Volatile var chargingDismissed = false
    private var lastRingerMode = -1
    private var batteryJob: Job? = null

    private var listening = false
    @Volatile private var lastClipboardToken: String? = null
    @Volatile private var clipboardGeneration = 0L

    private val clipboardHistory = mutableListOf<IslandEvent.ClipboardItem>()

    private var persistJob: Job? = null

    private val clipboardUserCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                switchClipboardUser(UserHandle.of(newUser), userContext)
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
        ClipboardManager.OnPrimaryClipChangedListener {
            val state = clipboardUserState
            val clipboardManager = state.manager

            if (!clipboardManager.hasPrimaryClip()) {
                cleanupActiveClipboardLeases(state)
                lastClipboardToken = null
                invalidatePendingClipboardWorkAndPersist(state)
                _clipboardEvent.value = null
                return@OnPrimaryClipChangedListener
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
                } ?: return@OnPrimaryClipChangedListener

            if (clip.itemCount <= 0) return@OnPrimaryClipChangedListener
            val item = clip.getItemAt(0)
            val desc = clip.description

            if (
                clipSource == context.packageName &&
                    desc.extras?.getBoolean(EXTRA_DYNAMIC_BAR_SELF_COPY, false) == true
            ) {
                // Image self-copies must keep the newly leased backing file alive. Text self-copies
                // replace an older image clipboard, so any previous active image lease is stale.
                if (!(desc.hasMimeType("image/*") && item.uri != null)) {
                    cleanupActiveClipboardLeases(state)
                }
                invalidatePendingClipboardWorkAndPersist(state)
                return@OnPrimaryClipChangedListener
            }

            cleanupActiveClipboardLeases(state)

            if (desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
                invalidatePendingClipboardWorkAndPersist(state)
                _clipboardEvent.value = null
                return@OnPrimaryClipChangedListener
            }

            val rawText = item.text?.toString() ?: ""
            val preview = rawText.trim()
            val isUrl =
                preview.startsWith("http://") ||
                    preview.startsWith("https://") ||
                    preview.startsWith("www.")
            val isImage = desc.hasMimeType("image/*") && item.uri != null
            val sourceUri = if (isImage) item.uri else null
            val label = desc.label?.toString() ?: ""

            if (preview.isEmpty() && !isImage && label.isEmpty()) {
                return@OnPrimaryClipChangedListener
            }

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
                        append(sourceUri?.toString() ?: "")
                    }
                } else {
                    null
                }
            if (token != null && token == lastClipboardToken) {
                return@OnPrimaryClipChangedListener
            }
            lastClipboardToken = token

            val generation = nextClipboardGeneration()
            val itemId =
                if (clipTimestamp > 0L) clipTimestamp
                else System.currentTimeMillis()

            if (isImage && sourceUri != null) {
                applicationScope.launch(backgroundDispatcher) {
                    val cachedUri = cacheClipboardImage(state, sourceUri, itemId)
                    if (cachedUri == null) {
                        persistCurrentHistoryForGeneration(state, generation)
                        return@launch
                    }
                    if (
                        !commitClipboardEvent(
                            state,
                            itemId,
                            preview,
                            label,
                            isUrl,
                            true,
                            cachedUri,
                            generation,
                            callbackOnMainThread = true,
                        )
                    ) {
                        cleanupCachedImage(state, itemId)
                    }
                }
            } else {
                commitClipboardEvent(
                    state,
                    itemId,
                    preview,
                    label,
                    isUrl,
                    false,
                    null,
                    generation,
                )
            }
        }

    private suspend fun cacheClipboardImage(
        state: ClipboardUserState,
        sourceUri: Uri,
        itemId: Long,
    ): Uri? =
        withTimeoutOrNull(CLIPBOARD_IMAGE_TIMEOUT_MS) {
            try {
                val bitmap =
                    state.context.contentResolver.loadThumbnail(
                        sourceUri,
                        Size(MAX_CLIPBOARD_IMAGE_WIDTH_PX, MAX_CLIPBOARD_IMAGE_HEIGHT_PX),
                        null,
                    )
                val file = File(clipboardCacheDir(state), "clip_$itemId.webp")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                }
                bitmap.recycle()
                FileProvider.getUriForFile(state.context, FILE_PROVIDER_AUTHORITY, file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache clipboard image", e)
                null
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
        callbackOnMainThread: Boolean = false,
    ): Boolean {
        val clipItem =
            IslandEvent.ClipboardItem(
                id = itemId,
                preview = preview,
                label = label,
                isUrl = isUrl,
                isImage = isImage,
                imageUri = imageUri,
                timestamp = itemId,
            )

        val event: IslandEvent.Clipboard
        val historySnapshot: List<IslandEvent.ClipboardItem>
        synchronized(clipboardHistory) {
            if (
                state.userId != clipboardUserState.userId ||
                    generation != clipboardGeneration
            ) {
                return false
            }

            clipboardHistory.removeAll { it.preview == preview && !isImage }
            clipboardHistory.add(0, clipItem)
            while (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) {
                val removed = clipboardHistory.removeLast()
                cleanupCachedImage(state, removed.id)
            }
            historySnapshot = clipboardHistory.toList()
            event =
                IslandEvent.Clipboard(
                    label = label,
                    preview = preview,
                    isUrl = isUrl,
                    isImage = isImage,
                    imageUri = imageUri,
                    items = historySnapshot,
                )
            // Keep publication atomic with generation invalidation paths using the same lock.
            _clipboardEvent.value = event
        }

        persistClipboardHistory(state, historySnapshot, generation)

        if (callbackOnMainThread) {
            mainHandler.post {
                synchronized(clipboardHistory) {
                    if (
                        state.userId == clipboardUserState.userId &&
                            generation == clipboardGeneration
                    ) {
                        onClipboardCopied?.invoke(event)
                    }
                }
            }
        } else {
            onClipboardCopied?.invoke(event)
        }
        return true
    }

    private fun nextClipboardGeneration(): Long =
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
            clipboardGeneration
        }

    private fun invalidatePendingClipboardWorkAndPersist(state: ClipboardUserState) {
        val snapshot: List<IslandEvent.ClipboardItem>
        val generation: Long
        synchronized(clipboardHistory) {
            if (state.userId != clipboardUserState.userId) return
            clipboardGeneration += 1L
            generation = clipboardGeneration
            snapshot = clipboardHistory.toList()
        }
        persistClipboardHistory(state, snapshot, generation)
    }

    private fun persistCurrentHistoryForGeneration(
        state: ClipboardUserState,
        generation: Long,
    ) {
        val snapshot =
            synchronized(clipboardHistory) {
                if (
                    state.userId != clipboardUserState.userId ||
                        generation != clipboardGeneration
                ) {
                    return
                }
                clipboardHistory.toList()
            }
        persistClipboardHistory(state, snapshot, generation)
    }

    private fun createClipboardUserState(
        user: UserHandle,
        userContext: Context,
    ): ClipboardUserState =
        ClipboardUserState(
            userId = user.identifier,
            context = userContext,
            manager = clipboardManagerProvider.forUser(user),
        )

    private fun clipboardPrefs(state: ClipboardUserState) =
        state.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clipboardCacheDir(state: ClipboardUserState): File =
        File(state.context.cacheDir, CLIPBOARD_CACHE_DIR).apply { mkdirs() }

    private fun activeClipboardDir(state: ClipboardUserState): File =
        File(clipboardCacheDir(state), ACTIVE_CLIPBOARD_DIR).apply { mkdirs() }

    private fun switchClipboardUser(
        user: UserHandle,
        userContext: Context,
    ) {
        val oldState = clipboardUserState
        if (oldState.userId == user.identifier) return

        val wasListening = clipboardListening
        if (wasListening) {
            try {
                oldState.manager.removePrimaryClipChangedListener(clipboardListener)
            } catch (_: Exception) {}
        }

        val oldSnapshot: List<IslandEvent.ClipboardItem>
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
            oldSnapshot = clipboardHistory.toList()
            clipboardHistory.clear()
            lastClipboardToken = null
            _clipboardEvent.value = null
        }
        persistJob?.cancel()
        // Only ten entries are serialized and SharedPreferences.apply() performs the disk write
        // asynchronously. Persist the outgoing user's snapshot before rebinding so a fast
        // A -> B -> A switch cannot let an older detached coroutine overwrite newer user state.
        writeClipboardHistory(oldState, oldSnapshot)

        val newState = createClipboardUserState(user, userContext)
        clipboardUserState = newState
        loadClipboardHistory(newState)

        if (wasListening) {
            newState.manager.addPrimaryClipChangedListener(clipboardListener)
        }
    }

    private fun ensureClipboardUserBinding() {
        val user = userTracker.userHandle
        if (clipboardUserState.userId != user.identifier) {
            switchClipboardUser(user, userTracker.userContext)
        }
    }

    private fun cleanupCachedImage(
        state: ClipboardUserState,
        itemId: Long,
    ) {
        try {
            File(clipboardCacheDir(state), "clip_$itemId.webp").delete()
            File(clipboardCacheDir(state), "clip_$itemId.png").delete()
        } catch (_: Exception) {}
    }

    private fun cleanupHistoryCache(state: ClipboardUserState) {
        try {
            clipboardCacheDir(state).listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("clip_")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun cleanupActiveClipboardLeases(
        state: ClipboardUserState,
        keep: File? = null,
    ) {
        try {
            activeClipboardDir(state).listFiles()?.forEach { file ->
                if (file != keep) file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun createActiveClipboardLease(
        state: ClipboardUserState,
        sourceUri: Uri,
    ): ActiveClipboardLease? {
        return try {
            val extension =
                sourceUri.lastPathSegment
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                    ?: "bin"
            val file =
                File(
                    activeClipboardDir(state),
                    "active_${System.currentTimeMillis()}.$extension",
                )
            val input = state.context.contentResolver.openInputStream(sourceUri) ?: return null
            input.use { src ->
                file.outputStream().use { dst -> src.copyTo(dst) }
            }
            ActiveClipboardLease(
                uri = FileProvider.getUriForFile(state.context, FILE_PROVIDER_AUTHORITY, file),
                file = file,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create active clipboard image lease", e)
            null
        }
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
        ensureClipboardUserBinding()
        clipboardListening = true
        if (!clipboardUserCallbackRegistered) {
            userTracker.addCallback(clipboardUserCallback, context.mainExecutor)
            clipboardUserCallbackRegistered = true
        }
        val state = clipboardUserState
        loadClipboardHistory(state)
        state.manager.addPrimaryClipChangedListener(clipboardListener)
    }

    fun stopClipboard() {
        if (!clipboardListening) return
        val state = clipboardUserState
        clipboardListening = false
        try {
            state.manager.removePrimaryClipChangedListener(clipboardListener)
        } catch (_: Exception) {}
        if (clipboardUserCallbackRegistered) {
            userTracker.removeCallback(clipboardUserCallback)
            clipboardUserCallbackRegistered = false
        }
        lastClipboardToken = null
        invalidatePendingClipboardWorkAndPersist(state)
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

    fun clearClipboard() {
        val state = clipboardUserState
        persistJob?.cancel()
        synchronized(clipboardHistory) {
            clipboardGeneration += 1L
            _clipboardEvent.value = null
            clipboardHistory.forEach { cleanupCachedImage(state, it.id) }
            clipboardHistory.clear()
            cleanupHistoryCache(state)
            clipboardPrefs(state).edit().remove(KEY_CLIPBOARD_STASH).apply()
        }
    }

    fun clearCharging() {
        chargingDismissed = true
        _chargingEvent.value = null
    }

    fun removeClipboardItem(id: Long) {
        val state = clipboardUserState
        cleanupCachedImage(state, id)
        val event: IslandEvent.Clipboard?
        val historySnapshot: List<IslandEvent.ClipboardItem>
        val generation: Long
        synchronized(clipboardHistory) {
            clipboardHistory.removeAll { it.id == id }
            generation = clipboardGeneration
            historySnapshot = clipboardHistory.toList()
            event =
                if (clipboardHistory.isEmpty()) null
                else {
                    val latest = clipboardHistory.first()
                    _clipboardEvent.value?.copy(
                        label = latest.label,
                        preview = latest.preview,
                        isUrl = latest.isUrl,
                        isImage = latest.isImage,
                        imageUri = latest.imageUri,
                        items = historySnapshot,
                    )
                }
        }
        persistClipboardHistory(state, historySnapshot, generation)
        _clipboardEvent.value = event
    }

    fun copyToClipboard(text: String) {
        if (text.isEmpty()) return
        ensureClipboardUserBinding()
        val state = clipboardUserState
        state.manager.setPrimaryClip(
            markDynamicBarSelfCopy(ClipData.newPlainText("Copied", text))
        )
    }

    fun copyUriToClipboard(uri: Uri, mimeType: String = "image/*") {
        ensureClipboardUserBinding()
        val state = clipboardUserState
        applicationScope.launch(backgroundDispatcher) {
            val lease = createActiveClipboardLease(state, uri)
            if (lease == null) {
                Log.w(TAG, "Unable to lease clipboard image; leaving system clipboard unchanged")
                return@launch
            }
            if (state.userId != clipboardUserState.userId) {
                lease.file.delete()
                return@launch
            }
            try {
                state.manager.setPrimaryClip(
                    markDynamicBarSelfCopy(
                        ClipData("Copied", arrayOf(mimeType), ClipData.Item(lease.uri))
                    )
                )
                cleanupActiveClipboardLeases(state, keep = lease.file)
            } catch (e: SecurityException) {
                lease.file.delete()
                Log.w(TAG, "Failed to copy leased URI to clipboard, trying plain text", e)
                state.manager.setPrimaryClip(
                    markDynamicBarSelfCopy(ClipData.newPlainText("Copied", uri.toString()))
                )
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
        if (url.isEmpty()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            _clipboardEvent.value = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open URL: $url", e)
        }
    }

    private fun persistClipboardHistory(
        state: ClipboardUserState,
        history: List<IslandEvent.ClipboardItem>,
        generation: Long,
    ) {
        persistJob?.cancel()
        persistJob =
            applicationScope.launch(backgroundDispatcher) {
                synchronized(clipboardHistory) {
                    if (
                        state.userId != clipboardUserState.userId ||
                            generation != clipboardGeneration
                    ) {
                        return@launch
                    }
                }
                writeClipboardHistory(state, history)
            }
    }

    private fun writeClipboardHistory(
        state: ClipboardUserState,
        history: List<IslandEvent.ClipboardItem>,
    ) {
        try {
            val arr = JSONArray()
            history.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("preview", item.preview)
                        put("label", item.label)
                        put("isUrl", item.isUrl)
                        put("isImage", item.isImage)
                        put("imageUri", item.imageUri?.toString() ?: "")
                        put("ts", item.timestamp)
                    }
                )
            }
            clipboardPrefs(state).edit().putString(KEY_CLIPBOARD_STASH, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist clipboard history for user ${state.userId}", e)
        }
    }

    private fun loadClipboardHistory(state: ClipboardUserState) {
        try {
            val json = clipboardPrefs(state).getString(KEY_CLIPBOARD_STASH, null) ?: return
            val arr = JSONArray(json)
            var prunedBrokenImage = false
            synchronized(clipboardHistory) {
                clipboardHistory.clear()
                for (i in 0 until arr.length().coerceAtMost(MAX_CLIPBOARD_HISTORY)) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optLong("id", 0L)
                    val isImage = obj.optBoolean("isImage", false)
                    val imageUri =
                        if (isImage) {
                            val cachedWebp = File(clipboardCacheDir(state), "clip_$id.webp")
                            val cachedPng = File(clipboardCacheDir(state), "clip_$id.png")
                            when {
                                cachedWebp.exists() ->
                                    FileProvider.getUriForFile(
                                        state.context,
                                        FILE_PROVIDER_AUTHORITY,
                                        cachedWebp,
                                    )
                                cachedPng.exists() ->
                                    FileProvider.getUriForFile(
                                        state.context,
                                        FILE_PROVIDER_AUTHORITY,
                                        cachedPng,
                                    )
                                else -> {
                                    prunedBrokenImage = true
                                    null
                                }
                            }
                        } else {
                            null
                        }

                    if (isImage && imageUri == null) {
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
                            timestamp = obj.optLong("ts", 0L),
                        )
                    )
                }
            }

            if (prunedBrokenImage && state.userId == clipboardUserState.userId) {
                val snapshot = synchronized(clipboardHistory) { clipboardHistory.toList() }
                persistClipboardHistory(state, snapshot, clipboardGeneration)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load clipboard history for user ${state.userId}", e)
        }
    }

    private data class ChargingSnapshot(
        val isCharging: Boolean,
        val level: Int?,
        val isPowerSave: Boolean,
        val timeEst: String?,
    )
}
