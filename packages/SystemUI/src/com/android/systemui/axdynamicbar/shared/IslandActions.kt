package com.android.systemui.axdynamicbar.shared

import android.net.Uri
import com.android.systemui.axdynamicbar.model.IslandEvent

val EVENT_TYPE_IDS: Map<Class<out IslandEvent>, String> =
    mapOf(
        IslandEvent.ScreenRecording::class.java to "screen_recording",
        IslandEvent.MicCamActive::class.java to "privacy",
        IslandEvent.AudioRecording::class.java to "audio_recording",
        IslandEvent.Casting::class.java to "casting",
        IslandEvent.PromotedOngoing::class.java to "promoted_ongoing",
        IslandEvent.Sports::class.java to "sports",
        IslandEvent.Media::class.java to "media",
        IslandEvent.Bluetooth::class.java to "bluetooth",
        IslandEvent.Hotspot::class.java to "hotspot",
        IslandEvent.Charging::class.java to "charging",
        IslandEvent.Alarm::class.java to "alarm",
        IslandEvent.Timer::class.java to "timer",
        IslandEvent.Stopwatch::class.java to "stopwatch",
        IslandEvent.RingerMode::class.java to "ringer",
        IslandEvent.Vpn::class.java to "vpn",
        IslandEvent.Clipboard::class.java to "clipboard",
        IslandEvent.Notification::class.java to "notification",
        IslandEvent.AppSwitch::class.java to "app_switch",
        IslandEvent.Torch::class.java to "torch",
        IslandEvent.BiometricUnlock::class.java to "biometric_unlock",
    )

interface IslandActions {
    val onFocusableRequested: ((Boolean) -> Unit)?
        get() = null
    fun collapseIsland()
    fun dismissEvent(event: IslandEvent)
    fun togglePlayPause()
    fun skipNext()
    fun skipPrev()
    fun seekTo(position: Long)
    fun sendCustomAction(action: String)
    fun openMediaOutputSwitcher()
    fun openMediaApp()
    fun stopScreenRecording()
    fun disconnectBluetooth(address: String)
    fun setRingerMode(mode: Int)
    fun toggleTorch()
    fun setTorchLevel(level: Int)
    fun setTorchLevelTemporary(level: Int)
    fun copyToClipboard(text: String)
    fun copyUriToClipboard(uri: Uri)
    fun openUrl(url: String)
    fun removeClipboardItem(id: Long)
    fun switchToApp(taskId: Int)
    fun onNotificationInteraction(eventId: String)
    fun onNotificationInteractionEnd(eventId: String)
    fun onNotificationAlertInteractionStart()
    fun onNotificationAlertInteractionEnd()
    fun launchNotificationDismissingKeyguard(event: IslandEvent.Notification)
}
