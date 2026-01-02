package com.google.android.systemui.smartspace;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.systemui.res.R;

public final class PagerDots extends View {
    public final float activeDotSize;
    public int currentPageIndex;
    public int currentPositionIndex;
    public float currentPositionOffset;
    public final float dotMargin;
    public final float dotRadius;
    public final float dotSize;
    public int numPages;
    public final Paint paint;
    public int primaryColor;
    public final RectF tempRectF;

    public PagerDots(Context context, AttributeSet attrs) {
        super(context, attrs);
        numPages = -1;
        currentPageIndex = -1;
        currentPositionIndex = -1;
        dotSize = getResources().getDimension(R.dimen.page_indicator_dot_size);
        dotMargin = getResources().getDimension(R.dimen.page_indicator_dot_margin);
        activeDotSize = dotSize * 2;
        dotRadius = dotSize / 2;
        tempRectF = new RectF();
        TypedArray obtainStyledAttributes = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        primaryColor = obtainStyledAttributes.getColor(0, 0);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primaryColor);
        obtainStyledAttributes.recycle();
        this.paint = paint;
    }

    @Override
    public final void onDraw(Canvas canvas) {
        if (numPages < 2) {
            return;
        }
        int save = canvas.save();
        try {
            canvas.scale(isLayoutRtl() ? -1.0f : 1.0f, 1.0f, canvas.getWidth() * 0.5f, 0.0f);
            canvas.translate(getPaddingStart(), getPaddingTop());
            float f4 = (activeDotSize - dotSize) * currentPositionOffset;
            int i = (primaryColor >> 24) & 255;
            int i2 = (int) (i * 0.4f);
            int i3 = (int) ((i - i2) * currentPositionOffset);
            tempRectF.top = 0.0f;
            tempRectF.bottom = dotSize;
            tempRectF.left = 0.0f;
            for (int i5 = 0; i5 < numPages; i5++) {
                int i6 = currentPositionIndex;
                float f5 = i5 == i6 ? activeDotSize - f4 : i5 == i6 + 1 ? dotSize + f4 : dotSize;
                int i7 = i5 == i6 ? i - i3 : i5 == i6 + 1 ? i2 + i3 : i2;
                tempRectF.right = tempRectF.left + f5;
                paint.setAlpha(i7);
                canvas.drawRoundRect(tempRectF, dotRadius, dotRadius, paint);
                tempRectF.left = tempRectF.right + dotMargin;
            }
            canvas.restoreToCount(save);
        } catch (Throwable th) {
            canvas.restoreToCount(save);
            throw th;
        }
    }

    @Override
    public final void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(getPaddingRight() + getPaddingLeft() + (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY ? View.MeasureSpec.getSize(widthMeasureSpec) : (int) (((dotMargin + dotSize) * (numPages - 1)) + activeDotSize)), getPaddingBottom() + getPaddingTop() + (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY ? View.MeasureSpec.getSize(heightMeasureSpec) : (int) dotSize));
    }

    public final void setNumPages(int i, boolean z) {
        if (i == numPages) {
            return;
        }
        if (i <= 0) {
            Log.w("SsPagerDots", "Total number of pages invalid: " + i + ". Assuming 1 page.");
            numPages = 1;
        } else {
            numPages = i;
        }
        if (currentPageIndex < 0) {
            updateCurrentPageIndex(z ? numPages - 1 : 0);
            currentPositionIndex = currentPageIndex;
        } else {
            if (currentPageIndex >= numPages) {
                updateCurrentPageIndex(z ? 0 : numPages - 1);
                currentPositionIndex = currentPageIndex;
            }
        }
        if (numPages < 2) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, View.GONE);
        } else if (getVisibility() != View.INVISIBLE) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, View.VISIBLE);
        }
        requestLayout();
        invalidate();
    }

    public final void updateCurrentPageIndex(int i) {
        if (i == currentPageIndex) {
            return;
        }
        currentPageIndex = i;
        setContentDescription(getContext().getString(R.string.accessibility_smartspace_page, currentPageIndex + 1, numPages));
    }

    public PagerDots(Context context) {
        this(context, null);
    }
}
