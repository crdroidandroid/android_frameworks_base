/*
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2021 Project 404
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
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;

import java.util.TimeZone;

import static com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm.CLOCK_USE_DEFAULT_Y;

/**
 * Plugin for the default clock face used only to provide a preview.
 */
public class IDEClockController implements ClockPlugin {

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

    private final Context mContext;

    /**
     * Helper to extract colors from wallpaper palette for clock face.
     */
    private final ClockPalette mPalette = new ClockPalette();

    /**
     * Root view of clock.
     */
    private ClockLayout mView;

    /**
     * Text clock for time, date, day and month
     */
    private TextClock mTime;
    private TextClock mDate;
    private TextClock mDay;
    private TextClock mMonth;
    private TextView mtextInclude;
    private TextView mtextStd;
    private TextView mtextUsingNamespace;
    private TextView mtextIntMain;
    private TextView mtextTimeDateDayMonth;

    /**
     * Create a DefaultClockController instance.
     *
     * @param res            Resources contains title and thumbnail.
     * @param inflater       Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     */
    public IDEClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor) {
        this(res, inflater, colorExtractor, null);
    }

    /**
     * Create a DefaultClockController instance.
     *
     * @param res Resources contains title and thumbnail.
     * @param inflater Inflater used to inflate custom clock views.
     * @param colorExtractor Extracts accent color from wallpaper.
     * @param context A context.
     */
    public IDEClockController(Resources res, LayoutInflater inflater,
            SysuiColorExtractor colorExtractor, Context context) {
        mResources = res;
        mLayoutInflater = inflater;
        mColorExtractor = colorExtractor;
        mContext = context;
    }

    private void createViews() {
        mView = (ClockLayout) mLayoutInflater
                .inflate(R.layout.p404_ide_clock, null);
        setViews(mView);
    }

    private void setViews(View view) {
        mTime = view.findViewById(R.id.clockTime);
        mDate = view.findViewById(R.id.clockDate);
        mDay = view.findViewById(R.id.clockDay);
        mMonth = view.findViewById(R.id.clockMonth);
        mtextInclude = view.findViewById(R.id.textInclude);
        mtextStd = view.findViewById(R.id.textStd);
        mtextUsingNamespace = view.findViewById(R.id.textUsingNamespace);
        mtextIntMain = view.findViewById(R.id.textIntMain);
        mtextTimeDateDayMonth = view.findViewById(R.id.textTimeDateDayMonth);
    }

    @Override
    public void onDestroyView() {
        mView = null;
        mTime = null;
        mDate = null;
        mDay = null;
        mMonth = null;
    }

    @Override
    public String getName() {
        return "ide";
    }

    @Override
    public String getTitle() {
        return "IDE";
    }

    @Override
    public Bitmap getThumbnail() {
        return BitmapFactory.decodeResource(mResources, R.drawable.ide_thumbnail);
    }

    @Override
    public Bitmap getPreview(int width, int height) {

        View previewView = mLayoutInflater.inflate(R.layout.p404_ide_clock_preview, null);
        setViews(previewView);

        ColorExtractor.GradientColors colors = mColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK);
        setColorPalette(colors.supportsDarkText(), colors.getColorPalette());
        onTimeTick();

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
    public void setStyle(Style style) {
    }

    @Override
    public void setTextColor(int color) {
        mTime.setTextColor(color);
    }

    @Override
    public void setColorPalette(boolean supportsDarkText, int[] colorPalette) {
        mPalette.setColorPalette(supportsDarkText, colorPalette);
        updateColor();
    }

    private void updateColor() {
        final int primary = mPalette.getPrimaryColor();
        final int secondary = mPalette.getSecondaryColor();
        mDate.setTextColor(secondary);
        mtextInclude.setTextColor(secondary);
        mtextUsingNamespace.setTextColor(secondary);
        mtextIntMain.setTextColor(secondary);
        mMonth.setTextColor(primary);
        mtextStd.setTextColor(primary);
        mtextTimeDateDayMonth.setTextColor(primary);
    }

    @Override
    public void setDarkAmount(float darkAmount) {
        mPalette.setDarkAmount(darkAmount);
        mView.setDarkAmount(darkAmount);
    }

    @Override
    public void onTimeTick() {
        if (mView != null)
            mView.onTimeChanged();
        if (mTime != null)
            mTime.refresh();
        if (mDate != null)
            mDate.refresh();
        if (mDay != null)
            mDay.refresh();
        if (mMonth != null)
            mMonth.refresh();
    }

    @Override
    public void onTimeZoneChanged(TimeZone timeZone) {
    }

    @Override
    public boolean shouldShowStatusArea() {
        return false;
    }
}
