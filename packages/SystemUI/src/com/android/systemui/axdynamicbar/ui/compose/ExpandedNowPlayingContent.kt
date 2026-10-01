/*
 * Copyright 2025-2026 AxionOS
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*

@Composable
internal fun NowPlayingExpanded(event: IslandEvent.NowPlaying, interactor: IslandActions) {
    val context = LocalContext.current
    val tone = toneFor(event)
    val tint = tone.tint
    val inline = rememberActionsFitInline(event.actions.map { it.action })

    val actionButtons: @Composable () -> Unit = {
        event.actions.forEach { notifAction ->
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
        icon = { Icon(Icons.Filled.MusicNote, null, tint = tint, modifier = Modifier.size(16.dp)) },
        title = {
            Text(
                event.songTitle,
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.artist.isNotEmpty()) {
                Text(
                    event.artist,
                    color = OnCardSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        // The old "Now playing" trailing label repeated what the icon and title already say.
        trailing = if (inline && event.actions.isNotEmpty()) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                ) { actionButtons() }
            }
        } else null,
        actions = if (!inline && event.actions.isNotEmpty()) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpaceSm),
                ) { actionButtons() }
            }
        } else null,
    )
}
