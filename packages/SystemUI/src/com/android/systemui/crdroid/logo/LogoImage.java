/*
 * Copyright (C) 2018-2023 crDroid Android Project
 * Copyright (C) 2018-2019 AICP
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

package com.android.systemui.crdroid.logo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.util.ArrayList;

public abstract class LogoImage extends ImageView {

    private Context mContext;

    private boolean mAttached;

    private boolean mShowLogo;
    public int mLogoPosition;
    private int mLogoStyle;
    private int mTintColor = Color.WHITE;

    private static final String STATUS_BAR_LOGO =
            Settings.System.STATUS_BAR_LOGO;
    private static final String STATUS_BAR_LOGO_POSITION =
            Settings.System.STATUS_BAR_LOGO_POSITION;
    private static final String STATUS_BAR_LOGO_STYLE =
            Settings.System.STATUS_BAR_LOGO_STYLE;

    class SettingsObserver extends ContentObserver {

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(STATUS_BAR_LOGO), false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(STATUS_BAR_LOGO_POSITION),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(STATUS_BAR_LOGO_STYLE),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    public LogoImage(Context context) {
        this(context, null);
    }

    public LogoImage(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogoImage(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    protected abstract boolean isLogoVisible();

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached)
            return;

        mAttached = true;

        SettingsObserver observer = new SettingsObserver(new Handler());
        observer.observe();
        updateSettings();

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached)
            return;

        mAttached = false;
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(areas, this, tint);
        if (mShowLogo && isLogoVisible()) {
            updateLogo();
        }
    }

    public void updateLogo() {
        Drawable drawable = null;
        switch (mLogoStyle) {
            case 0:
            default:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_crdroid_logo);
                break;
            case 1:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_android_logo);
                break;
            case 2:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_adidas);
                break;
            case 3:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_alien);
                break;
            case 4:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_apple_logo);
                break;
            case 5:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_avengers);
                break;
            case 6:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_batman);
                break;
            case 7:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_batman_tdk);
                break;
            case 8:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_beats);
                break;
            case 9:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_biohazard);
                break;
            case 10:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_blackberry);
                break;
            case 11:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_cannabis);
                break;
            case 12:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_cool);
                break;
            case 13:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_emoticon_devil);
                break;
            case 14:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_fire);
                break;
            case 15:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_heart);
                break;
            case 16:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_nike);
                break;
            case 17:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_pac_man);
                break;
            case 18:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_puma);
                break;
            case 19:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_rog);
                break;
            case 20:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_spiderman);
                break;
            case 21:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_superman);
                break;
            case 22:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_windows);
                break;
            case 23:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_xbox);
                break;
            case 24:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ghost);
                break;
            case 25:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ninja);
                break;
            case 26:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_robot);
                break;
            case 27:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ironman);
                break;
            case 28:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_captain_america);
                break;
            case 29:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_flash);
                break;
            case 30:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_tux_logo);
                break;
            case 31:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_ubuntu_logo);
                break;
            case 32:
                drawable = mContext.getResources().getDrawable(R.drawable.ic_mint_logo);
                break;
        }

        drawable.setTint(mTintColor);
        setImageDrawable(drawable);
    }

    public void updateSettings() {
        mShowLogo = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_LOGO, 0) != 0;
        mLogoPosition = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_LOGO_POSITION, 0);
        mLogoStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_LOGO_STYLE, 0);
        if (!mShowLogo || !isLogoVisible()) {
            setImageDrawable(null);
            setVisibility(View.GONE);
            return;
        }
        updateLogo();
        setVisibility(View.VISIBLE);
    }
}
