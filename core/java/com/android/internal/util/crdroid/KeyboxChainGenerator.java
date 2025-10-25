/*
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.crdroid;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyOrigin;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.Tag;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyDescriptor;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERSet;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.KeyUsage;
import com.android.internal.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.internal.org.bouncycastle.asn1.x509.Time;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * @hide
 */
public final class KeyboxChainGenerator {

    private static final String TAG = "KeyboxChainGenerator";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX = 0;
    private static final int ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX = 1;
    private static final int ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX = 0;
    private static final int ATTESTATION_PACKAGE_INFO_VERSION_INDEX = 1;

    public static List<Certificate> generateCertChain(int uid, KeyDescriptor descriptor, KeyGenParameters params) {
        dlog("Requested KeyPair with alias: " + descriptor.alias);
        int size = params.keySize;
        KeyPair kp;
        try {
            if (Objects.equals(params.algorithm, Algorithm.EC)) {
                dlog("Generating EC keypair of size " + size);
                kp = buildECKeyPair(params);
            } else if (Objects.equals(params.algorithm, Algorithm.RSA)) {
                dlog("Generating RSA keypair of size " + size);
                kp = buildRSAKeyPair(params);
            } else {
                dlog("Unsupported algorithm");
                return null;
            }

            X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                    KeyboxUtils.getCertificateHolder(
                            Objects.equals(params.algorithm, Algorithm.EC)
                                    ? KeyProperties.KEY_ALGORITHM_EC
                                    : KeyProperties.KEY_ALGORITHM_RSA
                    ).getSubject(),
                    params.certificateSerial,
                    new Time(params.certificateNotBefore),
                    new Time(params.certificateNotAfter),
                    params.certificateSubject,
                    SubjectPublicKeyInfo.getInstance(
                            ASN1Sequence.getInstance(kp.getPublic().getEncoded())
                    )
            );

            KeyUsage keyUsage = new KeyUsage(KeyUsage.keyCertSign);
            certBuilder.addExtension(Extension.keyUsage, true, keyUsage);
            certBuilder.addExtension(createExtension(params, uid));

            ContentSigner contentSigner;
            if (Objects.equals(params.algorithm, Algorithm.EC)) {
                contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").build(KeyboxUtils.getPrivateKey(KeyProperties.KEY_ALGORITHM_EC));
            } else {
                contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(KeyboxUtils.getPrivateKey(KeyProperties.KEY_ALGORITHM_RSA));
            }
            X509CertificateHolder certHolder = certBuilder.build(contentSigner);
            Certificate leaf = KeyboxUtils.getCertificateFromHolder(certHolder);
            List<Certificate> chain = KeyboxUtils.getCertificateChain(leaf.getPublicKey().getAlgorithm());
            chain.add(0, leaf);
            dlog("Successfully generated X500 Cert for alias: " + descriptor.alias);
            return chain;
        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
        }
        return null;
    }

    private static ASN1Encodable[] fromIntList(List<Integer> list) {
        ASN1Encodable[] result = new ASN1Encodable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = new ASN1Integer(list.get(i));
        }
        return result;
    }

    private static Extension createExtension(KeyGenParameters params, int uid) {
        try {
            Context context = ActivityThread.currentApplication();
            if (context == null) {
                Log.e(TAG, "Context is null in createExtension");
                return null;
            }
            SecureRandom secureRandom = new SecureRandom();
            
            String key = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.VBOOT_KEY);
            byte[] verifiedBootKey;
            if (key == null) {
                byte[] randomBytes = new byte[32];
                secureRandom.nextBytes(randomBytes);
                String encoded = Base64.encodeToString(randomBytes, Base64.NO_WRAP);
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.VBOOT_KEY, encoded);
                verifiedBootKey = randomBytes;
            } else {
                verifiedBootKey = Base64.decode(key, Base64.NO_WRAP);
            }

            String hash = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.VBOOT_HASH);
            byte[] verifiedBootHash;
            if (hash == null) {
                byte[] randomBytes = new byte[32];
                secureRandom.nextBytes(randomBytes);
                String encoded = Base64.encodeToString(randomBytes, Base64.NO_WRAP);
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.VBOOT_HASH, encoded);
                verifiedBootHash = randomBytes;
            } else {
                verifiedBootHash = Base64.decode(hash, Base64.NO_WRAP);
            }

            ASN1Encodable[] rootOfTrustEncodables = {
                    new DEROctetString(verifiedBootKey),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(verifiedBootHash)
            };

            ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEncodables);

            var Apurpose = new DERSet(fromIntList(params.purpose));
            var Aalgorithm = new ASN1Integer(params.algorithm);
            var AkeySize = new ASN1Integer(params.keySize);
            var Adigest = new DERSet(fromIntList(params.digest));
            var AecCurve = new ASN1Integer(params.ecCurve);
            var AnoAuthRequired = DERNull.INSTANCE;
            var Aorigin = new ASN1Integer(0);

            // To be loaded
            var AosVersion = new ASN1Integer(getOsVersion());
            var AosPatchLevel = new ASN1Integer(getPatchLevel());
            var AbootPatchlevel = new ASN1Integer(getPatchLevelLong());
            var AvendorPatchLevel = new ASN1Integer(getPatchLevelLong());

            var purpose = new DERTaggedObject(true, 1, Apurpose);
            var algorithm = new DERTaggedObject(true, 2, Aalgorithm);
            var keySize = new DERTaggedObject(true, 3, AkeySize);
            var digest = new DERTaggedObject(true, 5, Adigest);
            var ecCurve = new DERTaggedObject(true, 10, AecCurve);
            var noAuthRequired = new DERTaggedObject(true, 503, AnoAuthRequired);
            var origin = new DERTaggedObject(true, 702, Aorigin);
            var rootOfTrust = new DERTaggedObject(true, 704, rootOfTrustSeq);
            var osVersion = new DERTaggedObject(true, 705, AosVersion);
            var osPatchLevel = new DERTaggedObject(true, 706, AosPatchLevel);
            var vendorPatchLevel = new DERTaggedObject(true, 718, AvendorPatchLevel);
            var bootPatchLevel = new DERTaggedObject(true, 719, AbootPatchlevel);

            ASN1Encodable[] teeEnforcedEncodables;

            // Support device properties attestation
            if (params.brand != null) {
                var Abrand = new DEROctetString(params.brand);
                var Adevice = new DEROctetString(params.device);
                var Aproduct = new DEROctetString(params.product);
                var Amanufacturer = new DEROctetString(params.manufacturer);
                var Amodel = new DEROctetString(params.model);
                var brand = new DERTaggedObject(true, 710, Abrand);
                var device = new DERTaggedObject(true, 711, Adevice);
                var product = new DERTaggedObject(true, 712, Aproduct);
                var manufacturer = new DERTaggedObject(true, 716, Amanufacturer);
                var model = new DERTaggedObject(true, 717, Amodel);

                teeEnforcedEncodables = new ASN1Encodable[]{purpose, algorithm, keySize, digest, ecCurve,
                        noAuthRequired, origin, rootOfTrust, osVersion, osPatchLevel, vendorPatchLevel,
                        bootPatchLevel, brand, device, product, manufacturer, model};
            } else {
                teeEnforcedEncodables = new ASN1Encodable[]{purpose, algorithm, keySize, digest, ecCurve,
                        noAuthRequired, origin, rootOfTrust, osVersion, osPatchLevel, vendorPatchLevel,
                        bootPatchLevel};
            }

            var AcreationDateTime = new ASN1Integer(System.currentTimeMillis());
            var AapplicationID = createApplicationId(uid);

            var creationDateTime = new DERTaggedObject(true, 701, AcreationDateTime);
            var applicationID = new DERTaggedObject(true, 709, AapplicationID);

            ASN1Encodable[] softwareEnforced = {creationDateTime, applicationID};

            ASN1OctetString keyDescriptionOctetStr = getAsn1OctetString(teeEnforcedEncodables, softwareEnforced, params);

            return new Extension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, keyDescriptionOctetStr);
        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
        }
        return null;
    }

    private static int getOsVersion() {
        String release = Build.VERSION.RELEASE;
        int major = 0, minor = 0, patch = 0;

        String[] parts = release.split("\\.");
        if (parts.length > 0) major = Integer.parseInt(parts[0]);
        if (parts.length > 1) minor = Integer.parseInt(parts[1]);
        if (parts.length > 2) patch = Integer.parseInt(parts[2]);

        return major * 10000 + minor * 100 + patch;
    }

    private static int getPatchLevel() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, false);
    }

    private static int getPatchLevelLong() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, true);
    }

    private static int convertPatchLevel(String patchLevel, boolean longFormat) {
        try {
            String[] parts = patchLevel.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            if (longFormat) {
                int day = Integer.parseInt(parts[2]);
                return year * 10000 + month * 100 + day;
            } else {
                return year * 100 + month;
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch level: " + patchLevel, e);
            return 202404;
        }
    }

    private static ASN1OctetString getAsn1OctetString(ASN1Encodable[] teeEnforcedEncodables, ASN1Encodable[] softwareEnforcedEncodables, KeyGenParameters params) throws IOException {
        ASN1Integer attestationVersion = new ASN1Integer(100);
        ASN1Enumerated attestationSecurityLevel = new ASN1Enumerated(1);
        ASN1Integer keymasterVersion = new ASN1Integer(100);
        ASN1Enumerated keymasterSecurityLevel = new ASN1Enumerated(1);
        ASN1OctetString attestationChallenge = new DEROctetString(params.attestationChallenge);
        ASN1OctetString uniqueId = new DEROctetString(new byte[0]);
        ASN1Encodable softwareEnforced = new DERSequence(softwareEnforcedEncodables);
        ASN1Sequence teeEnforced = new DERSequence(teeEnforcedEncodables);

        ASN1Encodable[] keyDescriptionEncodables = {attestationVersion, attestationSecurityLevel, keymasterVersion,
                keymasterSecurityLevel, attestationChallenge, uniqueId, softwareEnforced, teeEnforced};

        ASN1Sequence keyDescriptionHackSeq = new DERSequence(keyDescriptionEncodables);

        return new DEROctetString(keyDescriptionHackSeq);
    }

    private static DEROctetString createApplicationId(int uid) throws Throwable {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            throw new IllegalStateException("createApplicationId: context not available from ActivityThread!");
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            throw new IllegalStateException("createApplicationId: PackageManager not found!");
        }

        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            throw new IllegalStateException("No packages found for UID: " + uid);
        }

        int size = packages.length;
        ASN1Encodable[] packageInfoAA = new ASN1Encodable[size];
        Set<Digest> signatures = new HashSet<>();
        MessageDigest dg = MessageDigest.getInstance("SHA-256");

        for (int i = 0; i < size; i++) {
            String name = packages[i];
            PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
            ASN1Encodable[] arr = new ASN1Encodable[2];
            arr[ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX] =
                    new DEROctetString(name.getBytes(StandardCharsets.UTF_8));
            arr[ATTESTATION_PACKAGE_INFO_VERSION_INDEX] =
                    new ASN1Integer(info.getLongVersionCode());
            packageInfoAA[i] = new DERSequence(arr);

            if (info != null && info.signatures != null) {
                for (Signature s : info.signatures) {
                    if (s != null) signatures.add(new Digest(dg.digest(s.toByteArray())));
                }
            }
        }

        ASN1Encodable[] signaturesAA = new ASN1Encodable[signatures.size()];
        int i = 0;
        for (Digest d : signatures) {
            signaturesAA[i++] = new DEROctetString(d.digest);
        }

        ASN1Encodable[] applicationIdAA = new ASN1Encodable[2];
        applicationIdAA[ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX] =
                new DERSet(packageInfoAA);
        applicationIdAA[ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX] =
                new DERSet(signaturesAA);

        return new DEROctetString(new DERSequence(applicationIdAA).getEncoded());
    }

    record Digest(byte[] digest) {
        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof Digest d)
                return Arrays.equals(digest, d.digest);
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(digest);
        }
    }

    private static KeyPair buildECKeyPair(KeyGenParameters params) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
        ECGenParameterSpec spec = new ECGenParameterSpec(params.ecCurveName);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static KeyPair buildRSAKeyPair(KeyGenParameters params) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
        RSAKeyGenParameterSpec spec = new RSAKeyGenParameterSpec(
                params.keySize, params.rsaPublicExponent);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }

    public static class KeyGenParameters {
        public int keySize;
        public int algorithm;
        public BigInteger certificateSerial;
        public Date certificateNotBefore;
        public Date certificateNotAfter;
        public X500Name certificateSubject;

        public BigInteger rsaPublicExponent;
        public int ecCurve;
        public String ecCurveName;

        public List<Integer> purpose = new ArrayList<>();
        public List<Integer> digest = new ArrayList<>();

        public byte[] attestationChallenge;
        public byte[] brand;
        public byte[] device;
        public byte[] product;
        public byte[] manufacturer;
        public byte[] model;

        public int securityLevel;
        public boolean noAuthRequired;

        // Extra fields for response metadata
        public int osVersion = KeyboxChainGenerator.getOsVersion();
        public int osPatchLevel = KeyboxChainGenerator.getPatchLevel();
        public int vendorPatchLevel = KeyboxChainGenerator.getPatchLevelLong();
        public int bootPatchLevel = KeyboxChainGenerator.getPatchLevelLong();
        public long creationDateTime = System.currentTimeMillis();
        public int userId = UserHandle.myUserId();
        public int origin = KeyOrigin.GENERATED;

        public KeyGenParameters(KeyParameter[] params) {
            for (KeyParameter kp : params) {
                switch (kp.tag) {
                    case Tag.KEY_SIZE -> keySize = kp.value.getInteger();
                    case Tag.ALGORITHM -> algorithm = kp.value.getAlgorithm();
                    case Tag.CERTIFICATE_SERIAL -> certificateSerial = new BigInteger(kp.value.getBlob());
                    case Tag.CERTIFICATE_NOT_BEFORE -> certificateNotBefore = new Date(kp.value.getDateTime());
                    case Tag.CERTIFICATE_NOT_AFTER -> certificateNotAfter = new Date(kp.value.getDateTime());
                    case Tag.CERTIFICATE_SUBJECT ->
                            certificateSubject = new X500Name(new X500Principal(kp.value.getBlob()).getName());
                    case Tag.RSA_PUBLIC_EXPONENT -> rsaPublicExponent = BigInteger.valueOf(kp.value.getLongInteger());
                    case Tag.EC_CURVE -> {
                        ecCurve = kp.value.getEcCurve();
                        ecCurveName = getEcCurveName(ecCurve);
                    }
                    case Tag.PURPOSE -> purpose.add(kp.value.getKeyPurpose());
                    case Tag.DIGEST -> digest.add(kp.value.getDigest());
                    case Tag.ATTESTATION_CHALLENGE -> attestationChallenge = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_BRAND -> brand = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_DEVICE -> device = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_PRODUCT -> product = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_MANUFACTURER -> manufacturer = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_MODEL -> model = kp.value.getBlob();
                    case Tag.HARDWARE_TYPE -> securityLevel = kp.value.getSecurityLevel();
                    case Tag.NO_AUTH_REQUIRED -> noAuthRequired = kp.value.getBoolValue();
                }
            }
        }

        private static String getEcCurveName(int curve) {
            return switch (curve) {
                case EcCurve.CURVE_25519 -> "CURVE_25519";
                case EcCurve.P_224 -> "secp224r1";
                case EcCurve.P_256 -> "secp256r1";
                case EcCurve.P_384 -> "secp384r1";
                case EcCurve.P_521 -> "secp521r1";
                default -> throw new IllegalArgumentException("unknown curve");
            };
        }
    }
}
