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

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R
import kotlinx.coroutines.delay

@Composable
internal fun TimerExpanded(event: IslandEvent.Timer, interactor: IslandActions) {
    val context = LocalContext.current
    val style = eventStyleFor(event)
    val totalMs = event.originalDurationMs.takeIf { it > 0L } ?: 1L
    var remainingMs by
        remember(event.endTimeMs) {
            mutableLongStateOf(
                if (event.endTimeMs > 0L)
                    (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                else 0L
            )
        }
    if (!event.isPaused) {
        LaunchedEffect(event.endTimeMs) {
            if (event.endTimeMs > 0L) {
                while (remainingMs > 0L) {
                    delay(500)
                    remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                }
            }
        }
    }
    val progress = if (event.endTimeMs > 0L) remainingMs.toFloat() / totalMs else 0f

    ExpandedCardLayout(
        accentColor = style.accent,
        icon = {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(44.dp)) {
                    drawArc(
                        color = style.accent.copy(alpha = AlphaSubtle),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                    )
                    drawArc(
                        color = style.accent,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                    )
                }
                style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(22.dp)) }
            }
        },
        title = {
            Text(event.label.ifEmpty { stringResource(style.labelRes) }, color = SubtleGray, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (event.endTimeMs > 0L) {
                Text(
                    if (event.isPaused) stringResource(R.string.ax_dynamic_bar_paused) else formatCountdownLong(remainingMs),
                    color = if (event.isPaused) SubtleGray else style.accent,
                    style = MaterialTheme.typography.headlineMedium,
                )
            } else {
                Text(
                    stringResource(if (event.isPaused) R.string.ax_dynamic_bar_paused else R.string.ax_dynamic_bar_running),
                    color = if (event.isPaused) SubtleGray else style.accent,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        trailing = { if (event.isPaused) StatusChip(stringResource(R.string.ax_dynamic_bar_paused), SubtleGray) },
        actions = {
            if (event.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                ) {
                    event.actions.forEach { notifAction ->
                        ActionChip(
                            label = notifAction.label.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    notifAction.action.actionIntent?.sendWithBal(context)
                                } catch (_: Exception) {}
                            },
                        )
                    }
                }
            } else {
                ActionChip(
                    label = stringResource(R.string.ax_dynamic_bar_dismiss),
                    icon = Icons.Filled.Close,
                    color = style.accent,
                    bg = style.accent.copy(alpha = AlphaIconBg),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { interactor.dismissEvent(event) },
                )
            }
        },
    )
}

@Composable
internal fun StopwatchExpanded(event: IslandEvent.Stopwatch, interactor: IslandActions) {
    val context = LocalContext.current
    val style = eventStyleFor(event)
    var elapsedMs by
        remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
    if (event.isRunning) {
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(100)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
    }

    ExpandedCardLayout(
        accentColor = style.accent,
        icon = {
            if (event.isRunning) PulsingDot(color = style.accent, size = 22.dp)
            else style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(22.dp)) }
        },
        title = {
            Text(event.label.ifEmpty { stringResource(style.labelRes) }, color = SubtleGray, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (event.isRunning) formatStopwatch(elapsedMs) else stringResource(R.string.ax_dynamic_bar_paused),
                color = if (event.isRunning) style.accent else SubtleGray,
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        trailing = { if (!event.isRunning) StatusChip(stringResource(R.string.ax_dynamic_bar_paused), SubtleGray) },
        actions = {
            if (event.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                ) {
                    event.actions.forEach { notifAction ->
                        ActionChip(
                            label = notifAction.label.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    notifAction.action.actionIntent?.sendWithBal(context)
                                } catch (_: Exception) {}
                            },
                        )
                    }
                }
            } else {
                ActionChip(
                    label = stringResource(R.string.ax_dynamic_bar_dismiss),
                    icon = Icons.Filled.Close,
                    color = style.accent,
                    bg = style.accent.copy(alpha = AlphaIconBg),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { interactor.dismissEvent(event) },
                )
            }
        },
    )
}

@Composable
internal fun RowScope.CompactTimerRow(event: IslandEvent.Timer) {
    var remainingMs by
        remember(event.endTimeMs) {
            mutableLongStateOf(
                if (event.endTimeMs > 0L)
                    (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                else 0L
            )
        }
    if (!event.isPaused) {
        LaunchedEffect(event.endTimeMs) {
            if (event.endTimeMs > 0L) {
                while (remainingMs > 0L) {
                    delay(500)
                    remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                }
            }
        }
    }
    val style = eventStyleFor(event)
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(style.accent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(18.dp)) }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        if (event.label.isNotEmpty())
            Text(
                event.label,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        if (event.endTimeMs > 0L) {
            Text(
                if (event.isPaused) stringResource(R.string.ax_dynamic_bar_paused) else formatCountdownLong(remainingMs),
                color = if (event.isPaused) SubtleGray else style.accent,
                style = TsMono.copy(fontSize = 13.sp),
            )
        } else {
            Text(stringResource(if (event.isPaused) R.string.ax_dynamic_bar_paused else R.string.ax_dynamic_bar_running), color = style.accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun RowScope.CompactStopwatchRow(event: IslandEvent.Stopwatch) {
    var elapsedMs by
        remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
    if (event.isRunning) {
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
    }
    val style = eventStyleFor(event)
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(style.accent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        if (event.isRunning) PulsingDot(color = style.accent, size = 18.dp)
        else style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(18.dp)) }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.label.ifEmpty { stringResource(style.labelRes) },
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (event.isRunning) formatStopwatch(elapsedMs) else stringResource(R.string.ax_dynamic_bar_paused),
            color = if (event.isRunning) style.accent else SubtleGray,
            style = TsMono.copy(fontSize = 13.sp),
        )
    }
}
