/*
 * Copyright (C) 2025 AxionOS
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
package com.android.internal.util.crdroid;

import static android.graphics.Typeface.*;

import android.app.ActivityThread;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FontController {

    private static final String TAG = "FontController";

    private static FontController sInstance = null;

    private static final Set<String> OVERRIDE_FONTS = new HashSet<>(Arrays.asList(
            "google", "sans-serif", "gsf-"
    ));

    private static final Set<String> EXCLUDED_APPS = new HashSet<>(Arrays.asList(
            "it.subito",
            "tv.arte.plus7",
            "com.google.android.gm"
    ));

    private static final Map<String, Integer> WEIGHT_MAP = new ArrayMap<>();
    static {
        WEIGHT_MAP.put("thin", 100);
        WEIGHT_MAP.put("extralight", 200);
        WEIGHT_MAP.put("light", 300);
        WEIGHT_MAP.put("normal", 400);
        WEIGHT_MAP.put("regular", 400);
        WEIGHT_MAP.put("medium", 500);
        WEIGHT_MAP.put("semibold", 600);
        WEIGHT_MAP.put("bold", 700);
        WEIGHT_MAP.put("extrabold", 800);
        WEIGHT_MAP.put("black", 900);
    }

    public static FontController get() {
        if (sInstance == null) {
            sInstance = new FontController();
        }
        return sInstance;
    }

    private FontController() {
    }

    private void handleOnConfiguration(Resources res) {
        String pkgName = ActivityThread.currentPackageName();
        if (pkgName == null || EXCLUDED_APPS.contains(pkgName)) return;

        logger("handleOnConfiguration: Changing default font to: " + getFontName());
        changeFont(res);
    }

    public static Typeface getOverrideTypeface(String fontToOverride) {
        if (fontToOverride == null) return null;

        String currentFont = getFontName();

        if (fontToOverride.matches("^" + java.util.regex.Pattern.quote(currentFont) + "(-.*)?$")) {
            logger(fontToOverride + " matches current font root '" + currentFont + "', skipping override!");
            return null;
        }

        boolean override = OVERRIDE_FONTS.stream().anyMatch(fontToOverride::contains);
        if (!override) {
            logger("Not on override list, skipping override: " + fontToOverride);
            return null;
        }

        return TypefaceFactory.create(fontToOverride, currentFont);
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_font_debug", false)) {
            Log.d(TAG, msg);
        }
    }

    public static void OnConfigurationChanged(Resources res) {
        get().handleOnConfiguration(res);
    }

    private static class TypefaceFactory {

        public static Typeface create(String fontToOverride, String currentFont) {
            int weight = resolveWeightByName(fontToOverride);
            boolean isBold = weight >= 700;
            boolean isItalic = fontToOverride.contains("italic");

            int style = NORMAL;
            if (isBold && isItalic) style = BOLD_ITALIC;
            else if (isBold) style = BOLD;
            else if (isItalic) style = ITALIC;

            Typeface base = getSystemDefaultTypeface(currentFont);

            Typeface result = Typeface.create(base, style);
            result = Typeface.create(result, weight, isItalic);

            logger("TypefaceFactory.create: fontToOverride=" + fontToOverride +
                   ", style=" + style +
                   ", weight=" + weight +
                   ", isItalic=" + isItalic +
                   ", success=" + (result != null));

            return result;
        }

        private static int resolveWeightByName(String familyName) {
            for (Map.Entry<String, Integer> entry : WEIGHT_MAP.entrySet()) {
                if (familyName.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return 400;
        }
    }
}
