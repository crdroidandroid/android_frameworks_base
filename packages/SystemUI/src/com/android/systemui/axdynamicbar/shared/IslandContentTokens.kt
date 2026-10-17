@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.shared

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.axion.blur.axBlurBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.res.R

internal val SpaceXxs = 2.dp
internal val SpaceXs = 4.dp
internal val SpaceSm = 6.dp
internal val SpaceMd = 8.dp
internal val SpaceLg = 12.dp
internal val SpaceXl = 14.dp
internal val SpaceXxl = 16.dp
internal val SpaceSection = 20.dp
internal val SpacePanel = 24.dp
internal val SpacePanelLarge = 28.dp

internal val RadiusXs = 8.dp
internal val RadiusSm = 12.dp
internal val RadiusLg = 24.dp
internal val RadiusXl = 32.dp
internal val RadiusCard = 28.dp

internal val ShapeXs = RoundedCornerShape(RadiusXs)
internal val ShapeSm = RoundedCornerShape(RadiusSm)
internal val ShapeLg = RoundedCornerShape(RadiusLg)
internal val ShapeXl = RoundedCornerShape(RadiusXl)
internal val ShapeCard = RoundedCornerShape(RadiusCard)

internal val ShapeIconLarge = RoundedCornerShape(20.dp)
internal val ShapeIconMedium = RoundedCornerShape(16.dp)
internal val ShapeAlbum = RoundedCornerShape(16.dp)
internal val ShapeChip = RoundedCornerShape(percent = 50)
internal val ShapeCompact = RoundedCornerShape(12.dp)

internal val SizeBadge = 14.dp
internal val SizeIconSm = 20.dp
internal val SizeIconMd = 28.dp
internal val SizeButton = 48.dp
internal val SizeButtonLg = 48.dp
internal val SizeButtonXl = 64.dp
internal val SizeAlbumSm = 52.dp
internal val SizeAlbumLg = 200.dp
internal val SizeSeekHeight = 16.dp
internal val SizeProgressHeight = 8.dp
internal val SizeStrokeWidth = 2.dp
internal val SizeStrokeThin = 1.5f
internal val SizeCompactIcon = 40.dp
internal val SizeActionHeight = 48.dp

/** The leading icon slot in an expanded tile: a small rounded badge, not a column of its own. */
internal val SizeIconBadge = 28.dp

/** A compact tile action — circular and icon-only, small enough to sit in the header row. */
internal val SizeActionIcon = 36.dp

internal val ExpandedMaxWidth = 420.dp

internal const val AlphaSecondary = 0.7f
internal const val AlphaTertiary = 0.5f
internal const val AlphaHint = 0.4f
internal const val AlphaDisabled = 0.3f
internal const val AlphaSubtle = 0.15f
internal const val AlphaFaint = 0.1f
internal const val AlphaBorder = 0.08f
internal const val AlphaStatusChip = 0.14f
internal const val AlphaIconBg = 0.16f
internal const val AlphaTrack = 0.25f

/**
 * A wash strong enough to read as a filled control without going solid — the primary of two
 * accent washes that sit side by side (play/pause next to skip).
 */
internal const val AlphaAccent = 0.30f

/**
 * Opacity of a chip's coloured frost (the tint passed to [islandBlur]) and of solid tiles inside
 * a glass card: enough to carry colour and keep a status-bar label legible, thin enough to frost.
 */
internal const val AlphaGlass = 0.72f

/**
 * Opacity of every [islandBlur] surface below this point in the tree.
 *
 * The cross-window blur is drawn by a platform drawable, which a Compose `graphicsLayer { alpha }`
 * or an `AnimatedVisibility` fade above it does not reach. Containers that fade (the expanded
 * panel, the alert card, a card being swiped away) publish their alpha here instead; nested
 * providers multiply into the parent value rather than replace it.
 */
internal val LocalBlurAlpha = compositionLocalOf { 1f }

/** Rim width for the small glass pills; the 1 dp card rim would read as an outline at chip size. */
internal val ChipHighlightWidth = 0.5.dp

/**
 * Frosted glass for one island surface, drawn behind the content of the node it's applied to.
 * Apply it after the surface's `clip` and before padding, so it covers the full surface.
 *
 * [cornerRadius] must match the shape the surface clips to. [fallbackColor] is painted instead
 * wherever the platform won't blur (blur disabled, battery saver, unsupported device). [tint] is
 * the frost laid over the blur: null takes the system frost; chips pass their own colour so the
 * frost *is* the chip's colour instead of a second fill stacked on top of it. The rim highlight
 * replaces a separate border.
 */
@Composable
internal fun Modifier.islandBlur(
    cornerRadius: Dp,
    fallbackColor: Color,
    tint: Color? = null,
    alpha: Float = LocalBlurAlpha.current,
    highlightWidth: Dp = AxBlurSurfaceDefaults.HighlightWidth,
): Modifier =
    axBlurBackground(
        enabled = true,
        fallbackColor = fallbackColor,
        tintColor = tint ?: AxBlurSurfaceDefaults.tintColor(),
        cornerRadius = cornerRadius,
        alpha = alpha,
        highlight = AxBlurSurfaceDefaults.highlightBrush(),
        highlightWidth = highlightWidth,
    )

/**
 * Emphasis tone for an island event.
 *
 * [eventStyleFor] returns one of these instead of a fixed colour, so every hue the island draws
 * comes from the user's wallpaper-derived scheme:
 *
 * - [ACCENT]    event identity (most events)
 * - [POSITIVE]  success / active / done — charging, recording saved, unlocked, validated VPN, calls
 * - [ATTENTION] needs notice — alarm ringing, vibrate/silent, unvalidated VPN, paused capture
 * - [ALERT]     privacy or danger — mic/screen capture in progress, low battery, hard errors
 *
 * Events sharing a tone are told apart by icon, motion and chip tier.
 */
internal enum class IslandTone { ACCENT, POSITIVE, ATTENTION, ALERT }

/** Saturated role: icon/text tints and translucent washes (`tint.copy(alpha = …)`). */
internal val IslandTone.tint: Color
    @Composable
    get() = when (this) {
        IslandTone.ACCENT -> MaterialTheme.colorScheme.primary
        IslandTone.POSITIVE -> MaterialTheme.colorScheme.secondary
        IslandTone.ATTENTION -> MaterialTheme.colorScheme.tertiary
        IslandTone.ALERT -> MaterialTheme.colorScheme.error
    }

/** Content legible on the saturated [tint] itself, for glyphs drawn inside a filled shape. */
internal val IslandTone.onTint: Color
    @Composable
    get() = when (this) {
        IslandTone.ACCENT -> MaterialTheme.colorScheme.onPrimary
        IslandTone.POSITIVE -> MaterialTheme.colorScheme.onSecondary
        IslandTone.ATTENTION -> MaterialTheme.colorScheme.onTertiary
        IslandTone.ALERT -> MaterialTheme.colorScheme.onError
    }

/** Filled container role: for chips that paint the tone as their background. */
internal val IslandTone.container: Color
    @Composable
    get() = when (this) {
        IslandTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
        IslandTone.POSITIVE -> MaterialTheme.colorScheme.secondaryContainer
        IslandTone.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
        IslandTone.ALERT -> MaterialTheme.colorScheme.errorContainer
    }

/** Content legible on [container]. Never pair it with [tint] — that is what [onTint] is for. */
internal val IslandTone.onContainer: Color
    @Composable
    get() = when (this) {
        IslandTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        IslandTone.POSITIVE -> MaterialTheme.colorScheme.onSecondaryContainer
        IslandTone.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
        IslandTone.ALERT -> MaterialTheme.colorScheme.onErrorContainer
    }

internal val SubtleGray: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val CardBg: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceBright
internal val DarkCard: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val OnCardText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
internal val OnCardSecondary: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val ActionBg: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val OnActionText: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary
internal val DestructiveBg: Color
    @Composable get() = MaterialTheme.colorScheme.errorContainer
internal val OnDestructiveText: Color
    @Composable get() = MaterialTheme.colorScheme.onErrorContainer

/** Keyguard battery chip icon roles. Monet-derived — no fixed hues. */
internal val BatteryChargingColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val BatteryPowerSaveColor: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

internal val CardBorderBrush: Brush
    @Composable
    get() =
        Brush.verticalGradient(
            colors =
                listOf(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
                )
        )


/** The tone an event is drawn in. */
internal fun toneFor(event: IslandEvent): IslandTone = eventStyleFor(event).tone

/** How much room an event's chip takes. */
internal fun tierFor(event: IslandEvent): ChipTier = eventStyleFor(event).tier

/** Saturated accent for [event]: icon/text tints and translucent washes. */
@Composable internal fun accentColorFor(event: IslandEvent): Color = toneFor(event).tint

/** Filled-container role for [event], for chips that paint the accent as their background. */
@Composable internal fun chipAccentColorFor(event: IslandEvent): Color = toneFor(event).container

/** Content role legible on [chipAccentColorFor]. */
@Composable internal fun chipContentColorFor(event: IslandEvent): Color = toneFor(event).onContainer

/**
 * Colour set for one expanded / keyguard surface, resolved from the event's [IslandTone] against
 * the user's scheme.
 */
internal data class IslandColorScheme(
    /** The saturated fill. */
    val accent: Color,
    /** Content legible *on* [accent] (the tone's `onTint`, not `onContainer`). */
    val onAccent: Color,
    /** A faint wash of [accent], for tinting a surface rather than filling it. */
    val tonal: Color,
    /** A slightly stronger wash, kept for callers that tint a whole panel. */
    val surfaceTint: Color,
)

@Composable
internal fun rememberIslandColors(event: IslandEvent): IslandColorScheme {
    val tone = toneFor(event)
    val tint = tone.tint
    return IslandColorScheme(
        accent = tint,
        onAccent = tone.onTint,
        tonal = tint.copy(alpha = AlphaFaint),
        surfaceTint = tint.copy(alpha = 0.12f),
    )
}

/** Media surfaces use the event's own tone; album-art colour extraction is gone. */
@Composable
internal fun rememberMediaColors(event: IslandEvent.Media): IslandColorScheme =
    rememberIslandColors(event)

/*
 * Height carries urgency, corner radius carries kind. The status bar is only ~24–32 dp tall, so
 * the tiers stay inside it: a passive chip is the smallest, a live one the tallest that still fits.
 */
internal val ChipTierPassiveHeight = 22.dp
internal val ChipTierActiveHeight = 24.dp
internal val ChipTierLiveHeight = 28.dp

/** Less than half of [ChipTierLiveHeight], so a live chip reads as a squircle, not a pill. */
internal val ChipTierLiveCorner = 10.dp

internal val ChipTier.chipHeight: Dp
    get() = when (this) {
        ChipTier.PASSIVE -> ChipTierPassiveHeight
        ChipTier.ACTIVE -> ChipTierActiveHeight
        ChipTier.LIVE -> ChipTierLiveHeight
    }

/** Fixed radii rather than percentages, so the corner animates smoothly between tiers. */
internal val ChipTier.chipCornerRadius: Dp
    get() = when (this) {
        ChipTier.PASSIVE -> ChipTierPassiveHeight / 2
        ChipTier.ACTIVE -> ChipTierActiveHeight / 2
        ChipTier.LIVE -> ChipTierLiveCorner
    }

/** Micro badge — a count inside a chip. Below the M3 label floor by design. */
internal val TsBadge: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmallEmphasized.copy(
        fontSize = 8.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

internal val PillPrimary: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmallEmphasized.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

internal val PillAccent: TextStyle
    @Composable get() = MaterialTheme.typography.labelLargeEmphasized

/** Title over a caption, as in the keyguard media chip. */
internal val PillTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleSmallEmphasized

/** Second line under [PillTitle]; 10 sp is below the M3 label floor by design. */
internal val TsCaption: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmallEmphasized.copy(
        fontSize = 10.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

/** Mono so ticking digits don't make the readout jitter. */
internal val PillMono: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmallEmphasized.copy(
        fontFamily = FontFamily.Monospace,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

internal val TsMono: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)

/** A ticking number inside an expanded row. */
internal val TsCounter: TextStyle
    @Composable get() = MaterialTheme.typography.labelLargeEmphasized.copy(
        fontFamily = FontFamily.Monospace,
    )

/** Headline value of a compact expanded tile (countdown, stopwatch, recording time). */
internal val TsValue: TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = FontFamily.Monospace,
    )

@Composable
internal fun StatusChip(text: String, color: Color = SubtleGray) {
    Box(
        modifier =
            Modifier.background(color.copy(alpha = AlphaSubtle), ShapeChip)
                .padding(horizontal = SpaceMd, vertical = SpaceXxs)
    ) {
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelSmallEmphasized)
    }
}

@Composable
internal fun CircleButton(
    color: Color = ActionBg,
    size: Dp = 48.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) { content() }
    }
}

/**
 * The expanded tile shell: a compact header row — icon badge, title column, optional trailing
 * control — over an optional action row. The header is a plain row, not a second tinted surface
 * nested inside the frosted card the tile already sits on.
 */
@Composable
internal fun ExpandedCardLayout(
    accentColor: Color,
    icon: @Composable () -> Unit,
    iconSize: Dp = SizeIconBadge,
    iconBackground: Boolean = true,
    title: @Composable ColumnScope.() -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    actions: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceSm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Box(
                modifier =
                    if (iconBackground) {
                        Modifier.size(iconSize)
                            .background(accentColor.copy(alpha = AlphaIconBg), ShapeCompact)
                    } else {
                        Modifier.size(iconSize)
                    },
                contentAlignment = Alignment.Center,
            ) { icon() }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXxs),
            ) { title() }
            trailing?.invoke()
        }
        actions?.invoke(this)
    }
}

@Composable
internal fun ActionChip(
    label: String,
    icon: ImageVector? = null,
    color: Color = OnActionText,
    bg: Color = ActionBg,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val height = if (compact) SizeActionIcon else SizeActionHeight
    val horizontal = if (compact) SpaceLg else SpacePanel
    Surface(onClick = onClick, shape = ShapeChip, color = bg, modifier = modifier) {
        Row(
            modifier = Modifier.height(height).padding(horizontal = horizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(SizeIconSm))
            }
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Circular, icon-only tile action that fits in a tile's header row. */
@Composable
internal fun ActionIconButton(
    icon: ImageVector,
    tint: Color,
    bg: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = SizeActionIcon,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = bg, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(SizeIconSm))
        }
    }
}

/**
 * Icon for a classified notification action, or null when there is no unambiguous glyph — the
 * caller then keeps the action's own label rather than inventing a meaning.
 *
 * Exhaustive over [NotificationActionType] so a new constant fails the build instead of
 * silently falling back to a label.
 */
internal val NotificationActionType.icon: ImageVector?
    get() =
        when (this) {
            NotificationActionType.PAUSE -> Icons.Filled.Pause
            NotificationActionType.RESUME -> Icons.Filled.PlayArrow
            NotificationActionType.STOP -> Icons.Filled.Stop
            NotificationActionType.RESET -> Icons.Filled.Refresh
            NotificationActionType.LAP -> Icons.Filled.Flag
            NotificationActionType.ADD_MINUTE -> Icons.Filled.MoreTime
            NotificationActionType.SNOOZE -> Icons.Filled.Snooze
            NotificationActionType.DELETE -> Icons.Filled.Delete
            NotificationActionType.DISMISS -> Icons.Filled.Close
            NotificationActionType.OTHER -> null
        }

internal data class ActionVisuals(val bg: Color, val content: Color)

/** Destructive commands take the error roles, resume the positive one, the rest the event tone. */
@Composable
internal fun NotificationActionType.visuals(tone: IslandTone): ActionVisuals =
    when (this) {
        NotificationActionType.STOP,
        NotificationActionType.DELETE -> ActionVisuals(DestructiveBg, OnDestructiveText)
        NotificationActionType.RESUME -> ActionVisuals(IslandTone.POSITIVE.container, IslandTone.POSITIVE.onContainer)
        else -> ActionVisuals(tone.tint.copy(alpha = AlphaIconBg), tone.tint)
    }

/**
 * One action forwarded from an event's notification: an [ActionIconButton] when the classifier
 * recognises it, a compact labelled [ActionChip] when it doesn't.
 */
@Composable
internal fun NotificationActionButton(
    action: Notification.Action,
    label: String,
    tone: IslandTone,
    modifier: Modifier = Modifier,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    // classify() goes through the package manager; don't repeat it every tick of a running timer.
    val kind =
        remember(action) {
            action.classify(context, action.actionIntent?.creatorPackage ?: context.packageName)
        }
    val icon = kind.icon
    if (icon != null) {
        val visuals = kind.visuals(tone)
        ActionIconButton(
            icon = icon,
            tint = visuals.content,
            bg = visuals.bg,
            contentDescription = label,
            modifier = modifier,
            onClick = onSend,
        )
    } else {
        ActionChip(
            label = label,
            color = tone.tint,
            bg = tone.tint.copy(alpha = AlphaIconBg),
            modifier = modifier,
            compact = true,
            onClick = onSend,
        )
    }
}

/** Most actions a tile carries in its header row before they move to a row of their own. */
internal const val MAX_INLINE_ACTIONS = 2

/**
 * Whether a tile's forwarded actions can sit inline in its header row: only when there are few of
 * them and every one resolves to an icon. A single labelled fallback chip in the header squeezes
 * the title to nothing, so any unrecognised action sends the whole set to an action row instead.
 */
@Composable
internal fun rememberActionsFitInline(actions: List<Notification.Action>): Boolean {
    val context = LocalContext.current
    return remember(actions) {
        actions.size <= MAX_INLINE_ACTIONS &&
            actions.all { action ->
                action.classify(context, action.actionIntent?.creatorPackage ?: context.packageName)
                    .icon != null
            }
    }
}

@Composable
internal fun ExpressivePillButton(
    label: String,
    icon: ImageVector? = null,
    contentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.height(SizeActionHeight).padding(horizontal = SpacePanel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            if (icon != null) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(SizeIconSm))
            }
            Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal data class MediaProgress(val progress: Float, val positionMs: Long)

@Composable
internal fun rememberMediaProgress(event: IslandEvent.Media): MediaProgress {
    return MediaProgress(event.progress, event.position)
}

internal fun formatElapsedTime(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(secs / 60, secs % 60)
}

internal fun formatCountdownSeconds(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

internal fun formatCountdownLong(ms: Long): String {
    val secs = (ms / 1000).coerceAtLeast(0)
    val mins = secs / 60
    val hrs = mins / 60
    return if (hrs > 0) "%02d:%02d:%02d".format(hrs, mins % 60, secs % 60)
    else "%02d:%02d".format(mins, secs % 60)
}

internal fun formatStopwatch(ms: Long): String {
    val totalSecs = (ms / 1000).coerceAtLeast(0)
    val hrs = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    val tenths = ((ms % 1000) / 100).coerceIn(0, 9)
    return if (hrs > 0) "%d:%02d:%02d.%d".format(hrs, mins, secs, tenths)
    else "%d:%02d.%d".format(mins, secs, tenths)
}

internal fun formatTimeAgo(timestampMs: Long, res: android.content.res.Resources): String {
    val diff = System.currentTimeMillis() - timestampMs
    if (diff < 0) return ""
    val secs = diff / 1000
    val mins = secs / 60
    val hrs = mins / 60
    val days = hrs / 24
    return when {
        secs < 60 -> res.getString(R.string.ax_dynamic_bar_just_now)
        mins < 60 -> res.getString(R.string.ax_dynamic_bar_mins_ago, mins.toInt())
        hrs < 24 -> res.getString(R.string.ax_dynamic_bar_hours_ago, hrs.toInt())
        days < 7 -> res.getString(R.string.ax_dynamic_bar_days_ago, days.toInt())
        else -> res.getString(R.string.ax_dynamic_bar_weeks_ago, (days / 7).toInt())
    }
}

@Composable
internal fun Drawable.toScaledBitmap(sizeDp: Dp): ImageBitmap {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }
    return remember(this, px) { toBitmap(px, px).asImageBitmap() }
}

/**
 * Square, centre-cropped bitmap of this drawable. Album art and artwork are rarely square, and
 * [toScaledBitmap] stretches them to fit; `ContentScale.Crop` can't undo a stretch that already
 * happened in the bitmap.
 */
@Composable
internal fun Drawable.toSquareScaledBitmap(sizeDp: Dp): ImageBitmap {
    val px = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(1)
    return remember(this, px) { centerCropSquare(this, px).asImageBitmap() }
}

private fun centerCropSquare(drawable: Drawable, px: Int): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        val src = drawable.bitmap
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(left, top, left + side, top + side), Rect(0, 0, px, px), null)
        return out
    }
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0) return drawable.toBitmap(px, px)
    val scale = maxOf(px.toFloat() / w, px.toFloat() / h)
    val dw = (w * scale).toInt()
    val dh = (h * scale).toInt()
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    // Bounds are shared drawable state; restore them so other users of this drawable are unaffected.
    val oldBounds = Rect(drawable.bounds)
    drawable.setBounds((px - dw) / 2, (px - dh) / 2, (px - dw) / 2 + dw, (px - dh) / 2 + dh)
    drawable.draw(Canvas(out))
    drawable.bounds = oldBounds
    return out
}

internal fun chipProgressFor(event: IslandEvent): Float? =
    when (event) {
        is IslandEvent.Media ->
            if (event.duration > 0) (event.position.toFloat() / event.duration).coerceIn(0f, 1f)
            else null
        is IslandEvent.PromotedOngoing ->
            if (event.progress >= 0f) event.progress.coerceIn(0f, 1f) else null
        is IslandEvent.Notification ->
            if (event.progress >= 0 && event.progressMax > 0)
                (event.progress.toFloat() / event.progressMax).coerceIn(0f, 1f)
            else null
        else -> null
    }

internal fun iconKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> event.albumArt?.hashCode() ?: "media_default"
        is IslandEvent.Notification -> event.appIcon?.hashCode() ?: "notif_default"
        is IslandEvent.AppSwitch -> {
            val app = event.previousApp ?: event.recentApps.firstOrNull()
            app?.appIcon?.hashCode() ?: "app_default"
        }
        else -> event::class.simpleName ?: "default"
    }

internal fun textKeyFor(event: IslandEvent): Any =
    when (event) {
        is IslandEvent.Media -> "${event.track}|${event.artist}"
        is IslandEvent.Timer,
        is IslandEvent.Stopwatch,
        is IslandEvent.AudioRecording -> "tick_text"
        else -> event.id
    }

internal fun resolveLabelIcon(label: String): ImageVector {
    val lower = label.lowercase()
    return when {
        lower.contains("shuffle") -> Icons.Filled.Shuffle
        lower.contains("repeat") -> Icons.Filled.Repeat
        lower.contains("thumb") && lower.contains("up") -> Icons.Filled.ThumbUp
        lower.contains("thumb") && lower.contains("down") -> Icons.Filled.ThumbDown
        lower.contains("like") || lower.contains("love") || lower.contains("favorite") -> Icons.Filled.Favorite
        else -> Icons.Filled.Shuffle
    }
}

@Composable
internal fun CustomActionIcon(
    ca: IslandEvent.MediaCustomAction,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val appBitmap = ca.icon?.let { drawable ->
        remember(drawable) {
            try { drawable.toBitmap(48, 48).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    if (appBitmap != null) {
        Icon(appBitmap, ca.label, tint = tint, modifier = modifier)
    } else {
        Icon(resolveLabelIcon(ca.label), ca.label, tint = tint, modifier = modifier)
    }
}

internal fun PendingIntent.sendWithBal(context: Context, fillIntent: Intent? = null) {
    val options = ActivityOptions.makeBasic()
    options.setPendingIntentBackgroundActivityStartMode(
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    )
    send(context, 0, fillIntent, null, null, null, options.toBundle())
}
