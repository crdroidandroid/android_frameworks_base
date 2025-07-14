/*
 * Copyright (C) 2014 The NamelessRom Project
 *           (C) 2025 crDroid Android Project
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

package com.android.systemui.crdroid.onthego;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.systemui.res.R;

import com.android.internal.util.crdroid.OnTheGoUtils;

import java.io.IOException;
import java.util.Arrays;

public class OnTheGoService extends Service {

    private static final String  TAG   = "OnTheGoService";
    private static final boolean DEBUG = false;

    private static final int ONTHEGO_NOTIFICATION_ID = 81333378;
    private static final String ONTHEGO_CHANNEL_ID = "onthego_notif";

    public static final String ACTION_START          = "start";
    public static final String ACTION_STOP           = "stop";
    public static final String ACTION_TOGGLE_ALPHA   = "toggle_alpha";
    public static final String ACTION_TOGGLE_CAMERA  = "toggle_camera";
    public static final String ACTION_TOGGLE_OPTIONS = "toggle_options";
    public static final String EXTRA_ALPHA           = "extra_alpha";

    private static final int CAMERA_BACK  = 0;
    private static final int CAMERA_FRONT = 1;

    private static final int NOTIFICATION_STARTED = 0;
    private static final int NOTIFICATION_RESTART = 1;
    private static final int NOTIFICATION_ERROR   = 2;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Object  mRestartObject = new Object();

    private FrameLayout mOverlay;
    private boolean isOverlayAdded = false;

    private NotificationManager mNotificationManager;
    private NotificationChannel mNotificationChannel;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private String mCameraId;
    private TextureView mTextureView;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceivers();
        resetViews();
    }

    private void registerReceivers() {
        final IntentFilter alphaFilter = new IntentFilter(ACTION_TOGGLE_ALPHA);
        registerReceiver(mAlphaReceiver, alphaFilter, Context.RECEIVER_NOT_EXPORTED);
        final IntentFilter cameraFilter = new IntentFilter(ACTION_TOGGLE_CAMERA);
        registerReceiver(mCameraReceiver, cameraFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterReceivers() {
        try {
            unregisterReceiver(mAlphaReceiver);
        } catch (Exception ignored) { }
        try {
            unregisterReceiver(mCameraReceiver);
        } catch (Exception ignored) { }
    }

    private final BroadcastReceiver mAlphaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final float intentAlpha = intent.getFloatExtra(EXTRA_ALPHA, 0.5f);
            toggleOnTheGoAlpha(intentAlpha);
        }
    };

    private final BroadcastReceiver mCameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mRestartObject) {
                final ContentResolver resolver = getContentResolver();
                final boolean restartService = Settings.System.getInt(resolver,
                        Settings.System.ON_THE_GO_SERVICE_RESTART, 0) == 1;
                if (restartService) {
                    restartOnTheGo();
                } else {
                    stopOnTheGo(true);
                }
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logDebug("onStartCommand called");

        if (intent == null || !OnTheGoUtils.hasCamera(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();

        if (action != null && !action.isEmpty()) {
            logDebug("Action: " + action);
            if (action.equals(ACTION_START)) {
                startOnTheGo();
            } else if (action.equals(ACTION_STOP)) {
                stopOnTheGo(false);
            } else if (action.equals(ACTION_TOGGLE_OPTIONS)) {
                new OnTheGoDialog(this).show();
            }
        } else {
            logDebug("Action is NULL or EMPTY!");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startOnTheGo() {
        if (mNotificationManager != null) {
            logDebug("Starting while active, stopping.");
            stopOnTheGo(false);
            return;
        }

        resetViews();
        registerReceivers();
        setupViews(false);

        createNotification(NOTIFICATION_STARTED);
    }

    private void stopOnTheGo(boolean shouldRestart) {
        unregisterReceivers();
        resetViews();

        // Cancel notification
        if (mNotificationManager != null) {
            mNotificationManager.cancel(ONTHEGO_NOTIFICATION_ID);
            mNotificationManager.deleteNotificationChannel(ONTHEGO_CHANNEL_ID);
            mNotificationManager = null;
        }

        if (shouldRestart) {
            createNotification(NOTIFICATION_RESTART);
        }

        stopSelf();
    }

    private void restartOnTheGo() {
        resetViews();
        mHandler.removeCallbacks(mRestartRunnable);
        mHandler.postDelayed(mRestartRunnable, 750);
    }

    private final Runnable mRestartRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mRestartObject) {
                setupViews(true);
            }
        }
    };

    private void toggleOnTheGoAlpha() {
        final float alpha = Settings.System.getFloat(getContentResolver(),
                Settings.System.ON_THE_GO_ALPHA,
                0.5f);
        toggleOnTheGoAlpha(alpha);
    }

    private void toggleOnTheGoAlpha(float alpha) {
        Settings.System.putFloat(getContentResolver(),
                Settings.System.ON_THE_GO_ALPHA,
                alpha);

        if (mOverlay != null) {
            mOverlay.setAlpha(alpha);
        }
    }

    private void setupViews(final boolean isRestarting) {
        logDebug("Setup Views with Camera2");

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                int preferredFacing = Settings.System.getInt(getContentResolver(),
                        Settings.System.ON_THE_GO_CAMERA, 0);

                if ((preferredFacing == 1 && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) ||
                    (preferredFacing == 0 && lensFacing == CameraCharacteristics.LENS_FACING_BACK)) {
                    mCameraId = cameraId;
                    break;
                }
            }

            if (mCameraId == null) {
                logDebug("No suitable camera found");
                createNotification(NOTIFICATION_ERROR);
                stopOnTheGo(true);
                return;
            }

            mTextureView = new TextureView(this);
            mTextureView.setSurfaceTextureListener(textureListener);

            mOverlay = new FrameLayout(this);
            mOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            mOverlay.addView(mTextureView);

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT
            );
            if (!isOverlayAdded) {
                wm.addView(mOverlay, params);
                isOverlayAdded = true;
            }

            toggleOnTheGoAlpha();

        } catch (CameraAccessException e) {
            logDebug("CameraAccessException: " + e.getMessage());
            createNotification(NOTIFICATION_ERROR);
            stopOnTheGo(true);
        }
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            closeCamera();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openCamera() {
        if (mCameraDevice != null) return;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                logDebug("Camera permission not granted");
                stopOnTheGo(true);
                return;
            }

            manager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    startCameraPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    logDebug("Camera error: " + error);
                    camera.close();
                    mCameraDevice = null;
                    createNotification(NOTIFICATION_ERROR);
                    stopOnTheGo(true);
                }
            }, mHandler);
        } catch (CameraAccessException e) {
            logDebug("Failed to open camera: " + e.getMessage());
        }
    }

    private void startCameraPreview() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            if (texture == null) {
                logDebug("SurfaceTexture is null. Skipping preview.");
                return;
            }
            texture.setDefaultBufferSize(1080, 1920);

            Surface surface = new Surface(texture);
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(
                Arrays.asList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        mCaptureSession = session;
                        try {
                            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                                    null, mHandler);
                        } catch (CameraAccessException e) {
                            logDebug("Preview session error: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        logDebug("CaptureSession configuration failed");
                    }
                }, mHandler);

        } catch (CameraAccessException e) {
            logDebug("CameraAccessException in preview: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void resetViews() {
        closeCamera();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (mOverlay != null) {
            if (isOverlayAdded) {
                wm.removeView(mOverlay);
                isOverlayAdded = false;
            }
            mOverlay.removeAllViews();
            mOverlay = null;
        }
    }

    private void createNotification(final int type) {
        final Resources r = getResources();
        final Notification.Builder builder = new Notification.Builder(this, ONTHEGO_CHANNEL_ID)
                .setTicker(r.getString(
                        (type == 1 ? R.string.onthego_notif_camera_changed :
                                (type == 2 ? R.string.onthego_notif_error
                                        : R.string.onthego_notif_ticker))
                ))
                .setContentTitle(r.getString(
                        (type == 1 ? R.string.onthego_notif_camera_changed :
                                (type == 2 ? R.string.onthego_notif_error
                                        : R.string.onthego_notif_title))
                ))
                .setSmallIcon(com.android.systemui.res.R.drawable.ic_lock_onthego)
                .setWhen(System.currentTimeMillis())
                .setOngoing(!(type == 1 || type == 2));

        if (type == 1 || type == 2) {
            final ComponentName cn = new ComponentName("com.android.systemui",
                    "com.android.systemui.spark.onthego.OnTheGoService");
            final Intent startIntent = new Intent();
            startIntent.setComponent(cn);
            startIntent.setAction(ACTION_START);
            final PendingIntent startPendIntent = PendingIntent.getService(this, 0, startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            builder.addAction(com.android.internal.R.drawable.ic_media_play,
                    r.getString(R.string.onthego_notif_restart), startPendIntent);
        } else {
            final Intent stopIntent = new Intent(this, OnTheGoService.class)
                    .setAction(OnTheGoService.ACTION_STOP);
            final PendingIntent stopPendIntent = PendingIntent.getService(this, 0, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            final Intent optionsIntent = new Intent(this, OnTheGoService.class)
                    .setAction(OnTheGoService.ACTION_TOGGLE_OPTIONS);
            final PendingIntent optionsPendIntent = PendingIntent.getService(this, 0, optionsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            builder
                    .addAction(com.android.internal.R.drawable.ic_media_stop,
                            r.getString(R.string.onthego_notif_stop), stopPendIntent)
                    .addAction(com.android.internal.R.drawable.ic_text_dot,
                            r.getString(R.string.onthego_notif_options), optionsPendIntent);
        }

        final Notification notif = builder.build();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationChannel = new NotificationChannel(ONTHEGO_CHANNEL_ID,
                r.getString(R.string.onthego_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        mNotificationManager.createNotificationChannel(mNotificationChannel);

        mNotificationManager.notify(ONTHEGO_NOTIFICATION_ID, notif);
    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.e(TAG, msg);
        }
    }
}
