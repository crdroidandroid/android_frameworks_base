package com.android.internal.util.custom;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

public class HideDeveloperStatusUtils {
    private static volatile Set<String> mApps = Collections.emptySet();
    private static final Set<String> settingsToHide = new HashSet<>();
    
    static {
        settingsToHide.add(Settings.Global.ADB_ENABLED);
        settingsToHide.add(Settings.Global.ADB_WIFI_ENABLED);
        settingsToHide.add(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
    }

    public static boolean shouldHideDevStatus(ContentResolver cr, String packageName, String name) {
        if (cr == null || packageName == null || packageName.isEmpty() || name == null || name.isEmpty()) {
            return false;
        }
        Set<String> apps = getApps(cr);
        return !apps.isEmpty() && apps.contains(packageName) && settingsToHide.contains(name);
    }

    private static Set<String> getApps(ContentResolver cr) {
        if (mApps.isEmpty()) {
            synchronized (HideDeveloperStatusUtils.class) {
                if (mApps.isEmpty()) {
                    String apps = Settings.Secure.getString(cr, "hide_developer_status");
                    if (apps != null && !apps.isEmpty()) {
                        String[] appArray = apps.split(",");
                        mApps = new HashSet<>(appArray.length);
                        Collections.addAll(mApps, appArray);
                    } else {
                        mApps = Collections.emptySet();
                    }
                }
            }
        }
        return mApps;
    }

    public void addApp(Context mContext, String packageName, int userId) {
        synchronized (HideDeveloperStatusUtils.class) {
            if (mApps.isEmpty()) {
                mApps = new HashSet<>();
            }
            mApps.add(packageName);
            updateSettings(mContext, userId);
        }
    }

    public void removeApp(Context mContext, String packageName, int userId) {
        synchronized (HideDeveloperStatusUtils.class) {
            if (mApps.isEmpty()) {
                return;
            }
            mApps.remove(packageName);
            updateSettings(mContext, userId);
        }
    }

    public void setApps(Context mContext, int userId) {
        synchronized (HideDeveloperStatusUtils.class) {
            String apps = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    "hide_developer_status", userId);
            if (apps != null && !apps.isEmpty()) {
                String[] appArray = apps.split(",");
                mApps = new HashSet<>(appArray.length);
                Collections.addAll(mApps, appArray);
            } else {
                mApps = Collections.emptySet();
            }
        }
    }

    private void updateSettings(Context mContext, int userId) {
        if (!mApps.isEmpty()) {
            StringJoiner joiner = new StringJoiner(",");
            for (String app : mApps) {
                joiner.add(app);
            }
            String updatedApps = joiner.toString();
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    "hide_developer_status", updatedApps, userId);
        } else {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    "hide_developer_status", null, userId);
        }
    }
}
