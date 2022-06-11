package android.os;

import android.annotation.Nullable;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;

public class OplusSystemProperties {

    private static final String TAG = "OplusSystemProperties";

    private ArrayList<String> mOplusPropertyPersistList;
    private ArrayList<String> mOplusPropertyReadOnlyList;
    private ArrayList<String> mOplusPropertySysList;

    private static OplusSystemProperties sInstance = null;

    @Nullable
    public static OplusSystemProperties getInstance() {
        if (sInstance == null) {
            sInstance = new OplusSystemProperties();
        }
        return sInstance;
    }

    private OplusSystemProperties() {
        this.mOplusPropertyReadOnlyList = new ArrayList<>();
        this.mOplusPropertyPersistList = new ArrayList<>();
        this.mOplusPropertySysList = new ArrayList<>();
    }

    private boolean isPredefinedProperty(String propertyName) {
        if (propertyName == null) {
            return false;
        }
        return true;
    }

    private static boolean isPredefinedOplusProperty(String propertyName) {
        if (Build.IS_DEBUGGABLE) {
            return getInstance().isPredefinedProperty(propertyName);
        }
        return true;
    }

    @Nullable
    public static String get(@Nullable String key) {
        if (isPredefinedOplusProperty(key)) {
            return SystemProperties.get(key);
        }
        Log.e(TAG, "Warning: This property is not predefined, prop:" + key);
        throw new IllegalArgumentException("Warning: This property is not predefined, prop:" + key);
    }

    @Nullable
    public static String get(@Nullable String key, @Nullable String def) {
        if (isPredefinedOplusProperty(key)) {
            return SystemProperties.get(key, def);
        }
        Log.e(TAG, "Warning: This property is not predefined, prop:" + key);
        throw new IllegalArgumentException("Warning: This property is not predefined, prop:" + key);
    }

    @Nullable
    public static int getInt(@Nullable String key, @Nullable int def) {
        if (isPredefinedOplusProperty(key)) {
            return SystemProperties.getInt(key, def);
        }
        Log.e(TAG, "Warning: This property is not predefined, prop:" + key);
        throw new IllegalArgumentException("Warning: This property is not predefined, prop:" + key);
    }

    @Nullable
    public static long getLong(@Nullable String key, @Nullable long def) {
        if (isPredefinedOplusProperty(key)) {
            return SystemProperties.getLong(key, def);
        }
        Log.e(TAG, "Warning: This property is not predefined, prop:" + key);
        throw new IllegalArgumentException("Warning: This property is not predefined, prop:" + key);
    }

    @Nullable
    public static boolean getBoolean(@Nullable String key, @Nullable boolean def) {
        if (isPredefinedOplusProperty(key)) {
            return SystemProperties.getBoolean(key, def);
        }
        Log.e(TAG, "Warning: This property is not predefined, prop:" + key);
        throw new IllegalArgumentException("Warning: This property is not predefined, prop:" + key);
    }

    public static void set(@Nullable String key, @Nullable String val) {
        if (isPredefinedOplusProperty(key)) {
            SystemProperties.set(key, val);
            return;
        }
        Log.e(TAG, "Warning: This property is not predefined, prop:" + key);
        throw new IllegalArgumentException("Warning: This property is not predefined, prop:" + key);
    }
}
