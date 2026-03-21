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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon

/**
 * Icon composable that draws [icon] using [tint].
 *
 * Note: You can use [Color.Unspecified] to disable the tint and keep the original icon colors.
 *
 * Note: Some drawables aren't compatible with [rememberDrawablePainter], used here for
 * [Icon.Loaded] icons, and won't be resized from their intrinsic size (b/394738023).
 */
@Composable
fun Icon(icon: Icon, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    IconInternal(icon, modifier, tint)
}

/**
 * Icon composable that draws [icon] using [tint]. Pass null [tint] to use the default tint color.
 *
 * Note: You can use [Color.Unspecified] to disable the tint and keep the original icon colors.
 *
 * Note: Some drawables aren't compatible with [rememberDrawablePainter], used here for
 * [Icon.Loaded] icons, and won't be resized from their intrinsic size (b/394738023).
 */
@Composable
fun Icon(icon: Icon, tint: (() -> Color)?, modifier: Modifier = Modifier) {
    IconInternal(icon, modifier, tint?.invoke() ?: LocalContentColor.current)
}

@Composable
private fun IconInternal(icon: Icon, modifier: Modifier, tint: Color) {
    val context = LocalContext.current
    val contentDescription = icon.contentDescription?.load()

    when (icon) {
        is Icon.Loaded -> {
            Icon(
                bitmap = remember(icon.drawable) {
                    icon.drawable.toBitmap().asImageBitmap()
                },
                contentDescription = contentDescription,
                modifier = modifier,
                tint = tint,
            )
        }
        is Icon.Resource -> {
            val drawable = remember(icon.resId) {
                ContextCompat.getDrawable(context, icon.resId)
            }
            if (drawable != null) {
                Image(
                    painter = rememberDrawablePainter(drawable),
                    contentDescription = contentDescription,
                    colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
                    modifier = modifier,
                )
            }
        }
    }
}
