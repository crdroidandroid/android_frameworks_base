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

package com.android.systemui.smartpixel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.compose.PlatformButton
import com.android.compose.theme.PlatformTheme
import com.android.systemui.smartpixel.domain.SmartPixelSettings
import com.android.systemui.smartpixel.domain.SmartPixelSettings.Companion.MAX_PERCENT
import com.android.systemui.smartpixel.domain.SmartPixelSettings.Companion.MIN_PERCENT
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import javax.inject.Inject

class SmartPixelDialogDelegate @Inject constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val settings: SmartPixelSettings,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
) {

    fun createDialog(): SystemUIDialog =
        sysuiDialogFactory.create(context = shadeDialogContextInteractor.context) {
            SmartPixelDialogContent(it)
        }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun SmartPixelDialogContent(dialog: SystemUIDialog) {
        val isCurrentlyInDarkTheme = isSystemInDarkTheme()
        val cachedDarkTheme = remember { isCurrentlyInDarkTheme }
        PlatformTheme(isDarkTheme = cachedDarkTheme) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.quick_settings_smart_pixels_label)) },
                content = { SmartPixelControls() },
                positiveButton = {
                    PlatformButton(onClick = { dialog.dismiss() }) {
                        Text(stringResource(R.string.quick_settings_done))
                    }
                },
                contentBottomPadding = 8.dp,
            )
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun SmartPixelControls() {
        val isEnabled by settings.isEnabled.collectAsState()
        val currentPercent by settings.percent.collectAsState()
        var sliderValue by remember(currentPercent) { mutableFloatStateOf(currentPercent.toFloat()) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (isEnabled) R.string.quick_settings_smart_pixels_on
                        else R.string.quick_settings_smart_pixels_off,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { settings.setEnabled(it) },
                    thumbContent = {
                        Icon(
                            imageVector = if (isEnabled) Icons.Filled.Check else Icons.Filled.Clear,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.smart_pixels_percent_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.smart_pixels_percent_format,
                            sliderValue.toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { settings.setPercent(sliderValue.toInt()) },
                    valueRange = MIN_PERCENT.toFloat()..MAX_PERCENT.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    enabled = isEnabled,
                )
            }
        }
    }
}
