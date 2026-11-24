/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import kotlin.math.PI

// Springs are described by response (seconds) and damping ratio.
internal object IslandMorphMotion {
    private const val SCALE_THRESHOLD = 0.001f

    private fun springFor(
        response: Float,
        damping: Float,
        threshold: Float = Spring.DefaultDisplacementThreshold,
    ): SpringSpec<Float> {
        val omega = 2.0 * PI / response
        return spring(
            dampingRatio = damping,
            stiffness = (omega * omega).toFloat(),
            visibilityThreshold = threshold,
        )
    }

    val OpenWidth = springFor(0.40f, 0.76f, SCALE_THRESHOLD)
    val OpenHeight = springFor(0.48f, 0.78f, SCALE_THRESHOLD)
    val OpenPosition = springFor(0.46f, 0.74f)
    val CloseWidth = springFor(0.30f, 0.92f, SCALE_THRESHOLD)
    val CloseHeight = springFor(0.34f, 0.90f, SCALE_THRESHOLD)
    val ClosePosition = springFor(0.40f, 0.90f)
    val PillShrink = springFor(0.34f, 0.86f, SCALE_THRESHOLD)

    const val OpenFadeMillis = 180
    const val CloseFadeMillis = 160

    fun pillRestore(from: Float): KeyframesSpec<Float> = keyframes {
        durationMillis = 380
        from at 0
        1.08f at 240 using FastOutSlowInEasing
        0.96f at 320
        1f at 380
    }
}

internal fun LayoutCoordinates.boundsOnScreen(view: View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
