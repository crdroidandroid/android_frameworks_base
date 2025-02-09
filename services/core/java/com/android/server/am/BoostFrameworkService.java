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
package com.android.server.am;

import android.app.ActivityManager;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import com.android.internal.os.IBoostFramework;

import java.io.File;
import java.io.IOException;

public class BoostFrameworkService extends IBoostFramework.Stub {

    private final static int BIG_CORES = 0;
    private final static int SMALL_CORES = 1;

    private static final long ANIMATION_BOOST_ON = 0L;
    private static final long ANIMATION_BOOST_OFF = -1L;

    @Override
    public void animationBoost(int tid, long boost) throws RemoteException {
        try {
            int threadPriority = Process.getThreadPriority(tid);
            if (boost >= ANIMATION_BOOST_ON) {
                Process.setThreadScheduler(tid, Process.SCHED_RR, 1);
            } else if (boost == ANIMATION_BOOST_OFF) {
                Process.setThreadScheduler(tid, Process.SCHED_OTHER, 0);
                try {
                    Process.setThreadPriority(threadPriority);
                } catch (Exception e) {
                    Log.v("BoostFrameworkService", "Failed to restore thread priority for " + tid + ", setting to default.");
                    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
                }
                Process.setThreadScheduler(tid, Process.SCHED_OTHER, 0);
            }
        } catch (Exception e) {
            Log.v("BoostFrameworkService", "Unexpected error in animationBoost: " + e.getMessage());
        }
    }

    @Override
    public void setProcThreadAffinity(int tid, int affinity) throws RemoteException {
        try {
            int threadGroup = 1;
            if (affinity == BIG_CORES) {
                threadGroup = Process.THREAD_GROUP_TOP_APP;
            } else if (affinity == SMALL_CORES) {
                threadGroup = Process.THREAD_GROUP_BACKGROUND;
            }
            Process.setThreadGroupAndCpuset(tid, threadGroup);
            Process.setThreadAffinity(tid, affinity);
        } catch (Exception e) {}
    }
}
