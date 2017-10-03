/*
 * Copyright (C) 2018 crDroid Android Project
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

package com.android.systemui.crdroid.batterybar;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.BatteryManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;

public class BatteryBarController extends LinearLayout implements TunerService.Tunable {

    private static final String TAG = "BatteryBarController";

    BatteryBar mainBar;
    BatteryBar alternateStyleBar;

    public static final int STYLE_REGULAR = 0;
    public static final int STYLE_SYMMETRIC = 1;
    public static final int STYLE_REVERSE = 2;

    int mStyle;
    int mLocation;
    int mThickness;

    protected final static int CURRENT_LOC = 1;
    int mLocationToLookFor = 0;

    private boolean mAttached = false;
    private int mBatteryLevel = 0;
    private boolean mBatteryCharging = false;

    boolean isVertical = false;

    private static final String STATUSBAR_BATTERY_BAR =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR;
    private static final String STATUSBAR_BATTERY_BAR_STYLE =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_STYLE;
    private static final String STATUSBAR_BATTERY_BAR_THICKNESS =
            "system:" + Settings.System.STATUSBAR_BATTERY_BAR_THICKNESS;

    public BatteryBarController(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (attrs != null) {
            String ns = "http://schemas.android.com/apk/res/com.android.systemui";
            mLocationToLookFor = attrs.getAttributeIntValue(ns, "viewLocation", 0);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mAttached)
            return;

        mAttached = true;

        isVertical = (getLayoutParams().height == LayoutParams.MATCH_PARENT);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        getContext().registerReceiver(mIntentReceiver, filter);

        Dependency.get(TunerService.class).addTunable(this,
                STATUSBAR_BATTERY_BAR,
                STATUSBAR_BATTERY_BAR_STYLE,
                STATUSBAR_BATTERY_BAR_THICKNESS);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mBatteryCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING;
                Prefs.setLastBatteryLevel(context, mBatteryLevel);
            }
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (!mAttached)
            return;

        mAttached = false;

        Dependency.get(TunerService.class).removeTunable(this);
        getContext().unregisterReceiver(mIntentReceiver);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mAttached) {
            getHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    addBars();
                }
            }, 500);
        }
    }

    public void configThickness() {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int pixels = (int) ((metrics.density * mThickness) + 0.5);

        ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) getLayoutParams();

        if (isVertical)
            params.width = pixels;
        else
            params.height = pixels;
        setLayoutParams(params);

        if (isVertical)
            params.width = pixels;
        else
            params.height = pixels;

        setLayoutParams(params);
    }

    public void addBars() {
        removeAllViews();

        if (mLocation == 0 || !isLocationValid(mLocation))
            return;

        configThickness();

        mBatteryLevel = Prefs.getLastBatteryLevel(getContext());
        if (mStyle == STYLE_REGULAR) {
            addView(new BatteryBar(mContext, mBatteryCharging, mBatteryLevel, isVertical),
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT, 1));
        } else if (mStyle == STYLE_SYMMETRIC) {
            BatteryBar bar1 = new BatteryBar(mContext, mBatteryCharging, mBatteryLevel, isVertical);
            BatteryBar bar2 = new BatteryBar(mContext, mBatteryCharging, mBatteryLevel, isVertical);

            if (isVertical) {
                bar2.setRotation(180);
                addView(bar2, (new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT, 1)));
                addView(bar1, (new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT, 1)));
            } else {
                bar1.setRotation(180);
                addView(bar1, (new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT, 1)));
                addView(bar2, (new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT, 1)));
            }
        } else if (mStyle == STYLE_REVERSE) {
            BatteryBar bar = new BatteryBar(mContext, mBatteryCharging, mBatteryLevel, isVertical);
            bar.setRotation(180);
            addView(bar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT, 1));
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case STATUSBAR_BATTERY_BAR:
                mLocation =
                        newValue == null ? 0 : Integer.parseInt(newValue);
                addBars();
                break;
            case STATUSBAR_BATTERY_BAR_STYLE:
                mStyle =
                        newValue == null ? STYLE_REGULAR : Integer.parseInt(newValue);
                addBars();
                break;
            case STATUSBAR_BATTERY_BAR_THICKNESS:
                mThickness =
                        newValue == null ? 2 : Integer.parseInt(newValue);
                configThickness();
                break;
            default:
                break;
        }
    }

    protected boolean isLocationValid(int location) {
        return mLocationToLookFor == location;
    }
}
