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
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public class TrickyStoreService {
    private static final String TAG = "TrickyStoreService";

    private static TrickyStoreService sInstance;

    private static final class FetchResult {
        final boolean success;
        final String value;

        FetchResult(boolean success, String value) {
            this.success = success;
            this.value = value;
        }
    }

    private static final class PatchConfig {
        final CustomPatchLevel global;
        final Map<String, CustomPatchLevel> byPackage;

        PatchConfig(CustomPatchLevel global, Map<String, CustomPatchLevel> byPackage) {
            this.global = global;
            this.byPackage = Collections.unmodifiableMap(new HashMap<>(byPackage));
        }
    }

    private static final class PatchConfigBuilder {
        CustomPatchLevel global;
        final Map<String, CustomPatchLevel> byPackage = new HashMap<>();

        PatchConfig build() {
            return new PatchConfig(global, byPackage);
        }
    }

    // Revocation check runs from system_server over the network (background thread,
    // 24h cooldown) and is logging-only — it never changes keybox state.
    private static final boolean ENABLE_REVOCATION_CHECK = false;

    // Publish package policy atomically. An attestation cannot see half a refresh.
    private volatile Map<String, Mode> mPackageModes = Collections.emptyMap();

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
    private volatile PatchConfig mPatchConfig =
            new PatchConfig(null, Collections.emptyMap());
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

    private FetchResult fetchFromAms(Fetcher fetcher) {
        try {
            IActivityManager am = ActivityManager.getService();
            if (am == null) {
                Log.w(TAG, "ActivityManager not ready, retaining last trickystore configuration");
                return new FetchResult(false, null);
            }
            return new FetchResult(true, fetcher.fetch(am));
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch trickystore config; retaining existing values", e);
            return new FetchResult(false, null);
        }
    }

    private interface Fetcher {
        String fetch(IActivityManager am) throws RemoteException;
    }

    public synchronized void refreshTargets() {
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStoreTarget());
        if (!fetched.success) return;
        if (fetched.value == null || fetched.value.trim().isEmpty()) {
            mPackageModes = Collections.emptyMap();
            return;
        }
        String content = fetched.value.trim();
        try {
            Map<String, Mode> parsed = content.startsWith("[")
                    ? parseTargetsJson(content) : parseTargetsText(content);
            mPackageModes = Collections.unmodifiableMap(parsed);
            Log.i(TAG, "Updated target policies: " + parsed.size());
        } catch (Exception e) {
            Log.e(TAG, "Invalid target policies; retaining previous configuration", e);
        }
    }

    private Map<String, Mode> parseTargetsText(String content) {
        Map<String, Mode> parsed = new HashMap<>();
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.endsWith("!")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) parsed.put(pkg, Mode.GENERATE);
            } else if (line.endsWith("?")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) parsed.put(pkg, Mode.LEAF_HACK);
            } else if (line.endsWith("-")) {
                String pkg = line.substring(0, line.length() - 1).trim();
                if (!pkg.isEmpty()) parsed.put(pkg, Mode.SKIP);
            } else {
                parsed.put(line, Mode.AUTO);
            }
        }
        return parsed;
    }

    private Map<String, Mode> parseTargetsJson(String content) throws IOException {
        Map<String, Mode> parsed = new HashMap<>();
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
                if (!pkg.trim().isEmpty()) parsed.put(pkg.trim(), mode);
            }
            reader.endArray();
        }
        return parsed;
    }

    public void refreshKeyBox() {
        synchronized (mKeyboxRefreshLock) {
            mLastKeyboxRefreshElapsedMs = SystemClock.elapsedRealtime();
            refreshKeyBoxLocked();
        }
    }

    private void refreshKeyBoxLocked() {
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStoreKeyBox());
        if (!fetched.success) return;
        String raw = fetched.value;
        if (raw == null || raw.trim().isEmpty()) {
            // A successful empty response means the config was explicitly deleted.
            mKeyBoxManager.clear();
            mLastKeyboxFingerprint = null;
            return;
        }
        String fingerprint = sha256(raw);
        if (fingerprint.equals(mLastKeyboxFingerprint)) {
            return;
        }
        String xml = decodeKeybox(raw);
        if (xml == null) {
            Log.e(TAG, "Keybox payload not recognised as XML or base64-encoded XML");
            return;
        }
        try {
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

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return AttestationUtils.bytesToHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
        FetchResult fetched = fetchFromAms(am -> am.getSpoofTrickyStorePatch());
        if (!fetched.success) return;
        String content = fetched.value;
        if (content == null || content.trim().isEmpty()) {
            mPatchConfig = new PatchConfig(null, Collections.emptyMap());
            return;
        }
        try {
            PatchConfigBuilder next = new PatchConfigBuilder();
            String trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                parsePatchJson(trimmed, next);
            } else {
                parsePatchText(trimmed, next);
            }
            mPatchConfig = next.build();
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch levels; retaining previous configuration", e);
        }
    }

    private void parsePatchText(String content, PatchConfigBuilder next) {
        String currentPackage = null;
        String system = null, vendor = null, boot = null, all = null;

        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                flushPatchSection(next, currentPackage, system, vendor, boot, all);
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
        flushPatchSection(next, currentPackage, system, vendor, boot, all);
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

    private void flushPatchSection(PatchConfigBuilder next,
            String pkg, String system, String vendor, String boot, String all) {
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
            next.global = level;
        } else {
            next.byPackage.put(pkg, level);
        }
    }

    private void parsePatchJson(String content, PatchConfigBuilder next) throws IOException {
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
                            flushPatchSection(next, pkg, ps, pv, pb, pa);
                        }
                        reader.endObject();
                        break;
                    default: reader.skipValue(); break;
                }
            }
            reader.endObject();
        }
        flushPatchSection(next, null, system, vendor, boot, all);
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
        final String alias = "TrickyStoreTeeCheck";
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[16]);
            kpg.initialize(builder.build());
            kpg.generateKeyPair();
            Log.i(TAG, "Attested key generation probe successful");
            return false;
        } catch (Exception e) {
            // A failed probe is not proof of a damaged TEE. Unsupported attestation
            // or unavailable provisioning may produce the same failure.
            Log.w(TAG, "Attested key generation probe unavailable", e);
            return true;
        } finally {
            if (ks != null) {
                try {
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete temporary TEE check alias", e);
                }
            }
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
        return mPatchConfig.global;
    }

    public CustomPatchLevel getCustomPatchLevelForPackage(String[] packages) {
        maybeRefreshPatchLevel();
        PatchConfig config = mPatchConfig;
        if (packages != null) {
            for (String pkg : packages) {
                CustomPatchLevel level = config.byPackage.get(pkg);
                if (level != null) return level;
            }
        }
        return config.global;
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
