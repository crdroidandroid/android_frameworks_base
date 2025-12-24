package android.security.trickystore;

import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.security.MessageDigest;
import java.security.SecureRandom;

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
                sBootHash = generateRandomBytes(32);
            }
        }
        return sBootHash;
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
