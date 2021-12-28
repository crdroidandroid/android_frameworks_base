/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.ContentResolver;
import android.os.UserHandle;
import android.view.KeyEvent;

import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import lineageos.providers.LineageSettings;

import static org.lineageos.internal.util.DeviceKeysConstants.*;

/**
 * Various utilities for the navigation bar.
 */
public class NavbarUtilities {

    // These need to match the documentation/constants in
    //      core/res/res/values/config.xml
    // and the Action entries in
    //      lineage-sdk/sdk/src/java/org/lineageos/internal/util/DeviceKeysConstants.java
    public static final int KEY_ACTION_NOTHING = 0;
    public static final int KEY_ACTION_MENU = 1;
    public static final int KEY_ACTION_APP_SWITCH = 2;
    public static final int KEY_ACTION_SEARCH = 3;
    public static final int KEY_ACTION_VOICE_SEARCH = 4;
    public static final int KEY_ACTION_IN_APP_SEARCH = 5;
    public static final int KEY_ACTION_LAUNCH_CAMERA = 6;
    public static final int KEY_ACTION_SLEEP = 7;
    public static final int KEY_ACTION_LAST_APP = 8;
    public static final int KEY_ACTION_SPLIT_SCREEN = 9;
    public static final int KEY_ACTION_KILL_APP = 10;
    public static final int KEY_ACTION_TORCH = 11;
    public static final int KEY_ACTION_SCREENSHOT = 12;
    public static final int KEY_ACTION_VOLUME_PANEL = 13;
    public static final int KEY_ACTION_CLEAR_ALL_NOTIFICATIONS = 14;
    public static final int KEY_ACTION_NOTIFICATIONS = 15;
    public static final int KEY_ACTION_QS_PANEL = 16;
    public static final int KEY_ACTION_RINGER_MODES = 17;

    // Special values, used internal only.
    public static final int KEY_ACTION_HOME = 100;
    public static final int KEY_ACTION_BACK = 101;

    /**
     * List of key codes to intercept with our custom policy.
     */
    public static final int[] SUPPORTED_KEYCODE_LIST = {
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_APP_SWITCH,
    };

    /*
     * List of key actions available for key code behaviors.
     *
     * These need to match the order of the Action entries in
     * lineage-sdk/sdk/src/java/org/lineageos/internal/util/DeviceKeysConstants.java
     */
    static final int[] SUPPORTED_KEY_ACTIONS = {
        KEY_ACTION_NOTHING,
        KEY_ACTION_MENU,
        KEY_ACTION_APP_SWITCH,
        KEY_ACTION_SEARCH,
        KEY_ACTION_VOICE_SEARCH,
        KEY_ACTION_IN_APP_SEARCH,
        KEY_ACTION_LAUNCH_CAMERA,
        KEY_ACTION_SLEEP,
        KEY_ACTION_LAST_APP,
        KEY_ACTION_SPLIT_SCREEN,
        KEY_ACTION_KILL_APP,
        KEY_ACTION_TORCH,
        KEY_ACTION_SCREENSHOT,
        KEY_ACTION_VOLUME_PANEL,
        KEY_ACTION_CLEAR_ALL_NOTIFICATIONS,
        KEY_ACTION_NOTIFICATIONS,
        KEY_ACTION_QS_PANEL,
        KEY_ACTION_RINGER_MODES,
    };

    /**
     * @return the key action ID code after checking if it is out of bounds.
     * @param id the action ID code, or KEY_ACTION_NOTHING if out of bounds.
     */
    public static int fromIntSafe(int id) {
        if (id < KEY_ACTION_NOTHING || id > SUPPORTED_KEY_ACTIONS.length) {
            return KEY_ACTION_NOTHING;
        }
        return id;
    }

    /**
     * @return the value of the specified setting in LineageSettings.System.
     * @param cr the supplied ContentResolver.
     * @param setting the LineageSettings.System setting.
     * @param def the default value if no value has been set yet.
     */
    public static int fromSettings(ContentResolver cr, String setting, int def) {
        return fromIntSafe(LineageSettings.System.getIntForUser(cr,
                setting, def, UserHandle.USER_CURRENT));
    }

    /**
     * @return the default res id for the key double tap default action.
     * @param keyCode the KeyEvent key code.
     */
    public static int getKeyDoubleTapBehaviorResId(int keyCode) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_HOME:
                return org.lineageos.platform.internal.R.integer.config_doubleTapOnHomeBehavior;
            case KeyEvent.KEYCODE_BACK:
                return org.lineageos.platform.internal.R.integer.config_doubleTapOnBackBehavior;
            case KeyEvent.KEYCODE_MENU:
                return org.lineageos.platform.internal.R.integer.config_doubleTapOnMenuBehavior;
            case KeyEvent.KEYCODE_ASSIST:
                return org.lineageos.platform.internal.R.integer.config_doubleTapOnAssistBehavior;
            case KeyEvent.KEYCODE_APP_SWITCH:
                return org.lineageos.platform.internal.R.integer.config_doubleTapOnAppSwitchBehavior;
        }
        return 0;
    }

    /**
     * @return the default res id for the key long press default action.
     * @param keyCode the KeyEvent key code.
     */
    public static int getKeyLongPressBehaviorResId(int keyCode) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_HOME:
                return org.lineageos.platform.internal.R.integer.config_longPressOnHomeBehavior;
            case KeyEvent.KEYCODE_BACK:
                return org.lineageos.platform.internal.R.integer.config_longPressOnBackBehavior;
            case KeyEvent.KEYCODE_MENU:
                return org.lineageos.platform.internal.R.integer.config_longPressOnMenuBehavior;
            case KeyEvent.KEYCODE_ASSIST:
                return org.lineageos.platform.internal.R.integer.config_longPressOnAssistBehavior;
            case KeyEvent.KEYCODE_APP_SWITCH:
                return org.lineageos.platform.internal.R.integer.config_longPressOnAppSwitchBehavior;
        }
        return 0;
    }

    /**
     * @return if key code is supported by custom policy.
     * @param keyCode the KeyEvent key code.
     */
    public static boolean canApplyCustomPolicy(int keyCode) {
        boolean supported = false;
        int length = NavbarUtilities.SUPPORTED_KEYCODE_LIST.length;
        for (int i = 0; i < length; i++) {
            if (NavbarUtilities.SUPPORTED_KEYCODE_LIST[i] == keyCode) {
                supported = true;
                break;
            }
        }
        return supported/* && mDeviceHardwareKeys > 0*/;
    }

    /**
     * @return key code's long press behavior.
     * @param keyCode the KeyEvent key code.
     */
    public static int getKeyLongPressBehavior(int keyCode) {
        int behavior = -1;
        try {
            behavior = PhoneWindowManager.mKeyLongPressBehavior.get(keyCode);
        } catch (NullPointerException e) {
            // Ops.
        } finally {
            return behavior;
        }
    }

    /**
     * @return if key code's double tap is pending.
     * @param keyCode the KeyEvent key code.
     */
    public static boolean isKeyDoubleTapPending(int keyCode) {
        boolean pending = false;
        try {
            pending = PhoneWindowManager.mKeyDoubleTapPending.get(keyCode);
        } catch (NullPointerException e) {
            // Ops.
        } finally {
            return pending;
        }
    }

    /**
     * @return key code's dpouble tap behavior.
     * @param keyCode the KeyEvent key code.
     */
    public static int getKeyDoubleTapBehavior(int keyCode) {
        int behavior = -1;
        try {
            behavior = PhoneWindowManager.mKeyDoubleTapBehavior.get(keyCode);
        } catch (NullPointerException e) {
            // Ops.
        } finally {
            return behavior;
        }
    }

    /**
     * @return key code's double tap timeout runnable.
     * @param keyCode the KeyEvent key code.
     */
    public static Runnable getDoubleTapTimeoutRunnable(int keyCode) {
        Runnable runnable = null;
        try {
            runnable = PhoneWindowManager.mKeyDoubleTapRunnable.get(keyCode);
        } catch (NullPointerException e) {
            // Ops.
        } finally {
            return runnable;
        }
    }

    /**
     * @return if key code's last event has been consumed.
     * @param keyCode the KeyEvent key code.
     */
    public static boolean isKeyConsumed(int keyCode) {
        boolean consumed = false;
        try {
            consumed = PhoneWindowManager.mKeyConsumed.get(keyCode);
        } catch (NullPointerException e) {
            // Ops.
        } finally {
            return consumed;
        }
    }

    /**
     * Set last key code's event consumed state.
     * @param keyCode the KeyEvent key code.
     */
    public static void setKeyConsumed(int keyCode, boolean consumed) {
        try {
            PhoneWindowManager.mKeyConsumed.put(keyCode, consumed);
        } catch (NullPointerException e) {
            // Ops.
        }
    }

    /**
     * Set last key code's event pressed state.
     * @param keyCode the KeyEvent key code.
     */
    public static void setKeyPressed(int keyCode, boolean pressed) {
        try {
            PhoneWindowManager.mKeyPressed.put(keyCode, pressed);
        } catch (NullPointerException e) {
            // Ops.
        }
    }

    /**
     * Set last key code's event double tap pending state.
     * @param keyCode the KeyEvent key code.
     */
    public static void setKeyDoubleTapPending(int keyCode, boolean pending) {
        try {
            PhoneWindowManager.mKeyDoubleTapPending.put(keyCode, pending);
        } catch (NullPointerException e) {
            // Ops.
        }
    }
}
