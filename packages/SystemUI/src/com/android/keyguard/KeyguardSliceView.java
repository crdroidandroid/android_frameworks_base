/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.ColorInt;
import android.annotation.StyleRes;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.text.LineBreaker;
import android.net.Uri;
import android.os.Trace;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.slice.SliceItem;
import androidx.slice.core.SliceQuery;
import androidx.slice.widget.RowContent;
import androidx.slice.widget.SliceContent;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.res.R;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout {

    private static final String TAG = "KeyguardSliceView";
    public static final int DEFAULT_ANIM_DURATION = 550;

    private final LayoutTransition mLayoutTransition;
    @VisibleForTesting
    TextView mTitle;
    private Row mRow;
    private int mTextColor;
    private float mDarkAmount = 0;

    private int mIconSize;
    private int mIconSizeWithHeader;
    /**
     * Runnable called whenever the view contents change.
     */
    private Runnable mContentChangeListener;
    private boolean mHasHeader;
    private View.OnClickListener mOnClickListener;

    public KeyguardSliceView(Context context, AttributeSet attrs) {
        super(context, attrs);

        Resources resources = context.getResources();
        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.setStagger(LayoutTransition.CHANGE_APPEARING, DEFAULT_ANIM_DURATION / 2);
        mLayoutTransition.setDuration(LayoutTransition.APPEARING, DEFAULT_ANIM_DURATION);
        mLayoutTransition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 2);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        mLayoutTransition.setInterpolator(LayoutTransition.APPEARING,
                Interpolators.FAST_OUT_SLOW_IN);
        mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        mLayoutTransition.setAnimateParentHierarchy(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitle = findViewById(R.id.title);
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mIconSize = (int) mContext.getResources().getDimension(R.dimen.widget_icon_size);
        mIconSizeWithHeader = (int) mContext.getResources().getDimension(R.dimen.header_icon_size);
        mTitle.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        setLayoutTransition(isVisible ? mLayoutTransition : null);
    }

    /**
     * Returns whether the current visible slice has a title/header.
     */
    public boolean hasHeader() {
        return mHasHeader;
    }

    void hideSlice() {
        mTitle.setVisibility(GONE);
        mRow.setVisibility(GONE);
        mHasHeader = false;
        if (mContentChangeListener != null) {
            mContentChangeListener.run();
        }
    }

    private LinearLayout createSubRow() {
        LinearLayout layout = new LinearLayout(mContext);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (LinearLayout.LayoutParams) mRow.getLayoutParams());
        lp.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                R.dimen.widget_horizontal_padding));
        layout.setLayoutParams(lp);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setVisibility(View.VISIBLE);
        return layout;
    }

    Map<View, PendingIntent> showSlice(RowContent header, List<SliceContent> subItems) {
        Trace.beginSection("KeyguardSliceView#showSlice");
        mHasHeader = header != null;
        Map<View, PendingIntent> clickActions = new HashMap<>();

        if (!mHasHeader) {
            mTitle.setVisibility(GONE);
        } else {
            mTitle.setVisibility(VISIBLE);

            SliceItem mainTitle = header.getTitleItem();
            CharSequence title = mainTitle != null ? mainTitle.getText() : null;
            mTitle.setText(title);
            if (header.getPrimaryAction() != null
                    && header.getPrimaryAction().getAction() != null) {
                clickActions.put(mTitle, header.getPrimaryAction().getAction());
            }
        }

        final int subItemsCount = subItems.size();
        final int blendedColor = getTextColor();
        final int startIndex = mHasHeader ? 1 : 0; // First item is header; skip it
        mRow.setVisibility(subItemsCount > 0 ? VISIBLE : GONE);
        LinearLayout.LayoutParams layoutParams = (LayoutParams) mRow.getLayoutParams();
        layoutParams.gravity = Gravity.START;
        mRow.setLayoutParams(layoutParams);

        int rowIndex = 0, colIndex = 0;
        LinearLayout subRow = null;
        for (SliceContent sc : subItems.subList(startIndex, subItems.size())) {
            subRow = (LinearLayout) mRow.getChildAt(rowIndex);
            if (subRow == null) {
                subRow = createSubRow();
                mRow.addView(subRow, rowIndex);
            }
            RowContent rc = (RowContent) sc;
            SliceItem item = rc.getSliceItem();
            final Uri itemTag = item.getSlice().getUri();
            // Try to reuse the view if already exists in the layout
            KeyguardSliceTextView button = mRow.findViewWithTag(itemTag);
            if (button == null) {
                button = new KeyguardSliceTextView(mContext);
                button.setTextColor(blendedColor);
                button.setTag(itemTag);
                subRow.addView(button, colIndex);
            } else {
                LinearLayout buttonParent = (LinearLayout) button.getParent();
                if (mRow.indexOfChild(buttonParent) != rowIndex
                        || buttonParent.indexOfChild(button) != colIndex) {
                    buttonParent.removeView(button);
                    subRow.addView(button, colIndex);
                }
            }
            colIndex++;

            PendingIntent pendingIntent = null;
            if (rc.getPrimaryAction() != null) {
                pendingIntent = rc.getPrimaryAction().getAction();
            }
            clickActions.put(button, pendingIntent);

            final SliceItem titleItem = rc.getTitleItem();
            button.setText(titleItem == null ? null : titleItem.getText());
            button.setContentDescription(rc.getContentDescription());

            Drawable iconDrawable = null;
            SliceItem icon = SliceQuery.find(item.getSlice(),
                    android.app.slice.SliceItem.FORMAT_IMAGE);
            if (icon != null) {
                final int iconSize = mHasHeader ? mIconSizeWithHeader : mIconSize;
                iconDrawable = icon.getIcon().loadDrawable(mContext);
                if (iconDrawable != null) {
                    if (iconDrawable instanceof InsetDrawable) {
                        // System icons (DnD) use insets which are fine for centered slice content
                        // but will cause a slight indent for left/right-aligned slice views
                        iconDrawable = ((InsetDrawable) iconDrawable).getDrawable();
                    }
                    final int width = (int) (iconDrawable.getIntrinsicWidth()
                            / (float) iconDrawable.getIntrinsicHeight() * iconSize);
                    iconDrawable.setBounds(0, 0, Math.max(width, 1), iconSize);
                }
            }
            button.setCompoundDrawablesRelative(iconDrawable, null, null, null);
            button.setOnClickListener(mOnClickListener);
            button.setClickable(pendingIntent != null);

            if (rc.hasBottomDivider()) {
                rowIndex++;
                colIndex = 0;
            } else {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) button.getLayoutParams();
                lp.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                        R.dimen.widget_horizontal_padding));
                button.setLayoutParams(lp);
            }
        }

        // Removing old views
        for (int i = 0; i < mRow.getChildCount(); i++) {
            subRow = (LinearLayout) mRow.getChildAt(i);
            for (int j = 0; j < subRow.getChildCount(); j++) {
                View child = subRow.getChildAt(j);
                if (!clickActions.containsKey(child)) {
                    subRow.removeView(child);
                    j--;
                }
            }
            if (subRow.getChildCount() == 0) {
                mRow.removeView(subRow);
                i--;
            }
        }

        if (mContentChangeListener != null) {
            mContentChangeListener.run();
        }
        Trace.endSection();

        return clickActions;
    }

    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        mRow.setDarkAmount(darkAmount);
        updateTextColors();
    }

    private void updateTextColors() {
        final int blendedColor = getTextColor();
        mTitle.setTextColor(blendedColor);
        mRow.performOnChildren(tv -> tv.setTextColor(blendedColor));
    }

    /**
     * Runnable that gets invoked every time the title or the row visibility changes.
     * @param contentChangeListener The listener.
     */
    public void setContentChangeListener(Runnable contentChangeListener) {
        mContentChangeListener = contentChangeListener;
    }

    @VisibleForTesting
    int getTextColor() {
        return ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
    }

    @VisibleForTesting
    void setTextColor(@ColorInt int textColor) {
        mTextColor = textColor;
        updateTextColors();
    }

    void onDensityOrFontScaleChanged() {
        mIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.widget_icon_size);
        mIconSizeWithHeader = (int) mContext.getResources().getDimension(R.dimen.header_icon_size);

        mRow.performOnChildren(tv -> tv.onDensityOrFontScaleChanged());
    }

    void onOverlayChanged() {
        mRow.performOnChildren(tv -> tv.onOverlayChanged());
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardSliceView:");
        pw.println("  mTitle: " + (mTitle == null ? "null" : mTitle.getVisibility() == VISIBLE));
        pw.println("  mRow: " + (mRow == null ? "null" : mRow.getVisibility() == VISIBLE));
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mHasHeader: " + mHasHeader);
    }

    @Override
    public void setOnClickListener(View.OnClickListener onClickListener) {
        mOnClickListener = onClickListener;
        mTitle.setOnClickListener(onClickListener);
    }

    public static class Row extends LinearLayout {
        /**
         * This view is visible in AOD, which means that the device will sleep if we
         * don't hold a wake lock. We want to enter doze only after all views have reached
         * their desired positions.
         */
        private final Animation.AnimationListener mKeepAwakeListener;
        private LayoutTransition mLayoutTransition;
        private float mDarkAmount;

        public Row(Context context) {
            this(context, null);
        }

        public Row(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
            mKeepAwakeListener = new KeepAwakeAnimationListener(mContext);
        }

        @Override
        protected void onFinishInflate() {
            mLayoutTransition = new LayoutTransition();
            mLayoutTransition.setDuration(DEFAULT_ANIM_DURATION);

            PropertyValuesHolder left = PropertyValuesHolder.ofInt("left", 0, 1);
            PropertyValuesHolder right = PropertyValuesHolder.ofInt("right", 0, 1);
            ObjectAnimator changeAnimator = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                    left, right);
            mLayoutTransition.setAnimator(LayoutTransition.CHANGE_APPEARING, changeAnimator);
            mLayoutTransition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, changeAnimator);
            mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_APPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            mLayoutTransition.setStartDelay(LayoutTransition.CHANGE_APPEARING,
                    DEFAULT_ANIM_DURATION);
            mLayoutTransition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING,
                    DEFAULT_ANIM_DURATION);

            ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
            mLayoutTransition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
            mLayoutTransition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

            ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
            mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING,
                    Interpolators.ALPHA_OUT);
            mLayoutTransition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 4);
            mLayoutTransition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

            mLayoutTransition.setAnimateParentHierarchy(false);
        }

        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            setLayoutTransition(isVisible ? mLayoutTransition : null);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);

            performOnChildren(tv -> tv.setMaxWidth(Integer.MAX_VALUE));

            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        /**
         * Set the amount (ratio) that the device has transitioned to doze.
         *
         * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
         */
        public void setDarkAmount(float darkAmount) {
            boolean isDozing = darkAmount != 0;
            boolean wasDozing = mDarkAmount != 0;
            if (isDozing == wasDozing) {
                return;
            }
            mDarkAmount = darkAmount;
            setLayoutAnimationListener(isDozing ? null : mKeepAwakeListener);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        public void performOnChildren(Consumer<KeyguardSliceTextView> action) {
            for (int i = 0; i < getChildCount(); i++) {
                LinearLayout subRow = (LinearLayout) getChildAt(i);
                for (int j = 0; j < subRow.getChildCount(); j++) {
                    View child = subRow.getChildAt(j);
                    if (child instanceof KeyguardSliceTextView) {
                        action.accept((KeyguardSliceTextView) child);
                    }
                }
            }
        }
    }

    /**
     * Representation of an item that appears under the clock on main keyguard message.
     */
    @VisibleForTesting
    static class KeyguardSliceTextView extends TextView {

        @StyleRes
        private static int sStyleId = R.style.TextAppearance_Keyguard_Secondary;

        KeyguardSliceTextView(Context context) {
            super(context, null /* attrs */, 0 /* styleAttr */, sStyleId);
            onDensityOrFontScaleChanged();
            setEllipsize(TruncateAt.END);
        }

        public void onDensityOrFontScaleChanged() {
            updatePadding();
        }

        public void onOverlayChanged() {
            setTextAppearance(sStyleId);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updatePadding();
        }

        private void updatePadding() {
            boolean hasText = !TextUtils.isEmpty(getText());
            boolean isDate = Uri.parse(KeyguardSliceProvider.KEYGUARD_DATE_URI).equals(getTag());
            int padding = (int) getContext().getResources()
                    .getDimension(R.dimen.widget_horizontal_padding) / 2;
            int iconPadding = (int) mContext.getResources()
                    .getDimension(R.dimen.widget_icon_padding);
            // orientation is vertical, so add padding to top & bottom
            setPadding(!isDate ? iconPadding : 0, padding, 0, hasText ? padding : 0);

            setCompoundDrawablePadding(iconPadding);
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(color);
            updateDrawableColors();
        }

        @Override
        public void setCompoundDrawablesRelative(Drawable start, Drawable top, Drawable end,
                Drawable bottom) {
            super.setCompoundDrawablesRelative(start, top, end, bottom);
            updateDrawableColors();
            updatePadding();
        }

        private void updateDrawableColors() {
            final int color = getCurrentTextColor();
            for (Drawable drawable : getCompoundDrawables()) {
                if (drawable != null) {
                    drawable.setTint(color);
                }
            }
        }
    }
}
