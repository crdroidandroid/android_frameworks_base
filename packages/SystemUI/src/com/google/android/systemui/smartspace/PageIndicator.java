package com.google.android.systemui.smartspace;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

public abstract class PageIndicator extends LinearLayout {
    public PageIndicator(Context context) {
        super(context);
    }

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PageIndicator(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
