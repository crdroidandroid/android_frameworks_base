/*
 * Copyright (C) 2019 The PixelExperience Project
 * Copyright (C) 2024-2025 crDroid Android Project
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

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import com.android.server.ServiceThread;

/**
 * Detects a three fingers swipe down on the default display.
 */
public class ThreeFingersSwipeListener {

    private static final String TAG = "ThreeFingersSwipeListener";
    private static final int THREE_GESTURE_STATE_NONE = 0;
    private static final int THREE_GESTURE_STATE_DETECTING = 1;
    private static final int THREE_GESTURE_STATE_DETECTED_FALSE = 2;
    private static final int THREE_GESTURE_STATE_DETECTED_TRUE = 3;
    private static final int THREE_GESTURE_STATE_NO_DETECT = 4;

    private final Context mContext;
    private final Callbacks mCallbacks;
    private final Handler mHandler;

    private final float[] mInitMotionY = new float[3];
    private final int[] mPointerIds = new int[3];
    private final int mThreeGestureThreshold;
    private final int mThreshold;
    private final float mDensity;
    private final int mScreenHeight;
    private final int mScreenWidth;

    private int mThreeGestureState = THREE_GESTURE_STATE_NONE;
    private boolean mEnabled;
    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    public ThreeFingersSwipeListener(Context context, Callbacks callbacks) {
        mCallbacks = callbacks;
        mContext = context;
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        mDensity = displayMetrics.density;
        mThreshold = (int) (50.0f * mDensity);
        mThreeGestureThreshold = mThreshold * 3;
        mScreenHeight = displayMetrics.heightPixels;
        mScreenWidth = displayMetrics.widthPixels;

        ServiceThread thread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DISPLAY, false /* allowIo */);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    public void setEnabled(boolean enabled) {
        mHandler.post(() -> setEnabledInternal(enabled));
    }

    private void setEnabledInternal(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        if (enabled) {
            try {
                mInputMonitor = mContext.getSystemService(InputManager.class)
                        .monitorGestureInput(TAG, Display.DEFAULT_DISPLAY);
                mInputEventReceiver = new InputEventReceiver(
                        mInputMonitor.getInputChannel(), mHandler.getLooper()) {
                    @Override
                    public void onInputEvent(InputEvent event) {
                        try {
                            if (event instanceof MotionEvent
                                    && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                                onPointerEvent((MotionEvent) event);
                            }
                        } finally {
                            finishInputEvent(event, false /* handled */);
                        }
                    }
                };
            } catch (Exception e) {
                Slog.e(TAG, "Failed to create input monitor", e);
                disposeMonitor();
                mEnabled = false;
            }
        } else {
            disposeMonitor();
        }
        mThreeGestureState = THREE_GESTURE_STATE_NONE;
    }

    private void disposeMonitor() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void onPointerEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            changeThreeGestureState(THREE_GESTURE_STATE_NONE);
        } else if (mThreeGestureState == THREE_GESTURE_STATE_NONE && event.getPointerCount() == 3) {
            if (checkIsStartThreeGesture(event)) {
                changeThreeGestureState(THREE_GESTURE_STATE_DETECTING);
                for (int i = 0; i < 3; i++) {
                    mPointerIds[i] = event.getPointerId(i);
                    mInitMotionY[i] = event.getY(i);
                }
            } else {
                changeThreeGestureState(THREE_GESTURE_STATE_NO_DETECT);
            }
        }
        if (mThreeGestureState == THREE_GESTURE_STATE_DETECTING) {
            if (event.getPointerCount() != 3) {
                changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_FALSE);
                return;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float distance = 0.0f;
                for (int i = 0; i < 3; i++) {
                    int index = event.findPointerIndex(mPointerIds[i]);
                    if (index < 0 || index >= 3) {
                        changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_FALSE);
                        return;
                    }
                    distance += event.getY(index) - mInitMotionY[i];
                }
                if (distance >= ((float) mThreeGestureThreshold)) {
                    changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_TRUE);
                    mCallbacks.onSwipeThreeFingers();
                }
            }
        }
    }

    private void changeThreeGestureState(int state) {
        if (mThreeGestureState == state) {
            return;
        }
        mThreeGestureState = state;
        if (state == THREE_GESTURE_STATE_DETECTING && mInputMonitor != null) {
            mInputMonitor.pilferPointers();
        }
    }

    private boolean checkIsStartThreeGesture(MotionEvent event) {
        if (event.getEventTime() - event.getDownTime() > 500) {
            return false;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < event.getPointerCount(); i++) {
            float x = event.getX(i);
            float y = event.getY(i);
            if (y > ((float) (mScreenHeight - mThreshold))) {
                return false;
            }
            maxX = Math.max(maxX, x);
            minX = Math.min(minX, x);
            maxY = Math.max(maxY, y);
            minY = Math.min(minY, y);
        }
        if (maxY - minY <= mDensity * 150.0f) {
            return maxX - minX <= ((float) Math.min(mScreenWidth, mScreenHeight));
        }
        return false;
    }

    interface Callbacks {
        void onSwipeThreeFingers();
    }
}
