package com.android.keyguard;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.res.Resources;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Annotation;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.TextView;
import android.os.Bundle;
import android.os.Handler;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.NotificationColorUtil;
import com.android.keyguard.R;
import com.android.systemui.Dependency;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.tuner.TunerService;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

public class TypographicClock extends TextView implements
            ColorExtractor.OnColorsChangedListener, TunerService.Tunable {

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

    private String mDescFormat;
    private final String[] mHours;
    private final String[] mMinutes;
    private final Resources mResources;
    private final Calendar mTime;
    private TimeZone mTimeZone;
    private SysuiColorExtractor mColorExtractor;

    private int mPrimaryColor;
    private int mAmbientColor;
    private int mSystemAccent;
    private int mFallbackColor;
    private int mCurrentAccent;
    private float mDarkAmount;
    private float[] mHslOut = new float[3];

    private boolean mAttached;
    private int mLockClockFontStyle;
    private boolean mUseAccentColor;

    private static final String LOCK_CLOCK_FONT_STYLE =
            "system:" + Settings.System.LOCK_CLOCK_FONT_STYLE;
    private static final String LOCKSCREEN_CLOCK_SELECTION =
            "system:" + Settings.System.LOCKSCREEN_CLOCK_SELECTION;

    private final BroadcastReceiver mTimeZoneChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                onTimeZoneChanged(TimeZone.getTimeZone(tz));
            }
            onTimeChanged();
            invalidate();
        }
    };

    public TypographicClock(Context context) {
        this(context, null);
    }

    public TypographicClock(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public TypographicClock(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);

        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        mColorExtractor.addOnColorsChangedListener(this);
        mTime = Calendar.getInstance(TimeZone.getDefault());
        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
        mResources = context.getResources();
        mHours = mResources.getStringArray(R.array.type_clock_hours);
        mMinutes = mResources.getStringArray(R.array.type_clock_minutes);
        mSystemAccent = mResources.getColor(R.color.accent_device_default_light, null);
        mFallbackColor = mResources.getColor(R.color.custom_text_clock_top_fallback_color, null);
        onColorsChanged(mColorExtractor, 0);
    }

    public void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        setContentDescription(DateFormat.format(mDescFormat, mTime));
        int hours = mTime.get(Calendar.HOUR) % 12;
        int minutes = mTime.get(Calendar.MINUTE) % 60;
        SpannedString rawFormat = (SpannedString) mResources.getQuantityText(R.plurals.type_clock_header, hours);
        Annotation[] annotationArr = (Annotation[]) rawFormat.getSpans(0, rawFormat.length(), Annotation.class);
        SpannableString colored = new SpannableString(rawFormat);
        for (Annotation annotation : annotationArr) {
            if ("color".equals(annotation.getValue())) {
                colored.setSpan(new ForegroundColorSpan(mCurrentAccent),
                        colored.getSpanStart(annotation),
                        colored.getSpanEnd(annotation),
                        Spanned.SPAN_POINT_POINT);
            }
        }
        setText(TextUtils.expandTemplate(colored, new CharSequence[]{mHours[hours], mMinutes[minutes]}));
    }

    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mTime.setTimeZone(timeZone);
    }

    @Override
    public void onColorsChanged(ColorExtractor extractor, int which) {
        GradientColors colors = extractor.getColors(WallpaperManager.FLAG_LOCK);
        setWallpaperColors(colors.getMainColor(), colors.supportsDarkText(), colors.getColorPalette());
    }

    private void setWallpaperColors(int mainColor, boolean supportsDarkText, int[] colorPalette) {
        int scrimColor = supportsDarkText ? Color.WHITE : Color.BLACK;
        int scrimTinted = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(scrimColor, mainColor, 0.5f), 64);
        int bgColor = ColorUtils.compositeColors(scrimTinted, mainColor);

        int paletteColor = getColorFromPalette(colorPalette);
        bgColor = ColorUtils.compositeColors(bgColor, Color.BLACK);
        mPrimaryColor = findColor(paletteColor, bgColor, !supportsDarkText, mSystemAccent, mFallbackColor);
        mAmbientColor = findColor(paletteColor, Color.BLACK, true, mSystemAccent, mFallbackColor);

        setDarkAmount(mDarkAmount);
    }

    private int getColorFromPalette(int[] palette) {
        if (palette != null && palette.length != 0 && !mUseAccentColor) {
            return palette[Math.max(0, palette.length - 5)];
        } else {
            return mSystemAccent;
        }
    }

    private int findColor(int color, int background, boolean againstDark, int accent, int fallback) {
        if (!isGreyscale(color)) {
            return color;
        }
        int contrastAccent = NotificationColorUtil.ensureTextContrast(accent, background, againstDark);
        if (!isGreyscale(contrastAccent)) {
            return contrastAccent;
        } else {
            return fallback;
        }
    }

    private boolean isGreyscale(int color) {
        ColorUtils.RGBToHSL(Color.red(color), Color.green(color), Color.blue(color), mHslOut);
        return mHslOut[1] < 0.1f || mHslOut[2] < 0.1f;
    }

    public void setDarkAmount(float dark) {
        mDarkAmount = dark;
        mCurrentAccent = ColorUtils.blendARGB(mPrimaryColor, mAmbientColor, mDarkAmount);
        onTimeChanged();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mAttached)
            return;

        mAttached = true;

        Calendar calendar = mTime;
        TimeZone timeZone = mTimeZone;
        if (timeZone == null) {
            timeZone = TimeZone.getDefault();
        }
        calendar.setTimeZone(timeZone);
        onTimeChanged();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);

        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, LOCK_CLOCK_FONT_STYLE);
        tunerService.addTunable(this, LOCKSCREEN_CLOCK_SELECTION);

        getContext().registerReceiver(mTimeZoneChangedReceiver, filter);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (!mAttached)
            return;

        Dependency.get(TunerService.class).removeTunable(this);
        getContext().unregisterReceiver(mTimeZoneChangedReceiver);
        mColorExtractor.removeOnColorsChangedListener(this);
        mAttached = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
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
            case LOCKSCREEN_CLOCK_SELECTION:
                int clockSelection = 1;
                try {
                    clockSelection = Integer.valueOf(newValue);
                } catch (NumberFormatException ex) {}
                mUseAccentColor =
                        clockSelection == 17;
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

}
