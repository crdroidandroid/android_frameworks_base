/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.cell.ui.mapper

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileIcon
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

class MobileDataTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
    @ShadeDisplayAware private val context: Context,
    @Main private val handler: Handler,
) : QSTileDataToStateMapper<MobileDataTileModel> {

    override fun map(config: QSTileConfig, data: MobileDataTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val label = resources.getString(R.string.quick_settings_cellular_detail_title)

            when (val dataIcon = data.icon) {
                is MobileDataTileIcon.SignalIcon -> {
                    val signalDrawable = SignalDrawable(context, handler)
                    signalDrawable.setLevel(dataIcon.level)
                    icon = Icon.Loaded(signalDrawable, contentDescription = null)
                }

                is MobileDataTileIcon.ResourceIcon -> {
                    icon =
                        Icon.Loaded(
                            resources.getDrawable(dataIcon.resourceIcon.resId, theme),
                            contentDescription = null,
                            dataIcon.resourceIcon.resId,
                        )
                }
            }

            contentDescription = "$label".toString()
            activationState =
                if (data.isSimActive) {
                    if (data.isEnabled) {
                        QSTileState.ActivationState.ACTIVE
                    } else {
                        QSTileState.ActivationState.INACTIVE
                    }
                } else {
                    QSTileState.ActivationState.UNAVAILABLE
                }
            supportedActions =
                setOf(
                    QSTileState.UserAction.CLICK,
                    QSTileState.UserAction.LONG_CLICK,
                    QSTileState.UserAction.TOGGLE_CLICK,
                )
        }
}
