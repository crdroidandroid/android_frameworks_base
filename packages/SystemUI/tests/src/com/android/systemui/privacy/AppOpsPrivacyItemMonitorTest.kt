/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.os.UserHandle
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth
import java.io.PrintWriter
import java.io.StringWriter
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@RunWithLooper
class AppOpsPrivacyItemMonitorTest : SysuiTestCase() {

    companion object {
        val CURRENT_USER_ID = 1
        val TEST_UID = CURRENT_USER_ID * UserHandle.PER_USER_RANGE
        const val TEST_PACKAGE_NAME = "test"
        private const val MAPS_PACKAGE_NAME = "com.google.android.apps.maps"

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()

        fun <T> eq(value: T): T = Mockito.eq(value) ?: value

        fun <T> any(): T = Mockito.any<T>()
    }

    @Mock private lateinit var appOpsController: AppOpsController

    @Mock private lateinit var callback: PrivacyItemMonitor.Callback

    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var privacyConfig: PrivacyConfig

    @Mock private lateinit var logger: PrivacyLogger

    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var activityManager: ActivityManager

    @Captor private lateinit var argCaptorConfigCallback: ArgumentCaptor<PrivacyConfig.Callback>

    @Captor private lateinit var argCaptorCallback: ArgumentCaptor<AppOpsController.Callback>

    private lateinit var appOpsPrivacyItemMonitor: AppOpsPrivacyItemMonitor
    private lateinit var executor: FakeExecutor
    private lateinit var clock: FakeSystemClock
    private lateinit var uiEventLogger: UiEventLoggerFake
    private lateinit var locationAccumulatedLogger: LocationAccumulatedLogger

    fun createAppOpsPrivacyItemMonitor(): AppOpsPrivacyItemMonitor {
        return AppOpsPrivacyItemMonitor(
            appOpsController,
            userTracker,
            privacyConfig,
            executor,
            logger,
            packageManager,
            activityManager,
            mContext,
            uiEventLogger,
            locationAccumulatedLogger,
        )
    }

    @Before
    fun setup() {
        clock = FakeSystemClock()
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(clock)
        uiEventLogger = UiEventLoggerFake()
        locationAccumulatedLogger = LocationAccumulatedLogger(clock)

        // Listen to everything by default
        `when`(privacyConfig.micCameraAvailable).thenReturn(true)
        `when`(privacyConfig.locationAvailable).thenReturn(true)
        `when`(userTracker.userProfiles)
            .thenReturn(listOf(UserInfo(CURRENT_USER_ID, TEST_PACKAGE_NAME, 0)))

        appOpsPrivacyItemMonitor = createAppOpsPrivacyItemMonitor()
        verify(privacyConfig).addCallback(capture(argCaptorConfigCallback))
    }

    @Test
    fun testStartListeningAddsAppOpsCallback() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(appOpsController).addCallback(eq(AppOpsPrivacyItemMonitor.OPS), any())
    }

    @Test
    fun testStopListeningRemovesAppOpsCallback() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(appOpsController, never()).removeCallback(any(), any())

        appOpsPrivacyItemMonitor.stopListening()
        executor.runAllReady()
        verify(appOpsController).removeCallback(eq(AppOpsPrivacyItemMonitor.OPS), any())
    }

    @Test
    fun testDistinctItems() {
        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertEquals(1, appOpsPrivacyItemMonitor.getActivePrivacyItems().size)
    }

    @Test
    fun testVoiceActivationPrivacyItems() {
        doReturn(
                listOf(
                    AppOpItem(
                        AppOpsManager.OP_RECEIVE_SANDBOX_TRIGGER_AUDIO,
                        TEST_UID,
                        TEST_PACKAGE_NAME,
                        0,
                    )
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())
        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_MICROPHONE, privacyItems[0].privacyType)
    }

    @Test
    fun testSimilarItemsDifferentTimeStamp() {
        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 1),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertEquals(2, appOpsPrivacyItemMonitor.getActivePrivacyItems().size)
    }

    @Test
    fun testRegisterUserTrackerCallback() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(userTracker, atLeastOnce())
            .addCallback(eq(appOpsPrivacyItemMonitor.userTrackerCallback), any())
        verify(userTracker, never())
            .removeCallback(eq(appOpsPrivacyItemMonitor.userTrackerCallback))
    }

    @Test
    fun testUserTrackerCallback_userChanged() {
        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(0, mContext)
        executor.runAllReady()
        verify(userTracker).userProfiles
    }

    @Test
    fun testUserTrackerCallback_profilesChanged() {
        appOpsPrivacyItemMonitor.userTrackerCallback.onProfilesChanged(emptyList())
        executor.runAllReady()
        verify(userTracker).userProfiles
    }

    @Test
    fun testCallbackIsUpdated() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        reset(callback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
            AppOpsManager.OP_CAMERA,
            TEST_UID,
            TEST_PACKAGE_NAME,
            true,
        )
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged()
    }

    @Test
    fun testRemoveCallback() {
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        reset(callback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        appOpsPrivacyItemMonitor.stopListening()
        argCaptorCallback.value.onActiveStateChanged(0, TEST_UID, TEST_PACKAGE_NAME, true)
        executor.runAllReady()
        verify(callback, never()).onPrivacyItemsChanged()
    }

    @Test
    fun testListShouldNotHaveNull() {
        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_ACTIVATE_VPN, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertThat(appOpsPrivacyItemMonitor.getActivePrivacyItems(), not(hasItem(nullValue())))
    }

    @Test
    fun testNotListeningWhenIndicatorsDisabled() {
        changeMicCamera(false)
        changeLocation(false)

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        verify(appOpsController, never()).addCallback(eq(AppOpsPrivacyItemMonitor.OPS), any())
    }

    @Test
    fun testNotSendingLocationWhenLocationDisabled() {
        changeLocation(false)
        executor.runAllReady()

        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_COARSE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_CAMERA, privacyItems[0].privacyType)
    }

    @Test
    fun testCoarseLocationOpForeground() {
        // Set to non system.
        doReturn(512)
            .`when`(packageManager)
            .getPermissionFlags(
                "android.permission.ACCESS_COARSE_LOCATION",
                "com.google.android.apps.maps",
                UserHandle.getUserHandleForUid(TEST_UID),
            )
        // Default is foreground.
        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        doReturn(
                listOf(
                    AppOpItem(
                        AppOpsManager.OP_COARSE_LOCATION,
                        TEST_UID,
                        "com.google.android.apps.maps",
                        0,
                    )
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        val result = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(result.size, 1)
        assertEquals(PrivacyType.TYPE_LOCATION, result[0].privacyType)
        assertEquals(result[0].application.packageName, "com.google.android.apps.maps")
        // Expect logs for NON_SYSTEM_APP, SYSTEM_APP, BACKGROUND_APP, and ALL_APP when location
        // is first used.
        assertEquals(uiEventLogger.numLogs(), 4)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_NON_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOpForeground() {
        setMapsToNonSystem()

        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_FOREGROUND_SERVICE
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        var result = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(result.size, 1)
        assertEquals(result[0].application.packageName, MAPS_PACKAGE_NAME)

        // Expect logs for NON_SYSTEM_APP, SYSTEM_APP, BACKGROUND_APP, and ALL_APP when location
        // is first used.
        assertEquals(uiEventLogger.numLogs(), 4)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_NON_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()

        // Simulate a new round of appOps and confirm that there are no additional logs because the
        // indicator is already showing.
        result = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(result.size, 1)
        assertEquals(result[0].application.packageName, MAPS_PACKAGE_NAME)
        // Assert no additional logging events
        assertEquals(uiEventLogger.numLogs(), 4)

        // Simulate a round of appOps where location is disabled so that the indicator disappears.
        doReturn(listOf<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        result = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(result.size, 0)
        // Assert OFF events are logged
        assertEquals(uiEventLogger.numLogs(), 8)

        // Simulate a round of appOps where the location indicator appears again and the logging
        // count increases.
        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())
        result = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(result.size, 1)
        assertEquals(result[0].application.packageName, MAPS_PACKAGE_NAME)
        // Assert there are additional logging events
        assertEquals(uiEventLogger.numLogs(), 12)
        Truth.assertThat(
                uiEventLogger.logs.count { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_NON_SYSTEM_APP
                            .id
                }
            )
            .isEqualTo(2)
    }

    @Test
    fun testLocationOpForegroundOff() {
        setMapsToNonSystem()

        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_FOREGROUND_SERVICE
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        // First, location is used by a foreground app
        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        var result = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(result.size, 1)
        // Expect logs for NON_SYSTEM_APP, SYSTEM_APP, BACKGROUND_APP, and ALL_APP when location
        // is first used.
        assertEquals(uiEventLogger.numLogs(), 4)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_NON_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()

        // Then, location is not used anymore
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        result = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(result.size, 0)

        // Then we log the OFF events
        assertEquals(uiEventLogger.numLogs(), 8)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_NON_SYSTEM_APP_OFF
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP_OFF
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP_OFF
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_ALL_APP_OFF
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOpSystemOff() {
        // First, location is used by a system app
        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, "com.google.android.gms", 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_FOREGROUND_SERVICE
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)
        // Expect logs for SYSTEM_APP and ALL_APP when location is first used by a system app.
        assertEquals(uiEventLogger.numLogs(), 2)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()

        // Then, location is not used anymore
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)

        // Then we log the OFF events
        assertEquals(uiEventLogger.numLogs(), 4)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP_OFF
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_ALL_APP_OFF
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOpSystem() {
        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, "com.google.android.gms", 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_FOREGROUND_SERVICE
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)
        // Expect logs for SYSTEM_APP and ALL_APP.
        assertEquals(uiEventLogger.numLogs(), 2)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOpBackgroundOff() {
        setMapsToNonSystem()

        // Set to background
        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_CACHED
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        // First, location is used by a background app
        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)
        // Expect logs for BACKGROUND_APP and ALL_APP when location is first used by a background
        // app.
        assertEquals(uiEventLogger.numLogs(), 2)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()

        // Then, location is not used anymore
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)

        // Then we log the OFF events
        assertEquals(uiEventLogger.numLogs(), 4)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP_OFF
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_ALL_APP_OFF
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOpBackground() {
        setMapsToNonSystem()

        // Set to background
        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_CACHED
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)
        // Expect logs for BACKGROUND_APP and ALL_APP.
        assertEquals(uiEventLogger.numLogs(), 2)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOp_getUidImportance_foreground() {
        setMapsToNonSystem()

        // Make the test uid not found
        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID + 1
        process.importance = IMPORTANCE_CACHED
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        doReturn(IMPORTANCE_FOREGROUND_SERVICE)
            .`when`(activityManager)
            .getUidImportance(eq(TEST_UID))

        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 1)
        // Expect logs for NON_SYSTEM_APP, SYSTEM_APP, BACKGROUND_APP, and ALL_APP when location
        // is first used.
        assertEquals(uiEventLogger.numLogs(), 4)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_NON_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_SYSTEM_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOp_getUidImportance_background() {
        setMapsToNonSystem()

        // Make the test uid not found
        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID + 1
        process.importance = IMPORTANCE_CACHED
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        doReturn(IMPORTANCE_CACHED).`when`(activityManager).getUidImportance(eq(TEST_UID))

        doReturn(
                listOf(
                    // Regular item which should not be filtered
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        assertEquals(appOpsPrivacyItemMonitor.getActivePrivacyItems().size, 0)
        // Expect logs for BACKGROUND_APP and ALL_APP.
        assertEquals(uiEventLogger.numLogs(), 2)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_BACKGROUND_APP
                            .id
                }
            )
            .isTrue()
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent.LOCATION_INDICATOR_ALL_APP
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLocationOpHighPowerOff() {
        // First, high power location is used
        doReturn(
                listOf(
                    AppOpItem(
                        AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION,
                        TEST_UID,
                        TEST_PACKAGE_NAME,
                        0,
                    )
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        // Expect a log for LOCATION_INDICATOR_MONITOR_HIGH_POWER when high power location is used.
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(uiEventLogger.numLogs(), 1)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_MONITOR_HIGH_POWER
                            .id
                }
            )
            .isTrue()

        // Then, it is not used anymore
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.getActivePrivacyItems()

        // Then we log the OFF event
        assertEquals(uiEventLogger.numLogs(), 2)
        Truth.assertThat(
                uiEventLogger.logs.any { log ->
                    log.eventId ==
                        AppOpsPrivacyItemMonitor.LocationIndicatorEvent
                            .LOCATION_INDICATOR_MONITOR_HIGH_POWER_OFF
                            .id
                }
            )
            .isTrue()
    }

    @Test
    fun testLogActiveChanged() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
            AppOpsManager.OP_FINE_LOCATION,
            TEST_UID,
            TEST_PACKAGE_NAME,
            true,
        )

        verify(logger)
            .logUpdatedItemFromAppOps(
                AppOpsManager.OP_FINE_LOCATION,
                TEST_UID,
                TEST_PACKAGE_NAME,
                true,
            )
    }

    @Test
    fun testLogCoarseLocationActiveChanged() {
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()
        reset(callback)

        verify(appOpsController).addCallback(any(), capture(argCaptorCallback))
        argCaptorCallback.value.onActiveStateChanged(
            AppOpsManager.OP_COARSE_LOCATION,
            TEST_UID,
            TEST_PACKAGE_NAME,
            true,
        )
        executor.runAllReady()

        verify(callback).onPrivacyItemsChanged()
        verify(logger)
            .logUpdatedItemFromAppOps(
                AppOpsManager.OP_COARSE_LOCATION,
                TEST_UID,
                TEST_PACKAGE_NAME,
                true,
            )
    }

    @Test
    fun testListRequestedShowPaused() {
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        verify(appOpsController).getActiveAppOps(true)
    }

    @Test
    fun testListFilterCurrentUser() {
        val otherUser = CURRENT_USER_ID + 1
        val otherUserUid = otherUser * UserHandle.PER_USER_RANGE
        `when`(userTracker.userProfiles)
            .thenReturn(listOf(UserInfo(otherUser, TEST_PACKAGE_NAME, 0)))

        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_CAMERA, otherUserUid, TEST_PACKAGE_NAME, 0),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_CAMERA, privacyItems[0].privacyType)
        assertEquals(otherUserUid, privacyItems[0].application.uid)
    }

    @Test
    fun testAlwaysGetPhoneCameraOps() {
        val otherUser = CURRENT_USER_ID + 1
        `when`(userTracker.userProfiles)
            .thenReturn(listOf(UserInfo(otherUser, TEST_PACKAGE_NAME, 0)))

        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_PHONE_CALL_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_CAMERA, privacyItems[0].privacyType)
    }

    @Test
    fun testAlwaysGetPhoneMicOps() {
        val otherUser = CURRENT_USER_ID + 1
        `when`(userTracker.userProfiles)
            .thenReturn(listOf(UserInfo(otherUser, TEST_PACKAGE_NAME, 0)))

        doReturn(
                listOf(
                    AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(AppOpsManager.OP_CAMERA, TEST_UID, TEST_PACKAGE_NAME, 0),
                    AppOpItem(
                        AppOpsManager.OP_PHONE_CALL_MICROPHONE,
                        TEST_UID,
                        TEST_PACKAGE_NAME,
                        0,
                    ),
                )
            )
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        appOpsPrivacyItemMonitor.userTrackerCallback.onUserChanged(otherUser, mContext)
        executor.runAllReady()

        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()

        assertEquals(1, privacyItems.size)
        assertEquals(PrivacyType.TYPE_MICROPHONE, privacyItems[0].privacyType)
    }

    @Test
    fun testDisabledAppOpIsPaused() {
        val item = AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0)
        item.isDisabled = true
        `when`(appOpsController.getActiveAppOps(anyBoolean())).thenReturn(listOf(item))

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertTrue(privacyItems[0].paused)
    }

    @Test
    fun testEnabledAppOpIsNotPaused() {
        val item = AppOpItem(AppOpsManager.OP_RECORD_AUDIO, TEST_UID, TEST_PACKAGE_NAME, 0)
        `when`(appOpsController.getActiveAppOps(anyBoolean())).thenReturn(listOf(item))

        val privacyItems = appOpsPrivacyItemMonitor.getActivePrivacyItems()
        assertEquals(1, privacyItems.size)
        assertFalse(privacyItems[0].paused)
    }

    @Test
    fun testDumpLogsLocationIndicatorDetails() {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)

        // Start listening to populate logging start time
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        // Given a non-system, foreground app is using location
        setMapsToNonSystem()

        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_FOREGROUND_SERVICE
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        doReturn(listOf(AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)))
            .`when`(appOpsController)
            .getActiveAppOps(anyBoolean())

        // When location is used
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        executor.runAllReady()

        clock.advanceTime(123456L)

        // ...and then not used
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        executor.runAllReady()

        // Check the dump
        appOpsPrivacyItemMonitor.dump(printWriter, emptyArray())

        val output = stringWriter.toString()
        Truth.assertThat(output).contains("NON_SYSTEM_FG: blinks=1, onDuration=2m")
        Truth.assertThat(output).contains("SYSTEM: blinks=1, onDuration=2m")
        Truth.assertThat(output).contains("BACKGROUND: blinks=1, onDuration=2m")
        Truth.assertThat(output).contains("ALL: blinks=1, onDuration=2m")
    }

    @Test
    fun testDumpLogsLocationIndicatorDetails_multipleBlinks() {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)

        // Start listening to populate logging start time
        appOpsPrivacyItemMonitor.startListening(callback)
        executor.runAllReady()

        // Given a non-system, foreground app is using location
        setMapsToNonSystem()

        val process = ActivityManager.RunningAppProcessInfo()
        process.uid = TEST_UID
        process.importance = IMPORTANCE_FOREGROUND_SERVICE
        doReturn(listOf(process)).`when`(activityManager).runningAppProcesses

        val locationAppOp =
            AppOpItem(AppOpsManager.OP_FINE_LOCATION, TEST_UID, MAPS_PACKAGE_NAME, 0)

        // Blink 1 on
        doReturn(listOf(locationAppOp)).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        executor.runAllReady()

        clock.advanceTime(60_000L) // 1 minute

        // Blink 1 off
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        executor.runAllReady()

        clock.advanceTime(10_000L) // 10 seconds off

        // Blink 2 on
        doReturn(listOf(locationAppOp)).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        executor.runAllReady()

        clock.advanceTime(120_000L) // 2 minutes

        // Blink 2 off
        doReturn(emptyList<AppOpItem>()).`when`(appOpsController).getActiveAppOps(anyBoolean())
        appOpsPrivacyItemMonitor.getActivePrivacyItems()
        executor.runAllReady()

        // Check the dump
        appOpsPrivacyItemMonitor.dump(printWriter, emptyArray())

        val output = stringWriter.toString()
        Truth.assertThat(output).contains("NON_SYSTEM_FG: blinks=2, onDuration=3m")
        Truth.assertThat(output).contains("SYSTEM: blinks=2, onDuration=3m")
        Truth.assertThat(output).contains("BACKGROUND: blinks=2, onDuration=3m")
        Truth.assertThat(output).contains("ALL: blinks=2, onDuration=3m")
    }

    private fun changeMicCamera(value: Boolean) {
        `when`(privacyConfig.micCameraAvailable).thenReturn(value)
        argCaptorConfigCallback.value.onFlagMicCameraChanged(value)
    }

    private fun changeLocation(value: Boolean) {
        `when`(privacyConfig.locationAvailable).thenReturn(value)
        argCaptorConfigCallback.value.onFlagLocationChanged(value)
    }

    /** Sets Maps to non-system. */
    private fun setMapsToNonSystem() {
        // Set to non system
        doReturn(512)
            .`when`(packageManager)
            .getPermissionFlags(
                "android.permission.ACCESS_FINE_LOCATION",
                MAPS_PACKAGE_NAME,
                UserHandle.getUserHandleForUid(TEST_UID),
            )
    }
}
