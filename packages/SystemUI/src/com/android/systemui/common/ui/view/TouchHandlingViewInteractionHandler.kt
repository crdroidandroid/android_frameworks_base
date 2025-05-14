/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.common.ui.view

import android.content.Context
import android.graphics.Point
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.android.systemui.log.TouchHandlingViewLogger
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.properties.Delegates
import kotlinx.coroutines.DisposableHandle
import com.android.systemui.util.TapPositionUtil

/** Encapsulates logic to handle complex touch interactions with a [TouchHandlingView]. */
class TouchHandlingViewInteractionHandler(
    context: Context,
    /**
     * Callback to run the given [Runnable] with the given delay, returning a [DisposableHandle]
     * allowing the delayed runnable to be canceled before it is run.
     */
    private val postDelayed: (block: Runnable, delayMs: Long) -> DisposableHandle,
    /** Callback to be queried to check if the view is attached to its window. */
    private val isAttachedToWindow: () -> Boolean,
    /** Callback reporting the a long-press gesture was detected at the given coordinates. */
    private val onLongPressDetected: (x: Int, y: Int) -> Unit,
    /** Callback reporting the a single tap gesture was detected at the given coordinates. */
    private val onSingleTapDetected: (x: Int, y: Int) -> Unit,
    /** Callback reporting that a double tap gesture was detected. */
    private val onDoubleTapDetected: () -> Unit,
    /** Time for the touch to be considered a long-press in ms */
    var longPressDuration: () -> Long,
    /**
     * Default touch slop that is allowed, if the movement between [MotionEventModel.Down] and
     * [MotionEventModel.Up] is more than [allowedTouchSlop] then the touch is not processed as
     * single tap or a long press.
     */
    val allowedTouchSlop: Int,
    /** Optional logger that can be passed in to log touch events */
    val logger: TouchHandlingViewLogger? = null,
) {
    sealed class MotionEventModel {
        object Other : MotionEventModel()

        data class Down(val x: Int, val y: Int) : MotionEventModel()

        data class Move(val distanceMoved: Float) : MotionEventModel()

        data class Up(val distanceMoved: Float, val gestureDuration: Long) : MotionEventModel()

        object Cancel : MotionEventModel()
    }

    var isLongPressHandlingEnabled: Boolean = false
    var isDoubleTapHandlingEnabled: Boolean = false
    var scheduledLongPressHandle: DisposableHandle? = null

    private var doubleTapAwaitingUp: Boolean = false
    private var lastDoubleTapDownEventTime: Long? = null

    /** Record coordinate for last DOWN event for single tap */
    val lastEventDownCoordinate = Point(-1, -1)

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(event: MotionEvent): Boolean {
                    if (isDoubleTapHandlingEnabled) {
                        doubleTapAwaitingUp = true
                        lastDoubleTapDownEventTime = event.eventTime
                        return true
                    }
                    return false
                }
            },
        )

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (isDoubleTapHandlingEnabled) {
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && doubleTapAwaitingUp) {
                lastDoubleTapDownEventTime?.let { time ->
                    if (
                        event.eventTime - time < ViewConfiguration.getDoubleTapTimeout()
                    ) {
                        cancelScheduledLongPress()
                        TapPositionUtil.INSTANCE().setTapPos(event.rawX.toInt(), event.rawY.toInt())
                        onDoubleTapDetected()
                    }
                }
                doubleTapAwaitingUp = false
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL && doubleTapAwaitingUp) {
                doubleTapAwaitingUp = false
            }
        }

        if (isLongPressHandlingEnabled) {
            val motionEventModel = event.toModel()

            return when (motionEventModel) {
                is MotionEventModel.Down -> {
                    scheduleLongPress(motionEventModel.x, motionEventModel.y)
                    lastEventDownCoordinate.x = motionEventModel.x
                    lastEventDownCoordinate.y = motionEventModel.y
                    true
                }

                is MotionEventModel.Move -> {
                    if (motionEventModel.distanceMoved > allowedTouchSlop) {
                        logger?.cancelingLongPressDueToTouchSlop(
                            motionEventModel.distanceMoved,
                            allowedTouchSlop,
                        )
                        cancelScheduledLongPress()
                    }
                    false
                }

                is MotionEventModel.Up -> {
                    logger?.onUpEvent(
                        motionEventModel.distanceMoved,
                        allowedTouchSlop,
                        motionEventModel.gestureDuration,
                    )
                    cancelScheduledLongPress()
                    if (
                        motionEventModel.distanceMoved <= allowedTouchSlop &&
                            motionEventModel.gestureDuration < longPressDuration()
                    ) {
                        logger?.dispatchingSingleTap()
                        dispatchSingleTap(lastEventDownCoordinate.x, lastEventDownCoordinate.y)
                    }
                    false
                }

                is MotionEventModel.Cancel -> {
                    logger?.motionEventCancelled()
                    cancelScheduledLongPress()
                    false
                }

                else -> false
            }
        }

        return false
    }

    private fun scheduleLongPress(x: Int, y: Int) {
        val duration = longPressDuration()
        logger?.schedulingLongPress(duration)
        scheduledLongPressHandle =
            postDelayed(
                {
                    logger?.longPressTriggered()
                    dispatchLongPress(x = x, y = y)
                },
                duration,
            )
    }

    private fun dispatchLongPress(x: Int, y: Int) {
        if (!isAttachedToWindow()) {
            return
        }

        onLongPressDetected(x, y)
    }

    private fun cancelScheduledLongPress() {
        scheduledLongPressHandle?.dispose()
    }

    private fun dispatchSingleTap(x: Int, y: Int) {
        if (!isAttachedToWindow()) {
            return
        }
        TapPositionUtil.INSTANCE().setTapPos(x, y)
        onSingleTapDetected(x, y)
    }

    private fun MotionEvent.toModel(): MotionEventModel {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEventModel.Down(x = x.toInt(), y = y.toInt())
            MotionEvent.ACTION_MOVE -> MotionEventModel.Move(distanceMoved = distanceMoved())
            MotionEvent.ACTION_UP ->
                MotionEventModel.Up(
                    distanceMoved = distanceMoved(),
                    gestureDuration = gestureDuration(),
                )
            MotionEvent.ACTION_CANCEL -> MotionEventModel.Cancel
            else -> MotionEventModel.Other
        }
    }

    private fun MotionEvent.distanceMoved(): Float {
        return if (historySize > 0) {
            sqrt((x - getHistoricalX(0)).pow(2) + (y - getHistoricalY(0)).pow(2))
        } else {
            0f
        }
    }

    private fun MotionEvent.gestureDuration(): Long {
        return eventTime - downTime
    }
}
