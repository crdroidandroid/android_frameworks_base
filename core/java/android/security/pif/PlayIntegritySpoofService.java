package android.security.pif;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.IActivityManager;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** @hide */
public final class PlayIntegritySpoofService {
    private static final String TAG = "PIF";

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
    private volatile boolean mSpoofProvider = false;
    private volatile boolean mSpoofSignature = false;
    private volatile boolean mSpoofVendingBuild = true;
    private volatile boolean mSpoofVendingSdk = false;
    private volatile boolean mSpoofPhotos = false;
    private volatile boolean mDebug = false;

    private final Map<String, String> mBuildFields = new ConcurrentHashMap<>();
    private final Map<String, String> mSystemProps = new ConcurrentHashMap<>();

    private volatile String mDeviceInitialSdkInt = "";
    private volatile boolean mConfigLoaded = false;
    private volatile boolean mSignatureSpoofed = false;
    private volatile boolean mProviderSpoofed = false;
    private volatile boolean mPropsSpoofed = false;
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
        mDeviceInitialSdkInt = "";
        mConfigLoaded = false;

        IActivityManager service = ActivityManager.getService();
        if (service == null) {
            Log.w(TAG, "ActivityManager not ready, skipping PIF config load");
            return;
        }

        String content;
        try {
            content = service.getSpoofPifConfig();
        } catch (Throwable e) {
            Log.e(TAG, "Failed to fetch PIF config from system_server", e);
            return;
        }

        if (content == null || content.isEmpty()) {
            Log.w(TAG, "No PIF config in Settings.Secure");
            return;
        }

        try {
            String trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                parseJson(content);
            } else {
                parseProp(content);
            }

            String fingerprint = mBuildFields.get("FINGERPRINT");
            if (fingerprint != null && !fingerprint.isEmpty()) {
                expandFingerprint(fingerprint);
            }

            mConfigLoaded = true;
            Log.i(TAG, "PIF config loaded, fields=" + mBuildFields.size()
                    + ", props=" + mSystemProps.size());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load PIF config", e);
        }
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
                String value;
                switch (reader.peek()) {
                    case BOOLEAN:
                        value = String.valueOf(reader.nextBoolean());
                        break;
                    case NUMBER:
                        value = reader.nextString();
                        break;
                    case NULL:
                        reader.nextNull();
                        continue;
                    default:
                        value = reader.nextString();
                        break;
                }
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
            case "DEVICE_INITIAL_SDK_INT":
                mDeviceInitialSdkInt = value;
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

    private void expandFingerprint(String fingerprint) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i++) {
            char ch = fingerprint.charAt(i);
            if (ch == '/' || ch == ':') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());

        String[] keys = {
            "BRAND", "PRODUCT", "DEVICE", "RELEASE", "ID", "INCREMENTAL", "TYPE", "TAGS"
        };
        for (int i = 0; i < keys.length && i < parts.size(); i++) {
            String val = parts.get(i);
            if (!val.isEmpty()) {
                mBuildFields.put(keys[i], val);
            }
        }
    }
    private static String resolveDeviceInitialSdk(String device, String configuredSdk) {
        if (device != null) {
            switch (device) {
                case "oriole":
                case "raven":
                    return "31";
                case "bluejay":
                    return "32";
                case "panther":
                case "cheetah":
                case "lynx":
                case "felix":
                case "tangorpro":
                    return "33";
                case "shiba":
                case "husky":
                case "akita":
                case "tokay":
                case "caiman":
                case "komodo":
                case "comet":
                    return "34";
                case "tegu":
                case "stallion":
                    return "35";
                case "frankel":
                case "blazer":
                case "mustang":
                case "rango":
                    return "36";
                case "cubs":
                case "grizzly":
                case "kodiak":
                case "yogi":
                    return "37";
                default:
                    break;
            }
        }
        return (configuredSdk != null && !configuredSdk.isEmpty()) ? configuredSdk : "32";
    }
    public static boolean isTargetProcess(String processName) {
        return DROIDGUARD_PACKAGE.equals(processName) || VENDING_PACKAGE.equals(processName);
    }

    public static boolean isPhotosPackage(String packageName) {
        return TextUtils.equals(GPHOTOS_PACKAGE, packageName);
    }

    public boolean shouldSpoof(String processName) {
        if (!mConfigLoaded) return false;
        return isTargetProcess(processName);
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
            if (mSpoofVendingBuild) {
                for (Map.Entry<String, String> entry : mBuildFields.entrySet()) {
                    spoofField(entry.getKey(), entry.getValue(), "PS");
                }
            } else if (mSpoofVendingSdk) {
                spoofSdkInt();
            }
            return;
        }

        if (!mSpoofBuild) {
            if (mVerboseLogs > 0) Log.d(TAG, "Build spoofing disabled");
            return;
        }

        reSpoofBuildFields();
    }

    public void reSpoofBuildFields() {
        if (!mConfigLoaded || !mSpoofBuild) return;
        for (Map.Entry<String, String> entry : mBuildFields.entrySet()) {
            spoofField(entry.getKey(), entry.getValue(), "DG");
        }
    }

    public void spoofProperties() {
        if (!mConfigLoaded || !mSpoofProps || mPropsSpoofed) return;

        Map<String, String> props = new HashMap<>(mSystemProps);

        props.putIfAbsent("init.svc.adbd", "stopped");
        props.putIfAbsent("sys.usb.state", "mtp");
        props.putIfAbsent("ro.board.first_api_level", "32");

        String initialSdk = resolveDeviceInitialSdk(
                mBuildFields.get("DEVICE"), mDeviceInitialSdkInt);
        if (initialSdk != null && !initialSdk.isEmpty()) {
            props.putIfAbsent("ro.product.first_api_level", initialSdk);
        }
        putPropIfPresent(props, "ro.build.fingerprint", mBuildFields.get("FINGERPRINT"));
        putPropIfPresent(props, "ro.build.id", mBuildFields.get("ID"));
        putPropIfPresent(props, "ro.product.device", mBuildFields.get("DEVICE"));
        putPropIfPresent(props, "ro.product.name", mBuildFields.get("PRODUCT"));
        putPropIfPresent(props, "ro.product.brand", mBuildFields.get("BRAND"));
        putPropIfPresent(props, "ro.product.model", mBuildFields.get("MODEL"));
        putPropIfPresent(props, "ro.build.version.security_patch", mBuildFields.get("SECURITY_PATCH"));

        if (props.isEmpty()) return;

        String[] keys = new String[props.size()];
        String[] values = new String[props.size()];
        int idx = 0;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            keys[idx] = entry.getKey();
            values[idx] = entry.getValue();
            idx++;
        }

        try {
            boolean ok = nativeSpoofProperties(keys, values, mDebug || mVerboseLogs > 0);
            if (ok) {
                mPropsSpoofed = true;
                Log.i(TAG, "Property area virtualization active (" + keys.length + " rules)");
            } else {
                Log.w(TAG, "Property area virtualization returned false");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to run nativeSpoofProperties", t);
        }
    }

    private static void putPropIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.putIfAbsent(key, value);
        }
    }

    public void spoofProvider() {
        if (!mConfigLoaded || !mSpoofProvider || mProviderSpoofed) return;

        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            Field keyStoreSpiField = keyStore.getClass().getDeclaredField("keyStoreSpi");
            keyStoreSpiField.setAccessible(true);
            CustomKeyStoreSpi.sDelegate = (KeyStoreSpi) keyStoreSpiField.get(keyStore);
            keyStoreSpiField.setAccessible(false);
        } catch (Throwable t) {
            Log.e(TAG, "Couldn't get keyStoreSpi field!", t);
            return;
        }

        Provider provider = Security.getProvider("AndroidKeyStore");
        if (provider == null) return;

        Provider customProvider = new CustomProvider(provider);
        Security.removeProvider("AndroidKeyStore");
        Security.insertProviderAt(customProvider, 1);
        mProviderSpoofed = true;
        Log.i(TAG, "KeyStore provider spoofing enabled");
    }

    private static native boolean nativeSpoofProperties(
            String[] keys, String[] values, boolean debug);

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

            if (field.getType() != String.class) {
                field.setAccessible(false);
                return;
            }

            field.set(null, value);
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
        if (key == null || !mSpoofProps || !mConfigLoaded) return null;

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
        return mConfigLoaded && mSpoofPhotos && isPhotosPackage(packageName);
    }

    public void spoofPhotosProps() {
        for (Map.Entry<String, Object> entry : PIXEL_XL_PROPS.entrySet()) {
            spoofField(entry.getKey(), String.valueOf(entry.getValue()), "Photos");
        }
        Log.i(TAG, "Photos spoofing enabled - device appears as Pixel XL");
    }

    public Boolean hasSystemFeature(String name, int version) {
        final String pkgName = ActivityThread.currentPackageName();
        if (shouldSpoofPhotos(pkgName)) {
            if (!isPixelDevice() && PIXEL_FEATURES.contains(name)) return false;
            return NEXUS_FEATURES.contains(name);
        }
        return null;
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

    private static final class CustomProvider extends Provider {
        CustomProvider(Provider provider) {
            super(provider.getName(), provider.getVersion(), provider.getInfo());
            putAll(provider);
            put("KeyStore.AndroidKeyStore", CustomKeyStoreSpi.class.getName());
        }

        @Override
        public synchronized Service getService(String type, String algorithm) {
            PlayIntegritySpoofService.getInstance().reSpoofBuildFields();
            return super.getService(type, algorithm);
        }
    }

    /** @hide */
    public static final class CustomKeyStoreSpi extends KeyStoreSpi {
        static volatile KeyStoreSpi sDelegate = null;

        @Override
        public Key engineGetKey(String alias, char[] password)
                throws NoSuchAlgorithmException, UnrecoverableKeyException {
            return sDelegate.engineGetKey(alias, password);
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                if (element.getClassName().toLowerCase(Locale.US).contains("droidguard")) {
                    Log.w(TAG, "DroidGuard invoked engineGetCertificateChain; throwing exception");
                    throw new UnsupportedOperationException();
                }
            }
            return sDelegate.engineGetCertificateChain(alias);
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            return sDelegate.engineGetCertificate(alias);
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            return sDelegate.engineGetCreationDate(alias);
        }

        @Override
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
                throws KeyStoreException {
            sDelegate.engineSetKeyEntry(alias, key, password, chain);
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain)
                throws KeyStoreException {
            sDelegate.engineSetKeyEntry(alias, key, chain);
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert)
                throws KeyStoreException {
            sDelegate.engineSetCertificateEntry(alias, cert);
        }

        @Override
        public void engineDeleteEntry(String alias) throws KeyStoreException {
            sDelegate.engineDeleteEntry(alias);
        }

        @Override
        public Enumeration<String> engineAliases() {
            return sDelegate.engineAliases();
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return sDelegate.engineContainsAlias(alias);
        }

        @Override
        public int engineSize() {
            return sDelegate.engineSize();
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            return sDelegate.engineIsKeyEntry(alias);
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            return sDelegate.engineIsCertificateEntry(alias);
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            return sDelegate.engineGetCertificateAlias(cert);
        }

        @Override
        public void engineStore(OutputStream stream, char[] password)
                throws IOException, NoSuchAlgorithmException, CertificateException {
            sDelegate.engineStore(stream, password);
        }

        @Override
        public void engineLoad(InputStream stream, char[] password)
                throws IOException, NoSuchAlgorithmException, CertificateException {
            sDelegate.engineLoad(stream, password);
        }
    }
}
