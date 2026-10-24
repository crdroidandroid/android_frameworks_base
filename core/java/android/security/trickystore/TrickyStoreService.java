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
import android.app.IActivityManager;
import android.os.RemoteException;
import android.os.SystemClock;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class TrickyStoreService {
    private static final String TAG = "TrickyStoreService";

    private static TrickyStoreService sInstance;

    // Revocation check runs from system_server over the network (background thread,
    // 24h cooldown) and is logging-only — it never changes keybox state.
    private static final boolean ENABLE_REVOCATION_CHECK = false;

    private final Set<String> mHackPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> mGeneratePackages = ConcurrentHashMap.newKeySet();
    private final Set<String> mSkipPackages = ConcurrentHashMap.newKeySet();
    private final Map<String, Mode> mPackageModes = new ConcurrentHashMap<>();

    private volatile Boolean mTeeBroken = null;
    // A "healthy" result is cached for the process lifetime; a "broken" one is
    // re-checked after this long, so one transient keystore failure (early boot,
    // exhausted RKP key pool) doesn't force GENERATE mode until the next reboot.
    private static final long TEE_RECHECK_COOLDOWN_MS = 5L * 60 * 1000L;
    private volatile long mTeeCheckedAtMs = 0L;
    private volatile long mLastRevocationCheckMs = 0L;
    private volatile long mLastTargetsRefreshMs = 0L;
    private static final long TARGETS_REFRESH_COOLDOWN_MS = 5_000L;
    // Most attestations call into keybox and patch configuration repeatedly. These
    // cooldowns avoid synchronous ActivityManager Binder calls on every request.
    // Explicit refreshKeyBox()/refreshPatchLevel() still bypass the cooldown.
    private static final long CONFIG_REFRESH_COOLDOWN_MS = 5_000L;
    private final Object mKeyboxRefreshLock = new Object();
    private final Object mPatchRefreshLock = new Object();
    private volatile long mLastKeyboxRefreshElapsedMs = 0L;
    private volatile long mLastPatchRefreshElapsedMs = 0L;
    private static final long REVOCATION_CHECK_COOLDOWN_MS = 24 * 60 * 60 * 1000L;
    private static final long CACHE_TRUST_WINDOW_MS = 7L * 24 * 60 * 60 * 1000L;
    private static final java.io.File REVOCATION_CACHE_FILE =
        new java.io.File("/data/system/trickystore/revocation_cache.json");
    private volatile CustomPatchLevel mCustomPatchLevel = null;
    private final Map<String, CustomPatchLevel> mPerPackagePatchLevels = new ConcurrentHashMap<>();
    // Attesting through a hooked process breaks STRONG — always skipped
    // regardless of what mode the target list has for them, and never
    // auto-targeted by AxSpoofManager. Shared so the two can't drift apart.
    public static final java.util.Set<String> XPOSED_PACKAGES = java.util.Set.of(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager",
            "io.va.exposed",
            "com.topjohnwu.lsplant.manager",
            "me.weishu.exposed"
    );
    // Default TrickyStore targets, in the same syntax as SPOOF_TRICKYSTORE_TARGET:
    // "pkg" is AUTO, "pkg?" leaf hack, "pkg!" cert generation, "pkg-" skip.
    // AxSpoofManager writes this into a target setting that was never set, so a
    // fresh install attests GMS and friends by default.
    public static final String DEFAULT_TARGET_LIST = String.join("\n",
            "android",
            // GMS and friends, AUTO mode
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.google.android.contactkeys",
            "com.google.android.ims",
            "com.google.android.safetycore",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
            // Cert generation
            "com.revolut.revolut!",
            // Key attestation checkers, leaf hack
            "io.github.qwq233.keyattestation?",
            "io.github.vvb2060.keyattestation?",
            "io.github.vvb2060.mahoshojo?",
            "icu.nullptr.nativetest?",
            "com.reveny.nativecheck?",
            "com.zhenxi.hunter?",
            "com.android.nativetest?",
            "io.liankong.riskdetector?",
            "luna.safe.luna?",
            "com.eltavine.duckdetector?",
            "com.rem01gaming.disclosure?",
            "wu.keyChain.test?",
            "com.kikyps.crackme?",
            "com.chunqiunativecheck?"
    );
    private volatile String mLastKeyboxFingerprint = null;

    private final KeyBoxManager mKeyBoxManager;

    /** @hide */
    public enum Mode {
        AUTO, LEAF_HACK, GENERATE, SKIP
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
        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            Log.w(TAG, "ActivityManager not ready, skipping trickystore fetch");
            return null;
        }
        try {
            return fetcher.fetch(am);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to fetch trickystore config from system_server", e);
            return null;
        }
    }

    private interface Fetcher {
        String fetch(IActivityManager am) throws RemoteException;
    }

    public void refreshTargets() {
        String content = fetchFromAms(am -> am.getSpoofTrickyStoreTarget());
        mHackPackages.clear();
        mGeneratePackages.clear();
        mSkipPackages.clear();
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
                  ", generate=" + mGeneratePackages + ", skip=" + mSkipPackages +
                  ", modes=" + mPackageModes);
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
            } else if (line.endsWith("-")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                mSkipPackages.add(pkg);
                mPackageModes.put(pkg, Mode.SKIP);
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
                if (mode == Mode.SKIP) mSkipPackages.add(pkg);
            }
            reader.endArray();
        }
    }

    public void refreshKeyBox() {
        synchronized (mKeyboxRefreshLock) {
            mLastKeyboxRefreshElapsedMs = SystemClock.elapsedRealtime();
            refreshKeyBoxLocked();
        }
    }

    private void refreshKeyBoxLocked() {
        String raw = fetchFromAms(am -> am.getSpoofTrickyStoreKeyBox());
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
            if (!isValidKeyboxXml(xml)) {
                mLastKeyboxFingerprint = null;
                Log.e(TAG, "Keybox XML failed structural validation (missing keys or identifier)");
                return;
            }
            if (ENABLE_REVOCATION_CHECK) {
                checkKeyboxRevocation(xml);
            }
            if (!mKeyBoxManager.parseKeybox(xml)) {
                // Rejected as incomplete: the previous keyboxes stay loaded. Don't record
                // this payload as applied, so the next read looks at it again.
                mLastKeyboxFingerprint = null;
                return;
            }
            if (mKeyBoxManager.hasKeyboxes()) {
                mLastKeyboxFingerprint = fingerprint;
                Log.i(TAG, "Keybox updated successfully");
            } else {
                mLastKeyboxFingerprint = null;
                Log.e(TAG, "Keybox parse produced no usable entries");
            }
        } catch (Exception e) {
            mLastKeyboxFingerprint = null;
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
        synchronized (mPatchRefreshLock) {
            mLastPatchRefreshElapsedMs = SystemClock.elapsedRealtime();
            refreshPatchLevelLocked();
        }
    }

    private void refreshPatchLevelLocked() {
        String content = fetchFromAms(am -> am.getSpoofTrickyStorePatch());
        mPerPackagePatchLevels.clear();
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
        String currentPackage = null;
        String system = null, vendor = null, boot = null, all = null;

        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                flushPatchSection(currentPackage, system, vendor, boot, all);
                currentPackage = line.substring(1, line.length() - 1).trim();
                system = vendor = boot = all = null;
                continue;
            }

            int idx = line.indexOf('=');
            if (idx > 0) {
                String key = line.substring(0, idx).trim().toLowerCase();
                String value = line.substring(idx + 1).trim();
                switch (key) {
                    case "system": system = value; break;
                    case "vendor": vendor = value; break;
                    case "boot":   boot   = value; break;
                    case "all":    all    = value; break;
                }
            } else {
                all = line;
            }
        }
        flushPatchSection(currentPackage, system, vendor, boot, all);
    }

    private static String resolvePatchTemplate(String value) {
        if (value == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^YYYY-MM-(\\d{2})$").matcher(value.trim());
        if (!m.matches()) return value;
        String day = m.group(1);
        java.util.Calendar cal = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"));
        return String.format(java.util.Locale.US, "%04d-%02d-%s",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            day);
    }

    private void flushPatchSection(String pkg, String system, String vendor, String boot, String all) {
        if (system == null && vendor == null && boot == null && all == null) return;
        String resolvedAll    = resolvePatchTemplate(all);
        String resolvedSystem = resolvePatchTemplate(system);
        String resolvedVendor = resolvePatchTemplate(vendor);
        String resolvedBoot   = resolvePatchTemplate(boot);
        CustomPatchLevel level = new CustomPatchLevel(
            resolvedSystem != null ? resolvedSystem : resolvedAll,
            resolvedVendor != null ? resolvedVendor : resolvedAll,
            resolvedBoot   != null ? resolvedBoot   : resolvedAll,
            resolvedAll
        );
        if (pkg == null) {
            mCustomPatchLevel = level;
        } else {
            mPerPackagePatchLevels.put(pkg, level);
        }
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
                    case "boot":   boot   = reader.nextString(); break;
                    case "all":    all    = reader.nextString(); break;
                    case "packages":
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String pkg = reader.nextName();
                            String ps = null, pv = null, pb = null, pa = null;
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String pk = reader.nextName();
                                switch (pk) {
                                    case "system": ps = reader.nextString(); break;
                                    case "vendor": pv = reader.nextString(); break;
                                    case "boot":   pb = reader.nextString(); break;
                                    case "all":    pa = reader.nextString(); break;
                                    default: reader.skipValue(); break;
                                }
                            }
                            reader.endObject();
                            flushPatchSection(pkg, ps, pv, pb, pa);
                        }
                        reader.endObject();
                        break;
                    default: reader.skipValue(); break;
                }
            }
            reader.endObject();
        }
        flushPatchSection(null, system, vendor, boot, all);
    }

    private boolean isTeeStatusFresh() {
        Boolean cached = mTeeBroken;
        return cached != null && (!cached
                || System.currentTimeMillis() - mTeeCheckedAtMs < TEE_RECHECK_COOLDOWN_MS);
    }

    private void ensureTeeStatus() {
        if (isTeeStatusFresh()) return;
        synchronized (this) {
            if (isTeeStatusFresh()) return;
            boolean broken = checkTeeBroken();
            mTeeBroken = broken;
            mTeeCheckedAtMs = System.currentTimeMillis();
            AttestationUtils.setTeeBroken(broken);
        }
    }

    private boolean isValidKeyboxXml(String xml) {
        boolean hasEcdsa = xml.contains("<Key algorithm=\"ecdsa\">");
        boolean hasRsa = xml.contains("<Key algorithm=\"rsa\">");
        boolean hasId = xml.contains("<serial>") || xml.contains("DeviceID");
        if (!hasEcdsa && !hasRsa) {
            Log.e(TAG, "Keybox validation failed: no ECDSA or RSA key block found");
            return false;
        }
        if (!hasId) {
            Log.e(TAG, "Keybox validation failed: no identifier field (serial/DeviceID)");
            return false;
        }
        if (!hasEcdsa) Log.w(TAG, "Keybox warning: missing ECDSA key block");
        if (!hasRsa)   Log.w(TAG, "Keybox warning: missing RSA key block");
        return true;
    }

    private void checkKeyboxRevocation(String xml) {
        long now = System.currentTimeMillis();
        if (now - mLastRevocationCheckMs < REVOCATION_CHECK_COOLDOWN_MS) {
            Log.d(TAG, "Skipping revocation check — ran within 24h");
            return;
        }
        new Thread(() -> {
            try {
                List<String> serials = extractCertSerials(xml);
                if (serials.isEmpty()) return;
                java.net.URL url = new java.net.URL(
                        "https://android.googleapis.com/attestation/status?encrypted=0");
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                    checkCachedRevocation(serials);
                    return;
                }
                String body = new String(
                        conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                org.json.JSONObject entries =
                        new org.json.JSONObject(body).optJSONObject("entries");
                if (entries == null) return;
                for (String serial : serials) {
                    org.json.JSONObject entry = entries.optJSONObject(serial);
                    if (entry == null) continue;
                    String status = entry.optString("status", "").toUpperCase(java.util.Locale.US);
                    if ("REVOKED".equals(status) || "SUSPENDED".equals(status)) {
                        Log.w(TAG, "Keybox serial " + serial + " is " + status +
                                " — attestation may fail");
                        writeCachedRevokedSerial(serial);
                    }
                }
                mLastRevocationCheckMs = now;
            } catch (Exception e) {
                Log.w(TAG, "Keybox revocation check failed, trying offline cache", e);
                try {
                    checkCachedRevocation(extractCertSerials(xml));
                } catch (Exception inner) {
                    Log.w(TAG, "Offline revocation cache check also failed", inner);
                }
            }
        }, "TrickyStore-RevocationCheck").start();
    }

    /**
     * Writes [serial] to a small private cache file so a later network
     * failure can still flag it. Downgrade-only: this cache is only ever
     * consulted to log a warning, never to assert a keybox is safe.
     */
    private void writeCachedRevokedSerial(String serial) {
        try {
            REVOCATION_CACHE_FILE.getParentFile().mkdirs();
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("serial", serial);
            obj.put("cachedAt", System.currentTimeMillis());
            try (java.io.FileWriter fw = new java.io.FileWriter(REVOCATION_CACHE_FILE)) {
                fw.write(obj.toString());
            }
            android.system.Os.chmod(REVOCATION_CACHE_FILE.getAbsolutePath(), 0600);
        } catch (Exception e) {
            Log.w(TAG, "Failed to write revocation cache", e);
        }
    }

    /**
     * Logging-only fallback used when the live revocation fetch fails.
     * Reads the cached bad serial, if any, and logs a warning if it matches
     * one of the current keybox's serials and the cache is still within the
     * trust window. Never mutates keybox state — that decision stays with
     * the Settings UI layer.
     */
    private void checkCachedRevocation(List<String> serials) {
        if (!REVOCATION_CACHE_FILE.exists()) return;
        try {
            String content = new String(
                java.nio.file.Files.readAllBytes(REVOCATION_CACHE_FILE.toPath()),
                StandardCharsets.UTF_8);
            org.json.JSONObject obj = new org.json.JSONObject(content);
            long cachedAt = obj.optLong("cachedAt", 0L);
            if (cachedAt == 0L ||
                System.currentTimeMillis() - cachedAt > CACHE_TRUST_WINDOW_MS) {
                return;
            }
            String cachedSerial = obj.optString("serial", "");
            if (!cachedSerial.isEmpty() && serials.contains(cachedSerial)) {
                Log.w(TAG, "Keybox serial " + cachedSerial +
                        " was cached as revoked/suspended (offline fallback,"
                        + " live check unavailable)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read revocation cache", e);
        }
    }

    private List<String> extractCertSerials(String xml) {
        List<String> serials = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----");
        java.util.regex.Matcher m = p.matcher(xml);
        java.security.cert.CertificateFactory factory;
        try {
            factory = java.security.cert.CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            return serials;
        }
        while (m.find()) {
            try {
                byte[] der = Base64.getDecoder().decode(
                        m.group(1).replaceAll("\\s", ""));
                java.security.cert.X509Certificate cert =
                        (java.security.cert.X509Certificate)
                        factory.generateCertificate(
                                new java.io.ByteArrayInputStream(der));
                // Google's revocation list spells 128-bit serials as lowercase hex without
                // leading zeros and the old 64-bit ones as decimal, so look up both.
                serials.add(cert.getSerialNumber().toString(16));
                serials.add(cert.getSerialNumber().toString());
            } catch (Exception ignored) {}
        }
        return serials;
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
        maybeRefreshTargets();
        for (String pkg : packages) {
            if (XPOSED_PACKAGES.contains(pkg)) continue;
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.SKIP) continue;
            if (mode == Mode.LEAF_HACK) return true;
            // Only AUTO needs to know the TEE state. Probing it costs a real
            // attested key generation, so don't do it for untargeted apps.
            if (mode == Mode.AUTO && !isTeeBroken()) return true;
        }
        return false;
    }

    public boolean needGenerate(int callingUid, String[] packages) {
        if (packages == null) return false;
        maybeRefreshTargets();
        for (String pkg : packages) {
            if (XPOSED_PACKAGES.contains(pkg)) continue;
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.SKIP) continue;
            if (mode == Mode.GENERATE) return true;
            if (mode == Mode.AUTO && isTeeBroken()) return true;
        }
        return false;
    }

    private void maybeRefreshTargets() {
        long now = System.currentTimeMillis();
        if (now - mLastTargetsRefreshMs >= TARGETS_REFRESH_COOLDOWN_MS) {
            mLastTargetsRefreshMs = now;
            refreshTargets();
        }
    }

    private void maybeRefreshKeyBox() {
        long now = SystemClock.elapsedRealtime();
        if (mLastKeyboxRefreshElapsedMs != 0L
                && now - mLastKeyboxRefreshElapsedMs < CONFIG_REFRESH_COOLDOWN_MS) {
            return;
        }
        synchronized (mKeyboxRefreshLock) {
            now = SystemClock.elapsedRealtime();
            if (mLastKeyboxRefreshElapsedMs == 0L
                    || now - mLastKeyboxRefreshElapsedMs >= CONFIG_REFRESH_COOLDOWN_MS) {
                mLastKeyboxRefreshElapsedMs = now;
                refreshKeyBoxLocked();
            }
        }
    }

    private void maybeRefreshPatchLevel() {
        long now = SystemClock.elapsedRealtime();
        if (mLastPatchRefreshElapsedMs != 0L
                && now - mLastPatchRefreshElapsedMs < CONFIG_REFRESH_COOLDOWN_MS) {
            return;
        }
        synchronized (mPatchRefreshLock) {
            now = SystemClock.elapsedRealtime();
            if (mLastPatchRefreshElapsedMs == 0L
                    || now - mLastPatchRefreshElapsedMs >= CONFIG_REFRESH_COOLDOWN_MS) {
                mLastPatchRefreshElapsedMs = now;
                refreshPatchLevelLocked();
            }
        }
    }

    public KeyBoxManager getKeyBoxManager() {
        maybeRefreshKeyBox();
        return mKeyBoxManager;
    }

    public CustomPatchLevel getCustomPatchLevel() {
        maybeRefreshPatchLevel();
        return mCustomPatchLevel;
    }

    public CustomPatchLevel getCustomPatchLevelForPackage(String[] packages) {
        maybeRefreshPatchLevel();
        if (packages != null) {
            for (String pkg : packages) {
                CustomPatchLevel level = mPerPackagePatchLevels.get(pkg);
                if (level != null) return level;
            }
        }
        return mCustomPatchLevel;
    }

    /**
     * Returns true if the given patch date string is more than 12 months old.
     * Accepts YYYY-MM-DD only; unparsable or null input is treated as not stale
     * (we don't want to warn on a value we can't understand).
     */
    private static final long PATCH_STALE_THRESHOLD_MS = 365L * 24 * 60 * 60 * 1000L;

    public static boolean isPatchLevelStale(String patchDate) {
        if (patchDate == null || patchDate.isEmpty()) return false;
        try {
            java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setLenient(false);
            long patchMs = sdf.parse(patchDate.trim()).getTime();
            return System.currentTimeMillis() - patchMs > PATCH_STALE_THRESHOLD_MS;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasKeyboxes() {
        return mKeyBoxManager.hasKeyboxes();
    }

    /**
     * Returns whether the TEE is broken, forcing the check if it hasn't run yet.
     * Safe to call from any thread; the underlying check is synchronized.
     */
    public boolean isTeeBroken() {
        ensureTeeStatus();
        return Boolean.TRUE.equals(mTeeBroken);
    }
}
