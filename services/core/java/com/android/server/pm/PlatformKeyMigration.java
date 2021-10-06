/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Base64;

import com.android.internal.util.HexDump;

import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PlatformKeyMigration {
    // We spoof tags for SafetyNet, but not the display build ID
    private static final boolean IS_RELEASE = !Build.DISPLAY.contains("test-keys");

    private static final List<String> KEY_NAMES = Arrays.asList(
            "media",
            "networkstack",
            "platform",
            "default", // releasekey/testkey
            "shared"
            /* AVB key doesn't matter here */
    );

    private static final Map<String, String> replacementMap = createReplacementMap();

    private static Map<String, Signature> loadCertsFromProps(String group) {
        Map<String, Signature> certs = new HashMap<>();
        for (String name : KEY_NAMES) {
            String encodedCert = SystemProperties.get("ro.build.certs." + group + "." + name);
            if (encodedCert == null || encodedCert.equals("")) {
                continue;
            }

            Signature cert = new Signature(encodedCert);
            certs.put(name, cert);
        }

        return certs;
    }

    private static String certToPublicKey(Signature cert) throws CertificateException {
        return Base64.encodeToString(cert.getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    private static Map<String, String> buildMappings(Map<String, Signature> from, Map<String, Signature> to)
            throws CertificateException {
        Map<String, String> replacementMap = new HashMap<>();
        for (Map.Entry<String, Signature> fromEntry : from.entrySet()) {
            String name = fromEntry.getKey();
            Signature fromCert = fromEntry.getValue();
            Signature toCert = to.get(name);

            // Forward mapping only, since this is directional
            replacementMap.put(fromCert.toCharsString(), toCert.toCharsString());
            replacementMap.put(certToPublicKey(fromCert), certToPublicKey(toCert));
        }

        return replacementMap;
    }

    private static Map<String, String> createReplacementMap() {
        Map<String, Signature> releaseCerts = loadCertsFromProps("release");
        Map<String, Signature> testCerts = loadCertsFromProps("test");

        try {
            if (IS_RELEASE) {
                // User test -> release migration
                return buildMappings(testCerts, releaseCerts);
            } else {
                // Dev release -> test migration
                return buildMappings(releaseCerts, testCerts);
            }
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    private static String mapValue(String value) {
        String replacement = replacementMap.get(value);
        return replacement != null ? replacement : value;
    }

    public static byte[] mapCert(String cert) {
        return cert != null ? HexDump.hexStringToByteArray(mapValue(cert)) : null;
    }

    public static byte[] mapKey(String key) {
        return key != null ? Base64.decode(mapValue(key), 0) : null;
    }
}
