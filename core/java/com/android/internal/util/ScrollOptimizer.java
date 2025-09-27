/*
 * Copyright (C) 2025 AxionOS
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
package com.android.internal.util;

import android.graphics.BLASTBufferQueue;
import android.os.Process;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;

public class ScrollOptimizer {

    /** @hide */
    public static final int FLING_START = 1;
    /** @hide */
    public static final int FLING_END = 0;

    private static final String TAG = "AxPerf";

    private static final String PROP_SCROLL_OPT = "persist.sys.perf.scroll_opt";
    private static final String PROP_SCROLL_OPT_HEAVY_APP = "persist.sys.perf.scroll_opt.heavy_app";
    private static final String PROP_DEBUG = "persist.sys.perf.scroll_opt_debug";

    private static final String TIMER_SLACK_CONTENT = "50000";

    private static final long FRAME_INTERVAL_THRESHOLD_NS = 10_000_000L;
    private static final long DEFAULT_FRAME_DELAY_MS = 10L;
    private static final long OPTIMIZED_FRAME_DELAY_MS = 3L;
    private static final long FLING_END_TIMEOUT_MS = 3000L;

    private static int sInitialUndequeued = 4;
    private static int sFallbackUndequeued = 3;

    private static final int PROP_UNSET = -1;
    private static final int MOTION_TOUCH = 0;
    private static final int MOTION_FLING = 1;
    private static final int MOTION_SCROLL = 2;
    private static final int APP_TYPE_NORMAL = 1;
    private static final int APP_TYPE_HEAVY = 2;

    private static boolean sPrevUseVsync = true;
    private static boolean sAdjustCalled = false;
    private static boolean sTimerSlackUpdated = false;
    private static boolean sPreRenderDone = false;
    private static boolean sNeedUpdateBuffer = false;

    private static long sFrameIntervalNs = -1;
    private static long sFrameIntervalMs = -1;
    private static long sHalfFrameIntervalNs = -1;
    private static long sHeavyFrameThresholdNs = -1;
    private static long sLastVsyncTimeNs = -1;
    private static long sLastAdjustedTimeNs = -1;
    private static long sLastFlingStartMs = -1;
    private static long sLastUIStartNs = -1;
    private static long sLastUIEndNs = -1;

    private static int sPid = -1;
    private static int sHeavyAppProp = -1;
    private static int sHeavyApp = 0;
    private static int sHeavyFrameCount = 0;
    private static int sAppType = APP_TYPE_NORMAL;
    private static int sMotionType = PROP_UNSET;
    private static int mLastFlingFlg = 0;

    private static int sActualUndequeued = 0;
    private static int sExpectedUndequeued = 0;
    
    private static BLASTBufferQueue sBlastQueue = null;
    private static Method sSetUndequeuedMethod = null;
    private static Method sGetUndequeuedMethod = null;

    private static FileOutputStream sTimerSlackStream = null;

    private static boolean sDebugEnabled = false;
    private static boolean sInitCalled = false;
    private static boolean sFeatureEnabled = false;
    private static boolean sTemporarilyDisabled = false;
    private static boolean sLastUseVsync = true;

    private static void logger(String msg) {
        if (sDebugEnabled) {
            Log.d(TAG, msg);
        }
    }

    private static int getUndequeuedBufferCount() {
        int undequeued = 0;
        if (sBlastQueue == null) {
            logger("sBlastBufferQueue is null.");
            sFeatureEnabled = false;
            return 0;
        }
        try {
            Object res = sGetUndequeuedMethod.invoke(sBlastQueue);
            undequeued = res instanceof Integer ? (Integer) res : 0;
            logger("undequeuedBufferCount: " + undequeued);
        } catch (Exception e) {
            undequeued = 0;
        }
        return undequeued;
    }

    private static void initIfNeeded() {
        try {
            sFeatureEnabled = SystemProperties.getBoolean(PROP_SCROLL_OPT, true);
            int prop = SystemProperties.getInt(PROP_SCROLL_OPT_HEAVY_APP, 2);
            sHeavyAppProp = prop;
            sHeavyApp = prop;
            sDebugEnabled = SystemProperties.getBoolean(PROP_DEBUG, false);

            Class<?> clazz = Class.forName("android.graphics.BLASTBufferQueue");
            sSetUndequeuedMethod = clazz.getMethod("setUndequeuedBufferCount", Integer.TYPE);
            sGetUndequeuedMethod = clazz.getMethod("getUndequeuedBufferCount");

            sPid = Process.myPid();
            sTimerSlackStream = new FileOutputStream(
                    String.format(Locale.US, "/proc/%d/timerslack_ns", sPid));

            if (Process.myUid() == 1000) {
                logger("Disable for system_server");
                sFeatureEnabled = false;
            }
            sInitCalled = true;
        } catch (Exception e) {
            Log.e(TAG, "Couldn't load BLASTBufferQueue Class");
            sInitCalled = true;
            sFeatureEnabled = false;
        }

        if (sHeavyApp == 1) {
            logger("Heavy app detection is enabled.");
        }
        if (sSetUndequeuedMethod == null || sGetUndequeuedMethod == null) {
            Log.e(TAG, "Couldn't find UndequeuedBufferCount functions");
            sFeatureEnabled = false;
        }
    }

    public static void disableOptimizer(boolean disable) {
        boolean wasTemporarilyDisabled = sTemporarilyDisabled;
        if (wasTemporarilyDisabled == disable) return;
        if (wasTemporarilyDisabled) {
            sFeatureEnabled = true;
            sTemporarilyDisabled = false;
            logger("enable ScrollOptimizer again.");
        } else if (sFeatureEnabled) {
            sFeatureEnabled = false;
            sTemporarilyDisabled = true;
            logger("disable ScrollOptimizer temperarily.");
        }
    }

    private static void resetFlingState() {
        sLastUseVsync = true;
        sAdjustCalled = false;
        sAppType = APP_TYPE_NORMAL;
        sHeavyFrameCount = 0;
        sMotionType = PROP_UNSET;
        mLastFlingFlg = 0;
        sTimerSlackUpdated = false;
    }

    private static void updateExpectedFromPreRender() {
        if (sPreRenderDone) {
            sPreRenderDone = false;
            int val = getUndequeuedBufferCount();
            sActualUndequeued = val;
            sExpectedUndequeued = val;
            if (val > 1) {
                sExpectedUndequeued = val - 1;
            } else if (val < 1) {
                sExpectedUndequeued = 1;
            }
        }
    }

    public static long getAdjustedAnimationClock(long originalTimeNs) {
        if (!sFeatureEnabled || Process.myTid() != sPid) {
            return originalTimeNs;
        }
        if (sAdjustCalled) {
            logger("unnecessary adjustClock is called!");
            if (originalTimeNs > sLastAdjustedTimeNs) {
                sLastAdjustedTimeNs = originalTimeNs;
            }
            return sLastAdjustedTimeNs;
        }
        sAdjustCalled = true;
        long candidate = sLastAdjustedTimeNs + sFrameIntervalMs;
        if (mLastFlingFlg != 1) {
            if (originalTimeNs >= candidate ||
                SystemClock.uptimeMillis() >= sLastFlingStartMs + FLING_END_TIMEOUT_MS) {
                sLastAdjustedTimeNs = originalTimeNs;
                return originalTimeNs;
            }
            logger("extended adjustedTime: " + candidate + ", originTime: " + originalTimeNs);
            logger("extend clock adjustion");
            sLastAdjustedTimeNs = candidate;
            return candidate;
        }
        if (candidate < originalTimeNs) {
            candidate = originalTimeNs;
        } else if (sPrevUseVsync) {
            long offset = candidate - originalTimeNs;
            if (offset > 0 && sFrameIntervalMs > 0) {
                long rounds = Math.round((double) offset / (double) sFrameIntervalMs);
                candidate = (sFrameIntervalMs * rounds) + originalTimeNs;
            }
        }
        logger("adjustedTime: " + candidate + ", originTime: " + originalTimeNs);
        sLastAdjustedTimeNs = candidate;
        return candidate;
    }

    public static long getFrameDelay() {
        if (!sFeatureEnabled) {
            return DEFAULT_FRAME_DELAY_MS;
        }
        return OPTIMIZED_FRAME_DELAY_MS;
    }

    private static void setUndequeuedBufferCount(int count) {
        if (sBlastQueue == null) {
            logger("sBlastBufferQueue is null.");
            sFeatureEnabled = false;
            return;
        }
        try {
            sSetUndequeuedMethod.invoke(sBlastQueue, count);
            logger("setUndequeuedBufferCount: " + count);
        } catch (Exception e) {
            e.printStackTrace();
            sFeatureEnabled = false;
        }
    }

    private static void writeTimerSlack() {
        if (sTimerSlackStream == null) {
            sFeatureEnabled = false;
            return;
        }
        StrictMode.ThreadPolicy old = StrictMode.allowThreadViolations();
        try {
            try {
                sTimerSlackStream.write(TIMER_SLACK_CONTENT.getBytes());
                sTimerSlackUpdated = true;
            } catch (IOException e) {
                e.printStackTrace();
                Log.w(TAG, "Failed to update timer slack!");
                sFeatureEnabled = false;
            }
        } finally {
            StrictMode.setThreadPolicy(old);
        }
    }

    public static void setBLASTBufferQueue(BLASTBufferQueue queue) {
        if (sFeatureEnabled && Process.myTid() == sPid && sBlastQueue != queue) {
            sBlastQueue = queue;
            sNeedUpdateBuffer = false;
            setUndequeuedBufferCount(sInitialUndequeued);
        }
    }

    public static void setFlingFlag(int flingFlg) {
        if (sFeatureEnabled && Process.myTid() == sPid) {
            logger("setFlingFlag: " + flingFlg);
            if (flingFlg != 1) {
                if (flingFlg < 0) {
                    logger("Fling quit for unknown.");
                }
                if (mLastFlingFlg == 1) {
                    resetFlingState();
                    logger("Fling end.");
                }
                return;
            }
            if (mLastFlingFlg == 1) {
                resetFlingState();
                logger("avoid concurrent fling");
                return;
            }
            if (sMotionType == MOTION_FLING) {
                mLastFlingFlg = flingFlg;
                if (!sTimerSlackUpdated) {
                    writeTimerSlack();
                }
                sNeedUpdateBuffer = false;
                sLastFlingStartMs = SystemClock.uptimeMillis();
                logger("Fling start.");
            } else {
                logger("Fling without touch");
            }
            sMotionType = PROP_UNSET;
        }
    }

    public static void setFrameInterval(long nanos) {
        if (!sInitCalled) {
            initIfNeeded();
        }
        logger("frameIntervalNanos: " + nanos);
        sFrameIntervalNs = nanos;
        sFrameIntervalMs = nanos / 1_000_000;
        long half = nanos / 2;
        sHalfFrameIntervalNs = half;
        sHeavyFrameThresholdNs = half * OPTIMIZED_FRAME_DELAY_MS;
        if (nanos > FRAME_INTERVAL_THRESHOLD_NS) {
            sInitialUndequeued = 3;
            sFallbackUndequeued = 2;
        } else {
            sInitialUndequeued = 4;
            sFallbackUndequeued = 3;
        }
        if (sHeavyAppProp == PROP_UNSET) {
            if (nanos > FRAME_INTERVAL_THRESHOLD_NS) {
                sHeavyApp = 0;
            } else {
                sHeavyApp = 1;
            }
        }
    }

    public static void setMotionType(int motion) {
        if (sFeatureEnabled && Process.myTid() == sPid) {
            if (motion == MOTION_TOUCH) {
                boolean wasFling = (mLastFlingFlg == 1);
                resetFlingState();
                int curUndequeued = getUndequeuedBufferCount();
                sActualUndequeued = curUndequeued;
                if (sNeedUpdateBuffer && curUndequeued != sFallbackUndequeued) {
                    if (curUndequeued > sFallbackUndequeued ||
                        System.nanoTime() - sLastUIEndNs > (sFrameIntervalNs * 2) + 1_000_000) {
                        setUndequeuedBufferCount(sFallbackUndequeued);
                        sExpectedUndequeued = sFallbackUndequeued;
                    }
                } else if (wasFling || sActualUndequeued > 0) {
                    sExpectedUndequeued = sActualUndequeued;
                } else {
                    sExpectedUndequeued = 1;
                }
            } else if (motion == MOTION_SCROLL) {
                sNeedUpdateBuffer = true;
                int cur = getUndequeuedBufferCount();
                sActualUndequeued = cur;
                if (sExpectedUndequeued > cur && cur > 0) {
                    sExpectedUndequeued = cur;
                }
            }
            sMotionType = motion;
            logger("setMotionType: " + motion);
        }
    }

    public static void setUITaskStatus(boolean running) {
        if (sFeatureEnabled && Process.myTid() == sPid) {
            long nowNs = System.nanoTime();
            long uiDurationNs;
            if (running) {
                sAdjustCalled = false;
                if (mLastFlingFlg == 1) {
                    long durSinceUIStart = nowNs - sLastUIStartNs;
                    if (durSinceUIStart > (sFrameIntervalNs * 2) - 1_000_000) {
                        updateExpectedFromPreRender();
                    }
                    long hf = sHalfFrameIntervalNs;
                    if (durSinceUIStart > (OPTIMIZED_FRAME_DELAY_MS * hf) - 1_000_000 &&
                        nowNs - sLastUIEndNs > hf) {
                        sHeavyFrameCount++;
                    }
                }
                sLastUIStartNs = nowNs;
                uiDurationNs = 0;
            } else {
                sLastUIEndNs = nowNs;
                sPrevUseVsync = sLastUseVsync;
                uiDurationNs = nowNs - sLastUIStartNs;
                if (mLastFlingFlg == 1 && uiDurationNs > sFrameIntervalNs * 2) {
                    updateExpectedFromPreRender();
                }
            }
            int mode = sHeavyApp;
            if (mode == 0) return;
            if (mode == 2) {
                sAppType = APP_TYPE_HEAVY;
                return;
            }
            if (running) return;
            if (sMotionType == MOTION_SCROLL || mLastFlingFlg == 1) {
                if (uiDurationNs > sFrameIntervalNs) {
                    sHeavyFrameCount++;
                }
                if ((sHeavyFrameCount > 1 || uiDurationNs > sHeavyFrameThresholdNs) &&
                    sAppType != APP_TYPE_HEAVY) {
                    sAppType = APP_TYPE_HEAVY;
                    logger("App type: heavy app");
                }
            }
            logger("UI duration: " + uiDurationNs);
        }
    }

    public static void setVsyncTime(long vsyncTimeNs) {
        if (sFeatureEnabled) {
            sLastVsyncTimeNs = vsyncTimeNs;
            logger("setVsyncTime: " + sLastVsyncTimeNs);
        }
    }

    public static boolean shouldUseVsync() {
        boolean result = true;
        if (sFeatureEnabled && Process.myTid() == sPid) {
            if (mLastFlingFlg != 1) {
                sLastUseVsync = true;
                return true;
            }
            if (sAppType == APP_TYPE_HEAVY) {
                sLastUseVsync = false;
                return false;
            }
            if (sPreRenderDone) {
                logger("pre-render done");
                sLastUseVsync = true;
                return true;
            }
            long interval = sFrameIntervalNs;
            long timeToNext = interval - ((sLastUIStartNs - sLastVsyncTimeNs) % interval);
            if (timeToNext < 3_000_000L) {
                logger("too close to next vsync");
                sLastUseVsync = false;
                if (sExpectedUndequeued > 0) {
                    sExpectedUndequeued = sExpectedUndequeued - 1;
                }
                return false;
            }
            if (!sLastUseVsync) {
                logger("use vsync as last frame not use vsync");
                sLastUseVsync = true;
                return true;
            }
            int undequeued = getUndequeuedBufferCount();
            sActualUndequeued = undequeued;
            int expected = sExpectedUndequeued;
            if (undequeued > expected) {
                logger("align undequeued: " + sActualUndequeued + " with expected: " + sExpectedUndequeued);
                sActualUndequeued = expected;
            } else if (undequeued < 1 && expected > 0) {
                sActualUndequeued = 1;
            }
            if (sActualUndequeued > 0) {
                sExpectedUndequeued = sExpectedUndequeued - 1;
                result = false;
            } else {
                sPreRenderDone = true;
                logger("pre-render done");
                result = true;
            }
            sLastUseVsync = result;
        }
        return result;
    }
}
