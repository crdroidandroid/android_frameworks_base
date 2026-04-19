package com.android.systemui.axdynamicbar.domain

import android.net.Uri
import com.android.systemui.axdynamicbar.data.IslandEventRepository
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.IslandState
import com.android.systemui.axdynamicbar.model.IslandUiState
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class AxDynamicBarInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: IslandEventRepository,
    val settings: AxDynamicBarSettings,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    val sliderHapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val activityStarter: ActivityStarter,
    private val indicationController: KeyguardIndicationController,
    private val shadeInteractor: ShadeInteractor,
    private val shadeRepository: ShadeRepository,
) : IslandActions {
    private val _uiState = MutableStateFlow(IslandUiState())
    val uiState: StateFlow<IslandUiState> = _uiState.asStateFlow()

    private val autoDismissJobs = ConcurrentHashMap<String, Job>()

    private val dismissedEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override var onFocusableRequested: ((Boolean) -> Unit)? = null

    var onCollapseRequested: (() -> Unit)? = null

    private var isInitialized = false

    @Volatile private var panelBlocking = false
    private val _isPanelExpanded = MutableStateFlow(false)
    
    val isPanelExpanded: StateFlow<Boolean> = _isPanelExpanded.asStateFlow()
    
    val qsExpansion: StateFlow<Float> = shadeInteractor.qsExpansion
    
    val legacyShadeExpansion: StateFlow<Float> = shadeRepository.legacyShadeExpansion
    private val _isOnKeyguard = MutableStateFlow(false)
    val isOnKeyguard: StateFlow<Boolean> = _isOnKeyguard.asStateFlow()
    private val _isKeyguardFadingAway = MutableStateFlow(false)
    val isKeyguardFadingAway: StateFlow<Boolean> = _isKeyguardFadingAway.asStateFlow()
    private val _isBouncerShowing = MutableStateFlow(false)
    val isBouncerShowing: StateFlow<Boolean> = _isBouncerShowing.asStateFlow()
    private val _isDozing = MutableStateFlow(statusBarStateController.isDozing)
    val isDozing: StateFlow<Boolean> = _isDozing.asStateFlow()
    private val _dozeAmount = MutableStateFlow(0f)
    
    val dozeAmount: StateFlow<Float> = _dozeAmount.asStateFlow()
    @Volatile private var isDreaming = false

    private val statusBlocking: Boolean
        get() = _isDozing.value || isDreaming

    companion object {
        private const val TAG = "AxDynamicBarInteractor"
    }

    fun init() {
        if (isInitialized) return
        isInitialized = true

        settings.init()

        repository.system.onChargingStarted = { scheduleAutoDismiss(it) }
        repository.system.onRingerChanged = { scheduleAutoDismiss(it) }
        repository.system.onClipboardCopied = { scheduleAutoDismiss(it) }

        repository.notification.activeMediaPackageProvider = { repository.media.activeMediaPackage }
        repository.notification.onAlarmEvent = {
            scheduleAutoDismiss(it, if (it.isRinging) 30_000L else 5_000L)
        }

        repository.media.onMediaSessionLost = { repository.media.clear() }

        repository.biometric.onBiometricUnlock = { scheduleAutoDismiss(it) }

        applicationScope.launch {
            repository.notification.audioRecordingEvent.collect { event ->
                if (event?.state == RecordingState.SAVED) {
                    scheduleAutoDismiss(event, 5_000L)
                }
            }
        }

        applicationScope.launch {
            repository.notification.notificationFlow.collect { notification ->
                repository.notification.coalesceNotification(notification)
            }
        }

        applicationScope.launch {
            combine(
                _uiState.map { state ->
                    state.shouldShow &&
                        state.events.any { it is IslandEvent.Media && it.isPlaying && it.duration > 0L }
                },
                _isPanelExpanded,
                qsExpansion.map { it > 0f },
            ) { mediaActive, panelExpanded, qsOpen ->
                mediaActive && !panelExpanded && !qsOpen
            }.distinctUntilChanged().collect { needsPolling ->
                if (needsPolling) repository.media.startProgressPolling()
                else repository.media.stopProgressPolling()
            }
        }

        _isOnKeyguard.value = statusBarStateController.state == StatusBarState.KEYGUARD
        _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
        _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing

        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onKeyguardFadingAwayChanged() {
                    _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
                }

                override fun onPrimaryBouncerShowingChanged() {
                    _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing
                }
            }
        )

        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onExpandedChanged(isExpanded: Boolean) {
                    onPanelExpandedChanged(isExpanded)
                }

                override fun onStateChanged(newState: Int) {
                    _isOnKeyguard.value = newState == StatusBarState.KEYGUARD
                    updateChipVisibility()
                }

                override fun onDozingChanged(dozing: Boolean) {
                    _isDozing.value = dozing
                    updateChipVisibility()
                }

                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    _dozeAmount.value = linear
                }

                override fun onDreamingChanged(dreaming: Boolean) {
                    isDreaming = dreaming
                    updateChipVisibility()
                }
            }
        )

        indicationController.addIndicationListener { type, text ->
            val indicationType = mapIndicationType(type) ?: return@addIndicationListener
            if (text != null && text.isNotEmpty()) {
                val event = IslandEvent.KeyguardIndication(
                    text = text.toString(),
                    indicationType = indicationType,
                )
                repository.updateIndicationEvent(event)
                scheduleAutoDismiss(event)
            } else {
                repository.clearIndicationEvent(indicationType)
            }
        }

        applicationScope.launch {
            settings.isEnabled.collect { enabled ->
                if (enabled) repository.startListening()
                else {
                    repository.stopListening()
                    autoDismissJobs.values.forEach { it.cancel() }
                    autoDismissJobs.clear()
                    dismissedEventIds.clear()
                    repository.clearAllIndicationEvents()
                    _uiState.value = IslandUiState()
                }
            }
        }

        applicationScope.launch {
            settings.disabledEventTypes.collect {
                repository.refreshListeners()
            }
        }

        applicationScope.launch {
            combine(
                repository.events,
                settings.disabledEventTypes,
                _isOnKeyguard,
            ) { raw, _, kg ->
                raw.filter { settings.isEventEnabled(it) } to kg
            }.collect { (rawEvents, onKeyguard) ->
                if (!settings.isEnabled.value) return@collect
                dismissedEventIds.removeAll { id -> rawEvents.none { it.id == id } }
                val events = rawEvents.filter { e ->
                    e.id !in dismissedEventIds &&
                        
                        !(onKeyguard && e is IslandEvent.Notification) &&
                        
                        !(onKeyguard && e is IslandEvent.Charging) &&
                        
                        !(onKeyguard && e is IslandEvent.AppSwitch) &&
                        
                        !(!onKeyguard && e is IslandEvent.KeyguardIndication)
                }

                val current = _uiState.value

                val hasNewEvents = events.any { e -> current.events.none { it.id == e.id } }

                val userInitiatedRefresh =
                    events.any { e ->
                        e is IslandEvent.Torch &&
                            current.events.firstOrNull { it.id == e.id }?.let { it != e } == true
                    }

                val hasSignificantChange =
                    !hasNewEvents &&
                        events.any { e ->
                            val old =
                                current.events.firstOrNull { it.id == e.id } ?: return@any false
                            when {
                                e is IslandEvent.Media && old is IslandEvent.Media ->
                                    e.track != old.track || e.artist != old.artist
                                else -> false
                            }
                        }

                val newState =
                    when {
                        events.isEmpty() -> IslandState.HIDDEN
                        panelBlocking || statusBlocking -> IslandState.HIDDEN
                        else -> IslandState.CHIP
                    }

                val prevPinnedId =
                    current.events.getOrNull(current.pinnedEventIndex)?.id

                val pinnedIndex =
                    when {
                        hasNewEvents -> {
                            val currentIds = current.events.map { it.id }.toSet()
                            events.indexOfFirst { it.id !in currentIds }.coerceAtLeast(0)
                        }
                        hasSignificantChange -> {
                            val idx =
                                events.indexOfFirst { e ->
                                    val old =
                                        current.events.firstOrNull { it.id == e.id }
                                            ?: return@indexOfFirst false
                                    when {
                                        e is IslandEvent.Media && old is IslandEvent.Media ->
                                            e.track != old.track || e.artist != old.artist
                                        else -> false
                                    }
                                }
                            if (idx >= 0) idx
                            else resolveByIdOrFallback(prevPinnedId, events, current)
                        }
                        userInitiatedRefresh -> 0
                        else -> resolveByIdOrFallback(prevPinnedId, events, current)
                    }
                val shouldReset = hasNewEvents || userInitiatedRefresh || hasSignificantChange

                _uiState.value =
                    IslandUiState(
                        events = events,
                        islandState = newState,
                        pinnedEventIndex = pinnedIndex,
                        manuallyHidden = if (shouldReset) false else current.manuallyHidden,
                        forceVisible = false,
                    )
            }
        }
    }

    fun cycleNext() {
        val current = _uiState.value
        if (current.events.size <= 1) return
        val next = (current.pinnedEventIndex + 1) % current.events.size
        _uiState.value = current.copy(pinnedEventIndex = next)
    }

    fun cyclePrev() {
        val current = _uiState.value
        if (current.events.size <= 1) return
        val prev = (current.pinnedEventIndex - 1 + current.events.size) % current.events.size
        _uiState.value = current.copy(pinnedEventIndex = prev)
    }

    fun pinEventAt(index: Int) {
        val current = _uiState.value
        if (index < 0 || index >= current.events.size) return
        _uiState.value = current.copy(pinnedEventIndex = index)
    }

    override fun dismissEvent(event: IslandEvent) {
        autoDismissJobs[event.id]?.cancel()
        autoDismissJobs.remove(event.id)
        if (event.behavior.suppressOnDismiss) {
            dismissedEventIds.add(event.id)
        }

        val current = _uiState.value
        val updatedEvents = current.events.filter { it.id != event.id }
        val newIndex =
            current.pinnedEventIndex.coerceAtMost((updatedEvents.size - 1).coerceAtLeast(0))

        _uiState.value =
            current.copy(
                events = updatedEvents,
                pinnedEventIndex = newIndex,
                islandState =
                    if (updatedEvents.isEmpty()) IslandState.HIDDEN else current.islandState,
            )

        when (event) {
            is IslandEvent.AudioRecording -> repository.notification.clearAudioRecording()
            is IslandEvent.Sports -> {
                repository.smartspace.clearSportsEvent(event.key)
                repository.notification.clearSportsEvent(event.key)
            }
            is IslandEvent.NowPlaying -> {}
            is IslandEvent.PromotedOngoing ->
                repository.notification.clearPromotedOngoing(event.sbn.key)
            is IslandEvent.Media -> repository.media.clear()
            is IslandEvent.Bluetooth -> repository.connectivity.clearBluetooth()
            is IslandEvent.Hotspot -> repository.connectivity.clearHotspot()
            is IslandEvent.Charging -> repository.system.clearCharging()
            is IslandEvent.Alarm -> repository.notification.clearAlarm()
            is IslandEvent.Timer -> repository.notification.clearTimer()
            is IslandEvent.Stopwatch -> repository.notification.clearStopwatch()
            is IslandEvent.RingerMode -> repository.system.clearRinger()
            is IslandEvent.Vpn -> repository.connectivity.clearVpn()
            is IslandEvent.Clipboard -> repository.system.clearClipboard()
            is IslandEvent.Notification -> repository.notification.dismissNotification(event)
            is IslandEvent.AppSwitch -> repository.appTracking.clear()
            is IslandEvent.Torch -> {
                repository.torch.toggleTorch()
                repository.torch.clear()
            }
            is IslandEvent.BiometricUnlock -> repository.biometric.clear()
            is IslandEvent.KeyguardIndication -> repository.clearIndicationEvent(event.indicationType)
            is IslandEvent.AospChip -> {}
        }
    }

    fun getTopEvent(): IslandEvent? = _uiState.value.topEvent

    override fun collapseIsland() {
        onCollapseRequested?.invoke()
    }

    override fun onNotificationInteraction(eventId: String) {
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs.remove(eventId)
    }

    override fun onNotificationInteractionEnd(eventId: String) {
        val event = _uiState.value.events.find { it.id == eventId } ?: return
        scheduleAutoDismiss(event)
    }

    override fun togglePlayPause() = repository.media.togglePlayPause()

    override fun skipNext() = repository.media.skipNext()

    override fun skipPrev() = repository.media.skipPrev()

    override fun sendCustomAction(action: String) = repository.media.sendCustomAction(action)

    override fun openMediaOutputSwitcher() = repository.media.openMediaOutputSwitcher()

    override fun disconnectBluetooth(address: String) = repository.connectivity.disconnectBluetooth(address)

    override fun openUrl(url: String) = repository.system.openUrl(url)

    override fun openMediaApp() = repository.media.openMediaApp()

    override fun seekTo(position: Long) = repository.media.seekTo(position)

    override fun setRingerMode(mode: Int) = repository.system.setRingerMode(mode)

    override fun toggleTorch() = repository.torch.toggleTorch()

    override fun launchNotificationDismissingKeyguard(event: IslandEvent.Notification) {
        val intent = event.sbn.notification?.contentIntent ?: return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    override fun setTorchLevel(level: Int) = repository.torch.setLevel(level)

    override fun setTorchLevelTemporary(level: Int) = repository.torch.setLevelTemporary(level)

    override fun copyToClipboard(text: String) = repository.system.copyToClipboard(text)

    override fun copyUriToClipboard(uri: Uri) = repository.system.copyUriToClipboard(uri)

    override fun removeClipboardItem(id: Long) = repository.system.removeClipboardItem(id)

    override fun switchToApp(taskId: Int) = repository.appTracking.switchToApp(taskId)

    fun onPanelExpandedChanged(expanded: Boolean) {
        panelBlocking = expanded
        _isPanelExpanded.value = expanded
        updateChipVisibility()
    }

    private fun updateChipVisibility() {
        val current = _uiState.value
        val shouldHide = panelBlocking || statusBlocking
        if (shouldHide && current.islandState == IslandState.CHIP) {
            _uiState.value = current.copy(islandState = IslandState.HIDDEN)
        } else if (!shouldHide && current.events.isNotEmpty() && current.islandState == IslandState.HIDDEN && !current.manuallyHidden) {
            _uiState.value = current.copy(islandState = IslandState.CHIP)
        }
    }

    private fun scheduleAutoDismiss(event: IslandEvent, delayOverride: Long? = null) {
        val ms = delayOverride ?: event.behavior.autoDismissMs ?: return
        val eventId = event.id
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs[eventId] =
            applicationScope.launch {
                delay(ms)
                val current = _uiState.value.events.find { it.id == eventId } ?: event
                dismissEvent(current)
            }
    }

    private fun resolveByIdOrFallback(
        pinnedId: String?,
        events: List<IslandEvent>,
        current: IslandUiState,
    ): Int {
        if (pinnedId != null) {
            val idx = events.indexOfFirst { it.id == pinnedId }
            if (idx >= 0) return idx
        }
        return current.pinnedEventIndex.coerceAtMost(
            (events.size - 1).coerceAtLeast(0)
        )
    }

    private fun mapIndicationType(type: Int): IslandEvent.KeyguardIndication.IndicationType? =
        when (type) {
            KeyguardIndicationController.AX_TYPE_BIOMETRIC ->
                IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC
            KeyguardIndicationController.AX_TYPE_TRANSIENT ->
                IslandEvent.KeyguardIndication.IndicationType.TRANSIENT
            KeyguardIndicationController.AX_TYPE_TRUST ->
                IslandEvent.KeyguardIndication.IndicationType.TRUST
            KeyguardIndicationController.AX_TYPE_DISCLOSURE ->
                IslandEvent.KeyguardIndication.IndicationType.DISCLOSURE
            KeyguardIndicationController.AX_TYPE_OWNER_INFO ->
                IslandEvent.KeyguardIndication.IndicationType.OWNER_INFO
            KeyguardIndicationController.AX_TYPE_ALIGNMENT ->
                IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT
            KeyguardIndicationController.AX_TYPE_PERSISTENT_UNLOCK ->
                IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK
            else -> null
        }
}
