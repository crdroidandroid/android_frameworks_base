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

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInProgressOffset;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimationDrawable;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.graphics.Rect;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;

public class UdfpsAnimation extends ImageView {

    private static final boolean DEBUG = true;
    private static final String LOG_TAG = "UdfpsAnimations";

    private boolean mShowing = false;
    private Context mContext;
    private int mAnimationSize;
    private int mAnimationOffset;
    private AnimationDrawable recognizingAnim;

    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();
    private WindowManager mWindowManager;

    private boolean mIsKeyguard;
    private boolean mEnabled;

    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;

    private int mSelectedAnim;
    private String[] mStyleNames;

    private static final String UDFPS_ANIMATIONS_PACKAGE = "com.crdroid.udfps.animations";

    private Resources mApkResources;

    public UdfpsAnimation(Context context, WindowManager windowManager,
           FingerprintSensorPropertiesInternal props) {
        super(context);
        mContext = context;

        mWindowManager = windowManager;

        mMaxBurnInOffsetX = context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);

        mAnimationSize = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_size);
        mAnimationOffset = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_offset);

        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind Udfps icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = props.getLocation().sensorLocationY - props.getLocation().sensorRadius
                - (mAnimationSize / 2) + mAnimationOffset;

        try {
            PackageManager pm = mContext.getPackageManager();
            mApkResources = pm.getResourcesForApplication(UDFPS_ANIMATIONS_PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        int res = mApkResources.getIdentifier("udfps_animation_styles",
                "array", UDFPS_ANIMATIONS_PACKAGE);
        mStyleNames = mApkResources.getStringArray(res);

        setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        Uri udfpsAnim = Settings.System.getUriFor(Settings.System.UDFPS_ANIM);
        Uri udfpsAnimStyle = Settings.System.getUriFor(Settings.System.UDFPS_ANIM_STYLE);
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (udfpsAnim.equals(uri)) {
                    mEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                            Settings.System.UDFPS_ANIM, 0, UserHandle.USER_CURRENT) == 1;
                } else if (udfpsAnimStyle.equals(uri)) {
                    mSelectedAnim = Settings.System.getIntForUser(mContext.getContentResolver(),
                            Settings.System.UDFPS_ANIM_STYLE, 0, UserHandle.USER_CURRENT);
                    mContext.getMainExecutor().execute(() -> {
                        updateAnimationStyle(mStyleNames[mSelectedAnim]);
                    });
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                udfpsAnim, false, contentObserver, UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
                udfpsAnimStyle, false, contentObserver, UserHandle.USER_CURRENT);
        contentObserver.onChange(true, udfpsAnim);
        contentObserver.onChange(true, udfpsAnimStyle);
    }

    private void updateAnimationStyle(String drawableName) {
        if (DEBUG) Log.i(LOG_TAG, "Updating animation style to:" + drawableName);
        int resId = mApkResources.getIdentifier(drawableName, "drawable", UDFPS_ANIMATIONS_PACKAGE);
        if (DEBUG) Log.i(LOG_TAG, "Got resource id: "+ resId +" from package" );
        setBackgroundDrawable(mApkResources.getDrawable(resId));
        recognizingAnim = (AnimationDrawable) getBackground();
    }

    public void updatePosition(FingerprintSensorPropertiesInternal props) {
        mAnimParams.y = props.getLocation().sensorLocationY - props.getLocation().sensorRadius
                - (mAnimationSize / 2) + mAnimationOffset;
        // Update view if it's showing already.
        if (mShowing) {
            showAnimation();
        }
    }

    public void show() {
        if (!mShowing && mIsKeyguard && mEnabled) {
            mShowing = true;
            showAnimation();
        }
    }

    private void showAnimation() {
        try {
            if (getWindowToken() == null) {
                mWindowManager.addView(this, mAnimParams);
            } else {
                mWindowManager.updateViewLayout(this, mAnimParams);
            }
        } catch (RuntimeException e) {
            // Ignore
        }
        if (recognizingAnim != null) {
            recognizingAnim.start();
        }
    }

    public void hide() {
        if (mShowing) {
            mShowing = false;
            if (recognizingAnim != null) {
                clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (getWindowToken() != null) {
                mWindowManager.removeView(this);
            }
        }
    }

    public void setIsKeyguard(boolean isKeyguard) {
        mIsKeyguard = isKeyguard;
    }

    public void dozeTimeTick() {
        float amt = Dependency.get(StatusBarStateController.class).getDozeAmount();

        float mBurnInOffsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                - mMaxBurnInOffsetX, amt);
        float mBurnInOffsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                - mMaxBurnInOffsetY, amt);
        setTranslationX(mBurnInOffsetX);
        setTranslationY(mBurnInOffsetY);
    }
}
