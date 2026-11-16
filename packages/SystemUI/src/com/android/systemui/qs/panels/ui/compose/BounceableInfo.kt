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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.runtime.Stable
import com.android.compose.animation.Bounceable
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel

@Stable
data class BounceableInfo(
    val bounceable: BounceableTileViewModel,
    val previousTile: Bounceable?,
    val nextTile: Bounceable?,
    val bounceEnd: Boolean,
) {
    /** Whether the container has any side it can grow towards when bouncing. */
    val canBounceContainer: Boolean
        get() = previousTile != null || nextTile != null || bounceEnd
}

fun List<BounceableTileViewModel>.bounceableInfo(
    sizedTile: SizedTile<TileViewModel>,
    index: Int,
    column: Int,
    columns: Int,
    isFirstInRow: Boolean,
    isLastInRow: Boolean,
    bouncesContainer: (index: Int) -> Boolean = { true },
): BounceableInfo {
    // A tile may be the last in the row without being on the last column
    val onLastColumn = sizedTile.onLastColumn(column, columns)

    // Only look for neighbor bounceables if they are on the same row. Neighbors that don't bounce
    // their container never shrink to make room, so don't grow towards them either.
    val nextBounces = !isLastInRow && bouncesContainer(index + 1)
    val previousTile = getOrNull(index - 1)?.takeIf { !isFirstInRow && bouncesContainer(index - 1) }
    val nextTile = getOrNull(index + 1)?.takeIf { nextBounces }
    val bounceEnd = !onLastColumn && (isLastInRow || nextBounces)

    return BounceableInfo(this[index], previousTile, nextTile, bounceEnd)
}

private fun <T> SizedTile<T>.onLastColumn(column: Int, columns: Int): Boolean {
    return column == columns - width
}
