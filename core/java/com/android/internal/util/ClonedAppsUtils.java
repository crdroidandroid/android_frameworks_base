/*
 * Copyright (C) 2025 AxionOS
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

import android.os.UserHandle;
import android.os.SystemProperties;

public class ClonedAppsUtils {

    public static int getClonedAppsUserId() {
        return SystemProperties.getInt("persist.sys.cloned_apps_userId", -1);
    }

    public static boolean isClonedUser(UserHandle user) {
        return user.getIdentifier() == getClonedAppsUserId() && getClonedAppsUserId() != -1; // UserHandle.USER_ALL is -1
    }

    public static boolean isClonedUser(int userId) {
        return userId == getClonedAppsUserId();
    }
}
