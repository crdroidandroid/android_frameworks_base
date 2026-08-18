package com.google.android.systemui.ambientmusic

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object AmbientIndicationAnimationUtils {

    @JvmField
    val defaultSpatialSpec: SpringForce =
        SpringForce().apply {
            dampingRatio = 0.8f
            stiffness = 380f
        }

    @JvmField
    val slowSpatialSpec: SpringForce =
        SpringForce().apply {
            dampingRatio = 0.8f
            stiffness = 200f
        }

    @JvmField
    val defaultEffectsSpec: SpringForce =
        SpringForce().apply {
            dampingRatio = 1.0f
            stiffness = 1600f
        }

    @JvmField
    val fastEffectsSpec: SpringForce =
        SpringForce().apply {
            dampingRatio = 1.0f
            stiffness = 3800f
        }

    @JvmField
    val slowEffectsSpec: SpringForce =
        SpringForce().apply {
            dampingRatio = 1.0f
            stiffness = 800f
        }

    @JvmField
    val fastSpatialSpec: SpringForce =
        SpringForce().apply {
            dampingRatio = 0.6f
            stiffness = 800f
        }

    @JvmStatic
    fun animateAlpha(
        view: View,
        targetAlpha: Float,
        onUpdateListener: DynamicAnimation.OnAnimationUpdateListener? = null,
        onEndListener: Runnable? = null,
        springForce: SpringForce,
    ) {
        val animation = SpringAnimation(view, DynamicAnimation.ALPHA, targetAlpha)
        animation.spring = copySpringForce(springForce, targetAlpha)
        if (onUpdateListener != null) {
            animation.addUpdateListener(onUpdateListener)
        }
        if (onEndListener != null) {
            animation.addEndListener { _, _, _, _ -> onEndListener.run() }
        }
        animation.start()
    }

    @JvmStatic
    fun animateDrawableAlpha(
        drawable: Drawable,
        host: View,
        targetAlpha: Int,
        onUpdateListener: DynamicAnimation.OnAnimationUpdateListener? = null,
        onEndListener: Runnable? = null,
        springForce: SpringForce,
    ) {
        val alphaProperty =
            object : FloatPropertyCompat<Drawable>("drawableAlpha") {
                override fun getValue(obj: Drawable): Float = obj.alpha.toFloat()
                override fun setValue(obj: Drawable, value: Float) {
                    obj.alpha = value.toInt()
                    host.invalidate()
                }
            }
        val targetAlphaFloat = targetAlpha.toFloat()
        val animation = SpringAnimation(drawable, alphaProperty, targetAlphaFloat)
        animation.spring = copySpringForce(springForce, targetAlphaFloat)
        if (onUpdateListener != null) {
            animation.addUpdateListener(onUpdateListener)
        }
        if (onEndListener != null) {
            animation.addEndListener { _, _, _, _ -> onEndListener.run() }
        }
        animation.start()
    }

    @JvmStatic
    fun animateTranslationX(
        view: View,
        targetTranslationX: Float,
        onUpdateListener: DynamicAnimation.OnAnimationUpdateListener? = null,
        onEndListener: Runnable? = null,
        springForce: SpringForce,
    ) {
        val animation = SpringAnimation(view, DynamicAnimation.TRANSLATION_X, targetTranslationX)
        animation.spring = copySpringForce(springForce, targetTranslationX)
        if (onUpdateListener != null) {
            animation.addUpdateListener(onUpdateListener)
        }
        if (onEndListener != null) {
            animation.addEndListener { _, _, _, _ -> onEndListener.run() }
        }
        animation.start()
    }

    @JvmStatic
    fun animateTranslationY(
        view: View,
        targetTranslationY: Float,
        onUpdateListener: DynamicAnimation.OnAnimationUpdateListener? = null,
        onEndListener: Runnable? = null,
        springForce: SpringForce,
    ) {
        val animation = SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, targetTranslationY)
        animation.spring = copySpringForce(springForce, targetTranslationY)
        if (onUpdateListener != null) {
            animation.addUpdateListener(onUpdateListener)
        }
        if (onEndListener != null) {
            animation.addEndListener { _, _, _, _ -> onEndListener.run() }
        }
        animation.start()
    }

    inline fun animateLayeredDrawableAlpha(
        layerDrawable: LayerDrawable,
        host: View,
        targetAlpha: Int,
        onUpdateListener: DynamicAnimation.OnAnimationUpdateListener? = null,
        onEndListener: Runnable? = null,
        springForce: SpringForce,
    ) {
        val alphaProperty =
            object : FloatPropertyCompat<LayerDrawable>("layeredDrawableAlpha") {
                override fun getValue(obj: LayerDrawable): Float =
                    obj.getDrawable(0).alpha.toFloat()
                override fun setValue(obj: LayerDrawable, value: Float) {
                    val numberOfLayers = layerDrawable.numberOfLayers
                    for (i in 0 until numberOfLayers) {
                        layerDrawable.getDrawable(i).alpha = value.toInt()
                    }
                    host.invalidate()
                }
            }
        val targetAlphaFloat = targetAlpha.toFloat()
        val animation = SpringAnimation(layerDrawable, alphaProperty, targetAlphaFloat)
        animation.spring = copySpringForce(springForce, targetAlphaFloat)
        if (onUpdateListener != null) {
            animation.addUpdateListener(onUpdateListener)
        }
        if (onEndListener != null) {
            animation.addEndListener { _, _, _, _ -> onEndListener.run() }
        }
        animation.start()
    }

    @JvmStatic
    fun copySpringForce(source: SpringForce, finalPosition: Float): SpringForce {
        return SpringForce(finalPosition).apply {
            dampingRatio = source.dampingRatio.toFloat()
            stiffness = source.stiffness.toFloat()
        }
    }
}
