package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaHint
import com.android.systemui.axdynamicbar.shared.AlphaTrack
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.ShapeXl
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipContentColorOn
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.iconKeyFor
import com.android.systemui.axdynamicbar.shared.textKeyFor
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipState
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import kotlin.math.abs

private val NowBarShape = ShapeXl
private val NowBarHeight = 32.dp
private val NowBarMaxWidth = 200.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AxDynamicBarNowBar(
    state: AxDynamicBarChipState?,
    viewModel: AxDynamicBarChipViewModel,
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val motionScheme = MaterialTheme.motionScheme

    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(motionScheme.defaultSpatialSpec()) { -it } + fadeIn(motionScheme.defaultEffectsSpec()),
        exit = slideOutVertically(motionScheme.fastSpatialSpec()) { -it } + fadeOut(motionScheme.fastEffectsSpec()),
    ) {
        state?.let { chipState ->
            val displayEvent = chipState.notificationAlert ?: chipState.event
            val isAlert = chipState.notificationAlert != null

            AnimatedContent(
                targetState = NowBarDisplay(displayEvent, isAlert),
                transitionSpec = {
                    (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.92f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                        (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.92f, animationSpec = motionScheme.fastSpatialSpec()))
                },
                contentKey = { if (it.isAlert) "alert" else it.event::class.simpleName },
                label = "nowbar_event",
            ) { display ->
                var targetWidthPx by remember { mutableIntStateOf(0) }
                val animatedWidthPx by animateIntAsState(
                    targetWidthPx, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "nowbar_w",
                )

                val rawAccent = chipAccentColorFor(display.event)
                val accent by animateColorAsState(rawAccent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "accent")
                val contentColor by animateColorAsState(
                    chipContentColorOn(rawAccent), MaterialTheme.motionScheme.fastEffectsSpec(), label = "content",
                )
                val rawProgress = chipProgressFor(display.event)
                val progressTarget = rawProgress ?: 0f
                val animatedProgress by animateFloatAsState(
                    progressTarget, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "progress",
                )
                val progress = if (rawProgress != null) animatedProgress else null

                Box(
                    modifier = Modifier
                        .padding(top = SpaceXs)
                        .pointerInput(viewModel, touchSlop) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var totalDragX = 0f
                                var isDragging = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break

                                    if (!change.pressed) {
                                        change.consume()
                                        if (isDragging) {
                                            if (totalDragX > 0f) viewModel.cyclePrev()
                                            else viewModel.cycleNext()
                                        } else {
                                            viewModel.togglePanel()
                                        }
                                        break
                                    }

                                    val delta = change.positionChange()
                                    totalDragX += delta.x

                                    if (!isDragging && abs(totalDragX) > touchSlop) {
                                        isDragging = true
                                    }

                                    if (isDragging) {
                                        change.consume()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .height(NowBarHeight)
                            .widthIn(max = NowBarMaxWidth)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                if (targetWidthPx != placeable.width) targetWidthPx = placeable.width
                                val w = if (animatedWidthPx > 0) animatedWidthPx else placeable.width
                                layout(w, placeable.height) { placeable.placeRelative(0, 0) }
                            }
                            .shadow(6.dp, NowBarShape)
                            .clip(NowBarShape)
                            .background(accent)
                            .then(
                                if (progress != null) {
                                    Modifier.drawWithContent {
                                        drawContent()
                                        val barH = 3.dp.toPx()
                                        val y = size.height - barH
                                        drawRect(
                                            contentColor.copy(alpha = AlphaTrack),
                                            topLeft = Offset(0f, y),
                                            size = Size(size.width, barH),
                                        )
                                        drawRect(
                                            contentColor.copy(alpha = 0.85f),
                                            topLeft = Offset(0f, y),
                                            size = Size(size.width * progress, barH),
                                        )
                                    }
                                } else Modifier
                            )
                            .padding(start = 10.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (display.isAlert && display.event is IslandEvent.Notification) {
                            AlertPillContent(display.event, contentColor)
                        } else {
                            EventPillContent(display.event, contentColor, chipState)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlertPillContent(event: IslandEvent, contentColor: Color) {
    val motionScheme = MaterialTheme.motionScheme
    val notif = event as IslandEvent.Notification
    AnimatedContent(
        targetState = notif,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()))
                .using(sizeTransform = null)
        },
        contentKey = { it.sbn.key },
        label = "alert_content",
    ) { n ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            n.appIcon?.let { icon ->
                Image(
                    bitmap = icon.toScaledBitmap(18.dp),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(SpaceSm))
            }
            Text(
                text = n.appName ?: "",
                style = PillPrimary,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EventPillContent(
    event: IslandEvent,
    contentColor: Color,
    chipState: AxDynamicBarChipState,
) {
    val motionScheme = MaterialTheme.motionScheme

    AnimatedContent(
        targetState = event,
        transitionSpec = { fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()) },
        contentKey = { iconKeyFor(it) },
        label = "nowbar_icon",
    ) { e ->
        PillEventIcon(e, tint = contentColor)
    }
    Spacer(Modifier.width(SpaceSm))
    
    AnimatedContent(
        targetState = event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()))
                .using(sizeTransform = null)
        },
        contentKey = { textKeyFor(it) },
        label = "nowbar_text",
    ) { e ->
        PillEventText(e, Modifier, overrideColor = contentColor)
    }
    if (chipState.eventCount > 1) {
        Spacer(Modifier.width(SpaceSm))
        NowBarDots(
            count = chipState.eventCount,
            activeIndex = chipState.pinnedIndex,
            color = contentColor,
        )
    }
}

@Composable
private fun NowBarDots(count: Int, activeIndex: Int, color: Color) {
    val dotCount = count.coerceAtMost(5)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until dotCount) {
            val active = i == activeIndex.coerceIn(0, dotCount - 1) % dotCount
            Box(
                Modifier
                    .size(if (active) 5.dp else 4.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) 1f else AlphaHint))
            )
        }
    }
}

private data class NowBarDisplay(val event: IslandEvent, val isAlert: Boolean)

