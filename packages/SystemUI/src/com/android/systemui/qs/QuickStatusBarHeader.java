/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.tuner.TunerService;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout implements TunerService.Tunable {

    private boolean mExpanded;
    private boolean mQsDisabled;

    private static final String QS_HEADER_IMAGE =
            "system:" + Settings.System.QS_HEADER_IMAGE;

    protected QuickQSPanel mHeaderQsPanel;

    // QS Header
    private ImageView mQsHeaderImageView;
    private View mQsHeaderLayout;
    private boolean mHeaderImageEnabled;
    private int mHeaderImageValue;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);

        mQsHeaderLayout = findViewById(R.id.layout_header);
        mQsHeaderImageView = findViewById(R.id.qs_header_image_view);
        mQsHeaderImageView.setClipToOutline(true);

        updateResources();

        Dependency.get(TunerService.class).addTunable(this, QS_HEADER_IMAGE);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QS_HEADER_IMAGE:
                mHeaderImageValue =
                       TunerService.parseInteger(newValue, 0);
                mHeaderImageEnabled = mHeaderImageValue != 0;
                updateResources();
                break;
            default:
                break;
        }
    }

    private void updateQSHeaderImage() {
        if (!mHeaderImageEnabled) {
            mQsHeaderLayout.setVisibility(View.GONE);
            return;
        }
        Configuration config = mContext.getResources().getConfiguration();
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            boolean mIsNightMode = (mContext.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int fadeFilter = ColorUtils.blendARGB(Color.TRANSPARENT, mIsNightMode ?
                Color.BLACK : Color.WHITE, 30 / 100f);
            int resId = getResources().getIdentifier("qs_header_image_" +
                String.valueOf(mHeaderImageValue), "drawable", "com.android.systemui");
            mQsHeaderImageView.setImageResource(resId);
            mQsHeaderImageView.setColorFilter(fadeFilter, PorterDuff.Mode.SRC_ATOP);
            mQsHeaderLayout.setVisibility(View.VISIBLE);
        } else {
            mQsHeaderLayout.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Only react to touches inside QuickQSPanel
        if (event.getY() > mHeaderQsPanel.getTop()) {
            return super.onTouchEvent(event);
        } else {
            return false;
        }
    }

    void updateResources() {
        Resources resources = mContext.getResources();
        boolean largeScreenHeaderActive =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources);

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = 0;
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        MarginLayoutParams qqsLP = (MarginLayoutParams) mHeaderQsPanel.getLayoutParams();
        if (largeScreenHeaderActive) {
            qqsLP.topMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.qqs_layout_margin_top);
        } else {
            qqsLP.topMargin = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.large_screen_shade_header_min_height);
        }
        mHeaderQsPanel.setLayoutParams(qqsLP);
        updateQSHeaderImage();
    }

    public void setExpanded(boolean expanded, QuickQSPanelController quickQSPanelController) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        quickQSPanelController.setExpanded(expanded);
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        updateResources();
    }

    private void setContentMargins(View view, int marginStart, int marginEnd) {
        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        view.setLayoutParams(lp);
    }
}
