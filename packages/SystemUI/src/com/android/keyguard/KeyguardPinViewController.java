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

package com.android.keyguard;

import android.os.UserHandle;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.policy.DevicePostureController;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeyguardPinViewController
        extends KeyguardPinBasedInputViewController<KeyguardPINView> {
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DevicePostureController mPostureController;
    private final DevicePostureController.Callback mPostureCallback = posture ->
            mView.onDevicePostureChanged(posture);
    private LockPatternUtils mLockPatternUtils;
    private final FeatureFlags mFeatureFlags;
    private static final int DEFAULT_PIN_LENGTH = 6;
    private static final int MIN_FAILED_PIN_ATTEMPTS = 5;
    private NumPadButton mBackspaceKey;
    private View mOkButton = mView.findViewById(R.id.key_enter);

    private long mPinLength;

    private boolean mDisabledAutoConfirmation;

    private boolean mScramblePin;

    private List<Integer> mNumbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 0);
    private final List<Integer> mDefaultNumbers = List.of(mNumbers.toArray(new Integer[0]));

    protected KeyguardPinViewController(KeyguardPINView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode, LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            LatencyTracker latencyTracker, LiftToActivateListener liftToActivateListener,
            EmergencyButtonController emergencyButtonController,
            FalsingCollector falsingCollector,
            DevicePostureController postureController,
            FeatureFlags featureFlags) {
        super(view, keyguardUpdateMonitor, securityMode, lockPatternUtils, keyguardSecurityCallback,
                messageAreaControllerFactory, latencyTracker, liftToActivateListener,
                emergencyButtonController, falsingCollector, featureFlags);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPostureController = postureController;
        mLockPatternUtils = lockPatternUtils;
        mFeatureFlags = featureFlags;
        mBackspaceKey = view.findViewById(R.id.delete_button);
        mPinLength = mLockPatternUtils.getPinLength(KeyguardUpdateMonitor.getCurrentUser());
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                getKeyguardSecurityCallback().reset();
                getKeyguardSecurityCallback().onCancelClicked();
            });
        }
        mPasswordEntry.setUserActivityListener(this::onUserInput);
        mView.onDevicePostureChanged(mPostureController.getDevicePosture());
        mPostureController.addCallback(mPostureCallback);
        if (mFeatureFlags.isEnabled(Flags.AUTO_PIN_CONFIRMATION)) {
            mPasswordEntry.setUsePinShapes(true);
            updateAutoConfirmationState();
        }
    }

    private void updatePinScrambling() {
        boolean scramblePin = LineageSettings.System.getIntForUser(getContext().getContentResolver(),
                LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT, 0,
                UserHandle.USER_CURRENT) == 1;

        if (scramblePin || scramblePin != mScramblePin) {
            mScramblePin = scramblePin;
            if (scramblePin) {
                Collections.shuffle(mNumbers);
            } else {
                mNumbers = new ArrayList<>(mDefaultNumbers);
            }

            // get all children who are NumPadKey's
            ConstraintLayout container = (ConstraintLayout) mView.findViewById(R.id.pin_container);

            List<NumPadKey> views = new ArrayList<NumPadKey>();
            for (int i = 0; i < container.getChildCount(); i++) {
                View view = container.getChildAt(i);
                if (view.getClass() == NumPadKey.class) {
                    views.add((NumPadKey) view);
                }
            }

            // reset the digits in the views
            for (int i = 0; i < mNumbers.size(); i++) {
                NumPadKey view = views.get(i);
                view.setDigit(mNumbers.get(i));
            }
        }
    }

    protected void onUserInput() {
        super.onUserInput();
        if (isAutoPinConfirmEnabledInSettings()) {
            updateAutoConfirmationState();
            if (mPasswordEntry.getText().length() == mPinLength
                    && mOkButton.getVisibility() == View.INVISIBLE) {
                verifyPasswordAndUnlock();
            }
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mPostureController.removeCallback(mPostureCallback);
    }

    @Override
    public void startAppearAnimation() {
        updatePinScrambling();
        super.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(
                mKeyguardUpdateMonitor.needsSlowUnlockTransition(), finishRunnable);
    }

    @Override
    protected void handleAttemptLockout(long elapsedRealtimeDeadline) {
        super.handleAttemptLockout(elapsedRealtimeDeadline);
        updateAutoConfirmationState();
    }

    private void updateAutoConfirmationState() {
        mDisabledAutoConfirmation = mLockPatternUtils.getCurrentFailedPasswordAttempts(
                KeyguardUpdateMonitor.getCurrentUser()) >= MIN_FAILED_PIN_ATTEMPTS;
        updateOKButtonVisibility();
        updateBackSpaceVisibility();
        updatePinHinting();
    }

    /**
     * Updates the visibility of the OK button for auto confirm feature
     */
    private void updateOKButtonVisibility() {
        if (isAutoPinConfirmEnabledInSettings() && !mDisabledAutoConfirmation) {
            mOkButton.setVisibility(View.INVISIBLE);
        } else {
            mOkButton.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Updates the visibility and the enabled state of the backspace.
     * Visibility changes are only for auto confirmation configuration.
     */
    private void updateBackSpaceVisibility() {
        boolean isAutoConfirmation = isAutoPinConfirmEnabledInSettings();
        mBackspaceKey.setTransparentMode(/* isTransparentMode= */
                isAutoConfirmation && !mDisabledAutoConfirmation);
        if (isAutoConfirmation) {
            if (mPasswordEntry.getText().length() > 0
                    || mDisabledAutoConfirmation) {
                mBackspaceKey.setVisibility(View.VISIBLE);
            } else {
                mBackspaceKey.setVisibility(View.INVISIBLE);
            }
        }
    }
    /** Updates whether to use pin hinting or not. */
    void updatePinHinting() {
        mPasswordEntry.setIsPinHinting(isAutoPinConfirmEnabledInSettings() && isPinHinting()
                && !mDisabledAutoConfirmation);
    }

    /**
     * Responsible for identifying if PIN hinting is to be enabled or not
     */
    private boolean isPinHinting() {
        return mPinLength == DEFAULT_PIN_LENGTH;
    }

    /**
     * Responsible for identifying if auto confirm is enabled or not in Settings and
     * a valid PIN_LENGTH is stored on the device (though the latter check is only to make it more
     * robust since we only allow enabling PIN confirmation if the user has a valid PIN length
     * saved on device)
     */
    private boolean isAutoPinConfirmEnabledInSettings() {
        //Checks if user has enabled the auto confirm in Settings
        return mLockPatternUtils.isAutoPinConfirmEnabled(KeyguardUpdateMonitor.getCurrentUser())
                && mPinLength != LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
    }
}
