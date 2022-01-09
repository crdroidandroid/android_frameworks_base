/*
 * Copyright (C) 2022 FlamingoOS Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.RelativeLayout

import com.android.systemui.R

class EdgeLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleRes: Int = 0,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleRes, defStyleAttr) {

    private var leftView: ImageView? = null
    private var rightView: ImageView? = null
    private var animating = false
    private var repeatCount = 0
    // Incremented during each animation cycle
    // Reset when hidden or exceeds [repeatCount]
    private var counter = 0

    private var color: Int = Color.WHITE

    // Vertical expansion animation
    private val expandAnimation = ScaleAnimation(
        1f /** fromX */, 1f /** toX */,
        0f /** fromY */, 1f /** toY */,
        Animation.RELATIVE_TO_SELF /** pivotXType */, 0f /** pivotX */,
        Animation.RELATIVE_TO_SELF /** pivotYType */, 0.5f /** pivotY */
    )

    // Horiozontal collapse animation to the edges,
    // right after vertical expansion animation
    private val collapseAnimationLeft = ScaleAnimation(
        1f /** fromX */, 0f /** toX */,
        1f /** fromY */, 1f /** toY */,
        Animation.RELATIVE_TO_SELF /** pivotXType */, 0f /** pivotX */,
        Animation.RELATIVE_TO_SELF /** pivotYType */, 0f /** pivotY */
    )
    private val collapseAnimationRight = ScaleAnimation(
        1f /** fromX */, 0f /** toX */,
        1f /** fromY */, 1f /** toY */,
        Animation.RELATIVE_TO_SELF /** pivotXType */, 1f /** pivotX */,
        Animation.RELATIVE_TO_SELF /** pivotYType */, 0f /** pivotY */
    )

    // Convenience extension function to reduce some boilerplate
    inline fun Animation.addListener(
        crossinline onStart: (Animation?) -> Unit = {},
        crossinline onEnd: (Animation?) -> Unit = {},
        crossinline onRepeat: (Animation?) -> Unit = {},
    ) {
        setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = onStart(animation)
            override fun onAnimationEnd(animation: Animation?) = onEnd(animation)
            override fun onAnimationRepeat(animation: Animation?) = onRepeat(animation)
        })
    }

    init {
        expandAnimation.addListener(
            onStart = {
                animating = true
            },
            onEnd = {
                leftView?.startAnimation(collapseAnimationLeft)
                rightView?.startAnimation(collapseAnimationRight)
            }
        )
        collapseAnimationLeft.addListener(onEnd = {
            if (repeatCount == Animation.INFINITE) {
                leftView?.startAnimation(expandAnimation)
            } else {
                if (visibility == VISIBLE) visibility = GONE
                animating = false
            }
        })
        collapseAnimationRight.addListener(onEnd = {
            if (repeatCount == Animation.INFINITE) {
                rightView?.startAnimation(expandAnimation)
            } else {
                if (visibility == VISIBLE) visibility = GONE
                animating = false
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        leftView = findViewById(R.id.edge_light_start)
        rightView = findViewById(R.id.edge_light_end)
        setColor(color)
    }

    /**
     * Animate appear and repeat animation if specified with [setRepeatCount].
     */
    fun show() {
        if (animating) return
        if (visibility == GONE) visibility = VISIBLE
        leftView?.startAnimation(expandAnimation)
        rightView?.startAnimation(expandAnimation)
    }

    /**
     * Clear all animations and hide views
     */
    fun hide() {
        if (visibility == VISIBLE) visibility = GONE
        if (!animating) return
        leftView?.clearAnimation()
        rightView?.clearAnimation()
        animating = false
    }

    /**
     * Set vertical expansion animation duration
     */
    fun setExpandAnimationDuration(duration: Long) {
        expandAnimation.duration = duration
    }

    /**
     * Set horizontal collapse animation duration
     */
    fun setCollapseAnimationDuration(duration: Long) {
        collapseAnimationLeft.duration = duration
        collapseAnimationRight.duration = duration
    }

    /**
     * Set repeat count for animation.
     * Expected either 0 or Animation.INFINITE.
     */
    fun setRepeatCount(repeatCount: Int) {
        this.repeatCount = repeatCount
    }

    fun setColor(color: Int) {
        // If this method is called before the view is attached then it'll have
        // no effect, so we store this color and call it from [onAttachedToWindow]
        this.color = color
        leftView?.setColorFilter(color)
        rightView?.setColorFilter(color)
    }
}