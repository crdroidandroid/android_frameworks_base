/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import static android.bluetooth.BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.DEFAULT_DISPLAY_GROUP;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.KEYCODE_POWER;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.ADD_OKAY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.internal.policy.IKeyguardService.SCREEN_TURNING_ON_REASON_DISPLAY_SWITCH;
import static com.android.internal.policy.IKeyguardService.SCREEN_TURNING_ON_REASON_UNKNOWN;
import static com.android.server.flags.Flags.FLAG_RESET_KEYGUARD_FIRST_STATE_DISPATCH_ON_SERVICE_CONNECTED;
import static com.android.server.policy.PhoneWindowManager.EXTRA_TRIGGER_HUB;
import static com.android.server.policy.PhoneWindowManager.MULTI_PRESS_POWER_BRIGHTNESS_BOOST;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_POWER_DREAM_OR_AWAKE_OR_SLEEP;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_POWER_GO_TO_SLEEP;
import static com.android.server.policy.PhoneWindowManager.SHORT_PRESS_POWER_HUB_OR_DREAM_OR_SLEEP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.testing.TestableContext;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.util.test.LocalServiceKeeperRule;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemServiceManager;
import com.android.server.UiThread;
import com.android.server.input.InputManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.WindowManagerPolicy.ScreenOnListener;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.testutils.OffsettableClock;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.DisplayPolicy;
import com.android.server.wm.DisplayRotation;
import com.android.server.wm.WindowManagerInternal;
import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

import java.util.List;

/**
 * Test class for {@link PhoneWindowManager}.
 *
 * Build/Install/Run:
 * atest WmTests:PhoneWindowManagerTests
 */
@Presubmit
@SmallTest
public class PhoneWindowManagerTests {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public LocalServiceKeeperRule mLocalServiceKeeperRule = new LocalServiceKeeperRule();

    @Rule
    public final TestableContext mContext = spy(
            new TestableContext(getInstrumentation().getContext()));

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock private IBinder mInputToken;
    private OffsettableClock mOffsettableClock;

    PhoneWindowManager mPhoneWindowManager;

    @Mock
    private android.app.IActivityTaskManager mIActivityTaskManager;
    @Mock
    private ActivityTaskManagerInternal mAtmInternal;
    @Mock
    private DreamManagerInternal mDreamManagerInternal;
    @Mock
    private InputManagerInternal mInputManagerInternal;
    @Mock
    private InputManager mInputManager;
    @Mock
    private PowerManagerInternal mPowerManagerInternal;
    @Mock
    private StatusBarManagerInternal mStatusBarManagerInternal;
    @Mock
    private UserManagerInternal mUserManagerInternal;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternal;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();

    @Mock
    private PowerManager mPowerManager;
    @Mock
    private DisplayPolicy mDisplayPolicy;
    @Mock
    private KeyguardServiceDelegate mKeyguardServiceDelegate;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private WindowWakeUpPolicy mWindowWakeUpPolicy;
    @Mock
    private SystemServiceManager mSystemServiceManager;
    @Mock
    private ActivityManagerInternal mActivityManagerInternal;
    @Mock
    private PackageManager mPackageManager;

    private static final int INTERCEPT_SYSTEM_KEY_NOT_CONSUMED_DELAY = 0;

    private StaticMockitoSession mMockitoSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mPowerManager);
        mMockitoSession = mockitoSession()
                .mockStatic(SystemProperties.class)
                .mockStatic(ActivityTaskManager.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        when(ActivityTaskManager.getService()).thenReturn(mIActivityTaskManager);

        mOffsettableClock = new OffsettableClock.Stopped();

        spyOn(ActivityManager.getService());

        mPhoneWindowManager = new PhoneWindowManager();
        spyOn(mPhoneWindowManager);

        mLocalServiceKeeperRule.overrideLocalService(ActivityTaskManagerInternal.class,
                mAtmInternal);
        mPhoneWindowManager.mActivityTaskManagerInternal = mAtmInternal;
        mLocalServiceKeeperRule.overrideLocalService(DreamManagerInternal.class,
                mDreamManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(InputManagerInternal.class,
                mInputManagerInternal);
        mPhoneWindowManager.mInputManagerInternal = mInputManagerInternal;
        mLocalServiceKeeperRule.overrideLocalService(PowerManagerInternal.class,
                mPowerManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(StatusBarManagerInternal.class,
                mStatusBarManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(UserManagerInternal.class,
                mUserManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(WindowManagerInternal.class,
                mock(WindowManagerInternal.class));
        mLocalServiceKeeperRule.overrideLocalService(SystemServiceManager.class,
                mSystemServiceManager);
        when(mSystemServiceManager.isBootCompleted()).thenReturn(true);
        mLocalServiceKeeperRule.overrideLocalService(ActivityManagerInternal.class,
                mActivityManagerInternal);
        when(mActivityManagerInternal.getCurrentUserId()).thenReturn(0);

        mPhoneWindowManager.mKeyguardDelegate = mKeyguardServiceDelegate;
        doNothing().when(mInputManager).registerKeyGestureEventHandler(anyList(), any());
        doReturn(mInputManager).when(mContext).getSystemService(eq(Context.INPUT_SERVICE));
    }

    @After
    public void tearDown() {
        UiThread.dispose();
        reset(ActivityManager.getService());
        reset(mContext);
        mMockitoSession.finishMocking();
    }

    @Test
    public void testShouldNotStartDockOrHomeWhenSetup() throws Exception {
        mockStartDockOrHome(Display.TYPE_INTERNAL);
        doReturn(false).when(mPhoneWindowManager).isUserSetupComplete();

        mPhoneWindowManager.startDockOrHome(
                0 /* displayId */, false /* fromHomeKey */, false /* awakenFromDreams */);

        verify(mPhoneWindowManager, never()).createHomeDockIntent();
    }

    @Test
    public void testShouldStartDockOrHomeAfterSetup() throws Exception {
        mockStartDockOrHome(Display.TYPE_INTERNAL);
        doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();

        mPhoneWindowManager.startDockOrHome(
                0 /* displayId */, false /* fromHomeKey */, false /* awakenFromDreams */);

        verify(mPhoneWindowManager).createHomeDockIntent();
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.display.feature.flags.Flags.FLAG_SEPARATE_TIMEOUTS)
    public void testFinishedWakingUp_withoutPendingGroup() {
        mPhoneWindowManager.finishedWakingUp(DEFAULT_DISPLAY_GROUP, 0 /* reason */);

        verify(mKeyguardServiceDelegate, never()).onFinishedWakingUp();
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.display.feature.flags.Flags.FLAG_SEPARATE_TIMEOUTS)
    public void testFinishedGoingToSleep_withoutPendingGroup() {
        mPhoneWindowManager.finishedGoingToSleep(DEFAULT_DISPLAY_GROUP, 0 /* reason */);

        verify(mKeyguardServiceDelegate, never()).onFinishedGoingToSleep(anyInt(), anyBoolean());
    }

    @Test
    public void testScreenTurnedOff() {
        doNothing().when(mPhoneWindowManager).postUpdateSettings();
        doNothing().when(mPhoneWindowManager).updateSettings();
        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        final boolean[] isScreenTurnedOff = {false};
        doAnswer(invocation -> isScreenTurnedOff[0] = true).when(mDisplayPolicy).screenTurnedOff(
                anyBoolean());
        doAnswer(invocation -> !isScreenTurnedOff[0]).when(mDisplayPolicy).isScreenOnEarly();
        doAnswer(invocation -> !isScreenTurnedOff[0]).when(mDisplayPolicy).isScreenOnFully();

        when(mPowerManager.isInteractive()).thenReturn(true);
        initPhoneWindowManager();
        assertThat(isScreenTurnedOff[0]).isFalse();
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isFalse();

        // Skip sleep-token for non-sleep-screen-off.
        mPhoneWindowManager.screenTurnedOff(DEFAULT_DISPLAY, true /* isSwappingDisplay */);
        verify(mDisplayPolicy).screenTurnedOff(false /* acquireSleepToken */);
        assertThat(isScreenTurnedOff[0]).isTrue();
        when(mPowerManagerInternal.isGroupInteractive(DEFAULT_DISPLAY_GROUP)).thenReturn(false);

        // Apply sleep-token for sleep-screen-off.
        isScreenTurnedOff[0] = false;
        mPhoneWindowManager.startedGoingToSleep(
                DEFAULT_DISPLAY_GROUP,
                0 /* reason */,
                /* anyDefaultOrAdjacentGroupInteractive= */ false);
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isTrue();
        mPhoneWindowManager.screenTurnedOff(DEFAULT_DISPLAY, true /* isSwappingDisplay */);
        verify(mDisplayPolicy).screenTurnedOff(true /* acquireSleepToken */);

        mPhoneWindowManager.finishedGoingToSleep(DEFAULT_DISPLAY_GROUP, 0 /* reason */);
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.display.feature.flags.Flags.FLAG_SEPARATE_TIMEOUTS)
    public void testScreenTurnedOff_forNonAdjacentDisplayGroup() {
        doNothing().when(mPhoneWindowManager).postUpdateSettings();
        doNothing().when(mPhoneWindowManager).updateSettings();
        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        initPhoneWindowManager();

        int nonDefaultDisplay = DEFAULT_DISPLAY + 1;
        when(mPowerManagerInternal.isDefaultGroupAdjacent(nonDefaultDisplay)).thenReturn(false);
        mPhoneWindowManager.startedGoingToSleep(
                nonDefaultDisplay,
                0 /* reason */,
                /* anyDefaultOrAdjacentGroupInteractive= */ false);
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isFalse();

        mPhoneWindowManager.finishedGoingToSleep(nonDefaultDisplay, 0 /* reason */);
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.display.feature.flags.Flags.FLAG_SEPARATE_TIMEOUTS)
    public void testScreenTurnedOff_forAdjacentDisplayGroup() {
        doNothing().when(mPhoneWindowManager).postUpdateSettings();
        doNothing().when(mPhoneWindowManager).updateSettings();
        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        initPhoneWindowManager();

        int nonDefaultDisplay = DEFAULT_DISPLAY + 1;
        when(mPowerManagerInternal.isDefaultGroupAdjacent(nonDefaultDisplay)).thenReturn(true);
        mPhoneWindowManager.startedGoingToSleep(
                nonDefaultDisplay,
                0 /* reason */,
                /* anyDefaultOrAdjacentGroupInteractive= */ false);
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isTrue();

        mPhoneWindowManager.finishedGoingToSleep(nonDefaultDisplay, 0 /* reason */);
        assertThat(mPhoneWindowManager.mIsGoingToSleep).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.display.feature.flags.Flags.FLAG_SEPARATE_TIMEOUTS)
    public void testScreenTurnedOn_forNonAdjacentDisplayGroup() {
        doNothing().when(mPhoneWindowManager).postUpdateSettings();
        doNothing().when(mPhoneWindowManager).updateSettings();
        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        initPhoneWindowManager();

        int nonDefaultDisplay = DEFAULT_DISPLAY + 1;
        when(mPowerManagerInternal.isDefaultGroupAdjacent(nonDefaultDisplay)).thenReturn(false);
        mPhoneWindowManager.startedWakingUp(
                nonDefaultDisplay,
                0 /* reason */,
                /* anyDefaultOrAdjacentGroupInteractive= */ true);
        verify(mPhoneWindowManager.mDefaultDisplayPolicy, times(0)).setAwake(true);

        mPhoneWindowManager.finishedWakingUp(nonDefaultDisplay, 0 /* reason */);
        verify(mKeyguardServiceDelegate, times(0)).onFinishedWakingUp();
    }

    @Test
    @RequiresFlagsEnabled(com.android.server.display.feature.flags.Flags.FLAG_SEPARATE_TIMEOUTS)
    public void testScreenTurnedOn_forAdjacentDisplayGroup() {
        doNothing().when(mPhoneWindowManager).postUpdateSettings();
        doNothing().when(mPhoneWindowManager).updateSettings();
        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        initPhoneWindowManager();

        int nonDefaultDisplay = DEFAULT_DISPLAY + 1;
        when(mPowerManagerInternal.isDefaultGroupAdjacent(nonDefaultDisplay)).thenReturn(true);

        mPhoneWindowManager.startedWakingUp(
                nonDefaultDisplay,
                0 /* reason */,
                /* anyDefaultOrAdjacentGroupInteractive= */ true);
        verify(mPhoneWindowManager.mDefaultDisplayPolicy).setAwake(true);

        mPhoneWindowManager.finishedWakingUp(nonDefaultDisplay, 0 /* reason */);
        verify(mKeyguardServiceDelegate).onFinishedWakingUp();
    }

    // TODO: Add test that actually relies on a test of that flag

    @Test
    public void testCheckAddPermission_withoutAccessibilityOverlay_noAccessibilityAppOpLogged() {
        mSetFlagsRule.enableFlags(android.view.contentprotection.flags.Flags.FLAG_CREATE_ACCESSIBILITY_OVERLAY_APP_OP_ENABLED);
        int[] outAppOp = new int[1];
        assertEquals(ADD_OKAY, mPhoneWindowManager.checkAddPermission(TYPE_WALLPAPER,
                /* isRoundedCornerOverlay= */ false, "test.pkg", outAppOp, DEFAULT_DISPLAY));
        assertThat(outAppOp[0]).isEqualTo(AppOpsManager.OP_NONE);
    }

    @Test
    public void testCheckAddPermission_withAccessibilityOverlay() {
        mSetFlagsRule.enableFlags(android.view.contentprotection.flags.Flags.FLAG_CREATE_ACCESSIBILITY_OVERLAY_APP_OP_ENABLED);
        int[] outAppOp = new int[1];
        assertEquals(ADD_OKAY, mPhoneWindowManager.checkAddPermission(TYPE_ACCESSIBILITY_OVERLAY,
                /* isRoundedCornerOverlay= */ false, "test.pkg", outAppOp, DEFAULT_DISPLAY));
        assertThat(outAppOp[0]).isEqualTo(AppOpsManager.OP_CREATE_ACCESSIBILITY_OVERLAY);
    }

    @Test
    public void testCheckAddPermission_withAccessibilityOverlay_flagDisabled() {
        mSetFlagsRule.disableFlags(android.view.contentprotection.flags.Flags.FLAG_CREATE_ACCESSIBILITY_OVERLAY_APP_OP_ENABLED);
        int[] outAppOp = new int[1];
        assertEquals(ADD_OKAY, mPhoneWindowManager.checkAddPermission(TYPE_ACCESSIBILITY_OVERLAY,
                /* isRoundedCornerOverlay= */ false, "test.pkg", outAppOp, DEFAULT_DISPLAY));
        assertThat(outAppOp[0]).isEqualTo(AppOpsManager.OP_NONE);
    }

    @Test
    public void userSwitching_keyboardShortcutHelperDismissed() {
        mPhoneWindowManager.setSwitchingUser(true);

        verify(mStatusBarManagerInternal).dismissKeyboardShortcutsMenu();
    }

    @Test
    public void userNotSwitching_keyboardShortcutHelperDismissed() {
        mPhoneWindowManager.setSwitchingUser(false);

        verify(mStatusBarManagerInternal, never()).dismissKeyboardShortcutsMenu();
    }

    @Test
    public void powerPress_hubOrDreamOrSleep_goesToSleepFromDream() {
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        when(mLockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS, SHORT_PRESS_POWER_HUB_OR_DREAM_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Device is dreaming.
        when(mDreamManagerInternal.isDreaming()).thenReturn(true);

        // Power button pressed.
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);

        // Device goes to sleep.
        verify(mPowerManager).goToSleep(eventTime, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
    }

    @Test
    public void powerPress_hubOrDreamOrSleep_noDreamManager_noCrash() {
        mLocalServiceKeeperRule.overrideLocalService(DreamManagerInternal.class,
                null);

        when(mDisplayPolicy.isAwake()).thenReturn(true);
        when(mLockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS, SHORT_PRESS_POWER_HUB_OR_DREAM_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Power button pressed. Make sure no crash occurs
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);
    }

    @Test
    public void powerPress_hubOrDreamOrSleep_hubAvailableLocks() {
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        when(mLockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false);
        mContext.getTestablePermissions().setPermission(android.Manifest.permission.DEVICE_POWER,
                PERMISSION_GRANTED);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS, SHORT_PRESS_POWER_HUB_OR_DREAM_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Set up hub prerequisites.
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.GLANCEABLE_HUB_ENABLED, 1);
        when(mUserManagerInternal.isUserUnlocked(any(Integer.class))).thenReturn(true);
        when(mDreamManagerInternal.dreamConditionActive()).thenReturn(true);

        // Power button pressed.
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);

        // Lock requested with the proper bundle options.
        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mPhoneWindowManager).lockNow(bundleCaptor.capture());
        assertThat(bundleCaptor.getValue().getBoolean(EXTRA_TRIGGER_HUB)).isTrue();
    }

    @Test
    public void powerPress_hubOrDreamOrSleep_hubNotAvailableDreams() {
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        when(mLockPatternUtils.isLockScreenDisabled(anyInt())).thenReturn(false);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS, SHORT_PRESS_POWER_HUB_OR_DREAM_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Hub is not available.
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.GLANCEABLE_HUB_ENABLED, 0);
        when(mDreamManagerInternal.canStartDreaming(any(Boolean.class))).thenReturn(true);

        // Power button pressed.
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);

        // Dream is requested.
        verify(mDreamManagerInternal).requestDream();
    }

    @Test
    public void powerPress_dreamOrAwakeOrSleep_awakeFromDream() {
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS,
                SHORT_PRESS_POWER_DREAM_OR_AWAKE_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Can not dream when device is dreaming.
        when(mDreamManagerInternal.canStartDreaming(any(Boolean.class))).thenReturn(false);
        // Device is dreaming.
        when(mDreamManagerInternal.isDreaming()).thenReturn(true);

        // Power button pressed.
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);

        // Dream is stopped.
        verify(mDreamManagerInternal)
                .stopDream(false /*immediate*/, "short press power" /*reason*/);
    }

    @Test
    public void powerPress_dreamOrAwakeOrSleep_canNotDreamGoToSleep() {
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS,
                SHORT_PRESS_POWER_DREAM_OR_AWAKE_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Can not dream for other reasons.
        when(mDreamManagerInternal.canStartDreaming(any(Boolean.class))).thenReturn(false);
        // Device is not dreaming.
        when(mDreamManagerInternal.isDreaming()).thenReturn(false);

        // Power button pressed.
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);

        // Device goes to sleep.
        verify(mPowerManager).goToSleep(eventTime, PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
    }

    @Test
    public void powerPress_dreamOrAwakeOrSleep_dreamFromActive() {
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        initPhoneWindowManager();

        // Set power button behavior.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS,
                SHORT_PRESS_POWER_DREAM_OR_AWAKE_OR_SLEEP);
        mPhoneWindowManager.updateSettings();

        // Can dream when active.
        when(mDreamManagerInternal.canStartDreaming(any(Boolean.class))).thenReturn(true);

        // Power button pressed.
        int eventTime = 0;
        mPhoneWindowManager.powerPress(eventTime, 1, 0);

        // Dream is requested.
        verify(mDreamManagerInternal).requestDream();
    }

    @Test
    @EnableFlags(Flags.FLAG_GRANT_MANAGE_KEY_GESTURES_TO_RECENTS)
    public void testKeyGestureEvents_recentKeyGesturesEventsEnabled_notRegistered() {
        initPhoneWindowManager();

        ArgumentCaptor<List<Integer>> registeredKeyGestureEvents = ArgumentCaptor.forClass(
                List.class);
        verify(mInputManager).registerKeyGestureEventHandler(registeredKeyGestureEvents.capture(),
                any());
        assertThat(registeredKeyGestureEvents.getValue()).containsNoneIn(
                List.of(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER));
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_USE_EVENT_DISPLAY_ID_FOR_KEY_WAKEUP)
    public void testWakeKey_wakesCorrectDisplay_useEventDisplayId() {
        testWakeKey_wakesCorrectDisplay(/* expectEventDisplayIdForWakeup= */ true);
    }

    @Test
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_EVENT_DISPLAY_ID_FOR_KEY_WAKEUP)
    public void testWakeKey_wakesCorrectDisplay_notUseEventDisplayId() {
        testWakeKey_wakesCorrectDisplay(/* expectEventDisplayIdForWakeup= */ false);
    }

    private void testWakeKey_wakesCorrectDisplay(boolean expectEventDisplayIdForWakeup) {
        initPhoneWindowManager();
        final int keyCode = KEYCODE_POWER;
        final long time = 100L;
        final int displayId = 3;
        final int userId = 4;
        // Create the KeyEvent.
        final KeyEvent event =
                new KeyEvent(time, time, ACTION_DOWN, keyCode, /* repeat= */ 0, /* metaState= */ 0);
        event.setDisplayId(displayId);
        // Set up the current user ID.
        mPhoneWindowManager.setCurrentUserLw(userId);
        when(mUserManagerInternal.getUserAssignedToDisplay(displayId)).thenReturn(userId);

        mPhoneWindowManager.interceptKeyBeforeQueueing(event, WindowManagerPolicy.FLAG_WAKE);

        final int expectedDisplayId = expectEventDisplayIdForWakeup ? displayId : DEFAULT_DISPLAY;
        verify(mWindowWakeUpPolicy)
                .wakeUpFromKey(
                        expectedDisplayId,
                        time,
                        keyCode,
                        /* isDown= */ true,
                        /* keyEventFlags= */ 0);
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_USE_EVENT_DISPLAY_ID_FOR_KEY_WAKEUP)
    public void testPowerMultiPress_wakesCorrectDisplay_useEventDisplayId() {
        testPowerMultiPress_wakesCorrectDisplay(/* expectEventDisplayIdForWakeup= */ true);
    }

    @Test
    @DisableFlags(com.android.hardware.input.Flags.FLAG_USE_EVENT_DISPLAY_ID_FOR_KEY_WAKEUP)
    public void testPowerMultiPress_wakesCorrectDisplay_notUseEventDisplayId() {
        testPowerMultiPress_wakesCorrectDisplay(/* expectEventDisplayIdForWakeup= */ false);
    }

    private void testPowerMultiPress_wakesCorrectDisplay(boolean expectEventDisplayIdForWakeup) {
        when(mDisplayPolicy.isAwake()).thenReturn(false);
        when(mDisplayPolicy.isScreenOnEarly()).thenReturn(false);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_TRIPLE_PRESS, MULTI_PRESS_POWER_BRIGHTNESS_BOOST);
        initPhoneWindowManager();
        mPhoneWindowManager.updateSettings();

        final long time = 3L;
        final int displayId = 5;
        mPhoneWindowManager.powerPress(time, /* count= */ 3, displayId);

        final int expectedDisplayId = expectEventDisplayIdForWakeup ? displayId : DEFAULT_DISPLAY;
        verify(mWindowWakeUpPolicy)
                .wakeUpFromKey(
                        expectedDisplayId,
                        time,
                        KEYCODE_POWER,
                        /* isDown= */ false,
                        /* keyEventFlags= */ 0);
    }

    @Test
    @DisableFlags(Flags.FLAG_GRANT_MANAGE_KEY_GESTURES_TO_RECENTS)
    public void testKeyGestureEvents_recentKeyGesturesEventsDisabled_registered() {
        initPhoneWindowManager();

        ArgumentCaptor<List<Integer>> registeredKeyGestureEvents = ArgumentCaptor.forClass(
                List.class);
        verify(mInputManager).registerKeyGestureEventHandler(registeredKeyGestureEvents.capture(),
                any());
        assertThat(registeredKeyGestureEvents.getValue()).containsAtLeastElementsIn(
                List.of(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER));
    }

    @Test
    public void testKeyGestureEvents_sysuiKeyGesturesEventsEnabled_notRegistered() {
        initPhoneWindowManager();

        ArgumentCaptor<List<Integer>> registeredKeyGestureEvents = ArgumentCaptor.forClass(
                List.class);
        verify(mInputManager).registerKeyGestureEventHandler(registeredKeyGestureEvents.capture(),
                any());
        assertThat(registeredKeyGestureEvents.getValue()).doesNotContain(
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL);
    }

    @Test
    public void testBluetoothHidConnectionBroadcastCanWakeup() {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_PC)).thenReturn(true);
        doReturn(true).when(() -> SystemProperties.getBoolean(
                                eq("bluetooth.power.suspend.hid_wake_up.enabled"), eq(false)));

        initPhoneWindowManager();

        final Intent intent = new Intent(ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        ArgumentCaptor<BroadcastReceiver> captor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiverForAllUsers(captor.capture(), argThat(intentFilter ->
                                intentFilter.matchAction(ACTION_CONNECTION_STATE_CHANGED)),
                                eq(null), eq(null));
        captor.getValue().onReceive(mContext, intent);
        verify(mWindowWakeUpPolicy).wakeUpFromBluetooth();
    }

    @Test
    public void powerPress_firstTapThenPowerPress_shouldIgnorePowerPress() {
        final int tapGestureEventTimeMillis = 0;
        final int suppressionDelayMillis = 400;
        final int powerButtonPressEventTimeMillis = tapGestureEventTimeMillis
                + suppressionDelayMillis - 1;
        final int sleepDuration = 10_000;
        PowerManager.WakeData wakeData = new PowerManager.WakeData(
                tapGestureEventTimeMillis, PowerManager.WAKE_REASON_TAP, sleepDuration);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS, SHORT_PRESS_POWER_GO_TO_SLEEP);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE,
                suppressionDelayMillis);

        initPhoneWindowManager();
        when(mPowerManagerInternal.getLastWakeup()).thenReturn(wakeData);
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        mPhoneWindowManager.updateSettings();

        mOffsettableClock.fastForward(powerButtonPressEventTimeMillis);
        mPhoneWindowManager.powerPress(powerButtonPressEventTimeMillis, 1, DEFAULT_DISPLAY);

        verify(mPowerManager, never()).goToSleep(powerButtonPressEventTimeMillis,
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
    }

    @Test
    public void powerPress_firstTapThenPowerPress_shouldRespectPowerPress() {
        final int tapGestureEventTimeMillis = 0;
        final int suppressionDelayMillis = 400;
        final int powerButtonPressEventTimeMillis = tapGestureEventTimeMillis
                + suppressionDelayMillis + 1;
        final int sleepDuration = 10_000;
        PowerManager.WakeData wakeData = new PowerManager.WakeData(
                tapGestureEventTimeMillis, PowerManager.WAKE_REASON_TAP, sleepDuration);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SHORT_PRESS, SHORT_PRESS_POWER_GO_TO_SLEEP);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.POWER_BUTTON_SUPPRESSION_DELAY_AFTER_GESTURE_WAKE,
                suppressionDelayMillis);

        initPhoneWindowManager();
        when(mPowerManagerInternal.getLastWakeup()).thenReturn(wakeData);
        when(mDisplayPolicy.isAwake()).thenReturn(true);
        mPhoneWindowManager.updateSettings();

        mOffsettableClock.fastForward(powerButtonPressEventTimeMillis);
        mPhoneWindowManager.powerPress(powerButtonPressEventTimeMillis, 1, DEFAULT_DISPLAY);

        verify(mPowerManager, times(1)).goToSleep(powerButtonPressEventTimeMillis,
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
    }

    @Test
    public void testScreenTurningOn_defaultDisplaySwitching_passesDisplaySwitchReasonToKeyguard() {
        initPhoneWindowManager();
        when(mKeyguardServiceDelegate.hasKeyguard()).thenReturn(true);
        when(mDisplayPolicy.isDisplaySwitching()).thenReturn(true);

        mPhoneWindowManager.screenTurningOn(DEFAULT_DISPLAY, mock(ScreenOnListener.class));

        verify(mKeyguardServiceDelegate).onScreenTurningOn(
                /* reason= */ eq(SCREEN_TURNING_ON_REASON_DISPLAY_SWITCH));
    }

    @Test
    public void testScreenTurningOn_defaultDisplayNotSwitching_passesUnknownReasonToKeyguard() {
        initPhoneWindowManager();
        when(mKeyguardServiceDelegate.hasKeyguard()).thenReturn(true);
        when(mDisplayPolicy.isDisplaySwitching()).thenReturn(false);

        mPhoneWindowManager.screenTurningOn(DEFAULT_DISPLAY, mock(ScreenOnListener.class));

        verify(mKeyguardServiceDelegate).onScreenTurningOn(
                /* reason= */ eq(SCREEN_TURNING_ON_REASON_UNKNOWN));
    }

    @Test
    public void startDockOrHome_externalDisplay_flagEnabled_shouldHandleKeyGesture()
            throws Exception {
        mockStartDockOrHome(Display.TYPE_EXTERNAL);
        doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();

        mPhoneWindowManager.startDockOrHome(
                /* displayId= */ DEFAULT_DISPLAY, /* fromHomeKey= */ true, /* awakenFromDreams= */
                false);

        verify(mInputManagerInternal).handleKeyGestureInKeyGestureController(
                any(KeyGestureEvent.class));
        verify(mAtmInternal, never()).startHomeOnDisplay(anyInt(), anyString(), anyInt(),
                anyBoolean(), anyBoolean());
    }

    @Test
    public void startDockOrHome_internalDisplay_flagEnabled_shouldStartHome() throws Exception {
        mockStartDockOrHome(Display.TYPE_INTERNAL);
        doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();

        mPhoneWindowManager.startDockOrHome(
                /* displayId= */DEFAULT_DISPLAY, /* fromHomeKey= */ true, /* awakenFromDreams= */
                false);

        verify(mInputManagerInternal, never()).handleKeyGestureInKeyGestureController(
                any(KeyGestureEvent.class));
        verify(mAtmInternal).startHomeOnDisplay(/* userId= */ anyInt(), /* reason= */
                anyString(), /* displayId= */ eq(DEFAULT_DISPLAY), /* allowInstrumenting= */
                eq(true), /* fromHomeKey= */ eq(true));
    }

    @Test
    @EnableFlags(FLAG_RESET_KEYGUARD_FIRST_STATE_DISPATCH_ON_SERVICE_CONNECTED)
    public void testKeyguardServiceDelegate_onKeyguardServiceConnected() throws Exception {
        // Set up the KeyguardServiceDelegate so that it can attempt to bind a KeyguardService
        KeyguardServiceDelegate.StateCallback mockCallback =
                mock(KeyguardServiceDelegate.StateCallback.class);
        KeyguardServiceDelegate delegate = new KeyguardServiceDelegate(mContext, mockCallback);

        ArgumentCaptor<ServiceConnection> serviceConnectionCaptor =
                ArgumentCaptor.forClass(ServiceConnection.class);

        ComponentName keyguardServiceComponentName =
                new ComponentName("testPackage", "testService");

        // Return a mock KeyguardService when binder is queried for IKeyguardService
        IBinder mockBinder = mock(IBinder.class);
        IKeyguardService mockKeyguardService = mock(IKeyguardService.class);
        when(mockBinder.queryLocalInterface(anyString())).thenReturn(mockKeyguardService);

        doReturn(true)
                .when(mContext)
                .bindServiceAsUser(
                        any(Intent.class),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(Handler.class),
                        any(UserHandle.class));
        Handler mockHandler = mock(Handler.class);
        delegate.bindService(mContext, mockHandler);

        verify(mContext)
                .bindServiceAsUser(
                        any(Intent.class),
                        serviceConnectionCaptor.capture(),
                        anyInt(),
                        any(Handler.class),
                        any(UserHandle.class));
        ServiceConnection connection = serviceConnectionCaptor.getValue();

        // Fake out a successful connection
        connection.onServiceConnected(keyguardServiceComponentName, mockBinder);

        // Verify that the onKeyguardServiceConnected callback is called
        verify(mockCallback).onKeyguardServiceConnected();

        // Simulate disconnect
        connection.onServiceDisconnected(keyguardServiceComponentName);

        // Verify that the onKeyguardServiceConnected callback is not called on disconnect
        verify(mockCallback, times(1)).onKeyguardServiceConnected();

        // Simulate a reconnect
        connection.onServiceConnected(keyguardServiceComponentName, mockBinder);

        // Verify that the onKeyguardServiceConnected callback is called a second time on reconnect
        verify(mockCallback, times(2)).onKeyguardServiceConnected();
    }

    private void initPhoneWindowManager() {
        mPhoneWindowManager.mDefaultDisplayPolicy = mDisplayPolicy;
        mPhoneWindowManager.mDefaultDisplayRotation = mock(DisplayRotation.class);
        mContext.getMainThreadHandler().runWithScissors(() -> mPhoneWindowManager.init(
                new TestInjector(mContext, mock(WindowManagerPolicy.WindowManagerFuncs.class))), 0);
    }

    private void mockStartDockOrHome(int displayType) throws Exception {
        doNothing().when(ActivityManager.getService()).stopAppSwitches();
        when(mAtmInternal.startHomeOnDisplay(
                anyInt(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(false);
        mPhoneWindowManager.mUserManagerInternal = mock(UserManagerInternal.class);

        mPhoneWindowManager.mDisplayManagerInternal = mDisplayManagerInternal;
        mDisplayInfo.type = displayType;
        when(mDisplayManagerInternal.getDisplayInfo(anyInt())).thenReturn(mDisplayInfo);
    }

    private class TestInjector extends PhoneWindowManager.Injector {
        TestInjector(Context context, WindowManagerPolicy.WindowManagerFuncs funcs) {
            super(context, funcs);
        }

        @NonNull
        @Override
        KeyguardServiceDelegate getKeyguardServiceDelegate(
                @NonNull KeyguardServiceDelegate.StateCallback callbacks) {
            return mKeyguardServiceDelegate;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        /**
         * {@code WindowWakeUpPolicy} registers a local service in its constructor, easier to just
         * mock it out so we don't have to unregister it after every test.
         */
        WindowWakeUpPolicy getWindowWakeUpPolicy() {
            return mWindowWakeUpPolicy;
        }

        long getUptimeMillis() {
            return mOffsettableClock.now();
        }
    }
}
