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
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FontController {

    private static final String TAG = "FontController";

    private static FontController sInstance = null;

    private static final Set<String> EXCLUDED_APPS = new HashSet<>(Arrays.asList(
            "it.subito",
            "tv.arte.plus7",
            "com.google.android.gm"
    ));

    private static final Map<String, Integer> WEIGHT_MAP = new ArrayMap<>();
    static {
        WEIGHT_MAP.put("thin", 100);
        WEIGHT_MAP.put("light", 300);
        WEIGHT_MAP.put("regular", 400);
        WEIGHT_MAP.put("medium", 500);
        WEIGHT_MAP.put("bold", 700);
        WEIGHT_MAP.put("black", 900);
    }

    public static FontController get() {
        if (sInstance == null) sInstance = new FontController();
        return sInstance;
    }

    private FontController() {
    }

    private void handleOnConfiguration(Resources res) {
        String pkgName = ActivityThread.currentPackageName();

        if (pkgName == null) return;
        if (EXCLUDED_APPS.contains(pkgName)) return;

        logger("handleOnConfiguration: Changing default font to: " + getFontName());

        changeFont(res);
    }

    private static Typeface getResolvedTypeface(String familyName) {
        int style;
        int weight = resolveWeightByName(familyName);

        boolean isBold = weight >= 700;
        boolean isItalic = familyName.contains("italic");

        if (isBold && isItalic) {
            style = BOLD_ITALIC;
        } else if (isBold) {
            style = BOLD;
        } else if (isItalic) {
            style = ITALIC;
        } else {
            style = NORMAL;
        }

        Typeface tf = getSystemDefaultTypeface(getFontName());

        tf = create(tf, style);
        tf = create(tf, weight, isItalic);

        logger("getResolvedTypeface: family=" + familyName +
               ", style=" + style +
               ", weight=" + weight +
               ", isItalic=" + isItalic +
               ", created Typeface=" + (tf != null ? "success" : "null"));

        return tf;
    }

    public static Typeface getOverrideTypeface(String familyName) {
        boolean same = TextUtils.equals(getFontName(), familyName);

        if (familyName == null || same) return null;

        String fN = familyName.toLowerCase();

        boolean override = fN.contains("google") || fN.contains("sans-serif");

        Typeface result = override ? getResolvedTypeface(familyName) : null;

        logger("getOverrideTypeface: family=" + familyName +
               ", override=" + override +
               ", defaultFont=" + getFontName() +
               ", result=" + (result != null ? getFontName() : "null"));

        return result;
    }

    private static int resolveWeightByName(String familyName) {
        String fN = familyName.toLowerCase();
        for (Map.Entry<String, Integer> entry : WEIGHT_MAP.entrySet()) {
            if (fN.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 400;
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_font_debug", false)) {
            Log.d(TAG, msg);
        }
    }

    public static void OnConfigurationChanged(Resources res) {
        get().handleOnConfiguration(res);
    }
}
