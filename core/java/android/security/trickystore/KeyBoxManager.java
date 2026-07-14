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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @hide
 */
public class KeyBoxManager {
    private static final String TAG = "KeyBoxManager";

    private volatile Map<String, KeyBox> mKeyboxes = new ConcurrentHashMap<>();

    private static final Pattern PEM_HEADER = Pattern.compile("-----BEGIN ([^-]+)-----");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

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

    /**
     * Parses [xmlContent] and, if it is acceptable, replaces the loaded keyboxes.
     * A truncated or keyless document leaves the previously loaded keyboxes untouched,
     * so one bad refresh can't take attestation down.
     *
     * @return false if the document was rejected as incomplete, true otherwise
     */
    public boolean parseKeybox(String xmlContent) {
        // Parse into a private map and publish it at the end, so a concurrent
        // attestation never sees a half-filled or empty keybox mid-refresh.
        Map<String, KeyBox> next = new ConcurrentHashMap<>();
        if (xmlContent == null || xmlContent.isEmpty()) {
            mKeyboxes = next;
            return true;
        }

        // Truncation is detected by well-formedness, not by NumberOfKeyboxes: the root
        // element must have closed for a parse failure to still be usable.
        int depth = 0;
        boolean rootClosed = false;

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
            StringBuilder privateKeyBuilder = null;
            StringBuilder certBuilder = null;
            boolean inKey = false;
            boolean inPrivateKey = false;
            boolean inCertificateChain = false;
            boolean inCertificate = false;
            boolean inNumberOfKeyboxes = false;
            StringBuilder numberOfKeyboxesBuilder = null;
            Integer declaredKeyboxCount = null;
            // NumberOfKeyboxes counts <Keybox> elements, each of which normally holds
            // both an ECDSA and an RSA <Key>, so count boxes and not keys.
            int parsedKeyboxCount = 0;
            boolean sawKeyboxTag = false;
            boolean boxHasKey = false;
            int parsedKeyCount = 0;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    depth++;
                } else if (eventType == XmlPullParser.END_TAG && --depth == 0) {
                    rootClosed = true;
                }

                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("Keybox".equals(tagName)) {
                            sawKeyboxTag = true;
                            boxHasKey = false;
                        } else if ("Key".equals(tagName)) {
                            inKey = true;
                            currentKeyIndex++;
                            currentAlgorithm = parser.getAttributeValue(null, "algorithm");
                            privateKeyPem = null;
                            certificatePems.clear();
                            privateKeyBuilder = null;
                            certBuilder = null;
                        } else if ("PrivateKey".equals(tagName) && inKey) {
                            inPrivateKey = true;
                            privateKeyBuilder = new StringBuilder();
                        } else if ("CertificateChain".equals(tagName) && inKey) {
                            inCertificateChain = true;
                        } else if ("Certificate".equals(tagName) && inCertificateChain) {
                            inCertificate = true;
                            certBuilder = new StringBuilder();
                        } else if ("NumberOfKeyboxes".equals(tagName) && !inKey) {
                            inNumberOfKeyboxes = true;
                            numberOfKeyboxesBuilder = new StringBuilder();
                        }
                        break;

                    case XmlPullParser.TEXT:
                        String text = parser.getText();
                        if (text != null) {
                            if (inPrivateKey && privateKeyBuilder != null) {
                                privateKeyBuilder.append(text);
                            } else if (inCertificate && certBuilder != null) {
                                certBuilder.append(text);
                            } else if (inNumberOfKeyboxes && numberOfKeyboxesBuilder != null) {
                                numberOfKeyboxesBuilder.append(text);
                            }
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if ("PrivateKey".equals(tagName)) {
                            if (privateKeyBuilder != null) {
                                String pem = privateKeyBuilder.toString().trim();
                                if (!pem.isEmpty()) {
                                    privateKeyPem = pem;
                                }
                                privateKeyBuilder = null;
                            }
                            inPrivateKey = false;
                        } else if ("Certificate".equals(tagName)) {
                            if (certBuilder != null) {
                                String pem = certBuilder.toString().trim();
                                if (!pem.isEmpty()) {
                                    certificatePems.add(pem);
                                }
                                certBuilder = null;
                            }
                            inCertificate = false;
                        } else if ("CertificateChain".equals(tagName)) {
                            inCertificateChain = false;
                        } else if ("NumberOfKeyboxes".equals(tagName)) {
                            if (numberOfKeyboxesBuilder != null) {
                                try {
                                    declaredKeyboxCount =
                                            Integer.parseInt(numberOfKeyboxesBuilder.toString().trim());
                                } catch (NumberFormatException ignored) {
                                }
                                numberOfKeyboxesBuilder = null;
                            }
                            inNumberOfKeyboxes = false;
                        } else if ("Key".equals(tagName)) {
                            inKey = false;
                            if (currentAlgorithm != null && privateKeyPem != null && !certificatePems.isEmpty()) {
                                processKeybox(next, currentAlgorithm, privateKeyPem, certificatePems);
                                parsedKeyCount++;
                                boxHasKey = true;
                            }
                        } else if ("Keybox".equals(tagName)) {
                            if (boxHasKey) parsedKeyboxCount++;
                            boxHasKey = false;
                        }
                        break;
                }

                eventType = parser.next();
            }

            // A document without <Keybox> wrappers holds a single implicit box.
            if (!sawKeyboxTag && parsedKeyCount > 0) parsedKeyboxCount = 1;
            // NumberOfKeyboxes is advisory. Generators disagree on what it counts (the
            // <Keybox> wrappers or the <Key> elements) and some write a value that matches
            // neither, which TEESimulator-RS and AlwaysStrong accept as well: they iterate
            // every <Key> they find. So a mismatch is only logged. A truncated download is
            // caught by the parse failure below, and a document that yields no key at all
            // is still rejected so it can't wipe the loaded keyboxes.
            if (declaredKeyboxCount != null && declaredKeyboxCount > 0 && parsedKeyCount == 0) {
                Log.e(TAG, "Keybox XML declares " + declaredKeyboxCount
                        + " keybox(es) but no complete key parsed — rejecting, keeping the"
                        + " current keyboxes");
                return false;
            }
            if (declaredKeyboxCount != null
                    && declaredKeyboxCount != parsedKeyboxCount
                    && declaredKeyboxCount != parsedKeyCount) {
                Log.w(TAG, "Keybox XML declares " + declaredKeyboxCount + " keybox(es), parsed "
                        + parsedKeyboxCount + " box(es) / " + parsedKeyCount
                        + " key(s); NumberOfKeyboxes is advisory, using what parsed");
            }

            mKeyboxes = next;
            Log.i(TAG, "Parsed " + next.size() + " keyboxes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse keybox XML", e);
            if (!rootClosed || next.isEmpty()) {
                // Cut off before the root element closed (an interrupted download), or
                // nothing usable came out: keep what is loaded instead of publishing a
                // partial or empty set.
                Log.e(TAG, "Keybox XML looks truncated or unusable — keeping the current keyboxes");
                return false;
            }
            // Complete document with trailing garbage: everything before it parsed.
            mKeyboxes = next;
            return true;
        }
    }

    private void processKeybox(Map<String, KeyBox> target, String algorithm, String privateKeyPem,
            List<String> certificatePems) {
        try {
            String normalizedAlgorithm;
            switch (algorithm.toLowerCase()) {
                case "ec":
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
                target.put(normalizedAlgorithm, new KeyBox(keyPair, certificates));
                Log.i(TAG, "Added keybox for algorithm: " + normalizedAlgorithm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to process keybox for algorithm: " + algorithm, e);
        }
    }

    private PrivateKey parsePrivateKey(String pem, String algorithm) throws Exception {
        String pemType = detectPemType(pem);
        byte[] keyBytes = parsePemContent(pem);

        if ("EC PRIVATE KEY".equals(pemType)
                || ("EC".equals(algorithm) && pemType == null)) {
            return parseEcPrivateKey(keyBytes, algorithm);
        }

        if ("RSA PRIVATE KEY".equals(pemType)
                || ("RSA".equals(algorithm) && pemType == null)) {
            return parseRsaPrivateKey(keyBytes);
        }

        if ("PRIVATE KEY".equals(pemType) || "ENCRYPTED PRIVATE KEY".equals(pemType)) {
            PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(keyBytes);
            if ("RSA".equals(algorithm)) {
                return new KeyFactorySpi().generatePrivate(pkInfo);
            }
            if ("EC".equals(algorithm)) {
                return new EC().generatePrivate(pkInfo);
            }
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(algorithm).generatePrivate(spec);
        }

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
    }

    private PrivateKey parseEcPrivateKey(byte[] keyBytes, String algorithm) throws Exception {
        try (ASN1InputStream asn1In = new ASN1InputStream(keyBytes)) {
            ASN1Primitive asn1 = asn1In.readObject();
            try {
                ECPrivateKey ecPrivateKey = ECPrivateKey.getInstance(asn1);
                X9ECParameters ecParams = ECNamedCurveTable.getByOID(
                        (ASN1ObjectIdentifier) ecPrivateKey.getParameters());
                ECDomainParameters domainParams = new ECDomainParameters(
                        ecParams.getCurve(), ecParams.getG(), ecParams.getN(), ecParams.getH());
                ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(
                        ecPrivateKey.getKey(), domainParams);
                return new BCECPrivateKey(algorithm, privParams, null);
            } catch (Exception e) {
                PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(asn1);
                return new EC().generatePrivate(pkInfo);
            }
        }
    }

    private PrivateKey parseRsaPrivateKey(byte[] keyBytes) throws Exception {
        try (ASN1InputStream asn1In = new ASN1InputStream(keyBytes)) {
            ASN1Primitive asn1 = asn1In.readObject();
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
                        rsaPrivateKey.getCoefficient());
                return KeyFactory.getInstance("RSA").generatePrivate(rsaSpec);
            } catch (Exception e) {
                PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(asn1);
                return new KeyFactorySpi().generatePrivate(pkInfo);
            }
        }
    }

    private String detectPemType(String pem) {
        if (pem == null) return null;
        Matcher m = PEM_HEADER.matcher(pem);
        return m.find() ? m.group(1).trim().toUpperCase() : null;
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
        String[] boms = {"﻿", "￾", "\u0000﻿"};
        for (String bom : boms) {
            if (content.startsWith(bom)) {
                content = content.substring(bom.length());
            }
        }
        content = XML_COMMENT.matcher(content).replaceAll("");
        return content.trim();
    }
}
