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
                        val pkg = notifAction.action.actionIntent?.creatorPackage ?: context.packageName
                        val kind = notifAction.action.classify(context, pkg)
                        val btnColor =
                            when (kind) {
                                NotificationActionType.STOP, NotificationActionType.DELETE -> DestructiveBg
                                NotificationActionType.RESUME -> GreenAccent
                                else -> ActionBg
                            }
                        val textColor =
                            when (kind) {
                                NotificationActionType.STOP, NotificationActionType.DELETE -> OnDestructiveText
                                NotificationActionType.RESUME -> chipContentColorOn(GreenAccent)
                                else -> OnActionText
                            }
                        val btnIcon =
                            when (kind) {
                                NotificationActionType.STOP, NotificationActionType.DELETE -> Icons.Filled.Stop
                                NotificationActionType.RESUME -> Icons.Filled.PlayArrow
                                NotificationActionType.PAUSE -> Icons.Filled.Pause
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

