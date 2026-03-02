/*
 * SPDX-FileCopyrightText: VoltageOS
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    val accent = MaterialTheme.colorScheme.primary
    val chipShape = RoundedCornerShape(24.dp)

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center) {
            var dragOffset = 0f
            val gestureModifier = Modifier
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragOffset = 0f },
                        onDragEnd = {
                            if (dragOffset < -50) controller.onSwipe(true)
                            else if (dragOffset > 50) controller.onSwipe(false)
                        }
                    ) { _, dragAmount -> dragOffset += dragAmount }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { controller.onDoubleTap() },
                        onLongPress = { controller.onLongPress() },
                        onTap = { controller.onInteraction() }
                    )
                }

            when {
                state.isCompactMode -> {
                    val progressValue = progressFraction(state)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .then(gestureModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokePx = 3.dp.toPx()
                            val diam = size.minDimension - strokePx
                            val r = diam / 2
                            val tl = center - Offset(r, r)
                            val arcSz = Size(diam, diam)

                            drawArc(
                                color = Color(0x33FFFFFF),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = tl,
                                size = arcSz,
                                style = Stroke(width = strokePx)
                            )
                            drawArc(
                                color = accent,
                                startAngle = -90f,
                                sweepAngle = 360f * progressValue,
                                useCenter = false,
                                topLeft = tl,
                                size = arcSz,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                        }

                        state.iconBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        }
                    }
                }

                state.trackTitle != null -> {
                    MusicChip(
                        state = state,
                        chipShape = chipShape,
                        gestureModifier = gestureModifier
                    )
                }

                else -> {
                    val progressValue = progressFraction(state)
                    Row(
                        modifier = Modifier
                            .width(86.dp)
                            .height(26.dp)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .then(gestureModifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.iconBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .padding(start = 1.dp)
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
                                    .background(accent)
                            )
                        }
                    }
                }
            }

            if (state.showMediaControls) {
                val surfaceColor = MaterialTheme.colorScheme.surface
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
                            .background(surfaceColor, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MediaControlButton(
                            iconRes = R.drawable.ic_media_control_skip_previous,
                            contentDescription = "Previous",
                            onClick = { controller.onMediaAction(0) }
                        )

                        MediaControlButton(
                            iconRes = if (state.isMediaPlaying) {
                                R.drawable.ic_media_control_pause
                            } else {
                                R.drawable.ic_media_control_play
                            },
                            contentDescription = if (state.isMediaPlaying) "Pause" else "Play",
                            onClick = { controller.onMediaAction(1) }
                        )

                        MediaControlButton(
                            iconRes = R.drawable.ic_media_control_skip_next,
                            contentDescription = "Next",
                            onClick = { controller.onMediaAction(2) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicChip(
    state: ProgressState,
    chipShape: RoundedCornerShape,
    gestureModifier: Modifier,
) {
    val musicChipBg = colorResource(android.R.color.system_accent1_500)
    val musicChipText = colorResource(android.R.color.system_accent1_100)

    Row(
        modifier = Modifier
            .widthIn(min = 55.dp, max = 85.dp)
            .padding(start = 4.dp)
            .clip(chipShape)
            .background(musicChipBg)
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .then(gestureModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        state.iconBitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .clip(RoundedCornerShape(4.dp)),
                colorFilter = null
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fadingEdge(
                    brush = Brush.horizontalGradient(
                        0.85f to Color.White,
                        1.00f to Color.Transparent
                    )
                )
        ) {
            Text(
                text = state.trackTitle ?: "",
                style = TextStyle(
                    color = musicChipText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                ),
                maxLines = 1,
                modifier = Modifier
                    .basicMarquee(
                        initialDelayMillis = 15_000,
                        repeatDelayMillis = 15_000
                    )
                    .padding(start = 1.dp)
            )
        }
    }
}

private fun Modifier.fadingEdge(brush: Brush) = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
    }

private fun progressFraction(state: ProgressState): Float =
    if (state.maxProgress > 0)
        (state.progress.toFloat() / state.maxProgress.toFloat()).coerceIn(0f, 1f)
    else 0f

@Composable
private fun MediaControlButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
        )
    }
}

/**
 * Compose-facing controller that adapts OnGoingActionProgressController state
 * into Compose-friendly ProgressState.
 */
class OnGoingActionProgressComposeController(
    private val context: Context,
    notificationListener: NotificationListener,
    keyguardStateController: KeyguardStateController,
    headsUpManager: HeadsUpManager,
    vibrator: VibratorHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state

    private val controller: OnGoingActionProgressController

    init {
        Log.d(TAG, "Initializing OnGoingActionProgressComposeController")

        controller = OnGoingActionProgressController(
            context,
            notificationListener,
            keyguardStateController,
            headsUpManager,
            vibrator
        )

        scope.launch {
            controller.state.collect { state ->
                _state.value = ProgressState(
                    isVisible = state.isVisible,
                    progress = state.progress,
                    maxProgress = state.maxProgress,
                    iconBitmap = state.iconBitmap,
                    packageName = state.packageName,
                    isCompactMode = state.isCompactMode,
                    showMediaControls = state.showMediaControls,
                    isMediaPlaying = state.isMediaPlaying,
                    trackTitle = state.trackTitle,
                )
            }
        }

        Log.d(TAG, "OnGoingActionProgressComposeController initialized successfully")
    }

    fun destroy() {
        scope.cancel()
        controller.destroy()
    }

    fun onInteraction() = controller.onInteraction()
    fun onMediaAction(action: Int) = controller.onMediaAction(action)
    fun onMediaMenuDismiss() = controller.onMediaMenuDismiss()
    fun onDoubleTap() = controller.onDoubleTap()
    fun onSwipe(isNext: Boolean) = controller.onSwipe(isNext)
    fun onLongPress() = controller.onLongPress()
    fun setSystemChipVisible(visible: Boolean) = controller.setSystemChipVisible(visible)
}
