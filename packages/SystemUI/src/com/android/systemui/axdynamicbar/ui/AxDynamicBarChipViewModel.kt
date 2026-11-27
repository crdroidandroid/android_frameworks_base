package com.android.systemui.axdynamicbar.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.android.systemui.animation.Expandable
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

@OptIn(ExperimentalCoroutinesApi::class)
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
    val keyguardExpansion: AxDynamicBarKeyguardExpansion,
    val statusBarExpansion: AxDynamicBarStatusBarExpansion,
    private val keyguardIndicationController: KeyguardIndicationController,
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
                    eventCount = uiState.events.count { it !is IslandEvent.Notification },
                    pinnedIndex = uiState.pinnedEventIndex,
                    allEvents = uiState.events,
                    notificationAlert = alert,
                )
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, null)

    val isEnabled: StateFlow<Boolean> = interactor.settings.isEnabled
    val isKeyguardEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardEnabled
    val keyguardBatteryChipMode: StateFlow<Int> = interactor.settings.keyguardBatteryChipMode
    val chipMorphEnabled: StateFlow<Boolean> = interactor.settings.chipMorphEnabled
    val hapticsEnabled: StateFlow<Boolean> = interactor.settings.hapticsEnabled
    val centerIsland: StateFlow<Boolean> = interactor.settings.centerIsland

    val cardStyle: StateFlow<Int> = interactor.settings.cardStyle

    val shadeColorStyle: StateFlow<Int> = interactor.settings.shadeColorStyle

    fun performTapHaptic(view: View) {
        if (hapticsEnabled.value) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

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

    init {
        applicationScope.launch {
            interactor.uiState
                .map { state ->
                    state.events
                        .firstOrNull { it is IslandEvent.Call && it.callType == "Phone:incoming" }
                        ?.id
                }
                .distinctUntilChanged()
                .collect { incomingCallId ->
                    if (incomingCallId != null) {
                        interactor.dismissNotificationAlert()
                        if (interactor.isOnKeyguard.value) {
                            keyguardExpansion.expand()
                        } else {
                            statusBarExpansion.expand()
                        }
                    } else {
                        statusBarExpansion.collapse()
                        keyguardExpansion.collapse()
                    }
                }
        }
    }

    // Re-compute charging string only when battery state changes. Polling this while idle is costly
    // because KeyguardIndicationController formats through Resources on the main thread.
    val batteryString: StateFlow<String> =
        keyguardBatteryInfo
            .map {
                if (it.isCharging) {
                    formatChargingString(keyguardIndicationController.powerChargingString)
                } else {
                    ""
                }
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, "")

    val isOnKeyguard: StateFlow<Boolean> = interactor.isOnKeyguard

    private val _islandActive = MutableStateFlow(false)

    fun setIslandActive(active: Boolean) {
        _islandActive.value = active
    }

    // Only the start side chip competes with the notification icons; the island makes room instead.
    val isShowingOnStatusBar: Flow<Boolean> =
        combine(isEnabled, chipState, isOnKeyguard, _islandActive) { enabled, state, onKeyguard, island ->
                enabled && state != null && !onKeyguard && !island
            }
            .distinctUntilChanged()

    val isKeyguardFadingAway: StateFlow<Boolean> = interactor.isKeyguardFadingAway

    val isBouncerShowing: StateFlow<Boolean> = interactor.isBouncerShowing

    private val _keyguardCarrierText = MutableStateFlow("")
    val keyguardCarrierText: StateFlow<String> = _keyguardCarrierText.asStateFlow()

    fun updateKeyguardCarrierText(text: String) {
        _keyguardCarrierText.value = text
    }

    private val _chipBoundsOnScreen = MutableStateFlow<Rect?>(null)
    val chipBoundsOnScreen: StateFlow<Rect?> = _chipBoundsOnScreen.asStateFlow()

    fun updateChipBounds(bounds: Rect) {
        if (bounds.width > 0f && bounds.height > 0f) _chipBoundsOnScreen.value = bounds
    }

    private val islandWidths = mutableMapOf<Any, Int>()
    private val _islandWidthPx = MutableStateFlow(0)
    val islandReservedWidthPx: StateFlow<Int> = _islandWidthPx.asStateFlow()

    fun reportIslandWidth(key: Any, px: Int) {
        islandWidths[key] = px
        _islandWidthPx.value = islandWidths.values.max()
    }

    fun clearIslandWidth(key: Any) {
        islandWidths.remove(key)
        _islandWidthPx.value = islandWidths.values.maxOrNull() ?: 0
    }

    // Island tucked into the camera for the event it was minimized on; anything new shows again.
    private val minimizedEventId = MutableStateFlow<String?>(null)

    val isIslandMinimized: StateFlow<Boolean> =
        combine(chipState, minimizedEventId) { state, id ->
                id != null && state != null && state.notificationAlert == null &&
                    state.event.id == id && canMinimize(state.event)
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    init {
        applicationScope.launch {
            chipState.collect { state ->
                val id = minimizedEventId.value ?: return@collect
                if (state == null || (state.notificationAlert == null && state.event.id != id)) {
                    minimizedEventId.value = null
                }
            }
        }
    }

    fun minimizeIsland(): Boolean {
        val state = chipState.value ?: return false
        if (state.notificationAlert != null || !canMinimize(state.event)) return false
        minimizedEventId.value = state.event.id
        return true
    }

    fun isIslandSwipeTarget(x: Float, y: Float): Boolean {
        if (!_islandActive.value || chipState.value == null || isIslandMinimized.value) return false
        return chipBoundsOnScreen.value?.contains(Offset(x, y)) == true
    }

    fun restoreIsland() {
        minimizedEventId.value = null
    }

    private fun canMinimize(event: IslandEvent): Boolean = when (event) {
        is IslandEvent.Call -> event.callType != "Phone:incoming"
        is IslandEvent.Alarm -> !event.isRinging
        else -> true
    }

    val isExpanded: StateFlow<Boolean> = statusBarExpansion.isExpanded

    val isKeyguardExpanded: StateFlow<Boolean> = keyguardExpansion.isExpanded

    @Volatile private var outsideCollapseDownTime = NO_OUTSIDE_COLLAPSE

    fun collapseFromOutsideTouch(downTime: Long) {
        if (!statusBarExpansion.isExpanded.value || downTime <= 0L) return
        outsideCollapseDownTime = downTime
        statusBarExpansion.collapse()
    }

    fun chipTapClosesPanel(downTime: Long): Boolean {
        val outside = outsideCollapseDownTime
        outsideCollapseDownTime = NO_OUTSIDE_COLLAPSE
        val sameGesture =
            outside != NO_OUTSIDE_COLLAPSE && abs(downTime - outside) <= SAME_GESTURE_TOLERANCE_MS
        return sameGesture || statusBarExpansion.isExpanded.value
    }

    fun cycleNext() = interactor.cycleNext()

    fun cyclePrev() = interactor.cyclePrev()

    fun dismissEvent(event: IslandEvent) = interactor.dismissEvent(event)

    fun togglePlayPause() = interactor.togglePlayPause()

    fun skipNext() = interactor.skipNext()

    fun skipPrev() = interactor.skipPrev()

    fun toggleTorch() = interactor.toggleTorch()

    fun launchNotificationFromKeyguard(event: IslandEvent.Notification) {
        interactor.launchNotificationDismissingKeyguard(event)
    }

    fun handleAospChipTap(event: IslandEvent.AospChip, expandable: Expandable): Boolean {
        val active = event.active
        return when (val behavior = active.clickBehavior) {
            is OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.HideHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.ExpandAction -> {
                behavior.onClick(expandable)
                true
            }
            is OngoingActivityChipModel.ClickBehavior.None -> false
        }
    }

    companion object {
        private const val LOW_UDFPS_THRESHOLD = 0.93f
        private const val NO_OUTSIDE_COLLAPSE = -1L
        private const val SAME_GESTURE_TOLERANCE_MS = 100L
    }

    private fun formatChargingString(text: String?): String {
        val cleaned = text?.trim()
        return if (cleaned.isNullOrEmpty()) "" else cleaned
    }
}
