/*
 * Copyright (C) 2019 The PixelExperience Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.internal.util.thermal;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.content.Intent;
import android.os.UserHandle;

public class ThermalController {

    public static final String SERVICE_PACKAGE = "com.android.thermalcontroller";
    public static final String ACTIVE_PACKAGE_CHANGED_ACTION = "android.intent.action.ACTIVE_PACKAGE_CHANGED";
    public static final String ACTIVE_PACKAGE_CHANGED_EXTRA = "package_name";
    public static final String AUTHORITY = "com.android.thermalcontroller";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/preferences");
    public static final String COLUMN_PROFILE = "profile";
    public static final String[] PROJECTION_DEFAULT = new String[]{COLUMN_PROFILE};

    public static boolean isAvailable(Context context) {
        final PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(SERVICE_PACKAGE, PackageManager.GET_ACTIVITIES);
            int enabled = pm.getApplicationEnabledSetting(SERVICE_PACKAGE);
            return enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                    enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void setProfile(String packageName, int profile, Context context) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_PROFILE, profile);
        context.getContentResolver().insert(Uri.parse(CONTENT_URI + "/" + packageName), values);
    }

    public static int getProfile(String packageName, Context context) {
        Cursor c = context.getContentResolver().query(Uri.parse(CONTENT_URI + "/" + packageName), PROJECTION_DEFAULT,
                null, null, null);
        if (c != null) {
            try {
                int count = c.getCount();
                if (count > 0) {
                    for (int i = 0; i < count; i++) {
                        c.moveToPosition(i);
                        if (i == 0) {
                            return c.getInt(0);
                        }
                    }
                }
            } finally {
                c.close();
            }
        }
        return 0;
    }

    public static void sendActivePackageChangedBroadcast(String packageName, Context context) {
        Intent intent = new Intent(ACTIVE_PACKAGE_CHANGED_ACTION);
        intent.putExtra(ACTIVE_PACKAGE_CHANGED_EXTRA, packageName);
        intent.setFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        context.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
    }
}
