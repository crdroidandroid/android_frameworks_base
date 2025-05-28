/*
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.crdroid;

import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;

import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import com.android.internal.org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import com.android.internal.org.bouncycastle.asn1.sec.ECPrivateKey;
import com.android.internal.org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class KeyboxUtils {

    private static final ConcurrentHashMap<Key, KeyEntryResponse> response = new ConcurrentHashMap<>();
    public static record Key(int uid, String alias) {}

    public static byte[] decodePemOrBase64(String input) {
        String base64 = input
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(base64);
    }

    public static PrivateKey parsePrivateKey(String encodedKey, String algorithm) throws Exception {
        byte[] keyBytes = decodePemOrBase64(encodedKey);
        ASN1Primitive primitive = ASN1Primitive.fromByteArray(keyBytes);
        if ("EC".equalsIgnoreCase(algorithm)) {
            try {
                // Try parsing as PKCS#8
                PrivateKeyInfo info = PrivateKeyInfo.getInstance(primitive);
                return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
            } catch (Exception e) {
                // Possibly SEC1 / PKCS#1 EC
                ASN1Sequence seq = ASN1Sequence.getInstance(primitive);
                ECPrivateKey ecPrivateKey = ECPrivateKey.getInstance(seq);
                AlgorithmIdentifier algId = new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, ecPrivateKey.getParameters());
                PrivateKeyInfo privInfo = new PrivateKeyInfo(algId, ecPrivateKey);
                PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(privInfo.getEncoded());
                return KeyFactory.getInstance("EC").generatePrivate(pkcs8Spec);
            }
        } else if ("RSA".equalsIgnoreCase(algorithm)) {
            try {
                // Try parsing as PKCS#8
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } catch (Exception e) {
                // Parse as PKCS#1
                RSAPrivateKey rsaKey = RSAPrivateKey.getInstance(primitive);
                AlgorithmIdentifier algId = new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);
                PrivateKeyInfo privInfo = new PrivateKeyInfo(algId, rsaKey);
                PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(privInfo.getEncoded());
                return KeyFactory.getInstance("RSA").generatePrivate(pkcs8Spec);
            }
        } else {
            throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        }
    }

    public static X509Certificate parseCertificate(String encodedCert) throws Exception {
        byte[] certBytes = decodePemOrBase64(encodedCert);
        return (X509Certificate) CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certBytes));
    }

    public static List<Certificate> getCertificateChain(String algorithm) throws Exception {
        IKeyboxProvider provider = KeyProviderManager.getProvider();
        String[] certChainPem = KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)
                ? provider.getEcCertificateChain()
                : provider.getRsaCertificateChain();

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> certs = new ArrayList<>();

        for (String certPem : certChainPem) {
            certs.add(parseCertificate(certPem));
        }

        return certs;
    }

    public static void putCertificateChain(KeyEntryResponse response, Certificate[] chain) throws Exception {
        putCertificateChain(response.metadata, chain);
    }

    public static void putCertificateChain(KeyMetadata metadata, Certificate[] chain) throws Exception {
        metadata.certificate = chain[0].getEncoded();
        var output = new ByteArrayOutputStream();
        for (int i = 1; i < chain.length; i++) {
            output.write(chain[i].getEncoded());
        }
        metadata.certificateChain = output.toByteArray();
    }

    public static X509Certificate getCertificateFromHolder(X509CertificateHolder holder) throws Exception {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream in = new ByteArrayInputStream(holder.getEncoded());
        return (X509Certificate) certFactory.generateCertificate(in);
    }

    public static PrivateKey getPrivateKey(String algorithm) throws Exception {
        IKeyboxProvider provider = KeyProviderManager.getProvider();
        String privateKeyEncoded = KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)
                ? provider.getEcPrivateKey()
                : provider.getRsaPrivateKey();

        return parsePrivateKey(privateKeyEncoded, algorithm);
    }

    public static X509CertificateHolder getCertificateHolder(String algorithm) throws Exception {
        IKeyboxProvider provider = KeyProviderManager.getProvider();
        String certPem = KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)
                ? provider.getEcCertificateChain()[0]
                : provider.getRsaCertificateChain()[0];

        X509Certificate parsedCert = parseCertificate(certPem);
        return new X509CertificateHolder(parsedCert.getEncoded());
    }

    public static void append(int uid, String a, KeyEntryResponse c) {
        response.put(new Key(uid, a), c);
    }

    public static KeyEntryResponse retrieve(int uid, String a) {
        return response.get(new Key(uid, a));
    }
}
