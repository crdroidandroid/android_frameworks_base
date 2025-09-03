/*
 * Copyright (C) 2025 AxionOS Project
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

import android.util.Slog;

public class WmHelper {
    private static final String TAG = "NtGestureImpl[WmHelper]";
    
    public static boolean rejectScreenshot(WindowManagerService windowManagerService) {
        synchronized (windowManagerService.mGlobalLock) {
            DisplayContent defaultDisplayContentLocked = windowManagerService.getDefaultDisplayContentLocked();
            WindowState windowState = defaultDisplayContentLocked.mCurrentFocus;
            ActivityRecord activityRecord = defaultDisplayContentLocked.mFocusedApp;
            if (windowState != null) {
                if (windowState.isSecureLocked()) {
                    Slog.i(TAG, "rejectScreenshot because current is secured. " + windowState);
                    return true;
                }
                if (defaultDisplayContentLocked.isKeyguardLocked() && !defaultDisplayContentLocked.isKeyguardOccluded()) {
                    Slog.i(TAG, "rejectScreenshot because current is keyguard");
                    return true;
                }
            }
            if (activityRecord == null || !"com.nothing.ntscreenshot".equals(activityRecord.packageName)) {
                return false;
            }
            Slog.i(TAG, "rejectScreenshot because current package is not enable to take screenshot");
            return true;
        }
    }
}
