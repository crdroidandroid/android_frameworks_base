/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021-2024 crDroid Android Project
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

package com.android.internal.util.crdroid;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 */
public final class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final String DEVICE = "ro.product.device";
    private static final boolean DEBUG = false;

    private static final String SPOOF_PIXEL_PI = "persist.sys.pixelprops.pi";
    private static final String SPOOF_PIXEL_GAMES = "persist.sys.pixelprops.games";
    private static final String SPOOF_PIXEL_GPHOTOS = "persist.sys.pixelprops.gphotos";
    private static final String SPOOF_PIXEL_NETFLIX = "persist.sys.pixelprops.netflix";

    private static final Map<String, Object> propsToChangeGeneric;
    private static final Map<String, Object> propsToChangePixel5a;
    private static final Map<String, Object> propsToChangePixel9ProXL;
    private static final Map<String, Object> propsToChangePixelTablet;
    private static final Map<String, Object> propsToChangePixelXL;
    private static final Map<String, Object> propsToChangeROG6;
    private static final Map<String, Object> propsToChangeLenovoY700;
    private static final Map<String, Object> propsToChangeOP8P;
    private static final Map<String, Object> propsToChangeOP9P;
    private static final Map<String, Object> propsToChangeMI11TP;
    private static final Map<String, Object> propsToChangeMI13P;
    private static final Map<String, Object> propsToChangeF5;
    private static final Map<String, Object> propsToChangeBS4;
    private static final Map<String, ArrayList<String>> propsToKeep;

    private static final String[] pTensorCodenames = {
            "comet",
            "komodo",
            "caiman",
            "tokay",
            "akita",
            "husky",
            "shiba",
            "felix",
            "tangorpro",
            "lynx",
            "cheetah",
            "panther",
            "bluejay",
            "oriole",
            "raven"
    };

    // Packages to Spoof as Pixel 8 Pro
    private static final String[] packagesToChangePixel9ProXL = {
            "com.google.android.apps.aiwallpapers",
            "com.google.android.apps.bard",
            "com.google.android.apps.customization.pixel",
            "com.google.android.apps.emojiwallpaper",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.googlequicksearchbox",
            "com.google.android.wallpaper.effects",
            "com.google.pixel.livewallpaper"
    };

    // Packages to Spoof as Pixel 5a
    private static final String[] packagesToChangePixel5a = {
            "com.android.chrome",
            "com.android.vending",
            "com.breel.wallpapers20",
            "com.nhs.online.nhsonline",
            "com.netflix.mediaclient"
    };

    // Packages to Keep with original device
    private static final String[] packagesToKeep = {
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite",
            "com.google.android.apps.dreamlinerupdater",
            "com.google.android.apps.dreamliner",
            "com.google.android.apps.miphone.aiai.AiaiApplication",
            "com.google.android.apps.motionsense.bridge",
            "com.google.android.apps.pixelmigrate",
            "com.google.android.apps.recorder",
            "com.google.android.apps.restore",
            "com.google.android.apps.subscriptions.red",
            "com.google.android.apps.tachyon",
            "com.google.android.apps.tips",
            "com.google.android.apps.tycho",
            "com.google.android.apps.wearables.maestro.companion",
            "com.google.android.apps.youtube.kids",
            "com.google.android.apps.youtube.music",
            "com.google.android.as",
            "com.google.android.backup",
            "com.google.android.backuptransport",
            "com.google.android.dialer",
            "com.google.android.euicc",
            "com.google.android.setupwizard",
            "com.google.android.youtube",
            "com.google.ar.core",
            "com.google.intelligence.sense",
            "com.google.oslo"
    };

    // Packages to Spoof as ROG Phone 6
    private static final String[] packagesToChangeROG6 = {
            "com.ea.gp.fifamobile",
            "com.gameloft.android.ANMP.GloftA9HM",
            "com.madfingergames.legends",
            "com.pearlabyss.blackdesertm",
            "com.pearlabyss.blackdesertm.gl"
    };

    // Packages to Spoof as Lenovo Y700
    private static final String[] packagesToChangeLenovoY700 = {
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codmvn"
    };

    // Packages to Spoof as OnePlus 8 Pro
    private static final String[] packagesToChangeOP8P = {
            "com.netease.lztgglobal",
            "com.pubg.imobile",
            "com.pubg.krmobile",
            "com.rekoo.pubgm",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildrifttw",
            "com.riotgames.league.wildriftvn",
            "com.riotgames.league.teamfighttactics",
            "com.riotgames.league.teamfighttacticstw",
            "com.riotgames.league.teamfighttacticsvn",
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.vng.pubgmobile"
    };

    // Packages to Spoof as OnePlus 9 Pro
    private static final String[] packagesToChangeOP9P = {
            "com.epicgames.fortnite",
            "com.epicgames.portal",
            "com.tencent.lolm"
    };

    // Packages to Spoof as Mi 11T Pro
    private static final String[] packagesToChangeMI11TP = {
            "com.ea.gp.apexlegendsmobilefps",
            "com.levelinfinite.hotta.gp",
            "com.supercell.clashofclans",
            "com.vng.mlbbvn"
    };

    // Packages to Spoof as Xiaomi 13 Pro
    private static final String[] packagesToChangeMI13P = {
            "com.levelinfinite.sgameGlobal",
            "com.tencent.tmgp.sgame"
    };

    // Packages to Spoof as POCO F5
    private static final String[] packagesToChangeF5 = {
            "com.dts.freefiremax",
            "com.dts.freefireth",
            "com.mobile.legends"
    };

    // Packages to Spoof as Black Shark 4
    private static final String[] packagesToChangeBS4 = {
            "com.proximabeta.mf.uamo"
    };

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put("com.google.android.settings.intelligence", new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
        propsToChangePixel9ProXL = new HashMap<>();
        propsToChangePixel9ProXL.put("BRAND", "google");
        propsToChangePixel9ProXL.put("MANUFACTURER", "Google");
        propsToChangePixel9ProXL.put("DEVICE", "komodo");
        propsToChangePixel9ProXL.put("PRODUCT", "komodo");
        propsToChangePixel9ProXL.put("HARDWARE", "komodo");
        propsToChangePixel9ProXL.put("MODEL", "Pixel 9 Pro XL");
        propsToChangePixel9ProXL.put("ID", "AD1A.240905.004");
        propsToChangePixel9ProXL.put("FINGERPRINT", "google/komodo/komodo:14/AD1A.240905.004/12196292:user/release-keys");
        propsToChangePixelTablet = new HashMap<>();
        propsToChangePixelTablet.put("BRAND", "google");
        propsToChangePixelTablet.put("MANUFACTURER", "Google");
        propsToChangePixelTablet.put("DEVICE", "tangorpro");
        propsToChangePixelTablet.put("PRODUCT", "tangorpro");
        propsToChangePixelTablet.put("HARDWARE", "tangorpro");
        propsToChangePixelTablet.put("MODEL", "Pixel Tablet");
        propsToChangePixelTablet.put("ID", "AP2A.240905.003");
        propsToChangePixelTablet.put("FINGERPRINT", "google/tangorpro/tangorpro:14/AP2A.240905.003/12231197:user/release-keys");
        propsToChangePixel5a = new HashMap<>();
        propsToChangePixel5a.put("BRAND", "google");
        propsToChangePixel5a.put("MANUFACTURER", "Google");
        propsToChangePixel5a.put("DEVICE", "barbet");
        propsToChangePixel5a.put("PRODUCT", "barbet");
        propsToChangePixel5a.put("HARDWARE", "barbet");
        propsToChangePixel5a.put("MODEL", "Pixel 5a");
        propsToChangePixel5a.put("ID", "AP2A.240805.005");
        propsToChangePixel5a.put("FINGERPRINT", "google/barbet/barbet:14/AP2A.240805.005/12025142:user/release-keys");
        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("HARDWARE", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("ID", "QP1A.191005.007.A3");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        propsToChangeROG6 = new HashMap<>();
        propsToChangeROG6.put("BRAND", "asus");
        propsToChangeROG6.put("MANUFACTURER", "asus");
        propsToChangeROG6.put("DEVICE", "AI2201");
        propsToChangeROG6.put("MODEL", "ASUS_AI2201");
        propsToChangeLenovoY700 = new HashMap<>();
        propsToChangeLenovoY700.put("MODEL", "Lenovo TB-9707F");
        propsToChangeLenovoY700.put("MANUFACTURER", "lenovo");
        propsToChangeOP8P = new HashMap<>();
        propsToChangeOP8P.put("MODEL", "IN2020");
        propsToChangeOP8P.put("MANUFACTURER", "OnePlus");
        propsToChangeOP9P = new HashMap<>();
        propsToChangeOP9P.put("MODEL", "LE2123");
        propsToChangeOP9P.put("MANUFACTURER", "OnePlus");
        propsToChangeMI11TP = new HashMap<>();
        propsToChangeMI11TP.put("MODEL", "2107113SI");
        propsToChangeMI11TP.put("MANUFACTURER", "Xiaomi");
        propsToChangeMI13P = new HashMap<>();
        propsToChangeMI13P.put("BRAND", "Xiaomi");
        propsToChangeMI13P.put("MANUFACTURER", "Xiaomi");
        propsToChangeMI13P.put("MODEL", "2210132C");
        propsToChangeF5 = new HashMap<>();
        propsToChangeF5.put("MODEL", "23049PCD8G");
        propsToChangeF5.put("MANUFACTURER", "Xiaomi");
        propsToChangeBS4 = new HashMap<>();
        propsToChangeBS4.put("MODEL", "2SM-X706B");
        propsToChangeBS4.put("MANUFACTURER", "blackshark");
    }

    public static void setProps(Context context) {
        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        final String packageName = context.getPackageName();
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        if (packageName.startsWith("com.google.")
                || packageName.startsWith("com.samsung.")
                || Arrays.asList(packagesToChangePixel9ProXL).contains(packageName)
                || Arrays.asList(packagesToChangePixel5a).contains(packageName)) {

            if (Arrays.asList(packagesToKeep).contains(packageName) ||
                    packageName.startsWith("com.google.android.GoogleCamera")) {
                return;
            }

            Map<String, Object> propsToChange = new HashMap<>();

            if (packageName.equals("com.google.android.apps.photos")) {
                if (SystemProperties.getBoolean(SPOOF_PIXEL_GPHOTOS, true)) {
                    propsToChange.putAll(propsToChangePixelXL);
                }
            } else if (packageName.equals("com.netflix.mediaclient") && 
                        !SystemProperties.getBoolean(SPOOF_PIXEL_NETFLIX, false)) {
                    if (DEBUG) Log.d(TAG, "Netflix spoofing disabled by system prop");
                    return;
            } else if (packageName.equals("com.google.android.gms")) {
                setPropValue("TIME", System.currentTimeMillis());
                final String processName = Application.getProcessName().toLowerCase();
                if (processName.contains("unstable")) {
                    spoofBuildGms();
                    return;
                }
                boolean isTensorDevice = Arrays.asList(pTensorCodenames).contains(SystemProperties.get(DEVICE));
                if (!isTensorDevice && (processName.contains("gservice")
                        || processName.contains("learning")
                        || processName.contains("persistent"))) {
                    propsToChange.putAll(propsToChangePixel5a);
                } else if (isDeviceTablet(context.getApplicationContext())) {
                    propsToChange.putAll(propsToChangePixelTablet);
                } else {
                    propsToChange.putAll(propsToChangePixel9ProXL);
                }
            } else if (Arrays.asList(packagesToChangePixel9ProXL).contains(packageName)) {
                if (isDeviceTablet(context.getApplicationContext())) {
                    propsToChange.putAll(propsToChangePixelTablet);
                } else {
                    propsToChange.putAll(propsToChangePixel9ProXL);
                }
            } else {
                propsToChange.putAll(propsToChangePixel5a);
            }

            if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                if (propsToKeep.containsKey(packageName) && propsToKeep.get(packageName).contains(key)) {
                    if (DEBUG) Log.d(TAG, "Not defining " + key + " prop for: " + packageName);
                    continue;
                }
                if (DEBUG) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
                setPropValue(key, value);
            }
            // Set proper indexing fingerprint
            if (packageName.equals("com.google.android.settings.intelligence")) {
                setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
            }
        } else {

            if (!SystemProperties.getBoolean(SPOOF_PIXEL_GAMES, false))
                return;

            if (Arrays.asList(packagesToChangeROG6).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeROG6.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeLenovoY700).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeLenovoY700.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeOP8P).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeOP8P.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeOP9P).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeOP9P.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeMI11TP).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeMI11TP.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeMI13P).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeMI13P.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeF5).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeF5.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeBS4).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeBS4.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            }
        }
    }

    private static boolean isDeviceTablet(Context context) {
        if (context == null) {
            return false;
        }
        Configuration config = context.getResources().getConfiguration();
        boolean isTablet = (config.smallestScreenWidthDp >= 600);
        return isTablet;
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value.toString());
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setVersionField(String key, Object value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value.toString());
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setVersionFieldString(String key, String value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value);
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setVersionFieldInt(String key, int value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value);
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void spoofBuildGms() {
        if (!SystemProperties.getBoolean(SPOOF_PIXEL_PI, true))
            return;
        // Alter build parameters to avoid hardware attestation enforcement
        setPropValue("MANUFACTURER", "Google");
        setPropValue("MODEL", "Pixel 9 Pro XL");
        setPropValue("FINGERPRINT", "google/komodo_beta/komodo:15/AP41.240726.010/12266677:user/release-keys");
        setPropValue("BRAND", "google");
        setPropValue("PRODUCT", "komodo_beta");
        setPropValue("DEVICE", "komodo");
        setVersionFieldString("RELEASE", "15");
        setPropValue("ID", "AP41.240726.010");
        setVersionFieldString("INCREMENTAL", "12266677");
        setPropValue("TYPE", "user");
        setPropValue("TAGS", "release-keys");
        setVersionFieldString("SECURITY_PATCH", "2024-08-05");
        setVersionFieldInt("DEVICE_INITIAL_SDK_INT", 32);
    }
}
