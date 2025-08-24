
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
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Set;

public class PropsHooksUtils {

    private static final String TAG = PropsHooksUtils.class.getSimpleName();
    private static final String SPOOF_PIXEL_GMS = "persist.sys.pixelprops.gms";
    private static final String SPOOF_PIXEL_GPHOTOS = "persist.sys.pixelprops.gphotos";
    public static final String SPOOF_GAMES = "persist.sys.gameprops.enable";
    private static final String FINSKY = "com.android.vending";
    private static volatile boolean sIsPhotos;

    private static final Map<String, Object> propsPixelXL = new ArrayMap<>();
    private static final Map<String, Object> propsBS4 = createMap("2SM-X706B", "blackshark");
    private static final Map<String, Object> propsMI11TP = createMap("2107113SI", "Xiaomi");
    private static final Map<String, Object> propsMI13P = createMap("2210132C", "Xiaomi");
    private static final Map<String, Object> propsOP8P = createMap("IN2020", "OnePlus");
    private static final Map<String, Object> propsOP9P = createMap("LE2101", "OnePlus");
    private static final Map<String, Object> propsF5 = createMap("23049PCD8G", "Xiaomi");
    private static final Map<String, Object> propsROG6 = createMap("ASUS_AI2201", "asus");
    private static final Map<String, Object> propsROG8P = createMap("ASUS_AI2401_A", "asus");
    private static final Map<String, Object> propsLenovoY700 = createMap("Lenovo TB-9707F", "Lenovo");
    private static final Map<String, Object> propsS24Ultra = createMap("SM-S928B", "samsung");
    private static final Map<String, Object> propsS9Tab = createMap("SM-X916B", "samsung");

    private static final ArrayMap<String, Map<String, Object>> packagePropsMap = new ArrayMap<>();
    private static final ArrayMap<String, ArrayMap<String, Map<String, Object>>> pubgPropsMap = new ArrayMap<>();
    private static final ArraySet<String> packagesToSpoof = new ArraySet<>();
    private static final ArraySet<String> pubgPackages = new ArraySet<>();
    private static final ArraySet<String> featuresPixel = new ArraySet<>();
    private static final ArraySet<String> featuresNexus = new ArraySet<>();
    private static final ArrayMap<String, String> commonKeys = new ArrayMap<>();

    static {
        commonKeys.put("MF", "MANUFACTURER");
        commonKeys.put("MD", "MODEL");
        commonKeys.put("FP", "FINGERPRINT");
        commonKeys.put("PR", "PRODUCT");
        commonKeys.put("DV", "DEVICE");
        commonKeys.put("SP", "SECURITY_PATCH");
        commonKeys.put("ISDK", "DEVICE_INITIAL_SDK_INT");

        featuresPixel.add("PIXEL_2017_PRELOAD");
        featuresPixel.add("PIXEL_2018_PRELOAD");
        featuresPixel.add("PIXEL_2019_MIDYEAR_PRELOAD");
        featuresPixel.add("PIXEL_2019_PRELOAD");
        featuresPixel.add("PIXEL_2020_EXPERIENCE");
        featuresPixel.add("PIXEL_2020_MIDYEAR_EXPERIENCE");
        featuresPixel.add("PIXEL_EXPERIENCE");

        featuresNexus.add("com.google.android.apps.photos.NEXUS_PRELOAD");
        featuresNexus.add("com.google.android.apps.photos.nexus_preload");
        featuresNexus.add("com.google.android.feature.PIXEL_EXPERIENCE");
        featuresNexus.add("com.google.android.feature.GOOGLE_BUILD");
        featuresNexus.add("com.google.android.feature.GOOGLE_EXPERIENCE");

        propsPixelXL.put("BRAND", "google");
        propsPixelXL.put("MANUFACTURER", "Google");
        propsPixelXL.put("DEVICE", "marlin");
        propsPixelXL.put("PRODUCT", "marlin");
        propsPixelXL.put("HARDWARE", "marlin");
        propsPixelXL.put("ID", "QP1A.191005.007.A3");
        propsPixelXL.put("MODEL", "Pixel XL");
        propsPixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");

        addToMap(Set.of("com.proximabeta.mf.uamo"), propsBS4);

        addToMap(Set.of(
            "com.levelinfinite.hotta.gp",
            "com.supercell.brawlstars",
            "com.supercell.clashofclans",
            "com.vng.mlbbvn"
        ), propsMI11TP);

        addToMap(Set.of("com.levelinfinite.sgameGlobal","com.tencent.tmgp.sgame"), propsMI13P);

        addToMap(Set.of(
            "com.netease.lztgglobal",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildrifttw",
            "com.riotgames.league.wildriftvn",
            "com.riotgames.league.teamfighttactics",
            "com.riotgames.league.teamfighttacticstw",
            "com.riotgames.league.teamfighttacticsvn"
        ), propsOP8P);

        addToMap(Set.of(
            "com.epicgames.fortnite",
            "com.epicgames.portal",
            "com.tencent.lolm"
        ), propsOP9P);

        addToMap(Set.of("com.dts.freefiremax","com.dts.freefireth"), propsF5);

        addToMap(Set.of(
            "com.ea.gp.fifamobile",
            "com.gameloft.android.ANMP.GloftA9HM",
            "com.madfingergames.legends",
            "com.pearlabyss.blackdesertm",
            "com.pearlabyss.blackdesertm.gl"
        ), propsROG6);

        addToMap(Set.of("com.ea.gp.apexlegendsmobilefps","com.mobile.legends"), propsROG8P);

        addToMap(Set.of(
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codmvn"
        ), propsLenovoY700);

        pubgPackages.addAll(Set.of(
            "com.pubg.imobile",
            "com.pubg.krmobile",
            "com.rekoo.pubgm",
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.vng.pubgmobile"
        ));

        for (String pkg : pubgPackages) {
            ArrayMap<String, Map<String, Object>> props = new ArrayMap<>();
            props.put("phone", propsS24Ultra);
            props.put("tablet", propsS9Tab);
            pubgPropsMap.put(pkg, props);
        }

        packagesToSpoof.addAll(packagePropsMap.keySet());
        packagesToSpoof.add("com.google.android.gms");
        packagesToSpoof.add(FINSKY);
        packagesToSpoof.add("com.google.android.apps.photos");
        packagesToSpoof.add("com.google.android.settings.intelligence");
        packagesToSpoof.addAll(pubgPackages);
    }

    private static void addToMap(Set<String> packages, Map<String, Object> props) {
        for (String pkg : packages) packagePropsMap.put(pkg, props);
    }

    private static Map<String, Object> createMap(String model, String manufacturer) {
        Map<String, Object> map = new ArrayMap<>();
        map.put("MODEL", model);
        map.put("MANUFACTURER", manufacturer);
        return map;
    }

    public static void setProps(Context context) {
        if (context == null) return;

        String packageName = context.getPackageName();
        if (TextUtils.isEmpty(packageName)) return;
        if (!packagesToSpoof.contains(packageName)) return;

        final String processName = Application.getProcessName();
        if (TextUtils.isEmpty(processName)) return;

        boolean isTablet = isLargeScreen(context);

        if (pubgPropsMap.containsKey(packageName)) {
            Map<String, Object> propsToApply = isTablet
                    ? pubgPropsMap.get(packageName).get("tablet")
                    : pubgPropsMap.get(packageName).get("phone");
            if (propsToApply != null) packagePropsMap.put(packageName, propsToApply);
        }

        if (shoudlSpoofGames()) {
            Map<String, Object> props = packagePropsMap.get(packageName);
            if (props != null) {
                for (Map.Entry<String, Object> prop : props.entrySet()) {
                    setPropValue(packageName, prop.getKey(), prop.getValue());
                }
            }
        }

        sIsPhotos = packageName.equals("com.google.android.apps.photos");
        if (shouldSpoofPhotos()) {
            for (Map.Entry<String, Object> entry : propsPixelXL.entrySet()) {
                setPropValue(packageName, entry.getKey(), entry.getValue());
            }
        }

        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPropValue(packageName, "FINGERPRINT", "eng.nobody." +
                    new SimpleDateFormat("yyyyMMdd.HHmmss").format(new Date()));
        }

        if (shouldSpoofGMS(packageName, processName)) {
            spoofBuildGms(context, packageName);
        }
    }

    private static void setPropValue(String packageName, String key, Object newValue) {
        try {
            Field field = getBuildClassField(key);
            if (field == null) return;

            if (field.getType() == int.class) {
                field.setInt(null, newValue instanceof Integer ? (Integer)newValue : Integer.parseInt(newValue.toString()));
            } else if (field.getType() == long.class) {
                field.setLong(null, newValue instanceof Long ? (Long)newValue : Long.parseLong(newValue.toString()));
            } else {
                field.set(null, newValue.toString());
            }
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set prop " + key + " for package " + packageName, e);
        }
    }

    private static Field getBuildClassField(String key) {
        try {
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e1) {
            try {
                Field field = Build.VERSION.class.getDeclaredField(key);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e2) {
                Log.e(TAG, "Field " + key + " not found", e2);
                return null;
            }
        }
    }

    private static boolean shoudlSpoofGames() {
        return SystemProperties.getBoolean(SPOOF_GAMES, false);
    }

    private static boolean shouldSpoofPhotos() {
        return sIsPhotos && SystemProperties.getBoolean(SPOOF_PIXEL_GPHOTOS, true);
    }

    private static boolean shouldSpoofGMS(String packageName, String processName) {
        final boolean sIsGms = packageName.equals("com.google.android.gms") 
                && processName.toLowerCase().contains("unstable");
        final boolean sIsFinsky = packageName.equals(FINSKY);
        return (sIsGms || sIsFinsky) && SystemProperties.getBoolean(SPOOF_PIXEL_GMS, true);
    }

    private static void spoofBuildGms(Context context, String pkg) {
        try {
            String keys = SystemProperties.get("persist.sys.propshooks_keys", "");
            if (TextUtils.isEmpty(keys)) return;
            ArrayMap<String, Object> propsToApply = new ArrayMap<>();
            for (String key : keys.split(",")) {
                String value = SystemProperties.get("persist.sys.propshooks_" + key, null);
                if (!TextUtils.isEmpty(value)) {
                    String fullKey = commonKeys.containsKey(key) ? commonKeys.get(key) : key;
                    propsToApply.put(fullKey, value);
                }
            }
            for (Map.Entry<String, Object> entry : propsToApply.entrySet()) {
                setPropValue(pkg, entry.getKey(), entry.getValue());
            }
            packagePropsMap.put(pkg, propsToApply);
        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof Build props for GMS/Finsky", e);
        }
    }

    public static boolean hasSystemFeature(String name, int version, boolean hasSystemFeature) {
        if (shouldSpoofPhotos()) {
            if (!isPixelDevice() && featuresPixel.contains(name)) return false;
            return featuresNexus.contains(name);
        }
        return hasSystemFeature;
    }

    private static boolean isPixelDevice() {
        return SystemProperties.get("ro.soc.manufacturer", "").equalsIgnoreCase("google");
    }

    private static boolean isLargeScreen(Context context) {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
        float smallestWidth = dpiFromPx(Math.min(bounds.width(), bounds.height()),
                context.getResources().getConfiguration().densityDpi);
        return smallestWidth >= 600;
    }

    private static float dpiFromPx(float size, int densityDpi) {
        return size / ((float)densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }
}
