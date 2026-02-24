/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

@Composable
internal fun SportsExpanded(event: IslandEvent.Sports, interactor: IslandActions) {
    val accent = accentColorFor(event)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        if (event.league.isNotEmpty()) {
            Text(
                event.league,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        StatusBadge(event.status, accent)

        if (event.team2Name.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamColumn(event.team1Name, event.team1Icon?.toScaledBitmap(48.dp))
                if (event.score1.isNotEmpty()) {
                    ScoreDisplay(event.score1, event.score2, accent)
                } else {
                    Text(
                        stringResource(R.string.ax_dynamic_bar_sports_vs),
                        color = SubtleGray,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Light,
                    )
                }
                TeamColumn(event.team2Name, event.team2Icon?.toScaledBitmap(48.dp))
            }
        } else {
            Text(
                event.team1Name,
                color = OnCardText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (event.statusDetail.isNotEmpty()) {
            Text(
                event.statusDetail,
                color = OnCardSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (event.commentary.isNotEmpty()) {
            Text(
                event.commentary,
                color = OnCardSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun RowScope.CompactSportsRow(event: IslandEvent.Sports) {
    val accent = accentColorFor(event)

    CompactTeamBadge(event.team1Name, event.team1Icon, accent)

    Spacer(Modifier.width(SpaceSm))

    if (event.team2Name.isNotEmpty()) {
        if (event.score1.isNotEmpty()) {
            Text(
                "${event.score1} - ${event.score2}",
                color = accent,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        } else {
            Text(
                stringResource(R.string.ax_dynamic_bar_sports_vs),
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(SpaceSm))

        CompactTeamBadge(event.team2Name, event.team2Icon, accent)
    } else {
        Text(
            event.team1Name,
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.width(SpaceSm))

    StatusBadge(event.status, accent)
}

@Composable
private fun CompactTeamBadge(name: String, icon: Drawable?, accent: Color) {
    icon?.let {
        Image(
            bitmap = it.toScaledBitmap(SizeCompactIcon),
            contentDescription = name,
            modifier = Modifier.size(SizeCompactIcon).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } ?: Box(
        modifier = Modifier.size(SizeCompactIcon).clip(CircleShape).background(accent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(3).uppercase(),
            color = accent,
            style = TsBadge,
        )
    }
}

@Composable
private fun TeamColumn(
    name: String,
    icon: ImageBitmap?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
        modifier = Modifier.width(80.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = name,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(3).uppercase(),
                    color = OnCardText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            name,
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScoreDisplay(score1: String, score2: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Text(
            score1,
            color = OnCardText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "-",
            color = SubtleGray,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
        )
        Text(
            score2,
            color = OnCardText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusBadge(status: IslandEvent.GameStatus, accent: Color) {
    val label = when (status) {
        IslandEvent.GameStatus.LIVE -> stringResource(R.string.ax_dynamic_bar_sports_live)
        IslandEvent.GameStatus.FINAL -> stringResource(R.string.ax_dynamic_bar_sports_final)
        IslandEvent.GameStatus.HALFTIME -> stringResource(R.string.ax_dynamic_bar_sports_halftime)
        IslandEvent.GameStatus.PRE_GAME -> stringResource(R.string.ax_dynamic_bar_sports_upcoming)
    }
    Surface(
        shape = ShapeChip,
        color = accent.copy(alpha = AlphaIconBg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            if (status == IslandEvent.GameStatus.LIVE) {
                PulsingDot(color = accent, size = 6.dp)
            }
            Text(label, color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

