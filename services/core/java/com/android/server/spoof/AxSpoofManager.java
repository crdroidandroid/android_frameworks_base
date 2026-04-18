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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.NtServiceInjector;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AxSpoofManager implements IAxSpoofManager {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private Context mContext;
    private ContentResolver mResolver;
    private ContentObserver mObserver;
    private volatile boolean mReady = false;

    public AxSpoofManager() {
        mHandlerThread = new HandlerThread("AxSpoofManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        if (mContext == null) {
            Log.w(TAG, "Context unavailable, deferring init");
            return;
        }
        mResolver = mContext.getContentResolver();

        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                final String last = uri.getLastPathSegment();
                if (last == null) return;
                refreshKey(last);
                Log.i(TAG, "Spoof config refreshed: " + last);
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }

        mReady = true;
        Log.i(TAG, "AxSpoofManager ready");
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(
                mResolver, key, UserHandle.USER_SYSTEM);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
    }

    private String getCached(String key) {
        return mCache.get(key);
    }

    @Override
    public String getPifConfig() {
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
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
