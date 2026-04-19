package com.android.systemui.axdynamicbar.model

import android.app.Notification
import android.graphics.drawable.Drawable
import android.net.Uri
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel

enum class RecordingState {
    RECORDING,
    PAUSED,
    SAVED,
}

const val NOTIFICATION_DECAY_MS = 5000L
const val NOTIFICATION_FRESH_PRIORITY = 75
const val NOTIFICATION_STALE_PRIORITY = 15
const val MEDIA_PLAYING_PRIORITY = 70
const val MEDIA_PAUSED_PRIORITY = 20

data class EventBehavior(
    val autoDismissMs: Long? = null,
    val suppressOnDismiss: Boolean = true,
    val autoShowsIsland: Boolean = true,
)

sealed class IslandEvent(open val priority: Int, val id: String) : Comparable<IslandEvent> {

    open val behavior: EventBehavior
        get() = DEFAULT_BEHAVIOR

    open fun withoutDrawables(): IslandEvent = this

    data class AospChip(
        val active: OngoingActivityChipModel.Active,
    ) : IslandEvent(priority = priorityForAospChipKey(active.key), id = "aosp_${active.key}")

    data class AudioRecording(
        val appName: String = "",
        val state: RecordingState = RecordingState.RECORDING,
        val startTimeMs: Long = System.currentTimeMillis(),
        val actions: List<NotificationAction> = emptyList(),
        val appIcon: Drawable? = null,
        val pausedDurationMs: Long = 0L,
    ) : IslandEvent(priority = 84, id = "audio_recording") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Media(
        val track: String,
        val artist: String,
        val isPlaying: Boolean,
        val albumArt: Drawable? = null,
        val progress: Float = 0f,
        val duration: Long = 0L,
        val position: Long = 0L,
        val playbackSpeed: Float = 1f,
        val positionUpdateTime: Long = 0L,
        val outputDeviceName: String = "",
        val customActions: List<MediaCustomAction> = emptyList(),
        val appIcon: Drawable? = null,
        val packageName: String = "",
        val mediaColor: Int = 0,
    ) : IslandEvent(priority = MEDIA_PLAYING_PRIORITY, id = "media") {
        override val priority: Int
            get() = if (isPlaying) MEDIA_PLAYING_PRIORITY else MEDIA_PAUSED_PRIORITY
        override fun withoutDrawables() = copy(albumArt = null, appIcon = null)
    }

    data class Bluetooth(
        val deviceName: String,
        val batteryLevel: Int = -1,
        val isConnected: Boolean = true,
        val address: String = "",
        val deviceIcon: Drawable? = null,
        val deviceTypeLabel: String = "",
    ) : IslandEvent(priority = 60, id = "bluetooth") {
        override fun withoutDrawables() = copy(deviceIcon = null)
    }

    data class Hotspot(val numDevices: Int) : IslandEvent(priority = 55, id = "hotspot")

    data class Charging(
        val level: Int,
        val isWireless: Boolean,
        val isCharging: Boolean = true,
        val isPowerSave: Boolean = false,
        val timeRemaining: String? = null,
    ) : IslandEvent(priority = 50, id = "charging") {
        override val behavior = EventBehavior(autoDismissMs = 3000L, suppressOnDismiss = false)
    }

    data class RingerMode(val mode: Int, val label: String) :
        IslandEvent(priority = 40, id = "ringer_mode") {
        override val behavior = EventBehavior(autoDismissMs = 2500L, suppressOnDismiss = false)
    }

    data class BiometricUnlock(val sourceType: Int = 0, val sourceName: String = "Fingerprint") :
        IslandEvent(priority = 80, id = "biometric_unlock") {
        override val behavior = EventBehavior(autoDismissMs = 2000L, suppressOnDismiss = false)
    }

    data class Torch(val level: Int = -1, val maxLevel: Int = -1) :
        IslandEvent(priority = 97, id = "torch") {
        override val behavior = EventBehavior(suppressOnDismiss = false)
        val supportsLevel: Boolean
            get() = level >= 1 && maxLevel > 1
    }

    data class Vpn(val isBranded: Boolean = false, val isValidated: Boolean = false) :
        IslandEvent(priority = 35, id = "vpn")

    data class ClipboardItem(
        val id: Long,
        val preview: String,
        val label: String = "",
        val isUrl: Boolean = false,
        val isImage: Boolean = false,
        val imageUri: Uri? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class Clipboard(
        val label: String = "",
        val preview: String = "",
        val isUrl: Boolean = false,
        val isImage: Boolean = false,
        val imageUri: Uri? = null,
        val items: List<ClipboardItem> = emptyList(),
    ) : IslandEvent(priority = 25, id = "clipboard")

    data class PromotedOngoing(
        val shortText: String = "",
        val title: String = "",
        val text: String = "",
        val appName: String = "",
        val appIcon: Drawable? = null,
        val sbn: StatusBarNotification,
        val actions: List<NotificationAction> = emptyList(),
        val progress: Float = -1f,
        val isIndeterminate: Boolean = false,
    ) : IslandEvent(priority = 72, id = "promoted_${sbn.key}") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Sports(
        val team1Name: String,
        val team2Name: String,
        val score1: String,
        val score2: String,
        val team1Icon: Drawable? = null,
        val team2Icon: Drawable? = null,
        val status: GameStatus = GameStatus.LIVE,
        val statusDetail: String = "",
        val league: String = "",
        val commentary: String = "",
        val key: String,
        val sbn: StatusBarNotification? = null,
        val appIcon: Drawable? = null,
    ) : IslandEvent(priority = 73, id = "sports_$key") {
        override fun withoutDrawables() = copy(team1Icon = null, team2Icon = null, appIcon = null)
    }

    enum class GameStatus { PRE_GAME, LIVE, HALFTIME, FINAL }

    data class NowPlaying(
        val songTitle: String,
        val artist: String,
        val key: String,
        val sbn: StatusBarNotification? = null,
        val appIcon: Drawable? = null,
        val actions: List<NotificationAction> = emptyList(),
    ) : IslandEvent(priority = 42, id = "now_playing") {
        override val behavior = EventBehavior(autoDismissMs = null)
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Timer(
        val label: String = "",
        val endTimeMs: Long = 0L,
        val originalDurationMs: Long = 0L,
        val appIcon: Drawable? = null,
        val isPaused: Boolean = false,
        val actions: List<NotificationAction> = emptyList(),
    ) : IslandEvent(priority = 45, id = "timer") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Stopwatch(
        val label: String = "",
        val startTimeMs: Long = System.currentTimeMillis(),
        val isRunning: Boolean = true,
        val appIcon: Drawable? = null,
        val actions: List<NotificationAction> = emptyList(),
    ) : IslandEvent(priority = 44, id = "stopwatch") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Alarm(
        val label: String = "",
        val triggerTimeMs: Long = 0L,
        val isRinging: Boolean = false,
        val appIcon: Drawable? = null,
    ) : IslandEvent(priority = 48, id = "alarm") {
        override val behavior = EventBehavior(autoDismissMs = 5000L)
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class AppSwitch(
        val recentApps: List<RecentApp> = emptyList(),
        val previousApp: RecentApp? = null,
    ) : IslandEvent(priority = 10, id = "app_switch") {
        override val behavior = EventBehavior(suppressOnDismiss = false, autoShowsIsland = false)
        override fun withoutDrawables() = copy(
            recentApps = recentApps.map { it.copy(appIcon = null) },
            previousApp = previousApp?.copy(appIcon = null),
        )
    }

    data class KeyguardIndication(
        val text: String,
        val indicationType: IndicationType,
    ) : IslandEvent(priority = 5, id = "kg_indication_${indicationType.name}") {
        override val behavior: EventBehavior
            get() = when (indicationType) {
                IndicationType.BIOMETRIC -> EventBehavior(autoDismissMs = 3500L, suppressOnDismiss = false)
                IndicationType.TRANSIENT -> EventBehavior(autoDismissMs = 3500L, suppressOnDismiss = false)
                IndicationType.TRUST -> EventBehavior(suppressOnDismiss = false)
                IndicationType.DISCLOSURE -> EventBehavior(suppressOnDismiss = false)
                IndicationType.OWNER_INFO -> EventBehavior(suppressOnDismiss = false)
                IndicationType.ALIGNMENT -> EventBehavior(autoDismissMs = 5000L, suppressOnDismiss = false)
                IndicationType.PERSISTENT_UNLOCK -> EventBehavior(suppressOnDismiss = false)
            }

        enum class IndicationType {
            BIOMETRIC,
            TRANSIENT,
            TRUST,
            DISCLOSURE,
            OWNER_INFO,
            ALIGNMENT,
            PERSISTENT_UNLOCK,
        }
    }

    data class RecentApp(
        val packageName: String,
        val appName: String,
        val appIcon: Drawable? = null,
        val taskId: Int = -1,
        val lastActiveTime: Long = System.currentTimeMillis(),
    )

    data class NotificationAction(val label: CharSequence, val action: Notification.Action)

    data class MediaCustomAction(
        val label: String,
        val action: String,
        val icon: Drawable? = null,
    )

    data class Notification(
        val sbn: StatusBarNotification,
        val title: String? = null,
        val text: String? = null,
        val appIcon: Drawable? = null,
        val appName: String = "",
        val progress: Int = -1,
        val progressMax: Int = 100,
        val isProgressIndeterminate: Boolean = false,
        val actions: List<NotificationAction> = emptyList(),
        val groupKey: String? = null,
        val isGroupSummary: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
    ) : IslandEvent(priority = NOTIFICATION_STALE_PRIORITY, id = "notification_${sbn.key}") {
        override val behavior = EventBehavior(autoDismissMs = null)
        override val priority: Int
            get() =
                if (System.currentTimeMillis() - createdAt < NOTIFICATION_DECAY_MS)
                    NOTIFICATION_FRESH_PRIORITY
                else NOTIFICATION_STALE_PRIORITY
    }

    companion object {
        internal val DEFAULT_BEHAVIOR = EventBehavior()

        val ONGOING_TYPES: Set<Class<out IslandEvent>> =
            setOf(
                AudioRecording::class.java,
                PromotedOngoing::class.java,
                Sports::class.java,
                Media::class.java,
                Timer::class.java,
                Stopwatch::class.java,
                AospChip::class.java,
            )
    }

    val isOngoing: Boolean
        get() = this::class.java in ONGOING_TYPES

    override fun compareTo(other: IslandEvent): Int = other.priority - this.priority
}

enum class IslandState {
    HIDDEN,
    CHIP,
}

internal fun priorityForAospChipKey(key: String): Int = when {
    key.startsWith("callChip-") -> 88
    key == "ScreenRecord" -> 90
    key == "ShareToApp" -> 86
    key == "CastToOtherDevice" -> 82
    else -> 70
}

