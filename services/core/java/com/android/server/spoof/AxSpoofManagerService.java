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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AxSpoofManagerService extends SystemService {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_PIF_PHOTOS,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
            Settings.Secure.SPOOF_TRICKYSTORE_ENABLED,
    };

    private final ContentResolver mResolver;
    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final AxSpoofManagerInternal mLocalService = new LocalService();
    private final TrickyStoreUpdater mTrickyStoreUpdater;

    private ContentObserver mObserver;
    private volatile boolean mReady;

    public AxSpoofManagerService(Context context) {
        super(context);
        mResolver = context.getContentResolver();
        mTrickyStoreUpdater = new TrickyStoreUpdater(context);
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

    private void systemReady() {
        if (mReady) {
            return;
        }
        refreshAll();
        registerObserver();
        mReady = true;
        mTrickyStoreUpdater.start();
        Slog.i(TAG, "AxSpoofManager ready");
    }

    private void registerObserver() {
        if (mObserver != null) {
            return;
        }
        mObserver = new ContentObserver(BackgroundThread.getHandler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) {
                    return;
                }
                String key = uri.getLastPathSegment();
                if (key == null) {
                    return;
                }
                refreshKey(key);
                Slog.i(TAG, "Spoof config refreshed: " + key);
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
        return Settings.Secure.getStringForUser(mResolver, key, UserHandle.USER_CURRENT);
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

        @Override
        public void refreshTrickyStoreKeyBox() {
            mTrickyStoreUpdater.requestKeyBoxFetch();
        }

        @Override
        public void refreshTrickyStoreStatus() {
            mTrickyStoreUpdater.requestStatusCheck();
        }
    }
}
