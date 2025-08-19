/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.crdroid;

import android.app.ActivityThread;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;
import android.util.Xml;

import com.android.internal.R;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";
    private static final int MAX_CERT_COUNT = 3;

    private static IKeyboxProvider instance = null;
    private static String lastKeyboxData = null;

    private KeyProviderManager() {}

    public static synchronized IKeyboxProvider getProvider() {
        Context context = DefaultKeyboxProvider.getApplicationContext();
        if (context == null) {
            return null;
        }

        String keyboxData = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.KEYBOX_DATA);
                
        if (TextUtils.isEmpty(keyboxData)) return null;

        if (isSameData(lastKeyboxData, keyboxData)) {
            return instance;
        }

        instance = new DefaultKeyboxProvider();
        lastKeyboxData = keyboxData;
        return instance;
    }

    public static boolean isKeyboxAvailable() {
        IKeyboxProvider provider = getProvider();
        return provider != null && provider.hasKeybox();
    }

    private static boolean isSameData(String oldData, String newData) {
        return instance != null && TextUtils.equals(oldData, newData);
    }

    private static class DefaultKeyboxProvider implements IKeyboxProvider {
        private final Map<String, String> keyboxData = new HashMap<>();

        private DefaultKeyboxProvider() {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Failed to get application context");
                return;
            }

            if (!loadFromXmlSetting(context)) {
                loadFromConfigArray(context);
            }
        }

        private boolean loadFromXmlSetting(Context ctx) {
            try {
                String json = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.KEYBOX_DATA);
                if (json == null || json.trim().isEmpty()) return false;
                JSONObject obj = new JSONObject(json);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    keyboxData.put(key, obj.getString(key));
                }
                if (!hasKeybox()) {
                    Log.w(TAG, "Incomplete keybox data");
                    return false;
                }
                Log.i(TAG, "Loaded keybox from Secure settings");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load keybox JSON", e);
                return false;
            }
        }

        private void loadFromConfigArray(Context ctx) {
            for (String entry : ctx.getResources().getStringArray(R.array.config_certifiedKeybox)) {
                String[] parts = entry.split(":", 2);
                if (parts.length == 2) {
                    keyboxData.put(parts[0], parts[1]);
                }
            }

            if (!hasKeybox()) {
                Log.w(TAG, "Incomplete keybox provided by overlays");
            }
        }

        public static Context getApplicationContext() {
            try {
                return ActivityThread.currentApplication().getApplicationContext();
            } catch (Exception e) {
                Log.e(TAG, "Error getting application context", e);
                return null;
            }
        }

        @Override
        public boolean hasKeybox() {
            return hasCertificateChain("EC") || hasCertificateChain("RSA");
        }

        private boolean hasCertificateChain(String prefix) {
            if (!keyboxData.containsKey(prefix + ".PRIV")) return false;
            for (int i = 1; i <= MAX_CERT_COUNT; i++) {
                if (keyboxData.containsKey(prefix + ".CERT_" + i)) return true;
            }
            return false;
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            List<String> chain = new ArrayList<>(3);
            for (int i = 1; i <= MAX_CERT_COUNT; i++) {
                String key = prefix + ".CERT_" + i;
                String val = keyboxData.get(key);
                if (val == null) break;
                chain.add(val);
            }
            return chain.toArray(new String[0]);
        }
    }
}
