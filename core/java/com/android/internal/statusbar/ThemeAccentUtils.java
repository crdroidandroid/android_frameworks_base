/*
 * Copyright (C) 2018-2021 crDroid Android Project
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

    // QS Tile Styles
    private static final String[] QS_TILE_STYLES = {
        "com.android.systemui.qstile.default", // 0
        "com.android.systemui.qstile.circlegradient", // 1
        "com.android.systemui.qstile.circletrim", // 2
        "com.android.systemui.qstile.dottedcircle", // 3
        "com.android.systemui.qstile.dualtonecircle", // 4
        "com.android.systemui.qstile.dualtonecircletrim", // 5
        "com.android.systemui.qstile.ink", // 6
        "com.android.systemui.qstile.inkdrop", // 7
        "com.android.systemui.qstile.mountain", // 8
        "com.android.systemui.qstile.ninja", // 9
        "com.android.systemui.qstile.oreo", // 10
        "com.android.systemui.qstile.oreocircletrim", // 11
        "com.android.systemui.qstile.oreosquircletrim", // 12
        "com.android.systemui.qstile.pokesign", // 13
        "com.android.systemui.qstile.squaremedo", // 14
        "com.android.systemui.qstile.squircle", // 15
        "com.android.systemui.qstile.squircletrim", // 16
        "com.android.systemui.qstile.teardrop", // 17
        "com.android.systemui.qstile.wavey", // 18
        "com.android.systemui.qstile.cookie", //19
        "com.android.systemui.qstile.cosmos", //20
        "com.android.systemui.qstile.dividedcircle", //21
        "com.android.systemui.qstile.justicons", //22
        "com.android.systemui.qstile.neonlike", //23
        "com.android.systemui.qstile.triangle", //24
        "com.android.systemui.qstile.oos", //25
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

    // Unloads the QS tile styles
    private static void unloadQSTileStyle(OverlayManager om, UserHandle userId) {
        for (String theme : QS_TILE_STYLES) {
            try {
                om.setEnabled(theme, false, userId);
            } catch (Exception e) {
            }
        }
    }

    // Set QS tile style
    public static void setQSTileStyle(OverlayManager om, int qsTileStyle) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());

        // Always unload QS tile styles
        unloadQSTileStyle(om, userId);

        if (qsTileStyle == 0) return;

        try {
            om.setEnabled(QS_TILE_STYLES[qsTileStyle], true, userId);
        } catch (Exception e) {
        }
    }

    // Unloads the switch styles
    private static void unloadSwitchStyle(OverlayManager om, UserHandle userId) {
        for (String style : SWITCH_STYLES) {
            try {
                om.setEnabled(style, false, userId);
            } catch (Exception e) {
            }
        }
    }

    // Set switch style
    public static void setSwitchStyle(OverlayManager om, int switchStyle) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());

        // Always unload switch styles
        unloadSwitchStyle(om, userId);

        if (switchStyle == 0) return;

        try {
            om.setEnabled(SWITCH_STYLES[switchStyle], true, userId);
        } catch (Exception e) {
        }
    }

    public static void setCutoutOverlay(OverlayManager om, boolean enable) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());
        try {
            om.setEnabled("com.android.overlay.hidecutout", enable, userId);
        } catch (Exception e) {
        }
    }

    public static void setStatusBarStockOverlay(OverlayManager om, boolean enable) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());
        try {
            om.setEnabled("com.android.overlay.statusbarstock", enable, userId);
            om.setEnabled("com.android.overlay.statusbarstocksysui", enable, userId);
        } catch (Exception e) {
        }
    }

    public static void setImmersiveOverlay(OverlayManager om, boolean enable) {
        UserHandle userId = UserHandle.of(ActivityManager.getCurrentUser());
        try {
            om.setEnabled("com.android.overlay.immersive", enable, userId);
        } catch (Exception e) {
        }
    }
}
