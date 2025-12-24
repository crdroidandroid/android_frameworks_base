package android.security.trickystore;

import android.os.FileObserver;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public class TrickyStoreService {
    private static final String TAG = "TrickyStoreService";
    private static final String CONFIG_PATH = "/data/adb/tricky_store";
    private static final String TARGET_FILE = "target.txt";
    private static final String KEYBOX_FILE = "keybox.xml";
    private static final String PATCHLEVEL_FILE = "security_patch.txt";

    private static TrickyStoreService sInstance;

    private final Set<String> mHackPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> mGeneratePackages = ConcurrentHashMap.newKeySet();
    private final Map<String, Mode> mPackageModes = new ConcurrentHashMap<>();

    private volatile Boolean mTeeBroken = null;
    private volatile CustomPatchLevel mCustomPatchLevel = null;

    private final KeyBoxManager mKeyBoxManager;
    private ConfigObserver mConfigObserver;

    /** @hide */
    public enum Mode {
        AUTO, LEAF_HACK, GENERATE
    }

    /** @hide */
    public static class CustomPatchLevel {
        public final String system;
        public final String vendor;
        public final String boot;
        public final String all;

        public CustomPatchLevel(String system, String vendor, String boot, String all) {
            this.system = system;
            this.vendor = vendor;
            this.boot = boot;
            this.all = all;
        }
    }

    private TrickyStoreService() {
        mKeyBoxManager = new KeyBoxManager();
    }

    public static synchronized TrickyStoreService getInstance() {
        if (sInstance == null) {
            sInstance = new TrickyStoreService();
            sInstance.initialize();
        }
        return sInstance;
    }

    public void initialize() {
        File root = new File(CONFIG_PATH);
        if (!root.exists()) {
            root.mkdirs();
        }

        File scopeFile = new File(root, TARGET_FILE);
        if (scopeFile.exists()) {
            updateTargetPackages(scopeFile);
        } else {
            Log.w(TAG, "target.txt not found at " + scopeFile.getAbsolutePath());
        }

        File keyboxFile = new File(root, KEYBOX_FILE);
        if (keyboxFile.exists()) {
            updateKeyBox(keyboxFile);
        } else {
            Log.w(TAG, "keybox.xml not found at " + keyboxFile.getAbsolutePath());
        }

        File patchFile = new File(root, PATCHLEVEL_FILE);
        updatePatchLevel(patchFile.exists() ? patchFile : null);

        mConfigObserver = new ConfigObserver(root);
        mConfigObserver.startWatching();

        Log.i(TAG, "TrickyStoreService initialized");
    }

    private void updateTargetPackages(File file) {
        mHackPackages.clear();
        mGeneratePackages.clear();
        mPackageModes.clear();

        if (file == null || !file.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.endsWith("!")) {
                    String pkg = line.substring(0, line.length() - 1).trim();
                    mGeneratePackages.add(pkg);
                    mPackageModes.put(pkg, Mode.GENERATE);
                } else if (line.endsWith("?")) {
                    String pkg = line.substring(0, line.length() - 1).trim();
                    mHackPackages.add(pkg);
                    mPackageModes.put(pkg, Mode.LEAF_HACK);
                } else {
                    mPackageModes.put(line, Mode.AUTO);
                }
            }
            Log.i(TAG, "Updated target packages: hack=" + mHackPackages + 
                  ", generate=" + mGeneratePackages + ", modes=" + mPackageModes);
        } catch (IOException e) {
            Log.e(TAG, "Failed to update target packages", e);
        }
    }

    private void updateKeyBox(File file) {
        if (file == null || !file.exists()) {
            mKeyBoxManager.clear();
            return;
        }

        try {
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            mKeyBoxManager.parseKeybox(content.toString());
            Log.i(TAG, "Keybox updated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to update keybox", e);
        }
    }

    private void updatePatchLevel(File file) {
        if (file == null || !file.exists()) {
            mCustomPatchLevel = null;
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    content.append(line).append("\n");
                }
            }

            String lines = content.toString().trim();
            if (lines.isEmpty()) {
                mCustomPatchLevel = null;
                return;
            }

            String[] parts = lines.split("\n");
            if (parts.length == 1 && !parts[0].contains("=")) {
                mCustomPatchLevel = new CustomPatchLevel(parts[0], parts[0], parts[0], parts[0]);
                return;
            }

            String system = null, vendor = null, boot = null, all = null;
            for (String part : parts) {
                int idx = part.indexOf('=');
                if (idx > 0) {
                    String key = part.substring(0, idx).trim().toLowerCase();
                    String value = part.substring(idx + 1).trim();
                    switch (key) {
                        case "system": system = value; break;
                        case "vendor": vendor = value; break;
                        case "boot": boot = value; break;
                        case "all": all = value; break;
                    }
                }
            }
            mCustomPatchLevel = new CustomPatchLevel(
                system != null ? system : all,
                vendor != null ? vendor : all,
                boot != null ? boot : all,
                all
            );
        } catch (IOException e) {
            Log.e(TAG, "Failed to update patch level", e);
        }
    }

    public boolean needHack(int callingUid, String[] packages) {
        if (packages == null) return false;
        for (String pkg : packages) {
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.LEAF_HACK) return true;
            if (mode == Mode.AUTO && mTeeBroken != null && !mTeeBroken) return true;
        }
        return false;
    }

    public boolean needGenerate(int callingUid, String[] packages) {
        if (packages == null) return false;
        for (String pkg : packages) {
            Mode mode = mPackageModes.get(pkg);
            if (mode == Mode.GENERATE) return true;
            if (mode == Mode.AUTO && mTeeBroken != null && mTeeBroken) return true;
        }
        return false;
    }

    public KeyBoxManager getKeyBoxManager() {
        return mKeyBoxManager;
    }

    public CustomPatchLevel getCustomPatchLevel() {
        return mCustomPatchLevel;
    }

    public boolean hasKeyboxes() {
        return mKeyBoxManager.hasKeyboxes();
    }

    private class ConfigObserver extends FileObserver {
        private final File mRoot;

        ConfigObserver(File root) {
            super(root, CLOSE_WRITE | DELETE | MOVED_FROM | MOVED_TO);
            mRoot = root;
        }

        @Override
        public void onEvent(int event, String path) {
            if (path == null) return;

            File file = null;
            if (event == CLOSE_WRITE || event == MOVED_TO) {
                file = new File(mRoot, path);
            }

            switch (path) {
                case TARGET_FILE:
                    updateTargetPackages(file);
                    break;
                case KEYBOX_FILE:
                    updateKeyBox(file);
                    break;
                case PATCHLEVEL_FILE:
                    updatePatchLevel(file);
                    break;
            }
        }
    }
}
