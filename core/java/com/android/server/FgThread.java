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

package com.android.server;

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton foreground thread for the system.  This is a thread for regular
 * foreground service operations, which shouldn't be blocked by anything running in
 * the background.  In particular, the shared background thread could be doing
 * relatively long-running operations like saving state to disk (in addition to
 * simply being a background priority), which can cause operations scheduled on it
 * to be delayed for a user-noticeable amount of time.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class FgThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    private Handler mHandler;
    private HandlerExecutor mHandlerExecutor;

    private FgThread() {
        super("android.fg", android.os.Process.THREAD_PRIORITY_DEFAULT, true /*allowIo*/);
    }

    public static FgThread get() {
        return ThreadHolder.INSTANCE;
    }

    public static Handler getHandler() {
        return ThreadHolder.INSTANCE.mHandler;
    }

    public static Executor getExecutor() {
        return ThreadHolder.INSTANCE.mHandlerExecutor;
    }

    private static void initHandler(FgThread thread) {
        thread.mHandler = makeSharedHandler(thread.getLooper());
    }

    private static final class ThreadHolder {
        private static final FgThread INSTANCE;
        static {
            FgThread thread = new FgThread();
            thread.start();
            final Looper looper = thread.getLooper();
            looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            looper.setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
            initHandler(thread);
            thread.mHandlerExecutor = new HandlerExecutor(thread.mHandler);
            INSTANCE = thread;
        }
    }
}
