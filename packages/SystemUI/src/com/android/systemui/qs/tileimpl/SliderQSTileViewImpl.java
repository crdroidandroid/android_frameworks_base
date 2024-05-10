/*
 * Copyright (C) 2022 StatiXOS
 * Copyright (C) 2024 The LibreMobileOS Foundation
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

package com.android.systemui.qs.tileimpl;

import static android.service.quicksettings.Tile.STATE_ACTIVE;
import static android.service.quicksettings.Tile.STATE_INACTIVE;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;
import com.android.systemui.res.R;

public class SliderQSTileViewImpl extends QSTileViewImpl {

    private PercentageDrawable percentageDrawable;
    private String mSettingsKey;
    private SettingObserver mSettingObserver;
    private boolean enabled = false;
    private float mCurrentPercent;
    private int mWarnColor;

    private final static int ACTIVE_STATE_PERCENTAGE_ALPHA = 64;
    private final static int INACTIVE_STATE_PERCENTAGE_ALPHA = 0;

    public SliderQSTileViewImpl(
            Context context,
            boolean collapsed,
            View.OnTouchListener touchListener,
            String settingKey,
            float settingsDefaultValue) {
        super(context, collapsed);
        if (touchListener != null && !settingKey.isEmpty()) {
            mSettingsKey = settingKey;
            mWarnColor = Utils.getColorErrorDefaultColor(context);
            percentageDrawable = new PercentageDrawable(settingsDefaultValue);
            percentageDrawable.setTint(Color.WHITE);
            updatePercentBackground(STATE_INACTIVE); // default
            mSettingObserver = new SettingObserver(new Handler(Looper.getMainLooper()));
            setOnTouchListener(touchListener);
            mContext.getContentResolver()
                    .registerContentObserver(
                            Settings.System.getUriFor(settingKey),
                            false,
                            mSettingObserver,
                            UserHandle.USER_CURRENT);
            enabled = true;
        }
    }

    @Override
    public void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        if (enabled) {
            updatePercentBackground(state.state);
        }
    }

    @Override
    public int getBackgroundColorForState(int state, boolean disabledByPolicy) {
        if (state == STATE_ACTIVE && mCurrentPercent >= 0.90f) {
            return mWarnColor;
        } else {
            return super.getBackgroundColorForState(state, disabledByPolicy);
        }
    }

    private void updatePercentBackground(int state) {
        // Hide the percentage when inactive.
        boolean isActive = state == STATE_ACTIVE;
        percentageDrawable.setAlpha(isActive ? ACTIVE_STATE_PERCENTAGE_ALPHA
                : INACTIVE_STATE_PERCENTAGE_ALPHA);
        if (isActive) {
            setColor(getBackgroundColorForState(state, false));
        }
        LayerDrawable layerDrawable =
                new LayerDrawable(new Drawable[] {backgroundBaseDrawable, percentageDrawable});
        setBackground(layerDrawable);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            percentageDrawable.updatePercent();
        }
    }

    private class PercentageDrawable extends Drawable {
        private Drawable shape;
        private float mDefaultPercent;

        private PercentageDrawable(float defaultPercent) {
            shape = mContext.getDrawable(R.drawable.qs_tile_background_shape);
            mDefaultPercent = defaultPercent;
            mCurrentPercent = Settings.System.getFloat(mContext.getContentResolver(),
                    mSettingsKey, mDefaultPercent);
        }

        synchronized void updatePercent() {
            mCurrentPercent = Settings.System.getFloat(mContext.getContentResolver(),
                    mSettingsKey, mDefaultPercent);
        }

        @Override
        public void setBounds(Rect bounds) {
            shape.setBounds(bounds);
        }

        @Override
        public void setBounds(int a, int b, int c, int d) {
            shape.setBounds(a, b, c, d);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            // Sometimes, SystemUI doens't create the bitmap correctly and we end up with a 0
            // width bitmap, which is illegal.
            try {
                Bitmap bitmap =
                        Bitmap.createBitmap(
                                Math.round(shape.getBounds().width() * mCurrentPercent),
                                shape.getBounds().height(),
                                Bitmap.Config.ARGB_8888);
                Canvas tempCanvas = new Canvas(bitmap);
                shape.draw(tempCanvas);
                canvas.drawBitmap(bitmap, 0, 0, new Paint());
            } catch (IllegalArgumentException e) {
                Log.e("SliderQSTileViewImpl", "Invalid width or height for creating Bitmap");
                return;
            }
        }

        @Override
        public void setAlpha(int i) {
            shape.setAlpha(i);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            shape.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.UNKNOWN;
        }

        @Override
        public void setTint(int t) {
            shape.setTint(t);
        }
    }
}
