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

import static com.android.server.gesture.threefinger.NtGestureImpl.Callbacks;

import android.graphics.PointF;
import android.content.Context;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.android.internal.util.NtThreeFingerGestureHelper;

public class SwipeDownGesture extends ThreeFingerGestureImpl {
    private static final float SWIPE_THRESHOLD_RATIO = 0.14f;
    private static final String TAG = "ThreeFinger[SwipeDown]";
    private final Callbacks mCallbacks;

    public SwipeDownGesture(Context context, Callbacks callbacks) {
        super(context);
        mCallbacks = callbacks;
    }

    private boolean isTfSwipe(SparseArray<PointF> points) {
        int distance = isPortrait() ? screenSize.getHeight() : screenSize.getWidth();
        int height = (int) (distance * SWIPE_THRESHOLD_RATIO);
        for (int i = 0; i < points.size(); i++) {
            int iKeyAt = points.keyAt(i);
            if (points.get(iKeyAt).y - touchPoints.get(iKeyAt).y < height) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected boolean onGestureFinish(MotionEvent event) {
        if (!gestureActive) {
            return false;
        }
        SparseArray<PointF> points = new SparseArray<>();
        NtThreeFingerGestureHelper.getPoints(event, points);
        if (isTfSwipe(points)) {
            mCallbacks.onThreeFingerSwipe();
            return true;
        }
        return false;
    }
}
