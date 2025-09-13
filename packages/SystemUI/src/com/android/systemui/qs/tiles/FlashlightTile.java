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

package com.android.systemui.qs.tiles;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tiles.dialog.FlashlightDialogDelegate;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.FlashlightController;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Quick settings tile: Control flashlight
 **/
public class FlashlightTile extends QSTileImpl<BooleanState> implements
        FlashlightController.FlashlightListener {

    public static final String TILE_SPEC = "flashlight";
    private static final String FLASHLIGHT_BRIGHTNESS_SETTING = "flashlight_brightness";
    private static final String INTERACTION_JANK_TAG = "flashlight_strength";

    private final FlashlightController mFlashlightController;

    private final Handler mHandler;
    private final Looper mBgLooper;
    private final Provider<FlashlightDialogDelegate> mFlashlightDialogProvider;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final ContentObserver mBrightnessObserver;
    private final boolean mStrengthControlSupported;

    private CameraManager mCameraManager;

    private int mDefaultLevel;
    private int mMaxLevel;
    private float mCurrentPercent;
    private int mCurrentLevel;

    @Nullable private String mCameraId; 

    @Inject
    public FlashlightTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            FlashlightController flashlightController,
            Provider<FlashlightDialogDelegate> flashlightDialogDelegateProvider,
            DialogTransitionAnimator dialogTransitionAnimator
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mHandler = mainHandler;
        mBgLooper = backgroundLooper;
        mFlashlightController = flashlightController;
        mFlashlightController.observe(getLifecycle(), this);
        mFlashlightDialogProvider = flashlightDialogDelegateProvider;
        mDialogTransitionAnimator = dialogTransitionAnimator;

        mBrightnessObserver = new ContentObserver(new Handler(mBgLooper)) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                super.onChange(selfChange, uri);
                refreshState();
            }
        };

        mStrengthControlSupported = isStrengthControlSupported();
        if (mStrengthControlSupported) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(FLASHLIGHT_BRIGHTNESS_SETTING),
                    false,
                    mBrightnessObserver
            );
            getCameraManager().registerTorchCallback(mTorchCallback, new Handler(mBgLooper));
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mStrengthControlSupported) {
            mContext.getContentResolver().unregisterContentObserver(mBrightnessObserver);
            getCameraManager().unregisterTorchCallback(mTorchCallback);
        }
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    }

    @Override
    public boolean isAvailable() {
        return mFlashlightController.hasFlashlight();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (ActivityManager.isUserAMonkey()) {
            return;
        }

        if (mStrengthControlSupported) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    SystemUIDialog dialog = mFlashlightDialogProvider.get()
                            .setCameraInfo(mCameraId, mMaxLevel, mDefaultLevel)
                            .createDialog();
                    if (expandable != null) {
                        DialogTransitionAnimator.Controller controller =
                                expandable.dialogTransitionController(
                                        new DialogCuj(
                                                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                                INTERACTION_JANK_TAG));
                        if (controller != null) {
                            mDialogTransitionAnimator.show(dialog, controller);
                        } else {
                            dialog.show();
                        }
                    } else {
                        dialog.show();
                    }
                }
            };

            mHandler.post(runnable);
        } else {
            handleSecondaryClick(expandable);
        }
    }

    @Override
    protected void handleSecondaryClick(@Nullable Expandable expandable) {
        if (ActivityManager.isUserAMonkey()) {
            return;
        }
        boolean newState = !mState.value;
        refreshState(newState);
        
        if (mStrengthControlSupported && newState) {
            try {
                int level = Math.max((int) (mCurrentPercent * mMaxLevel), 1);
                mCameraManager.turnOnTorchWithStrengthLevel(mCameraId, level);
            } catch (CameraAccessException e) {
            }
        } else {
            mFlashlightController.setFlashlight(newState);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_flashlight_label);
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        handleClick(expandable);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mHost.getContext().getString(R.string.quick_settings_flashlight_label);
        state.secondaryLabel = "";
        state.stateDescription = "";
        state.handlesSecondaryClick = mStrengthControlSupported;
        if (!mFlashlightController.isAvailable()) {
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_flashlight_camera_in_use);
            state.stateDescription = state.secondaryLabel;
            state.state = Tile.STATE_UNAVAILABLE;
            state.icon = maybeLoadResourceIcon(R.drawable.qs_flashlight_icon_off);
            return;
        }
        if (mStrengthControlSupported) {
            boolean enabled = mFlashlightController.isEnabled();
            mCurrentPercent = Settings.System.getFloatForUser(
                    mContext.getContentResolver(),
                    FLASHLIGHT_BRIGHTNESS_SETTING,
                    (float) mDefaultLevel / (float) mMaxLevel,
                    UserHandle.USER_CURRENT
            );

            mCurrentPercent = Math.max(0.01f, mCurrentPercent);
            
            if (enabled) {
                state.secondaryLabel = Math.round(mCurrentPercent * 100f) + "%";
                state.stateDescription = state.secondaryLabel;
            }
        }
        if (arg instanceof Boolean) {
            boolean value = (Boolean) arg;
            if (value == state.value) {
                return;
            }
            state.value = value;
        } else {
            state.value = mFlashlightController.isEnabled();
        }
        state.contentDescription = mContext.getString(R.string.quick_settings_flashlight_label);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = maybeLoadResourceIcon(state.value
                ? R.drawable.qs_flashlight_icon_on : R.drawable.qs_flashlight_icon_off);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_FLASHLIGHT;
    }

    @Override
    public void onFlashlightChanged(boolean enabled) {
        refreshState(enabled);
    }

    @Override
    public void onFlashlightError() {
        refreshState(false);
    }

    @Override
    public void onFlashlightAvailabilityChanged(boolean available) {
        refreshState();
    }

    private final CameraManager.TorchCallback mTorchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchStrengthLevelChanged(@NonNull String cameraId, int newStrengthLevel) {
            if (!cameraId.equals(mCameraId)) {
                return;
            }

            if (mCurrentLevel == newStrengthLevel) {
                return;
            }

            mCurrentLevel = newStrengthLevel;
            mCurrentPercent = Math.max(0.01f, ((float) mCurrentLevel) / ((float) mMaxLevel));
            Settings.System.putFloatForUser(
                    mContext.getContentResolver(),
                    FLASHLIGHT_BRIGHTNESS_SETTING,
                    mCurrentPercent,
                    UserHandle.USER_CURRENT);
            refreshState(true);
        }
    };

    private CameraManager getCameraManager() {
        if (mCameraManager == null) {
            mCameraManager = (CameraManager) mContext.getApplicationContext()
                    .getSystemService(Context.CAMERA_SERVICE);
        }
        return mCameraManager;
    }

    private String getCameraId(CameraManager cm) throws CameraAccessException {
        String[] ids = cm.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null
                    && flashAvailable
                    && lensFacing != null
                    && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private boolean isStrengthControlSupported() {
        CameraManager cm = getCameraManager();
        if (cm == null) return false;

        try {
            mCameraId = getCameraId(cm);
            if (mCameraId != null) {
                CameraCharacteristics c = cm.getCameraCharacteristics(mCameraId);
                Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mDefaultLevel = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL);
                mMaxLevel = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                if (flashAvailable && mMaxLevel > mDefaultLevel) {
                    return true;
                }
            }
        } catch (CameraAccessException e) {}
        return false;
    }
}
