@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.abs
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.res.R
import kotlin.math.abs
import kotlinx.coroutines.delay
import android.content.Context
import android.graphics.drawable.Drawable
import java.util.Calendar

private val ChipHeight = 36.dp
private val ChipShape = ShapeChip
private val ChipIconSize = ChipHeight - SpaceLg
private val ActionSize = SpacePanel
private val ActionIconSize = SizeBadge
private val BatteryIconSize = ChipHeight - SpaceXxl
private val CountBadgeHeight = ChipHeight / 2

@Composable
fun AxDynamicBarKeyguardChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isKeyguardEnabled by viewModel.isKeyguardEnabled.collectAsStateWithLifecycle()
    val batteryInfo by viewModel.keyguardBatteryInfo.collectAsStateWithLifecycle()
    val isKeyguardExpanded by viewModel.isKeyguardExpanded.collectAsStateWithLifecycle()
    val touchSlop = LocalViewConfiguration.current.touchSlop

    val motionScheme = MaterialTheme.motionScheme

    Box(modifier = modifier) {

        AnimatedVisibility(
            visible = isKeyguardExpanded && state != null,
            enter = fadeIn(motionScheme.defaultEffectsSpec()) +
                slideInVertically(
                    animationSpec = motionScheme.defaultSpatialSpec(),
                    initialOffsetY = { it / 3 },
                ) +
                expandVertically(
                    animationSpec = motionScheme.defaultSpatialSpec(),
                    expandFrom = Alignment.Bottom,
                ),
            exit = fadeOut(motionScheme.fastEffectsSpec()) +
                slideOutVertically(
                    animationSpec = motionScheme.fastSpatialSpec(),
                    targetOffsetY = { it / 4 },
                ) +
                shrinkVertically(
                    animationSpec = motionScheme.fastSpatialSpec(),
                    shrinkTowards = Alignment.Bottom,
                ),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(bottom = ChipHeight + SpaceLg),
        ) {
            state?.let {
                KeyguardExpandedContent(
                    event = it.event,
                    allEvents = it.allEvents,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.collapsePanel() },
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }

        AnimatedVisibility(
            visible = isOnKeyguard && isEnabled && isKeyguardEnabled,
            enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.9f, animationSpec = motionScheme.defaultSpatialSpec()),
            exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .pointerInput(viewModel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startX = down.position.x
                        var dragging = false
                        var totalDx = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    change.consume()
                                    if (totalDx > 0) viewModel.cyclePrev()
                                    else viewModel.cycleNext()
                                }
                                break
                            }
                            val dx = change.position.x - startX
                            if (!dragging && abs(dx) > touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                totalDx = dx
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            val chipState = state
            if (chipState != null) {
                val displayEvent = chipState.notificationAlert ?: chipState.event

                AnimatedContent(
                    targetState = displayEvent,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
                            initialScale = 0.95f,
                            animationSpec = motionScheme.defaultSpatialSpec(),
                        )) togetherWith (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                            targetScale = 0.95f,
                            animationSpec = motionScheme.fastSpatialSpec(),
                        )) using SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { it::class.simpleName },
                    label = "keyguard_chip_event",
                ) { event ->
                    val rawAccent = chipAccentColorFor(event)
                    val accent by animateColorAsState(
                        rawAccent,
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_accent",
                    )
                    val contentColor by animateColorAsState(
                        chipContentColorOn(rawAccent),
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_content",
                    )
                    val rawProgress = chipProgressFor(event)
                    val progressTarget = rawProgress ?: 0f
                    val progressAnim = remember { Animatable(progressTarget) }
                    LaunchedEffect(progressTarget) {
                        if (abs(progressTarget - progressAnim.value) > 0.05f) {
                            progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
                        } else {
                            progressAnim.snapTo(progressTarget)
                        }
                    }
                    val progress = if (rawProgress != null) progressAnim.value else null

                    KeyguardChipBody(
                        event = event,
                        accent = accent,
                        contentColor = contentColor,
                        progress = progress,
                        eventCount = chipState.eventCount,
                        viewModel = viewModel,
                    )
                }
            } else {
                
                KeyguardBatteryChip(batteryInfo)
            }
        }
    }
}

@Composable
private fun KeyguardChipBody(
    event: IslandEvent,
    accent: Color,
    contentColor: Color,
    progress: Float?,
    eventCount: Int,
    viewModel: AxDynamicBarChipViewModel,
) {
    val context = LocalContext.current
    val motionScheme = MaterialTheme.motionScheme

    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(ChipHeight)
                .widthIn(max = 260.dp)
                .clip(ChipShape)
                .background(accent)
                .animateContentSize(motionScheme.defaultSpatialSpec())
                .then(
                    if (progress != null) {
                        val trackColor = lerp(accent, contentColor, 0.2f)
                        val fillColor = lerp(accent, contentColor, 0.6f)
                        Modifier.drawWithContent {
                            drawContent()
                            val barH = SizeStrokeWidth.toPx()
                            val y = size.height - barH
                            drawRect(trackColor, Offset(0f, y), Size(size.width, barH))
                            drawRect(fillColor, Offset(0f, y), Size(size.width * progress, barH))
                        }
                    } else Modifier
                )
                .clickable {
                    when (event) {
                        is IslandEvent.Notification ->
                            viewModel.launchNotificationFromKeyguard(event)

                        is IslandEvent.KeyguardIndication,
                        is IslandEvent.AppSwitch -> { }
                        else -> viewModel.togglePanel()
                    }
                }
                .padding(start = SpaceSm, end = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (event is IslandEvent.Media) {
                AnimatedContent(
                    targetState = event.albumArt,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false)
                    },
                    contentKey = { it?.hashCode() ?: 0 },
                    label = "kg_media_icon",
                ) { art ->
                    if (art != null) {
                        Image(
                            bitmap = art.toScaledBitmap(ChipIconSize),
                            contentDescription = null,
                            modifier = Modifier
                                .size(ChipIconSize)
                                .clip(ShapeXs),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        PillEventIcon(event, tint = contentColor)
                    }
                }
                Spacer(Modifier.width(SpaceXs))
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { "${it.track}|${it.artist}" },
                    label = "kg_media_text",
                    modifier = Modifier.weight(1f, fill = false),
                ) { ev ->
                    if (ev.artist.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                                style = PillPrimary,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 90.dp).basicMarquee(iterations = 1),
                            )
                            Text(
                                " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaHint),
                            )
                            Text(
                                ev.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 60.dp),
                            )
                        }
                    } else {
                        MarqueeText(ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) }, contentColor, Modifier)
                    }
                }
                Spacer(Modifier.width(SpaceXs))
                ActionButton(
                    icon = ActionIcon.SKIP_PREV,
                    color = contentColor,
                    bgColor = lerp(accent, contentColor, AlphaSubtle),
                    onClick = { viewModel.skipPrev() },
                    size = ActionSize,
                    iconSize = ActionIconSize,
                )
                Spacer(Modifier.width(SpaceXxs))
                Surface(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(ActionSize),
                    shape = CircleShape,
                    color = lerp(accent, contentColor, AlphaSubtle),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ActionSize)) {
                        Icon(
                            if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (event.isPlaying) R.string.ax_dynamic_bar_pause
                                else R.string.ax_dynamic_bar_play,
                            ),
                            tint = contentColor,
                            modifier = Modifier.size(ActionIconSize),
                        )
                    }
                }
                Spacer(Modifier.width(SpaceXxs))
                ActionButton(
                    icon = ActionIcon.SKIP_NEXT,
                    color = contentColor,
                    bgColor = lerp(accent, contentColor, AlphaSubtle),
                    onClick = { viewModel.skipNext() },
                    size = ActionSize,
                    iconSize = ActionIconSize,
                )
            } else if (event is IslandEvent.Sports && event.team2Name.isNotEmpty()) {
                SportsChipTeamBadge(event.team1Name, event.team1Icon, contentColor)
                Spacer(Modifier.width(SpaceXs))
                Text(
                    if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
                        else stringResource(R.string.ax_dynamic_bar_sports_vs),
                    style = PillPrimary,
                    color = contentColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(SpaceXs))
                SportsChipTeamBadge(event.team2Name, event.team2Icon, contentColor)
            } else {
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { iconKeyFor(it) },
                    label = "kg_chip_icon",
                ) { ev ->
                    PillEventIcon(ev, tint = contentColor)
                }
                Spacer(Modifier.width(SpaceXs))
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { textKeyFor(it) },
                    label = "kg_chip_text",
                    modifier = Modifier.weight(1f, fill = false),
                ) { ev ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeyguardPrimaryText(ev, contentColor, Modifier.weight(1f, fill = false))
                        val secondary = secondaryTextFor(ev)
                        if (secondary != null) {
                            Text(
                                " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaTertiary),
                            )
                            Text(
                                secondary,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 80.dp),
                            )
                        }
                    }
                }
            }

            if (event !is IslandEvent.Media) {
                val actions = actionsFor(event)
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.width(SpaceXs))
                    actions.forEach { action ->
                        Spacer(Modifier.width(SpaceXxs))
                        ActionButton(
                            icon = action.icon,
                            color = contentColor,
                            bgColor = lerp(accent, contentColor, AlphaSubtle),
                            onClick = { action.perform(viewModel, event, context) },
                            size = ActionSize,
                            iconSize = ActionIconSize,
                        )
                    }
                }
            }

            if (eventCount > 1) {
                Spacer(Modifier.width(SpaceXs))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(CountBadgeHeight)
                        .widthIn(min = CountBadgeHeight)
                        .background(lerp(accent, contentColor, AlphaDisabled), ShapeChip)
                        .padding(horizontal = SpaceXxs),
                ) {
                    Text(
                        "$eventCount",
                        style = TsBadge,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyguardBatteryChip(info: KeyguardBatteryInfo) {
    val accent = when {
        info.isCharging -> BatteryChargingColor
        info.isPowerSave -> BatteryPowerSaveColor
        else -> BatteryNeutralColor
    }
    val contentColor = chipContentColorOn(accent)

    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(ChipHeight)
                .clip(ChipShape)
                .background(accent)
                .padding(horizontal = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            
            if (info.isCharging) {
                AnimatedChargingBoltIcon(contentColor, BatteryIconSize)
            } else {
                AnimatedBatteryFillIcon(info.level, contentColor, BatteryIconSize)
            }
            Spacer(Modifier.width(SpaceXs))
            Text(
                "${info.level}%",
                style = PillPrimary,
                color = contentColor,
                maxLines = 1,
            )

            val secondaryLabel = when {
                info.isCharging && info.isWireless -> stringResource(R.string.ax_dynamic_bar_wireless)
                info.isCharging -> stringResource(R.string.ax_dynamic_bar_charging)
                info.isPowerSave -> stringResource(R.string.ax_dynamic_bar_saver)
                else -> null
            }
            if (secondaryLabel != null) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTertiary),
                )
                Text(
                    secondaryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaSecondary),
                    maxLines = 1,
                )
            }

            val timeRemaining = info.timeRemaining
            if (timeRemaining != null) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTertiary),
                )
                Text(
                    timeRemaining,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaSecondary),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AnimatedChargingBoltIcon(color: Color, iconSize: Dp = BatteryIconSize) {
    val transition = rememberInfiniteTransition(label = "kg_bolt")
    val glow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "kg_bolt_glow",
    )
    val boltPath = remember { Path() }
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        boltPath.rewind()
        boltPath.moveTo(w * 0.55f, h * 0.05f)
        boltPath.lineTo(w * 0.25f, h * 0.50f)
        boltPath.lineTo(w * 0.45f, h * 0.50f)
        boltPath.lineTo(w * 0.40f, h * 0.95f)
        boltPath.lineTo(w * 0.75f, h * 0.42f)
        boltPath.lineTo(w * 0.55f, h * 0.42f)
        boltPath.close()
        drawPath(boltPath, color.copy(alpha = glow))
    }
}

@Composable
private fun AnimatedBatteryFillIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize) {
    val fillFraction by animateFloatAsState(
        targetValue = (level / 100f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "kg_battery_fill",
    )
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.7f
        val bodyH = h * 0.85f
        val bodyX = (w - bodyW) / 2f
        val bodyY = h - bodyH
        val tipW = bodyW * 0.4f
        val tipH = h * 0.1f
        
        drawRect(
            color.copy(alpha = AlphaTertiary),
            topLeft = Offset((w - tipW) / 2f, 0f),
            size = Size(tipW, tipH),
        )
        
        drawRect(
            color.copy(alpha = AlphaTertiary),
            topLeft = Offset(bodyX, bodyY),
            size = Size(bodyW, bodyH),
        )
        
        val fillH = bodyH * fillFraction
        drawRect(
            color,
            topLeft = Offset(bodyX, bodyY + bodyH - fillH),
            size = Size(bodyW, fillH),
        )
    }
}

@Composable
private fun KeyguardPrimaryText(event: IslandEvent, color: Color, modifier: Modifier) {
    when (event) {
        is IslandEvent.ScreenRecording ->
            if (event.isCountdown) MarqueeText(formatCountdownSeconds(event.countdownSeconds), color, modifier)
            else ElapsedTimeText(event.startTimeMs, color, modifier)
        is IslandEvent.AudioRecording -> when (event.state) {
            RecordingState.RECORDING -> ElapsedTimeText(
                event.startTimeMs, color, modifier, event.pausedDurationMs,
            )
            RecordingState.PAUSED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_paused), color, modifier)
            RecordingState.SAVED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_saved), color, modifier)
        }
        is IslandEvent.Media -> MarqueeText(event.track, color, modifier)
        is IslandEvent.Timer -> {
            if (event.endTimeMs > 0L) CountdownText(event, color, modifier)
            else MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }, color, modifier)
        }
        is IslandEvent.Stopwatch -> StopwatchTimeText(event, color, modifier)
        is IslandEvent.Notification -> MarqueeText(event.title ?: event.appName, color, modifier)
        is IslandEvent.Charging -> MarqueeText("${event.level}%", color, modifier)
        is IslandEvent.Bluetooth -> MarqueeText(event.deviceName.take(12), color, modifier)
        is IslandEvent.Hotspot -> {
            val hotspotLabel = stringResource(R.string.ax_dynamic_bar_hotspot)
            MarqueeText(
                if (event.numDevices > 0) "$hotspotLabel · ${event.numDevices}" else hotspotLabel,
                color, modifier,
            )
        }
        is IslandEvent.Alarm -> MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) }, color, modifier)
        is IslandEvent.Casting -> MarqueeText(event.deviceName.take(12), color, modifier)
        is IslandEvent.Torch -> MarqueeText(
            if (event.supportsLevel) "${(event.level.toFloat() / event.maxLevel * 100).toInt()}%"
            else stringResource(R.string.ax_dynamic_bar_flashlight),
            color, modifier,
        )
        is IslandEvent.RingerMode -> MarqueeText(event.label, color, modifier)
        is IslandEvent.Vpn -> MarqueeText(stringResource(R.string.ax_dynamic_bar_vpn_active), color, modifier)
        is IslandEvent.Clipboard -> MarqueeText(
            event.preview.ifEmpty { stringResource(R.string.ax_dynamic_bar_copied) }, color, modifier,
        )
        is IslandEvent.BiometricUnlock -> MarqueeText(stringResource(R.string.ax_dynamic_bar_unlocked), color, modifier)
        is IslandEvent.AppSwitch -> MarqueeText(stringResource(R.string.ax_dynamic_bar_recents), color, modifier)
        is IslandEvent.MicCamActive -> MarqueeText(
            event.appName.ifEmpty {
                buildString {
                    if (event.isCam) append(stringResource(R.string.ax_dynamic_bar_cam_short))
                    if (event.isMic && event.isCam) append(" · ")
                    if (event.isMic) append(stringResource(R.string.ax_dynamic_bar_mic_short))
                }
            },
            color, modifier,
        )
        is IslandEvent.PromotedOngoing -> MarqueeText(
            event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }, color, modifier,
        )
        is IslandEvent.Sports -> MarqueeText(
            "${event.score1}-${event.score2}", color, modifier,
        )
        is IslandEvent.NowPlaying -> MarqueeText(
            "${event.songTitle} · ${event.artist}".trimEnd(' ', '·', ' '), color, modifier,
        )
        is IslandEvent.KeyguardIndication -> MarqueeText(event.text, color, modifier)
    }
}

@Composable
private fun secondaryTextFor(event: IslandEvent): String? = when (event) {
    is IslandEvent.Media -> event.artist.takeIf { it.isNotBlank() }
    is IslandEvent.ScreenRecording ->
        if (event.isCountdown) formatCountdownSeconds(event.countdownSeconds)
        else stringResource(R.string.ax_dynamic_bar_rec_short)
    is IslandEvent.AudioRecording -> event.appName.takeIf { it.isNotBlank() }
    is IslandEvent.Timer -> event.label.takeIf { it.isNotBlank() }
    is IslandEvent.Stopwatch -> event.label.takeIf { it.isNotBlank() }
    is IslandEvent.Charging -> event.timeRemaining
    is IslandEvent.Bluetooth -> if (event.batteryLevel >= 0) "${event.batteryLevel}%" else null
    is IslandEvent.Hotspot -> if (event.numDevices > 0) stringResource(R.string.ax_dynamic_bar_hotspot_devices, event.numDevices) else null
    is IslandEvent.Alarm -> {
        if (event.triggerTimeMs > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = event.triggerTimeMs }
            "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        } else null
    }
    is IslandEvent.Casting -> stringResource(R.string.ax_dynamic_bar_cast_short)
    is IslandEvent.Vpn -> stringResource(R.string.ax_dynamic_bar_active)
    is IslandEvent.BiometricUnlock -> event.sourceName
    is IslandEvent.AppSwitch -> null
    is IslandEvent.Notification -> event.text?.take(30)
    is IslandEvent.PromotedOngoing -> event.text.takeIf { it.isNotBlank() }?.take(20)
    is IslandEvent.Sports -> "${event.team1Name} ${stringResource(R.string.ax_dynamic_bar_sports_vs)} ${event.team2Name}"
    is IslandEvent.KeyguardIndication -> when (event.indicationType) {
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC -> stringResource(R.string.ax_dynamic_bar_biometric)
        IslandEvent.KeyguardIndication.IndicationType.TRUST -> stringResource(R.string.ax_dynamic_bar_trust)
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> stringResource(R.string.ax_dynamic_bar_alignment)
        else -> null
    }
    else -> null
}

@Composable
private fun SportsChipTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(ChipIconSize),
            contentDescription = name,
            modifier = Modifier.size(ChipIconSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(ChipIconSize).clip(CircleShape)
                .background(contentColor.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                style = TsBadge,
                color = contentColor,
            )
        }
    }
}

private enum class ActionIcon { PLAY, PAUSE, STOP, SKIP_PREV, SKIP_NEXT }

private data class ChipAction(
    val icon: ActionIcon,
    val perform: (AxDynamicBarChipViewModel, IslandEvent, Context) -> Unit,
)

private fun actionsFor(event: IslandEvent): List<ChipAction> = when (event) {
    is IslandEvent.Media -> listOf(
        ChipAction(ActionIcon.SKIP_PREV) { vm, _, _ -> vm.skipPrev() },
        ChipAction(if (event.isPlaying) ActionIcon.PAUSE else ActionIcon.PLAY) { vm, _, _ ->
            vm.togglePlayPause()
        },
        ChipAction(ActionIcon.SKIP_NEXT) { vm, _, _ -> vm.skipNext() },
    )
    is IslandEvent.ScreenRecording ->
        if (event.isCountdown) emptyList()
        else listOf(ChipAction(ActionIcon.STOP) { vm, _, _ -> vm.stopScreenRecording() })
    is IslandEvent.AudioRecording -> {
        val pauseResume = event.actions.firstOrNull()
        listOfNotNull(
            pauseResume?.let { action ->
                ChipAction(
                    if (event.state == RecordingState.RECORDING) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
            ChipAction(ActionIcon.STOP) { vm, e, _ -> vm.dismissEvent(e) },
        )
    }
    is IslandEvent.Timer -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isPaused) ActionIcon.PLAY else ActionIcon.PAUSE,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Stopwatch -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isRunning) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Torch -> listOf(
        ChipAction(ActionIcon.STOP) { vm, _, _ -> vm.toggleTorch() },
    )
    is IslandEvent.Casting -> listOf(
        ChipAction(ActionIcon.STOP) { vm, e, _ -> vm.dismissEvent(e) },
    )
    else -> emptyList()
}

@Composable
private fun ActionButton(
    icon: ActionIcon,
    color: Color,
    bgColor: Color,
    onClick: () -> Unit,
    size: Dp = ActionSize,
    iconSize: Dp = ActionIconSize,
) {
    val imageVector = when (icon) {
        ActionIcon.PLAY -> Icons.Filled.PlayArrow
        ActionIcon.PAUSE -> Icons.Filled.Pause
        ActionIcon.STOP -> Icons.Filled.Stop
        ActionIcon.SKIP_PREV -> Icons.Filled.SkipPrevious
        ActionIcon.SKIP_NEXT -> Icons.Filled.SkipNext
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = bgColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Icon(
                imageVector,
                contentDescription = icon.name,
                tint = color,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun MarqueeText(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        style = PillPrimary,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.widthIn(max = 120.dp).basicMarquee(iterations = 1),
    )
}

@Composable
private fun ElapsedTimeText(
    startTimeMs: Long,
    color: Color,
    modifier: Modifier,
    pausedDurationMs: Long = 0L,
) {
    var elapsedMs by remember(startTimeMs, pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - startTimeMs - pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(startTimeMs, pausedDurationMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - startTimeMs - pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }
    Text(formatElapsedTime(elapsedMs), color = color, style = PillMono, modifier = modifier)
}

@Composable
private fun CountdownText(event: IslandEvent.Timer, color: Color, modifier: Modifier) {
    if (event.isPaused) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var remainingMs by remember(event.endTimeMs) {
            mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
        Text(formatCountdownLong(remainingMs), color = color, style = PillMono, modifier = modifier)
    }
}

@Composable
private fun StopwatchTimeText(event: IslandEvent.Stopwatch, color: Color, modifier: Modifier) {
    if (!event.isRunning) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var elapsedMs by remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
        Text(formatStopwatch(elapsedMs), color = color, style = PillMono, modifier = modifier)
    }
}

