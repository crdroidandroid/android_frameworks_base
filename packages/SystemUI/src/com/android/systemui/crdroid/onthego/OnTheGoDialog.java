/*
 * Copyright (C) 2014 The NamelessRom Project
 *           (C) 2026 crDroid Android Project
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

package com.android.systemui.crdroid.onthego;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.res.R;

import com.android.internal.util.crdroid.OnTheGoUtils;

public class OnTheGoDialog extends Dialog {

    private static final float MIN_ALPHA = 0.15f;

    private static final int DIALOG_MAX_WIDTH_DP   = 560;
    private static final int DIALOG_SIDE_MARGIN_DP = 24;

    protected final Context mContext;
    protected final Handler mHandler = new Handler(Looper.getMainLooper());

    private final int mOnTheGoDialogLongTimeout;
    private final int mOnTheGoDialogShortTimeout;

    private TextView mAlphaValue;

    private final Runnable mDismissDialogRunnable = new Runnable() {
        public void run() {
            if (OnTheGoDialog.this.isShowing()) {
                OnTheGoDialog.this.dismiss();
            }
        }
    };

    public OnTheGoDialog(Context ctx) {
        super(ctx, android.R.style.Theme_DeviceDefault_DayNight);
        mContext = ctx;
        final Resources r = mContext.getResources();
        mOnTheGoDialogLongTimeout =
                r.getInteger(R.integer.quick_settings_onthego_dialog_long_timeout);
        mOnTheGoDialogShortTimeout =
                r.getInteger(R.integer.quick_settings_onthego_dialog_short_timeout);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Window window = getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.getAttributes().privateFlags |=
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.requestFeature(Window.FEATURE_NO_TITLE);
        // The rounded card supplies its own surface; keep the window transparent.
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.CENTER);

        setContentView(R.layout.quick_settings_onthego_dialog);
        setCanceledOnTouchOutside(true);

        applyDialogWidth(window);

        final ContentResolver resolver = mContext.getContentResolver();

        mAlphaValue = (TextView) findViewById(R.id.alpha_value);

        final SeekBar slider = (SeekBar) findViewById(R.id.alpha_slider);
        final float alpha = Settings.System.getFloat(resolver,
                Settings.System.ON_THE_GO_ALPHA, 0.5f);
        final int progress = alphaToProgress(alpha);
        slider.setProgress(progress);
        updateAlphaLabel(progress);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateAlphaLabel(progress);
                // Ignore the programmatic setProgress() above; only react to drags.
                if (!fromUser) {
                    return;
                }
                sendAlphaBroadcast(progressToAlpha(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                removeAllOnTheGoDialogCallbacks();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dismissOnTheGoDialog(mOnTheGoDialogShortTimeout);
            }
        });

        if (!OnTheGoUtils.hasFrontCamera(getContext())) {
            findViewById(R.id.onthego_category_1).setVisibility(View.GONE);
        } else {
            final Switch mServiceToggle = (Switch) findViewById(R.id.onthego_service_toggle);
            final boolean restartService = Settings.System.getInt(resolver,
                    Settings.System.ON_THE_GO_SERVICE_RESTART, 0) == 1;
            mServiceToggle.setChecked(restartService);
            mServiceToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    Settings.System.putInt(resolver,
                            Settings.System.ON_THE_GO_SERVICE_RESTART,
                            (b ? 1 : 0));
                    dismissOnTheGoDialog(mOnTheGoDialogShortTimeout);
                }
            });

            final Switch mCamSwitch = (Switch) findViewById(R.id.onthego_camera_toggle);
            final boolean useFrontCam = (Settings.System.getInt(resolver,
                    Settings.System.ON_THE_GO_CAMERA,
                    0) == 1);
            mCamSwitch.setChecked(useFrontCam);
            mCamSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    Settings.System.putInt(resolver,
                            Settings.System.ON_THE_GO_CAMERA,
                            (b ? 1 : 0));
                    sendCameraBroadcast();
                    dismissOnTheGoDialog(mOnTheGoDialogShortTimeout);
                }
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        dismissOnTheGoDialog(mOnTheGoDialogLongTimeout);
    }

    @Override
    protected void onStop() {
        super.onStop();
        removeAllOnTheGoDialogCallbacks();
    }

    private void applyDialogWidth(Window window) {
        final DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        final int maxWidth = (int) (DIALOG_MAX_WIDTH_DP * dm.density);
        final int margin = (int) (DIALOG_SIDE_MARGIN_DP * dm.density);
        final int width = Math.min(dm.widthPixels - (2 * margin), maxWidth);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static float progressToAlpha(int progress) {
        return MIN_ALPHA + (progress / 100f) * (1f - MIN_ALPHA);
    }

    private static int alphaToProgress(float alpha) {
        final float clamped = Math.max(MIN_ALPHA, Math.min(1f, alpha));
        return Math.round((clamped - MIN_ALPHA) / (1f - MIN_ALPHA) * 100f);
    }

    private void updateAlphaLabel(int progress) {
        if (mAlphaValue != null) {
            mAlphaValue.setText(Math.round(progressToAlpha(progress) * 100f) + "%");
        }
    }

    private void dismissOnTheGoDialog(int timeout) {
        removeAllOnTheGoDialogCallbacks();
        mHandler.postDelayed(mDismissDialogRunnable, timeout);
    }

    private void removeAllOnTheGoDialogCallbacks() {
        mHandler.removeCallbacks(mDismissDialogRunnable);
    }

    private void sendAlphaBroadcast(float alpha) {
        final Intent alphaBroadcast = new Intent(OnTheGoService.ACTION_TOGGLE_ALPHA);
        alphaBroadcast.setPackage(mContext.getPackageName());
        alphaBroadcast.putExtra(OnTheGoService.EXTRA_ALPHA, alpha);
        mContext.sendBroadcast(alphaBroadcast);
    }

    private void sendCameraBroadcast() {
        final Intent cameraBroadcast = new Intent(OnTheGoService.ACTION_TOGGLE_CAMERA);
        cameraBroadcast.setPackage(mContext.getPackageName());
        mContext.sendBroadcast(cameraBroadcast);
    }

}
