/*
 * Copyright (C) 2025-2026 AxionOS
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
package android.view;

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.Executor;

/**
 * @hide
 */
public final class ViewBackgroundThread extends HandlerThread {
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30000;
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10000;
    private static volatile Handler sHandler;
    private static HandlerExecutor sHandlerExecutor;
    private static volatile ViewBackgroundThread sInstance;

    private ViewBackgroundThread() {
        super("view.bg", Process.THREAD_PRIORITY_BACKGROUND);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new ViewBackgroundThread();
            sInstance.start();
        }
        if (sHandler == null) {
            Looper looper = sInstance.getLooper();
            looper.setSlowLogThresholdMs(SLOW_DISPATCH_THRESHOLD_MS,
                    SLOW_DELIVERY_THRESHOLD_MS);
            sHandler = new Handler(sInstance.getLooper());
            sHandlerExecutor = new HandlerExecutor(sHandler);
        }
    }

    public static void init() {
        synchronized (ViewBackgroundThread.class) {
            if (sInstance == null) {
                sInstance = new ViewBackgroundThread();
                sInstance.start();
            }
        }
    }

    public static ViewBackgroundThread get() {
        synchronized (ViewBackgroundThread.class) {
            ensureThreadLocked();
            return sInstance;
        }
    }

    public static Handler getHandler() {
        synchronized (ViewBackgroundThread.class) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    public static Executor getExecutor() {
        synchronized (ViewBackgroundThread.class) {
            ensureThreadLocked();
            return sHandlerExecutor;
        }
    }
}
