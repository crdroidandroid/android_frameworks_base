/*
 * Copyright (C) 2018-2025 crDroid Android Project
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

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;

import java.util.ArrayList;

public abstract class LogoImage extends ImageView implements DarkReceiver {

    private static final int[] LOGO_RES_IDS = {
            R.drawable.ic_crdroid_logo,
            R.drawable.ic_android_logo,
            R.drawable.ic_adidas,
            R.drawable.ic_alien,
            R.drawable.ic_apple_logo,
            R.drawable.ic_avengers,
            R.drawable.ic_batman,
            R.drawable.ic_batman_tdk,
            R.drawable.ic_beats,
            R.drawable.ic_biohazard,
            R.drawable.ic_blackberry,
            R.drawable.ic_cannabis,
            R.drawable.ic_emoticon_cool,
            R.drawable.ic_emoticon_devil,
            R.drawable.ic_fire,
            R.drawable.ic_heart,
            R.drawable.ic_nike,
            R.drawable.ic_pac_man,
            R.drawable.ic_puma,
            R.drawable.ic_rog,
            R.drawable.ic_spiderman,
            R.drawable.ic_superman,
            R.drawable.ic_windows,
            R.drawable.ic_xbox,
            R.drawable.ic_ghost,
            R.drawable.ic_ninja,
            R.drawable.ic_robot,
            R.drawable.ic_ironman,
            R.drawable.ic_captain_america,
            R.drawable.ic_flash,
            R.drawable.ic_tux_logo,
            R.drawable.ic_ubuntu_logo,
            R.drawable.ic_mint_logo,
    };

    private final ContentObserver mSettingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    updateSettings();
                }
            };

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    updateSettings();
                }
            };

    private boolean mAttached;

    private boolean mShowLogo;
    public int mLogoPosition;
    private int mLogoStyle;
    private int mLoadedStyle = -1;
    private int mTintColor = Color.WHITE;
    // Whether the status bar (disable flags, keyguard, etc.) currently allows the logo.
    private boolean mAllowedBySystem = true;

    public LogoImage(Context context) {
        this(context, null);
    }

    public LogoImage(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LogoImage(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setImageTintList(ColorStateList.valueOf(mTintColor));
    }

    protected abstract boolean isLogoVisible();

    /** True if the user enabled the logo and selected this view's position. */
    public boolean isLogoEnabled() {
        return mShowLogo && isLogoVisible();
    }

    /**
     * Called by the status bar binder with system-driven visibility. While disallowed, settings
     * changes only update the drawable and leave visibility/alpha to the binder.
     */
    public void setAllowedBySystem(boolean allowed) {
        mAllowedBySystem = allowed;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAttached) {
            return;
        }
        mAttached = true;

        final ContentResolver resolver = getContext().getContentResolver();
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_POSITION),
                false, mSettingsObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_STYLE),
                false, mSettingsObserver, UserHandle.USER_ALL);
        Dependency.get(UserTracker.class).addCallback(
                mUserChangedCallback, getContext().getMainExecutor());
        updateSettings();

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mAttached) {
            return;
        }
        mAttached = false;

        getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
        Dependency.get(UserTracker.class).removeCallback(mUserChangedCallback);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLoadedStyle = -1;
        if (isLogoEnabled()) {
            updateLogo();
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        final int color = DarkIconDispatcher.getTint(areas, this, tint);
        if (color == mTintColor) {
            return;
        }
        mTintColor = color;
        setImageTintList(ColorStateList.valueOf(color));
    }

    public void updateLogo() {
        final int style = (mLogoStyle >= 0 && mLogoStyle < LOGO_RES_IDS.length) ? mLogoStyle : 0;
        if (style == mLoadedStyle) {
            return;
        }
        setImageDrawable(getContext().getDrawable(LOGO_RES_IDS[style]));
        mLoadedStyle = style;
    }

    public void updateSettings() {
        final ContentResolver resolver = getContext().getContentResolver();
        mShowLogo = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_LOGO, 0, UserHandle.USER_CURRENT) != 0;
        mLogoPosition = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_LOGO_POSITION, 0, UserHandle.USER_CURRENT);
        mLogoStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_LOGO_STYLE, 0, UserHandle.USER_CURRENT);
        if (!isLogoEnabled()) {
            animate().cancel();
            setImageDrawable(null);
            mLoadedStyle = -1;
            setVisibility(View.GONE);
            return;
        }
        updateLogo();
        if (mAllowedBySystem) {
            animate().cancel();
            setAlpha(1f);
            setVisibility(View.VISIBLE);
        }
    }
}
