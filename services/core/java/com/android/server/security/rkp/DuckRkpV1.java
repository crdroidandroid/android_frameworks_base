/* SPDX-License-Identifier: MIT
 * Adapted from Duck-ToolBox and rkp-v1-oracle b28480c98c1256753aed140c6ff86d0336f55c45.
 * Copyright (c) 2026 Eltavine (尉迟栩澜)
 * Copyright (c) 2026
 * The corresponding MIT notices are included in frameworks/base/NOTICE.
 */
package com.android.server.security.rkp;

import static com.android.server.security.rkp.DuckRkpCbor.array;
import static com.android.server.security.rkp.DuckRkpCbor.arrayOf;
import static com.android.server.security.rkp.DuckRkpCbor.bytes;
import static com.android.server.security.rkp.DuckRkpCbor.decode;
import static com.android.server.security.rkp.DuckRkpCbor.encode;
import static com.android.server.security.rkp.DuckRkpCbor.integer;
import static com.android.server.security.rkp.DuckRkpCbor.map;
import static com.android.server.security.rkp.DuckRkpCbor.number;
import static com.android.server.security.rkp.DuckRkpCbor.text;

import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.RpcHardwareInfo;
import android.os.Build;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;

final class DuckRkpV1 {
    static final String SERVICE = IRemotelyProvisionedComponent.DESCRIPTOR + "/default";
    private static final String ENDPOINT = "https://remoteprovisioning.googleapis.com/v1";
    private static final int HAL_LIMIT = 0xa000;

    interface Progress {
        void update(String stage) throws Failure;
    }

    static final class Failure extends Exception {
        final String code;
        final int detail;

        Failure(String code) {
            this(code, 0);
        }

        Failure(String code, int detail) {
            super(code);
            this.code = code;
            this.detail = detail;
        }
    }

    private DuckRkpV1() {}

    static DuckRkpCertificates.Result provision(Progress progress) throws Exception {
        progress.update("hardware");
        IRemotelyProvisionedComponent component = IRemotelyProvisionedComponent.Stub.asInterface(
                ServiceManager.checkService(SERVICE));
        if (component == null) throw new Failure("hal_unavailable");
        RpcHardwareInfo info = component.getHardwareInfo();
        int curve = info.supportedEekCurve;
        if (curve != RpcHardwareInfo.CURVE_P256 && curve != RpcHardwareInfo.CURVE_25519) {
            throw new Failure("hal_unsupported");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        Map publicKey = publicKey((ECPublicKey) key.getPublic());
        MacedPublicKey macedKey = new MacedPublicKey();
        macedKey.macedKey = macedPublicKey(publicKey);

        progress.update("fetching");
        byte[] request = encode(new Map().put(text("fingerprint"), text(Build.FINGERPRINT))
                .put(text("id"), number(42)));
        List<DataItem> eekResponse = array(decode(post(ENDPOINT + ":fetchEekChain", request,
                false, progress)));
        if (eekResponse.size() < 2) throw new Failure("invalid_response");
        byte[] challenge = bytes(eekResponse.get(1));
        if (challenge.length == 0 || challenge.length > 64) {
            throw new Failure("invalid_response");
        }
        byte[] eek = null;
        for (DataItem entry : array(eekResponse.get(0))) {
            List<DataItem> pair = array(entry);
            if (pair.size() != 2) throw new Failure("invalid_response");
            if (integer(pair.get(0)) == curve) {
                if (eek != null || array(pair.get(1)).isEmpty()) {
                    throw new Failure("invalid_response");
                }
                eek = encode(pair.get(1));
            }
        }
        if (eek == null || eek.length > HAL_LIMIT) throw new Failure("missing_eek");

        progress.update("hardware");
        DeviceInfo deviceInfo = new DeviceInfo();
        ProtectedData protectedData = new ProtectedData();
        byte[] tag;
        try {
            tag = component.generateCertificateRequest(false, new MacedPublicKey[] {macedKey},
                    eek, challenge, deviceInfo, protectedData);
        } catch (ServiceSpecificException e) {
            throw new Failure("hal_rejected", e.errorCode);
        }
        byte[] csr = assemble(publicKey, challenge, tag, deviceInfo.deviceInfo,
                protectedData.protectedData, Build.FINGERPRINT);
        progress.update("signing");
        String url = ENDPOINT + ":signCertificates?challenge="
                + Base64.encodeToString(challenge, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING)
                + "&request_id=" + UUID.randomUUID();
        byte[] response = post(url, csr, true, progress);
        progress.update("validating");
        try {
            return DuckRkpCertificates.verify(key, response);
        } catch (Exception e) {
            throw new Failure("certificate_invalid");
        }
    }

    static Map publicKey(ECPublicKey publicKey) throws Exception {
        return new Map().put(number(1), number(2)).put(number(3), number(-7))
                .put(number(-1), number(1))
                .put(number(-2), bytes(DuckRkpCbor.coordinate(publicKey.getW().getAffineX())))
                .put(number(-3), bytes(DuckRkpCbor.coordinate(publicKey.getW().getAffineY())));
    }

    static byte[] macedPublicKey(Map publicKey) throws Exception {
        byte[] protectedHeaders = encode(new Map().put(number(1), number(5)));
        byte[] payload = encode(publicKey);
        byte[] prk = hmac(new byte[32], new byte[32]);
        byte[] info = "Key to MAC public keys\u0001".getBytes(StandardCharsets.UTF_8);
        byte[] macKey = hmac(prk, info);
        byte[] tag = hmac(macKey, encode(arrayOf(text("MAC0"), bytes(protectedHeaders),
                bytes(new byte[0]), bytes(payload))));
        return encode(arrayOf(bytes(protectedHeaders), new Map(), bytes(payload), bytes(tag)));
    }

    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    static byte[] assemble(Map publicKey, byte[] challenge, byte[] tag, byte[] device,
            byte[] encrypted, String fingerprint) throws Exception {
        if (tag == null || tag.length != 32 || device == null || encrypted == null
                || device.length > HAL_LIMIT || encrypted.length > HAL_LIMIT) {
            throw new Failure("invalid_hal_response");
        }
        if (map(decode(device)).getKeys().isEmpty()) throw new Failure("invalid_hal_response");
        List<DataItem> cose = array(decode(encrypted));
        if (cose.size() != 4
                || integer(map(decode(bytes(cose.get(0)))).get(number(1))) != 3
                || bytes(map(cose.get(1)).get(number(5))).length != 12
                || bytes(cose.get(2)).length < 16 || array(cose.get(3)).size() != 1) {
            throw new Failure("invalid_hal_response");
        }
        List<DataItem> recipient = array(array(cose.get(3)).get(0));
        if (recipient.size() != 3 || !SimpleValue.NULL.equals(recipient.get(2))) {
            throw new Failure("invalid_hal_response");
        }
        byte[] recipientHeaders = bytes(recipient.get(0));
        if (recipientHeaders.length > 0) map(decode(recipientHeaders));
        map(recipient.get(1));
        byte[] headers = encode(new Map().put(number(1), number(5)));
        byte[] macedKeys = encode(arrayOf(bytes(headers), new Map(), bytes(encode(arrayOf(publicKey))),
                bytes(tag)));
        ByteArrayOutputStream csr = new ByteArrayOutputStream();
        csr.write(0x84);
        csr.write(0x82);
        csr.write(device);
        csr.write(encode(new Map().put(text("fingerprint"), text(fingerprint))));
        csr.write(encode(bytes(challenge)));
        csr.write(encrypted);
        csr.write(macedKeys);
        if (csr.size() > DuckRkpCbor.LIMIT) throw new Failure("invalid_hal_response");
        return csr.toByteArray();
    }

    private static byte[] post(String url, byte[] request, boolean signing, Progress progress)
            throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/cbor");
            connection.setRequestProperty("Accept", "application/cbor");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(request.length);
            progress.update(signing ? "signing" : "fetching");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            int code = connection.getResponseCode();
            if (code != 200) throw new Failure("http_error", code);
            if (connection.getContentLengthLong() > DuckRkpCbor.LIMIT) {
                throw new Failure(signing ? "submission_unknown" : "invalid_response");
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    progress.update(signing ? "signing" : "fetching");
                    if (count > DuckRkpCbor.LIMIT - output.size()) {
                        throw new Failure(signing ? "submission_unknown" : "invalid_response");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            throw new Failure(signing ? "submission_unknown" : "network_error");
        } finally {
            connection.disconnect();
        }
    }
}
