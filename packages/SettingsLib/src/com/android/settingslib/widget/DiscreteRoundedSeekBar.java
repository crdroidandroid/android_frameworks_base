/*
 * Copyright (C) 2007 The Android Open Source Project
 *           (C) 2025 The LibreMobileOS Foundation
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

package com.android.settingslib.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.SeekBar;

import com.android.settingslib.R;

/**
 * Material 3 slider with stop indicators
 * Ensure to use with @style/SeekBar.SettingsLib.Discrete
 */
public class DiscreteRoundedSeekBar extends SeekBar {

    protected Drawable mActiveTickMark;

    public DiscreteRoundedSeekBar(Context context) {
        super(context);
    }

    public DiscreteRoundedSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DiscreteRoundedSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setTickMark(Drawable tickMark) {
        super.setTickMark(tickMark);

        // We need to use 2 different colors for tick marks drawn over active and inactive track.
        mActiveTickMark = getTickMark().getConstantState().newDrawable().mutate();
        if (mActiveTickMark.setState(new int[]{android.R.attr.state_checked})) {
            mActiveTickMark.invalidateSelf();
        }
    }

    @Override
    protected void drawTickMarks(Canvas canvas) {
        final Drawable tickMark = getTickMark();
        final int count = getMax() - getMin();
        final int progress = getProgress() - getMin();
        final int margin = getContext().getResources().getDimensionPixelSize(
                R.dimen.settingslib_seekbar_tick_margin);

        if (count <= 1) {
            return;
        }

        int w = tickMark.getIntrinsicWidth();
        int h = tickMark.getIntrinsicHeight();
        int halfW = w >= 0 ? w / 2 : 1;
        int halfH = h >= 0 ? h / 2 : 1;
        tickMark.setBounds(-halfW, -halfH, halfW, halfH);

        w = mActiveTickMark.getIntrinsicWidth();
        h = mActiveTickMark.getIntrinsicHeight();
        halfW = w >= 0 ? w / 2 : 1;
        halfH = h >= 0 ? h / 2 : 1;
        mActiveTickMark.setBounds(-halfW, -halfH, halfW, halfH);

        final float spacing =
                (getWidth() - (margin * 2) - mPaddingLeft - mPaddingRight) / (float) count;
        final int saveCount = canvas.save();
        canvas.translate(margin + mPaddingLeft, getHeight() / 2);
        for (int i = 0; i <= count; i++) {
            if (isEnabled() && i < progress) {
                // Use the checked state of tickmark if we are drawing over active track
                mActiveTickMark.draw(canvas);
            } else {
                // Otherwise the default state.
                tickMark.draw(canvas);
            }
            canvas.translate(spacing, 0);
        }
        canvas.restoreToCount(saveCount);
    }

}
