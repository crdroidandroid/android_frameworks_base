package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.android.systemui.axdynamicbar.shared.LocalBlurAlpha

/**
 * A child (e.g. media seekbar) can set this to `true` to prevent
 * [MagneticSwipeToDismiss] from claiming horizontal drags.
 * Set it on ACTION_DOWN so the lock is active before slop detection.
 */
val LocalDismissSwipeLock = compositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

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
    val latestOnDismiss = rememberUpdatedState(onDismiss)
    val viewConfig = LocalViewConfiguration.current

    val detachPx = with(density) { DETACH_THRESHOLD.toPx() }
    val dismissVelPx = with(density) { DISMISS_VELOCITY.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var detached by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    val swipeLock = remember { mutableStateOf(false) }

    if (dismissed) return

    val absOff = maxOf(abs(offsetX), abs(offsetY))
    val progress = (absOff / (detachPx * 4f)).coerceIn(0f, 1f)
    val contentAlpha = 1f - progress * 0.5f

    // The frosted backdrop is a platform View that the graphicsLayer alpha below does not reach,
    // so the swipe fade is forwarded to it explicitly — multiplied into whatever fade the
    // container (expanded panel, alert overlay) is already applying.
    val blurAlpha = LocalBlurAlpha.current * contentAlpha

    CompositionLocalProvider(
        LocalDismissSwipeLock provides swipeLock,
        LocalBlurAlpha provides blurAlpha,
    ) {
        Box(
            modifier =
                modifier
                    .graphicsLayer {
                        translationX = offsetX
                        translationY = offsetY
                        alpha = contentAlpha
                        scaleX = 1f - progress * 0.04f
                        scaleY = 1f - progress * 0.04f
                        rotationZ = (offsetX / detachPx).coerceIn(-1f, 1f) * 2f
                    }
                    .pointerInput(allowUpDismiss) {
                        val touchSlop = viewConfig.touchSlop
                        var settleJob: kotlinx.coroutines.Job? = null

                        awaitEachGesture {
                        
                            val down: PointerInputChange
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val d = event.changes.firstOrNull {
                                    it.changedToDownIgnoreConsumed()
                                }
                                if (d != null) { down = d; break }
                            }
                        
                            settleJob?.cancel()
                            settleJob = null
                            // The previous snap-back may have been interrupted by this gesture.
                            offsetX = 0f
                            offsetY = 0f
                            var axis = 0
                            var velocity = 0f
                            var prevTime = 0L
                            detached = false

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (!change.pressed) {
                                
                                    if (axis != 0) change.consume()
                                    if (axis == 0) break 

                                    val releaseAxis = axis
                                    val vel = velocity
                                    val off = if (releaseAxis == 2) offsetY else offsetX
                                    val wasDetached = detached
                                    val shouldDismiss =
                                        abs(vel) >= dismissVelPx ||
                                            (wasDetached && abs(off) > detachPx * 0.5f)
                                    val destination =
                                        if (shouldDismiss) {
                                            if (releaseAxis == 2) {
                                                -size.height * 1.5f
                                            } else {
                                                val direction =
                                                    if (abs(vel) > 100f) sign(vel) else sign(off)
                                                direction * size.width * 1.5f
                                            }
                                        } else {
                                            0f
                                        }
                                    settleJob = scope.launch {
                                        if (shouldDismiss || wasDetached) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        val animation = Animatable(off)
                                        animation.animateTo(
                                            targetValue = destination,
                                            animationSpec =
                                                if (shouldDismiss) {
                                                    spring(dampingRatio = 1f, stiffness = FLING_STIFFNESS)
                                                } else {
                                                    spring(dampingRatio = SNAP_DAMPING, stiffness = SNAP_STIFFNESS)
                                                },
                                        ) {
                                            if (releaseAxis == 2) offsetY = value else offsetX = value
                                        }
                                        if (shouldDismiss) {
                                            dismissed = true
                                            latestOnDismiss.value()
                                        } else {
                                            detached = false
                                        }
                                    }
                                    break
                                }

                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y

                                if (axis == 0) {
                                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                        val wouldBeHorizontal = abs(dx) >= abs(dy)
                                        // If a child (seekbar) has locked horizontal swipe, skip.
                                        if (wouldBeHorizontal && swipeLock.value) {
                                            break
                                        }
                                        axis = if (wouldBeHorizontal) {
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
                                val currentOffset = if (axis == 2) offsetY else offsetX

                                val now = change.uptimeMillis
                                if (prevTime > 0L) {
                                    val dt = (now - prevTime).coerceAtLeast(1) / 1000f
                                    val instantVel = dragAmount / dt
                                    velocity =
                                        velocity * (1f - VELOCITY_SMOOTHING) +
                                            instantVel * VELOCITY_SMOOTHING
                                }
                                prevTime = now

                                val nextOffset =
                                    if (detached) {
                                        currentOffset + dragAmount
                                    } else {
                                        val pulledOffset = currentOffset + dragAmount * MAGNETIC_PULL
                                        if (abs(pulledOffset) >= detachPx) {
                                            detached = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            currentOffset + dragAmount
                                        } else {
                                            pulledOffset
                                        }
                                    }
                                if (axis == 2) offsetY = nextOffset else offsetX = nextOffset
                            }
                        }
                    }
        ) {
            content()
        }
    }
}
