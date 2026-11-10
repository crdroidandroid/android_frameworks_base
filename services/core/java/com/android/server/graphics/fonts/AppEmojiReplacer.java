/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts the custom emoji font into apps that ship or download their own emoji, as the
 * Magisk-iOS-Emoji module's service.sh does. App data can only be written by installd, so the
 * file work goes through it.
 */
final class AppEmojiReplacer {

    private static final String TAG = "AppEmojiReplacer";

    private static final String GMS = "com.google.android.gms";
    private static final String MESSENGER = "com.facebook.orca";

    // androidx.emoji2 in apps downloads Noto emoji from this provider instead of using ours.
    private static final ComponentName[] GMS_FONT_COMPONENTS = {
            new ComponentName(GMS, "com.google.android.gms.fonts.provider.FontsProvider"),
            new ComponentName(GMS, "com.google.android.gms.fonts.update.UpdateSchedulerService"),
    };

    // Facebook re-downloads FacebookEmoji.ttf unless a read-only copy is already there.
    private static final Map<String, String[]> LOCKED_FILES = Map.of(
            MESSENGER, new String[] {"app_ras_blobs/FacebookEmoji.ttf"},
            "com.facebook.katana", new String[] {"app_ras_blobs/FacebookEmoji.ttf"});

    // Apps whose caches keep emoji they already rendered.
    private static final Set<String> CACHE_CLEARED_PACKAGES = Set.of(
            MESSENGER, "com.facebook.katana", "com.facebook.lite", "com.facebook.mlite",
            "com.google.android.inputmethod.latin");
    private static final String[] CACHE_DIRS = {
            "cache", "code_cache", "app_webview", "files/GCache",
    };

    private static final String[] NO_FILES = new String[0];

    private final Context mContext;
    private Installer mInstaller;

    AppEmojiReplacer(@NonNull Context context) {
        mContext = context;
    }

    /** Returns the number of app files written. Blocking. */
    int apply(@NonNull ParcelFileDescriptor font) {
        final long token = Binder.clearCallingIdentity();
        try {
            Installer installer = getInstaller();
            int written = 0;
            for (int userId : unlockedUsers()) {
                setGmsFontComponentsEnabled(userId, false);
                List<ApplicationInfo> apps = mContext.getPackageManager()
                        .getInstalledApplicationsAsUser(0, userId);
                for (ApplicationInfo app : apps) {
                    written += applyToApp(installer, font, app, userId);
                }
            }
            return written;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Undoes the GMS font provider change {@link #apply} made. */
    void restoreGmsFontProvider() {
        final long token = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : mContext.getSystemService(UserManager.class).getAliveUsers()) {
                setGmsFontComponentsEnabled(user.id, true);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int applyToApp(Installer installer, ParcelFileDescriptor font, ApplicationInfo app,
            int userId) {
        String pkg = app.packageName;
        int appId = UserHandle.getAppId(app.uid);
        String uuid = app.volumeUuid;
        int written = 0;
        boolean touched = false;
        try {
            written = installer.replaceAppEmojiFonts(uuid, pkg, userId, appId, font,
                    LOCKED_FILES.getOrDefault(pkg, NO_FILES));
            if (MESSENGER.equals(pkg)) {
                installer.clearAppDataSubdir(uuid, pkg, userId, appId, "files/fonts", true);
                touched = true;
            }
            if (CACHE_CLEARED_PACKAGES.contains(pkg)) {
                for (String dir : CACHE_DIRS) {
                    installer.clearAppDataSubdir(uuid, pkg, userId, appId, dir, false);
                }
                touched = true;
            }
            if (GMS.equals(pkg)) {
                installer.clearAppDataSubdir(uuid, pkg, userId, appId, "files/fonts", false);
            }
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to replace emoji fonts of " + pkg + " for user " + userId, e);
        }
        if ((written > 0 || touched) && !GMS.equals(pkg)) {
            mContext.getSystemService(ActivityManager.class).forceStopPackageAsUser(pkg, userId);
        }
        return written;
    }

    private int[] unlockedUsers() {
        UserManager um = mContext.getSystemService(UserManager.class);
        return um.getAliveUsers().stream()
                .mapToInt(user -> user.id)
                .filter(um::isUserUnlocked)
                .toArray();
    }

    private void setGmsFontComponentsEnabled(int userId, boolean enabled) {
        IPackageManager pm = AppGlobals.getPackageManager();
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        for (ComponentName component : GMS_FONT_COMPONENTS) {
            try {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP,
                        userId, mContext.getOpPackageName());
            } catch (Exception e) {
                // GMS isn't installed for this user, or no longer has the component.
                Slog.i(TAG, "Couldn't change " + component + " for user " + userId + ": " + e);
            }
        }
    }

    private synchronized Installer getInstaller() {
        if (mInstaller == null) {
            mInstaller = new Installer(mContext);
            mInstaller.onStart();
        }
        return mInstaller;
    }
}
