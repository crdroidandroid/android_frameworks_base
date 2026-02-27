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
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;

import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.tuner.TunerService;

public class ClockStyle extends RelativeLayout implements TunerService.Tunable {

    private static final int[] CLOCK_LAYOUTS = {
            0,
            R.layout.keyguard_clock_oos,
            R.layout.keyguard_clock_center,
            R.layout.keyguard_clock_simple,
            R.layout.keyguard_clock_miui,
            R.layout.keyguard_clock_ide,
            R.layout.keyguard_clock_moto,
            R.layout.keyguard_clock_label,
            R.layout.keyguard_clock_ios, 
            R.layout.keyguard_clock_num,
            R.layout.keyguard_clock_taden,
            R.layout.keyguard_clock_mont,
            R.layout.keyguard_clock_accent,
            R.layout.keyguard_clock_nos1,
            R.layout.keyguard_clock_nos2,
            R.layout.keyguard_clock_life,
            R.layout.keyguard_clock_word,
            R.layout.keyguard_clock_encode,
            R.layout.keyguard_clock_block,
            R.layout.keyguard_clock_bubble,
            R.layout.keyguard_clock_nos3,
            R.layout.keyguard_clock_analog,
            R.layout.keyguard_clock_a9
    };

    private static final int[] mCenterClocks = {2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22};

    private static final int DEFAULT_STYLE = 0; // Disabled
    public static final String CLOCK_STYLE_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE;
    public static final String CLOCK_TEXT_COLOR_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_ACCENT_COLOR;
    public static final String CLOCK_TEXT_OPACITY_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_OPACITY;
    public static final String CLOCK_FRAME_MARGIN_TOP_KEY = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_MARGIN_TOP;

    private static final int DEFAULT_OPACITY = 100;
    private static final int DEFAULT_MARGIN_TOP = 15;

    private final Context mContext;
    private final KeyguardManager mKeyguardManager;
    private final TunerService mTunerService;

    private View currentClockView;
    private int mClockStyle;
    private boolean mUseAccentColor = false;
    private int mClockOpacity = DEFAULT_OPACITY;
    private int mClockFrameMarginTop = DEFAULT_MARGIN_TOP;

    private static final long UPDATE_INTERVAL_MILLIS = 15 * 1000;
    private long lastUpdateTimeMillis = 0;

    private final StatusBarStateController mStatusBarStateController;

    private boolean mDozing;

    // Burn-in protection
    private static final int BURN_IN_PROTECTION_INTERVAL = 10000; // 10 seconds
    private static final int BURN_IN_PROTECTION_MAX_SHIFT = 4; // 4 pixels
    private final Handler mBurnInProtectionHandler = new Handler();
    private int mCurrentShiftX = 0;
    private int mCurrentShiftY = 0;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mKeyguardManager != null 
                && mKeyguardManager.isKeyguardLocked()) {
                onTimeChanged();
            }
        }
    };

    private final Runnable mBurnInProtectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDozing) {
                mCurrentShiftX = (int) (Math.random() * BURN_IN_PROTECTION_MAX_SHIFT * 2) - BURN_IN_PROTECTION_MAX_SHIFT;
                mCurrentShiftY = (int) (Math.random() * BURN_IN_PROTECTION_MAX_SHIFT * 2) - BURN_IN_PROTECTION_MAX_SHIFT;
                if (currentClockView != null) {
                    currentClockView.setTranslationX(mCurrentShiftX);
                    currentClockView.setTranslationY(mCurrentShiftY);
                }
                invalidate();
                mBurnInProtectionHandler.postDelayed(this, BURN_IN_PROTECTION_INTERVAL);
            }
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {}

        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;
            if (mDozing) {
                startBurnInProtection();
            } else {
                stopBurnInProtection();
            }
        }
    };

    public ClockStyle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        mTunerService = Dependency.get(TunerService.class);
        mTunerService.addTunable(this, CLOCK_STYLE_KEY, CLOCK_TEXT_COLOR_KEY,
                CLOCK_TEXT_OPACITY_KEY, CLOCK_FRAME_MARGIN_TOP_KEY);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction("com.android.systemui.doze.pulse");
        mContext.registerReceiver(mScreenReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        updateClockView();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mTunerService.removeTunable(this);
        mBurnInProtectionHandler.removeCallbacks(mBurnInProtectionRunnable);
        mContext.unregisterReceiver(mScreenReceiver);
    }

    private void startBurnInProtection() {
        if (mClockStyle == 0) return;
        mBurnInProtectionHandler.post(mBurnInProtectionRunnable);
    }

    private void stopBurnInProtection() {
        if (mClockStyle == 0) return;
        mBurnInProtectionHandler.removeCallbacks(mBurnInProtectionRunnable);
        if (currentClockView != null) {
            currentClockView.setTranslationX(0);
            currentClockView.setTranslationY(0);
        }
    }

    private void updateTextClockViews(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View childView = viewGroup.getChildAt(i);
                updateTextClockViews(childView);
                if (childView instanceof TextClock) {
                    ((TextClock) childView).refreshTime();
                }
            }
        }
    }

    public void onTimeChanged() {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - lastUpdateTimeMillis >= UPDATE_INTERVAL_MILLIS) {
            if (currentClockView != null) {
                updateTextClockViews(currentClockView);
                lastUpdateTimeMillis = currentTimeMillis;
            }
        }
    }

    private void updateClockTextColor() {
        if (currentClockView != null) {
            updateTextClockColor(currentClockView);
        }
    }

    private void updateClockFrameMargin() {
        RelativeLayout clockFrame = findViewById(R.id.clock_frame);
        if (clockFrame != null) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) clockFrame.getLayoutParams();
            int marginPx = (int) (mClockFrameMarginTop * mContext.getResources().getDisplayMetrics().density);
            params.topMargin = marginPx;
            clockFrame.setLayoutParams(params);
        }
    }

    private void updateTextClockColor(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View childView = viewGroup.getChildAt(i);
                updateTextClockColor(childView);
            }
        }

        if (view instanceof TextClock) {
            TextClock textClock = (TextClock) view;

            if (textClock.getTag(R.id.original_text_color) == null) {
                int currentColor = textClock.getCurrentTextColor();
                textClock.setTag(R.id.original_text_color, currentColor);
            }

            int originalColor = (Integer) textClock.getTag(R.id.original_text_color);
            int whiteColor = mContext.getColor(android.R.color.white);

            if ((originalColor & 0x00FFFFFF) == (whiteColor & 0x00FFFFFF)) {
                int color;
                if (mUseAccentColor) {
                    color = mContext.getColor(
                        mContext.getResources().getIdentifier(
                            "system_accent1_100", "color", "android"));
                } else {
                    color = mContext.getColor(android.R.color.white);
                }
                int alpha = Math.round((mClockOpacity / 100f) * 255);
                color = (color & 0x00FFFFFF) | (alpha << 24);
                textClock.setTextColor(color);
            }
        }
    }

    private void updateClockView() {
        if (currentClockView != null) {
            ((ViewGroup) currentClockView.getParent()).removeView(currentClockView);
            currentClockView = null;
        }
        if (mClockStyle > 0 && mClockStyle < CLOCK_LAYOUTS.length) {
            ViewStub stub = findViewById(R.id.clock_view_stub);
            if (stub != null) {
                stub.setLayoutResource(CLOCK_LAYOUTS[mClockStyle]);
                currentClockView = stub.inflate();
                int gravity = isCenterClock(mClockStyle) ? Gravity.CENTER : Gravity.START;
                if (currentClockView instanceof LinearLayout) {
                    ((LinearLayout) currentClockView).setGravity(gravity);
                }
                updateClockTextColor();
                updateClockFrameMargin();
            }
        }
        onTimeChanged();
        setVisibility(mClockStyle != 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case CLOCK_STYLE_KEY:
                mClockStyle = TunerService.parseInteger(newValue, DEFAULT_STYLE);
                if (mClockStyle != 0) {
                    Settings.Secure.putIntForUser(mContext.getContentResolver(), 
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, 0, UserHandle.USER_CURRENT);
                }
                updateClockView();
                break;
            case CLOCK_TEXT_COLOR_KEY:
                mUseAccentColor = TunerService.parseIntegerSwitch(newValue, false);
                updateClockTextColor();
                break;
            case CLOCK_TEXT_OPACITY_KEY:
                mClockOpacity = TunerService.parseInteger(newValue, DEFAULT_OPACITY);
                // Keep opacity within valid range (0-100)
                mClockOpacity = Math.max(0, Math.min(100, mClockOpacity));
                updateClockTextColor();
                break;
            case CLOCK_FRAME_MARGIN_TOP_KEY:
                mClockFrameMarginTop = TunerService.parseInteger(newValue, DEFAULT_MARGIN_TOP);
                mClockFrameMarginTop = Math.max(0, Math.min(100, mClockFrameMarginTop));
                updateClockFrameMargin();
                break;
        }
    }

    private boolean isCenterClock(int clockStyle) {
        for (int centerClock : mCenterClocks) {
            if (centerClock == clockStyle) {
                return true;
            }
        }
        return false;
    }
}
