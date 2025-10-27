/*
 * Copyright (C) 2025 crDroid Android Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package com.android.server.crdroid;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.SystemService;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.server.crdroid.vbmeta.CertificateInfo;

public final class VbmetaHashService extends SystemService {
    private static final String TAG = "VbmetaHashService";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public VbmetaHashService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() { }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mHandler.post(VbmetaHashService.this::maybeComputeAndUpdate);
            Slog.i(TAG, "registered");
        }
    }

    private void maybeComputeAndUpdate() {
        try {
            String hex = computeVerifiedBootHashHex();
            if (hex == null || hex.isEmpty() || "null".equals(hex)) {
                Slog.w(TAG, "No verified boot hash available");
                return;
            }
            setRuntimeProps(hex.toLowerCase());
        } catch (Throwable t) {
            Slog.e(TAG, "vbmeta update failed", t);
        }
    }

    private static void setRuntimeProps(String digestLowerHex) {
        SystemProperties.set("persist.sys.vbmeta.digest", digestLowerHex);
        Slog.i(TAG, "Updated persist.sys.vbmeta.digest to " + digestLowerHex);
    }

    @Nullable
    private String computeVerifiedBootHashHex() throws Exception {
        final String alias = "vbmeta_fw";
        final Date now = new Date();

        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeyValidityStart(now)
                .setAttestationChallenge(now.toString().getBytes());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        try {
            kpg.initialize(builder.build());
            kpg.generateKeyPair();
        } catch (Exception ex) {
            Slog.e(TAG, "TEE likely broken", ex);
            return null;
        }

        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate[] chain = ks.getCertificateChain(alias);
        if (chain == null || chain.length == 0) return null;

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<java.security.cert.X509Certificate> x509s = new ArrayList<>();
        for (Certificate c : chain) {
            x509s.add((java.security.cert.X509Certificate)
                    cf.generateCertificate(new java.io.ByteArrayInputStream(c.getEncoded())));
        }

        var result = CertificateInfo.parseCertificateChain(x509s);
        var rot = result.getRootOfTrust();
        if (rot == null || rot.getVerifiedBootHash() == null) return null;

        return toLowerHex(rot.getVerifiedBootHash());
    }

    private static String toLowerHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }
}
