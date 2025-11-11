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

import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.Dependency
import com.android.systemui.dump.DumpManager
import com.android.systemui.Dumpable
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import javax.inject.Inject
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean

/* Scrim - aka testing utils */
@SysUISingleton
class ScrimUtils @Inject constructor(dumpManager: DumpManager) : Dumpable {

    interface ScrimEventListener {
        fun onKeyguardShowingChanged(showing: Boolean) {}
        fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {}
        fun onKeyguardGoingAwayChanged(goingAway: Boolean) {}
        fun onPrimaryBouncerShowingChanged(showing: Boolean) {}
        fun onDozingChanged(dozing: Boolean) {}
        fun onExpandedFractionChanged(expandedFraction: Float) {}
        fun onBarStateChanged(state: Int) {}
        fun onQsVisibilityChanged(visible: Boolean) {}
        fun onStartedWakingUp() {}
        fun onScreenTurnedOff() {}
        fun setPulsing(pulsing: Boolean) {}
        fun onNotificationPosted(sbn: StatusBarNotification) {}
    }

    private val listeners = WeakListenerManager<ScrimEventListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mQsVisible = AtomicBoolean()
    private val mPulsing = AtomicBoolean()
    private val mFadingAwayDuration = 500L

    @Volatile private var mIsDozing: Boolean? = null
    @Volatile private var mKeyguardShowing: Boolean? = null
    @Volatile private var mExpandedFraction: Float? = null
    @Volatile private var mBarState: Int? = null
    @Volatile private var mAwake: Boolean? = null

    private var keyguardRetryRunnable: Runnable? = null

    companion object {
        @JvmStatic
        fun get(): ScrimUtils = Dependency.get(ScrimUtils::class.java)
    }

    init {
        dumpManager.registerNormalDumpable("ScrimUtils", this)
    }

    fun addListener(listener: ScrimEventListener) = listeners.addListener(listener)
    fun removeListener(listener: ScrimEventListener) = listeners.removeListener(listener)

    fun setKeyguardShowing(showing: Boolean) {
        if (mKeyguardShowing == null || mKeyguardShowing != showing) {
            mKeyguardShowing = showing
            listeners.notifyOnMain { it.onKeyguardShowingChanged(showing) }
        }
    }

    fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        listeners.notifyOnMain { it.onKeyguardFadingAwayChanged(fadingAway) }
        postKeyguardRetry()
    }

    fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        listeners.notifyOnMain { it.onKeyguardGoingAwayChanged(goingAway) }
        postKeyguardRetry()
    }

    fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        listeners.notifyOnMain { it.onPrimaryBouncerShowingChanged(showing) }
        postKeyguardRetry()
    }

    private fun postKeyguardRetry() {
        keyguardRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        keyguardRetryRunnable = Runnable {
            listeners.notifyOnMain { it.onKeyguardShowingChanged(mKeyguardShowing == true) }
        }
        mainHandler.postDelayed(keyguardRetryRunnable!!, mFadingAwayDuration)
    }

    fun setExpandedFraction(fraction: Float) {
        if (mExpandedFraction == null || (fraction == 0.0f || fraction == 1.0f && mExpandedFraction != fraction)) {
            mExpandedFraction = fraction
            listeners.notifyOnMain { it.onExpandedFractionChanged(fraction) }
        }
    }

    fun onDozingChanged(dozing: Boolean) {
        if (mIsDozing == null || mIsDozing != dozing) {
            mIsDozing = dozing
            listeners.notifyOnMain { it.onDozingChanged(dozing) }
        }
    }

    fun setBarState(state: Int) {
        if (mBarState == null || mBarState != state) {
            mBarState = state
            listeners.notifyOnMain { it.onBarStateChanged(state) }
        }
        // hack 4 bug: 
        // 1. user is on keyguard but is mBarState == SHADE
        // 2. keyguard update monitor wrong state when dozing 
        val shouldShowKeyguard = state == KEYGUARD || mIsDozing == true || mPulsing.get()
        setKeyguardShowing(shouldShowKeyguard)
    }

    fun setQsVisible(visible: Boolean) {
        if (mQsVisible.getAndSet(visible) != visible) {
            listeners.notifyOnMain { it.onQsVisibilityChanged(visible) }
        }
    }

    fun setPulsing(pulsing: Boolean) {
        if (mPulsing.getAndSet(pulsing) != pulsing) {
            listeners.notifyOnMain { it.setPulsing(pulsing) }
        }
    }

    fun onStartedWakingUp() {
        mAwake = true
        listeners.notifyOnMain { it.onStartedWakingUp() }
    }

    fun onScreenTurnedOff() {
        mAwake = false
        listeners.notifyOnMain { it.onScreenTurnedOff() }
    }

    fun onNotificationPosted(sbn: StatusBarNotification) {
        listeners.notifyOnMain { it.onNotificationPosted(sbn) }
    }

    fun isDozing(): Boolean = mIsDozing == true
    fun isAwake(): Boolean = mAwake == true
    fun isPulsing(): Boolean = mPulsing.get()
    fun isKeyguardShowing(): Boolean = mKeyguardShowing == true

    fun isPanelFullyCollapsed(): Boolean =
        if (mBarState == SHADE_LOCKED || mBarState == KEYGUARD) {
            !mQsVisible.get()
        } else {
            (mExpandedFraction ?: 0.0f) <= 0.0f
        }

    // adb shell dumpsys activity service com.android.systemui | grep "ScrimUtils states:" -A10
    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("ScrimUtils states:")
        pw.println("  mKeyguardShowing = $mKeyguardShowing")
        pw.println("  mIsDozing = $mIsDozing")
        pw.println("  mAwake = $mAwake")
        pw.println("  mPulsing = ${mPulsing.get()}")
        pw.println("  mQsVisible = ${mQsVisible.get()}")
        pw.println("  mExpandedFraction = $mExpandedFraction")
        pw.println("  mBarState = $mBarState")
        pw.println("  isPanelFullyCollapsed() = ${isPanelFullyCollapsed()}")
    }
}
