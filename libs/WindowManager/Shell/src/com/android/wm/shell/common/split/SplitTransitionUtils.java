/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.wm.shell.common.split;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_SPLIT_SCREEN;
import static com.android.wm.shell.shared.TransitionUtil.isOpeningMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.window.TransitionInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.ProtoLog;
import com.android.wm.shell.splitscreen.StageTaskListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Function;

public class SplitTransitionUtils {
    /**
     * Returns a WCT to apply if the provided transition contains only a partial set of tasks
     * necessary to probably create a split pair, and try to clean up the broken state by
     * reparenting the split tasks back into the TaskDisplayArea.
     */
    @Nullable
    public static WindowContainerTransaction handleMalformedEnterTransition(
            @NonNull TransitionInfo info,
            @NonNull Function<ActivityManager.RunningTaskInfo, StageTaskListener> stageResolver) {
        // Extract the leaf split tasks in the transition
        final ArrayList<ActivityManager.RunningTaskInfo> leafSplitTasks = new ArrayList<>();
        final HashSet<StageTaskListener> parentStages = new HashSet<>();
        for (int i = 0; i < info.getChanges().size(); i++) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            final StageTaskListener stage = taskInfo != null
                    ? stageResolver.apply(taskInfo)
                    : null;
            if (stage == null || !isOpeningMode(change.getMode())) {
                // Is non-task or the task is not opening or related to split
                continue;
            }
            parentStages.add(stage);
            leafSplitTasks.add(taskInfo);
        }

        // In all cases, we consider an enter split transition malformed if it doesn't have tasks
        // for at least 2 stages
        final boolean isMalformedEnterTransition = !leafSplitTasks.isEmpty()
                && (parentStages.size() <= 1);
        if (!isMalformedEnterTransition) {
            return null;
        }

        // We have a malformed enter transition, restore the state for now by reparenting the task
        // back to the TDA.
        ProtoLog.d(WM_SHELL_SPLIT_SCREEN, "Malformed enter transition=%d detected",
                info.getDebugId());
        WindowContainerTransaction wct = new WindowContainerTransaction();
        for (int i = 0; i < leafSplitTasks.size(); i++) {
            wct.reparent(leafSplitTasks.get(i).token, null /* parent */,
                    true /* onTop */);
        }
        return wct;
    }
}
