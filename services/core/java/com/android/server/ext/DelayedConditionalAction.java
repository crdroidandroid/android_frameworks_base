/*
 * Copyright (C) 2022 GrapheneOS
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

package com.android.server.ext;

import android.annotation.IntRange;
import android.app.AlarmManager;
import android.content.Context;
import android.ext.settings.IntSetting;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;

/**
 * Infrastructure for actions that:
 * - happen after a user-configurable device-wide (Settings.Global) delay
 * - need to be taken even when the device is in deep sleep
 * - need to be rescheduled based on some listenable event
 */
public abstract class DelayedConditionalAction {
    private static final String TAG = "DelayedConditionalAction";

    protected final SystemServerExt sse;
    protected final Thread thread;
    protected final Handler handler;

    protected final IntSetting setting;

    protected final AlarmManager alarmManager;
    private final AlarmManager.OnAlarmListener alarmListener;

    protected DelayedConditionalAction(SystemServerExt sse, IntSetting setting, Handler handler) {
        this.sse = sse;

        Looper looper = handler.getLooper();
        thread = looper.getThread();
        this.handler = handler;

        if (Build.isDebuggable()) {
            if (thread != Thread.currentThread()) {
                throw new IllegalStateException("all calls should happen on the same thread");
            }
        }

        Context ctx = sse.context;
        alarmManager = ctx.getSystemService(AlarmManager.class);

        alarmListener = () -> {
            if (getDelayDurationMillis() == 0) {
                return;
            }

            alarmTriggered();
        };

        registerStateListener();

        this.setting = setting;

        if (setting.canObserveState()) {
            setting.registerObserver(ctx, s -> update(), handler);
        }
    }

    private boolean alarmScheduled;

    protected final void update() {
        final Thread curThread = Thread.currentThread();
        if (curThread != thread) {
            String msg = "update() called on an unknown thread " + curThread;
            if (Build.isDebuggable()) {
                throw new IllegalStateException(msg);
            } else {
                Slog.e(TAG, msg, new Throwable());
                return;
            }
        }

        if (alarmScheduled) {
            alarmManager.cancel(alarmListener);
            alarmScheduled = false;
        }

        if (!shouldScheduleAlarm()) {
            return;
        }

        long delayMillis = getDelayDurationMillis();

        if (delayMillis == 0) {
            return;
        }

        long current = SystemClock.elapsedRealtime();

        if (Long.MAX_VALUE - delayMillis < current) {
            return;
        }

        final long triggerAt = current + delayMillis;

        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt,
                    getClass().getName(), alarmListener, handler);
        alarmScheduled = true;
    }

    @IntRange(from = 0)
    private long getDelayDurationMillis() {
        return Math.max(0, setting.get(sse.context));
    }

    // Make sure to use the same Handler that is used for all other callbacks;
    // call update() to reschedule / cancel the alarm
    protected abstract void registerStateListener();

    protected abstract boolean shouldScheduleAlarm();
    protected abstract void alarmTriggered();
}
