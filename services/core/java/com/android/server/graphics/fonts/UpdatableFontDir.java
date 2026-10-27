/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import static com.android.server.graphics.fonts.FontManagerService.SystemFontException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.FileUtils;
import android.os.LocaleList;
import android.system.ErrnoException;
import android.system.Os;
import android.text.FontConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;

import com.android.internal.util.android.FontController;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages set of updatable font files.
 *
 * <p>This class is not thread safe.
 */
final class UpdatableFontDir {

    private static final String TAG = "UpdatableFontDir";
    private static final String RANDOM_DIR_PREFIX = "~~";

    private static final String FONT_SIGNATURE_FILE = "font.fsv_sig";
    private static final String CUSTOM_FONT_MARKER_FILE = "custom.font";
    private static final String CUSTOM_FONT_FAMILY_NAME = "sans-serif";
    private static final long MAX_CUSTOM_FONT_FILE_SIZE = 64L * 1024L * 1024L;
    /** Framework string naming the lock screen clock family; the custom font never touches it. */
    private static final String CLOCK_FONT_FAMILY_RES = "config_clockFontFamily";

    /** Interface to mock font file access in tests. */
    interface FontFileParser {
        String getPostScriptName(File file) throws IOException;

        String buildFontFileName(File file) throws IOException;

        long getRevision(File file) throws IOException;

        void tryToCreateTypeface(File file) throws Throwable;
    }

    /** Interface to mock fs-verity in tests. */
    interface FsverityUtil {
        boolean isFromTrustedProvider(String path, byte[] pkcs7Signature);

        boolean hasFsverity(String path);

        void setUpFsverity(String path) throws IOException;

        boolean rename(File src, File dest);
    }

    /** Data class to hold font file path and revision. */
    private static final class FontFileInfo {
        private final File mFile;
        private final String mPsName;
        private final long mRevision;

        FontFileInfo(File file, String psName, long revision) {
            mFile = file;
            mPsName = psName;
            mRevision = revision;
        }

        public File getFile() {
            return mFile;
        }

        public String getPostScriptName() {
            return mPsName;
        }

        /** Returns the unique randomized font dir containing this font file. */
        public File getRandomizedFontDir() {
            return mFile.getParentFile();
        }

        public long getRevision() {
            return mRevision;
        }

        @Override
        public String toString() {
            return "FontFileInfo{mFile=" + mFile
                    + ", psName=" + mPsName
                    + ", mRevision=" + mRevision + '}';
        }
    }

    /**
     * Root directory for storing updated font files. Each font file is stored in a unique
     * randomized dir. The font file path would be {@code mFilesDir/~~{randomStr}/{fontFileName}}.
     */
    private final File mFilesDir;
    private final FontFileParser mParser;
    private final FsverityUtil mFsverityUtil;
    private final AtomicFile mConfigFile;
    private final Supplier<Long> mCurrentTimeSupplier;
    private final Function<Map<String, File>, FontConfig> mConfigSupplier;

    private long mLastModifiedMillis;
    private int mConfigVersion;

    /**
     * A mutable map containing mapping from font file name (e.g. "NotoColorEmoji.ttf") to {@link
     * FontFileInfo}. All files in this map are validated, and have higher revision numbers than
     * corresponding font files returned by {@link #mConfigSupplier}.
     */
    private final ArrayMap<String, FontFileInfo> mFontFileInfoMap = new ArrayMap<>();

    UpdatableFontDir(File filesDir, FontFileParser parser, FsverityUtil fsverityUtil,
            File configFile) {
        this(filesDir, parser, fsverityUtil, configFile,
                System::currentTimeMillis,
                (map) -> SystemFonts.getSystemFontConfig(map, 0, 0)
        );
    }

    // For unit testing
    UpdatableFontDir(File filesDir, FontFileParser parser, FsverityUtil fsverityUtil,
            File configFile, Supplier<Long> currentTimeSupplier,
            Function<Map<String, File>, FontConfig> configSupplier) {
        mFilesDir = filesDir;
        mParser = parser;
        mFsverityUtil = fsverityUtil;
        mConfigFile = new AtomicFile(configFile);
        mCurrentTimeSupplier = currentTimeSupplier;
        mConfigSupplier = configSupplier;
    }

    /**
     * Loads fonts from file system, validate them, and delete obsolete font files.
     * Note that this method may be called by multiple times in integration tests via {@link
     * FontManagerService#restart()}.
     */
    /* package */ void loadFontFileMap() {
        mFontFileInfoMap.clear();
        mLastModifiedMillis = 0;
        mConfigVersion = 1;
        boolean success = false;
        try {
            PersistentSystemFontConfig.Config config = readPersistentConfig();
            String activeCustomFont = null;
            for (FontUpdateRequest.Family family : config.fontFamilies) {
                if (CUSTOM_FONT_FAMILY_NAME.equals(family.getName())
                        && !family.getFonts().isEmpty()) {
                    activeCustomFont = family.getFonts().get(0).getPostScriptName();
                }
            }
            mLastModifiedMillis = config.lastModifiedMillis;

            File[] dirs = mFilesDir.listFiles();
            if (dirs == null) {
                // mFilesDir should be created by init script.
                Slog.e(TAG, "Could not read: " + mFilesDir);
                return;
            }
            FontConfig fontConfig = null;
            for (File dir : dirs) {
                if (!dir.getName().startsWith(RANDOM_DIR_PREFIX)) {
                    Slog.e(TAG, "Unexpected dir found: " + dir);
                    return;
                }
                if (!config.updatedFontDirs.contains(dir.getName())) {
                    Slog.i(TAG, "Deleting obsolete dir: " + dir);
                    FileUtils.deleteContentsAndDir(dir);
                    continue;
                }

                File signatureFile = new File(dir, FONT_SIGNATURE_FILE);
                if (!signatureFile.exists()) {
                    Slog.i(TAG, "The signature file is missing.");
                    return;
                }
                byte[] signature;
                try {
                    signature = Files.readAllBytes(Paths.get(signatureFile.getAbsolutePath()));
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to read signature file.");
                    return;
                }

                File customMarker = new File(dir, CUSTOM_FONT_MARKER_FILE);
                boolean isCustomFont = customMarker.isFile();
                File[] files = dir.listFiles();
                int expectedFileCount = isCustomFont ? 3 : 2;
                if (files == null || files.length != expectedFileCount) {
                    Slog.e(TAG, "Unexpected files in dir: " + dir);
                    return;
                }

                File fontFile = null;
                for (File file : files) {
                    if (!file.equals(signatureFile) && !file.equals(customMarker)) {
                        fontFile = file;
                        break;
                    }
                }
                if (fontFile == null) {
                    Slog.e(TAG, "Font file is missing in: " + dir);
                    return;
                }

                if (isCustomFont
                        && !Objects.equals(activeCustomFont, mParser.getPostScriptName(fontFile))) {
                    FileUtils.deleteContentsAndDir(dir);
                    continue;
                }

                FontFileInfo fontFileInfo = validateFontFile(fontFile, signature, isCustomFont);
                if (fontConfig == null) {
                    // Use preinstalled font config for checking revision number.
                    fontConfig = mConfigSupplier.apply(Collections.emptyMap());
                }
                addFileToMapIfSameOrNewer(fontFileInfo, fontConfig, true /* deleteOldFile */);
            }

            // Treat as error if post script name of font family was not installed.
            for (int i = 0; i < config.fontFamilies.size(); ++i) {
                FontUpdateRequest.Family family = config.fontFamilies.get(i);
                for (int j = 0; j < family.getFonts().size(); ++j) {
                    FontUpdateRequest.Font font = family.getFonts().get(j);
                    if (mFontFileInfoMap.containsKey(font.getPostScriptName())) {
                        continue;
                    }

                    if (fontConfig == null) {
                        fontConfig = mConfigSupplier.apply(Collections.emptyMap());
                    }

                    if (getFontByPostScriptName(font.getPostScriptName(), fontConfig) != null) {
                        continue;
                    }

                    Slog.e(TAG, "Unknown font that has PostScript name "
                            + font.getPostScriptName() + " is requested in FontFamily "
                            + family.getName());
                    return;
                }
            }

            success = true;
        } catch (Throwable t) {
            // If something happened during loading system fonts, clear all contents in finally
            // block. Here, just dumping errors.
            Slog.e(TAG, "Failed to load font mappings.", t);
        } finally {
            // Delete all files just in case if we find a problematic file.
            if (!success) {
                mFontFileInfoMap.clear();
                mLastModifiedMillis = 0;
                FileUtils.deleteContents(mFilesDir);
                mConfigFile.delete();
            }
        }
    }

    /**
     * Applies multiple {@link FontUpdateRequest}s in transaction.
     * If one of the request fails, the fonts and config are rolled back to the previous state
     * before this method is called.
     */
    public void update(List<FontUpdateRequest> requests) throws SystemFontException {
        for (FontUpdateRequest request : requests) {
            switch (request.getType()) {
                case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                    Objects.requireNonNull(request.getFd());
                    Objects.requireNonNull(request.getSignature());
                    break;
                case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                    Objects.requireNonNull(request.getFontFamily());
                    Objects.requireNonNull(request.getFontFamily().getName());
                    break;
            }
        }
        // Backup the mapping for rollback.
        ArrayMap<String, FontFileInfo> backupMap = new ArrayMap<>(mFontFileInfoMap);
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontUpdateRequest.Family> familyMap = new HashMap<>();
        for (int i = 0; i < curConfig.fontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.fontFamilies.get(i);
            familyMap.put(family.getName(), family);
        }

        long backupLastModifiedDate = mLastModifiedMillis;
        boolean success = false;
        try {
            for (FontUpdateRequest request : requests) {
                switch (request.getType()) {
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                        installFontFile(
                                request.getFd().getFileDescriptor(), request.getSignature());
                        break;
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                        FontUpdateRequest.Family family = request.getFontFamily();
                        familyMap.put(family.getName(), family);
                        break;
                }
            }

            // Before processing font family update, check all family points the available fonts.
            for (FontUpdateRequest.Family family : familyMap.values()) {
                if (resolveFontFilesForNamedFamily(family) == null) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_FONT_NOT_FOUND,
                            "Required fonts are not available");
                }
            }

            // Write config file.
            mLastModifiedMillis = mCurrentTimeSupplier.get();

            PersistentSystemFontConfig.Config newConfig = new PersistentSystemFontConfig.Config();
            newConfig.lastModifiedMillis = mLastModifiedMillis;
            for (FontFileInfo info : mFontFileInfoMap.values()) {
                newConfig.updatedFontDirs.add(info.getRandomizedFontDir().getName());
            }
            newConfig.fontFamilies.addAll(familyMap.values());
            writePersistentConfig(newConfig);
            mConfigVersion++;
            success = true;
        } finally {
            if (!success) {
                mFontFileInfoMap.clear();
                mFontFileInfoMap.putAll(backupMap);
                mLastModifiedMillis = backupLastModifiedDate;
            }
        }
    }

    /**
     * Installs a new font file, or updates an existing font file.
     *
     * <p>The new font will be immediately available for new Zygote-forked processes through
     * {@link #getPostScriptMap()}. Old font files will be kept until next system server reboot,
     * because existing Zygote-forked processes have paths to old font files.
     *
     * @param fd             A file descriptor to the font file.
     * @param pkcs7Signature A PKCS#7 detached signature to enable fs-verity for the font file.
     * @throws SystemFontException if error occurs.
     */
    private FontFileInfo installFontFile(FileDescriptor fd, byte[] pkcs7Signature)
            throws SystemFontException {
        return installFontFile(fd, pkcs7Signature, false);
    }

    private FontFileInfo installFontFile(
            FileDescriptor fd, byte[] pkcs7Signature, boolean isCustomFont)
            throws SystemFontException {
        File newDir = getRandomDir(mFilesDir);
        if (!newDir.mkdir()) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to create font directory.");
        }
        try {
            // Make newDir executable so that apps can access font file inside newDir.
            Os.chmod(newDir.getAbsolutePath(), 0711);
        } catch (ErrnoException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to change mode to 711", e);
        }
        boolean success = false;
        try {
            File tempNewFontFile = new File(newDir, "font.ttf");
            try (FileOutputStream out = new FileOutputStream(tempNewFontFile)) {
                long copied = isCustomFont
                        ? FileUtils.copy(fd, out.getFD(), MAX_CUSTOM_FONT_FILE_SIZE + 1,
                                null, null, null)
                        : FileUtils.copy(fd, out.getFD());
                if (isCustomFont && copied > MAX_CUSTOM_FONT_FILE_SIZE) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                            "Custom font exceeds 64 MiB.");
                }
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write font file to storage.", e);
            }
            try {
                // Do not parse font file before setting up fs-verity.
                // setUpFsverity throws IOException if failed.
                mFsverityUtil.setUpFsverity(tempNewFontFile.getAbsolutePath());
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_VERIFICATION_FAILURE,
                        "Failed to setup fs-verity.", e);
            }
            String fontFileName;
            try {
                fontFileName = mParser.buildFontFileName(tempNewFontFile);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to read PostScript name from font file", e);
            }
            if (fontFileName == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                        "Failed to read PostScript name from font file");
            }
            File newFontFile = new File(newDir, fontFileName);
            if (!mFsverityUtil.rename(tempNewFontFile, newFontFile)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to move verified font file.");
            }
            try {
                // Make the font file readable by apps.
                Os.chmod(newFontFile.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to change font file mode to 644", e);
            }
            File signatureFile = new File(newDir, FONT_SIGNATURE_FILE);
            try (FileOutputStream out = new FileOutputStream(signatureFile)) {
                out.write(pkcs7Signature);
            } catch (IOException e) {
                // TODO: Do we need new error code for signature write failure?
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write font signature file to storage.", e);
            }
            try {
                Os.chmod(signatureFile.getAbsolutePath(), 0600);
            } catch (ErrnoException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to change the signature file mode to 600", e);
            }
            if (isCustomFont) {
                File customMarker = new File(newDir, CUSTOM_FONT_MARKER_FILE);
                try (FileOutputStream ignored = new FileOutputStream(customMarker)) {
                    Os.chmod(customMarker.getAbsolutePath(), 0600);
                } catch (IOException | ErrnoException e) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                            "Failed to create custom font marker", e);
                }
            }
            FontFileInfo fontFileInfo = validateFontFile(
                    newFontFile, pkcs7Signature, isCustomFont);

            // Try to create Typeface and treat as failure something goes wrong.
            try {
                mParser.tryToCreateTypeface(fontFileInfo.getFile());
            } catch (Throwable t) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to create Typeface from file", t);
            }

            FontConfig fontConfig = getSystemFontConfig();
            if (!addFileToMapIfSameOrNewer(fontFileInfo, fontConfig, false)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_DOWNGRADING,
                        "Downgrading font file is forbidden.");
            }
            success = true;
            return fontFileInfo;
        } finally {
            if (!success) {
                FileUtils.deleteContentsAndDir(newDir);
            }
        }
    }

    /**
     * Given {@code parent}, returns {@code parent/~~[randomStr]}.
     * Makes sure that {@code parent/~~[randomStr]} directory doesn't exist.
     * Notice that this method doesn't actually create any directory.
     */
    private static File getRandomDir(File parent) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        File dir;
        do {
            random.nextBytes(bytes);
            String dirName = RANDOM_DIR_PREFIX
                    + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
            dir = new File(parent, dirName);
        } while (dir.exists());
        return dir;
    }

    private FontFileInfo lookupFontFileInfo(String psName) {
        return mFontFileInfoMap.get(psName);
    }

    private void putFontFileInfo(FontFileInfo info) {
        mFontFileInfoMap.put(info.getPostScriptName(), info);
    }

    /**
     * Add the given {@link FontFileInfo} to {@link #mFontFileInfoMap} if its font revision is
     * equal to or higher than the revision of currently used font file (either in
     * {@link #mFontFileInfoMap} or {@code fontConfig}).
     */
    private boolean addFileToMapIfSameOrNewer(FontFileInfo fontFileInfo, FontConfig fontConfig,
            boolean deleteOldFile) {
        FontFileInfo existingInfo = lookupFontFileInfo(fontFileInfo.getPostScriptName());
        final boolean shouldAddToMap;
        if (existingInfo == null) {
            // We got a new updatable font. We need to check if it's newer than preinstalled fonts.
            // Note that getPreinstalledFontRevision() returns -1 if there is no preinstalled font
            // with 'name'.
            long preInstalledRev = getPreinstalledFontRevision(fontFileInfo, fontConfig);
            shouldAddToMap = preInstalledRev <= fontFileInfo.getRevision();
        } else {
            shouldAddToMap = existingInfo.getRevision() <= fontFileInfo.getRevision();
        }
        if (shouldAddToMap) {
            if (deleteOldFile && existingInfo != null) {
                FileUtils.deleteContentsAndDir(existingInfo.getRandomizedFontDir());
            }
            putFontFileInfo(fontFileInfo);
        } else {
            if (deleteOldFile) {
                FileUtils.deleteContentsAndDir(fontFileInfo.getRandomizedFontDir());
            }
        }
        return shouldAddToMap;
    }

    private FontConfig.Font getFontByPostScriptName(String psName, FontConfig fontConfig) {
        FontConfig.Font targetFont = null;
        for (int i = 0; i < fontConfig.getFontFamilies().size(); i++) {
            FontConfig.FontFamily family = fontConfig.getFontFamilies().get(i);
            for (int j = 0; j < family.getFontList().size(); ++j) {
                FontConfig.Font font = family.getFontList().get(j);
                if (font.getPostScriptName().equals(psName)) {
                    targetFont = font;
                    break;
                }
            }
        }
        for (int i = 0; i < fontConfig.getNamedFamilyLists().size(); ++i) {
            FontConfig.NamedFamilyList namedFamilyList = fontConfig.getNamedFamilyLists().get(i);
            for (int j = 0; j < namedFamilyList.getFamilies().size(); ++j) {
                FontConfig.FontFamily family = namedFamilyList.getFamilies().get(j);
                for (int k = 0; k < family.getFontList().size(); ++k) {
                    FontConfig.Font font = family.getFontList().get(k);
                    if (font.getPostScriptName().equals(psName)) {
                        targetFont = font;
                        break;
                    }
                }
            }
        }
        return targetFont;
    }

    private long getPreinstalledFontRevision(FontFileInfo info, FontConfig fontConfig) {
        String psName = info.getPostScriptName();
        FontConfig.Font targetFont = getFontByPostScriptName(psName, fontConfig);

        if (targetFont == null) {
            return -1;
        }

        File preinstalledFontFile = targetFont.getOriginalFile() != null
                ? targetFont.getOriginalFile() : targetFont.getFile();
        if (!preinstalledFontFile.exists()) {
            return -1;
        }
        long revision = getFontRevision(preinstalledFontFile);
        if (revision == -1) {
            Slog.w(TAG, "Invalid preinstalled font file");
        }
        return revision;
    }

    /**
     * Checks the fs-verity protection status of the given font file, validates the file name, and
     * returns a {@link FontFileInfo} on success. This method does not check if the font revision
     * is higher than the currently used font.
     */
    @NonNull
    private FontFileInfo validateFontFile(File file, byte[] pkcs7Signature)
            throws SystemFontException {
        return validateFontFile(file, pkcs7Signature, false);
    }

    private FontFileInfo validateFontFile(
            File file, byte[] pkcs7Signature, boolean isCustomFont)
            throws SystemFontException {
        boolean verified = isCustomFont
                ? mFsverityUtil.hasFsverity(file.getAbsolutePath())
                : mFsverityUtil.isFromTrustedProvider(file.getAbsolutePath(), pkcs7Signature);
        if (!verified) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_VERIFICATION_FAILURE,
                    "Font validation failed. Fs-verity is not enabled: " + file);
        }
        final String psName;
        try {
            psName = mParser.getPostScriptName(file);
        } catch (IOException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                    "Font validation failed. Could not read PostScript name name: " + file);
        }
        long revision = getFontRevision(file);
        if (revision == -1) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                    "Font validation failed. Could not read font revision: " + file);
        }
        return new FontFileInfo(file, psName, revision);
    }
    /** Returns the non-negative font revision of the given font file, or -1. */
    private long getFontRevision(File file) {
        try {
            return mParser.getRevision(file);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read font file", e);
            return -1;
        }
    }

    void installCustomFont(FileDescriptor fd) throws SystemFontException {
        // Same backup / rollback as update(); this tree has no validateRequestsAndBackup().
        ArrayMap<String, FontFileInfo> backupMap = new ArrayMap<>(mFontFileInfoMap);
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        long backupLastModifiedDate = mLastModifiedMillis;
        Map<String, FontUpdateRequest.Family> familyMap = new HashMap<>();
        for (FontUpdateRequest.Family family : curConfig.fontFamilies) {
            familyMap.put(family.getName(), family);
        }

        boolean success = false;
        try {
            FontFileInfo fontFileInfo = installFontFile(fd, new byte[0], true);
            FontUpdateRequest.Font font = new FontUpdateRequest.Font(
                    fontFileInfo.getPostScriptName(),
                    new FontStyle(FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT),
                    0,
                    "");
            familyMap.put(CUSTOM_FONT_FAMILY_NAME, new FontUpdateRequest.Family(
                    CUSTOM_FONT_FAMILY_NAME, Collections.singletonList(font)));

            PersistentSystemFontConfig.Config newConfig = new PersistentSystemFontConfig.Config();
            mLastModifiedMillis = mCurrentTimeSupplier.get();
            newConfig.lastModifiedMillis = mLastModifiedMillis;
            for (FontFileInfo info : mFontFileInfoMap.values()) {
                newConfig.updatedFontDirs.add(info.getRandomizedFontDir().getName());
            }
            newConfig.fontFamilies.addAll(familyMap.values());
            writePersistentConfig(newConfig);
            mConfigVersion++;
            success = true;
        } finally {
            if (!success) {
                mFontFileInfoMap.clear();
                mFontFileInfoMap.putAll(backupMap);
                mLastModifiedMillis = backupLastModifiedDate;
            }
        }
    }

    void clearCustomFont() throws SystemFontException {
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        PersistentSystemFontConfig.Config newConfig = new PersistentSystemFontConfig.Config();
        long previousLastModifiedMillis = mLastModifiedMillis;
        long newLastModifiedMillis = mCurrentTimeSupplier.get();
        newConfig.lastModifiedMillis = newLastModifiedMillis;
        newConfig.updatedFontDirs.addAll(curConfig.updatedFontDirs);
        for (FontUpdateRequest.Family family : curConfig.fontFamilies) {
            if (!isCustomFontFamily(family)) {
                newConfig.fontFamilies.add(family);
            }
        }
        try {
            writePersistentConfig(newConfig);
        } catch (SystemFontException e) {
            mLastModifiedMillis = previousLastModifiedMillis;
            throw e;
        }
        mLastModifiedMillis = newLastModifiedMillis;
        mConfigVersion++;
    }

    @Nullable
    String getCustomFontName() {
        PersistentSystemFontConfig.Config config = readPersistentConfig();
        for (int i = config.fontFamilies.size() - 1; i >= 0; i--) {
            FontUpdateRequest.Family family = config.fontFamilies.get(i);
            if (isCustomFontFamily(family)) {
                return family.getFonts().get(0).getPostScriptName();
            }
        }
        return null;
    }

    private boolean isCustomFontFamily(FontUpdateRequest.Family family) {
        if (!CUSTOM_FONT_FAMILY_NAME.equals(family.getName()) || family.getFonts().isEmpty()) {
            return false;
        }
        FontFileInfo info = mFontFileInfoMap.get(family.getFonts().get(0).getPostScriptName());
        return info != null
                && new File(info.getRandomizedFontDir(), CUSTOM_FONT_MARKER_FILE).isFile();
    }

    private FontConfig.NamedFamilyList resolveFontFilesForNamedFamily(
            FontUpdateRequest.Family fontFamily) {
        List<FontUpdateRequest.Font> fontList = fontFamily.getFonts();
        List<FontConfig.Font> resolvedFonts = new ArrayList<>(fontList.size());
        for (int i = 0; i < fontList.size(); i++) {
            FontUpdateRequest.Font font = fontList.get(i);
            FontFileInfo info = mFontFileInfoMap.get(font.getPostScriptName());
            if (info == null) {
                Slog.e(TAG, "Failed to lookup font file that has " + font.getPostScriptName());
                return null;
            }
            resolvedFonts.add(new FontConfig.Font(info.mFile, null, info.getPostScriptName(),
                    font.getFontStyle(), font.getIndex(), font.getFontVariationSettings(),
                    null /* family name */, FontConfig.Font.VAR_TYPE_AXES_NONE));
        }
        FontConfig.FontFamily family = new FontConfig.FontFamily(resolvedFonts,
                LocaleList.getEmptyLocaleList(), FontConfig.FontFamily.VARIANT_DEFAULT);
        return new FontConfig.NamedFamilyList(Collections.singletonList(family),
                fontFamily.getName(), null /* TODO: USF should support fallback */);
    }

    Map<String, File> getPostScriptMap() {
        Map<String, File> map = new ArrayMap<>();
        for (int i = 0; i < mFontFileInfoMap.size(); ++i) {
            FontFileInfo info = mFontFileInfoMap.valueAt(i);
            map.put(info.getPostScriptName(), info.getFile());
        }
        return map;
    }

    /* package */ FontConfig getSystemFontConfig() {
        FontConfig config = mConfigSupplier.apply(getPostScriptMap());
        PersistentSystemFontConfig.Config persistentConfig = readPersistentConfig();
        List<FontUpdateRequest.Family> families = persistentConfig.fontFamilies;

        List<FontConfig.NamedFamilyList> mergedFamilies =
                new ArrayList<>(config.getNamedFamilyLists().size() * 2 + families.size());
        // We should keep the first font family (config.getFontFamilies().get(0)) because it's used
        // as a fallback font. See SystemFonts.java.
        mergedFamilies.addAll(config.getNamedFamilyLists());
        FontConfig.NamedFamilyList customFontFamily = null;
        for (int i = families.size() - 1; i >= 0; --i) {
            if (isCustomFontFamily(families.get(i))) {
                customFontFamily = resolveFontFilesForNamedFamily(families.get(i));
                break;
            }
        }
        boolean customFontApplied = false;
        if (customFontFamily != null && !customFontFamily.getFamilies().isEmpty()) {
            FontConfig.FontFamily customFamily = customFontFamily.getFamilies().get(0);
            // The clock family (config_clockFontFamily, e.g. google-sans-flex-clock) keeps its
            // own font. Its name matches the "google-sans" UI prefix, so it is excluded here.
            final String clockFamily = getClockFontFamilyName();
            for (FontConfig.NamedFamilyList baseFamily : config.getNamedFamilyLists()) {
                if (!FontController.isOverridableFamily(baseFamily.getName())
                        || isSameFamilyName(baseFamily.getName(), clockFamily)) {
                    continue;
                }
                List<FontConfig.FontFamily> fallbackFamilies =
                        new ArrayList<>(baseFamily.getFamilies().size() + 1);
                fallbackFamilies.add(customFamily);
                fallbackFamilies.addAll(baseFamily.getFamilies());
                mergedFamilies.add(new FontConfig.NamedFamilyList(fallbackFamilies,
                        baseFamily.getName(), baseFamily.getFallback()));
                customFontApplied = true;
            }
        }
        // When building Typeface, a latter font family definition will override the previous font
        // family definition with the same name. An exception is config.getFontFamilies.get(0),
        // which will be used as a fallback font without being overridden.
        for (int i = 0; i < families.size(); ++i) {
            if (customFontFamily != null && isCustomFontFamily(families.get(i))) {
                continue;
            }
            FontConfig.NamedFamilyList family = resolveFontFilesForNamedFamily(families.get(i));
            if (family != null) {
                mergedFamilies.add(family);
            }
        }

        if (customFontFamily != null && !customFontApplied) {
            mergedFamilies.add(customFontFamily);
        }

        if (customFontFamily != null && !customFontFamily.getFamilies().isEmpty()) {
            mergedFamilies.add(new FontConfig.NamedFamilyList(customFontFamily.getFamilies(),
                    FontController.CUSTOM_FONT_MARKER_FAMILY, null));
        }

        // If config_clockFontFamily is an alias of a UI family, the alias would now resolve to
        // the custom font. Pin it to an untouched copy of the original family instead: named
        // families take precedence over aliases with the same name when Typefaces are built.
        if (customFontFamily != null && customFontApplied) {
            FontConfig.NamedFamilyList pinnedClock =
                    pinAliasedClockFamily(config, getClockFontFamilyName());
            if (pinnedClock != null) {
                mergedFamilies.add(pinnedClock);
            }
        }

        return new FontConfig(
                config.getFontFamilies(), config.getAliases(), mergedFamilies,
                config.getLocaleFallbackCustomizations(), mLastModifiedMillis, mConfigVersion);
    }

    /**
     * The family named by config_clockFontFamily, or null when the resource doesn't exist.
     * Looked up by name because not every tree defines it (SystemUI's DefaultClockProvider
     * does the same).
     */
    @Nullable
    private static String getClockFontFamilyName() {
        try {
            Resources res = Resources.getSystem();
            int id = res.getIdentifier(CLOCK_FONT_FAMILY_RES, "string", "android");
            if (id == 0) {
                return null;
            }
            String family = res.getString(id);
            return TextUtils.isEmpty(family) ? null : family;
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    private static boolean isSameFamilyName(@Nullable String a, @Nullable String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /**
     * When {@code clockFamily} is not a named family but a plain (weight-less) alias of a UI
     * family, returns a named copy of that family as preinstalled, so the clock keeps it.
     * Returns null when nothing needs pinning.
     */
    @Nullable
    private static FontConfig.NamedFamilyList pinAliasedClockFamily(
            FontConfig config, @Nullable String clockFamily) {
        if (clockFamily == null) {
            return null;
        }
        for (FontConfig.NamedFamilyList family : config.getNamedFamilyLists()) {
            if (isSameFamilyName(family.getName(), clockFamily)) {
                return null; // A real family: already left alone above.
            }
        }
        String original = null;
        for (FontConfig.Alias alias : config.getAliases()) {
            if (isSameFamilyName(alias.getName(), clockFamily)
                    && alias.getWeight() == FontStyle.FONT_WEIGHT_NORMAL) {
                original = alias.getOriginal();
                break;
            }
        }
        if (original == null || !FontController.isOverridableFamily(original)) {
            return null;
        }
        for (FontConfig.NamedFamilyList family : config.getNamedFamilyLists()) {
            if (isSameFamilyName(family.getName(), original)) {
                return new FontConfig.NamedFamilyList(
                        family.getFamilies(), clockFamily, family.getFallback());
            }
        }
        return null;
    }

    private PersistentSystemFontConfig.Config readPersistentConfig() {
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        try (FileInputStream fis = mConfigFile.openRead()) {
            PersistentSystemFontConfig.loadFromXml(fis, config);
        } catch (IOException | XmlPullParserException e) {
            // The font config file is missing on the first boot. Just do nothing.
        }
        return config;
    }

    private void writePersistentConfig(PersistentSystemFontConfig.Config config)
            throws SystemFontException {
        FileOutputStream fos = null;
        try {
            fos = mConfigFile.startWrite();
            PersistentSystemFontConfig.writeToXml(fos, config);
            mConfigFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mConfigFile.failWrite(fos);
            }
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG,
                    "Failed to write config XML.", e);
        }
    }

    /* package */ int getConfigVersion() {
        return mConfigVersion;
    }

    public Map<String, FontConfig.NamedFamilyList> getFontFamilyMap() {
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontConfig.NamedFamilyList> familyMap = new HashMap<>();
        for (int i = 0; i < curConfig.fontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.fontFamilies.get(i);
            FontConfig.NamedFamilyList resolvedFamily = resolveFontFilesForNamedFamily(family);
            if (resolvedFamily != null) {
                familyMap.put(family.getName(), resolvedFamily);
            }
        }
        return familyMap;
    }

    /* package */ static void deleteAllFiles(File filesDir, File configFile) {
        // As this method is called in safe mode, try to delete all files even though an exception
        // is thrown.
        try {
            new AtomicFile(configFile).delete();
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to delete " + configFile);
        }
        try {
            FileUtils.deleteContents(filesDir);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to delete " + filesDir);
        }
    }
}
