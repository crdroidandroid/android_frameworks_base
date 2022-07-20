package com.google.android.systemui.smartspace;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import com.android.internal.graphics.ColorUtils;
import com.android.systemui.bcsmartspace.R;

public class DoubleShadowIconDrawable extends Drawable {
    public int mAmbientShadowRadius;
    public final int mCanvasSize;
    public RenderNode mDoubleShadowNode;
    public InsetDrawable mIconDrawable;
    public final int mIconInsetSize;
    public int mKeyShadowOffsetX;
    public int mKeyShadowOffsetY;
    public int mKeyShadowRadius;
    public boolean mShowShadow;

    public DoubleShadowIconDrawable(Context context) {
        int dimensionPixelSize = context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size);
        int dimensionPixelSize2 = context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_inset);
        this.mIconInsetSize = dimensionPixelSize2;
        int i = (dimensionPixelSize2 * 2) + dimensionPixelSize;
        this.mCanvasSize = i;
        this.mAmbientShadowRadius = context.getResources().getDimensionPixelSize(R.dimen.ambient_text_shadow_radius);
        this.mKeyShadowRadius = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_radius);
        this.mKeyShadowOffsetX = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dx);
        this.mKeyShadowOffsetY = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dy);
        setBounds(0, 0, i, i);
    }

    @Override // android.graphics.drawable.Drawable
    public int getOpacity() {
        return 0;
    }

    public void setIcon(Drawable drawable) {
        RenderNode renderNode = null;
        if (drawable == null) {
            this.mIconDrawable = null;
            return;
        }
        InsetDrawable insetDrawable = new InsetDrawable(drawable, this.mIconInsetSize);
        this.mIconDrawable = insetDrawable;
        int i = this.mCanvasSize;
        insetDrawable.setBounds(0, 0, i, i);
        if (this.mIconDrawable != null) {
            RenderNode renderNode2 = new RenderNode("DoubleShadowNode");
            int i2 = this.mCanvasSize;
            renderNode2.setPosition(0, 0, i2, i2);
            RenderEffect createShadowRenderEffect = createShadowRenderEffect(this.mAmbientShadowRadius, 0, 0, 48);
            RenderEffect createShadowRenderEffect2 = createShadowRenderEffect(this.mKeyShadowRadius, this.mKeyShadowOffsetX, this.mKeyShadowOffsetY, 72);
            if (createShadowRenderEffect != null && createShadowRenderEffect2 != null) {
                renderNode2.setRenderEffect(RenderEffect.createBlendModeEffect(createShadowRenderEffect, createShadowRenderEffect2, BlendMode.DARKEN));
                renderNode = renderNode2;
            }
        }
        this.mDoubleShadowNode = renderNode;
    }

    public static RenderEffect createShadowRenderEffect(int i, int i2, int i3, int i4) {
        return RenderEffect.createColorFilterEffect(new PorterDuffColorFilter(Color.argb(i4, 0, 0, 0), PorterDuff.Mode.MULTIPLY), RenderEffect.createOffsetEffect(i2, i3, RenderEffect.createBlurEffect(i, i, Shader.TileMode.CLAMP)));
    }

    @Override // android.graphics.drawable.Drawable
    public void setAlpha(int i) {
        this.mIconDrawable.setAlpha(i);
    }

    @Override // android.graphics.drawable.Drawable
    public void setColorFilter(ColorFilter colorFilter) {
        this.mIconDrawable.setColorFilter(colorFilter);
    }

    @Override // android.graphics.drawable.Drawable
    public void setTint(int alpha) {
        if (this.mIconDrawable != null) {
            this.mIconDrawable.setTint(alpha);
        }
        this.mShowShadow = ColorUtils.calculateLuminance(alpha) > 0.5d;
    }

    @Override // android.graphics.drawable.Drawable
    public void draw(Canvas canvas) {
        RenderNode renderNode;
        if (canvas.isHardwareAccelerated() && (renderNode = this.mDoubleShadowNode) != null && this.mShowShadow) {
            if (!renderNode.hasDisplayList()) {
                this.mIconDrawable.draw(this.mDoubleShadowNode.beginRecording());
                this.mDoubleShadowNode.endRecording();
            }
            canvas.drawRenderNode(this.mDoubleShadowNode);
        }
        InsetDrawable insetDrawable = this.mIconDrawable;
        if (insetDrawable != null) {
            insetDrawable.draw(canvas);
        }
    }
}
