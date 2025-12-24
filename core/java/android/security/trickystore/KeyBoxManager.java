package android.security.trickystore;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1InputStream;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import com.android.internal.org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import com.android.internal.org.bouncycastle.asn1.sec.ECPrivateKey;
import com.android.internal.org.bouncycastle.asn1.x9.ECNamedCurveTable;
import com.android.internal.org.bouncycastle.asn1.x9.X9ECParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECDomainParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi.EC;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyFactorySpi;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class KeyBoxManager {
    private static final String TAG = "KeyBoxManager";

    private final Map<String, KeyBox> mKeyboxes = new ConcurrentHashMap<>();

    /** @hide */
    public static class KeyBox {
        public final KeyPair keyPair;
        public final List<Certificate> certificates;

        public KeyBox(KeyPair keyPair, List<Certificate> certificates) {
            this.keyPair = keyPair;
            this.certificates = certificates;
        }
    }

    public void clear() {
        mKeyboxes.clear();
        Log.i(TAG, "Keyboxes cleared");
    }

    public boolean hasKeyboxes() {
        return !mKeyboxes.isEmpty();
    }

    public KeyBox getKeybox(String algorithm) {
        return mKeyboxes.get(algorithm);
    }

    public void parseKeybox(String xmlContent) {
        mKeyboxes.clear();
        if (xmlContent == null || xmlContent.isEmpty()) {
            return;
        }

        try {
            xmlContent = sanitizeXml(xmlContent);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xmlContent));

            int currentKeyIndex = -1;
            String currentAlgorithm = null;
            String privateKeyPem = null;
            List<String> certificatePems = new ArrayList<>();
            boolean inKey = false;
            boolean inPrivateKey = false;
            boolean inCertificateChain = false;
            boolean inCertificate = false;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();

                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("Key".equals(tagName)) {
                            inKey = true;
                            currentKeyIndex++;
                            currentAlgorithm = parser.getAttributeValue(null, "algorithm");
                            privateKeyPem = null;
                            certificatePems.clear();
                        } else if ("PrivateKey".equals(tagName) && inKey) {
                            inPrivateKey = true;
                        } else if ("CertificateChain".equals(tagName) && inKey) {
                            inCertificateChain = true;
                        } else if ("Certificate".equals(tagName) && inCertificateChain) {
                            inCertificate = true;
                        }
                        break;

                    case XmlPullParser.TEXT:
                        String text = parser.getText().trim();
                        if (!text.isEmpty()) {
                            if (inPrivateKey) {
                                privateKeyPem = text;
                            } else if (inCertificate) {
                                certificatePems.add(text);
                            }
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if ("PrivateKey".equals(tagName)) {
                            inPrivateKey = false;
                        } else if ("Certificate".equals(tagName)) {
                            inCertificate = false;
                        } else if ("CertificateChain".equals(tagName)) {
                            inCertificateChain = false;
                        } else if ("Key".equals(tagName)) {
                            inKey = false;
                            if (currentAlgorithm != null && privateKeyPem != null && !certificatePems.isEmpty()) {
                                processKeybox(currentAlgorithm, privateKeyPem, certificatePems);
                            }
                        }
                        break;
                }

                eventType = parser.next();
            }

            Log.i(TAG, "Parsed " + mKeyboxes.size() + " keyboxes");
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse keybox XML", e);
        }
    }

    private void processKeybox(String algorithm, String privateKeyPem, List<String> certificatePems) {
        try {
            String normalizedAlgorithm;
            switch (algorithm.toLowerCase()) {
                case "ecdsa":
                    normalizedAlgorithm = "EC";
                    break;
                case "rsa":
                    normalizedAlgorithm = "RSA";
                    break;
                default:
                    normalizedAlgorithm = algorithm;
            }

            PrivateKey privateKey = parsePrivateKey(privateKeyPem, normalizedAlgorithm);
            List<Certificate> certificates = new ArrayList<>();
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            for (String certPem : certificatePems) {
                byte[] certBytes = parsePemContent(certPem);
                Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
                certificates.add(cert);
            }

            if (!certificates.isEmpty()) {
                PublicKey publicKey = ((X509Certificate) certificates.get(0)).getPublicKey();
                KeyPair keyPair = new KeyPair(publicKey, privateKey);
                mKeyboxes.put(normalizedAlgorithm, new KeyBox(keyPair, certificates));
                Log.i(TAG, "Added keybox for algorithm: " + normalizedAlgorithm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to process keybox for algorithm: " + algorithm, e);
        }
    }

    private PrivateKey parsePrivateKey(String pem, String algorithm) throws Exception {
        byte[] keyBytes = parsePemContent(pem);
        
        ASN1InputStream asn1In = new ASN1InputStream(keyBytes);
        ASN1Primitive asn1 = asn1In.readObject();
        asn1In.close();
        
        if ("EC".equals(algorithm)) {
            try {
                ECPrivateKey ecPrivateKey = ECPrivateKey.getInstance(asn1);
                X9ECParameters ecParams = ECNamedCurveTable.getByOID(
                    (ASN1ObjectIdentifier) ecPrivateKey.getParameters());
                ECDomainParameters domainParams = new ECDomainParameters(
                    ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());
                ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(
                    ecPrivateKey.getKey(), domainParams);
                BCECPrivateKey bcKey = new BCECPrivateKey(algorithm, privParams, null);
                return bcKey;
            } catch (Exception e) {
                PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(asn1);
                EC ecFactory = new EC();
                return ecFactory.generatePrivate(pkInfo);
            }
        } else if ("RSA".equals(algorithm)) {
            try {
                RSAPrivateKey rsaPrivateKey = RSAPrivateKey.getInstance(asn1);
                RSAPrivateCrtKeySpec rsaSpec = new RSAPrivateCrtKeySpec(
                    rsaPrivateKey.getModulus(),
                    rsaPrivateKey.getPublicExponent(),
                    rsaPrivateKey.getPrivateExponent(),
                    rsaPrivateKey.getPrime1(),
                    rsaPrivateKey.getPrime2(),
                    rsaPrivateKey.getExponent1(),
                    rsaPrivateKey.getExponent2(),
                    rsaPrivateKey.getCoefficient()
                );
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePrivate(rsaSpec);
            } catch (Exception e) {
                PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(asn1);
                KeyFactorySpi rsaFactory = new KeyFactorySpi();
                return rsaFactory.generatePrivate(pkInfo);
            }
        }
        
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
        return keyFactory.generatePrivate(spec);
    }

    private byte[] parsePemContent(String pem) {
        String base64 = pem
            .replaceAll("-----BEGIN [^-]+-----", "")
            .replaceAll("-----END [^-]+-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private String sanitizeXml(String content) {
        content = content.trim();
        String[] boms = {"\uFEFF", "\uFFFE", "\u0000\uFEFF"};
        for (String bom : boms) {
            if (content.startsWith(bom)) {
                content = content.substring(bom.length());
            }
        }
        return content.trim();
    }
}
