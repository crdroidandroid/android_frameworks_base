/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.display.ColorDisplayManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;
import android.view.View;

import com.android.internal.R;
import com.android.internal.util.crdroid.FodUtils;

import com.android.systemui.SystemUI;
import com.android.systemui.biometrics.FODCircleViewImplCallback;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.Assert;

import lineageos.app.LineageContextConstants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class FODCircleViewImpl extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "FODCircleViewImpl";

    private FODCircleView mFodCircleView;

    private final ArrayList<WeakReference<FODCircleViewImplCallback>>
            mCallbacks = new ArrayList<>();
    private final CommandQueue mCommandQueue;
    private boolean mDisableNightMode;
    private boolean mNightModeActive;
    private int mAutoModeState;

    private boolean mIsFODVisible;

    @Inject
    public FODCircleViewImpl(Context context, CommandQueue commandQueue) {
        super(context);
        mCommandQueue = commandQueue;
    }

    @Override
    public void start() {
        PackageManager packageManager = mContext.getPackageManager();
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                !(packageManager.hasSystemFeature(LineageContextConstants.Features.FOD) || FodUtils.hasFodSupport(mContext))) {
            return;
        }
        mCommandQueue.addCallback(this);
        try {
            mFodCircleView = new FODCircleView(mContext);
            for (int i = 0; i < mCallbacks.size(); i++) {
                FODCircleViewImplCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onFODStart();
                }
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to initialize FODCircleView", e);
        }
        mDisableNightMode = mContext.getResources().getBoolean(R.bool.disable_fod_night_light);
    }

    @Override
    public void showInDisplayFingerprintView() {
        if (mFodCircleView != null) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                FODCircleViewImplCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onFODStatusChange(true);
                }
            }
            mIsFODVisible = true;
            if (mDisableNightMode && isNightLightEnabled()) {
                disableNightMode();
            }
            mFodCircleView.show();
        }
    }

    private boolean isNightLightEnabled() {
       return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_NIGHT_LIGHT, 1) == 1;
    }
    
    @Override
    public void hideInDisplayFingerprintView() {
        if (mFodCircleView != null) {
            if (!mDisableNightMode) {
                for (int i = 0; i < mCallbacks.size(); i++) {
                    FODCircleViewImplCallback cb = mCallbacks.get(i).get();
                    if (cb != null) {
                        cb.onFODStatusChange(false);
                    }
                }
            }
            mIsFODVisible = false;
            if (mDisableNightMode && isNightLightEnabled()) {
                setNightMode(mNightModeActive, mAutoModeState);
            }
            mFodCircleView.hide();
        }
    }

    public int getHeight(boolean includeDecor) {
         return mFodCircleView.getHeight(includeDecor);
    }

    public void registerCallback(FODCircleViewImplCallback callback) {
        Assert.isMainThread();
        Slog.v(TAG, "*** register callback for " + callback);
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                Slog.e(TAG, "Object tried to add another callback",
                        new Exception("Called by"));
                return;
            }
        }
        mCallbacks.add(new WeakReference<>(callback));
        removeCallback(null);
        sendUpdates(callback);
    }

    public void removeCallback(FODCircleViewImplCallback callback) {
        Assert.isMainThread();
        Slog.v(TAG, "*** unregister callback for " + callback);
        mCallbacks.removeIf(el -> el.get() == callback);
    }

    private void sendUpdates(FODCircleViewImplCallback callback) {
        callback.onFODStart();
        callback.onFODStatusChange(mIsFODVisible);
    }

   private void disableNightMode() {
        ColorDisplayManager colorDisplayManager = mContext.getSystemService(ColorDisplayManager.class);
        mAutoModeState = colorDisplayManager.getNightDisplayAutoMode();
        mNightModeActive = colorDisplayManager.isNightDisplayActivated();
        colorDisplayManager.setNightDisplayActivated(false);
    }

    private void setNightMode(boolean activated, int autoMode) {
        ColorDisplayManager colorDisplayManager = mContext.getSystemService(ColorDisplayManager.class);
        colorDisplayManager.setNightDisplayAutoMode(0);
        if (autoMode == 0) {
            colorDisplayManager.setNightDisplayActivated(activated);
        } else if (autoMode == 1 || autoMode == 2) {
            colorDisplayManager.setNightDisplayAutoMode(autoMode);
        }
    }
}
