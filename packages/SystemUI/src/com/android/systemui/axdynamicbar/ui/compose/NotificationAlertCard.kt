package com.android.systemui.axdynamicbar.ui.compose

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.ActionBg
import com.android.systemui.axdynamicbar.shared.AlphaIconBg
import com.android.systemui.axdynamicbar.shared.AlphaSubtle
import com.android.systemui.axdynamicbar.shared.CardBg
import com.android.systemui.axdynamicbar.shared.CardBorderBrush
import com.android.systemui.axdynamicbar.shared.DarkCard
import com.android.systemui.axdynamicbar.shared.ExpressivePillButton
import com.android.systemui.axdynamicbar.shared.GreenAccent
import com.android.systemui.axdynamicbar.shared.OnActionText
import com.android.systemui.axdynamicbar.shared.OnCardText
import com.android.systemui.axdynamicbar.shared.RedAccent
import com.android.systemui.axdynamicbar.shared.ShapeCard
import com.android.systemui.axdynamicbar.shared.ShapeChip
import com.android.systemui.axdynamicbar.shared.ShapeIconMedium
import com.android.systemui.axdynamicbar.shared.ShapeSm
import com.android.systemui.axdynamicbar.shared.SizeCompactIcon
import com.android.systemui.axdynamicbar.shared.SizeIconSm
import com.android.systemui.axdynamicbar.shared.SpaceLg
import com.android.systemui.axdynamicbar.shared.SpaceMd
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.SpaceXxl
import com.android.systemui.axdynamicbar.shared.SubtleGray
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipContentColorOn
import com.android.systemui.axdynamicbar.shared.sendWithBal
import com.android.systemui.axdynamicbar.shared.toScaledBitmap

private const val MAX_EXPANDED_ACTIONS = 3
private val NotifCompactHeight = 56.dp
private val NotifCollapsedHeight = 164.dp
private val NotifExpandedHeight = 358.dp
private val ThumbnailSize = 44.dp

private suspend fun PointerInputScope.detectInteractionGesture(
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        var ev: PointerEvent
        do {
            ev = awaitPointerEvent(PointerEventPass.Final)
        } while (!ev.changes.any { it.changedToDownIgnoreConsumed() })
        onStart()
        do {
            ev = awaitPointerEvent(PointerEventPass.Final)
        } while (ev.changes.any { it.pressed })
        onEnd()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationAlertCard(
    notification: IslandEvent.Notification,
    interactor: IslandActions,
    onDismiss: () -> Unit,
    initiallyCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val extras = notification.sbn.notification?.extras
    val isCall = extras?.let {
        it.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
            it.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
            it.containsKey(Notification.EXTRA_HANG_UP_INTENT)
    } ?: false
    var isCompact by remember(notification.sbn.key) { mutableStateOf(initiallyCompact && !isCall) }
    var showReply by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(!isCompact) }
    var isTextTruncated by remember { mutableStateOf(false) }
    val title = notification.title
    val body = notification.text
    val hasTitleAndBody = title != null && title != notification.senderName && !body.isNullOrEmpty()
    val hasExtraActions = notification.actions.size > 2
    val hasExpandableContent = !isCall && (isTextTruncated || hasTitleAndBody || hasExtraActions)
    val accent = chipAccentColorFor(notification)
    val density = LocalDensity.current
    val compactHeightPx = with(density) { NotifCompactHeight.roundToPx() }
    val minHeightPx = with(density) { NotifCollapsedHeight.roundToPx() }
    val maxHeightPx = with(density) { NotifExpandedHeight.roundToPx() }
    val effectiveMinPx = if (isCompact) compactHeightPx else minHeightPx
    val effectiveMaxPx = if (isCompact) compactHeightPx else maxHeightPx
    var measuredHeightPx by remember { mutableIntStateOf(effectiveMinPx) }
    val heightAnim = remember { Animatable(effectiveMinPx.toFloat()) }
    val motionScheme = MaterialTheme.motionScheme
    val targetPx = measuredHeightPx.coerceIn(effectiveMinPx, effectiveMaxPx)
    LaunchedEffect(targetPx) {
        heightAnim.animateTo(targetPx.toFloat(), motionScheme.defaultSpatialSpec())
    }

    DisposableEffect(Unit) {
        onDispose { interactor.onNotificationAlertInteractionEnd() }
    }

    MagneticSwipeToDismiss(
        onDismiss = {
            if (showReply) interactor.onFocusableRequested?.invoke(false)
            onDismiss()
        },
        modifier = modifier,
        allowUpDismiss = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        constraints.copy(maxHeight = effectiveMaxPx),
                    )
                    measuredHeightPx = placeable.height
                    val h = heightAnim.value.toInt().coerceIn(effectiveMinPx, effectiveMaxPx)
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .pointerInput(Unit) {
                    detectInteractionGesture(
                        onStart = interactor::onNotificationAlertInteractionStart,
                        onEnd = interactor::onNotificationAlertInteractionEnd,
                    )
                }
                .clip(ShapeCard)
                .background(CardBg)
                .border(1.dp, CardBorderBrush, ShapeCard),
        ) {
            if (isCompact) {
                Surface(
                    onClick = { isCompact = false; isExpanded = true },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = SpaceXxl, vertical = SpaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                    ) {
                        notification.appIcon?.let { icon ->
                            Image(
                                bitmap = icon.toScaledBitmap(SizeIconSm),
                                contentDescription = null,
                                modifier = Modifier.size(SizeIconSm).clip(ShapeSm),
                            )
                        }
                        val label = notification.senderName
                            ?: notification.title
                            ?: notification.appName
                        Text(
                            label,
                            color = OnCardText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val preview = notification.text ?: notification.title ?: ""
                        if (preview.isNotEmpty() && preview != label) {
                            Text(
                                "· $preview",
                                color = SubtleGray,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        ExpandChip(Icons.Filled.ExpandMore, accent) { isCompact = false; isExpanded = true }
                    }
                }
            } else {
            Surface(
                onClick = {
                    if (!showReply) {
                        try { notification.sbn.notification?.contentIntent?.sendWithBal(context) }
                        catch (_: PendingIntent.CanceledException) {}
                        if (!isCall) onDismiss()
                    }
                },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(SpaceXxl),
                    verticalArrangement = Arrangement.spacedBy(SpaceLg),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                    ) {
                        notification.appIcon?.let { icon ->
                            Image(
                                bitmap = icon.toScaledBitmap(SizeIconSm),
                                contentDescription = null,
                                modifier = Modifier.size(SizeIconSm).clip(ShapeSm),
                            )
                        }
                        Text(
                            if (notification.isGroupConversation && notification.conversationTitle != null)
                                "${notification.appName} · ${notification.conversationTitle}"
                            else
                                notification.appName.ifEmpty { notification.senderName ?: "" },
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCall) {
                            CallActions(notification, interactor, onDismiss)
                        }
                        if (!showReply && !isCall) {
                            if (initiallyCompact) {
                                ExpandChip(Icons.Filled.ExpandLess, accent) {
                                    isExpanded = false
                                    isCompact = true
                                }
                            } else if (hasExpandableContent) {
                                ExpandChip(
                                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    accent,
                                ) { isExpanded = !isExpanded }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(SpaceLg),
                    ) {
                        NotifAvatar(notification, accent, SizeCompactIcon)

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(SpaceXs),
                        ) {
                            val sender = notification.senderName ?: notification.appName
                            Text(
                                sender,
                                color = OnCardText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            if (isExpanded) {
                                if (hasTitleAndBody) {
                                    Text(
                                        title!!,
                                        color = OnCardText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        body!!,
                                        color = SubtleGray,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    val preview = body ?: title ?: ""
                                    if (preview.isNotEmpty()) {
                                        Text(
                                            preview,
                                            color = SubtleGray,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 10,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            } else {
                                val preview = body ?: title ?: ""
                                if (preview.isNotEmpty()) {
                                    Text(
                                        preview,
                                        color = SubtleGray,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { isTextTruncated = it.hasVisualOverflow },
                                    )
                                }
                            }
                        }

                        notification.notificationImage?.let { img ->
                            Image(
                                bitmap = img.toScaledBitmap(ThumbnailSize),
                                contentDescription = null,
                                modifier = Modifier.size(ThumbnailSize).clip(ShapeSm),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }

            val hasReply = notification.replyAction != null
            val hasActions = notification.actions.isNotEmpty() || hasReply
            if (!isCall && !showReply && hasActions) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = SpaceXxl)
                        .padding(bottom = SpaceXxl),
                ) {
                    if (isExpanded) {
                        ExpandedActions(notification, accent, onDismiss) { showReply = true }
                    } else {
                        CollapsedActions(notification, accent, onDismiss) { showReply = true }
                    }
                }
            }

            if (showReply && notification.replyAction != null) {
                ReplyField(
                    reply = notification.replyAction!!,
                    accent = accent,
                    interactor = interactor,
                    onSent = {
                        showReply = false
                        onDismiss()
                    },
                )
            }
            }
        }
    }
}

@Composable
private fun NotifAvatar(notification: IslandEvent.Notification, accent: Color, size: Dp) {
    val icon = notification.senderIcon ?: notification.appIcon
    val isRound = notification.isConversation && notification.senderIcon != null

    if (notification.isConversation && notification.senderIcon != null && notification.appIcon != null) {
        BadgedContactIcon(icon!!, notification.appIcon!!, size, (size.value * 0.44f).dp, true)
    } else if (icon != null) {
        Box(
            modifier = Modifier.size(size).clip(if (isRound) CircleShape else ShapeIconMedium),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = icon.toScaledBitmap(size),
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        Box(
            modifier = Modifier.size(size).clip(ShapeIconMedium).background(accent.copy(alpha = AlphaSubtle)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Notifications, null, tint = accent, modifier = Modifier.size(SizeIconSm))
        }
    }
}

@Composable
private fun CollapsedActions(
    notification: IslandEvent.Notification,
    accent: Color,
    onDismiss: () -> Unit,
    onReplyClick: () -> Unit,
) {
    val context = LocalContext.current
    val hasReply = notification.replyAction != null
    val visibleActions = notification.actions.take(2)
    if (visibleActions.isEmpty() && !hasReply) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        if (hasReply) {
            ExpressivePillButton(
                label = notification.replyAction!!.label.toString(),
                icon = Icons.AutoMirrored.Filled.Reply,
                contentColor = chipContentColorOn(accent),
                backgroundColor = accent,
                modifier = Modifier.weight(1f),
                onClick = onReplyClick,
            )
        }
        visibleActions.take(if (hasReply) 1 else 2).forEach { action ->
            ExpressivePillButton(
                label = action.label.toString(),
                contentColor = accent,
                backgroundColor = accent.copy(alpha = AlphaIconBg),
                modifier = Modifier.weight(1f),
                onClick = {
                    try { action.action.actionIntent?.sendWithBal(context) }
                    catch (_: PendingIntent.CanceledException) {}
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun CallActions(
    notification: IslandEvent.Notification,
    interactor: IslandActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val extras = notification.sbn.notification?.extras
    val isIncoming = extras?.containsKey(Notification.EXTRA_ANSWER_INTENT) == true
    val answerPi = extras?.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
    val declinePi = extras?.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
    val hangUpPi = extras?.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)

    Row(horizontalArrangement = Arrangement.spacedBy(SpaceMd)) {
        if (isIncoming) {
            notification.actions.forEach { action ->
                val pi = action.action.actionIntent
                val isDecline = (declinePi != null && pi == declinePi) || (hangUpPi != null && pi == hangUpPi)
                val isAnswer = answerPi != null && pi == answerPi
                val decline = isDecline || (!isAnswer && declinePi == null)
                val bg = if (decline) RedAccent else GreenAccent
                ActionCircle(
                    if (decline) Icons.Filled.CallEnd else Icons.Filled.Call,
                    bg, chipContentColorOn(bg),
                ) {
                    try { action.action.actionIntent?.sendWithBal(context) }
                    catch (_: PendingIntent.CanceledException) {}
                    onDismiss()
                }
            }
        } else {
            notification.actions.forEach { action ->
                val pi = action.action.actionIntent
                val semantic = action.action.semanticAction
                val isMute = semantic == Notification.Action.SEMANTIC_ACTION_MUTE ||
                    semantic == Notification.Action.SEMANTIC_ACTION_UNMUTE
                val isEnd = if (hangUpPi != null) pi == hangUpPi
                    else !isMute && action == notification.actions.first()
                val bg = if (isEnd) RedAccent else ActionBg
                val tint = if (isEnd) chipContentColorOn(RedAccent) else OnCardText
                val drawable = action.action.getIcon()?.loadDrawable(context)
                if (drawable != null) {
                    DrawableActionCircle(drawable, bg, tint) {
                        try { action.action.actionIntent?.sendWithBal(context) }
                        catch (_: PendingIntent.CanceledException) {}
                        if (isEnd) onDismiss()
                    }
                } else {
                    ActionCircle(
                        if (isEnd) Icons.Filled.CallEnd else Icons.Filled.Call,
                        bg, tint,
                    ) {
                        try { action.action.actionIntent?.sendWithBal(context) }
                        catch (_: PendingIntent.CanceledException) {}
                        if (isEnd) onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCircle(
    icon: ImageVector,
    bg: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = bg, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ExpandChip(icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = accent.copy(alpha = AlphaIconBg),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DrawableActionCircle(
    drawable: Drawable,
    bg: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = bg, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
            Image(
                bitmap = drawable.toScaledBitmap(18.dp),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

@Composable
private fun ExpandedActions(
    notification: IslandEvent.Notification,
    accent: Color,
    onDismiss: () -> Unit,
    onReplyClick: () -> Unit,
) {
    val context = LocalContext.current
    val hasReply = notification.replyAction != null
    val visibleActions = notification.actions.take(MAX_EXPANDED_ACTIONS)
    if (visibleActions.isEmpty() && !hasReply) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        if (hasReply) {
            ExpressivePillButton(
                label = notification.replyAction!!.label.toString(),
                icon = Icons.AutoMirrored.Filled.Reply,
                contentColor = chipContentColorOn(accent),
                backgroundColor = accent,
                modifier = Modifier.weight(1f),
                onClick = onReplyClick,
            )
        }
        visibleActions.forEach { action ->
            ExpressivePillButton(
                label = action.label.toString(),
                contentColor = accent,
                backgroundColor = accent.copy(alpha = AlphaIconBg),
                modifier = Modifier.weight(1f),
                onClick = {
                    try { action.action.actionIntent?.sendWithBal(context) }
                    catch (_: PendingIntent.CanceledException) {}
                    onDismiss()
                },
            )
        }
    }
}


@Composable
private fun ReplyField(
    reply: IslandEvent.ReplyAction,
    accent: Color,
    interactor: IslandActions,
    onSent: () -> Unit,
) {
    var replyText by remember { mutableStateOf("") }
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SpaceLg, vertical = SpaceMd)
            .height(40.dp)
            .clip(ShapeChip)
            .background(DarkCard),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = replyText,
            onValueChange = { replyText = it },
            modifier = Modifier.weight(1f)
                .padding(horizontal = SpaceLg)
                .onFocusChanged { interactor.onFocusableRequested?.invoke(it.isFocused) },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = OnCardText),
            singleLine = true,
            cursorBrush = SolidColor(accent),
            decorationBox = { inner ->
                if (replyText.isEmpty()) {
                    Text(reply.label.toString(), color = SubtleGray, style = MaterialTheme.typography.bodySmall)
                }
                inner()
            },
        )
        if (replyText.isNotEmpty()) {
            Surface(
                onClick = {
                    val intent = Intent().apply {
                        val results = Bundle().apply {
                            putCharSequence(reply.remoteInput.resultKey, replyText)
                        }
                        RemoteInput.addResultsToIntent(arrayOf(reply.remoteInput), this, results)
                    }
                    try { reply.action.actionIntent?.sendWithBal(context, intent) }
                    catch (e: Exception) { Log.w("NotificationReply", "Failed to send reply", e) }
                    replyText = ""
                    interactor.onFocusableRequested?.invoke(false)
                    onSent()
                },
                shape = CircleShape,
                color = accent,
                modifier = Modifier.size(32.dp).padding(SpaceXs),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = chipContentColorOn(accent), modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(SpaceXs))
        }
    }
}
