/*
 * Copyright (C) 2025-2026 AxionOS Project
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
import android.widget.Toast;

import com.android.server.UiThread;

class GamePackageHandler {

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final GameListManager mGameListManager;
    private final Handler mBgHandler;

    GamePackageHandler(Context context, GameListManager manager, Handler bgHandler) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mGameListManager = manager;
        mBgHandler = bgHandler;
    }

    void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String pkg = intent.getData() != null
                        ? intent.getData().getSchemeSpecificPart() : null;
                if (pkg == null) return;

                mBgHandler.post(() -> {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                        if (isGame(pkg)) {
                            String label = getAppLabel(pkg);
                            mGameListManager.addGame(pkg);
                            UiThread.getHandler().post(
                                    () -> showGameAddedToast(label));
                        }
                    } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                        mGameListManager.removeGame(pkg);
                    }
                });
            }
        }, filter);
    }

    private boolean isGame(String pkg) {
        try {
            ApplicationInfo info = mPackageManager.getApplicationInfo(
                    pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
            return info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getAppLabel(String pkg) {
        try {
            return mPackageManager
                    .getApplicationLabel(mPackageManager.getApplicationInfo(
                            pkg, PackageManager.ApplicationInfoFlags.of(0)))
                    .toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void showGameAddedToast(String appLabel) {
        Toast.makeText(mContext, "Added " + appLabel + " to GameSpace", Toast.LENGTH_LONG).show();
    }
}
