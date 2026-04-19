package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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

