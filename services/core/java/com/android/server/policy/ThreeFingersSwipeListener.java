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
import android.hardware.display.DisplayManager;
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
    private static final String MONITOR_NAME = "three-fingers-swipe";

    private static final int THREE_GESTURE_STATE_NONE = 0;
    private static final int THREE_GESTURE_STATE_DETECTING = 1;
    private static final int THREE_GESTURE_STATE_DETECTED_FALSE = 2;
    private static final int THREE_GESTURE_STATE_DETECTED_TRUE = 3;
    private static final int THREE_GESTURE_STATE_NO_DETECT = 4;

    private static final int MAX_CREATE_ATTEMPTS = 5;
    private static final long CREATE_RETRY_DELAY_MS = 1000;

    private final Context mContext;
    private final Callbacks mCallbacks;
    private final Handler mHandler;
    private final DisplayManager mDisplayManager;
    private final Runnable mCreateMonitorRunnable = this::createMonitor;

    private final float[] mInitMotionY = new float[3];
    private final int[] mPointerIds = new int[3];

    private float mDensity;
    private int mThreshold;
    private int mThreeGestureThreshold;
    private int mScreenHeight;
    private int mScreenWidth;
    private int mThreeGestureState = THREE_GESTURE_STATE_NONE;
    private boolean mEnabled;
    private int mCreateAttempts;
    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;
    private boolean mDisplayListenerRegistered;
    private boolean mLoggedCreate;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            // Rotation, resolution switch or density change.
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateDisplayMetrics();
            }
        }
    };

    public ThreeFingersSwipeListener(Context context, Callbacks callbacks) {
        mCallbacks = callbacks;
        mContext = context;
        mDisplayManager = context.getSystemService(DisplayManager.class);
        setMetrics(mContext.getResources().getDisplayMetrics());

        ServiceThread thread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DISPLAY, false /* allowIo */);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    public void setEnabled(boolean enabled) {
        mHandler.post(() -> setEnabledInternal(enabled));
    }

    public void refresh() {
        mHandler.post(this::refreshInternal);
    }

    private void setEnabledInternal(boolean enabled) {
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        mHandler.removeCallbacks(mCreateMonitorRunnable);
        mCreateAttempts = 0;
        mThreeGestureState = THREE_GESTURE_STATE_NONE;
        if (enabled) {
            // Log the first successful creation; silent recreations on wake up are not logged.
            mLoggedCreate = false;
            registerDisplayListener();
            createMonitor();
        } else {
            unregisterDisplayListener();
            disposeMonitor();
            Slog.i(TAG, "Input monitor disposed");
        }
    }

    private void refreshInternal() {
        if (!mEnabled) {
            return;
        }
        mHandler.removeCallbacks(mCreateMonitorRunnable);
        mCreateAttempts = 0;
        mThreeGestureState = THREE_GESTURE_STATE_NONE;
        disposeMonitor();
        createMonitor();
    }

    private void createMonitor() {
        if (!mEnabled || mInputMonitor != null) {
            return;
        }
        try {
            updateDisplayMetrics();

            mInputMonitor = mContext.getSystemService(InputManager.class)
                    .monitorGestureInput(MONITOR_NAME, Display.DEFAULT_DISPLAY);
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
            if (mCreateAttempts > 0 || !mLoggedCreate) {
                Slog.i(TAG, "Input monitor created, display " + mScreenWidth + "x"
                        + mScreenHeight + " density " + mDensity);
                mLoggedCreate = true;
            }
            mCreateAttempts = 0;
        } catch (Exception e) {
            disposeMonitor();
            if (++mCreateAttempts < MAX_CREATE_ATTEMPTS) {
                Slog.w(TAG, "Failed to create input monitor (attempt " + mCreateAttempts
                        + "), retrying", e);
                mHandler.postDelayed(mCreateMonitorRunnable,
                        CREATE_RETRY_DELAY_MS * mCreateAttempts);
            } else {
                Slog.e(TAG, "Failed to create input monitor, giving up until next wake up",
                        e);
            }
        }
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

    private void registerDisplayListener() {
        if (mDisplayListenerRegistered || mDisplayManager == null) {
            return;
        }
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler,
                DisplayManager.EVENT_TYPE_DISPLAY_CHANGED);
        mDisplayListenerRegistered = true;
    }

    private void unregisterDisplayListener() {
        if (!mDisplayListenerRegistered) {
            return;
        }
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        mDisplayListenerRegistered = false;
    }

    private void updateDisplayMetrics() {
        final Display display = mDisplayManager != null
                ? mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (display == null) {
            return;
        }
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        setMetrics(metrics);
    }

    private void setMetrics(DisplayMetrics metrics) {
        mDensity = metrics.density;
        mThreshold = (int) (50.0f * mDensity);
        mThreeGestureThreshold = mThreshold * 3;
        mScreenHeight = metrics.heightPixels;
        mScreenWidth = metrics.widthPixels;
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
