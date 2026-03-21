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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;

/**
 * @hide
 */
public interface IAxSandboxService {

    public static final IAxSandboxService DEFAULT = new IAxSandboxService() {};

    default void systemReadyInternal() {
    }

    default void setKeyguardDoneLocked(boolean done) {
    }

    default void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
    }

    default void onWindowingModeChanged(Task task, int prevWindowingMode) {
    }

    default boolean isAppLocked(String packageName, int uid, ComponentName componentName) {
        return false;
    }

    default boolean isAppLocked(ActivityRecord r) {
        return false;
    }

    default void lockTopApp(Task task, String reason) {
    }

    default boolean isSandboxActivity(ComponentName componentName) {
        return false;
    }

    default boolean checkLockApp(ActivityRecord prev, ActivityRecord next) {
        return false;
    }

    default boolean checkUnlockApp(ActivityRecord r, int resultCode, Intent resultData) {
        return false;
    }

    default boolean isTopAppLocked(ActivityManager.RecentTaskInfo rti, int topUserId) {
        return false;
    }

    default void getRecentTasksCheck(int callingUid, int userId) {
    }

    default void clearUnlockedApp() {
    }

    default void clearUnlockedApp(ActivityRecord r) {
    }

    default void removeTask(Task task, String reason) {
    }

    default void onAppDied(String packageName, int userId) {
    }
}
