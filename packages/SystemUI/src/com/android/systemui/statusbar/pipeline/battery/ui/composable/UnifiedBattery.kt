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

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastFirstOrNull
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryFrame
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import com.android.systemui.statusbar.pipeline.battery.shared.ui.PathSpec
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a battery directly on to a [Canvas]. The canvas is scaled to fill its container, and the
 * resulting battery is scaled using a FIT_CENTER type scaling that preserves the aspect ratio.
 */
@Composable
fun BatteryCanvas(
    path: PathSpec,
    innerWidth: Float,
    innerHeight: Float,
    glyphs: List<BatteryGlyph>,
    level: Int?,
    isFull: Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {

    val totalWidth by
        remember(glyphs) {
            mutableFloatStateOf(
                if (glyphs.isEmpty()) {
                    0f
                } else {
                    // Pads in between each glyph, skipping the first
                    glyphs.drop(1).fold(glyphs.first().width) { acc: Float, next: BatteryGlyph ->
                        acc + INTER_GLYPH_PADDING_PX + next.width
                    }
                }
            )
        }

    Canvas(
        modifier = modifier.fillMaxSize().sysuiResTag(BatteryViewModel.TEST_TAG),
        contentDescription = contentDescription,
    ) {
        val scale = path.scaleTo(size.width, size.height)
        val colors = colorsProvider()

        scale(scale, pivot = Offset.Zero) {
            if (isFull) {
                // Saves a layer since we don't need background here
                drawPath(path = path.path, color = colors.fill)
            } else {
                // First draw the body
                val bgColor =
                    if (glyphs.isEmpty()) {
                        colors.backgroundOnly
                    } else {
                        colors.backgroundWithGlyph
                    }
                drawPath(path.path, bgColor)
                // Then draw the body, clipped to the fill level
                if (level != null && level > 0) {
                    clipRect(0f, 0f, level.scaledLevel(), innerHeight) {
                        drawRoundRect(
                            color = colors.fill,
                            topLeft = Offset.Zero,
                            size = Size(width = innerWidth, height = innerHeight),
                            cornerRadius = CornerRadius(2f),
                        )
                    }
                }
            }

            // Now draw the glyphs
            var horizontalOffset = (BatteryFrame.innerWidth - totalWidth) / 2
            for (glyph in glyphs) {
                // Move the glyph to the right spot
                val verticalOffset = (BatteryFrame.innerHeight - glyph.height) / 2
                inset(
                    // Never try and inset more than half of the available size - see b/400246091.
                    minOf(horizontalOffset, size.width / 2),
                    minOf(verticalOffset, size.height / 2),
                ) {
                    glyph.draw(this, colors)
                }

                horizontalOffset += glyph.width + INTER_GLYPH_PADDING_PX
            }
        }
    }
}

// Experimentally-determined value
private const val INTER_GLYPH_PADDING_PX = 0.8f

/** Calculate the right-edge of the clip for the fill-rect, based on a level of [0-100] */
private fun Int.scaledLevel(): Float {
    val endSide = BatteryFrame.innerWidth
    return ceil((toFloat() / 100f) * endSide)
}

/**
 * A battery icon that will optionally display the percentage inside. Battery state attributions are
 * layered on top with a cutout path around them for visibility.
 *
 * This icon is designed to be parameterized on the height. The only valid way to use it is by
 * explicitly setting `Modifier.height()`, and using `Modifier.wrapContentWidth()` together.
 */
@Composable
fun UnifiedBattery(
    viewModel: BatteryViewModel,
    isDarkProvider: () -> IsAreaDark,
    modifier: Modifier,
) {
    var bounds by remember { mutableStateOf(Rect()) }

    val colorProvider = {
        if (isDarkProvider().isDark(bounds)) {
            viewModel.colorProfile.dark
        } else {
            viewModel.colorProfile.light
        }
    }

    BatteryLayout(
        attribution = viewModel.attribution,
        levelProvider = { viewModel.level },
        isFullProvider = { viewModel.isFull },
        glyphsProvider = { viewModel.glyphList },
        colorsProvider = colorProvider,
        modifier =
            modifier.sysuiResTag(BatteryViewModel.TEST_TAG).onLayoutRectChanged {
                relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
        contentDescription = viewModel.contentDescription.load() ?: "",
    )
}

@Composable
fun BatteryLayout(
    attribution: BatteryGlyph?,
    levelProvider: () -> Int?,
    isFullProvider: () -> Boolean,
    glyphsProvider: () -> List<BatteryGlyph>,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier,
    contentDescription: String = "",
) {
    Layout(
        content = {
            BatteryBody(
                pathSpec = BatteryFrame.bodyPathSpec,
                levelProvider = levelProvider,
                glyphsProvider = glyphsProvider,
                isFullProvider = isFullProvider,
                colorsProvider = colorsProvider,
                modifier = Modifier.layoutId(BatteryMeasurePolicy.LayoutId.Frame),
                contentDescription = contentDescription,
            )
            if (attribution != null) {
                BatteryAttribution(
                    attr = attribution,
                    colorsProvider = colorsProvider,
                    modifier =
                        Modifier.layoutId(
                            BatteryMeasurePolicy.LayoutId.Attribution(wrapped = attribution)
                        ),
                )
            } else {
                BatteryCap(
                    colorsProvider = colorsProvider,
                    isFullProvider = isFullProvider,
                    modifier = Modifier.layoutId(BatteryMeasurePolicy.LayoutId.Cap),
                )
            }
        },
        measurePolicy = BatteryMeasurePolicy(),
        // [Offscreen] Enables the BlendMode.Clear usage for the battery attribution
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    )
}

class BatteryMeasurePolicy : MeasurePolicy {
    sealed class LayoutId {
        data object Frame : LayoutId()

        data object Cap : LayoutId()

        // We don't have to depend on the whole [BatteryGlyph] here, we just need to know the
        // size so we can scale and measure appropriately
        data class Attribution(val wrapped: BatteryGlyph) : LayoutId()
    }

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val batteryFrame = measurables.fastFirst { it.layoutId == LayoutId.Frame }

        // We will scale the entire battery icon based on the given height
        val scale = constraints.maxHeight / BatteryFrame.innerHeight

        val batterySize = BatteryFrame.bodyPathSpec.scaledSize(scale)
        val batteryFramePlaceable =
            batteryFrame.measure(
                constraints =
                    constraints.copy(
                        minWidth = batterySize.width.roundToInt(),
                        maxWidth = batterySize.width.roundToInt(),
                        minHeight = batterySize.height.roundToInt(),
                        maxHeight = batterySize.height.roundToInt(),
                    )
            )

        val cap = measurables.fastFirstOrNull { it.layoutId == LayoutId.Cap }
        val capPlaceable = run {
            cap?.let {
                val size = BatteryFrame.capPathSpec.scaledSize(scale)
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                it.measure(
                    constraints =
                        constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)
                )
            }
        }

        val attr = measurables.fastFirstOrNull { it.layoutId is LayoutId.Attribution }
        val attrPlaceable = run {
            attr?.let {
                val ps = (it.layoutId as LayoutId.Attribution).wrapped
                val size = ps.scaledSize(scale)
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                it.measure(
                    constraints =
                        constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)
                )
            }
        }

        var totalWidth: Int = batteryFramePlaceable.width
        if (attrPlaceable != null) {
            totalWidth += (attrPlaceable.width * 0.8).roundToInt()
        } else if (capPlaceable != null) {
            // 1dp of padding * scale for the cap
            totalWidth += capPlaceable.width + scale.roundToInt()
        }
        val totalHeight = batterySize.height.roundToInt()
        return layout(totalWidth, totalHeight) {
            batteryFramePlaceable.place(0, 0)

            attrPlaceable?.apply {
                // Overlap the attribution by 20% of its width
                val xOffset = batteryFramePlaceable.width - (0.2 * width).roundToInt()
                val yOffset =
                    ((batteryFramePlaceable.height - attrPlaceable.height) / 2f).roundToInt()
                place(xOffset, yOffset)
            }

            capPlaceable?.apply {
                // Cap is offset by exactly 1dp (scaled)
                val xOffset = batteryFramePlaceable.width + scale.roundToInt()
                val yOffset =
                    ((batteryFramePlaceable.height - capPlaceable.height) / 2f).roundToInt()
                place(xOffset, yOffset)
            }
        }
    }
}

/**
 * Draws just the round-rect piece of the battery frame. If [glyphsProvider] is non-empty, then this
 * composable also renders the glyphs centered in the frame.
 *
 * Always shows the fill amount, clipped to the given [levelProvider]
 */
@Composable
fun BatteryBody(
    pathSpec: PathSpec,
    levelProvider: () -> Int?,
    glyphsProvider: () -> List<BatteryGlyph>,
    isFullProvider: () -> Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {
    Canvas(modifier = modifier, contentDescription = contentDescription) {
        val level = levelProvider()
        val colors = colorsProvider()

        val glyphs = glyphsProvider()
        val totalGlyphWidth =
            if (glyphs.isEmpty()) {
                0f
            } else {
                // Pads in between each glyph, skipping the first
                glyphs.drop(1).fold(glyphs.first().width) { acc: Float, next: BatteryGlyph ->
                    acc + INTER_GLYPH_PADDING_PX + next.width
                }
            }

        val s = pathSpec.scaleTo(size.width, size.height)
        scale(scale = s, pivot = Offset.Zero) {
            if (isFullProvider()) {
                // If the battery is full, we just show the fill color for the whole path
                drawPath(pathSpec.path, colors.fill)
            } else {
                // Else, clip the fill at the desired level
                // 1. select body color
                val color =
                    if (glyphs.isNotEmpty()) {
                        colors.backgroundWithGlyph
                    } else {
                        colors.backgroundOnly
                    }
                // 2. draw body
                drawPath(pathSpec.path, color)

                // 3. clip the fill to the level if we have it
                if (level != null && level > 0) {
                    clipRect(
                        left = 0f,
                        top = 0f,
                        right = level.scaledLevel(),
                        bottom = BatteryFrame.innerHeight,
                    ) {
                        // 4. Draw the rounded rect fill fully, it'll be clipped above
                        drawRoundRect(
                            color = colors.fill,
                            topLeft = Offset.Zero,
                            size =
                                Size(
                                    width = BatteryFrame.innerWidth,
                                    height = BatteryFrame.innerHeight,
                                ),
                            CornerRadius(x = BatteryFrame.cornerRadius),
                        )
                    }
                }
            }

            // Next: draw the glyphs
            var horizontalOffset = (BatteryFrame.innerWidth - totalGlyphWidth) / 2f
            for (glyph in glyphs) {
                // Move the glyph to the right spot
                val verticalOffset = (BatteryFrame.innerHeight - glyph.height) / 2
                inset(
                    // Never try and inset more than half of the available size - see b/400246091.
                    minOf(horizontalOffset, size.width / 2),
                    minOf(verticalOffset, size.height / 2),
                ) {
                    glyph.draw(this, colors)
                }
                horizontalOffset += glyph.width + INTER_GLYPH_PADDING_PX
            }
        }
    }
}

@Composable
fun BatteryCap(
    colorsProvider: () -> BatteryColors,
    isFullProvider: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val pathSpec = BatteryFrame.capPathSpec
    Canvas(modifier = modifier) {
        val colors = colorsProvider()
        val isFull = isFullProvider()
        val s = pathSpec.scaleTo(size.width, size.height)
        scale(scale = s, pivot = Offset.Zero) {
            val color = if (isFull) colors.fill else colors.backgroundOnly
            drawPath(pathSpec.path, color = color)
        }
    }
}

@Composable
fun BatteryAttribution(
    attr: BatteryGlyph,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
) {
    val stroke = remember { Stroke(width = 2f) }
    Canvas(modifier = modifier) {
        val s = attr.scaleTo(size.width, size.height)
        val colors = colorsProvider()
        scale(s, pivot = Offset.Zero) {
            drawPath(
                path = attr.path,
                color = Color.Black,
                style = stroke,
                blendMode = BlendMode.Clear,
            )
            drawPath(attr.path, color = colors.attribution)
        }
    }
}
