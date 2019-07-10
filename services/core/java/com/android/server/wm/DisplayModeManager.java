/*
 *  Copyright (C) 2019 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
*/

package com.android.server.wm;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Display.Mode;

public class DisplayModeManager {

    SettingsObserver mSettingsObserver;
    Context mContext;
    final DisplayManager mDisplayManager;
    WindowManagerService mService;

    private final class SettingsObserver extends ContentObserver {

        public SettingsObserver() {
            super(new Handler());
            mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.DISPLAY_MODE),
                false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null) {
                updateSettings();
            }
        }
    }

    private void updateSettings() {
         int displayMode = getScreenMode();
         if (displayMode != -1) {
             mDisplayManager.requestScreenMode(displayMode);
             //mService.requestTraversal();
             //mService.setCurrentUser(mService.mCurrentUserId, mService.mCurrentProfileIds);
         }
    }

    public DisplayModeManager(WindowManagerService service, Context context) {
        mService = service;
        mContext = context;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mSettingsObserver = new SettingsObserver();
    }

    public int getScreenMode() {
        int displayMode = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.DISPLAY_MODE, -1, UserHandle.USER_CURRENT);
        return displayMode;
    }
}
