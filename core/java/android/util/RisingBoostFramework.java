/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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

package android.util;

import com.android.server.LocalServices;
import android.os.PowerManagerInternal;
import android.os.SystemProperties;
import android.util.Log;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** @hide */
public class RisingBoostFramework {

    private static final String TAG = "RisingBoostFramework";
    public final static boolean DEBUG = false;

    private static final int HINT_INTERACTION = PowerManagerInternal.BOOST_INTERACTION;
    private static final int HINT_FRAME = PowerManagerInternal.BOOST_DISPLAY_UPDATE_IMMINENT;
    private static final int DEFAULT_DURATION = SystemProperties.getInt("persist.sys.powerhal.interaction.max", 64);

    private static volatile RisingBoostFramework instance;

    private PowerManagerInternal mPowerManagerInternal;
    private EnumMap<WorkloadType, HintInfo> workloadHints;

    private final Map<String, Boolean> gamePackageMap = new ConcurrentHashMap<>();

    /** @hide */
    private RisingBoostFramework() {
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        initHintInfos();
    }

    /** @hide */
    public static RisingBoostFramework getInstance() {
        if (instance == null) {
            synchronized (RisingBoostFramework.class) {
                if (instance == null) {
                    instance = new RisingBoostFramework();
                }
            }
        }
        return instance;
    }

    private void initHintInfos() {
        workloadHints = new EnumMap<>(WorkloadType.class);
        workloadHints.put(WorkloadType.DISPLAY_CHANGE, new HintInfo(HINT_FRAME));
        workloadHints.put(WorkloadType.ANIMATION, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.SCROLLING, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.VENDOR_HINT_KILL, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.TAP_EVENT, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.VENDOR_HINT_ROTATION_LATENCY_BOOST, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.LOADING, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.VENDOR_HINT_PACKAGE_INSTALL_BOOST, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.LAUNCH, new HintInfo(HINT_INTERACTION));
        workloadHints.put(WorkloadType.GAME, new HintInfo(HINT_INTERACTION));
    }

    public enum WorkloadType {
        ANIMATION,
        DISPLAY_CHANGE,
        GAME,
        LAUNCH,
        LOADING,
        SCROLLING,
        TAP_EVENT,
        VENDOR_HINT_KILL,
        VENDOR_HINT_PACKAGE_INSTALL_BOOST,
        VENDOR_HINT_ROTATION_LATENCY_BOOST
    }

    private static class HintInfo {
        final int hint;

        HintInfo(int hint) {
            this.hint = hint;
        }
    }

    public void perfBoost(WorkloadType workloadType, int duration) {
        HintInfo hintInfo = workloadHints.get(workloadType);
        if (hintInfo == null) {
            if (DEBUG) Log.d(TAG, "No HintInfo found for workloadType: " + workloadType);
            return;
        }
        if (mPowerManagerInternal == null) {
            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            if (mPowerManagerInternal == null) {
                if (DEBUG) Log.d(TAG, "PowerManagerInternal is null");
                return;
            }
        }
        if (DEBUG) Log.d(TAG, "Applying BOOST hint: " + hintInfo.hint + " for duration: " + duration);
        // override this till we have proper tuning for each hint
        mPowerManagerInternal.setPowerBoost(hintInfo.hint, DEFAULT_DURATION);
    }

    public void perfBoost(WorkloadType workloadType) {
        // override this till we have proper tuning for each hint
        perfBoost(workloadType, DEFAULT_DURATION);
    }
    
    public void perfBoost(WorkloadType workloadType, boolean enable) {
        // override this till we have proper tuning for each hint
        perfBoost(workloadType, DEFAULT_DURATION);
    }

    public void addPackageToGameList(String packageName) {
        gamePackageMap.put(packageName, true);
    }

    public boolean isPackageOnGameList(String packageName) {
        return gamePackageMap.containsKey(packageName);
    }
}

