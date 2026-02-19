/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.icu.lang.UCharacter;
import android.icu.text.DateTimePatternGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.core.StatusBarRootModernization;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import lineageos.providers.LineageSettings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements
        DemoModeCommandReceiver,
        Tunable,
        CommandQueue.Callbacks,
        DarkReceiver {

    private static final String CLOCK_SUPER_PARCELABLE = "clock_super_parcelable";
    private static final String CURRENT_USER_ID = "current_user_id";
    private static final String VISIBLE_BY_POLICY = "visible_by_policy";
    private static final String VISIBLE_BY_USER = "visible_by_user";
    private static final String SHOW_SECONDS = "show_seconds";
    private static final String VISIBILITY = "visibility";

    public static final String STATUS_BAR_CLOCK_SECONDS =
            "system:" + Settings.System.STATUS_BAR_CLOCK_SECONDS;
    private static final String STATUS_BAR_AM_PM =
            "lineagesystem:" + LineageSettings.System.STATUS_BAR_AM_PM;
    public static final String STATUS_BAR_CLOCK_DATE_DISPLAY =
            "system:" + Settings.System.STATUS_BAR_CLOCK_DATE_DISPLAY;
    public static final String STATUS_BAR_CLOCK_DATE_STYLE =
            "system:" + Settings.System.STATUS_BAR_CLOCK_DATE_STYLE;
    public static final String STATUS_BAR_CLOCK_DATE_POSITION =
            "system:" + Settings.System.STATUS_BAR_CLOCK_DATE_POSITION;
    public static final String STATUS_BAR_CLOCK_DATE_FORMAT =
            "system:" + Settings.System.STATUS_BAR_CLOCK_DATE_FORMAT;

    private final UserTracker mUserTracker;
    private final CommandQueue mCommandQueue;
    private int mCurrentUserId;

    private boolean mClockVisibleByPolicy = true;
    private boolean mClockVisibleByUser = true;

    private boolean mAttached;
    private boolean mScreenReceiverRegistered;
    private Calendar mCalendar;
    private String mContentDescriptionFormatString;
    private SimpleDateFormat mClockFormat;
    private SimpleDateFormat mContentDescriptionFormat;
    private Locale mLocale;
    private DateTimePatternGenerator mDateTimePatternGenerator;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private static final int CLOCK_DATE_DISPLAY_GONE = 0;
    private static final int CLOCK_DATE_DISPLAY_SMALL = 1;
    private static final int CLOCK_DATE_DISPLAY_NORMAL = 2;

    private static final int CLOCK_DATE_STYLE_REGULAR = 0;
    private static final int CLOCK_DATE_STYLE_LOWERCASE = 1;
    private static final int CLOCK_DATE_STYLE_UPPERCASE = 2;

    private static final int STYLE_DATE_LEFT = 0;
    private static final int STYLE_DATE_RIGHT = 1;

    private int mAmPmStyle = AM_PM_STYLE_GONE;
    private boolean mShowSeconds;
    private Handler mSecondsHandler;
    private int mClockDateDisplay = CLOCK_DATE_DISPLAY_GONE;
    private int mClockDateStyle = CLOCK_DATE_STYLE_REGULAR;
    private int mClockDatePosition;
    private String mClockDateFormat = null;

    private boolean mIsStatusBar;
    private boolean useStaticColor = false;
    private int lastDynamicColor = -1;

    // Tracks config changes that will make the clock change dimensions
    private final InterestingConfigChanges mInterestingConfigChanges;
    /**
     * Color to be set on this {@link TextView}, when wallpaperTextColor is <b>not</b> utilized.
     */
    private int mNonAdaptedColor;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mCurrentUserId = newUser;
                    updateClock();
                }
            };

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mCommandQueue = Dependency.get(CommandQueue.class);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.Clock,
                0, 0);
        try {
            mAmPmStyle = LineageSettings.System.getIntForUser(
                context.getContentResolver(), LineageSettings.System.STATUS_BAR_AM_PM,
                AM_PM_STYLE_GONE, UserHandle.USER_CURRENT);
            mIsStatusBar = a.getBoolean(R.styleable.Clock_isStatusBar, mIsStatusBar);
            mNonAdaptedColor = getCurrentTextColor();
        } finally {
            a.recycle();
        }
        mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mUserTracker = Dependency.get(UserTracker.class);
        if (ShadeWindowGoesAround.isEnabled()) {
            mInterestingConfigChanges = new InterestingConfigChanges(
                    ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_DENSITY);
        } else {
            mInterestingConfigChanges = null;
        }

        setIncludeFontPadding(false);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(CLOCK_SUPER_PARCELABLE, super.onSaveInstanceState());
        bundle.putInt(CURRENT_USER_ID, mCurrentUserId);
        bundle.putBoolean(VISIBLE_BY_POLICY, mClockVisibleByPolicy);
        bundle.putBoolean(VISIBLE_BY_USER, mClockVisibleByUser);
        bundle.putBoolean(SHOW_SECONDS, mShowSeconds);
        bundle.putInt(VISIBILITY, getVisibility());

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !(state instanceof Bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }

        Bundle bundle = (Bundle) state;
        Parcelable superState = bundle.getParcelable(CLOCK_SUPER_PARCELABLE);
        super.onRestoreInstanceState(superState);
        if (bundle.containsKey(CURRENT_USER_ID)) {
            mCurrentUserId = bundle.getInt(CURRENT_USER_ID);
        }
        mClockVisibleByPolicy = bundle.getBoolean(VISIBLE_BY_POLICY, true);
        mClockVisibleByUser = bundle.getBoolean(VISIBLE_BY_USER, true);
        mShowSeconds = bundle.getBoolean(SHOW_SECONDS, false);
        if (bundle.containsKey(VISIBILITY)) {
            super.setVisibility(bundle.getInt(VISIBILITY));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);

            // NOTE: This receiver could run before this method returns, as it's not dispatching
            // on the main thread and BroadcastDispatcher may not need to register with Context.
            // The receiver will return immediately if the view does not have a Handler yet.
            mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter,
                    Dependency.get(Dependency.TIME_TICK_HANDLER), UserHandle.ALL);
            Dependency.get(TunerService.class).addTunable(this,
                    STATUS_BAR_CLOCK_SECONDS,
                    STATUS_BAR_AM_PM,
                    STATUS_BAR_CLOCK_DATE_DISPLAY,
                    STATUS_BAR_CLOCK_DATE_STYLE,
                    STATUS_BAR_CLOCK_DATE_POSITION,
                    STATUS_BAR_CLOCK_DATE_FORMAT);
            mCommandQueue.addCallback(this);
            mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
            mCurrentUserId = mUserTracker.getUserId();
        }

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());
        mContentDescriptionFormatString = "";
        mDateTimePatternGenerator = null;

        // Make sure we update to the current time
        updateClock();
        if (!StatusBarRootModernization.isEnabled()) {
            updateClockVisibility();
        }
        updateShowSeconds();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mScreenReceiverRegistered) {
            mScreenReceiverRegistered = false;
            mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
            if (mSecondsHandler != null) {
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
            }
        }
        if (mAttached) {
            mBroadcastDispatcher.unregisterReceiver(mIntentReceiver);
            mAttached = false;
            Dependency.get(TunerService.class).removeTunable(this);
            mCommandQueue.removeCallback(this);
            mUserTracker.removeCallback(mUserChangedCallback);
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If the handler is null, it means we received a broadcast while the view has not
            // finished being attached or in the process of being detached.
            // In that case, do not post anything.
            Handler handler = getHandler();
            if (handler == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                handler.post(() -> {
                    mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                    if (mClockFormat != null) {
                        mClockFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                    if (mContentDescriptionFormat != null) {
                        mContentDescriptionFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                });
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                handler.post(() -> {
                    if (!newLocale.equals(mLocale)) {
                        mLocale = newLocale;
                        // Force refresh of dependent variables.
                        mContentDescriptionFormatString = "";
                        mDateTimePatternGenerator = null;
                    }
                });
            }
            handler.post(() -> updateClock());
        }
    };

    @Override
    public void setVisibility(int visibility) {
        if (!StatusBarRootModernization.isEnabled()) {
            if (visibility == View.VISIBLE && !shouldBeVisible()) {
                return;
            }
        }

        super.setVisibility(visibility);
    }

    private void setClockVisibleByUser(boolean visible) {
        StatusBarRootModernization.assertInLegacyMode();

        mClockVisibleByUser = visible;
        updateClockVisibility();
    }

    private void setClockVisibilityByPolicy(boolean visible) {
        StatusBarRootModernization.assertInLegacyMode();

        mClockVisibleByPolicy = visible;
        updateClockVisibility();
    }

    private boolean shouldBeVisible() {
        return mClockVisibleByPolicy && mClockVisibleByUser;
    }

    private void updateClockVisibility() {
        StatusBarRootModernization.assertInLegacyMode();

        boolean visible = shouldBeVisible();
        int visibility = visible ? View.VISIBLE : View.GONE;
        super.setVisibility(visibility);
    }

    final void updateClock(boolean forceTextUpdate) {
        if (mDemoMode || mCalendar == null) return;
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        CharSequence smallTime = getSmallTime();
        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        if (forceTextUpdate || !TextUtils.equals(smallTime, getText())) {
            setText(smallTime);
        }
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    final void updateClock() {
        updateClock(false);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case STATUS_BAR_CLOCK_SECONDS:
                mShowSeconds =
                        TunerService.parseIntegerSwitch(newValue, false);
                updateShowSeconds();
                break;
            case STATUS_BAR_AM_PM:
                mAmPmStyle =
                        TunerService.parseInteger(newValue, AM_PM_STYLE_GONE);
                break;
            case STATUS_BAR_CLOCK_DATE_DISPLAY:
                mClockDateDisplay =
                        TunerService.parseInteger(newValue, CLOCK_DATE_DISPLAY_GONE);
                break;
            case STATUS_BAR_CLOCK_DATE_STYLE:
                mClockDateStyle =
                        TunerService.parseInteger(newValue, CLOCK_DATE_STYLE_REGULAR);
                break;
            case STATUS_BAR_CLOCK_DATE_POSITION:
                mClockDatePosition =
                        TunerService.parseInteger(newValue, STYLE_DATE_LEFT);
                break;
            case STATUS_BAR_CLOCK_DATE_FORMAT:
                mClockDateFormat = newValue;
                break;
            default:
                break;
        }
        // Force refresh of dependent variables.
        mContentDescriptionFormatString = "";
        mDateTimePatternGenerator = null;
        updateClock(true);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (StatusBarRootModernization.isEnabled()) {
            return;
        }

        if (displayId != getDisplay().getDisplayId()) {
            return;
        }
        boolean clockVisibleByPolicy = (state1 & StatusBarManager.DISABLE_CLOCK) == 0;
        if (clockVisibleByPolicy != mClockVisibleByPolicy) {
            setClockVisibilityByPolicy(clockVisibleByPolicy);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mNonAdaptedColor = DarkIconDispatcher.getTint(areas, this, tint);
        lastDynamicColor = mNonAdaptedColor;
        if (useStaticColor) return;
        setTextColor(lastDynamicColor);
    }

    // Update text color based when shade scrim changes color.
    public void onColorsChanged(boolean lightTheme) {
        final Context context = new ContextThemeWrapper(mContext,
                lightTheme ? R.style.Theme_SystemUI_LightWallpaper : R.style.Theme_SystemUI);
        lastDynamicColor = Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor);
        if (useStaticColor) return;
        setTextColor(lastDynamicColor);
    }

    public void setStaticColor(boolean enable) {
        useStaticColor = enable;
        if (!useStaticColor && lastDynamicColor != -1) {
            setTextColor(lastDynamicColor);
        }
    }

    public void onDensityOrFontScaleChanged() {
        ShadeWindowGoesAround.assertInLegacyMode();
        // Note that this class is not being registered as configuration listener when used
        // from compose. It will instead receive a normal "View#onConfigurationChanged".
        reloadDimens();
    }

    private void reloadDimens() {
        FontSizeUtils.updateFontSize(this, R.dimen.status_bar_clock_size);

        // Note: The padding for the clock in the shade is controlled by ShadeHeaderController so
        // this just affects the status bar clock.
        setPaddingRelative(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_starting_padding),
                0,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_end_padding),
                0);
    }


    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (ShadeWindowGoesAround.isEnabled()) {
            final boolean shouldReloadDimensions =
                    mInterestingConfigChanges.applyNewConfig(newConfig);
            if (shouldReloadDimensions) {
                reloadDimens();
            }
        }
    }

    private void updateShowSeconds() {
        if (mShowSeconds) {
            // Wait until we have a display to start trying to show seconds.
            if (mSecondsHandler == null && getDisplay() != null) {
                mSecondsHandler = new Handler();
                if (getDisplay().getState() == Display.STATE_ON) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
                mScreenReceiverRegistered = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                mBroadcastDispatcher.registerReceiver(mScreenReceiver, filter);
            }
            setFontFeatureSettings("tnum");
        } else {
            if (mSecondsHandler != null) {
                mScreenReceiverRegistered = false;
                mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
                updateClock();
            }
            setFontFeatureSettings(null);
        }
    }

    private final CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context, mCurrentUserId);
        if (mDateTimePatternGenerator == null) {
            // Despite its name, getInstance creates a cloned instance, so reuse the generator to
            // avoid unnecessary churn.
            mDateTimePatternGenerator = DateTimePatternGenerator.getInstance(
                    context.getResources().getConfiguration().locale);
        }

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        final String formatSkeleton = mShowSeconds
                ? is24 ? "Hms" : "hms"
                : is24 ? "Hm" : "hm";
        String format = mDateTimePatternGenerator.getBestPattern(formatSkeleton);
        if (!format.equals(mContentDescriptionFormatString)) {
            mContentDescriptionFormatString = format;
            mContentDescriptionFormat = new SimpleDateFormat(format);
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add marker characters around it to let us find it again after
             * formatting and change its size.
             */
            if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && UCharacter.isUWhiteSpace(format.charAt(a - 1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                            + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = new SimpleDateFormat(format);
        }

        CharSequence dateString = null;

        String result = "";
        String timeResult = mClockFormat.format(mCalendar.getTime());
        String dateResult = "";

        if (mIsStatusBar && mClockDateDisplay != CLOCK_DATE_DISPLAY_GONE) {
            Date now = new Date();

            if (mClockDateFormat == null || mClockDateFormat.isEmpty()) {
                // Set dateString to short uppercase Weekday if empty
                dateString = DateFormat.format("EEE", now);
            } else {
                dateString = DateFormat.format(mClockDateFormat, now);
            }
            if (mClockDateStyle == CLOCK_DATE_STYLE_LOWERCASE) {
                // When Date style is small, convert date to uppercase
                dateResult = dateString.toString().toLowerCase();
            } else if (mClockDateStyle == CLOCK_DATE_STYLE_UPPERCASE) {
                dateResult = dateString.toString().toUpperCase();
            } else {
                dateResult = dateString.toString();
            }
            result = (mClockDatePosition == STYLE_DATE_LEFT) ? dateResult + " " + timeResult
                    : timeResult + " " + dateResult;
        } else {
            // No date, just show time
            result = timeResult;
        }

        SpannableStringBuilder formatted = new SpannableStringBuilder(result);

        if (mClockDateDisplay != CLOCK_DATE_DISPLAY_NORMAL) {
            if (dateString != null) {
                int dateStringLen = dateString.length();
                int timeStringOffset = (mClockDatePosition == STYLE_DATE_RIGHT)
                        ? timeResult.length() + 1 : 0;
                if (mClockDateDisplay == CLOCK_DATE_DISPLAY_GONE) {
                   formatted.delete(0, dateStringLen);
                } else {
                    if (mClockDateDisplay == CLOCK_DATE_DISPLAY_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, timeStringOffset,
                                timeStringOffset + dateStringLen,
                                Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                }
            }
        }

        if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                if (mAmPmStyle == AM_PM_STYLE_GONE) {
                    formatted.delete(magic1, magic2 + 1);
                } else {
                    if (mAmPmStyle == AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
            }
        }

        return formatted;
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // Only registered for COMMAND_CLOCK
        String millis = args.getString("millis");
        String hhmm = args.getString("hhmm");
        if (millis != null) {
            mCalendar.setTimeInMillis(Long.parseLong(millis));
        } else if (hhmm != null && hhmm.length() == 4) {
            int hh = Integer.parseInt(hhmm.substring(0, 2));
            int mm = Integer.parseInt(hhmm.substring(2));
            boolean is24 = DateFormat.is24HourFormat(getContext(), mCurrentUserId);
            if (is24) {
                mCalendar.set(Calendar.HOUR_OF_DAY, hh);
            } else {
                mCalendar.set(Calendar.HOUR, hh);
            }
            mCalendar.set(Calendar.MINUTE, mm);
        }
        setText(getSmallTime());
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    @Override
    public void onDemoModeStarted() {
        mDemoMode = true;
    }

    @Override
    public void onDemoModeFinished() {
        mDemoMode = false;
        updateClock();
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.removeCallbacks(mSecondTick);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
            }
        }
    };

    private final Runnable mSecondTick = new Runnable() {
        @Override
        public void run() {
            if (mCalendar != null) {
                updateClock();
            }
            mSecondsHandler.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
        }
    };
}

