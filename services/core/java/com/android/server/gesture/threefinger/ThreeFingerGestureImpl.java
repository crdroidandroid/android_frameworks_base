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

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static com.android.server.gesture.threefinger.NtThreeFingerGesture.ActionMask;

import android.content.Context;
import android.graphics.PointF;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.android.internal.util.NtThreeFingerGestureHelper;

public abstract class ThreeFingerGestureImpl {

    protected static final int HOLD_DELAY_MS = 500;

    protected static final int ACTION_DOWN = 1;
    protected static final int ACTION_MOVE = 2;
    protected static final int ACTION_UP = 3;
    protected static final int ACTION_CANCEL = 4;

    protected PowerManager powerManager;
    protected SparseArray<PointF> touchPoints = new SparseArray<>();
    protected Size screenSize;
    protected boolean gestureActive;
    
    private final Context mContext;

    private static class ActionMapper {
        static final int[] actionMap;

        static {
            actionMap = new int[ActionMask.values().length];
            actionMap[ActionMask.DOWN.ordinal()] = ACTION_DOWN;
            actionMap[ActionMask.MOVE.ordinal()] = ACTION_MOVE;
            actionMap[ActionMask.UP.ordinal()] = ACTION_UP;
            actionMap[ActionMask.CANCEL.ordinal()] = ACTION_CANCEL;
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

        Slog.d(getTag(), "Screen size: " + screenSize);

        powerManager = (PowerManager) context.getSystemService(PowerManager.class);
    }

    protected boolean handle(ActionMask mask, MotionEvent event) {
        if (event.getPointerCount() != 3) {
            reset(event);
            return false;
        }

        boolean didSomething = false;
        int action = ActionMapper.actionMap[mask.ordinal()];

        switch (action) {
            case ACTION_DOWN:
                gestureActive = checkGestureStart(event);
                break;
            case ACTION_MOVE:
                onGestureMove(event);
                break;
            case ACTION_UP:
            case ACTION_CANCEL:
                didSomething = onGestureFinish(event);
                break;
        }
        return gestureActive || didSomething;
    }

    protected void reset(MotionEvent event) {
        gestureActive = false;
    }

    protected boolean checkGestureStart(MotionEvent event) {
        if ((event.getEventTime() - event.getDownTime()) >= HOLD_DELAY_MS) {
            Slog.d(getTag(), "invalid finger down interval");
            return false;
        }
        NtThreeFingerGestureHelper.getPoints(event, touchPoints);
        if (!NtThreeFingerGestureHelper.validStartingPoints(touchPoints)) {
            Slog.d(getTag(), "invalid start points");
            return false;
        }
        if (!powerManager.isInteractive()) {
            Slog.i(getTag(), "is not interactive, return");
            return false;
        }
        return true;
    }

    protected abstract String getTag();

    protected boolean onGestureFinish(MotionEvent event) {
        return false;
    }

    protected void onGestureMove(MotionEvent event) {
    }

    protected boolean isPortrait() {
        return mContext.getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;
    }
}
