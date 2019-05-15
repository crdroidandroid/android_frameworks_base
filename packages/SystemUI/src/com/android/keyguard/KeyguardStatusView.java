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
import android.content.ContentResolver;
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
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

import android.widget.GridLayout;
import android.widget.RelativeLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.graphics.Typeface;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.ViewClippingUtil;
import com.android.keyguard.clocks.CustomAnalogClock;
import com.android.keyguard.clocks.CustomTextClock;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

import com.google.android.collect.Sets;
import java.lang.Math;
import java.util.Locale;

public class KeyguardStatusView extends GridLayout implements
        ConfigurationController.ConfigurationListener, View.OnLayoutChangeListener {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;

	private static final String FONT_FAMILY = "sans-serif-light";

    private final LockPatternUtils mLockPatternUtils;
    private final IActivityManager mIActivityManager;
    private final float mSmallClockScale;

    private TextView mLogoutView;
    private CustomAnalogClock mCustomClockView;
    private CustomAnalogClock mCustomNumClockView;
    private CustomAnalogClock mSpideyClockView;
    private CustomAnalogClock mSpectrumClockView;
    private CustomAnalogClock mSneekyClockView;
    private CustomAnalogClock mDotClockView;
    private LinearLayout mTextClock;
    private TextClock mClockView;
    private CustomTextClock mTextClockV0;
    private CustomTextClock mTextClockV1;
    private CustomTextClock mTextClockV2;
    private View mClockSeparator;
    private TextView mOwnerInfo;
    private KeyguardSliceView mKeyguardSlice;
    private View mKeyguardSliceView;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;


    private ArraySet<View> mVisibleInDoze;
    private boolean mPulsing;
    private boolean mWasPulsing;
    private float mDarkAmount = 0;
    private int mTextColor;
    private float mWidgetPadding;
    private int mLastLayoutHeight;

    private boolean mShowClock;
    private boolean mShowInfo;
    private int mClockSelection;
    private int mTextClockAlign;

    private boolean mWasLatestViewSmall;

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
                refreshLockFont();
                refreshLockDateFont();
                refreshclocksize();
                refreshdatesize();
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
            refreshLockFont();
            refreshLockDateFont();
            refreshclocksize();
            refreshdatesize();
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
        onDensityOrFontScaleChanged();
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
        mCustomNumClockView = findViewById(R.id.custom_num_clock_view);
        mSpideyClockView = findViewById(R.id.spidey_clock_view);
        mSpectrumClockView = findViewById(R.id.spectrum_clock_view);
        mSneekyClockView = findViewById(R.id.sneeky_clock_view);
		mDotClockView = findViewById(R.id.dot_clock_view);
        mTextClock = findViewById(R.id.custom_textclock_view);
        mTextClockV0 = findViewById(R.id.custom_textclock_view0);
        mTextClockV1 = findViewById(R.id.custom_textclock_view1);
        mTextClockV2 = findViewById(R.id.custom_textclock_view2);
        mOwnerInfo = findViewById(R.id.owner_info);
        mKeyguardSlice = findViewById(R.id.keyguard_status_area);
        mKeyguardSliceView = findViewById(R.id.keyguard_status_area);
        mClockSeparator = findViewById(R.id.clock_separator);
        mVisibleInDoze = Sets.newArraySet(mClockView, mKeyguardSlice, mCustomClockView, mSpideyClockView, mCustomNumClockView, mDotClockView, mSpectrumClockView, mSneekyClockView, mTextClock);
        mTextColor = mClockView.getCurrentTextColor();

        int clockStroke = getResources().getDimensionPixelSize(R.dimen.widget_small_font_stroke);
        mClockView.getPaint().setStrokeWidth(clockStroke);
        mClockView.addOnLayoutChangeListener(this);
        mClockSeparator.addOnLayoutChangeListener(this);
        mKeyguardSlice.setContentChangeListener(this::onSliceContentChanged);
        onSliceContentChanged();

        updateSettings();

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refreshFormat();
        updateOwnerInfo();
        updateLogoutView();
        updateDark();
        refreshLockFont();
        refreshLockDateFont();
        refreshclocksize();
        refreshdatesize();
    }

    /**
     * Moves clock and separator, adjusting margins when slice content changes.
     */
    private void onSliceContentChanged() {
        boolean smallClock = mKeyguardSlice.hasHeader() || mPulsing;
        prepareSmallView(smallClock);
        float clockScale = smallClock ? mSmallClockScale : 1;
		Typeface tf = Typeface.create(FONT_FAMILY, Typeface.NORMAL);
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

        // Spidey analog clock
        RelativeLayout.LayoutParams spideylayoutParams =
                (RelativeLayout.LayoutParams) mSpideyClockView.getLayoutParams();
        spideylayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mSpideyClockView.setLayoutParams(spideylayoutParams);

        // Custom analog clock
        RelativeLayout.LayoutParams customnumlayoutParams =
                (RelativeLayout.LayoutParams) mCustomNumClockView.getLayoutParams();
        customnumlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_security_view_top_margin);
        mCustomNumClockView.setLayoutParams(customnumlayoutParams);

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

        /* TODO: Switch case for diff clock variants */
        mTextClockV0.setTextAppearance(getContext(), R.style.customtextclock_big_thin);
        mTextClockV1.setTextAppearance(getContext(), R.style.customtextclock_big_thin);
        mTextClockV2.setTextAppearance(getContext(), R.style.customtextclock_big_thin);
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

    @Override
    public void onDensityOrFontScaleChanged() {
        mWidgetPadding = getResources().getDimension(R.dimen.widget_vertical_padding);
        if (mClockView != null) {
            mClockView.getPaint().setStrokeWidth(
                    getResources().getDimensionPixelSize(R.dimen.widget_small_font_stroke));
        }
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        }
    }

    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSlice.refresh();
    }

    private void refreshTime() {
        mClockView.refresh();

        if (mClockSelection == 0 || mWasLatestViewSmall) {
            mClockView.setFormat12Hour(Patterns.clockView12);
            mClockView.setFormat24Hour(Patterns.clockView24);
        } else if (mClockSelection == 1) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong>:mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong>:mm"));
        } else if (mClockSelection == 4) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>hh</strong><br>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><br>mm"));
        } else if (mClockSelection == 5) {
            mClockView.setFormat12Hour(Html.fromHtml("hh<br><font color=" + getResources().getColor(R.color.sammy_minutes_accent) + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("kk<br><font color=" + getResources().getColor(R.color.sammy_minutes_accent) + ">mm</font>"));
        } else if (mClockSelection == 6) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color='#454545'>hh</font><br><font color=" + getResources().getColor(R.color.sammy_minutes_accent) + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color='#454545'>kk</font><br><font color=" + getResources().getColor(R.color.sammy_minutes_accent) + ">mm</font>"));
        } else if (mClockSelection == 9) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.sammy_minutes_accent) + "><strong>h</strong>mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.sammy_minutes_accent) + "><strong>kk</strong>mm</font>"));
        } else if (mClockSelection == 10) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.sammy_minutes_accent) + "><strong>h</strong></font>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.sammy_minutes_accent) + "><strong>kk</strong></font>mm"));
        } else if (mClockSelection == 11) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong><font color=" + getResources().getColor(R.color.sammy_minutes_accent) + ">mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><font color=" + getResources().getColor(R.color.sammy_minutes_accent) + ">mm</font>"));
        } else if (mClockSelection == 15) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">hh mm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">kk mm</font>"));
        } else if (mClockSelection == 16) {
            mClockView.setFormat12Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">hh\nmm</font>"));
            mClockView.setFormat24Hour(Html.fromHtml("<font color=" + getResources().getColor(R.color.clock_gradient_full_clock_text_color) + ">kk\nmm</font>"));
        } else {
            mClockView.setFormat12Hour("hh\nmm");
            mClockView.setFormat24Hour("kk\nmm");
        }
    }
	
    private int getLockClockFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_CLOCK_FONTS, 0);
    }
	
    private int getLockDateFont() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCK_DATE_FONTS, 0);
    }
	
    private int getLockClockSize() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKCLOCK_FONT_SIZE, 64);
    }

    private int getLockDateSize() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKDATE_FONT_SIZE, 16);
    }


    private void refreshFormat() {
        Patterns.update(mContext);
        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
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
	
    private void refreshLockFont() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockClockFont = isPrimary ? getLockClockFont() : 0;

        if (lockClockFont == 0) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (lockClockFont == 1) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (lockClockFont == 2) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (lockClockFont == 3) {
            mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 4) {
            mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (lockClockFont == 5) {
                mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (lockClockFont == 6) {
            mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (lockClockFont == 7) {
                mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (lockClockFont == 8) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (lockClockFont == 9) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (lockClockFont == 10) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (lockClockFont == 11) {
            mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 12) {
            mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (lockClockFont == 13) {
            mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (lockClockFont == 14) {
                mClockView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (lockClockFont == 15) {
                mClockView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (lockClockFont == 16) {
                mClockView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (lockClockFont == 17) {
                mClockView.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (lockClockFont == 18) {
                mClockView.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
        }
        if (lockClockFont == 19) {
                mClockView.setTypeface(Typeface.create("cursive", Typeface.BOLD));
        }
        if (lockClockFont == 20) {
                mClockView.setTypeface(Typeface.create("casual", Typeface.NORMAL));
        }
        if (lockClockFont == 21) {
                mClockView.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (lockClockFont == 22) {
                mClockView.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        }
        if (lockClockFont == 23) {
                mClockView.setTypeface(Typeface.create("serif", Typeface.BOLD));
        }
        if (lockClockFont == 24) {
                mClockView.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        }
        if (lockClockFont == 25) {
            mClockView.setTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 26) {
            mClockView.setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 27) {
            mClockView.setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 28) {
            mClockView.setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 29) {
            mClockView.setTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 30) {
            mClockView.setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 31) {
            mClockView.setTypeface(Typeface.create("samsung-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 32) {
            mClockView.setTypeface(Typeface.create("abcthru-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 33) {
            mClockView.setTypeface(Typeface.create("anurati-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 34) {
            mClockView.setTypeface(Typeface.create("joostmillionaire-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 35) {
            mClockView.setTypeface(Typeface.create("locust-sys", Typeface.NORMAL));
        }
        if (lockClockFont == 36) {
            mClockView.setTypeface(Typeface.create("wallpoet-sys", Typeface.NORMAL));

        }
    }
	
    private void refreshLockDateFont() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockDateFont = isPrimary ? getLockDateFont() : 0;

        if (lockDateFont == 0) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        if (lockDateFont == 1) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (lockDateFont == 2) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
        }
        if (lockDateFont == 3) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
        }
        if (lockDateFont == 4) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
        }
        if (lockDateFont == 5) {
                mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        if (lockDateFont == 6) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
        }
        if (lockDateFont == 7) {
                mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        }
        if (lockDateFont == 8) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        }
        if (lockDateFont == 9) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
        }
        if (lockDateFont == 10) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }
        if (lockDateFont == 11) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        }
        if (lockDateFont == 12) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (lockDateFont == 13) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
        }
        if (lockDateFont == 14) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        }
        if (lockDateFont == 15) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
        }
        if (lockDateFont == 16) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        }
        if (lockDateFont == 17) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        }
        if (lockDateFont == 18) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("cursive", Typeface.NORMAL));
        }
        if (lockDateFont == 19) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("cursive", Typeface.BOLD));
        }
        if (lockDateFont == 20) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("casual", Typeface.NORMAL));
        }
        if (lockDateFont == 21) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (lockDateFont == 22) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("serif", Typeface.ITALIC));
        }
        if (lockDateFont == 23) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("serif", Typeface.BOLD));
        }
        if (lockDateFont == 24) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        }
        if (lockDateFont == 25) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 26) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 27) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 28) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
        }
	    if (lockDateFont == 29) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 30) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
    	}
	    if (lockDateFont == 31) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("samsung-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 32) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("abcthru-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 33) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("anurati-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 34) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("joostmillionaire-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 35) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("locust-sys", Typeface.NORMAL));
        }
        if (lockDateFont == 36) {
            mKeyguardSlice.setViewsTypeface(Typeface.create("wallpoet-sys", Typeface.NORMAL));

        }
    }
	
    public void refreshclocksize() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockClockSize = isPrimary ? getLockClockSize() : 64;

        if (lockClockSize == 50) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_50));
        } else if (lockClockSize == 51) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_51));
        } else if (lockClockSize == 52) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_52));
        } else if (lockClockSize == 53) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_53));
        } else if (lockClockSize == 54) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_54));
        } else if (lockClockSize == 55) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_55));
        } else if (lockClockSize == 56) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_56));
        } else if (lockClockSize == 57) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_57));
        } else if (lockClockSize == 58) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_58));
        } else if (lockClockSize == 59) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_59));
        } else if (lockClockSize == 60) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_60));
        } else if (lockClockSize == 61) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_61));
        } else if (lockClockSize == 62) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_62));
        } else if (lockClockSize == 63) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_63));
        } else if (lockClockSize == 64) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_64));
        } else if (lockClockSize == 65) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_65));
        } else if (lockClockSize == 66) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_66));
        } else if (lockClockSize == 66) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_67));
        } else if (lockClockSize == 68) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_68));
        } else if (lockClockSize == 69) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_69));
        } else if (lockClockSize == 70) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_70));
        } else if (lockClockSize == 71) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_71));
        } else if (lockClockSize == 72) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_72));
        } else if (lockClockSize == 73) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_73));
        } else if (lockClockSize == 74) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_74));
        } else if (lockClockSize == 75) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_75));
        } else if (lockClockSize == 76) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_76));
        } else if (lockClockSize == 77) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_77));
        } else if (lockClockSize == 78) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_78));
        } else if (lockClockSize == 79) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_79));
        } else if (lockClockSize == 80) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_80));
        } else if (lockClockSize == 81) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_81));
        } else if (lockClockSize == 82) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_82));
        } else if (lockClockSize == 83) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_83));
        } else if (lockClockSize == 84) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_84));
        } else if (lockClockSize == 85) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_85));
        } else if (lockClockSize == 86) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_86));
        } else if (lockClockSize == 87) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_87));
        } else if (lockClockSize == 88) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_88));
        } else if (lockClockSize == 89) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_89));
        } else if (lockClockSize == 90) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_90));
        } else if (lockClockSize == 91) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_91));
        } else if (lockClockSize == 92) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_92));
        } else if (lockClockSize == 93) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_93));
        } else if (lockClockSize == 94) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_94));
        } else if (lockClockSize == 95) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_95));
        } else if (lockClockSize == 96) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_96));
        } else if (lockClockSize == 97) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_97));
        } else if (lockClockSize == 98) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_98));
        } else if (lockClockSize == 99) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_99));
        } else if (lockClockSize == 100) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_100));
        } else if (lockClockSize == 101) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_101));
        } else if (lockClockSize == 102) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_102));
        } else if (lockClockSize == 103) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_103));
        } else if (lockClockSize == 104) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_104));
        } else if (lockClockSize == 105) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_105));
        } else if (lockClockSize == 106) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_106));
        } else if (lockClockSize == 107) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_107));
        } else if (lockClockSize == 108) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_108));
        } else if (lockClockSize == 109) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_109));
        } else if (lockClockSize == 110) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_110));
        } else if (lockClockSize == 111) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_111));
        } else if (lockClockSize == 112) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_112));
        } else if (lockClockSize == 113) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_113));
        } else if (lockClockSize == 114) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_114));
        } else if (lockClockSize == 115) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_115));
        } else if (lockClockSize == 116) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_116));
        } else if (lockClockSize == 117) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_117));
        } else if (lockClockSize == 118) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_118));
        } else if (lockClockSize == 119) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_119));
        } else if (lockClockSize == 120) {
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_120));
        }
    }

    public void refreshdatesize() {
        final Resources res = getContext().getResources();
        boolean isPrimary = UserHandle.getCallingUserId() == UserHandle.USER_OWNER;
        int lockDateSize = isPrimary ? getLockDateSize() : 16;

        if (lockDateSize == 0) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_1));
        } else if (lockDateSize == 1) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_1));
        } else if (lockDateSize == 2) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_2));
        } else if (lockDateSize == 3) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_3));
        } else if (lockDateSize == 4) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_4));
        } else if (lockDateSize == 5) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_5));
        } else if (lockDateSize == 6) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_6));
        } else if (lockDateSize == 7) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_7));
        } else if (lockDateSize == 8) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_8));
        } else if (lockDateSize == 9) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_9));
        } else if (lockDateSize == 10) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_10));
        } else if (lockDateSize == 11) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_11));
        } else if (lockDateSize == 12) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_12));
        } else if (lockDateSize == 13) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_13));
        } else if (lockDateSize == 14) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_14));
        } else if (lockDateSize == 15) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_15));
        } else if (lockDateSize == 16) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_16));
        } else if (lockDateSize == 17) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_17));
        } else if (lockDateSize == 18) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_18));
        } else if (lockDateSize == 19) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_19));
        } else if (lockDateSize == 20) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_20));
        } else if (lockDateSize == 21) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_21));
        } else if (lockDateSize == 22) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_22));
        } else if (lockDateSize == 23) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_23));
        } else if (lockDateSize == 24) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_24));
        } else if (lockDateSize == 25) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_25));
        } else if (lockDateSize == 26) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_26));
        } else if (lockDateSize == 27) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_27));
        } else if (lockDateSize == 28) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_28));
        } else if (lockDateSize == 29) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_29));
        } else if (lockDateSize == 30) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_30));
        } else if (lockDateSize == 31) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_31));
        } else if (lockDateSize == 32) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_32));
        } else if (lockDateSize == 33) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_33));
        } else if (lockDateSize == 34) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_34));
        } else if (lockDateSize == 35) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_35));
        } else if (lockDateSize == 36) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_36));
        } else if (lockDateSize == 37) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_37));
        } else if (lockDateSize == 38) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_38));
        } else if (lockDateSize == 39) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_39));
        } else if (lockDateSize == 40) {
        mKeyguardSlice.setViewsTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_40));
        }
    }

    private void updateTextClockAlign() {
        int paddingPixel = (int) getResources().getDimension(R.dimen.custom_clock_left_padding);
        switch(mTextClockAlign) {
            case 0:
            default:
                mTextClockV0.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                mTextClockV1.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                mTextClockV2.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                mTextClock.setPaddingRelative(paddingPixel , 0 , 0 , 0);
                mTextClockV0.setAlign();
                mTextClockV1.setAlign();
                mTextClockV2.setAlign();
                break;

            case 1:
                mTextClockV0.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                mTextClockV1.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                mTextClockV2.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                mTextClock.setPaddingRelative(0 , 0 , 0 , 0);
                mTextClockV0.setAlign();
                mTextClockV1.setAlign();
                mTextClockV2.setAlign();
                break;

            case 2:
                mTextClockV0.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
                mTextClockV1.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
                mTextClockV2.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
                mTextClock.setPaddingRelative(0 , 0 , paddingPixel , 0);
                mTextClockV0.setAlign();
                mTextClockV1.setAlign();
                mTextClockV2.setAlign();
                break;
        }
    }
    private void updateVisibilities() {
        switch (mClockSelection) {
            case 0: // default digital
            default:
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
                mClockView.setLineSpacing(0,1f);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 1: // digital (bold)
            case 9: // digital (accent full)
			case 10: // digital (accent hour)
			case 11: // digital (accent minutes)
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
                mClockView.setLineSpacing(0,1f);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 2: // custom analog
                mCustomClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
                mClockView.setLineSpacing(0,1f);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 3: // sammy
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
                mClockView.setLineSpacing(0,1f);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 4: // sammy (bold)
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
                mClockView.setLineSpacing(0,1f);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 5: // sammy accent
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_sammy_accent_font_size));
                mClockView.setLineSpacing(0,1f);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 6: // sammy accent
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_sammy_accent_font_size));
                mClockView.setLineSpacing(0,1f);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 7: // spidey analog
                mSpideyClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 8: // custom analog with numbers
                mCustomNumClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 12: // dot analog
                mDotClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 13: // spectrum analog
                mSpectrumClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 14: // sneeky analog
                mSneekyClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mClockView.setBackgroundResource(0);
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 15: // Gradient with dark clock
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_gradient_shadow));
                mClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mClockView.setLineSpacing(0,1f);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mClockView.setPadding(0,20,0,20);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding), 
        getResources().getDisplayMetrics()),0,0
                );
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 16: // Monochrome gradient, like the qs tile
                mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setBackground(getResources().getDrawable(R.drawable.clock_bootleg_qsgradient));
                mClockView.getLayoutParams().width = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mClockView.getLayoutParams().height = getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_size);
                mClockView.setLineSpacing(0,1f);
                mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_nerves_font_size));
                mClockView.setPadding(0,20,0,20);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
                mKeyguardSlice.setPadding(0,(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
        getResources().getDimensionPixelSize(R.dimen.widget_clock_shishu_date_padding), 
        getResources().getDisplayMetrics()),0,0
                );
                mTextClock.setVisibility(View.GONE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                break;
            case 17: // custom text clock
                mTextClock.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                       View.GONE) : View.VISIBLE);
                mKeyguardSlice.setVisibility(mShowInfo ? View.VISIBLE : View.GONE);
                mClockView.setVisibility(View.GONE);
                mCustomClockView.setVisibility(View.GONE);
                mSpideyClockView.setVisibility(View.GONE);
                mCustomNumClockView.setVisibility(View.GONE);
                mDotClockView.setVisibility(View.GONE);
                mSpectrumClockView.setVisibility(View.GONE);
                mSneekyClockView.setVisibility(View.GONE);
        }
    }

    private void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();

        mShowClock = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_CLOCK, 1, UserHandle.USER_CURRENT) == 1;
        mShowInfo = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_INFO, 1, UserHandle.USER_CURRENT) == 1;
        mClockSelection = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT);
        mTextClockAlign = Settings.System.getIntForUser(resolver,
                Settings.System.LOCKSCREEN_TEXT_CLOCK_ALIGN, 0, UserHandle.USER_CURRENT);

        setStyle();
    }

    private void setStyle() {
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                mKeyguardSlice.getLayoutParams();
        switch (mClockSelection) {
            case 0: // default digital
            default:
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(true);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 1: // digital (bold)
            case 9: // digital (accent full)
			case 10: // digital (accent hour)
			case 11: // digital (accent minutes)
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(true);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 2: // custom analog
                params.addRule(RelativeLayout.BELOW, R.id.custom_clock_view);
                break;
            case 3: // sammy
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(false);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 4: // sammy (bold)
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(false);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 5: // sammy accent
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(false);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 6: // sammy accent
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(false);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 7: // spidey analog
                params.addRule(RelativeLayout.BELOW, R.id.spidey_clock_view);
                break;
            case 8: // custom analog with numbers
                params.addRule(RelativeLayout.BELOW, R.id.custom_num_clock_view);
                break;
            case 12: // dot analog
                params.addRule(RelativeLayout.BELOW, R.id.dot_clock_view);
                break;
            case 13: // spectrum analog
                params.addRule(RelativeLayout.BELOW, R.id.spectrum_clock_view);
                break;
            case 14: // sneeky analog
                params.addRule(RelativeLayout.BELOW, R.id.sneeky_clock_view);
                break;
            case 15: // Gradient with dark clock
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(false);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 16: // Monochrome gradient, like the qs tile
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(false);
                mClockView.setGravity(Gravity.CENTER);
                break;
            case 17: // custom text clock
                params.addRule(RelativeLayout.BELOW, R.id.custom_textclock_view);
                updateTextClockAlign();
                break;
        }
        updateVisibilities();
        updateDozeVisibleViews();
    }

    private void prepareSmallView(boolean small) {
        if (mWasLatestViewSmall == small) return;
        mWasLatestViewSmall = small;
        if (small) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                    mKeyguardSlice.getLayoutParams();
            params.addRule(RelativeLayout.BELOW, R.id.clock_view);
            mClockView.setSingleLine(true);
            mClockView.setGravity(Gravity.CENTER);
            mClockView.setVisibility(mDarkAmount != 1 ? (mShowClock ? View.VISIBLE :
                    View.GONE) : View.VISIBLE);
            mCustomClockView.setVisibility(View.GONE);
            mSpideyClockView.setVisibility(View.GONE);
            mCustomNumClockView.setVisibility(View.GONE);
            mDotClockView.setVisibility(View.GONE);
            mSpectrumClockView.setVisibility(View.GONE);
            mSneekyClockView.setVisibility(View.GONE);
            mTextClock.setVisibility(View.GONE);
        } else {
            setStyle();
            refreshTime();
        }
    }

    public void updateAll() {
        updateSettings();
        mKeyguardSlice.updateSettings();
        mKeyguardSlice.refresh();
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
        mClockSeparator.setBackgroundColor(blendedTextColor);
        mCustomClockView.setDark(dark);
		mSpideyClockView.setDark(dark);
		mCustomNumClockView.setDark(dark);
		mDotClockView.setDark(dark);
        mSpectrumClockView.setDark(dark);
        mSneekyClockView.setDark(dark);
        updateVisibilities();
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

    private void updateDozeVisibleViews() {
        for (View child : mVisibleInDoze) {
            child.setAlpha(mDarkAmount == 1 && mPulsing ? 0.8f : 1);
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

