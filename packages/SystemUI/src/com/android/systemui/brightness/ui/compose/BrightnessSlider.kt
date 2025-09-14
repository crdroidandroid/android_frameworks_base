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

package com.android.systemui.brightness.ui.compose

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconAppearSpec
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconDisappearSpec
import com.android.systemui.brightness.ui.compose.Dimensions.IconPadding
import com.android.systemui.brightness.ui.compose.Dimensions.IconSize
import com.android.systemui.brightness.ui.compose.Dimensions.SliderBackgroundFrameSize
import com.android.systemui.brightness.ui.compose.Dimensions.SliderBackgroundRoundedCorner
import com.android.systemui.brightness.ui.compose.Dimensions.ThumbHeight
import com.android.systemui.brightness.ui.compose.Dimensions.SliderTrackRoundedCorner
import com.android.systemui.brightness.ui.compose.Dimensions.ThumbTrackGapSize
import com.android.systemui.brightness.ui.compose.Dimensions.ThumbWidth
import com.android.systemui.brightness.ui.compose.Dimensions.TrackHeight
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction
import lineageos.providers.LineageSettings
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@VisibleForTesting
fun BrightnessSlider(
    gammaValue: Int,
    valueRange: IntRange,
    autoMode: Boolean,
    iconResProvider: (Float) -> Int,
    imageLoader: suspend (Int, Context) -> Icon.Loaded?,
    restriction: PolicyRestriction,
    onRestrictedClick: (PolicyRestriction.Restricted) -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    onIconClick: suspend () -> Unit,
    overriddenByAppState: Boolean,
    modifier: Modifier = Modifier,
    showToast: () -> Unit = {},
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    val context = LocalContext.current
    val cr = context.contentResolver

    var hapticsEnabled by remember { mutableStateOf(readEnableHaptics(cr)) }

    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = restriction is PolicyRestriction.Restricted
    val enabled = !isRestricted
    val contentDescription = stringResource(R.string.accessibility_brightness)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel? =
        if (hapticsEnabled) {
            rememberViewModel(traceName = "SliderHapticsViewModel") {
                hapticsViewModelFactory.create(
                    interactionSource,
                    floatValueRange,
                    Orientation.Horizontal,
                    SliderHapticFeedbackConfig(
                        maxVelocityToScale = 1f /* slider progress(from 0 to 1) per sec */
                    ),
                    SeekableSliderTrackerConfig(),
                )
            }
        } else {
            null
        }
    val colors = colors()

    // The value state is recreated every time gammaValue changes, so we recreate this derivedState
    // We have to use value as that's the value that changes when the user is dragging (gammaValue
    // is always the starting value: actual (not temporary) brightness).
    val iconRes by
        remember(gammaValue, valueRange) {
            derivedStateOf {
                val percentage =
                    (value - valueRange.first) * 100f / (valueRange.last - valueRange.first)
                iconResProvider(percentage)
            }
        }
    val painter: Painter by
        produceState<Painter>(
            initialValue = ColorPainter(Color.Transparent),
            key1 = iconRes,
            key2 = context,
        ) {
            val icon: Icon.Loaded? = imageLoader(iconRes, context)
            if (icon != null) {
                val bitmap = icon.drawable.toBitmap()?.asImageBitmap()
                if (bitmap != null) {
                    this@produceState.value = BitmapPainter(bitmap)
                }
            }
        }
    val activeIconColor = colors.activeTickColor
    val inactiveIconColor = colors.inactiveTickColor
    // Offset from the right
    val trackIcon: DrawScope.(Offset, Color, Float) -> Unit = remember {
        { offset, color, alpha ->
            val rtl = layoutDirection == LayoutDirection.Rtl
            scale(if (rtl) -1f else 1f, 1f) {
                translate(offset.x - IconPadding.toPx() - IconSize.toSize().width, offset.y) {
                    with(painter) {
                        draw(
                            IconSize.toSize(),
                            colorFilter = ColorFilter.tint(color),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
    }

    val hasAutoBrightness = context.resources.getBoolean(
        com.android.internal.R.bool.config_automatic_brightness_available
    )
    var showAutoBrightness by remember { mutableStateOf(readShowAutoBrightness(cr)) }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    showAutoBrightness = readShowAutoBrightness(cr)
                    hapticsEnabled = readEnableHaptics(cr)
                }
            }
        }

        cr.registerContentObserver(
            LineageSettings.Secure.getUriFor(LineageSettings.Secure.QS_SHOW_AUTO_BRIGHTNESS),
            false, observer, UserHandle.USER_ALL
        )

        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            cr.unregisterContentObserver(observer)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Slider(
            value = animatedValue,
            valueRange = floatValueRange,
            enabled = enabled,
            colors = colors,
            onValueChange = {
                if (enabled) {
                    if (!overriddenByAppState) {
                        hapticsViewModel?.onValueChange(it)
                        value = it.toInt()
                        onDrag(value)
                    }
                }
            },
            onValueChangeFinished = {
                if (enabled) {
                    if (!overriddenByAppState) {
                        hapticsViewModel?.onValueChangeEnded()
                        onStop(value)
                    }
                }
            },
            modifier =
                modifier
                    .weight(1f)
                    .sysuiResTag("slider")
                    .semantics(mergeDescendants = true) {
                        this.text = AnnotatedString(contentDescription)
                    }
                    .sliderPercentage {
                        (value - valueRange.first).toFloat() / (valueRange.last - valueRange.first)
                    }
                    .thenIf(isRestricted) {
                        Modifier.clickable {
                            if (restriction is PolicyRestriction.Restricted) {
                                onRestrictedClick(restriction)
                            }
                        }
                    },
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    thumbSize = DpSize(ThumbWidth, ThumbHeight),
                    colors = colors,
                )
            },
            track = { sliderState ->
                var showIconActive by remember { mutableStateOf(true) }
                val iconActiveAlphaAnimatable = remember {
                    Animatable(
                        initialValue = 1f,
                        typeConverter = Float.VectorConverter,
                        label = "iconActiveAlpha",
                    )
                }

                val iconInactiveAlphaAnimatable = remember {
                    Animatable(
                        initialValue = 0f,
                        typeConverter = Float.VectorConverter,
                        label = "iconInactiveAlpha",
                    )
                }

                LaunchedEffect(iconActiveAlphaAnimatable, iconInactiveAlphaAnimatable, showIconActive) {
                    if (showIconActive) {
                        launch { iconActiveAlphaAnimatable.appear() }
                        launch { iconInactiveAlphaAnimatable.disappear() }
                    } else {
                        launch { iconActiveAlphaAnimatable.disappear() }
                        launch { iconInactiveAlphaAnimatable.appear() }
                    }
                }

                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier =
                        Modifier.motionTestValues {
                                (iconActiveAlphaAnimatable.isRunning ||
                                    iconInactiveAlphaAnimatable.isRunning) exportAs
                                    BrightnessSliderMotionTestKeys.AnimatingIcon

                                iconActiveAlphaAnimatable.value exportAs
                                    BrightnessSliderMotionTestKeys.ActiveIconAlpha
                                iconInactiveAlphaAnimatable.value exportAs
                                    BrightnessSliderMotionTestKeys.InactiveIconAlpha
                            }
                            .height(TrackHeight)
                            .drawWithContent {
                                drawContent()

                                val yOffset = size.height / 2 - IconSize.toSize().height / 2
                                val activeTrackStart = 0f
                                val activeTrackEnd =
                                    size.width * sliderState.coercedValueAsFraction -
                                        ThumbTrackGapSize.toPx()
                                val inactiveTrackStart = activeTrackEnd + ThumbTrackGapSize.toPx() * 2
                                val inactiveTrackEnd = size.width

                                val activeTrackWidth = activeTrackEnd - activeTrackStart
                                val inactiveTrackWidth = inactiveTrackEnd - inactiveTrackStart

                                if (
                                    IconSize.toSize().width <
                                        inactiveTrackWidth - IconPadding.toPx() * 2
                                ) {
                                    showIconActive = false
                                    trackIcon(
                                        Offset(inactiveTrackEnd, yOffset),
                                        inactiveIconColor,
                                        iconInactiveAlphaAnimatable.value,
                                    )
                                } else if (
                                    IconSize.toSize().width < activeTrackWidth - IconPadding.toPx() * 2
                                ) {
                                    showIconActive = true
                                    trackIcon(
                                        Offset(activeTrackEnd, yOffset),
                                        activeIconColor,
                                        iconActiveAlphaAnimatable.value,
                                    )
                                }
                            },
                    trackCornerSize = SliderTrackRoundedCorner,
                    trackInsideCornerSize = 2.dp,
                    drawStopIndicator = null,
                    thumbTrackGapSize = ThumbTrackGapSize,
                    colors = colors,
                )
            },
        )

        if (hasAutoBrightness && showAutoBrightness) {
            Spacer(modifier = Modifier.width(10.dp))
            drawAutoBrightnessButton(
                autoMode = autoMode,
                hapticsEnabled = hapticsEnabled,
                onIconClick = onIconClick
            )
        }
    }

    val currentShowToast by rememberUpdatedState(showToast)
    // Showing the warning toast if the current running app window has controlled the
    // brightness value.
    LaunchedEffect(interactionSource, overriddenByAppState) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && overriddenByAppState) {
                currentShowToast()
            }
        }
    }
}

private fun Modifier.sliderBackground(color: Color) = drawWithCache {
    val offsetAround = SliderBackgroundFrameSize.toSize()
    val newSize = Size(size.width + 2 * offsetAround.width, size.height + 2 * offsetAround.height)
    val offset = Offset(-offsetAround.width, -offsetAround.height)
    val cornerRadius = CornerRadius(SliderBackgroundRoundedCorner.toPx())
    onDrawBehind {
        drawRoundRect(color = color, topLeft = offset, size = newSize, cornerRadius = cornerRadius)
    }
}

private fun readShowAutoBrightness(cr: ContentResolver): Boolean =
    try {
        LineageSettings.Secure.getIntForUser(
            cr, LineageSettings.Secure.QS_SHOW_AUTO_BRIGHTNESS,
            1, UserHandle.USER_CURRENT
        ) != 0
    } catch (_: Throwable) {
        false
    }

private fun readEnableHaptics(cr: ContentResolver): Boolean =
    try {
        Settings.System.getIntForUser(
            cr, Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC,
            1, UserHandle.USER_CURRENT
        ) != 0
    } catch (_: Throwable) {
        false
    }

@Composable
private fun drawAutoBrightnessButton(
    autoMode: Boolean,
    hapticsEnabled: Boolean,
    onIconClick: suspend () -> Unit,
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val animatedCornerRadius by animateDpAsState(
        targetValue = if (autoMode) {
            SliderTrackRoundedCorner
        } else {
            22.5.dp
        }
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (autoMode) {
            MaterialTheme.colorScheme.primary
        } else {
            LocalAndroidColorScheme.current.surfaceEffect1
        }
    )
    val iconTint by animateColorAsState(
        targetValue = if (autoMode) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )
    val painterRes = if (autoMode) {
        R.drawable.ic_qs_brightness_auto_on
    } else {
        R.drawable.ic_qs_brightness_auto_off
    }
    val hapticConstant = if (autoMode) {
        HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.TOGGLE_ON
    }

    Box(
        modifier = Modifier
            .size(45.dp)
            .clip(RoundedCornerShape(animatedCornerRadius))
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // Disable ripple effect
                onClick = {
                    if (hapticsEnabled) {
                        view.performHapticFeedback(hapticConstant)
                    }
                    coroutineScope.launch { onIconClick() }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = stringResource(R.string.accessibility_adaptive_brightness),
            tint = iconTint
        )
    }
}

@Composable
fun BrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
    containerColors: ContainerColors,
) {
    val gamma = viewModel.currentBrightness.value
    if (gamma == BrightnessSliderViewModel.initialValue.value) { // Ignore initial negative value.
        return
    }
    val autoMode = viewModel.autoMode
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val overriddenByAppState by viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { onDispose { viewModel.setIsDragging(false) } }

    var dragging by remember { mutableStateOf(false) }

    // Use dragging instead of viewModel.showMirror so the color starts changing as soon as the
    // dragging state changes. If not, we may be waiting for the background to finish fading in
    // when stopping dragging
    val containerColor by
        animateColorAsState(
            if (dragging) containerColors.mirrorColor else containerColors.idleColor
        )

    Box(
        modifier =
            modifier
                .padding(vertical = { SliderBackgroundFrameSize.height.roundToPx() })
                .fillMaxWidth()
                .sysuiResTag("brightness_slider")
    ) {
        BrightnessSlider(
            gammaValue = gamma,
            valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
            autoMode = autoMode,
            iconResProvider = BrightnessSliderViewModel::getIconForPercentage,
            imageLoader = viewModel::loadImage,
            restriction = restriction,
            onRestrictedClick = viewModel::showPolicyRestrictionDialog,
            onDrag = {
                viewModel.setIsDragging(true)
                dragging = true
                coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(it))) }
            },
            onStop = {
                viewModel.setIsDragging(false)
                dragging = false
                coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(it))) }
            },
            onIconClick = { viewModel.onIconClick() },
            modifier =
                Modifier.borderOnFocus(
                        color = MaterialTheme.colorScheme.secondary,
                        cornerSize = CornerSize(SliderTrackRoundedCorner),
                    )
                    .then(if (viewModel.showMirror) Modifier.drawInOverlay() else Modifier)
                    .sliderBackground(containerColor)
                    .fillMaxWidth()
                    .pointerInteropFilter {
                        if (
                            it.actionMasked == MotionEvent.ACTION_UP ||
                                it.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            viewModel.emitBrightnessTouchForFalsing()
                        }
                        false
                    },
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
            overriddenByAppState = overriddenByAppState,
            showToast = {
                viewModel.showToast(context, R.string.quick_settings_brightness_unable_adjust_msg)
            },
        )
    }
}

data class ContainerColors(val idleColor: Color, val mirrorColor: Color) {
    companion object {
        fun singleColor(color: Color) = ContainerColors(color, color)

        val defaultContainerColor: Color
            @Composable @ReadOnlyComposable get() = colorResource(R.color.shade_panel_fallback)
    }
}

private object Dimensions {
    val SliderBackgroundFrameSize = DpSize(10.dp, 6.dp)
    val SliderBackgroundRoundedCorner = 24.dp
    val SliderTrackRoundedCorner = 12.dp
    val IconSize = DpSize(28.dp, 28.dp)
    val IconPadding = 6.dp
    val ThumbTrackGapSize = 6.dp

    val ThumbHeight : Dp
        @Composable
        @ReadOnlyComposable
        get() =
            dimensionResource(id = R.dimen.overlay_qs_layout_brightness_thumb_height)

    val ThumbWidth : Dp
        @Composable
        @ReadOnlyComposable
        get() =
            dimensionResource(id = R.dimen.overlay_qs_layout_brightness_thumb_width)

    val TrackHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() =
            dimensionResource(id = R.dimen.overlay_qs_layout_brightness_track_height)
}

private object AnimationSpecs {
    val IconAppearSpec = tween<Float>(durationMillis = 100, delayMillis = 33)
    val IconDisappearSpec = tween<Float>(durationMillis = 50)
}

private suspend fun Animatable<Float, AnimationVector1D>.appear() =
    animateTo(targetValue = 1f, animationSpec = IconAppearSpec)

private suspend fun Animatable<Float, AnimationVector1D>.disappear() =
    animateTo(targetValue = 0f, animationSpec = IconDisappearSpec)

@VisibleForTesting
object BrightnessSliderMotionTestKeys {
    val AnimatingIcon = MotionTestValueKey<Boolean>("animatingIcon")
    val ActiveIconAlpha = MotionTestValueKey<Float>("activeIconAlpha")
    val InactiveIconAlpha = MotionTestValueKey<Float>("inactiveIconAlpha")
}

@Composable
private fun colors(): SliderColors {
    return SliderDefaults.colors()
        .copy(
            inactiveTrackColor = LocalAndroidColorScheme.current.surfaceEffect1,
            activeTickColor = MaterialTheme.colorScheme.onPrimary,
            inactiveTickColor = MaterialTheme.colorScheme.onSurface,
        )
}
