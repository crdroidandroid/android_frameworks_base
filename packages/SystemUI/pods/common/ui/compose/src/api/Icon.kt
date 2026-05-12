/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.common.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asImageBitmap

/**
 * Icon composable that draws [icon] using [tint].
 *
 * Note: You can use [Color.Unspecified] to disable the tint and keep the original icon colors.
 *
 * Note: Some drawables aren't compatible with [rememberDrawablePainter], used here for
 * [Icon.Loaded] icons, and won't be resized from their intrinsic size (b/394738023).
 */
@Composable
public fun Icon(
    icon: Icon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val contentDescription = icon.contentDescription?.load()
    when (icon) {
        is Icon.Loaded -> {
            if (icon.isBitmapImage) {
                Image(
                    icon.asImageBitmap(),
                    contentDescription,
                    modifier,
                )
            } else {
                Icon(
                    rememberDrawablePainter(icon.drawable),
                    contentDescription,
                    modifier,
                    tint,
                )
            }
        }
        is Icon.Resource -> {
            Icon(painterResource(icon.resId), contentDescription, modifier, tint)
        }
    }
}

/**
 * Icon composable that draws [icon] using [tint]. Pass null [tint] to use the default tint color.
 *
 * Note: You can use [Color.Unspecified] to disable the tint and keep the original icon colors.
 *
 * Note: Some drawables aren't compatible with [rememberDrawablePainter], used here for
 * [Icon.Loaded] icons, and won't be resized from their intrinsic size (b/394738023).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun Icon(icon: Icon, tint: (() -> Color)?, modifier: Modifier = Modifier) {
    val localContentColor = LocalContentColor.current
    val contentDescription = icon.contentDescription?.load()
    when (icon) {
        is Icon.Loaded -> {
            if (icon.isBitmapImage) {
                Image(
                    icon.asImageBitmap(),
                    contentDescription,
                    modifier,
                )
            } else {
                Icon(
                    rememberDrawablePainter(icon.drawable),
                    tint ?: { localContentColor },
                    contentDescription,
                    modifier,
                )
            }
        }
        is Icon.Resource -> {
            Icon(
                painterResource(icon.resId),
                tint ?: { localContentColor },
                contentDescription,
                modifier,
            )
        }
    }
}
