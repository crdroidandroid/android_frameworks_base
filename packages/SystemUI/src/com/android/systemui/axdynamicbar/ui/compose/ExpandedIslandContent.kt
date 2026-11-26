@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import kotlinx.coroutines.delay
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

private val EXPANDED_BOTTOM_PAD = 50.dp

@Composable
fun ExpandedIslandContent(
    events: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    expandedFilter: String? = null,
    pinnedEventId: String? = null,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    isVisible: Boolean = true,
) {
    val reveal = rememberCardStackReveal(isVisible)
    if (events.isEmpty()) return

    val filteredEvents =
        remember(events, expandedFilter, pinnedEventId) {
            if (expandedFilter != null) {
                events.filter {
                    EVENT_TYPE_IDS[it::class.java] == expandedFilter
                }
            } else {

                val base = events.filter { it !is IslandEvent.Notification }
                val pinned = base.find { it.id == pinnedEventId }
                if (pinned != null) {
                    listOf(pinned) + base.filter { it.id != pinned.id }
                } else {
                    base
                }
            }
        }

    // Open cards do not time out while they are being read.
    val heldIds = remember(filteredEvents) {
        filteredEvents
            .filter { it is IslandEvent.Notification || it is IslandEvent.Charging }
            .map { it.id }
            .distinct()
    }
    DisposableEffect(interactor, heldIds) {
        heldIds.forEach(interactor::onNotificationInteraction)
        onDispose { heldIds.forEach(interactor::onNotificationInteractionEnd) }
    }

    val shouldCollapse = filteredEvents.isEmpty()
    LaunchedEffect(shouldCollapse) {
        if (shouldCollapse) {
            delay(200)
            onCollapse()
        }
    }

    if (filteredEvents.isEmpty()) return

    val isNotificationFilter = expandedFilter == "notification"
    val notifGroups =
        remember(filteredEvents, isNotificationFilter) {
            if (!isNotificationFilter) return@remember emptyList()
            filteredEvents
                .filterIsInstance<IslandEvent.Notification>()
                .groupBy { it.sbn.packageName }
                .values
                .toList()
        }

    val expandedGroups = remember(expandedFilter) { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier =
            Modifier.widthIn(max = ExpandedMaxWidth)
                .wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
        contentPadding =
            PaddingValues(start = SpaceLg, end = SpaceLg, top = SpaceXxs, bottom = EXPANDED_BOTTOM_PAD),
    ) {
        if (isNotificationFilter && notifGroups.isNotEmpty()) {
            notifGroups.forEachIndexed { index, group ->
                if (group.size == 1) {
                    val event = group.first()
                    item(key = event.id) {
                        StackedCard(reveal, index, Modifier.animateItem()) {
                            MagneticSwipeToDismiss(onDismiss = { interactor.dismissEvent(event) }) {
                                PrimaryCard { NotificationExpanded(event, interactor) }
                            }
                        }
                    }
                } else {
                    val pkg = group.first().sbn.packageName
                    val isExpanded = expandedGroups[pkg] == true
                    item(key = "group_$pkg") {
                        StackedCard(reveal, index, Modifier.animateItem()) {
                            MagneticSwipeToDismiss(
                                onDismiss = { group.forEach { interactor.dismissEvent(it) } },
                            ) {
                                PrimaryCard {
                                    NotificationGroupCard(
                                        notifications = group,
                                        isExpanded = isExpanded,
                                        onToggleExpand = { expandedGroups[pkg] = !isExpanded },
                                        interactor = interactor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            itemsIndexed(filteredEvents, key = { _, it -> it.id }) { index, event ->
                StackedCard(reveal, index, Modifier.animateItem()) {
                    MagneticSwipeToDismiss(onDismiss = { interactor.dismissEvent(event) }) {
                        if (event is IslandEvent.Media) {
                            MediaCard(event, interactor)
                        } else {
                            val context = LocalContext.current
                            val tapModifier =
                                if (event.contentIntent != null) {
                                    Modifier.clickable {
                                        if (event.contentIntent.trySendWithBal(context)) {
                                            interactor.collapseIsland()
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            PrimaryCard(modifier = tapModifier) {
                                // Hoisted: the specs are read inside the non-composable transitionSpec.
                                val motionScheme = MaterialTheme.motionScheme
                                AnimatedContent(
                                    targetState = event,
                                    transitionSpec = {
                                        ((fadeIn(motionScheme.defaultEffectsSpec()) +
                                            scaleIn(
                                                initialScale = 0.95f,
                                                animationSpec = motionScheme.defaultSpatialSpec(),
                                            )) togetherWith
                                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                                scaleOut(
                                                    targetScale = 0.95f,
                                                    animationSpec = motionScheme.fastSpatialSpec(),
                                                ))).using(sizeTransform = null)
                                    },
                                    contentKey = { it::class.simpleName + it.id },
                                    label = "expanded_card",
                                ) { animatedEvent ->
                                    ExpandedEventContent(
                                        animatedEvent,
                                        interactor,
                                        hapticsViewModelFactory,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExpandedEventContent(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    when (event) {
        is IslandEvent.AudioRecording -> AudioRecordingExpanded(event, interactor)
        is IslandEvent.PromotedOngoing -> PromotedOngoingExpanded(event, interactor)
        is IslandEvent.Sports -> SportsExpanded(event, interactor)
        is IslandEvent.NowPlaying -> NowPlayingExpanded(event, interactor)
        // Unreachable in practice — Media is routed straight to MediaCard above, which draws its
        // own frosted surface — but the `when` must stay exhaustive.
        is IslandEvent.Media -> MediaCard(event, interactor)
        is IslandEvent.Bluetooth -> BluetoothExpanded(event, interactor)
        is IslandEvent.Hotspot -> HotspotExpanded(event)
        is IslandEvent.Charging -> ChargingExpanded(event)
        is IslandEvent.Alarm -> AlarmExpanded(event, interactor)
        is IslandEvent.Timer -> TimerExpanded(event, interactor)
        is IslandEvent.Stopwatch -> StopwatchExpanded(event, interactor)
        is IslandEvent.RingerMode -> RingerModeExpanded(event, interactor)
        is IslandEvent.Vpn -> VpnExpanded(event)
        is IslandEvent.Clipboard -> ClipboardExpanded(event, interactor)
        is IslandEvent.Call -> CallExpanded(event, interactor)
        is IslandEvent.Notification -> NotificationExpanded(event, interactor)
        is IslandEvent.AppSwitch -> AppHistoryExpanded(event, interactor)
        is IslandEvent.Torch -> TorchExpanded(event, interactor, hapticsViewModelFactory)
        is IslandEvent.BiometricUnlock -> BiometricUnlockExpanded(event)
        is IslandEvent.KeyguardIndication -> {}
        is IslandEvent.AospChip -> {}
    }
}

@Composable
internal fun BiometricUnlockExpanded(event: IslandEvent.BiometricUnlock) {
    ExpandedCardLayout(
        accentColor = IslandTone.POSITIVE.tint,
        icon = { Icon(Icons.Filled.Check, null, tint = IslandTone.POSITIVE.tint, modifier = Modifier.size(16.dp)) },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_device_unlocked), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            Text(event.sourceName, color = SubtleGray, style = MaterialTheme.typography.labelMedium)
        },
    )
}

@Composable
internal fun CallExpanded(event: IslandEvent.Call, interactor: IslandActions) {
    val context = LocalContext.current
    val isIncoming = event.callType.contains("incoming", ignoreCase = true)
    val tone = if (isIncoming) IslandTone.POSITIVE else IslandTone.ACCENT
    val accent = tone.tint
    val declineTint = IslandTone.ALERT.tint
    val answerTint = IslandTone.POSITIVE.tint

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (event.contentIntent.trySendWithBal(context)) interactor.collapseIsland()
            },
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        // Plain row: the card behind it is already the surface, a tinted panel inside it was a
        // second surface stacked on the glass.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            // Caller photo or app icon
            val photo = event.callerPhoto ?: event.appIcon
            if (photo != null) {
                Image(
                    bitmap = photo.toScaledBitmap(SizeCompactIcon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(SizeCompactIcon)
                        .clip(CircleShape),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXxs),
            ) {
                Text(
                    if (isIncoming) stringResource(R.string.ax_dynamic_bar_incoming_call)
                    else stringResource(R.string.ax_dynamic_bar_call),
                    color = SubtleGray,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    event.callerName ?: event.number ?: "",
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Call timer for active calls
            if (!isIncoming && event.callStartTimeMs > 0L) {
                CallElapsedTimer(event.callStartTimeMs, accent)
            }
        }

        // Number subtitle if caller name is present
        if (event.callerName != null && event.number != null) {
            Text(
                event.number,
                color = SubtleGray,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (!isIncoming && (event.speaker != null || event.mute != null)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                event.speaker?.let {
                    CallToggleButton(
                        toggle = it,
                        label = stringResource(R.string.ax_dynamic_bar_call_speaker),
                        icon = Icons.Filled.VolumeUp,
                        tone = tone,
                        modifier = Modifier.weight(1f),
                    )
                }
                event.mute?.let {
                    CallToggleButton(
                        toggle = it,
                        label = stringResource(R.string.ax_dynamic_bar_call_mute),
                        icon = Icons.Filled.MicOff,
                        tone = tone,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Action buttons
        if (event.actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                event.actions.forEach { action ->
                    val isDestructive = action.label.toString().lowercase().let {
                        it.contains("decline") || it.contains("reject") || it.contains("hang up")
                    }
                    val isAnswer = action.label.toString().lowercase().let {
                        it.contains("answer") || it.contains("accept")
                    }
                    val tint = when {
                        isDestructive -> declineTint
                        isAnswer -> answerTint
                        else -> accent
                    }
                    ExpressivePillButton(
                        label = action.label.toString(),
                        icon = when {
                            isDestructive -> Icons.Filled.CallEnd
                            isAnswer -> Icons.Filled.Call
                            else -> null
                        },
                        contentColor = tint,
                        backgroundColor = tint.copy(alpha = AlphaIconBg),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try { action.action.actionIntent?.sendWithBal(context) }
                            catch (_: Exception) {}
                            interactor.collapseIsland()
                        },
                    )
                }
            }
        }
    }
}

// Lit while on; flips at once and settles on the state the next notification update reports.
@Composable
private fun CallToggleButton(
    toggle: IslandEvent.CallToggle,
    label: String,
    icon: ImageVector,
    tone: IslandTone,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var on by remember(toggle) { mutableStateOf(toggle.on) }
    ExpressivePillButton(
        label = label,
        icon = icon,
        contentColor = if (on) tone.onTint else OnCardText,
        backgroundColor = if (on) tone.tint else ActionBg,
        modifier = modifier,
        onClick = {
            try {
                toggle.intent.sendWithBal(context)
                on = !on
            } catch (_: Exception) {}
        },
    )
}

@Composable
private fun CallElapsedTimer(startTimeMs: Long, color: Color) {
    var elapsedMs by remember(startTimeMs) {
        mutableLongStateOf((System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L))
    }
    LaunchedEffect(startTimeMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        }
    }
    Text(
        formatElapsedTime(elapsedMs),
        color = color,
        style = TsCounter,
    )
}

/**
 * The frosted card every expanded tile sits on. The blur backdrop is the surface itself: it falls
 * back to the opaque [CardBg] wherever the platform will not blur, so no separate background is
 * painted here.
 */
@Composable
internal fun PrimaryCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(ShapeCard)
                .islandBlur(RadiusCard, CardBg)
                .then(modifier)
    ) {
        Box(modifier = Modifier.padding(SpaceXxl)) { content() }
    }
}

