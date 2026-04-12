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

package com.android.systemui.notifications.ui.composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView

/**
 * Modify element, which updates the height to be the same as the Notification stack height returned
 * by the legacy Notification stack scroll view in [NotificationScrollView.intrinsicStackHeight].
 *
 * @param view Notification stack scroll view
 * @param totalVerticalPadding extra padding to be added to the received stack content height.
 * @param constrainToMaxHeight if true, the height will not be taller than the available space.
 */
private const val STACK_HEIGHT_THRESHOLD = 8

fun Modifier.notificationStackHeight(
    view: NotificationScrollView,
    totalVerticalPadding: Dp = 0.dp,
    constrainToMaxHeight: Boolean = false,
) = this then StackLayoutElement(view, totalVerticalPadding, constrainToMaxHeight)

private data class StackLayoutElement(
    val view: NotificationScrollView,
    val padding: Dp,
    val constrainToMaxHeight: Boolean,
) : ModifierNodeElement<StackLayoutNode>() {

    override fun create(): StackLayoutNode = StackLayoutNode(view, padding, constrainToMaxHeight)

    override fun update(node: StackLayoutNode) {
        check(view == node.view) { "Trying to reuse the node with a new View." }
        if (node.padding != padding || node.constrainToMaxHeight != constrainToMaxHeight) {
            node.padding = padding
            node.constrainToMaxHeight = constrainToMaxHeight
            node.invalidateMeasureIfAttached()
        }
    }
}

private class StackLayoutNode(
    val view: NotificationScrollView,
    var padding: Dp,
    var constrainToMaxHeight: Boolean,
) : LayoutModifierNode, Modifier.Node() {

    private var lastMeasuredStackHeight = -1
    private val stackHeightChangedListener = Runnable {
        val currentHeight = view.intrinsicStackHeight
        if (Math.abs(currentHeight - lastMeasuredStackHeight) > STACK_HEIGHT_THRESHOLD) {
            lastMeasuredStackHeight = currentHeight
            invalidateMeasureIfAttached()
        }
    }

    override fun onAttach() {
        super.onAttach()
        view.addStackHeightChangedListener(stackHeightChangedListener)
    }

    override fun onDetach() {
        super.onDetach()
        view.removeStackHeightChangedListener(stackHeightChangedListener)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        lastMeasuredStackHeight = view.intrinsicStackHeight
        var contentHeight = padding.roundToPx() + lastMeasuredStackHeight
        if (constrainToMaxHeight) {
            contentHeight = contentHeight.coerceAtMost(constraints.maxHeight)
        }
        val placeable =
            measurable.measure(
                constraints.copy(minHeight = contentHeight, maxHeight = contentHeight)
            )

        return layout(placeable.width, placeable.height) { placeable.place(IntOffset.Zero) }
    }

    override fun toString(): String {
        return "StackLayoutNode(view=$view padding:$padding constrainToMaxHeight:$constrainToMaxHeight)"
    }

    fun invalidateMeasureIfAttached() {
        if (isAttached) {
            this.invalidateMeasurement()
        }
    }
}
