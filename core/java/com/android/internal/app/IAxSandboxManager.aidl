package com.android.internal.app;

import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IHiddenNotificationListener;
import com.android.internal.app.HiddenNotificationInfo;

/**
 * @hide
 */
interface IAxSandboxManager {
    boolean isAppLocked(String packageName);
    boolean isPackageHidden(String packageName);

    void addLockedApp(String packageName);
    void removeLockedApp(String packageName);
    void setPackageHidden(String packageName, boolean hidden);

    List<String> getLockedPackages();
    List<String> getHiddenPackages();
    List<String> getLockablePackages();

    boolean isPackageLockable(String packageName);

    void unlockApp(String packageName, int userId);
    void promptUnlock(String packageName, int userId);

    void registerAppLockStateListener(IAppLockStateListener listener);
    void unregisterAppLockStateListener(IAppLockStateListener listener);
    void registerHiddenNotificationListener(IHiddenNotificationListener listener);
    void unregisterHiddenNotificationListener(IHiddenNotificationListener listener);

    List<HiddenNotificationInfo> getHiddenNotifications();
    void onHiddenNotificationPosted(in HiddenNotificationInfo info);
    void onHiddenNotificationRemoved(String key);

    boolean isPackageSandboxed(String packageName);
    void addSandboxedPackage(String packageName);
    void removeSandboxedPackage(String packageName);
    List<String> getSandboxedPackages();

    void setRestrictedGids(String packageName, in int[] gids);
    int[] getRestrictedGids(String packageName);

    boolean isSpoofSettingEnabled(String packageName, String settingKey);
    void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled);
    List<String> getEnabledSpoofSettings(String packageName);

    boolean isSandboxDataIsolationEnabled(String packageName);
    void setSandboxDataIsolationEnabled(String packageName, boolean enabled);

    String getSpoofedSetting(String callingPackage, String settingName);
}
