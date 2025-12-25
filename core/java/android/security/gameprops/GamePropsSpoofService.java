package android.security.gameprops;

import android.os.Build;
import android.util.JsonReader;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public final class GamePropsSpoofService {
    private static final String TAG = "GameProps";
    private static final String CONFIG_PATH = "/data/adb/gameprops";
    private static final String CONFIG_FILE = "gameprops.json";

    private static GamePropsSpoofService sInstance;

    private boolean mEnabled = false;
    private boolean mDebug = false;
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

        File configFile = new File(CONFIG_PATH, CONFIG_FILE);
        if (!configFile.exists() || !configFile.canRead()) {
            Log.w(TAG, "Config file not found or not readable: " + configFile.getAbsolutePath());
            mConfigLoaded = false;
            return;
        }

        try {
            String content = readFile(configFile);
            if (content == null || content.isEmpty()) {
                mConfigLoaded = false;
                return;
            }

            parseJson(content);
            mConfigLoaded = true;
            Log.i(TAG, "Game props config loaded, games=" + mGameConfigs.size() + ", enabled=" + mEnabled);

        } catch (Exception e) {
            Log.e(TAG, "Failed to load game props config", e);
            mConfigLoaded = false;
        }
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
            Map<String, String> gameProps = new ConcurrentHashMap<>();
            
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

        Log.i(TAG, "Spoofing props for game: " + packageName);
        
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
            Field field = null;
            String oldValue = null;

            if (hasField(Build.class, fieldName)) {
                field = Build.class.getDeclaredField(fieldName);
            } else if (hasField(Build.VERSION.class, fieldName)) {
                field = Build.VERSION.class.getDeclaredField(fieldName);
            } else {
                if (mDebug) Log.d(TAG, "Field not found: " + fieldName);
                return;
            }

            field.setAccessible(true);
            oldValue = String.valueOf(field.get(null));

            if (value.equals(oldValue)) {
                if (mDebug) Log.d(TAG, "[" + fieldName + "]: " + value + " (unchanged)");
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

            Log.d(TAG, "[" + packageName + "][" + fieldName + "]: " + oldValue + " -> " + value);

        } catch (Exception e) {
            Log.e(TAG, "Failed to spoof " + fieldName + " for " + packageName, e);
        }
    }

    private boolean hasField(Class<?> clazz, String fieldName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals(fieldName)) return true;
        }
        return false;
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
