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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;

import com.android.settingslib.Utils;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.tuner.TunerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout implements TunerService.Tunable {

    private static final String TAG = "QuickStatusBarHeader";

    private static final String QS_HEADER_IMAGE =
            "system:" + Settings.System.QS_HEADER_IMAGE;
    private static final String QS_HEADER_IMAGE_TINT =
            "system:" + Settings.System.QS_HEADER_IMAGE_TINT;
    private static final String QS_HEADER_IMAGE_TINT_CUSTOM =
            "system:" + Settings.System.QS_HEADER_IMAGE_TINT_CUSTOM;
    private static final String QS_HEADER_IMAGE_ALPHA =
            "system:" + Settings.System.QS_HEADER_IMAGE_ALPHA;
    private static final String QS_HEADER_IMAGE_HEIGHT_PORTRAIT =
            "system:" + Settings.System.QS_HEADER_IMAGE_HEIGHT_PORTRAIT;
    private static final String QS_HEADER_IMAGE_HEIGHT_LANDSCAPE =
            "system:" + Settings.System.QS_HEADER_IMAGE_HEIGHT_LANDSCAPE;
    private static final String QS_HEADER_IMAGE_LANDSCAPE_ENABLED =
            "system:" + Settings.System.QS_HEADER_IMAGE_LANDSCAPE_ENABLED;
    private static final String QS_HEADER_IMAGE_PADDING_SIDE =
            "system:" + Settings.System.QS_HEADER_IMAGE_PADDING_SIDE;
    private static final String QS_HEADER_IMAGE_PADDING_TOP =
            "system:" + Settings.System.QS_HEADER_IMAGE_PADDING_TOP;
    private static final String QS_HEADER_IMAGE_URI =
            "system:" + Settings.System.QS_HEADER_IMAGE_URI;

    private static final String HEADER_FILE_NAME = "qsheader";

    private final int MAX_TINT_OPACITY = 155;

    private boolean mExpanded;
    private boolean mQsDisabled;
    private View mQsHeaderLayout;
    protected QuickQSPanel mHeaderQsPanel;

    // QS Header Image
    private ImageView qshiView;
    private int qshiValue;
    private boolean qshiEnabled;
    private boolean qshiLandscapeEnabled;
    private int qshiHeightPortrait;
    private int qshiHeightLandscape;
    private int qshiAlpha;
    private int qshiTint;
    private int qshiTintCustom;
    private int qshiPaddingSide;
    private int qshiPaddingTop;
    private Uri qshiUri;

    private int mColorAccent;
    private int mColorTextPrimary;
    private int mColorTextPrimaryInverse;
    private TunerService mTuner;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        mTuner = Dependency.get(TunerService.class);
        mTuner.addTunable(this,
                QS_HEADER_IMAGE,
                QS_HEADER_IMAGE_TINT,
                QS_HEADER_IMAGE_TINT_CUSTOM,
                QS_HEADER_IMAGE_ALPHA,
                QS_HEADER_IMAGE_HEIGHT_PORTRAIT,
                QS_HEADER_IMAGE_HEIGHT_LANDSCAPE,
                QS_HEADER_IMAGE_LANDSCAPE_ENABLED,
                QS_HEADER_IMAGE_PADDING_SIDE,
                QS_HEADER_IMAGE_PADDING_TOP,
                QS_HEADER_IMAGE_URI);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mTuner != null) mTuner.removeTunable(this);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mQsHeaderLayout = findViewById(R.id.layout_header);
        qshiView = findViewById(R.id.qs_header_image_view);
        qshiView.setClipToOutline(true);

        if (mTuner != null) {
            qshiValue = mTuner.getValue(QS_HEADER_IMAGE, 0);
            qshiEnabled = qshiValue != 0;
            qshiTint = mTuner.getValue(QS_HEADER_IMAGE_TINT, 0);
            qshiTintCustom = mTuner.getValue(QS_HEADER_IMAGE_TINT_CUSTOM, 0XFFFFFFFF);
            qshiAlpha = mTuner.getValue(QS_HEADER_IMAGE_ALPHA, 255);
            qshiHeightPortrait = mTuner.getValue(QS_HEADER_IMAGE_HEIGHT_PORTRAIT, 325);
            qshiHeightLandscape = mTuner.getValue(QS_HEADER_IMAGE_HEIGHT_LANDSCAPE, 200);
            qshiLandscapeEnabled = mTuner.getValue(QS_HEADER_IMAGE_LANDSCAPE_ENABLED, 0) != 0;
            qshiPaddingSide = mTuner.getValue(QS_HEADER_IMAGE_PADDING_SIDE, -50);
            qshiPaddingTop = mTuner.getValue(QS_HEADER_IMAGE_PADDING_TOP, 0);
        }
        updateResources();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QS_HEADER_IMAGE:
                qshiValue = TunerService.parseInteger(newValue, 0);
                qshiEnabled = qshiValue != 0;
                updateResources();
                break;
            case QS_HEADER_IMAGE_TINT:
                qshiTint = TunerService.parseInteger(newValue, 0);
                updateResources();
                break;
            case QS_HEADER_IMAGE_TINT_CUSTOM:
                qshiTintCustom = TunerService.parseInteger(newValue, 0XFFFFFFFF);
                updateResources();
                break;
            case QS_HEADER_IMAGE_ALPHA:
                qshiAlpha = TunerService.parseInteger(newValue, 255);
                updateResources();
                break;
            case QS_HEADER_IMAGE_HEIGHT_PORTRAIT:
                qshiHeightPortrait = TunerService.parseInteger(newValue, 325);
                updateResources();
                break;
            case QS_HEADER_IMAGE_HEIGHT_LANDSCAPE:
                qshiHeightLandscape = TunerService.parseInteger(newValue, 200);
                updateResources();
                break;
            case QS_HEADER_IMAGE_LANDSCAPE_ENABLED:
                qshiLandscapeEnabled = TunerService.parseIntegerSwitch(newValue, false);
                updateResources();
                break;
            case QS_HEADER_IMAGE_PADDING_SIDE:
                qshiPaddingSide = TunerService.parseInteger(newValue, -50);
                updateResources();
                break;
            case QS_HEADER_IMAGE_PADDING_TOP:
                qshiPaddingTop = TunerService.parseInteger(newValue, 0);
                updateResources();
                break;
            case QS_HEADER_IMAGE_URI:
                updateResources();
                break;
            default:
                break;
        }
    }

    private void updateQSHeaderImage() {
        Resources resources = mContext.getResources();
        Configuration config = resources.getConfiguration();

        if (!qshiEnabled) {
            mQsHeaderLayout.setVisibility(View.GONE);
            return;
        }

        // handle landscape mode
        int orientation = getResources().getConfiguration().orientation;
        if (!qshiLandscapeEnabled &&
                orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mQsHeaderLayout.setVisibility(View.GONE);
            return;
        }

        // custom header image
        if (qshiValue == -1) {
            String uriStr = Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.QS_HEADER_IMAGE_URI, UserHandle.USER_CURRENT);
            Bitmap customHeader = loadFromStringUri(uriStr);
            if (customHeader != null) {
                qshiView.setImageBitmap(customHeader);
            }
        } else {
            int resId = getResources().getIdentifier("qs_header_image_" +
                    String.valueOf(qshiValue), "drawable", "com.android.systemui");
            qshiView.setImageResource(resId);
        }

        // tint
        mColorAccent = Utils.getColorAttrDefaultColor(mContext,
                android.R.attr.colorAccent);
        mColorTextPrimary = Utils.getColorAttrDefaultColor(mContext,
                android.R.attr.textColorPrimary);
        mColorTextPrimaryInverse = Utils.getColorAttrDefaultColor(
                mContext, android.R.attr.textColorPrimaryInverse);

        int tintColor = -1;
        if (qshiTint == 0) {
            qshiView.setColorFilter(null);
        } else if (qshiTint == 1) {
            tintColor = mColorAccent;
        } else if (qshiTint == 2) {
            tintColor = mColorTextPrimary;
        } else if (qshiTint == 3) {
            tintColor = mColorTextPrimaryInverse;
        } else if (qshiTint == 4) {
            // validate color and limit custom tint opacity to MAX_TINT_OPACITY
            tintColor = getValidCustomTint(qshiTintCustom);
        }

        if (tintColor != -1) {
            int fadeFilter = ColorUtils.blendARGB(Color.TRANSPARENT, tintColor, 50 / 100f);
            qshiView.setColorFilter(fadeFilter, Mode.SRC_ATOP);
        }

        // transparency
        qshiView.setAlpha(qshiAlpha);

        // height and paddings
        int qshiMinHeight = resources.getDimensionPixelSize(
                R.dimen.qs_header_image_min_height);
        int qshiDefaultHeight = resources.getDimensionPixelSize(
                R.dimen.qs_header_height_full);

        ViewGroup.MarginLayoutParams qshiParams = (ViewGroup.MarginLayoutParams) mQsHeaderLayout.getLayoutParams();
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (qshiHeightLandscape > 0) {
                if (qshiHeightLandscape >= qshiMinHeight) {
                    qshiParams.height = qshiHeightLandscape;
                } else {
                    qshiParams.height = qshiMinHeight;
                }
            }
            else {
                qshiParams.height = qshiDefaultHeight;
            }
        } else {
            if (qshiHeightPortrait > 0) {
                if (qshiHeightPortrait >= qshiMinHeight) {
                    qshiParams.height = qshiHeightPortrait;
                } else {
                    qshiParams.height = qshiMinHeight;
                }
            }
            else {
                qshiParams.height = qshiDefaultHeight;
            }
        }

        // set layout parameters
        qshiParams.setMargins(qshiPaddingSide, qshiPaddingTop, qshiPaddingSide, 0);
        mQsHeaderLayout.setLayoutParams(qshiParams);
        mQsHeaderLayout.setVisibility(View.VISIBLE);
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

        int statusBarSideMargin = qshiEnabled ? mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_header_image_side_margin) : 0;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = 0;
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        MarginLayoutParams qqsLP = (MarginLayoutParams) mHeaderQsPanel.getLayoutParams();
        if (largeScreenHeaderActive) {
            qqsLP.topMargin = resources
                    .getDimensionPixelSize(R.dimen.qqs_layout_margin_top);
        } else {
            qqsLP.topMargin = resources
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

    private int getValidCustomTint(int customTint) {
        int alpha = Color.alpha(customTint);
        int red = Color.red(customTint);
        int blue = Color.blue(customTint);
        int green = Color.green(customTint);

        if (alpha < 0 || red < 0 || red > 255 || blue < 0 || blue > 255
                || green < 0 || green > 255) {
            return -1;
        }

        //limit tint opacity level (alpha <= MAX_TINT_OPACITY)
        if (alpha > MAX_TINT_OPACITY) {
            alpha = MAX_TINT_OPACITY;
            return Color.argb(alpha, red, green, blue);
        }
        else {
            return customTint;
        }
    }

    private Bitmap loadFromStringUri(String uriStr) {
        saveHeader(uriStr);
        return getSavedHeader();
    }

    private void saveHeader(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            final InputStream imageStream =
                    mContext.getContentResolver().openInputStream(uri);
            File file = new File(mContext.getFilesDir(), HEADER_FILE_NAME);
            if (file.exists()) {
                file.delete();
            }
            FileOutputStream output = new FileOutputStream(file);
            byte[] buffer = new byte[8 * 1024];
            int read;

            while ((read = imageStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to store header image");
        }
    }

    private Bitmap getSavedHeader() {
        File file = new File(mContext.getFilesDir(), HEADER_FILE_NAME);
        if (file.exists()) {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        }
        return null;
    }
}
