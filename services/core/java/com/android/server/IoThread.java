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
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared singleton I/O thread for the system.  This is a thread for non-background
 * service operations that can potential block briefly on network IO operations
 * (not waiting for data itself, but communicating with network daemons).
 */
public final class IoThread extends ServiceThread {
    private Handler mHandler;
    private HandlerExecutor mHandlerExecutor;

    private IoThread() {
        super("android.io", android.os.Process.THREAD_PRIORITY_DEFAULT, true /*allowIo*/);
    }

    public static IoThread get() {
        return ThreadHolder.INSTANCE;
    }

    public static Handler getHandler() {
        return ThreadHolder.INSTANCE.mHandler;
    }

    public static Executor getExecutor() {
        return ThreadHolder.INSTANCE.mHandlerExecutor;
    }

    private static void initHandler(IoThread thread) {
        thread.mHandler = makeSharedHandler(thread.getLooper());
    }

    private static final class ThreadHolder {
        private static final IoThread INSTANCE;
        static {
            IoThread thread = new IoThread();
            thread.start();
            thread.getLooper().setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
            initHandler(thread);
            thread.mHandlerExecutor = new HandlerExecutor(thread.mHandler);
            INSTANCE = thread;
        }
    }
}
