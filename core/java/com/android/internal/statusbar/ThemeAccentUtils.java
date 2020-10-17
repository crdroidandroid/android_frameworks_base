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

import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;

public class ThemeAccentUtils {

    public static final String TAG = "ThemeAccentUtils";

    // Dark Variants
    private static final String[] DARK_THEMES = {
        "com.android.system.theme.charcoalblack", // 0
        "com.android.system.theme.amoledblack", // 1
    };

    // Switch themes
    private static final String[] SWITCH_STYLES = {
        "com.android.system.switch.stock", // 0
        "com.android.system.switch.md2", // 1
        "com.android.system.switch.oneplus", // 2
        "com.android.system.switch.narrow", // 3
        "com.android.system.switch.contained", // 4
        "com.android.system.switch.retro", // 5
        "com.android.system.switch.telegram", // 6
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
    public static int getDarkStyle(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;

        for (int darkStyle = 0; darkStyle < DARK_THEMES.length; darkStyle++) {
            String darktheme = DARK_THEMES[darkStyle];
            try {
                themeInfo = om.getOverlayInfo(darktheme, userId);
                if (themeInfo != null && themeInfo.isEnabled())
                    return (darkStyle + 1);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    // Unloads the dark themes
    private static void unloadDarkTheme(IOverlayManager om, int userId) {
        for (String theme : DARK_THEMES) {
            try {
                om.setEnabled(theme, false, userId);
            } catch (RemoteException e) {
            }
        }
    }

    // Set dark system theme
    public static void setSystemTheme(IOverlayManager om, int userId, boolean useDarkTheme, int darkStyle) {
         unloadDarkTheme(om, userId);

        // Ensure dark/black theme enabled if requested
        if (useDarkTheme && darkStyle > 0) {
            try {
                om.setEnabled(DARK_THEMES[darkStyle - 1], useDarkTheme, userId);
            } catch (RemoteException e) {
            }
        }
    }

    // Unloads the switch styles
    private static void unloadSwitchStyle(IOverlayManager om, int userId) {
        for (String style : SWITCH_STYLES) {
            try {
                om.setEnabled(style, false, userId);
            } catch (RemoteException e) {
            }
        }
    }

    // Set switch style
    public static void setSwitchStyle(IOverlayManager om, int userId, int switchStyle) {
        // Always unload switch styles
        unloadSwitchStyle(om, userId);

        if (switchStyle == 0) return;

        try {
            om.setEnabled(SWITCH_STYLES[switchStyle], true, userId);
        } catch (RemoteException e) {
        }
    }

    // Unloads the navbar styles
    private static void unloadNavbarStyle(IOverlayManager om, int userId) {
        for (String style : NAVBAR_STYLES) {
            try {
                om.setEnabled(style, false, userId);
            } catch (RemoteException e) {
            }
        }
    }

    // Set navbar style
    public static void setNavbarStyle(IOverlayManager om, int userId, int navbarStyle) {
        // Always unload navbar styles
        unloadNavbarStyle(om, userId);

        if (navbarStyle == 0) return;

        try {
            om.setEnabled(NAVBAR_STYLES[navbarStyle], true, userId);
        } catch (RemoteException e) {
        }
    }

    public static void setCutoutOverlay(IOverlayManager om, int userId, boolean enable) {
        try {
            om.setEnabled("com.android.overlay.hidecutout", enable, userId);
        } catch (RemoteException e) {
        }
    }

    public static void setStatusBarStockOverlay(IOverlayManager om, int userId, boolean enable) {
        try {
            om.setEnabled("com.android.overlay.statusbarstock", enable, userId);
            om.setEnabled("com.android.overlay.statusbarstocksysui", enable, userId);
        } catch (RemoteException e) {
        }
    }

    public static void setImmersiveOverlay(IOverlayManager om, int userId, boolean enable) {
        try {
            om.setEnabled("com.android.overlay.immersive", enable, userId);
        } catch (RemoteException e) {
        }
    }
}
