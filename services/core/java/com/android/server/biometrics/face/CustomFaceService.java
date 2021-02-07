/*
 * Copyright (C) 2021 PixelExperience
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

package com.android.server.biometrics.face;

import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_SUCCESS;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_ERROR_TIMEOUT;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.face.Face;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.biometrics.AuthenticationClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.internal.util.custom.faceunlock.IFaceServiceReceiver;
import com.android.internal.util.custom.faceunlock.FaceUnlockUtils;

/**
 * @hide
 */
public class CustomFaceService {

    protected static final String TAG = "CustomFaceService";

    public static final int HAL_DEVICE_ID = 1008;

    private Context mContext;
    private FaceService mFaceService;
    private Handler mHandler;

    private int mCurrentUserId;

    private Handler mServiceHandler;
    private boolean mIsServiceBinding = false;
    private final BroadcastReceiver mUserUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FaceUnlockUtils.isFaceUnlockSupported()) {
                if (getService(mCurrentUserId) == null) {
                    bind(mCurrentUserId);
                }
            }
        }
    };

    final SparseArray<IFaceService> mServices = new SparseArray<>();
    final IFaceServiceReceiver mReceiver = new IFaceServiceReceiver.Stub() {
        @Override
        public void onEnrollResult(int faceId, int userId, int remaining) {
            mHandler.post(() -> {
                mFaceService.handleEnrollResult(new Face(
                        mFaceService.getBiometricUtils().getUniqueName(
                        mContext, userId), faceId, HAL_DEVICE_ID), remaining);
            });
        }

        @Override
        public void onAuthenticated(int faceId, int userId, byte[] token) {
            mHandler.post(() -> {
                final boolean authenticated = faceId != 0;
                if (token == null || faceId <= 0) {
                    if (token == null && faceId > 0) {
                        Slog.e("FaceService", "token should not be null for authentication success");
                    }
                    Slog.w("FaceService", "onAuthenticated failure");
                    mFaceService.handleAuthenticated(authenticated, new Face("", 0, HAL_DEVICE_ID), null);
                    return;
                }
                Face face = new Face("", faceId, HAL_DEVICE_ID);
                ArrayList<Byte> token_AL = new ArrayList<>(token.length);
                for (byte b : token) {
                    token_AL.add(new Byte(b));
                }
                mFaceService.handleAuthenticated(authenticated, face, token_AL);
            });
        }

        @Override
        public void onAcquired(int userId, int acquiredInfo, int vendorCode) {
            mHandler.post(() -> {
                mFaceService.handleAcquired(HAL_DEVICE_ID, acquiredInfo, vendorCode);
            });
        }

        @Override
        public void onError(int error, int vendorCode) {
            mHandler.post(() -> {
                mFaceService.handleError(HAL_DEVICE_ID, error, vendorCode);
            });
        }

        @Override
        public void onRemoved(int[] faceIds, int userId) throws RemoteException {
            mHandler.post(() -> {
                if (faceIds.length > 0) {
                    for (int i = 0; i < faceIds.length; i++) {
                        mFaceService.handleRemoved(new Face("", faceIds[i], HAL_DEVICE_ID), (faceIds.length - i) - 1);
                    }
                    return;
                }
                mFaceService.handleRemoved(new Face("", 0, HAL_DEVICE_ID), 0);
            });
        }

        @Override
        public void onEnumerate(int[] faceIds, int userId) throws RemoteException {
            mHandler.post(() -> {
                if (faceIds.length > 0) {
                    for (int i = 0; i < faceIds.length; i++) {
                        mFaceService.handleEnumerate(new Face("", faceIds[i], HAL_DEVICE_ID), (faceIds.length - i) - 1);
                    }
                    return;
                }
                mFaceService.handleEnumerate(null, 0);
            });
        }

        @Override
        public void onLockoutChanged(long duration) throws RemoteException {
            if (duration == 0) {
                mFaceService.mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_NONE;
            } else if (duration == Long.MAX_VALUE) {
                mFaceService.mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_PERMANENT;
            } else {
                mFaceService.mCurrentUserLockoutMode = AuthenticationClient.LOCKOUT_TIMED;
            }
            mHandler.post(() -> {
                if (duration == 0) {
                    mFaceService.notifyLockoutResetMonitors();
                }
            });
        }
    };

    public CustomFaceService(Context context, FaceService service, Handler handler) {
        mContext = context;
        mFaceService = service;
        mHandler = handler;
        mContext.registerReceiver(mUserUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
    }

    public int authenticate(long operationId) {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                service.authenticate(operationId);
            } catch (RemoteException e) {
                Slog.e(TAG, "authenticate failed", e);
            }
            return BIOMETRIC_SUCCESS;
        }
        bind(mCurrentUserId);
        Slog.w(TAG, "authenticate(): Face service not started!");
        return BIOMETRIC_ERROR_TIMEOUT;
    }

    public int cancel() {
        IFaceService service = getService(mCurrentUserId);
        if (service == null) {
            return BIOMETRIC_SUCCESS;
        }

        try {
            service.cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "cancel failed", e);
        }
        return BIOMETRIC_SUCCESS;
    }

    public int remove(int biometricId) {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                service.remove(biometricId);
            } catch (RemoteException e) {
                Slog.e(TAG, "remove failed", e);
            }
            return BIOMETRIC_SUCCESS;
        }
        bind(mCurrentUserId);
        Slog.w(TAG, "remove(): Face service not started!");
        return BIOMETRIC_ERROR_TIMEOUT;
    }

    public int enumerate() {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            mServiceHandler.post(() -> {
                try {
                    service.enumerate();
                } catch (RemoteException e) {
                    Slog.e(TAG, "enumerate failed", e);
                    mHandler.post(() -> {
                        mFaceService.handleError(HAL_DEVICE_ID, 8, 0);
                    });
                }
            });
            return BIOMETRIC_SUCCESS;
        }
        bind(mCurrentUserId);
        Slog.w(TAG, "enumerate(): Face service not started!");
        return BIOMETRIC_ERROR_TIMEOUT;
    }

    public int enroll(byte[] cryptoToken, int timeout, int[] disabledFeatures) {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                service.enroll(cryptoToken, timeout, disabledFeatures);
            } catch (RemoteException e) {
                Slog.e(TAG, "enroll failed", e);
            }
            return BIOMETRIC_SUCCESS;
        }
        bind(mCurrentUserId);
        Slog.w(FaceService.TAG, "enroll(): Face service not started!");
        return BIOMETRIC_ERROR_TIMEOUT;
    }

    public void resetLockout(byte[] cryptoToken) {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                service.resetLockout(cryptoToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            return;
        }
        bind(mCurrentUserId);
        Slog.w(TAG, "resetLockout(): Face service not started!");
    }

    public int getAuthenticatorId() {
        int authId = 0;
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                authId = service.getAuthenticatorId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            return authId;
        }
        bind(mCurrentUserId);
        Slog.w(TAG, "updateActiveGroup(): Face service not started!");
        return authId;
    }

    public long generateChallenge(int timeout) {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                return service.generateChallenge(timeout);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            bind(mCurrentUserId);
            Slog.w(TAG, "startGenerateChallenge(): Face service not started!");
        }
        return BIOMETRIC_SUCCESS;
    }

    public int revokeChallenge() {
        IFaceService service = getService(mCurrentUserId);
        if (service != null) {
            try {
                return service.revokeChallenge();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return BIOMETRIC_SUCCESS;
    }

    public void setCurrentUserId(int userId) {
        mCurrentUserId = userId;
    }

    public void setServiceHandler(Handler handler) {
        mServiceHandler = handler;
    }

    public boolean callForBind(int userId) {
        return bind(userId);
    }

    private boolean bind(int userId) {
        Slog.d(TAG, "bind");
        if (!isServiceEnabled()) {
            Slog.d(TAG, "Face service disabled");
            return false;
        } else if (mIsServiceBinding) {
            Slog.d(TAG, "Face service is binding");
            return true;
        } else {
            if (userId != UserHandle.USER_NULL && getService(userId) == null) {
                if (createService(userId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean createService(int userId) {
        try {
            Intent intent = getServiceIntent();
            if (intent == null) {
                Slog.d(TAG, "Face service not found");
                return false;
            }
            boolean result = mContext.bindServiceAsUser(intent, new FaceServiceConnection(userId), 1, UserHandle.of(userId));
            if (result) {
                mIsServiceBinding = true;
            }
            return result;
        } catch (Exception e) {
            Slog.e(TAG, "bind failed", e);
        }
        return false;
    }

    public IFaceService getService(int userId) {
        if (userId == UserHandle.USER_NULL) {
            mFaceService.updateActiveGroup(ActivityManager.getCurrentUser(), null);
        }
        return mServices.get(mCurrentUserId);
    }

    private Intent getServiceIntent() {
        Intent intent = new Intent("com.crdroid.faceunlock.BIND");
        intent.setComponent(ComponentName.unflattenFromString(
                "com.crdroid.faceunlock/com.crdroid.faceunlock.service.FaceAuthService"));
        return intent;
    }

    public String getServicePackageName() {
        return "com.crdroid.faceunlock";
    }

    private boolean isServiceEnabled() {
        if (!FaceUnlockUtils.isFaceUnlockSupported()) {
            return false;
        }
        PackageManager pm = mContext.getPackageManager();
        Intent intent = getServiceIntent();
        ResolveInfo info = pm.resolveService(intent, PackageManager.MATCH_ALL);
        return info != null && info.serviceInfo.isEnabled();
    }

    public boolean isSupported() {
        return FaceUnlockUtils.isFaceUnlockSupported();
    }

    public boolean isDetected() {
        boolean enabled = isServiceEnabled();
        if (enabled) {
            mHandler.post(() -> {
                if (getService(mCurrentUserId) == null) {
                    bind(mCurrentUserId);
                }
            });
        }
        return enabled;
    }

    private class FaceServiceConnection implements ServiceConnection {
        int mUserId;

        public FaceServiceConnection(int userId) {
            mUserId = userId;
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Slog.d(TAG, "Face service connected");
            IFaceService faceService = IFaceService.Stub.asInterface(service);
            if (faceService != null) {
                synchronized (mServices) {
                    try {
                        faceService.setCallback(mReceiver);
                        mServices.put(mUserId, faceService);
                        mHandler.post(() -> {
                            if (mServices.size() == 1) {
                                mFaceService.loadAuthenticatorIds();
                            }
                            mFaceService.updateActiveGroup(mUserId, null);
                            mFaceService.doTemplateCleanupForUser(mUserId);
                        });
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    mIsServiceBinding = false;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Slog.d(TAG, "Face service disconnected");
            mServices.remove(mUserId);
            mIsServiceBinding = false;
            if (mUserId == mCurrentUserId) {
                mHandler.postDelayed(() -> {
                    mFaceService.handleError(HAL_DEVICE_ID, 8, 0);
                    bind(mUserId);
                }, 100);
                mContext.unbindService(this);
            }
        }
    }
}