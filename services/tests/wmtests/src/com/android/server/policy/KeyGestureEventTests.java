/*
 * Copyright 2023 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.KeyEvent.KEYCODE_B;
import static android.view.KeyEvent.KEYCODE_C;
import static android.view.KeyEvent.KEYCODE_E;
import static android.view.KeyEvent.KEYCODE_M;
import static android.view.KeyEvent.KEYCODE_P;
import static android.view.KeyEvent.KEYCODE_U;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.input.AppLaunchData;
import android.hardware.input.KeyGestureEvent;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.KeyEvent;

import androidx.test.filters.MediumTest;

import com.android.internal.annotations.Keep;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.lineageos.internal.util.DeviceKeysConstants.Action;

@Presubmit
@MediumTest
@RunWith(JUnitParamsRunner.class)
public class KeyGestureEventTests extends ShortcutKeyTestBase {

    private static final SparseArray<String> INTENT_SHORTCUTS =  new SparseArray<>();
    private static final SparseArray<String> ROLE_SHORTCUTS =  new SparseArray<>();
    static {
        INTENT_SHORTCUTS.append(KEYCODE_U, Intent.CATEGORY_APP_CALCULATOR);
        INTENT_SHORTCUTS.append(KEYCODE_P, Intent.CATEGORY_APP_CONTACTS);
        INTENT_SHORTCUTS.append(KEYCODE_E, Intent.CATEGORY_APP_EMAIL);
        INTENT_SHORTCUTS.append(KEYCODE_C, Intent.CATEGORY_APP_CALENDAR);
        INTENT_SHORTCUTS.append(KEYCODE_M, Intent.CATEGORY_APP_MAPS);

        ROLE_SHORTCUTS.append(KEYCODE_B, RoleManager.ROLE_BROWSER);
    }

    @Keep
    private static Object[][] shortcutTestArgumentsNotMigratedToKeyGestureController() {
        // testName, testKeys, expectedKeyGestureType, expectedKey, expectedModifierState
        return new Object[][]{
                {"HOME key -> Open Home", new int[]{KeyEvent.KEYCODE_HOME},
                        KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                        KeyEvent.KEYCODE_HOME, 0},
                {"BACK key -> Go back", new int[]{KeyEvent.KEYCODE_BACK},
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                        KeyEvent.KEYCODE_BACK, 0},
                {"VOLUME_UP key -> Increase Volume", new int[]{KeyEvent.KEYCODE_VOLUME_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_UP,
                        KeyEvent.KEYCODE_VOLUME_UP, 0},
                {"VOLUME_DOWN key -> Decrease Volume", new int[]{KeyEvent.KEYCODE_VOLUME_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_DOWN,
                        KeyEvent.KEYCODE_VOLUME_DOWN, 0},
                {"VOLUME_MUTE key -> Mute Volume", new int[]{KeyEvent.KEYCODE_VOLUME_MUTE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_MUTE,
                        KeyEvent.KEYCODE_VOLUME_MUTE, 0},
                {"MUTE key -> Mute System Microphone", new int[]{KeyEvent.KEYCODE_MUTE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_MUTE, KeyEvent.KEYCODE_MUTE,
                        0},
                {"POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_POWER},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_POWER, KeyEvent.KEYCODE_POWER,
                        0},
                {"TV_POWER key -> Toggle Power", new int[]{KeyEvent.KEYCODE_TV_POWER},
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_POWER,
                        KeyEvent.KEYCODE_TV_POWER, 0},
                {"SYSTEM_NAVIGATION_DOWN key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
                        0},
                {"SYSTEM_NAVIGATION_UP key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
                        0},
                {"SYSTEM_NAVIGATION_LEFT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
                        0},
                {"SYSTEM_NAVIGATION_RIGHT key -> System Navigation",
                        new int[]{KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SYSTEM_NAVIGATION,
                        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT, 0},
                {"SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SLEEP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SLEEP, KeyEvent.KEYCODE_SLEEP, 0},
                {"SOFT_SLEEP key -> System Sleep", new int[]{KeyEvent.KEYCODE_SOFT_SLEEP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP,
                        0},
                {"WAKEUP key -> System Wakeup", new int[]{KeyEvent.KEYCODE_WAKEUP},
                        KeyGestureEvent.KEY_GESTURE_TYPE_WAKEUP, KeyEvent.KEYCODE_WAKEUP, 0},
                {"MEDIA_PLAY key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PLAY},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY, 0},
                {"MEDIA_PAUSE key -> Media Control", new int[]{KeyEvent.KEYCODE_MEDIA_PAUSE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE, 0},
                {"MEDIA_PLAY_PAUSE key -> Media Control",
                        new int[]{KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
                        KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0}};
    }

    @Keep
    private static Object[][] longPressOnHomeTestArguments() {
        // testName, testKeys, action, expectedKeyGestureType, expectedKey,
        // expectedModifierState
        return new Object[][]{
                {"Long press HOME key -> Launch assistant",
                        new int[]{KeyEvent.KEYCODE_HOME}, Action.SEARCH,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT,
                        KeyEvent.KEYCODE_HOME, 0},
                {"Long press HOME key -> Open app switcher",
                        new int[]{KeyEvent.KEYCODE_HOME}, Action.APP_SWITCH,
                        KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                        KeyEvent.KEYCODE_HOME, 0}};
    }

    @Before
    public void setUp() {
        setUpPhoneWindowManager(/*supportSettingsUpdate*/ true, /* supportFeature */ "");
        mPhoneWindowManager.overrideLaunchHome();
        mPhoneWindowManager.overrideStatusBarManagerInternal();
        mPhoneWindowManager.overrideStartActivity();
        mPhoneWindowManager.overrideSendBroadcast();
        mPhoneWindowManager.overrideUserSetupComplete();
        mPhoneWindowManager.setupAssistForLaunch();
        mPhoneWindowManager.overrideTogglePanel();
        mPhoneWindowManager.overrideInjectKeyEvent();
        mPhoneWindowManager.overrideRoleManager();
    }

    @Test
    @Parameters(method = "shortcutTestArgumentsNotMigratedToKeyGestureController")
    public void testShortcuts_notMigratedToKeyGestureController(String testName,
            int[] testKeys, @KeyGestureEvent.KeyGestureType int expectedKeyGestureType,
            int expectedKey, int expectedModifierState) {
        testShortcutInternal(testName, testKeys, expectedKeyGestureType, expectedKey,
                expectedModifierState);
    }

    @Test
    public void testLongPressOnHome_toggleNotificationPanel() throws RemoteException {
        mPhoneWindowManager.overrideLongPressOnHomeAction(Action.NOTIFICATIONS);
        sendLongPressKeyCombination(new int[]{KeyEvent.KEYCODE_HOME});
        mPhoneWindowManager.assertTogglePanel();
    }

    @Test
    @Parameters(method = "longPressOnHomeTestArguments")
    public void testLongPressOnHome(String testName, int[] testKeys, Action action,
            @KeyGestureEvent.KeyGestureType int expectedKeyGestureType, int expectedKey,
            int expectedModifierState) {
        mPhoneWindowManager.overrideLongPressOnHomeAction(action);
        sendLongPressKeyCombination(testKeys);
        mPhoneWindowManager.assertKeyGestureCompleted(
                new int[]{expectedKey}, expectedModifierState, expectedKeyGestureType,
                "Failed while executing " + testName);
    }

    @Test
    public void testDoubleTapOnHomeBehavior_AppSwitchBehavior() {
        mPhoneWindowManager.overrideDoubleTapOnHomeAction(Action.APP_SWITCH);
        sendKeyCombination(new int[]{KeyEvent.KEYCODE_HOME}, 0 /* duration */);
        sendKeyCombination(new int[]{KeyEvent.KEYCODE_HOME}, 0 /* duration */);
        mPhoneWindowManager.assertKeyGestureCompleted(
                new int[]{KeyEvent.KEYCODE_HOME}, /* modifierState = */0,
                KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                "Failed while executing Double tap HOME -> Open App switcher");
    }

    private void testShortcutInternal(String testName, int[] testKeys,
            @KeyGestureEvent.KeyGestureType int expectedKeyGestureType, int expectedKey,
            int expectedModifierState) {
        sendKeyCombination(testKeys, 0 /* duration */);
        mPhoneWindowManager.assertKeyGestureCompleted(
                new int[]{expectedKey}, expectedModifierState, expectedKeyGestureType,
                "Failed while executing " + testName);
    }

    @Test
    public void testKeyGestureRecentApps() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS);
        mPhoneWindowManager.assertShowRecentApps();
    }

    @Test
    public void testKeyGestureAppSwitch() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH);
        mPhoneWindowManager.assertToggleRecentApps();
    }

    @Test
    public void testKeyGestureLaunchAssistant() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT);
        mPhoneWindowManager.assertSearchManagerLaunchAssist();
    }

    @Test
    public void testKeyGestureLaunchVoiceAssistant() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT);
        mPhoneWindowManager.assertSearchManagerLaunchAssist();
    }

    @Test
    public void testKeyGestureGoHome() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_HOME);
        mPhoneWindowManager.assertGoToHomescreen();
    }

    @Test
    public void testKeyGestureStopDreaming() {
        mPhoneWindowManager.overrideCanStartDreaming(true);
        mPhoneWindowManager.overrideIsDreaming(true);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_HOME);
        mPhoneWindowManager.assertDreamStopped();
        mPhoneWindowManager.assertNotGoToHomescreen();
    }

    @Test
    public void testKeyGestureLaunchSystemSettings() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS);
        mPhoneWindowManager.assertLaunchSystemSettings();
    }

    @Test
    public void testKeyGestureLock() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN);
        mPhoneWindowManager.assertLockedAfterAppTransitionFinished();
    }

    @Test
    public void testKeyGestureToggleNotificationPanel() throws RemoteException {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL);
        mPhoneWindowManager.assertTogglePanel();
    }

    @Test
    public void testKeyGestureTriggerBugReport_opensBugHandler() throws RemoteException {
        mPhoneWindowManager.overrideBugHandler(true);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT);
        mPhoneWindowManager.assertOpenBugHandler();
    }

    @Test
    public void testKeyGestureTriggerBugReport_takeBugReportIfHandlerNotPresent()
            throws RemoteException {
        mPhoneWindowManager.overrideDebuggable(true);
        mPhoneWindowManager.overrideBugHandler(false);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT);
        mPhoneWindowManager.assertTakeBugReport();
    }

    @Test
    public void testKeyGestureBack_notHandled() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_BACK);
        mPhoneWindowManager.assertBackEventNotInjected();
    }

    @Test
    public void testKeyGestureMultiWindowNavigation() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION);
        mPhoneWindowManager.assertMoveFocusedTaskToFullscreen();
    }

    @Test
    public void testKeyGestureDesktopMode() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE);
        mPhoneWindowManager.assertMoveFocusedTaskToDesktop();
    }

    @Test
    public void testKeyGestureSplitscreenNavigation() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT);
        mPhoneWindowManager.assertMoveFocusedTaskToStageSplit(true);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT);
        mPhoneWindowManager.assertMoveFocusedTaskToStageSplit(false);
    }

    @Test
    public void testKeyGestureShortcutHelper() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER);
        mPhoneWindowManager.assertToggleShortcutsMenu();
    }

    @Test
    public void testKeyGestureBrightnessChange() {
        float[] currentBrightness = new float[]{0.1f, 0.05f, 0.0f};
        float[] newBrightness = new float[]{0.065738f, 0.0275134f, 0.0f};

        for (int i = 0; i < currentBrightness.length; i++) {
            mPhoneWindowManager.prepareBrightnessDecrease(currentBrightness[i]);
            sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN);
            mPhoneWindowManager.verifyNewBrightness(newBrightness[i]);
        }
    }

    @Test
    public void testKeyGestureRecentAppSwitcher() {
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER);
        mPhoneWindowManager.assertShowRecentApps();
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER);
        mPhoneWindowManager.assertHideRecentApps();
    }

    @Test
    public void testKeyGestureLanguageSwitch() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH);
        mPhoneWindowManager.assertSwitchKeyboardLayout(1, DEFAULT_DISPLAY);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                KeyEvent.META_SHIFT_ON);
        mPhoneWindowManager.assertSwitchKeyboardLayout(-1, DEFAULT_DISPLAY);
    }

    @Test
    public void testKeyGestureLaunchSearch() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH);
        mPhoneWindowManager.assertLaunchSearch();
    }

    @Test
    public void testKeyGestureRingerToggleChord() {
        mPhoneWindowManager.overrideVolumeHushMode();
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD);
        mPhoneWindowManager.moveTimeForward(500);
        sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD);
        mPhoneWindowManager.assertVolumeMute();
    }

    @Test
    public void testKeyGestureRingerToggleChordCancelled() {
        mPhoneWindowManager.overrideVolumeHushMode();
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD);
        sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD);
        mPhoneWindowManager.assertVolumeNotMuted();
    }

    @Test
    public void testKeyGestureGlobalAction() {
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS);
        mPhoneWindowManager.moveTimeForward(500);
        sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS);
        mPhoneWindowManager.assertShowGlobalActionsCalled();
    }

    @Test
    public void testKeyGestureGlobalActionCancelled() {
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS);
        sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS);
        mPhoneWindowManager.assertShowGlobalActionsNotCalled();
    }

    @Test
    public void testKeyGestureTvTriggerBugReport() {
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT);
        mPhoneWindowManager.moveTimeForward(1000);
        sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT);
        mPhoneWindowManager.assertBugReportTakenForTv();
    }

    @Test
    public void testKeyGestureTvTriggerBugReportCancelled() {
        sendKeyGestureEventStart(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT);
        sendKeyGestureEventCancel(KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT);
        mPhoneWindowManager.assertBugReportNotTakenForTv();
    }

    @Test
    public void testKeyGestureCloseAllDialogs() {
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_CLOSE_ALL_DIALOGS);
        mPhoneWindowManager.assertCloseAllDialogs();
    }

    @Test
    public void testKeyGestureToggleDoNotDisturb() {
        mPhoneWindowManager.overrideZenMode(Settings.Global.ZEN_MODE_OFF);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_DO_NOT_DISTURB);
        mPhoneWindowManager.assertZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        mPhoneWindowManager.overrideZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_DO_NOT_DISTURB);
        mPhoneWindowManager.assertZenMode(Settings.Global.ZEN_MODE_OFF);
    }

    @Test
    public void testLaunchSettingsAndSearchDoesntOpenAnything_withKeyguardOn() {
        mPhoneWindowManager.overrideKeyguardOn(true);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH);

        mPhoneWindowManager.assertNoActivityLaunched();
    }

    @Test
    public void testLaunchSettingsAndSearchDoesntOpenAnything_withUserSetupIncomplete() {
        mPhoneWindowManager.overrideIsUserSetupComplete(false);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH);

        mPhoneWindowManager.assertNoActivityLaunched();
    }

    @Test
    public void testLaunchAssistantDoesntWork_withKeyguardOn() {
        mPhoneWindowManager.overrideKeyguardOn(true);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT);

        mPhoneWindowManager.assertSearchManagerDoesntLaunchAssist();
    }

    @Test
    public void testLaunchAssistantDoesntWork_withUserSetupIncomplete() {
        mPhoneWindowManager.overrideIsUserSetupComplete(false);

        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT);
        sendKeyGestureEventComplete(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT);

        mPhoneWindowManager.assertSearchManagerDoesntLaunchAssist();
    }

    @Test
    public void testAppLaunchShortcuts() {
        for (int i = 0; i < INTENT_SHORTCUTS.size(); i++) {
            final String category = INTENT_SHORTCUTS.valueAt(i);
            mPhoneWindowManager.sendKeyGestureEvent(
                    new KeyGestureEvent.Builder()
                            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                            .setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
                            .setAppLaunchData(AppLaunchData.createLaunchDataForCategory(category))
                            .build()
            );
            mPhoneWindowManager.assertLaunchCategory(category);
        }

        mPhoneWindowManager.overrideRoleManager();
        for (int i = 0; i < ROLE_SHORTCUTS.size(); i++) {
            final String role = ROLE_SHORTCUTS.valueAt(i);

            mPhoneWindowManager.sendKeyGestureEvent(
                    new KeyGestureEvent.Builder()
                            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                            .setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
                            .setAppLaunchData(AppLaunchData.createLaunchDataForRole(role))
                            .build()
            );
            mPhoneWindowManager.assertLaunchRole(role);
        }

        mPhoneWindowManager.sendKeyGestureEvent(
                new KeyGestureEvent.Builder()
                        .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                        .setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
                        .setAppLaunchData(
                                new AppLaunchData.ComponentData("com.test",
                                        "com.test.BookmarkTest"))
                        .build()
        );
        mPhoneWindowManager.assertActivityTargetLaunched(
                new ComponentName("com.test", "com.test.BookmarkTest"));

    }
}
