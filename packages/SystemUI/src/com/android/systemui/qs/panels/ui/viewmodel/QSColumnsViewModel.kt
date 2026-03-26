/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.qs.panels.domain.interactor.LargeTileSpanInteractor
import com.android.systemui.qs.panels.domain.interactor.QSColumnsInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * View model for the number of columns that should be shown in a QS grid.
 * * Create it with a [MediaLocation] to halve the number of columns when media should show in a row
 *   with the tiles.
 * * Create it with a `null` [MediaLocation] to ignore media visibility (useful for edit mode).
 */
class QSColumnsViewModel
@AssistedInject
constructor(
    interactor: QSColumnsInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    private val largeTileSpanInteractor: LargeTileSpanInteractor,
    @Assisted @MediaLocation mediaLocation: Int?,
    @Assisted mediaUiBehavior: MediaUiBehavior?,
) : HydratedActivatable() {

    val columns by derivedStateOf {
        if (mediaInRowInLandscapeViewModel?.shouldMediaShowInRow == true) {
            columnsWithoutMedia / 2
        } else {
            columnsWithoutMedia
        }
    }

    private val maxSpan by
        largeTileSpanInteractor.tileMaxWidth.hydratedStateOf(
            initialValue = largeTileSpanInteractor.defaultTileMaxWidth
        )

    private val useExtraLargeTiles by
        largeTileSpanInteractor.useExtraLargeTiles.hydratedStateOf(initialValue = false)

    private val classicStyle by
        largeTileSpanInteractor.classicStyle.hydratedStateOf(initialValue = false)

    val largeSpan: Int by derivedStateOf {
        if (classicStyle) {
            1
        } else if (useExtraLargeTiles) {
            if (columns > maxSpan) columns / 2 else columns
        } else {
            largeTileSpanInteractor.defaultTileMaxWidth
        }
    }

    private val mediaInRowInLandscapeViewModel =
        if (mediaLocation != null && mediaUiBehavior != null) {
            mediaInRowInLandscapeViewModelFactory.create(mediaLocation, mediaUiBehavior)
        } else {
            null
        }

    private val columnsWithoutMedia by interactor.columns.hydratedStateOf()

    override suspend fun onActivated() {
        coroutineScope { launch { mediaInRowInLandscapeViewModel?.activate() } }
    }

    @AssistedFactory
    interface Factory {
        fun create(mediaLocation: Int?, mediaUiBehavior: MediaUiBehavior?): QSColumnsViewModel

        fun createWithoutMediaTracking() = create(null, null)
    }
}
