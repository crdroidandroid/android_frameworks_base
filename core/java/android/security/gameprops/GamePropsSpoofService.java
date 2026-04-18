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
import android.os.Build;
import android.os.RemoteException;
import android.util.JsonReader;
import android.util.Log;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public final class GamePropsSpoofService {
    private static final String TAG = "GameProps";

    private static GamePropsSpoofService sInstance;

    private volatile boolean mEnabled = false;
    private volatile boolean mDebug = false;
    private final Map<String, Map<String, String>> mGameConfigs = new ConcurrentHashMap<>();
    private volatile boolean mConfigLoaded = false;

    private GamePropsSpoofService() {}

    /**
     * @hide
     */
    public static synchronized GamePropsSpoofService getInstance() {
        if (sInstance == null) {
            sInstance = new GamePropsSpoofService();
            sInstance.loadConfig();
        }
        return sInstance;
    }

    /**
     * @hide
     */
    public void loadConfig() {
        mGameConfigs.clear();
        mEnabled = false;
        mConfigLoaded = false;

        String content;
        try {
            content = ActivityManager.getService().getSpoofGamePropsConfig();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to fetch gameprops config from system_server", e);
            return;
        }

        if (content == null || content.isEmpty()) {
            Log.w(TAG, "No gameprops config in Settings.Secure");
            return;
        }

        try {
            parseJson(content);
            mConfigLoaded = true;
            Log.i(TAG, "Game props config loaded, games=" + mGameConfigs.size() + ", enabled=" + mEnabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse game props config", e);
        }
    }

    private void parseJson(String content) {
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();

                if ("enabled".equals(key)) {
                    mEnabled = reader.nextBoolean();
                } else if ("debug".equals(key)) {
                    mDebug = reader.nextBoolean();
                } else if ("games".equals(key)) {
                    parseGames(reader);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse JSON config", e);
        }
    }

    private void parseGames(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String packageName = reader.nextName();
            Map<String, String> gameProps = new HashMap<>();

            reader.beginObject();
            while (reader.hasNext()) {
                String propKey = reader.nextName();
                String propValue = reader.nextString();
                gameProps.put(propKey, propValue);
            }
            reader.endObject();

            if (!gameProps.isEmpty()) {
                mGameConfigs.put(packageName, gameProps);
                if (mDebug) {
                    Log.d(TAG, "Loaded config for " + packageName + ": " + gameProps.size() + " props");
                }
            }
        }
        reader.endObject();
    }

    /**
     * @hide
     */
    public void spoofForPackage(String packageName) {
        if (!mEnabled || !mConfigLoaded || packageName == null) {
            return;
        }

        Map<String, String> gameProps = mGameConfigs.get(packageName);
        if (gameProps == null || gameProps.isEmpty()) {
            if (mDebug) {
                Log.d(TAG, "No config found for package: " + packageName);
            }
            return;
        }

        if (mDebug) {
            Log.d(TAG, "Spoofing props for game: " + packageName);
        }

        for (Map.Entry<String, String> entry : gameProps.entrySet()) {
            spoofField(entry.getKey(), entry.getValue(), packageName);
        }
    }

    private void spoofField(String fieldName, String value, String packageName) {
        if (value == null || value.isEmpty()) {
            if (mDebug) Log.d(TAG, fieldName + " is empty, skipping");
            return;
        }

        try {
            Field field = getField(Build.class, fieldName);
            if (field == null) {
                field = getField(Build.VERSION.class, fieldName);
            }
            if (field == null) {
                if (mDebug) Log.d(TAG, "Field not found: " + fieldName);
                return;
            }

            field.setAccessible(true);
            String oldValue = String.valueOf(field.get(null));

            if (value.equals(oldValue)) {
                if (mDebug) Log.d(TAG, "[" + fieldName + "]: " + value + " (unchanged)");
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
                return;
            }

            field.set(null, newValue);

            if (mDebug) {
                Log.d(TAG, "[" + packageName + "][" + fieldName + "]: " + oldValue + " -> " + value);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof " + fieldName + " for " + packageName, e);
        }
    }

    private Field getField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * @hide
     */
    public boolean isEnabled() {
        return mEnabled && mConfigLoaded;
    }

    /**
     * @hide
     */
    public boolean hasConfigForPackage(String packageName) {
        return mGameConfigs.containsKey(packageName);
    }

    /**
     * @hide
     */
    public Map<String, Map<String, String>> getAllGameConfigs() {
        return new ConcurrentHashMap<>(mGameConfigs);
    }

    /**
     * @hide
     */
    public boolean isConfigLoaded() {
        return mConfigLoaded;
    }
}
