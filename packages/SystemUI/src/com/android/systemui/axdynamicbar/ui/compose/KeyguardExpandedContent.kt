@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.res.R
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import kotlinx.coroutines.delay

@Composable
internal fun KeyguardExpandedContent(
    event: IslandEvent,
    allEvents: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    if (event is IslandEvent.KeyguardIndication || event is IslandEvent.AppSwitch) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCollapse() },
        contentAlignment = Alignment.Center,
    ) {
        when (event) {
            is IslandEvent.Media -> KeyguardMediaPanel(event, interactor)
            is IslandEvent.Timer -> KeyguardTimerPanel(event, interactor)
            is IslandEvent.Stopwatch -> KeyguardStopwatchPanel(event, interactor)
            is IslandEvent.ScreenRecording -> KeyguardRecordingPanel(event, interactor)
            is IslandEvent.AudioRecording -> KeyguardAudioRecordingPanel(event, interactor)
            else -> KeyguardGenericPanel(event, interactor, hapticsViewModelFactory)
        }
    }
}

@Composable
private fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = color.copy(alpha = AlphaFaint),
    strokeWidth: Dp = 10.dp,
    handleRadius: Dp = 8.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val handle = handleRadius.toPx()
        val pad = handle.coerceAtLeast(stroke / 2)
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        val topLeft = Offset(pad, pad)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            val sweep = 360f * progress
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val angleRad = Math.toRadians((-90.0 + sweep)).toFloat()
            val cx = size.width / 2 + (arcSize.width / 2) * cos(angleRad)
            val cy = size.height / 2 + (arcSize.height / 2) * sin(angleRad)
            drawCircle(color = color, radius = handle, center = Offset(cx, cy))
        }
    }
}

@Composable
private fun KeyguardPanelSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(max = ExpandedMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = SpaceSection)
            .clip(ShapeXl)
            .background(CardBg)
            .border(1.dp, CardBorderBrush, ShapeXl)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        content()
    }
}

@Composable
private fun TonalBanner(
    colors: IslandColorScheme,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeLg)
            .background(colors.tonal)
            .padding(horizontal = SpaceXxl, vertical = SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        content()
    }
}

@Composable
private fun KeyguardMediaPanel(event: IslandEvent.Media, interactor: IslandActions) {
    val colors = rememberMediaColors(event)

    val eqBars = if (event.isPlaying) {
        val eqTransition = rememberInfiniteTransition(label = "media_eq")
        (0 until 4).map { i ->
            eqTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500 + i * 100, easing = LinearEasing),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 130),
                ),
                label = "meq_$i",
            )
        }
    } else {
        null
    }

    KeyguardPanelSurface { Column(modifier = Modifier.fillMaxWidth()) {
        event.albumArt?.let { art ->
            Image(
                bitmap = art.toScaledBitmap(280.dp),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(SpaceLg)
                    .clip(ShapeLg),
                contentScale = ContentScale.Crop,
            )
        } ?: Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(SpaceLg)
                .clip(ShapeLg)
                .background(colors.tonal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = colors.accent.copy(AlphaDisabled), modifier = Modifier.size(72.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpaceSection),
            verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpaceXs),
                ) {
                    Text(
                        event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                        color = OnCardText,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (event.artist.isNotEmpty()) {
                        Text(
                            event.artist,
                            color = colors.accent,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(SpaceLg))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                ) {
                    Canvas(modifier = Modifier.width(SpaceSection).height(SpaceXxl)) {
                        val barW = 3.dp.toPx()
                        val gap = 2.dp.toPx()
                        val totalW = 4 * barW + 3 * gap
                        val startX = (size.width - totalW) / 2f
                        for (i in 0 until 4) {
                            val h = (eqBars?.get(i)?.value ?: 0.3f) * size.height
                            drawRoundRect(
                                color = colors.accent,
                                topLeft = Offset(startX + i * (barW + gap), size.height - h),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(barW / 2f),
                            )
                        }
                    }
                    event.appIcon?.let { icon ->
                        Image(
                            bitmap = icon.toScaledBitmap(SizeIconSm),
                            contentDescription = null,
                            modifier = Modifier.size(SizeIconSm).clip(ShapeXs),
                            colorFilter = ColorFilter.tint(OnCardText),
                        )
                    }
                }
            }

            if (event.outputDeviceName.isNotBlank()) {
                Surface(
                    onClick = {
                        interactor.openMediaOutputSwitcher()
                        interactor.collapseIsland()
                    },
                    shape = ShapeChip,
                    color = colors.tonal,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = SpaceXl, vertical = SpaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                    ) {
                        Icon(Icons.Filled.VolumeUp, null, tint = SubtleGray, modifier = Modifier.size(SpaceXl))
                        Text(event.outputDeviceName, color = SubtleGray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }

            if (event.duration > 0L) {
                val mediaProgress = rememberMediaProgress(event)
                var isSeeking by remember { mutableStateOf(false) }
                var seekProgress by remember { mutableFloatStateOf(mediaProgress.progress) }
                if (!isSeeking) seekProgress = mediaProgress.progress
                val displayMs =
                    if (isSeeking) (seekProgress * event.duration).toLong()
                    else mediaProgress.positionMs

                Column(verticalArrangement = Arrangement.spacedBy(SpaceSm)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SpaceSection)
                            .pointerInput("tap") {
                                detectTapGestures { offset ->
                                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    seekProgress = f
                                    interactor.seekTo((f * event.duration).toLong())
                                }
                            }
                            .pointerInput("drag") {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        isSeeking = true
                                        seekProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    },
                                    onDragEnd = {
                                        isSeeking = false
                                        interactor.seekTo((seekProgress * event.duration).toLong())
                                    },
                                    onDragCancel = { isSeeking = false },
                                    onHorizontalDrag = { change, _ ->
                                        seekProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        change.consume()
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        LinearWavyProgressIndicator(
                            progress = { seekProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                            trackColor = colors.accent.copy(alpha = AlphaSubtle),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatElapsedTime(displayMs), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
                        Text(formatElapsedTime(event.duration), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SpaceLg),
            shape = ShapeLg,
            color = colors.tonal,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpaceLg),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        event.customActions.firstOrNull()?.let {
                            interactor.sendCustomAction(it.action)
                        }
                    },
                    modifier = Modifier.size(SizeButtonLg),
                ) {
                    val ca = event.customActions.firstOrNull()
                    val caIcon = ca?.let { resolveCustomActionIcon(it.label) } ?: Icons.Filled.Shuffle
                    Icon(caIcon, ca?.label ?: stringResource(R.string.ax_dynamic_bar_shuffle), tint = SubtleGray, modifier = Modifier.size(SpacePanel))
                }

                IconButton(
                    onClick = { interactor.skipPrev() },
                    modifier = Modifier.size(SizeButtonLg),
                ) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.ax_dynamic_bar_previous), tint = OnCardText, modifier = Modifier.size(SizeIconMd))
                }

                Surface(
                    onClick = { interactor.togglePlayPause() },
                    modifier = Modifier.size(SizeButtonXl),
                    shape = CircleShape,
                    color = colors.accent,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (event.isPlaying) stringResource(R.string.ax_dynamic_bar_pause) else stringResource(R.string.ax_dynamic_bar_play),
                            tint = colors.onAccent,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                IconButton(
                    onClick = { interactor.skipNext() },
                    modifier = Modifier.size(SizeButtonLg),
                ) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.ax_dynamic_bar_next), tint = OnCardText, modifier = Modifier.size(SizeIconMd))
                }

                IconButton(
                    onClick = {
                        val ca = event.customActions.getOrNull(1)
                        if (ca != null) interactor.sendCustomAction(ca.action)
                        else {
                            interactor.openMediaOutputSwitcher()
                            interactor.collapseIsland()
                        }
                    },
                    modifier = Modifier.size(SizeButtonLg),
                ) {
                    val ca = event.customActions.getOrNull(1)
                    val endIcon = ca?.let { resolveEndActionIcon(it.label) } ?: Icons.Filled.VolumeUp
                    Icon(endIcon, ca?.label ?: stringResource(R.string.ax_dynamic_bar_output), tint = SubtleGray, modifier = Modifier.size(SpacePanel))
                }
            }
        }
    }
}
}

@Composable
private fun KeyguardTimerPanel(event: IslandEvent.Timer, interactor: IslandActions) {
    val context = LocalContext.current
    val colors = rememberIslandColors(event)
    var remainingMs by remember(event.endTimeMs) {
        mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    if (!event.isPaused) {
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
    }

    val elapsedFraction = if (event.originalDurationMs > 0L)
        (remainingMs.toFloat() / event.originalDurationMs).coerceIn(0f, 1f)
    else 0f

    val pulseTransition = rememberInfiniteTransition(label = "timer_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f, targetValue = if (remainingMs < 10_000L) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "timer_pulse_scale",
    )

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = AlphaSubtle)),
                contentAlignment = Alignment.Center,
            ) {
                eventStyleFor(event).icon?.let { Icon(it, null, tint = colors.accent, modifier = Modifier.size(18.dp)) }
            }
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(SizeAlbumLg)
                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale },
        ) {
            ProgressRing(
                progress = elapsedFraction,
                color = colors.accent,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                if (event.isPaused) stringResource(R.string.ax_dynamic_bar_paused) else formatCountdownLong(remainingMs),
                color = OnCardText,
                style = MaterialTheme.typography.displayMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            val toggleAction = event.actions.firstOrNull()
            if (toggleAction != null) {
                ExpressivePillButton(
                    label = if (event.isPaused) stringResource(R.string.ax_dynamic_bar_resume) else stringResource(R.string.ax_dynamic_bar_pause),
                    icon = if (event.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentColor = colors.onAccent,
                    backgroundColor = colors.accent,
                    modifier = Modifier.weight(1f),
                    onClick = { toggleAction.action.actionIntent?.sendWithBal(context) },
                )
            }
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                icon = Icons.Filled.Stop,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardStopwatchPanel(event: IslandEvent.Stopwatch, interactor: IslandActions) {
    val context = LocalContext.current
    val colors = rememberIslandColors(event)
    var elapsedMs by remember(event.startTimeMs) {
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

    val secFraction = if (event.isRunning) (elapsedMs % 60000) / 60000f else 0f

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = AlphaSubtle)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AvTimer, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_stopwatch) }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SizeAlbumLg)) {
            ProgressRing(
                progress = secFraction,
                color = colors.accent,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                if (event.isRunning) formatStopwatch(elapsedMs) else stringResource(R.string.ax_dynamic_bar_paused),
                color = OnCardText,
                style = MaterialTheme.typography.displayMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            val toggleAction = event.actions.firstOrNull()
            if (toggleAction != null) {
                ExpressivePillButton(
                    label = if (event.isRunning) stringResource(R.string.ax_dynamic_bar_pause) else stringResource(R.string.ax_dynamic_bar_resume),
                    icon = if (event.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentColor = colors.onAccent,
                    backgroundColor = colors.accent,
                    modifier = Modifier.weight(1f),
                    onClick = { toggleAction.action.actionIntent?.sendWithBal(context) },
                )
            }
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_reset),
                icon = Icons.Filled.Stop,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardRecordingPanel(event: IslandEvent.ScreenRecording, interactor: IslandActions) {
    val colors = rememberIslandColors(event)

    if (event.isCountdown) {
        KeyguardPanelSurface { Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpaceSection),
            verticalArrangement = Arrangement.spacedBy(SpaceXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TonalBanner(colors) {
                Icon(Icons.Filled.Videocam, null, tint = colors.accent, modifier = Modifier.size(SizeIconSm))
                Text(
                    stringResource(R.string.ax_dynamic_bar_screen_recording).uppercase(),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Text(
                event.countdownSeconds.toString(),
                color = OnCardText,
                style = MaterialTheme.typography.displayLarge,
            )

            Text(
                stringResource(R.string.ax_dynamic_bar_screen_recording),
                color = SubtleGray,
                style = MaterialTheme.typography.bodyMedium,
            )
        } }
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

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            PulsingDot(color = colors.accent, size = SpaceMd)
            Icon(Icons.Filled.Videocam, null, tint = colors.accent, modifier = Modifier.size(SizeIconSm))
            Text(
                stringResource(R.string.ax_dynamic_bar_screen_recording).uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Text(
            formatElapsedTime(elapsedMs),
            color = OnCardText,
            style = MaterialTheme.typography.displayLarge,
        )

        LinearWavyProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(ShapeChip),
            color = colors.accent,
            trackColor = colors.accent.copy(alpha = AlphaFaint),
        )

        ExpressivePillButton(
            label = stringResource(R.string.ax_dynamic_bar_stop),
            icon = Icons.Filled.Stop,
            contentColor = colors.onAccent,
            backgroundColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
            onClick = { interactor.stopScreenRecording() },
        )
    }
}
}

@Composable
private fun KeyguardAudioRecordingPanel(event: IslandEvent.AudioRecording, interactor: IslandActions) {
    val context = LocalContext.current
    val colors = rememberIslandColors(event)
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(event.startTimeMs, event.state, event.pausedDurationMs) {
        if (event.state == RecordingState.RECORDING) {
            while (true) {
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                    .coerceAtLeast(0L)
                delay(1000)
            }
        } else {
            elapsedMs = (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }

    val isRecording = event.state == RecordingState.RECORDING

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            if (isRecording) PulsingDot(color = colors.accent, size = SpaceMd)
            Icon(Icons.Filled.Mic, null, tint = colors.accent, modifier = Modifier.size(SizeIconSm))
            Text(
                when (event.state) {
                    RecordingState.RECORDING -> stringResource(R.string.ax_dynamic_bar_recording)
                    RecordingState.PAUSED -> stringResource(R.string.ax_dynamic_bar_paused)
                    RecordingState.SAVED -> stringResource(R.string.ax_dynamic_bar_saved)
                }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Text(
            formatElapsedTime(elapsedMs),
            color = OnCardText,
            style = MaterialTheme.typography.displayLarge,
        )

        if (isRecording) {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(ShapeChip),
                color = colors.accent,
                trackColor = colors.accent.copy(alpha = AlphaFaint),
            )
        } else {
            LinearWavyProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().clip(ShapeChip),
                color = colors.accent.copy(alpha = AlphaDisabled),
                trackColor = colors.accent.copy(alpha = AlphaFaint),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            val pauseResume = event.actions.firstOrNull()
            if (pauseResume != null) {
                ExpressivePillButton(
                    label = if (isRecording) stringResource(R.string.ax_dynamic_bar_pause) else stringResource(R.string.ax_dynamic_bar_resume),
                    icon = if (isRecording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentColor = colors.onAccent,
                    backgroundColor = colors.accent,
                    modifier = Modifier.weight(1f),
                    onClick = { pauseResume.action.actionIntent?.sendWithBal(context) },
                )
            }
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_stop),
                icon = Icons.Filled.Stop,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardGenericPanel(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    val colors = rememberIslandColors(event)
    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
    ) {
        ExpandedEventContent(event, interactor, hapticsViewModelFactory)

        ExpressivePillButton(
            label = stringResource(R.string.ax_dynamic_bar_dismiss),
            contentColor = colors.onAccent,
            backgroundColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
            onClick = { interactor.dismissEvent(event) },
        )
    }
}
}
