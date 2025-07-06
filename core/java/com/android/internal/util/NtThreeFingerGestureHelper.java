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
package com.android.internal.util;

import android.content.Context;
import android.graphics.PointF;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

public class NtThreeFingerGestureHelper {

    public static final boolean FEATURE_ENABLED = true;
    public static final int REQUIRED_FINGER_COUNT = 3;
    public static final String SCREENSHOT_SETTING_KEY = "nothing_three_finger_screenshot";
    public static final long MAX_FINGER_DOWN_TIME_MS = 500;
    private static final float MAX_STARTING_POINT_DISTANCE = 500.0f;

    private final Context mContext;
    private boolean gestureStarted;
    private boolean pointerProcessed;

    public NtThreeFingerGestureHelper(Context context) {
        mContext = context;
    }

    public static void getPoints(MotionEvent event, SparseArray<PointF> pointMap) {
        if (pointMap != null) {
            pointMap.clear();
            for (int i = 0; i < event.getPointerCount(); i++) {
                int pointerId = event.getPointerId(i);
                pointMap.put(pointerId, new PointF(event.getX(i), event.getY(i)));
            }
        }
    }

    private static float distanceBetween(PointF p1, PointF p2) {
        float dx = p2.x - p1.x;
        float dy = p2.y - p1.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float minDistanceToTwoOthers(PointF ref, PointF p1, PointF p2) {
        if (ref == null || p1 == null || p2 == null) {
            return Float.MAX_VALUE;
        }
        return Math.min(distanceBetween(ref, p1), distanceBetween(ref, p2));
    }

    public static boolean validStartingPoints(SparseArray<PointF> points) {
        if (points == null || points.size() != REQUIRED_FINGER_COUNT) {
            return false;
        }

        PointF p1 = points.get(points.keyAt(0));
        PointF p2 = points.get(points.keyAt(1));
        PointF p3 = points.get(points.keyAt(2));

        return minDistanceToTwoOthers(p1, p2, p3) < MAX_STARTING_POINT_DISTANCE &&
               minDistanceToTwoOthers(p2, p1, p3) < MAX_STARTING_POINT_DISTANCE &&
               minDistanceToTwoOthers(p3, p1, p2) < MAX_STARTING_POINT_DISTANCE;
    }

    private boolean isScreenshotEnabled() {
        return Settings.Secure.getInt(mContext.getContentResolver(), SCREENSHOT_SETTING_KEY, 0) != 0;
    }

    public boolean processPointerEvent(View view, MotionEvent motionEvent) {
        if (!pointerProcessed) {
            if (!detectGestureStart(motionEvent)) {
                return false;
            }
            motionEvent.setAction(3);
            view.dispatchPointerEvent(motionEvent);
            pointerProcessed = true;
            return true;
        }
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            pointerProcessed = false;
            return false;
        }
        if (actionMasked == 1 || actionMasked == 3) {
            pointerProcessed = false;
        }
        return true;
    }

    public boolean detectGestureStart(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureStarted = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount == REQUIRED_FINGER_COUNT &&
                        (event.getEventTime() - event.getDownTime()) < MAX_FINGER_DOWN_TIME_MS) {

                    SparseArray<PointF> fingerPoints = new SparseArray<>();
                    getPoints(event, fingerPoints);

                    if (validStartingPoints(fingerPoints)) {
                        gestureStarted = isScreenshotEnabled();
                    } else {
                        gestureStarted = false;
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                break;
        }

        return gestureStarted && pointerCount >= REQUIRED_FINGER_COUNT;
    }
}
