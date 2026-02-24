@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

private val AlbumArtSize = 80.dp
private val PlayPauseSize = 56.dp
private val ControlButtonSize = 44.dp
private val ControlIconSize = 22.dp

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
    val clamped = mediaProgress.progress
    var isSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(clamped) }
    if (!isSeeking) seekProgress = clamped
    val displayMs =
        if (isSeeking) (seekProgress * event.duration).toLong()
        else mediaProgress.positionMs

    Column(verticalArrangement = Arrangement.spacedBy(SpaceXs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatElapsedTime(displayMs), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
            Text(formatElapsedTime(event.duration), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SizeSeekHeight)
                .pointerInput("tap") {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        seekProgress = fraction
                        interactor.seekTo((fraction * event.duration).toLong())
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
                            seekProgress =
                                (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            change.consume()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { seekProgress },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                trackColor = accent.copy(alpha = AlphaSubtle),
            )
        }
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
                Icon(
                    resolveCustomActionIcon(ca.label), ca.label,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
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
                Icon(
                    resolveEndActionIcon(ca.label), ca.label,
                    tint = accent,
                    modifier = Modifier.size(ControlIconSize),
                )
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
