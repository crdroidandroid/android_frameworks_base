/**
 * Copyright 2011, Felix Palmer
 * Copyright (C) 2014 The TeamEos Project
 * Copyright (C) 2016-2022 crDroid Android Project
 *
 * AOSP Navigation implementation by
 * @author: Randall Rushing <randall.rushing@gmail.com>
 *
 * Licensed under the MIT license:
 * http://creativecommons.org/licenses/MIT/
 *
 * Old school FFT renderer adapted from
 * @link https://github.com/felixpalmer/android-visualizer
 *
 */

package com.android.systemui.pulse;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.Bitmap.Config;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;

public class FadingBlockRenderer extends Renderer {
    //private static final int DEF_PAINT_ALPHA = (byte) 188;
    private static final int DBFUZZ = 2;
    private byte[] mFFTBytes;
    private Paint mPaint;
    private Paint mFadePaint;
    private boolean mVertical;
    private boolean mLeftInLandscape;
    private FFTAverage[] mFFTAverage;
    private float[] mFFTPoints;
    private byte rfk, ifk;
    private int dbValue;
    private float magnitude;
    private int mDivisions;
    private int mDbFuzzFactor;
    private int mPathEffect1;
    private int mPathEffect2;
    private Bitmap mCanvasBitmap;
    private Canvas mCanvas;
    private Matrix mMatrix;
    private int mWidth;
    private int mHeight;

    private LegacySettingsObserver mObserver;
    private boolean mSmoothingEnabled;

    public FadingBlockRenderer(Context context, Handler handler, PulseView view,
            PulseControllerImpl controller, ColorController colorController) {
        super(context, handler, view, colorController);
        mObserver = new LegacySettingsObserver(handler);
        mPaint = new Paint();
        mFadePaint = new Paint();
        mFadePaint.setColor(Color.argb(200, 255, 255, 255));
        mFadePaint.setXfermode(new PorterDuffXfermode(Mode.MULTIPLY));
        mMatrix = new Matrix();
        mObserver.updateSettings();
        mPaint.setAntiAlias(true);
        onSizeChanged(0, 0, 0, 0);
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
    public void onFFTUpdate(byte[] bytes) {
        if (bytes == null || mDivisions <= 0) return;
        int fudgeFactor = mKeyguardShowing ? mDbFuzzFactor * 4 : mDbFuzzFactor;
        mFFTBytes = bytes;
        if (mFFTPoints == null || mFFTPoints.length < mFFTBytes.length * 4) {
            mFFTPoints = new float[mFFTBytes.length * 4];
        }
        int divisionLength = mFFTBytes.length / mDivisions;
        if (divisionLength < 2) return;
        if (mSmoothingEnabled) {
            if (mFFTAverage == null || mFFTAverage.length != divisionLength) {
                setupFFTAverage(divisionLength);
            }
        } else {
            mFFTAverage = null;
        }
        int i = 0;
        for (; i < divisionLength; i++) {
            if (mVertical) {
                mFFTPoints[i * 4 + 1] = i * 4 * mDivisions;
                mFFTPoints[i * 4 + 3] = i * 4 * mDivisions;
            } else {
                mFFTPoints[i * 4] = i * 4 * mDivisions;
                mFFTPoints[i * 4 + 2] = i * 4 * mDivisions;
            }
            if ((mDivisions * i + 1) < mFFTBytes.length) {
                byte rfk = mFFTBytes[mDivisions * i];
                byte ifk = mFFTBytes[mDivisions * i + 1];
                float magnitude = (rfk * rfk + ifk * ifk);
                int dbValue = magnitude > 0 ? (int) (10 * Math.log10(magnitude)) : 0;
                if (mSmoothingEnabled) {
                    dbValue = mFFTAverage[i].average(dbValue);
                }
                if (mVertical) {
                    mFFTPoints[i * 4] = mLeftInLandscape ? 0 : mWidth;
                    mFFTPoints[i * 4 + 2] = mLeftInLandscape ? (dbValue * fudgeFactor + DBFUZZ)
                            : (mWidth - 1 * (dbValue * fudgeFactor + DBFUZZ));
                } else {
                    mFFTPoints[i * 4 + 1] = mHeight;
                    mFFTPoints[i * 4 + 3] = mHeight - 1 * (dbValue * fudgeFactor + DBFUZZ);
                }
            }
        }
        if (mCanvas != null && mFFTPoints != null) {
            mCanvas.drawLines(mFFTPoints, mPaint);
            mCanvas.drawPaint(mFadePaint);
        }
        postInvalidate();
    }

    private void setupFFTAverage(int size) {
        mFFTAverage = new FFTAverage[size];
        for (int i = 0; i < size; i++) {
            mFFTAverage[i] = new FFTAverage();
        }
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (mView.getWidth() > 0 && mView.getHeight() > 0) {
            mWidth = mView.getWidth();
            mHeight = mView.getHeight();
            mVertical = mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            mCanvasBitmap = Bitmap.createBitmap(mWidth, mHeight, Config.ARGB_8888);
            mCanvas = new Canvas(mCanvasBitmap);
        }
    }

    @Override
    public void setLeftInLandscape(boolean leftInLandscape) {
        if (mLeftInLandscape != leftInLandscape) {
            mLeftInLandscape = leftInLandscape;
            onSizeChanged(0, 0, 0, 0);
        }
    }

    @Override
    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mColorController.stopLavaLamp();
        mCanvasBitmap = null;
    }

    @Override
    public void onVisualizerLinkChanged(boolean linked) {
        if (!linked) {
            mColorController.stopLavaLamp();
        }
    }

    @Override
    public void onUpdateColor(int color) {
        mPaint.setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.scale(1, 1, mWidth / 2f, mHeight / 2f);
        canvas.drawBitmap(mCanvasBitmap, mMatrix, null);
    }

    private class LegacySettingsObserver extends ContentObserver {
        public LegacySettingsObserver(Handler handler) {
            super(handler);
            register();
        }

        void register() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_CUSTOM_DIMEN), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_CUSTOM_DIV), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_FILLED_BLOCK_SIZE), false,
                    this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_EMPTY_BLOCK_SIZE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_CUSTOM_FUDGE_FACTOR), false,
                    this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PULSE_SMOOTHING_ENABLED), false,
                    this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }

        public void updateSettings() {
            ContentResolver resolver = mContext.getContentResolver();
            final Resources res = mContext.getResources();

            int emptyBlock = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_EMPTY_BLOCK_SIZE, 1,
                    UserHandle.USER_CURRENT);
            int customDimen = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_CUSTOM_DIMEN, 14,
                    UserHandle.USER_CURRENT);
            int numDivision = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_CUSTOM_DIV, 16,
                    UserHandle.USER_CURRENT);
            int fudgeFactor = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_CUSTOM_FUDGE_FACTOR, 5,
                    UserHandle.USER_CURRENT);
            int filledBlock = Settings.Secure.getIntForUser(
                    resolver, Settings.Secure.PULSE_FILLED_BLOCK_SIZE, 4,
                    UserHandle.USER_CURRENT);

            mPathEffect1 = getLimitedDimenValue(filledBlock, 4, 8, res);
            mPathEffect2 = getLimitedDimenValue(emptyBlock, 0, 4, res);
            mPaint.setPathEffect(null);
            mPaint.setPathEffect(new android.graphics.DashPathEffect(new float[] {
                    mPathEffect1,
                    mPathEffect2
            }, 0));
            mPaint.setStrokeWidth(getLimitedDimenValue(customDimen, 1, 30, res));
            mDivisions = validateDivision(numDivision);
            mDbFuzzFactor = Math.max(2, Math.min(6, fudgeFactor));

            mSmoothingEnabled = Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.PULSE_SMOOTHING_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
        }
    }

    private static int getLimitedDimenValue(int val, int min, int max, Resources res) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Math.max(min, Math.min(max, val)), res.getDisplayMetrics());
    }

    private static int validateDivision(int val) {
        // if a bad value was passed from settings (not divisible by 2)
        // reset to default value of 16. Validate range.
        if (val % 2 != 0) {
            val = 16;
        }
        return Math.max(2, Math.min(44, val));
    }
}
