/*
 * Copyright (C) 2026 crDroid Android Project
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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import com.android.systemui.res.R

/** Number of columns the default tile margins are designed for. */
private const val BASE_SPACING_COLUMNS = 4

/** Scales the grid gaps down proportionally for grids denser than [BASE_SPACING_COLUMNS]. */
private fun spacingScale(columns: Int): Float =
    (BASE_SPACING_COLUMNS.toFloat() / columns.coerceAtLeast(1)).coerceAtMost(1f)

/** Horizontal gap between QS tiles, shared by QQS and QS so tiles don't shift on expansion. */
@Composable
@ReadOnlyComposable
fun qsTileColumnSpacing(columns: Int): Dp =
    dimensionResource(R.dimen.qs_tile_margin_horizontal) * spacingScale(columns)

/** Vertical gap between QS tile rows. Classic tiles use the same gap in both directions. */
@Composable
@ReadOnlyComposable
fun qsTileRowSpacing(columns: Int, classicStyle: Boolean): Dp =
    if (classicStyle) {
        qsTileColumnSpacing(columns)
    } else {
        dimensionResource(R.dimen.qs_tile_margin_vertical) * spacingScale(columns)
    }
