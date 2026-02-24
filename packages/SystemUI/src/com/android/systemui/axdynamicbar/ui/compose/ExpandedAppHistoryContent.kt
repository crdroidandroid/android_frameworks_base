package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

@Composable
internal fun AppHistoryExpanded(event: IslandEvent.AppSwitch, interactor: IslandActions) {
    if (event.recentApps.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceXxl)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(BlueAccent.copy(alpha = AlphaFaint))
                .padding(SpaceXxl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.ax_dynamic_bar_recent_apps), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ax_dynamic_bar_count_running, event.recentApps.size), color = SubtleGray, style = MaterialTheme.typography.labelMedium)
        }

        val rows = event.recentApps.chunked(4)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                row.forEach { app ->
                    AppGridItem(
                        app = app,
                        onClick = {
                            interactor.switchToApp(app.taskId)
                            interactor.collapseIsland()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppGridItem(
    app: IslandEvent.RecentApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(ShapeLg)
                .clickable(onClick = onClick)
                .background(BlueAccent.copy(alpha = AlphaFaint), ShapeLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Spacer(Modifier.size(SpaceMd))
        app.appIcon?.let { icon ->
            Image(
                bitmap = icon.toScaledBitmap(48.dp),
                contentDescription = app.appName,
                modifier = Modifier.size(48.dp).clip(ShapeIconLarge),
                contentScale = ContentScale.Crop,
            )
        }
            ?: Box(
                modifier = Modifier.size(48.dp).clip(ShapeIconLarge).background(CardBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Apps, null, tint = SubtleGray, modifier = Modifier.size(24.dp))
            }

        Text(
            app.appName,
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(SpaceSm))
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
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(CardBg),
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

