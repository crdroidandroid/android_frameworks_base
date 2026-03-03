/*
 * SPDX-FileCopyrightText: VoltageOS
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar

import android.content.Context
import android.util.Log
import android.widget.SeekBar
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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

private const val EXPAND_DURATION_MS = 350
private const val COLLAPSE_DURATION_MS = 250

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

    var showPlayer by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(state.showMediaControls) {
        if (state.showMediaControls) {
            showPlayer = true
        } else if (showPlayer == true) {
            showPlayer = false
        }
    }

    if (!state.isVisible) return

    var dragOffset = 0f
    val gestureModifier = Modifier
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragOffset = 0f },
                onDragEnd = {
                    if (dragOffset < -50) controller.onSwipe(true)
                    else if (dragOffset > 50) controller.onSwipe(false)
                }
            ) { _, delta -> dragOffset += delta }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { controller.onDoubleTap() },
                onLongPress = { controller.onLongPress() },
                onTap = { controller.onInteraction() }
            )
        }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        when {
            state.isCompactMode -> {
                val pv = progressFraction(state)
                Box(
                    modifier = Modifier.size(26.dp).then(gestureModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val strokePx = 3.dp.toPx()
                        val diam = size.minDimension - strokePx
                        val r = diam / 2
                        val tl = center - Offset(r, r)
                        val sz = Size(diam, diam)
                        drawArc(Color(0x33FFFFFF), 0f, 360f, false, tl, sz,
                            style = Stroke(strokePx))
                        drawArc(accent, -90f, 360f * pv, false, tl, sz,
                            style = Stroke(strokePx, cap = StrokeCap.Round))
                    }
                    state.iconBitmap?.let { bmp ->
                        Image(bmp, null, Modifier.size(14.dp).clip(RoundedCornerShape(14.dp)))
                    }
                }
            }

            state.trackTitle != null -> {
                MusicChip(state = state, chipShape = chipShape,
                    gestureModifier = gestureModifier)
            }

            else -> {
                val pv = progressFraction(state)
                Row(
                    modifier = Modifier
                        .width(86.dp).height(26.dp)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .then(gestureModifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.iconBitmap?.let { bmp ->
                        Image(bmp, null, Modifier.size(16.dp)
                            .clip(RoundedCornerShape(16.dp)).padding(start = 1.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Box(
                        Modifier.weight(1f).height(6.dp).padding(end = 3.dp)
                            .clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(pv).background(accent))
                    }
                }
            }
        }

        if (showPlayer != null) {
            Popup(
                alignment = Alignment.BottomCenter,
                onDismissRequest = { controller.onMediaMenuDismiss() },
                properties = PopupProperties(focusable = false)
            ) {
                AnimatedMiniMediaPlayer(
                    state = state,
                    isOpening = showPlayer == true,
                    onAnimationEnd = { showPlayer = null },
                    onPrev = { controller.onMediaAction(0) },
                    onPlayPause = { controller.onMediaAction(1) },
                    onNext = { controller.onMediaAction(2) },
                    onSeek = { controller.onSeek(it) },
                )
            }
        }
    }
}

@Composable
private fun AnimatedMiniMediaPlayer(
    state: ProgressState,
    isOpening: Boolean,
    onAnimationEnd: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val anim = remember { Animatable(0f) }

    LaunchedEffect(isOpening) {
        if (isOpening) {
            anim.animateTo(1f, tween(EXPAND_DURATION_MS, easing = FastOutSlowInEasing))
        } else {
            anim.animateTo(0f, tween(COLLAPSE_DURATION_MS, easing = LinearOutSlowInEasing))
            onAnimationEnd()
        }
    }

    val p = anim.value
    val scale = 0.88f + p * 0.12f
    val alpha = p

    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
    ) {
        MiniMediaPlayer(state, onPrev, onPlayPause, onNext, onSeek)
    }
}

@Composable
private fun MiniMediaPlayer(
    state: ProgressState,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = screenWidth - 24.dp
    val cardShape = RoundedCornerShape(24.dp)
    val accent = MaterialTheme.colorScheme.primary

    val progressMs = state.progress.toLong()
    val durationMs = state.maxProgress.toLong()

    val artKey = state.trackChangeId

    val hasRealArt = state.albumArtBitmap != null

    val blurEffect = remember {
        android.graphics.RenderEffect
            .createBlurEffect(28f, 28f, android.graphics.Shader.TileMode.MIRROR)
            .asComposeRenderEffect()
    }

    Box(
        modifier = Modifier
            .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
            .width(cardWidth)
            .wrapContentHeight()
            .shadow(20.dp, cardShape)
            .clip(cardShape)
    ) {
        key(artKey) {
            if (hasRealArt) {
                Image(state.albumArtBitmap!!, null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        renderEffect = blurEffect; scaleX = 1.15f; scaleY = 1.15f
                    })
            } else {
                Box(Modifier.matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }

        Box(Modifier.matchParentSize()
            .background(Color.Black.copy(alpha = if (hasRealArt) 0.45f else 0f)))
        Box(Modifier.matchParentSize().background(accent.copy(alpha = 0.07f)))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            key(artKey) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.albumArtBitmap != null -> Image(
                            state.albumArtBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                        )
                        state.iconBitmap != null -> Image(
                            state.iconBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                        )
                        else -> Image(
                            painterResource(R.drawable.ic_default_music_icon),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.55f))
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = state.trackTitle ?: "",
                    style = TextStyle(color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!state.artistName.isNullOrBlank()) {
                    Text(
                        text = state.artistName,
                        style = TextStyle(color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatMs(progressMs),
                        style = TextStyle(color = Color.White.copy(alpha = 0.55f),
                            fontSize = 9.sp, fontWeight = FontWeight.Medium))
                    if (durationMs > 0)
                        Text("-${formatMs(durationMs - progressMs)}",
                            style = TextStyle(color = Color.White.copy(alpha = 0.55f),
                                fontSize = 9.sp, fontWeight = FontWeight.Medium))
                }

                SeekBarCompose(
                    progressFraction = progressFraction(state),
                    isPlaying = state.isMediaPlaying,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PlayerButton(R.drawable.ic_media_control_skip_previous, "Previous",
                    36.dp, 20.dp, Color.White.copy(alpha = 0.88f), onPrev)
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.20f))
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            if (state.isMediaPlaying) R.drawable.ic_media_control_pause
                            else R.drawable.ic_media_control_play),
                        contentDescription = if (state.isMediaPlaying) "Pause" else "Play",
                        modifier = Modifier.size(22.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                PlayerButton(R.drawable.ic_media_control_skip_next, "Next",
                    36.dp, 20.dp, Color.White.copy(alpha = 0.88f), onNext)
            }
        }
    }
}

@Composable
private fun SeekBarCompose(
    progressFraction: Float,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isScrubbing by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            SeekBar(ctx).apply {
                max = 10_000
                splitTrack = false

                thumb?.mutate()?.setTint(android.graphics.Color.WHITE)
                progressDrawable?.mutate()?.setTint(android.graphics.Color.WHITE)

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        sb: SeekBar?,
                        v: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser) onSeek(v / 10_000f)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {
                        isScrubbing = true
                    }

                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        isScrubbing = false
                    }
                })
            }
        },
        update = { bar ->
            if (!isScrubbing) {
                val target = (progressFraction * 10_000f).toInt().coerceIn(0, 10_000)
                if (bar.progress != target) bar.progress = target
            }

            val alpha = if (isPlaying) 255 else (255 * 0.55f).toInt()
            bar.thumb?.alpha = alpha
            bar.progressDrawable?.alpha = alpha
        },
        modifier = modifier
    )
}

@Composable
private fun PlayerButton(
    iconRes: Int, contentDescription: String,
    size: Dp, iconSize: Dp, tint: Color, onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(iconRes), contentDescription,
            modifier = Modifier.size(iconSize), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
private fun MusicChip(
    state: ProgressState,
    chipShape: RoundedCornerShape,
    gestureModifier: Modifier,
) {
    val bg = colorResource(android.R.color.system_accent1_500)
    val text = colorResource(android.R.color.system_accent1_100)

    Row(
        modifier = Modifier
            .animateContentSize(animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
            .widthIn(min = 55.dp, max = 85.dp)
            .padding(start = 4.dp)
            .clip(chipShape)
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .then(gestureModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        state.iconBitmap?.let { bmp ->
            Image(bmp, null, Modifier.size(15.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(4.dp))
        }
        Box(
            Modifier.fadingEdge(
                Brush.horizontalGradient(0.85f to Color.White, 1f to Color.Transparent))
        ) {
            Text(
                text = state.trackTitle ?: "",
                style = TextStyle(color = text, fontSize = 10.sp,
                    fontWeight = FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .basicMarquee(initialDelayMillis = 15_000, repeatDelayMillis = 15_000)
                    .padding(start = 1.dp)
            )
        }
    }
}

private fun Modifier.fadingEdge(brush: Brush) = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent { drawContent(); drawRect(brush = brush, blendMode = BlendMode.DstIn) }

private fun progressFraction(state: ProgressState): Float =
    if (state.maxProgress > 0)
        (state.progress.toFloat() / state.maxProgress.toFloat()).coerceIn(0f, 1f)
    else 0f

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
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
                    albumArtBitmap = state.albumArtBitmap,
                    packageName = state.packageName,
                    isCompactMode = state.isCompactMode,
                    showMediaControls = state.showMediaControls,
                    isMediaPlaying = state.isMediaPlaying,
                    trackTitle = state.trackTitle,
                    artistName = state.artistName,
                    appLabel = state.appLabel,
                    trackChangeId = state.trackChangeId,
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
    fun onSeek(fraction: Float) = controller.onSeek(fraction)
    fun setSystemChipVisible(visible: Boolean) = controller.setSystemChipVisible(visible)
}
