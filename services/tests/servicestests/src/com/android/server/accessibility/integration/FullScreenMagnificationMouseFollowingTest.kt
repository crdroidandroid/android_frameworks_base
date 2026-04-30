/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.accessibility.integration

import android.accessibility.cts.common.AccessibilityDumpOnFailureRule
import android.accessibility.cts.common.InstrumentedAccessibilityService
import android.accessibility.cts.common.InstrumentedAccessibilityServiceTestRule
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.MagnificationConfig
import android.app.Activity
import android.app.ActivityOptions
import android.app.UiAutomation
import android.app.WindowConfiguration
import android.graphics.PointF
import android.graphics.Rect
import android.os.OutcomeReceiver
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.Settings
import android.testing.PollingCheck
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SettingsStateChangerRule
import com.android.server.accessibility.Flags
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.desktop.DesktopMouseTestRule

// Convenient extension functions.
// Injecting mouse move is in integer in PX. Allowing at most 1px error.
private const val EPS = 1f
private fun Float.nearEq(other: Float) = abs(this - other) < EPS

/** End-to-end tests for full screen magnification following mouse cursor. */
@RunWith(AndroidJUnit4::class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MAGNIFICATION_FOLLOWS_MOUSE_WITH_POINTER_MOTION_FILTER)
class FullScreenMagnificationMouseFollowingTest {
    private val TAG = FullScreenMagnificationMouseFollowingTest::class.java.simpleName

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var uiAutomation: UiAutomation

    @get:Rule(order = 0)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule(order = 1)
    val immersiveModeConfirmationDialogSettingsRule =
        SettingsStateChangerRule(
            instrumentation.context,
            Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
            "confirmed"
        )

    @get:Rule(order = 2)
    val magnificationCapabilitySettingsRule =
        SettingsStateChangerRule(
            instrumentation.context,
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL.toString()
        )

    @get:Rule(order = 3)
    val magnificationModeSettingsRule =
        SettingsStateChangerRule(
            instrumentation.context,
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN.toString()
        )

    @get:Rule(order = 4)
    val desktopMouseRule = DesktopMouseTestRule()

    @get:Rule(order = 5)
    val magnificationAccessibilityServiceRule =
        InstrumentedAccessibilityServiceTestRule<TestMagnificationAccessibilityService>(
            TestMagnificationAccessibilityService::class.java, false
        )

    @get:Rule(order = 6)
    val a11yDumpRule: AccessibilityDumpOnFailureRule = AccessibilityDumpOnFailureRule()

    private lateinit var service: TestMagnificationAccessibilityService
    private lateinit var controller: AccessibilityService.MagnificationController
    private val displayId = Display.DEFAULT_DISPLAY
    private var activityScenario: ActivityScenario<TestActivity>? = null

    private lateinit var displaySize: Rect
    private var displayWidth = 0
    private var displayHeight = 0
    private var centerX = 0f
    private var centerY = 0f

    @Before
    fun setUp() {
        uiAutomation =
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        uiAutomation.serviceInfo =
            uiAutomation.serviceInfo!!.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

        // Disables showing a dialog to prevent it interfering with the test.
        uiAutomation
            .executeShellCommand("setprop debug.wm.disable_deprecated_target_sdk_dialog 1")
            .close()

        displaySize =
            instrumentation.context.getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.bounds
        displayWidth = displaySize.width()
        displayHeight = displaySize.height()
        centerX = displaySize.exactCenterX()
        centerY = displaySize.exactCenterY()

        launchTestActivityFullscreen(displayId)

        service = magnificationAccessibilityServiceRule.enableService()
        service.observingDisplayId = displayId

        controller = service.getMagnificationController(displayId)
    }

    @After
    fun cleanUp() {
        activityScenario?.close()

        instrumentation.waitForIdleSync()

        uiAutomation
            .executeShellCommand("setprop debug.wm.disable_deprecated_target_sdk_dialog 0")
            .close()
    }

    // Note on continuous movement:
    // Assume that the entire display is magnified, and the zoom level is z.
    // In continuous movement, mouse speed relative to the unscaled physical display is the same as
    // unmagnified speed. While, when a cursor moves from the left edge to the right edge of the
    // screen, the magnification center moves from the left bound to the right bound, which is
    // (display width) * (z - 1) / z.
    //
    // Similarly, when the mouse cursor moves by d in unscaled, display coordinates,
    // the magnification center moves by d * (z - 1) / z.

    @Test
    fun testContinuous_toBottomRight() {
        setCursorFollowingMode(
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CURSOR_FOLLOWING_MODE_CONTINUOUS
        )

        ensureMouseAtCenter()

        scaleTo(2f)
        assertMagnification(scale = 2f, centerX, centerY)

        // Move cursor by (10, 15)
        // This will move magnification center by (5, 7.5)
        sendMouseMove(10, 15)
        assertCursorLocation(centerX + 10, centerY + 15)
        assertMagnification(scale = 2f, centerX + 5, centerY + 7.5f)

        // Move cursor to the rest of the way to the edge.
        sendMouseMove(displayWidth - 10, displayHeight - 15)
        assertCursorLocation(displayWidth - 1f, displayHeight - 1f)
        assertMagnification(scale = 2f, displayWidth * 3f / 4, displayHeight * 3f / 4)

        // Move cursor further won't move the magnification.
        sendMouseMove(100, 100)
        assertCursorLocation(displayWidth - 1f, displayHeight - 1f)
    }

    @Test
    fun testContinuous_toTopLeft() {
        setCursorFollowingMode(
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CURSOR_FOLLOWING_MODE_CONTINUOUS
        )

        ensureMouseAtCenter()

        scaleTo(3f)
        assertMagnification(scale = 3f, centerX, centerY)

        // Move cursor by (-30, -15)
        // This will move magnification center by (-20, -10)
        sendMouseMove(-30, -15)
        assertCursorLocation(centerX - 30, centerY - 15)
        assertMagnification(scale = 3f, centerX - 20, centerY - 10)

        // Move cursor to the rest of the way to the edge.
        sendMouseMove(-centerX.toInt() + 30, -centerY.toInt() + 15)
        assertCursorLocation(0f, 0f)
        assertMagnification(scale = 3f, displayWidth / 6f, displayHeight / 6f)

        // Move cursor further won't move the magnification.
        sendMouseMove(-100, -100)
        assertCursorLocation(0f, 0f)
        assertMagnification(scale = 3f, displayWidth / 6f, displayHeight / 6f)
    }

    private fun setCursorFollowingMode(mode: Int) {
        Settings.Secure.putInt(
            instrumentation.context.contentResolver,
            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CURSOR_FOLLOWING_MODE,
            mode
        )
    }

    private fun ensureMouseAtCenter() {
        desktopMouseRule.move(displayId, centerX.toInt(), centerY.toInt())
    }

    private fun sendMouseMove(dx: Int, dy: Int) {
        desktopMouseRule.moveDelta(dx, dy)
    }

    /**
     * Asserts that the cursor location is at the specified coordinates. The coordinates
     * are in the non-scaled, display coordinates.
     */
    private fun assertCursorLocation(x: Float, y: Float) {
        PollingCheck.check("Wait for the cursor at ($x, $y)", CURSOR_TIMEOUT.inWholeMilliseconds) {
            service.lastObservedCursorLocation?.let { it.x.nearEq(x) && it.y.nearEq(y) } ?: false
        }
    }

    private fun scaleTo(scale: Float) {
        val config =
            MagnificationConfig.Builder()
                .setActivated(true)
                .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(scale)
                .build()
        val setResult = BooleanArray(1)
        service.runOnServiceSync { setResult[0] = controller.setMagnificationConfig(config, false) }
        assertThat(setResult[0]).isTrue()
    }

    private fun assertMagnification(
        scale: Float = Float.NaN, centerX: Float = Float.NaN, centerY: Float = Float.NaN
    ) {
        PollingCheck.check(
            "Wait for the magnification to scale=$scale, centerX=$centerX, centerY=$centerY",
            MAGNIFICATION_TIMEOUT.inWholeMilliseconds
        ) check@{
            val actual = controller.getMagnificationConfig() ?: return@check false
            val result = actual.isActivated &&
                (actual.mode == MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN) &&
                (scale.isNaN() || scale.nearEq(actual.scale)) &&
                (centerX.isNaN() || centerX.nearEq(actual.centerX)) &&
                (centerY.isNaN() || centerY.nearEq(actual.centerY))

            // Log check results during polling.
            if (!result) {
                Log.d(TAG, "Actual config: $actual")
            }

            result
        }
    }

    /**
     * Launches a test (empty) activity and makes it fullscreen on the specified display. This
     * ensures that system bars are hidden and the full screen magnification enlarges the entire
     * display.
     */
    private fun launchTestActivityFullscreen(displayId: Int) {
        activityScenario = ActivityScenario.launch(
            TestActivity::class.java,
            ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle()
        )

        val future = CompletableFuture<Void?>()
        val fullscreenCallback =
            object : OutcomeReceiver<Void, Throwable> {
                override fun onResult(result: Void?) {
                    future.complete(null)
                }
                override fun onError(error: Throwable) {
                    future.completeExceptionally(error)
                }
            }

        activityScenario!!.moveToState(Lifecycle.State.RESUMED).onActivity { activity ->
            // Lay out content ignoring display cutout and insets, to make the window fullscreen.
            activity.window.attributes =
                activity.window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            activity.window.insetsController?.hide(WindowInsets.Type.all())

            val windowingMode =
                activity.resources.configuration.windowConfiguration.windowingMode
            if (windowingMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                // Already fullscreen. No need to toggle.
                future.complete(null)
            } else {
                activity.requestFullscreenMode(
                    Activity.FULLSCREEN_MODE_REQUEST_ENTER,
                    fullscreenCallback
                )
            }
        }
        future.get(UI_IDLE_GLOBAL_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)

        uiAutomation.waitForIdle(
            UI_IDLE_TIMEOUT.inWholeMilliseconds, UI_IDLE_GLOBAL_TIMEOUT.inWholeMilliseconds
        )
    }

    class TestMagnificationAccessibilityService : InstrumentedAccessibilityService() {
        private val lock = Any()

        var observingDisplayId = Display.INVALID_DISPLAY
            set(v) {
                synchronized(lock) { field = v }
            }

        var lastObservedCursorLocation: PointF? = null
            private set
            get() {
                synchronized(lock) {
                    return field
                }
            }

        override fun onServiceConnected() {
            serviceInfo =
                getServiceInfo()!!.apply { setMotionEventSources(InputDevice.SOURCE_MOUSE) }

            super.onServiceConnected()
        }

        override fun onMotionEvent(event: MotionEvent) {
            super.onMotionEvent(event)

            synchronized(lock) {
                if (event.displayId == observingDisplayId) {
                    lastObservedCursorLocation = PointF(event.x, event.y)
                }
            }
        }
    }

    class TestActivity : Activity()

    companion object {
        private val CURSOR_TIMEOUT = 1.seconds
        private val MAGNIFICATION_TIMEOUT = 3.seconds
        private val UI_IDLE_TIMEOUT = 500.milliseconds
        private val UI_IDLE_GLOBAL_TIMEOUT = 5.seconds
    }
}
