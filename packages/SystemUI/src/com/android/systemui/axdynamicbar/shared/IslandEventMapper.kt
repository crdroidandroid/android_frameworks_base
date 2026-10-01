/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axdynamicbar.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.res.R

/**
 * How much room an event's chip takes. Size signals urgency, shape signals kind — this is what
 * separates events now that they share wallpaper-derived tones. See [chipHeight]/[chipCornerRadius].
 */
internal enum class ChipTier {
    /** Passive status, worth a glance: the smallest pill. */
    PASSIVE,
    /** Information the user is tracking: the standard pill. */
    ACTIVE,
    /** Something is happening right now (capture, call, live game): a taller squircle. */
    LIVE,
}

internal data class EventStyle(
    val tone: IslandTone,
    val tier: ChipTier,
    val icon: ImageVector?,
    val labelRes: Int,
)

/** The event's accent as a colour: the saturated Material role for [EventStyle.tone]. */
internal val EventStyle.accent: Color
    @Composable get() = tone.tint

/** AOSP chips whose content is a privacy indicator (screen capture / share / cast). */
private fun IslandEvent.AospChip.isCaptureChip(): Boolean {
    // Same keys AospChipAbsorptionPolicy absorbs; substring matching also caught unrelated
    // third-party chips whose key merely contained "share"/"cast" (e.g. "podcast").
    val key = active.key
    return key == "ScreenRecord" || key == "ShareToApp" || key == "CastToOtherDevice"
}

internal fun eventStyleFor(event: IslandEvent): EventStyle = when (event) {
    is IslandEvent.AudioRecording -> EventStyle(
        tier = ChipTier.LIVE,
        // An open mic is a privacy indicator, so it takes the alert role; paused is a capture
        // that still needs notice; saved is done.
        tone = when (event.state) {
            RecordingState.RECORDING -> IslandTone.ALERT
            RecordingState.PAUSED -> IslandTone.ATTENTION
            RecordingState.SAVED -> IslandTone.POSITIVE
        },
        icon = when (event.state) {
            RecordingState.RECORDING -> Icons.Filled.Mic
            RecordingState.PAUSED -> Icons.Filled.Pause
            RecordingState.SAVED -> Icons.Filled.CheckCircle
        },
        labelRes = when (event.state) {
            RecordingState.RECORDING -> R.string.ax_dynamic_bar_recording
            RecordingState.PAUSED -> R.string.ax_dynamic_bar_paused
            RecordingState.SAVED -> R.string.ax_dynamic_bar_saved
        },
    )
    is IslandEvent.Media -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.MusicNote,
        labelRes = R.string.ax_dynamic_bar_music,
    )
    is IslandEvent.Call -> EventStyle(
        tone = IslandTone.POSITIVE,
        tier = ChipTier.LIVE,
        icon = null,
        labelRes = R.string.ax_dynamic_bar_call,
    )
    is IslandEvent.PromotedOngoing -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = null,
        labelRes = R.string.ax_dynamic_bar_on,
    )
    is IslandEvent.NowPlaying -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.MusicNote,
        labelRes = R.string.ax_dynamic_bar_now_playing,
    )
    is IslandEvent.Sports -> EventStyle(
        tone = when (event.status) {
            IslandEvent.GameStatus.LIVE, IslandEvent.GameStatus.HALFTIME -> IslandTone.POSITIVE
            IslandEvent.GameStatus.PRE_GAME -> IslandTone.ACCENT
            IslandEvent.GameStatus.FINAL -> IslandTone.ACCENT
        },
        tier = when (event.status) {
            IslandEvent.GameStatus.LIVE, IslandEvent.GameStatus.HALFTIME -> ChipTier.LIVE
            else -> ChipTier.ACTIVE
        },
        icon = null,
        labelRes = R.string.ax_dynamic_bar_on,
    )
    is IslandEvent.Bluetooth -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.PASSIVE,
        icon = Icons.Filled.Bluetooth,
        labelRes = R.string.ax_dynamic_bar_connected,
    )
    is IslandEvent.Hotspot -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.Wifi,
        labelRes = R.string.ax_dynamic_bar_hotspot,
    )
    is IslandEvent.Charging -> EventStyle(
        tone = IslandTone.POSITIVE,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.BatteryChargingFull,
        labelRes = if (event.isWireless) R.string.ax_dynamic_bar_wireless_charging
        else R.string.ax_dynamic_bar_charging,
    )
    is IslandEvent.Alarm -> EventStyle(
        tone = IslandTone.ATTENTION,
        tier = if (event.isRinging) ChipTier.LIVE else ChipTier.ACTIVE,
        icon = Icons.Filled.Alarm,
        labelRes = R.string.ax_dynamic_bar_alarm,
    )
    is IslandEvent.Timer -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.Timer,
        labelRes = R.string.ax_dynamic_bar_timer,
    )
    is IslandEvent.Stopwatch -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.AvTimer,
        labelRes = R.string.ax_dynamic_bar_stopwatch,
    )
    is IslandEvent.RingerMode -> EventStyle(
        tone = when (event.mode) {
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_VIBRATE -> IslandTone.ATTENTION
            else -> IslandTone.ACCENT
        },
        tier = ChipTier.PASSIVE,
        icon = when (event.mode) {
            AudioManager.RINGER_MODE_SILENT -> Icons.Filled.VolumeOff
            AudioManager.RINGER_MODE_VIBRATE -> Icons.Filled.Vibration
            else -> Icons.Filled.VolumeUp
        },
        labelRes = when (event.mode) {
            AudioManager.RINGER_MODE_SILENT -> R.string.ax_dynamic_bar_silent
            AudioManager.RINGER_MODE_VIBRATE -> R.string.ax_dynamic_bar_vibrate
            else -> R.string.ax_dynamic_bar_ring
        },
    )
    is IslandEvent.Vpn -> EventStyle(
        tone = if (event.isValidated) IslandTone.POSITIVE else IslandTone.ATTENTION,
        tier = ChipTier.PASSIVE,
        icon = Icons.Filled.VpnKey,
        labelRes = if (event.isBranded) R.string.ax_dynamic_bar_vpn_active
        else R.string.ax_dynamic_bar_vpn_connected,
    )
    is IslandEvent.Clipboard -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.PASSIVE,
        icon = Icons.Filled.ContentCopy,
        labelRes = R.string.ax_dynamic_bar_copied,
    )
    is IslandEvent.Torch -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.PASSIVE,
        icon = Icons.Filled.FlashlightOn,
        labelRes = R.string.ax_dynamic_bar_flashlight,
    )
    is IslandEvent.Notification -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.ACTIVE,
        icon = Icons.Filled.Notifications,
        labelRes = R.string.ax_dynamic_bar_on,
    )
    is IslandEvent.AppSwitch -> EventStyle(
        tone = IslandTone.ACCENT,
        tier = ChipTier.PASSIVE,
        icon = null,
        labelRes = R.string.ax_dynamic_bar_on,
    )
    is IslandEvent.BiometricUnlock -> EventStyle(
        tone = IslandTone.POSITIVE,
        tier = ChipTier.PASSIVE,
        icon = Icons.Filled.Fingerprint,
        labelRes = R.string.ax_dynamic_bar_unlocked,
    )
    is IslandEvent.KeyguardIndication -> EventStyle(
        tone = when (event.indicationType) {
            IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC,
            IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK -> IslandTone.POSITIVE
            IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> IslandTone.ATTENTION
            else -> IslandTone.ACCENT
        },
        tier = ChipTier.PASSIVE,
        icon = null,
        labelRes = R.string.ax_dynamic_bar_on,
    )
    // AOSP chips carry privacy meaning that a single accent would erase: a screen capture or
    // share is an alert, a call chip is the same positive/live treatment as our own Call.
    is IslandEvent.AospChip -> EventStyle(
        tone = when {
            event.isCaptureChip() -> IslandTone.ALERT
            event.active.key.startsWith("callChip-") -> IslandTone.POSITIVE
            else -> IslandTone.ACCENT
        },
        tier = when {
            event.isCaptureChip() || event.active.key.startsWith("callChip-") -> ChipTier.LIVE
            else -> ChipTier.ACTIVE
        },
        icon = Icons.Filled.Notifications,
        labelRes = R.string.ax_dynamic_bar_on,
    )
}
