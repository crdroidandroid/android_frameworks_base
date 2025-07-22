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
package com.android.systemui.util

import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class ScrimUtils private constructor() {

    interface ScrimEventListener {
        fun onKeyguardShowingChanged(showing: Boolean) {}
        fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {}
        fun onKeyguardGoingAwayChanged(goingAway: Boolean) {}
        fun onPrimaryBouncerShowingChanged(showing: Boolean) {}
        fun onDozingChanged() {}
        fun onExpandedFractionChanged(expandedFraction: Float) {}
        fun onBarStateChanged(state: Int) {}
        fun onQsVisibilityChanged(visible: Boolean) {}
        fun onStartedWakingUp() {}
        fun onScreenTurnedOff() {}
        fun setPulsing(pulsing: Boolean) {}
    }

    private val listeners = WeakListenerManager<ScrimEventListener>()

    private var mIsDozing = false
    private val mQsVisible = AtomicBoolean(false)
    private val mPulsing = AtomicBoolean(false)

    @Volatile private var mExpandedFraction = 0f
    @Volatile private var mBarState = -1
    @Volatile private var mKeyguardShowing = true

    companion object {
        @Volatile private var instance: ScrimUtils? = null

        @JvmStatic
        fun get(): ScrimUtils =
            instance ?: synchronized(this) {
                instance ?: ScrimUtils().also { instance = it }
            }
    }

    fun addListener(listener: ScrimEventListener) = listeners.addListener(listener)
    fun removeListener(listener: ScrimEventListener) = listeners.removeListener(listener)

    private fun notifyListeners(callback: Consumer<ScrimEventListener>) {
        listeners.notifyConsumer(callback)
    }

    fun setKeyguardShowing(showing: Boolean) {
        if (mKeyguardShowing != showing) {
            mKeyguardShowing = showing
            notifyListeners(Consumer { it.onKeyguardShowingChanged(showing) })
        }
    }

    fun setExpandedFraction(fraction: Float) {
        if ((fraction == 0.0f || fraction == 1.0f) && mExpandedFraction != fraction) {
            mExpandedFraction = fraction
            notifyListeners(Consumer { it.onExpandedFractionChanged(fraction) })
        }
    }

    fun onDozingChanged(dozing: Boolean) {
        if (mIsDozing != dozing) {
            mIsDozing = dozing
            listeners.notifyOnMain { it.onDozingChanged() }
        }
    }

    fun onKeyguardGoingAwayChanged(goingAway: Boolean) =
        notifyListeners(Consumer { it.onKeyguardGoingAwayChanged(goingAway) })

    fun onKeyguardFadingAwayChanged(fadingAway: Boolean) =
        notifyListeners(Consumer { it.onKeyguardFadingAwayChanged(fadingAway) })

    fun onPrimaryBouncerShowingChanged(showing: Boolean) =
        notifyListeners(Consumer { it.onPrimaryBouncerShowingChanged(showing) })

    fun setBarState(state: Int) {
        if (mBarState != state) {
            mBarState = state
            notifyListeners(Consumer { it.onBarStateChanged(state) })
        }
    }

    fun setQsVisible(visible: Boolean) {
        if (mQsVisible.getAndSet(visible) != visible) {
            notifyListeners(Consumer { it.onQsVisibilityChanged(visible) })
        }
    }
    
    fun setPulsing(pulsing: Boolean) {
        if (mPulsing.getAndSet(pulsing) != pulsing) {
            notifyListeners(Consumer { it.setPulsing(pulsing) })
        }
    }

    fun onStartedWakingUp() =
        notifyListeners(Consumer { it.onStartedWakingUp() })

    fun onScreenTurnedOff() =
        notifyListeners(Consumer { it.onScreenTurnedOff() })

    fun isDozing(): Boolean = mIsDozing

    fun isKeyguardShowing(): Boolean = mKeyguardShowing || mBarState == KEYGUARD

    fun isPanelFullyCollapsed(): Boolean =
        if (mBarState == SHADE_LOCKED || mBarState == KEYGUARD) {
            !mQsVisible.get()
        } else {
            mExpandedFraction <= 0.0f
        }
}
