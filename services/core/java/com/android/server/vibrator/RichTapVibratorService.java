/*
 * Copyright (C) 2023 Paranoid Android
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

package com.android.server.vibrator;

import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;
import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;

public class RichTapVibratorService {

    private static final String TAG = "RichTapVibratorService";
    private static final String VIBRATOR_DESCRIPTOR = "android.hardware.vibrator.IVibrator/default";

    private IRichtapCallback mCallback;
    private volatile IRichtapVibrator sRichtapVibratorService = null;

    private IRichtapVibrator getRichtapService() {
        synchronized (RichTapVibratorService.class) {
            if (sRichtapVibratorService == null) {
                Slog.d(TAG, "vibratorDescriptor:" + VIBRATOR_DESCRIPTOR);
                IVibrator vibratorHalService = IVibrator.Stub.asInterface(ServiceManager.getService(VIBRATOR_DESCRIPTOR));
                if (vibratorHalService == null) {
                    Slog.d(TAG, "can not get hal service");
                    return null;
                }
                Slog.d(TAG, "vibratorHalService:" + vibratorHalService);
                try {
                    Slog.d(TAG, "Capabilities:" + vibratorHalService.getCapabilities());
                } catch (Exception e) {
                    Slog.d(TAG, "getCapabilities failed", e);
                }
                try {
                    IBinder binder = vibratorHalService.asBinder().getExtension();
                    if (binder != null) {
                        sRichtapVibratorService = IRichtapVibrator.Stub.asInterface(Binder.allowBlocking(binder));
                        binder.linkToDeath(new VibHalDeathRecipient(this), 0);
                    } else {
                        sRichtapVibratorService = null;
                        Slog.e(TAG, "getExtension == null");
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "getExtension fail", e);
                }
            }
            return sRichtapVibratorService;
        }
    }

    public RichTapVibratorService() { }

    public RichTapVibratorService(IRichtapCallback callback) {
        mCallback = callback;
    }

    public void richTapVibratorOn(long millis) {
        try {
            IRichtapVibrator service = getRichtapService();
            if (service != null) {
                Slog.d(TAG, "aac richtap doVibratorOn");
                service.on((int) millis, mCallback);
            }
        } catch (Exception e) {
            Slog.e(TAG, "aac richtap doVibratorOn fail.", e);
        }
    }

    public void richTapVibratorSetAmplitude(int amplitude) {
        try {
            IRichtapVibrator service = getRichtapService();
            if (service != null) {
                Slog.d(TAG, "aac richtap doVibratorSetAmplitude");
                service.setAmplitude(amplitude, mCallback);
            }
        } catch (Exception e) {
            Slog.e(TAG, "aac richtap doVibratorSetAmplitude fail.", e);
        }
    }

    public void richTapVibratorOnRawPattern(int[] pattern, int amplitude, int freq) {
        try {
            IRichtapVibrator service = getRichtapService();
            if (service != null) {
                Slog.d(TAG, "aac richtap doVibratorOnRawPattern");
                service.performHe(1, 0, amplitude, freq, pattern, mCallback);
            }
        } catch (Exception e) {
            Slog.e(TAG, "aac richtap doVibratorOnRawPattern fail.", e);
        }
    }

    void resetHalServiceProxy() {
        sRichtapVibratorService = null;
    }

    public static final class VibHalDeathRecipient implements IBinder.DeathRecipient {
        RichTapVibratorService mRichTapService;

        VibHalDeathRecipient(RichTapVibratorService richtapService) {
            mRichTapService = richtapService;
        }

        @Override
        public void binderDied() {
            Slog.d(TAG, "vibrator hal died,should reset hal proxy!!");
            synchronized (VibHalDeathRecipient.class) {
                if (mRichTapService != null) {
                    Slog.d(TAG, "vibrator hal reset hal proxy");
                    mRichTapService.resetHalServiceProxy();
                }
            }
        }
    }
}
