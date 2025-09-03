/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.server.gesture.threefinger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PointF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.android.internal.util.NtThreeFingerGestureHelper;

import java.util.concurrent.atomic.AtomicReference;

public class PartialScreenshotHelper {

    private static final String TAG = "ThreeFinger[PartialScreenshotHelper]";
    private static final long SERVICE_CONNECTION_TIMEOUT_MS = 10000;

    private final Context context;
    private final HandlerThread handlerThread;
    private final Handler handler;
    private final Messenger incomingMessenger;

    private final Object lock = new Object();
    private final AtomicReference<LastGestureAction> lastGestureAction = new AtomicReference<>(null);

    private Messenger serviceMessenger = null;
    private ServiceConnection serviceConnection = null;

    private final Runnable serviceTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            handleServiceTimeout();
        }
    };

    private static class LastGestureAction {
        int gestureType;
        long duration;
        int yPosition;

        public LastGestureAction(int gestureType, int yPosition, MotionEvent event) {
            this.gestureType = gestureType;
            this.yPosition = yPosition;
            this.duration = event == null ? 0 : event.getEventTime() - event.getDownTime();
        }

        @Override
        public String toString() {
            return "LastGestureAction{gestureType=" + gestureType + ", duration=" + duration + "}";
        }
    }

    private class IncomingHandler extends Handler {
        public IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (lock) {
                resetServiceConnection();
            }
        }
    }

    private class ScreenshotServiceConnection implements ServiceConnection {
        private final SparseArray<PointF> gesturePoints;

        public ScreenshotServiceConnection(SparseArray<PointF> gesturePoints) {
            this.gesturePoints = gesturePoints;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                if (serviceConnection != this) return;
                serviceMessenger = new Messenger(binder);
                handler.removeCallbacks(serviceTimeoutRunnable);

                try {
                    LastGestureAction lastAction = lastGestureAction.getAndSet(null);
                    if (lastAction != null) {
                        if (lastAction.gestureType == 0) {
                            sendInitialGesture(gesturePoints);
                        } else if (lastAction.gestureType == 2) {
                            sendMessageToService(2, lastAction.yPosition);
                        } else {
                            sendUpGesture(lastAction.yPosition);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to send queued gesture", e);
                }
            }
        }


        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                if (serviceConnection != null) {
                    resetServiceConnection();
                    if (handler.hasCallbacks(serviceTimeoutRunnable)) {
                        Slog.e(TAG, "Service disconnected unexpectedly");
                        handler.removeCallbacks(serviceTimeoutRunnable);
                    }
                }
            }
        }
    }

    public PartialScreenshotHelper(Context context) {
        this.context = context;
        this.handlerThread = new HandlerThread(TAG);
        this.handlerThread.start();
        this.handler = new Handler(handlerThread.getLooper());
        this.incomingMessenger = new Messenger(new IncomingHandler(handler.getLooper()));
    }

    private void sendMessageToService(int messageType, int value) throws RemoteException {
        try {
            if (serviceMessenger != null) {
                serviceMessenger.send(Message.obtain(null, messageType, value, 0));
            }
        } catch (Exception e) {
            Slog.e(TAG, "sendMessage failed: type=" + messageType, e);
        }
    }

    private void sendUpGesture(int yPosition) throws RemoteException {
        try {
            Message message = Message.obtain(null, 1, yPosition, 0);
            message.replyTo = incomingMessenger;
            serviceMessenger.send(message);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to send touch-up gesture", e);
        }
    }

    private void resetServiceConnection() {
        if (serviceConnection != null) {
            Slog.i(TAG, "Resetting service connection");
            context.unbindService(serviceConnection);
            serviceConnection = null;
            serviceMessenger = null;
        }
    }

    private int getTopmostY(SparseArray<PointF> points) {
        float minY = Float.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            int key = points.keyAt(i);
            if (points.get(key).y < minY) {
                minY = points.get(key).y;
            }
        }
        return (int) minY;
    }

    private void handleServiceTimeout() {
        synchronized (lock) {
            if (serviceConnection != null) {
                Slog.e(TAG, "Service connection timed out");
                resetServiceConnection();
            }
        }
    }

    public void sendMove(MotionEvent event) {
        if (!isServiceBindingActive()) return;
        try {
            SparseArray<PointF> points = new SparseArray<>();
            NtThreeFingerGestureHelper.getPoints(event, points);
            int topY = getTopmostY(points);

            if (isServiceConnected()) {
                lastGestureAction.set(null);
                sendMessageToService(2, topY);
            } else {
                Slog.w(TAG, "Move gesture: service not connected yet");
                lastGestureAction.set(new LastGestureAction(2, topY, event));
            }
        } catch (Exception e) {
        }
    }

    public void sendUp(MotionEvent event) {
        if (!isServiceBindingActive()) return;
        try {
            SparseArray<PointF> points = new SparseArray<>();
            NtThreeFingerGestureHelper.getPoints(event, points);
            int topY = getTopmostY(points);

            if (isServiceConnected()) {
                lastGestureAction.set(null);
                sendUpGesture(topY);
            } else {
                Slog.w(TAG, "Touch-up gesture: service not connected yet");
                lastGestureAction.set(new LastGestureAction(1, topY, event));
            }
        } catch (Exception e) {
        }
    }

    public void bindService(SparseArray<PointF> points) {
        synchronized (lock) {
            lastGestureAction.set(null);

            if (serviceMessenger != null) {
                try {
                    sendInitialGesture(points);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to send gesture to service", e);
                }
            } else {
                if (serviceConnection != null) {
                    resetServiceConnection();
                }

                ComponentName serviceComponent = ComponentName.unflattenFromString(
                        "com.nothing.ntscreenshot/.PartialScreenshotService");
                Intent intent = new Intent();
                intent.setComponent(serviceComponent);

                ScreenshotServiceConnection connection = new ScreenshotServiceConnection(points);
                if (context.bindServiceAsUser(intent, connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.CURRENT)) {
                    serviceConnection = connection;
                    lastGestureAction.set(new LastGestureAction(0, getTopmostY(points), null));
                    handler.postDelayed(serviceTimeoutRunnable, SERVICE_CONNECTION_TIMEOUT_MS);
                } else {
                    Slog.e(TAG, "Failed to bind service");
                }
            }
        }
    }

    public boolean isServiceConnected() {
        synchronized (lock) {
            return serviceMessenger != null;
        }
    }

    public boolean isServiceBindingActive() {
        synchronized (lock) {
            return serviceConnection != null;
        }
    }

    private void sendInitialGesture(SparseArray<PointF> points) throws RemoteException {
        sendMessageToService(0, getTopmostY(points));
    }
}
