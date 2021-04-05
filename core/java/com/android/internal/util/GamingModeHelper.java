/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 *               2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;

public final class GamingModeHelper {

    private static final String TAG = "GamingModeHelper";
    private static final boolean DEBUG = false;

    private static final Intent sGamingModeOn = new Intent("exthmui.intent.action.GAMING_MODE_ON");
    static {
        sGamingModeOn.addFlags(Intent.FLAG_RECEIVER_FOREGROUND |
            Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
    }
    private static final Intent sGamingModeOff = new Intent("exthmui.intent.action.GAMING_MODE_OFF");
    static {
        sGamingModeOff.addFlags(Intent.FLAG_RECEIVER_FOREGROUND |
            Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
    }

    /** Must not clash with constants in ActivityManagerService */
    public static final int MSG_SEND_GAMING_MODE_BROADCAST = 1001;

    private final Context mContext;
    private final PackageManager mPackageManager;

    private final ArrayList<String> mGamingPackages = new ArrayList<>();

    private boolean mGamingModeEnabled;
    private boolean mIsGaming;
    private boolean mDynamicAddGame;

    @Nullable
    private String mCurrentGamePackage;

    @Nullable
    private Handler mHandler;

    private boolean mDisableADB = false;
    private boolean mADBStateChanged = false;

    public GamingModeHelper(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mPackageManager = context.getPackageManager();

        final SettingsObserver observer = new SettingsObserver(new Handler(Looper.getMainLooper()));
        observer.update();

        parseGameList();

        observer.init();
    }

    public void onPackageUninstalled(@NonNull String packageName) {
        if (mGamingPackages.remove(packageName)) {
            saveGamesList();
        }
        if (mIsGaming && packageName.equals(mCurrentGamePackage)) {
            stopGamingMode();
        }
    }

    public void onTopAppChanged(@NonNull String packageName, boolean focused) {
        if (DEBUG) Slog.d(TAG, "onTopAppChanged: " + packageName + ", focused = " + focused);

        // Gaming mode turned off, disabled if it's already enabled
        if (!mGamingModeEnabled) {
            if (mIsGaming) stopGamingMode();
            return;
        }

        if (packageName.equals(mCurrentGamePackage)) {
            // Ignore top app changed events for the same app,
            // but stop gaming mode if app isn't in focus.
            if (!focused) stopGamingMode();
            return;
        }
        
        if (mGamingPackages.contains(packageName)) {
            // If app is in list and is focused, start gaming mode and return
            if (focused) {
                startGamingMode(packageName);
                return;
            }
        } else if (mDynamicAddGame) {
            // Add app to list if dynamic add is enabled and app is indeed a game
            // Start gaming mode and return if so.
            final ApplicationInfo appInfo = getAppInfo(packageName);
            if (appInfo != null && (appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                    (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) == ApplicationInfo.FLAG_IS_GAME)) {
                addGameToList(packageName);
                if (focused) startGamingMode(packageName);
                return;
            }
        }

        if (mIsGaming) stopGamingMode();
    }

    private void startGamingMode(String packageName) {
        if (DEBUG) Slog.d(TAG, "startGamingMode called!");
        mCurrentGamePackage = packageName;
        if (mDisableADB) {
            final boolean isADBEnabled = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
            if (isADBEnabled) {
                mADBStateChanged = Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0);
            }
        }
        sendBroadcast(sGamingModeOn);
    }

    private void stopGamingMode() {
        if (DEBUG) Slog.d(TAG, "stopGamingMode called!");
        mCurrentGamePackage = null;
        if (mDisableADB && mADBStateChanged) {
            Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            mADBStateChanged = false;
        }
        sendBroadcast(sGamingModeOff);
    }

    private void sendBroadcast(Intent intent) {
        if (mHandler == null) {
            Slog.w(TAG, "AMS handler is null, unable to send broadcast");
            return;
        }
        final Message message = new Message();
        message.what = MSG_SEND_GAMING_MODE_BROADCAST;
        message.obj = new Intent(intent);
        mHandler.sendMessage(message);
    }

    private void parseGameList() {
        if (DEBUG) Slog.d(TAG, "parseGameList called!");
        mGamingPackages.clear();
        final String gameListData = Settings.System.getString(
            mContext.getContentResolver(), Settings.System.GAMING_MODE_APP_LIST);
        if (gameListData != null && !gameListData.isEmpty()) {
            mGamingPackages.addAll(Arrays.asList(gameListData.split(";")));
        }
    }

    private ApplicationInfo getAppInfo(String packageName) {
        try {
            return mPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package " + packageName + " doesn't exist");
            return null;
        }
    }

    private void addGameToList(String packageName) {
        if (!mGamingPackages.contains(packageName)) {
            mGamingPackages.add(packageName);
            saveGamesList();
        }
    }

    private void saveGamesList() {
        Settings.System.putString(mContext.getContentResolver(),
            Settings.System.GAMING_MODE_APP_LIST,
            mGamingPackages.isEmpty() ? null : String.join(";", mGamingPackages));
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void init() {
            final ContentResolver resolver = mContext.getContentResolver();
            register(resolver, Settings.System.GAMING_MODE_ENABLED);
            register(resolver, Settings.System.GAMING_MODE_ACTIVE);
            register(resolver, Settings.System.GAMING_MODE_APP_LIST);
            register(resolver, Settings.System.GAMING_MODE_DYNAMIC_ADD);
            register(resolver, Settings.System.GAMING_MODE_DISABLE_ADB);
        }

        void update() {
            final ContentResolver resolver = mContext.getContentResolver();
            mGamingModeEnabled = Settings.System.getIntForUser(resolver, Settings.System.GAMING_MODE_ENABLED,
                0, UserHandle.USER_CURRENT) == 1;
            mDynamicAddGame = Settings.System.getIntForUser(resolver, Settings.System.GAMING_MODE_DYNAMIC_ADD,
                0, UserHandle.USER_CURRENT) == 1;
            mIsGaming = false;
            Settings.System.putIntForUser(resolver, Settings.System.GAMING_MODE_ACTIVE,
                0, UserHandle.USER_CURRENT);
            mDisableADB = Settings.System.getIntForUser(resolver, Settings.System.GAMING_MODE_DISABLE_ADB,
                0, UserHandle.USER_CURRENT) == 1;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            switch (uri.getLastPathSegment()) {
                case Settings.System.GAMING_MODE_ENABLED:
                    mGamingModeEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
                    if (!mGamingModeEnabled && mIsGaming) {
                        stopGamingMode();
                    }
                    break;
                case Settings.System.GAMING_MODE_APP_LIST:
                    parseGameList();
                    break;
                case Settings.System.GAMING_MODE_DYNAMIC_ADD:
                    mDynamicAddGame = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_DYNAMIC_ADD, 0, UserHandle.USER_CURRENT) == 1;
                    break;
                case Settings.System.GAMING_MODE_ACTIVE:
                    mIsGaming = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;
                    break;
                case Settings.System.GAMING_MODE_DISABLE_ADB:
                    mDisableADB = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_DISABLE_ADB, 0, UserHandle.USER_CURRENT) == 1;
                    break;
            }
        }

        private void register(ContentResolver resolver, String key) {
            resolver.registerContentObserver(Settings.System.getUriFor(key),
                false, this, UserHandle.USER_ALL);
        }
    }
}
