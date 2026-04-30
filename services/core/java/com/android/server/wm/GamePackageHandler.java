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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

class GamePackageHandler {
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final GameListManager mGameListManager;
    private final Handler mHandler;

    GamePackageHandler(Context context, GameListManager manager, Handler handler) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mGameListManager = manager;
        mHandler = handler;
    }

    private boolean isAutoDetectEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                "gamespace_auto_game_detect", 1,
                UserHandle.USER_CURRENT) != 0;
    }

    void registerPackageReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new PackageReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void handlePackageChanged(String action, String packageName) {
        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (!isAutoDetectEnabled()) return;
            if (mGameListManager.isDenied(packageName)) return;
            if (mGameListManager.isGame(packageName)) return;
            if (isGame(packageName)) {
                mGameListManager.addGame(packageName);
            }
        } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
            mGameListManager.removeGame(packageName);
        }
    }

    private boolean isGame(String packageName) {
        try {
            final ApplicationInfo info = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
            return info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }
            final String packageName = intent.getData().getSchemeSpecificPart();
            final String action = intent.getAction();
            if (packageName == null || action == null) {
                return;
            }
            mHandler.post(() -> handlePackageChanged(action, packageName));
        }
    }
}
