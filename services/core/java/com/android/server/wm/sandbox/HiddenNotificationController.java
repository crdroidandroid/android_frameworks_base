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
package com.android.server.wm.sandbox;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.HiddenNotificationInfo;
import com.android.internal.app.IHiddenNotificationListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class HiddenNotificationController {
    private static final String TAG = "AxSandbox.Notif";

    private final RemoteCallbackList<IHiddenNotificationListener> mListeners = new RemoteCallbackList<>();
    private final Map<String, HiddenNotificationInfo> mHiddenNotifications = new HashMap<>();

    private Function<String, Boolean> mIsPackageHidden;

    public HiddenNotificationController() {
    }

    public void setPackageHiddenChecker(Function<String, Boolean> checker) {
        mIsPackageHidden = checker;
    }

    public void registerListener(IHiddenNotificationListener listener) {
        if (listener != null) {
            mListeners.register(listener);
        }
    }

    public void unregisterListener(IHiddenNotificationListener listener) {
        if (listener != null) {
            mListeners.unregister(listener);
        }
    }

    public List<HiddenNotificationInfo> getHiddenNotifications() {
        synchronized (mHiddenNotifications) {
            return new ArrayList<>(mHiddenNotifications.values());
        }
    }

    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        if (info == null) return;

        if (mIsPackageHidden != null && !mIsPackageHidden.apply(info.packageName)) {
            Slog.w(TAG, "Rejecting notification from non-hidden package: " + info.packageName);
            return;
        }

        synchronized (mHiddenNotifications) {
            mHiddenNotifications.put(info.key, info);
        }

        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onHiddenNotificationPosted(info);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify hidden notification listener", e);
            }
        }
        mListeners.finishBroadcast();
    }

    public void onHiddenNotificationRemoved(String key) {
        if (key == null) return;

        synchronized (mHiddenNotifications) {
            mHiddenNotifications.remove(key);
        }

        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onHiddenNotificationRemoved(key);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify hidden notification listener", e);
            }
        }
        mListeners.finishBroadcast();
    }

    public void clearNotificationsForPackage(String packageName) {
        if (packageName == null) return;

        List<String> keysToRemove = new ArrayList<>();
        synchronized (mHiddenNotifications) {
            for (Map.Entry<String, HiddenNotificationInfo> entry : mHiddenNotifications.entrySet()) {
                if (packageName.equals(entry.getValue().packageName)) {
                    keysToRemove.add(entry.getKey());
                }
            }
        }

        for (String key : keysToRemove) {
            onHiddenNotificationRemoved(key);
        }
    }
}
