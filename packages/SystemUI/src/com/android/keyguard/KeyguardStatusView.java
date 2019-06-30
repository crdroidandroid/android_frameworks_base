/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.v4.graphics.ColorUtils;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;
import com.android.systemui.tuner.TunerService;

import com.google.android.collect.Sets;

import java.util.Locale;

public class KeyguardStatusView extends GridLayout implements
        ConfigurationController.ConfigurationListener, View.OnLayoutChangeListener,
        TunerService.Tunable {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;

    private static final int FONT_NORMAL = 0;
    private static final int FONT_ITALIC = 1;
    private static final int FONT_BOLD = 2;
    private static final int FONT_BOLD_ITALIC = 3;
    private static final int FONT_LIGHT = 4;
    private static final int FONT_LIGHT_ITALIC = 5;
    private static final int FONT_THIN = 6;
    private static final int FONT_THIN_ITALIC = 7;
    private static final int FONT_CONDENSED = 8;
    private static final int FONT_CONDENSED_ITALIC = 9;
    private static final int FONT_CONDENSED_LIGHT = 10;
    private static final int FONT_CONDENSED_LIGHT_ITALIC = 11;
    private static final int FONT_CONDENSED_BOLD = 12;
    private static final int FONT_CONDENSED_BOLD_ITALIC = 13;
    private static final int FONT_MEDIUM = 14;
    private static final int FONT_MEDIUM_ITALIC = 15;
    private static final int FONT_BLACK = 16;
    private static final int FONT_BLACK_ITALIC = 17;
    private static final int FONT_DANCINGSCRIPT = 18;
    private static final int FONT_DANCINGSCRIPT_BOLD = 19;
    private static final int FONT_COMINGSOON = 20;
    private static final int FONT_NOTOSERIF = 21;
    private static final int FONT_NOTOSERIF_ITALIC = 22;
    private static final int FONT_NOTOSERIF_BOLD = 23;
    private static final int FONT_NOTOSERIF_BOLD_ITALIC = 24;

    private final LockPatternUtils mLockPatternUtils;
    private final IActivityManager mIActivityManager;
    private final float mSmallClockScale;

    private TextView mLogoutView;
    private CustomAnalogClock mCustomClockView;
    private CustomAnalogClock mCustomDarkClockView;
    private CustomAnalogClock mSpideyClockView;
    private CustomAnalogClock mSpectrumClockView;
    private CustomAnalogClock mSneekyClockView;
    private CustomAnalogClock mDotClockView;
    private LinearLayout mTextClock;
    private TextClock mClockView;
    private View mClockSeparator;
    private TextView mOwnerInfo;
    private KeyguardSliceView mKeyguardSlice;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;

    private ArraySet<View> mVisibleInDoze;
    private boolean mPulsing;
    private boolean mWasPulsing;
    private float mDarkAmount = 0;
    private int mTextColor;
    private float mWidgetPadding;
    private int mLastLayoutHeight;

    private boolean mForcedMediaDoze;

    private int mLockClockFontStyle;
    private int mLockDateFontStyle;
    private int mClockSelection = 1;
    private boolean mWasLatestViewSmall;
    private boolean mClockAvailable;
    private boolean mDigitalClock;

    private static final String LOCK_CLOCK_FONT_STYLE =
            "system:" + Settings.System.LOCK_CLOCK_FONT_STYLE;
    private static final String LOCK_DATE_FONT_STYLE =
            "system:" + Settings.System.LOCK_DATE_FONT_STYLE;
    private static final String LOCKSCREEN_CLOCK_SELECTION =
            "system:" + Settings.System.LOCKSCREEN_CLOCK_SELECTION;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refreshTime();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                updateOwnerInfo();
                updateLogoutView();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refreshFormat();
            updateOwnerInfo();
            updateLogoutView();
        }

        @Override
        public void onLogoutEnabledChanged() {
            updateLogoutView();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mIActivityManager = ActivityManager.getService();
        mLockPatternUtils = new LockPatternUtils(getContext());
        mHandler = new Handler(Looper.myLooper());
        mSmallClockScale = getResources().getDimension(R.dimen.widget_small_font_size)
                / getResources().getDimension(R.dimen.widget_big_font_size);
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, LOCK_CLOCK_FONT_STYLE);
        tunerService.addTunable(this, LOCK_DATE_FONT_STYLE);
        tunerService.addTunable(this, LOCKSCREEN_CLOCK_SELECTION);
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, "Schedule setEnableMarquee: " + (enabled ? "Enable" : "Disable"));
        if (enabled) {
            if (mPendingMarqueeStart == null) {
                mPendingMarqueeStart = () -> {
                    setEnableMarqueeImpl(true);
                    mPendingMarqueeStart = null;
                };
                mHandler.postDelayed(mPendingMarqueeStart, MARQUEE_DELAY_MS);
            }
        } else {
            if (mPendingMarqueeStart != null) {
                mHandler.removeCallbacks(mPendingMarqueeStart);
                mPendingMarqueeStart = null;
            }
            setEnableMarqueeImpl(false);
        }
    }

    private void setEnableMarqueeImpl(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLogoutView = findViewById(R.id.logout);
        if (mLogoutView != null) {
            mLogoutView.setOnClickListener(this::onLogoutClicked);
        }

        mClockView = findViewById(R.id.clock_view);
        mClockView.setShowCurrentUserTime(true);
        mCustomClockView = findViewById(R.id.custom_clock_view);
        mCustomDarkClockView = findViewById(R.id.custom_dark_clock_view);
        mSpideyClockView = findViewById(R.id.spidey_clock_view);
        mSpectrumClockView = findViewById(R.id.spectrum_clock_view);
        mSneekyClockView = findViewById(R.id.sneeky_clock_view);
        mDotClockView = findViewById(R.id.dot_clock_view);
        mTextClock = findViewById(R.id.custom_textclock_view);
        mOwnerInfo = findViewById(R.id.owner_info);
        mKeyguardSlice = findViewById(R.id.keyguard_status_area);
        mClockSeparator = findViewById(R.id.clock_separator);
        mVisibleInDoze = Sets.newArraySet(mClockView, mKeyguardSlice, mCustomClockView, mCustomDarkClockView,
                mSpideyClockView, mSpectrumClockView, mSneekyClockView, mDotClockView, mTextClock);
        mTextColor = mClockView.getCurrentTextColor();

        int clockStroke = getResources().getDimensionPixelSize(R.dimen.widget_small_font_stroke);
        mClockView.getPaint().setStrokeWidth(clockStroke);
        mClockView.addOnLayoutChangeListener(this);
        mClockSeparator.addOnLayoutChangeListener(this);
        mKeyguardSlice.setContentChangeListener(this::onSliceContentChanged);
        onSliceContentChanged();

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refreshFormat();
        updateOwnerInfo();
        updateLogoutView();
        updateDark();
        mClockAvailable = true;
        onDensityOrFontScaleChanged();
    }

    /**
     * Moves clock and separator, adjusting margins when slice content changes.
     */
    private void onSliceContentChanged() {
        boolean smallClock = mKeyguardSlice.hasHeader() || mPulsing;
        prepareSmallView(smallClock);
        float clockScale = smallClock ? mSmallClockScale : 1;

        RelativeLayout.LayoutParams layoutParams =
                (RelativeLayout.LayoutParams) mClockView.getLayoutParams();
        int height = mClockView.getHeight();
        layoutParams.bottomMargin = (int) -(height - (clockScale * height));
        mClockView.setLayoutParams(layoutParams);

        // Custom analog clock
        RelativeLayout.LayoutParams customlayoutParams =
                (RelativeLayout.LayoutParams) mCustomClockView.getLayoutParams();
        customlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mCustomClockView.setLayoutParams(customlayoutParams);

        // Custom dark analog clock
        RelativeLayout.LayoutParams customnumlayoutParams =
                (RelativeLayout.LayoutParams) mCustomDarkClockView.getLayoutParams();
        customnumlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mCustomDarkClockView.setLayoutParams(customnumlayoutParams);

        // Spidey analog clock
        RelativeLayout.LayoutParams spideylayoutParams =
                (RelativeLayout.LayoutParams) mSpideyClockView.getLayoutParams();
        spideylayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mSpideyClockView.setLayoutParams(spideylayoutParams);

        // Dot analog clock
        RelativeLayout.LayoutParams dotlayoutParams =
                (RelativeLayout.LayoutParams) mDotClockView.getLayoutParams();
        dotlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mDotClockView.setLayoutParams(dotlayoutParams);

        // Spectrum analog clock
        RelativeLayout.LayoutParams spectrumlayoutParams =
                (RelativeLayout.LayoutParams) mSpectrumClockView.getLayoutParams();
        spectrumlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mSpectrumClockView.setLayoutParams(spectrumlayoutParams);

        // Sneekyanalog clock
        RelativeLayout.LayoutParams sneekylayoutParams =
                (RelativeLayout.LayoutParams) mSneekyClockView.getLayoutParams();
        sneekylayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mSneekyClockView.setLayoutParams(sneekylayoutParams);

        //Custom Text clock
        RelativeLayout.LayoutParams textlayoutParams =
                (RelativeLayout.LayoutParams) mTextClock.getLayoutParams();
        textlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mTextClock.setLayoutParams(textlayoutParams);

        layoutParams = (RelativeLayout.LayoutParams) mClockSeparator.getLayoutParams();
        layoutParams.topMargin = smallClock ? (int) mWidgetPadding : 0;
        layoutParams.bottomMargin = layoutParams.topMargin;
        mClockSeparator.setLayoutParams(layoutParams);
    }

    /**
     * Animate clock and its separator when necessary.
     */
    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        int heightOffset = mPulsing || mWasPulsing ? 0 : getHeight() - mLastLayoutHeight;
        boolean hasHeader = mKeyguardSlice.hasHeader();
        boolean smallClock = hasHeader || mPulsing;
        prepareSmallView(smallClock);
        long duration = KeyguardSliceView.DEFAULT_ANIM_DURATION;
        long delay = smallClock || mWasPulsing ? 0 : duration / 4;
        mWasPulsing = false;

        boolean shouldAnimate = mKeyguardSlice.getLayoutTransition() != null
                && mKeyguardSlice.getLayoutTransition().isRunning();
        if (view == mClockView) {
            float clockScale = smallClock ? mSmallClockScale : 1;
            Paint.Style style = smallClock ? Paint.Style.FILL_AND_STROKE : Paint.Style.FILL;
            mClockView.animate().cancel();
            if (shouldAnimate) {
                mClockView.setY(oldTop + heightOffset);
                mClockView.animate()
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .setDuration(duration)
                        .setListener(new ClipChildrenAnimationListener())
                        .setStartDelay(delay)
                        .y(top)
                        .scaleX(clockScale)
                        .scaleY(clockScale)
                        .withEndAction(() -> {
                            mClockView.getPaint().setStyle(style);
                            mClockView.invalidate();
                        })
                        .start();
            } else {
                mClockView.setY(top);
                mClockView.setScaleX(clockScale);
                mClockView.setScaleY(clockScale);
                mClockView.getPaint().setStyle(style);
                mClockView.invalidate();
            }
        } else if (view == mClockSeparator) {
            boolean hasSeparator = hasHeader && !mPulsing;
            float alpha = hasSeparator ? 1 : 0;
            mClockSeparator.animate().cancel();
            if (shouldAnimate) {
                boolean isAwake = mDarkAmount != 0;
                mClockSeparator.setY(oldTop + heightOffset);
                mClockSeparator.animate()
                        .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                        .setDuration(duration)
                        .setListener(isAwake ? null : new KeepAwakeAnimationListener(getContext()))
                        .setStartDelay(delay)
                        .y(top)
                        .alpha(alpha)
                        .start();
            } else {
                mClockSeparator.setY(top);
                mClockSeparator.setAlpha(alpha);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mClockView.setPivotX(mClockView.getWidth() / 2);
        mClockView.setPivotY(0);
        mLastLayoutHeight = getHeight();
        layoutOwnerInfo();
    }

    private void isDigitalClock() {
        if (!mClockAvailable) {
            mDigitalClock = true;
            return;
        }
        switch (mClockSelection) {
            case 1:
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
            case 13:
            case 14:
            case 15:
            case 16:
            case 17:
                mDigitalClock = true;
                mCustomClockView.setVisibility(View.GONE);
                mCustomDarkClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mTextClock.setVisibility(View.GONE);
                mClockView.setVisibility(View.VISIBLE);
                break;
            default:
                mDigitalClock = false;
                break;
        }
    }

    private void setDigitalClock() {
        if (!mClockAvailable || !mDigitalClock) return;

        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
        setFontStyle(mClockView, mLockClockFontStyle);
        mClockView.getPaint().setStrokeWidth(
                getResources().getDimensionPixelSize(R.dimen.widget_small_font_stroke));
        mClockView.setGravity(Gravity.CENTER);
        mClockView.setLineSpacing(0, 0.8f);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mWidgetPadding = getResources().getDimension(R.dimen.widget_vertical_padding);
        isDigitalClock();
        if (mClockAvailable && mClockSelection == 0) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
        } else if (mClockAvailable && mClockSelection == 2) {
            mClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.VISIBLE);
        } else if (mClockAvailable && mClockSelection == 3) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.VISIBLE);
        } else if (mClockAvailable && mClockSelection == 4) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.VISIBLE);
        } else if (mClockAvailable && mClockSelection == 5) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.VISIBLE);
        } else if (mClockAvailable && mClockSelection == 6) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.VISIBLE);
        } else if (mClockAvailable && mClockSelection == 7) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.VISIBLE);
        } else if (mClockAvailable && (mClockSelection == 16 ||
                mClockSelection == 17)) {
            mClockView.setVisibility(View.GONE);
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.VISIBLE);
        }
        refreshFormat();
        setDigitalClock();
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
            setFontStyle(mOwnerInfo, mLockDateFontStyle);
        }
        if (mLogoutView != null) {
            setFontStyle(mLogoutView, mLockDateFontStyle);
        }
        if (mKeyguardSlice != null) {
            mKeyguardSlice.setFontStyle(mLockDateFontStyle);
            mKeyguardSlice.refresh();
        }
    }

    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSlice.refresh();
    }

    private void refreshTime() {
        mClockView.refresh();
    }

    private void refreshFormat() {
        if (!mClockAvailable || !mDigitalClock) return;

        boolean dark = mDarkAmount != 0;
        int accentColor = dark ? Color.WHITE : getResources().getColor(R.color.accent_device_default_light);
        Patterns.update(mContext);

        if (mClockSelection == 1) {
            mClockView.setSingleLine(true);
            mClockView.setFormat12Hour(Patterns.clockView12);
            mClockView.setFormat24Hour(Patterns.clockView24);
        } else if (mClockSelection == 8) {
            mClockView.setSingleLine(true);
            mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong>mm"));
        } else if (mClockSelection == 9) {
            mClockView.setSingleLine(true);
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + accentColor +
                "><strong>h</strong>mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + accentColor +
                "><strong>kk</strong>mm</font>"));
        } else if (mClockSelection == 10) {
            mClockView.setSingleLine(true);
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + accentColor +
                "><strong>h</strong></font>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + accentColor +
                "><strong>kk</strong></font>mm"));
        } else if (mClockSelection == 11) {
            mClockView.setSingleLine(true);
            mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong><font color=" + accentColor + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><font color=" + accentColor + ">mm</font>"));
        } else if (mClockSelection == 12) {
            mClockView.setSingleLine(false);
            mClockView.setFormat12Hour("hh\nmm");
            mClockView.setFormat24Hour("kk\nmm");
        } else if (mClockSelection == 13) {
            mClockView.setSingleLine(false);
            mClockView.setFormat12Hour(Html.fromHtml("<strong>hh</strong><br>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><br>mm"));
        } else if (mClockSelection == 14 || (dark && mClockSelection == 15)) {
            mClockView.setSingleLine(false);
            mClockView.setFormat12Hour(Html.fromHtml("hh<br><font color=" + accentColor + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("kk<br><font color=" + accentColor + ">mm</font>"));
        } else if (mClockSelection == 15) {
            mClockView.setSingleLine(false);
            mClockView.setFormat12Hour(Html.fromHtml("<font color='#454545'>hh</font><br><font color=" +
                accentColor + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color='#454545'>kk</font><br><font color=" +
                accentColor + ">mm</font>"));
        }
    }

    public int getLogoutButtonHeight() {
        if (mLogoutView == null) {
            return 0;
        }
        return mLogoutView.getVisibility() == VISIBLE ? mLogoutView.getHeight() : 0;
    }

    public float getClockTextSize() {
        return mClockView.getTextSize();
    }

    private void updateLogoutView() {
        if (mLogoutView == null) {
            return;
        }
        mLogoutView.setVisibility(shouldShowLogout() ? VISIBLE : GONE);
        // Logout button will stay in language of user 0 if we don't set that manually.
        mLogoutView.setText(mContext.getResources().getString(
                com.android.internal.R.string.global_action_logout));
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String info = mLockPatternUtils.getDeviceOwnerInfo();
        if (info == null) {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        mOwnerInfo.setText(info);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    @Override
    public void onLocaleListChanged() {
        refreshFormat();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCK_CLOCK_FONT_STYLE:
                mLockClockFontStyle = 4;
                try {
                    mLockClockFontStyle = Integer.valueOf(newValue);
                } catch (NumberFormatException ex) {}
                onDensityOrFontScaleChanged();
                break;
            case LOCK_DATE_FONT_STYLE:
                mLockDateFontStyle = 14;
                try {
                    mLockDateFontStyle = Integer.valueOf(newValue);
                } catch (NumberFormatException ex) {}
                onDensityOrFontScaleChanged();
                break;
            case LOCKSCREEN_CLOCK_SELECTION:
                mClockSelection = 1;
                try {
                    mClockSelection = Integer.valueOf(newValue);
                } catch (NumberFormatException ex) {}
                onDensityOrFontScaleChanged();
                break;
            default:
                break;
        }
    }

    private void setFontStyle(TextView view, int fontstyle) {
        switch (fontstyle) {
            case FONT_NORMAL:
                view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case FONT_ITALIC:
                view.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case FONT_BOLD:
                view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case FONT_BOLD_ITALIC:
                view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case FONT_LIGHT:
                view.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case FONT_THIN:
                view.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case FONT_THIN_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case FONT_CONDENSED:
                view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_LIGHT:
                view.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_LIGHT_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_BOLD:
                view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case FONT_CONDENSED_BOLD_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case FONT_MEDIUM:
                view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case FONT_MEDIUM_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                break;
            case FONT_BLACK:
                view.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case FONT_BLACK_ITALIC:
                view.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                break;
            case FONT_DANCINGSCRIPT:
                view.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                break;
            case FONT_DANCINGSCRIPT_BOLD:
                view.setTypeface(Typeface.create("cursive", Typeface.BOLD));
                break;
            case FONT_COMINGSOON:
                view.setTypeface(Typeface.create("casual", Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF:
                view.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF_ITALIC:
                view.setTypeface(Typeface.create("serif", Typeface.ITALIC));
                break;
            case FONT_NOTOSERIF_BOLD:
                view.setTypeface(Typeface.create("serif", Typeface.BOLD));
                break;
            case FONT_NOTOSERIF_BOLD_ITALIC:
                view.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                break;
            default:
                break;
        }
    }

    private void prepareSmallView(boolean small) {
        if (mWasLatestViewSmall == small || !mClockAvailable ||
                mClockSelection == 0)
            return;
        mWasLatestViewSmall = small;
        if (small) {
            mCustomClockView.setVisibility(View.GONE);
            mCustomDarkClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
            mClockView.setSingleLine(true);
            mClockView.setFormat12Hour(Patterns.clockView12);
            mClockView.setFormat24Hour(Patterns.clockView24);
            mDigitalClock = true;
            setDigitalClock();
        } else {
            onDensityOrFontScaleChanged();
        }
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            cacheKey = key;
        }
    }

    public void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        updateDark();
    }

    private void updateDark() {
        boolean dark = mDarkAmount == 1;
        if (mLogoutView != null) {
            mLogoutView.setAlpha(dark ? 0 : 1);
        }

        if (mOwnerInfo != null) {
            boolean hasText = !TextUtils.isEmpty(mOwnerInfo.getText());
            mOwnerInfo.setVisibility(hasText ? VISIBLE : GONE);
            layoutOwnerInfo();
        }

        final int blendedTextColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        updateDozeVisibleViews();
        mKeyguardSlice.setDarkAmount(mDarkAmount);
        mClockView.setTextColor(blendedTextColor);
        mCustomClockView.setDark(dark);
        mCustomDarkClockView.setDark(dark);
        mSpideyClockView.setDark(dark);
        mDotClockView.setDark(dark);
        mSpectrumClockView.setDark(dark);
        mSneekyClockView.setDark(dark);
        mClockSeparator.setBackgroundColor(blendedTextColor);
        refreshFormat();
    }

    private void layoutOwnerInfo() {
        if (mOwnerInfo != null && mOwnerInfo.getVisibility() != GONE) {
            // Animate owner info during wake-up transition
            mOwnerInfo.setAlpha(1f - mDarkAmount);

            float ratio = mDarkAmount;
            // Calculate how much of it we should crop in order to have a smooth transition
            int collapsed = mOwnerInfo.getTop() - mOwnerInfo.getPaddingTop();
            int expanded = mOwnerInfo.getBottom() + mOwnerInfo.getPaddingBottom();
            int toRemove = (int) ((expanded - collapsed) * ratio);
            setBottom(getMeasuredHeight() - toRemove);
        }
    }

    public void setPulsing(boolean pulsing, boolean animate) {
        if (mPulsing == pulsing) {
            return;
        }
        if (mPulsing) {
            mWasPulsing = true;
        }
        mPulsing = pulsing;
        // Animation can look really weird when the slice has a header, let's hide the views
        // immediately instead of fading them away.
        if (mKeyguardSlice.hasHeader()) {
            animate = false;
        }
        mKeyguardSlice.setPulsing(pulsing, animate);
        updateDozeVisibleViews();
    }

    public void setCleanLayout(int reason) {
        mForcedMediaDoze =
                reason == DozeLog.PULSE_REASON_FORCED_MEDIA_NOTIFICATION;
        updateDozeVisibleViews();
    }

    private void updateDozeVisibleViews() {
        for (View child : mVisibleInDoze) {
            if (!mForcedMediaDoze) {
                child.setAlpha(mDarkAmount == 1 && mPulsing ? 0.8f : 1);
            } else {
                child.setAlpha(mDarkAmount == 1 ? 0 : 1);
            }
        }
    }

    private boolean shouldShowLogout() {
        return KeyguardUpdateMonitor.getInstance(mContext).isLogoutEnabled()
                && KeyguardUpdateMonitor.getCurrentUser() != UserHandle.USER_SYSTEM;
    }

    private void onLogoutClicked(View view) {
        int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
        try {
            mIActivityManager.switchUser(UserHandle.USER_SYSTEM);
            mIActivityManager.stopUser(currentUserId, true /*force*/, null);
        } catch (RemoteException re) {
            Log.e(TAG, "Failed to logout user", re);
        }
    }

    private class ClipChildrenAnimationListener extends AnimatorListenerAdapter implements
            ViewClippingUtil.ClippingParameters {

        ClipChildrenAnimationListener() {
            ViewClippingUtil.setClippingDeactivated(mClockView, true /* deactivated */,
                    this /* clippingParams */);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            ViewClippingUtil.setClippingDeactivated(mClockView, false /* deactivated */,
                    this /* clippingParams */);
        }

        @Override
        public boolean shouldFinish(View view) {
            return view == getParent();
        }
    }
}
