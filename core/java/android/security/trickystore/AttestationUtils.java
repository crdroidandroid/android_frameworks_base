package android.security.trickystore;

import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.*;

import java.io.ByteArrayInputStream;
import java.security.*;
import java.security.cert.*;

/**
 * @hide
 */
public final class AttestationUtils {
    private static final String TAG = "AttestationUtils";

    private static byte[] sBootKey;
    private static byte[] sBootHash;

    private AttestationUtils() {}

    public static byte[] getBootKey() {
        if (sBootKey == null) {
            sBootKey = generateRandomBytes(32);
        }
        return sBootKey;
    }

    public static byte[] getBootHash() {
        if (sBootHash == null) {
            sBootHash = getBootHashFromProp();
            if (sBootHash == null) {
                sBootHash = extractBootHashFromTee();
            }
            if (sBootHash == null) {
                Log.w(TAG, "Failed to get boot hash from prop and TEE, using random bytes");
                sBootHash = generateRandomBytes(32);
            }
        }
        return sBootHash;
    }

    public static void initBootHash() {
        Log.i(TAG, "initBootHash: Starting boot hash initialization");
        byte[] hash = getBootHashFromProp();
        if (hash != null) {
            sBootHash = hash;
            Log.i(TAG, "initBootHash: Boot hash already set from prop: " + bytesToHex(hash));
            return;
        }
        Log.i(TAG, "initBootHash: No prop set, attempting TEE extraction");
        hash = extractBootHashFromTee();
        if (hash != null) {
            sBootHash = hash;
            Log.i(TAG, "initBootHash: TEE extraction successful, setting prop");
            setVbmetaDigestProp(bytesToHex(hash));
        } else {
            Log.e(TAG, "initBootHash: Failed to extract boot hash from TEE");
        }
    }

    private static void setVbmetaDigestProp(String digest) {
        try {
            SystemProperties.set("ro.boot.vbmeta.digest", digest);
            Log.i(TAG, "Set ro.boot.vbmeta.digest property to TEE boot hash");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set vbmeta.digest property", e);
        }
    }

    private static byte[] extractBootHashFromTee() {
        try {
            String alias = "trickystore_attestation_key";
            
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[32])
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();
            
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
            
            if (chain == null || chain.length == 0) {
                Log.e(TAG, "No certificate chain from AndroidKeyStore");
                ks.deleteEntry(alias);
                return null;
            }
            
            X509Certificate leafCert = (X509Certificate) chain[0];
            byte[] extValue = leafCert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
            
            ks.deleteEntry(alias);
            
            if (extValue == null) {
                Log.e(TAG, "No attestation extension in certificate");
                return null;
            }
            
            ASN1InputStream ais = new ASN1InputStream(extValue);
            ASN1OctetString octet = (ASN1OctetString) ais.readObject();
            ais.close();
            
            ASN1InputStream seqStream = new ASN1InputStream(octet.getOctets());
            ASN1Sequence keyDesc = (ASN1Sequence) seqStream.readObject();
            seqStream.close();
            
            ASN1Sequence teeEnforced = (ASN1Sequence) keyDesc.getObjectAt(7);
            
            for (int i = 0; i < teeEnforced.size(); i++) {
                ASN1TaggedObject tagged = (ASN1TaggedObject) teeEnforced.getObjectAt(i);
                if (tagged.getTagNo() == 704) {
                    ASN1Sequence rootOfTrust = (ASN1Sequence) tagged.getBaseObject();
                    if (rootOfTrust.size() >= 4) {
                        ASN1OctetString bootHashOctet = (ASN1OctetString) rootOfTrust.getObjectAt(3);
                        byte[] hash = bootHashOctet.getOctets();
                        Log.i(TAG, "Extracted boot hash from TEE: " + bytesToHex(hash));
                        return hash;
                    }
                }
            }
            
            Log.e(TAG, "RootOfTrust not found in TEE enforced list");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract boot hash from TEE", e);
            return null;
        }
    }

    public static byte[] getBootHashFromProp() {
        String digest = SystemProperties.get("ro.boot.vbmeta.digest", null);
        if (digest == null || digest.isEmpty() || digest.length() != 64) {
            return null;
        }
        try {
            return hexStringToByteArray(digest);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse vbmeta.digest", e);
            return null;
        }
    }

    public static int getOsVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q: return 100000;
            case Build.VERSION_CODES.R: return 110000;
            case Build.VERSION_CODES.S: return 120000;
            case Build.VERSION_CODES.S_V2: return 120100;
            case Build.VERSION_CODES.TIRAMISU: return 130000;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE: return 140000;
            case Build.VERSION_CODES.VANILLA_ICE_CREAM: return 150000;
            default: return 160000;
        }
    }

    public static int getAttestVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q:
            case Build.VERSION_CODES.R:
                return 4;
            case Build.VERSION_CODES.S:
            case Build.VERSION_CODES.S_V2:
                return 100;
            case Build.VERSION_CODES.TIRAMISU:
                return 200;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE:
            case Build.VERSION_CODES.VANILLA_ICE_CREAM:
                return 300;
            default:
                return 400;
        }
    }

    public static int getKeymasterVersion() {
        int attestVersion = getAttestVersion();
        return attestVersion == 4 ? 41 : attestVersion;
    }

    public static int getPatchLevel(boolean isLong) {
        TrickyStoreService.CustomPatchLevel customLevel = 
            TrickyStoreService.getInstance().getCustomPatchLevel();
        if (customLevel != null && customLevel.system != null) {
            Integer parsed = parsePatchLevel(customLevel.system, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getVendorPatchLevel(boolean isLong) {
        TrickyStoreService.CustomPatchLevel customLevel = 
            TrickyStoreService.getInstance().getCustomPatchLevel();
        if (customLevel != null && customLevel.vendor != null) {
            Integer parsed = parsePatchLevel(customLevel.vendor, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getBootPatchLevel(boolean isLong) {
        TrickyStoreService.CustomPatchLevel customLevel = 
            TrickyStoreService.getInstance().getCustomPatchLevel();
        if (customLevel != null && customLevel.boot != null) {
            Integer parsed = parsePatchLevel(customLevel.boot, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    private static Integer parsePatchLevel(String value, boolean isLong) {
        if (value == null || value.equalsIgnoreCase("no") || value.equalsIgnoreCase("prop")) {
            return null;
        }

        String normalized = value.replace("-", "");
        try {
            if (normalized.length() == 8) {
                int year = Integer.parseInt(normalized.substring(0, 4));
                int month = Integer.parseInt(normalized.substring(4, 6));
                int day = Integer.parseInt(normalized.substring(6, 8));
                return isLong ? year * 10000 + month * 100 + day : year * 100 + month;
            } else if (normalized.length() == 6) {
                int year = Integer.parseInt(normalized.substring(0, 4));
                int month = Integer.parseInt(normalized.substring(4, 6));
                return isLong ? year * 10000 + month * 100 : year * 100 + month;
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse patch level: " + value, e);
        }
        return null;
    }

    public static int convertPatchLevel(String patchString, boolean isLong) {
        try {
            String[] parts = patchString.split("-");
            if (isLong && parts.length >= 3) {
                return Integer.parseInt(parts[0]) * 10000 + 
                       Integer.parseInt(parts[1]) * 100 + 
                       Integer.parseInt(parts[2]);
            } else if (parts.length >= 2) {
                return Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch level format: " + patchString, e);
        }
        return 202404;
    }

    public static byte[] computeModuleHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(new byte[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute module hash", e);
            return new byte[32];
        }
    }

    private static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
