/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.WindowConfiguration
import android.os.SystemClock
import android.util.IndentingPrintWriter
import android.util.Log
import android.util.MathUtils
import android.view.Choreographer
import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.TrackTracer
import com.android.systemui.Dumpable
import com.android.systemui.Flags
import com.android.systemui.Flags.checkDesktopModeForSpacialModelAppPushback
import com.android.systemui.Flags.spatialModelAppPushback
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.display.data.repository.FocusedDisplayRepository
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionListener
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_WAKE_AND_UNLOCK
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.WallpaperController
import com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import com.android.wm.shell.appzoomout.AppZoomOut
import com.android.wm.shell.desktopmode.DesktopMode
import dagger.Lazy
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Responsible for blurring the notification shade window, and applying a zoom effect to the
 * wallpaper.
 */
@SysUISingleton
class NotificationShadeDepthController
@Inject
constructor(
    private val statusBarStateController: StatusBarStateController,
    private val blurUtils: BlurUtils,
    private val biometricUnlockController: BiometricUnlockController,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardInteractor: KeyguardInteractor,
    private val choreographer: Choreographer,
    private val wallpaperController: WallpaperController,
    private val wallpaperInteractor: WallpaperInteractor,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val dozeParameters: DozeParameters,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
    private val appZoomOutOptional: Optional<AppZoomOut>,
    private val shadeDisplaysRepository: Lazy<ShadeDisplaysRepository>,
    private val focusedDisplayRepository: FocusedDisplayRepository,
    @Application private val applicationScope: CoroutineScope,
    private val desktopMode: Optional<DesktopMode>,
    dumpManager: DumpManager,
) : ShadeExpansionListener, Dumpable {
    companion object {
        private const val WAKE_UP_ANIMATION_ENABLED = true
        private const val VELOCITY_SCALE = 100f
        private const val MAX_VELOCITY = 3000f
        private const val MIN_VELOCITY = -MAX_VELOCITY
        private const val INTERACTION_BLUR_FRACTION = 0.8f
        private const val ANIMATION_BLUR_FRACTION = 1f - INTERACTION_BLUR_FRACTION
        private const val TRANSITION_THRESHOLD = 0.98f
        private const val PUSHBACK_SCALE_FOR_LAUNCHER = 0.05f

        private const val PUSHBACK_SCALE_FOR_APP = 0.025f
        private const val SHADE_BLUR_STEP_PX = 2
        private const val SHADE_BLUR_SCALE_STEPS = 512f

        private const val TAG = "DepthController"
    }

    lateinit var root: View
    private var keyguardAnimator: Animator? = null
    private var notificationAnimator: Animator? = null
    private var updateScheduled: Boolean = false
    @VisibleForTesting var shadeExpansion = 0f
    private var isClosed: Boolean = true
    private var isOpen: Boolean = false
    private var isBlurred: Boolean = false
    private var listeners = mutableListOf<DepthListener>()

    private var prevTracking: Boolean = false
    private var prevTimestamp: Long = -1
    private var prevShadeDirection = 0
    private var prevShadeVelocity = 0f
    private var prevDozeAmount: Float = 0f
    @VisibleForTesting var wallpaperSupportsAmbientMode: Boolean = false

    // tracks whether app launch transition is in progress. This involves two independent factors
    // that control blur, shade expansion and app launch animation from outside sysui.
    // They can complete out of order, this flag will be reset by the animation that finishes later.
    private var appLaunchTransitionIsInProgress = false

    // Only for dumpsys
    private var lastAppliedBlur = 0

    private var isHomeFocused = true

    val maxBlurRadiusPx = blurUtils.maxBlurRadius

    // Shade expansion offset that happens when pulling down on a HUN.
    var panelPullDownMinFraction = 0f

    var existingStatusBarState: Int? = null

    var shadeAnimation = DepthAnimation()

    @VisibleForTesting var brightnessMirrorSpring = DepthAnimation()
    var brightnessMirrorVisible: Boolean = false
        set(value) {
            field = value
            brightnessMirrorSpring.animateTo(
                if (value) blurUtils.blurRadiusOfRatio(1f).toInt() else 0
            )
        }

    var qsPanelExpansion = 0f
        set(value) {
            if (value.isNaN()) {
                Log.w(TAG, "Invalid qs expansion")
                return
            }
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    /** How much we're transitioning to the full shade */
    var transitionToFullShadeProgress = 0f
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    /**
     * When launching an app from the shade, the animations progress should affect how blurry the
     * shade is, overriding the expansion amount.
     *
     * TODO(b/399617511): remove this once [Flags.notificationShadeBlur] is launched and the Shade
     *   closing is actually instantaneous.
     */
    var blursDisabledForAppLaunch: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            // Set this to true now, this will be reset when the next shade expansion finishes or
            // when the app launch finishes, whichever happens later.
            if (value) {
                appLaunchTransitionIsInProgress = true
            } else {
                // App was launching and now it has finished launching
                if (shadeExpansion == 0.0f) {
                    // this means shade expansion finished before app launch was done.
                    // reset the flag here
                    appLaunchTransitionIsInProgress = false
                }
            }
            field = value
            scheduleUpdate()

            if (shadeExpansion == 0f && shadeAnimation.radius == 0f) {
                return
            }
            // Do not remove blurs when we're re-enabling them
            if (!value) {
                return
            }

            if (Flags.notificationShadeBlur()) {
                shadeAnimation.skipTo(0)
            } else {
                shadeAnimation.animateTo(0)
                shadeAnimation.finishIfRunning()
            }
        }
        @Deprecated(
            message =
                "This might get reset to false before shade expansion is fully done, " +
                    "consider using areBlursDisabledForAppLaunch"
        )
        get() = field

    private var zoomOutCalculatedFromShadeRadius: Float = 0.0f

    /** We're unlocking, and should not blur as the panel expansion changes. */
    var blursDisabledForUnlock: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    private val areBlursDisabledForAppLaunch: Boolean
        get() =
            blursDisabledForAppLaunch ||
                (Flags.bouncerUiRevamp() && appLaunchTransitionIsInProgress)

    /** Force stop blur effect when necessary. */
    private var scrimsVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    private val isShadeOnDefaultDisplay: Boolean
        get() =
            if (ShadeWindowGoesAround.isEnabled) {
                shadeDisplaysRepository.get().displayId.value == Display.DEFAULT_DISPLAY
            } else {
                true
            }

    /** Blur radius of the wake and unlock animation on this frame, and whether to zoom out. */
    private var wakeAndUnlockBlurRadius = 0f
        set(value) {
            if (field == value) return
            field = value
            scheduleUpdate()
        }

    private fun computeBlurAndZoomOut(): Pair<Int, Float> {
        val animationRadius =
            MathUtils.constrain(
                shadeAnimation.radius,
                blurUtils.minBlurRadius,
                blurUtils.maxBlurRadius,
            )
        val expansionRadius =
            blurUtils.blurRadiusOfRatio(
                ShadeInterpolation.getNotificationScrimAlpha(
                    if (shouldApplyShadeBlur()) shadeExpansion else 0f
                )
            )
        var combinedBlur =
            (expansionRadius * INTERACTION_BLUR_FRACTION +
                animationRadius * ANIMATION_BLUR_FRACTION)
        val qsExpandedRatio =
            ShadeInterpolation.getNotificationScrimAlpha(qsPanelExpansion) * shadeExpansion
        combinedBlur = max(combinedBlur, blurUtils.blurRadiusOfRatio(qsExpandedRatio))
        combinedBlur = max(combinedBlur, blurUtils.blurRadiusOfRatio(transitionToFullShadeProgress))
        var shadeRadius = max(combinedBlur, wakeAndUnlockBlurRadius)

        if (areBlursDisabledForAppLaunch || blursDisabledForUnlock) {
            shadeRadius = 0f
        }

        // Brightness slider removes blur
        shadeRadius *= (1 - brightnessMirrorSpring.ratio)

        var blur = quantizeShadeBlurRadius(shadeRadius)

        // If the blur comes from waking up, we don't want to zoom out the background
        val zoomOut =
            when {
                // When the shade is in another display, we don't want to zoom out the background.
                // Only the default display is supported right now.
                !isShadeOnDefaultDisplay -> 0f
                shadeRadius != wakeAndUnlockBlurRadius ->
                    blurRadiusToZoomOut(blurRadius = shadeRadius)
                else -> 0f
            }
        // Make blur be 0 if it is necessary to stop blur effect.
        if (scrimsVisible) {
            if (!Flags.notificationShadeBlur()) {
                blur = 0
            }
        }

        if (!blurUtils.supportsBlursOnWindows()) {
            blur = 0
        }

        return Pair(blur, zoomOut)
    }

    private fun blurRadiusToZoomOut(blurRadius: Float): Float {
        val disableZoomForMode =
            if (checkDesktopModeForSpacialModelAppPushback()) {
                desktopMode
                    .map { dm ->
                        dm.isDisplayInDesktopMode(shadeDisplaysRepository.get().displayId.value)
                    }
                    .orElse(false)
            } else {
                shadeModeInteractor.isSplitShade
            }

        val zoomOut =
            when {
                disableZoomForMode -> 0f
                scrimsVisible -> 0f
                else -> MathUtils.saturate(blurUtils.ratioOfBlurRadius(blurRadius))
            }
        return zoomOut
    }

    private val shouldBlurBeOpaque: Boolean
        get() =
            if (Flags.notificationShadeBlur()) {
                false
            } else {
                scrimsVisible && !areBlursDisabledForAppLaunch
            }

    @VisibleForTesting
    fun zoomOutAsScale(zoomOutProgress: Float): Float {
        val scale = 1.0f - zoomOutProgress * getPushbackScale(isHomeFocused)
        return (scale * SHADE_BLUR_SCALE_STEPS).roundToInt() / SHADE_BLUR_SCALE_STEPS
    }

    private fun quantizeShadeBlurRadius(blurRadius: Float): Int {
        val blur = blurRadius.toInt()
        if (blur <= SHADE_BLUR_STEP_PX) {
            return blur
        }
        val quantized =
            ((blur + SHADE_BLUR_STEP_PX / 2) / SHADE_BLUR_STEP_PX) * SHADE_BLUR_STEP_PX
        return MathUtils.constrain(quantized, 0, blurUtils.maxBlurRadius.toInt())
    }

    private fun getPushbackScale(isHomeFocused: Boolean): Float =
        if (isHomeFocused) PUSHBACK_SCALE_FOR_LAUNCHER else PUSHBACK_SCALE_FOR_APP

    /** Callback that updates the window blur value and is called only once per frame. */
    @VisibleForTesting
    val updateBlurCallback =
        Choreographer.FrameCallback {
            updateScheduled = false
            val (blur, zoomOutFromShadeRadius) = computeBlurAndZoomOut()
            val opaque = shouldBlurBeOpaque
            val blurScale = zoomOutAsScale(zoomOutFromShadeRadius)
            TrackTracer.instantForGroup("shade", "shade_blur_radius", blur)
            blurUtils.applyBlur(root.viewRootImpl, blur, opaque, blurScale)
            onBlurApplied(blur, zoomOutFromShadeRadius)
        }

    private fun onBlurApplied(appliedBlurRadius: Int, zoomOutFromShadeRadius: Float) {
        lastAppliedBlur = appliedBlurRadius
        onZoomOutChanged(zoomOutFromShadeRadius)
        listeners.forEach { it.onBlurRadiusChanged(appliedBlurRadius) }
        notificationShadeWindowController.setBackgroundBlurRadius(appliedBlurRadius)
    }

    private fun onZoomOutChanged(zoomOutFromShadeRadius: Float) {
        TrackTracer.instantForGroup("shade", "zoom_out", zoomOutFromShadeRadius)
        Log.v(TAG, "onZoomOutChanged $zoomOutFromShadeRadius")
        if (spatialModelAppPushback()) {
            keyguardInteractor.setZoomOut(zoomOutFromShadeRadius)
        }
    }

    private val applyZoomOutForFrame =
        Choreographer.FrameCallback {
            updateScheduled = false
            val (_, zoomOutFromShadeRadius) = computeBlurAndZoomOut()
            onZoomOutChanged(zoomOutFromShadeRadius)
        }

    /** Animate blurs when unlocking. */
    private val keyguardStateCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardFadingAwayChanged() {
                if (
                    !keyguardStateController.isKeyguardFadingAway ||
                        biometricUnlockController.mode != MODE_WAKE_AND_UNLOCK
                ) {
                    return
                }

                keyguardAnimator?.cancel()
                keyguardAnimator =
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        // keyguardStateController.keyguardFadingAwayDuration might be zero when
                        // unlock by fingerprint due to there is no window container, see
                        // AppTransition#goodToGo. We use DozeParameters.wallpaperFadeOutDuration as
                        // an alternative.
                        duration = dozeParameters.wallpaperFadeOutDuration
                        startDelay = keyguardStateController.keyguardFadingAwayDelay
                        interpolator = Interpolators.FAST_OUT_SLOW_IN
                        addUpdateListener { animation: ValueAnimator ->
                            wakeAndUnlockBlurRadius =
                                blurUtils.blurRadiusOfRatioForAod(animation.animatedValue as Float)
                        }
                        addListener(
                            object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    keyguardAnimator = null
                                    wakeAndUnlockBlurRadius = 0f
                                }
                            }
                        )
                        start()
                    }
            }

            override fun onKeyguardShowingChanged() {
                if (keyguardStateController.isShowing) {
                    keyguardAnimator?.cancel()
                    notificationAnimator?.cancel()
                }
            }
        }

    private val statusBarStateCallback =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                if (Flags.noShadeBlurOnDreamStart()) {
                    if (
                        existingStatusBarState == StatusBarState.KEYGUARD &&
                            newState == StatusBarState.SHADE &&
                            keyguardInteractor.isDreaming.value
                    ) {
                        return
                    }
                }

                existingStatusBarState = newState

                updateShadeAnimationBlur(
                    shadeExpansion,
                    prevTracking,
                    prevShadeVelocity,
                    prevShadeDirection,
                )
                scheduleUpdate()
            }

            override fun onDozingChanged(isDozing: Boolean) {
                if (isDozing) {
                    shadeAnimation.finishIfRunning()
                    brightnessMirrorSpring.finishIfRunning()
                }
            }

            override fun onDozeAmountChanged(linear: Float, eased: Float) {
                prevDozeAmount = eased
                updateWakeBlurRadius(prevDozeAmount)
            }
        }

    private fun updateWakeBlurRadius(ratio: Float) {
        wakeAndUnlockBlurRadius = getNewWakeBlurRadius(ratio)
    }

    private fun getNewWakeBlurRadius(ratio: Float): Float {
        return if (!wallpaperSupportsAmbientMode) {
            0f
        } else {
            blurUtils.blurRadiusOfRatioForAod(ratio)
        }
    }

    init {
        dumpManager.registerCriticalDumpable(javaClass.name, this)
        if (WAKE_UP_ANIMATION_ENABLED) {
            keyguardStateController.addCallback(keyguardStateCallback)
        }
        statusBarStateController.addCallback(statusBarStateCallback)
        notificationShadeWindowController.setScrimsVisibilityListener {
            // Stop blur effect when scrims is opaque to avoid unnecessary GPU composition.
            visibility ->
            scrimsVisible = visibility == ScrimController.OPAQUE
        }
        shadeAnimation.setStiffness(SpringForce.STIFFNESS_LOW)
        shadeAnimation.setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
        brightnessMirrorSpring.setStiffness(SpringForce.STIFFNESS_LOW)
        brightnessMirrorSpring.setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
        applicationScope.launch {
            focusedDisplayRepository.globallyFocusedTask.collect { focusedTask ->
                if (focusedTask == null || focusedTask.displayId != DEFAULT_DISPLAY) return@collect
                isHomeFocused = (focusedTask.activityType == WindowConfiguration.ACTIVITY_TYPE_HOME)
            }
        }
        applicationScope.launch {
            wallpaperInteractor.wallpaperSupportsAmbientMode.collect { supported ->
                wallpaperSupportsAmbientMode = supported
                if (getNewWakeBlurRadius(prevDozeAmount) == wakeAndUnlockBlurRadius) {
                    // Update wake and unlock radius only if the previous value comes from wake-up.
                    updateWakeBlurRadius(prevDozeAmount)
                }
            }
        }
        initBlurListeners()
    }

    private fun initBlurListeners() {
        if (!Flags.bouncerUiRevamp()) return

        windowRootViewBlurInteractor.registerBlurAppliedListener { appliedBlurRadius ->
            if (updateScheduled) {
                // Process the blur applied event only if we scheduled the update
                TrackTracer.instantForGroup("shade", "shade_blur_radius", appliedBlurRadius)
                updateScheduled = false
                onBlurApplied(appliedBlurRadius, zoomOutCalculatedFromShadeRadius)
            } else {
                // Try scheduling an update now, maybe our blur request will be scheduled now.
                scheduleUpdate()
            }
        }

        applicationScope.launch {
            windowRootViewBlurInteractor.isBlurCurrentlySupported.collect { supported ->
                if (supported) {
                    // when battery saver changes, try scheduling an update.
                    scheduleUpdate()
                } else {
                    // when blur becomes unsupported, no more updates will be scheduled,
                    // reset updateScheduled state.
                    updateScheduled = false
                    // reset blur and internal state to 0
                    onBlurApplied(0, 0.0f)
                }
            }
        }
    }

    fun addListener(listener: DepthListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DepthListener) {
        listeners.remove(listener)
    }

    /** Update blurs when pulling down the shade */
    override fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
        val rawFraction = event.fraction
        val tracking = event.tracking
        val timestamp = SystemClock.elapsedRealtimeNanos()
        val expansion =
            MathUtils.saturate(
                (rawFraction - panelPullDownMinFraction) / (1f - panelPullDownMinFraction)
            )

        if (shadeExpansion == expansion && prevTracking == tracking) {
            prevTimestamp = timestamp
            return
        }

        var deltaTime = 1f
        if (prevTimestamp < 0) {
            prevTimestamp = timestamp
        } else {
            deltaTime =
                MathUtils.constrain(((timestamp - prevTimestamp) / 1E9).toFloat(), 0.00001f, 1f)
        }

        val diff = expansion - shadeExpansion
        val shadeDirection = sign(diff).toInt()
        val shadeVelocity =
            MathUtils.constrain(VELOCITY_SCALE * diff / deltaTime, MIN_VELOCITY, MAX_VELOCITY)
        if (expansion == 0.0f && appLaunchTransitionIsInProgress && !blursDisabledForAppLaunch) {
            // Shade expansion finished but the app launch is already done, then this should mark
            // the transition as done.
            Log.d(TAG, "appLaunchTransitionIsInProgress is now false from shade expansion event")
            appLaunchTransitionIsInProgress = false
        }

        updateShadeAnimationBlur(expansion, tracking, shadeVelocity, shadeDirection)

        prevShadeDirection = shadeDirection
        prevShadeVelocity = shadeVelocity
        shadeExpansion = expansion
        prevTracking = tracking
        prevTimestamp = timestamp

        scheduleUpdate()
    }

    fun onTransitionAnimationProgress(progress: Float) {
        if (!Flags.notificationShadeBlur() || !Flags.moveTransitionAnimationLayer()) return
        // Because the Shade takes a few frames to actually trigger the unblur after a transition
        // has ended, we need to disable it manually, or the opening window itself will be blurred
        // for a few frames due to relative ordering. We do this towards the end, so that the
        // window is already covering the background and the unblur is not visible.
        if (progress >= TRANSITION_THRESHOLD && shadeAnimation.radius > 0) {
            blursDisabledForAppLaunch = true
        }
    }

    fun onTransitionAnimationEnd() {
        if (!Flags.notificationShadeBlur() || !Flags.moveTransitionAnimationLayer()) return
        blursDisabledForAppLaunch = false
    }

    private fun updateShadeAnimationBlur(
        expansion: Float,
        tracking: Boolean,
        velocity: Float,
        direction: Int,
    ) {
        if (shouldApplyShadeBlur()) {
            if (expansion > 0f) {
                // Blur view if user starts animating in the shade.
                if (isClosed) {
                    animateBlur(true, velocity)
                    isClosed = false
                }

                // If we were blurring out and the user stopped the animation, blur view.
                if (tracking && !isBlurred) {
                    animateBlur(true, 0f)
                }

                // If shade is being closed and the user isn't interacting with it, un-blur.
                if (!tracking && direction < 0 && isBlurred) {
                    animateBlur(false, velocity)
                }

                if (expansion == 1f) {
                    if (!isOpen) {
                        isOpen = true
                        // If shade is open and view is not blurred, blur.
                        if (!isBlurred) {
                            animateBlur(true, velocity)
                        }
                    }
                } else {
                    isOpen = false
                }
                // Automatic animation when the user closes the shade.
            } else if (!isClosed) {
                isClosed = true
                // If shade is closed and view is not blurred, blur.
                if (isBlurred) {
                    animateBlur(false, velocity)
                }
            }
        } else {
            animateBlur(false, 0f)
            isClosed = true
            isOpen = false
        }
    }

    private fun animateBlur(blur: Boolean, velocity: Float) {
        isBlurred = blur

        val targetBlurNormalized =
            if (blur && shouldApplyShadeBlur()) {
                1f
            } else {
                0f
            }

        shadeAnimation.setStartVelocity(velocity)
        shadeAnimation.animateTo(blurUtils.blurRadiusOfRatio(targetBlurNormalized).toInt())
    }

    private fun scheduleUpdate() {
        val (blur, zoomOutFromShadeRadius) = computeBlurAndZoomOut()
        zoomOutCalculatedFromShadeRadius = zoomOutFromShadeRadius

        // Do not blur or zoom out the wallpaper if the blurred wallpaper is not supported.
        if (!windowRootViewBlurInteractor.isBlurredWallpaperSupported) {
            return
        }

        if (Flags.bouncerUiRevamp() || Flags.glanceableHubBlurredBackground()) {
            if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
                updateScheduled =
                    windowRootViewBlurInteractor.requestBlurForShade(
                        blur,
                        zoomOutAsScale(zoomOutFromShadeRadius),
                    )
                return
            }
            // When blur is not supported, zoom out still needs to happen when scheduleUpdate
            // is invoked and a separate frame callback has to be wired-up to support that.
            if (!updateScheduled) {
                updateScheduled = true
                choreographer.postFrameCallback(applyZoomOutForFrame)
            }
            return
        }
        if (updateScheduled) {
            return
        }
        updateScheduled = true
        blurUtils.prepareBlur(blur)
        choreographer.postFrameCallback(updateBlurCallback)
    }

    /**
     * Should blur be applied to the shade currently. This is mainly used to make sure that on the
     * lockscreen, the wallpaper isn't blurred.
     */
    private fun shouldApplyShadeBlur(): Boolean {
        val state = statusBarStateController.state
        return (state == StatusBarState.SHADE || state == StatusBarState.SHADE_LOCKED) &&
            !keyguardStateController.isKeyguardFadingAway
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("shadeExpansion: $shadeExpansion")
            it.println("shouldApplyShadeBlur: ${shouldApplyShadeBlur()}")
            it.println("shadeAnimation: ${shadeAnimation.radius}")
            it.println("brightnessMirrorRadius: ${brightnessMirrorSpring.radius}")
            it.println("wakeAndUnlockBlurRadius: $wakeAndUnlockBlurRadius")
            it.println("blursDisabledForAppLaunch: $blursDisabledForAppLaunch")
            it.println("appLaunchTransitionIsInProgress: $appLaunchTransitionIsInProgress")
            it.println("qsPanelExpansion: $qsPanelExpansion")
            it.println("transitionToFullShadeProgress: $transitionToFullShadeProgress")
            it.println("lastAppliedBlur: $lastAppliedBlur")
        }
    }

    /**
     * Animation helper that smoothly animates the depth using a spring and deals with frame
     * invalidation.
     */
    inner class DepthAnimation() {
        /** Blur radius visible on the UI, in pixels. */
        var radius = 0f

        /** Depth ratio of the current blur radius. */
        val ratio
            get() = blurUtils.ratioOfBlurRadius(radius)

        /** Radius that we're animating to. */
        private var pendingRadius = -1

        private var springAnimation =
            SpringAnimation(
                this,
                object : FloatPropertyCompat<DepthAnimation>("blurRadius") {
                    override fun setValue(rect: DepthAnimation?, value: Float) {
                        radius = value
                        scheduleUpdate()
                    }

                    override fun getValue(rect: DepthAnimation?): Float {
                        return radius
                    }
                },
            )

        init {
            springAnimation.spring = SpringForce(0.0f)
            springAnimation.spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            springAnimation.spring.stiffness = SpringForce.STIFFNESS_HIGH
            springAnimation.addEndListener { _, _, _, _ -> pendingRadius = -1 }
        }

        /**
         * Starts an animation to [newRadius], or updates the current one if already ongoing.
         * IMPORTANT: do NOT use this method + [finishIfRunning] to instantaneously change the value
         * of the animation. The change will NOT be instantaneous. Use [skipTo] instead.
         *
         * Explanation:
         * 1. If idle, [SpringAnimation.animateToFinalPosition] requests a start to the animation.
         * 2. On the first frame after an idle animation is requested to start, the animation simply
         *    acquires the starting value and does nothing else.
         * 3. [SpringAnimation.skipToEnd] requests a fast-forward to the end value, but this happens
         *    during calculation of the next animation value. Because on the first frame no such
         *    calculation happens (point #2), there is one lagging frame where we still see the old
         *    value.
         */
        fun animateTo(newRadius: Int) {
            if (pendingRadius == newRadius) {
                return
            }
            pendingRadius = newRadius
            springAnimation.animateToFinalPosition(newRadius.toFloat())
        }

        /**
         * Instantaneously set a new blur radius to this animation. Always use this instead of
         * [animateTo] and [finishIfRunning] to make sure that the change takes effect in the next
         * frame. See the doc for [animateTo] for an explanation.
         */
        fun skipTo(newRadius: Int) {
            if (pendingRadius == newRadius) return
            pendingRadius = newRadius
            springAnimation.cancel()
            springAnimation.setStartValue(newRadius.toFloat())
            springAnimation.animateToFinalPosition(newRadius.toFloat())
        }

        fun finishIfRunning() {
            if (springAnimation.isRunning) {
                springAnimation.skipToEnd()
            }
        }

        fun setStiffness(stiffness: Float) {
            springAnimation.spring.stiffness = stiffness
        }

        fun setDampingRatio(dampingRation: Float) {
            springAnimation.spring.dampingRatio = dampingRation
        }

        fun setStartVelocity(velocity: Float) {
            springAnimation.setStartVelocity(velocity)
        }
    }

    /** Invoked when changes are needed in z-space */
    interface DepthListener {
        fun onBlurRadiusChanged(blurRadius: Int) {}
    }
}
