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
package com.android.server.spoof;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Slog;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the TrickyStore keybox healthy without any user interaction.
 *
 * <p>Runs inside {@code system_server} on a dedicated {@link HandlerThread} and:
 * <ul>
 *     <li>validates the installed keybox certificate chain against the known Google
 *         hardware attestation roots,</li>
 *     <li>asks Google's attestation revocation endpoint whether the leaf
 *         certificate was revoked or suspended,</li>
 *     <li>downloads the official keybox when none is installed or when the
 *         installed one turned out to be revoked.</li>
 * </ul>
 *
 * <p>Everything is throttled: a validation pass runs at most once every 24 hours and
 * a failed download backs off for 24 hours, so a missing or hostile upstream never
 * turns into a request loop.
 *
 * @hide
 */
public final class TrickyStoreUpdater {

    private static final String TAG = "TrickyStoreUpdater";

    private static final String KEYBOX_URL =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/keybox.xml";
    private static final String REVOCATION_URL =
            "https://android.googleapis.com/attestation/status?encrypted=0";

    /** Cadence of the background validation pass. */
    static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    /** Backoff after the upstream offered no usable keybox. */
    static final long NO_VALID_COOLDOWN_MS = 24L * 60 * 60 * 1000;
    /** How long a cached revocation result may be trusted without network. */
    static final long OFFLINE_CACHE_MS = 7L * 24 * 60 * 60 * 1000;
    /** Warn this long before the leaf certificate expires. */
    static final long EXPIRING_SOON_MS = 14L * 24 * 60 * 60 * 1000;

    private static final int NETWORK_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final long BOOT_DELAY_MS = 3L * 60 * 1000;
    private static final long RETRY_DELAY_MS = 30L * 60 * 1000;
    private static final long MIN_SCHEDULE_DELAY_MS = 15L * 60 * 1000;

    static final String SOURCE_OFFICIAL = "official";
    static final String SOURCE_USER = "user";

    static final String STATUS_VALID = "VALID";
    static final String STATUS_EXPIRING_SOON = "EXPIRING_SOON";
    static final String STATUS_REVOKED = "REVOKED";
    static final String STATUS_SUSPENDED = "SUSPENDED";
    static final String STATUS_CHAIN_INVALID = "CHAIN_INVALID";
    static final String STATUS_UNTRUSTED_ROOT = "UNTRUSTED_ROOT";
    static final String STATUS_UNKNOWN = "UNKNOWN";

    static final String REASON_CERT_EXPIRED = "CERT_EXPIRED";
    static final String REASON_CACHED_OFFLINE = "CACHED_OFFLINE";
    static final String REASON_NO_KEYBOX = "NO_KEYBOX";
    static final String REASON_UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    static final String REASON_NO_CERTIFICATES = "NO_CERTIFICATES";
    static final String REASON_UNTRUSTED_ROOT = "ROOT_NOT_A_GOOGLE_ATTESTATION_ROOT";

    /**
     * SHA-256 of the DER encoded root certificate, lowercase hex, no separators.
     * Anything else is not a Google attestation root and therefore cannot produce a
     * chain Google would accept.
     */
    private static final Set<String> TRUSTED_ROOTS = Set.of(
            // Google Hardware Attestation Root (RSA-4096), issued 2019, expires 2034.
            "1ef1a04b8ba58ab94589ac498c8982a783f24ea7307e0159a0c3a73b377d87cc",
            // Same key, reissued 2022, expires 2042.
            "cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0febe34a9a1248800dfc",
            // Google Key Attestation CA1 (EC P-384), issued 2025, expires 2035.
            "c6e5dc76bd81307046a3ccc979f0fac6bddef46cc9b533b2134eb0e99f67550e");

    /** Packages whose attestation result is cached and must be dropped on rotation. */
    private static final String[] GMS_PACKAGES = {
            "com.android.vending",
            "com.google.android.gms.unstable",
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.google.android.rkpdapp",
            "com.google.android.gsf",
            "com.google.android.contactkeys",
            "com.google.android.safetycore",
    };

    /** The package whose user data is wiped so Play Integrity re-evaluates. */
    private static final String VENDING_PACKAGE = "com.android.vending";

    private static final int SYSTEM_USER_ID = UserHandle.USER_SYSTEM;

    private static final String PEM_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_END = "-----END CERTIFICATE-----";

    private final Context mContext;
    private final Handler mHandler;

    private final Runnable mPeriodicTask = () -> runPass(true);
    private final Runnable mRetryTask = () -> runPass(true);

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (mWaitingForNetwork) {
                        mWaitingForNetwork = false;
                        mHandler.removeCallbacks(mRetryTask);
                        mHandler.post(() -> runPass(true));
                    }
                }
            };

    private volatile boolean mWaitingForNetwork;
    private volatile boolean mStarted;
    private volatile boolean mRunning;

    public TrickyStoreUpdater(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("TrickyStoreUpdater");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /** Starts the background loop. Safe to call more than once. */
    public void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;

        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.registerDefaultNetworkCallback(mNetworkCallback);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unable to observe connectivity changes", e);
            }
        }

        mHandler.post(() -> runPass(true));
    }

    /**
     * Downloads the official keybox right now, whether or not the scheduled updates
     * are enabled: this is the user asking for it.
     */
    public void requestKeyBoxFetch() {
        mHandler.post(() -> runFetch(FetchReason.MANUAL));
    }

    /** Re-runs the validation now instead of waiting for the next 24 hour tick. */
    public void requestStatusCheck() {
        mHandler.post(() -> runPass(false));
    }

    private boolean isEnabled() {
        return readLong(Settings.Secure.SPOOF_TRICKYSTORE_ENABLED, 1L) != 0L;
    }

    // ------------------------------------------------------------------ pass

    /**
     * @param fromSchedule whether this run belongs to the scheduled loop, which is the
     *                     only thing the enable switch gates
     * @return {@code true} when the pass ended because there was no network
     */
    private boolean runPass(boolean fromSchedule) {
        if (mRunning) {
            Slog.d(TAG, "Pass already in progress, skipping");
            return false;
        }
        if (fromSchedule && !isEnabled()) {
            Slog.d(TAG, "Automatic keybox updates are disabled");
            return false;
        }

        mRunning = true;
        boolean retryOnNetwork = false;
        try {
            retryOnNetwork = validateInstalledKeyBox();
            autoFetchKeyBox();
        } catch (Throwable t) {
            Slog.e(TAG, "Keybox maintenance pass failed", t);
        } finally {
            mRunning = false;
            scheduleNext(retryOnNetwork);
        }
        return false;
    }

    private boolean validateInstalledKeyBox() {
        try {
            String keybox = readKeyBox();
            if (keybox == null || keybox.isEmpty()) {
                setStatus(STATUS_UNKNOWN, REASON_NO_KEYBOX);
                return false;
            }

            List<X509Certificate> certs = extractCertificates(keybox);
            if (certs.isEmpty()) {
                setStatus(STATUS_CHAIN_INVALID, REASON_NO_CERTIFICATES);
                return false;
            }

            List<List<X509Certificate>> chains = splitChains(certs);
            String localFailure = validateChains(chains);
            if (localFailure != null) {
                setStatus(statusForLocalFailure(localFailure), localFailure);
                handleActionableStatus();
                return false;
            }

            List<X509Certificate> leaves = leaves(chains);
            String revocationJson;
            try {
                revocationJson = httpGet(REVOCATION_URL);
            } catch (IOException | RuntimeException e) {
                Slog.w(TAG, "Revocation endpoint unreachable", e);
                applyOfflineFallback(leaves);
                return true;
            }

            applyRevocationEntry(leaves, revocationJson);
            handleActionableStatus();
            return false;
        } finally {
            writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_REVOCATION_CHECK,
                    System.currentTimeMillis());
        }
    }

    /**
     * A keybox does not necessarily hold a single chain: the common ones ship one
     * chain per algorithm, EC and RSA side by side. Split the document order on every
     * self signed certificate and on every issuer/subject mismatch so each chain is
     * validated on its own.
     *
     * @return one entry per chain, leaf first, root last
     */
    private static List<List<X509Certificate>> splitChains(List<X509Certificate> certs) {
        List<List<X509Certificate>> chains = new ArrayList<>();
        List<X509Certificate> current = new ArrayList<>();
        for (X509Certificate cert : certs) {
            if (!current.isEmpty() && !current.get(current.size() - 1)
                    .getIssuerX500Principal().equals(cert.getSubjectX500Principal())) {
                chains.add(current);
                current = new ArrayList<>();
            }
            current.add(cert);
            if (isSelfSigned(cert)) {
                chains.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            chains.add(current);
        }
        return chains;
    }

    private static List<X509Certificate> leaves(List<List<X509Certificate>> chains) {
        List<X509Certificate> leaves = new ArrayList<>();
        for (List<X509Certificate> chain : chains) {
            leaves.add(chain.get(0));
        }
        return leaves;
    }

    private static boolean isSelfSigned(X509Certificate cert) {
        try {
            cert.verify(cert.getPublicKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies the signatures, that the root is self signed and that it belongs to
     * Google, for every chain in the keybox.
     *
     * @return {@code null} when all chains are trustworthy, otherwise the first failure
     */
    private String validateChains(List<List<X509Certificate>> chains) {
        for (List<X509Certificate> chain : chains) {
            String failure = validateChain(chain);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    /**
     * @return {@code null} when the chain is trustworthy, otherwise the failure reason
     */
    private String validateChain(List<X509Certificate> chain) {
        for (int i = 0; i + 1 < chain.size(); i++) {
            try {
                chain.get(i).verify(chain.get(i + 1).getPublicKey());
            } catch (Exception e) {
                return "BAD_SIGNATURE_AT_" + i;
            }
        }

        X509Certificate root = chain.get(chain.size() - 1);
        try {
            root.verify(root.getPublicKey());
        } catch (Exception e) {
            return "ROOT_NOT_SELF_SIGNED";
        }

        String rootFingerprint;
        try {
            rootFingerprint = sha256Hex(root.getEncoded());
        } catch (GeneralSecurityException e) {
            Slog.w(TAG, "Unable to encode the keybox root certificate", e);
            return "ROOT_ENCODING_FAILED";
        }
        if (!TRUSTED_ROOTS.contains(rootFingerprint)) {
            return REASON_UNTRUSTED_ROOT;
        }

        try {
            chain.get(0).checkValidity();
        } catch (Exception e) {
            return REASON_CERT_EXPIRED;
        }

        return null;
    }

    private static String statusForLocalFailure(String failure) {
        return REASON_UNTRUSTED_ROOT.equals(failure)
                ? STATUS_UNTRUSTED_ROOT
                : STATUS_CHAIN_INVALID;
    }

    private void applyRevocationEntry(List<X509Certificate> leaves, String revocationJson) {
        for (X509Certificate leaf : leaves) {
            String serial = hexSerial(leaf);
            String[] entry = lookupRevocationEntry(serial, revocationJson);
            if (entry != null) {
                Slog.w(TAG, "Keybox leaf is " + entry[0] + " (" + entry[1] + ")");
                cacheRevokedSerial(serial);
                setStatus(entry[0], entry[1]);
                return;
            }
        }

        clearRevokedCache();
        X509Certificate soonest = soonestExpiry(leaves);
        long remaining = soonest.getNotAfter().getTime() - System.currentTimeMillis();
        if (remaining >= 0 && remaining < EXPIRING_SOON_MS) {
            setStatus(STATUS_EXPIRING_SOON, formatDate(soonest));
        } else {
            setStatus(STATUS_VALID, null);
        }
    }

    private static X509Certificate soonestExpiry(List<X509Certificate> certs) {
        X509Certificate soonest = certs.get(0);
        for (X509Certificate cert : certs) {
            if (cert.getNotAfter().before(soonest.getNotAfter())) {
                soonest = cert;
            }
        }
        return soonest;
    }

    /**
     * Without the revocation endpoint we cannot claim the keybox is good. A serial
     * cached as revoked inside the offline window is still actionable, everything
     * else degrades to {@code UNKNOWN}.
     */
    private void applyOfflineFallback(List<X509Certificate> leaves) {
        long cachedAt = readLong(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_AT, 0L);
        if (cachedAt > 0 && System.currentTimeMillis() - cachedAt < OFFLINE_CACHE_MS) {
            String cached =
                    readString(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_SERIALS);
            for (X509Certificate leaf : leaves) {
                if (cached != null && cached.contains(hexSerial(leaf))) {
                    setStatus(STATUS_SUSPENDED, REASON_CACHED_OFFLINE);
                    return;
                }
            }
        }
        setStatus(STATUS_UNKNOWN, REASON_UPSTREAM_UNAVAILABLE);
    }

    /**
     * @return {@code {status, reason}} when Google reports the serial as revoked or
     *         suspended, {@code null} when the keybox is clean
     */
    private static String[] lookupRevocationEntry(String serial, String revocationJson) {
        try {
            JSONObject entries = new JSONObject(revocationJson).optJSONObject("entries");
            if (entries == null) {
                return null;
            }
            // Google keys the entries by the serial in both decimal and lowercase hex.
            String[] keys = {serial, new BigInteger(serial, 16).toString()};
            for (String key : keys) {
                JSONObject entry = entries.optJSONObject(key);
                if (entry == null) {
                    continue;
                }
                String status = entry.optString("status", null);
                if (STATUS_REVOKED.equalsIgnoreCase(status)
                        || STATUS_SUSPENDED.equalsIgnoreCase(status)) {
                    return new String[] {status.toUpperCase(Locale.ROOT),
                            entry.optString("reason", null)};
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Malformed revocation response", e);
        }
        return null;
    }

    // -------------------------------------------------------------- fetching

    private void autoFetchKeyBox() {
        String installed = readKeyBox();
        boolean haveKeyBox = installed != null && !installed.isEmpty();
        boolean revoked = isRevoked();

        if (haveKeyBox && !revoked) {
            return;
        }
        if (revoked && haveKeyBox
                && SOURCE_USER.equals(readString(
                        Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE))) {
            Slog.i(TAG, "Installed keybox was imported by the user, leaving it alone");
            return;
        }
        if (noValidCooldownActive()) {
            Slog.i(TAG, "No valid official keybox available yet, will retry tomorrow");
            return;
        }

        runFetch(revoked ? FetchReason.AUTO_REVOKED : FetchReason.AUTO_MISSING);
    }

    private void runFetch(FetchReason reason) {
        String upstream;
        try {
            upstream = httpGet(KEYBOX_URL);
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "Unable to download the official keybox", e);
            enterNoValidCooldown();
            return;
        }

        if (upstream == null || upstream.isEmpty() || !upstream.contains(PEM_BEGIN)) {
            Slog.w(TAG, "Downloaded keybox is not a certificate list");
            enterNoValidCooldown();
            return;
        }

        String trimmed = upstream.trim();
        if (trimmed.equals(readKeyBox())) {
            Slog.i(TAG, "Keybox is already up to date");
            writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_FETCHED,
                    System.currentTimeMillis());
            writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_NO_VALID, 0L);
            return;
        }

        List<X509Certificate> certs = extractCertificates(trimmed);
        if (certs.isEmpty()) {
            Slog.w(TAG, "Downloaded keybox has no usable certificate");
            enterNoValidCooldown();
            return;
        }

        List<List<X509Certificate>> chains = splitChains(certs);
        String localFailure = validateChains(chains);
        if (localFailure != null && !REASON_UNTRUSTED_ROOT.equals(localFailure)) {
            // A broken chain means a broken key. An unknown root is only a warning:
            // the ROM publishes the keybox it expects to be used, and whether it is
            // accepted is decided by the revocation endpoint below.
            Slog.w(TAG, "Downloaded keybox is not usable: " + localFailure);
            enterNoValidCooldown();
            return;
        }
        if (localFailure != null) {
            Slog.w(TAG, "Downloaded keybox root is not a known Google root, installing anyway");
        }

        String revocationJson;
        try {
            revocationJson = httpGet(REVOCATION_URL);
        } catch (IOException | RuntimeException e) {
            // Without the revocation endpoint there is no way to tell whether this
            // keybox was already leaked, so do not push it to the device.
            Slog.w(TAG, "Cannot verify the downloaded keybox", e);
            enterNoValidCooldown();
            return;
        }
        for (X509Certificate leaf : leaves(chains)) {
            if (lookupRevocationEntry(hexSerial(leaf), revocationJson) != null) {
                Slog.w(TAG, "Official keybox is already revoked");
                enterNoValidCooldown();
                return;
            }
        }

        writeString(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, encode(trimmed));
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE, SOURCE_OFFICIAL);
        writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_FETCHED,
                System.currentTimeMillis());
        writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_NO_VALID, 0L);
        clearRevokedCache();
        Slog.i(TAG, "Installed official keybox (" + reason + ")");

        // Report the state of what is now actually installed.
        validateInstalledKeyBox();
        restartGms();
    }

    /**
     * Drops a keybox that Google no longer accepts. A keybox the user imported is
     * kept, since the settings screen is where that decision belongs.
     */
    private void handleActionableStatus() {
        if (!isRevoked()) {
            return;
        }
        if (SOURCE_USER.equals(
                readString(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE))) {
            Slog.w(TAG, "Installed keybox was revoked but was imported by the user");
            return;
        }
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, "");
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE, "");
        writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_FETCHED, 0L);
        Slog.i(TAG, "Revoked keybox removed, waiting for a replacement");
    }

    // ---------------------------------------------------------------- helpers

    private boolean isRevoked() {
        String status =
                readString(Settings.Secure.SPOOF_TRICKYSTORE_LAST_REVOCATION_STATUS);
        return STATUS_REVOKED.equals(status) || STATUS_SUSPENDED.equals(status);
    }

    private boolean noValidCooldownActive() {
        long since = readLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_NO_VALID, 0L);
        return since > 0 && System.currentTimeMillis() - since < NO_VALID_COOLDOWN_MS;
    }

    private void enterNoValidCooldown() {
        writeLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_NO_VALID,
                System.currentTimeMillis());
    }

    /**
     * Drops the Play Integrity caches of the Google packages so the freshly
     * installed keybox is evaluated again on the next check.
     */
    private void restartGms() {
        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            return;
        }
        final int userId = SYSTEM_USER_ID;
        for (String pkg : GMS_PACKAGES) {
            try {
                am.forceStopPackage(pkg, userId);
            } catch (Throwable t) {
                Slog.w(TAG, "Unable to stop " + pkg, t);
            }
        }
        try {
            am.clearApplicationUserData(VENDING_PACKAGE, false, null, userId);
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to clear Play Store data", t);
        }
    }

    private void setStatus(String status, String reason) {
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_LAST_REVOCATION_STATUS, status);
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_LAST_REVOCATION_REASON, reason);
    }

    private void cacheRevokedSerial(String serial) {
        String cached =
                readString(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_SERIALS);
        List<String> serials = new ArrayList<>();
        if (cached != null) {
            for (String value : cached.split("\n")) {
                if (!value.isEmpty()) {
                    serials.add(value);
                }
            }
        }
        if (!serials.contains(serial)) {
            serials.add(serial);
        }
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_SERIALS,
                String.join("\n", serials));
        writeLong(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_AT,
                System.currentTimeMillis());
    }

    private void clearRevokedCache() {
        writeString(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_SERIALS, "");
        writeLong(Settings.Secure.SPOOF_TRICKYSTORE_CACHED_REVOKED_AT, 0L);
    }

    /** The installed keybox, whether it was stored raw or base64 encoded. */
    private String readKeyBox() {
        String raw = readString(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("<")) {
            return trimmed;
        }
        try {
            String decoded = new String(Base64.decode(trimmed, Base64.DEFAULT),
                    StandardCharsets.UTF_8).trim();
            return decoded.startsWith("<") ? decoded : trimmed;
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private static String encode(String keybox) {
        return Base64.encodeToString(keybox.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
    }

    private String readString(String key) {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(), key,
                UserHandle.USER_SYSTEM);
    }

    private void writeString(String key, String value) {
        Settings.Secure.putStringForUser(mContext.getContentResolver(), key,
                value == null ? "" : value, UserHandle.USER_SYSTEM);
    }

    private long readLong(String key, long fallback) {
        return Settings.Secure.getLongForUser(mContext.getContentResolver(), key,
                fallback, SYSTEM_USER_ID);
    }

    private void writeLong(String key, long value) {
        Settings.Secure.putLongForUser(mContext.getContentResolver(), key, value,
                SYSTEM_USER_ID);
    }

    /** Extracts the certificate chain in document order, leaf first. */
    private static List<X509Certificate> extractCertificates(String keybox) {
        List<X509Certificate> certs = new ArrayList<>();
        if (keybox == null) {
            return certs;
        }

        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            Slog.e(TAG, "X.509 unavailable", e);
            return certs;
        }

        int cursor = 0;
        while (true) {
            int begin = keybox.indexOf(PEM_BEGIN, cursor);
            if (begin < 0) {
                break;
            }
            int end = keybox.indexOf(PEM_END, begin);
            if (end < 0) {
                break;
            }
            String pem = keybox.substring(begin, end + PEM_END.length());
            cursor = end + PEM_END.length();

            StringBuilder body = new StringBuilder();
            for (String line : pem.split("\n")) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("-----")) {
                    body.append(value);
                }
            }

            try {
                byte[] der = Base64.decode(body.toString(), Base64.DEFAULT);
                certs.add((X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(der)));
            } catch (Exception e) {
                Slog.w(TAG, "Skipping unreadable certificate", e);
            }
        }
        return certs;
    }

    private static String hexSerial(X509Certificate cert) {
        return cert.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
    }

    private static String formatDate(X509Certificate cert) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cert.getNotAfter());
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            Slog.e(TAG, "SHA-256 unavailable", e);
            return "";
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() / 100 != 2) {
                throw new IOException(
                        "HTTP " + connection.getResponseCode() + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readNBytes(MAX_RESPONSE_BYTES),
                        StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void scheduleNext(boolean retryOnNetwork) {
        if (retryOnNetwork) {
            mWaitingForNetwork = true;
            mHandler.postDelayed(mRetryTask, RETRY_DELAY_MS);
        }

        long lastCheck =
                readLong(Settings.Secure.SPOOF_TRICKYSTORE_LAST_REVOCATION_CHECK, 0L);
        long delay = lastCheck <= 0
                ? BOOT_DELAY_MS
                : lastCheck + CHECK_INTERVAL_MS - System.currentTimeMillis();
        delay = Math.max(MIN_SCHEDULE_DELAY_MS, delay);

        mHandler.removeCallbacks(mPeriodicTask);
        mHandler.postDelayed(mPeriodicTask, delay);
        Slog.i(TAG, "Next keybox maintenance pass in " + (delay / 60000) + " min");
    }

    private enum FetchReason {
        MANUAL,
        AUTO_MISSING,
        AUTO_REVOKED,
    }
}