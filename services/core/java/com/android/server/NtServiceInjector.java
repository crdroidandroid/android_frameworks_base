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
package com.android.server;

import android.content.Context;

import com.android.server.wm.WindowManagerService;

public class NtServiceInjector {
    private static NtServiceInjector instance;
    private final Context context;
    private WindowManagerService windowManagerService;

    private NtServiceInjector(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized NtServiceInjector get(Context context) {
        if (instance == null) {
            instance = new NtServiceInjector(context);
        }
        return instance;
    }

    public static synchronized NtServiceInjector get() {
        if (instance == null) {
            throw new IllegalStateException("NtServiceInjector not initialized. Call getInstance(Context) first.");
        }
        return instance;
    }

    public void setWindowManagerService(WindowManagerService service) {
        this.windowManagerService = service;
    }

    public WindowManagerService getWindowManagerService() {
        return windowManagerService;
    }

    public Context getContext() {
        return context;
    }
}

