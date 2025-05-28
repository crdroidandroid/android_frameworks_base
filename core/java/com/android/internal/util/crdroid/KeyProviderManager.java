/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.crdroid;

import android.app.ActivityThread;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";

    private KeyProviderManager() {}

    public static IKeyboxProvider getProvider() {
        return new DefaultKeyboxProvider();
    }

    public static boolean isKeyboxAvailable() {
        return getProvider().hasKeybox();
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
                String xml = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.KEYBOX_DATA);
                if (xml == null || xml.trim().isEmpty()) return false;

                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(xml));

                String currentAlg = null;
                int certCount = 0;
                boolean numberOfKeyboxesChecked = false;

                for (int ev = p.next(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        switch (tag) {
                            case "NumberOfKeyboxes":
                                p.next();
                                numberOfKeyboxesChecked = true;
                                try {
                                    int count = Integer.parseInt(p.getText().trim());
                                    if (count != 1) {
                                        Log.w(TAG, "Invalid NumberOfKeyboxes: " + count);
                                        return false;
                                    }
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "Failed to parse NumberOfKeyboxes", e);
                                    return false;
                                }
                                break;

                            case "Key":
                                currentAlg = p.getAttributeValue(null, "algorithm");
                                if ("ecdsa".equalsIgnoreCase(currentAlg)) currentAlg = "EC";
                                else if ("rsa".equalsIgnoreCase(currentAlg)) currentAlg = "RSA";
                                else currentAlg = null;
                                certCount = 0;
                                break;

                            case "PrivateKey": {
                                String format = p.getAttributeValue(null, "format");
                                if (!"pem".equalsIgnoreCase(format)) {
                                    Log.w(TAG, "Unsupported PrivateKey format: " + format);
                                    return false;
                                }
                                p.next();
                                if (currentAlg != null) {
                                    keyboxData.put(currentAlg + ".PRIV", p.getText().trim());
                                }
                                break;
                            }

                            case "Certificate": {
                                String format = p.getAttributeValue(null, "format");
                                if (!"pem".equalsIgnoreCase(format)) {
                                    Log.w(TAG, "Unsupported Certificate format: " + format);
                                    return false;
                                }
                                if (currentAlg != null && certCount < 3) {
                                    p.next();
                                    certCount++;
                                    keyboxData.put(currentAlg + ".CERT_" + certCount, p.getText().trim());
                                }
                                break;
                            }
                        }
                    }
                }

                if (!numberOfKeyboxesChecked) {
                    Log.w(TAG, "Missing <NumberOfKeyboxes> in keybox XML");
                    return false;
                }

                if (!hasKeybox()) {
                    Log.w(TAG, "Failed to load keybox from XML setting");
                    return false;
                }

                Log.i(TAG, "Loaded keybox from XML setting");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "XML keybox load failed", e);
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

        private static Context getApplicationContext() {
            try {
                return ActivityThread.currentApplication().getApplicationContext();
            } catch (Exception e) {
                Log.e(TAG, "Error getting application context", e);
                return null;
            }
        }

        @Override
        public boolean hasKeybox() {
            return Arrays.asList("EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")
                    .stream()
                    .allMatch(keyboxData::containsKey);
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
            return new String[]{
                    keyboxData.get(prefix + ".CERT_1"),
                    keyboxData.get(prefix + ".CERT_2"),
                    keyboxData.get(prefix + ".CERT_3")
            };
        }
    }
}
