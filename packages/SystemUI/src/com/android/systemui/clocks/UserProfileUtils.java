/*
 * Copyright (C) 2024-2025 Project Infinity X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.clocks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.content.pm.UserInfo;
import android.os.SystemProperties;
import android.util.Log;
import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.CircleFramedDrawable;

public class UserProfileUtils {

    private static final String TAG = "UserProfileUtils";

    public static Drawable getUserProfileIcon(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null");
            return null;
        }

        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager == null) {
            Log.e(TAG, "UserManager is null");
            return null;
        }

        int userId = UserHandle.USER_CURRENT;
        if (userId < 0) {
            userId = UserHandle.myUserId();
        }

        Bitmap userIcon = userManager.getUserIcon(userId);
        if (userIcon == null) {
            try {
                userIcon = UserIcons.convertToBitmap(UserIcons.getDefaultUserIcon(
                        context.getResources(), userId, false));
            } catch (Exception e) {
                Log.e(TAG, "Failed to get default user icon: " + e.getMessage());
                return null;
            }
        }

        return CircleFramedDrawable.getInstance(context, userIcon);
    }

    public static String getUsername(Context context) {
        if (context == null) {
            Log.e(TAG, "Context is null");
            return "User";
        }

        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager == null) {
            Log.e(TAG, "UserManager is null");
            return "User";
        }

        int userId = UserHandle.USER_CURRENT;
        if (userId < 0) {
            userId = UserHandle.myUserId();
        }

        UserInfo userInfo = userManager.getUserInfo(userId);
        if (userInfo == null) {
            Log.e(TAG, "UserInfo is null for userId: " + userId);
            return "User";
        }

        return userInfo.name;
    }

    public static String getDeviceName() {
        String marketName = SystemProperties.get("ro.product.marketname");
        String modelName = SystemProperties.get("ro.product.model");
        String mistDevice = SystemProperties.get("ro.mist.device");

        if (marketName != null && !marketName.isEmpty()) {
            return marketName;
        } else if (modelName != null && !modelName.isEmpty()) {
            return modelName;
        } else if (mistDevice != null && !mistDevice.isEmpty()) {
            return mistDevice;
        } else {
            Log.d(TAG, "No device name property found, using default.");
            return " ";
        }
    }
}
