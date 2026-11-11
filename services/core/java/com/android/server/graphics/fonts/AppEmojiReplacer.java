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
import android.os.Process;
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

    /**
     * Returns the number of app files written. Blocking. Runs one at a time, so two callers
     * can't write the same app files at once.
     *
     * @param callerUid the app that asked; it is never force-stopped under its own feet
     */
    synchronized int apply(@NonNull ParcelFileDescriptor font, int callerUid) {
        final long token = Binder.clearCallingIdentity();
        try {
            Installer installer = getInstaller();
            int written = 0;
            for (int userId : unlockedUsers()) {
                setGmsFontComponentsEnabled(userId, false);
                List<ApplicationInfo> apps = mContext.getPackageManager()
                        .getInstalledApplicationsAsUser(0, userId);
                for (ApplicationInfo app : apps) {
                    written += applyToApp(installer, font, app, userId, callerUid);
                }
            }
            return written;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Undoes what {@link #apply} did that would otherwise outlive the custom emoji: the GMS
     * font provider is re-enabled and Messenger may download fonts again.
     */
    synchronized void restore() {
        final long token = Binder.clearCallingIdentity();
        try {
            UserManager um = mContext.getSystemService(UserManager.class);
            for (UserInfo user : um.getAliveUsers()) {
                setGmsFontComponentsEnabled(user.id, true);
                if (um.isUserUnlocked(user.id)) {
                    unblockMessengerFonts(user.id);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void unblockMessengerFonts(int userId) {
        ApplicationInfo app;
        try {
            app = mContext.getPackageManager().getApplicationInfoAsUser(MESSENGER, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        try {
            getInstaller().clearAppDataSubdir(app.volumeUuid, MESSENGER, userId,
                    UserHandle.getAppId(app.uid), "files/fonts", false);
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to unblock Messenger fonts for user " + userId, e);
        }
    }

    private int applyToApp(Installer installer, ParcelFileDescriptor font, ApplicationInfo app,
            int userId, int callerUid) {
        String pkg = app.packageName;
        int appId = UserHandle.getAppId(app.uid);
        if (appId < Process.FIRST_APPLICATION_UID || appId > Process.LAST_APPLICATION_UID) {
            return 0;
        }
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
        if ((written > 0 || touched) && canForceStop(app, callerUid)) {
            mContext.getSystemService(ActivityManager.class).forceStopPackageAsUser(pkg, userId);
        }
        return written;
    }

    private static boolean canForceStop(ApplicationInfo app, int callerUid) {
        if (GMS.equals(app.packageName)) {
            return false;
        }
        if ((app.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) {
            return false;
        }
        int appId = UserHandle.getAppId(app.uid);
        return appId >= Process.FIRST_APPLICATION_UID
                && appId != UserHandle.getAppId(callerUid);
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
                // Only undo our own DISABLED state; leave any other setting alone.
                if (enabled && pm.getComponentEnabledSetting(component, userId)
                        != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    continue;
                }
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
