package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.flashlight.ui.composable.VerticalFlashlightSlider
import com.android.systemui.res.R

@Composable
internal fun RowScope.TorchPill(event: IslandEvent.Torch) {
    val style = eventStyleFor(event)
    style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(15.dp)) }
    Spacer(Modifier.width(SpaceSm))
    Text(stringResource(style.labelRes), color = OnCardText, style = PillPrimary)
    if (event.supportsLevel) {
        Spacer(Modifier.width(SpaceSm))
        val pct = (event.level.toFloat() / event.maxLevel * 100).toInt()
        Text("$pct%", color = style.accent, style = PillAccent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorchExpanded(
    event: IslandEvent.Torch,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    var isDragging by remember { mutableStateOf(false) }
    var localLevel by remember { mutableIntStateOf(event.level) }
    LaunchedEffect(event.level) { if (!isDragging) localLevel = event.level }
    val displayLevel = if (isDragging) localLevel else event.level

    val style = eventStyleFor(event)
    ExpandedCardLayout(
        accentColor = style.accent,
        icon = { style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(28.dp)) } },
        title = {
            Text(stringResource(style.labelRes), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            if (event.supportsLevel) {
                val pct = (displayLevel.toFloat() / event.maxLevel * 100).toInt()
                StatusChip("$pct%", style.accent)
            } else {
                StatusChip(stringResource(R.string.ax_dynamic_bar_on), style.accent)
            }
        },
        trailing = {
            CircleButton(
                color = RedAccent.copy(alpha = AlphaSubtle),
                size = SizeButton,
                onClick = { interactor.toggleTorch() },
            ) {
                Icon(Icons.Filled.FlashlightOff, null, tint = RedAccent, modifier = Modifier.size(22.dp))
            }
        },
        actions = if (event.supportsLevel) {
            {
                Box(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    VerticalFlashlightSlider(
                        valueRange = 1..event.maxLevel,
                        onValueChange = {
                            isDragging = true
                            localLevel = it
                            interactor.setTorchLevelTemporary(it)
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            interactor.setTorchLevel(it)
                        },
                        isEnabled = true,
                        levelValue = displayLevel,
                        hapticsViewModelFactory = hapticsViewModelFactory,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = style.accent,
                                activeTrackColor = style.accent,
                            ),
                    )
                }
            }
        } else null,
    )
}

@Composable
internal fun RowScope.CompactTorchRow(event: IslandEvent.Torch) {
    val style = eventStyleFor(event)
    style.icon?.let { Icon(it, null, tint = style.accent, modifier = Modifier.size(SpaceXxl)) }
    Spacer(Modifier.width(SpaceSm))
    Text(
        stringResource(style.labelRes),
        color = SubtleGray,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.weight(1f),
    )
    if (event.supportsLevel) {
        val pct = (event.level.toFloat() / event.maxLevel * 100).toInt()
        Text("$pct%", color = style.accent, style = PillAccent)
    } else {
        Text(stringResource(R.string.ax_dynamic_bar_on), color = style.accent, style = PillAccent)
    }
}

