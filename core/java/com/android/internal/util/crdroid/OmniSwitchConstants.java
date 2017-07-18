/*
* Copyright (C) 2014 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.internal.util.crdroid;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

public class OmniSwitchConstants {
    /**
     * Package name of the omnniswitch app
     */
    public static final String APP_PACKAGE_NAME = "org.omnirom.omniswitch";

    /**
     * Intent broadcast action for toogle the omniswitch overlay
     */
    private static final String ACTION_TOGGLE_OVERLAY2 = APP_PACKAGE_NAME + ".ACTION_TOGGLE_OVERLAY2";

    /**
     * Intent broadcast action for telling omniswitch to preload tasks
     */
    private static final String ACTION_PRELOAD_TASKS = APP_PACKAGE_NAME + ".ACTION_PRELOAD_TASKS";

    /**
     * Intent broadcast action for restoring the home stack
     */
    private static final String ACTION_RESTORE_HOME_STACK = APP_PACKAGE_NAME + ".ACTION_RESTORE_HOME_STACK";

    /**
     * Intent broadcast action for hide the omniswitch overlay
     */
    private static final String ACTION_HIDE_OVERLAY = APP_PACKAGE_NAME + ".ACTION_HIDE_OVERLAY";

    /**
     * Intent for launching the omniswitch settings actvity
     */
    public static Intent INTENT_LAUNCH_APP = new Intent(Intent.ACTION_MAIN)
            .setClassName(APP_PACKAGE_NAME, APP_PACKAGE_NAME + ".SettingsActivity");

    /**
     * @hide
     */
    public static void toggleOmniSwitchRecents(Context context, UserHandle user) {
        final Intent intent = new Intent(OmniSwitchConstants.ACTION_TOGGLE_OVERLAY2);
        intent.setPackage(APP_PACKAGE_NAME);
        context.sendBroadcastAsUser(intent, user);
    }

    /**
     * @hide
     */
    public static void hideOmniSwitchRecents(Context context, UserHandle user) {
        final Intent intent = new Intent(OmniSwitchConstants.ACTION_HIDE_OVERLAY);
        intent.setPackage(APP_PACKAGE_NAME);
        context.sendBroadcastAsUser(intent, user);
    }

    /**
     * @hide
     */
    public static void restoreHomeStack(Context context, UserHandle user) {
        final Intent intent = new Intent(OmniSwitchConstants.ACTION_RESTORE_HOME_STACK);
        intent.setPackage(APP_PACKAGE_NAME);
        context.sendBroadcastAsUser(intent, user);
    }

    /**
     * @hide
     */
     public static void preloadOmniSwitchRecents(Context context, UserHandle user) {
        final Intent intent = new Intent(OmniSwitchConstants.ACTION_PRELOAD_TASKS);
        intent.setPackage(APP_PACKAGE_NAME);
        context.sendBroadcastAsUser(intent, user);
    }
}
