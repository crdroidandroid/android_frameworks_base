/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton background thread for each process.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BackgroundThread extends HandlerThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;
    private Handler mHandler;
    private HandlerExecutor mHandlerExecutor;

    private BackgroundThread() {
        super("android.bg", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    @NonNull
    public static BackgroundThread get() {
        return ThreadHolder.INSTANCE;
    }

    @NonNull
    public static Handler getHandler() {
        return ThreadHolder.INSTANCE.mHandler;
    }

    @NonNull
    public static Executor getExecutor() {
        return ThreadHolder.INSTANCE.mHandlerExecutor;
    }

    private static final class ThreadHolder {
        private static final BackgroundThread INSTANCE;
        static {
            BackgroundThread thread = new BackgroundThread();
            thread.start();
            final Looper looper = thread.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            thread.mHandler = new Handler(looper, /*callback=*/ null, /* async=*/ false,
                    /* shared=*/ true);
            thread.mHandlerExecutor = new HandlerExecutor(thread.mHandler);
            INSTANCE = thread;
        }
    }
}
