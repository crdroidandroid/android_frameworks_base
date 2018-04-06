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

package com.android.systemui.qs.panels.ui.compose.toolbar

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.compose.animation.Expandable
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.load
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.compose.rememberSystemSettingEnabled
import com.android.systemui.qs.panels.ui.compose.toolbar.Toolbar.TransitionKeys.SecurityInfoKey
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.PowerMenuToggleButtonUiState
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Toolbar(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val securityInfoCollapsed = viewModel.securityInfoShowCollapsed

        SharedTransitionLayout(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = securityInfoCollapsed,
                contentAlignment = Alignment.CenterStart,
                label = "Toolbar.CollapsedSecurityInfo",
            ) { securityInfoCollapsed ->
                if (securityInfoCollapsed) {
                    StandardToolbarLayout(
                        animatedContentScope = this@AnimatedContent,
                        viewModel = viewModel,
                        isFullyVisible = isFullyVisible,
                    )
                } else {
                    SecurityInfo(
                        viewModel = viewModel.securityInfoViewModel,
                        showCollapsed = false,
                        modifier =
                            Modifier.sharedElement(
                                rememberSharedContentState(key = SecurityInfoKey),
                                animatedVisibilityScope = this@AnimatedContent,
                            ),
                    )
                }
            }
        }

        val showPowerMenu by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_POWER_MENU)

        if (showPowerMenu && viewModel.useInlinePowerMenu) {
            Box {
                PowerMenuToggleButton(
                    viewModel = viewModel.powerMenuToggleButtonUiState,
                    modifier = Modifier.sysuiResTag("pm_lite"),
                )
                if (viewModel.isInlinePowerMenuVisible) {
                    PowerMenu(
                        viewModelFactory = viewModel.powerMenuViewModelFactory,
                        onDismiss = viewModel::onPowerMenuDismissed,
                    )
                }
            }
        } else if (showPowerMenu) {
            IconButton(
                model = viewModel.powerButtonViewModel,
                modifier = Modifier.sysuiResTag("pm_lite"),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.StandardToolbarLayout(
    animatedContentScope: AnimatedContentScope,
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val showEdit by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_EDIT)
    val showSettings by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_SETTINGS)

    Row(modifier) {
        // User switcher button
        IconButton(
            model = viewModel.userSwitcherViewModel,
            Modifier.sysuiResTag("multi_user_switch"),
            iconColor = Color.Unspecified,
            useIconColorProtection = true,
        )

        // Edit mode button
        val editModeButtonViewModel =
            rememberViewModel("Toolbar") { viewModel.editModeButtonViewModelFactory.create() }
        if (showEdit) {
            EditModeButton(editModeButtonViewModel, isVisible = isFullyVisible())
        }

        // Settings button
        if (showSettings) {
            IconButton(
                model = viewModel.settingsButtonViewModel,
                modifier = Modifier.sysuiResTag("settings_button_container"),
            )
        }

        // Security info button
        SecurityInfo(
            viewModel = viewModel.securityInfoViewModel,
            showCollapsed = true,
            modifier =
                Modifier.sharedElement(
                    rememberSharedContentState(key = SecurityInfoKey),
                    animatedVisibilityScope = animatedContentScope,
                ),
        )

        // Text feedback chip / build number
        ToolbarTextFeedback(
            viewModelFactory = viewModel.textFeedbackContentViewModelFactory,
            modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
        )
    }
}

/** A button with an icon. */
@Composable
private fun IconButton(
    model: FooterActionsButtonViewModel?,
    modifier: Modifier = Modifier,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    useIconColorProtection: Boolean = false,
) {
    if (model == null) {
        return
    }
    Expandable(
        color = Color.Unspecified,
        shape = CircleShape,
        onClick = model.onClick,
        modifier =
            modifier
                .sizeIn(
                    minHeight = IconButtonDimensions.MinimumSize,
                    minWidth = IconButtonDimensions.MinimumSize,
                )
                .semantics { role = Role.Button }
                .aspectRatio(1.0F)
                .borderOnFocus(MaterialTheme.colorScheme.secondary, CornerSize(percent = 50))
                .wrapContentSize(),
        useModifierBasedImplementation = true,
    ) {
        val protectionColor =
            if (useIconColorProtection) {
                LocalAndroidColorScheme.current.surfaceEffect1
            } else {
                Color.Transparent
            }
        Box(
            modifier =
                Modifier.size(IconButtonDimensions.ColoredBackgroundSize)
                    .background(color = protectionColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ToolbarIcon(
                icon = model.icon,
                modifier = Modifier.size(IconButtonDimensions.IconSize),
                tint = iconColor,
            )
        }
    }
}

@Composable
private fun PowerMenuToggleButton(
    viewModel: PowerMenuToggleButtonUiState,
    modifier: Modifier = Modifier,
) {
    val bgColor =
        if (viewModel.isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        }

    val shape =
        RoundedCornerShape(
            topStart = PowerMenuToggleButtonConstants.DefaultCornerRadius,
            topEnd = PowerMenuToggleButtonConstants.DefaultCornerRadius,
            bottomStart = PowerMenuToggleButtonConstants.DefaultCornerRadius,
            bottomEnd =
                if (viewModel.isSelected) {
                    PowerMenuToggleButtonConstants.SelectedCornerRadius
                } else {
                    PowerMenuToggleButtonConstants.DefaultCornerRadius
                },
        )

    val stateDescription = stringResource(viewModel.stateDescriptionRes)

    Row(
        modifier =
            modifier
                .borderOnFocus(MaterialTheme.colorScheme.secondary, CornerSize(percent = 50))
                .clip(shape)
                .focusable()
                .clickable(role = Role.Button, onClick = viewModel.onClick)
                .background(bgColor)
                .padding(
                    start = PowerMenuToggleButtonConstants.PaddingStart,
                    end = PowerMenuToggleButtonConstants.PaddingEnd,
                    top = PowerMenuToggleButtonConstants.PaddingVertical,
                    bottom = PowerMenuToggleButtonConstants.PaddingVertical,
                )
                .semantics {
                    this.stateDescription = stateDescription
                    role = Role.Button
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowerMenuToggleButtonConstants.IconSpacing),
    ) {
        val fgColor =
            if (viewModel.isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }

        Icon(icon = viewModel.icon, tint = fgColor)

        val chevronRotation by
            animateFloatAsState(targetValue = viewModel.chevronRotation, label = "ChevronRotation")

        Icon(
            icon = viewModel.chevron,
            tint = fgColor,
            modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

// TODO(b/394738023): Use com.android.systemui.common.ui.compose.Icon instead.
@Composable
private fun ToolbarIcon(icon: Icon, modifier: Modifier = Modifier, tint: Color) {
    val contentDescription = icon.contentDescription?.load()
    when (icon) {
        is Icon.Loaded ->
            Icon(icon.drawable.toBitmap().asImageBitmap(), contentDescription, modifier, tint)
        is Icon.Resource -> Icon(painterResource(icon.resId), contentDescription, modifier, tint)
    }
}

@Composable
private fun ToolbarTextFeedback(
    viewModelFactory: TextFeedbackContentViewModel.Factory,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        val context = LocalContext.current
        val viewModel =
            rememberViewModel("Toolbar.TextFeedbackViewModel", key = context) {
                viewModelFactory.create(context)
            }
        val hasTextFeedback = viewModel.textFeedback !is TextFeedbackViewModel.NoFeedback

        AnimatedVisibility(
            visible = hasTextFeedback,
            modifier = Modifier.align(Alignment.Center),
            label = "Toolbar.ToolbarTextFeedback",
            enter = fadeIn(tween(durationMillis = 200)),
            exit = fadeOut(tween(durationMillis = 200)),
        ) {
            TextFeedback(viewModel = viewModel.textFeedback)
        }
    }
}

private object Toolbar {
    object TransitionKeys {
        const val SecurityInfoKey = "SecurityInfo"
    }
}

private object PowerMenuToggleButtonConstants {
    val DefaultCornerRadius = 16.dp
    val SelectedCornerRadius = 4.dp
    val PaddingStart = 8.dp
    val PaddingEnd = 4.dp
    val PaddingVertical = 4.dp
    val IconSpacing = 6.dp
}

object IconButtonDimensions {
    val ColoredBackgroundSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.toolbar_button_colored_background_size)

    val IconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(id = R.dimen.toolbar_button_icon_size)

    val MinimumSize: Dp
        @Composable @ReadOnlyComposable get() = dimensionResource(id = R.dimen.toolbar_button_size)
}
