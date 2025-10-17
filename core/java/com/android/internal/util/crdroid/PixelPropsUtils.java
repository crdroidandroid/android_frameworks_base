/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021-2025 crDroid Android Project
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
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.crdroid.KeyProviderManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public final class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SPOOF_PIXEL_PI = "persist.sys.pixelprops.pi";
    private static final String SPOOF_PIXEL_GMS_CERT_CHAIN = "persist.sys.pixelprops.gmscertchain";
    private static final String SPOOF_PIXEL_GAMES = "persist.sys.pixelprops.games";
    private static final String SPOOF_PIXEL_GPHOTOS = "persist.sys.pixelprops.gphotos";
    private static final String SPOOF_PIXEL_NETFLIX = "persist.sys.pixelprops.netflix";

    private static final Map<String, Object> propsToChangeGeneric = new HashMap<>();
    private static final Map<String, Object> propsToChangePixel10ProXL = new HashMap<>();
    private static final Map<String, Object> propsToChangePixelTablet = new HashMap<>();
    private static final Map<String, Object> propsToChangePixelXL = new HashMap<>();
    private static final Map<String, Object> propsToChangeROG6 = new HashMap<>();
    private static final Map<String, Object> propsToChangeROG6D = new HashMap<>();
    private static final Map<String, Object> propsToChangeLenovoY700 = new HashMap<>();
    private static final Map<String, Object> propsToChangeOP8P = new HashMap<>();
    private static final Map<String, Object> propsToChangeOP9P = new HashMap<>();
    private static final Map<String, Object> propsToChangeMI11TP = new HashMap<>();
    private static final Map<String, Object> propsToChangeMI13P = new HashMap<>();
    private static final Map<String, Object> propsToChangeF5 = new HashMap<>();
    private static final Map<String, Object> propsToChangeBS4 = new HashMap<>();
    private static final Map<String, Object> propsToChangeS24U = new HashMap<>();

    private static final ArraySet<String> PKGS_RECENT_PIXEL = new ArraySet<>(); // Pixel device
    private static final ArraySet<String> PKGS_ROG6 = new ArraySet<>(); // ROG Phone 6
    private static final ArraySet<String> PKGS_ROG6D = new ArraySet<>(); // ROG Phone 6D
    private static final ArraySet<String> PKGS_LENOVOY700 = new ArraySet<>(); // Lenovo Y700
    private static final ArraySet<String> PKGS_OP8P = new ArraySet<>(); // OnePlus 8 Pro
    private static final ArraySet<String> PKGS_OP9P = new ArraySet<>(); //  OnePlus 9 Pro
    private static final ArraySet<String> PKGS_MI11TP = new ArraySet<>(); // Mi 11T Pro
    private static final ArraySet<String> PKGS_MI13P = new ArraySet<>(); // Xiaomi 13 Pro
    private static final ArraySet<String> PKGS_F5 = new ArraySet<>(); // POCO F5
    private static final ArraySet<String> PKGS_BS4 = new ArraySet<>(); // Black Shark 4
    private static final ArraySet<String> PKGS_S24U = new ArraySet<>(); // Samsung S24 Ultra

    static {
        Collections.addAll(PKGS_RECENT_PIXEL,
            "com.google.android.aicore",
            "com.google.android.apps.aiwallpapers",
            "com.google.android.apps.bard",
            "com.google.android.apps.customization.pixel",
            "com.google.android.apps.emojiwallpaper",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.photos",
            "com.google.android.apps.pixel.agent",
            "com.google.android.apps.pixel.creativeassistant",
            "com.google.android.apps.pixel.nowplaying",
            "com.google.android.apps.pixel.psi",
            "com.google.android.apps.pixel.subzero",
            "com.google.android.apps.pixel.support",
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.apps.weather",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.pcs",
            "com.google.android.settings.intelligence",
            "com.google.android.wallpaper.effects",
            "com.google.pixel.livewallpaper",
            "com.netflix.mediaclient",
            "com.nhs.online.nhsonline"
        );

        Collections.addAll(PKGS_ROG6,
            "com.ea.gp.fifamobile",
            "com.gameloft.android.ANMP.GloftA9HM",
            "com.madfingergames.legends",
            "com.pearlabyss.blackdesertm",
            "com.pearlabyss.blackdesertm.gl"
        );

        Collections.addAll(PKGS_ROG6D,
            "com.proxima.dfm"
        );

        Collections.addAll(PKGS_LENOVOY700,
            "com.activision.callofduty.shooter",
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codmvn"
        );

        Collections.addAll(PKGS_OP8P,
            "com.netease.lztgglobal",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildrifttw",
            "com.riotgames.league.wildriftvn",
            "com.riotgames.league.teamfighttactics",
            "com.riotgames.league.teamfighttacticstw",
            "com.riotgames.league.teamfighttacticsvn"
        );

        Collections.addAll(PKGS_OP9P,
            "com.epicgames.fortnite",
            "com.epicgames.portal",
            "com.tencent.lolm"
        );

        Collections.addAll(PKGS_MI11TP,
            "com.ea.gp.apexlegendsmobilefps",
            "com.levelinfinite.hotta.gp",
            "com.supercell.clashofclans",
            "com.vng.mlbbvn"
        );

        Collections.addAll(PKGS_MI13P,
            "com.levelinfinite.sgameGlobal",
            "com.tencent.tmgp.sgame"
        );

        Collections.addAll(PKGS_F5,
            "com.dts.freefiremax",
            "com.dts.freefireth",
            "com.mobile.legends"
        );

        Collections.addAll(PKGS_BS4,
            "com.proximabeta.mf.uamo"
        );

        Collections.addAll(PKGS_S24U,
            "com.blizzard.diablo.immortal",
            "com.pubg.imobile",
            "com.pubg.krmobile",
            "com.rekoo.pubgm",
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.vng.pubgmobile"
        );

        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
        propsToChangePixel10ProXL.put("BRAND", "google");
        propsToChangePixel10ProXL.put("MANUFACTURER", "Google");
        propsToChangePixel10ProXL.put("DEVICE", "mustang");
        propsToChangePixel10ProXL.put("PRODUCT", "mustang");
        propsToChangePixel10ProXL.put("HARDWARE", "mustang");
        propsToChangePixel10ProXL.put("MODEL", "Pixel 10 Pro XL");
        propsToChangePixel10ProXL.put("ID", "CP1A.260405.005");
        propsToChangePixel10ProXL.put("FINGERPRINT", "google/mustang/mustang:16/CP1A.260405.005/15001963:user/release-keys");
        propsToChangePixelTablet.put("BRAND", "google");
        propsToChangePixelTablet.put("MANUFACTURER", "Google");
        propsToChangePixelTablet.put("DEVICE", "tangorpro");
        propsToChangePixelTablet.put("PRODUCT", "tangorpro");
        propsToChangePixelTablet.put("HARDWARE", "tangorpro");
        propsToChangePixelTablet.put("MODEL", "Pixel Tablet");
        propsToChangePixelTablet.put("ID", "CP1A.260405.005");
        propsToChangePixelTablet.put("FINGERPRINT", "google/tangorpro/tangorpro:16/CP1A.260405.005/15001963:user/release-keys");
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("HARDWARE", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("ID", "QP1A.191005.007.A3");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        propsToChangeROG6.put("BRAND", "asus");
        propsToChangeROG6.put("MANUFACTURER", "asus");
        propsToChangeROG6.put("DEVICE", "AI2201");
        propsToChangeROG6.put("MODEL", "ASUS_AI2201");
        propsToChangeROG6D.put("BRAND", "asus");
        propsToChangeROG6D.put("MANUFACTURER", "asus");
        propsToChangeROG6D.put("DEVICE", "AI2203_C");
        propsToChangeROG6D.put("MODEL", "ASUS_AI2203_C");
        propsToChangeLenovoY700.put("MODEL", "Lenovo TB-9707F");
        propsToChangeLenovoY700.put("MANUFACTURER", "lenovo");
        propsToChangeOP8P.put("MODEL", "IN2020");
        propsToChangeOP8P.put("MANUFACTURER", "OnePlus");
        propsToChangeOP9P.put("MODEL", "LE2123");
        propsToChangeOP9P.put("MANUFACTURER", "OnePlus");
        propsToChangeMI11TP.put("MODEL", "2107113SI");
        propsToChangeMI11TP.put("MANUFACTURER", "Xiaomi");
        propsToChangeMI13P.put("BRAND", "Xiaomi");
        propsToChangeMI13P.put("MANUFACTURER", "Xiaomi");
        propsToChangeMI13P.put("MODEL", "2210132C");
        propsToChangeF5.put("MODEL", "23049PCD8G");
        propsToChangeF5.put("MANUFACTURER", "Xiaomi");
        propsToChangeBS4.put("MODEL", "2SM-X706B");
        propsToChangeBS4.put("MANUFACTURER", "blackshark");
        propsToChangeS24U.put("BRAND", "samsung");
        propsToChangeS24U.put("MANUFACTURER", "samsung");
        propsToChangeS24U.put("MODEL", "SM-S928B");
    }

    private static volatile List<String> sCertifiedProps;

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        if (PKGS_RECENT_PIXEL.contains(packageName)) {
            Map<String,Object> propsToChange = null;

            if (packageName.equals("com.google.android.apps.photos")) {
                if (SystemProperties.getBoolean(SPOOF_PIXEL_GPHOTOS, true)) {
                    propsToChange = propsToChangePixelXL;
                }
            } else if (packageName.equals("com.netflix.mediaclient") && 
                        !SystemProperties.getBoolean(SPOOF_PIXEL_NETFLIX, false)) {
                    if (DEBUG) Log.d(TAG, "Netflix spoofing disabled by system prop");
                    return;
            } else if (packageName.equals("com.google.android.gms")) {
                final String processName = Application.getProcessName().toLowerCase();
                if (processName.contains("unstable")) {
                    spoofBuildGms(context);
                    return;
                }
                return;
            } else if (packageName.equals("com.google.android.settings.intelligence")) {
                setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
                return;
            } else {
                if (isDeviceTablet(context.getApplicationContext())) {
                    propsToChange = propsToChangePixelTablet;
                } else {
                    propsToChange = propsToChangePixel10ProXL;
                }
            }

            if (propsToChange != null) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                applyProps(propsToChange);
            }
        } else {
            if (!SystemProperties.getBoolean(SPOOF_PIXEL_GAMES, false))
                return;

            Map<String,Object> propsToChange = null;

            if (PKGS_ROG6.contains(packageName)) {
                propsToChange = propsToChangeROG6;
            } else if (PKGS_ROG6D.contains(packageName)) {
                propsToChange = propsToChangeROG6D;
            } else if (PKGS_LENOVOY700.contains(packageName)) {
                propsToChange = propsToChangeLenovoY700;
            } else if (PKGS_OP8P.contains(packageName)) {
                propsToChange = propsToChangeOP8P;
            } else if (PKGS_OP9P.contains(packageName)) {
                propsToChange = propsToChangeOP9P;
            } else if (PKGS_MI11TP.contains(packageName)) {
                propsToChange = propsToChangeMI11TP;
            } else if (PKGS_MI13P.contains(packageName)) {
                propsToChange = propsToChangeMI13P;
            } else if (PKGS_F5.contains(packageName)) {
                propsToChange = propsToChangeF5;
            } else if (PKGS_BS4.contains(packageName)) {
                propsToChange = propsToChangeBS4;
            } else if (PKGS_S24U.contains(packageName)) {
                propsToChange = propsToChangeS24U;
            }

            if (propsToChange != null) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                applyProps(propsToChange);
            }
        }
    }

    private static void applyProps(Map<String,Object> props) {
      for (Map.Entry<String,Object> e : props.entrySet())
        setPropValue(e.getKey(), e.getValue());
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
        setPropValue(key, String.valueOf(value));
    }

    private static void setPropValue(String key, String value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value);
            Class<?> clazz = Build.class;
            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            // Determine the field type and parse the value accordingly.
            if (field.getType().equals(Integer.TYPE)) {
                field.set(null, Integer.parseInt(value));
            } else if (field.getType().equals(Long.TYPE)) {
                field.set(null, Long.parseLong(value));
            } else {
                field.set(null, value);
            }
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void spoofBuildGms(Context context) {
        if (!SystemProperties.getBoolean(SPOOF_PIXEL_PI, true))
            return;

        List<String> fresh = Arrays.asList(context.getResources()
                 .getStringArray(R.array.config_certifiedBuildProperties));
        sCertifiedProps = new ArrayList<>(fresh);
        if (sCertifiedProps != null && !sCertifiedProps.isEmpty()) {
            applyCertifiedProps();
        }
    }

    private static void applyCertifiedProps() {
        for (String entry : sCertifiedProps) {
            String[] kv = entry.split(":", 2);
            if (kv.length == 2) setPropValue(kv[0], kv[1]);
        }
    }

    private static boolean isCallerSafetyNet() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            final String cn = e.getClassName();
            if (cn != null && (cn.contains("DroidGuard") || cn.contains("droidguard"))) return true;
        }
        return false;
    }

    public static void onEngineGetCertificateChain() {
        if (!SystemProperties.getBoolean(SPOOF_PIXEL_PI, true))
            return;
        // If a keybox is found, don't block key attestation
        if (SystemProperties.getBoolean(SPOOF_PIXEL_GMS_CERT_CHAIN, false)
                && KeyProviderManager.isKeyboxAvailable()) {
            Log.i(TAG, "Key attestation blocking is disabled because a keybox is defined to spoof");
            return;
        }
        // Check stack for SafetyNet
        if (isCallerSafetyNet()) {
            Log.i(TAG, "Blocked key attestation");
            throw new UnsupportedOperationException();
        }
    }
}
