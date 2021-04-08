/**
 * Copyright (C) 2021 Paranoid Android
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

package android.app;

import android.app.IAppLockCallback;

/** @hide */
interface IAppLockService {

    void addAppToList(String packageName);

    void removeAppFromList(String packageName);

    boolean isAppLocked(String packageName);

    boolean isAppOpen(String packageName);

    void setLockTimeout(int lockTimeout);

    int getLockTimeout();

    int getLockedAppsCount();

    List<String> getLockedPackages();

    boolean getAppNotificationHide(String packageName);

    void setAppNotificationHide(String packageName, boolean hide);

    void addAppLockCallback(IAppLockCallback callback);

    void removeAppLockCallback(IAppLockCallback callback);

}