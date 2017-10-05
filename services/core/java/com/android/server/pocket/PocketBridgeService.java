/**
 * Copyright (C) 2017 The ParanoidAndroid Project
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
package com.android.server.pocket;

import java.io.FileOutputStream;
import java.io.IOException;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.pocket.IPocketCallback;
import android.pocket.PocketManager;
import android.provider.Settings.System;
import android.util.Slog;
import com.android.internal.util.FastPrintWriter;
import com.android.server.SystemService;

import static android.provider.Settings.System.POCKET_JUDGE;

/**
 * This service communicates pocket state to the pocket judge kernel driver.
 * It maintains the pocket state by binding to the pocket service.
 *
 * @author Chris Lahaye
 * @hide
 */
public class PocketBridgeService extends SystemService {

    private static final String TAG = PocketBridgeService.class.getSimpleName();
    private static final int MSG_POCKET_STATE_CHANGED = 1;

    private Context mContext;
    private boolean mEnabled;
    private PocketBridgeHandler mHandler;
    private PocketBridgeObserver mObserver;

    private PocketManager mPocketManager;
    private boolean mIsDeviceInPocket;
    private final IPocketCallback mPocketCallback = new IPocketCallback.Stub() {
        @Override
        public void onStateChanged(boolean isDeviceInPocket, int reason) {
            boolean changed = false;
            if (reason == PocketManager.REASON_SENSOR) {
                if (isDeviceInPocket != mIsDeviceInPocket) {
                    mIsDeviceInPocket = isDeviceInPocket;
                    changed = true;
                }
            } else {
                changed = isDeviceInPocket != mIsDeviceInPocket;
                mIsDeviceInPocket = false;
            }
            if (changed) {
                mHandler.sendEmptyMessage(MSG_POCKET_STATE_CHANGED);
            }
        }
    };

    public PocketBridgeService(Context context) {
        super(context);
        mContext = context;
        HandlerThread handlerThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        mHandler = new PocketBridgeHandler(handlerThread.getLooper());
        mPocketManager = (PocketManager)
                context.getSystemService(Context.POCKET_SERVICE);
        mObserver = new PocketBridgeObserver(mHandler);
        mObserver.onChange(true);
        mObserver.register();
    }

    @Override
    public void onStart() {
    }

    private void setEnabled(boolean enabled) {
        if (enabled != mEnabled) {
            mEnabled = enabled;
            update();
        }
    }

    private void update() {
        if (mPocketManager == null) return;

        if (mEnabled) {
            mPocketManager.addCallback(mPocketCallback);
        } else {
            mPocketManager.removeCallback(mPocketCallback);
        }
    }

    private class PocketBridgeHandler extends Handler {

        private String path;

        public PocketBridgeHandler(Looper looper) {
            super(looper);
            path = mContext.getResources().getString(
                com.android.internal.R.string.config_pocketBridgeSysfsInpocket);
        }

        @Override
        public void handleMessage(android.os.Message msg) {
            if (msg.what != MSG_POCKET_STATE_CHANGED) {
                Slog.w(TAG, "Unknown message:" + msg.what);
                return;
            }

            try (FileOutputStream mFileOutputStream = new FileOutputStream(path)) {
                mFileOutputStream.write((int)(mIsDeviceInPocket ? '1' : '0'));
                mFileOutputStream.flush();
            }
            catch(IOException e) {
                Slog.w(TAG, "Pocket bridge error occured", e);
                setEnabled(false);
            }
        }

    }

    private class PocketBridgeObserver extends ContentObserver {

        private boolean mRegistered;

        public PocketBridgeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            final boolean enabled = System.getIntForUser(mContext.getContentResolver(),
                    POCKET_JUDGE, 0 /* default */, UserHandle.USER_CURRENT) == 1;
            setEnabled(enabled);
        }

        public void register() {
            if (!mRegistered) {
                mContext.getContentResolver().registerContentObserver(
                        System.getUriFor(POCKET_JUDGE), true, this);
                mRegistered = true;
            }
        }

        public void unregister() {
            if (mRegistered) {
                mContext.getContentResolver().unregisterContentObserver(this);
                mRegistered = false;
            }
        }

    }

}
