/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.os.UserHandle
import com.android.systemui.flashlight.domain.interactor.FlashlightInteractor
import com.android.systemui.flashlight.flags.FlashlightStrength
import com.android.systemui.flashlight.shared.model.FlashlightModel
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Observes flashlight state changes providing the [FlashlightModel]. */
class FlashlightTileDataInteractor
@Inject
constructor(
    private val flashlightController: FlashlightController,
    private val interactor: Lazy<FlashlightInteractor>,
) : QSTileDataInteractor<FlashlightModel> {
    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<FlashlightModel> = tileData()

    /**
     * An adapted version of the base class' [tileData] method for use in an old-style tile.
     *
     * TODO(b/299909989): Remove after the transition.
     */
    fun tileData(): Flow<FlashlightModel> =
        if (FlashlightStrength.isEnabled) interactor.get().state.onEach { currentTileModel = it }
        else
            conflatedCallbackFlow {
                val callback =
                    object : FlashlightController.FlashlightListener {
                        override fun onFlashlightChanged(enabled: Boolean) {
                            trySend(FlashlightModel.Available.Binary(enabled))
                        }

                        override fun onFlashlightError() {
                            trySend(FlashlightModel.Available.Binary(false))
                        }

                        override fun onFlashlightAvailabilityChanged(available: Boolean) {
                            trySend(
                                if (available)
                                    FlashlightModel.Available.Binary(flashlightController.isEnabled)
                                else FlashlightModel.Unavailable.Temporarily.CameraInUse
                            )
                        }
			override fun onFlashlightStrengthChanged(level: Int) {}
                    }
                flashlightController.addCallback(callback)
                awaitClose { flashlightController.removeCallback(callback) }
            }

    private var currentTileModel: FlashlightModel = FlashlightModel.Unavailable.Temporarily.Loading

    /** Temporary measure until we discard the old-style tile */
    fun getCurrentTileModel(): FlashlightModel = currentTileModel

    fun isAvailable(): Boolean {
        return if (FlashlightStrength.isUnexpectedlyInLegacyMode()) {
            false
        } else {
            interactor.get().deviceSupportsFlashlight
        }
    }

    /** Used to determine if the tile should be displayed or hidden. */
    override fun availability(user: UserHandle): Flow<Boolean> =
        if (FlashlightStrength.isEnabled) {
            interactor.get().state.map {
                it !is FlashlightModel.Unavailable.Permanently.NotSupported
            }
        } else flowOf(flashlightController.hasFlashlight())
}
