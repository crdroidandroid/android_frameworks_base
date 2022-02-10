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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.HalClientMonitor;
import com.android.server.biometrics.sensors.RevokeChallengeClient;

class FaceRevokeChallengeClient extends RevokeChallengeClient<IFaceService> {
    private static final String TAG = "FaceRevokeChallengeClient";

    FaceRevokeChallengeClient(Context context, HalClientMonitor.LazyDaemon<IFaceService> lazyDaemon, IBinder token, int userId, String owner, int sensorId) {
        super(context, lazyDaemon, token, userId, owner, sensorId);
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().revokeChallenge();
            mCallback.onClientFinished(this, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "revokeChallenge failed", e);
            mCallback.onClientFinished(this, false);
        }
    }
}
