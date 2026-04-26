package android.security.trickystore;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
        
        KeyBoxManager keyboxManager = TrickyStoreService.getInstance().getKeyBoxManager();
        String algorithm = params.algorithm == 3 ? "EC" : "RSA";
        KeyBoxManager.KeyBox keybox = keyboxManager.getKeybox(algorithm);
        
        if (keybox == null) {
            Log.e(TAG, "No keybox found for algorithm: " + algorithm);
            return null;
        }

        try {
            X509CertificateHolder issuerHolder = new X509CertificateHolder(
                keybox.certificates.get(0).getEncoded());
            X500Name issuer = issuerHolder.getSubject();

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

        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
        builder.addExtension(buildAttestExtension(params, securityLevel, uid));

        String sigAlg = params.algorithm == 3 ? "SHA256withECDSA" : "SHA256withRSA";
        JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder(sigAlg);
        if (params.algorithm == 3) {
            signerBuilder.setProvider(new BouncyCastleProvider());
        }
        ContentSigner signer = signerBuilder.build(keybox.keyPair.getPrivate());

        X509CertificateHolder holder = builder.build(signer);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(
            new ByteArrayInputStream(holder.getEncoded()));
    }

    private static Extension buildAttestExtension(KeyGenParameters params, int securityLevel, int uid) {
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

            ASN1EncodableVector teeEnforced = new ASN1EncodableVector();

            ASN1Integer[] purposes = new ASN1Integer[params.purpose.size()];
            for (int i = 0; i < params.purpose.size(); i++) {
                purposes[i] = new ASN1Integer(params.purpose.get(i));
            }
            teeEnforced.add(new DERTaggedObject(true, 1, new DERSet(purposes)));
            teeEnforced.add(new DERTaggedObject(true, 2, new ASN1Integer(params.algorithm)));
            teeEnforced.add(new DERTaggedObject(true, 3, new ASN1Integer(params.keySize)));

            ASN1Integer[] digests = new ASN1Integer[params.digest.size()];
            for (int i = 0; i < params.digest.size(); i++) {
                digests[i] = new ASN1Integer(params.digest.get(i));
            }
            teeEnforced.add(new DERTaggedObject(true, 5, new DERSet(digests)));

            teeEnforced.add(new DERTaggedObject(true, 10, new ASN1Integer(params.ecCurve)));
            teeEnforced.add(new DERTaggedObject(true, 503, DERNull.INSTANCE));
            teeEnforced.add(new DERTaggedObject(true, 702, new ASN1Integer(0)));
            teeEnforced.add(new DERTaggedObject(true, 704, rootOfTrust));
            teeEnforced.add(new DERTaggedObject(true, 705, new ASN1Integer(AttestationUtils.getOsVersion())));
            teeEnforced.add(new DERTaggedObject(true, 706, new ASN1Integer(AttestationUtils.getPatchLevel(false))));
            teeEnforced.add(new DERTaggedObject(true, 718, new ASN1Integer(AttestationUtils.getVendorPatchLevel(true))));
            teeEnforced.add(new DERTaggedObject(true, 719, new ASN1Integer(AttestationUtils.getBootPatchLevel(true))));

            if (params.brand != null) {
                teeEnforced.add(new DERTaggedObject(true, 710, new DEROctetString(params.brand)));
            }
            if (params.device != null) {
                teeEnforced.add(new DERTaggedObject(true, 711, new DEROctetString(params.device)));
            }
            if (params.product != null) {
                teeEnforced.add(new DERTaggedObject(true, 712, new DEROctetString(params.product)));
            }
            if (params.manufacturer != null) {
                teeEnforced.add(new DERTaggedObject(true, 716, new DEROctetString(params.manufacturer)));
            }
            if (params.model != null) {
                teeEnforced.add(new DERTaggedObject(true, 717, new DEROctetString(params.model)));
            }

            ASN1EncodableVector softwareEnforced = new ASN1EncodableVector();
            try {
                ASN1OctetString applicationId = createApplicationId(uid);
                softwareEnforced.add(new DERTaggedObject(true, 709, applicationId));
            } catch (Throwable e) {
                 Log.w(TAG, "Failed to create application ID", e);
            }
            softwareEnforced.add(new DERTaggedObject(true, 701, new ASN1Integer(System.currentTimeMillis())));

            ASN1Encodable[] keyDescriptionElements = new ASN1Encodable[] {
                new ASN1Integer(AttestationUtils.getAttestVersion()),
                new ASN1Enumerated(securityLevel),
                new ASN1Integer(AttestationUtils.getKeymasterVersion()),
                new ASN1Enumerated(securityLevel),
                new DEROctetString(params.attestationChallenge != null ? params.attestationChallenge : new byte[0]),
                new DEROctetString(new byte[0]),
                new DERSequence(softwareEnforced),
                new DERSequence(teeEnforced)
            };

            DERSequence keyDescription = new DERSequence(keyDescriptionElements);
            DEROctetString keyDescriptionOctets = new DEROctetString(keyDescription.getEncoded());

            return new Extension(ATTESTATION_OID, false, keyDescriptionOctets);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build attestation extension", e);
            throw new RuntimeException(e);
        }
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
