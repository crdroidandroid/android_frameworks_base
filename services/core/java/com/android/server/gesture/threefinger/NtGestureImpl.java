/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.gesture.threefinger;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;
import android.util.Log;

import com.android.server.wm.WindowManagerService;
import com.android.server.gesture.threefinger.NtThreeFingerGesture;
import com.android.server.NtServiceInjector;

public class NtGestureImpl {

    public interface Callbacks {
        void onThreeFingerSwipe();
    }

    private static final String TAG = "NtGestureImpl";

    public static final Uri THREE_FINGER_GESTURE_URI = Settings.Secure.getUriFor("nothing_three_finger_screenshot");
    public static final Uri THREE_FINGER_LONG_PRESS_URI = Settings.Secure.getUriFor("nothing_three_finger_long_press");
    public static final Uri COMBO_SCREENSHOT_DISABLE_URI = Settings.Secure.getUriFor("nt_disable_combination_screenshot");

    private final Context mContext;
    private final Handler handler;
    private final GestureHandler gestureHandler;
    private final SettingsObserver settingsObserver;
    private final WindowManagerService mWindowManagerService;

    private boolean gestureCurrentlyEnabled;

    public NtGestureImpl(Callbacks callbacks) {
        mContext = NtServiceInjector.get().getContext();
        mWindowManagerService = NtServiceInjector.get().getWindowManagerService();

        HandlerThread thread = new HandlerThread("Nt-Gesture");
        thread.start();
        handler = new Handler(thread.getLooper());

        gestureHandler = NtThreeFingerGesture.create(mContext, callbacks, handler, mWindowManagerService);
        settingsObserver = new SettingsObserver(handler);
        settingsObserver.onChange(false, THREE_FINGER_GESTURE_URI);
        settingsObserver.onChange(false, THREE_FINGER_LONG_PRESS_URI);
        settingsObserver.onChange(false, COMBO_SCREENSHOT_DISABLE_URI);
        gestureHandler.updateSettings();
        updateGestureMonitoring();
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
            gestureHandler.registerObservers(mContext.getContentResolver(), this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null) {
                gestureHandler.onSettingsChanged(uri);
                updateGestureMonitoring();
            }
        }
    }

    private void updateGestureMonitoring() {
        boolean enabled = gestureHandler.isEnabled();
        if (enabled != gestureCurrentlyEnabled) {
            if (enabled) {
                mWindowManagerService.registerPointerEventListener(gestureHandler, DEFAULT_DISPLAY);
            } else {
                mWindowManagerService.unregisterPointerEventListener(gestureHandler, DEFAULT_DISPLAY);
            }
            gestureCurrentlyEnabled = enabled;
        }
    }

    public void onUserSwitching() {
        gestureHandler.updateSettings();
        updateGestureMonitoring();
    }

    public boolean shouldBlockKeyChordScreenshot() {
        return gestureHandler.isComboScreenshotBlocked();
    }

    public static boolean isGestureFeatureAvailable() {
        return true;
    }

    public interface GestureHandler extends WindowManagerPolicyConstants.PointerEventListener {
        default boolean isEnabled() {
            return false;
        }

        default boolean isComboScreenshotBlocked() {
            return false;
        }

        default void updateSettings() {}

        default void registerObservers(ContentResolver resolver, ContentObserver observer) {}

        default void onSettingsChanged(Uri uri) {}

        @Override
        default void onPointerEvent(MotionEvent event) {}
    }
}
