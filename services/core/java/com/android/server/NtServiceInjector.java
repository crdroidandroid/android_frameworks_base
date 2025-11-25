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
package com.android.server;

import android.content.Context;
import com.android.server.am.ActivityManagerService;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerService;

public class NtServiceInjector {
    private static NtServiceInjector instance = null;
    private ActivityManagerService mService;
    private Context ctx;
    private WindowManagerService mWindowService;
    private PackageManagerService mPackageService;

    private NtServiceInjector() {
    }

    public static synchronized NtServiceInjector get() {
        if (instance == null) {
            instance = new NtServiceInjector();
        }
        return instance;
    }

    void setCtx(Context context) {
        ctx = context;
    }

    void setActivityManagerService(ActivityManagerService activityManagerService) {
        mService = activityManagerService;
    }

    void setWindowManagerService(WindowManagerService windowManagerService) {
        mWindowService = windowManagerService;
    }
    
    void setPackageManagerService(PackageManagerService pm) {
        mPackageService = pm;
    }

    public WindowManagerService getWindowManagerService() {
        return mWindowService;
    }

    public ActivityTaskManagerService getActivityTaskManagerService() {
        return mService.mActivityTaskManager;
    }

    public ActivityManagerService getActivityManagerService() {
        return mService;
    }

    public PackageManagerService getPackageManagerService() {
        return mPackageService;
    }

    public Context getContext() {
        return ctx;
    }

    public static Context getCtx() {
        return get().getContext();
    }

    public static WindowManagerService getWm() {
        return get().getWindowManagerService();
    }

    public static ActivityManagerService getAm() {
        return get().getActivityManagerService();
    }
    
    public static PackageManagerService getPm() {
        return get().getPackageManagerService();
    }
}
