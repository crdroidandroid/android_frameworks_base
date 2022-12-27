/**
 * Copyright (C) 2019-2023 crDroid Android Project
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

package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy.NetworkTrafficState;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;

import java.util.ArrayList;

/** @hide */
public class StatusBarNetworkTraffic extends NetworkTraffic implements DarkReceiver,
        StatusIconDisplayable {

    private int mVisibleState = -1;
    private boolean mColorIsStatic;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private boolean mKeyguardShowing;

    private String mSlot;

    public StatusBarNetworkTraffic(Context context) {
        this(context, null);
    }

    public StatusBarNetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarNetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
    }

    public static StatusBarNetworkTraffic fromContext(Context context, String slot) {
        StatusBarNetworkTraffic v = new StatusBarNetworkTraffic(context);
        v.setSlot(slot);
        v.setVisibleState(STATE_ICON);
        return v;
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mColorIsStatic) {
            return;
        }
        newTint = DarkIconDispatcher.getTint(areas, this, tint);
        checkUpdateTrafficDrawable();
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mColorIsStatic = true;
        newTint = color;
        checkUpdateTrafficDrawable();
    }

    @Override
    public void setDecorColor(int color) {
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    @Override
    public boolean isIconVisible() {
        return mEnabled;
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        mVisibleState = state;
        updateVisibility();
    }

    public void applyNetworkTrafficState(NetworkTrafficState state) {
        // mEnabled and state.visible will have same values, no need to set again
        updateVisibility();
    }

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    mKeyguardShowing = showing;
                    updateVisibility();
                }
            };

    @Override
    protected void setEnabled() {
        mEnabled = mLocation == LOCATION_STATUSBAR;
    }

    @Override
    protected void updateVisibility() {
        boolean visible = mEnabled && mIsActive && getText() != ""
                    && !mKeyguardShowing 
                    && mVisibleState == STATE_ICON;
        if (visible != mVisible) {
            mVisible = visible;
            setVisibility(mVisible ? View.VISIBLE : View.GONE);
            checkUpdateTrafficDrawable();
            requestLayout();
        }
    }

    private void checkUpdateTrafficDrawable() {
        // Wait for icon to be visible and tint to be changed
        if (mVisible && mIconTint != newTint) {
            mIconTint = newTint;
            updateTrafficDrawable();
        }
    }
}
