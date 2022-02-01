/**
 * Copyright (C) 2023 The LibreMobileOS Foundation
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

package com.android.server.app;

import android.content.Intent;

import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptorInfo;

import java.util.Set;

/**
 * Internal class for system server to manage app lock.
 *
 * @hide
 */
public interface AppLockManagerServiceInternal {

    /**
     * Whether user has to unlock this application in order to
     * open it.
     *
     * @param packageName the package name of the app to check.
     * @param userId the user id given by the caller.
     * @return true if user has to unlock, false otherwise.
     */
    boolean requireUnlock(String packageName, int userId);

    /**
     * Report that password for user has changed.
     *
     * @param userId the user for which password has changed.
     */
    void reportPasswordChanged(int userId);

    /**
     * Check whether notification content should be hidden for a package.
     *
     * @param packageName the package to check for.
     * @param userId the user id given by the caller.
     * @return true if notification should be hidden, false otherwise.
     */
    boolean shouldRedactNotification(String packageName, int userId);

    /**
     * Notify that the device is locked for current user.
     */
    void notifyDeviceLocked(boolean locked, int userId);

    /**
     * Whether to intercept the activity launch from a package. Used
     * to show confirm credentials prompt.
     *
     * @param info [ActivityInterceptorInfo] of intercepted activity.
     * @return [Intent] which will be fired. Return null if activity
     *    shouldn't be intercepted.
     */
    Intent interceptActivity(ActivityInterceptorInfo info);

    /**
     * Get the list of applications hidden from launcher.
     *
     * @param userId the user id given of the caller.
     * @return a hash set of package names.
     */
    Set<String> getHiddenPackages(int userId);
}
