package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.widget.SeekBar
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.res.R
import kotlinx.coroutines.delay

private val AlbumArtSize = 64.dp
private val PlayPauseSize = 48.dp
private val ControlButtonSize = 40.dp
private val ControlIconSize = 22.dp
private val SeekBarHeight = 24.dp

/**
 * The expanded media tile: art, track and the primary transport on one row, with a slim seek row
 * underneath. It draws its own frosted surface (it is routed around [PrimaryCard]).
 *
 * Custom actions (shuffle, like, …) are left to the keyguard media panel, which has room for them;
 * the status-bar tile keeps the controls people reach for from the top of the screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaCard(event: IslandEvent.Media, interactor: IslandActions) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent
    val motionScheme = MaterialTheme.motionScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeCard)
            .border(1.dp, CardBorderBrush, ShapeCard)
            .islandBlur(RadiusCard, CardBg)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SpaceXxl),
            verticalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                // Art + titles open the app; the transport to the right stays its own target.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeLg)
                        .clickable {
                            interactor.openMediaApp()
                            interactor.collapseIsland()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpaceLg),
                ) {
                    AnimatedContent(
                        targetState = event.albumArt,
                        transitionSpec = {
                            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                fadeOut(motionScheme.fastEffectsSpec())
                        },
                        contentKey = { it?.hashCode() ?: 0 },
                        label = "media_card_art",
                    ) { art ->
                        if (art != null) {
                            Image(
                                bitmap = art.toSquareScaledBitmap(AlbumArtSize),
                                contentDescription = null,
                                modifier = Modifier.size(AlbumArtSize).clip(ShapeLg),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(AlbumArtSize)
                                    .clip(ShapeLg)
                                    .background(accent.copy(alpha = AlphaFaint)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.MusicNote, null, tint = accent, modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SpaceXxs),
                    ) {
                        Text(
                            event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                            color = OnCardText,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (event.artist.isNotEmpty()) {
                            Text(
                                event.artist,
                                color = OnCardSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                MediaTransport(event, interactor, accent)
            }

            if (event.duration > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                ) {
                    MediaSeekBar(event, interactor, accent, Modifier.weight(1f))
                    OutputSwitcherButton(event, interactor, accent)
                }
            } else {
                // No duration (live stream, some podcasts apps): keep the output switcher reachable.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        event.outputDeviceName,
                        color = SubtleGray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OutputSwitcherButton(event, interactor, accent)
                }
            }
        }
    }
}

/**
 * Previous, play/pause, next — one treatment for all three: an accent wash with the accent as the
 * glyph. Play/pause leads with the stronger wash. (The old solid disc paired the tint with a
 * luminance-guessed content colour; washes keep the glyph legible on its own colour by design.)
 */
@Composable
private fun MediaTransport(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
) {
    val tonalBg = accent.copy(alpha = AlphaSubtle)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceXs),
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = stringResource(R.string.ax_dynamic_bar_previous),
            tint = accent,
            bg = tonalBg,
            size = ControlButtonSize,
            onClick = { interactor.skipPrev() },
        )
        TransportButton(
            icon = if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (event.isPlaying) R.string.ax_dynamic_bar_pause else R.string.ax_dynamic_bar_play
            ),
            tint = accent,
            bg = accent.copy(alpha = AlphaAccent),
            size = PlayPauseSize,
            onClick = { interactor.togglePlayPause() },
        )
        TransportButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = stringResource(R.string.ax_dynamic_bar_next),
            tint = accent,
            bg = tonalBg,
            size = ControlButtonSize,
            onClick = { interactor.skipNext() },
        )
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    bg: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = bg, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(ControlIconSize))
        }
    }
}

@Composable
private fun OutputSwitcherButton(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
) {
    ActionIconButton(
        icon = Icons.Filled.VolumeUp,
        tint = accent,
        bg = accent.copy(alpha = AlphaIconBg),
        contentDescription = event.outputDeviceName.ifBlank {
            stringResource(R.string.ax_dynamic_bar_output)
        },
        size = 32.dp,
        onClick = {
            interactor.openMediaOutputSwitcher()
            interactor.collapseIsland()
        },
    )
}

@Composable
private fun MediaSeekBar(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val mediaProgress = rememberMediaProgress(event)
    val isPlaying = event.isPlaying
    val durationMs = event.duration
    val positionMs = mediaProgress.positionMs
    val serverFraction = mediaProgress.progress

    var isScrubbing by remember { mutableStateOf(false) }
    var displayFraction by remember { mutableStateOf(serverFraction) }

    val interactorRef = rememberUpdatedState(interactor)

    // Read the dismiss swipe lock provided by MagneticSwipeToDismiss
    val swipeLock = LocalDismissSwipeLock.current

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

    val displayMs = (displayFraction * durationMs).toLong()
    val accentArgb = accent.toArgb()
    val trackAlphaArgb = accent.copy(alpha = AlphaSubtle).toArgb()

    // Times flank the bar on one line instead of taking a row of their own above it.
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
    ) {
        Text(
            formatElapsedTime(displayMs),
            color = SubtleGray,
            style = PillMono,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(SeekBarHeight)
                .pointerInput(swipeLock) {
                    awaitEachGesture {
                        awaitPointerEvent() // DOWN
                        swipeLock.value = true
                        try {
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        } finally {
                            swipeLock.value = false
                        }
                    }
                }
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

        Text(
            formatElapsedTime(durationMs),
            color = SubtleGray,
            style = PillMono,
        )
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
internal fun RowScope.CompactMediaRow(
    event: IslandEvent.Media,
    interactor: IslandActions,
) {
    event.albumArt?.let {
        Image(
            bitmap = it.toSquareScaledBitmap(SizeCompactIcon),
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
        color = rememberMediaColors(event).accent.copy(alpha = AlphaAccent),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                tint = rememberMediaColors(event).accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
