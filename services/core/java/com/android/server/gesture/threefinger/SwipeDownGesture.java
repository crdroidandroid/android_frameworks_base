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

import android.graphics.PointF;
import android.content.Context;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.android.internal.util.NtThreeFingerGestureHelper;

public class SwipeDownGesture extends ThreeFingerGestureImpl {
    private static final float SWIPE_THRESHOLD_RATIO = 0.14f;
    private static final String TAG = "ThreeFinger[SwipeDown]";
    private final NtGestureImpl.Callbacks mCallbacks;

    public SwipeDownGesture(Context context, NtGestureImpl.Callbacks callbacks) {
        super(context);
        mCallbacks = callbacks;
    }

    private boolean isThreeFingerSwipe(SparseArray<PointF> sparseArray) {
        int height = (int) ((isPortrait() ? screenSize.getHeight() : screenSize.getWidth()) * 0.14f);
        for (int i = 0; i < sparseArray.size(); i++) {
            int iKeyAt = sparseArray.keyAt(i);
            if (sparseArray.get(iKeyAt).y - touchPoints.get(iKeyAt).y < height) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
    
    @Override
    protected void handleEndGesture(MotionEvent event) {
        mCallbacks.onThreeFingerSwipe();
        Slog.i(TAG, "Taking screenshot via three-finger swipe down.");
    }

    @Override
    protected boolean handleMove(MotionEvent event) {
        if (!gestureStarted) {
            return false;
        }
        SparseArray<PointF> sparseArray = new SparseArray<>();
        NtThreeFingerGestureHelper.getPoints(event, sparseArray);
        if (isThreeFingerSwipe(sparseArray)) {
            return true;
        }
        return false;
    }
}
