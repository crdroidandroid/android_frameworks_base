/*
 * Copyright (C) 2025 AxionAOSP Project
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
package com.android.systemui.util;

import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.os.IBoostFramework;

public class SystemUIBoostFramework {

    private static final String TAG = "SystemUIBoostFramework";

    public static int REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_PANEL_VIEW = 1 << 1;
    public static int REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_NOTIFICATION_PANEL_VIEW_EXPAND = 1 << 2;
    public static int REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_STACK_SCROLL_LAYOUT = 1 << 5;
    public static int REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_EXPANSION_ANIMATION = 1 << 6;

    public static int REQUEST_ANIMATION_BOOST_TYPE_BASE = 1;
    public static int REQUEST_ANIMATION_BOOST_TYPE_FLING_NOTIFICATION_PANEL_VIEW = 1;

    private static final int STATUS_BIND_BIG_CORE = 0;
    private static final int STATUS_BIND_SMALL_CORE = 1;
    private static final int STATUS_UNBIND = 2;

    private static final long ANIMATION_BOOST_ON = 0L;
    private static final long ANIMATION_BOOST_OFF = -1L;

    private int mAnimationBoostType = 0;
    private int mBindStatus = STATUS_UNBIND;
    private long mAnimationBoost = ANIMATION_BOOST_OFF;
    
    private static IBoostFramework sService;
    
    private static SystemUIBoostFramework instance = null;

    private SystemUIBoostFramework() {}

    public static synchronized SystemUIBoostFramework getInstance() {
        if (instance == null) {
            instance = new SystemUIBoostFramework();
        }
        return instance;
    }

    private static IBoostFramework getService() {
        if (sService == null) {
            IBinder binder = ServiceManager.getService("boost_framework");
            sService = IBoostFramework.Stub.asInterface(binder);
        }
        return sService;
    }

    public void bindBigCore() {
        if (mBindStatus != STATUS_BIND_BIG_CORE) {
            mBindStatus = STATUS_BIND_BIG_CORE;
            executeSetThreadAffinity(STATUS_BIND_BIG_CORE);
        }
    }

    public void bindSmallCore() {
        if (mBindStatus != STATUS_BIND_SMALL_CORE) {
            mBindStatus = STATUS_BIND_SMALL_CORE;
            executeSetThreadAffinity(STATUS_BIND_SMALL_CORE);
        }
    }

    public void unbind() {
        if (mBindStatus != STATUS_UNBIND) {
            mBindStatus = STATUS_UNBIND;
            executeSetThreadAffinity(STATUS_UNBIND);
        }
    }

    public void animationBoostOn(int type) {
        mAnimationBoostType |= type;
        if (mAnimationBoost != ANIMATION_BOOST_ON) {
            bindBigCore();
            mAnimationBoost = ANIMATION_BOOST_ON;
            executeSetAnimationBoost(ANIMATION_BOOST_ON);
        }
    }

    public void animationBoostOff(int type) {
        mAnimationBoostType &= ~type;
        if (mAnimationBoostType <= 0 && mAnimationBoost != ANIMATION_BOOST_OFF) {
            unbind();
            mAnimationBoost = ANIMATION_BOOST_OFF;
            executeSetAnimationBoost(ANIMATION_BOOST_OFF);
        }
    }

    private void executeSetAnimationBoost(long boost) {
        try {
            animationBoost(boost);
        } catch (Exception e) {
            Log.w(TAG, "executeSetAnimationBoost() Exception: ", e);
        }
    }

    private void executeSetThreadAffinity(int affinity) {
        try {
            setProcThreadAffinity(affinity);
        } catch (Exception e) {
            Log.w(TAG, "executeSetThreadAffinity() Exception: ", e);
        }
    }
    
    public static void setProcThreadAffinity(int affinity) {
        try {
            int tid = Process.myPid();
            IBoostFramework service = getService();
            if (service != null) {
                service.setProcThreadAffinity(tid, affinity);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call setProcThreadAffinity", e);
        }
    }

    public static void animationBoost(long boost) {
        try {
            int tid = Process.myPid();
            IBoostFramework service = getService();
            if (service != null) {
                service.animationBoost(tid, boost);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call animationBoost", e);
        }
    }
}
