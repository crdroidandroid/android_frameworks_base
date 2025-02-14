
/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2025 the AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.android;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONObject;
import org.json.JSONException;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class PropsHooksUtils {

    private static final String TAG = PropsHooksUtils.class.getSimpleName();
    
    private static boolean DEBUG = SystemProperties.getBoolean("persist.sys.props_hooks_debug", false);

    public static final String SPOOF_PIXEL_GMS = "persist.sys.pixelprops.gms";
    public static final String SPOOF_PIXEL_GPHOTOS = "persist.sys.pixelprops.gphotos";
    public static final String SPOOF_GAMES = "persist.sys.gameprops.enable";
    
    private static volatile boolean sIsPhotos;

    private static final Map<String, Object> propsToChangePixelXL;
    private static final Map<String, Field> fieldCache = new HashMap<>();
    private static Boolean isPixelDevice = null;
    private static Boolean isLargeScreen = null;

    private static final Set<String> featuresPixel = new HashSet<>(Set.of(
            "PIXEL_2017_PRELOAD",
            "PIXEL_2018_PRELOAD",
            "PIXEL_2019_MIDYEAR_PRELOAD",
            "PIXEL_2019_PRELOAD",
            "PIXEL_2020_EXPERIENCE",
            "PIXEL_2020_MIDYEAR_EXPERIENCE",
            "PIXEL_EXPERIENCE"
    ));

    private static final Set<String> featuresNexus = new HashSet<>(Set.of(
            "com.google.android.apps.photos.NEXUS_PRELOAD",
            "com.google.android.apps.photos.nexus_preload",
            "com.google.android.feature.PIXEL_EXPERIENCE",
            "com.google.android.feature.GOOGLE_BUILD",
            "com.google.android.feature.GOOGLE_EXPERIENCE"
    ));

    private static final Map<String, Map<String, Object>> packagePropsMap = new HashMap<>();

    private static final Map<String, Object> propsToChangeBS4 = createMap("2SM-X706B", "blackshark");
    private static final Set<String> packagesToChangeBS4 = new HashSet<>(Set.of(
            "com.proximabeta.mf.uamo"
    ));

    private static final Map<String, Object> propsToChangeMI11TP = createMap("2107113SI", "Xiaomi");
    private static final Set<String> packagesToChangeMI11TP = new HashSet<>(Set.of(
            "com.levelinfinite.hotta.gp",
            "com.supercell.brawlstars",
            "com.supercell.clashofclans",
            "com.vng.mlbbvn"
    ));

    private static final Map<String, Object> propsToChangeMI13P = createMap("2210132C", "Xiaomi");
    private static final Set<String> packagesToChangeMI13P = new HashSet<>(Set.of(
            "com.levelinfinite.sgameGlobal",
            "com.tencent.tmgp.sgame"
    ));

    private static final Map<String, Object> propsToChangeOP8P = createMap("IN2020", "OnePlus");
    private static final Set<String> packagesToChangeOP8P = new HashSet<>(Set.of(
            "com.netease.lztgglobal",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildrifttw",
            "com.riotgames.league.wildriftvn",
            "com.riotgames.league.teamfighttactics",
            "com.riotgames.league.teamfighttacticstw",
            "com.riotgames.league.teamfighttacticsvn"
    ));

    private static final Map<String, Object> propsToChangeOP9P = createMap("LE2101", "OnePlus");
    private static final Set<String> packagesToChangeOP9P = new HashSet<>(Set.of(
            "com.epicgames.fortnite",
            "com.epicgames.portal",
            "com.tencent.lolm"
    ));

    private static final Map<String, Object> propsToChangeF5 = createMap("23049PCD8G", "Xiaomi");
    private static final Set<String> packagesToChangeF5 = new HashSet<>(Set.of(
            "com.dts.freefiremax",
            "com.dts.freefireth"
    ));

    private static final Map<String, Object> propsToChangeROG6 = createMap("ASUS_AI2201", "asus");
    private static final Set<String> packagesToChangeROG6 = new HashSet<>(Set.of(
            "com.ea.gp.fifamobile",
            "com.gameloft.android.ANMP.GloftA9HM",
            "com.madfingergames.legends",
            "com.pearlabyss.blackdesertm",
            "com.pearlabyss.blackdesertm.gl"
    ));

    private static final Map<String, Object> propsToChangeROG8P = createMap("ASUS_AI2401_A", "asus");
    private static final Set<String> packagesToChangeROG8P = new HashSet<>(Set.of(
            "com.ea.gp.apexlegendsmobilefps",
            "com.mobile.legends"
    ));

    private static final Map<String, Object> propsToChangeLenovoY700 = createMap("Lenovo TB-9707F", "Lenovo");
    private static final Set<String> packagesToChangeLenovoY700 = new HashSet<>(Set.of(
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codmvn"
    ));

    private static final Map<String, Object> propsToChangeS24Ultra  = createMap("SM-S928B", "samsung");
    private static final Map<String, Object> propsToChangeS9Tab = createMap("SM-X916B", "samsung");
    private static final Set<String> pubgPackages = new HashSet<>(Set.of(
            "com.pubg.imobile",
            "com.pubg.krmobile",
            "com.rekoo.pubgm",
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.vng.pubgmobile"
    ));

    static {
        addToPackageMap(packagesToChangeBS4, propsToChangeBS4);
        addToPackageMap(packagesToChangeMI11TP, propsToChangeMI11TP);
        addToPackageMap(packagesToChangeMI13P, propsToChangeMI13P);
        addToPackageMap(packagesToChangeOP8P, propsToChangeOP8P);
        addToPackageMap(packagesToChangeOP9P, propsToChangeOP9P);
        addToPackageMap(packagesToChangeF5, propsToChangeF5);
        addToPackageMap(packagesToChangeROG6, propsToChangeROG6);
        addToPackageMap(packagesToChangeROG8P, propsToChangeROG8P);
        addToPackageMap(packagesToChangeLenovoY700, propsToChangeLenovoY700);

        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("HARDWARE", "marlin");
        propsToChangePixelXL.put("ID", "QP1A.191005.007.A3");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
    }

    private static void addToPackageMap(Set<String> packages, Map<String, Object> props) {
        for (String pkg : packages) {
            packagePropsMap.put(pkg, props);
        }
    }

    private static Map<String, Object> createMap(String model, String manufacturer) {
        Map<String, Object> map = new HashMap<>();
        map.put("MODEL", model);
        map.put("MANUFACTURER", manufacturer);
        return map;
    }

    public static void setProps(Context context) {
        if (context == null) return;
        
        boolean isTablet = isLargeScreen(context);

        packagePropsMap.keySet().removeAll(pubgPackages);

        addToPackageMap(pubgPackages, isTablet ? propsToChangeS9Tab : propsToChangeS24Ultra);

        String packageName = context.getPackageName();

        if (TextUtils.isEmpty(packageName)) {
            return;
        }

        final String processName = Application.getProcessName();
        if (TextUtils.isEmpty(processName)) {
            return;
        }

        if (shoudlSpoofGames()) {
            Map<String, Object> propsToChange = packagePropsMap.get(packageName);
            if (propsToChange != null) {
                dlog("Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            }
        }

        sIsPhotos = packageName.equals("com.google.android.apps.photos");

        if (shouldSpoofPhotos()) {
            for (Map.Entry<String, Object> entry : propsToChangePixelXL.entrySet()) {
                setPropValue(entry.getKey(), entry.getValue());
            }
        }

        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPropValue("FINGERPRINT", "eng.nobody." +
                new java.text.SimpleDateFormat("yyyyMMdd.HHmmss").format(new java.util.Date()));
        }
    }

    private static void setPropValue(String key, Object newValue) {
        try {
            Field field = getBuildClassField(key);
            if (field == null) {
                dlog("Field " + key + " not found in Build or Build.VERSION classes");
                return;
            }

            Object currentValue = field.get(null);
            if (isObjectEqual(currentValue, newValue)) {
                return;
            }

            if (field.getType() == int.class) {
                field.setInt(null, newValue instanceof Integer ? 
                    (Integer) newValue : Integer.parseInt(newValue.toString()));
            } else if (field.getType() == long.class) {
                field.setLong(null, newValue instanceof Long ? 
                    (Long) newValue : Long.parseLong(newValue.toString()));
            } else {
                field.set(null, newValue.toString());
            }
            dlog("Set prop " + key + " to " + newValue);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static boolean isObjectEqual(Object oldValue, Object newValue) {
        if (oldValue == null) return newValue == null;
        return oldValue.toString().equals(newValue != null ? newValue.toString() : null);
    }

    private static Field getBuildClassField(String key) {
        Field field = fieldCache.get(key);
        if (field != null) {
            return field;
        }
        
        try {
            field = Build.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.class");
        } catch (NoSuchFieldException e) {
            try {
                field = Build.VERSION.class.getDeclaredField(key);
                dlog("Field " + key + " found in Build.VERSION.class");
            } catch (NoSuchFieldException ex) {
                Log.e(TAG, "Field " + key + " not found", ex);
                return null;
            }
        }
        
        field.setAccessible(true);
        fieldCache.put(key, field);
        return field;
    }

    public static boolean shoudlSpoofGames() {
        return SystemProperties.getBoolean(SPOOF_GAMES, false);
    }

    public static boolean hasSystemFeature(String name, int version, boolean hasSystemFeature) {
        if (shouldSpoofPhotos()) {
            if (!isPixelDevice() && featuresPixel.contains(name)) return false;
            return featuresNexus.contains(name);
        }
        return hasSystemFeature;
    }

    private static boolean shouldSpoofPhotos() {
        return sIsPhotos && SystemProperties.getBoolean(SPOOF_PIXEL_GPHOTOS, true);
    }

    private static boolean isPixelDevice() {
        if (isPixelDevice == null) {
            isPixelDevice = SystemProperties.get("ro.soc.manufacturer", "")
                .equalsIgnoreCase("google");
        }
        return isPixelDevice;
    }

    private static boolean isLargeScreen(Context context) {
        if (isLargeScreen == null) {
            WindowManager windowManager = context.getSystemService(WindowManager.class);
            final Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            float smallestWidth = dpiFromPx(Math.min(bounds.width(), bounds.height()),
                    context.getResources().getConfiguration().densityDpi);
            isLargeScreen = smallestWidth >= 600;
        }
        return isLargeScreen;
    }

    private static float dpiFromPx(float size, int densityDpi) {
        float densityRatio = (float) densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (size / densityRatio);
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
