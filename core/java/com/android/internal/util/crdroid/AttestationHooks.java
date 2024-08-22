/*
 * Copyright (C) 2024 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.crdroid;

import android.os.SystemProperties;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyEntryResponse;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

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

/**
 * @hide
 */
public final class AttestationHooks {

    private static final String TAG = AttestationHooks.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String SPOOF_PIXEL_PI = "persist.sys.pixelprops.pi";

    private static final PrivateKey EC, RSA;
    private static final byte[] EC_CERTS;
    private static final byte[] RSA_CERTS;
    private static final ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
    private static final CertificateFactory certificateFactory;
    private static final X509CertificateHolder EC_holder, RSA_holder;
    private static volatile String algo;

    static {
        try {
            certificateFactory = CertificateFactory.getInstance("X.509");

            EC = parsePrivateKey(Keybox.EC.PRIVATE_KEY, KeyProperties.KEY_ALGORITHM_EC);
            RSA = parsePrivateKey(Keybox.RSA.PRIVATE_KEY, KeyProperties.KEY_ALGORITHM_RSA);

            byte[] EC_cert1 = parseCert(Keybox.EC.CERTIFICATE_1);
            byte[] RSA_cert1 = parseCert(Keybox.RSA.CERTIFICATE_1);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();

            stream.write(EC_cert1);
            stream.write(parseCert(Keybox.EC.CERTIFICATE_2));
            stream.write(parseCert(Keybox.EC.CERTIFICATE_3));

            EC_CERTS = stream.toByteArray();

            stream.reset();

            stream.write(RSA_cert1);
            stream.write(parseCert(Keybox.RSA.CERTIFICATE_2));
            stream.write(parseCert(Keybox.RSA.CERTIFICATE_3));

            RSA_CERTS = stream.toByteArray();

            stream.close();

            EC_holder = new X509CertificateHolder(EC_cert1);
            RSA_holder = new X509CertificateHolder(RSA_cert1);

        } catch (Throwable t) {
            if (DEBUG) Log.e(TAG, Log.getStackTraceString(t));
            throw new RuntimeException(t);
        }
    }

    private static PrivateKey parsePrivateKey(String str, String algo) throws Throwable {
        byte[] bytes = Base64.getDecoder().decode(str);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        return KeyFactory.getInstance(algo).generatePrivate(spec);
    }

    private static byte[] parseCert(String str) {
        return Base64.getDecoder().decode(str);
    }

    private static byte[] getCertificateChain(String algo) throws Throwable {
        if (KeyProperties.KEY_ALGORITHM_EC.equals(algo)) {
            return EC_CERTS;
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equals(algo)) {
            return RSA_CERTS;
        }
        throw new Exception();
    }

    private static byte[] modifyLeaf(byte[] bytes) throws Throwable {
        X509Certificate leaf = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(bytes));

        if (leaf.getExtensionValue(OID.getId()) == null) throw new Exception();

        X509CertificateHolder holder = new X509CertificateHolder(leaf.getEncoded());

        Extension ext = holder.getExtension(OID);

        ASN1Sequence sequence = ASN1Sequence.getInstance(ext.getExtnValue().getOctets());

        ASN1Encodable[] encodables = sequence.toArray();

        ASN1Sequence teeEnforced = (ASN1Sequence) encodables[7];

        ASN1EncodableVector vector = new ASN1EncodableVector();

        ASN1Sequence rootOfTrust = null;
        for (ASN1Encodable asn1Encodable : teeEnforced) {
            ASN1TaggedObject taggedObject = (ASN1TaggedObject) asn1Encodable;
            if (taggedObject.getTagNo() == 704) {
                rootOfTrust = (ASN1Sequence) taggedObject.getObject();
                continue;
            }
            vector.add(asn1Encodable);
        }

        if (rootOfTrust == null) throw new Exception();

        algo = leaf.getPublicKey().getAlgorithm();

        boolean isEC = KeyProperties.KEY_ALGORITHM_EC.equals(algo);

        X509CertificateHolder cert1 = isEC ? EC_holder : RSA_holder;
        PrivateKey privateKey = isEC ? EC : RSA;

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(cert1.getSubject(),
            holder.getSerialNumber(), holder.getNotBefore(), holder.getNotAfter(),
            holder.getSubject(), holder.getSubjectPublicKeyInfo());
        ContentSigner signer = new JcaContentSignerBuilder(leaf.getSigAlgName()).build(privateKey);

        byte[] verifiedBootKey = new byte[32];
        ThreadLocalRandom.current().nextBytes(verifiedBootKey);

        DEROctetString verifiedBootHash = (DEROctetString) rootOfTrust.getObjectAt(3);

        if (verifiedBootHash == null) {
            byte[] temp = new byte[32];
            ThreadLocalRandom.current().nextBytes(temp);
            verifiedBootHash = new DEROctetString(temp);
        }

        ASN1Encodable[] rootOfTrustEnc = {new DEROctetString(verifiedBootKey),
            ASN1Boolean.TRUE, new ASN1Enumerated(0), new DEROctetString(verifiedBootHash)};

        ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEnc);

        ASN1TaggedObject rootOfTrustTagObj = new DERTaggedObject(704, rootOfTrustSeq);

        vector.add(rootOfTrustTagObj);

        ASN1Sequence hackEnforced = new DERSequence(vector);

        encodables[7] = hackEnforced;

        ASN1Sequence hackedSeq = new DERSequence(encodables);

        ASN1OctetString hackedSeqOctets = new DEROctetString(hackedSeq);

        Extension hackedExt = new Extension(OID, false, hackedSeqOctets);

        builder.addExtension(hackedExt);

        for (ASN1ObjectIdentifier extensionOID : holder.getExtensions().getExtensionOIDs()) {
            if (OID.getId().equals(extensionOID.getId())) continue;
            builder.addExtension(holder.getExtension(extensionOID));
        }

        return builder.build(signer).getEncoded();
    }

    public static KeyEntryResponse onGetKeyEntry(KeyEntryResponse response) {
        if (response == null)
            return null;

        if (!SystemProperties.getBoolean(SPOOF_PIXEL_PI, true))
            return response;

        if (response.metadata == null)
            return response;

        algo = null;

        try {
            byte[] newLeaf = modifyLeaf(response.metadata.certificate);
            response.metadata.certificateChain = getCertificateChain(algo);

            response.metadata.certificate = newLeaf;

        } catch (Throwable t) {
            if (DEBUG) Log.e(TAG, "onGetKeyEntry", t);
        }

        return response;
    }

    private static final class Keybox {
        public static final class EC {
            public static final String PRIVATE_KEY = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgZD40XzfCEMydUW9mpLuTkl5QZV2tPxbmak0Z2eOMMXmhRANCAAQpUJNXlGs+lkFDtO1hhZYfpnjIdkdhQLu4AvdBHhsA2RUtFJGXwgwdp+3B31unHwFtiNnTq180CAo69/tcb32o";
            public static final String CERTIFICATE_1 = "MIIB8jCCAXmgAwIBAgIQKwoJppxZtILduKIXhv3UOTAKBggqhkjOPQQDAjA5MQwwCgYDVQQMDANURUUxKTAnBgNVBAUTIDFlMDE2NzUzMzA4YTAxYzAzNjA3MGI5OTE2Mjk2YTI3MB4XDTIyMDkxNzE3MTQwNVoXDTMyMDkxNDE3MTQwNVowOTEMMAoGA1UEDAwDVEVFMSkwJwYDVQQFEyAwYzg2ODRjNjZkNWMzZjYzYzJkMjQ5NGI3MmI4MmQ1MDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABClQk1eUaz6WQUO07WGFlh+meMh2R2FAu7gC90EeGwDZFS0UkZfCDB2n7cHfW6cfAW2I2dOrXzQICjr3+1xvfaijYzBhMB0GA1UdDgQWBBT8eC55sS2oWckA4/jGdnp0YyS0WDAfBgNVHSMEGDAWgBRdhLpDLqBcYlbgdmid7HLDFF5bCzAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDAKBggqhkjOPQQDAgNnADBkAjEAtHZAFIYynmEGbvR9I2fFo3h5HJUERDqSc4z7I3vfkfFMwYGA56EcBxk1qxWmwBliAi9gH5fYU6TaZaD51bBSghTdDkhC6dU8mBxoBYwKc5RYL9UHitlJXn7k5pEY2Lhn/A==";
            public static final String CERTIFICATE_2 = "MIIDlDCCAXygAwIBAgIRANinq/UsMAzvigSUsi3p4fYwDQYJKoZIhvcNAQELBQAwGzEZMBcGA1UEBRMQZjkyMDA5ZTg1M2I2YjA0NTAeFw0yMjA5MTcxNzEyNDFaFw0zMjA5MTQxNzEyNDFaMDkxDDAKBgNVBAwMA1RFRTEpMCcGA1UEBRMgMWUwMTY3NTMzMDhhMDFjMDM2MDcwYjk5MTYyOTZhMjcwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAQfJkk4hCJ3MNB12tmt0DrQDjn9uwBF89CoJ/LU0kuj13hqfLIsHl3th9DkJArDpTsiAx6d71ar28LENHmgdvKnszyjAvMgXSp6Fpg0ALJ6KQHMS8PCIsjXv0YDEtUzFdSjYzBhMB0GA1UdDgQWBBRdhLpDLqBcYlbgdmid7HLDFF5bCzAfBgNVHSMEGDAWgBQ2YeEAfIgFCVGLRGxH/xpMyepPEjAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDANBgkqhkiG9w0BAQsFAAOCAgEAHQ0wJzHWVWAjPH+m98e2RXvO4bCZDihXDWc5qItz/Q1xIhjkmUI8Ftoka7ha2TJBxSvuPzLi50HaKXVw1cPXaOU2erovMzqioMkNg0Ga0m0xwf814RoHe6f75nOoEEpgVzUf1ghkqqhVuIcoNq8SJ/hsHIBeF8LARh5+8/9Ig4sR4hcSunRuV3lYbgTuxbiM7w1RsoIJsM7/SaWI/nYsdWh2TTgCuyqCt/epgp2lZAdGdNNGsCnUxoflZ/tdB+dMzptbqaRza27h5dODyaZRrJ6HTaL4uhZId5otPVbyhqG5RjY3oMK8m3GuMRq/ne8+6sV7JmXWfDHYdjJyyOLYgVlTnm62LSpq1KGeZqL0L8hlXeyOFxXvc/QrQ0Bt6YOgv6B4R+TAd1g7VrEeh1VJosXJFWrgrVHCpg00zqPGZUplUScP3E5YkCNqz87FfFmge0bYMIoOxAGa3PcyxokI7s73Bou2gtz8WFEVbkaVtvn/8kA+5zbROxZg2piaJdQkMROJ9LfH49saN5VdRn1qESh4QkA78/nVzHQBWvBMM7LbiXFKbWzXidBCB7O0K9tgqJZhgWCtvTPGrQLGNOYRs2fwN3BaaA11TcCLimFESMIh724v0Zc9DgTh3p4EA/X0loJnNrfUBON9UkNsrh8KWvJZ+bFn50eVDpEmzUJZXhI=";
            public static final String CERTIFICATE_3 = "MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgwNzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggjnar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQoVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1UdIwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGICW/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2GtkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkxoSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mFmr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPzlHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVwn6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1EuzbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHovaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHnw1IdYIg2Wxg7yHcQZemFQg==";
        }
        public static final class RSA {
            public static final String PRIVATE_KEY = "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";
            public static final String CERTIFICATE_1 = "MIIE4DCCAsigAwIBAgIRANEddk05W1jqsOQlSRGYH2wwDQYJKoZIhvcNAQELBQAwOTEMMAoGA1UEDAwDVEVFMSkwJwYDVQQFEyAxZTAxNjc1MzMwOGEwMWMwMzYwNzBiOTkxNjI5NmEyNzAeFw0yMjA5MTcxNzE0MDVaFw0zMjA5MTQxNzE0MDVaMDkxDDAKBgNVBAwMA1RFRTEpMCcGA1UEBRMgMGM4Njg0YzY2ZDVjM2Y2M2MyZDI0OTRiNzJiODJkNTAwggGiMA0GCSqGSIb3DQEBAQUAA4IBjwAwggGKAoIBgQCjkMAv0HCcncLjtvAtc+CS5Wy/BH8MS5nOr7CwM+76o87fv71323nZoBKDjb9VIrNJirE3i+DogUvcSG0oHQ0nlrYYmcD4d0Ze9SGNkeh3xNDgUKKUa0kmoCbbCTFqQz1FiNnRORu/jMvwiVO4mnmLBOGHfK7dIJ4m945NjD/Bv1F8f6RLzOJpCuQeOYbnup+aYKQo4PD1g4ctGiSaPatzucdfG2Q+Hbkmnibal++CMdpVG/DRfeHZ5Rx9W2b4++FbrMSv/RR0wkho07zRVqE9dZ6oiUK7eNmgwfpo3OK1RYUK5OPp8Tt0Fz74WFIqJjbiY0VgTFZvqCIrIieXWXImWTKbUBSL50fdjvR7rvUb4d01ZDKlGa3BXJxRaGktFxevAh7b7613Uy953DDgXd+XO4c3JGzGcH2l+h7mphB2+RzqnLJPzfHD3IwTBLNgIH1zCbGZQnhe4k3UGOxcIQJOzZ+KaA/uIl7DTdV3pb/UD5v3KKGuWo9W88sX+OYgoNsCAwEAAaNjMGEwHQYDVR0OBBYEFMJS2luT0WMslf/fwYc3xGvW8z0qMB8GA1UdIwQYMBaAFNplJLLkhPHv/IrpvTUvfFjRarMGMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQBolN+VgyoV8hTOQdtzLuOvLuYLfYNZGcpQ4GtCPjWUUa3YXTJTrYfTpT3nP5Yr4JhVurCK8toGVvEHWdGi8Zxsjw9z/tlpqLKguoPculD28OhjZBZbOZ5X9QH/NKi9H/KyRB/m0kv53/gw0p2GZrqhXkAklxuhvsY3bhchp2I6rz/ie2CZQedp4A3jX6C6pS5HMbQi9Y2m8kNp0/DQy8oJa7uiom07iL/X7KWZTY1sbZi3g99qLZJEYzd6B8PufR3dR5TFNx75+uBacyOUdzhuWGk+XPjhrSvACpk9my3CcO8phWfrKDKTmISoZQzEY4UFN8VclU5cX5QmJKNvIZ9mPJ2yzwzEVsBjv2qu146iLuCgz64hqeXlS7++Qfs1YWgIhVS/r8Og1p2HgnbRt1lm1x6iqIF0pcQOnPqbDAMeuTHnwoiBJlPTwf8ix3Yy9w0/UTVqO3LjK+ALdy6CS3agpUmVLkxUhIxlb8QGJ3GmG1eHQn/SHyXpxIwKCKIKMOCo85WssJv80YGQI5rpKrjQ8Yzhlc8wq3PClkY6sYPMIbgqoymYET56VLoRryiLIAnpUsezMLulTE8Wu6csUd4DqbzK2W+ZVN9eXDunLQzwi2jmdxLyN5DtpobDEaXo7B1yhO64Mg1nxAF4Wc3rF0QnkJUbuG2Fp6N2fnxA0foYLw==";
            public static final String CERTIFICATE_2 = "MIIFQTCCAymgAwIBAgIQBCM0AVWvUSM8Njd0xc/g5zANBgkqhkiG9w0BAQsFADAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MB4XDTIyMDkxNzE3MTIyM1oXDTMyMDkxNDE3MTIyM1owOTEMMAoGA1UEDAwDVEVFMSkwJwYDVQQFEyAxZTAxNjc1MzMwOGEwMWMwMzYwNzBiOTkxNjI5NmEyNzCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBALBW8YfWz8PlqzhJdISHAq10CI1pBVnTACtmuOogoWrLSfxuPtMBkW0bk5u+ger0ZPO5qlXzWjHTP6dpg12DxU7MA8CBvcgWZ5yGP4yYdpQTcWcOpxQIKD7CYOemGQUKAXWO5oVn1lkqIYPcXgsDB30tgmNT+lvT0OZhRnv3t3I9E2L852cMEWIZnYHNbwUdRIMf5ZAkspFatzwskGOKutknX4FTGHhIikzB0xdWbCoZYxczL9u6RkLy/RytmfWmWSpUz1E+HsvZzdjnSZ1u6ouU2hmNisOwGxKJZed4OmKcqifd087sy12sLpPN6/khSkWbp3Pk45lg2kXoovieH01P4I8NEYhgLOToEJY93TtBhp9eATrfTpj5X+lWEu4vu1X7kw6XL6cMwlHIy0jrL8++2pUXircsnuYZwUNlG6umjFCIUSiTl9iZJqwjYy1SDrfggcSX6Dm+lurYMdSbo5UN30zsaharGVyUf99nqe5a6eEHtliiPdl+WtS/P08wfF2Rm0NBqJUR8cbe/vaFxqZyZ2Y/upY7LzTBQNftMiPoxrQPiLfCB4lrPlNUH+7bKjziL7mnREQq18SHU5Gt5nlQtJIotFMUE3rFjTXtOMfwyQ4PSG6WMQ4XycOYwj9N74LqNC5MVmv2pYuWYw33dl620BgrZZrsVk9XwTJqf7PpAgMBAAGjYzBhMB0GA1UdDgQWBBTaZSSy5ITx7/yK6b01L3xY0WqzBjAfBgNVHSMEGDAWgBQ2YeEAfIgFCVGLRGxH/xpMyepPEjAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDANBgkqhkiG9w0BAQsFAAOCAgEAV+9eU49qaS+KJfynRtZWFtHLuGSTzh+L+QE+5U9QY6NFB0HHhEP9HUGmCt02biK6couBP2XsisNtcRqpM3SxyunTztZjP8U+ucBaonxhxOViS6J9Zxg6n54lLSataraLE800jyi83iPar6kU3EUJkagEGc54t1b7E/UZWaEtKZ/uaOSkhd7SCGOsmduTaecjshTxqV8Qwj/c+DNGMqu2HhQnpxs7krcdDNOxxXP6E0xY2/iIUqEcf5ON24S9qYD8ZJWt46TLrTO4PJPOmj7WwX5jA4qbkzmugP+v6EJls6gflk2hynAXm4lAI8xFdO7YFCZ8L0SDSVw8SK9cEyYhZhXiZ7MBvSJ9ak5XvuMYTaEXFS5QhqD9+ObEBKG68n7s5ySPfz44QP+8iftWAYMMwD4cYxJElYHTYp91zlN3kJDbwnLoDLS7PZVqBkJkSvnAEM5ejRiaKCK7tB3WkYX6YRxUQ0lsaEGXy4/183sYrKTCmXeU1ccWH8liMb8n81hmQSN9YQtnQVNKcHCkfKt++GFKNlkl43gdUUcLJ73zNrAJnV36TuF1HMtFWrNOAzT53qfvHY8gBD5OJrA+ZxdX4n9g52iWWYxJEIXmLg4caIuz028KlGHpFCT9RIeNaEsEWS03yQF7ekotjqfumdY8B9W53sKqiPRsY3jsljrXiC4=";
            public static final String CERTIFICATE_3 = "MIIFHDCCAwSgAwIBAgIJAPHBcqaZ6vUdMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMjIwMzIwMTgwNzQ4WhcNNDIwMzE1MTgwNzQ4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggjnar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQoVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1UdIwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQB8cMqTllHc8U+qCrOlg3H7174lmaCsbo/bJ0C17JEgMLb4kvrqsXZs01U3mB/qABg/1t5Pd5AORHARs1hhqGICW/nKMav574f9rZN4PC2ZlufGXb7sIdJpGiO9ctRhiLuYuly10JccUZGEHpHSYM2GtkgYbZba6lsCPYAAP83cyDV+1aOkTf1RCp/lM0PKvmxYN10RYsK631jrleGdcdkxoSK//mSQbgcWnmAEZrzHoF1/0gso1HZgIn0YLzVhLSA/iXCX4QT2h3J5z3znluKG1nv8NQdxei2DIIhASWfu804CA96cQKTTlaae2fweqXjdN1/v2nqOhngNyz1361mFmr4XmaKH/ItTwOe72NI9ZcwS1lVaCvsIkTDCEXdm9rCNPAY10iTunIHFXRh+7KPzlHGewCq/8TOohBRn0/NNfh7uRslOSZ/xKbN9tMBtw37Z8d2vvnXq/YWdsm1+JLVwn6yYD/yacNJBlwpddla8eaVMjsF6nBnIgQOf9zKSe06nSTqvgwUHosgOECZJZ1EuzbH4yswbt02tKtKEFhx+v+OTge/06V+jGsqTWLsfrOCNLuA8H++z+pUENmpqnnHovaI47gC+TNpkgYGkkBT6B/m/U01BuOBBTzhIlMEZq9qkDWuM2cA5kW5V3FJUcfHnw1IdYIg2Wxg7yHcQZemFQg==";
        }
    }
}
