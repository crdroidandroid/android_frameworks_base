package com.google.android.systemui.smartspace;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.android.systemui.bcsmartspace.R;

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

    public DoubleShadowTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DoubleShadowTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateDrawShadow(getCurrentTextColor());
        this.mKeyShadowBlur = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_radius);
        this.mKeyShadowOffsetX = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dx);
        this.mKeyShadowOffsetY = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dy);
        this.mKeyShadowColor = context.getResources().getColor(R.color.key_text_shadow_color);
        this.mAmbientShadowBlur = context.getResources().getDimensionPixelSize(R.dimen.ambient_text_shadow_radius);
        this.mAmbientShadowColor = context.getResources().getColor(R.color.ambient_text_shadow_color);
    }

    @Override // android.widget.TextView, android.view.View
    public void onDraw(Canvas canvas) {
        if (!this.mDrawShadow) {
            getPaint().clearShadowLayer();
            super.onDraw(canvas);
            return;
        }
        getPaint().setShadowLayer(this.mAmbientShadowBlur, 0.0f, 0.0f, this.mAmbientShadowColor);
        super.onDraw(canvas);
        canvas.save();
        canvas.clipRect(getScrollX(), getExtendedPaddingTop() + getScrollY(), getWidth() + getScrollX(), getHeight() + getScrollY());
        getPaint().setShadowLayer(this.mKeyShadowBlur, this.mKeyShadowOffsetX, this.mKeyShadowOffsetY, this.mKeyShadowColor);
        super.onDraw(canvas);
        canvas.restore();
    }

    @Override // android.widget.TextView
    public void setTextColor(int color) {
        super.setTextColor(color);
        updateDrawShadow(color);
    }

    private void updateDrawShadow(int color) {
        this.mDrawShadow = ColorUtils.calculateLuminance(color) > 0.5d;
    }
}
