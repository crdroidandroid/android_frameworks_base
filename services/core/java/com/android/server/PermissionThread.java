/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton thread for the system. This is a thread for handling
 * calls to and from the PermissionController and handling synchronization
 * between permissions and appops states.
 */
public final class PermissionThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    private Handler mHandler;
    private HandlerExecutor mHandlerExecutor;

    private PermissionThread() {
        super("android.perm", android.os.Process.THREAD_PRIORITY_DEFAULT, /* allowIo= */ true);
    }

    /**
     * Obtain a singleton instance of the PermissionThread.
     */
    public static PermissionThread get() {
        return ThreadHolder.INSTANCE;
    }

    /**
     * Obtain a singleton instance of a handler executing in the PermissionThread.
     */
    public static Handler getHandler() {
        return ThreadHolder.INSTANCE.mHandler;
    }


    /**
     * Obtain a singleton instance of an executor of the PermissionThread.
     */
    public static Executor getExecutor() {
        return ThreadHolder.INSTANCE.mHandlerExecutor;
    }

    private static final class ThreadHolder {
        private static final PermissionThread INSTANCE;
        static {
            PermissionThread thread = new PermissionThread();
            thread.start();
            final Looper looper = thread.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            thread.mHandler = new Handler(looper);
            thread.mHandlerExecutor = new HandlerExecutor(thread.mHandler);
            INSTANCE = thread;
        }
    }
}
