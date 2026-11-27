/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.shade.ui.ShadeColors
import kotlinx.coroutines.flow.StateFlow

internal const val CARD_STYLE_FROSTED = 0
internal const val CARD_STYLE_SHADE = 1
internal const val CARD_STYLE_SOLID_BLACK = 2

@Immutable
internal data class IslandCardStyle(val mode: Int = CARD_STYLE_FROSTED, val shadeStyle: Int = 0)

internal val LocalIslandCardStyle = compositionLocalOf { IslandCardStyle() }

@Composable
internal fun ProvideIslandCardStyle(
    mode: StateFlow<Int>,
    shadeStyle: StateFlow<Int>,
    content: @Composable () -> Unit,
) {
    val cardMode by mode.collectAsStateWithLifecycle()
    val shade by shadeStyle.collectAsStateWithLifecycle()
    val style = remember(cardMode, shade) { IslandCardStyle(cardMode, shade) }
    CompositionLocalProvider(LocalIslandCardStyle provides style, content = content)
}

// Solid black cards always carry light content, whatever the system theme.
@Composable
internal fun IslandCardTheme(content: @Composable () -> Unit) {
    if (LocalIslandCardStyle.current.mode == CARD_STYLE_SOLID_BLACK) {
        PlatformTheme(isDarkTheme = true, content = content)
    } else {
        content()
    }
}

/** Surface of an expanded card, in the style the user picked. Apply after the card's clip. */
@Composable
internal fun Modifier.islandCard(cornerRadius: Dp): Modifier {
    val style = LocalIslandCardStyle.current
    return when (style.mode) {
        CARD_STYLE_SOLID_BLACK -> background(Color.Black)
        CARD_STYLE_SHADE -> {
            val context = LocalContext.current
            val night = isSystemInDarkTheme()
            val assets = LocalConfiguration.current.assetsSeq
            val colors = remember(context, style.shadeStyle, night, assets) {
                Color(ShadeColors.shadePanel(context, true, false)) to
                    Color(ShadeColors.shadePanel(context, false, false))
            }
            islandBlur(cornerRadius, fallbackColor = colors.second, tint = colors.first)
        }
        else -> islandBlur(cornerRadius, CardBg)
    }
}
