/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.clocks;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewStub;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.systemui.clocks.UserProfileUtils;
import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.tuner.TunerService;

public class ClockStyle extends RelativeLayout implements TunerService.Tunable {

    private static final int[] CLOCK_LAYOUTS = {
            0,
            R.layout.keyguard_clock_oos,           // 1
            R.layout.keyguard_clock_oos2,          // 2
            R.layout.keyguard_clock_center,        // 3
            R.layout.keyguard_clock_simple,        // 4
            R.layout.keyguard_clock_miui,          // 5
            R.layout.keyguard_clock_ide,           // 6
            R.layout.keyguard_clock_moto,          // 7
            R.layout.keyguard_clock_stylish,       // 8
            R.layout.keyguard_clock_stylish2,      // 9
            R.layout.keyguard_clock_stylish3,      // 10
            R.layout.keyguard_clock_stylish4,      // 11
            R.layout.keyguard_clock_stylish5,      // 12
            R.layout.keyguard_clock_stylish6,      // 13
            R.layout.keyguard_clock_stylish7,      // 14
            R.layout.keyguard_clock_stylish8,      // 15
            R.layout.keyguard_clock_stylish9,      // 16
            R.layout.keyguard_clock_stylish10,     // 17
            R.layout.keyguard_clock_word,          // 18
            R.layout.keyguard_clock_life,          // 19
            R.layout.keyguard_clock_a9,            // 20
            R.layout.keyguard_clock_nos1,          // 21
            R.layout.keyguard_clock_nos2,          // 22
            R.layout.keyguard_clock_num,           // 23
            R.layout.keyguard_clock_accent,        // 24
            R.layout.keyguard_clock_analog,        // 25
            R.layout.keyguard_clock_block,         // 26
            R.layout.keyguard_clock_bubble,        // 27
            R.layout.keyguard_clock_label,         // 28
            R.layout.keyguard_clock_ios,           // 29
            R.layout.keyguard_clock_taden,         // 30
            R.layout.keyguard_clock_mont,          // 31
            R.layout.keyguard_clock_encode,        // 32
            R.layout.keyguard_clock_nos3,          // 33
            R.layout.keyguard_anci_clock_outline,  // 34
            R.layout.keyguard_anci_clock_ovalium,  // 35
            R.layout.keyguard_anci_clock_rectangle,// 36
            R.layout.keyguard_anci_clock_wallet,   // 37
            R.layout.keyguard_anci_clockdate_clavicula, // 38
            R.layout.keyguard_anci_clockdate_kln,  // 39
            R.layout.keyguard_anci_clockdate_miring, // 40
            R.layout.keyguard_anci_clockdate_scapula, // 41
            R.layout.keyguard_anci_clockdate_sternum, // 42
            R.layout.keyguard_sparkCircle,         // 43
            R.layout.keyguard_sparkList,           // 44
    };

    private static final int[] mCenterClocks = {
        3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33,
        34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44
    };

    private static final int[] mNoColorClocks = {1, 2, 25, 26};

    public static final String CLOCK_STYLE_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE;
    public static final String CLOCK_COLOR_MODE_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_COLOR_MODE;
    public static final String CLOCK_CUSTOM_COLOR_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_CUSTOM_COLOR;
    public static final String CLOCK_TEXT_OPACITY_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_OPACITY;
    public static final String CLOCK_FRAME_MARGIN_TOP_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_MARGIN_TOP;
    public static final String CLOCK_SIZE_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SIZE;
    public static final String CLOCK_AOD_ANIM_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_AOD_ANIM;

    public static final String COLOR_MODE_DEFAULT = "default";
    public static final String COLOR_MODE_ACCENT = "accent";
    public static final String COLOR_MODE_CUSTOM = "custom";

    private static final int DEFAULT_STYLE = 0;
    private static final int DEFAULT_OPACITY = 100;
    private static final int DEFAULT_MARGIN_TOP = 15;
    private static final int DEFAULT_CUSTOM_COLOR = Color.WHITE;
    private static final int AOD_OPACITY_CAP = 70;
    private static final int DEFAULT_CLOCK_SIZE = 100;
    private static final int MIN_CLOCK_SIZE = 50;
    private static final int MAX_CLOCK_SIZE = 150;

    private static final long AOD_UPDATE_INTERVAL_MILLIS = 60_000L;
    private static final long UPDATE_INTERVAL_MILLIS = 15_000L;

    private static final int BURN_IN_PROTECTION_INTERVAL = 10_000;
    private static final int BURN_IN_PROTECTION_MAX_SHIFT = 4;

    private static final float AOD_SCALE_DOWN = 0.85f;
    private static final long AOD_ANIM_OUT_MS = 300L;
    private static final long AOD_ANIM_IN_MS  = 400L;

    private boolean mAodAnimEnabled = true;

    private final Context mContext;
    private final KeyguardManager mKeyguardManager;
    private final TunerService mTunerService;
    private final StatusBarStateController mStatusBarStateController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private View currentClockView;
    private ViewStub mClockStub;
    private ViewGroup mClockContainer;

    private int mClockStyle;
    private String mColorMode = COLOR_MODE_DEFAULT;
    private int mCustomColor = DEFAULT_CUSTOM_COLOR;
    private int mClockOpacity = DEFAULT_OPACITY;
    private int mClockFrameMarginTop = DEFAULT_MARGIN_TOP;
    private int mClockSizeScale = DEFAULT_CLOCK_SIZE;

    private long lastUpdateTimeMillis = 0;
    private boolean mDozing;
    private boolean mCallbacksRegistered = false;

    private int mCurrentShiftX = 0;
    private int mCurrentShiftY = 0;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                forceTimeUpdate();
            } else if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)) {
                onTimeChanged();
            } else if ("com.android.systemui.doze.pulse".equals(action)) {
                onTimeChanged();
            }
        }
    };

    private final Runnable mAodTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mDozing || mClockStyle == 0) return;
            forceTimeUpdate();
            long now = System.currentTimeMillis();
            long nextMinute = ((now / AOD_UPDATE_INTERVAL_MILLIS) + 1) * AOD_UPDATE_INTERVAL_MILLIS;
            mHandler.postDelayed(this, nextMinute - now);
        }
    };

    private final Runnable mBurnInProtectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mDozing) return;
            mCurrentShiftX = (int) (Math.random() * BURN_IN_PROTECTION_MAX_SHIFT * 2)
                    - BURN_IN_PROTECTION_MAX_SHIFT;
            mCurrentShiftY = (int) (Math.random() * BURN_IN_PROTECTION_MAX_SHIFT * 2)
                    - BURN_IN_PROTECTION_MAX_SHIFT;
            if (currentClockView != null) {
                currentClockView.setTranslationX(mCurrentShiftX);
                currentClockView.setTranslationY(mCurrentShiftY);
            }
            invalidate();
            mHandler.postDelayed(this, BURN_IN_PROTECTION_INTERVAL);
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {}

        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) return;
            mDozing = dozing;
            animateAodTransition(dozing);
            applyClockAlpha();
            applyTextClockColor(currentClockView != null ? currentClockView : ClockStyle.this);
            if (mDozing) {
                startBurnInProtection();
                startAodTick();
            } else {
                stopBurnInProtection();
                stopAodTick();
                forceTimeUpdate();
            }
        }
    };

    public ClockStyle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        mTunerService = Dependency.get(TunerService.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClockStub = findViewById(R.id.clock_view_stub);
        updateClockView();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mCallbacksRegistered) {
            mTunerService.addTunable(this,
                    CLOCK_STYLE_KEY,
                    CLOCK_COLOR_MODE_KEY,
                    CLOCK_CUSTOM_COLOR_KEY,
                    CLOCK_TEXT_OPACITY_KEY,
                    CLOCK_FRAME_MARGIN_TOP_KEY,
                    CLOCK_SIZE_KEY,
                    CLOCK_AOD_ANIM_KEY);
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mDozing = mStatusBarStateController.isDozing();
            mStatusBarStateListener.onDozingChanged(mDozing);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction("com.android.systemui.doze.pulse");
            mContext.registerReceiver(mScreenReceiver, filter, Context.RECEIVER_EXPORTED);
            mCallbacksRegistered = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mCallbacksRegistered) {
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mTunerService.removeTunable(this);
            mHandler.removeCallbacks(mBurnInProtectionRunnable);
            mHandler.removeCallbacks(mAodTickRunnable);
            mContext.unregisterReceiver(mScreenReceiver);
            mCallbacksRegistered = false;
        }
    }

    private void startAodTick() {
        if (mClockStyle == 0) return;
        mHandler.removeCallbacks(mAodTickRunnable);
        long now = System.currentTimeMillis();
        long nextMinute = ((now / AOD_UPDATE_INTERVAL_MILLIS) + 1) * AOD_UPDATE_INTERVAL_MILLIS;
        mHandler.postDelayed(mAodTickRunnable, nextMinute - now);
    }

    private void stopAodTick() {
        mHandler.removeCallbacks(mAodTickRunnable);
    }

    private void startBurnInProtection() {
        if (mClockStyle == 0) return;
        mHandler.removeCallbacks(mBurnInProtectionRunnable);
        mHandler.postDelayed(mBurnInProtectionRunnable, BURN_IN_PROTECTION_INTERVAL);
    }

    private void stopBurnInProtection() {
        if (mClockStyle == 0) return;
        mHandler.removeCallbacks(mBurnInProtectionRunnable);
        if (currentClockView != null) {
            currentClockView.setTranslationX(0);
            currentClockView.setTranslationY(0);
        }
    }

    private void updateTextClockViews(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                updateTextClockViews(vg.getChildAt(i));
            }
        }
        if (view instanceof TextClock) {
            TextClock tc = (TextClock) view;
            if (tc.getTag(R.id.original_typeface) != null) {
                tc.setTypeface((Typeface) tc.getTag(R.id.original_typeface));
            }
            tc.refreshTime();
        }
    }

    public void onTimeChanged() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateTimeMillis >= UPDATE_INTERVAL_MILLIS) {
            forceTimeUpdate();
        }
    }

    private void forceTimeUpdate() {
        if (currentClockView != null) {
            restoreAllFonts(currentClockView);
            updateTextClockViews(currentClockView);
            lastUpdateTimeMillis = System.currentTimeMillis();
        }
    }

    private void restoreAllFonts(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                restoreAllFonts(vg.getChildAt(i));
            }
        }
        if (view instanceof TextClock) {
            TextClock tc = (TextClock) view;
            if (tc.getTag(R.id.original_typeface) != null) {
                tc.setTypeface((Typeface) tc.getTag(R.id.original_typeface));
            }
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (tv.getTag(R.id.original_typeface) != null) {
                tv.setTypeface((Typeface) tv.getTag(R.id.original_typeface));
            }
        }
    }

    private int resolveClockColor() {
        switch (mColorMode) {
            case COLOR_MODE_ACCENT:
                return mContext.getColor(
                        mContext.getResources().getIdentifier(
                                "system_accent1_100", "color", "android"));
            case COLOR_MODE_CUSTOM:
                return mCustomColor;
            case COLOR_MODE_DEFAULT:
            default:
                return mContext.getColor(android.R.color.white);
        }
    }

    private void applyClockAlpha() {
        if (currentClockView == null) return;
        int effective = (mDozing && mClockOpacity > AOD_OPACITY_CAP) ? AOD_OPACITY_CAP : mClockOpacity;
        currentClockView.setAlpha(effective / 100f);
    }

    private void updateClockAppearance() {
        if (currentClockView == null) return;
        applyClockAlpha();
        applyTextClockColor(currentClockView);
        applyClockScaleAfterLayout(currentClockView);
    }

    private void updateClockTextColor() {
        if (currentClockView == null) return;
        applyTextClockColor(currentClockView);
    }

    private float getScaleFactor() {
        int clamped = Math.max(MIN_CLOCK_SIZE, Math.min(MAX_CLOCK_SIZE, mClockSizeScale));
        return clamped / 100f;
    }

    private void disableClippingOnParents(View view) {
        setClipChildren(false);
        setClipToPadding(false);

        View current = view;
        while (current != null && current != ClockStyle.this) {
            if (current instanceof ViewGroup) {
                ((ViewGroup) current).setClipChildren(false);
                ((ViewGroup) current).setClipToPadding(false);
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
    }

    private void applyClockScale() {
        if (currentClockView == null) return;
        float scale = getScaleFactor();
        currentClockView.setScaleX(scale);
        currentClockView.setScaleY(scale);
        currentClockView.setPivotX(currentClockView.getWidth() / 2f);
        currentClockView.setPivotY(0f);
        disableClippingOnParents(currentClockView);
    }

    private void animateAodTransition(boolean toAod) {
        if (currentClockView == null || !mAodAnimEnabled) return;
        currentClockView.animate().cancel();
        if (toAod) {
            currentClockView.animate()
                    .scaleX(AOD_SCALE_DOWN)
                    .scaleY(AOD_SCALE_DOWN)
                    .alpha(0f)
                    .setDuration(AOD_ANIM_OUT_MS)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                    .withEndAction(() -> {
                        applyClockAlpha();
                        currentClockView.setScaleX(AOD_SCALE_DOWN);
                        currentClockView.setScaleY(AOD_SCALE_DOWN);
                    })
                    .start();
        } else {
            currentClockView.setScaleX(AOD_SCALE_DOWN);
            currentClockView.setScaleY(AOD_SCALE_DOWN);
            float targetScale = getScaleFactor();
            currentClockView.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .alpha(mClockOpacity / 100f)
                    .setDuration(AOD_ANIM_IN_MS)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                    .start();
        }
    }

    private void applyClockScaleAfterLayout(final View view) {
        if (view == null) return;
        if (view.getWidth() > 0) {
            applyClockScale();
        } else {
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                        int ol, int ot, int or, int ob) {
                    v.removeOnLayoutChangeListener(this);
                    applyClockScale();
                }
            });
        }
    }

    private void preloadFonts(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                preloadFonts(vg.getChildAt(i));
            }
        }
        if (view instanceof TextClock) {
            TextClock tc = (TextClock) view;
            Typeface tf = tc.getTypeface();
            if (tf != null) {
                tc.setTag(R.id.original_typeface, tf);
                tc.setTypeface(tf);
            }
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Typeface tf = tv.getTypeface();
            if (tf != null) {
                tv.setTag(R.id.original_typeface, tf);
                tv.setTypeface(tf);
            }
        }
    }

    private void applyTextClockColor(View view) {
        if (view == null) return;
        if (isNoColorClock(mClockStyle)) return;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyTextClockColor(vg.getChildAt(i));
            }
        }
        if (!(view instanceof TextClock)) return;
        TextClock tc = (TextClock) view;
        
        if (tc.getTag(R.id.original_typeface) == null && tc.getTypeface() != null) {
            tc.setTag(R.id.original_typeface, tc.getTypeface());
        }
        
        if (tc.getTag(R.id.original_text_color) == null) {
            tc.setTag(R.id.original_text_color, tc.getCurrentTextColor());
        }
        
        if (tc.getTag(R.id.original_typeface) != null) {
            tc.setTypeface((Typeface) tc.getTag(R.id.original_typeface));
        }
        
        int originalColor = (Integer) tc.getTag(R.id.original_text_color);
        int whiteColor = mContext.getColor(android.R.color.white);
        boolean isWhiteOriginal = (originalColor & 0x00FFFFFF) == (whiteColor & 0x00FFFFFF);
        if (mDozing) {
            tc.setTextColor(whiteColor);
            return;
        }
        if (!isWhiteOriginal) {
            tc.setTextColor(originalColor);
            return;
        }
        tc.setTextColor(resolveClockColor());
    }

    private void updateClockFrameMargin() {
        View clockFrame = findViewById(R.id.clock_frame);
        if (clockFrame != null) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) clockFrame.getLayoutParams();
            int marginPx = (int) (mClockFrameMarginTop
                    * mContext.getResources().getDisplayMetrics().density);
            params.topMargin = marginPx;
            clockFrame.setLayoutParams(params);
        }
    }

    private void updateClockView() {
        if (currentClockView != null) {
            ViewParent parent = currentClockView.getParent();
            if (parent instanceof ViewGroup)
                ((ViewGroup) parent).removeView(currentClockView);
            currentClockView = null;
        }
        if (mClockStyle > 0 && mClockStyle < CLOCK_LAYOUTS.length) {
            if (mClockStub != null) {
                mClockStub.setLayoutResource(CLOCK_LAYOUTS[mClockStyle]);
                currentClockView = mClockStub.inflate();
                mClockContainer = (ViewGroup) currentClockView.getParent();
                mClockStub = null;
            } else if (mClockContainer != null) {
                currentClockView = LayoutInflater.from(mContext)
                        .inflate(CLOCK_LAYOUTS[mClockStyle], mClockContainer, false);
                mClockContainer.addView(currentClockView);
            }

            if (currentClockView != null) {
                preloadFonts(currentClockView);
                disableClippingOnParents(currentClockView);
            }
            
            if (currentClockView != null) {
                ImageView userProfileIcon = currentClockView.findViewById(R.id.user_profile_icon);
                if (userProfileIcon != null) {
                    userProfileIcon.setImageDrawable(UserProfileUtils.getUserProfileIcon(mContext));
                }
                TextView userNameView = currentClockView.findViewById(R.id.user_name);
                if (userNameView != null) {
                    userNameView.setText(UserProfileUtils.getUsername(mContext));
                }
                TextView deviceNameView = currentClockView.findViewById(R.id.device_name);
                if (deviceNameView != null) {
                    deviceNameView.setText(UserProfileUtils.getDeviceName());
                }
                int gravity = isCenterClock(mClockStyle) ? Gravity.CENTER : Gravity.START;
                if (currentClockView instanceof LinearLayout) {
                    ((LinearLayout) currentClockView).setGravity(gravity);
                }
                updateClockAppearance();
                updateClockFrameMargin();
            }
        }
        forceTimeUpdate();
        setVisibility(mClockStyle != 0 ? View.VISIBLE : View.GONE);
        if (mDozing && mClockStyle != 0) {
            startAodTick();
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case CLOCK_STYLE_KEY:
                mClockStyle = TunerService.parseInteger(newValue, DEFAULT_STYLE);
                if (mClockStyle != 0) {
                    Settings.Secure.putIntForUser(mContext.getContentResolver(),
                            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, 0,
                            UserHandle.USER_CURRENT);
                }
                updateClockView();
                break;
            case CLOCK_COLOR_MODE_KEY:
                mColorMode = (newValue != null) ? newValue : COLOR_MODE_DEFAULT;
                updateClockTextColor();
                break;
            case CLOCK_CUSTOM_COLOR_KEY:
                mCustomColor = TunerService.parseInteger(newValue, DEFAULT_CUSTOM_COLOR);
                if (COLOR_MODE_CUSTOM.equals(mColorMode)) {
                    updateClockTextColor();
                }
                break;
            case CLOCK_TEXT_OPACITY_KEY:
                mClockOpacity = TunerService.parseInteger(newValue, DEFAULT_OPACITY);
                mClockOpacity = Math.max(0, Math.min(100, mClockOpacity));
                applyClockAlpha();
                break;
            case CLOCK_FRAME_MARGIN_TOP_KEY:
                mClockFrameMarginTop = TunerService.parseInteger(newValue, DEFAULT_MARGIN_TOP);
                mClockFrameMarginTop = Math.max(0, Math.min(100, mClockFrameMarginTop));
                updateClockFrameMargin();
                break;
            case CLOCK_SIZE_KEY:
                mClockSizeScale = TunerService.parseInteger(newValue, DEFAULT_CLOCK_SIZE);
                mClockSizeScale = Math.max(MIN_CLOCK_SIZE, Math.min(MAX_CLOCK_SIZE, mClockSizeScale));
                applyClockScale();
                break;
            case CLOCK_AOD_ANIM_KEY:
                mAodAnimEnabled = TunerService.parseInteger(newValue, 1) != 0;
                break;
        }
    }

    private boolean isCenterClock(int clockStyle) {
        for (int centerClock : mCenterClocks) {
            if (centerClock == clockStyle) return true;
        }
        return false;
    }

    private boolean isNoColorClock(int clockStyle) {
        for (int noColorClock : mNoColorClocks) {
            if (noColorClock == clockStyle) return true;
        }
        return false;
    }
}
