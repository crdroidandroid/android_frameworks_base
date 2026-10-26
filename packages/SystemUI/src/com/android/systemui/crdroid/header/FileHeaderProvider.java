/*
 *  Copyright (C) 2018 The OmniROM Project
 *  Copyright (C) 2026 crDroid Android Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.crdroid.header;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;

public class FileHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "FileHeaderProvider";
    private static final boolean DEBUG = false;
    private static final String HEADER_FILE_NAME = "custom_file_header_image";
    private static final String SOURCE_SUFFIX = ".src";

    private final Context mContext;
    private Drawable mImage;
    private File mLoadedFile;
    private long mLoadedStamp;

    public FileHeaderProvider(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public void settingsChanged(Uri uri) {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        if (!customHeader) {
            releaseImage();
            return;
        }

        final String imageUri = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_FILE_HEADER_IMAGE,
                UserHandle.USER_CURRENT);
        final File file = headerFile();
        if (imageUri != null && !imageUri.equals(readSavedSource(file))) {
            saveHeaderImage(Uri.parse(imageUri), file);
        }
        loadHeaderImage(file);
    }

    @Override
    public void enableProvider() {
        settingsChanged(null);
    }

    @Override
    public void disableProvider() {
        releaseImage();
    }

    private void releaseImage() {
        mImage = null;
        mLoadedFile = null;
        mLoadedStamp = 0;
    }

    /** One file per user, since SystemUI's files dir is shared across users. */
    private File headerFile() {
        final int userId = ActivityManager.getCurrentUser();
        final String name = userId == UserHandle.USER_SYSTEM
                ? HEADER_FILE_NAME : HEADER_FILE_NAME + "_" + userId;
        return new File(mContext.getFilesDir(), name);
    }

    private static File sourceFile(File file) {
        return new File(file.getPath() + SOURCE_SUFFIX);
    }

    private static String readSavedSource(File file) {
        final File src = sourceFile(file);
        if (!src.exists() || !file.exists()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void saveHeaderImage(Uri imageUri, File file) {
        if (DEBUG) Log.i(TAG, "Save header image " + imageUri);

        // Copy to a temp file first so a failed copy never clobbers the current image.
        final File tmp = new File(file.getPath() + ".tmp");
        try (InputStream imageStream = mContext.getContentResolver().openInputStream(imageUri)) {
            if (imageStream == null) {
                Log.e(TAG, "Unable to open input stream for URI: " + imageUri);
                return;
            }
            try (FileOutputStream output = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = imageStream.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("rename failed");
            }
            try (FileOutputStream src = new FileOutputStream(sourceFile(file))) {
                src.write(imageUri.toString().getBytes(StandardCharsets.UTF_8));
            }
            releaseImage(); // force a reload of the new content
            if (DEBUG) Log.i(TAG, "Saved header image " + file.getAbsolutePath());
        } catch (IOException | SecurityException e) {
            // SecurityException: SystemUI lost (or never had) a grant for this URI.
            Log.e(TAG, "Save header image failed for URI: " + imageUri, e);
            tmp.delete();
        }
    }

    private void loadHeaderImage(File file) {
        if (!file.exists()) {
            releaseImage();
            return;
        }
        final long stamp = file.lastModified() ^ file.length();
        if (mImage != null && file.equals(mLoadedFile) && stamp == mLoadedStamp) {
            return; // already decoded
        }
        if (DEBUG) Log.i(TAG, "Load header image " + file);

        // The header spans the shade width; there is no point keeping a full-resolution
        // photo (tens of MB) in SystemUI's heap.
        final DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        final int maxWidth = Math.max(dm.widthPixels, dm.heightPixels);
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            mImage = ImageDecoder.decodeDrawable(source, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                final Size size = info.getSize();
                int sample = 1;
                while (size.getWidth() / (sample * 2) >= maxWidth) {
                    sample *= 2;
                }
                if (sample > 1) {
                    decoder.setTargetSampleSize(sample);
                }
            });
            mLoadedFile = file;
            mLoadedStamp = stamp;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to decode header image", e);
            releaseImage();
        }
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        return mImage;
    }
}
