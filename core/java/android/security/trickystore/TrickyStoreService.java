/*
 * Copyright (C) 2025-2026 AxionOS
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

package android.security.trickystore;

import android.app.ActivityManager;
import android.os.RemoteException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.JsonReader;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class TrickyStoreService {
    private static final String TAG = "TrickyStoreService";

    private static TrickyStoreService sInstance;

    private final Set<String> mHackPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> mGeneratePackages = ConcurrentHashMap.newKeySet();
    private final Map<String, Mode> mPackageModes = new ConcurrentHashMap<>();

    private volatile Boolean mTeeBroken = null;
    private volatile CustomPatchLevel mCustomPatchLevel = null;
    private volatile String mLastKeyboxFingerprint = null;

    private final KeyBoxManager mKeyBoxManager;

    /** @hide */
    public enum Mode {
        AUTO, LEAF_HACK, GENERATE
    }

    /** @hide */
    public static class CustomPatchLevel {
        public final String system;
        public final String vendor;
        public final String boot;
        public final String all;

        public CustomPatchLevel(String system, String vendor, String boot, String all) {
            this.system = system;
            this.vendor = vendor;
            this.boot = boot;
            this.all = all;
        }
    }

    private TrickyStoreService() {
        mKeyBoxManager = new KeyBoxManager();
    }

    public static synchronized TrickyStoreService getInstance() {
        if (sInstance == null) {
            sInstance = new TrickyStoreService();
            sInstance.initialize();
        }
        return sInstance;
    }

    public void initialize() {
        refreshTargets();
        refreshKeyBox();
        refreshPatchLevel();
        Log.i(TAG, "TrickyStoreService initialized");
    }

    private String fetchFromAms(Fetcher fetcher) {
        try {
            return fetcher.fetch();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to fetch trickystore config from system_server", e);
            return null;
        }
    }

    private interface Fetcher {
        String fetch() throws RemoteException;
    }

    public void refreshTargets() {
        String content = fetchFromAms(() -> ActivityManager.getService().getSpoofTrickyStoreTarget());
        mHackPackages.clear();
        mGeneratePackages.clear();
        mPackageModes.clear();

        if (content == null || content.isEmpty()) {
            return;
        }

        String trimmed = content.trim();
        try {
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                parseTargetsJson(trimmed);
            } else {
                parseTargetsText(trimmed);
            }
            Log.i(TAG, "Updated target packages: hack=" + mHackPackages +
                  ", generate=" + mGeneratePackages + ", modes=" + mPackageModes);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse target packages", e);
        }
    }

    private void parseTargetsText(String content) {
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.endsWith("!")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                mGeneratePackages.add(pkg);
                mPackageModes.put(pkg, Mode.GENERATE);
            } else if (line.endsWith("?")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                mHackPackages.add(pkg);
                mPackageModes.put(pkg, Mode.LEAF_HACK);
            } else {
                mPackageModes.put(line, Mode.AUTO);
            }
        }
    }

    private void parseTargetsJson(String content) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginArray();
            while (reader.hasNext()) {
                reader.beginObject();
                String pkg = null;
                String modeStr = "AUTO";
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if ("package".equals(key)) {
                        pkg = reader.nextString();
                    } else if ("mode".equals(key)) {
                        modeStr = reader.nextString();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (pkg == null) continue;
                Mode mode;
                try {
                    mode = Mode.valueOf(modeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    mode = Mode.AUTO;
                }
                mPackageModes.put(pkg, mode);
                if (mode == Mode.LEAF_HACK) mHackPackages.add(pkg);
                if (mode == Mode.GENERATE) mGeneratePackages.add(pkg);
            }
            reader.endArray();
        }
    }

    public void refreshKeyBox() {
        String raw = fetchFromAms(() -> ActivityManager.getService().getSpoofTrickyStoreKeyBox());
        if (raw == null || raw.isEmpty()) {
            mKeyBoxManager.clear();
            mLastKeyboxFingerprint = null;
            return;
        }
        String fingerprint = Integer.toHexString(raw.hashCode()) + ":" + raw.length();
        if (fingerprint.equals(mLastKeyboxFingerprint)) {
            return;
        }
        String xml = decodeKeybox(raw);
        if (xml == null) {
            Log.e(TAG, "Keybox payload not recognised as XML or base64-encoded XML");
            return;
        }
        try {
            mKeyBoxManager.parseKeybox(xml);
            mLastKeyboxFingerprint = fingerprint;
            Log.i(TAG, "Keybox updated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to update keybox", e);
        }
    }

    private String decodeKeybox(String payload) {
        String trimmed = payload.trim();
        if (trimmed.startsWith("<")) {
            return trimmed;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            String asXml = new String(decoded, StandardCharsets.UTF_8).trim();
            if (asXml.startsWith("<")) {
                return asXml;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public void refreshPatchLevel() {
        String content = fetchFromAms(() -> ActivityManager.getService().getSpoofTrickyStorePatch());
        if (content == null || content.isEmpty()) {
            mCustomPatchLevel = null;
            return;
        }

        try {
            String trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                parsePatchJson(trimmed);
            } else {
                parsePatchText(trimmed);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse patch level", e);
        }
    }

    private void parsePatchText(String content) {
        StringBuilder filtered = new StringBuilder();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                filtered.append(line).append("\n");
            }
        }

        String lines = filtered.toString().trim();
        if (lines.isEmpty()) {
            mCustomPatchLevel = null;
            return;
        }

        String[] parts = lines.split("\n");
        if (parts.length == 1 && !parts[0].contains("=")) {
            mCustomPatchLevel = new CustomPatchLevel(parts[0], parts[0], parts[0], parts[0]);
            return;
        }

        String system = null, vendor = null, boot = null, all = null;
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                String key = part.substring(0, idx).trim().toLowerCase();
                String value = part.substring(idx + 1).trim();
                switch (key) {
                    case "system": system = value; break;
                    case "vendor": vendor = value; break;
                    case "boot": boot = value; break;
                    case "all": all = value; break;
                }
            }
        }
        mCustomPatchLevel = new CustomPatchLevel(
            system != null ? system : all,
            vendor != null ? vendor : all,
            boot != null ? boot : all,
            all
        );
    }

    private void parsePatchJson(String content) throws IOException {
        String system = null, vendor = null, boot = null, all = null;
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                switch (key) {
                    case "system": system = reader.nextString(); break;
                    case "vendor": vendor = reader.nextString(); break;
                    case "boot": boot = reader.nextString(); break;
                    case "all": all = reader.nextString(); break;
                    default: reader.skipValue(); break;
                }
            }
            reader.endObject();
        }
        mCustomPatchLevel = new CustomPatchLevel(
            system != null ? system : all,
            vendor != null ? vendor : all,
            boot != null ? boot : all,
            all
        );
    }

    private void ensureTeeStatus() {
        if (mTeeBroken == null) {
            synchronized (this) {
                if (mTeeBroken == null) {
                    mTeeBroken = checkTeeBroken();
                    if (mTeeBroken) {
                        AttestationUtils.setTeeBroken(true);
                    }
                }
            }
        }
    }

    private boolean checkTeeBroken() {
        try {
            String alias = "TrickyStoreTeeCheck";
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[16]);

            kpg.initialize(builder.build());
            kpg.generateKeyPair();

            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(alias);

            Log.i(TAG, "TEE verification successful");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "TEE verification failed, TEE is broken", e);
            return true;
        }
    }

    public boolean needHack(int callingUid, String[] packages) {
        if (packages == null) return false;
        refreshTargets();
        ensureTeeStatus();
        for (String pkg : packages) {
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.LEAF_HACK) return true;
            if (mode == Mode.AUTO && !mTeeBroken) return true;
        }
        return false;
    }

    public boolean needGenerate(int callingUid, String[] packages) {
        if (packages == null) return false;
        refreshTargets();
        ensureTeeStatus();
        for (String pkg : packages) {
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.GENERATE) return true;
            if (mode == Mode.AUTO && mTeeBroken) return true;
        }
        return false;
    }

    public KeyBoxManager getKeyBoxManager() {
        refreshKeyBox();
        return mKeyBoxManager;
    }

    public CustomPatchLevel getCustomPatchLevel() {
        refreshPatchLevel();
        return mCustomPatchLevel;
    }

    public boolean hasKeyboxes() {
        return mKeyBoxManager.hasKeyboxes();
    }
}
