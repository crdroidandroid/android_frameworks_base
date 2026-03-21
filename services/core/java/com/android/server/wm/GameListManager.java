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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class GameListManager {

    interface GameListChangeListener {
        void onGameListChanged();
    }

    private static final String GAME_LIST_KEY = "gamespace_game_list";

    private final Context mContext;
    private final Map<String, String> mGameList = Collections.synchronizedMap(new HashMap<>());
    private final List<GameListChangeListener> mListeners = new ArrayList<>();

    GameListManager(Context context) {
        mContext = context;
        loadGameList();
    }

    void loadGameList() {
        String raw = Settings.System.getStringForUser(mContext.getContentResolver(),
                GAME_LIST_KEY, UserHandle.USER_CURRENT);
        Map<String, String> parsed = parseGameList(raw);
        synchronized (mGameList) {
            mGameList.clear();
            mGameList.putAll(parsed);
        }
        notifyListeners();
    }

    boolean isGame(String packageName) {
        synchronized (mGameList) {
            return mGameList.containsKey(packageName);
        }
    }

    boolean isGameInPerfMode(String packageName) {
        synchronized (mGameList) {
            return "2".equals(mGameList.get(packageName));
        }
    }

    void registerGameListObserver(Handler handler) {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(GAME_LIST_KEY),
                false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        loadGameList();
                    }
                },
                UserHandle.USER_ALL
        );
    }

    private Map<String, String> parseGameList(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;

        for (String entry : raw.split(";")) {
            String[] parts = entry.split("=");
            if (parts.length == 2
                    && parts[0].matches("[a-zA-Z0-9_.]+")
                    && parts[1].matches("\\d+")) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }

    void addGame(String packageName) {
        updateGameList(packageName, true);
    }

    void removeGame(String packageName) {
        updateGameList(packageName, false);
    }

    private void updateGameList(String packageName, boolean add) {
        ContentResolver cr = mContext.getContentResolver();
        String raw = Settings.System.getStringForUser(cr, GAME_LIST_KEY, UserHandle.USER_CURRENT);
        Map<String, String> gameMap = parseGameList(raw);

        boolean modified;
        if (add) {
            modified = !"2".equals(gameMap.get(packageName));
            if (modified) gameMap.put(packageName, "2");
        } else {
            modified = gameMap.remove(packageName) != null;
        }

        if (modified) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : gameMap.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            Settings.System.putStringForUser(cr, GAME_LIST_KEY, sb.toString(),
                    UserHandle.USER_CURRENT);
            synchronized (mGameList) {
                if (add) mGameList.put(packageName, "2");
                else mGameList.remove(packageName);
            }
            notifyListeners();
        }
    }

    void addListener(GameListChangeListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    private void notifyListeners() {
        synchronized (mListeners) {
            for (GameListChangeListener listener : mListeners) {
                listener.onGameListChanged();
            }
        }
    }
}
