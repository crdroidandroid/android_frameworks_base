/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Paint.Style;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.TextClock;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import static com.android.systemui.statusbar.phone
        .KeyguardClockPositionAlgorithm.CLOCK_USE_DEFAULT_Y;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class OronosClockController implements ClockPlugin {

    /**
     * Resources used to get title and thumbnail.
     */
    private final Resources mResources;

    /**
     * LayoutInflater used to inflate custom clock views.
     */
    private final LayoutInflater mLayoutInflater;

    /**
     * Extracts accent color from wallpaper.
     */
    private final SysuiColorExtractor mColorExtractor;

    /**
     * Renders preview from clock view.
     */
    private final ViewPreviewer mRenderer = new ViewPreviewer();

    /**
     * Root view of clock.
     */
    private ClockLayout mView;

    /**
     * Text clock for both hour and minute
     */
    private TextClock mHourClock;
    private TextClock mMinuteClock;
    private TextView mLongDate;

    /**
     * Time and calendars to check the date
     */
    private final Calendar mTime = Calendar.getInstance(TimeZone.getDefault());
    private String mDescFormat;
    private TimeZone mTimeZone;

    /**
     * Create a DefaultClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public OronosClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
    }

    private void createViews() {
        mView = (ClockLayout) mLayoutInflater
                .inflate(R.layout.oronos_clock, null);
        mHourClock = mView.findViewById(R.id.clockHr);
        mMinuteClock = mView.findViewById(R.id.clockMin);
        mLongDate = mView.findViewById(R.id.longDate);
        onTimeTick();
        ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK);
        int[] paletteLS = colors.getColorPalette();
        if (paletteLS != null) {
            setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
        } else {
            ColorExtractor.GradientColors sysColors = mColorExtractor.getColors(
                    WallpaperManager.FLAG_SYSTEM);
            setColorPalette(sysColors.supportsDarkText(), sysColors.getColorPalette());
        }
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mHourClock = null;
        mMinuteClock = null;
        mLongDate = null;
    }

    @Override
    public String getName() {
        return "oronos";
    }

    @Override
    public String getTitle() {
        return "Oroño";
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.oronos_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {

        View previewView = mLayoutInflater.inflate(R.layout.oronos_clock, null);
        TextClock previewHourTime = previewView.findViewById(R.id.clockHr);
        TextClock previewMinuteTime = previewView.findViewById(R.id.clockMin);
        TextView previewDate = previewView.findViewById(R.id.longDate);

        // Initialize state of plugin before generating preview.
        ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK);
        int[] palette = colors.getColorPalette();
        if (palette == null) {
            ColorExtractor.GradientColors sysColors = mColorExtractor.getColors(
                    WallpaperManager.FLAG_SYSTEM);
            palette = sysColors.getColorPalette();
        }

        final int bgColor = palette[Math.max(0, palette.length - 11)];
        final int hiColor = palette[Math.max(0, palette.length - 5)];

        GradientDrawable hourBg = (GradientDrawable) previewHourTime.getBackground();
        GradientDrawable minBg = (GradientDrawable) previewMinuteTime.getBackground();
        GradientDrawable dateBg = (GradientDrawable) previewDate.getBackground();

        // Things that needs to be tinted with the background color
        previewHourTime.setTextColor(bgColor);
        minBg.setColor(bgColor);
        dateBg.setColor(bgColor);

        // Things that needs to be tinted with the highlighted color
        hourBg.setColor(hiColor);
        minBg.setStroke(mResources.getDimensionPixelSize(R.dimen.clock_oronos_outline_size),
                            hiColor);
        dateBg.setStroke(mResources.getDimensionPixelSize(R.dimen.clock_oronos_outline_size),
                            hiColor);
        previewMinuteTime.setTextColor(hiColor);
        previewDate.setTextColor(hiColor);

        mTime.setTimeInMillis(System.currentTimeMillis());
        previewDate.setText(mResources.getString(R.string.date_long_title_today, mTime.getDisplayName(
                Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())));

        return mRenderer.createPreview(previewView, width, height);
    }

    @Override
    public View getView() {
        if (mView == null) {
            createViews();
        }
        return mView;
    }

    @Override
    public View getBigClockView() {
        return null;
    }

    @Override
    public int getPreferredY(int totalHeight) {
        return CLOCK_USE_DEFAULT_Y;
    }

    @Override
    public void setStyle(Style style) {}

    @Override
    public void setTextColor(int color) {}

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        if (colorPalette == null || colorPalette.length == 0) {
            return;
        }
        final int backgroundColor = colorPalette[Math.max(0, colorPalette.length - 11)];
        final int highlightColor = colorPalette[Math.max(0, colorPalette.length - 5)];

        GradientDrawable hourBg = (GradientDrawable) mHourClock.getBackground();
        GradientDrawable minBg = (GradientDrawable) mMinuteClock.getBackground();
        GradientDrawable dateBg = (GradientDrawable) mLongDate.getBackground();

        // Things that needs to be tinted with the background color
        mHourClock.setTextColor(backgroundColor);
        minBg.setColor(backgroundColor);
        dateBg.setColor(backgroundColor);

        // Things that needs to be tinted with the highlighted color
        hourBg.setColor(highlightColor);
        minBg.setStroke(mResources.getDimensionPixelSize(R.dimen.clock_oronos_outline_size),
                            highlightColor);
        dateBg.setStroke(mResources.getDimensionPixelSize(R.dimen.clock_oronos_outline_size),
                            highlightColor);
        mMinuteClock.setTextColor(highlightColor);
        mLongDate.setTextColor(highlightColor);
    }

    @Override
    public void onTimeTick() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        mLongDate.setText(mResources.getString(R.string.date_long_title_today, mTime.getDisplayName(
                Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())));

    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mView.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mTime.setTimeZone(timeZone);
        onTimeTick();
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
