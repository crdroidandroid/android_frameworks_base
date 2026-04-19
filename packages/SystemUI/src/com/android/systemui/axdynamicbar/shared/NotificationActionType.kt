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

import android.app.Notification
import android.content.Context

enum class NotificationActionType {
    STOP,
    PAUSE,
    RESUME,
    LAP,
    RESET,
    DELETE,
    ADD_MINUTE,
    SNOOZE,
    DISMISS,
    OTHER,
}

fun Notification.Action.classify(context: Context, packageName: String): NotificationActionType {
    val intentAction = try { actionIntent?.intent?.action } catch (_: Exception) { null }
    when (intentAction) {
        "STOP" -> return NotificationActionType.STOP
        "PAUSE" -> return NotificationActionType.PAUSE
        "RESUME" -> return NotificationActionType.RESUME
        "com.android.deskclock.action.PAUSE_TIMER",
        "com.android.deskclock.action.PAUSE_STOPWATCH" -> return NotificationActionType.PAUSE
        "com.android.deskclock.action.START_TIMER",
        "com.android.deskclock.action.START_STOPWATCH" -> return NotificationActionType.RESUME
        "com.android.deskclock.action.RESET_TIMER",
        "com.android.deskclock.action.RESET_STOPWATCH" -> return NotificationActionType.RESET
        "com.android.deskclock.action.LAP_STOPWATCH" -> return NotificationActionType.LAP
        "com.android.deskclock.action.ADD_MINUTE_TIMER" -> return NotificationActionType.ADD_MINUTE
        "com.android.deskclock.ALARM_SNOOZE" -> return NotificationActionType.SNOOZE
        "com.android.deskclock.ALARM_DISMISS" -> return NotificationActionType.DISMISS
    }

    val iconResName: String? = run {
        val icon = getIcon() ?: return@run null
        val resId = try { icon.resId } catch (_: Exception) { 0 }
        if (resId == 0) return@run null
        try {
            context.packageManager.getResourcesForApplication(packageName)
                .getResourceEntryName(resId).lowercase()
        } catch (_: Exception) {
            null
        }
    }

    when {
        iconResName == null -> Unit
        iconResName.contains("stop") -> return NotificationActionType.STOP
        iconResName.contains("pause") -> return NotificationActionType.PAUSE
        iconResName.contains("play") -> return NotificationActionType.RESUME
        iconResName.contains("delete") || iconResName.contains("trash") -> return NotificationActionType.DELETE
        iconResName.contains("reset") -> return NotificationActionType.RESET
        iconResName.contains("lap") -> return NotificationActionType.LAP
    }

    val label = title?.toString()?.lowercase() ?: ""
    return when {
        label.contains("stop") -> NotificationActionType.STOP
        label.contains("delete") -> NotificationActionType.DELETE
        label.contains("pause") -> NotificationActionType.PAUSE
        label.contains("resume") || label.contains("play") -> NotificationActionType.RESUME
        label.contains("lap") -> NotificationActionType.LAP
        label.contains("reset") -> NotificationActionType.RESET
        label.contains("snooze") -> NotificationActionType.SNOOZE
        label.contains("dismiss") -> NotificationActionType.DISMISS
        else -> NotificationActionType.OTHER
    }
}
