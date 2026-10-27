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

package com.android.server.power;

import static android.os.PowerManager.SCREEN_TIMEOUT_ACTIVE;
import static android.os.PowerManager.SCREEN_TIMEOUT_KEEP_DISPLAY_ON;
import static android.os.PowerManagerInternal.WAKEFULNESS_ASLEEP;
import static android.os.PowerManagerInternal.WAKEFULNESS_AWAKE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManagerInternal;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.IScreenTimeoutPolicyListener;
import android.os.IWakeLockCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.os.test.TestLooper;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.testing.TestableContext;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.power.FrameworkStatsLogger.WakelockEventType;
import com.android.server.power.batterysaver.BatterySaverStateMachine;
import com.android.server.power.feature.PowerManagerFlags;
import com.android.server.power.feature.flags.Flags;
import com.android.server.statusbar.StatusBarManagerInternal;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Tests for {@link com.android.server.power.Notifier}
 */
@RunWith(TestParameterInjector.class)
public class NotifierTest {
    private static final String SYSTEM_PROPERTY_QUIESCENT = "ro.boot.quiescent";
    private static final int USER_ID = 0;
    private static final int DISPLAY_PORT = 0xFF;
    private static final long DISPLAY_MODEL = 0xEEEEEEEEL;

    private static final int UID = 1234;
    private static final int OWNER_UID = 1235;
    private static final int WORK_SOURCE_UID_1 = 2345;
    private static final int WORK_SOURCE_UID_2 = 2346;
    private static final int OWNER_WORK_SOURCE_UID_1 = 3456;
    private static final int OWNER_WORK_SOURCE_UID_2 = 3457;
    private static final int PID = 5678;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private BatterySaverStateMachine mBatterySaverStateMachineMock;
    @Mock private PowerManagerService.NativeWrapper mNativeWrapperMock;
    @Mock private Notifier mNotifierMock;
    @Mock private WirelessChargerDetector mWirelessChargerDetectorMock;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfigurationMock;
    @Mock private SystemPropertiesWrapper mSystemPropertiesMock;
    @Mock private InattentiveSleepWarningController mInattentiveSleepWarningControllerMock;
    @Mock private Vibrator mVibrator;
    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;
    @Mock private InputManagerInternal mInputManagerInternal;
    @Mock private InputMethodManagerInternal mInputMethodManagerInternal;
    @Mock private DisplayManagerInternal mDisplayManagerInternal;
    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock private WakeLockLog mWakeLockLog;
    @Mock private WakelockTracer mWakelockTracer;
    @Mock private WakelockMapper mWakelockMapper;
    @Mock private PackageManager mPackageManager;
    @Mock private TelephonyManager mTelephonyManager;

    @Mock private IBatteryStats mBatteryStats;

    @Mock private WindowManagerPolicy mPolicy;

    @Mock private PowerManagerFlags mPowerManagerFlags;

    @Mock private AppOpsManager mAppOpsManager;
    @Mock private IActivityManager mActivityManager;

    @Mock private BatteryStatsInternal mBatteryStatsInternal;
    @Mock private FrameworkStatsLogger mLogger;

    private PowerManagerService mService;
    private Context mContextSpy;
    private Resources mResourcesSpy;
    private TestLooper mTestLooper = new TestLooper();
    private FakeExecutor mTestExecutor = new FakeExecutor();
    private Notifier mNotifier;
    private DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);

        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternal);
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        LocalServices.addService(InputMethodManagerInternal.class, mInputMethodManagerInternal);

        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mActivityManagerInternal);

        mDefaultDisplayInfo.address = DisplayAddress.fromPortAndModel(DISPLAY_PORT, DISPLAY_MODEL);
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternal);

        mContextSpy = spy(new TestableContext(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mSystemPropertiesMock.get(eq(SYSTEM_PROPERTY_QUIESCENT), anyString())).thenReturn("");
        when(mContextSpy.getSystemService(Vibrator.class)).thenReturn(mVibrator);
        when(mDisplayManagerInternal.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(
                mDefaultDisplayInfo);

        mService = new PowerManagerService(mContextSpy, mInjector);
    }

    @Test
    public void testVibrateEnabled_wiredCharging() {
        createNotifier();

        // GIVEN the charging vibration is enabled
        enableChargingVibration(true);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device vibrates once
        verify(mVibrator, times(1)).vibrate(anyInt(), any(), any(), any(),
                any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateDisabled_wiredCharging() {
        createNotifier();

        // GIVEN the charging vibration is disabled
        enableChargingVibration(false);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verifyNoMoreInteractions(mVibrator);
    }

    @Test
    public void testVibrateEnabled_wirelessCharging() {
        createNotifier();

        // GIVEN the charging vibration is enabled
        enableChargingVibration(true);

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device vibrates once
        verify(mVibrator, times(1)).vibrate(anyInt(), any(), any(), any(),
                any(VibrationAttributes.class));
    }

    @Test
    public void testVibrateDisabled_wirelessCharging() {
        createNotifier();

        // GIVEN the charging vibration is disabled
        enableChargingVibration(false);

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verifyNoMoreInteractions(mVibrator);
    }

    @Test
    public void testVibrateEnabled_dndOn() {
        createNotifier();

        // GIVEN the charging vibration is enabled but dnd is on
        enableChargingVibration(true);
        enableChargingFeedback(
                /* chargingFeedbackEnabled */ true,
                /* dndOn */ true);

        // WHEN wired charging starts
        mNotifier.onWiredChargingStarted(USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the device doesn't vibrate
        verify(mVibrator, never()).vibrate(any(), any(VibrationAttributes.class));
    }

    @Test
    public void testWirelessAnimationEnabled() {
        // GIVEN the wireless charging animation is enabled
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim))
                .thenReturn(true);
        createNotifier();

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the charging animation is triggered
        verify(mStatusBarManagerInternal, times(1)).showChargingAnimation(5);
    }

    @Test
    public void testWirelessAnimationDisabled() {
        // GIVEN the wireless charging animation is disabled
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_showBuiltinWirelessChargingAnim))
                .thenReturn(false);
        createNotifier();

        // WHEN wireless charging starts
        mNotifier.onWirelessChargingStarted(5, USER_ID);
        mTestLooper.dispatchAll();
        mTestExecutor.simulateAsyncExecutionOfLastCommand();

        // THEN the charging animation never gets called
        verify(mStatusBarManagerInternal, never()).showChargingAnimation(anyInt());
    }

    @Test
    @DisableFlags(Flags.FLAG_INTERACTIVE_DOZE_EXPERIENCE)
    public void testOnGlobalWakefulnessChangeStarted_interactiveDozeFlagOff(
                @TestParameter boolean interactivDozeConfigEnabled) {
        testOnGlobalWakefulnessChangeStarted(
                /* interactiveDozeConfigEnabled= */ interactivDozeConfigEnabled,
                /* expectCallSetDisplayInteractivity= */ true);
    }

    @Test
    @EnableFlags(Flags.FLAG_INTERACTIVE_DOZE_EXPERIENCE)
    public void testOnGlobalWakefulnessChangeStarted_interactiveDozeFlagOn(
                @TestParameter boolean interactivDozeConfigEnabled) {
        testOnGlobalWakefulnessChangeStarted(
                /* interactiveDozeConfigEnabled= */ interactivDozeConfigEnabled,
                /* expectCallSetDisplayInteractivity= */ !interactivDozeConfigEnabled);
    }

    private void testOnGlobalWakefulnessChangeStarted(
            boolean interactiveDozeConfigEnabled,
            boolean expectCallSetDisplayInteractivity) {
        // GIVEN system is currently non-interactive
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_enableInteractiveDoze))
                .thenReturn(interactiveDozeConfigEnabled);
        createNotifier();
        final int displayId1 = 101;
        final int displayId2 = 102;
        final int[] displayIds = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds(eq(false))).thenReturn(displayIds);
        mNotifier.onGlobalWakefulnessChangeStarted(WAKEFULNESS_ASLEEP,
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, /* eventTime= */ 1000);
        mTestLooper.dispatchAll();

        // WHEN a global wakefulness change to interactive starts
        mNotifier.onGlobalWakefulnessChangeStarted(WAKEFULNESS_AWAKE,
                PowerManager.WAKE_REASON_TAP, /* eventTime= */ 2000);
        mTestLooper.dispatchAll();

        // THEN input is notified of all displays being interactive
        if (expectCallSetDisplayInteractivity) {
            final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
            expectedDisplayInteractivities.put(displayId1, true);
            expectedDisplayInteractivities.put(displayId2, true);
            verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);
        } else {
            verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
        }
        verify(mInputMethodManagerInternal).setInteractive(/* interactive= */ true);
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_newPowerGroup_perDisplayWakeDisabled() {
        createNotifier();
        // GIVEN power group is not yet known to Notifier and per-display wake by touch is disabled
        final int groupId = 123;
        final int changeReason = PowerManager.WAKE_REASON_TAP;
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);

        // WHEN a power group wakefulness change starts
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_AWAKE,
                changeReason,
                /* anyDefaultOrAdjacentGroupInteractive= */ true,
                /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN window manager policy is informed that device has started waking up
        verify(mPolicy)
                .startedWakingUp(
                        groupId, changeReason, /* anyDefaultOrAdjacentGroupInteractive= */ true);
        verify(mDisplayManagerInternal, never()).getDisplayIds(eq(false));
        verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_interactivityNoChange_perDisplayWakeDisabled() {
        createNotifier();
        // GIVEN power group is not interactive and per-display wake by touch is disabled
        final int groupId = 234;
        final int changeReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_ASLEEP,
                changeReason,
                /* anyDefaultOrAdjacentGroupInteractive= */ false,
                /* eventTime= */ 999);
        mTestLooper.dispatchAll();
        verify(mPolicy, times(1))
                .startedGoingToSleep(
                        groupId, changeReason, /* anyDefaultOrAdjacentGroupInteractive= */ false);

        // WHEN a power wakefulness change to not interactive starts
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_ASLEEP,
                changeReason,
                /* anyDefaultOrAdjacentGroupInteractive= */ false,
                /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN policy is only informed once of non-interactive wakefulness change
        verify(mPolicy, times(1))
                .startedGoingToSleep(
                        groupId, changeReason, /* anyDefaultOrAdjacentGroupInteractive= */ false);
        verify(mDisplayManagerInternal, never()).getDisplayIds(eq(false));
        verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
    }

    @Test
    public void testOnGroupWakefulnessChangeStarted_interactivityChange_perDisplayWakeDisabled() {
        createNotifier();
        // GIVEN power group is not interactive and per-display wake by touch is disabled
        final int groupId = 345;
        final int firstChangeReason = PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(false);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_ASLEEP,
                firstChangeReason,
                /* anyDefaultOrAdjacentGroupInteractive= */ false,
                /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // WHEN a power wakefulness change to interactive starts
        final int secondChangeReason = PowerManager.WAKE_REASON_TAP;
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_AWAKE,
                secondChangeReason,
                /* anyDefaultOrAdjacentGroupInteractive= */ false,
                /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        // THEN policy is informed of the change
        verify(mPolicy)
                .startedWakingUp(
                        groupId,
                        secondChangeReason,
                        /* anyDefaultOrAdjacentGroupInteractive= */ false);
        verify(mDisplayManagerInternal, never()).getDisplayIds(eq(false));
        verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
    }

    @Test
    @DisableFlags(Flags.FLAG_INTERACTIVE_DOZE_EXPERIENCE)
    public void testOnGroupWakefulnessChangeStarted_perDisplayWakeByTouchOn_interactiveDozeFlagOff(
            @TestParameter boolean interactivDozeConfigEnabled) {
        testOnGroupWakefulnessChangeStarted_perDisplayWakeByTouchEnabled(
                /* interactiveDozeConfigEnabled= */ interactivDozeConfigEnabled,
                /* expectCallSetDisplayInteractivity= */ true);
    }

    @Test
    @EnableFlags(Flags.FLAG_INTERACTIVE_DOZE_EXPERIENCE)
    public void testOnGroupWakefulnessChangeStarted_perDisplayWakeByTouchOn_interactiveDozeFlagOn(
            @TestParameter boolean interactivDozeConfigEnabled) {
        testOnGroupWakefulnessChangeStarted_perDisplayWakeByTouchEnabled(
                /* interactiveDozeConfigEnabled= */ interactivDozeConfigEnabled,
                /* expectCallSetDisplayInteractivity= */ !interactivDozeConfigEnabled);
    }

    private void testOnGroupWakefulnessChangeStarted_perDisplayWakeByTouchEnabled(
            boolean interactiveDozeConfigEnabled,
            boolean expectCallSetDisplayInteractivity) {
        // GIVEN per-display wake by touch flag is enabled
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(true);
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_enableInteractiveDoze))
                .thenReturn(interactiveDozeConfigEnabled);
        createNotifier();
        final int groupId = 456;
        final int displayId1 = 1001;
        final int displayId2 = 1002;
        final int[] displays = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds(eq(false))).thenReturn(displays);
        when(mDisplayManagerInternal.getDisplayIdsForGroup(groupId)).thenReturn(displays);
        final int changeReason = PowerManager.WAKE_REASON_TAP;

        // WHEN power group wakefulness change started
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_AWAKE,
                changeReason,
                /* anyDefaultOrAdjacentGroupInteractive= */ true,
                /* eventTime= */ 999);
        mTestLooper.dispatchAll();

        if (expectCallSetDisplayInteractivity) {
            // THEN native input manager is updated that the displays are interactive
            final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
            expectedDisplayInteractivities.put(displayId1, true);
            expectedDisplayInteractivities.put(displayId2, true);
            verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);
        } else {
            verify(mInputManagerInternal, never()).setDisplayInteractivities(any());
        }
    }

    @Test
    public void testOnGroupRemoved_perDisplayWakeByTouchEnabled() {
        createNotifier();
        // GIVEN per-display wake by touch is enabled and one display group has been defined
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(true);
        final int groupId = 313;
        final int displayId1 = 3113;
        final int displayId2 = 4114;
        final int[] displays = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds(eq(false))).thenReturn(displays);
        when(mDisplayManagerInternal.getDisplayIdsForGroup(groupId)).thenReturn(displays);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_AWAKE,
                PowerManager.WAKE_REASON_TAP,
                /* anyDefaultOrAdjacentGroupInteractive= */ true,
                /* eventTime= */ 1000);
        final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
        expectedDisplayInteractivities.put(displayId1, true);
        expectedDisplayInteractivities.put(displayId2, true);
        verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);

        // WHEN display group is removed
        when(mDisplayManagerInternal.getDisplayIdsByGroupsIds()).thenReturn(new SparseArray<>());
        mNotifier.onGroupRemoved(groupId);

        // THEN native input manager is informed that displays in that group no longer exist
        verify(mInputManagerInternal).setDisplayInteractivities(new SparseBooleanArray());
    }

    @Test
    public void testOnGroupChanged_perDisplayWakeByTouchEnabled() {
        createNotifier();
        // GIVEN per-display wake by touch is enabled and one display group has been defined with
        // two displays
        when(mPowerManagerFlags.isPerDisplayWakeByTouchEnabled()).thenReturn(true);
        final int groupId = 121;
        final int displayId1 = 1221;
        final int displayId2 = 1222;
        final int[] displays = new int[]{displayId1, displayId2};
        when(mDisplayManagerInternal.getDisplayIds(eq(false))).thenReturn(displays);
        when(mDisplayManagerInternal.getDisplayIdsForGroup(groupId)).thenReturn(displays);
        SparseArray<int[]> displayIdsByGroupId = new SparseArray<>();
        displayIdsByGroupId.put(groupId, displays);
        when(mDisplayManagerInternal.getDisplayIdsByGroupsIds()).thenReturn(displayIdsByGroupId);
        mNotifier.onGroupWakefulnessChangeStarted(
                groupId,
                WAKEFULNESS_AWAKE,
                PowerManager.WAKE_REASON_TAP,
                /* anyDefaultOrAdjacentGroupInteractive= */ true,
                /* eventTime= */ 1000);
        final SparseBooleanArray expectedDisplayInteractivities = new SparseBooleanArray();
        expectedDisplayInteractivities.put(displayId1, true);
        expectedDisplayInteractivities.put(displayId2, true);
        verify(mInputManagerInternal).setDisplayInteractivities(expectedDisplayInteractivities);

        // WHEN display group is changed to only contain one display
        SparseArray<int[]> newDisplayIdsByGroupId = new SparseArray<>();
        newDisplayIdsByGroupId.put(groupId, new int[]{displayId1});
        when(mDisplayManagerInternal.getDisplayIdsByGroupsIds()).thenReturn(newDisplayIdsByGroupId);
        mNotifier.onGroupChanged();

        // THEN native input manager is informed that the displays in the group have changed
        final SparseBooleanArray expectedDisplayInteractivitiesAfterChange =
            new SparseBooleanArray();
        expectedDisplayInteractivitiesAfterChange.put(displayId1, true);
        verify(mInputManagerInternal).setDisplayInteractivities(
            expectedDisplayInteractivitiesAfterChange);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnUidGone_invokesListener() throws RemoteException {
        createNotifier();

        Notifier.WakeLockChangedListener listener =
                mock(Notifier.WakeLockChangedListener.class);
        mNotifier.registerWakeLockChangedListener(listener);

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        PowerManagerService.WakeLock wakeLock = mock(PowerManagerService.WakeLock.class);
        String wakelockTag = "testTag";
        wakeLock.mFlags = PowerManager.PARTIAL_WAKE_LOCK;
        // Worksource with size 1
        WorkSource workSource = new WorkSource(2001);
        wakeLock.mTag = wakelockTag;
        wakeLock.mWorkSource = workSource;

        when(mWakelockMapper.getWakeLocksForUid(2001)).thenReturn(Set.of(wakeLock));
        when(mBatteryStatsInternal.getOwnerUid(2001)).thenReturn(2001);

        // Simulate UID gone
        uidObserver.onUidGone(2001, true);
        mTestLooper.dispatchAll();

        verify(listener).onWakeLockStateChanged(wakeLock);
        verify(wakeLock).setAttributedUidCached(true);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnUidGone_withNullWakeLock_doesNotCrash() throws RemoteException {
        createNotifier();

        Notifier.WakeLockChangedListener listener =
                mock(Notifier.WakeLockChangedListener.class);
        mNotifier.registerWakeLockChangedListener(listener);

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        int uid = 2001;
        Set<PowerManagerService.WakeLock> wakeLocks = new HashSet<>();
        wakeLocks.add(null);
        when(mWakelockMapper.getWakeLocksForUid(uid)).thenReturn(wakeLocks);

        // Simulate UID gone
        uidObserver.onUidGone(uid, true);
        mTestLooper.dispatchAll();

        // Verify no crash and no listener interaction for the null wakelock.
        verifyNoInteractions(listener);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnUidCachedChanged_updatesWakelockMapper() throws RemoteException {
        createNotifier();

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        int uid = 12345;
        uidObserver.onUidCachedChanged(uid, true);
        verify(mWakelockMapper).setUidCached(uid, true);
        verify(mWakelockMapper).getWakeLocksForUid(uid);

        uidObserver.onUidCachedChanged(uid, false);
        verify(mWakelockMapper).setUidCached(uid, false);

        uidObserver.onUidGone(uid, true);
        // setUidCached called with false again (total 2 times with false)
        verify(mWakelockMapper, times(2)).setUidCached(uid, false);
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_NoChains() {
        createNotifier();

        clearInvocations(mLogger, mWakeLockLog, mBatteryStats, mAppOpsManager);

        when(mBatteryStatsInternal.getOwnerUid(UID)).thenReturn(OWNER_UID);
        when(mBatteryStatsInternal.getOwnerUid(WORK_SOURCE_UID_1))
                .thenReturn(OWNER_WORK_SOURCE_UID_1);

        mNotifier.onWakeLockAcquired(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                /* workSource= */ null,
                /* historyTag= */ null,
                /* callback= */ null);

        WorkSource ws = new WorkSource(WORK_SOURCE_UID_1);

        mNotifier.onWakeLockChanging(
                /* existing WakeLock params */
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                /* workSource= */ null,
                /* historyTag= */ null,
                /* callback= */ null,
                /* updated WakeLock params */
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null, /* removeInactiveUids */ false, /* isCached */ false,
                /* uid */ -1);

        mNotifier.onWakeLockReleased(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(UID));
        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(WORK_SOURCE_UID_1));

        // ACQUIRE before RELEASE
        InOrder inOrder1 = inOrder(mLogger);
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_UID),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_UID),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));

        InOrder inOrder2 = inOrder(mLogger);
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_MultipleWorkSourceUids() {
        // UIDs stored directly in WorkSource
        WorkSource ws = new WorkSource(WORK_SOURCE_UID_1);
        ws.add(WORK_SOURCE_UID_2);
        testWorkSource(ws);

        InOrder inOrder = inOrder(mLogger);
        ArgumentCaptor<Integer> captorInt = ArgumentCaptor.forClass(int.class);

        // ACQUIRE
        inOrder.verify(mLogger, times(2))
                .wakelockStateChanged(
                        /* uid= */ captorInt.capture(),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        assertThat(captorInt.getAllValues())
                .containsExactly(OWNER_WORK_SOURCE_UID_1, OWNER_WORK_SOURCE_UID_2);

        // RELEASE
        captorInt = ArgumentCaptor.forClass(int.class);
        inOrder.verify(mLogger, times(2))
                .wakelockStateChanged(
                        /* uid= */ captorInt.capture(),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
        assertThat(captorInt.getAllValues())
                .containsExactly(OWNER_WORK_SOURCE_UID_1, OWNER_WORK_SOURCE_UID_2);
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_OneChain() {
        // UIDs stored in a WorkChain of the WorkSource
        WorkSource ws = new WorkSource();
        WorkChain wc = ws.createWorkChain();
        wc.addNode(WORK_SOURCE_UID_1, "tag1");
        wc.addNode(WORK_SOURCE_UID_2, "tag2");
        testWorkSource(ws);

        WorkChain expectedWorkChain = new WorkChain();
        expectedWorkChain.addNode(OWNER_WORK_SOURCE_UID_1, "tag1");
        expectedWorkChain.addNode(OWNER_WORK_SOURCE_UID_2, "tag2");

        InOrder inOrder = inOrder(mLogger);

        // ACQUIRE
        inOrder.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        // RELEASE
        inOrder.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_OneUid_OneChain() {
        WorkSource ws = new WorkSource(WORK_SOURCE_UID_1);
        WorkChain wc = ws.createWorkChain();
        wc.addNode(WORK_SOURCE_UID_2, "someTag");
        testWorkSource(ws);

        WorkChain expectedWorkChain = new WorkChain();
        expectedWorkChain.addNode(OWNER_WORK_SOURCE_UID_2, "someTag");

        InOrder inOrder1 = inOrder(mLogger);
        InOrder inOrder2 = inOrder(mLogger);

        // ACQUIRE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        // RELEASE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq(OWNER_WORK_SOURCE_UID_1),
                        eq("wakelockTag"),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockReleased_FrameworkStatsLogged_TwoChains() {
        // UIDs stored in a WorkChain of the WorkSource
        WorkSource ws = new WorkSource();
        WorkChain wc1 = ws.createWorkChain();
        wc1.addNode(WORK_SOURCE_UID_1, "tag1");

        WorkChain wc2 = ws.createWorkChain();
        wc2.addNode(WORK_SOURCE_UID_2, "tag2");

        testWorkSource(ws);

        WorkChain expectedWorkChain1 = new WorkChain();
        expectedWorkChain1.addNode(OWNER_WORK_SOURCE_UID_1, "tag1");

        WorkChain expectedWorkChain2 = new WorkChain();
        expectedWorkChain2.addNode(OWNER_WORK_SOURCE_UID_2, "tag2");

        InOrder inOrder1 = inOrder(mLogger);
        InOrder inOrder2 = inOrder(mLogger);

        // ACQUIRE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain1),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain2),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.ACQUIRE));

        // RELEASE
        inOrder1.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain1),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
        inOrder2.verify(mLogger)
                .wakelockStateChanged(
                        eq("wakelockTag"),
                        eq(expectedWorkChain2),
                        eq(PowerManager.PARTIAL_WAKE_LOCK),
                        eq(WakelockEventType.RELEASE));
    }

    @Test
    public void testOnWakeLockListener_RemoteException_NoRethrow() throws RemoteException {
        createNotifier();
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);
        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };

        final int uid = 1234;
        final int pid = 5678;

        mNotifier.onWakeLockReleased(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        verifyNoMoreInteractions(mWakeLockLog);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, 1);
        clearInvocations(mBatteryStats);
        mNotifier.onWakeLockAcquired(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.PARTIAL_WAKE_LOCK, 1);
        verify(mBatteryStats).noteStartWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_PARTIAL, false);

        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats);
        WorkSource worksourceOld = new WorkSource(/*uid=*/ 1);
        WorkSource worksourceNew = new WorkSource(/*uid=*/ 2);

        mNotifier.onWakeLockChanging(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceOld, /* historyTag= */ null,
                exceptingCallback,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceNew,
                /* newHistoryTag */ null, exceptingCallback, /* removeInactiveUids */ false,
                /* isCached */ false, -1);
        mTestLooper.dispatchAll();
        verify(mBatteryStats).noteChangeWakelockFromSource(worksourceOld, pid, "wakelockTag",
                null, BatteryStats.WAKE_TYPE_PARTIAL, worksourceNew, pid, "wakelockTag",
                null, BatteryStats.WAKE_TYPE_FULL, false);
    }

    @Test
    public void test_wakeLockLogUsesWorkSource() {
        createNotifier();
        clearInvocations(mWakeLockLog);
        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };

        final int uid = 1234;
        final int pid = 5678;
        WorkSource worksource = new WorkSource(1212);
        WorkSource worksource2 = new WorkSource(3131);

        mNotifier.onWakeLockAcquired(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksource, /* historyTag= */ null,
                exceptingCallback);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", 1212,
                PowerManager.PARTIAL_WAKE_LOCK, 1);

        // Release the wakelock
        mNotifier.onWakeLockReleased(PowerManager.FULL_WAKE_LOCK, "wakelockTag2",
                "my.package.name", uid, pid, worksource2, /* historyTag= */ null,
                exceptingCallback);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag2", 3131, 1);
    }

    @Test
    public void
            test_notifierProcessesWorkSourceDeepCopy_OnWakelockChanging() throws RemoteException {
        createNotifier();
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);
        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };

        final int uid = 1234;
        final int pid = 5678;
        mTestLooper.dispatchAll();
        WorkSource worksourceOld = new WorkSource(/*uid=*/ 1);
        WorkSource worksourceNew =  new WorkSource(/*uid=*/ 2);

        mNotifier.onWakeLockChanging(PowerManager.PARTIAL_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceOld, /* historyTag= */ null,
                exceptingCallback,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, worksourceNew, /* newHistoryTag= */ null,
                exceptingCallback, /* removeInactiveUids, /* removeInactiveUids */ false,
                /* isCached */ false, /* uid */ -1);
        // The newWorksource is modified before notifier could process it.
        worksourceNew.set(/*uid=*/ 3);

        mTestLooper.dispatchAll();
        verify(mBatteryStats).noteChangeWakelockFromSource(worksourceOld, pid,
                "wakelockTag", null, BatteryStats.WAKE_TYPE_PARTIAL,
                new WorkSource(/*uid=*/ 2), pid, "wakelockTag", null,
                BatteryStats.WAKE_TYPE_FULL, false);
    }


    @Test
    public void testOnWakeLockListener_FullWakeLock_ProcessesOnHandler() throws RemoteException {
        createNotifier();

        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager, mWakelockTracer);

        final int uid = 1234;
        final int pid = 5678;

        // Release the wakelock
        mNotifier.onWakeLockReleased(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        // Tracing is done synchronously.
        verify(mWakelockTracer).onWakelockEvent(false, "wakelockTag", uid, pid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, null);

        // No interaction because we expect that to happen in async
        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Progressing the looper, and validating all the interactions
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, 1);
        verify(mBatteryStats).noteStopWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL);
        verify(mAppOpsManager).finishOp(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", null);

        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager, mWakelockTracer);

        // Acquire the wakelock
        mNotifier.onWakeLockAcquired(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        // Tracing is done synchronously.
        verify(mWakelockTracer).onWakelockEvent(true, "wakelockTag", uid, pid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, null);

        // No interaction because we expect that to happen in async
        verifyNoMoreInteractions(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Progressing the looper, and validating all the interactions
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, 1);
        verify(mBatteryStats).noteStartWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL, false);
        verify(mAppOpsManager).startOpNoThrow(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", false, null, null);
    }

    @Test
    public void testOnWakeLockListener_FullWakeLock_ProcessesInSync() throws RemoteException {
        createNotifier();

        IWakeLockCallback exceptingCallback = new IWakeLockCallback.Stub() {
            @Override public void onStateChanged(boolean enabled) throws RemoteException {
                throw new RemoteException("Just testing");
            }
        };
        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);

        final int uid = 1234;
        final int pid = 5678;

        // Release the wakelock
        mNotifier.onWakeLockReleased(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);
        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockReleased("wakelockTag", uid, 1);
        verify(mBatteryStats).noteStopWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL);
        verify(mAppOpsManager).finishOp(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", null);

        clearInvocations(mWakeLockLog, mBatteryStats, mAppOpsManager);

        // Acquire the wakelock
        mNotifier.onWakeLockAcquired(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "wakelockTag",
                "my.package.name", uid, pid, /* workSource= */ null, /* historyTag= */ null,
                exceptingCallback);

        mTestLooper.dispatchAll();
        verify(mWakeLockLog).onWakeLockAcquired("wakelockTag", uid,
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, 1);
        verify(mBatteryStats).noteStartWakelock(uid, pid, "wakelockTag", /* historyTag= */ null,
                BatteryStats.WAKE_TYPE_FULL, false);
        verify(mAppOpsManager).startOpNoThrow(AppOpsManager.OP_WAKE_LOCK, uid,
                "my.package.name", false, null, null);
    }

    @Test
    public void getWakelockMonitorTypeForLogging_evaluatesWakelockLevel() {
        createNotifier();
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(PowerManager.SCREEN_DIM_WAKE_LOCK),
                PowerManager.FULL_WAKE_LOCK);
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK), PowerManager.FULL_WAKE_LOCK);
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(PowerManager.DRAW_WAKE_LOCK),
                PowerManager.DRAW_WAKE_LOCK);
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(PowerManager.PARTIAL_WAKE_LOCK),
                PowerManager.PARTIAL_WAKE_LOCK);
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(
                        PowerManager.DOZE_WAKE_LOCK), -1);
    }

    @Test
    public void getWakelockMonitorTypeForLogging_evaluateProximityLevel() {
        // How proximity wakelock is evaluated depends on boolean configuration. Test both.
        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity))
                .thenReturn(false);
        createNotifier();
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK),
                PowerManager.PARTIAL_WAKE_LOCK);

        when(mResourcesSpy.getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity))
                .thenReturn(true);
        createNotifier();
        assertEquals(mNotifier.getWakelockMonitorTypeForLogging(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK), -1);
    }

    @Test
    public void testScreenTimeoutListener_reportsScreenTimeoutPolicyChange() throws Exception {
        createNotifier();
        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();
        clearInvocations(listener);

        mNotifier.notifyScreenTimeoutPolicyChanges(Display.DEFAULT_DISPLAY_GROUP,
                /* hasScreenWakeLock= */ SCREEN_TIMEOUT_KEEP_DISPLAY_ON);

        // Verify that the event is sent asynchronously on a handler
        verify(listener, never()).onScreenTimeoutPolicyChanged(anyInt());
        mTestLooper.dispatchAll();
        verify(listener).onScreenTimeoutPolicyChanged(SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
    }

    @Test
    public void testScreenTimeoutListener_addAndRemoveListener_doesNotInvokeListener()
            throws Exception {
        createNotifier();
        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();
        clearInvocations(listener);
        mNotifier.removeScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY, listener);

        mNotifier.notifyScreenTimeoutPolicyChanges(Display.DEFAULT_DISPLAY_GROUP,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
        mTestLooper.dispatchAll();

        // Callback should not be fired as listener is removed
        verify(listener, never()).onScreenTimeoutPolicyChanged(anyInt());
    }

    @Test
    public void testScreenTimeoutListener_addAndClearListeners_doesNotInvokeListener()
            throws Exception {
        createNotifier();
        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();
        clearInvocations(listener);
        mNotifier.clearScreenTimeoutPolicyListeners(Display.DEFAULT_DISPLAY);

        mNotifier.notifyScreenTimeoutPolicyChanges(Display.DEFAULT_DISPLAY_GROUP,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
        mTestLooper.dispatchAll();

        // Callback should not be fired as listener is removed
        verify(listener, never()).onScreenTimeoutPolicyChanged(anyInt());
    }

    @Test
    public void testScreenTimeoutListener_subscribedToAnotherDisplay_listenerNotFired()
            throws Exception {
        createNotifier();

        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();
        clearInvocations(listener);

        mNotifier.notifyScreenTimeoutPolicyChanges(/* displayGroupId= */ 123,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
        mTestLooper.dispatchAll();

        // Callback should not be fired as we subscribed only to the DEFAULT_DISPLAY
        verify(listener, never()).onScreenTimeoutPolicyChanged(anyInt());
    }

    @Test
    public void testScreenTimeoutListener_listenerDied_listenerNotFired()
            throws Exception {
        createNotifier();

        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);

        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();

        ArgumentCaptor<IBinder.DeathRecipient> captor =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(listenerBinder).linkToDeath(captor.capture(), anyInt());
        mTestLooper.dispatchAll();
        captor.getValue().binderDied();
        clearInvocations(listener);

        mNotifier.notifyScreenTimeoutPolicyChanges(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
        mTestLooper.dispatchAll();

        // Callback should not be fired as binder died
        verify(listener, never()).onScreenTimeoutPolicyChanged(anyInt());
    }

    @Test
    public void testScreenTimeoutListener_listenerThrowsException_listenerNotFiredSecondTime()
            throws Exception {
        createNotifier();

        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        doThrow(RuntimeException.class).when(listener).onScreenTimeoutPolicyChanged(anyInt());
        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY_GROUP,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();
        clearInvocations(listener);

        mNotifier.notifyScreenTimeoutPolicyChanges(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
        mTestLooper.dispatchAll();

        // Callback should not be fired as it has thrown an exception once
        verify(listener, never()).onScreenTimeoutPolicyChanged(anyInt());
    }

    @Test
    public void testScreenTimeoutListener_nonDefaultDisplay_stillReportsPolicyCorrectly()
            throws Exception {
        createNotifier();
        final int otherDisplayId = 123;
        final int otherDisplayGroupId = 123_00;
        when(mDisplayManagerInternal.getGroupIdForDisplay(otherDisplayId)).thenReturn(
                otherDisplayGroupId);
        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);
        mNotifier.addScreenTimeoutPolicyListener(otherDisplayId,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();
        clearInvocations(listener);

        mNotifier.notifyScreenTimeoutPolicyChanges(otherDisplayGroupId,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
        mTestLooper.dispatchAll();

        verify(listener).onScreenTimeoutPolicyChanged(SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
    }

    @Test
    public void testScreenTimeoutListener_timeoutPolicyTimeout_reportsTimeoutOnSubscription()
            throws Exception {
        createNotifier();
        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);

        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_ACTIVE, listener);
        mTestLooper.dispatchAll();

        verify(listener).onScreenTimeoutPolicyChanged(SCREEN_TIMEOUT_ACTIVE);
    }

    @Test
    public void testScreenTimeoutListener_policyHeld_reportsHeldOnSubscription()
            throws Exception {
        createNotifier();
        final IScreenTimeoutPolicyListener listener = mock(
                IScreenTimeoutPolicyListener.class);
        final IBinder listenerBinder = mock(IBinder.class);
        when(listener.asBinder()).thenReturn(listenerBinder);

        mNotifier.addScreenTimeoutPolicyListener(Display.DEFAULT_DISPLAY,
                SCREEN_TIMEOUT_KEEP_DISPLAY_ON, listener);
        mTestLooper.dispatchAll();

        verify(listener).onScreenTimeoutPolicyChanged(SCREEN_TIMEOUT_KEEP_DISPLAY_ON);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnUidCachedChanged_invokesListener() throws RemoteException {
        createNotifier();

        Notifier.WakeLockChangedListener listener = mock(Notifier.WakeLockChangedListener.class);
        mNotifier.registerWakeLockChangedListener(listener);

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        PowerManagerService.WakeLock wakeLock = mock(PowerManagerService.WakeLock.class);
        String wakelockTag = "testTag";
        wakeLock.mFlags = PowerManager.PARTIAL_WAKE_LOCK;
        // Worksource with size 1
        WorkSource workSource = new WorkSource(2001);
        wakeLock.mTag = wakelockTag;
        wakeLock.mWorkSource = workSource;

        when(mWakelockMapper.getWakeLocksForUid(2001)).thenReturn(Set.of(wakeLock));
        when(mWakelockMapper.isUidCached(2001)).thenReturn(true);
        when(mBatteryStatsInternal.getOwnerUid(2001)).thenReturn(2001);

        // Simulate cached state change to true
        uidObserver.onUidCachedChanged(2001, true);
        mTestLooper.dispatchAll();

        verify(listener).onWakeLockStateChanged(wakeLock);
        verify(wakeLock).setAttributedUidCached(true);

        clearInvocations(listener, wakeLock);

        // Simulate cached state change to false
        when(mWakelockMapper.isUidCached(2001)).thenReturn(false);
        uidObserver.onUidCachedChanged(2001, false);
        mTestLooper.dispatchAll();

        verify(listener).onWakeLockStateChanged(wakeLock);
        verify(wakeLock).setAttributedUidCached(false);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnUidCachedChanged_withNullWakeLock_doesNotCrash() throws RemoteException {
        createNotifier();

        Notifier.WakeLockChangedListener listener = mock(Notifier.WakeLockChangedListener.class);
        mNotifier.registerWakeLockChangedListener(listener);

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        int uid = 2001;
        Set<PowerManagerService.WakeLock> wakeLocks = new HashSet<>();
        wakeLocks.add(null);
        when(mWakelockMapper.getWakeLocksForUid(uid)).thenReturn(wakeLocks);

        // Simulate cached state change to true
        uidObserver.onUidCachedChanged(uid, true);
        mTestLooper.dispatchAll();

        // Verify no crash and no listener interaction for the null wakelock.
        verifyNoInteractions(listener);

        // Simulate cached state change to false
        uidObserver.onUidCachedChanged(uid, false);
        mTestLooper.dispatchAll();

        // Verify no crash and no listener interaction for the null wakelock.
        verifyNoInteractions(listener);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnWakelockUidCached() throws Exception {
        createNotifier();

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        PowerManagerService.WakeLock wakeLock = mock(PowerManagerService.WakeLock.class);
        String wakelockTag = "testTag";
        wakeLock.mFlags = PowerManager.PARTIAL_WAKE_LOCK;
        WorkSource workSource = new WorkSource(1001);
        workSource.add(2001);
        wakeLock.mTag = wakelockTag;
        wakeLock.mWorkSource = workSource;

        when(mWakelockMapper.getWakeLocksForUid(2001)).thenReturn(Set.of(wakeLock));
        when(mWakelockMapper.isUidCached(2001)).thenReturn(true);
        when(mBatteryStatsInternal.getOwnerUid(2001)).thenReturn(2001);
        when(mBatteryStatsInternal.getOwnerUid(1001)).thenReturn(1001);

        uidObserver.onUidCachedChanged(2001, true);

        verify(mLogger).wakelockStateChanged(2001, wakelockTag,
                PowerManager.PARTIAL_WAKE_LOCK, WakelockEventType.RELEASE);
        verify(mLogger).wakelockStateChanged(1001, wakelockTag,
                PowerManager.PARTIAL_WAKE_LOCK, WakelockEventType.RELEASE);
        verify(mLogger).wakelockStateChanged(1001, wakelockTag,
                PowerManager.PARTIAL_WAKE_LOCK, WakelockEventType.ACQUIRE);

        clearInvocations(mLogger);

        when(mWakelockMapper.isUidCached(2001)).thenReturn(false);
        when(mWakelockMapper.isUidCached(1001)).thenReturn(false);
        uidObserver.onUidCachedChanged(2001, false);
        verify(mLogger).wakelockStateChanged(1001, wakelockTag,
                PowerManager.PARTIAL_WAKE_LOCK, WakelockEventType.RELEASE);
        verify(mLogger).wakelockStateChanged(1001, wakelockTag,
                PowerManager.PARTIAL_WAKE_LOCK, WakelockEventType.ACQUIRE);
        verify(mLogger).wakelockStateChanged(2001, wakelockTag,
                PowerManager.PARTIAL_WAKE_LOCK, WakelockEventType.ACQUIRE);
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_CACHED_UIDS_FROM_WAKELOCK)
    public void testOnWakelockUidCachedWithNullWorksource() throws Exception {
        createNotifier();

        ArgumentCaptor<IUidObserver> uidObserverCaptor =
                ArgumentCaptor.forClass(IUidObserver.class);
        verify(mActivityManager).registerUidObserver(uidObserverCaptor.capture(),
                eq(ActivityManager.UID_OBSERVER_CACHED),
                eq(ActivityManager.PROCESS_STATE_UNKNOWN),
                eq(null));
        IUidObserver uidObserver = uidObserverCaptor.getValue();
        assertNotNull(uidObserver);

        PowerManagerService.WakeLock wakeLock = mock(PowerManagerService.WakeLock.class);
        String wakelockTag = "testTag";

        // Given wakelock.mOwnerUid is a final variable, we can't really override this value to any
        // other integer
        int ownerUid = 0;
        wakeLock.mFlags = PowerManager.PARTIAL_WAKE_LOCK;
        wakeLock.mTag = wakelockTag;

        when(mWakelockMapper.getWakeLocksForUid(ownerUid)).thenReturn(Set.of(wakeLock));
        when(mWakelockMapper.isUidCached(ownerUid)).thenReturn(true);
        when(mBatteryStatsInternal.getOwnerUid(ownerUid)).thenReturn(ownerUid);

        uidObserver.onUidCachedChanged(ownerUid, true);

        verify(mWakelockMapper).setUidCached(ownerUid, true);
        verifyNoInteractions(mLogger);
    }

    @Test
    public void testOnUserActivity_telephony(@TestParameter boolean hasTelephony) {
        when(mContextSpy.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
                .thenReturn(hasTelephony);
        when(mContextSpy.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        createNotifier();

        mNotifier.onUserActivity(Display.DEFAULT_DISPLAY_GROUP,
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, UID);
        mTestLooper.dispatchAll();

        verify(mTelephonyManager, times(hasTelephony ? 1 : 0)).notifyUserActivity();
    }

    private final PowerManagerService.Injector mInjector = new PowerManagerService.Injector() {
        @Override
        Notifier createNotifier(Looper looper, Context context, IBatteryStats batteryStats,
                SuspendBlocker suspendBlocker, WindowManagerPolicy policy,
                FaceDownDetector faceDownDetector, ScreenUndimDetector screenUndimDetector,
                Executor backgroundExecutor, PowerManagerFlags powerManagerFlags,
                WakelockMapper wakelockMapper) {
            return mNotifierMock;
        }

        @Override
        SuspendBlocker createSuspendBlocker(PowerManagerService service, String name) {
            return super.createSuspendBlocker(service, name);
        }

        @Override
        BatterySaverStateMachine createBatterySaverStateMachine(Object lock, Context context) {
            return mBatterySaverStateMachineMock;
        }

        @Override
        PowerManagerService.NativeWrapper createNativeWrapper() {
            return mNativeWrapperMock;
        }

        @Override
        WirelessChargerDetector createWirelessChargerDetector(
                SensorManager sensorManager, SuspendBlocker suspendBlocker, Handler handler) {
            return mWirelessChargerDetectorMock;
        }

        @Override
        AmbientDisplayConfiguration createAmbientDisplayConfiguration(Context context) {
            return mAmbientDisplayConfigurationMock;
        }

        @Override
        InattentiveSleepWarningController createInattentiveSleepWarningController() {
            return mInattentiveSleepWarningControllerMock;
        }

        @Override
        public SystemPropertiesWrapper createSystemPropertiesWrapper() {
            return mSystemPropertiesMock;
        }

        @Override
        void invalidateIsInteractiveCaches() {
            // Avoids an SELinux denial.
        }
    };

    private void enableChargingFeedback(boolean chargingFeedbackEnabled, boolean dndOn) {
        // enable/disable charging feedback
        Settings.Secure.putIntForUser(
                mContextSpy.getContentResolver(),
                Settings.Secure.CHARGING_SOUNDS_ENABLED,
                chargingFeedbackEnabled ? 1 : 0,
                USER_ID);

        // toggle on/off dnd
        Settings.Global.putInt(
                mContextSpy.getContentResolver(),
                Settings.Global.ZEN_MODE,
                dndOn ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        : Settings.Global.ZEN_MODE_OFF);
    }

    private void enableChargingVibration(boolean enable) {
        enableChargingFeedback(true, false);

        Settings.Secure.putIntForUser(
                mContextSpy.getContentResolver(),
                Settings.Secure.CHARGING_VIBRATION_ENABLED,
                enable ? 1 : 0,
                USER_ID);
    }

    private void createNotifier() {
        Notifier.Injector injector =
                new Notifier.Injector() {
                    @Override
                    public long currentTimeMillis() {
                        return 1;
                    }

                    @Override
                    public @NonNull WakeLockLog getWakeLockLog(Context context) {
                        return mWakeLockLog;
                    }

                    @Override
                    public @Nullable WakelockTracer getWakelockTracer(Looper looper) {
                        return mWakelockTracer;
                    }

                    @Override
                    public AppOpsManager getAppOpsManager(Context context) {
                        return mAppOpsManager;
                    }

                    @Override
                    public FrameworkStatsLogger getFrameworkStatsLogger() {
                        return mLogger;
                    }

                    @Override
                    public BatteryStatsInternal getBatteryStatsInternal() {
                        return mBatteryStatsInternal;
                    }

                    @Override
                    public IActivityManager getActivityManager() {
                        return mActivityManager;
                    }
                };

        mNotifier = new Notifier(
                mTestLooper.getLooper(),
                mContextSpy,
                mBatteryStats,
                mInjector.createSuspendBlocker(mService, "testBlocker"),
                mPolicy,
                mock(FaceDownDetector.class),
                mock(ScreenUndimDetector.class),
                mTestExecutor, mPowerManagerFlags, injector, mWakelockMapper);
    }

    private static class FakeExecutor implements Executor {
        private Runnable mLastCommand;

        @Override
        public void execute(Runnable command) {
            assertNull(mLastCommand);
            assertNotNull(command);
            mLastCommand = command;
        }

        public Runnable getAndResetLastCommand() {
            Runnable toReturn = mLastCommand;
            mLastCommand = null;
            return toReturn;
        }

        public void simulateAsyncExecutionOfLastCommand() {
            Runnable toRun = getAndResetLastCommand();
            if (toRun != null) {
                toRun.run();
            }
        }
    }

    private void testWorkSource(WorkSource ws) {
        createNotifier();
        clearInvocations(
                mBatteryStatsInternal, mLogger, mWakeLockLog, mBatteryStats, mAppOpsManager);

        when(mBatteryStatsInternal.getOwnerUid(WORK_SOURCE_UID_1))
                .thenReturn(OWNER_WORK_SOURCE_UID_1);
        when(mBatteryStatsInternal.getOwnerUid(WORK_SOURCE_UID_2))
                .thenReturn(OWNER_WORK_SOURCE_UID_2);

        mNotifier.onWakeLockAcquired(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        mNotifier.onWakeLockReleased(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wakelockTag",
                "my.package.name",
                UID,
                PID,
                ws,
                /* historyTag= */ null,
                /* callback= */ null);

        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(WORK_SOURCE_UID_1));
        verify(mBatteryStatsInternal, atLeast(1)).getOwnerUid(eq(WORK_SOURCE_UID_2));
    }
}
