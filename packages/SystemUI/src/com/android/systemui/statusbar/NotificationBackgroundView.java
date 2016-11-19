/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.net.Uri;
import android.os.UserHandle;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.tuner.TunerService;

/**
 * A view that can be used for both the dimmed and normal background of an notification.
 */
public class NotificationBackgroundView extends View implements TunerService.Tunable {

    private Drawable mBackground;
    private int mClipTopAmount;
    private int mActualHeight;
    private static int mNotTranslucencyPercentage;
    private static boolean mTranslucentNotifications;

    private static final String TRANSLUCENT_NOTIFICATIONS_PREFERENCE_KEY =
            "system:" + Settings.System.TRANSLUCENT_NOTIFICATIONS_PREFERENCE_KEY;
    private static final String TRANSLUCENT_NOTIFICATIONS_PERCENTAGE_PREFERENCE_KEY =
            "system:" + Settings.System.TRANSLUCENT_NOTIFICATIONS_PERCENTAGE_PREFERENCE_KEY;

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, mBackground);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        if (drawable != null && mActualHeight > mClipTopAmount) {
            drawable.setBounds(0, mClipTopAmount, getWidth(), mActualHeight);

            if (mTranslucentNotifications) {
                if (drawable.getAlpha() != mNotTranslucencyPercentage)
                    drawable.setAlpha(mNotTranslucencyPercentage);
                if (NotificationPanelView.mKeyguardShowing) {
                    drawable.setAlpha(179);
                }
                if (NotificationPanelView.mHeadsUpShowing || NotificationPanelView.mHeadsUpAnimatingAway) {
                    drawable.setAlpha(255);
                }
            } else {
                drawable.setAlpha(255);
            }
            drawable.draw(canvas);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TunerService.get(mContext).addTunable(this,
                TRANSLUCENT_NOTIFICATIONS_PREFERENCE_KEY,
                TRANSLUCENT_NOTIFICATIONS_PERCENTAGE_PREFERENCE_KEY);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        TunerService.get(mContext).removeTunable(this);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        drawableStateChanged(mBackground);
    }

    private void drawableStateChanged(Drawable d) {
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case TRANSLUCENT_NOTIFICATIONS_PREFERENCE_KEY:
                 mTranslucentNotifications =
                        newValue != null && Integer.parseInt(newValue) == 1;
                setBlurSettings();
                break;
            case TRANSLUCENT_NOTIFICATIONS_PERCENTAGE_PREFERENCE_KEY:
                mNotTranslucencyPercentage =
                        newValue == null ? 70 : Integer.parseInt(newValue);
                setBlurSettings();
                break;
            default:
                break;
        }
    }

    private void setBlurSettings() {
        mNotTranslucencyPercentage = 255 - ((mNotTranslucencyPercentage * 255) / 100);
    }

    /**
     * Sets a background drawable. As we need to change our bounds independently of layout, we need
     * the notion of a background independently of the regular View background..
     */
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        if (mBackground != null) {
            mBackground.setCallback(this);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    public void setTint(int tintColor) {
        if (tintColor != 0) {
            mBackground.setColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP);
        } else {
            mBackground.clearColorFilter();
        }
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        mBackground.setState(drawableState);
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
        }
    }
}
