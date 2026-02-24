package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.res.R
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.axdynamicbar.shared.*
import kotlinx.coroutines.delay

@Composable
internal fun ScreenRecordExpanded(
    event: IslandEvent.ScreenRecording,
    interactor: IslandActions,
) {
    if (event.isCountdown) {
        ScreenRecordCountdownExpanded(event)
        return
    }
    var elapsedMs by remember(event.startTimeMs) {
        mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
    }
    LaunchedEffect(event.startTimeMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
        }
    }
    ExpandedCardLayout(
        accentColor = RedAccent,
        icon = { PulsingDot(color = RedAccent, size = 14.dp, durationMs = 550, minAlpha = AlphaTrack) },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_screen_recording), color = SubtleGray, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatElapsedTime(elapsedMs), color = RedAccent, style = MaterialTheme.typography.headlineMedium)
        },
        trailing = { StatusChip(stringResource(R.string.ax_dynamic_bar_recording), RedAccent) },
        actions = {
            ActionChip(
                label = stringResource(R.string.ax_dynamic_bar_stop_recording),
                icon = Icons.Filled.Stop,
                color = OnDestructiveText,
                bg = DestructiveBg,
                modifier = Modifier.fillMaxWidth(),
                onClick = { interactor.stopScreenRecording() },
            )
        },
    )
}

@Composable
private fun ScreenRecordCountdownExpanded(event: IslandEvent.ScreenRecording) {
    ExpandedCardLayout(
        accentColor = RedAccent,
        icon = { Icon(Icons.Filled.Videocam, null, tint = RedAccent, modifier = Modifier.size(22.dp)) },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_screen_recording), color = SubtleGray, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(event.countdownSeconds.toString(), color = RedAccent, style = MaterialTheme.typography.headlineMedium)
        },
    )
}

@Composable
internal fun AudioRecordingExpanded(
    event: IslandEvent.AudioRecording,
    interactor: IslandActions,
) {
    val context = LocalContext.current
    val style = eventStyleFor(event)
    val isSaved = event.state == RecordingState.SAVED

    var elapsedMs by remember(event.startTimeMs, event.pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(event.startTimeMs, event.state, event.pausedDurationMs) {
        if (event.state == RecordingState.RECORDING) {
            while (true) {
                delay(1000)
                elapsedMs =
                    (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                        .coerceAtLeast(0L)
            }
        }
    }

    ExpandedCardLayout(
        accentColor = style.accent,
        icon = {
            when (event.state) {
                RecordingState.RECORDING -> PulsingDot(color = style.accent, size = 14.dp, durationMs = 550, minAlpha = AlphaTrack)
                else -> style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(22.dp)) }
            }
        },
        title = {
            Text(event.appName.ifEmpty { stringResource(style.labelRes) }, color = SubtleGray, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isSaved) {
                Text(formatElapsedTime(elapsedMs), color = style.accent, style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(stringResource(style.labelRes), color = style.accent, style = MaterialTheme.typography.titleSmall)
            }
        },
        trailing = {
            when (event.state) {
                RecordingState.RECORDING -> StatusChip(stringResource(R.string.ax_dynamic_bar_recording), style.accent)
                RecordingState.PAUSED -> StatusChip(stringResource(R.string.ax_dynamic_bar_paused), SubtleGray)
                RecordingState.SAVED -> {}
            }
        },
        actions = if (event.actions.isNotEmpty()) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpaceLg),
                ) {
                    event.actions.forEach { notifAction ->
                        val lower = notifAction.label.toString().lowercase()
                        val btnColor =
                            when {
                                lower.contains("stop") || lower.contains("delete") -> DestructiveBg
                                lower.contains("resume") || lower.contains("play") -> GreenAccent
                                else -> ActionBg
                            }
                        val textColor =
                            when {
                                lower.contains("stop") || lower.contains("delete") -> OnDestructiveText
                                lower.contains("resume") || lower.contains("play") -> chipContentColorOn(GreenAccent)
                                else -> OnActionText
                            }
                        val btnIcon =
                            when {
                                lower.contains("stop") || lower.contains("delete") -> Icons.Filled.Stop
                                lower.contains("resume") || lower.contains("play") -> Icons.Filled.PlayArrow
                                lower.contains("pause") -> Icons.Filled.Pause
                                else -> null
                            }
                        ActionChip(
                            label = notifAction.label.toString(),
                            icon = btnIcon,
                            color = textColor,
                            bg = btnColor,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    notifAction.action.actionIntent?.sendWithBal(context)
                                } catch (_: Exception) {}
                                interactor.collapseIsland()
                            },
                        )
                    }
                }
            }
        } else null,
    )
}

@Composable
internal fun MicCamExpanded(event: IslandEvent.MicCamActive) {
    val accent = if (event.isCam) RedAccent else OrangeAccent
    ExpandedCardLayout(
        accentColor = accent,
        icon = {
            Row(horizontalArrangement = Arrangement.spacedBy(SpaceSm)) {
                if (event.isCam) Icon(Icons.Filled.Videocam, null, tint = RedAccent, modifier = Modifier.size(28.dp))
                if (event.isMic) Icon(Icons.Filled.Mic, null, tint = OrangeAccent, modifier = Modifier.size(28.dp))
            }
        },
        title = {
            Text(
                when {
                    event.isCam && event.isMic -> stringResource(R.string.ax_dynamic_bar_camera_mic)
                    event.isCam -> stringResource(R.string.ax_dynamic_bar_camera)
                    else -> stringResource(R.string.ax_dynamic_bar_microphone)
                },
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
            )
            if (event.apps.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    event.apps.take(4).forEach { app ->
                        app.appIcon?.let {
                            Image(
                                bitmap = it.toScaledBitmap(28.dp),
                                contentDescription = app.appName,
                                modifier = Modifier.size(28.dp).clip(ShapeIconMedium),
                            )
                        }
                    }
                }
                StatusChip(stringResource(R.string.ax_dynamic_bar_apps_count, event.apps.size), accent)
            } else if (event.appName.isNotEmpty()) {
                StatusChip(event.appName, accent)
            }
        },
        trailing = { PulsingDot(color = accent, size = SpaceMd) },
    )
}

@Composable
internal fun RowScope.CompactRecordingRow(event: IslandEvent.ScreenRecording) {
    if (event.isCountdown) {
        Box(
            modifier =
                Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(RedAccent.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Videocam, null, tint = RedAccent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(SpaceLg))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
            Text(stringResource(R.string.ax_dynamic_bar_screen_recording), color = OnCardText, style = MaterialTheme.typography.bodySmall)
            Text(event.countdownSeconds.toString(), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    var elapsedMs by remember(event.startTimeMs) {
        mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
    }
    LaunchedEffect(event.startTimeMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
        }
    }
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(RedAccent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        PulsingDot(color = RedAccent, size = 12.dp, durationMs = 550, minAlpha = AlphaTrack)
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(stringResource(R.string.ax_dynamic_bar_screen_recording), color = OnCardText, style = MaterialTheme.typography.bodySmall)
        Text(formatElapsedTime(elapsedMs), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun RowScope.CompactAudioRecordingRow(event: IslandEvent.AudioRecording) {
    val style = eventStyleFor(event)
    var elapsedMs by remember(event.startTimeMs, event.pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(event.startTimeMs, event.state, event.pausedDurationMs) {
        if (event.state == RecordingState.RECORDING) {
            while (true) {
                delay(1000)
                elapsedMs =
                    (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                        .coerceAtLeast(0L)
            }
        }
    }
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(style.accent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        when (event.state) {
            RecordingState.RECORDING -> PulsingDot(color = style.accent, size = 10.dp, durationMs = 550, minAlpha = AlphaTrack)
            RecordingState.PAUSED ->
                style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(16.dp)) }
            RecordingState.SAVED ->
                style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(16.dp)) }
        }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            when (event.state) {
                RecordingState.RECORDING -> stringResource(R.string.ax_dynamic_bar_recording)
                RecordingState.PAUSED -> stringResource(R.string.ax_dynamic_bar_paused)
                RecordingState.SAVED -> stringResource(R.string.ax_dynamic_bar_saved)
            },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
        )
        when (event.state) {
            RecordingState.RECORDING, RecordingState.PAUSED ->
                Text(formatElapsedTime(elapsedMs), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
            RecordingState.SAVED -> if (event.appName.isNotEmpty()) {
                Text(
                    event.appName,
                    color = SubtleGray,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun RowScope.CompactMicCamRow(event: IslandEvent.MicCamActive) {
    val color = if (event.isCam) RedAccent else OrangeAccent
    Box(
        modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(color.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (event.isCam) Icons.Filled.Videocam else Icons.Filled.Mic,
            null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            when {
                event.isCam && event.isMic -> stringResource(R.string.ax_dynamic_bar_camera_mic_short)
                event.isCam -> stringResource(R.string.ax_dynamic_bar_camera)
                else -> stringResource(R.string.ax_dynamic_bar_microphone)
            },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
        )
        if (event.apps.size > 1) {
            Text(stringResource(R.string.ax_dynamic_bar_apps_count, event.apps.size), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
        } else if (event.appName.isNotEmpty()) {
            Text(
                event.appName,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

