/*
* Copyright (C) 2022 crDroid Android Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.HalClientMonitor;

class FaceResetLockoutClient extends HalClientMonitor<IFaceService> {
    private static final String TAG = "FaceResetLockoutClient";
    private final byte[] mHardwareAuthToken;

    FaceResetLockoutClient(Context context, HalClientMonitor.LazyDaemon<IFaceService> lazyDaemon, int userId, String owner, int sensorId, byte[] hardwareAuthToken) {
        super(context, lazyDaemon, null, null, userId, owner, 0, sensorId, 0, 0, 0);
        mHardwareAuthToken = hardwareAuthToken.clone();
    }

    @Override
    public void unableToStart() {
    }

    @Override
    public void start(BaseClientMonitor.Callback callback) {
        super.start(callback);
        startHalOperation();
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().resetLockout(mHardwareAuthToken);
            mCallback.onClientFinished(this, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to reset lockout", e);
            mCallback.onClientFinished(this, false);
        }
    }

    @Override
    public int getProtoEnum() {
        return 12;
    }
}
