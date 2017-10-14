/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2017 The LineageOS Project
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

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.ThemeConfig;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

import com.android.systemui.statusbar.policy.BatteryController;

import cyanogenmod.providers.CMSettings;

import org.cyanogenmod.graphics.drawable.StopMotionVectorDrawable;

public class BatteryMeterDrawable extends Drawable implements
        BatteryController.BatteryStateChangeCallback {

    public static final String TAG = BatteryMeterDrawable.class.getSimpleName();
    public static final String SHOW_PERCENT_SETTING = "status_bar_show_battery_percent";

    private static final boolean SINGLE_DIGIT_PERCENT = false;

    // Values for the different battery styles
    public static final int BATTERY_STYLE_PORTRAIT  = 0;
    public static final int BATTERY_STYLE_SOLID     = 1;
    public static final int BATTERY_STYLE_CIRCLE    = 2;
    public static final int BATTERY_STYLE_HIDDEN    = 4;
    public static final int BATTERY_STYLE_LANDSCAPE = 5;
    public static final int BATTERY_STYLE_TEXT      = 6;

    private final int[] mColors;
    private final int mIntrinsicWidth;
    private final int mIntrinsicHeight;

    private boolean mShowPercent;
    private int mIconTint = Color.WHITE;
    private float mOldDarkIntensity = 0f;

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mCriticalLevel;

    private int mStyle;
    private boolean mBoltOverlay;

    private BatteryController mBatteryController;
    private boolean mPowerSaveEnabled;

    private int mDarkModeBatteryMeterFrameColor;
    private int mDarkModeFillColor;

    private int mLightModeBatteryMeterFrameColor;
    private int mLightModeFillColor;

    private final SettingObserver mSettingObserver = new SettingObserver();

    private final Context mContext;
    private final Handler mHandler;

    private int mLevel = -1;
    private boolean mPluggedIn;

    private float mTextX, mTextY; // precalculated position for drawText() to appear centered

    private boolean mInitialized;

    private Paint mTextAndBoltPaint;
    private Paint mWarningTextPaint;
    private Paint mClearPaint;

    private LayerDrawable mBatteryDrawable;
    private Drawable mFrameDrawable;
    private StopMotionVectorDrawable mLevelDrawable;
    private Drawable mBoltDrawable;
    private ValueAnimator mAnimator;

    private int mTextGravity;

    private int mCurrentBackgroundColor = 0;
    private int mCurrentFillColor = 0;

    public BatteryMeterDrawable(Context context, Handler handler, int frameColor) {
        // Portrait is the default drawable style
        this(context, handler, frameColor, BATTERY_STYLE_PORTRAIT, false);
    }

    public BatteryMeterDrawable(Context context, Handler handler, int frameColor, int style) {
        this(context, handler, frameColor, style, false);
    }

    public BatteryMeterDrawable(Context context, Handler handler, int frameColor, int style, boolean boltOverlay) {
        mContext = context;
        mHandler = handler;
        mStyle = style;
        mBoltOverlay = boltOverlay;
        final Resources res = context.getResources();
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2*N];
        for (int i=0; i<N; i++) {
            mColors[2*i] = levels.getInt(i, 0);
            mColors[2*i+1] = colors.getColor(i, 0);
        }
        levels.recycle();
        colors.recycle();
        updateShowPercent();
        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);
        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);

        loadBatteryDrawables(res, style);

        // Load text gravity and blend mode
        final int[] attrs = new int[] { android.R.attr.gravity, R.attr.blendMode };
        final int resId = getBatteryDrawableStyleResourceForStyle(style);
        PorterDuff.Mode xferMode = PorterDuff.Mode.XOR;
        if (resId != 0) {
            TypedArray a = mContext.obtainStyledAttributes(resId, attrs);
            mTextGravity = a.getInt(0, Gravity.CENTER);
            if (mBoltOverlay) {
                xferMode = PorterDuff.Mode.OVERLAY;
            } else {
                xferMode = PorterDuff.intToMode(a.getInt(1, PorterDuff.modeToInt(PorterDuff.Mode.XOR)));
            }
            a.recycle();
        } else {
            mTextGravity = Gravity.CENTER;
        }

        mTextAndBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextAndBoltPaint.setTypeface(font);
        mTextAndBoltPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));
        mTextAndBoltPaint.setXfermode(new PorterDuffXfermode(xferMode));
        mTextAndBoltPaint.setColor(mBoltOverlay || mCurrentFillColor == 0 ? getBoltColor() : mCurrentFillColor);

        mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        font = Typeface.create("sans-serif", Typeface.BOLD);
        mWarningTextPaint.setTypeface(font);
        mWarningTextPaint.setTextAlign(getPaintAlignmentFromGravity(mTextGravity));

        mClearPaint = new Paint();
        mClearPaint.setColor(0);

        mDarkModeBatteryMeterFrameColor =
                context.getColor(R.color.batterymeter_frame_color_darkintensity);
        mDarkModeFillColor = context.getColor(R.color.battery_charge_level_on_light_statusbar);
        mLightModeBatteryMeterFrameColor =
                context.getColor(R.color.batterymeter_frame_color);
        mLightModeFillColor = context.getColor(R.color.battery_charge_level_on_normal_statusbar);

        mIntrinsicWidth = context.getResources().getDimensionPixelSize(R.dimen.battery_width);
        mIntrinsicHeight = context.getResources().getDimensionPixelSize(R.dimen.battery_height);
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicWidth;
    }

    public void startListening() {
        mContext.getContentResolver().registerContentObserver(
                CMSettings.System.getUriFor(CMSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT),
                false, mSettingObserver);
        updateShowPercent();
        mBatteryController.addStateChangedCallback(this);
    }

    public void stopListening() {
        mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
        mBatteryController.removeStateChangedCallback(this);
    }

    public void disableShowPercent() {
        mShowPercent = false;
        postInvalidate();
    }

    private void postInvalidate() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                invalidateSelf();
            }
        });
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mPowerSaveEnabled = mBatteryController.isPowerSave();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        mPluggedIn = pluggedIn;

        if (charging && (mStyle == BATTERY_STYLE_CIRCLE ||
                mStyle == BATTERY_STYLE_SOLID)) {
            animateCircleBattery();
        }

        postInvalidate();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSaveEnabled = isPowerSave;
        invalidateSelf();
    }

    private static float[] loadBoltPoints(Resources res) {
        final int[] pts = res.getIntArray(R.array.batterymeter_bolt_points);
        int maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            maxX = Math.max(maxX, pts[i]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        final float[] ptsF = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            ptsF[i] = (float)pts[i] / maxX;
            ptsF[i + 1] = (float)pts[i + 1] / maxY;
        }
        return ptsF;
    }

    private static float[] loadPlusPoints(Resources res) {
        final int[] pts = res.getIntArray(R.array.batterymeter_plus_points);
        int maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            maxX = Math.max(maxX, pts[i]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        final float[] ptsF = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            ptsF[i] = (float) pts[i] / maxX;
            ptsF[i + 1] = (float) pts[i + 1] / maxY;
        }
        return ptsF;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        mHeight = bottom - top;
        mWidth = right - left;
    }

    private void updateShowPercent() {
        mShowPercent = CMSettings.System.getInt(mContext.getContentResolver(),
                CMSettings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0) == 1;
    }

    private int getColorForLevel(int percent) {
        return getColorForLevel(percent, false);
    }

    private int getColorForLevel(int percent, boolean isChargeLevel) {
        if (mBoltOverlay) {
            if (mPowerSaveEnabled || percent > mColors[0]) {
                if (isChargeLevel) {
                    return mColors[mColors.length-1];
                } else {
                    return getBoltColor();
                }
            } else {
                if ((mStyle == BATTERY_STYLE_SOLID || mStyle == BATTERY_STYLE_CIRCLE)
                       && !mPluggedIn) {
                    return mColors[1];
                } else if (!isChargeLevel) {
                    return getBoltColor();
                }
            }
        }
        // If we are in power save mode, always use the normal color.
        if (mPowerSaveEnabled) {
            return mColors[mColors.length - 1];
        }
        int thresh = 0;
        int color = 0;
        for (int i = 0; i < mColors.length; i += 2) {
            thresh = mColors[i];
            color = mColors[i + 1];
            if (percent <= thresh) {

                // Respect tinting for "normal" level
                if (i == mColors.length - 2) {
                    return mIconTint;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    public void animateCircleBattery() {
        if (mAnimator != null) mAnimator.cancel();

        final int defaultAlpha = mLevelDrawable.getAlpha();
        mAnimator = ValueAnimator.ofInt(defaultAlpha, 0, defaultAlpha);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mLevelDrawable.setAlpha((int) animation.getAnimatedValue());
                invalidateSelf();
            }
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                mLevelDrawable.setAlpha(defaultAlpha);
                mAnimator = null;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mLevelDrawable.setAlpha(defaultAlpha);
                mAnimator = null;
            }
        });
        mAnimator.setDuration(2000);
        mAnimator.start();
    }

    public void setDarkIntensity(float darkIntensity) {
        if (darkIntensity == mOldDarkIntensity) {
            return;
        }
        mCurrentBackgroundColor = getBackgroundColor(darkIntensity);
        mCurrentFillColor = getFillColor(darkIntensity);
        mIconTint = mCurrentFillColor;
        mFrameDrawable.setTint(mCurrentBackgroundColor);
        // Make bolt fully opaque for increased visibility
        if (mBoltDrawable != null) {
            mBoltDrawable.setTint(0xff000000 | mCurrentFillColor);
            updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
        }
        invalidateSelf();
        mOldDarkIntensity = darkIntensity;
    }

    private int getBackgroundColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeBatteryMeterFrameColor, mDarkModeBatteryMeterFrameColor);
    }

    private int getFillColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeFillColor, mDarkModeFillColor);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    @Override
    public void draw(Canvas c) {
        if (!mInitialized) {
            init();
        }

        drawBattery(c);
    }

    // Some stuff required by Drawable.
    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            updateShowPercent();
            postInvalidate();
        }
    }

    private void loadBatteryDrawables(Resources res, int style) {
        if (isThemeApplied()) {
            try {
                checkBatteryMeterDrawableValid(res, style);
            } catch (BatteryMeterDrawableException e) {
                Log.w(TAG, "Invalid themed battery meter drawable, falling back to system", e);
            }
        }

        final int drawableResId = getBatteryDrawableResourceForStyle(style);
        mBatteryDrawable = (LayerDrawable) mContext.getDrawable(drawableResId);
        mFrameDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_frame);
        mFrameDrawable.setTint(mCurrentBackgroundColor != 0
                ? mCurrentBackgroundColor : res.getColor(R.color.batterymeter_frame_color));
        // Set the animated vector drawable we will be stop-animating
        final Drawable levelDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_fill);
        mLevelDrawable = new StopMotionVectorDrawable(levelDrawable);
        mBoltDrawable = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        if (mBoltDrawable != null) {
            mBoltDrawable.setTint(getBoltColor());
        }
    }

    private boolean isThemeApplied() {
        final ThemeConfig themeConfig = ThemeConfig.getBootTheme(mContext.getContentResolver());
        return themeConfig != null &&
                !ThemeConfig.SYSTEM_DEFAULT.equals(themeConfig.getOverlayForStatusBar());
    }

    private void checkBatteryMeterDrawableValid(Resources res, int style) {
        final int resId = getBatteryDrawableResourceForStyle(style);
        final Drawable batteryDrawable;
        try {
            batteryDrawable = mContext.getDrawable(resId);
        } catch (Resources.NotFoundException e) {
            throw new BatteryMeterDrawableException(res.getResourceName(resId) + " is an " +
                    "invalid drawable", e);
        }

        // Check that the drawable is a LayerDrawable
        if (!(batteryDrawable instanceof LayerDrawable)) {
            throw new BatteryMeterDrawableException("Expected a LayerDrawable but received a " +
                    batteryDrawable.getClass().getSimpleName());
        }

        final LayerDrawable layerDrawable = (LayerDrawable) batteryDrawable;
        final Drawable frame = layerDrawable.findDrawableByLayerId(R.id.battery_frame);
        final Drawable level = layerDrawable.findDrawableByLayerId(R.id.battery_fill);
        // Now, check that the required layers exist and are of the correct type
        if (frame == null) {
            throw new BatteryMeterDrawableException("Missing battery_frame drawble");
        }
        if (level != null) {
            // Check that the level drawable is an AnimatedVectorDrawable
            if (!(level instanceof AnimatedVectorDrawable)) {
                throw new BatteryMeterDrawableException("Expected a AnimatedVectorDrawable " +
                        "but received a " + level.getClass().getSimpleName());
            }
            // Make sure we can stop-motion animate the level drawable
            try {
                StopMotionVectorDrawable smvd = new StopMotionVectorDrawable(level);
                smvd.setCurrentFraction(0.5f);
            } catch (Exception e) {
                throw new BatteryMeterDrawableException("Unable to perform stop motion on " +
                        "battery_fill drawable", e);
            }
        } else {
            throw new BatteryMeterDrawableException("Missing battery_fill drawable");
        }
    }

    private int getBatteryDrawableResourceForStyle(final int style) {
        switch (style) {
            case BATTERY_STYLE_LANDSCAPE:
                return R.drawable.ic_battery_landscape;
            case BATTERY_STYLE_SOLID:
                return R.drawable.ic_battery_solid;
            case BATTERY_STYLE_CIRCLE:
                return R.drawable.ic_battery_circle;
            case BATTERY_STYLE_PORTRAIT:
                return R.drawable.ic_battery_portrait;
            default:
                return 0;
        }
    }

    private int getBatteryDrawableStyleResourceForStyle(final int style) {
        switch (style) {
            case BATTERY_STYLE_LANDSCAPE:
                return R.style.BatteryMeterViewDrawable_Landscape;
            case BATTERY_STYLE_SOLID:
                return R.style.BatteryMeterViewDrawable_Solid;
            case BATTERY_STYLE_CIRCLE:
                return R.style.BatteryMeterViewDrawable_Circle;
            case BATTERY_STYLE_PORTRAIT:
                return R.style.BatteryMeterViewDrawable_Portrait;
            default:
                return R.style.BatteryMeterViewDrawable;
        }
    }

    private int getBoltColor() {
        if (mBoltOverlay) {
            return mContext.getResources().getColor((mStyle == BATTERY_STYLE_SOLID ||
                mStyle == BATTERY_STYLE_CIRCLE) ? R.color.batterymeter_bolt_color : R.color.system_primary_color);
        }
        return mContext.getResources().getColor(R.color.batterymeter_bolt_color);
    }

    /**
     * Initializes all size dependent variables
     */
    private void init() {
        // Not much we can do with zero width or height, we'll get another pass later
        if (mWidth <= 0 || mHeight <= 0) return;

        final float widthDiv2 = mWidth / 2f;
        // text size is width / 2 - 2dp for wiggle room
        final float textSize;
        switch(mStyle) {
            case BATTERY_STYLE_SOLID:
            case BATTERY_STYLE_CIRCLE:
                textSize = widthDiv2 - mContext.getResources().getDisplayMetrics().density / 1.3f;
                break;
            case BATTERY_STYLE_LANDSCAPE:
                textSize = widthDiv2 * 1.3f;
                break;
            default:
                textSize = widthDiv2;
                break;
        }
        mTextAndBoltPaint.setTextSize(textSize);
        mWarningTextPaint.setTextSize(textSize);

        Rect iconBounds = new Rect(0, 0, mWidth, mHeight);
        mBatteryDrawable.setBounds(iconBounds);

        // Calculate text position
        Rect bounds = new Rect();
        mTextAndBoltPaint.getTextBounds("99", 0, "99".length(), bounds);
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // Compute mTextX based on text gravity
        if ((mTextGravity & Gravity.START) == Gravity.START) {
            mTextX = isRtl ? mWidth : 0;
        } else if ((mTextGravity & Gravity.END) == Gravity.END) {
            mTextX = isRtl ? 0 : mWidth;
        } else if ((mTextGravity & Gravity.LEFT) == Gravity.LEFT) {
            mTextX = 0;
        } else if ((mTextGravity & Gravity.RIGHT) == Gravity.RIGHT) {
            mTextX = mWidth;
        } else {
            mTextX = widthDiv2;
        }

        // Compute mTextY based on text gravity
        if ((mTextGravity & Gravity.TOP) == Gravity.TOP) {
            mTextY = bounds.height();
        } else if ((mTextGravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            mTextY = mHeight;
        } else {
            mTextY = widthDiv2 + bounds.height() / 2.0f;
        }

        if (mBoltDrawable != null) {
            updateBoltDrawableLayer(mBatteryDrawable, mBoltDrawable);
        }

        mInitialized = true;
    }

    // Creates a BitmapDrawable of the bolt so we can make use of
    // the XOR xfer mode with vector-based drawables
    private void updateBoltDrawableLayer(LayerDrawable batteryDrawable, Drawable boltDrawable) {
        BitmapDrawable newBoltDrawable;
        if (boltDrawable instanceof BitmapDrawable) {
            newBoltDrawable = (BitmapDrawable) boltDrawable.mutate();
        } else {
            Bitmap boltBitmap = createBoltBitmap(boltDrawable);
            if (boltBitmap == null) {
                // Not much to do with a null bitmap so keep original bolt for now
                return;
            }
            Rect bounds = boltDrawable.getBounds();
            newBoltDrawable = new BitmapDrawable(mContext.getResources(), boltBitmap);
            newBoltDrawable.setBounds(bounds);
        }
        newBoltDrawable.getPaint().set(mTextAndBoltPaint);
        if (mBoltOverlay && mBoltDrawable != null) {
            newBoltDrawable.setTint(getBoltColor());
        }
        batteryDrawable.setDrawableByLayerId(R.id.battery_charge_indicator, newBoltDrawable);
    }

    private Bitmap createBoltBitmap(Drawable boltDrawable) {
        // Not much we can do with zero width or height, we'll get another pass later
        if (mWidth <= 0 || mHeight <= 0) return null;

        Bitmap bolt;
        if (!(boltDrawable instanceof BitmapDrawable)) {
            Rect iconBounds = new Rect(0, 0, mWidth, mHeight);
            bolt = Bitmap.createBitmap(iconBounds.width(), iconBounds.height(),
                    Bitmap.Config.ARGB_8888);
            if (bolt != null) {
                Canvas c = new Canvas(bolt);
                c.drawColor(-1, PorterDuff.Mode.CLEAR);
                boltDrawable.draw(c);
            }
        } else {
            bolt = ((BitmapDrawable) boltDrawable).getBitmap();
        }

        return bolt;
    }

    private void drawBattery(Canvas canvas) {
        final int level = mLevel;

        mTextAndBoltPaint.setColor(getColorForLevel(level));

        // Make sure we don't draw the charge indicator if not plugged in
        final Drawable d = mBatteryDrawable.findDrawableByLayerId(R.id.battery_charge_indicator);
        if (d != null) {
            if (d instanceof BitmapDrawable) {
                // In case we are using a BitmapDrawable, which we should be unless something bad
                // happened, we need to change the paint rather than the alpha in case the blendMode
                // has been set to clear.  Clear always clears regardless of alpha level ;)
                final BitmapDrawable bd = (BitmapDrawable) d;
                bd.getPaint().set(mPluggedIn ? mTextAndBoltPaint : mClearPaint);
                if (mBoltOverlay && mBoltDrawable != null) {
                    mBoltDrawable.setTint(getBoltColor());
                }
            } else {
                d.setAlpha(mPluggedIn ? 255 : 0);
            }
        }

        // Now draw the level indicator
        // Set the level and tint color of the fill drawable
        mLevelDrawable.setCurrentFraction(level / 100f);
        mLevelDrawable.setTint(getColorForLevel(level, true));
        mBatteryDrawable.draw(canvas);

        // If chosen by options, draw percentage text in the middle
        // Always skip percentage when 100, so layout doesnt break
        if (!mPluggedIn) {
            drawPercentageText(canvas);
        }
    }

    private void drawPercentageText(Canvas canvas) {
        final int level = mLevel;
        if ((level > mCriticalLevel || mPowerSaveEnabled) && mShowPercent && level != 100 && !mBoltOverlay) {
            // Draw the percentage text
            String pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level);
            mTextAndBoltPaint.setColor(getColorForLevel(level));
            canvas.drawText(pctText, mTextX, mTextY, mTextAndBoltPaint);
            if (mBoltDrawable != null) {
                mBoltDrawable.setTint(getBoltColor());
            }
        } else if (level <= mCriticalLevel) {
            // Draw the warning text
            mWarningTextPaint.setColor(mPowerSaveEnabled
                    ? mColors[mColors.length - 1]
                    : mColors[1]);
            canvas.drawText(mWarningString, mTextX, mTextY, mWarningTextPaint);
        }
    }

    private Paint.Align getPaintAlignmentFromGravity(int gravity) {
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if ((gravity & Gravity.START) == Gravity.START) {
            return isRtl ? Paint.Align.RIGHT : Paint.Align.LEFT;
        }
        if ((gravity & Gravity.END) == Gravity.END) {
            return isRtl ? Paint.Align.LEFT : Paint.Align.RIGHT;
        }
        if ((gravity & Gravity.LEFT) == Gravity.LEFT) return Paint.Align.LEFT;
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) return Paint.Align.RIGHT;

        // Default to center
        return Paint.Align.CENTER;
    }

    private class BatteryMeterDrawableException extends RuntimeException {
        public BatteryMeterDrawableException(String detailMessage) {
            super(detailMessage);
        }

        public BatteryMeterDrawableException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }
    }
}
