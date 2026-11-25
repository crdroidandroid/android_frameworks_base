/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.DisplayCutout
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

private const val CENTER_MIN_FRACTION = 0.35f
private const val CENTER_MAX_FRACTION = 0.65f
private const val MAX_WIDTH_FRACTION = 0.4f

// The camera hole at the top centre of the window, or null when the top cutout is off-centre,
// too wide for an island, or absent (including after rotating it to a side edge).
internal fun View.centerTopCutoutHole(): Rect? {
    val cutout = rootWindowInsets?.displayCutout ?: return null
    val top = cutout.boundingRectTop.takeUnless { it.isEmpty } ?: return null
    val width = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    if (width <= 0) return null
    if (top.exactCenterX() / width !in CENTER_MIN_FRACTION..CENTER_MAX_FRACTION) return null
    val hole = cutout.holeWithin(top)
    return hole.takeIf { it.width() <= width * MAX_WIDTH_FRACTION }
}

// Clipped to the edge's bounding rect, so a second hole elsewhere does not widen it.
private fun DisplayCutout.holeWithin(bounds: Rect): Rect {
    val path = cutoutPath ?: return Rect(bounds)
    val clipped = Path(path)
    val clip = Path().apply { addRect(RectF(bounds), Path.Direction.CW) }
    if (!clipped.op(clip, Path.Op.INTERSECT) || clipped.isEmpty) return Rect(bounds)
    val rect = RectF()
    clipped.computeBounds(rect, true)
    if (rect.isEmpty) return Rect(bounds)
    return Rect().also { rect.roundOut(it) }
}

@Composable
internal fun rememberCenterCutoutHole(): Rect? {
    val view = LocalView.current
    var hole by remember(view) { mutableStateOf(view.centerTopCutoutHole()) }
    DisposableEffect(view) {
        val root = view.rootView
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l == oldR - oldL && b - t == oldB - oldT) return@OnLayoutChangeListener
            val updated = view.centerTopCutoutHole()
            if (updated != hole) hole = updated
        }
        root.addOnLayoutChangeListener(listener)
        hole = view.centerTopCutoutHole()
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }
    return hole
}
