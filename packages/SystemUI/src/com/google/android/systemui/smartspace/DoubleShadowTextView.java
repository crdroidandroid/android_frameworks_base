package com.google.android.systemui.smartspace;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.systemui.res.R;

public class DoubleShadowTextView extends TextView {
    public final float mAmbientShadowBlur;
    public final int mAmbientShadowColor;
    public boolean mDrawShadow;
    public final float mKeyShadowBlur;
    public final int mKeyShadowColor;
    public final float mKeyShadowOffsetX;
    public final float mKeyShadowOffsetY;

    public DoubleShadowTextView(Context context) {
        this(context, null);
    }

    @Override
    public final void onDraw(Canvas canvas) {
        if (!mDrawShadow) {
            getPaint().clearShadowLayer();
            super.onDraw(canvas);
            return;
        }
        getPaint().setShadowLayer(mAmbientShadowBlur, 0.0f, 0.0f, mAmbientShadowColor);
        super.onDraw(canvas);
        canvas.save();
        canvas.clipRect(getScrollX(), getExtendedPaddingTop() + getScrollY(), getWidth() + getScrollX(), getHeight() + getScrollY());
        getPaint().setShadowLayer(mKeyShadowBlur, mKeyShadowOffsetX, mKeyShadowOffsetY, mKeyShadowColor);
        super.onDraw(canvas);
        canvas.restore();
    }

    @Override
    public final void setTextColor(int color) {
        super.setTextColor(color);
        mDrawShadow = ColorUtils.calculateLuminance(color) > 0.5d;
    }

    public DoubleShadowTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DoubleShadowTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDrawShadow = ColorUtils.calculateLuminance(getCurrentTextColor()) > 0.5d;
        mKeyShadowBlur = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_radius);
        mKeyShadowOffsetX = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dx);
        mKeyShadowOffsetY = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dy);
        mKeyShadowColor = context.getResources().getColor(R.color.key_text_shadow_color);
        mAmbientShadowBlur = context.getResources().getDimensionPixelSize(R.dimen.ambient_text_shadow_radius);
        mAmbientShadowColor = context.getResources().getColor(R.color.ambient_text_shadow_color);
    }
}
