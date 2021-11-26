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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.compose.animation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroupOverlay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.compose.modifiers.animatedBackground
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.FullScreenComposeViewInOverlay
import com.android.systemui.Flags.expandableForceModifierImplementation
import com.android.systemui.Flags.expandableUseModifierImplementation
import com.android.systemui.animation.ComposableControllerFactory
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.animation.TransitionSource
import kotlin.math.max
import kotlin.math.min

/**
 * Create an expandable shape that can launch into an Activity or a Dialog.
 *
 * If this expandable should be expanded when it is clicked directly, then you should specify a
 * [onClick] handler, which will ensure that this expandable interactive size and background size
 * are consistent with the M3 components (48dp and 40dp respectively).
 *
 * If this expandable should be expanded when a children component is clicked, like a button inside
 * the expandable, then you can use the Expandable parameter passed to the [content] lambda.
 *
 * Example:
 * ```
 *    Expandable(
 *      color = MaterialTheme.colorScheme.primary,
 *      shape = RoundedCornerShape(16.dp),
 *
 *      // For activities:
 *      onClick = { expandable ->
 *          activityStarter.startActivity(intent, expandable.activityTransitionController())
 *      },
 *
 *      // For dialogs:
 *      onClick = { expandable ->
 *          dialogTransitionAnimator.show(dialog, controller.dialogTransitionController())
 *      },
 *    ) {
 *      ...
 *    }
 * ```
 *
 * [transitionControllerFactory] must be defined when this [Expandable] is registered for a
 * long-term launch or return animation, to ensure that animation controllers can be created
 * correctly.
 *
 * @sample com.android.systemui.compose.gallery.ActivityLaunchScreen
 * @sample com.android.systemui.compose.gallery.DialogLaunchScreen
 * @param onClickLabel semantic / accessibility label for the onClick action. See
 *   [Modifier.clickable].
 * @param defaultMinSize true if a default minimum size should be enforced even if this Expandable
 *   isn't currently clickable and false otherwise.
 */
@Composable
fun Expandable(
    color: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    contentColor: Color = contentColorFor(color),
    borderStroke: BorderStroke? = null,
    onClick: ((Expandable) -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: ((Expandable) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    useModifierBasedImplementation: Boolean = expandableUseModifierImplementation(),
    defaultMinSize: Boolean = true,
    transitionControllerFactory: ComposableControllerFactory? = null,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        controller =
            rememberExpandableController(
                color,
                shape,
                contentColor,
                borderStroke,
                transitionControllerFactory,
            ),
        modifier = modifier,
        onClick = onClick,
        onClickLabel = onClickLabel,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
        useModifierBasedImplementation = useModifierBasedImplementation,
        defaultMinSize = defaultMinSize,
        content = content,
    )
}

/**
 * Create an expandable shape that can launch into an Activity or a Dialog.
 *
 * This overload can be used in cases where you need to create the [ExpandableController] before
 * composing this [Expandable], for instance if something outside of this Expandable can trigger a
 * launch animation
 *
 * Example:
 * ```
 *    // The controller that you can use to trigger the animations from anywhere.
 *    val controller =
 *        rememberExpandableController(
 *          color = MaterialTheme.colorScheme.primary,
 *          shape = RoundedCornerShape(16.dp),
 *        )
 *
 *    Expandable(controller) {
 *       ...
 *    }
 * ```
 *
 * @sample com.android.systemui.compose.gallery.ActivityLaunchScreen
 * @sample com.android.systemui.compose.gallery.DialogLaunchScreen
 * @param onClickLabel semantic / accessibility label for the onClick action. See
 *   [Modifier.clickable].
 * @param defaultMinSize true if a default minimum size should be enforced even if this Expandable
 *   isn't currently clickable and false otherwise.
 */
@Composable
fun Expandable(
    expandable: Expandable? = null,
    controller: ExpandableController,
    modifier: Modifier = Modifier,
    onClick: ((Expandable) -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: ((Expandable) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    // TODO(b/285250939): Default this to true then remove once the Compose QS expandables have
    // proven that the new implementation is robust.
    useModifierBasedImplementation: Boolean = false,
    defaultMinSize: Boolean = true,
    content: @Composable (Expandable) -> Unit,
) {

    val expandable = expandable ?: remember { Expandable(mutableSetOf()) }

    // If dynamicTargetResolutionEnabled is false, manually register the source.
    // Otherwise, the source is registered via the 'registerSource' Modifier on the Expandable.
    if (!dynamicTargetResolutionEnabled()) {
        expandable.addSource(controller.transitionSource)
    }

    controller.transitionControllerFactory?.let { factory ->
        DisposableEffect(factory) {
            // Notify the transition controller factory that the expandable is now available, so it
            // can move forward with any pending requests.
            factory.onCompose(expandable)
            // Once this composable is gone, the transition controller factory must be notified so
            // it doesn't accepts requests providing stale content.
            onDispose { factory.onDispose() }
        }
    }

    if (useModifierBasedImplementation || expandableForceModifierImplementation()) {
        Box(modifier.expandable(expandable, controller, onClick, onClickLabel, onLongClick, interactionSource)) {
            WrappedContent(
                expandable,
                controller.contentColor,
                defaultMinSize = defaultMinSize,
                content,
            )
        }
        return
    }

    val color = controller.color
    val contentColor = controller.contentColor
    val shape = controller.shape

    val wrappedContent =
        remember(content) {
            movableContentOf { expandable: Expandable ->
                WrappedContent(expandable, contentColor, defaultMinSize = defaultMinSize, content)
            }
        }

    var thisExpandableSize by remember { mutableStateOf(Size.Zero) }

    /** Set the current element size as this Expandable size. */
    fun Modifier.updateExpandableSize(): Modifier {
        return this.onGloballyPositioned { coords ->
            thisExpandableSize =
                coords
                    .findRootCoordinates()
                    // Make sure that we report the actual size, and not the visual/clipped one.
                    .localBoundingBoxOf(coords, clipBounds = false)
                    .size
        }
    }

    // Make sure we don't read animatorState directly here to avoid recomposition every time the
    // state changes (i.e. every frame of the animation).
    val isAnimating = controller.isAnimating

    // If this expandable is expanded when it's being directly clicked on, let's ensure that it has
    // the minimum interactive size followed by all M3 components (48.dp).
    val minInteractiveSizeModifier =
        if (onClick != null || onLongClick != null) {
            Modifier.minimumInteractiveComponentSize()
        } else {
            Modifier
        }

    when {
        isAnimating -> {
            // Don't compose the movable content during the animation, as it should be composed only
            // once at all times. We make this spacer exactly the same size as this Expandable when
            // it is visible.
            Spacer(
                modifier
                    .registerSource(controller.transitionSource, expandable)
                    .requiredSize(with(controller.density) { thisExpandableSize.toDpSize() })
            )

            // The content and its animated background in the overlay. We draw it only when we are
            // animating.
            AnimatedContentInOverlay(
                expandable,
                color,
                controller.boundsInComposeViewRoot.size,
                controller.overlay
                    ?: error("AnimatedContentInOverlay shouldn't be composed with null overlay."),
                controller,
                wrappedContent,
                controller.composeViewRoot,
                { controller.currentComposeViewInOverlay = it },
                controller.density,
            )
        }
        controller.isDialogShowing -> {
            Box(
                modifier
                    .updateExpandableSize()
                    .registerSource(controller.transitionSource, expandable)
                    .then(minInteractiveSizeModifier)
                    .drawWithContent { /* Don't draw anything when the dialog is shown. */ }
                    .onGloballyPositioned { controller.boundsInComposeViewRoot = it.boundsInRoot() }
            ) {
                wrappedContent(expandable)
            }
        }
        else -> {
            Box(
                modifier
                    .updateExpandableSize()
                    .registerSource(controller.transitionSource, expandable)
                    .then(minInteractiveSizeModifier)
                    .then(
                        clickModifier(
                            expandable,
                            controller,
                            onClick,
                            onClickLabel,
                            onLongClick,
                            interactionSource,
                        )
                    )
                    .animatedBackground(color, shape = shape)
                    .border(controller)
                    .onGloballyPositioned {
                        if (it.isAttached) {
                            controller.boundsInComposeViewRoot = it.boundsInRoot()
                        }
                    }
            ) {
                wrappedContent(expandable)
            }
        }
    }
}

@Composable
private fun WrappedContent(
    expandable: Expandable,
    contentColor: Color,
    defaultMinSize: Boolean,
    content: @Composable (Expandable) -> Unit,
) {
    val minSizeContent =
        @Composable {
            if (defaultMinSize) {
                // We make sure that the content itself (wrapped by the background) is at
                // least 40.dp, which is the same as the M3 buttons. This applies even if
                // onClick is null, to make it easier to write expandables that are
                // sometimes clickable and sometimes not.
                val minSize = 40.dp
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = minSize, minHeight = minSize),
                    contentAlignment = Alignment.Center,
                ) {
                    content(expandable)
                }
            } else {
                content(expandable)
            }
        }

    if (contentColor.isSpecified) {
        CompositionLocalProvider(LocalContentColor provides contentColor, content = minSizeContent)
    } else {
        minSizeContent()
    }
}

@Composable
@Stable
private fun Modifier.expandable(
    expandable: Expandable,
    controller: ExpandableController,
    onClick: ((Expandable) -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: ((Expandable) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val graphicsLayer = rememberGraphicsLayer()

    val isAnimating = controller.isAnimating
    val showDialog = controller.isDialogShowing
    if (isAnimating || (dynamicTargetResolutionEnabled() && showDialog)) {
        FullScreenComposeViewInOverlay(controller.overlay) { view ->
            Modifier.then(DrawExpandableInOverlayElement(view, controller, graphicsLayer))
        }
    }

    // TODO(b/412899675): create a Modifier extension for lifecycle based registration of
    // TransitionSource
    val drawContent = !isAnimating && !controller.isDialogShowing
    return this.thenIf(onClick != null) { Modifier.minimumInteractiveComponentSize() }
        .thenIf(drawContent) {
            Modifier.border(controller)
                .then(
                    clickModifier(expandable, controller, onClick, onClickLabel, onLongClick, interactionSource)
                )
                .animatedBackground(controller.color, shape = controller.shape)
        }
        .registerSource(controller.transitionSource, expandable)
        .onPlaced { coords ->
            // TODO(b/415570057): Remove this check.
            if (coords.isAttached) {
                controller.boundsInComposeViewRoot = coords.boundsInRoot()
            }
        }
        .drawWithContent {
            graphicsLayer.record { this@drawWithContent.drawContent() }

            if (drawContent) {
                drawLayer(graphicsLayer)
            }
        }
}

/**
 * Registers a [TransitionSource] with its [Expandable] coordinator in a lifecycle-aware manner for
 * Compose.
 *
 * This modifier is the way to link a Composable-based [TransitionSource] to its [Expandable]
 * instance. It intelligently handles registration based on the [dynamicTargetResolution] feature
 * flag
 *
 * @param transitionSource The [TransitionSource] instance associated with this Composable.
 * @param expandable The long-lived [Expandable] coordinator this source belongs to.
 * @return A [Modifier] that handles the lifecycle-aware registration.
 */
@Composable
fun Modifier.registerSource(transitionSource: TransitionSource, expandable: Expandable): Modifier {
    return if (dynamicTargetResolutionEnabled()) {
        this.then(RegisterTransitionSource(transitionSource, expandable))
    } else {
        this
    }
}

private data class RegisterTransitionSource(
    val transitionSource: TransitionSource,
    val expandable: Expandable,
) : ModifierNodeElement<TransitionSourceNode>() {
    override fun create(): TransitionSourceNode {
        return TransitionSourceNode(transitionSource, expandable)
    }

    override fun update(node: TransitionSourceNode) {
        node.transitionSource = transitionSource
        node.expandable = expandable
    }
}

/**
 * The core [Modifier.Node] responsible for the lifecycle-aware registration of a
 * [TransitionSource].
 *
 * This node observes the Composable's layout state via [LayoutAwareModifierNode] and
 * [UnplacedAwareModifierNode]:
 * - [onPlaced]: Called when the Composable is placed in the layout. It registers the
 *   [transitionSource] with the [expandable] coordinator.
 * - [onUnplaced]: Called when the Composable is removed from the layout. It unregisters the
 *   [transitionSource], critically preventing memory leaks.
 *
 * @param transitionSource The [TransitionSource] instance to register.
 * @param expandable The [Expandable] coordinator to register with.
 */
private class TransitionSourceNode(
    var transitionSource: TransitionSource,
    var expandable: Expandable,
) : Modifier.Node(), LayoutAwareModifierNode, UnplacedAwareModifierNode {

    override fun onPlaced(coordinates: LayoutCoordinates) {
        expandable.addSource(transitionSource)
    }

    override fun onUnplaced() {
        expandable.removeSource(transitionSource)
    }
}

private data class DrawExpandableInOverlayElement(
    private val overlayComposeView: ComposeView,
    private val controller: ExpandableController,
    private val contentGraphicsLayer: GraphicsLayer,
) : ModifierNodeElement<DrawExpandableInOverlayNode>() {
    override fun create(): DrawExpandableInOverlayNode {
        return DrawExpandableInOverlayNode(overlayComposeView, controller, contentGraphicsLayer)
    }

    override fun update(node: DrawExpandableInOverlayNode) {
        node.update(overlayComposeView, controller, contentGraphicsLayer)
    }
}

private class DrawExpandableInOverlayNode(
    composeView: ComposeView,
    controller: ExpandableController,
    private var contentGraphicsLayer: GraphicsLayer,
) : Modifier.Node(), DrawModifierNode {
    private var controller = controller
        set(value) {
            resetCurrentNodeInOverlay()
            field = value
            setCurrentNodeInOverlay()
        }

    private var composeViewLocationOnScreen = composeView.locationOnScreen

    fun update(
        composeView: ComposeView,
        controller: ExpandableController,
        contentGraphicsLayer: GraphicsLayer,
    ) {
        this.controller = controller
        this.composeViewLocationOnScreen = composeView.locationOnScreen
        this.contentGraphicsLayer = contentGraphicsLayer
    }

    override fun onAttach() {
        setCurrentNodeInOverlay()
    }

    override fun onDetach() {
        resetCurrentNodeInOverlay()
    }

    private fun setCurrentNodeInOverlay() {
        controller.currentNodeInOverlay = this
    }

    private fun resetCurrentNodeInOverlay() {
        if (controller.currentNodeInOverlay == this) {
            controller.currentNodeInOverlay = null
        }
    }

    override fun ContentDrawScope.draw() {
        val state = controller.animatorState?.takeIf { it.visible } ?: return
        val topOffset = state.top.toFloat() - composeViewLocationOnScreen[1]
        val leftOffset = state.left.toFloat() - composeViewLocationOnScreen[0]

        translate(top = topOffset, left = leftOffset) {
            // Background.
            this@draw.drawBackground(
                state,
                controller.color(),
                controller.borderStroke,
                size = Size(state.width.toFloat(), state.height.toFloat()),
            )

            // Content, scaled & centered w.r.t. the animated state bounds.
            val contentSize = controller.boundsInComposeViewRoot.size
            val contentWidth = contentSize.width
            val contentHeight = contentSize.height
            val scale = min(state.width / contentWidth, state.height / contentHeight)
            scale(scale, pivot = Offset(state.width / 2f, state.height / 2f)) {
                translate(
                    left = (state.width - contentWidth) / 2f,
                    top = (state.height - contentHeight) / 2f,
                ) {
                    drawLayer(contentGraphicsLayer)
                }
            }
        }
    }
}

private fun clickModifier(
    expandable: Expandable,
    controller: ExpandableController,
    onClick: ((Expandable) -> Unit)?,
    onClickLabel: String? = null,
    onLongClick: ((Expandable) -> Unit)?,
    interactionSource: MutableInteractionSource?,
): Modifier {
    if (onClick == null && onLongClick == null) {
        return Modifier
    }

    // If the caller provided an interaction source, then that means that they will draw the
    // click indication themselves.
    if (interactionSource != null && onLongClick != null) {
        return Modifier.combinedClickable(
            interactionSource,
            indication = null,
            onClickLabel = onClickLabel,
            onLongClick = { onLongClick?.invoke(expandable) },
            onClick = { onClick?.invoke(expandable) }
        )
    } else if (interactionSource != null) {
        return Modifier.clickable(
            interactionSource,
            indication = null,
            onClickLabel = onClickLabel,
        ) {
            onClick?.invoke(expandable)
        }
    }

    // If no interaction source is provided, we draw the default indication (a ripple) and make sure
    // it's clipped by the expandable shape.
    if (onLongClick != null) {
        return Modifier.clip(controller.shape).combinedClickable(
            onClickLabel = onClickLabel,
            onLongClick = { onLongClick?.invoke(expandable) },
            onClick = { onClick?.invoke(expandable) }
        )
    } else {
        return Modifier.clip(controller.shape).clickable(onClickLabel = onClickLabel) {
            onClick?.invoke(expandable)
        }
    }
}

/** Draw [content] in [overlay] while respecting its screen position given by [animatorState]. */
@Composable
private fun AnimatedContentInOverlay(
    expandable: Expandable,
    color: () -> Color,
    sizeInOriginalLayout: Size,
    overlay: ViewGroupOverlay,
    controller: ExpandableController,
    content: @Composable (Expandable) -> Unit,
    composeViewRoot: View,
    onOverlayComposeViewChanged: (View?) -> Unit,
    density: Density,
) {
    val compositionContext = rememberCompositionContext()
    val context = LocalContext.current

    // Create the ComposeView and force its content composition so that the movableContent is
    // composed exactly once when we start animating.
    val composeViewInOverlay =
        remember(context, density) {
            val startWidth = sizeInOriginalLayout.width
            val startHeight = sizeInOriginalLayout.height
            val contentModifier =
                Modifier
                    // Draw the content with the same size as it was at the start of the animation
                    // so that its content is laid out exactly the same way.
                    .requiredSize(with(density) { sizeInOriginalLayout.toDpSize() })
                    .drawWithContent {
                        val animatorState = controller.animatorState ?: return@drawWithContent

                        // Scale the content with the background while keeping its aspect ratio.
                        val widthRatio =
                            if (startWidth != 0f) {
                                animatorState.width.toFloat() / startWidth
                            } else {
                                1f
                            }
                        val heightRatio =
                            if (startHeight != 0f) {
                                animatorState.height.toFloat() / startHeight
                            } else {
                                1f
                            }
                        val scale = min(widthRatio, heightRatio)
                        scale(scale) { this@drawWithContent.drawContent() }
                    }

            val composeView =
                ComposeView(context).apply {
                    setContent {
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                val animatorState =
                                    controller.animatorState ?: return@drawWithContent
                                if (!animatorState.visible) {
                                    return@drawWithContent
                                }

                                drawBackground(animatorState, color(), controller.borderStroke)
                                drawContent()
                            },
                            // We center the content in the expanding container.
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(contentModifier) { content(expandable) }
                        }
                    }
                }

            // Set the owners.
            val overlayViewGroup = getOverlayViewGroup(context, overlay)

            overlayViewGroup.setViewTreeLifecycleOwner(composeViewRoot.findViewTreeLifecycleOwner())
            overlayViewGroup.setViewTreeViewModelStoreOwner(
                composeViewRoot.findViewTreeViewModelStoreOwner()
            )
            overlayViewGroup.setViewTreeSavedStateRegistryOwner(
                composeViewRoot.findViewTreeSavedStateRegistryOwner()
            )

            composeView.setParentCompositionContext(compositionContext)

            composeView
        }

    DisposableEffect(overlay, composeViewInOverlay) {
        // Add the ComposeView to the overlay.
        overlay.add(composeViewInOverlay)

        val startState =
            controller.animatorState
                ?: throw IllegalStateException(
                    "AnimatedContentInOverlay shouldn't be composed with null animatorState."
                )
        measureAndLayoutComposeViewInOverlay(composeViewInOverlay, startState)
        onOverlayComposeViewChanged(composeViewInOverlay)

        onDispose {
            composeViewInOverlay.disposeComposition()
            overlay.remove(composeViewInOverlay)
            onOverlayComposeViewChanged(null)
        }
    }
}

internal fun measureAndLayoutComposeViewInOverlay(view: View, state: TransitionAnimator.State) {
    val exactWidth = state.width
    val exactHeight = state.height
    view.measure(
        View.MeasureSpec.makeSafeMeasureSpec(exactWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeSafeMeasureSpec(exactHeight, View.MeasureSpec.EXACTLY),
    )

    val parent = view.parent as ViewGroup
    val parentLocation = parent.locationOnScreen
    val offsetX = parentLocation[0]
    val offsetY = parentLocation[1]
    view.layout(
        state.left - offsetX,
        state.top - offsetY,
        state.right - offsetX,
        state.bottom - offsetY,
    )
}

// TODO(b/230830644): Add hidden API to ViewGroupOverlay to access this ViewGroup directly?
private fun getOverlayViewGroup(context: Context, overlay: ViewGroupOverlay): ViewGroup {
    val view = View(context)
    overlay.add(view)
    var current = view.parent
    while (current.parent != null) {
        current = current.parent
    }
    overlay.remove(view)
    return current as ViewGroup
}

private fun Modifier.border(controller: ExpandableController): Modifier =
    controller.borderStroke?.let { stroke -> this.border(stroke, controller.shape) } ?: this

private fun ContentDrawScope.drawBackground(
    animatorState: TransitionAnimator.State,
    color: Color,
    border: BorderStroke?,
    size: Size = this.size,
) {
    val topRadius = animatorState.topCornerRadius
    val bottomRadius = animatorState.bottomCornerRadius
    if (topRadius == bottomRadius) {
        // Shortcut to avoid Outline calculation and allocation.
        val cornerRadius = CornerRadius(topRadius)

        // Draw the background.
        drawRoundRect(color, cornerRadius = cornerRadius, size = size)

        // Draw the border.
        if (border != null) {
            // Copied from androidx.compose.foundation.Border.kt
            val strokeWidth = border.width.toPx()
            val halfStroke = strokeWidth / 2
            val borderStroke = Stroke(strokeWidth)

            drawRoundRect(
                brush = border.brush,
                topLeft = Offset(halfStroke, halfStroke),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = cornerRadius.shrink(halfStroke),
                style = borderStroke,
            )
        }
    } else {
        val shape =
            RoundedCornerShape(
                topStart = topRadius,
                topEnd = topRadius,
                bottomStart = bottomRadius,
                bottomEnd = bottomRadius,
            )
        val outline = shape.createOutline(size, layoutDirection, this)

        // Draw the background.
        drawOutline(outline, color = color)

        // Draw the border.
        if (border != null) {
            // Copied from androidx.compose.foundation.Border.kt.
            val strokeWidth = border.width.toPx()
            val path = createRoundRectPath((outline as Outline.Rounded).roundRect, strokeWidth)

            drawPath(path, border.brush)
        }
    }
}

/**
 * Helper method that creates a round rect with the inner region removed by the given stroke width.
 *
 * Copied from androidx.compose.foundation.Border.kt.
 */
private fun createRoundRectPath(roundedRect: RoundRect, strokeWidth: Float): Path {
    return Path().apply {
        addRoundRect(roundedRect)
        val insetPath =
            Path().apply { addRoundRect(createInsetRoundedRect(strokeWidth, roundedRect)) }
        op(this, insetPath, PathOperation.Difference)
    }
}

/* Copied from androidx.compose.foundation.Border.kt. */
private fun createInsetRoundedRect(widthPx: Float, roundedRect: RoundRect) =
    RoundRect(
        left = widthPx,
        top = widthPx,
        right = roundedRect.width - widthPx,
        bottom = roundedRect.height - widthPx,
        topLeftCornerRadius = roundedRect.topLeftCornerRadius.shrink(widthPx),
        topRightCornerRadius = roundedRect.topRightCornerRadius.shrink(widthPx),
        bottomLeftCornerRadius = roundedRect.bottomLeftCornerRadius.shrink(widthPx),
        bottomRightCornerRadius = roundedRect.bottomRightCornerRadius.shrink(widthPx),
    )

/**
 * Helper method to shrink the corner radius by the given value, clamping to 0 if the resultant
 * corner radius would be negative.
 *
 * Copied from androidx.compose.foundation.Border.kt.
 */
private fun CornerRadius.shrink(value: Float): CornerRadius =
    CornerRadius(max(0f, this.x - value), max(0f, this.y - value))
