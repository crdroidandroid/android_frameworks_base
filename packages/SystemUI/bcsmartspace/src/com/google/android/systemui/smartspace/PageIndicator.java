package com.google.android.systemui.smartspace;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.android.systemui.bcsmartspace.R;

public class PageIndicator extends LinearLayout {
    public int mCurrentPageIndex;
    public int mNumPages;
    public int mPrimaryColor;

    public PageIndicator(Context context) {
        super(context);
        this.mPrimaryColor = getAttrColor(getContext(), 16842806);
        this.mCurrentPageIndex = -1;
        this.mNumPages = -1;
    }

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mPrimaryColor = getAttrColor(getContext(), 16842806);
        this.mCurrentPageIndex = -1;
        this.mNumPages = -1;
    }

    public PageIndicator(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.mPrimaryColor = getAttrColor(getContext(), 16842806);
        this.mCurrentPageIndex = -1;
        this.mNumPages = -1;
    }

    public PageIndicator(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.mPrimaryColor = getAttrColor(getContext(), 16842806);
        this.mCurrentPageIndex = -1;
        this.mNumPages = -1;
    }

    public static int getAttrColor(Context context, int attr) {
        TypedArray obtainStyledAttributes = context.obtainStyledAttributes(new int[]{attr});
        int color = obtainStyledAttributes.getColor(0, 0);
        obtainStyledAttributes.recycle();
        return color;
    }

    public void setNumPages(int pages) {
        if (pages <= 0) {
            Log.w("PageIndicator", "Total number of pages invalid: " + pages + ". Assuming 1 page.");
            pages = 1;
        }
        if (pages < 2) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, 8);
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(this, 0);
        if (pages != this.mNumPages) {
            this.mNumPages = pages;
            initializePageIndicators();
        }
    }

    public void setPrimaryColor(int color) {
        this.mPrimaryColor = color;
        for (int i = 0; i < getChildCount(); i++) {
            ((ImageView) getChildAt(i)).getDrawable().setTint(this.mPrimaryColor);
        }
    }

    public void setPageOffset(int position, float positionOffset) {
        int i4 = Float.compare(positionOffset, 0.0f);
        if ((i4 != 0 || position != this.mCurrentPageIndex) && position >= 0 && position < getChildCount() - 1) {
            ImageView imageView = (ImageView) getChildAt(position);
            int childIndex = position + 1;
            ImageView imageView2 = (ImageView) getChildAt(childIndex);
            if (imageView != null && imageView2 != null) {
                if (i4 == 0 || positionOffset >= 0.99f) {
                    if (this.mCurrentPageIndex >= 0 && this.mCurrentPageIndex < getChildCount()) {
                        getChildAt(this.mCurrentPageIndex).setAlpha(0.4f);
                    }
                    this.mCurrentPageIndex = i4 == 0 ? position : childIndex;
                }
                imageView.setAlpha(((1.0f - positionOffset) * 0.6f) + 0.4f);
                imageView2.setAlpha((0.6f * positionOffset) + 0.4f);
                if (positionOffset >= 0.5d) {
                    childIndex = position + 2;
                }
                Object[] offset = {Integer.valueOf(childIndex), Integer.valueOf(this.mNumPages)};
                setContentDescription(getContext().getString(R.string.accessibility_smartspace_page, offset));
            }
        }
    }

    private void initializePageIndicators() {
        LinearLayout.LayoutParams lp;
        int childCount = getChildCount() - this.mNumPages;
        for (int i = 0; i < childCount; i++) {
            removeViewAt(0);
        }
        int dimensionPixelSize = getContext().getResources().getDimensionPixelSize(R.dimen.page_indicator_dot_margin);
        int i2 = 0;
        while (i2 < this.mNumPages) {
            ImageView imageView = i2 < getChildCount() ? (ImageView) getChildAt(i2) : new ImageView(getContext());
            if (i2 < getChildCount()) {
                lp = (LinearLayout.LayoutParams) imageView.getLayoutParams();
            } else {
                lp = new LinearLayout.LayoutParams(-2, -2);
            }
            if (i2 == 0) {
                lp.setMarginStart(0);
            } else {
                lp.setMarginStart(dimensionPixelSize);
            }
            if (i2 == this.mNumPages - 1) {
                lp.setMarginEnd(0);
            } else {
                lp.setMarginEnd(dimensionPixelSize);
            }
            if (i2 < getChildCount()) {
                imageView.setLayoutParams(lp);
            } else {
                Drawable drawable = getContext().getResources().getDrawable(R.drawable.page_indicator_dot, getContext().getTheme());
                drawable.setTint(this.mPrimaryColor);
                imageView.setImageDrawable(drawable);
                addView(imageView, lp);
            }
            int index = this.mCurrentPageIndex;
            if (index < 0) {
                this.mCurrentPageIndex = 0;
            } else {
                int numPages = this.mNumPages;
                if (index >= numPages) {
                    this.mCurrentPageIndex = numPages - 1;
                }
            }
            imageView.setAlpha(i2 == this.mCurrentPageIndex ? 1.0f : 0.4f);
            i2++;
        }
        setContentDescription(getContext().getString(R.string.accessibility_smartspace_page, 1, Integer.valueOf(this.mNumPages)));
    }
}
