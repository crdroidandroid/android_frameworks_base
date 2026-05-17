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

package com.android.server.theme;

import static android.content.res.ThemeEngine.*;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.IThemeEngineCallback;
import android.content.res.IThemeEngineManager;
import android.content.res.Resources;
import android.content.res.ThemeEngine;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Slog;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** @hide */
public class ThemeEngineManagerService extends SystemService {
    private static final String TAG = "ThemeEngineManagerService";
    private static final boolean DEBUG = false;

    private static final String TARGET_ARRAY_SYSTEMUI = "target_systemui";
    private static final String TARGET_ARRAY_ANDROID = "target_android";
    private static final String TARGET_ARRAY_SETTINGS = "target_settings";

    private static final int BITMAP_CACHE_SIZE = 5 * 1024 * 1024;

    private final Context mContext;
    private final BinderService mBinderService;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;

    private final Map<String, String> mEnabledThemes = new ConcurrentHashMap<>();
    private final Map<String, Resources> mThemeResourcesCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> mTargetArrayCache = new ConcurrentHashMap<>();
    private final Map<String, String> mResourceCategoryCache = new ConcurrentHashMap<>();
    private final Map<String, String> mCategoryThemes = new ConcurrentHashMap<>();
    private final List<String> mSystemThemeIconTargets = new CopyOnWriteArrayList<>();

    private volatile String mActiveSystemThemeIcons = null;
    private volatile String mIconPackPackage = null;

    private final Map<ComponentName, String> mIconPackMap = new ConcurrentHashMap<>();
    private final List<String> mIconBackList = new CopyOnWriteArrayList<>();
    private final List<String> mIconMaskList = new CopyOnWriteArrayList<>();
    private volatile float mIconScale = 1.0f;

    private final Map<String, String> mPerAppIconPacks = new ConcurrentHashMap<>();
    private final Map<String, Map<ComponentName, String>> mPerAppIconPackMaps =
            new ConcurrentHashMap<>();

    private volatile List<String> mInstalledIconPacksCache = null;

    private final LruCache<String, Bitmap> mBitmapCache;
    private int mLastUiMode = Configuration.UI_MODE_NIGHT_UNDEFINED;
    private int mCachedTextColorPrimary = 0;
    private boolean mTextColorPrimaryCached = false;
    private ContentObserver mSettingsObserver;

    private static final long PERSIST_DEBOUNCE_MS = 500;
    private final Runnable mPersistPerAppRunnable = this::persistPerAppIconPacksNow;

    private final Object mThemeChangeLock = new Object();
    private boolean mThemeChangeDispatching;
    private boolean mThemeChangePending;
    @Nullable
    private String mPendingThemeChangeCategory;

    private final RemoteCallbackList<IThemeEngineCallback> mCallbacks =
            new RemoteCallbackList<>();

    public ThemeEngineManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
        mHandlerThread = new HandlerThread("ThemeEngineManagerService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mBitmapCache = new LruCache<String, Bitmap>(BITMAP_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
    }

    @Override
    public void onStart() {
        publishBinderService(Context.THEME_ENGINE_SERVICE, mBinderService);
        Slog.i(TAG, "ThemeEngineManagerService started");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mSettingsObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    notifyThemeChangedInternal(null);
                }
            };

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SETTINGS_THEME_ENGINE_DATA),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            loadThemeConfig();

            mLastUiMode = mContext.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;

            IntentFilter configFilter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int newUiMode = mContext.getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK;
                    if (newUiMode != mLastUiMode) {
                        mLastUiMode = newUiMode;
                        mBitmapCache.evictAll();
                        mTextColorPrimaryCached = false;
                    }
                }
            }, configFilter, null, mHandler);

            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packageFilter.addDataScheme("package");
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mInstalledIconPacksCache = null;
                }
            }, packageFilter, null, mHandler);


            Slog.i(TAG, "ThemeEngineManagerService ready");
        }
    }

    private synchronized void loadThemeConfig() {
        mEnabledThemes.clear();
        mThemeResourcesCache.clear();
        mTargetArrayCache.clear();
        mResourceCategoryCache.clear();
        mActiveSystemThemeIcons = null;
        mSystemThemeIconTargets.clear();
        mCategoryThemes.clear();
        mBitmapCache.evictAll();
        mPerAppIconPacks.clear();
        mPerAppIconPackMaps.clear();

        try {
            String json = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    UserHandle.USER_CURRENT);

            if (json == null || json.isEmpty()) {
                return;
            }

            JSONObject config = new JSONObject(json);
            JSONObject themes = config.optJSONObject("themes");

            if (themes != null) {
                Iterator<String> keys = themes.keys();
                while (keys.hasNext()) {
                    String category = keys.next();
                    JSONObject categoryConfig = themes.getJSONObject(category);
                    boolean enabled = categoryConfig.optBoolean("enabled", false);
                    String packageName = categoryConfig.optString("packageName", null);
                    if (enabled && packageName != null && !packageName.isEmpty()) {
                        mEnabledThemes.put(category, packageName);
                    }
                }
            }

            String systemThemeIcons = config.optString("systemThemeIcons", null);
            if (systemThemeIcons != null && !systemThemeIcons.isEmpty()) {
                mActiveSystemThemeIcons = systemThemeIcons;
                loadTargetArrays(systemThemeIcons);
            }

            JSONArray targets = config.optJSONArray("systemThemeIconTargets");
            if (targets != null) {
                for (int i = 0; i < targets.length(); i++) {
                    String target = targets.optString(i);
                    if (target != null && !target.isEmpty()) {
                        mSystemThemeIconTargets.add(target);
                    }
                }
            }

            JSONObject categoryThemes = config.optJSONObject("categoryThemes");
            if (categoryThemes != null) {
                Iterator<String> catKeys = categoryThemes.keys();
                while (catKeys.hasNext()) {
                    String category = catKeys.next();
                    String pkgName = categoryThemes.optString(category);
                    boolean isCategoryEnabled = mEnabledThemes.containsKey(category)
                            || mSystemThemeIconTargets.contains(category);
                    if (pkgName != null && !pkgName.isEmpty() && isCategoryEnabled) {
                        mCategoryThemes.put(category, pkgName);
                        loadTargetArrays(pkgName);
                    }
                }
            }

            JSONObject perAppPacks = config.optJSONObject("perAppIconPacks");
            if (perAppPacks != null) {
                Iterator<String> appKeys = perAppPacks.keys();
                while (appKeys.hasNext()) {
                    String appPkg = appKeys.next();
                    String iconPackPkg = perAppPacks.optString(appPkg);
                    if (iconPackPkg != null && !iconPackPkg.isEmpty()) {
                        mPerAppIconPacks.put(appPkg, iconPackPkg);
                    }
                }
            }

            String iconPackPkg = mEnabledThemes.get(CATEGORY_ICON_PACK);
            if (iconPackPkg != null
                    && (!iconPackPkg.equals(mIconPackPackage) || mIconPackMap.isEmpty())) {
                loadIconPack(iconPackPkg);
            } else if (iconPackPkg == null && mIconPackPackage != null) {
                mIconPackPackage = null;
                mIconPackMap.clear();
                mIconBackList.clear();
                mIconMaskList.clear();
            }

        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse theme config", e);
        }
    }

    private void loadTargetArrays(@NonNull String packageName) {
        Resources themeResources = getThemeResources(packageName);
        if (themeResources == null) return;

        Set<String> allTargets = new HashSet<>();

        Map<String, String> arrayCategoryMap = new HashMap<>();
        arrayCategoryMap.put(TARGET_ARRAY_SYSTEMUI, CATEGORY_SYSTEMUI);
        arrayCategoryMap.put(TARGET_ARRAY_ANDROID, CATEGORY_ANDROID);
        arrayCategoryMap.put(TARGET_ARRAY_SETTINGS, "settings");
        arrayCategoryMap.put("target_wifi", CATEGORY_STATUSBAR_WIFI);
        arrayCategoryMap.put("target_signal", CATEGORY_STATUSBAR_SIGNAL);
        arrayCategoryMap.put("target_systemui_icons", CATEGORY_SYSTEMUI);

        for (Map.Entry<String, String> entry : arrayCategoryMap.entrySet()) {
            String arrayName = entry.getKey();
            String category = entry.getValue();
            int arrayId = themeResources.getIdentifier(arrayName, "array", packageName);
            if (arrayId != 0) {
                try {
                    String[] targetNames = themeResources.getStringArray(arrayId);
                    for (String target : targetNames) {
                        allTargets.add(target);
                        mResourceCategoryCache.put(target, category);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to load target array: " + arrayName, e);
                }
            }
        }

        if (allTargets.isEmpty()) {
            String overlayCategory = null;
            try {
                android.content.pm.PackageInfo pi = mContext.getPackageManager()
                        .getPackageInfo(packageName, 0);
                overlayCategory = pi.overlayCategory;
            } catch (Exception e) {
                Slog.d(TAG, "Failed to get overlay category for " + packageName, e);
            }

            String resCategory = null;
            if ("android.theme.customization.signal_icon".equals(overlayCategory)) {
                resCategory = "signal";
            } else if ("android.theme.customization.wifi_icon".equals(overlayCategory)) {
                resCategory = "wifi";
            }

            String[] knownNames = {
                "ic_signal_cellular_0_4_bar", "ic_signal_cellular_1_4_bar",
                "ic_signal_cellular_2_4_bar", "ic_signal_cellular_3_4_bar",
                "ic_signal_cellular_4_4_bar",
                "ic_signal_cellular_0_5_bar", "ic_signal_cellular_1_5_bar",
                "ic_signal_cellular_2_5_bar", "ic_signal_cellular_3_5_bar",
                "ic_signal_cellular_4_5_bar", "ic_signal_cellular_5_5_bar",
                "ic_wifi_signal_0", "ic_wifi_signal_1", "ic_wifi_signal_2",
                "ic_wifi_signal_3", "ic_wifi_signal_4",
            };
            for (String name : knownNames) {
                if (themeResources.getIdentifier(name, "drawable", packageName) != 0) {
                    allTargets.add(name);
                    if (resCategory != null) mResourceCategoryCache.put(name, resCategory);
                }
            }
        }

        mTargetArrayCache.put(packageName, allTargets);
    }

    private void loadIconPack(String packageName) {
        mIconPackPackage = packageName;
        mIconPackMap.clear();
        mIconBackList.clear();
        mIconMaskList.clear();
        mIconScale = 1.0f;

        Resources res = getThemeResources(packageName);
        if (res == null) return;

        int resId = res.getIdentifier("appfilter", "xml", packageName);
        if (resId == 0) return;

        parseAppFilter(res, resId, packageName, mIconPackMap);
    }

    private void loadPerAppIconPack(String iconPackPackage) {
        if (mPerAppIconPackMaps.containsKey(iconPackPackage)) return;

        Resources res = getThemeResources(iconPackPackage);
        if (res == null) return;

        int resId = res.getIdentifier("appfilter", "xml", iconPackPackage);
        if (resId == 0) return;

        Map<ComponentName, String> map = new ConcurrentHashMap<>();
        parseAppFilter(res, resId, iconPackPackage, map);
        mPerAppIconPackMaps.put(iconPackPackage, map);
    }

    private void parseAppFilter(Resources res, int resId, String packageName,
            Map<ComponentName, String> targetMap) {
        try {
            XmlPullParser parser = res.getXml(resId);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("item".equals(tagName)) {
                        String component = parser.getAttributeValue(null, "component");
                        String drawable = parser.getAttributeValue(null, "drawable");
                        if (component != null && drawable != null) {
                            ComponentName cn = parseComponentInfo(component);
                            if (cn != null) {
                                targetMap.put(cn, drawable);
                            }
                        }
                    } else if ("iconback".equals(tagName)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            String val = parser.getAttributeValue(i);
                            if (val != null && !val.isEmpty()) mIconBackList.add(val);
                        }
                    } else if ("iconmask".equals(tagName)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            String val = parser.getAttributeValue(i);
                            if (val != null && !val.isEmpty()) mIconMaskList.add(val);
                        }
                    } else if ("scale".equals(tagName)) {
                        String factor = parser.getAttributeValue(null, "factor");
                        if (factor != null) {
                            try {
                                mIconScale = Float.parseFloat(factor);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            Slog.w(TAG, "Failed to parse appfilter.xml for " + packageName, e);
        }
    }

    @Nullable
    private ComponentName parseComponentInfo(String component) {
        if (!component.startsWith("ComponentInfo{") || !component.endsWith("}")) {
            return null;
        }
        String content = component.substring(14, component.length() - 1);
        String[] parts = content.split("/");
        if (parts.length != 2) return null;
        return new ComponentName(parts[0], parts[1]);
    }

    @Nullable
    private Resources getThemeResources(@NonNull String packageName) {
        Resources cached = mThemeResourcesCache.get(packageName);
        if (cached != null) return cached;

        try {
            Context themeContext = mContext.createPackageContext(
                    packageName, Context.CONTEXT_IGNORE_SECURITY);
            Resources resources = themeContext.getResources();
            mThemeResourcesCache.put(packageName, resources);
            return resources;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Theme package not found: " + packageName);
            return null;
        }
    }

    @Nullable
    private String getThemePackageForResource(@NonNull String resourceName) {
        String category = mResourceCategoryCache.get(resourceName);

        if (category != null && mCategoryThemes.containsKey(category)) {
            return mCategoryThemes.get(category);
        }

        if (category != null) {
            String aliasCategory = "statusbar_" + category;
            if (mCategoryThemes.containsKey(aliasCategory)) {
                return mCategoryThemes.get(aliasCategory);
            }
        }

        if (mActiveSystemThemeIcons != null) {
            if (category == null || mSystemThemeIconTargets.isEmpty()
                    || mSystemThemeIconTargets.contains(category)) {
                return mActiveSystemThemeIcons;
            }
            if (category != null
                    && mSystemThemeIconTargets.contains("statusbar_" + category)) {
                return mActiveSystemThemeIcons;
            }
        }

        return null;
    }

    @NonNull
    private Bitmap drawableToBitmap(@NonNull Drawable drawable, int density, boolean clearTint) {
        int size = density > 0 ? density : 192;

        if (clearTint) {
            drawable.setColorFilter(null);
            drawable.setTint(getTextColorPrimary());
        }

        if (!clearTint && drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) return bitmap;
        }

        if (drawable instanceof AdaptiveIconDrawable) {
            size = Math.max(size, 108);
        }

        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : size;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : size;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    private int getTextColorPrimary() {
        if (mTextColorPrimaryCached) return mCachedTextColorPrimary;
        try {
            int themeResId = mContext.getResources().getConfiguration().isNightModeActive()
                    ? android.R.style.Theme_DeviceDefault
                    : android.R.style.Theme_DeviceDefault_Light;
            Context themedContext = new ContextThemeWrapper(mContext, themeResId);
            TypedValue typedValue = new TypedValue();
            themedContext.getTheme().resolveAttribute(
                    android.R.attr.textColorPrimary, typedValue, true);
            if (typedValue.resourceId != 0) {
                mCachedTextColorPrimary = themedContext.getColor(typedValue.resourceId);
            } else {
                mCachedTextColorPrimary = typedValue.data != 0 ? typedValue.data : Color.WHITE;
            }
        } catch (Exception e) {
            mCachedTextColorPrimary = mContext.getResources().getConfiguration().isNightModeActive()
                    ? Color.WHITE : Color.BLACK;
        }
        mTextColorPrimaryCached = true;
        return mCachedTextColorPrimary;
    }

    private void notifyThemeChangedInternal(@Nullable String category) {
        String dispatchCategory = category;
        boolean clearDispatching = true;
        synchronized (mThemeChangeLock) {
            if (mThemeChangeDispatching) {
                queuePendingThemeChangeLocked(category);
                return;
            }
            mThemeChangeDispatching = true;
        }

        try {
            while (true) {
                dispatchThemeChanged(dispatchCategory);

                synchronized (mThemeChangeLock) {
                    if (!mThemeChangePending) {
                        mThemeChangeDispatching = false;
                        clearDispatching = false;
                        return;
                    }
                    dispatchCategory = mPendingThemeChangeCategory;
                    mPendingThemeChangeCategory = null;
                    mThemeChangePending = false;
                }
            }
        } finally {
            if (clearDispatching) {
                synchronized (mThemeChangeLock) {
                    mThemeChangeDispatching = false;
                }
            }
        }
    }

    private void queuePendingThemeChangeLocked(@Nullable String category) {
        if (!mThemeChangePending) {
            mPendingThemeChangeCategory = category;
        } else {
            mPendingThemeChangeCategory =
                    mergeThemeChangeCategories(mPendingThemeChangeCategory, category);
        }
        mThemeChangePending = true;
    }

    @Nullable
    private static String mergeThemeChangeCategories(
            @Nullable String current, @Nullable String next) {
        if (current == null || next == null) {
            return null;
        }
        return current.equals(next) ? current : null;
    }

    private void dispatchThemeChanged(@Nullable String category) {
        final long ident = Binder.clearCallingIdentity();
        try {
            loadThemeConfig();

            int count = mCallbacks.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onThemeChanged(category);
                    } catch (RemoteException | RuntimeException e) {
                        Slog.w(TAG, "Failed to notify theme callback", e);
                    }
                }
            } finally {
                mCallbacks.finishBroadcast();
            }

            if (category == null || CATEGORY_ICON_PACK.equals(category)) {
                Intent intent = new Intent(ThemeEngine.ACTION_THEME_CHANGED);
                intent.putExtra(ThemeEngine.EXTRA_THEME_CATEGORY, CATEGORY_ICON_PACK);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @NonNull
    private List<String> scanInstalledIconPacks() {
        List<String> result = new ArrayList<>();
        PackageManager pm = mContext.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo app : apps) {
            try {
                Resources res = pm.getResourcesForApplication(app);
                int filterId = res.getIdentifier("appfilter", "xml", app.packageName);
                if (filterId != 0) {
                    result.add(app.packageName);
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private final class BinderService extends IThemeEngineManager.Stub {

        @Override
        public Bitmap getIconPackIcon(ComponentName component, int density) {
            final String iconPackPkg;
            final String drawableName;
            synchronized (ThemeEngineManagerService.this) {
                iconPackPkg = mIconPackPackage;
                if (component == null || iconPackPkg == null) return null;
                drawableName = mIconPackMap.get(component);
            }
            if (drawableName == null) return null;

            String cacheKey = "iconpack:" + component.flattenToShortString() + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) return cached;

            Resources res = getThemeResources(iconPackPkg);
            if (res == null) return null;

            try {
                int resId = res.getIdentifier(drawableName, "drawable", iconPackPkg);
                if (resId != 0) {
                    Drawable drawable = res.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density, false);
                        mBitmapCache.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get icon pack icon: " + component, e);
            }
            return null;
        }

        @Override
        public boolean hasActiveIconPack() {
            synchronized (ThemeEngineManagerService.this) {
                return mIconPackPackage != null && !mIconPackMap.isEmpty();
            }
        }

        @Override
        public String getIconPackPackage() {
            synchronized (ThemeEngineManagerService.this) {
                return mIconPackPackage;
            }
        }

        @Override
        public Bitmap getPerAppIconPackIcon(String packageName, String className, int density) {
            if (packageName == null || className == null) return null;

            String iconPackPkg = mPerAppIconPacks.get(packageName);
            if (iconPackPkg == null) return null;

            loadPerAppIconPack(iconPackPkg);
            Map<ComponentName, String> packMap = mPerAppIconPackMaps.get(iconPackPkg);
            if (packMap == null) return null;

            ComponentName cn = new ComponentName(packageName, className);
            String drawableName = packMap.get(cn);
            if (drawableName == null) return null;

            String cacheKey = "perapp:" + cn.flattenToShortString()
                    + ":" + iconPackPkg + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) return cached;

            Resources res = getThemeResources(iconPackPkg);
            if (res == null) return null;

            try {
                int resId = res.getIdentifier(drawableName, "drawable", iconPackPkg);
                if (resId != 0) {
                    Drawable drawable = res.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density, false);
                        mBitmapCache.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get per-app icon pack icon", e);
            }
            return null;
        }

        @Override
        public void setPerAppIconPack(String packageName, String iconPackPackage) {
            if (packageName == null) return;
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    "setPerAppIconPack");
            final long ident = Binder.clearCallingIdentity();
            try {
                if (iconPackPackage != null && !iconPackPackage.isEmpty()) {
                    mPerAppIconPacks.put(packageName, iconPackPackage);
                } else {
                    mPerAppIconPacks.remove(packageName);
                }
                persistPerAppIconPacks();
                notifyThemeChangedInternal(CATEGORY_ICON_PACK);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void clearPerAppIconPack(String packageName) {
            setPerAppIconPack(packageName, null);
        }

        @Override
        public String getPerAppIconPack(String packageName) {
            if (packageName == null) return null;
            return mPerAppIconPacks.get(packageName);
        }

        @Override
        public List<String> getInstalledIconPacks() {
            if (mInstalledIconPacksCache == null) {
                mInstalledIconPacksCache = scanInstalledIconPacks();
            }
            return new ArrayList<>(mInstalledIconPacksCache);
        }

        @Override
        public Bitmap getSystemThemeIconDrawable(String resourceName, int density) {
            if (resourceName == null) return null;

            String cacheKey = "theme:" + resourceName + ":" + density;
            Bitmap cached = mBitmapCache.get(cacheKey);
            if (cached != null) return cached;

            String themePackage = getThemePackageForResource(resourceName);
            if (themePackage == null) return null;

            Resources themeResources = getThemeResources(themePackage);
            if (themeResources == null) return null;

            Set<String> targets = mTargetArrayCache.get(themePackage);
            if (targets != null && !targets.contains(resourceName)) {
                return null;
            }

            try {
                int resId = themeResources.getIdentifier(
                        resourceName, "drawable", themePackage);
                if (resId != 0) {
                    Drawable drawable = themeResources.getDrawableInternal(resId);
                    if (drawable != null) {
                        Bitmap bitmap = drawableToBitmap(drawable, density, true);
                        mBitmapCache.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get system theme icon: " + resourceName, e);
            }
            return null;
        }

        @Override
        public boolean isTargetedResource(String resourceName) {
            if (resourceName == null) return false;
            synchronized (ThemeEngineManagerService.this) {
                String category = mResourceCategoryCache.get(resourceName);

                if (category != null && mCategoryThemes.containsKey(category)) {
                    String pkgName = mCategoryThemes.get(category);
                    Set<String> targets = mTargetArrayCache.get(pkgName);
                    return targets != null && targets.contains(resourceName);
                }

                if (mActiveSystemThemeIcons == null) return false;

                Set<String> targets = mTargetArrayCache.get(mActiveSystemThemeIcons);
                if (targets == null || !targets.contains(resourceName)) return false;

                if (category != null && !mSystemThemeIconTargets.isEmpty()) {
                    return mSystemThemeIconTargets.contains(category);
                }

                return !mSystemThemeIconTargets.isEmpty();
            }
        }

        @Override
        public boolean hasActiveSystemThemeIcons() {
            synchronized (ThemeEngineManagerService.this) {
                return mActiveSystemThemeIcons != null
                        && (!mSystemThemeIconTargets.isEmpty()
                            || mTargetArrayCache.containsKey(mActiveSystemThemeIcons));
            }
        }

        @Override
        public String getActiveSystemThemeIcons() {
            synchronized (ThemeEngineManagerService.this) {
                return mActiveSystemThemeIcons;
            }
        }

        @Override
        public String getCategoryTheme(String category) {
            if (category == null) return null;
            return mCategoryThemes.get(category);
        }

        @Override
        public void registerCallback(IThemeEngineCallback callback) {
            if (callback != null) mCallbacks.register(callback);
        }

        @Override
        public void unregisterCallback(IThemeEngineCallback callback) {
            if (callback != null) mCallbacks.unregister(callback);
        }

        @Override
        public void notifyThemeChanged(String category) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    "notifyThemeChanged");
            notifyThemeChangedInternal(category);
        }

        @Override
        public List<String> getAvailableOverlays(String category) {
            return scanOverlaysByCategory(category);
        }
    }

    @NonNull
    private List<String> scanOverlaysByCategory(@Nullable String category) {
        List<String> result = new ArrayList<>();
        if (category == null) return result;
        PackageManager pm = mContext.getPackageManager();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(app.packageName, 0);
                if (pi.overlayTarget != null && category.equals(pi.overlayCategory)) {
                    result.add(app.packageName);
                }
            } catch (Exception e) {
                Slog.d(TAG, "Failed to check overlay for " + app.packageName, e);
            }
        }
        return result;
    }

    private void persistPerAppIconPacks() {
        mHandler.removeCallbacks(mPersistPerAppRunnable);
        mHandler.postDelayed(mPersistPerAppRunnable, PERSIST_DEBOUNCE_MS);
    }

    private void persistPerAppIconPacksNow() {
        try {
            String json = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    UserHandle.USER_CURRENT);

            JSONObject config = (json != null && !json.isEmpty())
                    ? new JSONObject(json) : new JSONObject();

            JSONObject perAppPacks = new JSONObject();
            for (Map.Entry<String, String> entry : mPerAppIconPacks.entrySet()) {
                perAppPacks.put(entry.getKey(), entry.getValue());
            }
            config.put("perAppIconPacks", perAppPacks);

            Settings.Secure.putStringForUser(
                    mContext.getContentResolver(),
                    SETTINGS_THEME_ENGINE_DATA,
                    config.toString(),
                    UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to persist per-app icon packs", e);
        }
    }
}
