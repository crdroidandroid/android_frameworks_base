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

package com.android.systemui.flashlight.ui.composable

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.height
import com.android.compose.modifiers.size
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.modifiers.width
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.flashlight.ui.composable.Specs.BLUR_CONTRACTION
import com.android.systemui.flashlight.ui.composable.Specs.BLUR_X
import com.android.systemui.flashlight.ui.composable.Specs.BLUR_Y
import com.android.systemui.flashlight.ui.composable.Specs.EdgeTreatment
import com.android.systemui.flashlight.ui.composable.Specs.MAX_TRACK_HEIGHT
import com.android.systemui.flashlight.ui.composable.Specs.MIN_TRACK_HEIGHT
import com.android.systemui.flashlight.ui.composable.Specs.THUMB_CENTER_TO_GAP_OUTSIDE
import com.android.systemui.flashlight.ui.composable.Specs.THUMB_MAX_HEIGHT
import com.android.systemui.flashlight.ui.composable.Specs.THUMB_MIN_HEIGHT
import com.android.systemui.flashlight.ui.composable.Specs.THUMB_WIDTH
import com.android.systemui.flashlight.ui.composable.Specs.TRACK_LENGTH
import com.android.systemui.flashlight.ui.composable.Specs.TRAPEZOID_BOTTOM_LEFT_HEIGHT_RATIO
import com.android.systemui.flashlight.ui.composable.Specs.TRAPEZOID_TOP_LEFT_HEIGHT_RATIO
import com.android.systemui.flashlight.ui.composable.Specs.WIDTH_CONTRACTION
import com.android.systemui.flashlight.ui.composable.VerticalFlashlightSliderMotionTestKeys.TRACK_TEST_TAG
import com.android.systemui.flashlight.ui.composable.VerticalFlashlightSliderMotionTestKeys.TrackHeight
import com.android.systemui.flashlight.ui.composable.VerticalFlashlightSliderMotionTestKeys.TrackWidth
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerticalFlashlightSlider(
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: (Int) -> Unit,
    isEnabled: Boolean,
    levelValue: Int,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    // for flashlight icon toggling on and off
    var atEnd by remember { mutableStateOf(levelValue != valueRange.first) }

    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()

    val interactionSource = remember { MutableInteractionSource() }
    val isDragged = interactionSource.collectIsDraggedAsState()

    var value by remember(levelValue) { mutableIntStateOf(levelValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "FlashlightSliderAnimatedValue")

    val hapticsViewModel: SliderHapticsViewModel =
        rememberViewModel(traceName = "SliderHapticsViewModel") {
            hapticsViewModelFactory.create(
                interactionSource,
                floatValueRange,
                Orientation.Vertical,
                SliderHapticFeedbackConfig(
                    maxVelocityToScale = 1f /* slider progress(from 0 to 1) per sec */
                ),
                SeekableSliderTrackerConfig(),
            )
        }
    val flashlightStrength = stringResource(R.string.flashlight_dialog_title)

    Column(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Slider(
            modifier =
                Modifier.rotate(270f)
                    // 80 dp is enough for track height, but we need 120 for thumb height.
                    // But since the rotate only rotates the draw layer, we would end up with  a 120
                    // slider length rather than 140. Instead of 120 we go with 140 so that we don't
                    // need extra code to rotate the layout.
                    .size(TRACK_LENGTH)
                    .semantics(mergeDescendants = true) {
                        this.text = AnnotatedString(flashlightStrength)
                    }
                    .sliderPercentage { toPercent(animatedValue, valueRange) }
                    .sysuiResTag("slider"),
            enabled = isEnabled,
            value = animatedValue,
            valueRange = floatValueRange,
            onValueChange = {
                if (isEnabled) {
                    hapticsViewModel.onValueChange(it)
                    value = it.toInt()
                    atEnd = value != valueRange.first
                    onValueChange(value)
                }
            },
            onValueChangeFinished = {
                if (isEnabled) {
                    hapticsViewModel.onValueChangeEnded()
                    onValueChangeFinished(value)
                }
            },
            interactionSource = interactionSource,
            colors = colors,
            thumb = { sliderState ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(
                        interactionSource = interactionSource,
                        colors = colors,
                        thumbSize =
                            DpSize(
                                THUMB_WIDTH,
                                thumbHeight(sliderState.value / sliderState.valueRange.endInclusive),
                            ),
                    )
                }
            },
            track = { sliderState ->
                TrapezoidTrack(
                    modifier =
                        Modifier.drawWithContent {
                                // Cut a gap around the thumb. The is pre-rotation and horizontal,
                                // hence we use the left-right gap metrics instead of top-bottom.
                                clipRect(
                                    left =
                                        size.width * (sliderState.coercedValueAsFraction) -
                                            THUMB_CENTER_TO_GAP_OUTSIDE.toPx(),
                                    top = 0f,
                                    bottom = size.height,
                                    right =
                                        size.width * (sliderState.coercedValueAsFraction) +
                                            THUMB_CENTER_TO_GAP_OUTSIDE.toPx(),
                                    clipOp = ClipOp.Difference,
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                            // TODO(440620729): gradient blur from top to bottom. or no bottom blur.
                            .blur(
                                BLUR_X,
                                // TODO(440620729): start contraction on click down, not on drag.
                                if (isDragged.value) (BLUR_Y * BLUR_CONTRACTION) else BLUR_Y,
                                EdgeTreatment,
                            )
                            .motionTestValues {
                                trackEndAlpha(sliderState.coercedValueAsFraction) exportAs
                                    VerticalFlashlightSliderMotionTestKeys.TrackEndAlpha
                            },
                    brush =
                        Brush.horizontalGradient(
                            0f to colors.activeTrackColor,
                            0.5f to colors.activeTrackColor,
                            1.0f to
                                // lower end alpha from 1 to 0.12 as slider progresses.
                                colors.activeTrackColor.copy(
                                    alpha = trackEndAlpha(sliderState.coercedValueAsFraction)
                                ),
                        ),
                    sliderState = sliderState,
                    maxSliderRange = floatValueRange.endInclusive,
                    widthContraction = if (isDragged.value) WIDTH_CONTRACTION else 1f,
                )
            },
        )
        AnimatedVectorFlashlightDrawable(atEnd, colors.thumbColor, Modifier.size(48.dp))
    }
}

private fun toPercent(value: Float, valueRange: IntRange): Float =
    (value - valueRange.first.toFloat()) / (valueRange.last - valueRange.first)

private fun trackEndAlpha(percentage: Float) = -0.88f * percentage + 1

@ExperimentalMaterial3Api
@Composable
private fun TrapezoidTrack(
    sliderState: SliderState,
    maxSliderRange: Float,
    brush: Brush,
    widthContraction: Float,
    modifier: Modifier = Modifier,
) {

    Row(
        modifier = modifier.size(TRACK_LENGTH, MAX_TRACK_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val canvasHeight by animateDpAsState((MAX_TRACK_HEIGHT * widthContraction))
        val path = remember { Path() }

        Canvas(
            modifier =
                modifier
                    .width { (TRACK_LENGTH * (sliderState.value / maxSliderRange)).roundToPx() }
                    .height { canvasHeight.roundToPx() }
                    .testTag(TRACK_TEST_TAG)
                    .motionTestValues {
                        canvasHeight exportAs TrackHeight
                        TRACK_LENGTH * (sliderState.value / maxSliderRange) exportAs TrackWidth
                    }
        ) {
            drawTrapezoidPathAndRewind(path, brush)
        }
    }
}

/** We draw a left to right trapezoid (actual proportion is more horizontally long) */
private fun DrawScope.drawTrapezoidPathAndRewind(path: Path, brush: Brush) {
    path.moveTo(0f, size.height * TRAPEZOID_BOTTOM_LEFT_HEIGHT_RATIO)
    path.lineTo(0f, size.height * TRAPEZOID_TOP_LEFT_HEIGHT_RATIO) // left
    path.lineTo(size.width, 0f) // top
    path.lineTo(size.width, size.height) // right
    path.lineTo(0f, size.height * TRAPEZOID_BOTTOM_LEFT_HEIGHT_RATIO) // bottom
    drawPath(path, brush)

    path.rewind()
}

@Composable
private fun AnimatedVectorFlashlightDrawable(
    atEnd: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Crossfade(targetState = atEnd, label = "FlashlightIcon") { on ->
        Icon(
            modifier = modifier.semantics { hideFromAccessibility() },
            imageVector = if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
            contentDescription = null,
            tint = color,
        )
    }
}

@Composable
fun Thumb(
    interactionSource: MutableInteractionSource,
    colors: SliderColors,
    thumbSize: DpSize,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactions = remember { mutableStateListOf<Interaction>() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> interactions.add(interaction)
                is PressInteraction.Release -> interactions.remove(interaction.press)
                is PressInteraction.Cancel -> interactions.remove(interaction.press)
                is DragInteraction.Start -> interactions.add(interaction)
                is DragInteraction.Stop -> interactions.remove(interaction.start)
                is DragInteraction.Cancel -> interactions.remove(interaction.start)
            }
        }
    }

    val size =
        if (interactions.isNotEmpty()) {
            thumbSize.copy(width = thumbSize.width / 2)
        } else {
            thumbSize
        }
    Spacer(
        modifier
            .width { size.width.roundToPx() }
            .height { size.height.roundToPx() }
            .hoverable(interactionSource = interactionSource)
            .background(
                if (enabled) colors.thumbColor else colors.disabledThumbColor,
                RoundedCornerShape(100),
            )
    )
}

private fun thumbHeight(thumbPosition: Float): Dp {
    return (THUMB_MAX_HEIGHT - THUMB_MIN_HEIGHT) * thumbPosition + THUMB_MIN_HEIGHT
}

private class BeamShape : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path =
            Path().apply { drawBeamPath(size, density, Offset(size.width / 2, size.height / 2)) }
        return Outline.Generic(path)
    }
}

private fun Path.drawBeamPath(size: Size, density: Density, center: Offset) {
    val leftSideLength = with(density) { MIN_TRACK_HEIGHT.toPx() }
    val topLeftY = center.y - leftSideLength / 2
    val bottomLeftY = center.y + leftSideLength / 2

    moveTo(leftSideLength / 2, topLeftY) // start at top-left
    lineTo(size.width, 0f) // top  (moving right and up)
    lineTo(size.width, size.height) // right (moving down)
    lineTo(leftSideLength / 2, bottomLeftY) // bottom (moving left and up)

    arcTo(
        rect = Rect(left = 0f, top = topLeftY, right = leftSideLength, bottom = bottomLeftY),
        startAngleDegrees = 90f, // point left
        sweepAngleDegrees = 180f, // half the circle
        forceMoveTo = true,
    )
}

@VisibleForTesting
object VerticalFlashlightSliderMotionTestKeys {
    const val TRACK_TEST_TAG = "TrapezoidTrack"
    val TrackEndAlpha = MotionTestValueKey<Float>("trackEndAlpha")
    val TrackHeight = MotionTestValueKey<Dp>("trackHeight")
    val TrackWidth = MotionTestValueKey<Dp>("trackWidth")
}

private object Specs {
    val TRACK_LENGTH = 140.dp
    val MAX_TRACK_HEIGHT = 80.dp
    val MIN_TRACK_HEIGHT = 22.dp
    val THUMB_MIN_HEIGHT = 48.dp
    val THUMB_MAX_HEIGHT = 120.dp
    val THUMB_WIDTH = 4.dp
    val THUMB_GAP = 2.dp
    val THUMB_CENTER_TO_GAP_OUTSIDE = THUMB_WIDTH / 2 + THUMB_GAP
    val BLUR_X = 20.dp // max 60
    val BLUR_Y = 5.dp // max 30
    val EdgeTreatment = BlurredEdgeTreatment(BeamShape())
    const val BLUR_CONTRACTION = 1f // max 1
    const val WIDTH_CONTRACTION = 0.7f // max 1
    const val TRAPEZOID_TOP_LEFT_HEIGHT_RATIO = 0.3625f
    const val TRAPEZOID_BOTTOM_LEFT_HEIGHT_RATIO = 0.6375f
}
