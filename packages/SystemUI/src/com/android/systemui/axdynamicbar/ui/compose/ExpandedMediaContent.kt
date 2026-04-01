package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.widget.SeekBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.res.R
import kotlinx.coroutines.delay

private val AlbumArtSize = 80.dp
private val PlayPauseSize = 56.dp
private val ControlButtonSize = 44.dp
private val ControlIconSize = 22.dp
private val SeekBarHeight = 28.dp

@Composable
internal fun MediaCard(event: IslandEvent.Media, interactor: IslandActions) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent

    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, CardBorderBrush, ShapeCard),
        shape = ShapeCard,
        color = CardBg,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        interactor.openMediaApp()
                        interactor.collapseIsland()
                    }
                    .padding(SpaceXxl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceXxl),
            ) {
                event.albumArt?.let { art ->
                    Image(
                        bitmap = art.toScaledBitmap(AlbumArtSize),
                        contentDescription = null,
                        modifier = Modifier.size(AlbumArtSize).clip(ShapeLg),
                        contentScale = ContentScale.Crop,
                    )
                } ?: Box(
                    modifier = Modifier
                        .size(AlbumArtSize)
                        .clip(ShapeLg)
                        .background(accent.copy(alpha = AlphaFaint)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MusicNote, null, tint = accent, modifier = Modifier.size(36.dp))
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpaceXs),
                ) {
                    Text(
                        event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                        color = OnCardText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (event.artist.isNotEmpty()) {
                        Text(
                            event.artist,
                            color = accent,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = AlphaFaint))
                    .padding(horizontal = SpaceXxl, vertical = SpaceLg),
                verticalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                if (event.duration > 0L) {
                    MediaSeekBar(event, interactor, accent)
                }
                MediaControls(event, interactor, accent)
            }
        }
    }
}

@Composable
internal fun MediaExpanded(
    event: IslandEvent.Media,
    interactor: IslandActions,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceXxl)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                interactor.openMediaApp()
                interactor.collapseIsland()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXxl),
        ) {
            event.albumArt?.let { art ->
                Image(
                    bitmap = art.toScaledBitmap(SizeAlbumSm),
                    contentDescription = null,
                    modifier = Modifier.size(SizeAlbumSm).clip(ShapeLg),
                    contentScale = ContentScale.Crop,
                )
            } ?: Surface(
                modifier = Modifier.size(SizeAlbumSm),
                shape = ShapeLg,
                color = accent.copy(alpha = AlphaSubtle),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.MusicNote, null,
                        tint = accent,
                        modifier = Modifier.size(SpacePanel),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXs),
            ) {
                Text(
                    event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.artist.isNotEmpty()) {
                    Text(
                        event.artist,
                        color = accent,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

        MediaControls(event, interactor, accent)
        if (event.duration > 0L) {
            MediaSeekBar(event, interactor, accent)
        }
    }
}

@Composable
private fun MediaControls(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
) {
    val onAccent = chipContentColorOn(accent)
    val tonalBg = accent.copy(alpha = AlphaSubtle)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaCustomActionButton(event, interactor, accent, tonalBg)

        Surface(
            onClick = { interactor.skipPrev() },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.SkipPrevious, null,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }

        Surface(
            onClick = { interactor.togglePlayPause() },
            shape = CircleShape,
            color = accent,
            modifier = Modifier.size(PlayPauseSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (event.isPlaying)
                        stringResource(R.string.ax_dynamic_bar_pause)
                    else
                        stringResource(R.string.ax_dynamic_bar_play),
                    tint = onAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Surface(
            onClick = { interactor.skipNext() },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.SkipNext, null,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }

        MediaEndActionButton(event, interactor, accent, tonalBg)
    }
}

@Composable
private fun MediaSeekBar(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
) {
    val mediaProgress = rememberMediaProgress(event)
    val isPlaying = event.isPlaying
    val durationMs = event.duration
    val positionMs = mediaProgress.positionMs
    val serverFraction = mediaProgress.progress

    var isScrubbing by remember { mutableStateOf(false) }
    var displayFraction by remember { mutableStateOf(serverFraction) }

    val interactorRef = rememberUpdatedState(interactor)

    // Smooth frame-interpolated progress when playing, snaps when paused or scrubbing
    LaunchedEffect(positionMs, durationMs, isPlaying) {
        if (isScrubbing) return@LaunchedEffect

        displayFraction = serverFraction

        if (!isPlaying || durationMs <= 0L) return@LaunchedEffect

        val startWallMs = System.currentTimeMillis()
        val startProgressMs = positionMs
        while (true) {
            delay(16L) // ~60 fps
            if (isScrubbing) break
            val elapsed = System.currentTimeMillis() - startWallMs
            val interpolated = ((startProgressMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
            displayFraction = interpolated
            if (interpolated >= 1f) break
        }
    }

    val displayMs = if (isScrubbing) (displayFraction * durationMs).toLong() else (displayFraction * durationMs).toLong()
    val accentArgb = accent.toArgb()
    val trackAlphaArgb = accent.copy(alpha = AlphaSubtle).toArgb()

    Column(verticalArrangement = Arrangement.spacedBy(SpaceXs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatElapsedTime(displayMs),
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                formatElapsedTime(durationMs),
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SeekBarHeight)
                .pointerInput("tap") {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        displayFraction = fraction
                        interactorRef.value.seekTo((fraction * durationMs).toLong())
                    }
                }
                .pointerInput("drag") {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isScrubbing = true
                            displayFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            interactorRef.value.seekTo((displayFraction * durationMs).toLong())
                            isScrubbing = false
                        },
                        onDragCancel = { isScrubbing = false },
                        onHorizontalDrag = { change, _ ->
                            displayFraction =
                                (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            change.consume()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    SeekBar(context).apply {
                        max = 10_000
                        splitTrack = false
                        setPadding(0, 0, 0, 0)
                        // Disable direct touch — Compose handles all gestures above
                        isEnabled = false

                        // Pill-shaped thumb
                        thumb = createSeekBarThumb(context, accentArgb)
                        thumbOffset = thumb.intrinsicWidth / 2

                        // Set up SquigglyProgress on the progress layer
                        val layer = (progressDrawable?.mutate() as? LayerDrawable)
                        if (layer != null) {
                            layer.findDrawableByLayerId(android.R.id.background)
                                ?.mutate()?.setTint(trackAlphaArgb)

                            layer.findDrawableByLayerId(android.R.id.secondaryProgress)
                                ?.mutate()?.setTint(
                                    com.android.internal.graphics.ColorUtils
                                        .setAlphaComponent(accentArgb, 60)
                                )

                            val squiggle = SquigglyProgress().apply {
                                waveLength = context.resources.getDimensionPixelSize(
                                    R.dimen.qs_media_seekbar_progress_wavelength
                                ).toFloat()
                                lineAmplitude = context.resources.getDimensionPixelSize(
                                    R.dimen.qs_media_seekbar_progress_amplitude
                                ).toFloat()
                                phaseSpeed = context.resources.getDimensionPixelSize(
                                    R.dimen.qs_media_seekbar_progress_phase
                                ).toFloat()
                                strokeWidth = context.resources.getDimensionPixelSize(
                                    R.dimen.qs_media_seekbar_progress_stroke_width
                                ).toFloat()
                                setTint(accentArgb)
                                drawRemainingLine = false
                                transitionEnabled = false
                                animate = false
                            }
                            layer.setDrawableByLayerId(android.R.id.progress, squiggle)
                            progressDrawable = layer
                        }
                    }
                },
                update = { bar ->
                    val target = (displayFraction * 10_000f).toInt().coerceIn(0, 10_000)
                    bar.progress = target

                    // Re-tint thumb for accent color changes (e.g. track switch)
                    (bar.thumb as? GradientDrawable)?.setColor(accentArgb)

                    val alpha = if (isPlaying) 255 else (255 * 0.55f).toInt()
                    bar.thumb?.alpha = alpha

                    val layer = bar.progressDrawable as? LayerDrawable

                    // Re-tint track colors
                    layer?.findDrawableByLayerId(android.R.id.background)
                        ?.setTint(trackAlphaArgb)
                    layer?.findDrawableByLayerId(android.R.id.secondaryProgress)
                        ?.setTint(
                            com.android.internal.graphics.ColorUtils
                                .setAlphaComponent(accentArgb, 60)
                        )

                    val squiggle = layer
                        ?.findDrawableByLayerId(android.R.id.progress) as? SquigglyProgress

                    squiggle?.apply {
                        setTint(accentArgb)
                        setAlpha(alpha)
                        animate = isPlaying && !isScrubbing
                    }

                    layer?.alpha = alpha
                },
                modifier = Modifier.fillMaxWidth().height(SeekBarHeight),
            )
        }
    }
}

/**
 * Creates a pill-shaped thumb drawable for the seekbar.
 */
private fun createSeekBarThumb(context: android.content.Context, tintColor: Int): GradientDrawable {
    val wPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
    val hPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
    val radiusPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
    )
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setSize(wPx, hPx)
        cornerRadius = radiusPx
        setColor(tintColor)
    }
}

@Composable
private fun MediaCustomActionButton(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    tonalBg: Color,
) {
    if (event.customActions.isNotEmpty()) {
        val ca = event.customActions.first()
        Surface(
            onClick = { interactor.sendCustomAction(ca.action) },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CustomActionIcon(ca, tint = accent, modifier = Modifier.size(ControlIconSize))
            }
        }
    } else {
        Surface(
            onClick = { },
            shape = CircleShape,
            color = tonalBg.copy(alpha = AlphaSubtle),
            modifier = Modifier.size(ControlButtonSize),
            enabled = false,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Shuffle, null,
                    tint = accent.copy(alpha = AlphaDisabled),
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }
    }
}

@Composable
private fun MediaEndActionButton(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    tonalBg: Color,
) {
    if (event.customActions.size > 1) {
        val ca = event.customActions[1]
        Surface(
            onClick = { interactor.sendCustomAction(ca.action) },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CustomActionIcon(ca, tint = accent, modifier = Modifier.size(ControlIconSize))
            }
        }
    } else {
        Surface(
            onClick = {
                interactor.openMediaOutputSwitcher()
                interactor.collapseIsland()
            },
            shape = CircleShape,
            color = tonalBg,
            modifier = Modifier.size(ControlButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.VolumeUp, null,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
            }
        }
    }
}

@Composable
internal fun RowScope.CompactMediaRow(
    event: IslandEvent.Media,
    interactor: IslandActions,
) {
    event.albumArt?.let {
        Image(
            bitmap = it.toScaledBitmap(SizeCompactIcon),
            null,
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact),
            contentScale = ContentScale.Crop,
        )
    } ?: run {
        val accent = rememberMediaColors(event).accent
        Box(
            modifier = Modifier.size(SizeCompactIcon)
                .clip(ShapeCompact)
                .background(accent.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = accent, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (event.artist.isNotEmpty())
            Text(
                event.artist,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
    Spacer(Modifier.width(SpaceMd))
    Surface(
        onClick = { interactor.togglePlayPause() },
        shape = CircleShape,
        color = ActionBg,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                tint = OnActionText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
