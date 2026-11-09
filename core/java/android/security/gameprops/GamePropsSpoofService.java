/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.gameprops;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @hide
 */
public final class GamePropsSpoofService {
    private static final String TAG = "GameProps";

    private static final long LOAD_RETRY_INTERVAL_MS = 1000;

    private static GamePropsSpoofService sInstance;

    private static final Object sUnsafeLock = new Object();
    private static boolean sUnsafeLookedUp;
    private static Object sUnsafe;
    private static Method sPutObject;
    private static Method sPutInt;
    private static Method sPutLong;
    private static Method sPutBoolean;
    private static Field sFieldOffset;
    private static Method sFieldGetOffset;

    private volatile boolean mEnabled = false;
    private volatile boolean mDebug = false;
    private volatile Map<String, Map<String, String>> mGameConfigs = Collections.emptyMap();
    private volatile boolean mConfigLoaded = false;

    private final Object mLoadLock = new Object();
    private long mLastLoadAttempt = 0;
    private boolean mLoadFailureLogged = false;

    private GamePropsSpoofService() {}

    /**
     * @hide
     */
    public static synchronized GamePropsSpoofService getInstance() {
        if (sInstance == null) {
            sInstance = new GamePropsSpoofService();
        }
        return sInstance;
    }

    /**
     * @hide
     */
    public void loadConfig() {
        synchronized (mLoadLock) {
            mLastLoadAttempt = SystemClock.uptimeMillis();
            loadConfigInternal();
        }
    }

    private void ensureLoaded() {
        if (mConfigLoaded) return;
        synchronized (mLoadLock) {
            if (mConfigLoaded) return;
            final long now = SystemClock.uptimeMillis();
            if (mLastLoadAttempt != 0 && now - mLastLoadAttempt < LOAD_RETRY_INTERVAL_MS) {
                return;
            }
            mLastLoadAttempt = now;
            loadConfigInternal();
        }
    }

    private void loadConfigInternal() {
        IActivityManager service = ActivityManager.getService();
        if (service == null) {
            if (!mLoadFailureLogged) {
                mLoadFailureLogged = true;
                Log.w(TAG, "ActivityManager not ready, gameprops config load deferred");
            }
            return;
        }

        String content;
        try {
            content = service.getSpoofGamePropsConfig();
        } catch (Throwable e) {
            if (!mLoadFailureLogged) {
                mLoadFailureLogged = true;
                Log.w(TAG, "Failed to fetch gameprops config from system_server: " + e);
            }
            return;
        }
        mLoadFailureLogged = false;

        if (content == null || content.trim().isEmpty()) {
            publish(false, false, Collections.emptyMap());
            Log.d(TAG, "No gameprops config in Settings.Secure");
            return;
        }

        try {
            Config config = parseJson(content);
            publish(config.enabled, config.debug, config.games);
            Log.i(TAG, "Game props config loaded, games=" + config.games.size()
                    + ", enabled=" + config.enabled);
            if (config.debug) {
                for (Map.Entry<String, Map<String, String>> e : config.games.entrySet()) {
                    Log.d(TAG, "Loaded config for " + e.getKey() + ": " + e.getValue());
                }
            }
        } catch (Throwable e) {
            publish(false, false, Collections.emptyMap());
            Log.e(TAG, "Failed to parse game props config, spoofing disabled", e);
        }
    }

    private void publish(boolean enabled, boolean debug, Map<String, Map<String, String>> games) {
        mGameConfigs = games;
        mDebug = debug;
        mEnabled = enabled;
        mConfigLoaded = true;
    }

    private static final class Config {
        boolean enabled;
        boolean debug;
        final Map<String, Map<String, String>> games = new HashMap<>();
    }

    private static Config parseJson(String content) throws IOException {
        Config config = new Config();
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                switch (key) {
                    case "enabled":
                        config.enabled = readBoolean(reader);
                        break;
                    case "debug":
                        config.debug = readBoolean(reader);
                        break;
                    case "games":
                        parseGames(reader, config.games);
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        }
        return config;
    }

    private static void parseGames(JsonReader reader, Map<String, Map<String, String>> out)
            throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue();
            return;
        }
        reader.beginObject();
        while (reader.hasNext()) {
            String packageName = reader.nextName();
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue();
                continue;
            }
            Map<String, String> gameProps = new HashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String propKey = reader.nextName();
                String propValue = readScalar(reader);
                if (propValue != null && !propValue.isEmpty()) {
                    gameProps.put(propKey, propValue);
                }
            }
            reader.endObject();

            if (!gameProps.isEmpty()) {
                out.put(packageName, Collections.unmodifiableMap(gameProps));
            }
        }
        reader.endObject();
    }

    private static String readScalar(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case STRING:
            case NUMBER:
                return reader.nextString();
            case BOOLEAN:
                return String.valueOf(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return null;
            default:
                reader.skipValue();
                return null;
        }
    }

    private static boolean readBoolean(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BOOLEAN:
                return reader.nextBoolean();
            case STRING: {
                String s = reader.nextString().trim();
                return "1".equals(s) || "true".equalsIgnoreCase(s);
            }
            case NUMBER:
                try {
                    return Double.parseDouble(reader.nextString()) != 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            case NULL:
                reader.nextNull();
                return false;
            default:
                reader.skipValue();
                return false;
        }
    }

    /**
     * @hide
     */
    public void spoofForPackage(String packageName) {
        if (packageName == null) return;
        ensureLoaded();
        if (!mEnabled || !mConfigLoaded) return;

        Map<String, String> gameProps = mGameConfigs.get(packageName);
        if (gameProps == null || gameProps.isEmpty()) {
            if (mDebug) Log.d(TAG, "No config found for package: " + packageName);
            return;
        }

        int ok = 0;
        for (Map.Entry<String, String> entry : gameProps.entrySet()) {
            if (spoofField(entry.getKey(), entry.getValue(), packageName)) ok++;
        }
        if (ok == gameProps.size()) {
            Log.i(TAG, "Spoofed " + ok + " props for " + packageName);
        } else {
            Log.w(TAG, "Spoofed " + ok + "/" + gameProps.size() + " props for " + packageName);
        }
    }

    private boolean spoofField(String fieldName, String value, String packageName) {
        if (value == null || value.isEmpty()) {
            if (mDebug) Log.d(TAG, fieldName + " is empty, skipping");
            return false;
        }

        Field field = findBuildField(fieldName);
        if (field == null) {
            Log.w(TAG, "[" + packageName + "] Unknown Build field: " + fieldName);
            return false;
        }
        if (!Modifier.isStatic(field.getModifiers())) {
            Log.w(TAG, "[" + packageName + "] Not a static field: " + fieldName);
            return false;
        }

        try {
            Object newValue = convert(field.getType(), value);
            if (newValue == null) {
                Log.w(TAG, "[" + packageName + "] Unsupported type " + field.getType()
                        + " for " + fieldName);
                return false;
            }

            field.setAccessible(true);
            Object oldValue;
            try {
                oldValue = field.get(null);
            } finally {
                field.setAccessible(false);
            }

            if (Objects.equals(oldValue, newValue)) {
                if (mDebug) Log.d(TAG, "[" + fieldName + "]: " + value + " (unchanged)");
                return true;
            }

            setStaticField(field, newValue);

            if (mDebug) {
                Log.d(TAG, "[" + packageName + "][" + fieldName + "]: "
                        + oldValue + " -> " + newValue);
            }
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to spoof " + fieldName + " for " + packageName + ": " + e);
            return false;
        }
    }

    private static Field findBuildField(String fieldName) {
        Field field = getDeclaredField(Build.class, fieldName);
        return field != null ? field : getDeclaredField(Build.VERSION.class, fieldName);
    }

    private static Field getDeclaredField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Object convert(Class<?> type, String value) {
        if (type == String.class) return value;
        String v = value.trim();
        if (type == int.class) return Integer.parseInt(v);
        if (type == long.class) return Long.parseLong(v);
        if (type == boolean.class) {
            if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
            throw new IllegalArgumentException("Not a boolean: " + value);
        }
        return null;
    }

    private static boolean initUnsafe() {
        synchronized (sUnsafeLock) {
            if (sUnsafeLookedUp) {
                return sUnsafe != null && sPutObject != null
                        && (sFieldOffset != null || sFieldGetOffset != null);
            }
            sUnsafeLookedUp = true;

            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Object unsafe = null;
                for (String name : new String[] { "theUnsafe", "THE_ONE" }) {
                    try {
                        Field f = unsafeClass.getDeclaredField(name);
                        f.setAccessible(true);
                        unsafe = f.get(null);
                        if (unsafe != null) break;
                    } catch (Throwable ignored) {
                    }
                }
                if (unsafe == null) {
                    try {
                        unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
                    } catch (Throwable ignored) {
                    }
                }
                if (unsafe == null) {
                    Log.w(TAG, "sun.misc.Unsafe instance unavailable");
                    return false;
                }

                sPutObject = unsafeClass.getMethod("putObject",
                        Object.class, long.class, Object.class);
                sPutInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
                sPutLong = unsafeClass.getMethod("putLong", Object.class, long.class, long.class);
                try {
                    sPutBoolean = unsafeClass.getMethod("putBoolean",
                            Object.class, long.class, boolean.class);
                } catch (NoSuchMethodException ignored) {
                }
                sUnsafe = unsafe;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to resolve sun.misc.Unsafe: " + t);
                sUnsafe = null;
                return false;
            }

            try {
                Field f = Field.class.getDeclaredField("offset");
                f.setAccessible(true);
                sFieldOffset = f;
            } catch (Throwable ignored) {
            }
            if (sFieldOffset == null) {
                try {
                    Method m = Field.class.getDeclaredMethod("getOffset");
                    m.setAccessible(true);
                    sFieldGetOffset = m;
                } catch (Throwable ignored) {
                }
            }
            if (sFieldOffset == null && sFieldGetOffset == null) {
                Log.w(TAG, "Unable to resolve java.lang.reflect.Field offset");
                return false;
            }
            return true;
        }
    }

    private static long fieldOffset(Field field) throws ReflectiveOperationException {
        if (sFieldOffset != null) {
            return ((Number) sFieldOffset.get(field)).longValue();
        }
        return ((Number) sFieldGetOffset.invoke(field)).longValue();
    }

    private static void putStaticViaUnsafe(Field field, Object value)
            throws ReflectiveOperationException {
        if (!initUnsafe()) {
            throw new IllegalStateException("Unsafe fallback unavailable");
        }
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(field.getName() + " is not static");
        }

        Object base = field.getDeclaringClass();
        long offset = fieldOffset(field);
        Class<?> type = field.getType();

        if (!type.isPrimitive()) {
            sPutObject.invoke(sUnsafe, base, offset, value);
        } else if (type == int.class) {
            sPutInt.invoke(sUnsafe, base, offset, ((Number) value).intValue());
        } else if (type == long.class) {
            sPutLong.invoke(sUnsafe, base, offset, ((Number) value).longValue());
        } else if (type == boolean.class && sPutBoolean != null) {
            sPutBoolean.invoke(sUnsafe, base, offset, (Boolean) value);
        } else {
            throw new IllegalArgumentException("Unsupported static type " + type);
        }
    }

    private static void setStaticField(Field field, Object value) throws Exception {
        field.setAccessible(true);
        try {
            try {
                field.set(null, value);
            } catch (IllegalAccessException e) {
                putStaticViaUnsafe(field, value);
            }
            Object now = field.get(null);
            if (!Objects.equals(now, value)) {
                throw new IllegalStateException("write to " + field.getName()
                        + " did not stick (still " + now + ")");
            }
        } finally {
            field.setAccessible(false);
        }
    }

    /**
     * @hide
     */
    public boolean isEnabled() {
        ensureLoaded();
        return mEnabled && mConfigLoaded;
    }

    /**
     * @hide
     */
    public boolean hasConfigForPackage(String packageName) {
        if (packageName == null) return false;
        ensureLoaded();
        return mGameConfigs.containsKey(packageName);
    }

    /**
     * @hide
     */
    public Map<String, Map<String, String>> getAllGameConfigs() {
        ensureLoaded();
        return new HashMap<>(mGameConfigs);
    }

    /**
     * @hide
     */
    public boolean isConfigLoaded() {
        return mConfigLoaded;
    }
}
