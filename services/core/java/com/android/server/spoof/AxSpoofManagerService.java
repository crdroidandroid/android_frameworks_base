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
package com.android.server.spoof;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.trickystore.TrickyStoreService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AxSpoofManagerService extends SystemService {
    private static final String TAG = "AxSpoofManager";

    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String DROIDGUARD_PROCESS = "com.google.android.gms.unstable";
    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_PIF_PHOTOS,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    private final ContentResolver mResolver;
    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final AxSpoofManagerInternal mLocalService = new LocalService();

    private ContentObserver mObserver;
    private volatile boolean mReady;
    private volatile int mUserId = UserHandle.USER_SYSTEM;

    public AxSpoofManagerService(Context context) {
        super(context);
        mResolver = context.getContentResolver();
    }

    @Override
    public void onStart() {
        publishLocalService(AxSpoofManagerInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            systemReady();
        }
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        final int userId = to.getUserIdentifier();
        BackgroundThread.getHandler().post(() -> {
            mUserId = userId;
            refreshAll();
            maybeSeedDefaultTargets(false);
            Slog.i(TAG, "Spoof config reloaded for user " + userId);
            restartPifTargets();
            restartPhotos();
        });
    }

    private void systemReady() {
        if (mReady) {
            return;
        }
        mUserId = ActivityManager.getCurrentUser();
        refreshAll();
        registerObserver();
        mReady = true;
        maybeSeedDefaultTargets(false);
        Slog.i(TAG, "AxSpoofManager ready");
    }

    private void registerObserver() {
        if (mObserver != null) {
            return;
        }
        mObserver = new ContentObserver(BackgroundThread.getHandler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (uri == null) {
                    return;
                }
                if (userId != UserHandle.USER_ALL && userId != mUserId) {
                    return;
                }
                String key = uri.getLastPathSegment();
                if (key == null) {
                    return;
                }
                refreshKey(key);
                Slog.i(TAG, "Spoof config refreshed: " + key);
                if (Settings.Secure.SPOOF_PIF_CONFIG.equals(key)) {
                    restartPifTargets();
                } else if (Settings.Secure.SPOOF_PIF_PHOTOS.equals(key)) {
                    restartPhotos();
                } else if (Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX.equals(key)) {
                    maybeSeedDefaultTargets(true);
                }
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
    }

    private void refreshAll() {
        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }
    }

    private void refreshKey(String key) {
        String value = readSetting(key);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
    }

    private String getCached(String key) {
        return mReady ? mCache.get(key) : readSetting(key);
    }

    private String readSetting(String key) {
        final int userId = mReady ? mUserId : UserHandle.USER_CURRENT;
        return Settings.Secure.getStringForUser(mResolver, key, userId);
    }

    /**
     * Seeds {@link TrickyStoreService#DEFAULT_TARGET_LIST} into the target setting
     * the first time a keybox is present and the target setting has never been
     * written. This is what makes a freshly loaded keybox attest GMS and the usual
     * targets without the user opening the TrickyStore screen and picking defaults.
     */
    private void maybeSeedDefaultTargets(boolean restartGms) {
        try {
            String keybox = readSetting(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
            if (TextUtils.isEmpty(keybox)) {
                return;
            }
            // Deliberately a null check, not isEmpty(): "" is a user who cleared
            // the list and must be respected; null is "never configured".
            String target = readSetting(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
            if (target != null) {
                return;
            }

            Settings.Secure.putStringForUser(
                    mResolver,
                    Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                    TrickyStoreService.DEFAULT_TARGET_LIST,
                    mUserId);
            refreshKey(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
            Slog.i(TAG, "Seeded default TrickyStore targets after keybox load (user "
                    + mUserId + ")");

            if (restartGms) {
                restartPifTargets();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to seed default TrickyStore targets", t);
        }
    }

    /** Restarts DroidGuard and Play Store so they pick up a new PIF config. */
    private void restartPifTargets() {
        try {
            ActivityManager am = getContext().getSystemService(ActivityManager.class);
            if (am == null) {
                return;
            }
            am.forceStopPackageAsUser(VENDING_PACKAGE, mUserId);
            killProcesses(am, DROIDGUARD_PROCESS, false);
            killProcesses(am, VENDING_PACKAGE, false);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to restart PIF target processes", t);
        }
    }

    /** Restarts Google Photos so it picks up a new Photos spoofing state. */
    private void restartPhotos() {
        try {
            ActivityManager am = getContext().getSystemService(ActivityManager.class);
            if (am == null) {
                return;
            }
            killProcesses(am, PHOTOS_PACKAGE, true);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to restart Photos", t);
        }
    }

    /**
     * Kills processes of the current user whose name equals {@code name} or, when
     * {@code includeSubProcesses} is set, starts with {@code name + ":"}.
     */
    private void killProcesses(ActivityManager am, String name, boolean includeSubProcesses) {
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) {
            return;
        }
        for (ActivityManager.RunningAppProcessInfo proc : procs) {
            if (proc.processName == null) {
                continue;
            }
            boolean matches = name.equals(proc.processName)
                    || (includeSubProcesses && proc.processName.startsWith(name + ":"));
            if (!matches || UserHandle.getUserId(proc.uid) != mUserId) {
                continue;
            }
            Slog.i(TAG, "Killing spoof target process " + proc.processName
                    + " (pid=" + proc.pid + ")");
            Process.killProcess(proc.pid);
        }
    }

    private final class LocalService implements AxSpoofManagerInternal {
        @Override
        public String getPifConfig() {
            return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        }

        @Override
        public String getPifSpoofPhotos() {
            return getCached(Settings.Secure.SPOOF_PIF_PHOTOS);
        }

        @Override
        public String getGamePropsConfig() {
            return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
        }

        @Override
        public String getTrickyStoreTarget() {
            return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        }

        @Override
        public String getTrickyStoreKeyBox() {
            return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
        }

        @Override
        public String getTrickyStorePatch() {
            return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
        }
    }
}
