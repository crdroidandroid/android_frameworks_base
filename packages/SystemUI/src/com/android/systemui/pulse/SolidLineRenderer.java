/**
 * Copyright (C) 2016-2022 crDroid Android Project
 * Copyright (C) 2015 The CyanogenMod Project
 * 
 * @author: Randall Rushing <randall.rushing@gmail.com>
 *
 * Contributions from The CyanogenMod Project
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
 *
 */

package com.android.systemui.pulse;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.core.graphics.ColorUtils;

public class SolidLineRenderer extends Renderer {
    private Paint mPaint;
    private int mUnitsOpacity = 200;
    private int mColor = Color.WHITE;
    private ValueAnimator[] mValueAnimators;
    private FFTAverage[] mFFTAverage;
    private float[] mFFTPoints;

    private byte rfk, ifk;
    private int dbValue;
    private float magnitude;
    private int mDbFuzzFactor;
    private boolean mVertical;
    private boolean mLeftInLandscape;
    private int mWidth, mHeight, mUnits;

    private boolean mSmoothingEnabled;
    private boolean mRounded;
    private CMRendererObserver mObserver;

    public SolidLineRenderer(Context context, Handler handler, PulseView view,
            PulseControllerImpl controller, ColorController colorController) {
        super(context, handler, view, colorController);
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mDbFuzzFactor = 5;
        mObserver = new CMRendererObserver(handler);
        mObserver.updateSettings();
        loadValueAnimators();
    }

    @Override
    public void setLeftInLandscape(boolean leftInLandscape) {
        if (mLeftInLandscape != leftInLandscape) {
            mLeftInLandscape = leftInLandscape;
            onSizeChanged(0, 0, 0, 0);
        }
    }

    private void loadValueAnimators() {
        if (mValueAnimators != null) {
            stopAnimation(mValueAnimators.length);
        }
        mValueAnimators = new ValueAnimator[mUnits];
        final boolean isVertical = mVertical;
        for (int i = 0; i < mUnits; i++) {
            final int j;
            if (isVertical) {
                j = i * 4;
            } else {
                j = i * 4 + 1;
            }
            mValueAnimators[i] = new ValueAnimator();
            mValueAnimators[i].setDuration(128);
            mValueAnimators[i].addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mFFTPoints[j] = (float) animation.getAnimatedValue();
                    postInvalidate();
                }
            });
        }
    }

    private void stopAnimation(int index) {
        if (mValueAnimators == null) return;
        for (int i = 0; i < index; i++) {
            // prevent onAnimationUpdate existing listeners (by stopping them) to call
            // a wrong mFFTPoints index after mUnits gets updated by the user
            mValueAnimators[i].removeAllUpdateListeners();
            mValueAnimators[i].cancel();
        }
    }

    private void setPortraitPoints() {
        float units = Float.valueOf(mUnits);
        float barUnit = mWidth / units;
        float barWidth = barUnit * 8f / 9f;
        barUnit = barWidth + (barUnit - barWidth) * units / (units - 1);
        mPaint.setStrokeWidth(barWidth);
        mPaint.setStrokeCap(mRounded ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        for (int i = 0; i < mUnits; i++) {
            mFFTPoints[i * 4] = mFFTPoints[i * 4 + 2] = i * barUnit + (barWidth / 2);
            mFFTPoints[i * 4 + 1] = mHeight;
            mFFTPoints[i * 4 + 3] = mHeight;
        }
    }

    private void setVerticalPoints() {
        float units = Float.valueOf(mUnits);
        float barUnit = mHeight / units;
        float barHeight = barUnit * 8f / 9f;
        barUnit = barHeight + (barUnit - barHeight) * units / (units - 1);
        mPaint.setStrokeWidth(barHeight);
        mPaint.setStrokeCap(mRounded ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        for (int i = 0; i < mUnits; i++) {
            mFFTPoints[i * 4 + 1] = mFFTPoints[i * 4 + 3] = i * barUnit + (barHeight / 2);
            mFFTPoints[i * 4] = mLeftInLandscape ? 0 : mWidth;
            mFFTPoints[i * 4 + 2] = mLeftInLandscape ? 0 : mWidth;
        }
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (mView.getWidth() > 0 && mView.getHeight() > 0) {
            mWidth = mView.getWidth();
            mHeight = mView.getHeight();
            mVertical = mKeyguardShowing ? mHeight < mWidth : mHeight > mWidth;
            loadValueAnimators();
            if (mVertical) {
                setVerticalPoints();
            } else {
                setPortraitPoints();
            }
        }
    }

    @Override
    public void onStreamAnalyzed(boolean isValid) {
        mIsValidStream = isValid;
        if (isValid) {
            onSizeChanged(0, 0, 0, 0);
            mColorController.startLavaLamp();
        }
    }

    @Override
    public void onFFTUpdate(byte[] fft) {
        int fudgeFactor = mKeyguardShowing ? mDbFuzzFactor * 4 : mDbFuzzFactor;
        for (int i = 0; i < mUnits; i++) {
            if (mValueAnimators[i] == null) continue;
            mValueAnimators[i].cancel();
            rfk = fft[i * 2 + 2];
            ifk = fft[i * 2 + 3];
            magnitude = rfk * rfk + ifk * ifk;
            dbValue = magnitude > 0 ? (int) (10 * Math.log10(magnitude)) : 0;
            if (mSmoothingEnabled) {
                if (mFFTAverage == null) {
                    setupFFTAverage();
                }
                dbValue = mFFTAverage[i].average(dbValue);
            }
            if (mVertical) {
                if (mLeftInLandscape) {
                    mValueAnimators[i].setFloatValues(mFFTPoints[i * 4],
                            dbValue * fudgeFactor);
                } else {
                    mValueAnimators[i].setFloatValues(mFFTPoints[i * 4],
                            mFFTPoints[2] - (dbValue * fudgeFactor));
                }
            } else {
                mValueAnimators[i].setFloatValues(mFFTPoints[i * 4 + 1],
                        mFFTPoints[3] - (dbValue * fudgeFactor));
            }
            mValueAnimators[i].start();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawLines(mFFTPoints, mPaint);
    }

    @Override
    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mColorController.stopLavaLamp();
    }

    @Override
    public void onVisualizerLinkChanged(boolean linked) {
        if (!linked) {
            mColorController.stopLavaLamp();
        }
    }

    @Override
    public void onUpdateColor(int color) {
        mColor = color;
        mPaint.setColor(ColorUtils.setAlphaComponent(mColor, mUnitsOpacity));
    }

    private class CMRendererObserver extends ContentObserver {
        public CMRendererObserver(Handler handler) {
            super(handler);
            register();
        }

        void register() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_FUDGE_FACTOR), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_UNITS_COUNT), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_UNITS_OPACITY), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SMOOTHING_ENABLED), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SOLID_UNITS_ROUNDED), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }

        public void updateSettings() {
            ContentResolver resolver = mContext.getContentResolver();

            // putFloat, getFloat is better. catch it next time
            mDbFuzzFactor = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_SOLID_FUDGE_FACTOR, 4,
                    UserHandle.USER_CURRENT);
            mSmoothingEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.PULSE_SMOOTHING_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
            mRounded = Settings.Secure.getIntForUser(resolver, 
                    Settings.Secure.PULSE_SOLID_UNITS_ROUNDED, 0, UserHandle.USER_CURRENT) == 1;

            int units = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_SOLID_UNITS_COUNT, 32,
                    UserHandle.USER_CURRENT);
            if (units != mUnits) {
                stopAnimation(mUnits);
                mUnits = units;
                mFFTPoints = new float[mUnits * 4];
                if (mSmoothingEnabled) {
                    setupFFTAverage();
                }
                onSizeChanged(0, 0, 0, 0);
            }

            if (mSmoothingEnabled) {
                if (mFFTAverage == null) {
                    setupFFTAverage();
                }
            } else {
                mFFTAverage = null;
            }

            mUnitsOpacity= Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_SOLID_UNITS_OPACITY, 200,
                    UserHandle.USER_CURRENT);

            mPaint.setColor(ColorUtils.setAlphaComponent(mColor, mUnitsOpacity));
        }
    }

    private void setupFFTAverage() {
        mFFTAverage = new FFTAverage[mUnits];
        for (int i = 0; i < mUnits; i++) {
            mFFTAverage[i] = new FFTAverage();
        }
    }
}
