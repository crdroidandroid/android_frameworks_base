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
package com.android.server.wm.sandbox;

import android.app.AxSandboxManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppControlController {
    private static final String TAG = "AxSandbox.AppControl";

    public static final String SETTING_SANDBOX_CONFIG = AxSandboxManager.SETTING_SANDBOX_CONFIG;

    private static final String KEY_HIDDEN_PKGS = "hidden_pkgs";
    private static final String KEY_LOCKED_PKGS = "locked_pkgs";
    private static final String KEY_SANDBOXED_PKGS = "sandboxed_pkgs";
    private static final String KEY_HIDEDEVOPTS_PKGS = "hidedevopts_pkgs";
    private static final String KEY_GID_RESTRICTIONS = "gid_restrictions";
    private static final String KEY_SPOOF_SETTINGS_MAP = "spoof_settings_map";
    private static final String KEY_DATA_ISOLATION = "data_isolation_pkgs";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final LauncherApps mLauncherApps;
    private final Set<String> mBlacklistedPackages;
    private final Handler mHandler;

    private final Set<String> mLockedPackages = new HashSet<>();
    private final Set<String> mHiddenPackages = new HashSet<>();
    private final Set<String> mSandboxedPackages = new HashSet<>();
    private final Set<String> mHideDevOptsPackages = new HashSet<>();
    private final Map<String, int[]> mGidRestrictions = new HashMap<>();
    private final Map<String, Set<String>> mSpoofSettingsMap = new HashMap<>();
    private final Set<String> mDataIsolationPackages = new HashSet<>();

    private ContentObserver mConfigObserver;

    public interface OnUpdateListener {
        void onSandboxUpdated();
    }

    private OnUpdateListener mUpdateListener;

    public AppControlController(Context context, Set<String> blacklistedPackages) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mBlacklistedPackages = blacklistedPackages;
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void setUpdateListener(OnUpdateListener listener) {
        mUpdateListener = listener;
    }

    public void init() {
        registerSettingsObserver();
        loadConfigFromSettings();
    }

    private void registerSettingsObserver() {
        mConfigObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                loadConfigFromSettings();
                broadcastPackageChanges();
                notifyUpdate();
            }
        };

        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_SANDBOX_CONFIG),
                false, mConfigObserver, UserHandle.USER_ALL);

        Slog.d(TAG, "Registered Settings observer for sandbox_config");
    }

    private void loadConfigFromSettings() {
        String jsonStr = Settings.Secure.getString(mContentResolver, SETTING_SANDBOX_CONFIG);

        synchronized (this) {
            mLockedPackages.clear();
            mHiddenPackages.clear();
            mSandboxedPackages.clear();
            mHideDevOptsPackages.clear();
            mGidRestrictions.clear();
            mSpoofSettingsMap.clear();
            mDataIsolationPackages.clear();

            if (!TextUtils.isEmpty(jsonStr)) {
                try {
                    JSONObject config = new JSONObject(jsonStr);

                    loadPackageSet(config, KEY_LOCKED_PKGS, mLockedPackages);
                    loadPackageSet(config, KEY_HIDDEN_PKGS, mHiddenPackages);
                    loadPackageSet(config, KEY_SANDBOXED_PKGS, mSandboxedPackages);
                    loadPackageSet(config, KEY_HIDEDEVOPTS_PKGS, mHideDevOptsPackages);
                    loadSpoofSettingsMap(config);
                    loadPackageSet(config, KEY_DATA_ISOLATION, mDataIsolationPackages);
                    loadGidRestrictions(config);

                } catch (JSONException e) {
                    Slog.e(TAG, "Failed to parse sandbox_config JSON", e);
                }
            }
        }

        Slog.i(TAG, "Loaded config: locked=" + mLockedPackages.size()
                + " hidden=" + mHiddenPackages.size()
                + " sandboxed=" + mSandboxedPackages.size()
                + " hideDevOpts=" + mHideDevOptsPackages.size());
    }

    private void loadPackageSet(JSONObject config, String key, Set<String> target) {
        JSONArray arr = config.optJSONArray(key);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.optString(i);
                if (!TextUtils.isEmpty(pkg) && !mBlacklistedPackages.contains(pkg)) {
                    target.add(pkg);
                }
            }
        }
    }

    private void saveConfigToSettings() {
        try {
            JSONObject config = new JSONObject();

            synchronized (this) {
                config.put(KEY_LOCKED_PKGS, new JSONArray(mLockedPackages));
                config.put(KEY_HIDDEN_PKGS, new JSONArray(mHiddenPackages));
                config.put(KEY_SANDBOXED_PKGS, new JSONArray(mSandboxedPackages));
                config.put(KEY_HIDEDEVOPTS_PKGS, new JSONArray(mHideDevOptsPackages));
                saveSpoofSettingsMap(config);
                config.put(KEY_DATA_ISOLATION, new JSONArray(mDataIsolationPackages));
                saveGidRestrictions(config);
            }

            Settings.Secure.putString(mContentResolver, SETTING_SANDBOX_CONFIG, config.toString());

        } catch (JSONException e) {
            Slog.e(TAG, "Failed to save sandbox_config JSON", e);
        }
    }

    private void broadcastPackageChanges() {
        Set<String> allPackages = new HashSet<>();
        synchronized (this) {
            allPackages.addAll(mHiddenPackages);
        }

        for (String packageName : allPackages) {
            int uid = getPackageUid(packageName);
            if (uid >= 0) {
                broadcastPackageChange(packageName, uid);
            }
        }
    }

    private void broadcastPackageChange(String packageName, int uid) {
        try {
            Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
            intent.setData(Uri.fromParts("package", packageName, null));
            intent.putExtra(Intent.EXTRA_UID, uid);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(uid));
            intent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, new String[]{packageName});
            intent.putExtra(Intent.EXTRA_DONT_KILL_APP, true);
            mContext.sendBroadcastAsUser(intent, UserHandle.of(UserHandle.getUserId(uid)));
        } catch (Exception e) {
            Slog.w(TAG, "Failed to broadcast package change for " + packageName, e);
        }
    }

    private void notifyUpdate() {
        if (mUpdateListener != null) {
            mUpdateListener.onSandboxUpdated();
        }
    }

    public boolean isAppLocked(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (mBlacklistedPackages.contains(packageName)) return false;
        synchronized (this) {
            return mLockedPackages.contains(packageName);
        }
    }

    public boolean hasLockedPackages() {
        synchronized (this) {
            return !mLockedPackages.isEmpty();
        }
    }

    public boolean isPackageHidden(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        synchronized (this) {
            final boolean hidden = mHiddenPackages.contains(packageName);
            return hidden;
        }
    }

    public boolean isPackageSandboxed(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        synchronized (this) {
            return mSandboxedPackages.contains(packageName);
        }
    }

    public boolean isDevOptionsHidden(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        synchronized (this) {
            return mHideDevOptsPackages.contains(packageName);
        }
    }

    public void addLockedApp(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        if (!isPackageLockable(packageName)) {
            Slog.w(TAG, "Cannot lock package - not lockable: " + packageName);
            return;
        }
        int uid = getPackageUid(packageName);
        synchronized (this) {
            if (mLockedPackages.add(packageName)) {
                saveConfigToSettings();
                if (uid >= 0) {
                    broadcastPackageChange(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "addLockedApp: " + packageName);
    }

    public void removeLockedApp(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        int uid = getPackageUid(packageName);
        synchronized (this) {
            if (mLockedPackages.remove(packageName)) {
                saveConfigToSettings();
                if (uid >= 0) {
                    broadcastPackageChange(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "removeLockedApp: " + packageName);
    }

    public void setPackageHidden(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) return;
        if (hidden && !isPackageLockable(packageName)) {
            Slog.w(TAG, "Cannot hide package - not lockable: " + packageName);
            return;
        }
        int uid = getPackageUid(packageName);
        synchronized (this) {
            boolean changed = hidden ? mHiddenPackages.add(packageName)
                                     : mHiddenPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings();
                if (uid >= 0) {
                    broadcastPackageChange(packageName, uid);
                }
            }
        }
        Slog.d(TAG, "setPackageHidden: " + packageName + " hidden=" + hidden);
    }

    public void setPackageSandboxed(String packageName, boolean sandboxed) {
        if (TextUtils.isEmpty(packageName)) return;
        synchronized (this) {
            boolean changed = sandboxed ? mSandboxedPackages.add(packageName)
                                       : mSandboxedPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings();
            }
        }
        Slog.d(TAG, "setPackageSandboxed: " + packageName + " sandboxed=" + sandboxed);
    }

    public void setDevOptionsHidden(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) return;
        synchronized (this) {
            boolean changed = hidden ? mHideDevOptsPackages.add(packageName)
                                    : mHideDevOptsPackages.remove(packageName);
            if (changed) {
                saveConfigToSettings();
            }
        }
        Slog.d(TAG, "setDevOptionsHidden: " + packageName + " hidden=" + hidden);
    }

    public List<String> getLockedPackages() {
        synchronized (this) {
            return new ArrayList<>(mLockedPackages);
        }
    }

    public List<String> getHiddenPackages() {
        synchronized (this) {
            return new ArrayList<>(mHiddenPackages);
        }
    }

    public List<String> getSandboxedPackages() {
        synchronized (this) {
            return new ArrayList<>(mSandboxedPackages);
        }
    }

    public List<String> getDevOptionsHiddenPackages() {
        synchronized (this) {
            return new ArrayList<>(mHideDevOptsPackages);
        }
    }

    public boolean isPackageLockable(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (mBlacklistedPackages.contains(packageName)) return false;

        if (mLauncherApps == null) return false;

        try {
            List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(
                    packageName, UserHandle.of(UserHandle.USER_SYSTEM));
            return activities != null && !activities.isEmpty();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to check if package is lockable: " + packageName, e);
            return false;
        }
    }

    public List<String> getLockablePackages() {
        List<String> result = new ArrayList<>();

        if (mLauncherApps != null) {
            try {
                List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(
                        null, UserHandle.of(UserHandle.USER_SYSTEM));

                Set<String> seen = new HashSet<>();
                for (LauncherActivityInfo info : activities) {
                    String pkgName = info.getApplicationInfo().packageName;
                    if (!mBlacklistedPackages.contains(pkgName) && !seen.contains(pkgName)) {
                        result.add(pkgName);
                        seen.add(pkgName);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get lockable packages from LauncherApps", e);
            }
        }

        if (result.isEmpty()) {
            Slog.w(TAG, "LauncherApps returned empty, using PackageManager fallback");
            try {
                PackageManager pm = mContext.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                for (ApplicationInfo appInfo : apps) {
                    if (mBlacklistedPackages.contains(appInfo.packageName)) continue;
                    if (isSystemUid(appInfo.uid)) continue;
                    if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                        result.add(appInfo.packageName);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get lockable packages from PackageManager", e);
            }
        }

        return result;
    }

    private int getPackageUid(String packageName) {
        try {
            return mContext.getPackageManager()
                    .getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static boolean isSystemUid(int uid) {
        int appId = UserHandle.getAppId(uid);
        return appId == android.os.Process.ROOT_UID || appId == android.os.Process.SYSTEM_UID;
    }

    private void loadGidRestrictions(JSONObject config) {
        JSONObject gidObj = config.optJSONObject(KEY_GID_RESTRICTIONS);
        if (gidObj == null) return;
        java.util.Iterator<String> keys = gidObj.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            JSONArray arr = gidObj.optJSONArray(pkg);
            if (arr != null && arr.length() > 0) {
                int[] gids = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    gids[i] = arr.optInt(i);
                }
                mGidRestrictions.put(pkg, gids);
            }
        }
    }

    private void saveGidRestrictions(JSONObject config) throws JSONException {
        JSONObject gidObj = new JSONObject();
        for (Map.Entry<String, int[]> entry : mGidRestrictions.entrySet()) {
            JSONArray arr = new JSONArray();
            for (int gid : entry.getValue()) {
                arr.put(gid);
            }
            gidObj.put(entry.getKey(), arr);
        }
        config.put(KEY_GID_RESTRICTIONS, gidObj);
    }

    public void setRestrictedGids(String packageName, int[] gids) {
        if (TextUtils.isEmpty(packageName)) return;
        synchronized (this) {
            if (gids == null || gids.length == 0) {
                mGidRestrictions.remove(packageName);
            } else {
                mGidRestrictions.put(packageName, gids);
            }
            saveConfigToSettings();
        }
    }

    public int[] getRestrictedGids(String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        synchronized (this) {
            return mGidRestrictions.get(packageName);
        }
    }

    public boolean isSettingsSpoofEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        synchronized (this) {
            return mSpoofSettingsMap.containsKey(packageName);
        }
    }

    public boolean isSpoofSettingEnabled(String packageName, String settingKey) {
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(settingKey)) return false;
        synchronized (this) {
            Set<String> settings = mSpoofSettingsMap.get(packageName);
            return settings != null && settings.contains(settingKey);
        }
    }

    public void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled) {
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(settingKey)) return;
        synchronized (this) {
            Set<String> settings = mSpoofSettingsMap.get(packageName);
            boolean changed;
            if (enabled) {
                if (settings == null) {
                    settings = new HashSet<>();
                    mSpoofSettingsMap.put(packageName, settings);
                }
                changed = settings.add(settingKey);
            } else {
                if (settings == null) return;
                changed = settings.remove(settingKey);
                if (settings.isEmpty()) {
                    mSpoofSettingsMap.remove(packageName);
                }
            }
            if (changed) saveConfigToSettings();
        }
    }

    public List<String> getEnabledSpoofSettings(String packageName) {
        if (TextUtils.isEmpty(packageName)) return java.util.Collections.emptyList();
        synchronized (this) {
            Set<String> settings = mSpoofSettingsMap.get(packageName);
            if (settings == null || settings.isEmpty()) return java.util.Collections.emptyList();
            return new java.util.ArrayList<>(settings);
        }
    }

    private void loadSpoofSettingsMap(JSONObject config) {
        JSONObject mapObj = config.optJSONObject(KEY_SPOOF_SETTINGS_MAP);
        if (mapObj == null) return;
        java.util.Iterator<String> keys = mapObj.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            JSONArray arr = mapObj.optJSONArray(pkg);
            if (arr != null && arr.length() > 0) {
                Set<String> settings = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i);
                    if (!TextUtils.isEmpty(s)) settings.add(s);
                }
                if (!settings.isEmpty()) {
                    mSpoofSettingsMap.put(pkg, settings);
                }
            }
        }
    }

    private void saveSpoofSettingsMap(JSONObject config) throws JSONException {
        JSONObject mapObj = new JSONObject();
        for (Map.Entry<String, Set<String>> entry : mSpoofSettingsMap.entrySet()) {
            mapObj.put(entry.getKey(), new JSONArray(entry.getValue()));
        }
        config.put(KEY_SPOOF_SETTINGS_MAP, mapObj);
    }

    public boolean isDataIsolationEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        synchronized (this) {
            return mDataIsolationPackages.contains(packageName);
        }
    }

    public void setDataIsolationEnabled(String packageName, boolean enabled) {
        if (TextUtils.isEmpty(packageName)) return;
        synchronized (this) {
            boolean changed = enabled ? mDataIsolationPackages.add(packageName)
                                      : mDataIsolationPackages.remove(packageName);
            if (changed) saveConfigToSettings();
        }
    }
}
