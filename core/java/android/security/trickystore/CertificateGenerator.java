package android.security.trickystore;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERSet;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.KeyUsage;
import com.android.internal.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * @hide
 */
public final class CertificateGenerator {
    private static final String TAG = "CertificateGenerator";
    private static final int MAX_ATTESTATION_CHALLENGE_SIZE = 128;
    private static final BouncyCastleProvider BC_PROVIDER = new BouncyCastleProvider();

    public static final ASN1ObjectIdentifier ATTESTATION_OID =
        new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

    private CertificateGenerator() {}

    /** @hide */
    public static class KeyGenParameters {
        public int keySize;
        public int algorithm;
        public BigInteger certificateSerial;
        public Date certificateNotBefore;
        public Date certificateNotAfter;
        public X500Principal certificateSubject;
        public BigInteger rsaPublicExponent;
        public int ecCurve;
        public String ecCurveName;
        public List<Integer> purpose = new ArrayList<>();
        public List<Integer> digest = new ArrayList<>();
        public List<Integer> rsaOaepMgfDigest = new ArrayList<>();
        public List<Integer> padding = new ArrayList<>();
        public String[] packages;
        public byte[] attestationChallenge;
        public byte[] brand;
        public byte[] device;
        public byte[] product;
        public byte[] manufacturer;
        public byte[] model;

        public String getEcCurveName() {
            if (ecCurveName != null) return ecCurveName;
            switch (keySize) {
                case 224: return "secp224r1";
                case 256: return "secp256r1";
                case 384: return "secp384r1";
                case 521: return "secp521r1";
                default: return "secp256r1";
            }
        }
    }

    public static KeyPair generateKeyPair(KeyGenParameters params) {
        try {
            KeyPairGenerator kpg;
            if (params.algorithm == 3) {
                kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec(params.getEcCurveName()));
            } else if (params.algorithm == 1) {
                kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(new RSAKeyGenParameterSpec(
                    params.keySize,
                    params.rsaPublicExponent != null ? params.rsaPublicExponent : RSAKeyGenParameterSpec.F4
                ));
            } else {
                Log.e(TAG, "Unsupported algorithm: " + params.algorithm);
                return null;
            }
            return kpg.generateKeyPair();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate key pair", e);
            return null;
        }
    }

    public static List<Certificate> generateCertificateChain(
            KeyPair keyPair,
            KeyGenParameters params,
            int securityLevel,
            int uid) {
        // Validate attestation challenge size (real TEE rejects oversized challenges)
        if (params.attestationChallenge != null &&
            params.attestationChallenge.length > MAX_ATTESTATION_CHALLENGE_SIZE) {
            Log.e(TAG, "Attestation challenge too large: " + params.attestationChallenge.length +
                  " bytes (max " + MAX_ATTESTATION_CHALLENGE_SIZE + ")");
            return null;
        }

        KeyBoxManager keyboxManager = TrickyStoreService.getInstance().getKeyBoxManager();
        String algorithm = params.algorithm == 3 ? "EC" : "RSA";
        KeyBoxManager.KeyBox keybox = keyboxManager.getKeybox(algorithm);

        if (keybox == null) {
            Log.e(TAG, "No keybox found for algorithm: " + algorithm);
            return null;
        }

        try {
            X500Name issuer = keybox.getIssuerSubject();

            X509Certificate leaf = buildCertificate(keyPair, keybox, params, issuer, securityLevel, uid);

            List<Certificate> chain = new ArrayList<>();
            chain.add(leaf);
            chain.addAll(keybox.certificates);
            return chain;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate certificate chain", e);
            return null;
        }
    }

    private static X509Certificate buildCertificate(
            KeyPair keyPair,
            KeyBoxManager.KeyBox keybox,
            KeyGenParameters params,
            X500Name issuer,
            int securityLevel,
            int uid) throws Exception {

        BigInteger serial = params.certificateSerial != null ?
            params.certificateSerial : BigInteger.ONE;
        Date notBefore = params.certificateNotBefore != null ?
            params.certificateNotBefore : new Date();
        Date notAfter = params.certificateNotAfter != null ?
            params.certificateNotAfter :
            ((X509Certificate) keybox.certificates.get(0)).getNotAfter();
        X500Name subject = params.certificateSubject != null ?
            X500Name.getInstance(params.certificateSubject.getEncoded()) : new X500Name("CN=Android Keystore Key");

        SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(
            keyPair.getPublic().getEncoded());

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            subject,
            publicKeyInfo
        );

        int keyUsage = keyUsageFromPurposes(params.purpose);
        if (keyUsage != 0) {
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
        }
        builder.addExtension(buildAttestExtension(params, securityLevel, uid));

        String sigAlg = params.algorithm == 3 ? "SHA256withECDSA" : "SHA256withRSA";
        JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder(sigAlg);
        if (params.algorithm == 3) {
            signerBuilder.setProvider(BC_PROVIDER);
        }
        ContentSigner signer = signerBuilder.build(keybox.keyPair.getPrivate());

        X509CertificateHolder holder = builder.build(signer);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(
            new ByteArrayInputStream(holder.getEncoded()));
    }

    private static Extension buildAttestExtension(KeyGenParameters params, int securityLevel, int uid) {
        // Validate attestation challenge size (real TEE rejects oversized challenges)
        if (params.attestationChallenge != null &&
            params.attestationChallenge.length > MAX_ATTESTATION_CHALLENGE_SIZE) {
            Log.e(TAG, "Attestation challenge too large: " + params.attestationChallenge.length +
                  " bytes (max " + MAX_ATTESTATION_CHALLENGE_SIZE + ")");
            return null;
        }
        try {
            byte[] bootKey = AttestationUtils.getBootKey();
            byte[] bootHash = AttestationUtils.getBootHash();

            ASN1Encodable[] rootOfTrustElements = new ASN1Encodable[] {
                new DEROctetString(bootKey),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0),
                new DEROctetString(bootHash)
            };
            DERSequence rootOfTrust = new DERSequence(rootOfTrustElements);

            // Fetch the patch configuration once, not three times during one leaf.
            final int[] patchLevels = AttestationUtils.getPatchLevels(params.packages);

            // AuthorizationList entries must be emitted in ascending tag order,
            // exactly as KeyMint does, so collect them in a TreeMap first.
            java.util.TreeMap<Integer, ASN1Encodable> tee = new java.util.TreeMap<>();
            if (!params.purpose.isEmpty()) {
                tee.put(1, toIntegerSet(params.purpose));
            }
            tee.put(2, new ASN1Integer(params.algorithm));
            tee.put(3, new ASN1Integer(params.keySize));
            if (!params.digest.isEmpty()) {
                tee.put(5, toIntegerSet(params.digest));
            }
            if (!params.padding.isEmpty()) {
                tee.put(6, toIntegerSet(params.padding));
            }
            if (params.algorithm == 3) {
                // EC_CURVE is only reported for EC keys.
                tee.put(10, new ASN1Integer(params.ecCurve));
            } else if (params.algorithm == 1) {
                tee.put(200, new ASN1Integer(params.rsaPublicExponent != null
                        ? params.rsaPublicExponent : java.security.spec.RSAKeyGenParameterSpec.F4));
                // Tag 203 = RSA_OAEP_MGF_DIGEST (KeyMint 3+)
                if (!params.rsaOaepMgfDigest.isEmpty()) {
                    tee.put(203, toIntegerSet(params.rsaOaepMgfDigest));
                }
            }
            tee.put(503, DERNull.INSTANCE);
            tee.put(702, new ASN1Integer(0));
            tee.put(704, rootOfTrust);
            tee.put(705, new ASN1Integer(AttestationUtils.getOsVersion()));
            tee.put(706, new ASN1Integer(patchLevels[0]));
            if (params.brand != null) {
                tee.put(710, new DEROctetString(params.brand));
            }
            if (params.device != null) {
                tee.put(711, new DEROctetString(params.device));
            }
            if (params.product != null) {
                tee.put(712, new DEROctetString(params.product));
            }
            if (params.manufacturer != null) {
                tee.put(716, new DEROctetString(params.manufacturer));
            }
            if (params.model != null) {
                tee.put(717, new DEROctetString(params.model));
            }
            tee.put(718, new ASN1Integer(patchLevels[1]));
            tee.put(719, new ASN1Integer(patchLevels[2]));

            java.util.TreeMap<Integer, ASN1Encodable> sw = new java.util.TreeMap<>();
            sw.put(701, new ASN1Integer(System.currentTimeMillis()));
            try {
                sw.put(709, createApplicationId(uid, params.packages));
            } catch (Throwable e) {
                Log.w(TAG, "Failed to create application ID", e);
            }

            ASN1Encodable[] keyDescriptionElements = new ASN1Encodable[] {
                new ASN1Integer(AttestationUtils.getAttestVersion()),
                new ASN1Enumerated(securityLevel),
                new ASN1Integer(AttestationUtils.getKeymasterVersion()),
                new ASN1Enumerated(securityLevel),
                new DEROctetString(params.attestationChallenge != null ? params.attestationChallenge : new byte[0]),
                new DEROctetString(new byte[0]),
                toAuthorizationList(sw),
                toAuthorizationList(tee)
            };

            DERSequence keyDescription = new DERSequence(keyDescriptionElements);
            DEROctetString keyDescriptionOctets = new DEROctetString(keyDescription.getEncoded());

            return new Extension(ATTESTATION_OID, false, keyDescriptionOctets);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build attestation extension", e);
            throw new RuntimeException(e);
        }
    }

    private static DERSet toIntegerSet(List<Integer> values) {
        ASN1Integer[] arr = new ASN1Integer[values.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new ASN1Integer(values.get(i));
        }
        return new DERSet(arr);
    }

    private static DERSequence toAuthorizationList(java.util.TreeMap<Integer, ASN1Encodable> fields) {
        ASN1EncodableVector vector = new ASN1EncodableVector();
        for (java.util.Map.Entry<Integer, ASN1Encodable> e : fields.entrySet()) {
            vector.add(new DERTaggedObject(true, e.getKey(), e.getValue()));
        }
        return new DERSequence(vector);
    }

    /**
     * Same purpose -> KeyUsage mapping stock KeyMint uses. keyCertSign is only
     * correct for ATTEST_KEY; using it for every key is a fingerprint.
     */
    private static int keyUsageFromPurposes(List<Integer> purposes) {
        int bits = 0;
        for (int purpose : purposes) {
            switch (purpose) {
                case 2: bits |= KeyUsage.digitalSignature; break;  // SIGN
                case 1: bits |= KeyUsage.dataEncipherment; break;  // DECRYPT
                case 5: bits |= KeyUsage.keyEncipherment; break;   // WRAP_KEY
                case 6: bits |= KeyUsage.keyAgreement; break;      // AGREE_KEY
                case 7: bits |= KeyUsage.keyCertSign; break;       // ATTEST_KEY
                default: break;
            }
        }
        return bits;
    }

    private static DEROctetString createApplicationId(int uid, String[] knownPackages)
            throws Throwable {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            throw new IllegalStateException("createApplicationId: context not available from ActivityThread!");
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            throw new IllegalStateException("createApplicationId: PackageManager not found!");
        }

        String[] packages = uid == Process.myUid() ? knownPackages : null;
        if (packages == null || packages.length == 0) {
            packages = pm.getPackagesForUid(uid);
        }
        if (packages == null || packages.length == 0) {
            throw new IllegalStateException("No packages found for UID: " + uid);
        }

        List<ASN1Encodable> packageInfoList = new ArrayList<>(packages.length);
        Set<ByteBuffer> signatures = new HashSet<>();
        MessageDigest dg = MessageDigest.getInstance("SHA-256");

        for (String name : packages) {
            PackageInfo info;
            try {
                info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Package not found: " + name);
                continue;
            }
            if (info == null) continue;

            ASN1Encodable[] arr = new ASN1Encodable[2];
            arr[0] = new DEROctetString(name.getBytes(StandardCharsets.UTF_8));
            arr[1] = new ASN1Integer(info.getLongVersionCode());
            packageInfoList.add(new DERSequence(arr));

            if (info.signingInfo != null) {
                Signature[] signers = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
                if (signers != null) {
                    for (Signature s : signers) {
                        signatures.add(ByteBuffer.wrap(dg.digest(s.toByteArray())));
                    }
                }
            }
        }

        ASN1Encodable[] signaturesAA = new ASN1Encodable[signatures.size()];
        int i = 0;
        for (ByteBuffer d : signatures) {
            signaturesAA[i++] = new DEROctetString(d.array());
        }

        ASN1Encodable[] applicationIdAA = new ASN1Encodable[2];
        applicationIdAA[0] = new DERSet(packageInfoList.toArray(new ASN1Encodable[0]));
        applicationIdAA[1] = new DERSet(signaturesAA);

        return new DEROctetString(new DERSequence(applicationIdAA).getEncoded());
    }
}
