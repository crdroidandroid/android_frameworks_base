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

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.UserHandle
import android.os.Vibrator
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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
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
import com.android.compose.lifecycle.DisposableEffectWithLifecycle
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconAppearSpec
import com.android.systemui.brightness.ui.compose.AnimationSpecs.IconDisappearSpec
import com.android.systemui.brightness.ui.compose.InternalDimensions.IconPadding
import com.android.systemui.brightness.ui.compose.InternalDimensions.SliderTrackRoundedCorner
import com.android.systemui.brightness.ui.compose.InternalDimensions.ThumbTrackGapSize
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.colors.SystemUISliderColors
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.gradient.GradientColors
import com.android.systemui.gradient.diagonal
import com.android.systemui.gradient.horizontal
import com.android.systemui.gradient.rememberGradientColors
import com.android.systemui.gradient.rememberGradientEnabled
import com.android.systemui.gradient.toPath
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.util.policy.PolicyRestriction
import kotlin.math.roundToInt
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
    enabled: Boolean = true,
    showToast: () -> Unit = {},
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
) {
    val context = LocalContext.current
    val cr = context.contentResolver

    var hapticsEnabled by remember { mutableStateOf(readEnableHaptics(cr)) }

    val shapeMode = rememberSliderShapeMode()
    val trackCornerDp: Dp = trackCornerFor(shapeMode)

    val isGradientEnabled = rememberGradientEnabled(Settings.System.QS_BRIGHTNESS_SLIDER_GRADIENT)
    val gradientColors: GradientColors? = rememberGradientColors().takeIf { isGradientEnabled }

    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = restriction is PolicyRestriction.Restricted
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
    val baseColors = SystemUISliderColors.Defaults
    val colors =
        if (gradientColors != null) {
            baseColors.copy(activeTrackColor = Color.Transparent)
        } else {
            baseColors
        }
    val thumbColors =
        if (gradientColors != null) {
            colors.copy(thumbColor = gradientColors.startColor)
        } else {
            colors
        }

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
    val iconSize = dimensions.iconSize
    val inactiveIconColor = colors.inactiveTickColor
    // Offset from the right
    val trackIcon: DrawScope.(Offset, Color, Float) -> Unit = remember {
        { offset, color, alpha ->
            val rtl = layoutDirection == LayoutDirection.Rtl
            scale(if (rtl) -1f else 1f, 1f) {
                translate(offset.x - IconPadding.toPx() - iconSize.toSize().width, offset.y) {
                    with(painter) {
                        draw(
                            iconSize.toSize(),
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
                Modifier
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
                    thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                    colors = thumbColors,
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

                LaunchedEffect(
                    iconActiveAlphaAnimatable,
                    iconInactiveAlphaAnimatable,
                    showIconActive,
                ) {
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
                                iconActiveAlphaAnimatable.value exportAs
                                    BrightnessSliderMotionTestKeys.ActiveIconAlpha
                                iconInactiveAlphaAnimatable.value exportAs
                                    BrightnessSliderMotionTestKeys.InactiveIconAlpha
                            }
                            .height(dimensions.trackHeight)
                            .drawWithCache {
                                // Cache the track outline and the brush, they only depend on the
                                // size/layout direction and not on the slider value.
                                val trackPath =
                                    RoundedCornerShape(trackCornerDp)
                                        .createOutline(size, layoutDirection, this)
                                        .toPath()
                                val isRtl = layoutDirection == LayoutDirection.Rtl
                                val gradientBrush: Brush? =
                                    gradientColors?.horizontal(reversed = isRtl)

                                onDrawWithContent {
                                    drawContent()

                                    if (gradientBrush != null) {
                                        val gapPx = ThumbTrackGapSize.toPx()
                                        val activeWidth =
                                            (size.width * sliderState.coercedValueAsFraction -
                                                    gapPx)
                                                .coerceIn(0f, size.width)
                                        if (activeWidth > 0f) {
                                            clipPath(trackPath) {
                                                drawRect(
                                                    brush = gradientBrush,
                                                    topLeft =
                                                        if (isRtl) {
                                                            Offset(size.width - activeWidth, 0f)
                                                        } else {
                                                            Offset.Zero
                                                        },
                                                    size = Size(activeWidth, size.height),
                                                )
                                            }
                                        }
                                    }

                                    val yOffset = size.height / 2 - iconSize.toSize().height / 2
                                    val activeTrackStart = 0f
                                    val activeTrackEnd =
                                        size.width * sliderState.coercedValueAsFraction -
                                            ThumbTrackGapSize.toPx()
                                    val inactiveTrackStart =
                                        activeTrackEnd + ThumbTrackGapSize.toPx() * 2
                                    val inactiveTrackEnd = size.width

                                    val activeTrackWidth = activeTrackEnd - activeTrackStart
                                    val inactiveTrackWidth = inactiveTrackEnd - inactiveTrackStart

                                    if (
                                        iconSize.toSize().width <
                                            inactiveTrackWidth - IconPadding.toPx() * 2
                                    ) {
                                        showIconActive = false
                                        trackIcon(
                                            Offset(inactiveTrackEnd, yOffset),
                                            inactiveIconColor,
                                            iconInactiveAlphaAnimatable.value,
                                        )
                                    } else if (
                                        iconSize.toSize().width <
                                            activeTrackWidth - IconPadding.toPx() * 2
                                    ) {
                                        showIconActive = true
                                        trackIcon(
                                            Offset(activeTrackEnd, yOffset),
                                            activeIconColor,
                                            iconActiveAlphaAnimatable.value,
                                        )
                                    }
                                }
                            },
                    trackCornerSize = trackCornerDp,
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
                onIconClick = onIconClick,
                hapticsEnabled = hapticsEnabled,
                gradientColors = gradientColors,
                buttonSize = dimensions.thumbHeight,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VolumeSlider(
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    modifier: Modifier = Modifier,
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
) {
    val context = LocalContext.current
    val cr = context.contentResolver

    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val streamType = AudioManager.STREAM_MUSIC

    val maxVolume =
        remember(audioManager) {
            runCatching { audioManager?.getStreamMaxVolume(streamType) }.getOrNull() ?: 100
        }
    val minVolume =
        remember(audioManager) {
            runCatching { audioManager?.getStreamMinVolume(streamType) }.getOrNull() ?: 0
        }
    // Guard against a degenerate range on odd device configurations.
    val safeMax = if (maxVolume > minVolume) maxVolume else minVolume + 1
    val floatValueRange = minVolume.toFloat()..safeMax.toFloat()

    var hapticsEnabled by remember { mutableStateOf(readEnableHaptics(cr)) }
    val showRinger = rememberShowRingerMode()

    val shapeMode = rememberSliderShapeMode()
    val trackCornerDp: Dp = trackCornerFor(shapeMode)

    val isGradientEnabled = rememberGradientEnabled(Settings.System.QS_BRIGHTNESS_SLIDER_GRADIENT)
    val gradientColors: GradientColors? = rememberGradientColors().takeIf { isGradientEnabled }

    val baseColors = SystemUISliderColors.Defaults
    val colors =
        if (gradientColors != null) {
            baseColors.copy(activeTrackColor = Color.Transparent)
        } else {
            baseColors
        }
    val thumbColors =
        if (gradientColors != null) {
            colors.copy(thumbColor = gradientColors.startColor)
        } else {
            colors
        }
    val activeIconColor = colors.activeTickColor
    val inactiveIconColor = colors.inactiveTickColor
    val iconSize = dimensions.iconSize

    var dragging by remember { mutableStateOf(false) }
    var systemVolume by
        remember(audioManager) {
            mutableIntStateOf(
                runCatching { audioManager?.getStreamVolume(streamType) }.getOrNull() ?: minVolume
            )
        }
    var value by remember(audioManager) { mutableIntStateOf(systemVolume) }

    LaunchedEffect(systemVolume) {
        if (!dragging) {
            value = systemVolume
        }
    }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "VolumeSliderAnimatedValue")

    DisposableEffect(context, audioManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.VOLUME_CHANGED_ACTION) {
                        val type = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                        if (type == streamType) {
                            systemVolume =
                                intent.getIntExtra(
                                    AudioManager.EXTRA_VOLUME_STREAM_VALUE,
                                    systemVolume,
                                )
                        }
                    }
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.VOLUME_CHANGED_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )

        val hapticsObserver =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { hapticsEnabled = readEnableHaptics(cr) }
                }
            }
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC),
            false,
            hapticsObserver,
            UserHandle.USER_ALL,
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { cr.unregisterContentObserver(hapticsObserver) }
        }
    }

    LaunchedEffect(audioManager) {
        runCatching { audioManager?.getStreamVolume(streamType) }.getOrNull()?.let {
            systemVolume = it
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel: SliderHapticsViewModel? =
        if (hapticsEnabled) {
            rememberViewModel(traceName = "VolumeSliderHapticsViewModel") {
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

    val iconRes =
        if (value <= minVolume) R.drawable.ic_volume_media_mute else R.drawable.ic_volume_media
    val painter = painterResource(iconRes)
    val currentPainter by rememberUpdatedState(painter)
    val trackIcon: DrawScope.(Offset, Color, Float) -> Unit = remember(iconSize) {
        { offset, color, alpha ->
            val rtl = layoutDirection == LayoutDirection.Rtl
            scale(if (rtl) -1f else 1f, 1f) {
                translate(offset.x - IconPadding.toPx() - iconSize.toSize().width, offset.y) {
                    with(currentPainter) {
                        draw(
                            iconSize.toSize(),
                            colorFilter = ColorFilter.tint(color),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
    }

    val contentDescription = stringResource(R.string.stream_music)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Slider(
            value = animatedValue,
            valueRange = floatValueRange,
            enabled = true,
            colors = colors,
            onValueChange = {
                dragging = true
                hapticsViewModel?.onValueChange(it)
                val newValue = it.roundToInt().coerceIn(minVolume, safeMax)
                if (newValue != value) {
                    value = newValue
                    setStreamVolume(audioManager, streamType, newValue)
                }
            },
            onValueChangeFinished = {
                hapticsViewModel?.onValueChangeEnded()
                setStreamVolume(audioManager, streamType, value)
                dragging = false
            },
            modifier =
                Modifier.weight(1f)
                    .sysuiResTag("volume_slider")
                    .semantics(mergeDescendants = true) {
                        this.text = AnnotatedString(contentDescription)
                    }
                    .sliderPercentage {
                        (value - minVolume).toFloat() /
                            (safeMax - minVolume).coerceAtLeast(1).toFloat()
                    },
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    enabled = true,
                    thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
                    colors = thumbColors,
                )
            },
            track = { sliderState ->
                var showIconActive by remember { mutableStateOf(true) }
                val iconActiveAlphaAnimatable = remember {
                    Animatable(
                        initialValue = 1f,
                        typeConverter = Float.VectorConverter,
                        label = "volIconActiveAlpha",
                    )
                }
                val iconInactiveAlphaAnimatable = remember {
                    Animatable(
                        initialValue = 0f,
                        typeConverter = Float.VectorConverter,
                        label = "volIconInactiveAlpha",
                    )
                }

                LaunchedEffect(
                    iconActiveAlphaAnimatable,
                    iconInactiveAlphaAnimatable,
                    showIconActive,
                ) {
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
                        Modifier.height(dimensions.trackHeight).drawWithCache {
                            val trackPath =
                                RoundedCornerShape(trackCornerDp)
                                    .createOutline(size, layoutDirection, this)
                                    .toPath()
                            val isRtl = layoutDirection == LayoutDirection.Rtl
                            val gradientBrush: Brush? =
                                gradientColors?.horizontal(reversed = isRtl)

                            onDrawWithContent {
                                drawContent()

                                if (gradientBrush != null) {
                                    val gapPx = ThumbTrackGapSize.toPx()
                                    val activeWidth =
                                        (size.width * sliderState.coercedValueAsFraction - gapPx)
                                            .coerceIn(0f, size.width)
                                    if (activeWidth > 0f) {
                                        clipPath(trackPath) {
                                            drawRect(
                                                brush = gradientBrush,
                                                topLeft =
                                                    if (isRtl) {
                                                        Offset(size.width - activeWidth, 0f)
                                                    } else {
                                                        Offset.Zero
                                                    },
                                                size = Size(activeWidth, size.height),
                                            )
                                        }
                                    }
                                }

                                val yOffset = size.height / 2 - iconSize.toSize().height / 2
                                val activeTrackStart = 0f
                                val activeTrackEnd =
                                    size.width * sliderState.coercedValueAsFraction -
                                        ThumbTrackGapSize.toPx()
                                val inactiveTrackStart =
                                    activeTrackEnd + ThumbTrackGapSize.toPx() * 2
                                val inactiveTrackEnd = size.width

                                val activeTrackWidth = activeTrackEnd - activeTrackStart
                                val inactiveTrackWidth = inactiveTrackEnd - inactiveTrackStart

                                if (
                                    iconSize.toSize().width <
                                        inactiveTrackWidth - IconPadding.toPx() * 2
                                ) {
                                    showIconActive = false
                                    trackIcon(
                                        Offset(inactiveTrackEnd, yOffset),
                                        inactiveIconColor,
                                        iconInactiveAlphaAnimatable.value,
                                    )
                                } else if (
                                    iconSize.toSize().width <
                                        activeTrackWidth - IconPadding.toPx() * 2
                                ) {
                                    showIconActive = true
                                    trackIcon(
                                        Offset(activeTrackEnd, yOffset),
                                        activeIconColor,
                                        iconActiveAlphaAnimatable.value,
                                    )
                                }
                            }
                        },
                    trackCornerSize = trackCornerDp,
                    trackInsideCornerSize = 2.dp,
                    drawStopIndicator = null,
                    thumbTrackGapSize = ThumbTrackGapSize,
                    colors = colors,
                )
            },
        )

        if (showRinger) {
            Spacer(modifier = Modifier.width(10.dp))
            VolumeRingerButton(
                hapticsEnabled = hapticsEnabled,
                gradientColors = gradientColors,
                buttonSize = dimensions.thumbHeight,
            )
        }
    }
}

@Composable
private fun VolumeRingerButton(
    hapticsEnabled: Boolean,
    gradientColors: GradientColors? = null,
    buttonSize: Dp = 52.dp,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val hasVibrator =
        remember(context) {
            runCatching { context.getSystemService(Vibrator::class.java)?.hasVibrator() }
                .getOrNull() == true
        }

    var ringerMode by
        remember(audioManager) { mutableIntStateOf(readRingerMode(audioManager)) }

    DisposableEffect(context, audioManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        AudioManager.RINGER_MODE_CHANGED_ACTION,
                        AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION ->
                            ringerMode = readRingerMode(audioManager)
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val isOn = ringerMode != AudioManager.RINGER_MODE_SILENT
    val ringerBrush: Brush? = if (isOn) gradientColors?.diagonal() else null

    val backgroundColor by
        animateColorAsState(
            targetValue =
                if (isOn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalAndroidColorScheme.current.surfaceEffect1
                }
        )
    val iconTint by
        animateColorAsState(
            targetValue =
                if (isOn) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
        )
    val animatedCornerRadius by
        animateDpAsState(
            targetValue = if (isOn) SliderTrackRoundedCorner else buttonSize / 2
        )
    val shapeMode = rememberSliderShapeMode()
    val ringerShape =
        when (shapeMode) {
            1 -> CircleShape
            2 -> RoundedCornerShape(12.dp)
            3 -> RoundedCornerShape(0.dp)
            else -> RoundedCornerShape(animatedCornerRadius)
        }

    val painterRes =
        when (ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_speaker_mute
            else -> R.drawable.ic_speaker_on
        }
    val hapticConstant =
        if (isOn) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    val contentDescription =
        when (ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> stringResource(R.string.accessibility_ringer_vibrate)
            AudioManager.RINGER_MODE_SILENT -> stringResource(R.string.accessibility_ringer_silent)
            else -> stringResource(R.string.stream_ring)
        }

    IconButton(
        onClick = {
            if (hapticsEnabled) {
                view.performHapticFeedback(hapticConstant)
            }
            val next = nextRingerMode(ringerMode, hasVibrator)
            // Can throw when DND policy access is not granted; fall back to the real state.
            val applied = runCatching { audioManager?.ringerModeInternal = next }.isSuccess
            ringerMode = if (applied) next else readRingerMode(audioManager)
        },
        modifier =
            Modifier.size(buttonSize)
                .clip(ringerShape)
                .background(backgroundColor)
                .thenIf(ringerBrush != null) { Modifier.background(ringerBrush!!) },
    ) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = contentDescription,
            tint = iconTint,
        )
    }
}

@Composable
fun rememberSliderShapeMode(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readShapeMode(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_BRIGHTNESS_SLIDER_SHAPE, 0,
                UserHandle.USER_CURRENT
            )
        } catch (_: Throwable) {
            0
        }
    }

    var shapeMode by remember { mutableIntStateOf(readShapeMode()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    shapeMode = readShapeMode()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_BRIGHTNESS_SLIDER_SHAPE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return shapeMode
}

/**
 * Layout of the volume slider inside the QS brightness row.
 *
 * 0 - brightness only (volume slider hidden)
 * 1 - volume slider beside the brightness slider
 * 2 - volume slider only (brightness slider hidden)
 * 3 - volume slider below the brightness slider
 */
@Composable
private fun rememberVolumeSliderMode(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readMode(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_SHOW_VOLUME_SLIDER,
                VolumeSliderMode.DEFAULT, UserHandle.USER_CURRENT
            ).coerceIn(VolumeSliderMode.OFF, VolumeSliderMode.BELOW)
        } catch (_: Throwable) {
            VolumeSliderMode.DEFAULT
        }
    }

    var mode by remember { mutableIntStateOf(readMode()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    mode = readMode()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_SHOW_VOLUME_SLIDER),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return mode
}

@Composable
private fun rememberShowRingerMode(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    var enabled by remember { mutableStateOf(readShowRingerMode(contentResolver)) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    enabled = readShowRingerMode(contentResolver)
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_SHOW_RINGER_MODE),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return enabled
}

private fun Modifier.sliderBackground(
    backgroundFrameSize: DpSize,
    backgroundRoundedCorner: Dp,
    color: Color,
) = drawWithCache {
    val offsetAround = backgroundFrameSize.toSize()
    val newSize = Size(size.width + 2 * offsetAround.width, size.height + 2 * offsetAround.height)
    val offset = Offset(-offsetAround.width, -offsetAround.height)
    val cornerRadius = CornerRadius(backgroundRoundedCorner.toPx())
    onDrawBehind {
        drawRoundRect(color = color, topLeft = offset, size = newSize, cornerRadius = cornerRadius)
    }
}

private fun trackCornerFor(shapeMode: Int, default: Dp = SliderTrackRoundedCorner): Dp =
    when (shapeMode) {
        1 -> 24.dp /* Circle */
        2 -> 12.dp /* Rounded Square */
        3 -> 0.dp /* Square */
        else -> default
    }

private fun backgroundCornerFor(shapeMode: Int, default: Dp): Dp =
    when (shapeMode) {
        1 -> 50.dp /* Circle */
        2 -> 24.dp /* Rounded Square */
        3 -> 0.dp /* Square */
        else -> default
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

private fun readShowRingerMode(cr: ContentResolver): Boolean =
    try {
        Settings.System.getIntForUser(
            cr, Settings.System.QS_SHOW_RINGER_MODE,
            1, UserHandle.USER_CURRENT
        ) != 0
    } catch (_: Throwable) {
        true
    }

private fun readRingerMode(audioManager: AudioManager?): Int =
    runCatching { audioManager?.ringerModeInternal }.getOrNull()
        ?: AudioManager.RINGER_MODE_NORMAL

private fun nextRingerMode(current: Int, hasVibrator: Boolean): Int =
    if (hasVibrator) {
        when (current) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
    } else {
        when (current) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
    }

/** Setting the stream volume can throw when a DND policy is active. */
private fun setStreamVolume(audioManager: AudioManager?, streamType: Int, volume: Int) {
    runCatching { audioManager?.setStreamVolume(streamType, volume, 0) }
}

@Composable
private fun drawAutoBrightnessButton(
    autoMode: Boolean,
    onIconClick: suspend () -> Unit,
    hapticsEnabled: Boolean,
    gradientColors: GradientColors? = null,
    buttonSize: Dp = 52.dp,
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val autoBrush: Brush? = if (autoMode) gradientColors?.diagonal() else null
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
    val animatedCornerRadius by animateDpAsState(
        targetValue = if (autoMode) {
            SliderTrackRoundedCorner
        } else {
            buttonSize / 2
        }
    )
    val shapeMode = rememberSliderShapeMode()
    val autoIconShape = when (shapeMode) {
        1 -> CircleShape
        2 -> RoundedCornerShape(12.dp)
        3 -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(animatedCornerRadius)
    }

    IconButton(
        onClick = {
            if (hapticsEnabled) {
                view.performHapticFeedback(hapticConstant)
            }
            coroutineScope.launch { onIconClick() }
        },
        modifier = Modifier
            .size(buttonSize)
            .clip(autoIconShape)
            .background(backgroundColor)
            .thenIf(autoBrush != null) {
                Modifier.background(autoBrush!!)
            }
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
    dimensions: BrightnessSliderDimensions = BrightnessSliderDimensions.Default,
) {
    val volumeSliderMode = rememberVolumeSliderMode()
    val wantsBrightness = volumeSliderMode != VolumeSliderMode.VOLUME_ONLY
    val wantsVolume = volumeSliderMode != VolumeSliderMode.OFF

    val gamma = viewModel.currentBrightness.value
    // Ignore initial negative value, but keep rendering the volume slider if it is the only
    // thing on screen (or while brightness is still settling).
    val brightnessReady = gamma != BrightnessSliderViewModel.initialValue.value
    if (!brightnessReady && !wantsVolume) {
        return
    }
    val showBrightness = wantsBrightness && brightnessReady

    val autoMode = viewModel.autoMode
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val overriddenByAppState by viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }

    val shapeMode = rememberSliderShapeMode()
    val trackCornerDp: Dp = trackCornerFor(shapeMode)
    val backgroundRoundedCorner: Dp =
        backgroundCornerFor(shapeMode, dimensions.backgroundRoundedCorner)

    DisposableEffectWithLifecycle(Unit) {
        enabled = true
        onDispose {
            dragging = false
            viewModel.setIsDragging(false)
            enabled = false
        }
    }

    // Use dragging instead of viewModel.showMirror so the color starts changing as soon as the
    // dragging state changes. If not, we may be waiting for the background to finish fading in
    // when stopping dragging
    val containerColor by
        animateColorAsState(
            if (dragging && viewModel.supportsMirroring) {
                containerColors.mirrorColor
            } else {
                containerColors.idleColor
            }
        )

    val backgroundFrameSize =
        DpSize(dimensions.backgroundFrameWidth, dimensions.backgroundFrameHeight)

    val isRestricted = restriction is PolicyRestriction.Restricted

    val brightnessSlider: @Composable () -> Unit = {
        BrightnessSlider(
            enabled = enabled && !isRestricted,
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
                        cornerSize = CornerSize(trackCornerDp),
                    )
                    .then(if (viewModel.showMirror) Modifier.drawInOverlay() else Modifier)
                    .sliderBackground(
                        backgroundFrameSize,
                        backgroundRoundedCorner,
                        containerColor,
                    )
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
                viewModel.showToast(
                    context,
                    com.android.internal.R.string.brightness_unable_adjust_msg,
                )
            },
            dimensions = dimensions,
        )
    }

    val volumeSlider: @Composable () -> Unit = {
        VolumeSlider(
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
            modifier =
                Modifier.borderOnFocus(
                        color = MaterialTheme.colorScheme.secondary,
                        cornerSize = CornerSize(trackCornerDp),
                    )
                    // The volume slider is never mirrored, so it keeps the idle container color.
                    .sliderBackground(
                        backgroundFrameSize,
                        backgroundRoundedCorner,
                        containerColors.idleColor,
                    )
                    .fillMaxWidth(),
            dimensions = dimensions,
        )
    }

    // Leave room for both background frames plus a small visual gap between the two sliders.
    val horizontalGap = dimensions.backgroundFrameWidth * 2 + 4.dp
    val verticalGap = dimensions.backgroundFrameHeight * 2 + 4.dp

    Box(
        modifier =
            modifier
                .padding(vertical = { dimensions.verticalPadding.roundToPx() })
                .fillMaxWidth()
                .sysuiResTag("brightness_slider")
    ) {
        when {
            showBrightness && wantsVolume && volumeSliderMode == VolumeSliderMode.BESIDE ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.weight(1f)) { brightnessSlider() }

                    Spacer(modifier = Modifier.width(horizontalGap))

                    Box(modifier = Modifier.weight(1f)) { volumeSlider() }
                }

            showBrightness && wantsVolume && volumeSliderMode == VolumeSliderMode.BELOW ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    brightnessSlider()

                    Spacer(modifier = Modifier.height(verticalGap))

                    volumeSlider()
                }

            showBrightness -> brightnessSlider()

            else -> volumeSlider()
        }
    }
}

private object VolumeSliderMode {
    /** Brightness slider only. */
    const val OFF = 0
    /** Volume slider next to the brightness slider. */
    const val BESIDE = 1
    /** Volume slider instead of the brightness slider. */
    const val VOLUME_ONLY = 2
    /** Volume slider below the brightness slider. */
    const val BELOW = 3

    const val DEFAULT = BESIDE
}

data class ContainerColors(val idleColor: Color, val mirrorColor: Color) {
    companion object {
        fun singleColor(color: Color) = ContainerColors(color, color)

        val defaultContainerColor: Color
            @Composable @ReadOnlyComposable get() = colorResource(R.color.shade_panel_fallback)
    }
}

data class BrightnessSliderDimensions(
    val iconSize: DpSize,
    val thumbHeight: Dp,
    val thumbWidth: Dp,
    val trackHeight: Dp,
    val verticalPadding: Dp,
    val backgroundRoundedCorner: Dp,
    val backgroundFrameWidth: Dp,
    val backgroundFrameHeight: Dp,
) {
    companion object {
        val Default =
            BrightnessSliderDimensions(
                iconSize = DpSize(28.dp, 28.dp),
                thumbHeight = 52.dp,
                thumbWidth = 4.dp,
                trackHeight = 40.dp,
                verticalPadding = 6.dp,
                backgroundRoundedCorner = 24.dp,
                backgroundFrameWidth = 10.dp,
                backgroundFrameHeight = 6.dp,
            )
    }
}

private object InternalDimensions {
    val SliderTrackRoundedCorner = 12.dp
    val IconPadding = 6.dp
    val ThumbTrackGapSize = 6.dp
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
    val ActiveIconAlpha = MotionTestValueKey<Float>("activeIconAlpha")
    val InactiveIconAlpha = MotionTestValueKey<Float>("inactiveIconAlpha")
}
