package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private val EXPANDED_BOTTOM_PAD = 110.dp

@Composable
fun ExpandedIslandContent(
    events: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    expandedFilter: String? = null,
    pinnedEventId: String? = null,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
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

    val notifIds =
        remember(filteredEvents) {
            filteredEvents.filterIsInstance<IslandEvent.Notification>().map { it.id }
        }
    LaunchedEffect(notifIds) { notifIds.forEach { interactor.onNotificationInteraction(it) } }
    DisposableEffect(notifIds) {
        onDispose { notifIds.forEach { interactor.onNotificationInteractionEnd(it) } }
    }

    LaunchedEffect(filteredEvents) {
        if (filteredEvents.isEmpty()) {
            
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
                .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
        contentPadding =
            PaddingValues(start = SpaceLg, end = SpaceLg, top = SpaceXxs, bottom = EXPANDED_BOTTOM_PAD),
    ) {
        if (isNotificationFilter && notifGroups.isNotEmpty()) {
            notifGroups.forEach { group ->
                if (group.size == 1) {
                    val event = group.first()
                    item(key = event.id) {
                        MagneticSwipeToDismiss(
                            onDismiss = { interactor.dismissEvent(event) },
                            modifier = Modifier.animateItem(),
                        ) {
                            PrimaryCard { NotificationExpanded(event, interactor) }
                        }
                    }
                } else {
                    val pkg = group.first().sbn.packageName
                    val isExpanded = expandedGroups[pkg] == true
                    item(key = "group_$pkg") {
                        MagneticSwipeToDismiss(
                            onDismiss = { group.forEach { interactor.dismissEvent(it) } },
                            modifier = Modifier.animateItem(),
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
        } else {
            items(filteredEvents, key = { it.id }) { event ->
                MagneticSwipeToDismiss(
                    onDismiss = { interactor.dismissEvent(event) },
                    modifier = Modifier.animateItem(),
                ) {
                    if (event is IslandEvent.Media) {
                        MediaCard(event, interactor)
                    } else {
                        PrimaryCard {
                            AnimatedContent(
                                targetState = event,
                                transitionSpec = {
                                    ((fadeIn(tween(180)) +
                                        scaleIn(
                                            initialScale = 0.95f,
                                            animationSpec = tween(250),
                                        )) togetherWith
                                        (fadeOut(tween(120)) +
                                            scaleOut(
                                                targetScale = 0.95f,
                                                animationSpec = tween(200),
                                            ))).using(sizeTransform = null)
                                },
                                contentKey = { it::class.simpleName + it.id },
                                label = "expanded_card",
                            ) { animatedEvent ->
                                ExpandedEventContent(animatedEvent, interactor, hapticsViewModelFactory)
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
        is IslandEvent.Media -> MediaExpanded(event, interactor)
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
        accentColor = GreenAccent,
        icon = { Icon(Icons.Filled.Check, null, tint = GreenAccent, modifier = Modifier.size(26.dp)) },
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
    val accent = if (isIncoming) GreenAccent else BlueAccent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try { event.sbn.notification?.contentIntent?.sendWithBal(context) }
                catch (_: Exception) {}
                interactor.collapseIsland()
            },
        verticalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(accent.copy(alpha = AlphaFaint))
                .padding(SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
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
                    ExpressivePillButton(
                        label = action.label.toString(),
                        contentColor = if (isDestructive) RedAccent
                            else if (isAnswer) GreenAccent
                            else accent,
                        backgroundColor = if (isDestructive) RedAccent.copy(alpha = AlphaFaint)
                            else if (isAnswer) GreenAccent.copy(alpha = AlphaFaint)
                            else accent.copy(alpha = AlphaFaint),
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
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
internal fun PrimaryCard(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(ShapeCard)
                .background(CardBg)
                .border(1.dp, CardBorderBrush, ShapeCard)
                .padding(SpaceXxl)
    ) {
        content()
    }
}

