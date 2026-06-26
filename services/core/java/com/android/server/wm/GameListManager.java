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
    private static final String PERF_MODE_VALUE = "2";

    private final Context mContext;
    private final Map<String, String> mGameList = Collections.synchronizedMap(new HashMap<>());
    private final List<GameListChangeListener> mListeners = new ArrayList<>();

    GameListManager(Context context) {
        mContext = context;
        loadGameList();
    }

    void loadGameList() {
        final String raw = Settings.System.getStringForUser(mContext.getContentResolver(),
                GAME_LIST_KEY, UserHandle.USER_CURRENT);
        final Map<String, String> parsed = parseGameList(raw);
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
            return PERF_MODE_VALUE.equals(mGameList.get(packageName));
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
                UserHandle.USER_ALL);
    }

    void addGame(String packageName) {
        updateGameList(packageName, true);
    }

    void removeGame(String packageName) {
        updateGameList(packageName, false);
    }

    void addListener(GameListChangeListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    private void updateGameList(String packageName, boolean add) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String raw = Settings.System.getStringForUser(resolver, GAME_LIST_KEY,
                UserHandle.USER_CURRENT);
        final Map<String, String> gameMap = parseGameList(raw);

        final boolean modified;
        if (add) {
            modified = !PERF_MODE_VALUE.equals(gameMap.get(packageName));
            if (modified) {
                gameMap.put(packageName, PERF_MODE_VALUE);
            }
        } else {
            modified = gameMap.remove(packageName) != null;
        }

        if (!modified) {
            return;
        }

        Settings.System.putStringForUser(resolver, GAME_LIST_KEY, formatGameList(gameMap),
                UserHandle.USER_CURRENT);
        synchronized (mGameList) {
            if (add) {
                mGameList.put(packageName, PERF_MODE_VALUE);
            } else {
                mGameList.remove(packageName);
            }
        }
        notifyListeners();
    }

    private Map<String, String> parseGameList(String raw) {
        final Map<String, String> gameMap = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return gameMap;
        }

        for (String entry : raw.split(";")) {
            final String[] parts = entry.split("=", 2);
            if (parts.length == 2
                    && parts[0].matches("[a-zA-Z0-9_.]+")
                    && parts[1].matches("\\d+")) {
                gameMap.put(parts[0].trim(), parts[1].trim());
            }
        }
        return gameMap;
    }

    private String formatGameList(Map<String, String> gameMap) {
        final StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : gameMap.entrySet()) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private void notifyListeners() {
        synchronized (mListeners) {
            for (GameListChangeListener listener : mListeners) {
                listener.onGameListChanged();
            }
        }
    }
}
