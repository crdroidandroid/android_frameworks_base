package com.google.android.systemui.ambientmusic

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Trace
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AmbientIndicationAnimationHelper {

    fun animateActionButtonsAlphaWithSpring(
        buttonView: View,
        iconView: View,
        actionContainer: View,
        targetAlpha: Float,
        iconAnimThreshold: Float,
    ) {
        if (buttonView.visibility == View.GONE) return

        val isFadingIn = targetAlpha == 1.0f
        val hasTriggeredIconAnim = AtomicBoolean(false)
        val springForce =
            if (isFadingIn) {
                AmbientIndicationAnimationUtils.defaultEffectsSpec
            } else {
                AmbientIndicationAnimationUtils.fastEffectsSpec
            }

        val updateListener =
            object : DynamicAnimation.OnAnimationUpdateListener {
                override fun onAnimationUpdate(
                    animation: DynamicAnimation<*>,
                    value: Float,
                    velocity: Float,
                ) {
                    if (isFadingIn && value >= iconAnimThreshold) {
                        AmbientIndicationAnimationUtils.animateAlpha(
                            iconView,
                            targetAlpha,
                            null,
                            Runnable {},
                            springForce,
                        )
                        animation.removeUpdateListener(this)
                        return
                    }
                    if (!isFadingIn && value <= iconAnimThreshold && !hasTriggeredIconAnim.get()) {
                        AmbientIndicationAnimationUtils.animateAlpha(
                            iconView,
                            targetAlpha,
                            null,
                            Runnable {},
                            springForce,
                        )
                        hasTriggeredIconAnim.set(true)
                    }
                    if (isFadingIn || value > 0.2f) {
                        return
                    }
                    buttonView.visibility = View.INVISIBLE
                    animation.removeUpdateListener(this)
                }
            }

        val onEndListener = Runnable {
            if (!isFadingIn) {
                actionContainer.visibility = View.GONE
            }
        }

        AmbientIndicationAnimationUtils.animateAlpha(
            buttonView,
            targetAlpha,
            updateListener,
            onEndListener,
            springForce,
        )
    }

    fun animateBackgroundArtworkInExpand(
        view: ImageView,
        toBgColor: Int,
        bgAnimationEnd: Runnable,
        toSrc: Drawable?,
    ): Runnable = Runnable {
        val colorDrawable = ColorDrawable(0)
        view.background = colorDrawable
        animateDrawableColor(
            colorDrawable,
            0,
            toBgColor,
            bgAnimationEnd,
            ::setColorDrawableColor,
        )
        animateBackgroundArtworkInExpandStartToSrcAnimation(toSrc, view)
    }

    fun animateBackgroundArtworkInExpandStartToSrcAnimation(
        drawable: Drawable?,
        imageView: ImageView,
    ) {
        drawable ?: return

        drawable.alpha = 0
        imageView.setImageDrawable(drawable)

        val layerDrawable = drawable as LayerDrawable
        val springForce = AmbientIndicationAnimationUtils.slowEffectsSpec

        AmbientIndicationAnimationUtils.animateLayeredDrawableAlpha(
            layerDrawable,
            imageView,
            255,
            null,
            Runnable { Trace.endAsyncSection("bind_artwork", 3) },
            springForce,
        )

        val spatialSpringForce = AmbientIndicationAnimationUtils.defaultSpatialSpec
        val scaleAnimation = SpringAnimation(imageView, DynamicAnimation.SCALE_X, 1.05f)
        scaleAnimation.spring =
            AmbientIndicationAnimationUtils.copySpringForce(spatialSpringForce, 1.05f)
        scaleAnimation.start()
    }

    val animateBackgroundArtworkInCollapseUpdateListener =
        DynamicAnimation.OnAnimationUpdateListener { animation, value, _ ->
            if (value <= 0.1f) {
                animation.cancel()
            }
        }

    fun animateBackgroundArtworkInCollapseEndAction(view: ImageView, variant: Int): Runnable =
        Runnable {
            when (variant) {
                0 -> {
                    view.background = null
                    view.setImageDrawable(null)
                    view.alpha = 1.0f
                    view.visibility = View.GONE
                }
                1 -> view.background = null
                2 -> view.background = null
                else -> view.imageTintList = null
            }
        }

    val animateIconAndThumbnailOnCollapseWithAlbumArtEnd = Runnable {}

    val animateIconAndThumbnailOnExpandNoAlbumArtEnd = Runnable {}

    fun animateIconAndThumbnailOnExpandWithAlbumArtUpdateListener(
        iconView: ImageView,
        toBgDrawable: Drawable,
        hasTriggeredBgDrawableAnim: AtomicBoolean,
    ) = DynamicAnimation.OnAnimationUpdateListener { _, value, _ ->
        if (!hasTriggeredBgDrawableAnim.get() && value <= 114.75f) {
            toBgDrawable.alpha = 0
            iconView.background = toBgDrawable
            AmbientIndicationAnimationUtils.animateDrawableAlpha(
                toBgDrawable,
                iconView,
                255,
                null,
                Runnable {},
                AmbientIndicationAnimationUtils.defaultEffectsSpec,
            )
            hasTriggeredBgDrawableAnim.set(true)
        }
    }

    fun songChangeTempTextSlideUpdateListener(
        tempTextView: TextView,
        iconTransitionListener: DynamicAnimation.OnAnimationUpdateListener?,
        sharedOnEndListener: Runnable,
    ) =
        object : DynamicAnimation.OnAnimationUpdateListener {
            override fun onAnimationUpdate(
                animation: DynamicAnimation<*>,
                value: Float,
                velocity: Float,
            ) {
                if (value <= 44.65f) {
                    AmbientIndicationAnimationUtils.animateAlpha(
                        tempTextView,
                        1.0f,
                        iconTransitionListener,
                        sharedOnEndListener,
                        AmbientIndicationAnimationUtils.defaultEffectsSpec,
                    )
                    animation.removeUpdateListener(this)
                }
            }
        }

    fun animateIconTransitionExpandNoIconUpdateListener(
        iconView: ImageView,
        toIconDrawable: Drawable,
    ) =
        object : DynamicAnimation.OnAnimationUpdateListener {
            override fun onAnimationUpdate(
                animation: DynamicAnimation<*>,
                value: Float,
                velocity: Float,
            ) {
                if (value <= 51.0f) {
                    animation.removeUpdateListener(this)
                    toIconDrawable.alpha = 0
                    iconView.setImageDrawable(toIconDrawable)
                    AmbientIndicationAnimationUtils.animateDrawableAlpha(
                        toIconDrawable,
                        iconView,
                        255,
                        null,
                        Runnable {},
                        AmbientIndicationAnimationUtils.defaultEffectsSpec,
                    )
                    animation.cancel()
                }
            }
        }

    fun animateIconTransitionCollapseWithArtUpdateListener(
        iconView: ImageView,
        hasTriggeredIconSwap: AtomicBoolean,
    ) = DynamicAnimation.OnAnimationUpdateListener { animation, value, _ ->
        if (value <= 25.5f) {
            animation.cancel()
        }
        if (!hasTriggeredIconSwap.get() && value <= 191.25f) {
            AmbientIndicationAnimationUtils.animateDrawableAlpha(
                iconView.drawable,
                iconView,
                255,
                null,
                animateIconAndThumbnailOnCollapseWithAlbumArtEnd,
                AmbientIndicationAnimationUtils.defaultEffectsSpec,
            )
            hasTriggeredIconSwap.set(true)
        }
    }

    fun songChangeIconIntroUpdateListener(
        tempArtistTextView: TextView,
        sharedOnEndListener: Runnable,
    ) =
        object : DynamicAnimation.OnAnimationUpdateListener {
            override fun onAnimationUpdate(
                animation: DynamicAnimation<*>,
                value: Float,
                velocity: Float,
            ) {
                if (value >= 0.35f) {
                    AmbientIndicationAnimationUtils.animateAlpha(
                        tempArtistTextView,
                        1.0f,
                        null,
                        sharedOnEndListener,
                        AmbientIndicationAnimationUtils.slowEffectsSpec,
                    )
                    animation.removeUpdateListener(this)
                }
            }
        }

    val animateIconTransitionEnd = Runnable {}

    fun textColorsUpdateListener(textViews: List<TextView>) =
        ValueAnimator.AnimatorUpdateListener { animator ->
            val color = animator.animatedValue as Int
            for (tv in textViews) tv.setTextColor(color)
        }

    fun iconTintUpdateListener(iconView: ImageView) =
        ValueAnimator.AnimatorUpdateListener { animator ->
            iconView.imageTintList = ColorStateList.valueOf(animator.animatedValue as Int)
        }

    val performFirstRecognitionAnimationTranslationEnd = Runnable {}

    val performFirstRecognitionAnimationAlphaEnd = Runnable {
        Trace.endAsyncSection("first_recognition_animation", 1)
    }

    fun performSongSearchingAnimationContinuation(view: LinearLayout): Runnable = Runnable {
        AmbientIndicationAnimationUtils.animateAlpha(
            view,
            1.0f,
            null,
            null,
            AmbientIndicationAnimationUtils.defaultEffectsSpec,
        )
    }

    fun bgAnimationEndTraceGuard(drawable: Drawable?): Runnable = Runnable {
        if (drawable == null) {
            Trace.endAsyncSection("bind_artwork", 3)
        }
    }

    fun runSongChangeContentSlideFinalSwapAction(
        realSong: TextView,
        newSong: CharSequence?,
        realArtist: TextView,
        newArtist: CharSequence?,
        realSet: View,
        tempSet: View,
        tempSong: TextView,
        tempArtist: TextView,
        container: View,
    ): Runnable = Runnable {
        realSong.text = newSong
        realArtist.text = newArtist
        realSet.translationY = 0.0f
        realSet.alpha = 1.0f
        tempSet.alpha = 0.0f
        tempSong.text = null
        tempArtist.text = null
        if (realSet.layoutParams.width != ViewGroupLayoutParamsWrapContent) {
            realSet.layoutParams.width = ViewGroupLayoutParamsWrapContent
        }
        container.translationX = 0.0f
    }

    private const val ViewGroupLayoutParamsWrapContent = -2

    fun runSongChangeContentSlideSharedOnEndListener(
        animationEndCounter: AtomicInteger,
        finalSwapAction: Runnable,
    ): Runnable = Runnable {
        if (animationEndCounter.decrementAndGet() == 0) {
            finalSwapAction.run()
        }
    }

    val updateActionContainerColorsEnd = Runnable {}

    fun animateDrawableColor(
        drawable: Any,
        fromColor: Int,
        toColor: Int,
        onEnd: Runnable,
        setColor: (Any, Int) -> Unit,
    ) {
        if (fromColor == toColor) {
            setColor(drawable, toColor)
            return
        }
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
        animator.duration = 200L
        animator.addUpdateListener { animation ->
            setColor(drawable, animation.animatedValue as Int)
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd.run()
                }
            }
        )
        animator.start()
    }

    fun setGradientDrawableColor(drawable: Any, color: Int) {
        (drawable as GradientDrawable).setColor(color)
    }

    fun setColorDrawableColor(drawable: Any, color: Int) {
        (drawable as ColorDrawable).color = color
    }

    fun getBackgroundColor(view: View): Int {
        return when (val background = view.background) {
            is GradientDrawable -> background.color?.defaultColor ?: 0
            is ColorDrawable -> background.color
            else -> 0
        }
    }
}
