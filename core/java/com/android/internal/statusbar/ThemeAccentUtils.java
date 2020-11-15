/*
 * Copyright (C) 2018-2020 crDroid Android Project
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

package com.android.internal.statusbar;

import android.app.ActivityManager;
import android.content.om.OverlayManager;
import android.content.om.OverlayInfo;
import android.os.UserHandle;

public class ThemeAccentUtils {

    public static final String TAG = "ThemeAccentUtils";

    // Dark Variants
    private static final String[] DARK_THEMES = {
        "com.android.system.theme.charcoalblack", // 0
        "com.android.system.theme.amoledblack", // 1
    };

    // Navbar styles
    public static final String[] NAVBAR_STYLES = {
        "com.android.system.navbar.stock", //0
        "com.android.system.navbar.asus", //1
        "com.android.system.navbar.oneplus", //2
        "com.android.system.navbar.oneui", //3
        "com.android.system.navbar.tecno", //4
    };

    // Check for the dark system theme
    public static int getDarkStyle(OverlayManager om) {
        OverlayInfo themeInfo = null;
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());

        for (int darkStyle = 0; darkStyle < DARK_THEMES.length; darkStyle++) {
            String darktheme = DARK_THEMES[darkStyle];
            try {
                themeInfo = om.getOverlayInfo(darktheme, userId);
                if (themeInfo != null && themeInfo.isEnabled())
                    return (darkStyle + 1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    // Unloads the dark themes
    private static void unloadDarkTheme(OverlayManager om, UserHandle userId) {
        for (String theme : DARK_THEMES) {
            try {
                om.setEnabled(theme, false, userId);
            } catch (Exception e) {
            }
        }
    }

    // Set dark system theme
    public static void setSystemTheme(OverlayManager om, boolean useDarkTheme, int darkStyle) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());

        unloadDarkTheme(om, userId);

        // Ensure dark/black theme enabled if requested
        if (useDarkTheme && darkStyle > 0) {
            try {
                om.setEnabled(DARK_THEMES[darkStyle - 1], useDarkTheme, userId);
            } catch (Exception e) {
            }
        }
    }

    // Unloads the navbar styles
    private static void unloadNavbarStyle(OverlayManager om, UserHandle userId) {
        for (String style : NAVBAR_STYLES) {
            try {
                om.setEnabled(style, false, userId);
            } catch (Exception e) {
            }
        }
    }

    // Set navbar style
    public static void setNavbarStyle(OverlayManager om, int navbarStyle) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());

        // Always unload navbar styles
        unloadNavbarStyle(om, userId);

        if (navbarStyle == 0) return;

        try {
            om.setEnabled(NAVBAR_STYLES[navbarStyle], true, userId);
        } catch (Exception e) {
        }
    }
}
