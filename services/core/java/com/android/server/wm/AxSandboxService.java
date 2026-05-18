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
package com.android.server.wm;

import static android.app.AxSandboxManager.AppLockState.LOCKED;
import static android.app.AxSandboxManager.AppLockState.NONE;
import static android.app.AxSandboxManager.AppLockState.UNLOCKED;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AxSandboxManager;
import android.app.AxSandboxManager.AppLockState;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;
import com.android.internal.app.IAxSandboxManager;
import com.android.internal.app.IHiddenNotificationListener;
import com.android.internal.app.HiddenNotificationInfo;

import com.android.server.NtServiceInjector;
import com.android.server.LocalServices;
import com.android.server.wm.sandbox.AppControlController;
import com.android.server.wm.sandbox.HiddenNotificationController;
import com.android.server.wm.sandbox.SettingsSpoofController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AxSandboxService extends IAxSandboxManager.Stub implements IAxSandboxService {
    private static final String TAG = "AxSandbox";

    public static final String SANDBOX_PACKAGE = "com.android.applocker";
    private static final String SANDBOX_ACTIVITY = "com.android.applocker.AuthenticateActivity";

    private static final String EXTRA_LOCKED_UID = AxSandboxManager.EXTRA_LOCKED_UID;
    private static final String EXTRA_LOCKED_PACKAGE = AxSandboxManager.EXTRA_LOCKED_PACKAGE;
    private static final String EXTRA_LOCKED_COMPONENT = AxSandboxManager.EXTRA_LOCKED_COMPONENT;
    private static final String EXTRA_USER_ID = "user_id";

    private static final String ACTION_SYSTEM_UNLOCK = "com.android.applocker.action.SYSTEM_UNLOCK";

    private static final String SETTING_LOCK_BEHAVIOR = AxSandboxManager.SETTING_LOCK_BEHAVIOR;
    private static final String SETTING_LOCK_TIMEOUT = AxSandboxManager.SETTING_LOCK_TIMEOUT;

    private static final int LOCK_BEHAVIOR_ON_LEAVE = AxSandboxManager.LOCK_BEHAVIOR_ON_LEAVE;
    private static final int LOCK_BEHAVIOR_TIMEOUT = AxSandboxManager.LOCK_BEHAVIOR_TIMEOUT;
    private static final int LOCK_BEHAVIOR_ON_SCREEN_OFF = AxSandboxManager.LOCK_BEHAVIOR_ON_SCREEN_OFF;
    private static final int LOCK_BEHAVIOR_ON_KILL = AxSandboxManager.LOCK_BEHAVIOR_ON_KILL;

    public static final Set<String> BLACKLISTED_PACKAGES = Set.of(
            "android",
            SANDBOX_PACKAGE,
            "com.android.axion.sandbox",
            "com.android.settings"
    );

    private ActivityTaskManagerService mAtms;
    private Context mContext;
    private SettingsObserver mSettingsObserver;
    private ResolveInfo mSandboxResolveInfo;
    private Intent mConfirmIntent;
    private int mRequestCode;

    private AppControlController mAppControlController;
    private HiddenNotificationController mHiddenNotificationController;

    private final RemoteCallbackList<IAppLockStateListener> mAppLockStateListeners =
            new RemoteCallbackList<>();
    private final RemoteCallbackList<IAppSessionListener> mAppSessionListeners =
            new RemoteCallbackList<>();

    private final Set<String> mUnlockedApps = ConcurrentHashMap.newKeySet();
    private final Set<String> mPendingUnlocks = new HashSet<>();
    private final Map<String, Long> mUnlockTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Runnable> mTimeoutRunnables = new ConcurrentHashMap<>();
    private String mLastFocusedAppKey = null;
    private ArrayList<String> mExcludedComponents = new ArrayList<>();

    private int mLockBehavior = LOCK_BEHAVIOR_ON_LEAVE;
    private int mLockTimeout = 30;
    private boolean mKeyguardDone = true;
    private boolean mCheckRecentTasks = false;
    private int mCurrentUserId = 0;

    private static final class Holder {
        private static final AxSandboxService INSTANCE = new AxSandboxService();
    }

    public static AxSandboxService get() {
        return Holder.INSTANCE;
    }

    private AxSandboxService() {
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_LOCK_BEHAVIOR), false, this, -1);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_LOCK_TIMEOUT), false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            mLockBehavior = Settings.Secure.getIntForUser(resolver, SETTING_LOCK_BEHAVIOR,
                    LOCK_BEHAVIOR_ON_LEAVE, -2);
            mLockTimeout = Settings.Secure.getIntForUser(resolver, SETTING_LOCK_TIMEOUT,
                    AxSandboxManager.DEFAULT_LOCK_TIMEOUT, -2);
            Slog.d(TAG, "Settings changed: lockBehavior=" + mLockBehavior
                    + " lockTimeout=" + mLockTimeout);
        }
    }

    private final BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) return;
            String packageName = intent.getData().getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) return;
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (replacing) return;

            Slog.i(TAG, "Package removed: " + packageName + ", cleaning up sandbox entries");
            cleanupPackage(packageName);
        }
    };

    private void cleanupPackage(String packageName) {
        if (mAppControlController == null) return;

        boolean changed = false;

        if (mAppControlController.isAppLocked(packageName)) {
            mAppControlController.removeLockedApp(packageName);
            notifyAppLockStateChanged(packageName, false);
            changed = true;
        }

        if (mAppControlController.isPackageHidden(packageName)) {
            mAppControlController.setPackageHidden(packageName, false);
            changed = true;
        }

        if (mAppControlController.isPackageSandboxed(packageName)) {
            mAppControlController.setPackageSandboxed(packageName, false);
            changed = true;
        }

        if (mHiddenNotificationController != null) {
            mHiddenNotificationController.clearNotificationsForPackage(packageName);
        }

        for (String key : new ArrayList<>(mUnlockedApps)) {
            if (key.endsWith(":" + packageName)) {
                mUnlockedApps.remove(key);
                mUnlockTimestamps.remove(key);
                cancelTimeoutLock(key);
            }
        }

        synchronized (mPendingUnlocks) {
            mPendingUnlocks.removeIf(key -> key.endsWith(":" + packageName));
        }

        if (changed) {
            Slog.i(TAG, "Cleaned up sandbox entries for uninstalled package: " + packageName);
        }
    }

    @Override
    public void systemReadyInternal() {
        Slog.d(TAG, "systemReady");
        mAtms = NtServiceInjector.get().getActivityTaskManagerService();
        mContext = NtServiceInjector.get().getContext();

        try {
            mSandboxResolveInfo = mContext.getPackageManager().resolveActivity(
                    getConfirmIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        } catch (Exception e) {
            Slog.w(TAG, "Could not resolve Sandbox activity", e);
        }

        mRequestCode = getConfirmIntent().toString().hashCode() & 0x0FFFFFFF;
        mExcludedComponents = new ArrayList<>();
        mSettingsObserver = new SettingsObserver(mAtms.mH);
        mSettingsObserver.onChange(true);

        mAppControlController = new AppControlController(mContext, BLACKLISTED_PACKAGES);
        mAppControlController.init();

        mHiddenNotificationController = new HiddenNotificationController();
        mHiddenNotificationController.setPackageHiddenChecker(this::isPackageHidden);

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mPackageRemovedReceiver, UserHandle.ALL, filter, null, mAtms.mH);
    }

    public static void systemReady() {
        AxSandboxService instance = get();
        instance.systemReadyInternal();
        ServiceManager.addService(Context.AX_SANDBOX_SERVICE, instance);
        Slog.i(TAG, "AxSandboxService ready");
    }

    private Intent getConfirmIntent() {
        if (mConfirmIntent == null) {
            mConfirmIntent = new Intent(ACTION_SYSTEM_UNLOCK);
            mConfirmIntent.setClassName(SANDBOX_PACKAGE, SANDBOX_ACTIVITY);
            mConfirmIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        return mConfirmIntent;
    }

    @Override
    public int getAppLockState(String packageName) {
        return computeAppLockState(packageName, UserHandle.getUserId(Binder.getCallingUid()))
                .ordinal();
    }

    @Override
    public int getAppLockStateForUser(String packageName, int userId) {
        return computeAppLockState(packageName, userId).ordinal();
    }

    public boolean hasAppLock(String packageName) {
        return computeAppLockState(packageName, UserHandle.getUserId(Binder.getCallingUid()))
                .hasAppLock();
    }

    private AppLockState computeAppLockState(String packageName, int userId) {
        if (mAppControlController == null) return NONE;
        if (BLACKLISTED_PACKAGES.contains(packageName)) {
            return NONE;
        }
        if (!mAppControlController.isAppLocked(packageName)) {
            return NONE;
        }
        if (!mKeyguardDone) {
            return LOCKED;
        }

        String key = sessionKey(userId, packageName);
        boolean sessionUnlocked = mUnlockedApps.contains(key);

        if (sessionUnlocked && mLockBehavior == LOCK_BEHAVIOR_TIMEOUT) {
            Long lastUsed = mUnlockTimestamps.get(key);
            if (lastUsed != null && (SystemClock.elapsedRealtime() - lastUsed) > (mLockTimeout * 1000L)) {
                sessionUnlocked = false;
            }
        }
        return sessionUnlocked
                ? UNLOCKED
                : LOCKED;
    }

    @Override
    public boolean isPackageHidden(String packageName) {
        if (mAppControlController == null) return false;
        return mAppControlController.isPackageHidden(packageName);
    }

    @Override
    public void addLockedApp(String packageName) {
        mAppControlController.addLockedApp(packageName);
        notifyAppLockStateChanged(packageName, true);
    }

    @Override
    public void removeLockedApp(String packageName) {
        mAppControlController.removeLockedApp(packageName);
        int uid = getPackageUid(packageName);
        if (uid >= 0) {
            int userId = UserHandle.getUserId(uid);
            markSessionLocked(packageName, userId);
        }
        notifyAppLockStateChanged(packageName, false);
    }

    @Override
    public void setPackageHidden(String packageName, boolean hidden) {
        mAppControlController.setPackageHidden(packageName, hidden);
        broadcastPackageChanged(packageName);
    }

    @Override
    public List<String> getLockedPackages() {
        return mAppControlController.getLockedPackages();
    }

    @Override
    public List<String> getHiddenPackages() {
        return mAppControlController.getHiddenPackages();
    }

    @Override
    public List<String> getLockablePackages() {
        return mAppControlController.getLockablePackages();
    }

    @Override
    public boolean isPackageLockable(String packageName) {
        return mAppControlController.isPackageLockable(packageName);
    }

    @Override
    public void unlockApp(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        markSessionUnlocked(packageName, userId);

        clearPendingUnlock(packageName, userId);

        Slog.d(TAG, "unlockApp: " + packageName + " for user " + userId);
    }

    @Override
    public void promptUnlock(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;

        Intent intent = new Intent(getConfirmIntent());
        intent.putExtra(EXTRA_LOCKED_PACKAGE, packageName);
        intent.putExtra(EXTRA_LOCKED_UID, userId);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra("app_label", resolveAppLabel(packageName, userId));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        long identity = Binder.clearCallingIdentity();
        try {
            mAtms.getActivityStartController()
                    .obtainStarter(intent, "promptUnlock->AxSandbox")
                    .setCallingUid(0)
                    .setActivityInfo(mSandboxResolveInfo != null ? mSandboxResolveInfo.activityInfo : null)
                    .execute();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void registerAppLockStateListener(IAppLockStateListener listener) {
        mAppLockStateListeners.register(listener);
    }

    @Override
    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        mAppLockStateListeners.unregister(listener);
    }

    @Override
    public void registerAppSessionListener(IAppSessionListener listener) {
        mAppSessionListeners.register(listener);
    }

    @Override
    public void unregisterAppSessionListener(IAppSessionListener listener) {
        mAppSessionListeners.unregister(listener);
    }

    @Override
    public void registerHiddenNotificationListener(IHiddenNotificationListener listener) {
        mHiddenNotificationController.registerListener(listener);
    }

    @Override
    public void unregisterHiddenNotificationListener(IHiddenNotificationListener listener) {
        mHiddenNotificationController.unregisterListener(listener);
    }

    @Override
    public List<HiddenNotificationInfo> getHiddenNotifications() {
        return mHiddenNotificationController.getHiddenNotifications();
    }

    @Override
    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        mHiddenNotificationController.onHiddenNotificationPosted(info);
    }

    @Override
    public void onHiddenNotificationRemoved(String key) {
        mHiddenNotificationController.onHiddenNotificationRemoved(key);
    }

    @Override
    public boolean isPackageSandboxed(String packageName) {
        if (mAppControlController == null) return false;
        return mAppControlController.isPackageSandboxed(packageName);
    }

    @Override
    public void addSandboxedPackage(String packageName) {
        mAppControlController.setPackageSandboxed(packageName, true);
        broadcastPackageChanged(packageName);
    }

    @Override
    public void removeSandboxedPackage(String packageName) {
        mAppControlController.setPackageSandboxed(packageName, false);
        broadcastPackageChanged(packageName);
    }

    @Override
    public List<String> getSandboxedPackages() {
        return mAppControlController.getSandboxedPackages();
    }

    @Override
    public void setRestrictedGids(String packageName, int[] gids) {
        mAppControlController.setRestrictedGids(packageName, gids);
    }

    @Override
    public int[] getRestrictedGids(String packageName) {
        if (mAppControlController == null) return null;
        return mAppControlController.getRestrictedGids(packageName);
    }

    @Override
    public boolean isSandboxDataIsolationEnabled(String packageName) {
        if (mAppControlController == null) return false;
        return mAppControlController.isDataIsolationEnabled(packageName);
    }

    @Override
    public void setSandboxDataIsolationEnabled(String packageName, boolean enabled) {
        mAppControlController.setDataIsolationEnabled(packageName, enabled);
    }

    @Override
    public boolean isSpoofSettingEnabled(String packageName, String settingKey) {
        if (mAppControlController == null) return false;
        return mAppControlController.isSpoofSettingEnabled(packageName, settingKey);
    }

    @Override
    public void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled) {
        mAppControlController.setSpoofSettingEnabled(packageName, settingKey, enabled);
    }

    @Override
    public List<String> getEnabledSpoofSettings(String packageName) {
        if (mAppControlController == null) return java.util.Collections.emptyList();
        return mAppControlController.getEnabledSpoofSettings(packageName);
    }

    @Override
    public String getSpoofedSetting(String callingPackage, String settingName) {
        if (mAppControlController == null) return null;
        final String spoofedValue = SettingsSpoofController.getSpoofedValue(settingName);
        if (spoofedValue == null) return null;
        if (!mAppControlController.isSpoofSettingEnabled(callingPackage, settingName)) {
            return null;
        }
        return spoofedValue;
    }

    @Override
    public boolean isAppLocked(ActivityRecord r) {
        if (r == null || !hasLockedPackages() || r.isNoDisplay()
                || r.isActivityTypeHomeOrRecents()) {
            return false;
        }

        int userId = r.mUserId;
        if (mSandboxResolveInfo == null || mCurrentUserId != userId) {
            List<ResolveInfo> list = mContext.getPackageManager()
                    .queryIntentActivitiesAsUser(mConfirmIntent, PackageManager.MATCH_SYSTEM_ONLY, userId);
            for (int i = 0; list != null && i < list.size(); i++) {
                if ((list.get(i).activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    mSandboxResolveInfo = list.get(i);
                    break;
                }
            }
            ResolveInfo ri = mSandboxResolveInfo;
            if (ri != null && ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                int newUserId = UserHandle.getUserId(ri.activityInfo.applicationInfo.uid);
                if (mCurrentUserId != newUserId) {
                    Slog.i(TAG, "Update user from " + mCurrentUserId + " to " + newUserId);
                    mCurrentUserId = newUserId;
                }
            }
            mSettingsObserver.onChange(true);
        }
        return isAppLocked(r.packageName, r.getUid(), r.mActivityComponent);
    }

    @Override
    public boolean isAppLocked(String packageName, int uid, ComponentName component) {
        if (mAppControlController == null) return false;
        if (BLACKLISTED_PACKAGES.contains(packageName)) return false;

        int userId = UserHandle.getUserId(uid);

        boolean isLocked = mAppControlController.isAppLocked(packageName);

        if (!isLocked) return false;

        String key = sessionKey(userId, packageName);
        boolean isAlreadyUnlocked = mUnlockedApps.contains(key);

        if (isAlreadyUnlocked && mLockBehavior == LOCK_BEHAVIOR_TIMEOUT) {
            Long lastUsed = mUnlockTimestamps.get(key);
            if (lastUsed != null && (SystemClock.elapsedRealtime() - lastUsed) > (mLockTimeout * 1000L)) {
                isAlreadyUnlocked = false;
            }
        }

        boolean isExcluded = component != null && mExcludedComponents.contains(component.getClassName());

        if (mKeyguardDone && isLocked) {
            if (!isAlreadyUnlocked && !isExcluded) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void lockTopApp(Task task, String reason) {
        if (task == null || !hasLockedPackages()) return;

        ActivityRecord r = task.topRunningActivityLocked();
        if (!isAppLocked(r)) return;

        String key = sessionKey(r);
        if (mUnlockedApps.contains(key)) {
            return;
        }

        Slog.i(TAG, "lockTopApp: blocking " + r + " reason=" + reason);
        startAuthPrompt(r, reason);
    }

    @Override
    public boolean checkLockApp(ActivityRecord prev, ActivityRecord next) {
        if (next == null) return false;

        clearUnlockedApp(next);

        if (!isAppLocked(next)) return false;

        Slog.i(TAG, "checkLockApp: blocking resume " + next + " app=" + next.app);
        if (!startAuthPrompt(next, "lockApp->AxSandbox")) {
            return false;
        }

        if (prev != null && prev.finishing) {
            prev.setVisibility(false);
        }
        next.mRootWindowContainer.ensureActivitiesVisible();
        return true;
    }

    private boolean startAuthPrompt(ActivityRecord target, String reason) {
        if (target == null) return false;

        String pendingKey = sessionKey(target);

        if (mUnlockedApps.contains(pendingKey)) {
            Slog.d(TAG, "startAuthPrompt: skip, already unlocked for " + pendingKey);
            return true;
        }

        synchronized (mPendingUnlocks) {
            if (!mPendingUnlocks.add(pendingKey)) {
                if (hasAppLockerActivity(target.getTask())) {
                    Slog.d(TAG, "startAuthPrompt: skip, already pending for " + pendingKey);
                    return true;
                }
                Slog.d(TAG, "startAuthPrompt: retry stale pending for " + pendingKey);
            }
        }

        try {
            Intent intent = new Intent(getConfirmIntent());
            intent.putExtra(EXTRA_LOCKED_UID, target.getUid());
            intent.putExtra(EXTRA_LOCKED_PACKAGE, target.packageName);
            intent.putExtra(EXTRA_USER_ID, target.mUserId);
            intent.putExtra(EXTRA_LOCKED_COMPONENT,
                    target.intent.getComponent() != null
                            ? target.intent.getComponent().flattenToString() : "");
            intent.putExtra("app_label", resolveAppLabel(target.packageName, target.mUserId));

            WindowProcessController wpc = target.app;
            Slog.d(TAG, "startAuthPrompt: launching AuthenticateActivity"
                    + " target=" + target.packageName
                    + " targetToken=" + target.token
                    + " targetTask=" + (target.getTask() != null ? target.getTask().mTaskId : -1)
                    + " wpc=" + (wpc == null ? "null" : "attached")
                    + " reason=" + reason);
            if (wpc == null) {
                mAtms.getActivityStartController()
                        .obtainStarter(intent, reason)
                        .setCallingUid(0)
                        .setResultTo(target.token)
                        .setRequestCode(mRequestCode)
                        .setActivityInfo(mSandboxResolveInfo != null
                                ? mSandboxResolveInfo.activityInfo : null)
                        .execute();
            } else {
                startActivityAsCaller(wpc.getThread(), target.packageName, intent,
                        "", target.token, target.resultWho, mRequestCode);
            }

            abortAnimation(target);
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "startAuthPrompt: failed for " + target, e);
            lockSession(target.packageName, target.mUserId);
            if (!target.finishing) {
                target.finishIfPossible("applock-prompt-failed", false);
            }
            return true;
        }
    }

    private String resolveAppLabel(String packageName, int userId) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfoAsUser(packageName, 0, userId);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public boolean checkUnlockApp(ActivityRecord r, int resultCode, Intent data) {
        if (r.requestCode != mRequestCode || r.intent == null
                || !isAppLockerActivity(r.intent.getComponent())) {
            return false;
        }
        String packageName = getResultPackageName(r, data);
        int userId = getResultUserId(r, data, packageName);
        if (data == null) {
            ActivityRecord target = r.resultTo;
            if (target == null) {
                return true;
            }
            lockSession(packageName, userId);
            if (!target.finishing) {
                Slog.d(TAG, "checkUnlockApp: finishing target " + target + " on cancel");
                target.finishIfPossible("applock-canceled", false);
            }
            return true;
        }
        try {
            Slog.d(TAG, "checkUnlockApp: unlocking pkg=" + packageName
                    + " userId=" + userId + " resultCode=" + resultCode);

            if (resultCode == Activity.RESULT_OK && !TextUtils.isEmpty(packageName)) {
                clearPendingUnlock(packageName, userId);
                markSessionUnlocked(packageName, userId);
            } else if (r.resultTo != null) {
                Slog.d(TAG, "checkUnlockApp: finishing target " + r.resultTo + " on cancel");
                lockSession(packageName, userId);
                r.resultTo.finishIfPossible("applock-canceled", false);
            } else {
                lockSession(packageName, userId);
            }
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "checkUnlockApp: failed", e);
            return false;
        }
    }

    private String getResultPackageName(ActivityRecord authRecord, Intent data) {
        ActivityRecord target = authRecord.resultTo;
        if (target != null) {
            return target.packageName;
        }
        if (data != null) {
            String packageName = data.getStringExtra(EXTRA_LOCKED_PACKAGE);
            if (!TextUtils.isEmpty(packageName)) {
                return packageName;
            }
        }
        return authRecord.intent != null
                ? authRecord.intent.getStringExtra(EXTRA_LOCKED_PACKAGE) : null;
    }

    private int getResultUserId(ActivityRecord authRecord, Intent data, String packageName) {
        ActivityRecord target = authRecord.resultTo;
        if (target != null && TextUtils.equals(target.packageName, packageName)) {
            return target.mUserId;
        }
        if (data != null && data.hasExtra(EXTRA_USER_ID)) {
            return data.getIntExtra(EXTRA_USER_ID, UserHandle.USER_SYSTEM);
        }
        Intent intent = authRecord.intent;
        if (intent != null && intent.hasExtra(EXTRA_USER_ID)) {
            return intent.getIntExtra(EXTRA_USER_ID, UserHandle.USER_SYSTEM);
        }
        Intent source = data != null ? data : intent;
        return source != null
                ? getUserIdFromLockedUidExtra(source)
                : UserHandle.USER_SYSTEM;
    }

    private static int getUserIdFromLockedUidExtra(Intent intent) {
        int value = intent.getIntExtra(EXTRA_LOCKED_UID, UserHandle.USER_SYSTEM);
        return value >= Process.FIRST_APPLICATION_UID || value == Process.SYSTEM_UID
                ? UserHandle.getUserId(value) : value;
    }

    @Override
    public boolean isSandboxActivity(ComponentName component) {
        return component != null
                && SANDBOX_PACKAGE.equals(component.getPackageName())
                && SANDBOX_ACTIVITY.equals(component.getClassName());
    }

    @Override
    public boolean isTopAppLocked(ActivityManager.RecentTaskInfo rti, int topUserId) {
        rti.isTopAppLocked = false;
        if (mCheckRecentTasks) {
            ComponentName component = rti.baseIntent.getComponent();
            String packageName;
            int uid = topUserId;
            if (component != null) {
                packageName = component.getPackageName();
            } else {
                packageName = "";
                uid = 0;
            }

            long identity = Binder.clearCallingIdentity();
            try {
                int userId = UserHandle.getUserId(uid);
                if (isSandboxActivity(component)) {
                    rti.isTopAppLocked = true;
                } else {
                    if (mAppControlController.isAppLocked(packageName)) {
                        if (mLockBehavior == LOCK_BEHAVIOR_ON_LEAVE
                                && mUnlockedApps.contains(sessionKey(userId, packageName))) {
                            rti.isTopAppLocked = false;
                        } else {
                            rti.isTopAppLocked = true;
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return rti.isTopAppLocked;
    }

    @Override
    public void getRecentTasksCheck(int callingUid, int userId) {
        mCheckRecentTasks = callingUid != 1000 && mAtms.mWindowManager.isKeyguardSecure(userId);
    }

    @Override
    public void setKeyguardDoneLocked(boolean done) {
        try {
            if (done) {
                mKeyguardDone = true;
                mAtms.mWindowManager.getDefaultDisplayContentLocked()
                        .getDefaultTaskDisplayArea().forAllTasks(this::addVisibleTaskToUnlocked);
            } else {
                mKeyguardDone = false;

                if (mLockBehavior == LOCK_BEHAVIOR_TIMEOUT && mLastFocusedAppKey != null) {
                    scheduleTimeoutLock(mLastFocusedAppKey);
                }

                if (mLockBehavior == LOCK_BEHAVIOR_ON_SCREEN_OFF) {
                    lockAllSessionsAndNotify();
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "setKeyguardDoneLocked: failed", e);
            mKeyguardDone = done;
        }
    }

    @Override
    public void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
        if (!hasLockedPackages()) {
            mLastFocusedAppKey = null;
            return;
        }

        String newKey = (newFocus != null) ? sessionKey(newFocus) : null;

        if (mLastFocusedAppKey != null && !mLastFocusedAppKey.equals(newKey)) {
            scheduleTimeoutLock(mLastFocusedAppKey);
            if (mLockBehavior == LOCK_BEHAVIOR_ON_LEAVE
                    && mUnlockedApps.contains(mLastFocusedAppKey)) {
                int colon = mLastFocusedAppKey.indexOf(':');
                if (colon > 0 && colon < mLastFocusedAppKey.length() - 1) {
                    try {
                        int oldUserId = Integer.parseInt(
                                mLastFocusedAppKey.substring(0, colon));
                        String oldPkg = mLastFocusedAppKey.substring(colon + 1);
                        markSessionLocked(oldPkg, oldUserId);
                    } catch (NumberFormatException ignored) { }
                }
            }
        }

        if (newKey != null) {
            cancelTimeoutLock(newKey);
            mUnlockTimestamps.put(newKey, SystemClock.elapsedRealtime());
        }

        mLastFocusedAppKey = newKey;
        lockTopApp(newTask, "onAppFocusChanged");
    }

    @Override
    public void onWindowingModeChanged(Task task, int prevWindowingMode) {
        if (task == null || mLockBehavior != LOCK_BEHAVIOR_ON_LEAVE) {
            return;
        }

        int currMode = task.getWindowingMode();

        if (!WindowConfiguration.isFloating(prevWindowingMode)
                && WindowConfiguration.isFloating(currMode) && task.isVisible()) {
            ActivityRecord r = task.topRunningActivityLocked();
            if (isAppLocked(r)) {
                markSessionUnlocked(r.packageName, r.mUserId);
            }
            return;
        }

        if (mUnlockedApps.size() > 0) {
            if ((!WindowConfiguration.inMultiWindowMode(prevWindowingMode)
                    && !WindowConfiguration.isFloating(prevWindowingMode))
                    || WindowConfiguration.inMultiWindowMode(currMode)
                    || WindowConfiguration.isFloating(currMode)) {
                return;
            }

            ActivityRecord r = task.topRunningActivityLocked();
            if (task.isVisible()) {
                if (currMode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                    if (r != null) {
                        clearUnlockedApp(r);
                    } else {
                        clearUnlockedApp();
                    }
                }
            } else {
                if (r != null) {
                    markSessionLocked(r.packageName, r.mUserId);
                }
                ActivityRecord lastPaused = task.mLastPausedActivity;
                if (lastPaused != null) {
                    markSessionLocked(lastPaused.packageName, lastPaused.mUserId);
                }
                ComponentName realActivity = task.realActivity;
                if (realActivity != null) {
                    markSessionLocked(realActivity.getPackageName(), task.effectiveUid);
                }
            }
        }
    }

    @Override
    public void clearUnlockedApp() {
        boolean tracksUnlockSession = mLockBehavior == LOCK_BEHAVIOR_ON_LEAVE;
        if (!tracksUnlockSession || mUnlockedApps.size() <= 0) {
            return;
        }
        int size = mUnlockedApps.size();
        lockAllSessionsAndNotify();
        lockVisibleMultiWindowApps(mAtms.mWindowManager.getDefaultDisplayContentLocked());
        Slog.d(TAG, "clearUnlockedApp: size=" + size);
    }

    @Override
    public void clearUnlockedApp(ActivityRecord r) {
        if (r == null || mLockBehavior != LOCK_BEHAVIOR_ON_LEAVE) {
            return;
        }

        if (r.occludesParent() || r.isActivityTypeHomeOrRecents()) {
            if (r.isActivityTypeHomeOrRecents() && r.mTransitionController.isTransientLaunch(r)) {
                return;
            }
            boolean wasUnlocked = mUnlockedApps.contains(sessionKey(r));
            clearUnlockedApp();
            if (wasUnlocked) {
                markSessionUnlocked(r.packageName, r.mUserId);
            } else {
                markSessionLocked(r.packageName, r.mUserId);
            }
            if (WindowConfiguration.isFloating(r.getWindowingMode())) {
                lockVisibleFullscreenApps(mAtms.mWindowManager.getDefaultDisplayContentLocked());
            }
        }
    }

    @Override
    public void removeTask(Task task, String reason) {
        if (task == null) {
            return;
        }

        boolean lockUnlocked = mLockBehavior == LOCK_BEHAVIOR_ON_LEAVE;
        updateTaskSession(task.topRunningActivityLocked(), lockUnlocked);
        updateTaskSession(task.mLastPausedActivity, lockUnlocked);
        updateTaskComponent(task.realActivity, task.effectiveUid, lockUnlocked);
        updateTaskComponent(task.origActivity, task.effectiveUid, lockUnlocked);
    }

    @Override
    public void onAppDied(String packageName, int userId) {
        if (SANDBOX_PACKAGE.equals(packageName)) {
            synchronized (mPendingUnlocks) {
                if (!mPendingUnlocks.isEmpty()) {
                    Slog.d(TAG, "onAppDied: clearing " + mPendingUnlocks.size() + " pending unlocks");
                    mPendingUnlocks.clear();
                }
            }
            return;
        }
        updateSession(packageName, userId, mLockBehavior == LOCK_BEHAVIOR_ON_KILL);
    }

    private void updateTaskSession(ActivityRecord r, boolean lockUnlocked) {
        if (r != null) {
            updateSession(r.packageName, r.mUserId, lockUnlocked);
        }
    }

    private void updateTaskComponent(ComponentName component, int uid, boolean lockUnlocked) {
        if (component != null && uid >= 0) {
            updateSession(component.getPackageName(), UserHandle.getUserId(uid), lockUnlocked);
        }
    }

    private void lockSession(String packageName, int userId) {
        updateSession(packageName, userId, true);
    }

    private void updateSession(String packageName, int userId, boolean lockUnlocked) {
        if (TextUtils.isEmpty(packageName)) return;
        boolean pending = clearPendingUnlock(packageName, userId);
        if (lockUnlocked && markSessionLocked(packageName, userId)) {
            return;
        }
        if (pending) {
            notifyAppLocked(packageName, userId);
        }
    }

    private boolean clearPendingUnlock(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return false;
        synchronized (mPendingUnlocks) {
            if (mPendingUnlocks.remove(sessionKey(userId, packageName))) {
                Slog.d(TAG, "clearPendingUnlock: " + packageName + " userId=" + userId);
                return true;
            }
        }
        return false;
    }

    private boolean hasAppLockerActivity(Task task) {
        return task != null && task.getActivity(r ->
                !r.finishing
                        && isAppLockerActivity(r.intent != null
                                ? r.intent.getComponent() : null)) != null;
    }

    private static String sessionKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }

    private static String sessionKey(ActivityRecord r) {
        return sessionKey(r.mUserId, r.packageName);
    }

    private boolean hasLockedPackages() {
        return mAppControlController != null && mAppControlController.hasLockedPackages();
    }

    private void markSessionUnlocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (mUnlockedApps.add(key)) {
            mUnlockTimestamps.put(key, SystemClock.elapsedRealtime());
            Slog.d(TAG, "markSessionUnlocked: " + packageName + " userId=" + userId);
            notifyAppUnlocked(packageName, userId);
        }
    }

    private void notifyAppUnlocked(String packageName, int userId) {
        final int count = mAppSessionListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mAppSessionListeners.getBroadcastItem(i).onAppUnlocked(packageName, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify app session listener", e);
            }
        }
        mAppSessionListeners.finishBroadcast();
    }

    private boolean markSessionLocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (mUnlockedApps.remove(key)) {
            mUnlockTimestamps.remove(key);
            cancelTimeoutLock(key);
            Slog.d(TAG, "markSessionLocked: " + packageName + " userId=" + userId);
            notifyAppLocked(packageName, userId);
            return true;
        }
        return false;
    }

    private void notifyAppLocked(String packageName, int userId) {
        final int count = mAppSessionListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mAppSessionListeners.getBroadcastItem(i).onAppLocked(packageName, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify app session listener (locked)", e);
            }
        }
        mAppSessionListeners.finishBroadcast();
    }

    private void lockAllSessionsAndNotify() {
        if (mUnlockedApps.isEmpty()) return;
        String[] keys = mUnlockedApps.toArray(new String[0]);
        mUnlockedApps.clear();
        mUnlockTimestamps.clear();
        clearAllTimeouts();
        for (String key : keys) {
            int colon = key.indexOf(':');
            if (colon <= 0 || colon >= key.length() - 1) continue;
            try {
                int userId = Integer.parseInt(key.substring(0, colon));
                String pkg = key.substring(colon + 1);
                notifyAppLocked(pkg, userId);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void scheduleTimeoutLock(String key) {
        if (mLockBehavior != LOCK_BEHAVIOR_TIMEOUT || !mUnlockedApps.contains(key)) {
            return;
        }

        cancelTimeoutLock(key);

        Runnable r = () -> {
            synchronized (mAtms.mGlobalLock) {
                ActivityRecord top = mAtms.mRootWindowContainer.getTopResumedActivity();
                String topKey = (top != null) ? sessionKey(top) : null;

                if (!key.equals(topKey)) {
                    int index = key.indexOf(":");
                    if (index != -1) {
                        try {
                            int userId = Integer.parseInt(key.substring(0, index));
                            String packageName = key.substring(index + 1);
                            markSessionLocked(packageName, userId);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                mTimeoutRunnables.remove(key);
            }
        };

        mTimeoutRunnables.put(key, r);
        mAtms.mH.postDelayed(r, mLockTimeout * 1000L);
    }

    private void cancelTimeoutLock(String key) {
        Runnable r = mTimeoutRunnables.remove(key);
        if (r != null) {
            mAtms.mH.removeCallbacks(r);
        }
    }

    private void clearAllTimeouts() {
        for (Runnable r : mTimeoutRunnables.values()) {
            mAtms.mH.removeCallbacks(r);
        }
        mTimeoutRunnables.clear();
    }

    private void broadcastPackageChanged(String packageName) {
        final long token = Binder.clearCallingIdentity();
        try {
            int uid = getPackageUid(packageName);
            int userId = uid >= 0 ? UserHandle.getUserId(uid) : mCurrentUserId;

            Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
            intent.setData(Uri.fromParts("package", packageName, null));
            intent.putExtra(Intent.EXTRA_UID, uid);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            String[] components = { packageName };
            intent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, components);

            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            Slog.d(TAG, "broadcastPackageChanged: " + packageName);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to broadcast package change for " + packageName, e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void notifyAppLockStateChanged(String packageName, boolean locked) {
        final int count = mAppLockStateListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mAppLockStateListeners.getBroadcastItem(i).onAppLockStateChanged(packageName, locked);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify app lock state listener", e);
            }
        }
        mAppLockStateListeners.finishBroadcast();
    }

    private void lockVisibleTask(Task task) {
        if (task.isLeafTask() && task.shouldBeVisible(null) && isAppLocked(task.topRunningActivityLocked())) {
            lockTopApp(task, "setKeyguardDone");
        }
    }

    private void addVisibleTaskToUnlocked(Task task) {
        if (task.isLeafTask() && task.isVisible()) {
            ActivityRecord r = task.topRunningActivityLocked();
            if (isAppLocked(r)) {
                lockVisibleTask(task);
                Slog.d(TAG, "addVisibleTaskToUnlocked: should be locked pkg: " + r.packageName + " userId=" + r.mUserId);
            }
        }
    }

    private void lockVisibleMultiWindowApps(DisplayContent dc) {
        if (dc == null) {
            dc = mAtms.mWindowManager.getDefaultDisplayContentLocked();
        }
        dc.getDefaultTaskDisplayArea().forAllTasks(task -> {
            if (task.isLeafTask()
                    && (WindowConfiguration.inMultiWindowMode(task.getWindowingMode())
                            || WindowConfiguration.isFloating(task.getWindowingMode()))
                    && task.isVisible()) {
                ActivityRecord r = task.topRunningActivityLocked();
                if (isAppLocked(r)) {
                    markSessionUnlocked(r.packageName, r.mUserId);
                }
            }
        });
    }

    private void lockVisibleFullscreenApps(DisplayContent dc) {
        if (dc == null) {
            dc = mAtms.mWindowManager.getDefaultDisplayContentLocked();
        }
        dc.getDefaultTaskDisplayArea().forAllTasks(task -> {
            if (task.isLeafTask() && task.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                    && task.isVisible()) {
                ActivityRecord r = task.topRunningActivityLocked();
                if (isAppLocked(r)) {
                    markSessionUnlocked(r.packageName, r.mUserId);
                }
            }
        });
    }

    private void abortAnimation(ActivityRecord r) {
        if (r == null) return;
        try {
            if (r.getOptions() != null && r.getOptions().getRemoteAnimationAdapter() != null) {
                r.getOptions().getRemoteAnimationAdapter().getRunner().onAnimationCancelled();
            }
        } catch (Exception e) {
            Slog.w(TAG, "abortAnimation failed for " + r, e);
        }
        r.abortAndClearOptionsAnimation();
    }

    private int startActivityAsCaller(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho,
            int requestCode) {
        return mAtms.getActivityStartController()
                .obtainStarter(intent, "startActivityAsCaller")
                .setCaller(caller)
                .setCallingPackage(callingPackage)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .execute();
    }

    private int getPackageUid(String packageName) {
        try {
            return mContext.getPackageManager()
                    .getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    public boolean isAppLockerActivity(ComponentName componentName) {
        return componentName != null &&
               SANDBOX_PACKAGE.equals(componentName.getPackageName()) &&
               SANDBOX_ACTIVITY.equals(componentName.getClassName());
    }
}
