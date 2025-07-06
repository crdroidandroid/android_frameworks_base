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
package com.android.server.gesture.threefinger;

import android.content.Context;
import android.graphics.PointF;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.android.server.gesture.threefinger.NtThreeFingerGesture;
import com.android.internal.util.NtThreeFingerGestureHelper;

public abstract class ThreeFingerGestureImpl {

    protected PowerManager powerManager;
    protected SparseArray<PointF> touchPoints = new SparseArray<>();
    protected Size screenSize;
    protected boolean gestureStarted;
    protected boolean gestureDetected = false;
    
    private final Context mContext;

    private static class ActionMapper {
        static final int[] actionMap;

        static {
            actionMap = new int[NtThreeFingerGesture.ActionMask.values().length];
            try {
                actionMap[NtThreeFingerGesture.ActionMask.DOWN.ordinal()] = 1;
            } catch (NoSuchFieldError ignored) {}
            try {
                actionMap[NtThreeFingerGesture.ActionMask.MOVE.ordinal()] = 2;
            } catch (NoSuchFieldError ignored) {}
            try {
                actionMap[NtThreeFingerGesture.ActionMask.UP.ordinal()] = 3;
            } catch (NoSuchFieldError ignored) {}
            try {
                actionMap[NtThreeFingerGesture.ActionMask.CANCEL.ordinal()] = 4;
            } catch (NoSuchFieldError ignored) {}
        }
    }

    public ThreeFingerGestureImpl(Context context) {
        mContext = context;

        DisplayMetrics metrics = new DisplayMetrics();
        context.getDisplay().getRealMetrics(metrics);

        screenSize = new Size(
                Math.min(metrics.widthPixels, metrics.heightPixels),
                Math.max(metrics.widthPixels, metrics.heightPixels)
        );

        Slog.d(getLogTag(), "Screen size: " + screenSize);

        powerManager = (PowerManager) context.getSystemService(PowerManager.class);
    }

    public boolean handle(NtThreeFingerGesture.ActionMask actionMask, MotionEvent motionEvent) {
        if (motionEvent.getPointerCount() != 3) {
            reset(motionEvent);
            return false;
        }
        int i = ActionMapper.actionMap[actionMask.ordinal()];
        if (i == 1) {
            gestureStarted = checkStartGesture(motionEvent);
        } else {
            if (handleMove(motionEvent)) {
                gestureDetected = true;
            }
        }
        return !gestureStarted || gestureDetected;
    }

    protected void reset(MotionEvent event) {
        if (gestureDetected) {
            handleEndGesture(event);
        }
        gestureDetected = false;
        gestureStarted = false;
    }

    protected boolean checkStartGesture(MotionEvent motionEvent) {
        if (motionEvent.getEventTime() - motionEvent.getDownTime() < 500) {
            NtThreeFingerGestureHelper.getPoints(motionEvent, touchPoints);
            if (NtThreeFingerGestureHelper.validStartingPoints(touchPoints)) {
                if (powerManager.isInteractive()) {
                    return true;
                }
                Slog.i(getLogTag(), "is not interactive, return");
                return false;
            }
            Slog.d(getLogTag(), "invalid start points");
        } else {
            Slog.d(getLogTag(), "invalid finger down interval");
        }
        return false;
    }

    protected abstract String getLogTag();

    protected void handleEndGesture(MotionEvent event) {
    }

    protected boolean handleMove(MotionEvent event) {
        return false;
    }

    protected boolean isPortrait() {
        return mContext.getResources().getConfiguration().orientation == 1;
    }
}
