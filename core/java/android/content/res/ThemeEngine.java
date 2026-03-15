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

package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @hide
 */
public class ThemeEngine {
    private static final String TAG = "ThemeEngine";

    private static final String[] TARGET_PKGS = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher3"
    };

    public static final String SETTINGS_THEME_ENGINE_DATA = "theme_engine_data";

    public static final String ACTION_THEME_CHANGED = "android.intent.action.THEME_ENGINE_CHANGED";
    public static final String EXTRA_THEME_CATEGORY = "theme_category";

    public static final String CATEGORY_ICON_PACK = "icon_pack";
    public static final String CATEGORY_STATUSBAR_WIFI = "statusbar_wifi";
    public static final String CATEGORY_STATUSBAR_SIGNAL = "statusbar_signal";
    public static final String CATEGORY_ANDROID = "android";
    public static final String CATEGORY_SYSTEMUI = "systemui";
    public static final String CATEGORY_UI_QS = "ui_qs";
    public static final String CATEGORY_UI_VOLUME = "ui_volume";

    private static volatile ThemeEngine sInstance;

    private final Context mContext;
    private volatile IThemeEngineManager mService;

    private ThemeEngine(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    @Nullable
    public static ThemeEngine getInstance(@Nullable Context context) {
        if (sInstance == null) {
            synchronized (ThemeEngine.class) {
                if (sInstance == null) {
                    Context app = context != null ? context.getApplicationContext() : null;
                    if (app == null) {
                        Application currentApp = ActivityThread.currentApplication();
                        if (currentApp != null) {
                            app = currentApp;
                        }
                    }
                    if (app != null) {
                        sInstance = new ThemeEngine(app);
                    }
                }
            }
        }
        return sInstance;
    }

    @Nullable
    public static ThemeEngine getInstance() {
        if (sInstance != null) return sInstance;
        Application app = ActivityThread.currentApplication();
        return app != null ? getInstance(app) : null;
    }

    @Nullable
    private synchronized IThemeEngineManager getService() {
        if (mService != null) return mService;
        IBinder binder = ServiceManager.getService(Context.THEME_ENGINE_SERVICE);
        if (binder != null) {
            mService = IThemeEngineManager.Stub.asInterface(binder);
            try {
                binder.linkToDeath(() -> {
                    synchronized (ThemeEngine.this) {
                        mService = null;
                    }
                }, 0);
            } catch (RemoteException e) {
                mService = null;
            }
        }
        return mService;
    }

    // Icon pack methods

    @Nullable
    public Drawable getIconPackDrawable(@NonNull ComponentName componentName, int density) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            Bitmap bitmap = service.getIconPackIcon(componentName, density);
            return bitmap != null ? new BitmapDrawable(mContext.getResources(), bitmap) : null;
        } catch (RemoteException e) {
            Log.w(TAG, "getIconPackDrawable failed", e);
            return null;
        }
    }

    @Nullable
    public Drawable getIconPackDrawable(@NonNull ComponentName componentName) {
        return getIconPackDrawable(componentName, 0);
    }

    public boolean hasActiveIconPack() {
        IThemeEngineManager service = getService();
        if (service == null) return false;
        try {
            return service.hasActiveIconPack();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Nullable
    public String getIconPackPackage() {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getIconPackPackage();
        } catch (RemoteException e) {
            return null;
        }
    }

    // Per-app icon pack methods

    @Nullable
    public Drawable getPerAppIconPackDrawable(
            @NonNull String packageName, @NonNull String className, int density) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            Bitmap bitmap = service.getPerAppIconPackIcon(packageName, className, density);
            return bitmap != null ? new BitmapDrawable(mContext.getResources(), bitmap) : null;
        } catch (RemoteException e) {
            Log.w(TAG, "getPerAppIconPackDrawable failed", e);
            return null;
        }
    }

    public void setPerAppIconPack(@NonNull String packageName, @NonNull String iconPackPackage) {
        IThemeEngineManager service = getService();
        if (service == null) return;
        try {
            service.setPerAppIconPack(packageName, iconPackPackage);
        } catch (RemoteException e) {
            Log.w(TAG, "setPerAppIconPack failed", e);
        }
    }

    public void clearPerAppIconPack(@NonNull String packageName) {
        IThemeEngineManager service = getService();
        if (service == null) return;
        try {
            service.clearPerAppIconPack(packageName);
        } catch (RemoteException e) {
            Log.w(TAG, "clearPerAppIconPack failed", e);
        }
    }

    @Nullable
    public String getPerAppIconPack(@NonNull String packageName) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getPerAppIconPack(packageName);
        } catch (RemoteException e) {
            return null;
        }
    }

    @Nullable
    public List<String> getInstalledIconPacks() {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getInstalledIconPacks();
        } catch (RemoteException e) {
            return null;
        }
    }

    // System theme icon methods (statusbar/systemui drawable theming)

    @Nullable
    public Drawable getSystemThemeIconDrawable(@NonNull String resourceName) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            Bitmap bitmap = service.getSystemThemeIconDrawable(resourceName, 0);
            return bitmap != null ? new BitmapDrawable(mContext.getResources(), bitmap) : null;
        } catch (RemoteException e) {
            Log.w(TAG, "getSystemThemeIconDrawable failed", e);
            return null;
        }
    }

    @Nullable
    public Drawable getSystemThemeIconDrawable(@NonNull Resources userResources, int resId) {
        try {
            return getSystemThemeIconDrawable(userResources.getResourceEntryName(resId));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isTargetedResource(@NonNull String resourceName) {
        IThemeEngineManager service = getService();
        if (service == null) return false;
        try {
            return service.isTargetedResource(resourceName);
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean hasActiveSystemThemeIcons() {
        IThemeEngineManager service = getService();
        if (service == null) return false;
        try {
            return service.hasActiveSystemThemeIcons();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Nullable
    public String getActiveSystemThemeIcons() {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getActiveSystemThemeIcons();
        } catch (RemoteException e) {
            return null;
        }
    }

    public boolean shouldOverlayResource(@NonNull Resources userResources, int resId) {
        if (!isTargetPkg()) return false;
        try {
            String entryName = userResources.getResourceEntryName(resId);
            if (!isTargetedResource(entryName)) return false;
            String packageName = userResources.getResourcePackageName(resId);
            String themePackage = getActiveSystemThemeIcons();
            return themePackage == null || !themePackage.equals(packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // Overlay scanning

    @Nullable
    public List<String> getAvailableOverlays(@NonNull String category) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getAvailableOverlays(category);
        } catch (RemoteException e) {
            return null;
        }
    }

    // Category methods

    @Nullable
    public String getEnabledPackage(@NonNull String category) {
        IThemeEngineManager service = getService();
        if (service == null) return null;
        try {
            return service.getCategoryTheme(category);
        } catch (RemoteException e) {
            return null;
        }
    }

    // Theme change listener

    public interface ThemeChangeListener {
        void onThemeChanged(@Nullable String category);
    }

    private final CopyOnWriteArrayList<ThemeChangeListener> mListeners =
            new CopyOnWriteArrayList<>();
    private IThemeEngineCallback mServiceCallback;

    /** @hide */
    public void addThemeChangeListener(@NonNull ThemeChangeListener listener) {
        mListeners.add(listener);
        if (mListeners.size() == 1) {
            registerServiceCallback();
        }
    }

    /** @hide */
    public void removeThemeChangeListener(@NonNull ThemeChangeListener listener) {
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            unregisterServiceCallback();
        }
    }

    /** @hide */
    public void notifyThemeChanged(@Nullable String category) {
        IThemeEngineManager service = getService();
        if (service == null) return;
        try {
            service.notifyThemeChanged(category);
        } catch (RemoteException e) {
            Log.w(TAG, "notifyThemeChanged failed", e);
        }
    }

    private void registerServiceCallback() {
        IThemeEngineManager service = getService();
        if (service == null) return;

        mServiceCallback = new IThemeEngineCallback.Stub() {
            @Override
            public void onThemeChanged(String category) {
                for (ThemeChangeListener listener : mListeners) {
                    try {
                        listener.onThemeChanged(category);
                    } catch (Exception e) {
                        Log.w(TAG, "Error dispatching theme change", e);
                    }
                }
            }
        };

        try {
            service.registerCallback(mServiceCallback);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register callback", e);
        }
    }

    private void unregisterServiceCallback() {
        if (mServiceCallback == null) return;
        IThemeEngineManager service = getService();
        if (service != null) {
            try {
                service.unregisterCallback(mServiceCallback);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to unregister callback", e);
            }
        }
        mServiceCallback = null;
    }

    private boolean isTargetPkg() {
        if (mContext == null) return false;
        String packageName = mContext.getPackageName();
        for (String pkg : TARGET_PKGS) {
            if (pkg.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}
