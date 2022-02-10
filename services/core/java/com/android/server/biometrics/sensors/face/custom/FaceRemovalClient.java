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
import android.hardware.face.Face;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.RemovalClient;

import java.util.Map;

class FaceRemovalClient extends RemovalClient<Face, IFaceService> {
    private static final String TAG = "FaceRemovalClient";
    private final int mBiometricId;

    FaceRemovalClient(Context context, HalClientMonitor.LazyDaemon<IFaceService> lazyDaemon, IBinder token, ClientMonitorCallbackConverter listener, int biometricId, int userId, String owner, BiometricUtils<Face> utils, int sensorId, Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, token, listener, userId, owner, utils, sensorId, authenticatorIds, 4);
        mBiometricId = biometricId;
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().remove(mBiometricId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting remove", e);
            mCallback.onClientFinished(this, false);
        }
    }
}
