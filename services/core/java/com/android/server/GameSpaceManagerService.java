/*
 * Copyright (C) 2025 the AxionAOSP Project
 *           (C) 2025 crDroid Android Project
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
package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Toast;

import com.android.internal.R;
import com.android.server.SystemService;
import com.android.server.UiThread;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GameSpaceManagerService extends SystemService {

    private static final String TAG = "GameSpaceManagerService";
    private static final String GAME_LIST_SETTING = Settings.System.GAMESPACE_GAME_LIST;
    private static final Set<String> VALID_MODES = Set.of("1", "2", "3");

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Handler mBackgroundHandler;
    private final BroadcastReceiver mPackageChangeReceiver = new PackageChangeReceiver();
    private final ContentObserver mGameListObserver;

    public GameSpaceManagerService(Context context) {
        super(context);
        mContext = context;
        mPackageManager = context.getPackageManager();

        HandlerThread handlerThread = new HandlerThread("GameSpaceManagerThread");
        handlerThread.start();
        mBackgroundHandler = new Handler(handlerThread.getLooper());

        mGameListObserver = new GameListObserver(mBackgroundHandler);
    }

    @Override
    public void onStart() {
        // No-op
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiver(mPackageChangeReceiver, filter);

            mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(GAME_LIST_SETTING), false,
                mGameListObserver, UserHandle.USER_ALL);

            sanitizeGameList();
        }
    }

    private class PackageChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData().getSchemeSpecificPart();
            if (packageName == null) return;

            mBackgroundHandler.post(() -> {
                if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    handlePackageAdded(packageName);
                } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                    handlePackageRemoved(packageName);
                }
            });
        }
    }

    private void handlePackageAdded(String packageName) {
        if (isGame(packageName)) {
            addToGameSpace(packageName);
        }
    }

    private void handlePackageRemoved(String packageName) {
        removeFromGameSpace(packageName);
    }

    private boolean isGame(String packageName) {
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return appInfo != null && appInfo.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void addToGameSpace(String packageName) {
        ContentResolver cr = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(cr, GAME_LIST_SETTING, UserHandle.USER_CURRENT);
        Map<String, String> gameMap = new HashMap<>();

        if (currentList != null && !currentList.isEmpty()) {
            String[] entries = currentList.split(";");
            for (String entry : entries) {
                String[] parts = entry.split("=");
                if (parts.length == 2 && isValidMode(parts[1])) {
                    gameMap.put(parts[0], parts[1]);
                }
            }
        }

        if (!gameMap.containsKey(packageName)) {
            gameMap.put(packageName, "2");
            String updatedList = serializeGameMap(gameMap);
            Settings.System.putStringForUser(cr, GAME_LIST_SETTING, updatedList, UserHandle.USER_CURRENT);
            sendGameAddedNotification(packageName);
        }
    }

    private void removeFromGameSpace(String packageName) {
        ContentResolver cr = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(cr, GAME_LIST_SETTING, UserHandle.USER_CURRENT);
        if (currentList == null || currentList.isEmpty()) return;

        Map<String, String> gameMap = new HashMap<>();
        String[] entries = currentList.split(";");
        for (String entry : entries) {
            String[] parts = entry.split("=");
            if (parts.length == 2 && isValidMode(parts[1]) && !parts[0].equals(packageName)) {
                gameMap.put(parts[0], parts[1]);
            }
        }

        String updatedList = serializeGameMap(gameMap);
        Settings.System.putStringForUser(cr, GAME_LIST_SETTING, updatedList, UserHandle.USER_CURRENT);
    }

    private boolean isValidMode(String modeStr) {
        return VALID_MODES.contains(modeStr);
    }

    private void sanitizeGameList() {
        ContentResolver cr = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(cr, GAME_LIST_SETTING, UserHandle.USER_CURRENT);
        if (currentList == null || currentList.isEmpty()) return;

        Map<String, String> gameMap = new HashMap<>();
        String[] entries = currentList.split(";");
        for (String entry : entries) {
            int firstEquals = entry.indexOf('=');
            if (firstEquals > 0 && firstEquals < entry.length() - 1) {
                String key = entry.substring(0, firstEquals).trim();
                String value = entry.substring(firstEquals + 1).split("[^0-9]", 2)[0].trim();
                if (isValidMode(value)) {
                    gameMap.put(key, value);
                }
            }
        }

        String sanitizedList = serializeGameMap(gameMap);
        Settings.System.putStringForUser(cr, GAME_LIST_SETTING, sanitizedList, UserHandle.USER_CURRENT);
    }

    private String serializeGameMap(Map<String, String> gameMap) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, String> entry : gameMap.entrySet()) {
            result.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(";", result);
    }

    private class GameListObserver extends ContentObserver {
        public GameListObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            sanitizeGameList();
        }
    }

    private void sendGameAddedNotification(String packageName) {
        String appName;
        try {
            appName = mPackageManager.getApplicationLabel(
                mPackageManager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            appName = packageName;
        }
        final String finalAppName = appName;

        UiThread.getHandler().post(
            () -> Toast.makeText(
                mContext,
                mContext.getString(R.string.gamespace_new_game_added, finalAppName),
                Toast.LENGTH_LONG
            ).show()
        );
    }
}
