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

import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon

@Composable
fun Icon(icon: Icon, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val contentDescription = icon.contentDescription?.load()
    val context = LocalContext.current

    when (icon) {
        is Icon.Loaded -> {
            val img = remember(icon.drawable) { icon.drawable.toBitmap().asImageBitmap() }
            M3Icon(img, contentDescription, modifier, tint)
        }
        is Icon.Resource -> {
            val drawable = remember(icon.res) { AppCompatResources.getDrawable(context, icon.res) }

            when (drawable) {
                is AnimatedVectorDrawable -> {
                    M3Icon(
                        painter = rememberDrawablePainter(drawable),
                        contentDescription = contentDescription,
                        modifier = modifier,
                        tint = tint
                    )
                }
                is VectorDrawable, is BitmapDrawable -> {
                    M3Icon(
                        painterResource(icon.res),
                        contentDescription,
                        modifier,
                        tint
                    )
                }
                else -> {
                    val img = remember(drawable) { drawable?.toBitmap()?.asImageBitmap() }
                    if (img != null) {
                        M3Icon(img, contentDescription, modifier, tint)
                    }
                }
            }
        }
    }
}
