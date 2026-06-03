/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.window.DesktopExperienceFlags.ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS;
import static android.window.DesktopExperienceFlags.ENABLE_MULTIPLE_DESKTOPS_BACKEND;

import static com.android.internal.protolog.common.LogLevel.VERBOSE;
import static com.android.server.wm.DesktopModeHelper.canEnterDesktopMode;
import static com.android.server.wm.LaunchParamsUtil.getPreferredLaunchTaskDisplayArea;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.window.DesktopExperienceFlags;
import android.window.DesktopModeFlags;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DesktopModeCompatPolicy;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.WmProtoLogGroups;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The class that defines default launch params for tasks in desktop mode
 */
class DesktopModeLaunchParamsModifier implements LaunchParamsModifier {
    private static final String ACTION_DRAG_DROP_VIEW =
            "org.chromium.chrome.browser.dragdrop.action.VIEW";
    private StringBuilder mLogBuilder;

    @NonNull
    private final Context mContext;
    @NonNull
    private final ActivityTaskSupervisor mSupervisor;

    @NonNull
    private final DesktopModeCompatPolicy mDesktopModeCompatPolicy;

    DesktopModeLaunchParamsModifier(@NonNull Context context,
            @NonNull ActivityTaskSupervisor supervisor,
            @NonNull DesktopModeCompatPolicy desktopCompatModePolicy) {
        mContext = context;
        mSupervisor = supervisor;
        mDesktopModeCompatPolicy = desktopCompatModePolicy;
    }

    @Override
    public int onCalculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            @NonNull LaunchParamsController.LaunchParams currentParams,
            @NonNull LaunchParamsController.LaunchParams outParams) {

        initLogBuilder(phase, task, activity);
        int result = calculate(task, layout, activity, source, options, request, phase,
                currentParams, outParams);
        outputLog();
        return result;
    }

    private static boolean isLaunchParamsLoggingEnabled() {
        return ProtoLog.isEnabled(WmProtoLogGroups.WM_DEBUG_TASKS_LAUNCH_PARAMS, VERBOSE);
    }

    private int calculate(@Nullable Task task, @Nullable ActivityInfo.WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request, int phase,
            @NonNull LaunchParamsController.LaunchParams currentParams,
            @NonNull LaunchParamsController.LaunchParams outParams) {

        if (!canEnterDesktopMode(mContext)) {
            appendLog("desktop mode is not enabled, skipping");
            return RESULT_SKIP;
        }

        // Determine the suggested display area to launch the activity/task.
        final TaskDisplayArea suggestedDisplayArea = getPreferredLaunchTaskDisplayArea(mSupervisor,
                task, options, source, currentParams, activity, request, this::appendLog);
        outParams.mPreferredTaskDisplayArea = suggestedDisplayArea;
        final DisplayContent display = suggestedDisplayArea.mDisplayContent;
        appendLog("display-id=" + display.getDisplayId()
                + " task-display-area-windowing-mode=" + suggestedDisplayArea.getWindowingMode()
                + " suggested-display-area=" + suggestedDisplayArea);

        boolean hasLaunchWindowingMode = false;
        final boolean inDesktopMode = suggestedDisplayArea.inFreeformWindowingMode()
                || suggestedDisplayArea.getTopMostVisibleFreeformActivity() != null;
        if (ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS.isTrue() && task == null
                && (isRequestingFreeformWindowMode(null, options, currentParams)
                || inDesktopMode)) {
            if (DesktopExperienceFlags.HANDLE_INCOMPATIBLE_TASKS_IN_DESKTOP_LAUNCH_PARAMS.isTrue()
                    && activity != null) {
                if (mDesktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
                        activity.mActivityComponent, activity.isNoDisplay(),
                        !activity.occludesParent(), /* numActivities */ 1, activity.mUserId,
                        activity.info, activity.windowType)) {
                    appendLog("activity exempt from desktop, launching in fullscreen");
                    outParams.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
                    return RESULT_DONE;
                }
            }
            if (options != null) {
                final int windowingMode = options.getLaunchWindowingMode();
                if (windowingMode == WINDOWING_MODE_FREEFORM) {
                    // Launching freeform in desktop but not ready to resolve bounds since task is
                    // null, return RESULT_DONE to prevent other modifiers from setting bounds.
                    outParams.mWindowingMode = windowingMode;
                    appendLog("launch-freeform");
                    return RESULT_DONE;
                }
            }
        }

        final int desktopFirstOverrideWindowingMode = getWindowingModeForDesktopFirstPolicy(task,
                source, options, suggestedDisplayArea, currentParams);
        if (desktopFirstOverrideWindowingMode != WINDOWING_MODE_UNDEFINED) {
            outParams.mWindowingMode = desktopFirstOverrideWindowingMode;
            if (task == null) {
                // Windowing mode is resolved by desktop-first policy but not ready to resolve
                // bounds since task is null, return RESULT_DONE to prevent other modifiers from
                // overwriting the params.
                return RESULT_DONE;
            }
            hasLaunchWindowingMode = true;
        }

        if (task == null || !task.isAttached()) {
            appendLog("task null, skipping");
            return RESULT_SKIP;
        }

        if (DesktopModeFlags.DISABLE_DESKTOP_LAUNCH_PARAMS_OUTSIDE_DESKTOP_BUG_FIX.isTrue()
                && !isEnteringDesktopMode(task, source, options, suggestedDisplayArea,
                currentParams)) {
            appendLog("not entering desktop mode, skipping");
            return RESULT_SKIP;
        }

        boolean requestFullscreen = options != null
                && options.getLaunchWindowingMode() == WINDOWING_MODE_FULLSCREEN;
        if (DesktopExperienceFlags.RESPECT_FULLSCREEN_ACTIVITY_OPTION_IN_DESKTOP_LAUNCH_PARAMS
                .isTrue() && requestFullscreen) {
            appendLog("respecting fullscreen activity option");
            outParams.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
            return RESULT_DONE;
        }

        // If activity is null attempt to use task top activity if available.
        final ActivityRecord targetActivity = activity != null ? activity
                : task.getTopMostActivity();

        if (DesktopExperienceFlags.HANDLE_INCOMPATIBLE_TASKS_IN_DESKTOP_LAUNCH_PARAMS.isTrue()
                && targetActivity != null) {
            final boolean isActivityStackTransparent = !task.forAllActivities(r ->
                    (r.occludesParent())) && !targetActivity.occludesParent();
            final AtomicInteger numActivities = new AtomicInteger(1);
            task.forAllActivities((r) -> {
                numActivities.incrementAndGet();
            });
            if (mDesktopModeCompatPolicy.isTopActivityExemptFromDesktopWindowing(
                    targetActivity.mActivityComponent, targetActivity.isNoDisplay(),
                    isActivityStackTransparent, numActivities.get(), task.getUserId(),
                    targetActivity.info, targetActivity.windowType)) {
                appendLog("activity exempt from desktop, launching in fullscreen");
                outParams.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
                return RESULT_DONE;
            }
        }

        final Task organizerTask = task.getCreatedByOrganizerTask();
        // If task is already launched, check if organizer task matches the target display.
        final boolean inDesktopFirstContainer = ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS.isTrue() && (
                suggestedDisplayArea.inFreeformWindowingMode() || (
                        ENABLE_MULTIPLE_DESKTOPS_BACKEND.isTrue() && organizerTask != null
                                && organizerTask.inFreeformWindowingMode()
                                && organizerTask.getDisplayId() == display.getDisplayId()));
        // In multiple desks, freeform tasks are always children of a root task controlled
        // by DesksOrganizer, so don't skip resolving freeform bounds.
        if (organizerTask != null && !inDesktopFirstContainer) {
            appendLog("has created-by-organizer-task, skipping");
            return RESULT_SKIP;
        }

        if (!task.isActivityTypeStandardOrUndefined()) {
            appendLog("not standard or undefined activity type, skipping");
            return RESULT_SKIP;
        }

        if (!DesktopExperienceFlags.IGNORE_CURRENT_PARAMS_IN_DESKTOP_LAUNCH_PARAMS.isTrue()) {
            // Copy over any values.
            outParams.set(currentParams);
            outParams.mPreferredTaskDisplayArea = suggestedDisplayArea;
            if (desktopFirstOverrideWindowingMode != WINDOWING_MODE_UNDEFINED) {
                outParams.mWindowingMode = desktopFirstOverrideWindowingMode;
            }
        }

        boolean isFullscreenInDeskTask = inDesktopFirstContainer && requestFullscreen;
        if (source != null && source.getTask() != null) {
            final Task sourceTask = source.getTask();
            // Don't explicitly set to freeform if task is launching in full-screen in desktop-first
            // container, as it should already inherit freeform by default if undefined.
            requestFullscreen |= task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
            isFullscreenInDeskTask = inDesktopFirstContainer && requestFullscreen;
            if (DesktopModeFlags.DISABLE_DESKTOP_LAUNCH_PARAMS_OUTSIDE_DESKTOP_BUG_FIX.isTrue()
                    && isEnteringDesktopMode(sourceTask, source, options, suggestedDisplayArea,
                    currentParams)
                    && !isFullscreenInDeskTask) {
                // If trampoline source is not freeform but we are entering or in desktop mode,
                // ignore the source windowing mode and set the windowing mode to freeform.
                outParams.mWindowingMode = WINDOWING_MODE_FREEFORM;
                appendLog("freeform window mode applied to task trampoline");
                if (persisitSourceBoundsForTabTearingTrampoline(source, activity)) {
                    outParams.mBounds.set(sourceTask.getBounds());
                    return RESULT_DONE;
                }
            } else {
                // In Proto2, trampoline task launches of an existing background task can result in
                // the previous windowing mode to be restored even if the desktop mode state has
                // changed. Let task launches inherit the windowing mode from the source task if
                // available, which should have the desired windowing mode set by WM Shell.
                // See b/286929122.
                outParams.mWindowingMode = sourceTask.getWindowingMode();
                appendLog("inherit-from-source=" + outParams.mWindowingMode);
            }
            hasLaunchWindowingMode = true;
        }

        if (isFullscreenInDeskTask) {
            // Return early to prevent freeform bounds always being set in multi-desk mode for
            // fullscreen tasks. Tasks should inherit from parent bounds.
            appendLog("launch-in-fullscreen");
            return RESULT_DONE;
        }

        if (phase == PHASE_WINDOWING_MODE) {
            if (ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS.isTrue()) {
                return RESULT_DONE;
            }
            return RESULT_CONTINUE;
        }

        if (!currentParams.mBounds.isEmpty() && !inDesktopMode
                && !DesktopExperienceFlags.IGNORE_CURRENT_PARAMS_IN_DESKTOP_LAUNCH_PARAMS.isTrue()
        ) {
            appendLog("currentParams has bounds set, not overriding");
            return RESULT_SKIP;
        }

        if ((options == null || options.getLaunchBounds() == null) && task.hasOverrideBounds()) {
            if (DesktopModeFlags.DISABLE_DESKTOP_LAUNCH_PARAMS_OUTSIDE_DESKTOP_BUG_FIX.isTrue()) {
                final Rect overrideTaskBounds = task.getRequestedOverrideBounds();
                if (DesktopExperienceFlags.IGNORE_OVERRIDE_TASK_BOUNDS_IF_INCOMPATIBLE_WITH_DISPLAY
                        .isTrue() && areTaskBoundsValidForDisplay(overrideTaskBounds, display)) {
                    // We are in desktop, return result done to prevent other modifiers from
                    // modifying exiting task bounds or resolved windowing mode.
                    if (ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS.isTrue()) {
                        outParams.mBounds.set(overrideTaskBounds);
                    }
                    appendLog("task-has-override-bounds=%s", overrideTaskBounds);
                    return RESULT_DONE;
                }
            } else {
                appendLog("current task has bounds set, not overriding");
                return RESULT_SKIP;
            }
        }

        if (DesktopModeFlags.INHERIT_TASK_BOUNDS_FOR_TRAMPOLINE_TASK_LAUNCHES.isTrue()) {
            ActivityRecord topVisibleFreeformActivity =
                    task.getDisplayContent().getTopMostVisibleFreeformActivity();
            final Rect inheritedBounds = getInheritedExistingTaskBounds(source,
                    topVisibleFreeformActivity, targetActivity, task);
            if (inheritedBounds != null) {
                appendLog("inheriting bounds from existing closing instance");
                outParams.mBounds.set(inheritedBounds);
                appendLog("final desktop mode task bounds set to %s", outParams.mBounds);
                // Return result done to prevent other modifiers from changing or cascading bounds.
                return RESULT_DONE;
            }
        }

        DesktopModeBoundsCalculator.updateInitialBounds(task, layout, targetActivity, options,
                display, outParams, this::appendLog);
        appendLog("final desktop mode task bounds set to %s", outParams.mBounds);
        if (options != null && options.getFlexibleLaunchSize()) {
            // Return result done to prevent other modifiers from respecting option bounds and
            // applying further cascading. Since other modifiers are being skipped in this case,
            // this modifier is now also responsible to respecting the options launch windowing
            // mode.
            outParams.mWindowingMode = options.getLaunchWindowingMode();
            appendLog("inherit-options=" + options.getLaunchWindowingMode());
            return RESULT_DONE;
        }
        if (ENABLE_FREEFORM_DISPLAY_LAUNCH_PARAMS.isTrue() && hasLaunchWindowingMode) {
            return RESULT_DONE;
        }
        return RESULT_CONTINUE;
    }

    /**
     * Returns true if a task is entering desktop mode, due to its windowing mode being freeform or
     * if there exists other freeform tasks on the display.
     */
    @VisibleForTesting
    boolean isEnteringDesktopMode(
            @NonNull Task task,
            @Nullable ActivityRecord source,
            @Nullable ActivityOptions options,
            @NonNull TaskDisplayArea taskDisplayArea,
            @NonNull LaunchParamsController.LaunchParams currentParams) {
        if (isRequestingFreeformWindowMode(task, options, currentParams)) {
            // It's launching in freeform without any modifications.
            return true;
        }

        if (!checkSourceWindowModesCompatible(task, options, currentParams)) {
            // It's launching in incompatible mode.
            return false;
        }

        if (getWindowingModeForDesktopFirstPolicy(task, source, options, taskDisplayArea,
                currentParams) == WINDOWING_MODE_FREEFORM) {
            // It's a target of desktop-first policy.
            return true;
        }

        if (DesktopExperienceFlags.ENABLE_DESKTOP_FIRST_POLICY_IN_LPM.isTrue()
                && taskDisplayArea.inFreeformWindowingMode()) {
            // The display is in desktop-first mode but non-freeform mode is requested.
            return false;
        }

        //  As freeform tasks cannot exist outside of desktop mode, it is safe to assume if
        //  freeform tasks are visible we are in desktop mode and as a result any launching
        //  activity will also enter desktop mode. On this same relationship, we can also assume
        //  if there are not visible freeform tasks but a freeform activity is now launching, it
        //  will force the device into desktop mode.
        final Task visibleFreeformTask = task.getDisplayContent().getTask(
                t -> t.inFreeformWindowingMode() && t.isVisibleRequested());
        return visibleFreeformTask != null;
    }

    private boolean isRequestingFreeformWindowMode(
            @Nullable Task task,
            @Nullable ActivityOptions options,
            @NonNull LaunchParamsController.LaunchParams currentParams) {
        return (task != null && task.inFreeformWindowingMode())
                || (options != null && options.getLaunchWindowingMode() == WINDOWING_MODE_FREEFORM)
                || (currentParams.mWindowingMode == WINDOWING_MODE_FREEFORM
                && !DesktopExperienceFlags.IGNORE_CURRENT_PARAMS_IN_DESKTOP_LAUNCH_PARAMS.isTrue());
    }

    /**
     * Returns the override windowing mode according to the desktop-first policy. Returns
     * WINDOWING_MODE_UNDEFINED if the policy is not applied.
     */
    private int getWindowingModeForDesktopFirstPolicy(
            @Nullable Task task,
            @Nullable ActivityRecord source,
            @Nullable ActivityOptions options,
            @NonNull TaskDisplayArea taskDisplayArea,
            @NonNull LaunchParamsController.LaunchParams currentParams) {
        if (!DesktopExperienceFlags.ENABLE_DESKTOP_FIRST_POLICY_IN_LPM.isTrue()) {
            return WINDOWING_MODE_UNDEFINED;
        }

        if (!taskDisplayArea.inFreeformWindowingMode()) {
            // The display is in touch-first mode.
            return WINDOWING_MODE_UNDEFINED;
        }

        if (!checkSourceWindowModesCompatible(task, options, currentParams)) {
            // The task is launching in incompatible mode (e.g., PIP).
            appendLog("desktop-first-but-incompatible-mode");
            return WINDOWING_MODE_UNDEFINED;
        }

        final boolean hasLaunchWindowingModeOption = options != null
                && options.getLaunchWindowingMode() != WINDOWING_MODE_UNDEFINED;
        if (hasLaunchWindowingModeOption) {
            // ActivityOptions comes first.
            appendLog("desktop-first-but-has-launch-windowing-mode-options");
            return options.getLaunchWindowingMode();
        }

        final boolean isFullscreenRelaunch = source != null && source.getTask() != null
                && source.getTask().getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && source.getTask() == task;
        if (isFullscreenRelaunch) {
            // Fullscreen relaunch is not a target of desktop-first policy.
            appendLog("desktop-first-but-fullscreen-relaunch");
            return WINDOWING_MODE_FULLSCREEN;
        }

        if (DesktopExperienceFlags.ENABLE_DESKTOP_FIRST_TOP_FULLSCREEN_BUGFIX.isTrue()) {
            final Task launchRootCandidate = taskDisplayArea.getLaunchRootTask(
                    WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD,
                    /* options= */ null, /* sourceTask= */ null, /* launchFlags= */ 0
            );
            final boolean isAnyDeskActive = launchRootCandidate != null
                    && launchRootCandidate.mCreatedByOrganizer
                    && launchRootCandidate.getWindowingMode() == WINDOWING_MODE_FREEFORM;
            final boolean isHomeVisible = taskDisplayArea.getDisplayContent()
                    .getTask(t -> t.isActivityTypeHome() && t.isVisibleRequested()) != null;
            if (!isAnyDeskActive && !isHomeVisible) {
                appendLog("desktop-first-but-fullscreen-visible");
                return WINDOWING_MODE_FULLSCREEN;
            }
        }

        appendLog("forced-freeform-in-desktop-first");
        return WINDOWING_MODE_FREEFORM;
    }

    /**
     * Returns true is all possible source window modes are compatible with desktop mode.
     */
    private boolean checkSourceWindowModesCompatible(
            @Nullable Task task,
            @Nullable ActivityOptions options,
            @NonNull LaunchParamsController.LaunchParams currentParams) {
        // 1. Check the task's own windowing mode.
        final boolean isTaskWindowModeCompatible = task == null
                || isCompatibleDesktopWindowingMode(task.getWindowingMode());
        // 2. Check the windowing mode from ActivityOptions, if they exist.
        // If options are null, we consider it compatible.
        final boolean isOptionsWindowModeCompatible = options == null
                || isCompatibleDesktopWindowingMode(options.getLaunchWindowingMode());
        // 3. Check the windowing mode from the current launch parameters.
        // This check can be skipped if the IGNORE_CURRENT_PARAMS_IN_DESKTOP_LAUNCH_PARAMS flag is
        // true.
        final boolean isCurrentParamsWindowModeCompatible =
                isCompatibleDesktopWindowingMode(currentParams.mWindowingMode)
                        || DesktopExperienceFlags.IGNORE_CURRENT_PARAMS_IN_DESKTOP_LAUNCH_PARAMS
                        .isTrue();
        // All checks must pass for the source window modes to be considered compatible.
        return isTaskWindowModeCompatible && isOptionsWindowModeCompatible
                && isCurrentParamsWindowModeCompatible;
    }

    /**
     * Returns true is the requesting window mode is one that can lead to the activity entering
     * desktop.
     */
    private boolean isCompatibleDesktopWindowingMode(
            @WindowConfiguration.WindowingMode int windowingMode) {
        return switch (windowingMode) {
            case WINDOWING_MODE_UNDEFINED,
                 WINDOWING_MODE_FULLSCREEN,
                 WINDOWING_MODE_FREEFORM -> true;
            default -> false;
        };
    }

    /**
     * Returns true if the given bounds are within the stables bounds of a given display.
     */
    private boolean areTaskBoundsValidForDisplay(@NonNull Rect taskBounds,
            @NonNull DisplayContent displayContent) {
        final Rect displayStableBounds = new Rect();
        displayContent.getStableRect(displayStableBounds);
        return displayStableBounds.contains(taskBounds);
    }

    /**
     * Return the bounds of an existing closing instance the launching task should inherit..
     */
    private Rect getInheritedExistingTaskBounds(
            @Nullable ActivityRecord sourceTaskActivity,
            @Nullable ActivityRecord existingVisibleTaskActivity,
            @Nullable ActivityRecord launchingActivity,
            @NonNull Task launchingTask) {
        if (launchingActivity == null) return null;
        if (sourceTaskActivity != null && shouldInheritExistingTaskBounds(sourceTaskActivity,
                launchingActivity, launchingTask)) {
            return sourceTaskActivity.getBounds();
        }
        if (existingVisibleTaskActivity != null && shouldInheritExistingTaskBounds(
                existingVisibleTaskActivity, launchingActivity, launchingTask)) {
            return existingVisibleTaskActivity.getBounds();
        }
        return null;
    }

    /**
     * Whether the launching task should inherit the task bounds of the given activity.
     */
    private boolean shouldInheritExistingTaskBounds(
            @NonNull ActivityRecord activityToCheck,
            @NonNull ActivityRecord launchingActivity,
            @NonNull Task launchingTask) {
        return (Objects.equals(activityToCheck.packageName, launchingActivity.packageName)
                && (activityToCheck.mUserId == launchingTask.mUserId)
                && activityToCheck.getTask().mTaskId != launchingTask.mTaskId
                // Safe to inherit activity bounds if activity is no longer visible or will be
                // closing as in either case there is not worry of content overlapping and being
                // obscured.
                && (!activityToCheck.isVisible()
                    || (isLaunchingNewSingleTask(launchingActivity.launchMode)
                        && isClosingExitingInstance(launchingTask.getBaseIntent().getFlags()))));
    }

    /**
     * Returns true if the bounds should be persisted from the source activity for
     * tab tearing trampoline launches.
     */
    private boolean persisitSourceBoundsForTabTearingTrampoline(
            @NonNull ActivityRecord source,
            @Nullable ActivityRecord activity) {
        return DesktopExperienceFlags.ENABLE_INTERACTION_DEPENDENT_TAB_TEARING_BOUNDS.isTrue()
                && activity != null
                && source.isNoDisplay()
                && source.packageName.equals(activity.packageName)
                && isChromiumDragAndDropAction(activity);
    }

    /**
     * Returns true if the given activity action is a chromium drag and drop.
     */
    private boolean isChromiumDragAndDropAction(@NonNull ActivityRecord activity) {
        return ACTION_DRAG_DROP_VIEW.equals(activity.intent.getAction());
    }

    /**
     * Returns true if the launch mode will result in a single new task being created for the
     * activity.
     */
    private boolean isLaunchingNewSingleTask(int launchMode) {
        return launchMode == LAUNCH_SINGLE_TASK
                || launchMode == LAUNCH_SINGLE_INSTANCE
                || launchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK;
    }

    /**
     * Returns true if the intent will result in an existing task instance being closed if a new
     * one appears.
     */
    private boolean isClosingExitingInstance(int intentFlags) {
        return (intentFlags & FLAG_ACTIVITY_CLEAR_TASK) != 0
                || (intentFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0;
    }

    private void initLogBuilder(int phase, Task task, ActivityRecord activity) {
        if (!isLaunchParamsLoggingEnabled()) {
            mLogBuilder = null;
            return;
        }
        mLogBuilder = new StringBuilder("DesktopModeLaunchParamsModifier: phase= " + phase
                + " task=" + task + " activity=" + activity);
    }

    private void appendLog(String format, Object... args) {
        if (mLogBuilder == null) return;
        mLogBuilder.append(" ").append(String.format(format, args));
    }

    private void outputLog() {
        if (mLogBuilder == null) return;
        ProtoLog.v(WmProtoLogGroups.WM_DEBUG_TASKS_LAUNCH_PARAMS, "%s", mLogBuilder.toString());
    }
}
