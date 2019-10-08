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
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.crdroid.header.StatusBarHeaderMachine;
import com.android.systemui.util.LargeScreenUtils;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout
            implements StatusBarHeaderMachine.IStatusBarHeaderMachineObserver {

    private boolean mExpanded;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;

    // QS Header
    private ImageView mQsHeaderImageView;
    private View mQsHeaderLayout;
    private boolean mHeaderImageEnabled;
    private StatusBarHeaderMachine mStatusBarHeaderMachine;
    private Drawable mCurrentBackground;
    private final Handler mHandler = new Handler();

    private class OmniSettingsObserver extends ContentObserver {
        OmniSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_CUSTOM_HEADER), false,
                    this, UserHandle.USER_ALL);
            }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }
    private OmniSettingsObserver mOmniSettingsObserver = new OmniSettingsObserver(mHandler);

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStatusBarHeaderMachine = new StatusBarHeaderMachine(context);
        mOmniSettingsObserver.observe();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);

        mQsHeaderLayout = findViewById(R.id.layout_header);
        mQsHeaderImageView = findViewById(R.id.qs_header_image_view);
        mQsHeaderImageView.setClipToOutline(true);

        updateSettings();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarHeaderMachine.addObserver(this);
        mStatusBarHeaderMachine.updateEnablement();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarHeaderMachine.removeObserver(this);
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
        int orientation = getResources().getConfiguration().orientation;

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

        Configuration config = mContext.getResources().getConfiguration();
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            mQsHeaderLayout.setVisibility(mHeaderImageEnabled ? View.VISIBLE : View.GONE);
        } else {
            mQsHeaderLayout.setVisibility(View.GONE);
        }
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

    private void updateSettings() {
        mHeaderImageEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        updateResources();
    }

    private void setContentMargins(View view, int marginStart, int marginEnd) {
        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        view.setLayoutParams(lp);
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
            public void run() {
                mCurrentBackground = null;
                mQsHeaderImageView.setVisibility(View.GONE);
                mHeaderImageEnabled = false;
                updateResources();
            }
        });
    }

    @Override
    public void refreshHeader() {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(mCurrentBackground, true);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            mQsHeaderImageView.setVisibility(View.VISIBLE);
            mCurrentBackground = next;
            setNotificationPanelHeaderBackground(next, force);
            mHeaderImageEnabled = true;
            updateResources();
        } else {
            mCurrentBackground = null;
            mQsHeaderImageView.setVisibility(View.GONE);
            mHeaderImageEnabled = false;
            updateResources();
        }
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mQsHeaderImageView.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mQsHeaderImageView.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mQsHeaderImageView.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mQsHeaderImageView.setImageDrawable(dw);
        }
        applyHeaderBackgroundShadow();
    }

    private void applyHeaderBackgroundShadow() {
        final int headerShadow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_SHADOW, 0,
                UserHandle.USER_CURRENT);
        if (mCurrentBackground != null && mQsHeaderImageView.getDrawable() != null) {
            mQsHeaderImageView.setImageAlpha(255 - headerShadow);
        }
    }
}
