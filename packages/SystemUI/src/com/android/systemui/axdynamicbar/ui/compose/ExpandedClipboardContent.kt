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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

private const val MAX_PREVIEW_DISPLAY_CHARS = 500

private fun IslandEvent.ClipboardItem.isCurrentClip(event: IslandEvent.Clipboard): Boolean =
    isImage == event.isImage &&
        if (isImage) imageUri != null && imageUri == event.imageUri
        else preview == event.preview

@Composable
internal fun ClipboardExpanded(event: IslandEvent.Clipboard, interactor: IslandActions) {
    val current = event.items.firstOrNull { it.isCurrentClip(event) }
    val older = event.items.filter { it !== current }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceLg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(IndigoAccent.copy(alpha = AlphaFaint))
                .padding(SpaceXxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ContentCopy, null, tint = IndigoAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(SpaceLg))
            Text(
                stringResource(if (event.isImage) R.string.ax_dynamic_bar_image_copied else R.string.ax_dynamic_bar_copied),
                color = OnCardText,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        if (event.isImage) {
            if (current != null) ClipboardStashItem(current, interactor)
        } else {
            ClipboardSingleItem(event, interactor)
        }

        if (older.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(SpaceMd)) {
                older.forEach { item ->
                    key(item.id) { ClipboardStashItem(item, interactor) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            if (event.items.isNotEmpty()) {
                ActionChip(
                    label = stringResource(R.string.ax_dynamic_bar_clear_all),
                    icon = Icons.Filled.DeleteSweep,
                    color = IndigoAccent,
                    bg = IndigoAccent.copy(alpha = AlphaIconBg),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        event.items.forEach { interactor.removeClipboardItem(it.id) }
                        interactor.dismissEvent(event)
                    },
                )
            }
            ActionChip(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                icon = Icons.Filled.Close,
                color = IndigoAccent,
                bg = IndigoAccent.copy(alpha = AlphaIconBg),
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}

@Composable
private fun ClipboardStashItem(
    item: IslandEvent.ClipboardItem,
    interactor: IslandActions,
) {
    val copy: () -> Unit = {
        val uri = item.imageUri
        if (item.isImage && uri != null) interactor.copyUriToClipboard(uri)
        else interactor.copyToClipboard(item.preview)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeSm)
            .background(DarkCard)
            .clickable(onClick = copy)
            .padding(SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        if (item.isImage) {
            Box(
                modifier = Modifier
                    .size(SizeCompactIcon)
                    .clip(ShapeCompact)
                    .background(IndigoAccent.copy(alpha = AlphaIconBg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Photo, null, tint = IndigoAccent, modifier = Modifier.size(18.dp))
            }
        } else if (item.isUrl) {
            Box(
                modifier = Modifier
                    .size(SizeCompactIcon)
                    .clip(ShapeCompact)
                    .background(IndigoAccent.copy(alpha = AlphaIconBg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Link, null, tint = IndigoAccent, modifier = Modifier.size(18.dp))
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
            if (item.isImage) {
                Text(
                    stringResource(R.string.ax_dynamic_bar_image),
                    color = OnCardText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                if (item.preview.isNotEmpty()) {
                    Text(
                        item.preview.take(MAX_PREVIEW_DISPLAY_CHARS),
                        color = SubtleGray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    item.preview.take(MAX_PREVIEW_DISPLAY_CHARS),
                    color = OnCardText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = stringResource(android.R.string.copy),
            tint = IndigoAccent,
            modifier = Modifier
                .size(SizeIconSm)
                .clickable(onClick = copy),
        )
    }
}

@Composable
private fun ClipboardSingleItem(event: IslandEvent.Clipboard, interactor: IslandActions) {
    if (event.preview.isNotEmpty()) {
        val copy: () -> Unit = {
            interactor.copyToClipboard(event.preview)
            interactor.collapseIsland()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeSm)
                .background(DarkCard)
                .clickable(onClick = copy)
                .padding(SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            if (event.isUrl) {
                Box(
                    modifier = Modifier
                        .size(SizeCompactIcon)
                        .clip(ShapeCompact)
                        .background(IndigoAccent.copy(alpha = AlphaIconBg)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Link, null, tint = IndigoAccent, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                event.preview.take(MAX_PREVIEW_DISPLAY_CHARS),
                color = OnCardText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(android.R.string.copy),
                tint = IndigoAccent,
                modifier = Modifier
                    .size(SizeIconSm)
                    .wrapContentWidth()
                    .clickable(onClick = copy),
            )
        }
    }

    if (event.isUrl) {
        ActionChip(
            label = stringResource(R.string.ax_dynamic_bar_open),
            icon = Icons.Filled.Link,
            color = IndigoAccent,
            bg = IndigoAccent.copy(alpha = AlphaIconBg),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                interactor.openUrl(event.preview)
                interactor.collapseIsland()
            },
        )
    }
}

@Composable
internal fun RowScope.CompactClipboardRow(
    event: IslandEvent.Clipboard,
    interactor: IslandActions,
) {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(BlueAccent.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.ContentCopy, null, tint = BlueAccent, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Text(
        event.preview.take(MAX_PREVIEW_DISPLAY_CHARS).ifEmpty {
            stringResource(if (event.isImage) R.string.ax_dynamic_bar_image_copied else R.string.ax_dynamic_bar_copied)
        },
        color = OnCardText,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(SpaceMd))
    Surface(
        onClick = { interactor.dismissEvent(event) },
        shape = ShapeChip,
        color = IndigoAccent.copy(alpha = AlphaIconBg),
    ) {
        Text(
            stringResource(R.string.ax_dynamic_bar_dismiss),
            color = IndigoAccent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceSm),
        )
    }
}
