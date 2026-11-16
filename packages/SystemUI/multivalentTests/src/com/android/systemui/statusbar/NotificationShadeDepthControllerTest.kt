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

import android.os.IBinder
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.view.Choreographer
import android.view.ViewRootImpl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.desktop.DesktopModeRepository
import com.android.systemui.desktop.desktopModeRepository
import com.android.systemui.display.data.repository.FocusedDisplayRepository
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.data.repository.fakeShadeDisplaysRepository
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.WallpaperController
import com.android.systemui.util.mockito.eq
import com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import com.android.systemui.window.domain.interactor.windowRootViewBlurInteractor
import com.android.wm.shell.desktopmode.api.DesktopMode
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers.gt
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.atLeastOnce

@RunWith(AndroidJUnit4::class)
@RunWithLooper
@SmallTest
class NotificationShadeDepthControllerTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val applicationScope = kosmos.testScope.backgroundScope
    private val shadeDisplayRepository = kosmos.fakeShadeDisplaysRepository
    private val backgroundScope: CoroutineScope = kosmos.backgroundScope
    private val backgroundExecutor: Executor = kosmos.fakeExecutor
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var blurUtils: BlurUtils
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var keyguardInteractor: KeyguardInteractor
    @Mock private lateinit var choreographer: Choreographer
    @Mock private lateinit var wallpaperController: WallpaperController
    @Mock private lateinit var wallpaperInteractor: WallpaperInteractor
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    private lateinit var windowRootViewBlurInteractor: WindowRootViewBlurInteractor
    @Mock private lateinit var shadeModeInteractor: ShadeModeInteractor
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var focusedDisplayRepository: FocusedDisplayRepository
    @Mock private lateinit var root: WindowRootView
    @Mock private lateinit var viewRootImpl: ViewRootImpl
    @Mock private lateinit var windowToken: IBinder
    @Mock private lateinit var shadeAnimation: NotificationShadeDepthController.DepthAnimation
    @Mock private lateinit var brightnessSpring: NotificationShadeDepthController.DepthAnimation
    @Mock private lateinit var listener: NotificationShadeDepthController.DepthListener
    @Mock private lateinit var dozeParameters: DozeParameters
    @Mock private lateinit var desktopMode: DesktopMode
    @Captor private lateinit var scrimVisibilityCaptor: ArgumentCaptor<Consumer<Int>>
    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var statusBarStateListener: StatusBarStateController.StateListener
    private var statusBarState = StatusBarState.SHADE
    private val maxBlur = 150
    private lateinit var notificationShadeDepthController: NotificationShadeDepthController
    private lateinit var desktopModeRepository: DesktopModeRepository

    private val dreamingFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        // Constructs kosmos.windowRootViewBlurInteractor after test starts to avoid the error that
        // the locked flag is read outside of the test code.
        windowRootViewBlurInteractor = Mockito.spy(kosmos.windowRootViewBlurInteractor)
        kosmos.desktopModeRepository =
            DesktopModeRepository(backgroundExecutor, backgroundScope, Optional.of(desktopMode))
        desktopModeRepository = kosmos.desktopModeRepository

        `when`(root.viewRootImpl).thenReturn(viewRootImpl)
        `when`(root.windowToken).thenReturn(windowToken)
        `when`(root.isAttachedToWindow).thenReturn(true)
        `when`(statusBarStateController.state).then { statusBarState }
        `when`(blurUtils.blurRadiusOfRatio(anyFloat())).then { answer ->
            answer.arguments[0] as Float * maxBlur.toFloat()
        }
        `when`(blurUtils.blurRadiusOfRatioForAod(anyFloat())).then { answer ->
            answer.arguments[0] as Float * maxBlur.toFloat() / 2
        }
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).then { answer ->
            answer.arguments[0] as Float / maxBlur.toFloat()
        }
        `when`(blurUtils.supportsBlursOnWindows()).thenReturn(true)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur.toFloat())
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur.toFloat())
        `when`(windowRootViewBlurInteractor.isBlurCurrentlySupported)
            .thenReturn(MutableStateFlow(true))
        `when`(keyguardInteractor.isDreaming).thenReturn(dreamingFlow)
        `when`(desktopMode.isDisplayInDesktopMode(anyInt())).thenReturn(false)

        notificationShadeDepthController =
            NotificationShadeDepthController(
                statusBarStateController,
                blurUtils,
                biometricUnlockController,
                keyguardStateController,
                keyguardInteractor,
                choreographer,
                wallpaperInteractor,
                notificationShadeWindowController,
                dozeParameters,
                shadeModeInteractor,
                windowRootViewBlurInteractor,
                { shadeDisplayRepository },
                focusedDisplayRepository,
                applicationScope,
                desktopModeRepository,
                dumpManager,
            )
        notificationShadeDepthController.shadeAnimation = shadeAnimation
        notificationShadeDepthController.brightnessMirrorSpring = brightnessSpring
        notificationShadeDepthController.root = root

        val captor = ArgumentCaptor.forClass(StatusBarStateController.StateListener::class.java)
        verify(statusBarStateController).addCallback(captor.capture())
        statusBarStateListener = captor.value
        verify(notificationShadeWindowController)
            .setScrimsVisibilityListener(scrimVisibilityCaptor.capture())

        disableSplitShade()
    }

    @Test
    fun setupListeners() {
        verify(dumpManager)
            .registerCriticalDumpable(anyString(), eq(notificationShadeDepthController))
    }

    @Test
    fun onPanelExpansionChanged_apliesBlur_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        verify(shadeAnimation).animateTo(eq(maxBlur))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurIn_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 0.01f, expanded = false, tracking = false)
        )
        verify(shadeAnimation).animateTo(eq(maxBlur))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurOut_ifShade() {
        onPanelExpansionChanged_animatesBlurIn_ifShade()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 0f, expanded = false, tracking = false)
        )
        verify(shadeAnimation).animateTo(eq(0))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurOut_ifFlick() {
        val event = ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(event)
        verify(shadeAnimation, never()).animateTo(anyInt())

        notificationShadeDepthController.onPanelExpansionChanged(
            event.copy(fraction = 0.9f, tracking = true)
        )
        verify(shadeAnimation, never()).animateTo(anyInt())

        notificationShadeDepthController.onPanelExpansionChanged(
            event.copy(fraction = 0.8f, tracking = false)
        )
        verify(shadeAnimation).animateTo(eq(0))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurIn_ifFlickCancelled() {
        onPanelExpansionChanged_animatesBlurOut_ifFlick()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 0.6f, expanded = true, tracking = true)
        )
        verify(shadeAnimation).animateTo(eq(maxBlur))
    }

    @Test
    fun onPanelExpansionChanged_respectsMinPanelPullDownFraction() {
        val event = ShadeExpansionChangeEvent(fraction = 0.5f, expanded = true, tracking = true)
        notificationShadeDepthController.panelPullDownMinFraction = 0.5f
        notificationShadeDepthController.onPanelExpansionChanged(event)
        assertThat(notificationShadeDepthController.shadeExpansion).isEqualTo(0f)

        notificationShadeDepthController.onPanelExpansionChanged(event.copy(fraction = 0.75f))
        assertThat(notificationShadeDepthController.shadeExpansion).isEqualTo(0.5f)

        notificationShadeDepthController.onPanelExpansionChanged(event.copy(fraction = 1f))
        assertThat(notificationShadeDepthController.shadeExpansion).isEqualTo(1f)
    }

    @Test
    fun onStateChanged_reevalutesBlurs_ifSameRadiusAndNewState() {
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(choreographer)

        statusBarState = StatusBarState.KEYGUARD
        statusBarStateListener.onStateChanged(statusBarState)
        verify(shadeAnimation).skipTo(eq(0))
    }

    @Test
    @EnableFlags(Flags.FLAG_NO_SHADE_BLUR_ON_DREAM_START)
    fun onStateChanged_noBlurEvaluation_ifShadeAndDreaming() {
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(choreographer)
        statusBarState = StatusBarState.KEYGUARD
        statusBarStateListener.onStateChanged(statusBarState)
        verify(shadeAnimation).skipTo(eq(0))

        clearInvocations(shadeAnimation)
        statusBarState = StatusBarState.SHADE
        dreamingFlow.value = true
        statusBarStateListener.onStateChanged(statusBarState)
        verify(shadeAnimation, never()).animateTo(anyInt())
    }

    @Test
    fun setQsPanelExpansion_appliesBlur() {
        statusBarState = StatusBarState.KEYGUARD
        notificationShadeDepthController.qsPanelExpansion = 1f
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false), anyFloat())
    }

    @Test
    fun setQsPanelExpansion_easing() {
        statusBarState = StatusBarState.KEYGUARD
        notificationShadeDepthController.qsPanelExpansion = 0.25f
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
    }

    @Test
    @DisableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_inSplitShade_setsZoomToZero() {
        enableSplitShade()

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
    }

    @Test
    @DisableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_notInSplitShade_setsZoomValue() {
        disableSplitShade()

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
    }

    @Test
    fun expandPanel_splitShadeEnabledChanged_setsCorrectZoomValueAfterChange() {
        disableSplitShade()
        val rawFraction = 1f
        val expanded = true
        val tracking = false
        val dragDownPxAmount = 0f
        val event = ShadeExpansionChangeEvent(rawFraction, expanded, tracking)
        val inOrder = Mockito.inOrder(wallpaperController)

        notificationShadeDepthController.onPanelExpansionChanged(event)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        enableSplitShade()
        notificationShadeDepthController.onPanelExpansionChanged(event)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
    }

    @Test
    @EnableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_notInDesktopMode_setsZoomValue() {
        `when`(brightnessSpring.ratio).thenReturn(0.5f)

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(keyguardInteractor, atLeastOnce()).setZoomOut(gt(0f))
    }

    @Test
    @EnableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_inDesktopMode_setsZoomToZero() {
        `when`(brightnessSpring.ratio).thenReturn(0.5f)
        `when`(desktopMode.isDisplayInDesktopMode(anyInt())).thenReturn(true)
        shadeDisplayRepository.setDisplayId(1) // Force flow to update

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(keyguardInteractor).setZoomOut(0f)
    }

    @Test
    @EnableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_notInDesktopMode_displayIdChange_setsZoomValue() {
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).thenReturn(0.5f)
        reset(keyguardInteractor)

        shadeDisplayRepository.setDisplayId(0)
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(keyguardInteractor, atLeastOnce()).setZoomOut(gt(0f))
    }

    @Test
    @DisableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_noSpatialModelInfo_inSplitShade_setsZoomToZero() {
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).thenReturn(0.5f)
        enableSplitShade()

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(keyguardInteractor).setZoomOut(0f)
    }

    @Test
    @DisableFlags(Flags.FLAG_CHECK_DESKTOP_MODE_FOR_SPACIAL_MODEL_APP_PUSHBACK)
    fun expandPanel_noSpatialModelInfo_notISplitShade_setsZoomValue() {
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).thenReturn(0.5f)
        disableSplitShade()

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(keyguardInteractor, atLeastOnce()).setZoomOut(gt(0f))
    }

    @Test
    fun setFullShadeTransition_appliesBlur() {
        notificationShadeDepthController.transitionToFullShadeProgress = 1f
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false), anyFloat())
    }

    @Test
    fun onDozeAmountChanged_doesNotApplyBlurWithAmbientAod() {
        notificationShadeDepthController.wallpaperSupportsAmbientMode = false

        statusBarStateListener.onDozeAmountChanged(1f, 1f)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false), anyFloat())
    }

    @Test
    fun onDozeAmountChanged_appliesBlurWithAmbientAod() {
        notificationShadeDepthController.wallpaperSupportsAmbientMode = true

        statusBarStateListener.onDozeAmountChanged(1f, 1f)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq((maxBlur / 2f).toInt()), eq(false), anyFloat())
    }

    @Test
    fun setFullShadeTransition_appliesBlur_onlyIfSupported() {
        reset(blurUtils)
        `when`(blurUtils.blurRadiusOfRatio(anyFloat())).then { answer ->
            answer.arguments[0] as Float * maxBlur
        }
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).then { answer ->
            answer.arguments[0] as Float / maxBlur.toFloat()
        }
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur.toFloat())
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.transitionToFullShadeProgress = 1f
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false), anyFloat())
    }

    @Test
    fun updateBlurCallback_setsBlurAndZoom() {
        notificationShadeDepthController.addListener(listener)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), anyInt(), eq(false), anyFloat())
    }

    @Test
    fun updateBlurCallback_shadeInExternalDisplay_doesSetZeroZoom() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.addListener(listener)
        shadeDisplayRepository.setDisplayId(1) // not default display.

        notificationShadeDepthController.updateBlurCallback.doFrame(0)
    }

    @Test
    fun updateBlurCallback_shadeInDefaultDisplay_doesNotSetZeroZoom() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        notificationShadeDepthController.addListener(listener)
        shadeDisplayRepository.setDisplayId(0) // shade is in default display

        notificationShadeDepthController.updateBlurCallback.doFrame(0)
    }

    @Test
    @DisableFlags(Flags.FLAG_NOTIFICATION_SHADE_BLUR)
    fun updateBlurCallback_setsOpaque_whenScrim() {
        scrimVisibilityCaptor.value.accept(ScrimController.OPAQUE)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), anyInt(), eq(true), anyFloat())
    }

    @Test
    fun updateBlurCallback_setsBlur_whenExpanded() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false), anyFloat())
    }

    @Test
    fun updateBlurCallback_ignoreShadeBlurUntilHidden_overridesZoom() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false), anyFloat())
    }

    @Test
    @DisableFlags(Flags.FLAG_BOUNCER_UI_REVAMP, Flags.FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND)
    fun ignoreShadeBlurUntilHidden_schedulesFrame() {
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(blurUtils).prepareBlur(anyInt())
        verify(choreographer)
            .postFrameCallback(eq(notificationShadeDepthController.updateBlurCallback))
    }

    @Test
    @EnableFlags(Flags.FLAG_BOUNCER_UI_REVAMP)
    fun ignoreShadeBlurUntilHidden_requestsBlur_windowBlurFlag() {
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(windowRootViewBlurInteractor).requestBlurForShade(anyInt(), anyFloat())
    }

    @Test
    fun ignoreBlurForUnlock_ignores() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.blursDisabledForAppLaunch = false
        notificationShadeDepthController.blursDisabledForUnlock = true

        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        // Since we are ignoring blurs for unlock, we should be applying blur = 0 despite setting it
        // to maxBlur above.
        verify(blurUtils).applyBlur(any(), eq(0), eq(false), anyFloat())
    }

    @Test
    fun ignoreBlurForUnlock_doesNotIgnore() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.blursDisabledForAppLaunch = false
        notificationShadeDepthController.blursDisabledForUnlock = false

        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        // Since we are not ignoring blurs for unlock (or app launch), we should apply the blur we
        // returned above (maxBlur).
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false), anyFloat())
    }

    @Test
    fun brightnessMirrorVisible_whenVisible() {
        notificationShadeDepthController.brightnessMirrorVisible = true
        verify(brightnessSpring).animateTo(eq(maxBlur))
    }

    @Test
    fun brightnessMirrorVisible_whenHidden() {
        notificationShadeDepthController.brightnessMirrorVisible = false
        verify(brightnessSpring).animateTo(eq(0))
    }

    @Test
    @DisableFlags(Flags.FLAG_BOUNCER_UI_REVAMP, Flags.FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND)
    fun brightnessMirror_hidesShadeBlur_withAppPushback() {
        // Brightness mirror is fully visible
        `when`(brightnessSpring.ratio).thenReturn(1f)
        // And shade is blurred
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(notificationShadeWindowController).setBackgroundBlurRadius(eq(0))
        verify(blurUtils).prepareBlur(eq(0))
        verify(blurUtils)
            .applyBlur(
                eq(viewRootImpl),
                eq(0),
                eq(false),
                eq(notificationShadeDepthController.zoomOutAsScale(0f)),
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_BOUNCER_UI_REVAMP)
    fun brightnessMirror_hidesShadeBlur_withWindowBlurFlagAndAppPushback() {
        // Brightness mirror is fully visible
        `when`(brightnessSpring.ratio).thenReturn(1f)
        // And shade is blurred
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(fraction = 1f, expanded = true, tracking = false)
        )
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(notificationShadeWindowController).setBackgroundBlurRadius(eq(0))
        verify(windowRootViewBlurInteractor)
            .requestBlurForShade(eq(0), eq(notificationShadeDepthController.zoomOutAsScale(0f)))
    }

    @Test
    fun ignoreShadeBlurUntilHidden_whennNull_ignoresIfShadeHasNoBlur() {
        `when`(shadeAnimation.radius).thenReturn(0f)
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(shadeAnimation, never()).animateTo(anyInt())
    }

    private fun enableSplitShade() {
        `when`(shadeModeInteractor.isSplitShade).thenReturn(true)
    }

    private fun disableSplitShade() {
        `when`(shadeModeInteractor.isSplitShade).thenReturn(false)
    }
}
