/*
 * Copyright (C) 2019 The PixelExperience Project
 * Copyright (C) 2024 crDroid Android Project
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
import android.os.UserHandle;
import android.provider.Settings;
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
    private float[] mInitMotionY;
    private int[] mPointerIds;
    private final Context mContext;
    private int mThreeGestureState = THREE_GESTURE_STATE_NONE;
    private int mThreeGestureThreshold;
    private int mThreshold;
    private float mDensity;
    private int mScreenHeight;
    private int mScreenWidth;
    private final Callbacks mCallbacks;

    public ThreeFingersSwipeListener(Context context, Callbacks callbacks) {
        mPointerIds = new int[3];
        mInitMotionY = new float[3];
        mCallbacks = callbacks;
        mContext = context;
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        mDensity = displayMetrics.density;
        mThreshold = (int) (50.0f * mDensity);
        mThreeGestureThreshold = mThreshold * 3;
        mScreenHeight = displayMetrics.heightPixels;
        mScreenWidth = displayMetrics.widthPixels;

        // reset the setting flag on init
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.THREE_FINGER_GESTURE_ACTIVE, 0,
                UserHandle.USER_CURRENT);
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (event.getAction() == 0) {
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
                int i = 0;
                while (i < 3) {
                    int index = event.findPointerIndex(mPointerIds[i]);
                    if (index < 0 || index >= 3) {
                        changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_FALSE);
                        return;
                    } else {
                        distance += event.getY(index) - mInitMotionY[i];
                        i++;
                    }
                }
                if (distance >= ((float) mThreeGestureThreshold)) {
                    changeThreeGestureState(THREE_GESTURE_STATE_DETECTED_TRUE);
                    mCallbacks.onSwipeThreeFingers();
                }
            }
        }
    }

    private void changeThreeGestureState(int state) {
        if (mThreeGestureState != state){
            mThreeGestureState = state;
            boolean shouldEnableFlag = mThreeGestureState == THREE_GESTURE_STATE_DETECTED_TRUE ||
                    mThreeGestureState == THREE_GESTURE_STATE_DETECTING;
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.THREE_FINGER_GESTURE_ACTIVE, shouldEnableFlag ? 1 : 0,
                    UserHandle.USER_CURRENT);
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
    }
}
