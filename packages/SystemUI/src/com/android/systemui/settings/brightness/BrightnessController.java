/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings.brightness;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.MathUtils;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.concurrent.Executor;

public class BrightnessController implements ToggleSlider.Listener, MirroredBrightnessController,
        TunerService.Tunable {

    private static final String TAG = "CentralSurfaces.BrightnessController";
    private static final int SLIDER_ANIMATION_DURATION = 1000;

    private static final int MSG_UPDATE_ICON = 0;
    private static final int MSG_UPDATE_SLIDER = 1;
    private static final int MSG_ATTACH_LISTENER = 2;
    private static final int MSG_DETACH_LISTENER = 3;
    private static final int MSG_VR_MODE_CHANGED = 4;

    private static final String SCREEN_BRIGHTNESS_MODE =
            "system:" + Settings.System.SCREEN_BRIGHTNESS_MODE;
    private static final String QS_BRIGHTNESS_SLIDER_HAPTIC =
            "system:" + Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC;

    private final ImageView mIcon;
    private final int mDisplayId;
    private final Context mContext;
    private final ToggleSlider mControl;
    private final DisplayManager mDisplayManager;
    private final UserTracker mUserTracker;
    private final DisplayTracker mDisplayTracker;
    @Nullable
    private final IVrManager mVrManager;

    private final Executor mMainExecutor;
    private final Handler mBackgroundHandler;

    private final DisplayTracker.Callback mBrightnessListener = new DisplayTracker.Callback() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (mUserChangedBrightness) return;
            mBackgroundHandler.post(mUpdateSliderRunnable);
        }
    };

    private volatile boolean mAutomatic;  // Brightness adjusted automatically using ambient light.
    private volatile boolean mIsVrModeEnabled;
    private boolean mListening;
    private boolean mExternalChange;
    private boolean mControlValueInitialized;
    private float mBrightnessMin = PowerManager.BRIGHTNESS_MIN;
    private float mBrightnessMax = PowerManager.BRIGHTNESS_MAX;

    private ValueAnimator mSliderAnimator;
    private boolean mUserChangedBrightness;

    private Vibrator mVibrator;
    private static final VibrationEffect BRIGHTNESS_SLIDER_HAPTIC =
            VibrationEffect.get(VibrationEffect.EFFECT_TICK);
    private boolean mBrightnessSliderHaptic;
    private final TunerService mTunerService;

    @Override
    public void setMirror(BrightnessMirrorController controller) {
        mControl.setMirrorControllerAndMirror(controller);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case SCREEN_BRIGHTNESS_MODE:
                if (mListening) {
                    mBackgroundHandler.post(mUpdateModeRunnable);
                    mBackgroundHandler.post(mUpdateSliderRunnable);
                }
                break;
            case QS_BRIGHTNESS_SLIDER_HAPTIC:
                mBrightnessSliderHaptic =
                        TunerService.parseIntegerSwitch(newValue, false);
                break;
            default:
                break;
         }
    }

    private final Runnable mStartListeningRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListening) {
                return;
            }
            mListening = true;

            if (mVrManager != null) {
                try {
                    mVrManager.registerListener(mVrStateCallbacks);
                    mIsVrModeEnabled = mVrManager.getVrModeState();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register VR mode state listener: ", e);
                }
            }

            mDisplayTracker.addBrightnessChangeCallback(mBrightnessListener,
                    new HandlerExecutor(mMainHandler));
            mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);

            // Update the slider and mode before attaching the listener so we don't
            // receive the onChanged notifications for the initial values.
            mUpdateModeRunnable.run();
            mUpdateSliderRunnable.run();

            mMainHandler.sendEmptyMessage(MSG_ATTACH_LISTENER);
        }
    };

    private final Runnable mStopListeningRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mListening) {
                return;
            }
            mListening = false;

            if (mVrManager != null) {
                try {
                    mVrManager.unregisterListener(mVrStateCallbacks);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to unregister VR mode state listener: ", e);
                }
            }

            mDisplayTracker.removeCallback(mBrightnessListener);
            mUserTracker.removeCallback(mUserChangedCallback);

            mMainHandler.sendEmptyMessage(MSG_DETACH_LISTENER);
        }
    };

    /**
     * Fetch the brightness mode from the system settings and update the icon. Should be called from
     * background thread.
     */
    private final Runnable mUpdateModeRunnable = new Runnable() {
        @Override
        public void run() {
            int automatic;
            automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    mUserTracker.getUserId());
            mAutomatic = automatic != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            mMainHandler.obtainMessage(MSG_UPDATE_ICON, mAutomatic ? 1 : 0).sendToTarget();
        }
    };

    /**
     * Fetch the brightness from the system settings and update the slider. Should be called from
     * background thread.
     */
    private final Runnable mUpdateSliderRunnable = new Runnable() {
        @Override
        public void run() {
            final boolean inVrMode = mIsVrModeEnabled;
            final BrightnessInfo info = mContext.getDisplay().getBrightnessInfo();
            if (info == null) {
                return;
            }
            mBrightnessMax = info.brightnessMaximum;
            mBrightnessMin = info.brightnessMinimum;
            // Value is passed as intbits, since this is what the message takes.
            final int valueAsIntBits = Float.floatToIntBits(info.brightness);
            mMainHandler.obtainMessage(MSG_UPDATE_SLIDER, valueAsIntBits,
                    inVrMode ? 1 : 0).sendToTarget();
        }
    };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mMainHandler.obtainMessage(MSG_VR_MODE_CHANGED, enabled ? 1 : 0, 0)
                    .sendToTarget();
        }
    };

    private final Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            mExternalChange = true;
            try {
                switch (msg.what) {
                    case MSG_UPDATE_ICON:
                        updateIcon(msg.arg1 != 0);
                        break;
                    case MSG_UPDATE_SLIDER:
                        updateSlider(Float.intBitsToFloat(msg.arg1), msg.arg2 != 0);
                        break;
                    case MSG_ATTACH_LISTENER:
                        mControl.setOnChangedListener(BrightnessController.this);
                        break;
                    case MSG_DETACH_LISTENER:
                        mControl.setOnChangedListener(null);
                        break;
                    case MSG_VR_MODE_CHANGED:
                        updateVrMode(msg.arg1 != 0);
                        break;
                    default:
                        return false;

                }
            } finally {
                mExternalChange = false;
            }
            return true;
        }
    };

    private final Handler mMainHandler;

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mBackgroundHandler.post(mUpdateModeRunnable);
                    mBackgroundHandler.post(mUpdateSliderRunnable);
                }
            };

    @AssistedInject
    public BrightnessController(
            Context context,
            @Assisted ToggleSlider control,
            UserTracker userTracker,
            DisplayTracker displayTracker,
            DisplayManager displayManager,
            TunerService tunerService,
            @Nullable IVrManager iVrManager,
            @Main Executor mainExecutor,
            @Main Looper mainLooper,
            @Background Handler bgHandler) {
        mContext = context;
        mControl = control;
        mControl.setMax(GAMMA_SPACE_MAX);
        mMainExecutor = mainExecutor;
        mBackgroundHandler = bgHandler;
        mUserTracker = userTracker;
        mDisplayTracker = displayTracker;
        mDisplayId = mContext.getDisplayId();
        mDisplayManager = displayManager;
        mVrManager = iVrManager;
        mTunerService = tunerService;

        mMainHandler = new Handler(mainLooper, mHandlerCallback);

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

        mIcon = control.getIcon();
        mIcon.setOnClickListener(v -> Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mAutomatic ?
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL :
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                UserHandle.USER_CURRENT));
    }

    public void registerCallbacks() {
        mBackgroundHandler.removeCallbacks(mStartListeningRunnable);
        mBackgroundHandler.post(mStartListeningRunnable);
    }

    /** Unregister all call backs, both to and from the controller */
    public void unregisterCallbacks() {
        mBackgroundHandler.removeCallbacks(mStopListeningRunnable);
        mBackgroundHandler.post(mStopListeningRunnable);
        mControlValueInitialized = false;
    }

    public void addListeners() {
        mTunerService.addTunable(this, SCREEN_BRIGHTNESS_MODE);
        mTunerService.addTunable(this, QS_BRIGHTNESS_SLIDER_HAPTIC);
    }
    
    public void removeListeners() {
        mBackgroundHandler.removeCallbacksAndMessages(null);
        mMainHandler.removeCallbacksAndMessages(null);
        mTunerService.removeTunable(this);
    }

    @Override
    public void onChanged(boolean tracking, int value, boolean stopTracking) {
        updateIcon(mAutomatic);
        if (mExternalChange) return;

        if (mSliderAnimator != null) {
            mSliderAnimator.cancel();
        }

        final float minBacklight;
        final float maxBacklight;
        final int metric;

        metric = mAutomatic
                ? MetricsEvent.ACTION_BRIGHTNESS_AUTO
                : MetricsEvent.ACTION_BRIGHTNESS;
        minBacklight = mBrightnessMin;
        maxBacklight = mBrightnessMax;
        final float valFloat = MathUtils.min(
                convertGammaToLinearFloat(value, minBacklight, maxBacklight),
                maxBacklight);
        if (stopTracking) {
            // TODO(brightnessfloat): change to use float value instead.
            MetricsLogger.action(mContext, metric,
                    BrightnessSynchronizer.brightnessFloatToInt(valFloat));

        }
        mUserChangedBrightness = tracking && !stopTracking;
        setBrightness(valFloat);

        // Give haptic feedback only if brightness is changed manually
        if (mBrightnessSliderHaptic && mVibrator != null && tracking)
            mVibrator.vibrate(BRIGHTNESS_SLIDER_HAPTIC);

        if (!tracking) {
            AsyncTask.execute(new Runnable() {
                    public void run() {
                        mDisplayManager.setBrightness(mDisplayId, valFloat);
                    }
                });
        }
    }

    public void checkRestrictionAndSetEnabled() {
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                mControl.setEnforcedAdmin(
                        RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                                UserManager.DISALLOW_CONFIG_BRIGHTNESS,
                                mUserTracker.getUserId()));
            }
        });
    }

    public void hideSlider() {
        mControl.hideView();
    }

    public void showSlider() {
        mControl.showView();
    }

    private void setBrightness(float brightness) {
        mDisplayManager.setTemporaryBrightness(mDisplayId, brightness);
    }

    private void updateIcon(boolean automatic) {
        if (mIcon != null) {
            mIcon.setImageResource(mAutomatic ?
                    R.drawable.ic_qs_brightness_auto_on_new :
                    R.drawable.ic_qs_brightness_auto_off_new);
            mIcon.setBackgroundResource(mAutomatic ?
                    R.drawable.bg_qs_brightness_auto_on :
                    R.drawable.bg_qs_brightness_auto_off);
        }
    }

    private void updateVrMode(boolean isEnabled) {
        if (mIsVrModeEnabled != isEnabled) {
            mIsVrModeEnabled = isEnabled;
            mBackgroundHandler.post(mUpdateSliderRunnable);
        }
    }

    private void updateSlider(float brightnessValue, boolean inVrMode) {
        final float min = mBrightnessMin;
        final float max = mBrightnessMax;

        // Ensure the slider is in a fixed position first, then check if we should animate.
        if (mSliderAnimator != null && mSliderAnimator.isStarted()) {
            mSliderAnimator.cancel();
        }
        // convertGammaToLinearFloat returns 0-1
        if (BrightnessSynchronizer.floatEquals(brightnessValue,
                convertGammaToLinearFloat(mControl.getValue(), min, max))) {
            // If the value in the slider is equal to the value on the current brightness
            // then the slider does not need to animate, since the brightness will not change.
            return;
        }
        // Returns GAMMA_SPACE_MIN - GAMMA_SPACE_MAX
        final int sliderVal = convertLinearToGammaFloat(brightnessValue, min, max);
        animateSliderTo(sliderVal);
    }

    private void animateSliderTo(int target) {
        if (!mControlValueInitialized || !mControl.isVisible()) {
            // Don't animate the first value since its default state isn't meaningful to users.
            // We also don't want to animate slider if it's not visible - especially important when
            // two sliders are active at the same time in split shade (one in QS and one in QQS),
            // as this negatively affects transition between them and they share mirror slider -
            // animating it from two different sources causes janky motion
            mControl.setValue(target);
            mControlValueInitialized = true;
        }
        mSliderAnimator = ValueAnimator.ofInt(mControl.getValue(), target);
        mSliderAnimator.addUpdateListener((ValueAnimator animation) -> {
            mExternalChange = true;
            mControl.setValue((int) animation.getAnimatedValue());
            mExternalChange = false;
        });
        final long animationDuration = SLIDER_ANIMATION_DURATION * Math.abs(
                mControl.getValue() - target) / GAMMA_SPACE_MAX;
        mSliderAnimator.setDuration(animationDuration);
        mSliderAnimator.start();
    }



    /** Factory for creating a {@link BrightnessController}. */
    @AssistedFactory
    public interface Factory {
        /** Create a {@link BrightnessController} */
        BrightnessController create(ToggleSlider toggleSlider);
    }
}
