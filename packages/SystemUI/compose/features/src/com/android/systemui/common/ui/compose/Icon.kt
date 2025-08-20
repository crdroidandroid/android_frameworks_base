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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import com.android.systemui.common.shared.model.Icon
import com.android.compose.ui.graphics.painter.rememberDrawablePainter

/**
 * Icon composable that draws [icon] using [tint].
 *
 * Note: You can use [Color.Unspecified] to disable the tint and keep the original icon colors.
 */
@Composable
fun Icon(icon: Icon, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
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
            val drawable = remember(icon.res) {
                ContextCompat.getDrawable(context, icon.res)
            }

            if (drawable != null) {
                val bitmap = remember(drawable) { drawable.toBitmap() }
                val croppedBitmap = remember(bitmap) { removeExtraSpaces(bitmap) }
                Image(
                    bitmap = croppedBitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = modifier,
                )
            }
        }
    }
}

private fun removeExtraSpaces(bitmap: Bitmap): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    var top = 0
    var left = 0
    var right = width - 1
    var bottom = height - 1
    loop@ for (y in 0 until height) {
        for (x in 0 until width) {
            if (bitmap.getPixel(x, y) != 0) {
                top = y
                break@loop
            }
        }
    }
    loop@ for (y in height - 1 downTo 0) {
        for (x in 0 until width) {
            if (bitmap.getPixel(x, y) != 0) {
                bottom = y
                break@loop
            }
        }
    }
    loop@ for (x in 0 until width) {
        for (y in 0 until height) {
            if (bitmap.getPixel(x, y) != 0) {
                left = x
                break@loop
            }
        }
    }
    loop@ for (x in width - 1 downTo 0) {
        for (y in 0 until height) {
            if (bitmap.getPixel(x, y) != 0) {
                right = x
                break@loop
            }
        }
    }
    return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
}
