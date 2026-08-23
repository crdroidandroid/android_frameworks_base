package com.android.systemui.volume.panel.component.appvolume.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.PlatformSliderDefaults
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.appvolume.ui.viewmodel.AppVolumePanelViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AppVolumeSliderViewModel
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlider
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderColors

private const val AppVolumePanelTestTag = "AppVolumePanel"
private val padding = 24.dp

@Composable
fun AppVolumePanelRoot(
    viewModel: AppVolumePanelViewModel,
    modifier: Modifier = Modifier,
) {
    val accessibilityTitle = stringResource(R.string.app_volume)
    val sliderViewModels by viewModel.sliderViewModels.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .sysuiResTag(AppVolumePanelTestTag)
                .semantics { paneTitle = accessibilityTitle }
                .padding(start = padding, top = padding, end = padding, bottom = 20.dp)
                .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(padding),
    ) {
        Text(
            text = stringResource(R.string.app_volume),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (sliderViewModels.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.app_volume_no_active_apps),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier =
                    Modifier.weight(weight = 1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (sliderViewModel in sliderViewModels) {
                    key(sliderViewModel) { AppVolumeSliderItem(sliderViewModel) }
                }
            }
        }

        Row(
            modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformOutlinedButton(onClick = viewModel::onSettingsClicked) {
                Text(text = stringResource(R.string.volume_panel_dialog_settings_button))
            }
            PlatformButton(onClick = viewModel::onDoneClicked) {
                Text(text = stringResource(R.string.inline_done_button))
            }
        }
    }
}

@Composable
private fun AppVolumeSliderItem(
    sliderViewModel: AppVolumeSliderViewModel,
    modifier: Modifier = Modifier,
) {
    val sliderState by sliderViewModel.slider.collectAsStateWithLifecycle()
    VolumeSlider(
        modifier = modifier.fillMaxWidth(),
        state = sliderState,
        onValueChange = { newValue -> sliderViewModel.onValueChanged(sliderState, newValue) },
        onValueChangeFinished = { sliderViewModel.onValueChangeFinished() },
        onIconTapped = { sliderViewModel.toggleMuted(sliderState) },
        sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
        hapticsViewModelFactory = sliderViewModel.getSliderHapticsViewModelFactory(),
        materialSliderColors = VolumeSliderColors.Defaults,
    )
}
