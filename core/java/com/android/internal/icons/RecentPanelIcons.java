/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.internal.icons;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.os.Process;
import android.os.UserHandle;

/**
 * Helper methods for generating various launcher icons
 */
public class RecentPanelIcons {

    private static final Rect sOldBounds = new Rect();
    private static final Canvas sCanvas = new Canvas();

    static {
        sCanvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.DITHER_FLAG,
                Paint.FILTER_BITMAP_FLAG));
    }

    /**
     * Returns a bitmap which is of the appropriate size to be displayed as an icon
     */
    public static Bitmap createIconBitmap(Bitmap icon, Context context,
            IconNormalizer normalizer, ShadowGenerator shadowGenerator) {
        final int iconBitmapSize = (int) (context.getResources()
                .getDimensionPixelSize(normalizer.getIconSizeId()) * normalizer.getScaleFactor());

        if (iconBitmapSize == icon.getWidth() && iconBitmapSize == icon.getHeight()) {
            return icon;
        }
        return createIconBitmap(new BitmapDrawable(context.getResources(), icon), context, normalizer, shadowGenerator);
    }

    /**
     * Returns a bitmap suitable for the all apps view. The icon is badged for {@param user}.
     * The bitmap is also visually normalized with other icons.
     */

    public static Bitmap createBadgedIconBitmap(
            IconNormalizer normalizer, ShadowGenerator shadowGenerator, Drawable icon,
            UserHandle user, Context context, int iconAppTargetSdk, boolean forceWrap, boolean defaultIconPack) {
        float scale = 1f;
        boolean[] outShape = new boolean[1];
        AdaptiveIconDrawable dr = (AdaptiveIconDrawable)
                context.getDrawable(com.android.internal.R.drawable.adaptive_icon_drawable_wrapper).mutate();
        dr.setBounds(0, 0, 1, 1);
        scale = normalizer.getScale(icon, null, dr.getIconMask(), outShape);
        if ((forceWrap || defaultIconPack)
                && !outShape[0]){
            Drawable wrappedIcon = wrapToAdaptiveIconDrawable(context, icon, scale);
            if (wrappedIcon != icon) {
                icon = wrappedIcon;
                scale = normalizer.getScale(icon, null, null, null);
            }
        }
        Bitmap bitmap = createIconBitmap(icon, context, scale, normalizer, shadowGenerator);
        if (icon instanceof AdaptiveIconDrawable) {
            bitmap = shadowGenerator.recreateIcon(bitmap);
        }
        return badgeIconForUser(bitmap, user, context, normalizer, shadowGenerator);
    }

    /**
     * Badges the provided icon with the user badge if required.
     */
    public static Bitmap badgeIconForUser(Bitmap icon, UserHandle user, Context context,
            IconNormalizer normalizer, ShadowGenerator shadowGenerator) {
        if (user != null && !Process.myUserHandle().equals(user)) {
            BitmapDrawable drawable = new FixedSizeBitmapDrawable(icon);
            Drawable badged = context.getPackageManager().getUserBadgedIcon(
                    drawable, user);
            if (badged instanceof BitmapDrawable) {
                return ((BitmapDrawable) badged).getBitmap();
            } else {
                return createIconBitmap(badged, context, normalizer, shadowGenerator);
            }
        } else {
            return icon;
        }
    }

    /**
     * If the platform is running O but the app is not providing AdaptiveIconDrawable, then
     * shrink the legacy icon and set it as foreground. Use color drawable as background to
     * create AdaptiveIconDrawable.
     */
    static Drawable wrapToAdaptiveIconDrawable(Context context, Drawable drawable, float scale) {
        try {
            if (!(drawable instanceof AdaptiveIconDrawable)) {
                AdaptiveIconDrawable iconWrapper = (AdaptiveIconDrawable)
                        context.getDrawable(com.android.internal.R.drawable.adaptive_icon_drawable_wrapper).mutate();
                FixedScaleDrawable fsd = ((FixedScaleDrawable) iconWrapper.getForeground());
                fsd.setDrawable(drawable);
                fsd.setScale(scale);
                return (Drawable) iconWrapper;
            }
        } catch (Exception e) {
            return drawable;
        }
        return drawable;
    }

    /**
     * Returns a bitmap suitable for the all apps view.
     */
    public static Bitmap createIconBitmap(Drawable icon, Context context,
            IconNormalizer normalizer, ShadowGenerator shadowGenerator) {
        float scale = 1f;
        if (icon instanceof AdaptiveIconDrawable) {
            scale = ShadowGenerator.getScaleForBounds(new RectF(0, 0, 0, 0));
        }
        Bitmap bitmap =  createIconBitmap(icon, context, scale, normalizer, shadowGenerator);
        if (icon instanceof AdaptiveIconDrawable) {
            bitmap = shadowGenerator.recreateIcon(bitmap);
        }
        return bitmap;
    }

    /**
     * @param scale the scale to apply before drawing {@param icon} on the canvas
     */
    public static Bitmap createIconBitmap(Drawable icon, Context context, float scale,
            IconNormalizer normalizer, ShadowGenerator shadowGenerator) {
        synchronized (sCanvas) {
            final int iconBitmapSize = (int) (context.getResources()
                    .getDimensionPixelSize(normalizer.getIconSizeId()) * normalizer.getScaleFactor());
            int width = iconBitmapSize;
            int height = iconBitmapSize;

            if (icon instanceof PaintDrawable) {
                PaintDrawable painter = (PaintDrawable) icon;
                painter.setIntrinsicWidth(width);
                painter.setIntrinsicHeight(height);
            } else if (icon instanceof BitmapDrawable) {
                // Ensure the bitmap has a density.
                BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
                Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap != null && bitmap.getDensity() == Bitmap.DENSITY_NONE) {
                    bitmapDrawable.setTargetDensity(context.getResources().getDisplayMetrics());
                }
            }

            int sourceWidth = icon.getIntrinsicWidth();
            int sourceHeight = icon.getIntrinsicHeight();
            if (sourceWidth > 0 && sourceHeight > 0) {
                // Scale the icon proportionally to the icon dimensions
                final float ratio = (float) sourceWidth / sourceHeight;
                if (sourceWidth > sourceHeight) {
                    height = (int) (width / ratio);
                } else if (sourceHeight > sourceWidth) {
                    width = (int) (height * ratio);
                }
            }
            // no intrinsic size --> use default size
            int textureWidth = iconBitmapSize;
            int textureHeight = iconBitmapSize;

            Bitmap bitmap = Bitmap.createBitmap(textureWidth, textureHeight,
                    Bitmap.Config.ARGB_8888);
            final Canvas canvas = sCanvas;
            canvas.setBitmap(bitmap);

            final int left = (textureWidth-width) / 2;
            final int top = (textureHeight-height) / 2;

            sOldBounds.set(icon.getBounds());
            if (icon instanceof AdaptiveIconDrawable) {
                int offset = Math.max((int)(ShadowGenerator.BLUR_FACTOR * iconBitmapSize),
                        Math.min(left, top));
                int size = Math.max(width, height);
                icon.setBounds(offset, offset, size, size);
            } else {
                icon.setBounds(left, top, left+width, top+height);
            }
            canvas.save(Canvas.MATRIX_SAVE_FLAG);
            canvas.scale(scale, scale, textureWidth / 2, textureHeight / 2);
            icon.draw(canvas);
            canvas.restore();
            icon.setBounds(sOldBounds);
            canvas.setBitmap(null);

            return bitmap;
        }
    }

    /**
     * An extension of {@link BitmapDrawable} which returns the bitmap pixel size as intrinsic size.
     * This allows the badging to be done based on the action bitmap size rather than
     * the scaled bitmap size.
     */
    private static class FixedSizeBitmapDrawable extends BitmapDrawable {

        public FixedSizeBitmapDrawable(Bitmap bitmap) {
            super(null, bitmap);
        }

        @Override
        public int getIntrinsicHeight() {
            return getBitmap().getWidth();
        }

        @Override
        public int getIntrinsicWidth() {
            return getBitmap().getWidth();
        }
    }
}
