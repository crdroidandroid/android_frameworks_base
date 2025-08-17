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

package com.android.systemui.qs.panels.ui.compose.selection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffset
import androidx.compose.animation.core.animateSize
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.android.compose.modifiers.thenIf
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileShapeMode
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.BADGE_ANGLE_RAD
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.BadgeIconSize
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.BadgeSize
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.BadgeXOffset
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.BadgeYOffset
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.SelectedBorderWidth
import com.android.systemui.qs.panels.ui.compose.selection.SelectionDefaults.decoration
import com.android.systemui.qs.panels.ui.compose.selection.TileState.GreyedOut
import com.android.systemui.qs.panels.ui.compose.selection.TileState.None
import com.android.systemui.qs.panels.ui.compose.selection.TileState.Placeable
import com.android.systemui.qs.panels.ui.compose.selection.TileState.Removable
import com.android.systemui.qs.panels.ui.compose.selection.TileState.Selected
import com.android.systemui.qs.ui.compose.borderOnFocus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a tile decoration and handles click and drag events for them.
 *
 * In states:
 * - [TileState.Removable]: removal icon shown in the top end
 * - [TileState.Selected]: pill shaped handle shown on the end border, as well as a colored border
 *   around the content.
 * - [TileState.None]: nothing
 *
 * @param tileState the state for the tile decoration
 * @param resizingState the [ResizingState] for the tile
 * @param onClick the callback when the tile decoration is clicked
 */
@Composable
fun InteractiveTileContainer(
    tileState: TileState,
    resizingState: ResizingState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val transition: Transition<Decoration> = updateTransition(tileState.decoration())
    val decorationColor by transition.animateColor()
    val decorationAngle by transition.animateAngle()
    val decorationSize by transition.animateSize { it.size }
    val decorationOffset by transition.animateOffset { it.offset }
    val decorationAlpha by transition.animateFloat { it.alpha }
    val badgeIconAlpha by transition.animateFloat { it.iconAlpha }
    val selectionBorderAlpha by transition.animateFloat { it.borderAlpha }
    val isIdle = transition.currentState == transition.targetState
    val isDraggable = tileState == Selected

    Box(
        modifier.resizable(tileState == Selected, resizingState).selectionBorder(
            MaterialTheme.colorScheme.primary,
            SelectedBorderWidth,
        ) {
            selectionBorderAlpha
        }
    ) {
        content()

        MinimumInteractiveSizeComponent(
            angle = { decorationAngle },
            offset = { decorationOffset },
            excludeSystemGesture = isIdle && isDraggable,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .drawWithCache {
                        val radius = min(decorationSize.width, decorationSize.height) / 2f
                        val cornerRadius = CornerRadius(radius)
                        val path = Path()
                        onDrawWithContent {
                            val rect = Rect(center - decorationSize.center, decorationSize)

                            drawRoundRect(
                                color = decorationColor,
                                topLeft = rect.topLeft,
                                size = rect.size,
                                cornerRadius = cornerRadius,
                            )

                            path.reset()
                            path.addRoundRect(RoundRect(rect, cornerRadius))
                            clipPath(path) { this@onDrawWithContent.drawContent() }
                        }
                    }
                    .graphicsLayer { this.alpha = decorationAlpha }
                    .anchoredDraggable(
                        enabled = isDraggable,
                        state = resizingState.anchoredDraggableState,
                        orientation = Orientation.Horizontal,
                    )
                    .clickable(enabled = tileState != None, onClick = onClick)
                    .semantics { contentDescription?.let { this.contentDescription = it } }
            ) {
                val size = with(LocalDensity.current) { BadgeIconSize.toDp() }
                Icon(
                    Icons.Default.Remove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier =
                        Modifier.size(size).align(Alignment.Center).graphicsLayer {
                            this.alpha = badgeIconAlpha
                        },
                )
            }
        }
    }
}

@Composable
private fun Modifier.selectionBorder(
    selectionColor: Color,
    selectionBorderWidth: Dp,
    selectionAlpha: () -> Float = { 0f },
): Modifier {
    val shapeMode = rememberTileShapeMode()
    val borderRadiusPx = with(LocalDensity.current) {
        when (shapeMode) {
            1 -> InactiveCornerRadius.toPx()
            2 -> ActiveTileCornerRadius.toPx()
            3 -> 0f
            else -> InactiveCornerRadius.toPx()
        }
    }

    return drawWithContent {
        drawContent()

        // Draw the border on the inside of the tile
        val borderWidth = selectionBorderWidth.toPx()
        drawRoundRect(
            SolidColor(selectionColor),
            cornerRadius = CornerRadius(borderRadiusPx),
            topLeft = Offset(borderWidth / 2, borderWidth / 2),
            size = Size(size.width - borderWidth, size.height - borderWidth),
            style = Stroke(borderWidth),
            alpha = selectionAlpha(),
        )
    }
}

/**
 * Draws a clickable badge in the top end corner of the parent composable.
 *
 * The badge will fade in and fade out based on whether or not it's enabled.
 *
 * @param icon the [ImageVector] to display in the badge
 * @param contentDescription the content description for the icon
 * @param enabled Whether the badge should be visible and clickable
 * @param onClick the callback when the badge is clicked
 */
@Composable
fun StaticTileBadge(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = with(LocalDensity.current) { Offset(BadgeXOffset.toPx(), BadgeYOffset.toPx()) }
    val alpha by animateFloatAsState(if (enabled) 1f else 0f)
    MinimumInteractiveSizeComponent(
        angle = { BADGE_ANGLE_RAD },
        offset = { offset },
        modifier = modifier,
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .thenIf(enabled) { Modifier.clickable(onClick = onClick) }
        ) {
            val size = with(LocalDensity.current) { BadgeIconSize.toDp() }
            val primaryColor = MaterialTheme.colorScheme.primary
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier.size(size).align(Alignment.Center).drawBehind {
                        drawCircle(primaryColor, radius = BadgeSize.toPx() / 2)
                    },
            )
        }
    }
}

@Composable
private fun MinimumInteractiveSizeComponent(
    angle: () -> Float,
    offset: () -> Offset,
    modifier: Modifier = Modifier,
    excludeSystemGesture: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // Use a higher zIndex than the tile to draw over it, and manually create the touch target
    // as we're drawing over neighbor tiles as well.
    val minTouchTargetSize = LocalMinimumInteractiveComponentSize.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .zIndex(2f)
                .layout { measurable, constraints ->
                    val size = minTouchTargetSize.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(size, size))
                    layout(placeable.width, placeable.height) {
                        val radius = constraints.maxHeight / 2f
                        val rotationCenter = Offset(constraints.maxWidth - radius, radius)
                        val position = offsetForAngle(angle(), radius, rotationCenter) + offset()
                        placeable.placeRelative(
                            position.x.roundToInt() - placeable.width / 2,
                            position.y.roundToInt() - placeable.height / 2,
                        )
                    }
                }
                .thenIf(excludeSystemGesture) {
                    Modifier.systemGestureExclusion { Rect(Offset.Zero, it.size.toSize()) }
                }
                .borderOnFocus(MaterialTheme.colorScheme.secondary, CornerSize(50)),
        content = content,
    )
}

@Composable
private fun Modifier.resizable(selected: Boolean, state: ResizingState): Modifier {
    if (!selected) return zIndex(1f)

    return zIndex(2f).layout { measurable, constraints ->
        val isIdle by derivedStateOf { state.progress().let { it == 0f || it == 1f } }
        // Grab the width from the resizing state if a resize is in progress
        val width =
            state.anchoredDraggableState.requireOffset().roundToInt().takeIf { !isIdle }
                ?: constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

enum class TileState {
    /** Tile is displayed as-is, no additional decoration needed. */
    None,
    /** Tile can be removed by the user. This is displayed by a badge in the upper end corner. */
    Removable,
    /**
     * Tile is selected and resizable. One tile can be selected at a time in the grid. This is when
     * we display the resizing handle and a highlighted border around the tile.
     */
    Selected,
    /**
     * Tile placeable. This state means that the grid is in placement mode and this tile is
     * selected. It should be highlighted to stand out in the grid.
     */
    Placeable,
    /**
     * Tile is faded out. This state means that the grid is in placement mode and this tile isn't
     * selected. It serves as a target to place the selected tile.
     */
    GreyedOut,
}

/**
 * Animate the angle of the tile decoration based on the previous state
 *
 * Some [TileState] don't have a visible decoration, and the angle should only animate when going
 * between visible states.
 */
@Composable
private fun Transition<Decoration>.animateAngle(): State<Float> {
    val animatable = remember { Animatable(0f) }
    val target = targetState
    LaunchedEffect(target) {
        if (target is VisibleDecoration) {
            // Snap to the target angle when the current decoration isn't visible
            when (currentState) {
                is NoDecoration -> animatable.snapTo(target.angle)
                is VisibleDecoration -> animatable.animateTo(target.angle)
            }
        }
    }
    return animatable.asState()
}

/**
 * Animate the color of the tile decoration based on the previous state
 *
 * Some [TileState] don't have a visible decoration, and the color should only animate when going
 * between visible states.
 */
@Composable
private fun Transition<Decoration>.animateColor(): State<Color> {
    val animatable = remember { androidx.compose.animation.Animatable(Color.Transparent) }
    val target = targetState
    LaunchedEffect(target) {
        if (target is VisibleDecoration) {
            // Snap to the target color when the current decoration isn't visible
            when (currentState) {
                is NoDecoration -> animatable.snapTo(target.color)
                is VisibleDecoration -> animatable.animateTo(target.color)
            }
        }
    }
    return animatable.asState()
}

private fun Size(size: Float) = Size(size, size)

private fun offsetForAngle(angle: Float, radius: Float, center: Offset): Offset {
    return Offset(x = radius * cos(angle) + center.x, y = radius * sin(angle) + center.y)
}

@Stable
sealed interface Decoration {
    val alpha: Float
    val iconAlpha: Float
    val borderAlpha: Float
    val size: Size
    val offset: Offset
}

private data class VisibleDecoration(
    override val alpha: Float = 1f,
    override val iconAlpha: Float,
    override val borderAlpha: Float,
    override val size: Size,
    override val offset: Offset,
    val color: Color,
    val angle: Float,
) : Decoration

private data object NoDecoration : Decoration {
    override val alpha: Float = 0f
    override val iconAlpha: Float = 0f
    override val borderAlpha: Float = 0f
    override val size: Size = Size.Zero
    override val offset: Offset = Offset.Zero
}

private object SelectionDefaults {
    val SelectedBorderWidth = 2.dp
    val BadgeSize = 24.dp
    val BadgeIconSize = 16.sp
    val BadgeXOffset = -4.dp
    val BadgeYOffset = 4.dp
    val ResizingPillWidth = 8.dp
    val ResizingPillHeight = 16.dp
    const val BADGE_ANGLE_RAD = -.8f
    const val RESIZING_PILL_ANGLE_RAD = 0f

    @Composable
    @ReadOnlyComposable
    fun TileState.decoration(): Decoration {
        return when (this) {
            Removable -> removalBadge()
            Selected -> resizingHandle()
            None,
            Placeable,
            GreyedOut -> NoDecoration
        }
    }

    @Composable
    @ReadOnlyComposable
    fun removalBadge(): VisibleDecoration {
        return with(LocalDensity.current) {
            VisibleDecoration(
                iconAlpha = 1f,
                borderAlpha = 0f,
                color = MaterialTheme.colorScheme.primaryContainer,
                size = Size(BadgeSize.toPx()),
                angle = BADGE_ANGLE_RAD,
                offset = Offset(BadgeXOffset.toPx(), BadgeYOffset.toPx()),
            )
        }
    }

    @Composable
    @ReadOnlyComposable
    fun resizingHandle(): VisibleDecoration {
        return with(LocalDensity.current) {
            VisibleDecoration(
                iconAlpha = 0f,
                borderAlpha = 1f,
                color = MaterialTheme.colorScheme.primary,
                size = Size(ResizingPillWidth.toPx(), ResizingPillHeight.toPx()),
                angle = RESIZING_PILL_ANGLE_RAD,
                offset = Offset(-SelectedBorderWidth.toPx(), 0f),
            )
        }
    }
}
