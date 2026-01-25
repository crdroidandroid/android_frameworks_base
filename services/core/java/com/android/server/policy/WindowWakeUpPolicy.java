/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.os.PowerManager.WAKE_REASON_CAMERA_LAUNCH;
import static android.os.PowerManager.WAKE_REASON_GESTURE;
import static android.os.PowerManager.WAKE_REASON_LID;
import static android.os.PowerManager.WAKE_REASON_POWER_BUTTON;
import static android.os.PowerManager.WAKE_REASON_WAKE_KEY;
import static android.os.PowerManager.WAKE_REASON_WAKE_MOTION;
import static android.view.KeyEvent.KEYCODE_POWER;

import static com.android.server.policy.Flags.supportInputWakeupDelegate;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_BEHAVIOR_NONE;
import static com.android.server.power.feature.flags.Flags.perDisplayWakeByTouch;

import android.annotation.Nullable;
import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeReason;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.server.LocalServices;

/** Policy controlling the decision and execution of window-related wake ups. */
class WindowWakeUpPolicy {
    private static final String TAG = "WindowWakeUpPolicy";

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final Clock mClock;

    // The policy will handle input-based wake ups if this delegate is null.
    @Nullable private WindowWakeUpPolicyInternal.InputWakeUpDelegate mInputWakeUpDelegate;

    WindowWakeUpPolicy(Context context) {
        this(context, Clock.SYSTEM_CLOCK);
    }

    @VisibleForTesting
    WindowWakeUpPolicy(Context context, Clock clock) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mClock = clock;

        if (supportInputWakeupDelegate()) {
            LocalServices.addService(WindowWakeUpPolicyInternal.class, new LocalService());
        }
    }

    private final class LocalService implements WindowWakeUpPolicyInternal {
        @Override
        public void setInputWakeUpDelegate(@Nullable InputWakeUpDelegate delegate) {
            if (!supportInputWakeupDelegate()) {
                Slog.w(TAG, "Input wake up delegates not supported.");
                return;
            }
            mInputWakeUpDelegate = delegate;
        }
    }

    /**
     * Wakes up from a key event.
     *
     * @param displayId the id of the display to wake.
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @param keyCode the {@link android.view.KeyEvent} key code of the key event.
     * @param isDown {@code true} if the event's action is {@link KeyEvent#ACTION_DOWN}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromKey(int displayId, long eventTime, int keyCode, boolean isDown) {
        return wakeUpFromKey(displayId, eventTime, keyCode, isDown, false);
    }

    /**
     * Wakes up from a key event.
     *
     * @param displayId the id of the display to wake.
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @param keyCode the {@link android.view.KeyEvent} key code of the key event.
     * @param isDown {@code true} if the event's action is {@link KeyEvent#ACTION_DOWN}.
     * @param withProximityCheck {@code true} if we should check proximity sensor before wakeup.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromKey(int displayId, long eventTime, int keyCode, boolean isDown,
            boolean withProximityCheck) {
        if (mInputWakeUpDelegate != null
                && mInputWakeUpDelegate.wakeUpFromKey(eventTime, keyCode, isDown)) {
            return true;
        }
        if (perDisplayWakeByTouch()) {
            wakeUp(
                    displayId,
                    eventTime,
                    keyCode == KEYCODE_POWER ? WAKE_REASON_POWER_BUTTON : WAKE_REASON_WAKE_KEY,
                    keyCode == KEYCODE_POWER ? "POWER" : "KEY",
                    withProximityCheck);
        } else {
            wakeUp(
                    eventTime,
                    keyCode == KEYCODE_POWER ? WAKE_REASON_POWER_BUTTON : WAKE_REASON_WAKE_KEY,
                    keyCode == KEYCODE_POWER ? "POWER" : "KEY",
                    withProximityCheck);
        }
        return true;
    }

    /**
     * Wakes up from a motion event.
     *
     * @param displayId the id of the display to wake.
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @param isDown {@code true} if the event's action is {@link MotionEvent#ACTION_DOWN}.
     * @param deviceGoingToSleep {@code true} if the device is in the middle of going to sleep. This
     *      will be {@code false} if the device is currently fully awake or is fully asleep
     *      (i.e. not trying to go to sleep)
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromMotion(
            int displayId, long eventTime, int source, boolean isDown,
            boolean deviceGoingToSleep) {
        if (mInputWakeUpDelegate != null
                && mInputWakeUpDelegate.wakeUpFromMotion(
                        eventTime, source, isDown, deviceGoingToSleep)) {
            return true;
        }
        if (perDisplayWakeByTouch()) {
            wakeUp(displayId, eventTime, WAKE_REASON_WAKE_MOTION, "MOTION");
        } else {
            wakeUp(eventTime, WAKE_REASON_WAKE_MOTION, "MOTION");
        }
        return true;
    }

    /**
     * Wakes up due to an opened camera cover.
     *
     * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromCameraCover(long eventTime) {
        wakeUp(eventTime, WAKE_REASON_CAMERA_LAUNCH, "CAMERA_COVER");
        return true;
    }

    /**
     * Wakes up due to an opened lid.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromLid() {
        final int lidBehavior = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LID_BEHAVIOR, LID_BEHAVIOR_NONE);
        if (lidBehavior != LID_BEHAVIOR_NONE) {
            wakeUp(mClock.uptimeMillis(), WAKE_REASON_LID, "LID");
            return true;
        }
        return false;
    }

    /**
     * Wakes up to prevent sleeping when opening camera through power button.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromPowerKeyCameraGesture() {
        wakeUp(mClock.uptimeMillis(), WAKE_REASON_CAMERA_LAUNCH, "CAMERA_GESTURE_PREVENT_LOCK");
        return true;
    }

    /**
     * Wake up from a wake gesture.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromWakeGesture() {
        wakeUp(mClock.uptimeMillis(), WAKE_REASON_GESTURE, "GESTURE");
        return true;
    }

    /**
     * Wakes up due to a Bluetooth HID profile connection.
     *
     * @return {@code true} if the policy allows the requested wake up and the request has been
     *      executed; {@code false} otherwise.
     */
    boolean wakeUpFromBluetooth() {
        wakeUp(mClock.uptimeMillis(), WAKE_REASON_WAKE_MOTION, "BLUETOOTH_DEVICE_CONNECTED");
        return true;
    }

    /** Wakes up {@link PowerManager}. */
    private void wakeUp(long wakeTime, @WakeReason int reason, String details) {
        wakeUp(wakeTime, reason, details, false);
    }

    private void wakeUp(long wakeTime, @WakeReason int reason, String details,
            boolean withProximityCheck) {
        if (withProximityCheck) {
            mPowerManager.wakeUpWithProximityCheck(wakeTime, reason, "android.policy:" + details,
                    Display.DEFAULT_DISPLAY);
        } else {
            mPowerManager.wakeUp(wakeTime, reason, "android.policy:" + details);
        }
    }

    /** Wakes up given display. */
    private void wakeUp(int displayId, long wakeTime, @WakeReason int reason, String details) {
        wakeUp(displayId, wakeTime, reason, details, false);
    }

    private void wakeUp(int displayId, long wakeTime, @WakeReason int reason, String details,
            boolean withProximityCheck) {
        // If we're given an invalid display id to wake, fall back to waking default display
        final int displayIdToWake =
                displayId == Display.INVALID_DISPLAY ? Display.DEFAULT_DISPLAY : displayId;
        // When there is a request to wakeup a default display, we would want to wakeup the displays
        // in the default and the adjacent groups
        if (com.android.server.display.feature.flags.Flags.separateTimeouts()
                && com.android.server.power.feature.flags.Flags.wakeAdjacentDisplaysOnWakeupCall()
                && displayIdToWake == Display.DEFAULT_DISPLAY) {
            wakeUp(wakeTime, reason, details, withProximityCheck);
            return;
        }
        if (withProximityCheck) {
            mPowerManager.wakeUpWithProximityCheck(wakeTime, reason, "android.policy:" + details,
                    displayIdToWake);
        } else {
            mPowerManager.wakeUp(wakeTime, reason, "android.policy:" + details, displayIdToWake);
        }
    }
}
