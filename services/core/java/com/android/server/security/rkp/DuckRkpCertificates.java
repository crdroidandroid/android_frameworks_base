/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.security.rkp;

import static com.android.server.security.rkp.DuckRkpCbor.array;
import static com.android.server.security.rkp.DuckRkpCbor.bytes;
import static com.android.server.security.rkp.DuckRkpCbor.decode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

final class DuckRkpCertificates {
    private static final Set<String> ROOTS = Set.of(
            "1EF1A04B8BA58AB94589AC498C8982A783F24EA7307E0159A0C3A73B377D87CC",
            "CEDB1CB6DC896AE5EC797348BCE9286753C2B38EE71CE0FBE34A9A1248800DFC",
            "6D9DB4CE6C5C0B293166D08986E05774A8776CEB525D9E4329520DE12BA4BCC0");

    static final class Result {
        final String xml;
        final long expiresAt;

        Result(String xml, long expiresAt) {
            this.xml = xml;
            this.expiresAt = expiresAt;
        }
    }

    private DuckRkpCertificates() {}

    static Result verify(KeyPair key, byte[] response) throws Exception {
        List<DataItem> outer = array(decode(response));
        if (outer.size() == 1 && outer.get(0) instanceof Array) outer = array(outer.get(0));
        if (outer.size() != 2) throw new IllegalArgumentException("Invalid certificate response");
        byte[] shared = bytes(outer.get(0));
        List<X509Certificate> chosen = null;
        for (DataItem item : array(outer.get(1))) {
            byte[] unique = bytes(item);
            if (unique.length + shared.length > DuckRkpCbor.LIMIT) {
                throw new IllegalArgumentException("Certificate chain too large");
            }
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            combined.write(unique);
            combined.write(shared);
            List<X509Certificate> certificates = parse(combined.toByteArray());
            for (X509Certificate certificate : certificates) {
                if (Arrays.equals(certificate.getPublicKey().getEncoded(),
                        key.getPublic().getEncoded())) {
                    if (chosen != null) throw new IllegalArgumentException("Ambiguous key match");
                    chosen = order(certificates, certificate);
                }
            }
        }
        if (chosen == null) throw new IllegalArgumentException("No certificate matches the key");
        long expiresAt = validate(chosen);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<AndroidAttestation><NumberOfKeyboxes>1</NumberOfKeyboxes>")
                .append("<Keybox DeviceID=\"DuckRkp\"><Key algorithm=\"ecdsa\">")
                .append("<PrivateKey format=\"pem\">\n")
                .append(pem("PRIVATE KEY", key.getPrivate().getEncoded()))
                .append("</PrivateKey><CertificateChain><NumberOfCertificates>")
                .append(chosen.size()).append("</NumberOfCertificates>");
        for (X509Certificate certificate : chosen) {
            xml.append("<Certificate format=\"pem\">\n")
                    .append(pem("CERTIFICATE", certificate.getEncoded()))
                    .append("</Certificate>");
        }
        xml.append("</CertificateChain></Key></Keybox></AndroidAttestation>\n");
        return new Result(xml.toString(), expiresAt);
    }

    private static List<X509Certificate> parse(byte[] encoded) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> result = new ArrayList<>();
        Set<X509Certificate> unique = new HashSet<>();
        try (ByteArrayInputStream input = new ByteArrayInputStream(encoded)) {
            while (input.available() > 0) {
                int previous = input.available();
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(input);
                if (input.available() >= previous || result.size() >= 16 || !unique.add(certificate)) {
                    throw new IllegalArgumentException("Invalid certificate collection");
                }
                result.add(certificate);
            }
        }
        if (result.size() < 2) throw new IllegalArgumentException("Incomplete certificate chain");
        return result;
    }

    private static List<X509Certificate> order(List<X509Certificate> certificates,
            X509Certificate leaf) throws Exception {
        List<X509Certificate> path = new ArrayList<>();
        X509Certificate current = leaf;
        while (true) {
            if (path.contains(current)) throw new IllegalArgumentException("Certificate cycle");
            path.add(current);
            if (current.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                current.verify(current.getPublicKey());
                break;
            }
            X509Certificate parent = null;
            for (X509Certificate candidate : certificates) {
                if (!current.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    continue;
                }
                try {
                    current.verify(candidate.getPublicKey());
                } catch (Exception e) {
                    continue;
                }
                if (parent != null) throw new IllegalArgumentException("Ambiguous issuer");
                parent = candidate;
            }
            if (parent == null) throw new IllegalArgumentException("Missing issuer");
            current = parent;
        }
        if (path.size() != certificates.size()) {
            throw new IllegalArgumentException("Unrelated certificates");
        }
        return path;
    }

    static long validate(List<X509Certificate> path) throws Exception {
        if (path.size() < 2 || path.size() > 16) {
            throw new IllegalArgumentException("Invalid certificate path size");
        }
        long expiresAt = Long.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            X509Certificate certificate = path.get(i);
            certificate.checkValidity();
            if (certificate.getBasicConstraints() < i) {
                throw new IllegalArgumentException("CA cannot sign an application certificate");
            }
            boolean[] usage = certificate.getKeyUsage();
            if (usage != null && (usage.length <= 5 || !usage[5])) {
                throw new IllegalArgumentException("CA lacks certificate signing usage");
            }
            expiresAt = Math.min(expiresAt, certificate.getNotAfter().getTime());
        }
        X509Certificate root = path.get(path.size() - 1);
        root.verify(root.getPublicKey());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(root.getEncoded());
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) {
            fingerprint.append(Character.toUpperCase(Character.forDigit((value >>> 4) & 15, 16)))
                    .append(Character.toUpperCase(Character.forDigit(value & 15, 16)));
        }
        if (!ROOTS.contains(fingerprint.toString())) {
            throw new IllegalArgumentException("Untrusted attestation root");
        }
        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(
                CertificateFactory.getInstance("X.509").generateCertPath(
                        path.subList(0, path.size() - 1)), parameters);
        return expiresAt;
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
