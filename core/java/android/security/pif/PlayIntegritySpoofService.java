package android.security.pif;

import android.app.ActivityThread;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.JsonReader;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** @hide */
public final class PlayIntegritySpoofService {
    private static final String TAG = "PIF";
    private static final String CONFIG_PATH = "/data/adb/playintegrityfix";

    private static final String[] PROP_FILES = {
        "custom.pif.prop",
        "custom.pif.json",
        "pif.prop",
        "pif.json"
    };

    private static final String DROIDGUARD_PACKAGE = "com.google.android.gms.unstable";
    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GPHOTOS_PACKAGE = "com.google.android.apps.photos";

    private static final Map<String, Object> PIXEL_XL_PROPS = Map.of(
        "BRAND", "google",
        "MANUFACTURER", "Google",
        "DEVICE", "marlin",
        "PRODUCT", "marlin",
        "HARDWARE", "marlin",
        "ID", "QP1A.191005.007.A3",
        "MODEL", "Pixel XL",
        "FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final Set<String> NEXUS_FEATURES = Set.of(
        "com.google.android.apps.photos.NEXUS_PRELOAD",
        "com.google.android.apps.photos.nexus_preload",
        "com.google.android.feature.PIXEL_EXPERIENCE",
        "com.google.android.feature.GOOGLE_BUILD",
        "com.google.android.feature.GOOGLE_EXPERIENCE"
    );

    private static final Set<String> PIXEL_FEATURES = Set.of(
        "com.google.android.feature.PIXEL_2022_EXPERIENCE",
        "com.google.android.feature.PIXEL_2022_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2023_EXPERIENCE",
        "com.google.android.feature.PIXEL_2023_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2024_EXPERIENCE",
        "com.google.android.feature.PIXEL_2024_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2025_EXPERIENCE",
        "com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2026_EXPERIENCE",
        "com.google.android.feature.PIXEL_2026_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2021_EXPERIENCE",
        "com.google.android.feature.PIXEL_2021_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2020_EXPERIENCE",
        "com.google.android.feature.PIXEL_2020_MIDYEAR_EXPERIENCE",
        "PIXEL_2017_PRELOAD",
        "PIXEL_2018_PRELOAD",
        "PIXEL_2019_MIDYEAR_PRELOAD",
        "PIXEL_2019_PRELOAD",
        "PIXEL_2020_EXPERIENCE",
        "PIXEL_2020_MIDYEAR_EXPERIENCE",
        "PIXEL_EXPERIENCE"
    );

    private static final String ROM_SIGNATURE_DATA = "MIIFyTCCA7GgAwIBAgIVALyxxl+zDS9SL68SzOr48309eAZyMA0GCSqGSIb3DQEBCwUAMHQxCzAJ" +
            "BgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQw" +
            "EgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAg" +
            "Fw0yMjExMDExODExMzVaGA8yMDUyMTEwMTE4MTEzNVowdDELMAkGA1UEBhMCVVMxEzARBgNVBAgT" +
            "CkNhbGlmb3JuaWExFjAUBgNVBAcTDU1vdW50YWluIFZpZXcxFDASBgNVBAoTC0dvb2dsZSBJbmMu" +
            "MRAwDgYDVQQLEwdBbmRyb2lkMRAwDgYDVQQDEwdBbmRyb2lkMIICIjANBgkqhkiG9w0BAQEFAAOC" +
            "Ag8AMIICCgKCAgEAsqtalIy/nctKlrhd1UVoDffFGnDf9GLi0QQhsVoJkfF16vDDydZJOycG7/kQ" +
            "ziRZhFdcoMrIYZzzw0ppBjsSe1AiWMuKXwTBaEtxN99S1xsJiW4/QMI6N6kMunydWRMsbJ6aAxi1" +
            "lVq0bxSwr8Sg/8u9HGVivfdG8OpUM+qjuV5gey5xttNLK3BZDrAlco8RkJZryAD40flmJZrWXJmc" +
            "r2HhJJUnqG4Z3MSziEgW1u1JnnY3f/BFdgYsA54SgdUGdQP3aqzSjIpGK01/vjrXvifHazSANjvl" +
            "0AUE5i6AarMw2biEKB2ySUDp8idC5w12GpqDrhZ/QkW8yBSa87KbkMYXuRA2Gq1fYbQx3YJraw0U" +
            "gZ4M3fFKpt6raxxM5j0sWHlULD7dAZMERvNESVrKG3tQ7B39WAD8QLGYc45DFEGOhKv5Fv8510h5" +
            "sXK502IvGpI4FDwz2rbtAgJ0j+16db5wCSW5ThvNPhCheyciajc8dU1B5tJzZN/ksBpzne4Xf9gO" +
            "LZ9ZU0+3Z5gHVvTS/YpxBFwiFpmL7dvGxew0cXGSsG5UTBlgr7i0SX0WhY4Djjo8IfPwrvvA0QaC" +
            "FamdYXKqBsSHgEyXS9zgGIFPt2jWdhaS+sAa//5SXcWro0OdiKPuwEzLgj759ke1sHRnvO735dYn" +
            "5whVbzlGyLBh3L0CAwEAAaNQME4wDAYDVR0TBAUwAwEB/zAdBgNVHQ4EFgQUU1eXQ7NoYKjvOQlh" +
            "5V8jHQMoxA8wHwYDVR0jBBgwFoAUU1eXQ7NoYKjvOQlh5V8jHQMoxA8wDQYJKoZIhvcNAQELBQAD" +
            "ggIBAHFIazRLs3itnZKllPnboSd6sHbzeJURKehx8GJPvIC+xWlwWyFO5+GHmgc3yh/SVd3Xja/k" +
            "8Ud59WEYTjyJJWTw0Jygx37rHW7VGn2HDuy/x0D+els+S8HeLD1toPFMepjIXJn7nHLhtmzTPlDW" +
            "DrhiaYsls/k5Izf89xYnI4euuOY2+1gsweJqFGfbznqyqy8xLyzoZ6bvBJtgeY+G3i/9Be14HseS" +
            "Na4FvI1Oze/l2gUu1IXzN6DGWR/lxEyt+TncJfBGKbjafYrfSh3zsE4N3TU7BeOL5INirOMjre/j" +
            "VgB1YQG5qLVaPoz6mdn75AbBBm5a5ahApLiKqzy/hP+1rWgw8Ikb7vbUqov/bnY3IlIU6XcPJTCD" +
            "b9aRZQkStvYpQd82XTyxD/T0GgRLnUj5Uv6iZlikFx1KNj0YNS2T3gyvL++J9B0Y6gAkiG0EtNpl" +
            "z7Pomsv5pVdmHVdKMjqWw5/6zYzVmu5cXFtR384Ti1qwML1xkD6TC3VIv88rKIEjrkY2c+v1frh9" +
            "fRJ2OmzXmML9NgHTjEiJR2Ib2iNrMKxkuTIs9oxKZgrJtJKvdU9qJJKM5PnZuNuHhGs6A/9gt9Oc" +
            "cetYeQvVSqeEmQluWfcunQn9C9Vwi2BJIiVJh4IdWZf5/e2PlSSQ9CJjz2bKI17pzdxOmjQfE0JS" +
            "F7Xt";

    private static PlayIntegritySpoofService sInstance;

    private volatile int mVerboseLogs = 0;
    private volatile boolean mSpoofBuild = true;
    private volatile boolean mSpoofProps = true;
    private volatile boolean mSpoofProvider = true;
    private volatile boolean mSpoofSignature = false;
    private volatile boolean mSpoofVendingBuild = true;
    private volatile boolean mSpoofVendingSdk = false;
    private volatile boolean mSpoofPhotos = false;
    private volatile boolean mDebug = false;

    private final Map<String, String> mBuildFields = new ConcurrentHashMap<>();
    private final Map<String, String> mSystemProps = new ConcurrentHashMap<>();

    private volatile boolean mConfigLoaded = false;
    private volatile boolean mSignatureSpoofed = false;

    private PlayIntegritySpoofService() {}

    public static synchronized PlayIntegritySpoofService getInstance() {
        if (sInstance == null) {
            sInstance = new PlayIntegritySpoofService();
            sInstance.loadConfig();
        }
        return sInstance;
    }

    public void loadConfig() {
        mBuildFields.clear();
        mSystemProps.clear();

        File configFile = findConfigFile();
        if (configFile == null) {
            Log.w(TAG, "No PIF config file found");
            mConfigLoaded = false;
            return;
        }

        try {
            String content = readFile(configFile);
            if (content == null || content.isEmpty()) {
                mConfigLoaded = false;
                return;
            }

            if (configFile.getName().endsWith(".json")) {
                parseJson(content);
            } else {
                parseProp(content);
            }

            mConfigLoaded = true;
            Log.i(TAG, "PIF config loaded from " + configFile.getAbsolutePath() 
                + ", fields=" + mBuildFields.size() + ", props=" + mSystemProps.size());

        } catch (Exception e) {
            Log.e(TAG, "Failed to load PIF config", e);
        }
    }

    private File findConfigFile() {
        File dir = new File(CONFIG_PATH);
        if (!dir.exists()) {
            return null;
        }

        for (String fileName : PROP_FILES) {
            File file = new File(dir, fileName);
            if (file.exists() && file.canRead()) {
                return file;
            }
        }
        return null;
    }

    private String readFile(File file) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read config file", e);
            return null;
        }
        return content.toString();
    }

    private void parseProp(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int eqIdx = line.indexOf('=');
            if (eqIdx <= 0) continue;

            String key = line.substring(0, eqIdx).trim();
            String value = line.substring(eqIdx + 1).trim();

            int commentIdx = value.indexOf('#');
            if (commentIdx >= 0) {
                value = value.substring(0, commentIdx).trim();
            }

            if (value.isEmpty()) continue;

            processKeyValue(key, value);
        }
    }

    private void parseJson(String content) {
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                String value = reader.nextString();
                processKeyValue(key, value);
            }
            reader.endObject();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse JSON config", e);
        }
    }

    private void processKeyValue(String key, String value) {
        switch (key) {
            case "verboseLogs":
            case "VERBOSE_LOGS":
                try {
                    mVerboseLogs = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    mVerboseLogs = 0;
                }
                break;
            case "spoofBuild":
                mSpoofBuild = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofProps":
                mSpoofProps = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofProvider":
                mSpoofProvider = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofSignature":
                mSpoofSignature = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofVendingBuild":
                mSpoofVendingBuild = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofVendingSdk":
                mSpoofVendingSdk = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "spoofPhotos":
                mSpoofPhotos = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            case "DEBUG":
                mDebug = "1".equals(value) || "true".equalsIgnoreCase(value);
                break;
            default:
                if (key.contains(".") || key.startsWith("*")) {
                    mSystemProps.put(key, value);
                } else {
                    mBuildFields.put(key, value);
                }
                break;
        }
    }

    public boolean shouldSpoof(String processName) {
        if (!mConfigLoaded) return false;
        return DROIDGUARD_PACKAGE.equals(processName) || VENDING_PACKAGE.equals(processName);
    }

    public boolean isGmsProcess(String dataDir) {
        if (dataDir == null) return false;
        return dataDir.endsWith("/" + GMS_PACKAGE) || dataDir.endsWith("/" + VENDING_PACKAGE);
    }

    public boolean isDroidGuard(String processName) {
        return DROIDGUARD_PACKAGE.equals(processName);
    }

    public boolean isVending(String processName) {
        return VENDING_PACKAGE.equals(processName);
    }

    public void spoofBuildFields(String processName) {
        if (!mConfigLoaded) return;

        boolean isVending = isVending(processName);
        boolean isDroidGuard = isDroidGuard(processName);

        if (!isDroidGuard && !isVending) return;

        if (isVending) {
            if (mSpoofVendingSdk) {
                spoofSdkInt();
            }
            if (mSpoofVendingBuild && !mSpoofVendingSdk) {
                for (Map.Entry<String, String> entry : mBuildFields.entrySet()) {
                    spoofField(entry.getKey(), entry.getValue(), "PS");
                }
            }
            return;
        }

        if (!mSpoofBuild) {
            if (mVerboseLogs > 0) Log.d(TAG, "Build spoofing disabled");
            return;
        }

        for (Map.Entry<String, String> entry : mBuildFields.entrySet()) {
            spoofField(entry.getKey(), entry.getValue(), "DG");
        }
    }

    public void spoofSignature() {
        if (!mSpoofSignature || mSignatureSpoofed) return;

        Signature spoofedSignature = new Signature(Base64.decode(ROM_SIGNATURE_DATA, Base64.DEFAULT));
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> customCreator = new CustomPackageInfoCreator(originalCreator, spoofedSignature);

        try {
            Field creatorField = findField(PackageInfo.class, "CREATOR");
            creatorField.setAccessible(true);
            creatorField.set(null, customCreator);
            creatorField.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't replace PackageInfoCreator: " + e);
            return;
        }

        try {
            Field cacheField = findField(PackageManager.class, "sPackageInfoCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache != null) {
                Method clearMethod = cache.getClass().getMethod("clear");
                clearMethod.invoke(cache);
            }
            cacheField.setAccessible(false);
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Couldn't clear PackageInfoCache: " + e);
        }

        try {
            Field creatorsField = findField(Parcel.class, "mCreators");
            creatorsField.setAccessible(true);
            Map<?, ?> mCreators = (Map<?, ?>) creatorsField.get(null);
            if (mCreators != null) mCreators.clear();
            creatorsField.setAccessible(false);
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Couldn't clear Parcel mCreators: " + e);
        }

        try {
            Field creatorsField = findField(Parcel.class, "sPairedCreators");
            creatorsField.setAccessible(true);
            Map<?, ?> sPairedCreators = (Map<?, ?>) creatorsField.get(null);
            if (sPairedCreators != null) sPairedCreators.clear();
            creatorsField.setAccessible(false);
        } catch (Exception e) {
            if (mDebug) Log.d(TAG, "Couldn't clear Parcel sPairedCreators: " + e);
        }

        mSignatureSpoofed = true;
        Log.i(TAG, "Signature spoofing enabled via Parcelable.Creator");
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> currentClass = clazz;
        while (currentClass != null && !currentClass.equals(Object.class)) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found");
    }

    private void spoofSdkInt() {
        try {
            Field field = Build.VERSION.class.getDeclaredField("SDK_INT");
            field.setAccessible(true);
            int oldValue = field.getInt(null);
            int targetSdk = Math.min(oldValue, 32);
            if (oldValue != targetSdk) {
                field.set(null, targetSdk);
                Log.d(TAG + "/Java:PS", "[SDK_INT]: " + oldValue + " -> " + targetSdk);
            }
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof SDK_INT", e);
        }
    }

    private void spoofField(String fieldName, String value, String logSuffix) {
        if (value == null || value.isEmpty()) {
            if (mVerboseLogs > 0) Log.d(TAG, fieldName + " is empty, skipping");
            return;
        }

        try {
            Field field = null;
            String oldValue = null;

            if (hasField(Build.class, fieldName)) {
                field = Build.class.getDeclaredField(fieldName);
            } else if (hasField(Build.VERSION.class, fieldName)) {
                field = Build.VERSION.class.getDeclaredField(fieldName);
            } else {
                if (mVerboseLogs > 1) Log.d(TAG, "Field not found: " + fieldName);
                return;
            }

            field.setAccessible(true);
            oldValue = String.valueOf(field.get(null));

            if (value.equals(oldValue)) {
                if (mVerboseLogs > 2) Log.d(TAG, "[" + fieldName + "]: " + value + " (unchanged)");
                field.setAccessible(false);
                return;
            }

            Class<?> fieldType = field.getType();
            Object newValue;

            if (fieldType == String.class) {
                newValue = value;
            } else if (fieldType == int.class) {
                newValue = Integer.parseInt(value);
            } else if (fieldType == long.class) {
                newValue = Long.parseLong(value);
            } else if (fieldType == boolean.class) {
                newValue = Boolean.parseBoolean(value);
            } else {
                Log.w(TAG, "Unsupported field type: " + fieldType);
                field.setAccessible(false);
                return;
            }

            field.set(null, newValue);
            field.setAccessible(false);

            Log.d(TAG + "/Java:" + logSuffix, "[" + fieldName + "]: " + oldValue + " -> " + value);

        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof " + fieldName, e);
        }
    }

    private boolean hasField(Class<?> clazz, String fieldName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals(fieldName)) return true;
        }
        return false;
    }

    public String getSpoofedProperty(String key) {
        if (!mSpoofProps || !mConfigLoaded) return null;

        String value = mSystemProps.get(key);
        if (value != null) return value;

        for (Map.Entry<String, String> entry : mSystemProps.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.startsWith("*") && key.endsWith(pattern.substring(1))) {
                return entry.getValue();
            }
        }

        return null;
    }

    public boolean isSpoofSignatureEnabled() {
        return mSpoofSignature && mConfigLoaded;
    }

    public boolean isSpoofProviderEnabled() {
        return mSpoofProvider && mConfigLoaded;
    }

    public int getVerboseLogs() {
        return mVerboseLogs;
    }

    public Map<String, String> getBuildFields() {
        return mBuildFields;
    }

    public Map<String, String> getSystemProps() {
        return mSystemProps;
    }

    public boolean isConfigLoaded() {
        return mConfigLoaded;
    }

    public byte[] getRomSignatureBytes() {
        return Base64.decode(ROM_SIGNATURE_DATA, Base64.DEFAULT);
    }

    public boolean shouldSpoofPhotos(String packageName) {
        return mConfigLoaded && mSpoofPhotos && TextUtils.equals(GPHOTOS_PACKAGE, packageName);
    }

    public void spoofPhotosProps() {
        for (Map.Entry<String, Object> entry : PIXEL_XL_PROPS.entrySet()) {
            spoofField(entry.getKey(), String.valueOf(entry.getValue()), "Photos");
        }
        Log.i(TAG, "Photos spoofing enabled - device appears as Pixel XL");
    }

    public boolean hasSystemFeature(String name, int version) {
        if (!isPixelDevice() && PIXEL_FEATURES.contains(name)) return false;
        return NEXUS_FEATURES.contains(name);
    }

    private static boolean isPixelDevice() {
        return SystemProperties.get("ro.soc.manufacturer", "").equalsIgnoreCase("google");
    }

    public void logBuildFields() {
        if (mVerboseLogs < 100) return;
        for (Field field : Build.class.getDeclaredFields()) {
            try {
                Log.d(TAG, "Build." + field.getName() + " = " + field.get(null));
            } catch (Exception e) {
            }
        }
        for (Field field : Build.VERSION.class.getDeclaredFields()) {
            try {
                Log.d(TAG, "Build.VERSION." + field.getName() + " = " + field.get(null));
            } catch (Exception e) {
            }
        }
    }

    private static class CustomPackageInfoCreator implements Parcelable.Creator<PackageInfo> {
        private final Parcelable.Creator<PackageInfo> originalCreator;
        private final Signature spoofedSignature;

        CustomPackageInfoCreator(Parcelable.Creator<PackageInfo> originalCreator, Signature spoofedSignature) {
            this.originalCreator = originalCreator;
            this.spoofedSignature = spoofedSignature;
        }

        @Override
        @SuppressWarnings("deprecation")
        public PackageInfo createFromParcel(Parcel source) {
            PackageInfo packageInfo = originalCreator.createFromParcel(source);
            if ("android".equals(packageInfo.packageName)) {
                if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                    packageInfo.signatures[0] = spoofedSignature;
                }
                if (packageInfo.signingInfo != null) {
                    Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                    if (signaturesArray != null && signaturesArray.length > 0) {
                        signaturesArray[0] = spoofedSignature;
                    }
                }
            }
            return packageInfo;
        }

        @Override
        public PackageInfo[] newArray(int size) {
            return originalCreator.newArray(size);
        }
    }
}
