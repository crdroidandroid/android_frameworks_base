/* SPDX-License-Identifier: MIT
 * CSR protocols adapted from eltavine/Duck-ToolBox
 * 8d6a2470b1033c35946b72bb1f9bc56d8947dd63 (including aviraxp's native V1).
 * Copyright (c) 2026 Eltavine (尉迟栩澜)
 * The corresponding MIT notice is included in frameworks/base/NOTICE.
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

import android.os.ServiceManager;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import vendor.lineage.duckrkp.IDuckRkp;

final class DuckRkpNative {
    private static final int LIMIT = 0xa000;
    private static final String SERVICE = "vendor.lineage.duckrkp.IDuckRkp/default";

    private DuckRkpNative() {}

    static boolean available() {
        return ServiceManager.isDeclared(SERVICE);
    }

    static byte[] signV2(Map publicKey, byte[] challenge) throws Exception {
        byte[] response = request(2, encode(new Map().put(number(4), bytes(challenge))
                .put(number(57), bytes(encode(arrayOf(publicKey))))));
        try {
            DataItem item = decode(response);
            if (item instanceof Map) {
                Map wrapper = map(item);
                require(wrapper.getKeys().size() == 1);
                response = bytes(wrapper.get(number(61)));
            }
            verifyV2(response, publicKey, challenge);
        } catch (Exception e) {
            throw new DuckRkpV1.Failure("invalid_hal_response");
        }
        return response;
    }

    static byte[] signV1(Map publicKey, byte[] challenge, byte[] eek, String fingerprint)
            throws Exception {
        byte[] macKey = new byte[32];
        new SecureRandom().nextBytes(macKey);
        byte[] headers = encode(new Map().put(number(1), number(5)));
        byte[] structure = encode(arrayOf(text("MAC0"), bytes(headers), bytes(new byte[0]),
                bytes(encode(arrayOf(publicKey)))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        byte[] tag = mac.doFinal(structure);
        byte[] payload = encode(new Map().put(number(4), bytes(challenge))
                .put(number(55), number(0)).put(number(56), bytes(macKey))
                .put(number(57), bytes(tag)).put(number(58), bytes(eek)));
        Arrays.fill(macKey, (byte) 0);
        Map response;
        try {
            response = map(decode(request(1, payload)));
            require(response.getKeys().size() == 2);
            return DuckRkpV1.assemble(publicKey, challenge, tag, bytes(response.get(number(59))),
                    bytes(response.get(number(60))), fingerprint);
        } catch (DuckRkpV1.Failure e) {
            throw e;
        } catch (Exception e) {
            throw new DuckRkpV1.Failure("invalid_hal_response");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static byte[] request(int backend, byte[] payload) throws Exception {
        if (payload.length == 0 || payload.length > 0x9000 - 4) {
            throw new DuckRkpV1.Failure("invalid_hal_response");
        }
        IDuckRkp service = IDuckRkp.Stub.asInterface(ServiceManager.checkService(SERVICE));
        if (service == null) throw new DuckRkpV1.Failure("native_unavailable");
        try {
            byte[] reply = service.request(backend, payload);
            if (reply == null || reply.length < 12 || reply.length > LIMIT + 12) {
                throw new DuckRkpV1.Failure("invalid_hal_response");
            }
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(reply));
            int status = input.readInt();
            int detail = input.readInt();
            int size = input.readInt();
            if (status != 0) {
                throw new DuckRkpV1.Failure(status == 5 ? "native_timeout"
                        : status == 4 ? "native_rejected"
                        : status == 2 ? "native_unavailable" : "native_transport", detail);
            }
            if (size <= 0 || size > LIMIT - 8 || size != reply.length - 12) {
                throw new DuckRkpV1.Failure("invalid_hal_response");
            }
            byte[] response = new byte[size];
            input.readFully(response);
            return response;
        } catch (android.os.RemoteException | java.io.IOException e) {
            throw new DuckRkpV1.Failure("native_transport");
        }
    }

    static void verifyV2(byte[] csr, Map expectedKey, byte[] challenge) throws Exception {
        List<DataItem> request = array(decode(csr));
        require(request.size() == 4 && integer(request.get(0)) == 1);
        Map certificates = map(request.get(1));
        for (DataItem issuer : certificates.getKeys()) {
            require(issuer instanceof UnicodeString);
            List<DataItem> chain = array(certificates.get(issuer));
            require(!chain.isEmpty() && chain.size() <= 16);
            for (DataItem certificate : chain) require(bytes(certificate).length > 0);
        }
        List<DataItem> dice = array(request.get(2));
        require(dice.size() >= 2 && dice.size() <= 32);
        Map key = map(dice.get(0));
        for (int i = 1; i < dice.size(); i++) {
            Map claims = map(decode(verifySign1(dice.get(i), key)));
            require(claims.get(number(1)) instanceof UnicodeString
                    && claims.get(number(2)) instanceof UnicodeString);
            require(bytes(claims.get(number(-4670553))).length > 0);
            key = map(decode(bytes(claims.get(number(-4670552)))));
        }
        List<DataItem> signed = array(decode(verifySign1(request.get(3), key)));
        require(signed.size() == 2 && Arrays.equals(bytes(signed.get(0)), challenge));
        List<DataItem> contents = array(decode(bytes(signed.get(1))));
        require(contents.size() == 4 && integer(contents.get(0)) == 3
                && text("keymint").equals(contents.get(1))
                && !map(contents.get(2)).getKeys().isEmpty());
        List<DataItem> keys = array(contents.get(3));
        require(keys.size() == 1 && expectedKey.equals(map(keys.get(0))));
    }

    private static byte[] verifySign1(DataItem item, Map key) throws Exception {
        List<DataItem> sign1 = array(item);
        require(sign1.size() == 4);
        byte[] protectedBytes = bytes(sign1.get(0));
        Map headers = map(decode(protectedBytes));
        Map unprotected = map(sign1.get(1));
        require(!headers.getKeys().contains(number(2))
                && !unprotected.getKeys().contains(number(2))
                && !unprotected.getKeys().contains(number(1)));
        for (DataItem label : headers.getKeys()) require(!unprotected.getKeys().contains(label));
        int algorithm = integer(key.get(number(3)));
        require(integer(headers.get(number(1))) == algorithm);
        byte[] payload = bytes(sign1.get(2));
        byte[] signature = bytes(sign1.get(3));
        PublicKey publicKey = publicKey(key);
        String name = algorithm == -8 ? "Ed25519"
                : algorithm == -7 ? "SHA256withECDSA" : "SHA384withECDSA";
        Signature verifier = Signature.getInstance(name);
        verifier.initVerify(publicKey);
        verifier.update(encode(arrayOf(text("Signature1"), bytes(protectedBytes),
                bytes(new byte[0]), bytes(payload))));
        require(verifier.verify(algorithm == -8 ? signature : derSignature(signature,
                algorithm == -7 ? 32 : 48)));
        return payload;
    }

    private static PublicKey publicKey(Map key) throws Exception {
        int type = integer(key.get(number(1)));
        int algorithm = integer(key.get(number(3)));
        int curve = integer(key.get(number(-1)));
        byte[] x = bytes(key.get(number(-2)));
        if (type == 1 && algorithm == -8 && curve == 6) {
            require(x.length == 32 && !key.getKeys().contains(number(-3)));
            byte[] spki = new byte[44];
            byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
                    0x03, 0x21, 0x00};
            System.arraycopy(prefix, 0, spki, 0, prefix.length);
            System.arraycopy(x, 0, spki, prefix.length, x.length);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        }
        require(type == 2 && ((algorithm == -7 && curve == 1 && x.length == 32)
                || (algorithm == -35 && curve == 2 && x.length == 48)));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curve == 1 ? "secp256r1" : "secp384r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger prime = ((java.security.spec.ECFieldFp) spec.getCurve().getField()).getP();
        BigInteger px = new BigInteger(1, x);
        BigInteger rhs = px.pow(3).add(spec.getCurve().getA().multiply(px))
                .add(spec.getCurve().getB()).mod(prime);
        DataItem encodedY = key.get(number(-3));
        BigInteger py;
        if (SimpleValue.TRUE.equals(encodedY) || SimpleValue.FALSE.equals(encodedY)) {
            py = rhs.modPow(prime.add(BigInteger.ONE).shiftRight(2), prime);
            if (py.testBit(0) != SimpleValue.TRUE.equals(encodedY)) py = prime.subtract(py);
        } else {
            byte[] y = bytes(encodedY);
            require(y.length == x.length);
            py = new BigInteger(1, y);
        }
        require(px.compareTo(prime) < 0 && py.compareTo(prime) < 0
                && py.pow(2).mod(prime).equals(rhs));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(px, py), spec));
    }

    private static byte[] derSignature(byte[] raw, int width) throws Exception {
        require(raw.length == width * 2);
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, width)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, width, raw.length)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (der.length - 2);
        der[2] = 2;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 2;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    private static void require(boolean condition) throws Exception {
        if (!condition) throw new java.io.IOException("Invalid native CSR");
    }
}
