/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.security.rkp;

import static com.android.server.security.rkp.DuckRkpCbor.*;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.List;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import org.junit.Test;

public class DuckRkpNativeTest {
    private static final byte[] CHALLENGE = {1, 2, 3, 4};

    private static KeyPair ec(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    private static byte[] coordinate(BigInteger number, int width) {
        byte[] result = new byte[width];
        byte[] source = number.toByteArray();
        int size = Math.min(width, source.length);
        System.arraycopy(source, source.length - size, result, width - size, size);
        return result;
    }

    private static Map key(KeyPair pair, int algorithm, boolean compressed) throws Exception {
        if (algorithm == -8) {
            byte[] spki = pair.getPublic().getEncoded();
            return new Map().put(number(1), number(1)).put(number(3), number(-8))
                    .put(number(-1), number(6))
                    .put(number(-2), bytes(Arrays.copyOfRange(spki, spki.length - 32, spki.length)));
        }
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        int width = algorithm == -7 ? 32 : 48;
        return new Map().put(number(1), number(2)).put(number(3), number(algorithm))
                .put(number(-1), number(algorithm == -7 ? 1 : 2))
                .put(number(-2), bytes(coordinate(publicKey.getW().getAffineX(), width)))
                .put(number(-3), compressed
                        ? publicKey.getW().getAffineY().testBit(0) ? SimpleValue.TRUE : SimpleValue.FALSE
                        : bytes(coordinate(publicKey.getW().getAffineY(), width)));
    }

    private static DataItem sign(KeyPair key, int algorithm, byte[] payload) throws Exception {
        byte[] headers = encode(new Map().put(number(1), number(algorithm)));
        Signature signer = Signature.getInstance(algorithm == -8 ? "Ed25519"
                : algorithm == -7 ? "SHA256withECDSA" : "SHA384withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(encode(arrayOf(text("Signature1"), bytes(headers), bytes(new byte[0]),
                bytes(payload))));
        byte[] signature = signer.sign();
        if (algorithm != -8) {
            int width = algorithm == -7 ? 32 : 48;
            int rLength = signature[3] & 255;
            int sLength = signature[5 + rLength] & 255;
            byte[] raw = new byte[width * 2];
            int rSize = Math.min(rLength, width), sSize = Math.min(sLength, width);
            System.arraycopy(signature, 4 + rLength - rSize, raw, width - rSize, rSize);
            System.arraycopy(signature, 6 + rLength + sLength - sSize, raw,
                    width * 2 - sSize, sSize);
            signature = raw;
        }
        return arrayOf(bytes(headers), new Map(), bytes(payload), bytes(signature));
    }

    private record Fixture(byte[] csr, Map target) {}

    private static Fixture fixture(int algorithm, boolean compressed) throws Exception {
        KeyPair root = ec("secp256r1");
        KeyPair leaf = algorithm == -8 ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                : ec(algorithm == -7 ? "secp256r1" : "secp384r1");
        Map target = key(ec("secp256r1"), -7, false);
        byte[] claims = encode(new Map().put(number(1), text("root"))
                .put(number(2), text("leaf")).put(number(-4670553), bytes(new byte[]{0x20}))
                .put(number(-4670552), bytes(encode(key(leaf, algorithm, compressed)))));
        byte[] contents = encode(arrayOf(number(3), text("keymint"),
                new Map().put(text("brand"), text("OnePlus")), arrayOf(target)));
        DataItem signed = sign(leaf, algorithm,
                encode(arrayOf(bytes(CHALLENGE), bytes(contents))));
        return new Fixture(encode(arrayOf(number(1), new Map(),
                arrayOf(key(root, -7, false), sign(root, -7, claims)), signed)), target);
    }

    private static void rejects(byte[] csr, Map key, byte[] challenge) throws Exception {
        try {
            DuckRkpNative.verifyV2(csr, key, challenge);
            fail("Accepted invalid or unbound CSR");
        } catch (java.io.IOException | java.security.GeneralSecurityException expected) {
        }
    }

    @Test
    public void verifyMixedDiceAndCsrAlgorithms() throws Exception {
        for (int algorithm : new int[]{-7, -8, -35}) {
            Fixture f = fixture(algorithm, false);
            DuckRkpNative.verifyV2(f.csr, f.target, CHALLENGE);
        }
    }

    @Test
    public void verifyCompressedEcDiceKeys() throws Exception {
        for (int algorithm : new int[]{-7, -35}) {
            Fixture f = fixture(algorithm, true);
            DuckRkpNative.verifyV2(f.csr, f.target, CHALLENGE);
        }
    }

    @Test
    public void rejectWrongChallengeAndKey() throws Exception {
        Fixture f = fixture(-8, false);
        rejects(f.csr, f.target, new byte[]{9});
        rejects(f.csr, key(ec("secp256r1"), -7, false), CHALLENGE);
    }

    @Test
    public void rejectCorruptedCsrAndDiceSignatures() throws Exception {
        Fixture f = fixture(-8, false);
        byte[] corrupted = f.csr.clone();
        corrupted[corrupted.length - 1] ^= 1;
        rejects(corrupted, f.target, CHALLENGE);
        List<DataItem> request = array(decode(f.csr));
        List<DataItem> cert = array(array(request.get(2)).get(1));
        byte[] signature = bytes(cert.get(3));
        signature[0] ^= 1;
        cert.set(3, bytes(signature));
        rejects(encode(arrayOf(request.toArray(new DataItem[0]))), f.target, CHALLENGE);
    }

    @Test
    public void rejectMalformedAndTrailingData() throws Exception {
        Fixture f = fixture(-7, false);
        rejects(encode(arrayOf(number(1))), f.target, CHALLENGE);
        rejects(Arrays.copyOf(f.csr, f.csr.length + 1), f.target, CHALLENGE);
        List<DataItem> request = array(decode(f.csr));
        request.set(0, number(2));
        rejects(encode(arrayOf(request.toArray(new DataItem[0]))), f.target, CHALLENGE);
    }
}
