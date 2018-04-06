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

package com.android.systemui.qs.footer.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.compose.animation.scene.ContentScope
import com.android.compose.lifecycle.LaunchedEffectWithLifecycle
import com.android.compose.modifiers.animatedBackground
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.theme.colorAttr
import com.android.systemui.Flags.notificationShadeBlur
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.footer.ui.compose.FooterActionsDefaults.FOOTER_TEXT_FADE_DURATION_MILLIS
import com.android.systemui.qs.footer.ui.compose.FooterActionsDefaults.FOOTER_TEXT_MINIMUM_SCALE_Y
import com.android.systemui.qs.footer.ui.compose.FooterActionsDefaults.FooterButtonHeight
import com.android.systemui.qs.footer.ui.compose.rememberSystemSettingEnabled
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsForegroundServicesButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsSecurityButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterTextButtonViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackViewModel
import com.android.systemui.qs.shared.ui.QuickSettings
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ContentScope.FooterActionsWithAnimatedVisibility(
    viewModel: FooterActionsViewModel,
    isCustomizing: Boolean,
    customizingAnimationDuration: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = !isCustomizing,
        enter =
            expandVertically(
                animationSpec = tween(customizingAnimationDuration),
                initialHeight = { 0 },
            ) + fadeIn(tween(customizingAnimationDuration)),
        exit =
            shrinkVertically(
                animationSpec = tween(customizingAnimationDuration),
                targetHeight = { 0 },
            ) + fadeOut(tween(customizingAnimationDuration)),
        modifier = modifier.fillMaxWidth(),
    ) {
        QuickSettingsTheme {
            // This view has its own horizontal padding
            // TODO(b/321716470) This should use a lifecycle tied to the scene.
            Element(QuickSettings.Elements.FooterActions, Modifier) {
                FooterActions(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun rememberSystemSettingEnabled(
    key: String,
    defaultValue: Int = 1,
    userId: Int = UserHandle.USER_CURRENT,
): State<Boolean> {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val uri = remember(key) { Settings.System.getUriFor(key) }

    return produceState(
        initialValue = Settings.System.getIntForUser(resolver, key, defaultValue, userId) == 1,
        key1 = uri,
        key2 = userId,
    ) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = Settings.System.getIntForUser(resolver, key, defaultValue, userId) == 1
            }
        }
        resolver.registerContentObserver(uri, false, observer, userId)
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
}

/** The Quick Settings footer actions row. */
@Composable
fun FooterActions(viewModel: FooterActionsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Collect alphas as soon as we are composed, even when not visible.
    val alpha by viewModel.alpha.collectAsStateWithLifecycle()
    val backgroundAlpha = viewModel.backgroundAlpha.collectAsStateWithLifecycle()

    var security by remember { mutableStateOf<FooterActionsSecurityButtonViewModel?>(null) }
    var foregroundServices by remember {
        mutableStateOf<FooterActionsForegroundServicesButtonViewModel?>(null)
    }
    var userSwitcher by remember { mutableStateOf<FooterActionsButtonViewModel?>(null) }
    var settings by remember { mutableStateOf<FooterActionsButtonViewModel?>(null) }

    var textFeedback by remember {
        mutableStateOf<TextFeedbackViewModel>(TextFeedbackViewModel.NoFeedback)
    }

    LaunchedEffect(context, viewModel) {
        launch {
            // Listen for dialog requests as soon as we are composed, even when not visible.
            viewModel.observeDeviceMonitoringDialogRequests(context)
        }
    }

    // Listen for model changes only when QS are visible.
    LaunchedEffectWithLifecycle(
        viewModel.security,
        viewModel.foregroundServices,
        viewModel.userSwitcher,
        viewModel.textFeedback,
        viewModel.settings,
        minActiveState = Lifecycle.State.RESUMED,
    ) {
        launch { viewModel.security.collect { security = it } }
        launch { viewModel.foregroundServices.collect { foregroundServices = it } }
        launch { viewModel.userSwitcher.collect { userSwitcher = it } }
        launch { viewModel.textFeedback.collect { textFeedback = it } }
        launch { viewModel.settings.collect { settings = it } }
    }

    val backgroundColor =
        if (!notificationShadeBlur()) colorAttr(R.attr.underSurface) else Color.Transparent
    val backgroundAlphaValue = if (!notificationShadeBlur()) backgroundAlpha::value else ({ 0f })
    val contentColor = MaterialTheme.colorScheme.onSurface
    val backgroundTopRadius = dimensionResource(R.dimen.qs_corner_radius)
    val backgroundModifier =
        remember(backgroundColor, backgroundAlphaValue, backgroundTopRadius) {
            Modifier.animatedBackground(
                { backgroundColor },
                backgroundAlphaValue,
                RoundedCornerShape(topStart = backgroundTopRadius, topEnd = backgroundTopRadius),
            )
        }

    val horizontalPadding = dimensionResource(R.dimen.qs_content_horizontal_padding)
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .then(backgroundModifier)
            .padding(
                top = dimensionResource(R.dimen.qs_footer_actions_top_padding),
                bottom = dimensionResource(R.dimen.qs_footer_actions_bottom_padding),
                start = horizontalPadding,
                end = horizontalPadding,
            )
            .layout { measurable, constraints ->
                // All buttons have a 4dp padding to increase their touch size. To be consistent
                // with the View implementation, we want to left-most and right-most buttons to be
                // visually aligned with the left and right sides of this row. So we let this
                // component be 2*4dp wider and then offset it by -4dp to the start.
                val inset = 4.dp.roundToPx()
                val additionalWidth = inset * 2
                val newConstraints =
                    if (constraints.hasBoundedWidth) {
                        constraints.copy(maxWidth = constraints.maxWidth + additionalWidth)
                    } else {
                        constraints
                    }
                val placeable = measurable.measure(newConstraints)

                val width = constraints.constrainWidth(placeable.width - additionalWidth)
                val height = constraints.constrainHeight(placeable.height)
                layout(width, height) { placeable.place(-inset, 0) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            val useModifierBasedExpandable = true

            // The viewModel to show, in order of priority:
            // 1. Text feedback
            // 2. Security
            // 3. Foreground services
            val textViewModel: FooterTextButtonViewModel? =
                textFeedback as? TextFeedbackViewModel.LoadedTextFeedback
                    ?: (security ?: foregroundServices)
            AnimatedFooterTextButton(textViewModel, useModifierBasedExpandable, Modifier.weight(1f))

            // Only add the foreground services number if text shouldn't be displayed
            ForegroundServicesNumberButton(
                { foregroundServices.takeIf { it?.displayText == false } },
                useModifierBasedExpandable,
            )

            IconButton(
                { userSwitcher },
                useModifierBasedExpandable,
                Modifier.sysuiResTag("multi_user_switch"),
            )

            val showSettings by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_SETTINGS)
            val showPowerMenu by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_POWER_MENU)

            if (showSettings) {
                IconButton(
                    { settings },
                    useModifierBasedExpandable,
                    Modifier.sysuiResTag("settings_button_container"),
                )
            }

            if (showPowerMenu) {
                IconButton(
                    { viewModel.power },
                    useModifierBasedExpandable,
                    Modifier.sysuiResTag("pm_lite"),
                )
            }
        }
    }
}

/**
 * Animated text button for [FooterTextButtonViewModel].
 *
 * This composable animates the entry/exit of the button, as well as cross fade the content when the
 * displayed information changes.
 */
@Composable
private fun AnimatedFooterTextButton(
    textViewModel: FooterTextButtonViewModel?,
    useModifierBasedExpandable: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = updateTransition(textViewModel)
    val scaleY by transition.animateFloat { if (it == null) FOOTER_TEXT_MINIMUM_SCALE_Y else 1f }
    val alpha by transition.animateFloat { if (it == null) 0f else 1f }
    val onClick: ((Expandable) -> Unit)? =
        textViewModel?.onClick?.let { onClick ->
            val context = LocalContext.current
            { expandable -> onClick(context, expandable) }
        }

    Box(
        modifier
            .height(FooterButtonHeight)
            .animatedScaledHeight { scaleY }
            .animatedWidth()
            .graphicsLayer { this.alpha = alpha }
    ) {
        val colors = textButtonColors()
        CircleExpandable(
            color = colors.background,
            contentColor = colors.content,
            borderStroke = colors.border,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            onClick = onClick,
            useModifierBasedImplementation = useModifierBasedExpandable,
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    // Using delayMillis to animate the fade in after the fade out completes at the
                    // halfway point
                    fadeIn(
                        tween(
                            durationMillis = FOOTER_TEXT_FADE_DURATION_MILLIS,
                            delayMillis = FOOTER_TEXT_FADE_DURATION_MILLIS,
                        )
                    ) togetherWith
                        fadeOut(tween(durationMillis = FOOTER_TEXT_FADE_DURATION_MILLIS)) using
                        null // Using a SizeTransform causes a weird horizontal translation
                }
            ) {
                when (it) {
                    is TextFeedbackViewModel.LoadedTextFeedback -> {
                        TextButtonContent(it.icon, it.text)
                    }
                    is FooterActionsSecurityButtonViewModel -> {
                        TextButtonContent(it.icon, it.text, showChevron = onClick != null)
                    }
                    is FooterActionsForegroundServicesButtonViewModel -> {
                        TextButtonContent(
                            it.icon,
                            it.text,
                            showChevron = onClick != null,
                            showNewDot = it.hasNewChanges,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The foreground services button in number format.
 *
 * The visibility of this button is animated.
 */
@Composable
private fun ForegroundServicesNumberButton(
    model: () -> FooterActionsForegroundServicesButtonViewModel?,
    useModifierBasedExpandable: Boolean,
) {
    val transition = updateTransition(model())
    val alpha by transition.animateFloat { if (it == null) 0f else 1f }
    (transition.currentState ?: transition.targetState)?.let {
        val onClick: (Expandable) -> Unit =
            it.onClick.let { onClick ->
                val context = LocalContext.current
                { expandable -> onClick(context, expandable) }
            }

        NumberButton(
            it.foregroundServicesCount,
            contentDescription = it.text,
            showNewDot = it.hasNewChanges,
            onClick = onClick,
            useModifierBasedExpandable,
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}

/** A button with an icon. */
@Composable
private fun IconButton(
    model: () -> FooterActionsButtonViewModel?,
    useModifierBasedExpandable: Boolean,
    modifier: Modifier = Modifier,
) {
    val viewModel = model() ?: return
    IconButton(viewModel, useModifierBasedExpandable, modifier)
}

/** A button with an icon. */
@Composable
private fun IconButton(
    model: FooterActionsButtonViewModel,
    useModifierBasedExpandable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = buttonColorsForModel(model)
    CircleExpandable(
        color = colors.background,
        onClick = model.onClick,
        onLongClick = model.onLongClick,
        modifier = modifier,
        useModifierBasedImplementation = useModifierBasedExpandable,
    ) {
        FooterIcon(model.icon, Modifier.size(20.dp), colors.icon)
    }
}

// TODO(b/394738023): Use com.android.systemui.common.ui.compose.Icon instead
@Composable
private fun FooterIcon(icon: Icon, modifier: Modifier = Modifier, tint: Color) {
    val contentDescription = icon.contentDescription?.load()
    when (icon) {
        is Icon.Loaded -> {
            Icon(icon.drawable.toBitmap().asImageBitmap(), contentDescription, modifier, tint)
        }
        is Icon.Resource -> Icon(painterResource(icon.resId), contentDescription, modifier, tint)
    }
}

/** A button with a number an an optional dot (to indicate new changes). */
@Composable
private fun NumberButton(
    number: Int,
    contentDescription: String,
    showNewDot: Boolean,
    onClick: (Expandable) -> Unit,
    useModifierBasedExpandable: Boolean,
    modifier: Modifier = Modifier,
) {
    // By default Expandable will show a ripple above its content when clicked, and clip the content
    // with the shape of the expandable. In this case we also want to show a "new changes dot"
    // outside of the shape, so we can't clip. To work around that we can pass our own interaction
    // source and draw the ripple indication ourselves above the text but below the "new changes
    // dot".
    val interactionSource = remember { MutableInteractionSource() }

    val colors = numberButtonColors()
    CircleExpandable(
        color = colors.background,
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier,
        useModifierBasedImplementation = useModifierBasedExpandable,
    ) {
        Box(Modifier.size(FooterButtonHeight)) {
            Box(
                Modifier.fillMaxSize()
                    .clip(CircleShape)
                    .indication(interactionSource, LocalIndication.current)
            ) {
                Text(
                    number.toString(),
                    modifier =
                        Modifier.align(Alignment.Center).semantics {
                            this.contentDescription = contentDescription
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.content,
                    // TODO(b/242040009): This should only use a standard text style instead and
                    // should not override the text size.
                    fontSize = 18.sp,
                )
            }

            if (showNewDot) {
                NewChangesDot(Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun CircleExpandable(
    color: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = contentColorFor(color),
    borderStroke: BorderStroke? = null,
    onClick: ((Expandable) -> Unit)? = null,
    onLongClick: ((Expandable) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    useModifierBasedImplementation: Boolean,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        color = color,
        contentColor = contentColor,
        borderStroke = borderStroke,
        shape = CircleShape,
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        modifier =
            modifier.borderOnFocus(
                color = MaterialTheme.colorScheme.secondary,
                cornerSize = CornerSize(percent = 50),
            ),
        useModifierBasedImplementation = useModifierBasedImplementation,
        content = content,
    )
}

/** A dot that indicates new changes. */
@Composable
private fun NewChangesDot(modifier: Modifier = Modifier) {
    val contentDescription = stringResource(R.string.fgs_dot_content_description)
    val color = MaterialTheme.colorScheme.tertiary

    Canvas(modifier.size(12.dp).semantics { this.contentDescription = contentDescription }) {
        drawCircle(color)
    }
}

/** A larger button with an icon, some text and an optional dot (to indicate new changes). */
@Composable
private fun TextButton(
    icon: Icon,
    text: String,
    showNewDot: Boolean,
    onClick: ((Expandable) -> Unit)?,
    useModifierBasedExpandable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = textButtonColors()
    CircleExpandable(
        color = colors.background,
        contentColor = colors.content,
        borderStroke = colors.border,
        modifier = modifier.padding(horizontal = 4.dp),
        onClick = onClick,
        useModifierBasedImplementation = useModifierBasedExpandable,
    ) {
        Row(
            Modifier.padding(horizontal = dimensionResource(R.dimen.qs_footer_padding)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, Modifier.padding(end = 12.dp).size(20.dp), colors.content)

            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 0.em,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (showNewDot) {
                NewChangesDot(Modifier.padding(start = 8.dp))
            }

            if (onClick != null) {
                Icon(
                    painterResource(com.android.internal.R.drawable.ic_chevron_end),
                    contentDescription = null,
                    Modifier.padding(start = 8.dp).size(20.dp),
                    colors.content,
                )
            }
        }
    }
}

/** Content to display in the footer text button. */
@Composable
private fun TextButtonContent(
    icon: Icon,
    text: String,
    modifier: Modifier = Modifier,
    showNewDot: Boolean = false,
    showChevron: Boolean = false,
) {
    val contentColor = textButtonColors().content
    Row(
        modifier.padding(horizontal = dimensionResource(R.dimen.qs_footer_padding)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, Modifier.padding(end = 12.dp).size(20.dp), contentColor)

        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 0.em,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (showNewDot) {
            NewChangesDot(Modifier.padding(start = 8.dp))
        }

        if (showChevron) {
            Icon(
                painterResource(com.android.internal.R.drawable.ic_chevron_end),
                contentDescription = null,
                Modifier.padding(start = 8.dp).size(20.dp),
                contentColor,
            )
        }
    }
}

/** Animate the width of this composable based on the incoming width constraints. */
@Composable
private fun Modifier.animatedWidth(): Modifier {
    var targetWidth by remember { mutableIntStateOf(0) }
    var animatable by remember { mutableStateOf<Animatable<Int, AnimationVector1D>?>(null) }
    val scope = rememberCoroutineScope()
    return layout { measurable, constraints ->
        targetWidth = constraints.maxWidth
        val anim =
            animatable ?: Animatable(targetWidth, Int.VectorConverter).also { animatable = it }
        if (anim.targetValue != targetWidth) {
            scope.launch { anim.animateTo(targetWidth) }
        }
        val newConstraints = constraints.copy(minWidth = anim.value, maxWidth = anim.value)
        val placeable = measurable.measure(newConstraints)
        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

/** Animate the height of this composable based on [scale]. */
@Composable
private fun Modifier.animatedScaledHeight(scale: () -> Float): Modifier {
    return layout { measurable, constraints ->
        val newHeight = (constraints.maxHeight * scale()).roundToInt()
        val newConstraints = constraints.copy(minHeight = newHeight, maxHeight = newHeight)
        val placeable = measurable.measure(newConstraints)
        // Layout using the max height to animate the expansion from the top
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
    }
}

@Composable
@ReadOnlyComposable
private fun textButtonColors(): TextButtonColors {
    return if (notificationShadeBlur()) {
        FooterActionsDefaults.blurTextButtonColors()
    } else {
        FooterActionsDefaults.textButtonColors()
    }
}

@Composable
@ReadOnlyComposable
private fun numberButtonColors(): TextButtonColors {
    return if (notificationShadeBlur()) {
        FooterActionsDefaults.blurTextButtonColors()
    } else {
        FooterActionsDefaults.numberButtonColors()
    }
}

@Composable
@ReadOnlyComposable
private fun buttonColorsForModel(footerAction: FooterActionsButtonViewModel): ButtonColors {
    return if (notificationShadeBlur()) {
        when (footerAction) {
            is FooterActionsButtonViewModel.PowerActionViewModel ->
                FooterActionsDefaults.activeButtonColors()
            is FooterActionsButtonViewModel.SettingsActionViewModel ->
                FooterActionsDefaults.inactiveButtonColors()
            is FooterActionsButtonViewModel.UserSwitcherViewModel ->
                FooterActionsDefaults.userSwitcherButtonColors()
        }
    } else {
        ButtonColors(
            icon = footerAction.iconTintFallback?.let { Color(it) } ?: Color.Unspecified,
            background = colorAttr(footerAction.backgroundColorFallback),
        )
    }
}

private data class ButtonColors(val icon: Color, val background: Color)

private data class TextButtonColors(
    val content: Color,
    val background: Color,
    val border: BorderStroke?,
)

private object FooterActionsDefaults {
    const val FOOTER_TEXT_MINIMUM_SCALE_Y = .2f
    const val FOOTER_TEXT_FADE_DURATION_MILLIS = 83
    val FooterButtonHeight = 40.dp

    @Composable
    @ReadOnlyComposable
    fun activeButtonColors(): ButtonColors =
        ButtonColors(
            icon = MaterialTheme.colorScheme.onPrimary,
            background = MaterialTheme.colorScheme.primary,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveButtonColors(): ButtonColors =
        ButtonColors(
            icon = MaterialTheme.colorScheme.onSurface,
            background = LocalAndroidColorScheme.current.surfaceEffect1,
        )

    @Composable
    @ReadOnlyComposable
    fun userSwitcherButtonColors(): ButtonColors =
        ButtonColors(
            icon = Color.Unspecified,
            background = LocalAndroidColorScheme.current.surfaceEffect1,
        )

    @Composable
    @ReadOnlyComposable
    fun blurTextButtonColors(): TextButtonColors =
        TextButtonColors(
            content = MaterialTheme.colorScheme.onSurface,
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            border = null,
        )

    @Composable
    @ReadOnlyComposable
    fun textButtonColors(): TextButtonColors =
        TextButtonColors(
            content = colorAttr(R.attr.onShadeInactiveVariant),
            background = colorAttr(R.attr.underSurface),
            border = BorderStroke(1.dp, colorAttr(R.attr.shadeInactive)),
        )

    @Composable
    @ReadOnlyComposable
    fun numberButtonColors(): TextButtonColors =
        TextButtonColors(
            content = colorAttr(R.attr.onShadeInactiveVariant),
            background = colorAttr(R.attr.shadeInactive),
            border = null,
        )
}
