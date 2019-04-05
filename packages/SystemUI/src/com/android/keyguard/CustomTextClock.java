/*
**
** Copyright 2019, Pearl Project
** Copyright 2019, Havoc-OS
** Copyright 2019, Descendant
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.keyguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.internal.util.ArrayUtils;
import com.android.keyguard.ColorText;
import com.android.keyguard.LangGuard;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;

import java.lang.IllegalStateException;
import java.lang.NullPointerException;
import java.lang.String;
import java.util.Locale;
import java.util.TimeZone;

public class CustomTextClock extends TextView implements TunerService.Tunable {

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

    private String topText = getResources().getString(R.string.custom_text_clock_top_text_default);
    private String[] TensString = getResources().getStringArray(R.array.TensString);
    private String[] UnitsString = getResources().getStringArray(R.array.UnitsString);
    private String[] TensStringH = getResources().getStringArray(R.array.TensStringH);
    private String[] UnitsStringH = getResources().getStringArray(R.array.UnitsStringH);
    private String[] langExceptions = getResources().getStringArray(R.array.langExceptions);
    private String curLang = Locale.getDefault().getLanguage();

    private Time mCalendar;
    private boolean mAttached;
    private int handType;
    private Context mContext;
    private boolean h24;
    private boolean langHasChanged;
    private int mLockClockFontStyle;

    private static final String LOCK_CLOCK_FONT_STYLE =
            "system:" + Settings.System.LOCK_CLOCK_FONT_STYLE;

    public CustomTextClock(Context context) {
        this(context, null);
    }

    public CustomTextClock(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.CustomTextClock);

        handType = a.getInteger(R.styleable.CustomTextClock_HandType, 2);

        mContext = context;
        mCalendar = new Time();

        setLineSpacing(0, 0.8f);
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
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);

            // OK, this is gross but needed. This class is supported by the
            // remote views machanism and as a part of that the remote views
            // can be inflated by a context for another user without the app
            // having interact users permission - just for loading resources.
            // For exmaple, when adding widgets from a user profile to the
            // home screen. Therefore, we register the receiver as the current
            // user not the one the context is for.
            getContext().registerReceiverAsUser(mIntentReceiver,
                    android.os.Process.myUserHandle(), filter, null, getHandler());

            final TunerService tunerService = Dependency.get(TunerService.class);
            tunerService.addTunable(this, LOCK_CLOCK_FONT_STYLE);
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = new Time();

        // Make sure we update to the current time
        onTimeChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            Dependency.get(TunerService.class).removeTunable(this);
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (handType == 2) {
            if (langHasChanged) {
                setText(topText);
                langHasChanged = false;
            }
            setTextColor(ColorText.getWallColor(mContext));
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCK_CLOCK_FONT_STYLE:
                mLockClockFontStyle = 4;
                try {
                    mLockClockFontStyle = Integer.valueOf(newValue);
                } catch (NumberFormatException ex) {}
                refreshFontStyle();
                break;
            default:
                break;
        }
    }

    private void refreshFontStyle() {
        switch (mLockClockFontStyle) {
            case FONT_NORMAL:
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case FONT_ITALIC:
                setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case FONT_BOLD:
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case FONT_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case FONT_LIGHT:
                setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case FONT_THIN:
                setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case FONT_THIN_ITALIC:
                setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case FONT_CONDENSED:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_LIGHT:
                setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_BOLD:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case FONT_CONDENSED_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case FONT_MEDIUM:
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case FONT_MEDIUM_ITALIC:
                setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                break;
            case FONT_BLACK:
                setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case FONT_BLACK_ITALIC:
                setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                break;
            case FONT_DANCINGSCRIPT:
                setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                break;
            case FONT_DANCINGSCRIPT_BOLD:
                setTypeface(Typeface.create("cursive", Typeface.BOLD));
                break;
            case FONT_COMINGSOON:
                setTypeface(Typeface.create("casual", Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF:
                setTypeface(Typeface.create("serif", Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF_ITALIC:
                setTypeface(Typeface.create("serif", Typeface.ITALIC));
                break;
            case FONT_NOTOSERIF_BOLD:
                setTypeface(Typeface.create("serif", Typeface.BOLD));
                break;
            case FONT_NOTOSERIF_BOLD_ITALIC:
                setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                break;
            default:
                break;
        }
    }

    private void onTimeChanged() {
        mCalendar.setToNow();
        h24 = DateFormat.is24HourFormat(getContext());

        int hour = mCalendar.hour;
        int minute = mCalendar.minute;

        if (!h24) {
            if (hour > 12) {
                hour = hour - 12;
            }
        }

        switch(handType){
            case 0:
                if (curLang == "nl" && minute <= 9 && minute != 0) {
                    setText(getIntStringMinOneLiner(minute));
                } else {
                    setText(getIntStringHour(hour));
                }
                break;

            case 1:
                if (minute == 0) {
                    setText(UnitsString[0]);
                }
                if (!LangGuard.isAvailable(langExceptions,curLang) && minute != 0) {
                    setVisibility(VISIBLE);
                    setText(getIntStringMinFirstRow(minute));
                }
                if (LangGuard.isAvailable(langExceptions,curLang)) {
                    setVisibility(VISIBLE);
                    setText(getIntStringMinOneLiner(minute));
                }
                if (curLang == "nl" && minute <= 9 && minute != 0) {
                    setVisibility(VISIBLE);
                    setText(getIntStringHour(hour));
                }
                break;

            case 3:
                if (!LangGuard.isAvailable(langExceptions,curLang)) {
                    if (getIntStringMinSecondRow(minute).contains("Clock") || getIntStringMinSecondRow(minute).contains("null")) {
                        setVisibility(GONE);
                    } else { 
                        setText(getIntStringMinSecondRow(minute));
                        setVisibility(VISIBLE);
                    }
                } 
                if (LangGuard.isAvailable(langExceptions,curLang)) { 
                    setVisibility(GONE); 
                } 
                break;

            default:
                break;
        }
        updateContentDescription(mCalendar, getContext());
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = new Time(TimeZone.getTimeZone(tz).getID());
            }

            if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                langHasChanged = true;
                curLang = Locale.getDefault().getLanguage();
                topText = getResources().getString(R.string.custom_text_clock_top_text_default);
                TensString = getResources().getStringArray(R.array.TensString);
                UnitsString = getResources().getStringArray(R.array.UnitsString);
                TensStringH = getResources().getStringArray(R.array.TensStringH);
                UnitsStringH = getResources().getStringArray(R.array.UnitsStringH);
            }

            onTimeChanged();
            invalidate();
        }
    };

    private void updateContentDescription(Time time, Context mContext) {
        final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR;
        String contentDescription = DateUtils.formatDateTime(mContext,
                time.toMillis(false), flags);
        setContentDescription(contentDescription);
    }

    private String getIntStringHour (int num) {
        int tens, units;
        String NumString = "";
        units = num % 10 ;
        tens =  num / 10;

        if(num >= 20) {
            if ( units == 0 && !LangGuard.isAvailable(langExceptions,curLang)) {
                NumString = TensStringH[tens];
            } else {
                if (LangGuard.isAvailable(langExceptions,curLang)) {
                    NumString = LangGuard.evaluateExHr(curLang, units, TensString, UnitsString, tens, num, UnitsStringH, TensStringH, h24);
                } else {
                    NumString = TensString[tens]+" "+UnitsString[units].substring(2, UnitsString[units].length());
                }
            }
        } else {
            if (num < 20 && num != 0) {
                NumString = UnitsStringH[num];
            }
            if (num == 0 && curLang == "pl") {
                NumString = LangGuard.evaluateExHr(curLang, units, TensString, UnitsString, tens, num, UnitsStringH, TensStringH, h24);
            }        
            if (num == 0 && curLang != "pl") {
                NumString = UnitsStringH[num];
            }
        }

        return NumString;
    }

    private String getIntStringMinFirstRow (int num) {
        int tens, units;
        units = num % 10;
        tens =  num / 10;
        String NumString = "";
        if ( units == 0 ) {
            NumString = TensString[tens];
        } else if (num < 10 ) {
            NumString = UnitsString[num];
        } else if (num >= 10 && num < 20) {
            NumString = UnitsString[num];
        } else if (num >= 20) {
            NumString= TensString[tens];
        }
        return NumString;
    }

    private String getIntStringMinSecondRow (int num) {   
        int units = num % 10;
        String NumString = "";
        if(num >= 20) {
            NumString = UnitsString[units].substring(2, UnitsString[units].length());
            return NumString;
        } 
        if (num <= 20) {
            return "null";
        }
        return NumString;
    }

    private String getIntStringMinOneLiner (int num) {
        int tens, units;
        String NumString = "";
        if(num >= 20) {
            units = num % 10 ;
            tens =  num / 10;
            if ( units == 0 ) {
                NumString = TensString[tens];
            } else {
                if (LangGuard.isAvailable(langExceptions,curLang)) {
                    NumString = LangGuard.evaluateExMin(curLang, units, TensString, UnitsString, tens);
                } else {
                    NumString = TensString[tens]+" "+UnitsString[units].substring(2, UnitsString[units].length());
                }
            }
        } else { 
            if (num < 10 ) {
                NumString = UnitsString[num];
            }
            if (num >= 10 && num < 20) {
                NumString = UnitsString[num];
            }
        }
        return NumString;
    }
}
