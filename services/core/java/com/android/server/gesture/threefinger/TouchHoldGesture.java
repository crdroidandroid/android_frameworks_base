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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WmHelper;

import com.android.internal.util.NtThreeFingerGestureHelper;

public class TouchHoldGesture extends ThreeFingerGestureImpl {

    private static final String TAG = "ThreeFinger[TouchHold]";

    private final DevicePolicyManager mDevicePolicyManager;
    private final ViewConfiguration mViewConfig;
    private final Handler mHandler;
    private final WindowManagerService mWm;

    private final PartialScreenshotHelper mScreenshotHelper;
    private final Runnable mHoldRunnable;

    public TouchHoldGesture(Context context, Handler handler, WindowManagerService wm) {
        super(context);
        mHandler = handler;
        mViewConfig = ViewConfiguration.get(context);
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        mScreenshotHelper = new PartialScreenshotHelper(context);
        mHoldRunnable = this::handleLongPress;
        mWm = wm;
    }

    private void handleLongPress() {
        if (mDevicePolicyManager.getScreenCaptureDisabled(null)) {
            Slog.w(TAG, "Partial screenshot blocked by IT admin");
            return;
        }
        Slog.i(TAG, "Triggering partial screenshot");
        try {
            mScreenshotHelper.bindService(touchPoints);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to trigger partial screenshot", e);
        }
    }

    private boolean moved(SparseArray<PointF> points) {
        PointF centerStart = getCenter(touchPoints);
        PointF centerNow = getCenter(points);
        float dist = distance(centerStart, centerNow);
        return dist > mViewConfig.getScaledTouchSlop();
    }

    private PointF getCenter(SparseArray<PointF> points) {
        float x = 0, y = 0;
        for (int i = 0; i < points.size(); i++) {
            PointF p = points.valueAt(i);
            x += p.x;
            y += p.y;
        }
        return new PointF(x / points.size(), y / points.size());
    }

    private float distance(PointF pointF, PointF pointF2) {
        float f = pointF2.x - pointF.x;
        float f2 = pointF2.y - pointF.y;
        return (float) Math.sqrt((f * f) + (f2 * f2));
    }

    @Override
    protected boolean checkGestureStart(MotionEvent event) {
        if (WmHelper.rejectScreenshot(mWm)) {
            return false;
        }
        boolean started = super.checkGestureStart(event);
        if (started) {
            mHandler.removeCallbacks(mHoldRunnable);
            mHandler.postAtTime(mHoldRunnable, event.getDownTime() + HOLD_DELAY_MS);
        }
        return started;
    }

    @Override
    protected void reset(MotionEvent event) {
        mHandler.removeCallbacks(mHoldRunnable);
        gestureActive = false;
        mScreenshotHelper.sendUp(event);
    }

    @Override
    protected boolean onGestureFinish(MotionEvent event) {
        boolean wasActive = gestureActive;
        gestureActive = false;
        mHandler.removeCallbacks(mHoldRunnable);
        mScreenshotHelper.sendUp(event);
        return wasActive;
    }

    @Override
    protected void onGestureMove(MotionEvent event) {
        if (gestureActive) {
            if (mHandler.hasCallbacks(mHoldRunnable)) {
                SparseArray<PointF> points = new SparseArray<>();
                NtThreeFingerGestureHelper.getPoints(event, points);
                if (!moved(points)) {
                    return;
                }
                Slog.i(TAG, "hold still failed");
                gestureActive = false;
            } else {
                mScreenshotHelper.sendMove(event);
            }
        }
        mHandler.removeCallbacks(mHoldRunnable);
    }

    @Override
    protected String getTag() {
        return TAG;
    }
}
