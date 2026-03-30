package com.android.systemui.axdynamicbar.ui

import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AxDynamicBarChipState(
    val event: IslandEvent,
    val eventCount: Int,
    val pinnedIndex: Int,
    val allEvents: List<IslandEvent>,
    val notificationAlert: IslandEvent.Notification? = null,
)

data class KeyguardBatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val isPowerSave: Boolean,
    val isWireless: Boolean,
    val timeRemaining: String?,
)

@SysUISingleton
class AxDynamicBarChipViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    val interactor: AxDynamicBarInteractor,
    batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    authController: AuthController,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {

    val isLowUdfps: StateFlow<Boolean> =
        udfpsOverlayInteractor.udfpsOverlayParams
            .map { params ->
                if (!authController.isUdfpsSupported) return@map false
                val sensorBottom = params.sensorBounds.bottom
                val displayHeight = params.naturalDisplayHeight
                if (displayHeight <= 0) return@map false
                sensorBottom > displayHeight * LOW_UDFPS_THRESHOLD
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val chipState: StateFlow<AxDynamicBarChipState?> =
        interactor.uiState
            .map { uiState ->
                if (!uiState.shouldShow) return@map null
                val alert = uiState.notificationAlert
                val topEvent = uiState.topEvent ?: alert ?: return@map null
                AxDynamicBarChipState(
                    event = topEvent,
                    eventCount = uiState.activeEvents.size,
                    pinnedIndex = uiState.pinnedEventIndex,
                    allEvents = uiState.events,
                    notificationAlert = alert,
                )
            }
            .stateIn(applicationScope, SharingStarted.Lazily, null)

    val isEnabled: StateFlow<Boolean> = interactor.settings.isEnabled
    val isKeyguardEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardEnabled

    val keyguardBatteryInfo: StateFlow<KeyguardBatteryInfo> =
        combine(
            batteryInteractor.level,
            batteryInteractor.isCharging,
            batteryInteractor.powerSave,
            batteryInteractor.batteryTimeRemainingEstimate,
        ) { level, charging, powerSave, timeRemaining ->
            KeyguardBatteryInfo(
                level = level ?: 0,
                isCharging = charging,
                isPowerSave = powerSave,
                isWireless = batteryController.isPluggedInWireless,
                timeRemaining = timeRemaining,
            )
        }.stateIn(
            applicationScope,
            SharingStarted.Lazily,
            KeyguardBatteryInfo(0, false, false, false, null),
        )

    val isOnKeyguard: StateFlow<Boolean> = interactor.isOnKeyguard

    val isKeyguardFadingAway: StateFlow<Boolean> = interactor.isKeyguardFadingAway

    val isBouncerShowing: StateFlow<Boolean> = interactor.isBouncerShowing

    private val _keyguardCarrierText = MutableStateFlow("")
    val keyguardCarrierText: StateFlow<String> = _keyguardCarrierText.asStateFlow()

    fun updateKeyguardCarrierText(text: String) {
        _keyguardCarrierText.value = text
    }

    private val _chipCenterXFraction = MutableStateFlow(0.5f)
    val chipCenterXFraction: StateFlow<Float> = _chipCenterXFraction.asStateFlow()

    fun updateChipCenterX(fraction: Float) {
        _chipCenterXFraction.value = fraction
    }

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()
    @Volatile private var collapseOnNullJob: Job? = null

    val isKeyguardExpanded: StateFlow<Boolean> =
        combine(isExpanded, isOnKeyguard) { exp, kg -> exp && kg }
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    init {
        
        chipState.onEach { state ->
            if (state == null) {
                collapseOnNullJob?.cancel()
                collapseOnNullJob = applicationScope.launch {
                    delay(200)
                    _isExpanded.value = false
                }
            } else {
                collapseOnNullJob?.cancel()
                collapseOnNullJob = null
            }
        }.launchIn(applicationScope)
        
        interactor.isPanelExpanded.onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
        interactor.qsExpansion.map { it > 0f }.distinctUntilChanged().onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
        
        isBouncerShowing.onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
        
        isOnKeyguard.onEach { if (!it) _isExpanded.value = false }.launchIn(applicationScope)
        
        combine(interactor.legacyShadeExpansion, isOnKeyguard) { expansion, onKg ->
            onKg && expansion < 0.95f
        }.onEach { dismissing -> if (dismissing) _isExpanded.value = false }.launchIn(applicationScope)
        
        interactor.isDozing.drop(1).onEach { _isExpanded.value = false }.launchIn(applicationScope)
        
        interactor.dozeAmount.map { it > 0f }.distinctUntilChanged().onEach { if (it) _isExpanded.value = false }.launchIn(applicationScope)
    }

    fun expandPanel() {
        if (chipState.value != null) _isExpanded.value = true
    }

    fun collapsePanel() {
        _isExpanded.value = false
    }

    fun togglePanel() {
        if (_isExpanded.value) collapsePanel() else expandPanel()
    }

    fun cycleNext() = interactor.cycleNext()

    fun cyclePrev() = interactor.cyclePrev()

    fun dismissEvent(event: IslandEvent) = interactor.dismissEvent(event)

    fun togglePlayPause() = interactor.togglePlayPause()

    fun skipNext() = interactor.skipNext()

    fun skipPrev() = interactor.skipPrev()

    fun toggleTorch() = interactor.toggleTorch()

    fun stopScreenRecording() = interactor.stopScreenRecording()

    fun launchNotificationFromKeyguard(event: IslandEvent.Notification) {
        interactor.launchNotificationDismissingKeyguard(event)
    }

    companion object {
        private const val LOW_UDFPS_THRESHOLD = 0.93f
    }
}

