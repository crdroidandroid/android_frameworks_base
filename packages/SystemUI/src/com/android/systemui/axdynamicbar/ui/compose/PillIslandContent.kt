package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.ui.platform.LocalContext
import java.text.NumberFormat
import com.android.internal.R as InternalR
import com.android.systemui.common.shared.model.Icon as SysUISharedIcon
import com.android.systemui.common.ui.compose.Icon as SysUIIcon
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import androidx.compose.ui.graphics.graphicsLayer
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.lang.Math.toRadians
import kotlinx.coroutines.delay

@Composable
internal fun PillEventIcon(event: IslandEvent, tint: Color? = null) {
    when (event) {
        is IslandEvent.AudioRecording -> AudioRecordingPillIcon(event, tint)
        is IslandEvent.Media -> MediaPillIcon(event)
        is IslandEvent.PromotedOngoing -> PromotedOngoingPillIcon(event, tint)
        is IslandEvent.Sports -> SportsPillIcon(event)
        is IslandEvent.NowPlaying -> AnimatedNowPlayingIcon(tint ?: MintAccent)
        is IslandEvent.Bluetooth -> AnimatedBluetoothIcon(tint ?: BlueAccent)
        is IslandEvent.Hotspot -> AnimatedHotspotIcon(tint ?: TealAccent)
        is IslandEvent.Charging -> AnimatedBoltIcon(tint ?: GreenAccent)
        is IslandEvent.Alarm -> AnimatedBellIcon(tint ?: OrangeAccent, isAnimating = event.isRinging)
        is IslandEvent.Timer -> AnimatedHourglassIcon(tint ?: BlueAccent, isAnimating = !event.isPaused)
        is IslandEvent.Stopwatch -> AnimatedTickIcon(tint ?: MintAccent, isRunning = event.isRunning)
        is IslandEvent.RingerMode -> RingerIcon(event, tint)
        is IslandEvent.Vpn -> AnimatedShieldIcon(tint ?: IndigoAccent)
        is IslandEvent.Clipboard -> AnimatedClipboardIcon(tint ?: IndigoAccent)
        is IslandEvent.Notification -> NotificationPillIcon(event)
        is IslandEvent.AppSwitch -> AppSwitchPillIcon(event)
        is IslandEvent.Torch ->
            Icon(Icons.Filled.FlashlightOn, null, tint = tint ?: YellowAccent, modifier = Modifier.size(SizeBadge))
        is IslandEvent.BiometricUnlock -> BiometricUnlockIcon(tint)
        is IslandEvent.KeyguardIndication -> KeyguardIndicationIcon(event, tint)
        is IslandEvent.AospChip -> AospChipPillIcon(event, tint)
    }
}

@Composable
private fun AospChipPillIcon(event: IslandEvent.AospChip, tint: Color? = null) {
    val color = tint ?: aospChipAccent(event.active)
    val context = LocalContext.current
    val isCountdown = event.active.content is OngoingActivityChipModel.Content.Countdown
    val isIconOnly = event.active.content is OngoingActivityChipModel.Content.IconOnly
    val isCall = event.active.key.startsWith("callChip-")
    val useScreenRecFallback = isCountdown && event.active.key == "ScreenRecord"

    val renderIcon: OngoingActivityChipModel.ChipIcon = when {
        isCall -> OngoingActivityChipModel.ChipIcon.SingleColorIcon(
            SysUISharedIcon.Resource(InternalR.drawable.ic_phone, null)
        )
        event.active.icon != null -> event.active.icon!!
        useScreenRecFallback -> OngoingActivityChipModel.ChipIcon.SingleColorIcon(
            SysUISharedIcon.Resource(R.drawable.ic_screenrecord, null)
        )
        else -> return
    }

    val iconContent: @Composable () -> Unit = {
        when (renderIcon) {
            is OngoingActivityChipModel.ChipIcon.SingleColorIcon -> {
                SysUIIcon(
                    icon = renderIcon.impl,
                    tint = color,
                    modifier = Modifier.size(SizeBadge),
                )
            }
            is OngoingActivityChipModel.ChipIcon.StatusBarView -> {
                val drawable = renderIcon.impl.drawable
                if (drawable != null) {
                    Image(
                        bitmap = drawable.toScaledBitmap(SizeBadge),
                        contentDescription = null,
                        modifier = Modifier.size(SizeBadge),
                    )
                }
            }
            is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon -> {
                val drawable = remember(event.active.managingPackageName) {
                    event.active.managingPackageName?.let { pkg ->
                        try { context.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
                    }
                }
                if (drawable != null) {
                    Image(
                        bitmap = drawable.toScaledBitmap(SizeBadge),
                        contentDescription = null,
                        modifier = Modifier.size(SizeBadge),
                    )
                }
            }
        }
    }

    val isScreenRec = event.active.key == "ScreenRecord"
    when {
        isCall -> {
            val transition = rememberInfiniteTransition(label = "aosp_call_shake")
            val shake by transition.animateFloat(
                initialValue = -0.8f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(tween(90), RepeatMode.Reverse),
                label = "aosp_call_shake_anim",
            )
            Box(modifier = Modifier.size(SizeBadge).offset(x = shake.dp)) {
                iconContent()
            }
        }
        isIconOnly -> {
            val transition = rememberInfiniteTransition(label = "aosp_icononly")
            val pulseAlpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = AlphaDisabled,
                animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "aosp_icononly_alpha",
            )
            Box(modifier = Modifier.size(SizeBadge).graphicsLayer { this.alpha = pulseAlpha }) {
                iconContent()
            }
        }
        isScreenRec -> {
            val transition = rememberInfiniteTransition(label = "aosp_screenrec")
            val pulseAlpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = AlphaSubtle,
                animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "aosp_screenrec_alpha",
            )
            Box(modifier = Modifier.size(SizeBadge).graphicsLayer { this.alpha = pulseAlpha }) {
                iconContent()
            }
        }
        else -> iconContent()
    }
}

@Composable
private fun aospChipAccent(active: OngoingActivityChipModel.Active): Color {
    val context = LocalContext.current
    return Color(active.colors.background(context).defaultColor)
}

@Composable
private fun BlinkingDotIcon(color: Color, isAnimating: Boolean = true) {
    if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "blink")
        val alpha by
            transition.animateFloat(
                initialValue = 1f,
                targetValue = AlphaSubtle,
                animationSpec =
                    infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "blink_alpha",
            )
        Canvas(modifier = Modifier.size(SizeBadge)) { drawCircle(color = color.copy(alpha = alpha)) }
    } else {
        
        Canvas(modifier = Modifier.size(SizeBadge)) { drawCircle(color = color.copy(alpha = AlphaTertiary)) }
    }
}

@Composable
private fun AnimatedTrophyIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "trophy")
    val shimmer by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "trophy_shimmer",
    )

    Canvas(modifier = Modifier.size(SizeBadge)) {
        val w = size.width
        val h = size.height
        val sw = 1.5f.dp.toPx()
        val cx = w / 2f
        val shimColor = color.copy(alpha = shimmer)

        val cupTop = h * 0.12f
        val cupBot = h * 0.58f
        val cupLeft = w * 0.25f
        val cupRight = w * 0.75f
        val cupPath = Path().apply {
            moveTo(cupLeft, cupTop)
            lineTo(cupRight, cupTop)
            lineTo(cupRight - w * 0.05f, cupBot)
            quadraticBezierTo(cx, cupBot + h * 0.08f, cupLeft + w * 0.05f, cupBot)
            close()
        }
        drawPath(cupPath, shimColor, style = Stroke(sw, cap = StrokeCap.Round))

        val handleY = cupTop + (cupBot - cupTop) * 0.35f
        val handleRad = w * 0.12f
        drawArc(shimColor, 0f, 180f, false, Offset(cupLeft - handleRad * 1.5f, handleY - handleRad), Size(handleRad * 2, handleRad * 2), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))
        drawArc(shimColor, 180f, 180f, false, Offset(cupRight - handleRad * 0.5f, handleY - handleRad), Size(handleRad * 2, handleRad * 2), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))

        val stemTop = cupBot
        val stemBot = h * 0.78f
        drawLine(shimColor, Offset(cx, stemTop), Offset(cx, stemBot), sw, StrokeCap.Round)

        val baseY = stemBot
        val baseHalf = w * 0.22f
        drawLine(shimColor, Offset(cx - baseHalf, baseY), Offset(cx + baseHalf, baseY), sw * 1.2f, StrokeCap.Round)
    }
}

@Composable
private fun MediaPillIcon(event: IslandEvent.Media) {
    event.albumArt?.let { art ->
        Image(
            bitmap = art.toScaledBitmap(16.dp),
            contentDescription = null,
            modifier = Modifier.size(16.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
        ?: Box(
            modifier =
                Modifier.size(16.dp).clip(CircleShape).background(OrangeAccent.copy(alpha = AlphaSubtle + 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            WaveformAnimation(OrangeAccent, Modifier.size(10.dp), isAnimating = event.isPlaying, barCount = 3)
        }
}

@Composable
private fun AnimatedHotspotIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "hotspot")
    val sweep by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec =
                infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
            label = "hotspot_sweep",
        )
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val cx = size.width / 2
        val cy = size.height / 2
        for (i in 0 until 3) {
            val r = size.minDimension * (0.18f + i * 0.18f)
            val a = if (sweep > i) ((sweep - i).coerceIn(0f, 1f) * 0.8f) else AlphaFaint
            drawArc(
                color = color.copy(alpha = a),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(SizeStrokeThin.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        drawCircle(color, radius = size.minDimension * 0.1f, center = Offset(cx, cy))
    }
}

@Composable
private fun AnimatedNowPlayingIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "np")
    val bounce by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "np_bounce",
        )
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val w = size.width
        val h = size.height
        val sw = SizeStrokeThin.dp.toPx()
        val noteX = w * 0.55f
        val noteTop = h * 0.15f + bounce * h * 0.08f
        val noteBottom = h * 0.72f + bounce * h * 0.04f
        drawLine(color, Offset(noteX, noteTop), Offset(noteX, noteBottom), sw, StrokeCap.Round)
        drawCircle(color, radius = w * 0.14f, center = Offset(noteX - w * 0.1f, noteBottom))
        drawLine(
            color,
            Offset(noteX, noteTop),
            Offset(noteX + w * 0.2f, noteTop + h * 0.06f),
            sw,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun AnimatedBluetoothIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "bt")
    val alpha by
        transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bt_alpha",
        )
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val cx = size.width / 2
        val w = size.width
        val h = size.height
        val sw = SizeStrokeThin.dp.toPx()
        val drawColor = color.copy(alpha = alpha)
        drawLine(drawColor, Offset(cx, h * 0.1f), Offset(cx, h * 0.9f), sw, StrokeCap.Round)
        drawLine(drawColor, Offset(cx, h * 0.1f), Offset(cx + w * 0.22f, h * 0.3f), sw, StrokeCap.Round)
        drawLine(drawColor, Offset(cx + w * 0.22f, h * 0.3f), Offset(cx - w * 0.22f, h * 0.7f), sw, StrokeCap.Round)
        drawLine(drawColor, Offset(cx, h * 0.9f), Offset(cx + w * 0.22f, h * 0.7f), sw, StrokeCap.Round)
        drawLine(drawColor, Offset(cx + w * 0.22f, h * 0.7f), Offset(cx - w * 0.22f, h * 0.3f), sw, StrokeCap.Round)
    }
}

@Composable
private fun AnimatedShieldIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "shield")
    val glow by
        transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "shield_glow",
        )
    val shieldPath = remember { Path() }
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        shieldPath.rewind()
        shieldPath.moveTo(cx, h * 0.08f)
        shieldPath.lineTo(w * 0.15f, h * 0.28f)
        shieldPath.lineTo(w * 0.15f, h * 0.55f)
        shieldPath.quadraticTo(w * 0.15f, h * 0.85f, cx, h * 0.95f)
        shieldPath.quadraticTo(w * 0.85f, h * 0.85f, w * 0.85f, h * 0.55f)
        shieldPath.lineTo(w * 0.85f, h * 0.28f)
        shieldPath.close()
        drawPath(shieldPath, color.copy(alpha = glow * 0.3f))
        drawPath(shieldPath, color.copy(alpha = glow), style = Stroke(SizeStrokeThin.dp.toPx()))
        val sw = SizeStrokeThin.dp.toPx()
        drawLine(color, Offset(cx - w * 0.12f, h * 0.52f), Offset(cx, h * 0.65f), sw, StrokeCap.Round)
        drawLine(color, Offset(cx, h * 0.65f), Offset(cx + w * 0.18f, h * 0.38f), sw, StrokeCap.Round)
    }
}

@Composable
private fun AnimatedClipboardIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "clip")
    val slideIn by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    tween(1500, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
            label = "clip_slide",
        )
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val w = size.width
        val h = size.height
        val sw = SizeStrokeThin.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.6f),
            topLeft = Offset(w * 0.18f, h * 0.2f),
            size = Size(w * 0.64f, h * 0.72f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(sw),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.3f, h * 0.1f),
            size = Size(w * 0.4f, h * 0.15f),
            cornerRadius = CornerRadius(w * 0.06f),
        )
        val lineAlpha = slideIn.coerceIn(0.3f, 1f)
        val lineY1 = h * 0.48f
        val lineY2 = h * 0.62f
        val lineY3 = h * 0.76f
        val lineLeft = w * 0.3f
        val lineRight = w * 0.7f
        drawLine(color.copy(alpha = lineAlpha), Offset(lineLeft, lineY1), Offset(lineRight, lineY1), sw, StrokeCap.Round)
        drawLine(color.copy(alpha = lineAlpha * 0.7f), Offset(lineLeft, lineY2), Offset(lineRight * 0.85f, lineY2), sw, StrokeCap.Round)
        drawLine(color.copy(alpha = lineAlpha * 0.4f), Offset(lineLeft, lineY3), Offset(lineRight * 0.6f, lineY3), sw, StrokeCap.Round)
    }
}

@Composable
private fun AnimatedBoltIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "bolt")
    val glow by
        transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bolt_glow",
        )
    val boltPath = remember { Path() }
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val w = size.width
        val h = size.height
        boltPath.rewind()
        boltPath.moveTo(w * 0.55f, h * 0.05f)
        boltPath.lineTo(w * 0.25f, h * 0.50f)
        boltPath.lineTo(w * 0.45f, h * 0.50f)
        boltPath.lineTo(w * 0.40f, h * 0.95f)
        boltPath.lineTo(w * 0.75f, h * 0.42f)
        boltPath.lineTo(w * 0.55f, h * 0.42f)
        boltPath.close()
        drawPath(boltPath, color.copy(alpha = glow))
    }
}

@Composable
private fun AnimatedBellIcon(color: Color, isAnimating: Boolean = true) {
    val swing: Float
    if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "bell")
        val animatedSwing by
            transition.animateFloat(
                initialValue = -8f,
                targetValue = 8f,
                animationSpec =
                    infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bell_swing",
            )
        swing = animatedSwing
    } else {
        swing = 0f
    }
    Canvas(modifier = Modifier.size(SizeBadge)) {
        rotate(swing) {
            val cx = size.width / 2

            drawRoundRect(
                color = color,
                topLeft = Offset(cx - size.width * 0.28f, size.height * 0.15f),
                size = Size(size.width * 0.56f, size.height * 0.55f),
                cornerRadius = CornerRadius(size.width * 0.15f),
            )

            drawRoundRect(
                color = color,
                topLeft = Offset(cx - size.width * 0.38f, size.height * 0.62f),
                size = Size(size.width * 0.76f, size.height * 0.12f),
                cornerRadius = CornerRadius(size.width * 0.06f),
            )

            drawCircle(color, radius = size.width * 0.08f, center = Offset(cx, size.height * 0.85f))
        }
    }
}

@Composable
private fun AnimatedHourglassIcon(color: Color, isAnimating: Boolean = true) {
    val angle: Float
    if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "hg")
        val animatedAngle by
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 180f,
                animationSpec =
                    infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "hg_angle",
            )
        angle = animatedAngle
    } else {
        angle = 0f
    }
    val drawColor = if (isAnimating) color else color.copy(alpha = AlphaTertiary)
    val hgPath = remember { Path() }
    Canvas(modifier = Modifier.size(SizeBadge)) {
        rotate(angle) {
            val w = size.width
            val h = size.height
            hgPath.rewind()
            hgPath.moveTo(w * 0.2f, h * 0.1f)
            hgPath.lineTo(w * 0.8f, h * 0.1f)
            hgPath.lineTo(w * 0.55f, h * 0.45f)
            hgPath.lineTo(w * 0.8f, h * 0.9f)
            hgPath.lineTo(w * 0.2f, h * 0.9f)
            hgPath.lineTo(w * 0.45f, h * 0.45f)
            hgPath.close()
            drawPath(hgPath, drawColor, style = Stroke(SizeStrokeThin.dp.toPx(), cap = StrokeCap.Round))

            drawCircle(
                drawColor.copy(alpha = AlphaTertiary),
                radius = w * 0.08f,
                center = Offset(w / 2, h * 0.72f),
            )
        }
    }
}

@Composable
private fun AnimatedTickIcon(color: Color, isRunning: Boolean) {
    val angle: Float
    if (isRunning) {
        val transition = rememberInfiniteTransition(label = "tick")
        val animatedAngle by
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        tween(1500, easing = LinearEasing),
                        RepeatMode.Restart,
                    ),
                label = "tick_angle",
            )
        angle = animatedAngle
    } else {
        angle = 0f 
    }
    val drawColor = if (isRunning) color else color.copy(alpha = AlphaTertiary)
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 * 0.85f

        drawCircle(drawColor.copy(alpha = AlphaDisabled), radius = r, style = Stroke(1.2f.dp.toPx()))

        val rad = toRadians(angle.toDouble() - 90.0)
        drawLine(
            drawColor,
            Offset(cx, cy),
            Offset(cx + (r * 0.7f * cos(rad)).toFloat(), cy + (r * 0.7f * sin(rad)).toFloat()),
            strokeWidth = SizeStrokeThin.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(drawColor, radius = SizeStrokeThin.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun RingerIcon(event: IslandEvent.RingerMode, tint: Color? = null) {
    val style = eventStyleFor(event)
    val color = tint ?: style.accent
    val vector = style.icon ?: return
    when (event.mode) {
        AudioManager.RINGER_MODE_VIBRATE -> {
            val transition = rememberInfiniteTransition(label = "ringer_shake")
            val anim by transition.animateFloat(
                initialValue = -0.8f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(tween(90), RepeatMode.Reverse),
                label = "ringer_shake_anim",
            )
            Icon(vector, null, tint = color, modifier = Modifier.size(SizeBadge).offset(x = anim.dp))
        }
        AudioManager.RINGER_MODE_NORMAL -> {
            val transition = rememberInfiniteTransition(label = "ringer_normal")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "ringer_normal_scale",
            )
            Icon(
                vector,
                null,
                tint = color,
                modifier = Modifier.size(SizeBadge).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
        AudioManager.RINGER_MODE_SILENT -> {
            val transition = rememberInfiniteTransition(label = "ringer_silent")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = AlphaDisabled,
                animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "ringer_silent_alpha",
            )
            Icon(
                vector,
                null,
                tint = color,
                modifier = Modifier.size(SizeBadge).graphicsLayer { this.alpha = alpha },
            )
        }
        else -> Icon(vector, null, tint = color, modifier = Modifier.size(SizeBadge))
    }
}

@Composable
private fun AnimatedOngoingIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "ongoing")
    val rotate by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
            label = "ongoing_rot",
        )
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 * 0.8f
        val sw = SizeStrokeThin.dp.toPx()
        drawCircle(color.copy(alpha = AlphaFaint), radius = r, style = Stroke(sw))
        drawArc(
            color = color,
            startAngle = rotate,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2, r * 2),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun AnimatedRecentsIcon(color: Color) {
    Canvas(modifier = Modifier.size(SizeBadge)) {
        val w = size.width
        val h = size.height
        val sw = SizeStrokeThin.dp.toPx()
        val pad = w * 0.18f
        val gap = w * 0.08f
        val half = (w - pad * 2 - gap) / 2
        drawRoundRect(color, Offset(pad, pad), Size(half, half), CornerRadius(w * 0.06f), style = Stroke(sw))
        drawRoundRect(color, Offset(pad + half + gap, pad), Size(half, half), CornerRadius(w * 0.06f), style = Stroke(sw))
        drawRoundRect(color, Offset(pad, pad + half + gap), Size(half, half), CornerRadius(w * 0.06f), style = Stroke(sw))
        drawRoundRect(color.copy(alpha = AlphaTertiary), Offset(pad + half + gap, pad + half + gap), Size(half, half), CornerRadius(w * 0.06f), style = Stroke(sw))
    }
}

@Composable
private fun NotificationPillIcon(event: IslandEvent.Notification) {
    val icon = event.appIcon
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(16.dp),
            contentDescription = null,
            modifier = Modifier.size(16.dp).clip(ShapeXs),
        )
    } else {
        Icon(Icons.Filled.Notifications, null, tint = BlueAccent, modifier = Modifier.size(SizeBadge))
    }
}

private val DOWNLOAD_KEYWORDS = Regex(
    "download",
    RegexOption.IGNORE_CASE,
)

@Composable
private fun PromotedOngoingPillIcon(event: IslandEvent.PromotedOngoing, tint: Color? = null) {
    val hasProgress = event.progress >= 0f || event.isIndeterminate
    val color = tint ?: BlueAccent

    val isDownloadLike = hasProgress && (
        DOWNLOAD_KEYWORDS.containsMatchIn(event.title) ||
            DOWNLOAD_KEYWORDS.containsMatchIn(event.text) ||
            DOWNLOAD_KEYWORDS.containsMatchIn(event.shortText)
    )

    if (isDownloadLike) {
        AnimatedDownloadIcon(color)
    } else if (event.appIcon != null) {
        Image(
            bitmap = event.appIcon.toScaledBitmap(16.dp),
            contentDescription = null,
            modifier = Modifier.size(16.dp).clip(ShapeXs),
            contentScale = ContentScale.Crop,
        )
    } else if (hasProgress) {
        AnimatedDownloadIcon(color)
    } else {
        AnimatedOngoingIcon(color)
    }
}

@Composable
private fun AnimatedDownloadIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "download")
    val bounce by transition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "arrow_bounce",
    )

    Canvas(modifier = Modifier.size(SizeBadge)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val sw = 1.6f.dp.toPx()
        val arrowOffset = bounce.dp.toPx()

        val trayY = size.height * 0.82f
        val trayHalf = size.width * 0.32f
        val trayDepth = SizeStrokeWidth.toPx()
        drawLine(color, Offset(cx - trayHalf, trayY), Offset(cx - trayHalf, trayY + trayDepth), sw, StrokeCap.Round)
        drawLine(color, Offset(cx - trayHalf, trayY + trayDepth), Offset(cx + trayHalf, trayY + trayDepth), sw, StrokeCap.Round)
        drawLine(color, Offset(cx + trayHalf, trayY + trayDepth), Offset(cx + trayHalf, trayY), sw, StrokeCap.Round)

        val arrowTop = size.height * 0.12f + arrowOffset
        val arrowBottom = size.height * 0.62f + arrowOffset
        drawLine(color, Offset(cx, arrowTop), Offset(cx, arrowBottom), sw, StrokeCap.Round)

        val headSize = size.width * 0.22f
        drawLine(color, Offset(cx - headSize, arrowBottom - headSize), Offset(cx, arrowBottom), sw, StrokeCap.Round)
        drawLine(color, Offset(cx + headSize, arrowBottom - headSize), Offset(cx, arrowBottom), sw, StrokeCap.Round)
    }
}

@Composable
private fun PromotedOngoingText(event: IslandEvent.PromotedOngoing, modifier: Modifier, overrideColor: Color? = null) {
    val color = overrideColor ?: BlueAccent
    val base = when {
        event.shortText.isNotEmpty() -> event.shortText
        event.title.isNotEmpty() -> event.title
        event.appName.isNotEmpty() -> event.appName
        else -> ""
    }
    val percent = if (event.progress in 0f..1f) "${(event.progress * 100).toInt()}%" else null
    val label = when {
        base.isNotEmpty() && percent != null -> "$base · $percent"
        base.isNotEmpty() -> base
        percent != null -> percent
        else -> return
    }
    MarqueeLabel(label, color, modifier)
}

@Composable
private fun SportsPillIcon(event: IslandEvent.Sports) {
    val icon = event.team1Icon ?: event.team2Icon ?: event.appIcon
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(16.dp),
            contentDescription = null,
            modifier = Modifier.size(16.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        AnimatedTrophyIcon(accentColorFor(event))
    }
}

@Composable
private fun SportsText(event: IslandEvent.Sports, modifier: Modifier, overrideColor: Color? = null) {
    val color = overrideColor ?: accentColorFor(event)
    val scoreText = when {
        event.score1.isNotEmpty() -> "${event.score1}-${event.score2}"
        event.team2Name.isEmpty() -> event.team1Name
        else -> "vs"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(scoreText, color = color, style = PillAccent, maxLines = 1)
        event.team2Icon?.let { icon ->
            Box(modifier = Modifier.padding(start = 4.dp)) {
                Image(
                    bitmap = icon.toScaledBitmap(14.dp),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

@Composable
private fun AppSwitchPillIcon(event: IslandEvent.AppSwitch) {
    val app = event.previousApp ?: event.recentApps.firstOrNull()
    app?.appIcon?.let {
        Image(
            bitmap = it.toScaledBitmap(16.dp),
            contentDescription = null,
            modifier = Modifier.size(16.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } ?: AnimatedRecentsIcon(SubtleGray)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BiometricUnlockIcon(tint: Color? = null) {
    val color = tint ?: GreenAccent
    val motionScheme = MaterialTheme.motionScheme
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    val sweep by
        animateFloatAsState(
            targetValue = if (started) 360f else 0f,
            animationSpec = motionScheme.defaultSpatialSpec(),
            label = "bio_sweep",
        )
    val checkAlpha by
        animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(250, delayMillis = 500),
            label = "bio_check",
        )

    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val inset = 1.dp.toPx()
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = SizeStrokeThin.dp.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
            )
        }

        if (checkAlpha < 1f) {
            Icon(
                Icons.Filled.Lock,
                null,
                tint = color.copy(alpha = 1f - checkAlpha),
                modifier = Modifier.size(10.dp),
            )
        }
        if (checkAlpha > 0f) {
            Icon(
                Icons.Filled.Check,
                null,
                tint = color.copy(alpha = checkAlpha),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

@Composable
private fun KeyguardIndicationIcon(event: IslandEvent.KeyguardIndication, tint: Color? = null) {
    when (event.indicationType) {
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC -> {
            
            val color = tint ?: GreenAccent
            val transition = rememberInfiniteTransition(label = "kg_bio")
            val sweep by transition.animateFloat(
                initialValue = 60f, targetValue = 300f,
                animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "kg_bio_sweep",
            )
            Canvas(modifier = Modifier.size(SizeBadge)) {
                val inset = 1.dp.toPx()
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = SizeStrokeThin.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                )
            }
        }
        IslandEvent.KeyguardIndication.IndicationType.TRUST -> {
            
            val color = tint ?: IndigoAccent
            Canvas(modifier = Modifier.size(SizeBadge)) {
                val w = size.width; val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.05f)
                    lineTo(w * 0.9f, h * 0.25f)
                    lineTo(w * 0.85f, h * 0.65f)
                    quadraticTo(w * 0.5f, h * 1.0f, w * 0.15f, h * 0.65f)
                    lineTo(w * 0.1f, h * 0.25f)
                    close()
                }
                drawPath(path, color)
            }
        }
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> {
            
            AnimatedBoltIcon(tint ?: OrangeAccent)
        }
        IslandEvent.KeyguardIndication.IndicationType.DISCLOSURE -> {
            
            val color = tint ?: IndigoAccent
            Canvas(modifier = Modifier.size(SizeBadge)) {
                val w = size.width; val h = size.height
                
                drawRect(color, Offset(w * 0.15f, h * 0.3f), Size(w * 0.35f, h * 0.65f))
                drawRect(color, Offset(w * 0.55f, h * 0.1f), Size(w * 0.3f, h * 0.85f))
                
                val windowColor = Color.Black.copy(alpha = AlphaDisabled)
                drawRect(windowColor, Offset(w * 0.25f, h * 0.45f), Size(w * 0.12f, h * 0.1f))
                drawRect(windowColor, Offset(w * 0.25f, h * 0.65f), Size(w * 0.12f, h * 0.1f))
                drawRect(windowColor, Offset(w * 0.62f, h * 0.22f), Size(w * 0.12f, h * 0.1f))
                drawRect(windowColor, Offset(w * 0.62f, h * 0.42f), Size(w * 0.12f, h * 0.1f))
                drawRect(windowColor, Offset(w * 0.62f, h * 0.62f), Size(w * 0.12f, h * 0.1f))
            }
        }
        IslandEvent.KeyguardIndication.IndicationType.OWNER_INFO -> {
            
            val color = tint ?: IndigoAccent
            Canvas(modifier = Modifier.size(SizeBadge)) {
                val w = size.width; val h = size.height
                
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, h * 0.3f))
                
                drawArc(
                    color, startAngle = 0f, sweepAngle = -180f, useCenter = true,
                    topLeft = Offset(w * 0.15f, h * 0.55f),
                    size = Size(w * 0.7f, h * 0.5f),
                )
            }
        }
        IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK -> {
            
            val color = tint ?: GreenAccent
            Canvas(modifier = Modifier.size(SizeBadge)) {
                val w = size.width; val h = size.height
                
                drawRoundRect(color, Offset(w * 0.2f, h * 0.45f), Size(w * 0.6f, h * 0.48f), CornerRadius(SizeStrokeWidth.toPx()))
                
                drawArc(
                    color, startAngle = -180f, sweepAngle = 180f, useCenter = false,
                    style = Stroke(width = SizeStrokeThin.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(w * 0.28f, h * 0.08f),
                    size = Size(w * 0.44f, h * 0.44f),
                )
            }
        }
        IslandEvent.KeyguardIndication.IndicationType.TRANSIENT -> {
            
            val color = tint ?: BlueAccent
            Canvas(modifier = Modifier.size(SizeBadge)) {
                drawCircle(color, style = Stroke(width = SizeStrokeThin.dp.toPx()))
                val cx = size.width / 2f; val cy = size.height / 2f
                drawCircle(Color.White, radius = 0.8.dp.toPx(), center = Offset(cx, cy - 2.2.dp.toPx()))
                drawLine(
                    Color.White, Offset(cx, cy - 0.3.dp.toPx()), Offset(cx, cy + 2.8.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
internal fun PillEventText(
    event: IslandEvent,
    modifier: Modifier,
    notifCount: Int = 0,
    overrideColor: Color? = null,
) {
    when (event) {
        is IslandEvent.AudioRecording -> AudioRecText(event, modifier, overrideColor)
        is IslandEvent.Media -> MediaText(event, modifier, overrideColor)
        is IslandEvent.PromotedOngoing -> PromotedOngoingText(event, modifier, overrideColor)
        is IslandEvent.Sports -> SportsText(event, modifier, overrideColor)
        is IslandEvent.NowPlaying -> MarqueeLabel(
            "${event.songTitle} · ${event.artist}".trimEnd(' ', '·', ' '),
            overrideColor ?: MintAccent,
            modifier,
        )
        is IslandEvent.Bluetooth -> BtText(event, modifier, overrideColor)
        is IslandEvent.Hotspot ->
            MarqueeLabel(
                if (event.numDevices > 0) "${stringResource(R.string.ax_dynamic_bar_hotspot)} · ${stringResource(R.string.ax_dynamic_bar_hotspot_devices, event.numDevices)}"
                else stringResource(R.string.ax_dynamic_bar_hotspot),
                overrideColor ?: TealAccent,
                modifier,
            )
        is IslandEvent.Charging -> MarqueeLabel("${event.level}%", overrideColor ?: GreenAccent, modifier)
        is IslandEvent.Alarm ->
            MarqueeLabel(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) }, overrideColor ?: OrangeAccent, modifier)
        is IslandEvent.Timer -> TimerText(event, modifier, overrideColor)
        is IslandEvent.Stopwatch -> StopwatchText(event, modifier, overrideColor)
        is IslandEvent.RingerMode -> MarqueeLabel(event.label, overrideColor ?: eventStyleFor(event).accent, modifier)
        is IslandEvent.Vpn -> MarqueeLabel(stringResource(R.string.ax_dynamic_bar_vpn_active), overrideColor ?: IndigoAccent, modifier)
        is IslandEvent.Clipboard ->
            MarqueeLabel(event.preview.ifEmpty { stringResource(R.string.ax_dynamic_bar_copied) }, overrideColor ?: IndigoAccent, modifier)
        is IslandEvent.Notification -> {
            val name = event.title
            if (name != null) {
                MarqueeLabel(name, overrideColor ?: BlueAccent, modifier)
            } else {
                NotifBellBadge(modifier, notifCount)
            }
        }
        is IslandEvent.AppSwitch ->
            MarqueeLabel(stringResource(R.string.ax_dynamic_bar_recents), overrideColor ?: SubtleGray, modifier)
        is IslandEvent.Torch -> {
            val label =
                if (event.supportsLevel)
                    "${(event.level.toFloat() / event.maxLevel * 100).toInt()}%"
                else stringResource(R.string.ax_dynamic_bar_on)
            MarqueeLabel(label, overrideColor ?: YellowAccent, modifier)
        }
        is IslandEvent.BiometricUnlock -> MarqueeLabel(stringResource(R.string.ax_dynamic_bar_unlocked), overrideColor ?: GreenAccent, modifier)
        is IslandEvent.KeyguardIndication -> MarqueeLabel(event.text, overrideColor ?: IndigoAccent, modifier)
        is IslandEvent.AospChip -> AospChipText(event, modifier, overrideColor)
    }
}

@Composable
private fun AospChipText(event: IslandEvent.AospChip, modifier: Modifier, overrideColor: Color? = null) {
    val color = overrideColor ?: aospChipAccent(event.active)
    when (val c = event.active.content) {
        is OngoingActivityChipModel.Content.Text -> MarqueeLabel(c.text, color, modifier)
        is OngoingActivityChipModel.Content.Timer -> AospChipTimerText(c, color, modifier)
        is OngoingActivityChipModel.Content.ShortTimeDelta -> AospChipDeltaText(c, color, modifier)
        is OngoingActivityChipModel.Content.Countdown ->
            Text(
                NumberFormat.getIntegerInstance().format(c.secondsUntilStarted),
                color = color,
                style = PillMono,
                modifier = modifier,
            )
        is OngoingActivityChipModel.Content.IconOnly -> {}
    }
}

@Composable
private fun AospChipTimerText(content: OngoingActivityChipModel.Content.Timer, color: Color, modifier: Modifier) {
    var elapsedMs by remember(content.startTimeMs) {
        mutableLongStateOf((SystemClock.elapsedRealtime() - content.startTimeMs).coerceAtLeast(0L))
    }
    LaunchedEffect(content.startTimeMs, content.isEventInFuture) {
        while (true) {
            elapsedMs = if (content.isEventInFuture) {
                (content.startTimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            } else {
                (SystemClock.elapsedRealtime() - content.startTimeMs).coerceAtLeast(0L)
            }
            delay(1000)
        }
    }
    Text(formatElapsedTime(elapsedMs), color = color, style = PillMono, modifier = modifier)
}

@Composable
private fun AospChipDeltaText(content: OngoingActivityChipModel.Content.ShortTimeDelta, color: Color, modifier: Modifier) {
    var text by remember(content.time) { mutableStateOf(formatShortDelta(System.currentTimeMillis() - content.time)) }
    LaunchedEffect(content.time) {
        while (true) {
            text = formatShortDelta(System.currentTimeMillis() - content.time)
            delay(30_000)
        }
    }
    MarqueeLabel(text, color, modifier)
}

private fun formatShortDelta(deltaMs: Long): String {
    val absMs = kotlin.math.abs(deltaMs)
    val mins = absMs / 60_000L
    return when {
        mins < 1L -> "now"
        mins < 60L -> "${mins}m"
        mins < 1440L -> "${mins / 60L}h"
        else -> "${mins / 1440L}d"
    }
}

@Composable
private fun MarqueeLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        style = PillPrimary,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.basicMarquee(iterations = 1),
    )
}

@Composable
private fun AudioRecordingPillIcon(event: IslandEvent.AudioRecording, tint: Color? = null) {
    val color = tint ?: when (event.state) {
        RecordingState.RECORDING -> RedAccent
        RecordingState.PAUSED -> SubtleGray
        RecordingState.SAVED -> GreenAccent
    }
    val vector = when (event.state) {
        RecordingState.RECORDING -> Icons.Filled.Mic
        RecordingState.PAUSED -> Icons.Filled.MicOff
        RecordingState.SAVED -> Icons.Filled.CheckCircle
    }
    if (event.state == RecordingState.RECORDING) {
        val transition = rememberInfiniteTransition(label = "audio_rec")
        val pulseAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = AlphaSubtle,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "audio_rec_alpha",
        )
        Icon(
            vector,
            null,
            tint = color,
            modifier = Modifier.size(SizeBadge).graphicsLayer { this.alpha = pulseAlpha },
        )
    } else {
        Icon(vector, null, tint = color, modifier = Modifier.size(SizeBadge))
    }
}

@Composable
private fun AudioRecText(event: IslandEvent.AudioRecording, modifier: Modifier, overrideColor: Color? = null) {
    when (event.state) {
        RecordingState.RECORDING, RecordingState.PAUSED -> {
            var elapsedMs by remember { mutableLongStateOf(0L) }
            LaunchedEffect(event.startTimeMs, event.state, event.pausedDurationMs) {
                if (event.state == RecordingState.RECORDING) {
                    while (true) {
                        elapsedMs =
                            (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                                .coerceAtLeast(0L)
                        delay(1000)
                    }
                } else {
                    elapsedMs =
                        (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                            .coerceAtLeast(0L)
                }
            }
            val color = overrideColor ?: eventStyleFor(event).accent
            Text(formatElapsedTime(elapsedMs), color = color, style = PillMono, modifier = modifier)
        }
        RecordingState.SAVED -> MarqueeLabel(stringResource(R.string.ax_dynamic_bar_saved), overrideColor ?: GreenAccent, modifier)
    }
}

@Composable
private fun MediaText(event: IslandEvent.Media, modifier: Modifier, overrideColor: Color? = null) {
    val baseColor = overrideColor ?: OrangeAccent
    val alpha = if (event.isPlaying) 1f else AlphaHint
    val color = baseColor.copy(alpha = alpha)
    val text = if (event.artist.isNotBlank()) "${event.track} - ${event.artist}" else event.track
    MarqueeLabel(text, color, modifier.widthIn(max = 66.dp))
}

@Composable
private fun BtText(event: IslandEvent.Bluetooth, modifier: Modifier, overrideColor: Color? = null) {
    val color = overrideColor ?: BlueAccent
    if (event.batteryLevel >= 0) {
        Text(
            "${event.deviceName.take(8)} ${event.batteryLevel}%",
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = modifier.basicMarquee(iterations = 1),
        )
    } else {
        MarqueeLabel(event.deviceName.take(12), color, modifier)
    }
}

@Composable
private fun TimerText(event: IslandEvent.Timer, modifier: Modifier, overrideColor: Color? = null) {
    if (event.endTimeMs > 0L) {
        val color = overrideColor ?: if (event.isPaused) SubtleGray else BlueAccent
        if (event.isPaused) {
            Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
        } else {
            var remainingMs by
                remember(event.endTimeMs) {
                    mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
                }
            LaunchedEffect(event.endTimeMs) {
                while (remainingMs > 0L) {
                    delay(500)
                    remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                }
            }
            Text(formatCountdownLong(remainingMs), color = color, style = PillMono, modifier = modifier)
        }
    } else {
        MarqueeLabel(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }, overrideColor ?: BlueAccent, modifier)
    }
}

@Composable
private fun StopwatchText(event: IslandEvent.Stopwatch, modifier: Modifier, overrideColor: Color? = null) {
    val color = overrideColor ?: if (event.isRunning) MintAccent else SubtleGray
    if (!event.isRunning) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var elapsedMs by
            remember(event.startTimeMs) {
                mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
            }
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
        Text(formatStopwatch(elapsedMs), color = color, style = PillMono, modifier = modifier)
    }
}

@Composable
private fun NotifBellBadge(modifier: Modifier, count: Int) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Notifications, null, tint = BlueAccent, modifier = Modifier.size(SizeBadge))
        if (count > 1) {
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                        .defaultMinSize(minWidth = SpaceLg, minHeight = SpaceLg)
                        .background(RedAccent, RoundedCornerShape(SpaceSm))
                        .padding(horizontal = SpaceXxs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 99) "99+" else "$count",
                    color = chipContentColorOn(RedAccent),
                    style = TsBadge,
                    lineHeight = SpaceLg.value.sp,
                    maxLines = 1,
                )
            }
        }
    }
}


@Composable
fun PulsingDot(color: Color, size: Dp = 8.dp, durationMs: Int = 600, minAlpha: Float = AlphaDisabled) {
    val transition = rememberInfiniteTransition(label = "pulse_${color.value}")
    val alpha by
        transition.animateFloat(
            initialValue = 1f,
            targetValue = minAlpha,
            animationSpec =
                infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse_alpha",
        )
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

@Composable
fun WaveformAnimation(color: Color, modifier: Modifier = Modifier.size(34.dp, 20.dp), isAnimating: Boolean = true, barCount: Int = 4) {
    val phaseState: State<Float>?
    if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "waveform")
        phaseState =
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 2f * PI.toFloat(),
                animationSpec =
                    infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "wave_phase",
            )
    } else {
        phaseState = null
    }
    Canvas(modifier = modifier) {
        val phase = phaseState?.value ?: 0f
        val barW = size.width / (barCount * 2.8f)
        val maxH = size.height * 0.82f
        val staticH = if (!isAnimating) maxH * 0.35f else 0f
        val gap = (size.width - barCount * barW) / (barCount + 1)
        for (i in 0 until barCount) {
            val x = gap + i * (barW + gap) + barW / 2f
            val h = if (isAnimating) maxH * (0.22f + 0.78f * ((sin(phase + i * 0.9f) + 1f) / 2f)) else staticH
            val y = (size.height - h) / 2f
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + h),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

