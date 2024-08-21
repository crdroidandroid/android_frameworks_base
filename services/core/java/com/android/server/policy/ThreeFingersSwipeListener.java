/*
 * Copyright (C) 2019 The PixelExperience Project
 * Copyright (C) 2024 crDroid Android Project
 *               2023-2024 The risingOS Android Project
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
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

public class ThreeFingersSwipeListener implements PointerEventListener {

    private static final String TAG = "ThreeFingersSwipeListener";
    private static final int THREE_GESTURE_STATE_NONE = 0;
    private static final int THREE_GESTURE_STATE_DETECTING = 1;
    private static final int THREE_GESTURE_STATE_DETECTED_FALSE = 2;
    private static final int THREE_GESTURE_STATE_DETECTED_TRUE = 3;
    private static final int THREE_GESTURE_STATE_NO_DETECT = 4;
    private static final long LONG_SWIPE_TIMEOUT = 800;
    private static final float MAX_MOVE_THRESHOLD = 50.0f;
    private float[] mInitMotionY;
    private float[] mInitMotionX;
    private int[] mPointerIds;
    private int mThreeGestureState = THREE_GESTURE_STATE_NONE;
    private int mThreeGestureThreshold;
    private int mThreshold;
    private float mDensity;
    private int mScreenHeight;
    private int mScreenWidth;
    private final Callbacks mCallbacks;
    private long mDownTime;
    private boolean isLongSwipeTriggered = false;

    public ThreeFingersSwipeListener(Context context, Callbacks callbacks) {
        mPointerIds = new int[3];
        mInitMotionY = new float[3];
        mInitMotionX = new float[3];
        mCallbacks = callbacks;
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        mDensity = displayMetrics.density;
        mThreshold = (int) (50.0f * mDensity);
        mThreeGestureThreshold = mThreshold * 3;
        mScreenHeight = displayMetrics.heightPixels;
        mScreenWidth = displayMetrics.widthPixels;
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mDownTime = event.getDownTime();
            mThreeGestureState = THREE_GESTURE_STATE_NONE;
            isLongSwipeTriggered = false;
        } else if (mThreeGestureState == THREE_GESTURE_STATE_NONE && event.getPointerCount() == 3) {
            if (checkIsStartThreeGesture(event)) {
                mThreeGestureState = THREE_GESTURE_STATE_DETECTING;
                for (int i = 0; i < 3; i++) {
                    mPointerIds[i] = event.getPointerId(i);
                    mInitMotionY[i] = event.getY(i);
                    mInitMotionX[i] = event.getX(i);
                }
            } else {
                mThreeGestureState = THREE_GESTURE_STATE_NO_DETECT;
            }
        }
        if (mThreeGestureState == THREE_GESTURE_STATE_DETECTING) {
            if (event.getPointerCount() != 3) {
                mThreeGestureState = THREE_GESTURE_STATE_DETECTED_FALSE;
                return;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float distanceY = 0.0f;
                float distanceX = 0.0f;
                int i = 0;
                while (i < 3) {
                    int index = event.findPointerIndex(mPointerIds[i]);
                    if (index < 0 || index >= 3) {
                        mThreeGestureState = THREE_GESTURE_STATE_DETECTED_FALSE;
                        return;
                    } else {
                        distanceY += event.getY(index) - mInitMotionY[i];
                        distanceX += event.getX(index) - mInitMotionX[i];
                        i++;
                    }
                }
                if (Math.abs(distanceY) >= ((float) mThreeGestureThreshold) 
                        || Math.abs(distanceX) >= ((float) mThreeGestureThreshold)) {
                    mThreeGestureState = THREE_GESTURE_STATE_DETECTED_TRUE;
                    mCallbacks.onSwipeThreeFingers();
                    return;
                }
                if (!isLongSwipeTriggered && event.getEventTime() - mDownTime 
                        >= LONG_SWIPE_TIMEOUT && Math.abs(distanceY) 
                        < MAX_MOVE_THRESHOLD && Math.abs(distanceX) < MAX_MOVE_THRESHOLD) {
                    mThreeGestureState = THREE_GESTURE_STATE_DETECTED_TRUE;
                    mCallbacks.onLongSwipeThreeFingers();
                    return;
                }
            }
        }
    }

    private boolean checkIsStartThreeGesture(MotionEvent event) {
        if (event.getEventTime() - event.getDownTime() > 500) {
            return false;
        }
        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
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
            return maxX - minX <= ((float) (mScreenWidth < mScreenHeight ? mScreenWidth : mScreenHeight));
        }
        return false;
    }

    interface Callbacks {
        void onSwipeThreeFingers();
        void onLongSwipeThreeFingers();
    }
}
