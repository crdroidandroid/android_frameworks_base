package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.PointerInputChange

private val DETACH_THRESHOLD = 72.dp
private const val MAGNETIC_PULL = 0.5f
private val DISMISS_VELOCITY = 500.dp
private const val SNAP_STIFFNESS = 550f
private const val SNAP_DAMPING = 0.6f
private const val FLING_STIFFNESS = 300f
private const val VELOCITY_SMOOTHING = 0.4f

@Composable
internal fun MagneticSwipeToDismiss(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    allowUpDismiss: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val viewConfig = LocalViewConfiguration.current

    val detachPx = with(density) { DETACH_THRESHOLD.toPx() }
    val dismissVelPx = with(density) { DISMISS_VELOCITY.toPx() }

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var detached by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    if (dismissed) return

    val absOff = maxOf(abs(offsetX.value), abs(offsetY.value))
    val progress = (absOff / (detachPx * 4f)).coerceIn(0f, 1f)

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    alpha = 1f - progress * 0.5f
                    scaleX = 1f - progress * 0.04f
                    scaleY = 1f - progress * 0.04f
                    rotationZ = (offsetX.value / detachPx).coerceIn(-1f, 1f) * 2f
                }
                .pointerInput(allowUpDismiss) {
                    val touchSlop = viewConfig.touchSlop

                    awaitEachGesture {
                        
                        val down: PointerInputChange
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val d = event.changes.firstOrNull {
                                it.changedToDownIgnoreConsumed()
                            }
                            if (d != null) { down = d; break }
                        }
                        
                        var axis = 0
                        var velocity = 0f
                        var prevTime = 0L
                        detached = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break

                            if (!change.pressed) {
                                
                                if (axis != 0) change.consume()
                                if (axis == 0) break 

                                val offset = if (axis == 2) offsetY else offsetX
                                val vel = velocity
                                val off = offset.value
                                scope.launch {
                                    val shouldDismiss =
                                        abs(vel) >= dismissVelPx ||
                                            (detached && abs(off) > detachPx * 0.5f)
                                    if (shouldDismiss) {
                                        val target = when (axis) {
                                            2 -> -size.height * 1.5f 
                                            else -> {
                                                val dir = if (abs(vel) > 100f) sign(vel) else sign(off)
                                                dir * size.width * 1.5f
                                            }
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        offset.animateTo(
                                            target,
                                            spring(dampingRatio = 1f, stiffness = FLING_STIFFNESS),
                                        )
                                        dismissed = true
                                        onDismiss()
                                    } else {
                                        if (detached) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        offset.animateTo(0f, spring(SNAP_DAMPING, SNAP_STIFFNESS))
                                        detached = false
                                    }
                                }
                                break
                            }

                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y

                            if (axis == 0) {
                                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                    axis = if (abs(dx) >= abs(dy)) {
                                        1 
                                    } else if (allowUpDismiss && dy < 0) {
                                        2 
                                    } else {
                                        break 
                                    }
                                } else {
                                    continue 
                                }
                            }

                            change.consume()

                            val delta = change.position - change.previousPosition
                            val dragAmount = if (axis == 2) delta.y else delta.x
                            val offset = if (axis == 2) offsetY else offsetX

                            val now = change.uptimeMillis
                            if (prevTime > 0L) {
                                val dt = (now - prevTime).coerceAtLeast(1) / 1000f
                                val instantVel = dragAmount / dt
                                velocity =
                                    velocity * (1f - VELOCITY_SMOOTHING) +
                                        instantVel * VELOCITY_SMOOTHING
                            }
                            prevTime = now

                            if (!detached) {
                                val target = offset.value + dragAmount * MAGNETIC_PULL
                                if (abs(target) >= detachPx) {
                                    detached = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch { offset.snapTo(offset.value + dragAmount) }
                                } else {
                                    scope.launch { offset.snapTo(target) }
                                }
                            } else {
                                scope.launch { offset.snapTo(offset.value + dragAmount) }
                            }
                        }
                    }
                }
    ) {
        content()
    }
}

