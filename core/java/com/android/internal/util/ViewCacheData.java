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
package com.android.internal.util;

import android.app.ActivityThread;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ViewCacheData {
    private static final String FILE_NAME = "view-cache-data";
    private static final String KEY_DEPEND_UI = "depend_ui";

    private final SharedPreferences mPrefs;
    private final Map<String, String> mCachedData = new HashMap<>();
    private StringBuilder mPendingBuilder = new StringBuilder();
    private String mPendingActivity;
    private boolean mDirty;
    private final List<Integer> mDependUiList = new ArrayList<>();

    public ViewCacheData(Context context) {
        mPrefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        Map<String, ?> all = mPrefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String val = (String) e.getValue();
            if (val != null) {
                String key = e.getKey();
                if (KEY_DEPEND_UI.equals(key)) {
                    parseDependUi(val);
                } else {
                    mCachedData.put(key, val);
                }
            }
        }
    }

    private void parseDependUi(String val) {
        for (String s : val.split("/")) {
            try {
                mDependUiList.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public synchronized List<Integer> getLayoutsForActivity(String activityName) {
        String val = mCachedData.get(activityName);
        if (val == null) return null;
        List<Integer> ids = new ArrayList<>();
        for (String s : val.split("/")) {
            try {
                ids.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    public synchronized boolean isValid(int layoutId) {
        return !mDependUiList.contains(layoutId);
    }

    public synchronized void recordDependUi(int layoutId) {
        if (!mDependUiList.contains(layoutId)) {
            mDependUiList.add(layoutId);
            String current = mDependUiList.isEmpty() ? "" : joinIds(mDependUiList);
            mPrefs.edit().putString(KEY_DEPEND_UI, current).apply();
        }
    }

    public synchronized void record(int layoutId) {
        if (mPendingActivity == null) {
            mPendingActivity = resolveProcessName();
        }
        if (mPendingBuilder.length() > 0) {
            mPendingBuilder.append("/");
        }
        mPendingBuilder.append(layoutId);
        mDirty = true;
    }

    public synchronized void sync() {
        if (!mDirty || mPendingActivity == null) return;
        String current = mPendingBuilder.toString();
        String old = mCachedData.get(mPendingActivity);
        if (!current.equals(old)) {
            mCachedData.put(mPendingActivity, current);
            mPrefs.edit().putString(mPendingActivity, current).apply();
        }
        mPendingBuilder.setLength(0);
        mPendingActivity = null;
        mDirty = false;
    }

    private static String resolveProcessName() {
        try {
            ActivityThread at = ActivityThread.currentActivityThread();
            return at != null ? at.getProcessName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String joinIds(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}
