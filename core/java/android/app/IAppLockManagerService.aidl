/*
 * Copyright (C) 2022 FlamingoOS Project
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

import android.app.AppLockData;

/**
 * Interface for managing app lock.
 * @hide
 */
interface IAppLockManagerService {

    void addPackage(in String packageName, in int userId);

    void removePackage(in String packageName, in int userId);

    long getTimeout(in int userId);

    void setTimeout(in long timeout, in int userId);

    List<AppLockData> getPackageData(in int userId);

    void setShouldRedactNotification(in String packageName, in boolean secure, in int userId);

    void setBiometricsAllowed(in boolean biometricsAllowed, in int userId);

    boolean isBiometricsAllowed(in int userId);

    void unlockPackage(in String packageName, in int userId);

    void setPackageHidden(in String packageName, boolean hide, in int userId);

    List<String> getHiddenPackages(in int userId);
}