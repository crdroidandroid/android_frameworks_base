/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.app.Notification
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.ShapeXs
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.SpaceXxs
import com.android.systemui.axdynamicbar.shared.TsBadge
import com.android.systemui.axdynamicbar.shared.boundsOnScreen
import com.android.systemui.axdynamicbar.shared.rememberCenterCutoutHole
import com.android.systemui.axdynamicbar.shared.tint
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.axdynamicbar.shared.toSquareScaledBitmap
import com.android.systemui.axdynamicbar.shared.toneFor
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.launch

private val IslandMinHeight = 30.dp
private val IslandMaxHeight = 40.dp
private val IslandLensPadding = 10.dp
private val IslandMinGap = 34.dp
private val IslandSideMaxWidth = 60.dp
private val IslandSidePadding = 8.dp
private val IslandSpacing = 6.dp
private val IslandIconSize = 18.dp
private val IslandShape = RoundedCornerShape(percent = 50)
private val IslandContent = Color.White
private const val CALL_ACTIVE = "Phone:active"
private const val AOSP_CALL_PREFIX = "callChip-"
private const val AOSP_SCREEN_RECORD_KEY = "ScreenRecord"
private val IslandRinging = Color(0xFF34C759)
private val IslandCountdown = Color(0xFFFFC107)
private val IslandCharging = Color(0xFFFFD60A)
private val IslandRecording = Color(0xFFFF5252)

// The camera hole the bar sits over, or null when it stays a regular status bar chip.
@Composable
internal fun rememberIslandHole(viewModel: AxDynamicBarChipViewModel): Rect? {
    val enabled by viewModel.centerIsland.collectAsStateWithLifecycle()
    val hole = rememberCenterCutoutHole()
    return hole?.takeIf { enabled }
}

// Fills the status bar and centres the island on the camera hole; empty space passes touches on.
@Composable
fun AxDynamicBarIslandHost(viewModel: AxDynamicBarChipViewModel) {
    val hole = rememberIslandHole(viewModel)
    val active = hole != null
    DisposableEffect(active) {
        viewModel.setIslandActive(active)
        onDispose { viewModel.setIslandActive(false) }
    }
    if (hole == null) return
    var natural by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AxDynamicBarChip(
            viewModel = viewModel,
            island = hole,
            modifier = Modifier.onPlaced { natural = it.boundsInWindow() }
                .graphicsLayer {
                    val placed = natural ?: return@graphicsLayer
                    translationX = hole.exactCenterX() - placed.center.x
                    translationY = (hole.exactCenterY() - placed.center.y)
                        .coerceAtLeast(-placed.top)
                },
        )
    }
}

@Composable
internal fun IslandPill(
    event: IslandEvent,
    isAlert: Boolean,
    hole: Rect,
    progress: Float?,
    morphScale: () -> Float,
    morphAlpha: () -> Float,
    onPositioned: (androidx.compose.ui.geometry.Rect) -> Unit,
    onWidth: (Int) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val gap = with(density) { hole.width().toDp() } + IslandLensPadding * 2
    val height = (with(density) { hole.height().toDp() } + 8.dp)
        .coerceIn(IslandMinHeight, IslandMaxHeight)
    val accent = toneFor(event).tint

    CameraSplitRow(
        gap = gap.coerceAtLeast(IslandMinGap),
        modifier = Modifier
            .onGloballyPositioned { onPositioned(it.boundsOnScreen(view)) }
            .onSizeChanged { onWidth(it.width) }
            .graphicsLayer {
                scaleX = morphScale()
                alpha = morphAlpha()
            }
            .height(height)
            .clip(IslandShape)
            .background(Color.Black)
            .then(
                if (progress != null) {
                    Modifier.drawWithContent {
                        drawContent()
                        val barH = 2.dp.toPx()
                        val y = size.height - barH
                        drawRect(accent.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, barH))
                        drawRect(accent, Offset(0f, y), Size(size.width * progress, barH))
                    }
                } else Modifier
            )
            .padding(horizontal = IslandSidePadding),
        leading = { IslandLeading(event, isAlert, accent) },
        trailing = { IslandTrailing(event, isAlert, accent) },
    )
}

@Composable
private fun IslandLeading(event: IslandEvent, isAlert: Boolean, accent: Color) {
    when {
        isAlert && event is IslandEvent.Notification ->
            event.appIcon?.let {
                Image(
                    bitmap = it.toScaledBitmap(IslandIconSize),
                    contentDescription = null,
                    modifier = Modifier.size(IslandIconSize).clip(ShapeXs),
                    contentScale = ContentScale.Crop,
                )
            } ?: PillEventIcon(event, tint = accent, animated = false)
        event is IslandEvent.Media ->
            event.albumArt?.let {
                Image(
                    bitmap = it.toSquareScaledBitmap(IslandIconSize),
                    contentDescription = null,
                    modifier = Modifier.size(IslandIconSize).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } ?: Icon(Icons.Filled.MusicNote, null, tint = accent, modifier = Modifier.size(16.dp))
        event is IslandEvent.Call && event.callerPhoto != null ->
            Image(
                bitmap = event.callerPhoto.toSquareScaledBitmap(IslandIconSize),
                contentDescription = null,
                modifier = Modifier.size(IslandIconSize).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        event is IslandEvent.Sports && event.team2Name.isNotEmpty() ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IslandTeamBadge(event.team1Name, event.team1Icon)
                if (event.score1.isNotEmpty()) {
                    Spacer(Modifier.width(SpaceXs))
                    Text(event.score1, style = PillPrimary, color = IslandContent, maxLines = 1)
                }
            }
        event is IslandEvent.Charging ->
            IslandBatteryIcon(
                level = event.level,
                fill = if (event.isPowerSave) IslandCountdown else IslandRinging,
            )
        else -> PillEventIcon(event, tint = accent, animated = false)
    }
}

// A thick landscape battery: a filled body over a faint track, with the terminal on the right.
@Composable
private fun IslandBatteryIcon(level: Int, fill: Color) {
    val fraction = level.coerceIn(0, 100) / 100f
    Canvas(Modifier.size(width = 24.dp, height = 13.dp)) {
        val nubWidth = size.width * 0.08f
        val gap = size.width * 0.04f
        val bodyWidth = size.width - nubWidth - gap
        val radius = CornerRadius(size.height * 0.32f)
        val track = IslandContent.copy(alpha = 0.3f)
        drawRoundRect(track, size = Size(bodyWidth, size.height), cornerRadius = radius)
        clipRect(right = bodyWidth * fraction) {
            drawRoundRect(fill, size = Size(bodyWidth, size.height), cornerRadius = radius)
        }
        val nubHeight = size.height * 0.42f
        drawRoundRect(
            if (fraction >= 1f) fill else track,
            topLeft = Offset(bodyWidth + gap, (size.height - nubHeight) / 2f),
            size = Size(nubWidth, nubHeight),
            cornerRadius = CornerRadius(nubWidth / 2f),
        )
    }
}

@Composable
private fun IslandTrailing(event: IslandEvent, isAlert: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = Modifier.weight(1f, fill = false)
        when {
            isAlert && event is IslandEvent.Notification && event.isCallStyle() ->
                IslandCallAlert(event, label)
            event is IslandEvent.Notification ->
                IslandLabel(event.title?.takeIf { it.isNotBlank() } ?: event.appName, label)
            event is IslandEvent.Media ->
                if (event.isPlaying) AudioReactiveBars(color = IslandContent, artwork = event.albumArt)
            event is IslandEvent.Sports && event.team2Name.isNotEmpty() -> {
                if (event.score2.isNotEmpty()) {
                    Text(event.score2, style = PillPrimary, color = IslandContent, maxLines = 1)
                    Spacer(Modifier.width(SpaceXs))
                }
                IslandTeamBadge(event.team2Name, event.team2Icon)
            }
            event is IslandEvent.AppSwitch -> {
                Icon(Icons.Filled.Layers, null, tint = accent, modifier = Modifier.size(14.dp))
                if (event.recentApps.isNotEmpty()) {
                    Spacer(Modifier.width(SpaceXxs))
                    Text("${event.recentApps.size}", style = PillPrimary, color = IslandContent, maxLines = 1)
                }
            }
            event is IslandEvent.Call && event.callType != CALL_ACTIVE -> RingingCallIcon()
            event is IslandEvent.AospChip && event.active.key.startsWith(AOSP_CALL_PREFIX) &&
                event.active.content is OngoingActivityChipModel.Content.IconOnly -> RingingCallIcon()
            event is IslandEvent.AospChip && event.active.key == AOSP_SCREEN_RECORD_KEY ->
                PillEventText(
                    event,
                    label,
                    overrideColor =
                        if (event.active.content is OngoingActivityChipModel.Content.Countdown) {
                            IslandCountdown
                        } else {
                            IslandRecording
                        },
                )
            event is IslandEvent.NowPlaying -> IslandLabel(event.songTitle, label)
            event is IslandEvent.Bluetooth -> event.batterySummary()?.let { IslandLabel(it, label) }
            event is IslandEvent.Hotspot ->
                if (event.numDevices > 0) IslandLabel("${event.numDevices}", label)
            event is IslandEvent.Clipboard ->
                IslandLabel(stringResource(R.string.ax_dynamic_bar_copied), label)
            event is IslandEvent.Vpn -> Unit
            event is IslandEvent.BiometricUnlock -> UnlockingIcon()
            event is IslandEvent.Charging -> {
                ChargingBoltIcon()
                Spacer(Modifier.width(SpaceXxs))
                IslandLabel("${event.level}%", label)
            }
            event is IslandEvent.Alarm && event.label.isNotEmpty() -> IslandLabel(event.label, label)
            else -> PillEventText(event, label, overrideColor = IslandContent)
        }
    }
}

private fun IslandEvent.Notification.isCallStyle(): Boolean {
    val extras = sbn.notification?.extras ?: return false
    return extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
        extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
        extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
}

@Composable
private fun IslandCallAlert(event: IslandEvent.Notification, modifier: Modifier) {
    val extras = event.sbn.notification?.extras
    val incoming =
        extras?.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING ||
            extras?.containsKey(Notification.EXTRA_HANG_UP_INTENT) == false
    if (incoming) {
        RingingCallIcon()
        return
    }
    val start = event.sbn.notification?.`when`?.takeIf { it > 0L } ?: event.sbn.postTime
    val call = remember(event.sbn.key, start) {
        IslandEvent.Call(sbn = event.sbn, callType = CALL_ACTIVE, callStartTimeMs = start)
    }
    PillEventText(call, modifier, overrideColor = IslandContent)
}

@Composable
private fun RingingCallIcon() {
    val transition = rememberInfiniteTransition(label = "island_ring")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1400
                -15f at 70
                15f at 140
                -15f at 210
                15f at 280
                -10f at 350
                0f at 420
            }
        ),
        label = "island_ring_angle",
    )
    Icon(
        Icons.Filled.Call,
        null,
        tint = IslandRinging,
        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = angle.value },
    )
}

@Composable
private fun UnlockingIcon() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(400, delayMillis = 150)) }
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Lock,
            null,
            tint = IslandRinging,
            modifier = Modifier.size(16.dp).graphicsLayer { alpha = 1f - progress.value },
        )
        Icon(
            Icons.Filled.LockOpen,
            null,
            tint = IslandRinging,
            modifier = Modifier.size(16.dp).graphicsLayer {
                alpha = progress.value
                val bounce = 1f + 0.15f * sin(progress.value * PI.toFloat())
                scaleX = bounce
                scaleY = bounce
            },
        )
    }
}

// Strikes in with a bounce and a short flicker, then holds.
@Composable
private fun ChargingBoltIcon() {
    val strike = remember { Animatable(0f) }
    val flicker = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { strike.animateTo(1f, tween(420, delayMillis = 150)) }
        flicker.animateTo(
            1f,
            keyframes {
                durationMillis = 570
                0f at 150
                1f at 270
                0.35f at 340
                1f at 410
                0.6f at 470
                1f at 570
            },
        )
    }
    Icon(
        Icons.Filled.Bolt,
        null,
        tint = IslandCharging,
        modifier = Modifier.size(16.dp).graphicsLayer {
            val p = strike.value
            val scale = 0.6f + 0.4f * p + 0.2f * sin(p * PI.toFloat())
            scaleX = scale
            scaleY = scale
            alpha = flicker.value
        },
    )
}

@Composable
private fun IslandLabel(text: String, modifier: Modifier) {
    Text(
        text,
        style = PillPrimary,
        color = IslandContent,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.basicMarquee(iterations = 1),
    )
}

@Composable
private fun IslandTeamBadge(name: String, icon: android.graphics.drawable.Drawable?) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(16.dp),
            contentDescription = name,
            modifier = Modifier.size(16.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text(name.take(3).uppercase(), style = TsBadge, color = IslandContent, maxLines = 1)
    }
}

// Lays the leading side left of the lens and the trailing side right of it, keeping both
// sides equally wide so the island stays centred on the camera.
@Composable
private fun CameraSplitRow(
    gap: Dp,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(
            { Box(contentAlignment = Alignment.CenterStart) { leading() } },
            { Box(contentAlignment = Alignment.CenterEnd) { trailing() } },
        ),
        modifier = modifier,
    ) { (leadingItems, trailingItems), constraints ->
        val gapPx = gap.roundToPx()
        val sideMax = IslandSideMaxWidth.roundToPx()
            .coerceAtMost(((constraints.maxWidth - gapPx) / 2).coerceAtLeast(0))
        val side = Constraints(maxWidth = sideMax, maxHeight = constraints.maxHeight)
        val start = leadingItems.first().measure(side)
        val end = trailingItems.first().measure(side)
        val sideWidth = maxOf(start.width, end.width, IslandSpacing.roundToPx())
        val width = (sideWidth * 2 + gapPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = constraints.minHeight.coerceAtLeast(maxOf(start.height, end.height))
        layout(width, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(width - end.width, (height - end.height) / 2)
        }
    }
}
