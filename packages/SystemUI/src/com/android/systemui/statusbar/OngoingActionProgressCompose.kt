/*
 * Copyright (C) 2025 VoltageOS
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

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "OngoingActionProgressCompose"

/**
 * Composable that displays an ongoing action progress indicator in the status bar.
 * Shows app icon and progress bar for notifications with progress information.
 */
@Composable
fun OngoingActionProgress(
    controller: OnGoingActionProgressComposeController,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsState()

    val accentColor = MaterialTheme.colorScheme.primary

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            val progressValue = if (state.maxProgress > 0) {
                (state.progress.toFloat() / state.maxProgress.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            var dragOffset = 0f
            val gestureModifier = Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragOffset = 0f },
                    onDragEnd = {
                        if (dragOffset < -50) controller.onSwipe(true) 
                        else if (dragOffset > 50) controller.onSwipe(false) 
                    }
                ) { _, dragAmount ->
                    dragOffset += dragAmount
                }
            }.pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { controller.onDoubleTap() },
                    onLongPress = { controller.onLongPress() },
                    onTap = { controller.onInteraction() }
                )
            }

            if (state.isCompactMode) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .then(gestureModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidthPx = 3.dp.toPx()
                        val diameter = size.minDimension - strokeWidthPx
                        val radius = diameter / 2
                        val topLeftOffset = center - Offset(radius, radius)
                        val arcSize = Size(diameter, diameter)

                        drawArc(
                            color = Color(0x33FFFFFF),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeftOffset,
                            size = arcSize,
                            style = Stroke(width = strokeWidthPx)
                        )

                        drawArc(
                            color = accentColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progressValue,
                            useCenter = false,
                            topLeft = topLeftOffset,
                            size = arcSize,
                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                        )
                    }

                    state.icon?.let { iconBitmap ->
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = "App icon",
                            modifier = Modifier.size(14.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            colorFilter = null 
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .width(86.dp)
                        .height(26.dp)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .then(gestureModifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.icon?.let { iconBitmap ->
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = "App icon",
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(16.dp)) // Clip to prevent rendering artifacts
                                .padding(start = 1.dp),
                            colorFilter = null 
                        )

                        Spacer(modifier = Modifier.width(5.dp))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .padding(end = 3.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x33FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressValue)
                                .background(accentColor) 
                        )
                    }
                }
            }

            if (state.showMediaControls) {
                Popup(
                    alignment = Alignment.BottomCenter,
                    onDismissRequest = { controller.onMediaMenuDismiss() }
                ) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(140.dp)
                            .height(48.dp)
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                            .background(Color(0xFF202020), RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(32.dp).clickable { controller.onMediaAction(0) }, contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                val path = Path().apply {
                                    moveTo(size.width, 0f)
                                    lineTo(0f, size.height / 2)
                                    lineTo(size.width, size.height)
                                    close()
                                }
                                drawPath(path, Color.White, style = Fill)
                                drawRect(Color.White, topLeft = Offset(0f, 0f), size = Size(2.dp.toPx(), size.height))
                            }
                        }

                        Box(modifier = Modifier.size(32.dp).clickable { controller.onMediaAction(1) }, contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                drawCircle(Color.White)
                            }
                        }

                        Box(modifier = Modifier.size(32.dp).clickable { controller.onMediaAction(2) }, contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(size.width, size.height / 2)
                                    lineTo(0f, size.height)
                                    close()
                                }
                                drawPath(path, Color.White, style = Fill)
                                drawRect(Color.White, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * State data for the progress indicator
 */
data class ProgressState(
    val isVisible: Boolean = false,
    val progress: Int = 0,
    val maxProgress: Int = 100,
    val icon: androidx.compose.ui.graphics.ImageBitmap? = null,
    val packageName: String? = null,
    val isIconAdaptive: Boolean = false,
    val isCompactMode: Boolean = false,
    val showMediaControls: Boolean = false
)

/**
 * Compose-friendly controller that bridges the Java OnGoingActionProgressController
 * to Compose state.
 */
class OnGoingActionProgressComposeController(
    context: Context,
    notificationListener: NotificationListener,
    keyguardStateController: KeyguardStateController,
    headsUpManager: HeadsUpManager,
    vibrator: VibratorHelper
) {
    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state

    private val javaController: OnGoingActionProgressController

    init {
        Log.d(TAG, "Initializing OnGoingActionProgressComposeController")

        val dummyGroup = OnGoingActionProgressGroup(null, null, null, null, null, null)

        try {
            javaController = OnGoingActionProgressController(
                context,
                dummyGroup,
                notificationListener,
                keyguardStateController,
                headsUpManager,
                vibrator
            )

            javaController.setStateCallback { isVisible, progress, maxProgress, icon, isAdaptive, packageName, isCompact, showMenu ->
                Log.d(TAG, "State callback: isVisible=$isVisible, compact=$isCompact, showMenu=$showMenu")

                val iconSizePx = if (isCompact) {
                    (14 * context.resources.displayMetrics.density).toInt() * 2 
                } else {
                    (16 * context.resources.displayMetrics.density).toInt() * 2 
                }

                val iconBitmap = try {
                    icon?.let { drawable ->
                        drawable.toBitmap(
                            width = iconSizePx,
                            height = iconSizePx,
                            config = Bitmap.Config.ARGB_8888
                        ).asImageBitmap()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to convert icon to bitmap", e)
                    null
                }

                _state.value = ProgressState(
                    isVisible = isVisible,
                    progress = progress,
                    maxProgress = maxProgress,
                    icon = iconBitmap,
                    packageName = packageName,
                    isIconAdaptive = isAdaptive,
                    isCompactMode = isCompact,
                    showMediaControls = showMenu
                )
            }

            Log.d(TAG, "OnGoingActionProgressComposeController initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OnGoingActionProgressController", e)
            throw e
        }
    }

    fun destroy() {
        javaController.destroy()
    }

    fun onInteraction() {
        javaController.onInteraction()
    }

    fun onMediaAction(action: Int) {
        javaController.onMediaAction(action)
    }

    fun onMediaMenuDismiss() {
        javaController.onMediaMenuDismiss()
    }

    fun onDoubleTap() {
        javaController.onDoubleTap()
    }

    fun onSwipe(isNext: Boolean) {
        javaController.onSwipe(isNext)
    }

    fun onLongPress() {
        javaController.onLongPress()
    }

    fun setSystemChipVisible(visible: Boolean) {
        javaController.setSystemChipVisible(visible)
    }
}
