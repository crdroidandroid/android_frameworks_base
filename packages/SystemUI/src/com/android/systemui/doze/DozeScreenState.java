/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;

import android.hardware.biometrics.BiometricAuthenticator;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;

import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.UdfpsController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.doze.dagger.WrappedService;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.keyguard.domain.interactor.DozeInteractor;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.ScreenAnimationController;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.util.settings.SystemSettings;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Controls the screen when dozing.
 */
@DozeScope
public class DozeScreenState implements DozeMachine.Part {

    private static final boolean DEBUG = DozeService.DEBUG;
    private static final String TAG = "DozeScreenState";

    /**
     * Delay entering low power mode when animating to make sure that we'll have
     * time to move all elements into their final positions while still at 60 fps.
     */
    private static final int ENTER_DOZE_DELAY = 4900;
    /**
     * Hide wallpaper earlier when entering low power mode. The gap between
     * hiding the wallpaper and changing the display mode is necessary to hide
     * the black frame that's inherent to hardware specs.
     */
    public static final int ENTER_DOZE_HIDE_WALLPAPER_DELAY = 2500;
    
    private static final int ENTER_SCREEN_OFF_WITH_ANIMATION_DELAY = 4900;
    private static final int ENTER_SCREEN_OFF_WITH_ANIMATION_DELAY_NO_UDFPS = 500;
    private static final int ENTER_DOZE_DELAY_BY_QS_EXPANDED_SCREEN_OFF = 700;
    private static final int ENTER_DOZE_DELAY_BY_LANDSCAPE_SCREEN_OFF = 1500;

    /**
     * Add an extra delay to the transition to DOZE when udfps is current activated before
     * the display state transitions from ON => DOZE.
     */
    public static final int UDFPS_DISPLAY_STATE_DELAY = 1200;

    private final DozeMachine.Service mDozeService;
    private final Handler mHandler;
    private final Runnable mApplyPendingScreenState = this::applyPendingScreenState;
    private final DozeParameters mParameters;
    private final DozeHost mDozeHost;
    private final AuthController mAuthController;
    private final Provider<UdfpsController> mUdfpsControllerProvider;
    @Nullable private UdfpsController mUdfpsController;
    private final DozeLog mDozeLog;
    private final DozeScreenBrightness mDozeScreenBrightness;
    private final SelectedUserInteractor mSelectedUserInteractor;
    private final DozeInteractor mDozeInteractor;
    private final SystemSettings mSystemSettings;

    private int mPendingScreenState = Display.STATE_UNKNOWN;
    private SettableWakeLock mWakeLock;
    private boolean mIsLandscapeScreenOff = false;

    @Inject
    public DozeScreenState(
            @WrappedService DozeMachine.Service service,
            @Main Handler handler,
            DozeHost host,
            DozeParameters parameters,
            WakeLock wakeLock,
            AuthController authController,
            Provider<UdfpsController> udfpsControllerProvider,
            DozeLog dozeLog,
            DozeScreenBrightness dozeScreenBrightness,
            DozeInteractor dozeInteractor,
            SelectedUserInteractor selectedUserInteractor,
            SystemSettings systemSettings) {
        mDozeService = service;
        mHandler = handler;
        mParameters = parameters;
        mDozeHost = host;
        mWakeLock = new SettableWakeLock(wakeLock, TAG);
        mAuthController = authController;
        mUdfpsControllerProvider = udfpsControllerProvider;
        mDozeLog = dozeLog;
        mDozeScreenBrightness = dozeScreenBrightness;
        mSelectedUserInteractor = selectedUserInteractor;
        mDozeInteractor = dozeInteractor;
        mSystemSettings = systemSettings;

        updateUdfpsController();
        if (mUdfpsController == null) {
            mAuthController.addCallback(mAuthControllerCallback);
        }
    }

    private void updateUdfpsController() {
        if (mAuthController.isUdfpsEnrolled(mSelectedUserInteractor.getSelectedUserId())) {
            mUdfpsController = mUdfpsControllerProvider.get();
        } else {
            mUdfpsController = null;
        }
    }

    @Override
    public void destroy() {
        mAuthController.removeCallback(mAuthControllerCallback);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        int screenState = newState.screenState(mParameters);
        mDozeHost.cancelGentleSleep();
        
        if (newState == DozeMachine.State.INITIALIZED) {
            ScreenAnimationController.INSTANCE().setAnimationPlaying(true);
        }

        if (newState == DozeMachine.State.FINISH) {
            // Make sure not to apply the screen state after DozeService was destroyed.
            mPendingScreenState = Display.STATE_UNKNOWN;
            mHandler.removeCallbacks(mApplyPendingScreenState);

            applyScreenState(screenState);
            mWakeLock.setAcquired(false);
            return;
        }

        if (screenState == Display.STATE_UNKNOWN) {
            // We'll keep it in the existing state
            return;
        }

        final boolean messagePending = mHandler.hasCallbacks(mApplyPendingScreenState);
        final boolean pulseEnding = oldState == DOZE_PULSE_DONE && newState.isAlwaysOn();
        final boolean turningOn = (oldState == DOZE_AOD_PAUSED || oldState == DOZE)
                && newState.isAlwaysOn();
        final boolean turningOff = (oldState.isAlwaysOn() && newState == DOZE)
                || (oldState == DOZE_AOD_PAUSING && newState == DOZE_AOD_PAUSED);
        final boolean justInitialized = oldState == DozeMachine.State.INITIALIZED;
        if (justInitialized) {
            mIsLandscapeScreenOff = false;
        }
        if (!messagePending && !justInitialized && !pulseEnding && !turningOn) {
            if (turningOff) {
                mDozeHost.prepareForGentleSleep(() -> applyScreenState(screenState));
                return;
            } else {
                applyScreenState(screenState);
                return;
            }
        }
        // a traversal; setting the screen state here is immediate however, so it can happen
        // that the screen turns on again before the navigation bar is hidden. To work around
        // that, wait for a traversal to happen before applying the initial screen state.
        mPendingScreenState = screenState;

        // Delay screen state transitions even longer while animations are running.
        boolean shouldDelayTransitionEnteringDoze = newState == DOZE_AOD
                && mParameters.shouldDelayDisplayDozeTransition() && !turningOn;

        // Delay screen state transition longer if UDFPS is actively authenticating a fp
        boolean shouldDelayTransitionForUDFPS = newState == DOZE_AOD
                && mUdfpsController != null && mUdfpsController.isFingerDown();

        boolean shouldAnimate = (newState == DozeMachine.State.DOZE || 
                    (newState == DozeMachine.State.DOZE_AOD && oldState != DozeMachine.State.DOZE)) && 
                    ScreenAnimationController.INSTANCE().shouldPlayAnimation();
        if (justInitialized) {
            mIsLandscapeScreenOff = ScreenAnimationController.INSTANCE().isLandscapeScreenOff();
        }
        boolean isPanelExpandedWhenScreenOff = ScreenAnimationController.INSTANCE().isPanelExpandedWhenScreenOff();

        if (!messagePending) {
            if (DEBUG) {
                Log.d(TAG, "Display state changed to " + screenState + " delayed by "
                        + (shouldDelayTransitionEnteringDoze ? ENTER_DOZE_DELAY : 1));
            }

            if (shouldDelayTransitionEnteringDoze) {
                if (!ScreenAnimationController.INSTANCE().isUnlockAnimPlaying()) {
                    if (justInitialized) {
                        applyScreenState(Display.STATE_ON);
                        mPendingScreenState = screenState;
                    }
                    Log.d(TAG, "applyPendingScreenState when unlock anim is not playing: PendingState: " + mPendingScreenState);
                    mHandler.postDelayed(mApplyPendingScreenState, 4900);
                } else if (justInitialized) {
                    applyScreenState(Display.STATE_ON);
                }
            } else if (shouldDelayTransitionForUDFPS) {
                mDozeLog.traceDisplayStateDelayedByUdfps(mPendingScreenState);
                mHandler.postDelayed(mApplyPendingScreenState, UDFPS_DISPLAY_STATE_DELAY);
            } else if (turningOn) {
                mHandler.post(mApplyPendingScreenState);
            } else if (shouldAnimate && !mIsLandscapeScreenOff && !isPanelExpandedWhenScreenOff) {
                if (justInitialized) {
                    Log.d(TAG, "apply state on when screen state change");
                    applyScreenState(Display.STATE_ON);
                    mPendingScreenState = screenState;
                }
                boolean showAodOnScreenOff = mSystemSettings.getIntForUser(
                        "screen_off_aod_enabled", 0, android.os.UserHandle.USER_CURRENT) == 1;
                boolean isUdfps = mAuthController.isUdfpsEnrolled(
                    mSelectedUserInteractor.getSelectedUserId());
                long delay = showAodOnScreenOff ? ENTER_SCREEN_OFF_WITH_ANIMATION_DELAY : ENTER_SCREEN_OFF_WITH_ANIMATION_DELAY_NO_UDFPS;
                mHandler.postDelayed(mApplyPendingScreenState, delay);
            } else if (mIsLandscapeScreenOff) {
                mDozeService.setDozeScreenState(Display.STATE_OFF);
                mHandler.postDelayed(mApplyPendingScreenState, ENTER_DOZE_DELAY_BY_LANDSCAPE_SCREEN_OFF);
                mIsLandscapeScreenOff = false;
            } else if (isPanelExpandedWhenScreenOff) {
                mDozeService.setDozeScreenState(Display.STATE_OFF);
                mHandler.postDelayed(mApplyPendingScreenState, ENTER_DOZE_DELAY_BY_QS_EXPANDED_SCREEN_OFF);
            } else {
                mHandler.post(mApplyPendingScreenState);
            }
        } else if (DEBUG) {
            Log.d(TAG, "Pending display state change to " + screenState);
        }
        if (shouldDelayTransitionEnteringDoze || shouldDelayTransitionForUDFPS || mIsLandscapeScreenOff || shouldAnimate) {
            mWakeLock.setAcquired(true);
        }
    }

    private void applyPendingScreenState() {
        if (mUdfpsController != null && mUdfpsController.isFingerDown()) {
            mDozeLog.traceDisplayStateDelayedByUdfps(mPendingScreenState);
            mHandler.postDelayed(mApplyPendingScreenState, UDFPS_DISPLAY_STATE_DELAY);
            return;
        }

        applyScreenState(mPendingScreenState);
        mPendingScreenState = Display.STATE_UNKNOWN;
    }

    private void applyScreenState(int screenState) {
        if (screenState != Display.STATE_UNKNOWN) {
            if (screenState == Display.STATE_DOZE_SUSPEND 
                && ScreenAnimationController.INSTANCE().getCurDisplayState() != Display.STATE_DOZE) {
                mDozeService.setDozeScreenState(Display.STATE_DOZE);
                mHandler.postDelayed(mApplyPendingScreenState, 3000);
                return;
            }
            mDozeService.setDozeScreenState(screenState);
            mDozeInteractor.setDozeScreenState(screenState);
            if (screenState == Display.STATE_DOZE) {
                mDozeScreenBrightness.updateBrightnessAndReady(false);
            }
            mPendingScreenState = Display.STATE_UNKNOWN;
            mWakeLock.setAcquired(false);
        }
    }

    private final AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onAllAuthenticatorsRegistered(@BiometricAuthenticator.Modality int modality) {
            if (modality == TYPE_FINGERPRINT) {
                updateUdfpsController();
            }
        }

        @Override
        public void onEnrollmentsChanged(@BiometricAuthenticator.Modality int modality) {
            if (modality == TYPE_FINGERPRINT) {
                updateUdfpsController();
            }
        }
    };
}
