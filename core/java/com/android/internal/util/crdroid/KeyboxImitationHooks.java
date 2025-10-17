/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.crdroid;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyParameterValue;
import android.hardware.security.keymint.Tag;
import android.os.Binder;
import android.security.KeyChain;
import android.system.keystore2.Authorization;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import com.android.internal.util.crdroid.KeyboxChainGenerator.KeyGenParameters;
import com.android.internal.util.crdroid.KeyboxUtils;

import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @hide
 */
public class KeyboxImitationHooks {

    private static final String TAG = "KeyboxImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static boolean mSuccess = false;

    private static final ASN1ObjectIdentifier KEY_ATTESTATION_OID = new ASN1ObjectIdentifier(
            "1.3.6.1.4.1.11129.2.1.17");

    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.vending",
            "com.google.android.gms"
    ));

    public static KeyEntryResponse onGetKeyEntry(KeyDescriptor descriptor) {
        if (!KeyProviderManager.isKeyboxAvailable()) {
            return null;
        }

        if (!mSuccess) {
            return null;
        }

        KeyEntryResponse spoofed = KeyboxUtils.retrieve(Binder.getCallingUid(), descriptor.alias);
        if (spoofed != null) {
            dlog("Key entry spoofed");
            return spoofed;
        }

        return null;
    }

    public static KeyEntryResponse fallbackKeyEntry(KeyEntryResponse response) {
        // If no keybox is found, don't continue spoofing
        if (!KeyProviderManager.isKeyboxAvailable()) {
            dlog("Key attestation spoofing is disabled because no keybox is defined to spoof");
            return response;
        }

        if (response == null || response.metadata == null) return response;

        try {
            final Context ctx = ActivityThread.currentApplication();
            if (ctx == null) return response;

            final int uid = Binder.getCallingUid();
            final String[] packages = ctx.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return response;

            if (Arrays.stream(packages)
                    .anyMatch(pkg -> ALLOWED_PACKAGES.contains(pkg))) {
                return response;
            }

            if (response.metadata.certificate == null) {
                Log.e(TAG, "Certificate is null, skipping modification");
                return response;
            }

            X509Certificate certificate = KeyChain.toCertificate(response.metadata.certificate);
            if (certificate.getExtensionValue(KEY_ATTESTATION_OID.getId()) == null) {
                Log.e(TAG, "Key attestation OID not found, skipping modification");
                return response;
            }

            String keyAlgorithm = certificate.getPublicKey().getAlgorithm();
            response.metadata.certificate = modifyLeafCertificate(certificate, keyAlgorithm);
            response.metadata.certificateChain = KeyboxUtils.getCertificateChainBytes(keyAlgorithm);
        } catch (Exception e) {
            Log.e(TAG, "Error in onGetKeyEntry", e);
        }

        return response;
    }

    private static byte[] modifyLeafCertificate(X509Certificate leafCertificate,
            String keyAlgorithm) throws Exception {
        X509CertificateHolder certificateHolder = new X509CertificateHolder(
                leafCertificate.getEncoded());
        Extension keyAttestationExtension = certificateHolder.getExtension(KEY_ATTESTATION_OID);
        ASN1Sequence keyAttestationSequence = ASN1Sequence.getInstance(
                keyAttestationExtension.getExtnValue().getOctets());
        ASN1Encodable[] keyAttestationEncodables = keyAttestationSequence.toArray();
        ASN1Sequence teeEnforcedSequence = (ASN1Sequence) keyAttestationEncodables[7];
        ASN1EncodableVector teeEnforcedVector = new ASN1EncodableVector();

        ASN1Sequence rootOfTrustSequence = null;
        for (ASN1Encodable teeEnforcedEncodable : teeEnforcedSequence) {
            ASN1TaggedObject taggedObject = (ASN1TaggedObject) teeEnforcedEncodable;
            if (taggedObject.getTagNo() == 704) {
                rootOfTrustSequence = (ASN1Sequence) taggedObject.getObject();
                continue;
            }
            teeEnforcedVector.add(teeEnforcedEncodable);
        }

        if (rootOfTrustSequence == null) throw new Exception("Root of trust not found");

        PrivateKey privateKey = KeyboxUtils.getPrivateKey(keyAlgorithm);
        X509CertificateHolder providerCertHolder = KeyboxUtils.getCertificateHolder(keyAlgorithm);

        X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
                providerCertHolder.getSubject(),
                certificateHolder.getSerialNumber(),
                certificateHolder.getNotBefore(),
                certificateHolder.getNotAfter(),
                certificateHolder.getSubject(),
                certificateHolder.getSubjectPublicKeyInfo()
        );

        ContentSigner contentSigner = new JcaContentSignerBuilder(
                leafCertificate.getSigAlgName()).build(privateKey);

        byte[] verifiedBootKey = new byte[32];
        ThreadLocalRandom.current().nextBytes(verifiedBootKey);

        DEROctetString verifiedBootHash = (DEROctetString) rootOfTrustSequence.getObjectAt(3);
        if (verifiedBootHash == null) {
            byte[] randomHash = new byte[32];
            ThreadLocalRandom.current().nextBytes(randomHash);
            verifiedBootHash = new DEROctetString(randomHash);
        }

        ASN1Encodable[] rootOfTrustEncodables = {
                new DEROctetString(verifiedBootKey),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0),
                verifiedBootHash
        };

        ASN1Sequence newRootOfTrustSequence = new DERSequence(rootOfTrustEncodables);
        ASN1TaggedObject rootOfTrustTaggedObject = new DERTaggedObject(704, newRootOfTrustSequence);
        teeEnforcedVector.add(rootOfTrustTaggedObject);

        ASN1Sequence newTeeEnforcedSequence = new DERSequence(teeEnforcedVector);
        keyAttestationEncodables[7] = newTeeEnforcedSequence;
        ASN1Sequence newKeyAttestationSequence = new DERSequence(keyAttestationEncodables);
        ASN1OctetString newKeyAttestationOctetString = new DEROctetString(
                newKeyAttestationSequence);
        Extension newKeyAttestationExtension = new Extension(KEY_ATTESTATION_OID, false,
                newKeyAttestationOctetString);

        certificateBuilder.addExtension(newKeyAttestationExtension);

        for (ASN1ObjectIdentifier extensionOID :
                certificateHolder.getExtensions().getExtensionOIDs()) {
            if (KEY_ATTESTATION_OID.getId().equals(extensionOID.getId())) continue;
            certificateBuilder.addExtension(certificateHolder.getExtension(extensionOID));
        }

        return certificateBuilder.build(contentSigner).getEncoded();
    }

    public static KeyMetadata generateKey(IKeystoreSecurityLevel level, KeyDescriptor descriptor, Collection<KeyParameter> args) {
        if (!KeyProviderManager.isKeyboxAvailable()) {
            return null;
        }

        KeyGenParameters params = new KeyGenParameters(args.toArray(new KeyParameter[args.size()]));

        if (params.attestationChallenge == null) {
            return null;
        }

        if (params.algorithm != Algorithm.EC && params.algorithm != Algorithm.RSA) {
            Log.w(TAG, "Unsupported algorithm: " + params.algorithm);
            return null;
        }

        try {
            final Context ctx = ActivityThread.currentApplication();
            if (ctx == null) return null;

            final int uid = Binder.getCallingUid();
            final String[] packages = ctx.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return null;

            if (!Arrays.stream(packages)
                    .anyMatch(pkg -> ALLOWED_PACKAGES.contains(pkg))) {
                return null;
            }

            List<Certificate> chain = KeyboxChainGenerator.generateCertChain(uid, descriptor, params);
            if (chain == null || chain.isEmpty()) {
                return null;
            }
            KeyEntryResponse response = buildResponse(level, chain, params, descriptor);
            if (response == null) {
                return null;
            }
            KeyboxUtils.append(uid, descriptor.alias, response);
            mSuccess = true;
            return response.metadata;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate key", e);
            return null;
        }
    }

    private static KeyEntryResponse buildResponse(
            IKeystoreSecurityLevel level,
            List<Certificate> chain,
            KeyGenParameters params,
            KeyDescriptor descriptor
    ) {
        try {
            KeyEntryResponse response = new KeyEntryResponse();
            KeyMetadata metadata = new KeyMetadata();
            metadata.keySecurityLevel = params.securityLevel;

            KeyboxUtils.putCertificateChain(metadata, chain.toArray(new Certificate[chain.size()]));

            KeyDescriptor d = new KeyDescriptor();
            d.domain = descriptor.domain;
            d.nspace = descriptor.nspace;
            metadata.key = d;

            List<Authorization> authorizations = new ArrayList<>();
            Authorization a;

            for (Integer i : params.purpose) {
                a = new Authorization();
                a.keyParameter = new KeyParameter();
                a.keyParameter.tag = Tag.PURPOSE;
                a.keyParameter.value = KeyParameterValue.keyPurpose(i);
                a.securityLevel = params.securityLevel;
                authorizations.add(a);
            }

            for (Integer i : params.digest) {
                a = new Authorization();
                a.keyParameter = new KeyParameter();
                a.keyParameter.tag = Tag.DIGEST;
                a.keyParameter.value = KeyParameterValue.digest(i);
                a.securityLevel = params.securityLevel;
                authorizations.add(a);
            }

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.ALGORITHM;
            a.keyParameter.value = KeyParameterValue.algorithm(params.algorithm);
            a.securityLevel = params.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.KEY_SIZE;
            a.keyParameter.value = KeyParameterValue.integer(params.keySize);
            a.securityLevel = params.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.EC_CURVE;
            a.keyParameter.value = KeyParameterValue.ecCurve(params.ecCurve);
            a.securityLevel = params.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.NO_AUTH_REQUIRED;
            a.keyParameter.value = KeyParameterValue.boolValue(true); // TODO: copy
            a.securityLevel = params.securityLevel;
            authorizations.add(a);

            // TODO: ORIGIN, OS_VERSION, OS_PATCHLEVEL, VENDOR_PATCHLEVEL, BOOT_PATCHLEVEL,
            // CREATION_DATETIME, USER_ID

            metadata.authorizations = authorizations.toArray(new Authorization[0]);
            response.metadata = metadata;
            response.iSecurityLevel = level;
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build key entry response", e);
            return null;
        }
    }

    public static void setSuccessFlag(boolean flag) {
        mSuccess = flag;
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
