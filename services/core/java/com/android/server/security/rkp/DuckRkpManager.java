/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.security.rkp;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.trickystore.KeyBoxManager;
import android.security.trickystore.TrickyStoreService;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

public final class DuckRkpManager {
    private static final String TAG = "DuckRkp";
    private static final String SOURCE_KEY = "spoof_trickystore_keybox_source";
    private static final String MASTER_KEY = "spoof_trickystore_enabled";
    private static final long TIMEOUT_MS = 240000;
    private static final long COOLDOWN_MS = 60000;
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long MAX_RETRY_MS = TimeUnit.HOURS.toMillis(6);
    private static final long NETWORK_CHECK_MS = TimeUnit.HOURS.toMillis(1);
    private static final long ALARM_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String AUTO_STATE_KEY = "spoof_duck_rkp_auto_state";

    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final AlarmManager mAlarms;
    private final ConnectivityManager mConnectivity;
    private final Runnable mReschedule = this::scheduleAutomatic;
    private final AlarmManager.OnAlarmListener mAutomatic = this::scheduleAutomatic;
    private final Runnable mOnChanged;
    private final AtomicFile mFile = new AtomicFile(
            new File(Environment.getDataSystemDirectory(), "duck_rkp.xml"));
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "DuckRkp"));
    private String mGenerated;
    private long mExpiresAt;
    private long mRenewAt;
    private long mNextAttempt;
    private long mNextAutomaticAt;
    private int mFailures;
    private boolean mAutomaticPaused;
    private boolean mWaitingForNetwork;
    private long mGeneration;
    private long mRevision;
    private long mStartedAt;
    private long mLastAttempt = -COOLDOWN_MS;
    private boolean mBusy;
    private boolean mLoaded;
    private String mStage = "idle";
    private String mFailure = "";
    private int mDetail;

    public DuckRkpManager(Context context, Handler handler, Runnable onChanged) {
        mResolver = context.getContentResolver();
        mHandler = handler;
        mAlarms = context.getSystemService(AlarmManager.class);
        mConnectivity = context.getSystemService(ConnectivityManager.class);
        mOnChanged = onChanged;
        mConnectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                requestSchedule();
            }

            @Override
            public void onLost(Network network) {
                requestSchedule();
            }
        }, mHandler);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                requestSchedule();
            }
        }, new IntentFilter(Intent.ACTION_TIME_CHANGED), null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
        mWorker.execute(this::load);
    }

    private synchronized void load() {
        try {
            String state = Settings.Secure.getStringForUser(mResolver, AUTO_STATE_KEY,
                    UserHandle.USER_SYSTEM);
            if (state != null) {
                JSONObject saved = new JSONObject(state);
                mNextAttempt = Math.min(saved.getLong("next_attempt"),
                        System.currentTimeMillis() + MAX_RETRY_MS);
                mFailures = Math.max(0, Math.min(5, saved.getInt("failures")));
                mAutomaticPaused = saved.getBoolean("paused");
                mFailure = saved.getString("error");
                mDetail = saved.getInt("detail");
                if (mAutomaticPaused && (mFailure.equals("hal_unsupported")
                        || mFailure.equals("hal_rejected")) && DuckRkpNative.available()) {
                    mAutomaticPaused = false;
                    mFailure = "";
                    mFailures = 0;
                    mNextAttempt = 0;
                }
            }
        } catch (Exception e) {
            mAutomaticPaused = true;
            mFailure = "settings_unavailable";
            Slog.w(TAG, "Cannot read automatic provisioning state");
        }
        try {
            if (mFile.exists()) {
                String xml;
                try (InputStream input = mFile.openRead()) {
                    byte[] data = input.readNBytes(DuckRkpCbor.LIMIT + 1);
                    if (data.length > DuckRkpCbor.LIMIT) throw new IllegalArgumentException();
                    xml = new String(data, StandardCharsets.UTF_8);
                }
                Validity validity = validateGenerated(xml);
                mExpiresAt = validity.expiresAt();
                mRenewAt = validity.renewAt();
                mGenerated = xml;
            }
        } catch (Exception e) {
            mFailure = "stored_key_invalid";
            Slog.w(TAG, "Stored RKP keybox is unusable");
        } finally {
            mLoaded = true;
            onSettingsChanged(false);
        }
    }

    public synchronized Bundle getState() {
        Bundle state = new Bundle();
        boolean imported = hasImportedKeybox();
        boolean enabled = isEnabled();
        state.putBoolean("available", mLoaded && isQualcomm()
                && (ServiceManager.isDeclared(DuckRkpV1.SERVICE) || DuckRkpNative.available()));
        state.putBoolean("enabled", enabled);
        state.putBoolean("busy", mBusy);
        state.putBoolean("imported", imported);
        state.putBoolean("has_keybox", imported || mGenerated != null);
        state.putBoolean("active", !imported && enabled && masterEnabled()
                && mGenerated != null && mExpiresAt > System.currentTimeMillis());
        state.putLong("expires_at", mExpiresAt);
        state.putLong("renew_at", mRenewAt);
        state.putLong("next_automatic_at", mNextAutomaticAt);
        state.putBoolean("automatic_paused", mAutomaticPaused);
        state.putBoolean("waiting_for_network", mWaitingForNetwork);
        state.putLong("revision", mRevision);
        state.putLong("retry_after_ms", Math.max(0,
                COOLDOWN_MS - (SystemClock.elapsedRealtime() - mLastAttempt)));
        state.putString("stage", mStage);
        state.putString("error", mBusy && SystemClock.elapsedRealtime() - mStartedAt >= TIMEOUT_MS
                ? "timeout" : mFailure);
        state.putInt("detail", mDetail);
        return state;
    }

    public synchronized void setEnabled(boolean enabled) {
        if (enabled && (!mLoaded || hasImportedKeybox() || !isQualcomm())) {
            throw new IllegalStateException("Remove the imported keybox before enabling device RKP");
        }
        putEnabled(enabled);
        cancelProvisioning();
        if (!mAutomaticPaused) mFailure = "";
        saveAutomaticState();
        requestSchedule();
        mOnChanged.run();
    }

    public synchronized void provision() {
        startProvisioning(true);
    }

    private void startProvisioning(boolean manual) {
        if (!mLoaded || !isEnabled() || !masterEnabled() || hasImportedKeybox()) {
            throw new IllegalStateException("Device RKP is disabled");
        }
        long now = SystemClock.elapsedRealtime();
        if (mBusy || now - mLastAttempt < COOLDOWN_MS) {
            throw new IllegalStateException("An RKP request is active or was attempted recently");
        }
        if (manual) mFailures = 0;
        // A system_server restart must not replay a possibly submitted signing request.
        writeAutomaticState("submission_unknown", 0, true, 0);
        mAutomaticPaused = false;
        mNextAutomaticAt = 0;
        mWaitingForNetwork = false;
        long generation = ++mGeneration;
        mStartedAt = now;
        mLastAttempt = now;
        mBusy = true;
        mFailure = "";
        mDetail = 0;
        mStage = "hardware";
        Runnable timeout = () -> {
            synchronized (this) {
                if (mBusy && generation == mGeneration) {
                    mGeneration++;
                    mFailure = "timeout";
                    mAutomaticPaused = true;
                    mNextAttempt = 0;
                    mStage = "idle";
                    saveAutomaticState();
                }
            }
        };
        mHandler.postDelayed(timeout, TIMEOUT_MS);
        mWorker.execute(() -> runProvisioning(generation, timeout));
    }

    private void runProvisioning(long generation, Runnable timeout) {
        try {
            DuckRkpCertificates.Result result = DuckRkpV1.provision(
                    stage -> progress(generation, stage));
            synchronized (this) {
                progress(generation, "saving");
                Validity validity = validateGenerated(result.xml);
                saveGenerated(result.xml);
                mGenerated = result.xml;
                mExpiresAt = validity.expiresAt();
                mRenewAt = validity.renewAt();
                mRevision++;
                mStage = "ready";
                resetRevocation();
                mOnChanged.run();
            }
        } catch (Exception e) {
            synchronized (this) {
                if (generation == mGeneration) {
                    if (e instanceof DuckRkpV1.Failure) {
                        mFailure = ((DuckRkpV1.Failure) e).code;
                        mDetail = ((DuckRkpV1.Failure) e).detail;
                    } else {
                        mFailure = "provision_failed";
                    }
                    mStage = "idle";
                    Slog.w(TAG, "Provisioning ended: " + mFailure + " (" + mDetail + ")");
                }
            }
        } finally {
            mHandler.removeCallbacks(timeout);
            synchronized (this) {
                mBusy = false;
                if (generation == mGeneration) {
                    if (mFailure.isEmpty()) {
                        mFailures = 0;
                        mAutomaticPaused = false;
                        mNextAttempt = System.currentTimeMillis() + COOLDOWN_MS;
                    } else if (isRetryable(mFailure, mDetail)) {
                        mFailures = Math.min(5, mFailures + 1);
                        mAutomaticPaused = false;
                        mNextAttempt = System.currentTimeMillis() + retryDelay(mFailures);
                    } else {
                        mAutomaticPaused = true;
                        mNextAttempt = 0;
                    }
                    saveAutomaticState();
                }
                requestSchedule();
            }
        }
    }

    private void saveGenerated(String xml) throws DuckRkpV1.Failure {
        byte[] encoded = xml.getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = mFile.startWrite();
            if (FileUtils.setPermissions(output.getFD(), 0600, -1, -1) != 0) {
                throw new IllegalStateException("Cannot protect keybox file");
            }
            output.write(encoded);
            output.getFD().sync();
            mFile.finishWrite(output);
            try (InputStream input = mFile.openRead()) {
                if (!Arrays.equals(encoded, input.readNBytes(encoded.length + 1))) {
                    throw new IllegalStateException("Saved keybox differs from generated keybox");
                }
            }
        } catch (Exception e) {
            mFile.failWrite(output);
            throw new DuckRkpV1.Failure("save_failed");
        }
    }

    private synchronized void progress(long generation, String stage) throws DuckRkpV1.Failure {
        if (generation != mGeneration || !isEnabled() || !masterEnabled() || hasImportedKeybox()) {
            throw new DuckRkpV1.Failure("canceled");
        }
        if (SystemClock.elapsedRealtime() - mStartedAt >= TIMEOUT_MS) {
            throw new DuckRkpV1.Failure("timeout");
        }
        mStage = stage;
    }

    public synchronized void setImportedKeybox(String payload) {
        if (payload == null) throw new IllegalArgumentException("Missing keybox");
        if (!payload.isEmpty()) {
            if (payload.length() > DuckRkpCbor.LIMIT) {
                throw new IllegalArgumentException("Keybox is too large");
            }
            String xml;
            try {
                xml = new String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Keybox is not Base64 encoded");
            }
            KeyBoxManager manager = new KeyBoxManager();
            if (xml.contains("<!DOCTYPE") || TrickyStoreService.keyboxProblem(xml) != null
                    || !manager.parseKeybox(xml) || !manager.hasKeyboxes()) {
                throw new IllegalArgumentException("Keybox contains no usable signing key");
            }
        }
        putEnabled(false);
        mGeneration++;
        if (!Settings.Secure.putStringForUser(mResolver, Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
                payload, UserHandle.USER_SYSTEM)) {
            throw new IllegalStateException("Cannot save imported keybox");
        }
        Settings.Secure.putStringForUser(mResolver, SOURCE_KEY,
                payload.isEmpty() ? "" : "user", UserHandle.USER_SYSTEM);
        mFile.delete();
        mGenerated = null;
        mExpiresAt = 0;
        mRenewAt = 0;
        mNextAttempt = 0;
        mFailures = 0;
        mAutomaticPaused = false;
        mRevision++;
        mFailure = "";
        mStage = "idle";
        resetRevocation();
        saveAutomaticState();
        requestSchedule();
        mOnChanged.run();
    }

    public synchronized void onSettingsChanged(boolean masterChanged) {
        try {
            if (hasImportedKeybox() && isEnabled()) putEnabled(false);
            if (masterChanged || !isEnabled() || !masterEnabled() || hasImportedKeybox()) {
                cancelProvisioning();
            }
        } catch (RuntimeException e) {
            mGeneration++;
            mStage = "idle";
            mAutomaticPaused = true;
            mFailure = "settings_unavailable";
            Slog.w(TAG, "Cannot read provisioning settings");
        }
        requestSchedule();
    }

    private void cancelProvisioning() {
        mGeneration++;
        mStage = "idle";
        if (mBusy) {
            mFailure = "canceled";
            mAutomaticPaused = true;
            mNextAttempt = 0;
            saveAutomaticState();
        }
    }

    private void requestSchedule() {
        mHandler.removeCallbacks(mReschedule);
        mHandler.post(mReschedule);
    }

    private synchronized void scheduleAutomatic() {
        mAlarms.cancel(mAutomatic);
        mNextAutomaticAt = 0;
        mWaitingForNetwork = false;
        try {
            if (!mLoaded || mBusy || mAutomaticPaused || !isEnabled() || !masterEnabled()
                    || hasImportedKeybox() || !isQualcomm()
                    || !(ServiceManager.isDeclared(DuckRkpV1.SERVICE)
                            || DuckRkpNative.available())) return;
            long now = System.currentTimeMillis();
            long cooldown = Math.max(0,
                    COOLDOWN_MS - (SystemClock.elapsedRealtime() - mLastAttempt));
            long due = Math.max(Math.max(mGenerated == null ? now : mRenewAt, mNextAttempt),
                    now + cooldown);
            if (due <= now) {
                NetworkCapabilities capabilities = mConnectivity.getNetworkCapabilities(
                        mConnectivity.getActiveNetwork());
                if (capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    startProvisioning(false);
                    return;
                }
                mWaitingForNetwork = true;
                due = now + NETWORK_CHECK_MS;
            }
            mNextAutomaticAt = due;
            mAlarms.setWindow(AlarmManager.RTC, due, ALARM_WINDOW_MS,
                    TAG, mAutomatic, mHandler);
        } catch (RuntimeException e) {
            mAutomaticPaused = true;
            mFailure = "settings_unavailable";
            Slog.w(TAG, "Cannot schedule automatic provisioning");
        }
    }

    private void writeAutomaticState(String error, int detail, boolean paused, long nextAttempt) {
        try {
            String state = new JSONObject().put("error", error).put("detail", detail)
                    .put("paused", paused).put("next_attempt", nextAttempt)
                    .put("failures", mFailures).toString();
            if (!Settings.Secure.putStringForUser(mResolver, AUTO_STATE_KEY, state,
                    UserHandle.USER_SYSTEM)) {
                throw new IllegalStateException("Cannot save automatic provisioning state");
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot encode automatic provisioning state", e);
        }
    }

    private void saveAutomaticState() {
        try {
            writeAutomaticState(mFailure, mDetail, mAutomaticPaused, mNextAttempt);
        } catch (RuntimeException e) {
            mAutomaticPaused = true;
            mFailure = "settings_unavailable";
            Slog.w(TAG, "Cannot save automatic provisioning state");
        }
    }

    static long renewalTime(long notBefore, long expiresAt) {
        long lifetime = Math.max(0, expiresAt - notBefore);
        return expiresAt - Math.min(TimeUnit.HOURS.toMillis(24), lifetime / 5);
    }

    static long retryDelay(int failures) {
        return Math.min(MAX_RETRY_MS, RETRY_MS * (1L << Math.min(4, Math.max(0, failures - 1))));
    }

    static boolean isRetryable(String error, int detail) {
        return "network_error".equals(error) || "hal_unavailable".equals(error)
                || ("http_error".equals(error)
                    && (detail == 408 || detail == 429 || (detail >= 500 && detail <= 599)));
    }

    public synchronized String getKeybox() {
        if (!masterEnabled()) return null;
        String stored = importedKeybox();
        if (stored != null && !stored.isEmpty() && (!isEnabled() || hasImportedKeybox())) {
            return stored;
        }
        if (!isEnabled() || mExpiresAt <= System.currentTimeMillis()) return null;
        return mGenerated;
    }

    private String importedKeybox() {
        return Settings.Secure.getStringForUser(mResolver,
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, UserHandle.USER_SYSTEM);
    }

    /**
     * Only a keybox the user imported blocks device RKP. A keybox without the user source
     * was fetched in the background (AxSpoofManager) and is replaced, not protected.
     */
    private boolean hasImportedKeybox() {
        String value = importedKeybox();
        return value != null && !value.isEmpty() && "user".equals(
                Settings.Secure.getStringForUser(mResolver, SOURCE_KEY, UserHandle.USER_SYSTEM));
    }

    private boolean isEnabled() {
        return Settings.Secure.getIntForUser(mResolver, Settings.Secure.SPOOF_DUCK_RKP_ENABLED,
                0, UserHandle.USER_SYSTEM) != 0;
    }

    private boolean masterEnabled() {
        return Settings.System.getIntForUser(mResolver, MASTER_KEY, 1, UserHandle.USER_SYSTEM) != 0;
    }

    private void putEnabled(boolean enabled) {
        if (!Settings.Secure.putIntForUser(mResolver, Settings.Secure.SPOOF_DUCK_RKP_ENABLED,
                enabled ? 1 : 0, UserHandle.USER_SYSTEM)) {
            throw new IllegalStateException("Cannot update device RKP setting");
        }
    }

    private static boolean isQualcomm() {
        return "Qualcomm".equalsIgnoreCase(Build.SOC_MANUFACTURER)
                || "QTI".equalsIgnoreCase(Build.SOC_MANUFACTURER)
                || "qcom".equalsIgnoreCase(Build.SOC_MANUFACTURER);
    }

    private void resetRevocation() {
        for (String suffix : new String[] {"last_revocation_status", "last_revocation_reason",
                "cached_revoked_serials"}) {
            Settings.Secure.putStringForUser(mResolver, "spoof_trickystore_" + suffix,
                    "", UserHandle.USER_SYSTEM);
        }
        Settings.Secure.putLongForUser(mResolver, "spoof_trickystore_last_revocation_check",
                0, UserHandle.USER_SYSTEM);
    }

    private record Validity(long expiresAt, long renewAt) {}

    private static Validity validateGenerated(String xml) throws Exception {
        KeyBoxManager manager = new KeyBoxManager();
        if (!manager.parseKeybox(xml)) throw new IllegalArgumentException("Invalid stored keybox");
        KeyBoxManager.KeyBox keybox = manager.getKeybox("EC");
        if (keybox == null) throw new IllegalArgumentException("Missing EC signing key");
        Signature proof = Signature.getInstance("SHA256withECDSA");
        byte[] challenge = "TrickyStore key match".getBytes(StandardCharsets.UTF_8);
        proof.initSign(keybox.keyPair.getPrivate());
        proof.update(challenge);
        byte[] signature = proof.sign();
        proof.initVerify(keybox.keyPair.getPublic());
        proof.update(challenge);
        if (!proof.verify(signature)) throw new IllegalArgumentException("Key mismatch");
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : keybox.certificates) {
            certificates.add((X509Certificate) certificate);
        }
        for (int i = 0; i + 1 < certificates.size(); i++) {
            X509Certificate child = certificates.get(i);
            X509Certificate issuer = certificates.get(i + 1);
            if (!child.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                throw new IllegalArgumentException("Certificate issuer mismatch");
            }
            child.verify(issuer.getPublicKey());
        }
        long expiresAt = DuckRkpCertificates.validate(certificates);
        long notBefore = 0;
        for (X509Certificate certificate : certificates) {
            notBefore = Math.max(notBefore, certificate.getNotBefore().getTime());
        }
        return new Validity(expiresAt, renewalTime(notBefore, expiresAt));
    }
}
