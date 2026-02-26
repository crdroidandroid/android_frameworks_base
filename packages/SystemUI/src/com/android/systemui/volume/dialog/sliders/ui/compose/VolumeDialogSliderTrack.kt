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

package com.android.systemui.volume.dialog.sliders.ui.compose

import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import kotlin.math.min

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
fun SliderTrack(
    sliderState: SliderState,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    thumbTrackGapSize: Dp = 6.dp,
    trackCornerSize: Dp = 12.dp,
    trackInsideCornerSize: Dp = 2.dp,
    trackSize: Dp = 40.dp,
    isVertical: Boolean = false,
    activeTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    activeTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measurePolicy =
        remember(sliderState, isRtl, isVertical, thumbTrackGapSize) {
            TrackMeasurePolicy(
                sliderState = sliderState,
                shouldMirrorIcons = !isVertical && isRtl || isVertical,
                isVertical = isVertical,
                gapSize = thumbTrackGapSize,
            )
        }
    Layout(
        measurePolicy = measurePolicy,
        content = {

            val gradientEnabled = rememberVolumeGradientEnabled()

            val gStart = MaterialTheme.colorScheme.primary
            val gEnd = MaterialTheme.colorScheme.secondary

            val activeBrush =
                if (gradientEnabled && gStart != Color(0) && gEnd != Color(0)) {
                    listOf(gStart, gEnd)
                } else {
                    null
                }

            GradientSliderTrack(
                sliderState = sliderState,
                isEnabled = isEnabled,
                colors = colors,
                trackCornerSize = trackCornerSize,
                trackInsideCornerSize = trackInsideCornerSize,
                thumbTrackGapSize = thumbTrackGapSize,
                isVertical = isVertical,
                gradientColors = activeBrush,
                modifier =
                    Modifier
                        .then(
                            if (isVertical) {
                                Modifier.width(trackSize).fillMaxHeight()
                            } else {
                                Modifier.height(trackSize).fillMaxWidth()
                            }
                        )
                        .layoutId(Contents.Track),
            )

            TrackIcon(
                icon = activeTrackStartIcon,
                contents = Contents.Active.TrackStartIcon,
                isEnabled = isEnabled,
                colors = colors,
                trackMeasurePolicy = measurePolicy,
            )
            TrackIcon(
                icon = activeTrackEndIcon,
                contents = Contents.Active.TrackEndIcon,
                isEnabled = isEnabled,
                colors = colors,
                trackMeasurePolicy = measurePolicy,
            )
            TrackIcon(
                icon = inactiveTrackStartIcon,
                contents = Contents.Inactive.TrackStartIcon,
                isEnabled = isEnabled,
                colors = colors,
                trackMeasurePolicy = measurePolicy,
            )
            TrackIcon(
                icon = inactiveTrackEndIcon,
                contents = Contents.Inactive.TrackEndIcon,
                isEnabled = isEnabled,
                colors = colors,
                trackMeasurePolicy = measurePolicy,
            )
        },
        modifier = modifier,
    )
}

private data class TrackGradient(
    val brush: Brush,
)

@Composable
private fun rememberVolumeGradientEnabled(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.VOLUME_SLIDER_GRADIENT, 0,
                UserHandle.USER_CURRENT
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var enabled by remember { mutableStateOf(readEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    enabled = readEnabled()
                }
            }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.VOLUME_SLIDER_GRADIENT),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return enabled
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GradientSliderTrack(
    sliderState: SliderState,
    isEnabled: Boolean,
    colors: SliderColors,
    trackCornerSize: Dp,
    trackInsideCornerSize: Dp,
    thumbTrackGapSize: Dp,
    isVertical: Boolean,
    gradientColors: List<Color>?,
    modifier: Modifier = Modifier,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val inactiveColor =
        if (isEnabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    val activeSolidColor =
        if (isEnabled) colors.activeTrackColor else colors.disabledActiveTrackColor

    Box(
        modifier =
            modifier.drawBehind {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@drawBehind

                val frac = sliderState.coercedValueAsFraction.coerceIn(0f, 1f)
                val outerR = trackCornerSize.toPx().coerceAtMost(minOf(w, h) / 2f)
                val innerR = trackInsideCornerSize.toPx().coerceAtMost(minOf(w, h) / 2f)
                val halfGap = (thumbTrackGapSize.toPx() / 2f).coerceAtLeast(0f)

                drawRoundRect(
                    color = inactiveColor,
                    size = size,
                    cornerRadius = CornerRadius(outerR, outerR)
                )

                if (!isVertical) {
                    val splitX = if (isRtl) w * (1f - frac) else w * frac

                    val gapEff =
                        if (isRtl) {
                            minOf(halfGap, splitX)
                        } else {
                            minOf(halfGap, w - splitX)
                        }

                    val activeStart = if (isRtl) splitX + gapEff else 0f
                    val activeEnd = if (isRtl) w else splitX - gapEff

                    if (activeEnd <= activeStart) return@drawBehind

                    val eps = 0.5f

                    val hitsStart = activeStart <= eps
                    val hitsEnd = activeEnd >= (w - eps)

                    val leftR =
                        if (isRtl) {
                            if (hitsStart) outerR else innerR   // rounded only at max (when it reaches start)
                        } else {
                            outerR                              // far end always rounded
                        }

                    val rightR =
                        if (isRtl) {
                            outerR                              // far end always rounded
                        } else {
                            if (hitsEnd) outerR else innerR     // rounded only at max (when it reaches end)
                        }

                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = Rect(activeStart, 0f, activeEnd, h),
                                topLeft = CornerRadius(leftR, leftR),
                                bottomLeft = CornerRadius(leftR, leftR),
                                topRight = CornerRadius(rightR, rightR),
                                bottomRight = CornerRadius(rightR, rightR),
                            )
                        )
                    }

                    val brush =
                        if (gradientColors != null) Brush.horizontalGradient(gradientColors)
                        else Brush.linearGradient(listOf(activeSolidColor, activeSolidColor))

                    drawPath(path = path, brush = brush)
                } else {
                    val frac = sliderState.coercedValueAsFraction.coerceIn(0f, 1f)
                    val splitY = h * (1f - frac)

                    val gapEff = minOf(halfGap, splitY)

                    val activeTop = splitY + gapEff
                    val activeBottom = h
                    if (activeBottom <= activeTop) return@drawBehind

                    val eps = 0.5f
                    val hitsTop = activeTop <= eps 

                    val topR = if (hitsTop) outerR else innerR
                    val bottomR = outerR

                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = Rect(0f, activeTop, w, activeBottom),
                                topLeft = CornerRadius(topR, topR),
                                bottomLeft = CornerRadius(bottomR, bottomR),
                                topRight = CornerRadius(topR, topR),
                                bottomRight = CornerRadius(bottomR, bottomR),
                            )
                        )
                    }

                    val brush =
                        if (gradientColors != null) {
                            Brush.verticalGradient(
                                colors = gradientColors,
                                startY = activeBottom,
                                endY = activeTop
                            )
                        } else {
                            Brush.linearGradient(listOf(activeSolidColor, activeSolidColor))
                        }

                    drawPath(path = path, brush = brush)
                }
            }
    )
}

@Composable
private fun TrackIcon(
    icon: (@Composable BoxScope.(sliderIconsState: SliderIconsState) -> Unit)?,
    isEnabled: Boolean,
    contents: Contents,
    trackMeasurePolicy: TrackMeasurePolicy,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    icon ?: return
    /*
    ignore icons mirroring for the rtl layouts here because icons positioning is handled by the
    TrackMeasurePolicy. It ensures that active icons are always above the active track and the
    same for inactive
    */
    val iconColor =
        when (contents) {
            is Contents.Inactive ->
                if (isEnabled) {
                    colors.inactiveTickColor
                } else {
                    colors.disabledInactiveTickColor
                }
            is Contents.Active ->
                if (isEnabled) {
                    colors.activeTickColor
                } else {
                    colors.disabledActiveTickColor
                }
            is Contents.Track -> {
                error("$contents is unsupported by the TrackIcon")
            }
        }
    Box(modifier = modifier.layoutId(contents).fillMaxSize()) {
        if (trackMeasurePolicy.isVisible(contents) != null) {
            CompositionLocalProvider(LocalContentColor provides iconColor) {
                icon(trackMeasurePolicy)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class TrackMeasurePolicy(
    private val sliderState: SliderState,
    private val shouldMirrorIcons: Boolean,
    private val gapSize: Dp,
    private val isVertical: Boolean,
) : MeasurePolicy, SliderIconsState {

    private val isVisible: Map<Contents, MutableState<Boolean?>> =
        mutableMapOf(
            Contents.Active.TrackStartIcon to mutableStateOf(null),
            Contents.Active.TrackEndIcon to mutableStateOf(null),
            Contents.Inactive.TrackStartIcon to mutableStateOf(null),
            Contents.Inactive.TrackEndIcon to mutableStateOf(null),
        )

    fun isVisible(contents: Contents): Boolean? = isVisible.getValue(contents.resolve()).value

    override val isActiveTrackStartIconVisible: Boolean
        get() = isVisible(Contents.Active.TrackStartIcon)!!

    override val isActiveTrackEndIconVisible: Boolean
        get() = isVisible(Contents.Active.TrackEndIcon)!!

    override val isInactiveTrackStartIconVisible: Boolean
        get() = isVisible(Contents.Inactive.TrackStartIcon)!!

    override val isInactiveTrackEndIconVisible: Boolean
        get() = isVisible(Contents.Inactive.TrackEndIcon)!!

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val track = measurables.fastFirst { it.layoutId == Contents.Track }.measure(constraints)

        val iconSize = min(track.width, track.height)
        val iconConstraints = constraints.copy(maxWidth = iconSize, maxHeight = iconSize)

        val components = buildMap {
            put(Contents.Track, track)
            for (measurable in measurables) {
                // don't measure track a second time
                if (measurable.layoutId != Contents.Track) {
                    put(
                        (measurable.layoutId as Contents).resolve(),
                        measurable.measure(iconConstraints),
                    )
                }
            }
        }

        return layout(track.width, track.height) {
            val gapSizePx = gapSize.roundToPx()
            val coercedValueAsFraction =
                if (shouldMirrorIcons) {
                    1 - sliderState.coercedValueAsFraction
                } else {
                    sliderState.coercedValueAsFraction
                }
            for (iconLayoutId in components.keys) {
                val iconPlaceable = components.getValue(iconLayoutId)
                if (isVertical) {
                    iconPlaceable.place(
                        0,
                        iconLayoutId.calculatePosition(
                            placeableDimension = iconPlaceable.height,
                            containerDimension = track.height,
                            gapSize = gapSizePx,
                            coercedValueAsFraction = coercedValueAsFraction,
                        ),
                    )
                } else {
                    iconPlaceable.place(
                        iconLayoutId.calculatePosition(
                            placeableDimension = iconPlaceable.width,
                            containerDimension = track.width,
                            gapSize = gapSizePx,
                            coercedValueAsFraction = coercedValueAsFraction,
                        ),
                        0,
                    )
                }

                // isVisible is only relevant for the icons
                if (iconLayoutId != Contents.Track) {
                    val isIconVisible =
                        iconLayoutId.isVisible(
                            placeableDimension =
                                if (isVertical) iconPlaceable.height else iconPlaceable.width,
                            containerDimension = if (isVertical) track.height else track.width,
                            gapSize = gapSizePx,
                            coercedValueAsFraction = coercedValueAsFraction,
                        )
                    isVisible.getValue(iconLayoutId).value = isIconVisible
                }
            }
        }
    }

    private fun Contents.resolve(): Contents {
        return if (shouldMirrorIcons) {
            mirrored
        } else {
            this
        }
    }
}

private sealed interface Contents {

    data object Track : Contents {

        override val mirrored: Contents
            get() = error("unsupported for Track")

        override fun calculatePosition(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Int = 0

        override fun isVisible(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Boolean = true
    }

    interface Active : Contents {

        override fun isVisible(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Boolean =
            (containerDimension * coercedValueAsFraction - gapSize).toInt() > placeableDimension

        data object TrackStartIcon : Active {

            override val mirrored: Contents
                get() = Inactive.TrackEndIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int = 0
        }

        data object TrackEndIcon : Active {

            override val mirrored: Contents
                get() = Inactive.TrackStartIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int =
                (containerDimension * coercedValueAsFraction - placeableDimension - gapSize).toInt()
        }
    }

    interface Inactive : Contents {

        override fun isVisible(
            placeableDimension: Int,
            containerDimension: Int,
            gapSize: Int,
            coercedValueAsFraction: Float,
        ): Boolean =
            containerDimension - (containerDimension * coercedValueAsFraction + gapSize) >
                placeableDimension

        data object TrackStartIcon : Inactive {

            override val mirrored: Contents
                get() = Active.TrackEndIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int = (containerDimension * coercedValueAsFraction + gapSize).toInt()
        }

        data object TrackEndIcon : Inactive {

            override val mirrored: Contents
                get() = Active.TrackStartIcon

            override fun calculatePosition(
                placeableDimension: Int,
                containerDimension: Int,
                gapSize: Int,
                coercedValueAsFraction: Float,
            ): Int = containerDimension - placeableDimension
        }
    }

    fun calculatePosition(
        placeableDimension: Int,
        containerDimension: Int,
        gapSize: Int,
        coercedValueAsFraction: Float,
    ): Int

    fun isVisible(
        placeableDimension: Int,
        containerDimension: Int,
        gapSize: Int,
        coercedValueAsFraction: Float,
    ): Boolean

    /**
     * [Contents] that is visually on the opposite side of the current one on the slider. This is
     * handy when dealing with the rtl layouts
     */
    val mirrored: Contents
}

/** Provides visibility state for each of the Slider's icons. */
interface SliderIconsState {
    val isActiveTrackStartIconVisible: Boolean
    val isActiveTrackEndIconVisible: Boolean
    val isInactiveTrackStartIconVisible: Boolean
    val isInactiveTrackEndIconVisible: Boolean
}
