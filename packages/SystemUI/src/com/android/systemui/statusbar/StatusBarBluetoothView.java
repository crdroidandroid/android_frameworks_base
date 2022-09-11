/*
 * Copyright (C) 2018 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_DOT;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN;
import static com.android.systemui.statusbar.StatusBarIconView.STATE_ICON;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy.BluetoothIconState;

import com.android.settingslib.Utils;

import java.util.ArrayList;

public class StatusBarBluetoothView extends FrameLayout implements StatusIconDisplayable {
    private static final String TAG = "StatusBarBluetoothView";

    /// Used to show etc dots
    private StatusBarIconView mDotView;

    /// Contains the main icon layout
    private LinearLayout mBluetoothGroup;
    private ImageView mBluetoothIcon;
    private ImageView mBatteryIcon;
    private BluetoothIconState mState;
    private String mSlot;
    private int mVisibleState = -1;
    private int mBatteryLevel = -1;
    private ColorStateList mBatteryColor;

    public static StatusBarBluetoothView fromContext(Context context, String slot) {
        StatusBarBluetoothView v = (StatusBarBluetoothView)
                LayoutInflater.from(context).inflate(R.layout.status_bar_bluetooth_group, null);
        v.setSlot(slot);
        v.init();
        v.setVisibleState(STATE_ICON);
        return v;
    }

    public StatusBarBluetoothView(Context context) {
        super(context);
    }

    public StatusBarBluetoothView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StatusBarBluetoothView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StatusBarBluetoothView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void setSlot(String slot) {
        mSlot = slot;
    }

    @Override
    public void setStaticDrawableColor(int color) {
        ColorStateList list = ColorStateList.valueOf(color);
        mBatteryColor = list;
        updateBatteryColor();
        mBluetoothIcon.setImageTintList(list);
        mDotView.setDecorColor(color);
    }

    @Override
    public void setDecorColor(int color) {
        mDotView.setDecorColor(color);
    }

    @Override
    public String getSlot() {
        return mSlot;
    }

    @Override
    public boolean isIconVisible() {
        return mState != null && mState.visible;
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (state == mVisibleState) {
            return;
        }
        mVisibleState = state;

        switch (state) {
            case STATE_ICON:
                mBluetoothGroup.setVisibility(View.VISIBLE);
                mDotView.setVisibility(View.GONE);
                break;
            case STATE_DOT:
                mBluetoothGroup.setVisibility(View.GONE);
                mDotView.setVisibility(View.VISIBLE);
                break;
            case STATE_HIDDEN:
            default:
                mBluetoothGroup.setVisibility(View.GONE);
                mDotView.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = getTranslationX();
        float translationY = getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    private void init() {
        mBluetoothGroup = findViewById(R.id.bluetooth_group);
        mBluetoothIcon = findViewById(R.id.bluetooth_icon);
        mBatteryIcon = findViewById(R.id.bluetooth_battery);

        initDotView();
    }

    private void initDotView() {
        mDotView = new StatusBarIconView(mContext, mSlot, null);
        mDotView.setVisibleState(STATE_DOT);

        int width = mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_size);
        LayoutParams lp = new LayoutParams(width, width);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        addView(mDotView, lp);
    }

    public void applyBluetoothState(BluetoothIconState state) {
        boolean requestLayout = false;

        if (state == null) {
            requestLayout = getVisibility() != View.GONE;
            setVisibility(View.GONE);
            mState = null;
        } else if (mState == null) {
            requestLayout = true;
            mState = state;
            initViewState();
        } else if (!mState.equals(state)) {
            requestLayout = updateState(state);
        }

        if (requestLayout) {
            requestLayout();
        }
    }

    private boolean updateState(BluetoothIconState state) {
        setContentDescription(state.contentDescription);

        if (mState.batteryLevel != state.batteryLevel) {
            updateBatteryIcon(state.batteryLevel);
        }

        boolean needsLayout = mState.batteryLevel != state.batteryLevel;

        if (mState.visible != state.visible) {
            needsLayout |= true;
            setVisibility(state.visible ? View.VISIBLE : View.GONE);
        }

        mState = state;
        return needsLayout;
    }

    private void updateBatteryIcon(int batteryLevel) {
        mBatteryLevel = batteryLevel;
        if (batteryLevel >= 0 && batteryLevel <= 100) {
            mBatteryIcon.setVisibility(View.VISIBLE);
            mBatteryIcon.setImageDrawable(mContext.getDrawable(
                mContext.getResources().getIdentifier("ic_bluetooth_battery_"
                + batteryLevel/10, "drawable", mContext.getPackageName())));
            updateBatteryColor();
        } else {
            mBatteryIcon.setVisibility(View.GONE);
        }
    }

    private void updateBatteryColor() {
        mBatteryIcon.setImageTintList(mBatteryLevel > 20 ? mBatteryColor :
                Utils.getColorError(mContext));
    }

    private void initViewState() {
        setContentDescription(mState.contentDescription);
        updateBatteryIcon(mState.batteryLevel);
        setVisibility(mState.visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        int areaTint = getTint(areas, this, tint);
        ColorStateList color = ColorStateList.valueOf(areaTint);
        mBatteryColor = color;
        updateBatteryColor();
        mBluetoothIcon.setImageTintList(color);
        mDotView.setDecorColor(areaTint);
        mDotView.setIconColor(areaTint, false);
    }

    @Override
    public String toString() {
        return "StatusBarBluetoothView(slot=" + mSlot + " state=" + mState + ")";
    }
}
