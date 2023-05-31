package com.android.internal.util.crdroid;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HideDeveloperStatusUtils {
    private static final Set<String> settingsToHide =
            new HashSet<>(
                    Arrays.asList(
                            Settings.Global.ADB_ENABLED,
                            Settings.Global.ADB_WIFI_ENABLED,
                            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED));

    enum Action {
        ADD,
        REMOVE,
        SET
    }

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    public static boolean shouldHideDevStatus(ContentResolver cr, String packageName, String name) {
        if (cr == null || packageName == null || name == null || !isBootCompleted()) {
            return false;
        }

        Set<String> apps = getApps(cr);
        if (apps.isEmpty()) {
            return false;
        }

        return apps.contains(packageName) && settingsToHide.contains(name);
    }

    private static Set<String> getApps(Context context) {
        if (context == null) {
            return new HashSet<>();
        }

        return getApps(context.getContentResolver());
    }

    private static Set<String> getApps(ContentResolver cr) {
        if (cr == null) {
            return new HashSet<>();
        }

        String apps = Settings.Secure.getString(cr, Settings.Secure.HIDE_DEVELOPER_STATUS);
        if (apps != null && !apps.isEmpty() && !apps.equals(",")) {
            return new HashSet<>(Arrays.asList(apps.split(",")));
        }

        return new HashSet<>();
    }

    private static void putAppsForUser(
            Context context, String packageName, int userId, Action action) {
        if (context == null || userId < 0) {
            return;
        }

        final Set<String> apps = getApps(context);
        switch (action) {
            case ADD:
                apps.add(packageName);
                break;
            case REMOVE:
                apps.remove(packageName);
                break;
            case SET:
                // Don't change
                break;
        }

        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS,
                String.join(",", apps),
                userId);
    }

    public void addApp(Context mContext, String packageName, int userId) {
        if (mContext == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, packageName, userId, Action.ADD);
    }

    public void removeApp(Context mContext, String packageName, int userId) {
        if (mContext == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, packageName, userId, Action.REMOVE);
    }

    public void setApps(Context mContext, int userId) {
        if (mContext == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, null, userId, Action.SET);
    }
}
