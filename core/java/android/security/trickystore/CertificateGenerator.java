package android.security.trickystore;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
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
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
            int securityLevel) {
        
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

            X509Certificate leaf = buildCertificate(keyPair, keybox, params, issuer, securityLevel);

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
            int securityLevel) throws Exception {

        BigInteger serial = params.certificateSerial != null ? 
            params.certificateSerial : BigInteger.ONE;
        Date notBefore = params.certificateNotBefore != null ? 
            params.certificateNotBefore : new Date();
        Date notAfter = params.certificateNotAfter != null ? 
            params.certificateNotAfter : 
            ((X509Certificate) keybox.certificates.get(0)).getNotAfter();
        X500Name subject = params.certificateSubject != null ? 
            params.certificateSubject : new X500Name("CN=Android Keystore Key");

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
        builder.addExtension(buildAttestExtension(params, securityLevel));

        String sigAlg = params.algorithm == 3 ? "SHA256withECDSA" : "SHA256withRSA";
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg)
            .build(keybox.keyPair.getPrivate());

        X509CertificateHolder holder = builder.build(signer);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(
            new ByteArrayInputStream(holder.getEncoded()));
    }

    private static Extension buildAttestExtension(KeyGenParameters params, int securityLevel) {
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
}
