package com.android.systemui.axdynamicbar.ui.compose

import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

/** A peripheral battery level: alert role when low, positive otherwise. */
@Composable
private fun batteryLevelColor(level: Int): Color =
    if (level > 20) IslandTone.POSITIVE.tint else IslandTone.ALERT.tint

@Composable
internal fun ChargingExpanded(event: IslandEvent.Charging) {
    val tint = IslandTone.POSITIVE.tint
    ExpandedCardLayout(
        accentColor = tint,
        icon = {
            Icon(Icons.Filled.BatteryChargingFull, null, tint = tint, modifier = Modifier.size(16.dp))
        },
        title = {
            Text(
                if (event.isWireless) stringResource(R.string.ax_dynamic_bar_wireless_charging)
                else stringResource(R.string.ax_dynamic_bar_charging),
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                StatusChip("${event.level}%", tint)
                if (event.isPowerSave) {
                    StatusChip(stringResource(R.string.ax_dynamic_bar_battery_saver), IslandTone.ATTENTION.tint)
                }
                // Time-to-full joins the caption line instead of a trailing column of its own.
                if (!event.timeRemaining.isNullOrEmpty()) {
                    Text(
                        "${event.timeRemaining} ${stringResource(R.string.ax_dynamic_bar_until_full)}",
                        color = SubtleGray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

@Composable
internal fun BluetoothExpanded(event: IslandEvent.Bluetooth, interactor: IslandActions) {
    val artwork = event.deviceImage
    val earbuds =
        listOfNotNull(
            event.leftBatteryLevel?.let {
                BudBattery(R.string.ax_dynamic_bar_left_earbud, it, mirror = false)
            },
            event.rightBatteryLevel?.let {
                BudBattery(R.string.ax_dynamic_bar_right_earbud, it, mirror = true)
            },
        )
    val showSingleBattery = earbuds.isEmpty() && event.caseBatteryLevel == null
    val tint = IslandTone.ACCENT.tint
    ExpandedCardLayout(
        accentColor = tint,
        // Product artwork is the tile's identity and gets real room; a plain device glyph sits in
        // the standard badge like every other tile.
        iconSize = if (artwork != null) 44.dp else SizeIconBadge,
        iconBackground = artwork == null,
        icon = {
            val image = artwork ?: event.deviceIcon
            if (image != null) {
                val size = if (artwork != null) 44.dp else 18.dp
                Image(
                    bitmap = image.toSquareScaledBitmap(size),
                    contentDescription =
                        event.deviceTypeLabel.ifEmpty {
                            stringResource(R.string.ax_dynamic_bar_bluetooth_device)
                        },
                    modifier = Modifier.size(size),
                )
            } else {
                Icon(Icons.Filled.Bluetooth, null, tint = tint, modifier = Modifier.size(16.dp))
            }
        },
        title = {
            Text(
                event.deviceName,
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                StatusChip(
                    event.deviceTypeLabel.ifEmpty { stringResource(R.string.ax_dynamic_bar_connected) },
                    tint,
                )
                if (showSingleBattery && event.batteryLevel >= 0) {
                    Text(
                        "${event.batteryLevel}%",
                        color = batteryLevelColor(event.batteryLevel),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            event.caseBatteryLevel?.let { level ->
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.dynamic_island_earbuds_case),
                        contentDescription = null,
                        tint = SubtleGray,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.ax_dynamic_bar_earbuds_case),
                        color = SubtleGray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Text(
                        "$level%",
                        color = batteryLevelColor(level),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        // Disconnect moves into the header row instead of a full-width 48 dp band underneath.
        trailing = {
            ActionIconButton(
                icon = Icons.Filled.BluetoothDisabled,
                tint = OnDestructiveText,
                bg = DestructiveBg,
                contentDescription = stringResource(R.string.ax_dynamic_bar_disconnect),
                onClick = { interactor.disconnectBluetooth(event.address) },
            )
        },
        actions = if (earbuds.isNotEmpty()) {
            { BluetoothEarbudBatteries(earbuds) }
        } else null,
    )
}

private data class BudBattery(val labelRes: Int, val level: Int, val mirror: Boolean)

@Composable
private fun BluetoothEarbudBatteries(batteries: List<BudBattery>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        batteries.forEach { battery ->
            Row(
                modifier =
                    Modifier.weight(1f)
                        .heightIn(min = 48.dp)
                        .fillMaxHeight()
                        .clip(ShapeSm)
                        .background(IslandTone.ACCENT.tint.copy(alpha = AlphaBorder))
                        .semantics(mergeDescendants = true) {}
                        .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.dynamic_island_earbud),
                    contentDescription = null,
                    tint = OnCardText.copy(alpha = 0.85f),
                    modifier =
                        Modifier.size(24.dp).graphicsLayer { scaleX = if (battery.mirror) -1f else 1f },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(battery.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = SubtleGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${battery.level}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = batteryLevelColor(battery.level),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HotspotExpanded(event: IslandEvent.Hotspot) {
    val tint = toneFor(event).tint
    ExpandedCardLayout(
        accentColor = tint,
        icon = { Icon(Icons.Filled.Wifi, null, tint = tint, modifier = Modifier.size(16.dp)) },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_hotspot_active), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            StatusChip(
                when (event.numDevices) {
                    0 -> stringResource(R.string.ax_dynamic_bar_no_devices)
                    1 -> stringResource(R.string.ax_dynamic_bar_one_device_connected)
                    else -> stringResource(R.string.ax_dynamic_bar_devices_connected, event.numDevices)
                },
                tint,
            )
        },
        trailing = { PulsingDot(color = tint, size = SpaceMd) },
    )
}

@Composable
internal fun RingerModeExpanded(event: IslandEvent.RingerMode, interactor: IslandActions) {
    val style = eventStyleFor(event)
    ExpandedCardLayout(
        accentColor = style.accent,
        icon = { style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(16.dp)) } },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_sound_mode), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            StatusChip(stringResource(style.labelRes), style.accent)
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceSm),
            ) {
                RingerCard(
                    isSelected = event.mode == AudioManager.RINGER_MODE_NORMAL,
                    icon = Icons.Filled.VolumeUp,
                    label = stringResource(R.string.ax_dynamic_bar_ring),
                    accent = IslandTone.ACCENT.tint,
                    onClick = { interactor.setRingerMode(AudioManager.RINGER_MODE_NORMAL) },
                    modifier = Modifier.weight(1f),
                )
                RingerCard(
                    isSelected = event.mode == AudioManager.RINGER_MODE_VIBRATE,
                    icon = Icons.Filled.Vibration,
                    label = stringResource(R.string.ax_dynamic_bar_vibrate),
                    accent = IslandTone.ATTENTION.tint,
                    onClick = { interactor.setRingerMode(AudioManager.RINGER_MODE_VIBRATE) },
                    modifier = Modifier.weight(1f),
                )
                RingerCard(
                    isSelected = event.mode == AudioManager.RINGER_MODE_SILENT,
                    icon = Icons.Filled.VolumeOff,
                    label = stringResource(R.string.ax_dynamic_bar_silent),
                    accent = IslandTone.ATTENTION.tint,
                    onClick = { interactor.setRingerMode(AudioManager.RINGER_MODE_SILENT) },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RingerCard(
    isSelected: Boolean,
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by
        animateColorAsState(
            targetValue = if (isSelected) accent.copy(alpha = AlphaIconBg) else OnCardText.copy(alpha = AlphaFaint),
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "ringer_bg",
        )
    val tint by
        animateColorAsState(
            targetValue = if (isSelected) accent else SubtleGray,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "ringer_tint",
        )

    Surface(
        onClick = onClick,
        modifier = modifier.semantics { selected = isSelected },
        shape = ShapeLg,
        color = bg,
    ) {
        // The tile is already inset; the generous padding these used to carry just made it tall.
        Column(
            modifier = Modifier.padding(vertical = SpaceMd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun VpnExpanded(event: IslandEvent.Vpn) {
    val style = eventStyleFor(event)
    ExpandedCardLayout(
        accentColor = style.accent,
        icon = { style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(16.dp)) } },
        title = {
            Text(
                stringResource(style.labelRes),
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
            )
            StatusChip(
                if (event.isValidated) stringResource(R.string.ax_dynamic_bar_secured)
                else stringResource(R.string.ax_dynamic_bar_connecting),
                style.accent,
            )
        },
        trailing = { PulsingDot(color = style.accent, size = SpaceMd) },
    )
}

@Composable
internal fun RowScope.CompactBluetoothRow(event: IslandEvent.Bluetooth) {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(IslandTone.ACCENT.tint.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        val image = event.deviceImage ?: event.deviceIcon
        if (image != null) {
            Image(bitmap = image.toSquareScaledBitmap(SizeIconSm), null, modifier = Modifier.size(SizeIconSm))
        } else {
            Icon(Icons.Filled.Bluetooth, null, tint = IslandTone.ACCENT.tint, modifier = Modifier.size(18.dp))
        }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.deviceName,
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            event.deviceTypeLabel.ifEmpty { stringResource(R.string.ax_dynamic_bar_connected) },
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    event.batterySummary()?.let { summary ->
        Spacer(Modifier.width(SpaceMd))
        val level = event.leftBatteryLevel ?: event.rightBatteryLevel ?: event.batteryLevel
        Text(
            summary,
            color = batteryLevelColor(level),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun RowScope.CompactHotspotRow(event: IslandEvent.Hotspot) {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(toneFor(event).tint.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Wifi, null, tint = toneFor(event).tint, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(stringResource(R.string.ax_dynamic_bar_hotspot), color = OnCardText, style = MaterialTheme.typography.bodySmall)
        Text(
            when (event.numDevices) {
                0 -> stringResource(R.string.ax_dynamic_bar_no_devices)
                1 -> stringResource(R.string.ax_dynamic_bar_one_device)
                else -> stringResource(R.string.ax_dynamic_bar_hotspot_devices, event.numDevices)
            },
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    PulsingDot(color = toneFor(event).tint, size = 7.dp)
}

@Composable
internal fun RowScope.CompactChargingRow(event: IslandEvent.Charging) {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(IslandTone.POSITIVE.tint.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.BatteryChargingFull,
            null,
            tint = IslandTone.POSITIVE.tint,
            modifier = Modifier.size(18.dp),
        )
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            if (event.isWireless) stringResource(R.string.ax_dynamic_bar_wireless_charging)
            else stringResource(R.string.ax_dynamic_bar_charging),
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
        )
        val saverLabel = if (event.isPowerSave) stringResource(R.string.ax_dynamic_bar_saver) else null
        Text(
            buildString {
                append("${event.level}%")
                saverLabel?.let { append(" · $it") }
            },
            color = if (event.isPowerSave) IslandTone.ATTENTION.tint else IslandTone.POSITIVE.tint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun RowScope.CompactRingerRow(event: IslandEvent.RingerMode) {
    val style = eventStyleFor(event)
    Box(
        modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(style.accent.copy(alpha = AlphaStatusChip)),
        contentAlignment = Alignment.Center,
    ) {
        style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(18.dp)) }
    }
    Spacer(Modifier.width(SpaceLg))
    Text(event.label, color = OnCardText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
}

@Composable
internal fun RowScope.CompactVpnRow() {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(IslandTone.ACCENT.tint.copy(alpha = AlphaIconBg)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.VpnKey, null, tint = IslandTone.ACCENT.tint, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Text(stringResource(R.string.ax_dynamic_bar_vpn_active), color = OnCardText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    PulsingDot(color = IslandTone.POSITIVE.tint, size = 7.dp)
}
