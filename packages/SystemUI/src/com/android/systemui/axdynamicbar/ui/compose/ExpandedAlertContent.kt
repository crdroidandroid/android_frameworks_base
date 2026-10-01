package com.android.systemui.axdynamicbar.ui.compose

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R
import java.util.Date

@Composable
internal fun AlarmExpanded(event: IslandEvent.Alarm, interactor: IslandActions) {
    val tint = IslandTone.ATTENTION.tint
    ExpandedCardLayout(
        accentColor = tint,
        icon = {
            if (event.isRinging) PulsingDot(color = tint, size = 14.dp)
            else Icon(Icons.Filled.Alarm, null, tint = tint, modifier = Modifier.size(16.dp))
        },
        title = {
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) },
                color = OnCardText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Ringing chip and trigger time share the caption line instead of each taking a row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                if (event.isRinging) {
                    StatusChip(stringResource(R.string.ax_dynamic_bar_ringing), tint)
                }
                if (event.triggerTimeMs > 0L) {
                    Text(
                        DateFormat.getTimeFormat(LocalContext.current)
                            .format(Date(event.triggerTimeMs)),
                        color = SubtleGray,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        trailing = {
            ActionIconButton(
                icon = Icons.Filled.Close,
                tint = tint,
                bg = tint.copy(alpha = AlphaIconBg),
                contentDescription = stringResource(R.string.ax_dynamic_bar_dismiss),
                onClick = { interactor.dismissEvent(event) },
            )
        },
    )
}

@Composable
internal fun RowScope.CompactAlarmRow(
    event: IslandEvent.Alarm,
    interactor: IslandActions,
) {
    val tint = IslandTone.ATTENTION.tint
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(tint.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        if (event.isRinging) PulsingDot(color = tint, size = SizeIconSm)
        else Icon(Icons.Filled.Alarm, null, tint = tint, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (event.isRinging) {
            Text(stringResource(R.string.ax_dynamic_bar_ringing), color = tint, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (event.isRinging) {
        Spacer(Modifier.width(SpaceMd))
        Surface(
            onClick = { interactor.dismissEvent(event) },
            shape = ShapeChip,
            color = tint.copy(alpha = AlphaIconBg),
        ) {
            Text(
                stringResource(R.string.ax_dynamic_bar_dismiss),
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceSm),
            )
        }
    }
}
