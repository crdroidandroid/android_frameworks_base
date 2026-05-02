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

import android.content.ComponentName;
import android.content.Context;
import android.os.SystemProperties;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewBackgroundThread;

import java.util.ArrayList;
import java.util.List;

public class ViewCacheManager {
    private static final String TAG = "ViewCacheManager";
    private static final boolean ENABLED = SystemProperties.getBoolean(
            "persist.sys.perf.view_cache", true);
    private static volatile ViewCacheManager sInstance;

    private final SparseArray<List<View>> mViewCache = new SparseArray<>();
    private final Object mLock = new Object();
    private ViewCacheData mData;
    private boolean mActive;
    private String mCurrentActivity;

    public static ViewCacheManager getInstance() {
        if (sInstance == null) {
            sInstance = new ViewCacheManager();
        }
        return sInstance;
    }

    public boolean isEnable() {
        return ENABLED && mActive;
    }

    public void onActivityAttached(Context context, ComponentName component) {
        if (!ENABLED || component == null) return;
        mActive = false;
        mCurrentActivity = component.getClassName();
        synchronized (mLock) {
            mViewCache.clear();
        }
        if (mData == null) {
            mData = new ViewCacheData(context);
        }
        List<Integer> layoutIds = mData.getLayoutsForActivity(mCurrentActivity);
        if (layoutIds == null || layoutIds.isEmpty()) return;
        mActive = true;
        ViewBackgroundThread.getHandler().post(() -> {
            preInflateLayouts(context, layoutIds);
        });
    }

    private void preInflateLayouts(Context context, List<Integer> layoutIds) {
        LayoutInflater inflater = LayoutInflater.from(context)
                .cloneInContext(context);
        for (int id : layoutIds) {
            if (Thread.interrupted()) break;
            if (mData != null && !mData.isValid(id)) continue;
            try {
                View view = inflater.inflate(id, null, false);
                if (view != null) {
                    put(id, view);
                }
            } catch (Exception e) {
                if (mData != null) {
                    mData.recordDependUi(id);
                }
            }
        }
    }

    public View tryGet(int layoutId) {
        if (!ENABLED) return null;
        synchronized (mLock) {
            List<View> entries = mViewCache.get(layoutId);
            if (entries != null && !entries.isEmpty()) {
                return entries.remove(0);
            }
        }
        return null;
    }

    public void put(int layoutId, View view) {
        synchronized (mLock) {
            List<View> entries = mViewCache.get(layoutId);
            if (entries == null) {
                entries = new ArrayList<>();
                mViewCache.put(layoutId, entries);
            }
            entries.add(view);
        }
    }

    public void recordLayoutRes(int resource) {
        if (mData != null) {
            mData.record(resource);
        }
    }

    public void onTraversalEnd(ViewRootImpl vri) {
        if (mData != null) {
            mData.sync();
        }
        mActive = false;
        mCurrentActivity = null;
        synchronized (mLock) {
            mViewCache.clear();
        }
    }
}
