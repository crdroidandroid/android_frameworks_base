package com.android.systemui.axdynamicbar.ui.compose

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

private fun resolveRemoteViews(ctx: Context, notification: Notification): RemoteViews? {
    notification.bigContentView?.let {
        return it
    }
    notification.contentView?.let {
        return it
    }
    try {
        val builder = Notification.Builder.recoverBuilder(ctx, notification)
        builder.createBigContentView()?.let {
            return it
        }
        builder.createContentView()?.let {
            return it
        }
    } catch (_: Exception) {}
    return null
}

private fun applyOrReapplyRemoteViews(frame: FrameLayout, notification: Notification): Boolean {
    val rv =
        try {
            resolveRemoteViews(frame.context, notification)
        } catch (_: Exception) {
            return false
        } ?: return false
    val existing = if (frame.childCount > 0) frame.getChildAt(0) else null
    if (existing != null) {
        try {
            rv.reapply(frame.context, existing)
            prepareForIsland(existing)
            return true
        } catch (_: Exception) {
            frame.removeAllViews()
        }
    }
    return try {
        val inflated = rv.apply(frame.context, frame)
        prepareForIsland(inflated)
        frame.addView(
            inflated,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun SbnContentView(sbn: StatusBarNotification, fallback: @Composable () -> Unit) {
    var failed by remember(sbn.key) { mutableStateOf(false) }
    if (failed) {
        fallback()
        return
    }
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { frame ->
            val notification = sbn.notification
            if (notification == null || !applyOrReapplyRemoteViews(frame, notification)) {
                failed = true
            }
        },
        modifier = Modifier.fillMaxWidth().clip(ShapeIconMedium),
    )
}

private val COLLAPSE_CHIP_IDS = arrayOf("expand_button_touch_container", "expand_button")

private fun prepareForIsland(root: View) {
    val res = root.resources

    for (name in COLLAPSE_CHIP_IDS) {
        val id = res.getIdentifier(name, "id", "android")
        if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
    }
    hideCollapseButtons(root)
}

private val COLLAPSE_LABELS = setOf("collapse", "expand", "minimize")

private fun hideCollapseButtons(view: View) {
    if (view is Button) {
        val text = view.text?.toString()?.lowercase() ?: ""
        if (COLLAPSE_LABELS.any { text.contains(it) }) {
            view.visibility = View.GONE
        }
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            hideCollapseButtons(view.getChildAt(i))
        }
    }
}

@Composable
internal fun PromotedOngoingExpanded(
    event: IslandEvent.PromotedOngoing,
    interactor: IslandActions,
) {
    val sbn = event.sbn
    SbnContentView(sbn, fallback = { PromotedOngoingFallback(event, interactor) })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromotedOngoingFallback(
    event: IslandEvent.PromotedOngoing,
    interactor: IslandActions,
) {
    val context = LocalContext.current
    val tone = toneFor(event)
    val tint = tone.tint
    val usableActions =
        remember(event.actions) {
            event.actions
                .filter { action ->
                    val label = action.label.toString().lowercase()
                    label != "collapse" && label != "expand" && label != "minimize"
                }
                .take(MAX_INLINE_ACTIONS)
        }
    val inline = rememberActionsFitInline(usableActions.map { it.action })
    val hasProgress = event.progress >= 0f || event.isIndeterminate

    val actionButtons: @Composable () -> Unit = {
        usableActions.forEach { notifAction ->
            NotificationActionButton(
                action = notifAction.action,
                label = notifAction.label.toString(),
                tone = tone,
            ) {
                try {
                    notifAction.action.actionIntent?.sendWithBal(context)
                } catch (_: Exception) {}
                interactor.collapseIsland()
            }
        }
    }

    ExpandedCardLayout(
        accentColor = tint,
        iconBackground = false,
        icon = {
            event.appIcon?.let { icon ->
                Image(
                    bitmap = icon.toScaledBitmap(SizeIconBadge),
                    contentDescription = null,
                    modifier = Modifier.size(SizeIconBadge).clip(ShapeCompact),
                    contentScale = ContentScale.Crop,
                )
            } ?: PulsingDot(color = tint, size = 10.dp)
        },
        title = {
            Text(
                event.title.ifEmpty { event.appName },
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Text and short status share the caption line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceSm),
            ) {
                if (event.shortText.isNotEmpty()) {
                    StatusChip(event.shortText, tint)
                }
                if (event.text.isNotEmpty()) {
                    Text(
                        event.text,
                        color = SubtleGray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailing = if (inline && usableActions.isNotEmpty()) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                ) { actionButtons() }
            }
        } else null,
        actions = if (hasProgress || (!inline && usableActions.isNotEmpty())) {
            {
                if (hasProgress) {
                    if (event.isIndeterminate) {
                        // The determinate overload pinned at 0 drew an empty bar for "working…".
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = tint,
                            trackColor = tint.copy(alpha = AlphaTrack),
                        )
                    } else {
                        LinearWavyProgressIndicator(
                            progress = { event.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = tint,
                            trackColor = tint.copy(alpha = AlphaTrack),
                        )
                    }
                }
                if (!inline && usableActions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                    ) { actionButtons() }
                }
            }
        } else null,
    )
}

@Composable
internal fun RowScope.CompactPromotedOngoingRow(event: IslandEvent.PromotedOngoing) {
    event.appIcon?.let { icon ->
        Image(
            bitmap = icon.toScaledBitmap(SizeCompactIcon),
            contentDescription = null,
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact),
            contentScale = ContentScale.Crop,
        )
    }
        ?: Box(
            modifier =
                Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(IslandTone.ACCENT.tint.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            PulsingDot(color = IslandTone.ACCENT.tint, size = SpaceMd)
        }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.title.ifEmpty { event.appName },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subLabel = event.shortText.ifEmpty {
            if ((event.progress >= 0f || event.isIndeterminate) && event.text.isNotEmpty()) event.text
            else ""
        }
        if (subLabel.isNotEmpty()) {
            Text(subLabel, color = IslandTone.ACCENT.tint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

