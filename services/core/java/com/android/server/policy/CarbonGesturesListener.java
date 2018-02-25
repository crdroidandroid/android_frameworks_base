/*
 * Copyright (C) 2015 The Euphoria-OS Project
 * Copyright (C) 2015 The SudaMod Project
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2018 CarbonROM
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

package com.android.server.policy;

import static java.lang.Math.abs;
import android.content.Context;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy.PointerEventListener;

public class CarbonGesturesListener implements PointerEventListener {
    private static final String TAG = "CarbonGestures";
    private static final boolean DEBUG = false;
    private static int NUM_POINTER_GESTURE = 0;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int MAX_TRACKED_POINTERS = 32;
    private static final int UNTRACKED_POINTER = -1;
    private static final int SWIPE_DISTANCE = 350;
    private int GESTURE_N_SWIPE_MASK = 1;
    private Directions N_SWIPE_DIRECTION = Directions.INVALID;
    private final Callbacks mCallbacks;
    private final int[] mDownPointerId = new int[MAX_TRACKED_POINTERS];
    private final float[] mDownX = new float[MAX_TRACKED_POINTERS];
    private final float[] mDownY = new float[MAX_TRACKED_POINTERS];
    private final long[] mDownTime = new long[MAX_TRACKED_POINTERS];
    private int mDownPointers;
    private boolean mSwipeFireable = false;
    private int mSwipeMask = 1;

    public CarbonGesturesListener(Context paramContext, int fingers, Directions direction, Callbacks callbacks) {
        NUM_POINTER_GESTURE = fingers;
        N_SWIPE_DIRECTION = direction;
        for (int i = 0; i < fingers; i++) {
            GESTURE_N_SWIPE_MASK |= 1 << i + 1;
        }
        mCallbacks = checkNull("callbacks", callbacks);
    }

    public enum Directions {
        RIGHT, LEFT, UP, DOWN, INVALID
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeFireable = true;
                mDownPointers = 0;
                captureDown(event, 0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                captureDown(event, event.getActionIndex());
                break;
            case MotionEvent.ACTION_MOVE:
                if (DEBUG) Slog.d(TAG, "count2" + event.getPointerCount());
                if (mSwipeFireable) {
                    detectSwipe(event);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mSwipeMask == GESTURE_N_SWIPE_MASK) {
                    mSwipeMask = 1;
                    if (DEBUG) Slog.d(TAG, "detected" + N_SWIPE_DIRECTION.toString() + "Gesture");
                    mCallbacks.onSwipeGesture();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mSwipeFireable = false;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                break;
            default:
                if (DEBUG) Slog.d(TAG, "Ignoring " + event);
        }
    }

    private void captureDown(MotionEvent event, int pointerIndex) {
        final int pointerId = event.getPointerId(pointerIndex);
        final int i = findIndex(pointerId);
        final int pointerCount  = event.getPointerCount();
        if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                " down pointerIndex=" + pointerIndex + " trackingIndex=" + i);
        if (i != UNTRACKED_POINTER) {
            mDownX[i] = event.getX(pointerIndex);
            mDownY[i] = event.getY(pointerIndex);
            mDownTime[i] = event.getEventTime();
            if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                    " down x=" + mDownX[i] + " y=" + mDownY[i]);
        }
        if (pointerCount == NUM_POINTER_GESTURE) {
            mSwipeFireable = true;
            return;
        }
        mSwipeFireable = false;
    }

    private int findIndex(int pointerId) {
        for (int i = 0; i < mDownPointers; i++) {
            if (mDownPointerId[i] == pointerId) {
                return i;
            }
        }
        if (mDownPointers == MAX_TRACKED_POINTERS || pointerId == MotionEvent.INVALID_POINTER_ID) {
            return UNTRACKED_POINTER;
        }
        mDownPointerId[mDownPointers++] = pointerId;
        return mDownPointers - 1;
    }

    private void detectSwipe(MotionEvent move) {
        move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (i != UNTRACKED_POINTER) {
                detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
            }
        }
    }

    private boolean validSwipeDirection(int i, float x, float fromX, float y, float fromY) {
        boolean valid = false;
        final float sizeInX = fromX - x;
        final float sizeInY = fromY - y;
        final float abssizeInX = Math.abs(sizeInX);
        final float abssizeInY = Math.abs(sizeInY);
        switch (N_SWIPE_DIRECTION) {
            case UP:
            if (abssizeInY > abssizeInX && sizeInY > SWIPE_DISTANCE) {
                if (DEBUG) Slog.d(TAG, "detected UP Gesture on Pointer: "+ i);
                valid = true;
            }
            break;
            case DOWN:
            if (abssizeInY > abssizeInX && sizeInY < -1.0f * SWIPE_DISTANCE) {
                if (DEBUG) Slog.d(TAG, "detected DOWN Gesture on Pointer: "+ i);
                valid = true;
            }
            break;
            case LEFT:
            if (abssizeInY < abssizeInX && sizeInX > SWIPE_DISTANCE) {
                if (DEBUG) Slog.d(TAG, "detected LEFT Gesture on Pointer: "+ i);
                valid = true;
            }
            break;
            case RIGHT:
            if (abssizeInY < abssizeInX && sizeInX < -1.0f * SWIPE_DISTANCE) {
                if (DEBUG) Slog.d(TAG, "detected RIGHT Gesture on Pointer: "+ i);
                valid = true;
            }
            break;
        }
        return valid;
    }

    private void detectSwipe(int i, long time, float x, float y) {
        final float fromX = mDownX[i];
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "pointer " + mDownPointerId[i]
                + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);
        if (mSwipeMask < GESTURE_N_SWIPE_MASK
                && elapsed < SWIPE_TIMEOUT_MS
                && validSwipeDirection(i, x, fromX, y, fromY)) {
            mSwipeMask |= 1 << i + 1;
            if (DEBUG) Slog.d(TAG, "swipe mask = " + mSwipeMask);
        }
    }

    interface Callbacks {
        void onSwipeGesture();
    }
}
