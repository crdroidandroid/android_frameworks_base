/*
 * Copyright (C) 2023 the risingOS Android Project
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
package com.android.systemui.statusbar.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.AsyncTask
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnCancel

import com.android.systemui.Dependency
import com.android.systemui.plugins.statusbar.StatusBarStateController

import com.android.systemui.res.R

class FaceUnlockImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    enum class State {
        SCANNING, NOT_VERIFIED, SUCCESS, HIDDEN
    }

    private val DELAY_HIDE_DURATION = 1500
    private var currentState: State = State.HIDDEN
    private var colorState: ColorStateList? = null
    private val startAnimation: ObjectAnimator = createScaleAnimation(start = true)
    private val dismissAnimation: ObjectAnimator = createScaleAnimation(start = false)
    private val scanningAnimation: ObjectAnimator = createScanningAnimation()
    private val successAnimation: ObjectAnimator = createSuccessRotationAnimation()
    private val failureShakeAnimation: ObjectAnimator = createShakeAnimation(10f)
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val animations = listOf(failureShakeAnimation, dismissAnimation, scanningAnimation, successAnimation, startAnimation)
    private var mDozing = false
    private val statusBarStateController: StatusBarStateController = Dependency.get(StatusBarStateController::class.java)

    companion object {
        private var instance: FaceUnlockImageView? = null

        @JvmStatic
        fun setBouncerState(state: State) {
            instance?.postDelayed({
                instance?.setState(state)
            }, 100)
        }

        @JvmStatic
        fun setInstance(instance: FaceUnlockImageView) {
            this.instance = instance
        }

        @JvmStatic
        fun getInstance(): FaceUnlockImageView? {
            return instance
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}

        override fun onDozingChanged(dozing: Boolean) {
            if (mDozing == dozing) {
                return
            }
            mDozing = dozing
            if (mDozing) {
                visibility = View.GONE
            }
        }
    }

    init {
        statusBarStateController.addCallback(statusBarStateListener)
        statusBarStateListener.onDozingChanged(statusBarStateController.isDozing())
        visibility = View.GONE
        updateFaceIconState()
    }

    public override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInstance(this)
        updateColor()
    }

    fun updateColor() {
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#99000000"))
    }

    fun setState(state: State) {
        if (currentState != state) {
            currentState = state
            updateFaceIconState()
            handleAnimationForState(state)
        }
    }

    fun updateFaceIconState() {
        if (mDozing) {
            visibility = View.GONE
            return
        }
        setImageResource(when (currentState) {
            State.SCANNING -> R.drawable.face_scanning
            State.NOT_VERIFIED -> R.drawable.face_not_verified
            State.SUCCESS -> R.drawable.face_success
            State.HIDDEN -> R.drawable.face_scanning
        })
    }

    private fun createScanningAnimation(): ObjectAnimator {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f, 1f)
        return ObjectAnimator.ofPropertyValuesHolder(this, scaleX, scaleY).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun createSuccessRotationAnimation(): ObjectAnimator {
        return ObjectAnimator.ofFloat(this, View.ROTATION_Y, 0f, 360f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    private fun createShakeAnimation(amplitude: Float): ObjectAnimator {
        return ObjectAnimator.ofFloat(this, View.TRANSLATION_X, 0f, amplitude, -amplitude, amplitude, -amplitude, 0f).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    private fun createScaleAnimation(start: Boolean): ObjectAnimator {
        val startScale = if (start) 0f else 1f
        val endScale = if (start) 1f else 0f
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, startScale, endScale)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, startScale, endScale)
        return ObjectAnimator.ofPropertyValuesHolder(this, scaleX, scaleY).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            if (!start) {
                doOnEnd { visibility = View.GONE }
            }
        }
    }

    private fun vibrate(effect: Int) {
        if (mDozing) {
            return
        }
        post {
            val vibrationEffect = VibrationEffect.createPredefined(effect)
            vibrator.vibrate(vibrationEffect)
        }
    }

    private fun cancelAllAnimations() {
        animations.forEach { it.cancel() }
    }

    private fun handleAnimationForState(state: State) {
        if (mDozing) {
            visibility = View.GONE
            return
        }
        cancelAllAnimations()
        if (state == State.SCANNING) {
            visibility = View.VISIBLE
        }
        when (state) {
            State.SCANNING -> startAnimationChain(startAnimation, scanningAnimation)
            State.NOT_VERIFIED -> {
                startAnimationChain(failureShakeAnimation, dismissAnimation)
                vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
            State.SUCCESS -> {
                startAnimationChain(successAnimation, dismissAnimation)
                vibrate(VibrationEffect.EFFECT_CLICK)
            }
            State.HIDDEN -> dismissAnimation.start()
        }
    }

    private fun startAnimationChain(first: ObjectAnimator, next: ObjectAnimator) {
        first.startWithEndAction { next.start() }
    }

    private fun ObjectAnimator.startWithEndAction(endAction: () -> Unit) {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                endAction()
            }
        })
        start()
    }
}
