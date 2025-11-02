/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import com.android.internal.graphics.ColorUtils
import com.android.systemui.SystemUIApplication
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaScrimState.STATE_SCRIM_HIDDEN
import com.android.systemui.media.MediaScrimState.STATE_SCRIM_VISIBLE
import com.android.systemui.util.ScrimUtils

import kotlinx.coroutines.*
import kotlin.math.*
import javax.inject.Inject

enum class MediaScrimState {
    STATE_SCRIM_VISIBLE,
    STATE_SCRIM_HIDDEN
}

@SysUISingleton
class MediaViewController @Inject constructor(
    private val context: Context
) : MediaSessionManager.MediaDataListener, ScrimUtils.ScrimEventListener {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var scrimState = STATE_SCRIM_HIDDEN

    private var listening = false
    private var featureEnabled = false
    private var artworkDrawable: Drawable? = null
    private var isMediaPlaying = false
    private var bouncerShowingOrKeyguardDismissing = false

    private var mediaFilter = 0
    private var mediaFadeLevel = 40
    private var mediaBlurLevel = 200

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateSettings()
        }
    }

    private val mediaScrim = ImageView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private var mediaArtJob: Job? = null
    private var isAlbumArtVisible = false
    private var dismissingKeyguard = false
    private var coalesceJob: Job? = null

    private val sharedTypedValue = TypedValue()

    init {
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_ENABLED),
            false,
            settingsObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_FILTER),
            false,
            settingsObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_FADE_LEVEL),
            false,
            settingsObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_BLUR_LEVEL),
            false,
            settingsObserver
        )

        updateSettings()
    }

    private fun updateSettings() {
        featureEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_ENABLED,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        mediaFilter = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_FILTER,
            0,
            UserHandle.USER_CURRENT
        )

        mediaFadeLevel = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_FADE_LEVEL,
            40,
            UserHandle.USER_CURRENT
        )

        mediaBlurLevel = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_BLUR_LEVEL,
            200,
            UserHandle.USER_CURRENT
        ).coerceIn(0, 600)


        if (!featureEnabled) {
            cleanupResources(false)
        }

        if (featureEnabled && !listening) {
            MediaSessionManager.get().addListener(this)
            ScrimUtils.get().addListener(this)
            listening = true
        } else if (!featureEnabled && listening) {
            MediaSessionManager.get().removeListener(this)
            ScrimUtils.get().removeListener(this)
            listening = false
        }
    }

    private fun setupMediaFilter() {
        val effect = when (mediaFilter) {
            1 -> RenderEffect.createBlurEffect(
                mediaBlurLevel.toFloat(), 
                mediaBlurLevel.toFloat(), 
                Shader.TileMode.MIRROR
            )
            2 -> {
                val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(grayMatrix))
            }
            3 -> {
                val sepiaMatrix = ColorMatrix().apply { setSaturation(0f) }
                val sepia = floatArrayOf(
                    1f, 0f, 0f, 0f, 30f,
                    0f, 1f, 0f, 0f, 20f,
                    0f, 0f, 1f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
                sepiaMatrix.postConcat(ColorMatrix(sepia))
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sepiaMatrix))
            }
            4 -> null
            5 -> {
                val invertMatrix = floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(invertMatrix)))
            }
            6 -> {
                val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                val grayscaleEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(grayMatrix))
                val blurEffect = RenderEffect.createBlurEffect(
                    mediaBlurLevel.toFloat(),
                    mediaBlurLevel.toFloat(),
                    Shader.TileMode.MIRROR
                )
                RenderEffect.createChainEffect(blurEffect, grayscaleEffect)
            }
            else -> null
        }

        mediaScrim.setRenderEffect(effect)
        if (mediaFilter == 4) {
            context.theme.resolveAttribute(android.R.attr.colorAccent, sharedTypedValue, true)
            val accent = sharedTypedValue.data
            mediaScrim.colorFilter = PorterDuffColorFilter(
                Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)),
                PorterDuff.Mode.SRC_ATOP
            )
        } else {
            mediaScrim.colorFilter = null
        }
    }

    private fun shouldShowMediaArt(): Boolean {
        if (!featureEnabled) return false
        if (artworkDrawable == null) return false
        val isPortrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val isKeyguard = ScrimUtils.get().isKeyguardShowing()
        val isCollapsed = ScrimUtils.get().isPanelFullyCollapsed()
        if (!isPortrait || !isKeyguard || !isCollapsed) return false
        if (!isMediaPlaying) return false
        if (bouncerShowingOrKeyguardDismissing) return false
        return true
    }

    private fun cancelScrimAnim() {
        mediaScrim.animate().setListener(null).cancel()
    }

    private fun showMediaArt() {
        if (isAlbumArtVisible && scrimState == STATE_SCRIM_VISIBLE) return
        if (dismissingKeyguard) return
        cancelScrimAnim()
        updateMediaArt()
        setupMediaFilter()
        mediaScrim.apply {
            alpha = 0f
            visibility = View.VISIBLE
            scrimState = STATE_SCRIM_VISIBLE
            animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
                .start()
        }
        isAlbumArtVisible = true
    }

    private fun updateMediaArt() {
        coalesceJob?.cancel()
        coalesceJob = coroutineScope.launch {
            delay(40)
            mediaArtJob?.cancel()
            mediaArtJob = launch(Dispatchers.Default) {
                val drawable = try {
                    processArtwork()
                } catch (t: Throwable) {
                    Log.w(TAG, "processArtwork failed", t)
                    LayerDrawable(arrayOf())
                }
                withContext(Dispatchers.Main) {
                    mediaScrim.setImageDrawable(null)
                    mediaScrim.setImageDrawable(drawable)
                }
            }
        }
    }

    private suspend fun processArtwork(): LayerDrawable = withContext(Dispatchers.Default) {
        if (!featureEnabled) return@withContext LayerDrawable(arrayOf())
        val drawable = artworkDrawable ?: return@withContext LayerDrawable(arrayOf())

        val bitmap = drawableToBitmap(drawable)
        val resizedBitmap = getResizedBitmap(bitmap)

        val processedBitmap = resizedBitmap

        val bitmapDrawable = BitmapDrawable(context.resources, processedBitmap).apply {
            alpha = 255
        }

        val fadeColor = ColorUtils.blendARGB(
            Color.TRANSPARENT,
            Color.BLACK,
            mediaFadeLevel / 100f
        )

        val overlay = ColorDrawable(fadeColor)

        val layers = arrayOf<Drawable>(bitmapDrawable, overlay)
        return@withContext LayerDrawable(layers).apply {
            setBounds(0, 0, processedBitmap.width, processedBitmap.height)
            setLayerInset(1, 0, 0, 0, 0)
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.let { return it }
        }

        val w = max(1, drawable.intrinsicWidth)
        val h = max(1, drawable.intrinsicHeight)

        val cfg = if (drawable.opacity == PixelFormat.OPAQUE) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }
        val bitmap = Bitmap.createBitmap(w, h, cfg)
        val canvas = Canvas(bitmap)
        val oldBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        drawable.setBounds(oldBounds)
        return bitmap
    }

    private fun getResizedBitmap(bitmap: Bitmap): Bitmap {
        val displayBounds = context.getSystemService(WindowManager::class.java)
            .currentWindowMetrics.bounds

        val ratioW = displayBounds.width() / bitmap.width.toFloat()
        val ratioH = displayBounds.height() / bitmap.height.toFloat()
        val ratio = max(ratioH, ratioW)

        val desiredHeight = (ratio * bitmap.height).roundToInt().coerceAtLeast(1)
        val desiredWidth = (ratio * bitmap.width ).roundToInt().coerceAtLeast(1)

        if (abs(desiredWidth - bitmap.width) <= 1 && abs(desiredHeight - bitmap.height) <= 1) {
            val xPixelShift = max((bitmap.width - displayBounds.width()) / 2, 0)
            val yPixelShift = max((bitmap.height - displayBounds.height()) / 2, 0)
            val cropWidth = min(displayBounds.width(), bitmap.width - xPixelShift)
            val cropHeight = min(displayBounds.height(), bitmap.height - yPixelShift)
            return Bitmap.createBitmap(bitmap, xPixelShift, yPixelShift, cropWidth, cropHeight)
        }

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, desiredWidth, desiredHeight, true)

        val xPixelShift = max((desiredWidth - displayBounds.width()) / 2, 0)
        val yPixelShift = max((desiredHeight - displayBounds.height()) / 2, 0)
        val cropWidth = min(displayBounds.width(), scaledBitmap.width - xPixelShift)
        val cropHeight = min(displayBounds.height(), scaledBitmap.height - yPixelShift)

        val cropped = Bitmap.createBitmap(
            scaledBitmap,
            xPixelShift,
            yPixelShift,
            cropWidth,
            cropHeight
        )
        if (scaledBitmap != cropped && !scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
        }
        return cropped
    }

    private fun clearResources() {
        mediaScrim.setImageDrawable(null)
        mediaScrim.setRenderEffect(null)
        mediaScrim.colorFilter = null
        mediaScrim.visibility = View.GONE
        scrimState = STATE_SCRIM_HIDDEN
        isAlbumArtVisible = false
        dismissingKeyguard = false
    }

    fun cleanupResources(animate: Boolean) {
        if (dismissingKeyguard) return
        dismissingKeyguard = true
        mediaArtJob?.cancel()
        cancelScrimAnim()
        if (!animate) {
            clearResources()
            return;
        }
        mediaScrim.animate()
            .alpha(0f)
            .setDuration(100)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearResources()
                }
            })
            .start()
    }

    override fun onAlbumArtChanged(drawable: Drawable) {
        coroutineScope.launch {
            artworkDrawable = drawable
            onMediaStateChanged()
            if (scrimState == STATE_SCRIM_VISIBLE) {
                updateMediaArt()
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        isMediaPlaying = state == PlaybackState.STATE_PLAYING
        coroutineScope.launch {
            if (!isMediaPlaying) artworkDrawable = null
            onMediaStateChanged()
        }
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        if (showing) {
            cleanupResources(true)
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) {
            cleanupResources(true)
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) {
            cleanupResources(true)
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    override fun onBarStateChanged(state: Int) {
        coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (showing) {
            dismissingKeyguard = false
        } else {
            cleanupResources(false)
        }
        coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    override fun onScreenTurnedOff() {
        cleanupResources(false)
    }

    override fun onStartedWakingUp() {
        coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    private suspend fun onMediaStateChanged() {
        val show = shouldShowMediaArt()
        if (show && scrimState == STATE_SCRIM_HIDDEN) {
            showMediaArt()
        } else if (!show && scrimState == STATE_SCRIM_VISIBLE) {
            cleanupResources(true)
        }
    }

    fun getMediaArtScrim() = mediaScrim

    fun albumArtVisible() = isAlbumArtVisible

    fun setSubjectAlpha(alpha: Float) {
        mediaScrim.post {
            mediaScrim.alpha = alpha.coerceIn(0f, 1f)
        }
    }

    fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
        if (listening) {
            MediaSessionManager.get().removeListener(this)
            ScrimUtils.get().removeListener(this)
            listening = false
        }
        mediaArtJob?.cancel()
        mediaScrim.setImageDrawable(null)
        mediaScrim.setRenderEffect(null)
        mediaScrim.colorFilter = null
        artworkDrawable = null
        isAlbumArtVisible = false
        coalesceJob?.cancel()
        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "MediaViewController"
        @JvmStatic
        fun get(context: Context): MediaViewController {
            val app = context.applicationContext as SystemUIApplication
            return app.sysUIComponent.mediaViewController()
        }
    }
}
