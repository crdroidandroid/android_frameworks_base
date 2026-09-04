package com.google.android.systemui.smartspace;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageButton;

/**
 * Left/right paging affordance shown beside the {@link PagerDots} indicator.
 *
 * <p>Dims itself when disabled instead of being hidden, so the indicator row keeps a stable width
 * while the user pages to either end of the carousel.
 */
public final class PaginationArrow extends ImageButton {

    private static final float DISABLED_ALPHA = 0.38f;

    public PaginationArrow(Context context) {
        this(context, null);
    }

    public PaginationArrow(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PaginationArrow(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : DISABLED_ALPHA);
    }
}
