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

import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;

import static com.android.server.gesture.threefinger.NtGestureImpl.THREE_FINGER_GESTURE_URI;
import static com.android.server.gesture.threefinger.NtGestureImpl.THREE_FINGER_LONG_PRESS_URI;
import static com.android.server.gesture.threefinger.NtGestureImpl.COMBO_SCREENSHOT_DISABLE_URI;
import static com.android.server.gesture.threefinger.NtGestureImpl.Callbacks;
import static com.android.server.gesture.threefinger.NtGestureImpl.GestureHandler;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.provider.Settings;
import android.view.MotionEvent;

import com.android.server.NtServiceInjector;
import com.android.server.wm.WindowManagerService;
import com.android.internal.util.NtThreeFingerGestureHelper;

public class NtThreeFingerGesture implements GestureHandler {

    private static final String TAG = "NtThreeFingerGesture";

    private final SwipeDownGesture swipeDownGesture;
    private final TouchHoldGesture touchHoldGesture;

    private boolean tfSwipeEnabled;
    private boolean tfLongPressEnabled;
    private boolean isComboScreenshotDisabled;
    private final Context mContext;

    private NtThreeFingerGesture(Context context, Callbacks callbacks, Handler handler, WindowManagerService wm) {
        mContext = context;
        swipeDownGesture = new SwipeDownGesture(context, callbacks);
        touchHoldGesture = new TouchHoldGesture(context, handler, wm);
        updateSettings();
    }

    public static GestureHandler create(Context context, Callbacks callbacks, Handler handler, WindowManagerService wm) {
        return new NtThreeFingerGesture(context, callbacks, handler, wm);
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        ActionMask actionMask = getActionMask(event.getAction());
        final boolean isTouchHold = tfLongPressEnabled ? touchHoldGesture.handle(actionMask, event) : false;
        if (!tfSwipeEnabled) return;
        if ((actionMask != ActionMask.UP && actionMask != ActionMask.CANCEL)|| !isTouchHold) {
            swipeDownGesture.handle(actionMask, event);
        } else {
            swipeDownGesture.reset(event);
        }
    }

    @Override
    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        tfSwipeEnabled = Settings.Secure.getIntForUser(
            resolver, "nothing_three_finger_screenshot", 0, USER_CURRENT) != 0;
        tfLongPressEnabled = Settings.Secure.getIntForUser(
            resolver, "nothing_three_finger_long_press", 0, USER_CURRENT) != 0;
        isComboScreenshotDisabled = Settings.Secure.getIntForUser(
            resolver, "nt_disable_combination_screenshot", 0, USER_CURRENT) != 0;
    }

    @Override
    public void registerObservers(ContentResolver resolver, ContentObserver observer) {
        resolver.registerContentObserver(THREE_FINGER_GESTURE_URI, false, observer, USER_ALL);
        resolver.registerContentObserver(THREE_FINGER_LONG_PRESS_URI, false, observer, USER_ALL);
        resolver.registerContentObserver(COMBO_SCREENSHOT_DISABLE_URI, false, observer, USER_ALL);
    }

    @Override
    public void onSettingsChanged(Uri uri) {
        if (THREE_FINGER_GESTURE_URI.equals(uri)
            || THREE_FINGER_LONG_PRESS_URI.equals(uri)
            || COMBO_SCREENSHOT_DISABLE_URI.equals(uri)) {
            updateSettings();
        }
    }

    @Override
    public boolean isEnabled() {
        return tfSwipeEnabled || tfLongPressEnabled;
    }

    @Override
    public boolean isComboScreenshotBlocked() {
        return isComboScreenshotDisabled;
    }

    private ActionMask getActionMask(int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            return ActionMask.FIRST_DOWN;
        }
        if (action == MotionEvent.ACTION_UP) {
            return ActionMask.LAST_UP;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            return ActionMask.MOVE;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            return ActionMask.CANCEL;
        }
        int actionMasked = action & MotionEvent.ACTION_MASK;
        return actionMasked == MotionEvent.ACTION_POINTER_DOWN 
            ? ActionMask.DOWN 
            : (actionMasked == MotionEvent.ACTION_POINTER_UP 
                ? ActionMask.UP 
                : ActionMask.NONE);
    }

    public enum ActionMask {
        NONE,
        FIRST_DOWN,
        DOWN,
        UP,
        MOVE,
        LAST_UP,
        CANCEL
    }
}
