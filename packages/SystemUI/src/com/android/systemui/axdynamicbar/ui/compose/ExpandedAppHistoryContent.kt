package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

private val AppIconSize = 40.dp
private const val APPS_PER_ROW = 4

@Composable
internal fun AppHistoryExpanded(event: IslandEvent.AppSwitch, interactor: IslandActions) {
    if (event.recentApps.isEmpty()) return
    val tint = IslandTone.ACCENT.tint

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceLg)) {
        // Plain header row rather than a tinted panel nested inside the frosted card.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Box(
                modifier = Modifier.size(SizeIconBadge).clip(ShapeCompact)
                    .background(tint.copy(alpha = AlphaIconBg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Apps, null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Text(
                stringResource(R.string.ax_dynamic_bar_recent_apps),
                color = OnCardText,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.ax_dynamic_bar_count_running, event.recentApps.size),
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        event.recentApps.chunked(APPS_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceSm),
            ) {
                row.forEach { app ->
                    AppGridItem(
                        app = app,
                        onClick = {
                            interactor.switchToApp(app.taskId)
                            interactor.collapseIsland()
                        },
                        onKill = { interactor.killApp(app.taskId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(APPS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppGridItem(
    app: IslandEvent.RecentApp,
    onClick: () -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val onErrorColor = MaterialTheme.colorScheme.onError
    Box(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(ShapeLg)
                    .clickable(onClick = onClick)
                    .background(IslandTone.ACCENT.tint.copy(alpha = AlphaFaint), ShapeLg)
                    .padding(vertical = SpaceMd, horizontal = SpaceXs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            app.appIcon?.let { icon ->
                Image(
                    bitmap = icon.toScaledBitmap(AppIconSize),
                    contentDescription = app.appName,
                    modifier = Modifier.size(AppIconSize).clip(ShapeCompact),
                    contentScale = ContentScale.Crop,
                )
            }
                ?: Box(
                    modifier = Modifier.size(AppIconSize).clip(ShapeCompact).background(DarkCard),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Apps, null, tint = SubtleGray, modifier = Modifier.size(20.dp))
                }

            Text(
                app.appName,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Surface(
            onClick = onKill,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp)
                .size(20.dp),
            shape = CircleShape,
            color = errorColor,
            contentColor = onErrorColor,
            border = BorderStroke(1.5.dp, onErrorColor.copy(alpha = 0.9f)),
            shadowElevation = 3.dp,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.ax_dynamic_bar_kill_app),
                    tint = onErrorColor,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun RowScope.CompactAppSwitchRow(event: IslandEvent.AppSwitch) {
    val lastApp = event.recentApps.firstOrNull() ?: return
    lastApp.appIcon?.let { icon ->
        Image(
            bitmap = icon.toScaledBitmap(SizeCompactIcon),
            null,
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact),
            contentScale = ContentScale.Crop,
        )
    }
        ?: Box(
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(DarkCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Apps, null, tint = SubtleGray, modifier = Modifier.size(20.dp))
        }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(stringResource(R.string.ax_dynamic_bar_recent_apps), color = OnCardText, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.ax_dynamic_bar_count_running, event.recentApps.size), color = SubtleGray, style = MaterialTheme.typography.labelSmall)
    }
}
