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
package com.android.internal.util.android;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFileUtil;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.text.TextUtils;
import com.android.internal.R;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

public class FontController {

    private static final String TAG = "FontController";

    private static final FontController sInstance = new FontController();

    private volatile Resources sResources = null;

    private volatile String sFontFamily = "sans-serif";

    private static final String DEFAULT_FONT_ROOT = "google-sans-flex";

    public static final String CUSTOM_FONT_MARKER_FAMILY = "user-custom-font";

    private static final String[] BUNDLED_UI_FONT_PREFIXES = {
            "googlesans",
            "productsans",
            "sourcesans",
            "roboto"
    };

    private static final String[] BUNDLED_UI_FONT_EXCLUDES = {
            "mono",
            "code",
            "clock",
            "slab",
            "serif",
            "condensed"
    };

    private static final String[] DEFAULT_FONT_FALLBACKS = {
            "google-sans-flex",
            "google-sans",
            "sans-serif"
    };

    private static final String[] OVERRIDE_FAMILY_PREFIXES = {
            "sans-serif",
            "google-sans",
            "gsf-",
            "variable-",
            "roboto",
            "source-sans-pro"
    };

    private static final String[] OVERRIDE_FAMILY_EXCLUDES = {
            "clock",
            "mono",
            "condensed",
            "smallcaps"
    };

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

        WEIGHT_MAP.put("variable-display-large-emphasized", 500);
        WEIGHT_MAP.put("variable-display-medium-emphasized", 500);
        WEIGHT_MAP.put("variable-display-small-emphasized", 500);
        WEIGHT_MAP.put("variable-headline-large-emphasized", 500);
        WEIGHT_MAP.put("variable-headline-medium-emphasized", 500);
        WEIGHT_MAP.put("variable-headline-small-emphasized", 500);
        WEIGHT_MAP.put("variable-title-large-emphasized", 500);
        WEIGHT_MAP.put("variable-title-medium-emphasized", 600);
        WEIGHT_MAP.put("variable-title-small-emphasized", 600);
        WEIGHT_MAP.put("variable-label-large-emphasized", 600);
        WEIGHT_MAP.put("variable-label-medium-emphasized", 600);
        WEIGHT_MAP.put("variable-label-small-emphasized", 600);
        WEIGHT_MAP.put("variable-body-large-emphasized", 500);
        WEIGHT_MAP.put("variable-body-medium-emphasized", 500);
        WEIGHT_MAP.put("variable-body-small-emphasized", 500);

        WEIGHT_MAP.put("variable-display-large", 400);
        WEIGHT_MAP.put("variable-display-medium", 400);
        WEIGHT_MAP.put("variable-display-small", 400);
        WEIGHT_MAP.put("variable-headline-large", 400);
        WEIGHT_MAP.put("variable-headline-medium", 400);
        WEIGHT_MAP.put("variable-headline-small", 400);
        WEIGHT_MAP.put("variable-title-large", 400);
        WEIGHT_MAP.put("variable-title-medium", 500);
        WEIGHT_MAP.put("variable-title-small", 500);
        WEIGHT_MAP.put("variable-label-large", 500);
        WEIGHT_MAP.put("variable-label-medium", 500);
        WEIGHT_MAP.put("variable-label-small", 500);
        WEIGHT_MAP.put("variable-body-large", 400);
        WEIGHT_MAP.put("variable-body-medium", 400);
        WEIGHT_MAP.put("variable-body-small", 400);
    }

    public static FontController get() {
        return sInstance;
    }

    private FontController() {}

    public static void OnConfigurationChanged(Resources res) {
        get().handleOnConfiguration(res);
    }

    public static Typeface getOverrideTypeface(String fontToOverride) {
        return get().getOverrideTypefaceInternal(fontToOverride);
    }

    public static String getCurrentFontFamily() {
        return get().getCurrentFont();
    }

    private String getCurrentFont() {
        if (sResources == null) return sFontFamily;
        try {
            String currFont = sResources.getString(R.string.config_bodyFontFamily);
            if (!TextUtils.isEmpty(currFont) && !TextUtils.equals(sFontFamily, currFont)) {
                sFontFamily = currFont;
                logger("Font changed to: " + sFontFamily);
            }
        } catch (Exception e) {
            logger("getCurrentFont failed: " + e.getMessage());
        }
        return sFontFamily;
    }

    private Typeface getOverrideTypefaceInternal(String fontToOverride) {
        if (fontToOverride == null) return null;

        final String pkgName = getCurrentPackageName();

        if (pkgName == null) return null;

        if (EXCLUDED_APPS.contains(pkgName)) {
            logger("Excluded app, skipping override: " + pkgName);
            return null;
        }

        String currentFont = getCurrentFont();

        if (fontToOverride.matches("^" + Pattern.quote(currentFont) + "(-.*)?$")) {
            logger(fontToOverride + " matches current font root '" + currentFont + "', skipping override!");
            return null;
        }

        // Don't remap variable-* families when the default Google Sans Flex
        // font stack is active; let them pass through so optical sizing and
        // ROND axis values in fonts_customization.xml render correctly.
        if (fontToOverride.contains("variable") && isDefaultFont(currentFont)) {
            logger("Default font active, passing through variable family: " + fontToOverride);
            return null;
        }

        if (!isOverridableFamily(fontToOverride)) {
            logger("Not on override list, skipping override: " + fontToOverride);
            return null;
        }

        return TypefaceFactory.create(fontToOverride, resolveDefaultTypeface());
    }

    @Nullable
    public static Typeface getBundledFontOverride(@Nullable Font font, int weight,
            boolean italic) {
        if (font == null) return null;
        try {
            return get().getBundledFontOverrideInternal(font, weight, italic);
        } catch (Throwable t) {
            // Never break typeface creation for an app.
            logger("getBundledFontOverride failed: " + t);
            return null;
        }
    }

    public static boolean hasCustomFont() {
        return Typeface.getSystemTypeface(CUSTOM_FONT_MARKER_FAMILY) != null;
    }

    private Typeface getBundledFontOverrideInternal(Font font, int weight, boolean italic) {
        if (ActivityThread.isSystem()) return null;
        if (sResources == null || Typeface.getSystemFontMap().isEmpty()) return null;

        final String pkgName = getCurrentPackageName();
        if (pkgName == null) return null;
        if (EXCLUDED_APPS.contains(pkgName)) return null;

        final String currentFont = getCurrentFont();
        if (isDefaultFont(currentFont) && !hasCustomFont()) return null;

        final String psName = getPostScriptName(font);
        if (!isBundledUiFont(psName)) return null;

        // Keep the requested weight as is; fontWeightAdjustment is applied by the caller.
        final int clampedWeight = Math.max(1, Math.min(1000, weight));
        if (!italic) {
            Typeface named = TypefaceFactory.resolveNamedVariant(currentFont, clampedWeight, false);
            if (named != null) {
                logger("Bundled font " + psName + " -> named variant, weight=" + clampedWeight);
                return named;
            }
        }
        final Typeface base = resolveDefaultTypeface();
        logger("Bundled font " + psName + " in " + pkgName + " -> " + currentFont
                + ", weight=" + clampedWeight + ", italic=" + italic);
        return Typeface.create(base, clampedWeight, italic);
    }

    @Nullable
    private static String getPostScriptName(Font font) {
        try {
            ByteBuffer buffer = font.getBuffer();
            if (buffer == null) return null;
            return FontFileUtil.getPostScriptName(buffer, font.getTtcIndex());
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isBundledUiFont(@Nullable String psName) {
        if (TextUtils.isEmpty(psName)) return false;
        final String normalized = psName.toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
        for (String exclude : BUNDLED_UI_FONT_EXCLUDES) {
            if (normalized.contains(exclude)) return false;
        }
        for (String prefix : BUNDLED_UI_FONT_PREFIXES) {
            if (normalized.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isOverridableFamily(@Nullable String familyName) {
        if (TextUtils.isEmpty(familyName)) return false;
        final String name = familyName.toLowerCase(Locale.ROOT);
        for (String exclude : OVERRIDE_FAMILY_EXCLUDES) {
            if (name.contains(exclude)) return false;
        }
        for (String prefix : OVERRIDE_FAMILY_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    public static Typeface resolveBaseTypeface() {
        return get().resolveDefaultTypeface();
    }

    public static boolean isDefaultFont(String fontFamily) {
        return fontFamily != null && fontFamily.contains(DEFAULT_FONT_ROOT);
    }

    private Typeface resolveDefaultTypeface() {
        // First try to resolve the currently active custom font directly
        String currentFont = getCurrentFont();
        Typeface installed = Typeface.getSystemTypeface(currentFont);
        if (installed != null) {
            logger("resolveDefaultTypeface: resolved current font '" + currentFont + "'");
            return installed;
        }
        // Family-list ids such as google-sans-flex are not always map keys.
        for (String family : DEFAULT_FONT_FALLBACKS) {
            Typeface tf = Typeface.getSystemTypeface(family);
            if (tf != null) {
                logger("resolveDefaultTypeface: resolved via fallback '" + family + "'");
                return tf;
            }
        }
        logger("resolveDefaultTypeface: all fallbacks failed, using DEFAULT");
        return Typeface.DEFAULT;
    }

    private void handleOnConfiguration(Resources res) {
        if (res == null) return;
        sResources = res;
        String pkgName = getCurrentPackageName();
        if (pkgName == null || EXCLUDED_APPS.contains(pkgName)) return;
        logger("handleOnConfiguration: Changing default font to: " + Typeface.getFontName());
        Typeface.changeFont();
    }

    private String getCurrentPackageName() {
        try {
            return ActivityThread.currentPackageName();
        } catch (Exception e) {
            logger("getCurrentPackageName failed: " + e.getMessage());
            return null;
        }
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_font_debug", false)) {
            Log.d(TAG, msg);
        }
    }

    private static class TypefaceFactory {

        public static Typeface create(String fontToOverride, Typeface base) {
            int weight = resolveWeightByName(fontToOverride);

            boolean isItalic = fontToOverride.contains("italic");
            boolean isBold = weight >= 700;

            int style = Typeface.NORMAL;
            if (isBold && isItalic) style = Typeface.BOLD_ITALIC;
            else if (isBold) style = Typeface.BOLD;
            else if (isItalic) style = Typeface.ITALIC;

            if (base == null) base = Typeface.DEFAULT;

            // Try to resolve a named weight variant of the current font first
            // (e.g. aclonica-semi-bold, nunito-medium) before falling back to
            // dynamic weight building on the base typeface. This gives correct
            // results for fonts that have proper weight files defined.
            String currentFont = FontController.get().getCurrentFont();
            Typeface named = resolveNamedVariant(currentFont, weight, isItalic);
            if (named != null) {
                logger("TypefaceFactory.create: resolved named variant for '" +
                       currentFont + "' weight=" + weight);
                return named;
            }

            Typeface result = Typeface.create(base, style);
            result = Typeface.create(result, weight, isItalic);

            logger("TypefaceFactory.create: fontToOverride=" + fontToOverride +
                   ", style=" + style +
                   ", weight=" + weight +
                   ", isItalic=" + isItalic +
                   ", success=" + (result != null));

            return result;
        }

        private static Typeface resolveNamedVariant(String fontName, int weight, boolean isItalic) {
            if (fontName == null) return null;

            // Map weight to suffix candidates in priority order
            String[] suffixes;
            if (weight >= 700) {
                suffixes = new String[]{ "-bold", "-extra-bold" };
            } else if (weight >= 600) {
                suffixes = new String[]{ "-semi-bold", "-bold" };
            } else if (weight >= 500) {
                suffixes = new String[]{ "-medium", "-semi-bold" };
            } else {
                suffixes = new String[]{ "", "-light" };
            }

            for (String suffix : suffixes) {
                String candidate = fontName + suffix;
                Typeface tf = Typeface.getSystemTypeface(candidate);
                if (tf != null) {
                    logger("resolveNamedVariant: matched '" + candidate + "'");
                    return tf;
                }
            }
            return null;
        }

        private static int resolveWeightByName(String familyName) {
            Integer exactMatch = WEIGHT_MAP.get(familyName);
            if (exactMatch != null) {
                return exactMatch;
            }
            for (Map.Entry<String, Integer> entry : WEIGHT_MAP.entrySet()) {
                if (familyName.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return 400;
        }
    }
}
