/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.app.HiddenNotificationInfo;
import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;
import com.android.internal.app.IHiddenNotificationListener;

import java.util.Collections;
import java.util.List;

/**
 * @hide
 */
@SystemService(Context.AX_SANDBOX_SERVICE)
public class AxSandboxManager {

    /** @hide */
    public static final int LOCK_BEHAVIOR_ON_LEAVE = 0;
    /** @hide */
    public static final int LOCK_BEHAVIOR_TIMEOUT = 1;
    /** @hide */
    public static final int LOCK_BEHAVIOR_ON_SCREEN_OFF = 2;
    /** @hide */
    public static final int LOCK_BEHAVIOR_ON_KILL = 3;

    /** @hide */
    public static final String SETTING_LOCK_BEHAVIOR = "sandbox_locked_app_behavior";
    /** @hide */
    public static final String SETTING_LOCK_TIMEOUT = "sandbox_locked_app_timeout";
    /** @hide */
    public static final String SETTING_SANDBOX_CONFIG = "sandbox_config";

    /** @hide */
    public static final String EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE";
    /** @hide */
    public static final String EXTRA_LOCKED_UID = "LOCKED_UID";
    /** @hide */
    public static final String EXTRA_LOCKED_COMPONENT = "LOCKED_COMPONENT";
    /** @hide */
    public static final String EXTRA_NOTIFICATION_APP_LOCKED = "android.app.extra.AX_APP_LOCKED";

    /** @hide */
    public static final int DEFAULT_LOCK_TIMEOUT = 30;

    /** @hide */
    public enum AppLockState {
        NONE,
        UNLOCKED,
        LOCKED;

        public boolean hasAppLock() {
            return this != NONE;
        }

        public boolean needsAuth() {
            return this == LOCKED;
        }

        public static AppLockState fromOrdinal(int ordinal) {
            AppLockState[] values = values();
            if (ordinal < 0 || ordinal >= values.length) return NONE;
            return values[ordinal];
        }
    }

    /** @hide */
    public static final int GID_INET = 3003;
    /** @hide */
    public static final int GID_SDCARD_RW = 1015;
    /** @hide */
    public static final int GID_MEDIA_RW = 1023;
    /** @hide */
    public static final int GID_EXTERNAL_STORAGE = 1077;
    /** @hide */
    public static final int GID_EXT_DATA_RW = 1078;
    /** @hide */
    public static final int GID_EXT_OBB_RW = 1079;
    /** @hide */
    public static final int GID_SHARED_USER = 9997;
    /** @hide */
    public static final int GID_PACKAGE_INFO = 1032;

    /** @hide */
    public static final int[] STORAGE_GIDS = {
            GID_SDCARD_RW, GID_MEDIA_RW, GID_EXTERNAL_STORAGE,
            GID_EXT_DATA_RW, GID_EXT_OBB_RW
    };

    /** @hide */
    public AxSandboxManager(@NonNull Context context) {
    }

    /** @hide */
    public boolean isAppLocked(@NonNull String packageName) {
        return getAppLockState(packageName).needsAuth();
    }

    /** @hide */
    public AppLockState getAppLockState(@NonNull String packageName) {
        try {
            return AppLockState.fromOrdinal(getService().getSandboxAppLockState(packageName));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public AppLockState getAppLockStateForUser(@NonNull String packageName, int userId) {
        try {
            return AppLockState.fromOrdinal(
                    getService().getSandboxAppLockStateForUser(packageName, userId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void addLockedApp(@NonNull String packageName) {
        try {
            getService().addSandboxLockedApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void removeLockedApp(@NonNull String packageName) {
        try {
            getService().removeSandboxLockedApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isPackageHidden(@NonNull String packageName) {
        try {
            return getService().isSandboxPackageHidden(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setPackageHidden(@NonNull String packageName, boolean hidden) {
        try {
            getService().setSandboxPackageHidden(packageName, hidden);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getLockedPackages() {
        try {
            List<String> result = getService().getSandboxLockedPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getHiddenPackages() {
        try {
            List<String> result = getService().getSandboxHiddenPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getLockablePackages() {
        try {
            List<String> result = getService().getSandboxLockablePackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isPackageLockable(@NonNull String packageName) {
        try {
            return getService().isSandboxPackageLockable(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unlockApp(@NonNull String packageName, int userId) {
        try {
            getService().unlockSandboxApp(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void promptUnlock(@NonNull String packageName, int userId) {
        try {
            getService().promptSandboxUnlock(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerAppLockStateListener(IAppLockStateListener listener) {
        try {
            getService().registerSandboxAppLockStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        try {
            getService().unregisterSandboxAppLockStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerAppSessionListener(IAppSessionListener listener) {
        try {
            getService().registerSandboxAppSessionListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterAppSessionListener(IAppSessionListener listener) {
        try {
            getService().unregisterSandboxAppSessionListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerHiddenNotificationListener(IHiddenNotificationListener listener) {
        try {
            getService().registerSandboxHiddenNotificationListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterHiddenNotificationListener(IHiddenNotificationListener listener) {
        try {
            getService().unregisterSandboxHiddenNotificationListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public List<HiddenNotificationInfo> getHiddenNotifications() {
        try {
            return getService().getSandboxHiddenNotifications();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        try {
            getService().onSandboxHiddenNotificationPosted(info);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void onHiddenNotificationRemoved(String key) {
        try {
            getService().onSandboxHiddenNotificationRemoved(key);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isPackageSandboxed(@NonNull String packageName) {
        try {
            return getService().isSandboxPackageSandboxed(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void addSandboxedPackage(@NonNull String packageName) {
        try {
            getService().addSandboxPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void removeSandboxedPackage(@NonNull String packageName) {
        try {
            getService().removeSandboxPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getSandboxedPackages() {
        try {
            List<String> result = getService().getSandboxPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setRestrictedGids(@NonNull String packageName, int[] gids) {
        try {
            getService().setSandboxRestrictedGids(packageName, gids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public int[] getRestrictedGids(@NonNull String packageName) {
        try {
            return getService().getSandboxRestrictedGids(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isSandboxDataIsolationEnabled(@NonNull String packageName) {
        try {
            return getService().isSandboxDataIsolationEnabled(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setSandboxDataIsolationEnabled(@NonNull String packageName, boolean enabled) {
        try {
            getService().setSandboxDataIsolationEnabled(packageName, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isSpoofSettingEnabled(@NonNull String packageName, @NonNull String settingKey) {
        try {
            return getService().isSandboxSpoofSettingEnabled(packageName, settingKey);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setSpoofSettingEnabled(@NonNull String packageName, @NonNull String settingKey,
            boolean enabled) {
        try {
            getService().setSandboxSpoofSettingEnabled(packageName, settingKey, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @NonNull
    public List<String> getEnabledSpoofSettings(@NonNull String packageName) {
        try {
            List<String> result = getService().getSandboxEnabledSpoofSettings(packageName);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Nullable
    public String getSpoofedSetting(@NonNull String callingPackage, @NonNull String settingName) {
        try {
            return getService().getSandboxSpoofedSetting(callingPackage, settingName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IActivityManager getService() {
        return ActivityManager.getService();
    }
}
