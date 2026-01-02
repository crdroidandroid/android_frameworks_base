package com.google.android.systemui.smartspace;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;

import com.android.internal.graphics.ColorUtils;

import com.android.systemui.res.R;

public final class DoubleShadowIconDrawable extends Drawable {
    public final int mAmbientShadowRadius;
    public final int mCanvasSize;
    public RenderNode mDoubleShadowNode;
    public InsetDrawable mIconDrawable;
    public final int mIconInsetSize;
    public final int mKeyShadowOffsetX;
    public final int mKeyShadowOffsetY;
    public final int mKeyShadowRadius;
    public boolean mShowShadow;

    public DoubleShadowIconDrawable(Context context) {
        this(context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size), context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_inset), context);
    }

    @Override
    public final void draw(Canvas canvas) {
        if (canvas.isHardwareAccelerated() && mDoubleShadowNode != null && mShowShadow) {
            if (!mDoubleShadowNode.hasDisplayList()) {
                RecordingCanvas beginRecording = mDoubleShadowNode.beginRecording();
                if (mIconDrawable != null) {
                    mIconDrawable.draw(beginRecording);
                }
                mDoubleShadowNode.endRecording();
            }
            canvas.drawRenderNode(mDoubleShadowNode);
        }
        if (mIconDrawable != null) {
            mIconDrawable.draw(canvas);
        }
    }

    @Override
    public final int getIntrinsicHeight() {
        return mCanvasSize;
    }

    @Override
    public final int getIntrinsicWidth() {
        return mCanvasSize;
    }

    @Override
    public final int getOpacity() {
        return -2;
    }

    @Override
    public final void setAlpha(int alpha) {
        if (mIconDrawable != null) {
            mIconDrawable.setAlpha(alpha);
        }
    }

    @Override
    public final void setColorFilter(ColorFilter colorFilter) {
        if (mIconDrawable != null) {
            mIconDrawable.setColorFilter(colorFilter);
        }
    }

    public final void setIcon(Drawable drawable) {
        RenderNode renderNode = null;
        if (drawable == null) {
            mIconDrawable = null;
            return;
        }
        mIconDrawable = new InsetDrawable(drawable, mIconInsetSize);
        mIconDrawable.setBounds(0, 0, mCanvasSize, mCanvasSize);
        if (mIconDrawable != null) {
            RenderNode shadowNode = new RenderNode("DoubleShadowNode");
            shadowNode.setPosition(0, 0, mCanvasSize, mCanvasSize);
            RenderEffect ambientShadowEffect = RenderEffect.createColorFilterEffect(new PorterDuffColorFilter(Color.argb(48, 0, 0, 0), PorterDuff.Mode.MULTIPLY), RenderEffect.createOffsetEffect(0f, 0f, RenderEffect.createBlurEffect(mAmbientShadowRadius, mAmbientShadowRadius, Shader.TileMode.CLAMP)));
            RenderEffect keyShadowEffect = RenderEffect.createColorFilterEffect(new PorterDuffColorFilter(Color.argb(72, 0, 0, 0), PorterDuff.Mode.MULTIPLY), RenderEffect.createOffsetEffect(mKeyShadowOffsetX, mKeyShadowOffsetY, RenderEffect.createBlurEffect(mKeyShadowRadius, mKeyShadowRadius, Shader.TileMode.CLAMP)));
            if (ambientShadowEffect != null && keyShadowEffect != null) {
                shadowNode.setRenderEffect(RenderEffect.createBlendModeEffect(ambientShadowEffect, keyShadowEffect, BlendMode.DARKEN));
                renderNode = shadowNode;
            }
        }
        mDoubleShadowNode = renderNode;
    }

    @Override
    public final void setTint(int color) {
        if (mIconDrawable != null) {
            mIconDrawable.setTint(color);
        }
        mShowShadow = ColorUtils.calculateLuminance(color) > 0.5d;
    }

    public DoubleShadowIconDrawable(int iconSize, int insetSize, Context context) {
        mShowShadow = true;
        mIconInsetSize = insetSize;
        mCanvasSize = insetSize * 2 + iconSize;
        mAmbientShadowRadius = context.getResources().getDimensionPixelSize(R.dimen.ambient_text_shadow_radius);
        mKeyShadowRadius = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_radius);
        mKeyShadowOffsetX = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dx);
        mKeyShadowOffsetY = context.getResources().getDimensionPixelSize(R.dimen.key_text_shadow_dy);
        setBounds(0, 0, mCanvasSize, mCanvasSize);
    }
}
