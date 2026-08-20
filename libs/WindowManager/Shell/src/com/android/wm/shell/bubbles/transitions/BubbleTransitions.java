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

package com.android.wm.shell.bubbles.transitions;

import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.View.INVISIBLE;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.bubbles.util.BubbleUtils.getEnterBubbleTransaction;
import static com.android.wm.shell.bubbles.util.BubbleUtils.getExitBubbleTransaction;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BUBBLES;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BUBBLES_NOISY;
import static com.android.wm.shell.shared.TransitionUtil.isOpeningMode;
import static com.android.wm.shell.transition.Transitions.TRANSIT_BUBBLE_CONVERT_FLOATING_TO_BAR;
import static com.android.wm.shell.transition.Transitions.TRANSIT_CONVERT_TO_BUBBLE;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.TaskInfo;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewRootImpl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowAnimationState;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.animation.Animator;
import androidx.core.animation.Animator.AnimatorUpdateListener;
import androidx.core.animation.AnimatorListenerAdapter;
import androidx.core.animation.ValueAnimator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.launcher3.icons.BubbleIconFactory;
import com.android.wm.shell.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleExpandedView;
import com.android.wm.shell.bubbles.BubbleExpandedViewManager;
import com.android.wm.shell.bubbles.BubbleExpandedViewTransitionAnimator;
import com.android.wm.shell.bubbles.BubbleHelper;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.BubbleStackView;
import com.android.wm.shell.bubbles.BubbleTaskViewFactory;
import com.android.wm.shell.bubbles.BubbleViewInfoTask;
import com.android.wm.shell.bubbles.BubbleViewProvider;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.bubbles.bar.BubbleBarExpandedView;
import com.android.wm.shell.bubbles.bar.BubbleBarLayerView;
import com.android.wm.shell.common.HomeIntentProvider;
import com.android.wm.shell.shared.bubbles.BubbleBarLocation;
import com.android.wm.shell.shared.bubbles.BubbleFlagHelper;
import com.android.wm.shell.shared.bubbles.logging.BubbleLog;
import com.android.wm.shell.taskview.TaskView;
import com.android.wm.shell.taskview.TaskViewRepository;
import com.android.wm.shell.taskview.TaskViewTaskController;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.AnimationPlan;
import com.android.wm.shell.transition.DetachResult;
import com.android.wm.shell.transition.ITransitionAnimation;
import com.android.wm.shell.transition.ITransitionPlanner;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.transition.Transitions.TransitionHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Implements transition coordination for bubble operations.
 */
public class BubbleTransitions {
    private static final String TAG = "BubbleTransitions";

    /**
     * Multiplier used to convert a view elevation to an "equivalent" shadow-radius. This is the
     * same multiple used by skia and surface-outsets in WMS.
     */
    private static final float ELEVATION_TO_RADIUS = 2;

    @NonNull final Transitions mTransitions;
    @NonNull final ShellTaskOrganizer mTaskOrganizer;
    @NonNull final TaskViewRepository mRepository;
    @NonNull final Executor mMainExecutor;
    @NonNull final BubbleData mBubbleData;
    @NonNull public final TaskViewTransitions mTaskViewTransitions;
    @NonNull final Context mContext;
    @NonNull final BubbleViewInfoTask.Factory mBubbleViewInfoTaskFactory;
    @NonNull final BubbleHelper mBubbleHelper;
    @NonNull final BubbleTransitionsPlanner mBubbleTransitionsPlanner;

    @VisibleForTesting
    // Map of a launch cookie (used to start an activity) to the associated transition handler
    final Map<IBinder, TransitionHandler> mPendingEnterTransitions =
            new HashMap<>();

    @VisibleForTesting
    // Map of a running transition token to the associated transition handler
    final Map<IBinder, TransitionHandler> mEnterTransitions =
            new HashMap<>();

    private BubbleController mBubbleController;

    public BubbleTransitions(Context context,
            @NonNull Transitions transitions, @NonNull ShellTaskOrganizer organizer,
            @NonNull TaskViewRepository repository, @NonNull BubbleData bubbleData,
            @NonNull TaskViewTransitions taskViewTransitions,
            @NonNull BubbleViewInfoTask.Factory bubbleViewInfoTaskFactory,
            @NonNull BubbleHelper bubbleHelper) {
        mTransitions = transitions;
        mTaskOrganizer = organizer;
        mRepository = repository;
        mMainExecutor = transitions.getMainExecutor();
        mBubbleData = bubbleData;
        mTaskViewTransitions = taskViewTransitions;
        mContext = context;
        mBubbleViewInfoTaskFactory = bubbleViewInfoTaskFactory;
        mBubbleHelper = bubbleHelper;
        mBubbleTransitionsPlanner = new BubbleTransitionsPlanner(transitions, this);
        if (BubbleFlagHelper.isBubbleTransitionPlannerEnabled()) {
            transitions.addPlanner(mBubbleTransitionsPlanner);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setBubbleController(BubbleController controller) {
        mBubbleController = controller;
    }

    /**
     * Returns whether there is a pending transition for the given request.
     */
    public boolean hasPendingEnterTransition(@NonNull TransitionRequestInfo info) {
        if (info.getTriggerTask() == null) {
            return false;
        }
        for (IBinder cookie : info.getTriggerTask().launchCookies) {
            if (mPendingEnterTransitions.containsKey(cookie)) {
                return true;
            }
        }
        return false;
    }

    /**
     * This is called to "convert" a pending enter transition into an active/running transition.
     * It is also only called after we've confirmed that this is a valid transition into a bubble,
     * ie. `hasPendingEnterTransition()` has been called.
     */
    @NonNull
    public TransitionHandler storePendingEnterTransition(IBinder transition,
            TransitionRequestInfo info) throws IllegalStateException {
        for (IBinder cookie : info.getTriggerTask().launchCookies) {
            final TransitionHandler handler = mPendingEnterTransitions.remove(cookie);
            if (handler != null) {
                ProtoLog.d(WM_SHELL_BUBBLES, "Transferring pending to playing transition for"
                        + " cookie=%s", cookie);
                mPendingEnterTransitions.remove(cookie);
                mEnterTransitions.put(transition, handler);
                return handler;
            }
        }
        throw new IllegalStateException("Expected pending enter transition for the given request");
    }

    /**
     * Returns the transition handler for the given `transition`, only non-null if called after
     * `storePendingEnterTransition()` (which may not be the case if the transition is consumed).
     */
    @Nullable
    public TransitionHandler getRunningEnterTransition(@NonNull IBinder transition)
            throws IllegalStateException {
        if (mEnterTransitions.containsKey(transition)) {
            return mEnterTransitions.get(transition);
        }
        return null;
    }

    /** Notifies when the unfold transition is starting. */
    public void notifyUnfoldTransitionStarting(@NonNull IBinder transition) {
        // this is used to block task view transitions from coming in while the unfold transition is
        // playing, and allows us to create a specific transition and merge it into unfold. for now
        // we only do this when switching from floating bubbles to bar bubbles so guard this with
        // the bubble bar flag, but once these are combined we should be able to remove this.
        if (com.android.wm.shell.Flags.enableBubbleBar()
                && mBubbleData.getSelectedBubble() instanceof Bubble
                && mBubbleData.isExpanded()) {
            BubbleLog.d("BubbleTransitions.notifyUnfoldTransitionStarting() transition=%s",
                    transition);
            Bubble bubble = (Bubble) mBubbleData.getSelectedBubble();
            mTaskViewTransitions.enqueueRunningExternal(bubble.getTaskView().getController(),
                    transition);
        }
    }

    /**
     * Handles a startTransition request and amend the necessary operations to the given wct.
     */
    @Nullable
    public Transitions.RequestResult handleRequestOnly(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        final TransitionHandler transitionHandler = mEnterTransitions.get(transition);
        Transitions.RequestResult result = null;
        if (BubbleFlagHelper.enableRootTaskForBubble() && transitionHandler != null) {
            result =  transitionHandler.handleRequestOnly(transition, request);
            if (result != null) {
                wct.merge(result.mWct, true /* transfer */);
            }
        }
        return result;
    }

    /** Notifies when the unfold transition has finished. */
    public void notifyUnfoldTransitionFinished(@NonNull IBinder transition) {
        if (com.android.wm.shell.Flags.enableBubbleBar()) {
            BubbleLog.d("BubbleTransitions.notifyUnfoldTransitionFinished() transition=%s",
                    transition);
            mTaskViewTransitions.onExternalDone(transition);
        }
    }

    /**
     * Starts a new launch or convert transition to show the given bubble.
     */
    public BubbleTransition startLaunchIntoOrConvertToBubble(Bubble bubble,
            BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
            BubblePositioner positioner, BubbleStackView stackView,
            BubbleBarLayerView layerView, BubbleIconFactory iconFactory,
            boolean inflateSync, @Nullable BubbleBarLocation bubbleBarLocation) {
        return new LaunchOrConvertToBubble(bubble, mContext, expandedViewManager, factory,
                positioner, stackView, layerView, iconFactory, inflateSync, bubbleBarLocation);
    }

    /**
     * Expands and selects the given task in a bubble when there is already a running transition
     * for it.
     */
    public void startExpandAndSelectBubbleForExistingTransition(
            @NonNull IBinder transition, @NonNull ActivityManager.RunningTaskInfo launchingTask,
            @NonNull Consumer<TransitionHandler> onInflatedCallback) {
        final TransitionHandler handler =
                mBubbleController.expandStackAndSelectBubbleForExistingTransition(
                        launchingTask, transition, onInflatedCallback);
        mEnterTransitions.put(transition, handler);
    }

    /**
     * Initiates a Task Trampoline Bubble launch for the given transition.
     */
    public void startTaskTrampolineBubbleLaunch(@NonNull IBinder transition,
            @NonNull ActivityManager.RunningTaskInfo openingTask,
            @NonNull ActivityManager.RunningTaskInfo closingTask,
            @NonNull Consumer<TransitionHandler> onInflatedCallback) {
        final TransitionHandler handler =
                mBubbleController.jumpcutBubbleSwitchTransition(openingTask, closingTask,
                        transition, onInflatedCallback);
        mEnterTransitions.put(transition, handler);
    }

    /**
     * Starts a new launch or convert transition to show the given bubble.
     */
    public TransitionHandler startLaunchNewTaskBubbleForExistingTransition(Bubble bubble,
            BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
            BubbleStackView stackView, BubbleBarLayerView layerView, BubbleIconFactory iconFactory,
            boolean inflateSync, IBinder transition,
            Consumer<TransitionHandler> onInflatedCallback) {
        return new LaunchNewTaskBubbleForExistingTransition(bubble, mContext, expandedViewManager,
                factory, stackView, layerView, iconFactory, inflateSync, transition,
                onInflatedCallback);
    }

    /**
     * Starts a jumpcut transition to update Task in the expanding Bubble.
     */
    public TransitionHandler startJumpcutBubbleSwitchTransition(Bubble openingBubble,
            Bubble closingBubble, BubbleExpandedViewManager expandedViewManager,
            BubbleTaskViewFactory factory, BubbleStackView stackView,
            BubbleBarLayerView layerView, BubbleIconFactory iconFactory, boolean inflateSync,
            IBinder transition, Consumer<TransitionHandler> onInflatedCallback) {
        return new JumpcutBubbleSwitchTransition(openingBubble, closingBubble, mContext,
                expandedViewManager, factory, stackView, layerView, iconFactory, inflateSync,
                transition, onInflatedCallback);
    }

    /**
     * Starts a convert-to-bubble transition.
     *
     * @see ConvertToBubble
     */
    public BubbleTransition startConvertToBubble(Bubble bubble, TaskInfo taskInfo,
            BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
            BubblePositioner positioner, BubbleStackView stackView, BubbleBarLayerView layerView,
            BubbleIconFactory iconFactory, HomeIntentProvider homeIntentProvider, DragData dragData,
            boolean inflateSync) {
        return new ConvertToBubble(bubble, taskInfo, mContext, expandedViewManager, factory,
                positioner, stackView, layerView, iconFactory, homeIntentProvider, dragData,
                inflateSync);
    }

    /**
     * Starts a convert-from-bubble transition.
     *
     * @see ConvertFromBubble
     */
    public BubbleTransition startConvertFromBubble(Bubble bubble,
            TaskInfo taskInfo) {
        return new ConvertFromBubble(bubble, taskInfo);
    }

    /** Starts a transition that converts a floating expanded bubble to a bar bubble. */
    public BubbleTransition startFloatingToBarConversion(Bubble bubble,
            BubblePositioner positioner) {
        return new FloatingToBarConversion(bubble, positioner);
    }

    /** Starts a transition that converts an expanded bubble bar bubble to floating. */
    public BubbleTransition startBarToFloatingConversion(Bubble bubble,
            BubblePositioner positioner) {
        return new BarToFloatingConversion(bubble, positioner);
    }

    /** Starts a transition that converts a dragged bubble icon to a full screen task. */
    public BubbleTransition startDraggedBubbleIconToFullscreen(Bubble bubble, Point dropLocation) {
        return new DraggedBubbleIconToFullscreen(bubble, dropLocation);
    }

    /**
     * Whether the transition contains any Task that is changed from expanded App Bubbled to
     * non-Bubbled.
     */
    public boolean containsExpandedBubbledTaskNoLongerBubbled(@NonNull TransitionInfo info) {
        if (!mBubbleData.isExpanded() || mBubbleData.getSelectedBubble() == null) {
            // No expanded.
            return false;
        }
        if (!(mBubbleData.getSelectedBubble() instanceof Bubble bubble) || !bubble.isApp()) {
            // Not app Bubble.
            return false;
        }
        final int expandedTaskId = bubble.getTaskId();
        for (int i = 0; i < info.getChanges().size(); i++) {
            final TransitionInfo.Change chg = info.getChanges().get(i);
            final ActivityManager.RunningTaskInfo taskInfo = chg.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId != expandedTaskId) {
                continue;
            }
            // Check whether it is still an app bubble.
            return !mBubbleHelper.isAppBubbleTask(taskInfo);
        }
        return false;
    }

    /**
     * After a handler was chosen, there is a chance that the handler cannot actually animate the
     * transition when it is ready to play {@link TransitionHandler#startAnimation}.
     *
     * Returns whether the pending handler can animate the given transition.
     */
    public boolean canAnimateTransition(@NonNull IBinder transition,
            @NonNull TransitionInfo info) {
        final Transitions.TransitionHandler handler = getRunningEnterTransition(transition);
        if (handler instanceof BubbleTransition bubbleTransition) {
            return bubbleTransition.canAnimateTransition(info);
        }
        // Fallback
        return true;
    }

    /**
     * Plucks the task-surface out of an ancestor view while making the view invisible. This helper
     * attempts to do this seamlessly (ie. view becomes invisible in sync with task reparent).
     */
    private void pluck(SurfaceControl taskLeash, View fromView, SurfaceControl dest,
            float destX, float destY, float cornerRadius, SurfaceControl.Transaction t,
            Runnable onPlucked) {
        SurfaceControl.Transaction pluckT = new SurfaceControl.Transaction();
        pluckT.reparent(taskLeash, dest);
        t.reparent(taskLeash, dest);
        pluckT.setPosition(taskLeash, destX, destY);
        t.setPosition(taskLeash, destX, destY);
        pluckT.show(taskLeash);
        pluckT.setAlpha(taskLeash, 1.f);
        float shadowRadius = fromView.getElevation() * ELEVATION_TO_RADIUS;
        pluckT.setShadowRadius(taskLeash, shadowRadius);
        pluckT.setCornerRadius(taskLeash, cornerRadius);
        t.setShadowRadius(taskLeash, shadowRadius);
        t.setCornerRadius(taskLeash, cornerRadius);

        // Need to remove the taskview AFTER applying the startTransaction because it isn't
        // synchronized.
        pluckT.addTransactionCommittedListener(mMainExecutor, onPlucked::run);
        fromView.getViewRootImpl().applyTransactionOnDraw(pluckT);
        fromView.setVisibility(INVISIBLE);
    }

    /**
     * Interface to a bubble-specific transition. Bubble transitions have a multi-step lifecycle
     * in order to coordinate with the bubble view logic. These steps are communicated on this
     * interface.
     */
    public interface BubbleTransition {
        /** Notifies this transition that the task view surface was created. */
        default void surfaceCreated() {}
        /** Notifies this transition that it can continue to expand the bubble. */
        default void continueExpand() {}
        /** Notifies that this transition should be skipped. */
        default void skip() {}
        /** Notifies this transition that it can continue to collapse the bubble. */
        default void continueCollapse() {}
        /** Called when the given Bubble's expanded TaskView has bounds changed. */
        default void onTaskViewBoundsChanged(Bubble bubble) {}
        /** Continues the conversion transition. */
        default void continueConvert() {}
        /** Merge this transition with the unfold transition. */
        default void mergeWithUnfold(
                SurfaceControl taskLeash, SurfaceControl.Transaction finishT) {}
        /** Whether the selected transition can actually animate the given transition. */
        default boolean canAnimateTransition(@NonNull TransitionInfo info) {
            return true;
        }
        /** Whether this transition is for converting a floating bubble to a bubble bar bubble. */
        default boolean isConvertingBubbleToBar() {
            return (this instanceof FloatingToBarConversion);
        }
        default boolean isConvertingBubbleToFloating() {
            return (this instanceof BarToFloatingConversion);
        }
        /** Whether this transition is for switching from one bubble to another using jumpcut. */
        default boolean isJumpcutBubbleSwitching() {
            return (this instanceof JumpcutBubbleSwitchTransition);
        }
    }

    /**
     * Information about the task when it is being dragged to a bubble.
     */
    public static class DragData {
        private final boolean mReleasedOnLeft;
        private final float mTaskScale;
        private final float mCornerRadius;
        private final PointF mDragPosition;

        /**
         * @param releasedOnLeft true if the bubble was released in the left drop target
         * @param taskScale      the scale of the task when it was dragged to bubble
         * @param cornerRadius   the corner radius of the task when it was dragged to bubble
         * @param dragPosition   the position of the task when it was dragged to bubble
         */
        public DragData(boolean releasedOnLeft, float taskScale, float cornerRadius,
                @Nullable PointF dragPosition) {
            mReleasedOnLeft = releasedOnLeft;
            mTaskScale = taskScale;
            mCornerRadius = cornerRadius;
            mDragPosition = dragPosition != null ? dragPosition : new PointF(0, 0);
        }

        /**
         * @return true if the bubble was released in the left drop target
         */
        public boolean isReleasedOnLeft() {
            return mReleasedOnLeft;
        }

        /**
         * @return the scale of the task when it was dragged to bubble
         */
        public float getTaskScale() {
            return mTaskScale;
        }

        /**
         * @return the corner radius of the task when it was dragged to bubble
         */
        public float getCornerRadius() {
            return mCornerRadius;
        }

        /**
         * @return position of the task when it was dragged to bubble
         */
        public PointF getDragPosition() {
            return mDragPosition;
        }
    }

    /**
     * Keeps track of internal state of different steps of a BubbleTransition. Serves as a gating
     * mechanism to block animations or updates until necessary states are set.
     */
    private static class TransitionProgress {
        private boolean mTransitionReady;
        private boolean mInflated;
        private boolean mSurfaceReady;

        void setInflated() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "TransitionProgress.setInflated()");
            mInflated = true;
        }

        void setTransitionReady() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "TransitionProgress.setTransitionReady()");
            mTransitionReady = true;
        }

        void setReadyToExpand() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "TransitionProgress.setReadyToExpand()");
        }

        void setSurfaceReady() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "TransitionProgress.setSurfaceReady()");
            mSurfaceReady = true;
        }

        boolean isReadyToAnimate() {
            // Animation only depends on transition and surface state
            return mTransitionReady && mSurfaceReady && mInflated;
        }
    }

    /**
     * Starts a new bubble for an existing playing transition.
     * TODO(b/408328557): To be consolidated with LaunchOrConvertToBubble and ConvertToBubble
     */
    @VisibleForTesting
    class LaunchNewTaskBubbleForExistingTransition implements TransitionHandler, BubbleTransition {
        final BubbleExpandedViewTransitionAnimator mExpandedViewAnimator;
        private final TransitionProgress mTransitionProgress;
        Bubble mBubble;
        IBinder mTransition;
        private Transitions.TransitionFinishCallback mFinishCb;
        private WindowContainerTransaction mFinishWct;
        final Rect mStartBounds = new Rect();
        SurfaceControl mSnapshot = null;
        // The task info is resolved once we find the task from the transition info using the
        // pending launch cookie otherwise
        @Nullable
        TaskInfo mTaskInfo;
        BubbleViewProvider mPriorBubble = null;
        // Whether we should play the convert-task animation, or the launch-task animation
        private boolean mPlayConvertTaskAnimation;

        private SurfaceControl.Transaction mFinishT;
        private SurfaceControl mTaskLeash;

        LaunchNewTaskBubbleForExistingTransition(Bubble bubble, Context context,
                BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
                BubbleStackView stackView, BubbleBarLayerView layerView,
                BubbleIconFactory iconFactory, boolean inflateSync, IBinder transition,
                Consumer<TransitionHandler> onInflatedCallback) {
            if (layerView != null) {
                mExpandedViewAnimator = layerView;
            } else {
                mExpandedViewAnimator = stackView;
            }
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition() expanded=%b",
                    mExpandedViewAnimator.isExpanded());
            mBubble = bubble;
            mTransition = transition;
            mTransitionProgress = new TransitionProgress();
            mBubble.setInflateSynchronously(inflateSync);
            mBubble.setCurrentTransition(this);
            mBubble.inflate(
                    b -> {
                        onInflated(b);
                        onInflatedCallback.accept(LaunchNewTaskBubbleForExistingTransition.this);
                    },
                    context,
                    expandedViewManager,
                    factory,
                    stackView,
                    layerView,
                    iconFactory,
                    false /* skipInflation */,
                    mBubbleViewInfoTaskFactory);
        }

        @VisibleForTesting
        void onInflated(Bubble b) {
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.onInflated()");
            if (b != mBubble) {
                throw new IllegalArgumentException("inflate callback doesn't match bubble");
            }
            if (!mBubble.isShortcut() && !mBubble.isApp()) {
                throw new IllegalArgumentException("Unsupported bubble type");
            }
            final TaskView tv = b.getTaskView();
            tv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(
                    tv.getController());
            if (state != null) {
                state.mVisible = true;
            }
            mTransitionProgress.setInflated();
            // Remove any intermediate queued transitions that were started as a result of the
            // inflation (the task view will be in the right bounds)
            mTaskViewTransitions.removePendingTransitions(tv.getController());
            mTaskViewTransitions.enqueueRunningExternal(tv.getController(), mTransition);
            if (BubbleFlagHelper.enableRootTaskForBubble()
                    && mBubble.getTaskView().isSurfaceCreated()) {
                // In case the Bubble surface has already been created, trigger the surfaceCreated
                // immediately
                surfaceCreated();
            }
        }

        @Override
        public void skip() {
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.skip()");
            cleanup();
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            BubbleLog.d(
                    "LaunchNewTaskBubbleForExistingTransition.onTransitionConsumed() aborted=%b",
                    aborted);
            if (!aborted) return;
            cleanup();
        }

        /**
         * @return true As DefaultMixedTransition assumes that this transition will be handled by
         * this handler in all cases.
         */
        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.startAnimation()");

            mFinishCb = finishCallback;

            // Identify the task that we are converting or launching. Note, we iterate back to front
            // so that we can adjust alpha for revealed surfaces as needed.
            boolean found = false;
            mPlayConvertTaskAnimation = false;
            for (int i = info.getChanges().size() - 1; i >= 0; i--) {
                final TransitionInfo.Change chg = info.getChanges().get(i);
                final ActivityManager.RunningTaskInfo taskInfo = chg.getTaskInfo();
                final boolean isLaunchedTask = (taskInfo != null)
                        && (chg.getMode() == TRANSIT_CHANGE || isOpeningMode(chg.getMode()));
                if (isLaunchedTask) {
                    mStartBounds.set(chg.getStartAbsBounds());
                    // Converting a task into taskview, so treat as "new"
                    mFinishWct = new WindowContainerTransaction();
                    mTaskInfo = taskInfo;
                    mFinishT = finishTransaction;
                    mTaskLeash = chg.getLeash();
                    mSnapshot = chg.getSnapshot();
                    // TODO: This should be set for the CHANGE transition, but for some reason there
                    //  is no snapshot, so fallback to the open transition for now
                    mPlayConvertTaskAnimation = false;
                    found = true;
                } else if (BubbleFlagHelper.enableRootTaskForBubble() && taskInfo != null
                        && mBubbleHelper.isAppBubbleTask(taskInfo)) {
                    // Starting a new bubble from an existing expanded bubble may immediately hide
                    // the currently expanded bubble in the same transition. Ensure the surfaces
                    // stays in the TaskView vs. under the transition root.
                    final Bubble b = mBubbleData.getBubbleInStackWithTaskId(taskInfo.taskId);
                    if (b != null) {
                        startTransaction.reparent(chg.getLeash(),
                                b.getTaskView().getSurfaceControl());
                    }
                } else {
                    // In core-initiated launches, the transition is of an OPEN type, and we need to
                    // manually show the surfaces behind the newly bubbled task
                    if (info.getType() == TRANSIT_OPEN && isOpeningMode(chg.getMode())) {
                        startTransaction.setAlpha(chg.getLeash(), 1f);
                    }
                }
            }
            if (!found) {
                Slog.w(TAG, "Expected a TaskView conversion in this transition but didn't get "
                        + "one, cleaning up the task view");
                mBubble.getTaskView().getController().setTaskNotFound();
                cleanup();
                return true;
            }

            // Now update state (and talk to launcher) in parallel with snapshot stuff
            mBubbleData.notificationEntryUpdated(mBubble, /* suppressFlyout= */ true,
                    /* showInShade= */ false);

            if (mPlayConvertTaskAnimation) {
                final int left = mStartBounds.left - info.getRoot(0).getOffset().x;
                final int top = mStartBounds.top - info.getRoot(0).getOffset().y;
                startTransaction.setPosition(mTaskLeash, left, top);
                startTransaction.show(mSnapshot);
                // Move snapshot to root so that it remains visible while task is moved to taskview
                startTransaction.reparent(mSnapshot, info.getRoot(0).getLeash());
                startTransaction.setPosition(mSnapshot, left, top);
                startTransaction.setLayer(mSnapshot, Integer.MAX_VALUE);
            } else {
                final int left = mStartBounds.left - info.getRoot(0).getOffset().x;
                final int top = mStartBounds.top - info.getRoot(0).getOffset().y;
                startTransaction.setPosition(mTaskLeash, left, top);
            }
            startTransaction.apply();

            mTransitionProgress.setTransitionReady();
            startExpandAnim();
            return true;
        }

        private void startExpandAnim() {
            if (mExpandedViewAnimator.canExpandView(mBubble)) {
                mPriorBubble = mExpandedViewAnimator.prepareConvertedView(mBubble);
            } else if (mExpandedViewAnimator.isExpanded()) {
                mTransitionProgress.setReadyToExpand();
            }
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.startExpandAnim()"
                    + " readyToAnimate=%b", mTransitionProgress.isReadyToAnimate());
            if (mTransitionProgress.isReadyToAnimate()) {
                playAnimation();
            }
        }

        @Override
        public void continueExpand() {
            mTransitionProgress.setReadyToExpand();
        }

        @Override
        public void surfaceCreated() {
            mTransitionProgress.setSurfaceReady();
            mMainExecutor.execute(() -> {
                BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.surfaceCreated()"
                        + " mTaskLeash=%s readyToAnimate=%b",
                        mTaskLeash, mTransitionProgress.isReadyToAnimate());
                final TaskViewTaskController tvc = mBubble.getTaskView().getController();
                final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
                if (state == null) return;
                state.mVisible = true;
                if (mTransitionProgress.isReadyToAnimate()) {
                    playAnimation();
                }
            });
        }

        private void playAnimation() {
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.playAnimation()"
                    + " playConvert=%b",
                    mPlayConvertTaskAnimation);
            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            final TaskViewTaskController tv = mBubble.getTaskView().getController();
            final SurfaceControl.Transaction startT = new SurfaceControl.Transaction();
            // Set task position to 0,0 as it will be placed inside the TaskView
            startT.setPosition(mTaskLeash, 0, 0)
                    .reparent(mTaskLeash, mBubble.getTaskView().getSurfaceControl())
                    .setAlpha(mTaskLeash, 1f)
                    .show(mTaskLeash);
            mTaskViewTransitions.prepareOpenAnimation(tv, true /* new */, startT, mFinishT,
                    (ActivityManager.RunningTaskInfo) mTaskInfo, mTaskLeash, mFinishWct);
            // Add the task view task listener manually since we aren't going through
            // TaskViewTransitions (which normally sets up the listener via a pending launch cookie)
            // Note: In this path, because a new task is being started, the transition may receive
            // the transition for the task before the organizer does
            mTaskOrganizer.addListenerForTaskId(tv, mTaskInfo.taskId);

            if (mFinishWct.isEmpty()) {
                mFinishWct = null;
            }

            float startScale = 1f;
            if (mPlayConvertTaskAnimation) {
                mExpandedViewAnimator.animateConvert(startT, mStartBounds, startScale, mSnapshot,
                        mTaskLeash,
                        this::cleanup);
            } else {
                startT.apply();
                mExpandedViewAnimator.animateExpand(null, this::cleanup);
            }
        }

        private void cleanup() {
            BubbleLog.d("LaunchNewTaskBubbleForExistingTransition.cleanup()");
            mBubble.setCurrentTransition(null);
            // Trigger finishCb after reset current transition as it will immediately kick off
            // the next transition, which may set transition to the previous Bubble.
            mFinishCb.onTransitionFinished(mFinishWct);
            mFinishCb = null;
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
        }
    }

    /**
     * Starts a jumpcut to update Task in the expanding Bubble transition.
     *
     * 1. In transition startTransaction, ensure the closing Task surface is attached to its Bubble
     *    TaskView, so that it is visible and unchanged.
     * 2. When opening Bubble TaskView is ready, ensure the opening Task surface is attached to its
     *    TaskView, and being visible, so that we only need to animate TaskView next.
     * 3. Apply jumpcut for the Bubble switch animation, and apply the Transition finishCallback
     *    after the TaskViews finish surface update.
     * TODO(b/408328557): To be consolidated with LaunchOrConvertToBubble and ConvertToBubble
     */
    @VisibleForTesting
    class JumpcutBubbleSwitchTransition implements TransitionHandler, BubbleTransition,
            View.OnLayoutChangeListener {
        private final BubbleExpandedViewTransitionAnimator mExpandedViewAnimator;
        private final TransitionProgress mTransitionProgress;
        private final Bubble mOpeningBubble;
        private final Bubble mClosingBubble;

        private IBinder mTransition;
        private Transitions.TransitionFinishCallback mFinishCb;
        private WindowContainerTransaction mFinishWct;
        // The task info is resolved once we find the task from the transition info using the
        // pending launch cookie otherwise
        @Nullable
        private TaskInfo mOpeningTaskInfo;

        private SurfaceControl.Transaction mFinishT;
        private SurfaceControl mTaskLeash;
        private boolean mShouldWaitForRelayout;
        @VisibleForTesting
        boolean mHasPlayed;

        JumpcutBubbleSwitchTransition(Bubble openingBubble, Bubble closingBubble, Context context,
                BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
                BubbleStackView stackView, BubbleBarLayerView layerView,
                BubbleIconFactory iconFactory, boolean inflateSync, IBinder transition,
                Consumer<TransitionHandler> onInflatedCallback) {
            if (layerView != null) {
                mExpandedViewAnimator = layerView;
            } else {
                mExpandedViewAnimator = stackView;
            }
            BubbleLog.d("JumpcutBubbleSwitchTransition() closing=%s, opening=%s",
                    closingBubble.getKey(), openingBubble.getKey());
            mOpeningBubble = openingBubble;
            mClosingBubble = closingBubble;
            mTransition = transition;
            mTransitionProgress = new TransitionProgress();
            mOpeningBubble.setInflateSynchronously(inflateSync);
            mOpeningBubble.setCurrentTransition(this);
            mClosingBubble.setCurrentTransition(this);
            // Still need the inflate to update the app icon in Bubble.
            mOpeningBubble.inflate(
                    b -> {
                        onInflated(b);
                        onInflatedCallback.accept(JumpcutBubbleSwitchTransition.this);
                    },
                    context,
                    expandedViewManager,
                    factory,
                    stackView,
                    layerView,
                    iconFactory,
                    false /* skipInflation */,
                    mBubbleViewInfoTaskFactory);
        }

        @VisibleForTesting
        void onInflated(Bubble b) {
            BubbleLog.d("JumpcutBubbleSwitchTransition.onInflated()");
            if (b != mOpeningBubble) {
                throw new IllegalArgumentException("inflate callback doesn't match bubble");
            }
            final TaskView tv = b.getTaskView();
            tv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(
                    tv.getController());
            if (state != null) {
                state.mVisible = true;
            }
            mTransitionProgress.setInflated();
            // Remove any intermediate queued transitions that were started as a result of the
            // inflation (the task view will be in the right bounds)
            mTaskViewTransitions.removePendingTransitions(tv.getController());
            mTaskViewTransitions.enqueueExternal(tv.getController(), () -> mTransition);
            // To listen on TaskView relayout
            tv.addOnLayoutChangeListener(this);
        }

        @Override
        public void skip() {
            BubbleLog.d("JumpcutBubbleSwitchTransition.skip()");
            cleanup();
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            cleanup();
        }

        /**
         * @return true As DefaultMixedTransition assumes that this transition will be handled by
         * this handler in all cases.
         */
        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            BubbleLog.d("JumpcutBubbleSwitchTransition.startAnimation()");

            final TransitionInfo.Change enterBubbleTask = mBubbleHelper.getEnterBubbleTask(info);
            final TransitionInfo.Change closingBubbleTask = mBubbleHelper.getClosingBubbleTask(
                    info);
            mOpeningTaskInfo = enterBubbleTask.getTaskInfo();
            mFinishWct = new WindowContainerTransaction();
            mFinishT = finishTransaction;
            mFinishCb = finishCallback;
            mTaskLeash = enterBubbleTask.getLeash();

            mBubbleData.jumpcutBubbleSwitch(mOpeningBubble, mClosingBubble);

            // Keep showing the closing Bubble Task within the closing Bubble TaskView until the
            // opening Bubble TaskView is ready.
            final SurfaceControl closingBubbleTaskLeash = closingBubbleTask.getLeash();
            final SurfaceControl closingBubbleTaskView = mClosingBubble.getTaskView()
                    .getSurfaceControl();
            startTransaction.setAlpha(closingBubbleTaskLeash, 1f)
                    .setPosition(closingBubbleTaskLeash, 0, 0)
                    .reparent(closingBubbleTaskLeash, closingBubbleTaskView)
                    .show(closingBubbleTaskLeash)
                    .setAlpha(enterBubbleTask.getLeash(), 0);

            startTransaction.apply();

            mTransitionProgress.setTransitionReady();
            if (mExpandedViewAnimator.canExpandView(mOpeningBubble)) {
                final BubbleViewProvider priorBubble =
                        mExpandedViewAnimator.prepareConvertedView(mOpeningBubble);
                if (priorBubble != mClosingBubble
                        // TODO b/419347947 BubbleStackView will return null for non-overflow bubble
                        && priorBubble != null) {
                    throw new IllegalStateException("Previous expanded Bubble was taskId="
                            + priorBubble.getTaskId() + " but expect taskId="
                            + mClosingBubble.getTaskId());
                }
            } else if (mExpandedViewAnimator.isExpanded()) {
                mTransitionProgress.setReadyToExpand();
            }
            startAnimationIfReady();

            return true;
        }

        @Override
        public void continueExpand() {
            mTransitionProgress.setReadyToExpand();
        }

        @Override
        public void surfaceCreated() {
            mTransitionProgress.setSurfaceReady();
            mMainExecutor.execute(() -> {
                BubbleLog.d("JumpcutBubbleSwitchTransition.surfaceCreated()");
                final TaskViewTaskController tvc = mOpeningBubble.getTaskView().getController();
                final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
                if (state == null) return;
                state.mVisible = true;
                startAnimationIfReady();
            });
        }

        @Override
        public void onTaskViewBoundsChanged(Bubble bubble) {
            if (!mHasPlayed && mOpeningBubble == bubble) {
                // There is a pending relayout on the opening Bubble's TaskView. We should wait
                // for the #onLayoutChange before starting the animation.
                mShouldWaitForRelayout = true;
            }
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            mShouldWaitForRelayout = false;
            mMainExecutor.execute(this::startAnimationIfReady);
        }

        private void startAnimationIfReady() {
            if (mHasPlayed || mShouldWaitForRelayout || !mTransitionProgress.isReadyToAnimate()) {
                // Not yet ready.
                return;
            }
            mHasPlayed = true;
            // Remove since we don't need to wait for relayout anymore.
            mOpeningBubble.getTaskView().removeOnLayoutChangeListener(this);
            animateJumpcut();
        }

        private void animateJumpcut() {
            BubbleLog.d("JumpcutBubbleSwitchTransition.animateJumpcut()");
            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            final TaskViewTaskController tv = mOpeningBubble.getTaskView().getController();

            // Prepare the transaction to apply when the TaskView surface is ready.
            final SurfaceControl.Transaction startT = new SurfaceControl.Transaction();
            final SurfaceControl openingTaskViewLeash = mOpeningBubble.getTaskView()
                    .getSurfaceControl();
            startT.setAlpha(mTaskLeash, 1f)
                    // Set task position to 0,0 as it will be placed inside the TaskView
                    .setPosition(mTaskLeash, 0, 0)
                    .reparent(mTaskLeash, openingTaskViewLeash)
                    .show(mTaskLeash);
            mTaskViewTransitions.prepareOpenAnimation(tv, true /* new */, startT, mFinishT,
                    (ActivityManager.RunningTaskInfo) mOpeningTaskInfo, mTaskLeash, mFinishWct);
            startT.apply();

            // Add the task view task listener manually since we aren't going through
            // TaskViewTransitions (which normally sets up the listener via a pending launch cookie)
            // Note: In this path, because a new task is being started, the transition may receive
            // the transition for the task before the organizer does
            mTaskOrganizer.addListenerForTaskId(tv, mOpeningTaskInfo.taskId);

            if (mFinishWct.isEmpty()) {
                mFinishWct = null;
            }

            mExpandedViewAnimator.animateExpand(mClosingBubble, this::cleanup);
        }

        private void cleanup() {
            mOpeningBubble.setCurrentTransition(null);
            mClosingBubble.setCurrentTransition(null);
            if (mOpeningBubble.getTaskView() != null) {
                mOpeningBubble.getTaskView().removeOnLayoutChangeListener(this);
            }
            if (mFinishCb != null) {
                // Trigger finishCb after reset current transition as it will immediately kick off
                // the next transition, which may set transition to the previous Bubble.
                ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "JumpcutBubbleSwitchTransition.cleanup()");
                mFinishCb.onTransitionFinished(mFinishWct);
                mFinishCb = null;
            }
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
        }
    }

    /**
     * Starts a new transition into a bubble, which will either play a launch animation (if the task
     * was not previously visible) or a convert animation (if the task is currently visible).
     */
    @VisibleForTesting
    class LaunchOrConvertToBubble implements TransitionHandler, BubbleTransition,
            ITransitionPlanner {
        final BubbleExpandedViewTransitionAnimator mExpandedViewAnimator;
        final BubblePositioner mPositioner;
        private final TransitionProgress mTransitionProgress;
        Bubble mBubble;
        IBinder mTransition;
        IBinder mPlayingTransition;
        private Transitions.TransitionFinishCallback mFinishCb;
        private Runnable mAnimationFinishCb;
        private WindowContainerTransaction mFinishWct;
        final Rect mStartBounds = new Rect();
        float mStartScale = 1.0f;
        SurfaceControl mSnapshot = null;
        // The task info is resolved once we find the task from the transition info using the
        // pending launch cookie otherwise
        @Nullable
        TaskInfo mTaskInfo;
        @Nullable
        ActivityOptions.LaunchCookie mLaunchCookie;
        BubbleViewProvider mPriorBubble = null;
        // Whether we should play the convert-task animation, or the launch-task animation
        private boolean mPlayConvertTaskAnimation;

        private SurfaceControl.Transaction mFinishT;
        private SurfaceControl mTaskLeash;
        @Nullable
        private BubbleBarLocation mBubbleBarLocation;
        private boolean mHasPlayed;

        LaunchOrConvertToBubble(Bubble bubble, Context context,
                BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
                BubblePositioner positioner, BubbleStackView stackView,
                BubbleBarLayerView layerView, BubbleIconFactory iconFactory,
                boolean inflateSync, @Nullable BubbleBarLocation bubbleBarLocation) {
            if (layerView != null) {
                mExpandedViewAnimator = layerView;
            } else {
                mExpandedViewAnimator = stackView;
            }
            BubbleLog.d("LaunchOrConvertToBubble() key=%s expanded=%b",
                    bubble.getKey(), mExpandedViewAnimator.isExpanded());
            mBubble = bubble;
            mTransitionProgress = new TransitionProgress();
            mPositioner = positioner;
            mBubble.setInflateSynchronously(inflateSync);
            mBubble.setCurrentTransition(this);
            mBubbleBarLocation = bubbleBarLocation;
            mBubble.inflate(
                    this::onInflated,
                    context,
                    expandedViewManager,
                    factory,
                    stackView,
                    layerView,
                    iconFactory,
                    false /* skipInflation */,
                    mBubbleViewInfoTaskFactory);
        }

        @VisibleForTesting
        void onInflated(Bubble b) {
            BubbleLog.d("LaunchOrConvertToBubble.onInflated()");
            if (b != mBubble) {
                throw new IllegalArgumentException("inflate callback doesn't match bubble");
            }
            if (!mBubble.isShortcut() && !mBubble.isApp()) {
                throw new IllegalArgumentException("Unsupported bubble type");
            }
            final Rect launchBounds = new Rect();
            mPositioner.getTaskViewRestBounds(launchBounds);

            final TaskView tv = b.getTaskView();
            tv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(
                    tv.getController());
            if (state != null) {
                state.mVisible = true;
            }
            mTransitionProgress.setInflated();
            mTaskViewTransitions.enqueueExternal(tv.getController(), () -> {
                // We need to convert the next launch into a bubble
                mLaunchCookie = new ActivityOptions.LaunchCookie();
                mPendingEnterTransitions.put(mLaunchCookie.binder, this);
                ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "Starting activity with pending cookie=%s",
                        mLaunchCookie.binder);

                final ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchCookie(mLaunchCookie);
                opts.setReparentLeafTaskToTda(true);
                final WindowContainerToken rootTaskToken =
                        mBubbleHelper.getAppBubbleRootTaskToken();
                if (rootTaskToken != null) {
                    opts.setLaunchRootTask(rootTaskToken);
                } else {
                    opts.setTaskAlwaysOnTop(true);
                    opts.setLaunchNextToBubble(true);
                    opts.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
                }
                opts.setLaunchBounds(launchBounds);
                // TODO(b/437451940): start the pending intent or shortcut via WCT
                if (mBubble.isShortcut()) {
                    final LauncherApps launcherApps = mContext.getSystemService(
                            LauncherApps.class);
                    launcherApps.startShortcut(mBubble.getShortcutInfo(),
                            null /* sourceBounds */, opts.toBundle());
                } else if (mBubble.isApp()) {
                    final ActivityOptions sendOpts = ActivityOptions.makeBasic();
                    sendOpts.setPendingIntentBackgroundActivityStartMode(
                            MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
                    final Bundle sendOptsBundle = sendOpts.toBundle();
                    final PendingIntent intent;
                    if (mBubble.getPendingIntent() != null) {
                        intent = mBubble.getPendingIntent();
                        sendOptsBundle.putAll(opts.toBundle());
                    } else {
                        opts.setPendingIntentCreatorBackgroundActivityStartMode(
                                MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
                        intent = PendingIntent.getActivityAsUser(mContext, 0,
                                mBubble.getIntent(), FLAG_IMMUTABLE | FLAG_ONE_SHOT,
                                opts.toBundle(), mBubble.getUser());
                    }
                    try {
                        intent.send(sendOptsBundle);
                    } catch (PendingIntent.CanceledException e) {
                        Log.w(TAG, "Failed to launch app bubble");
                    }
                }

                // Add the task view task listener manually since we aren't going through
                // TaskViewTransitions (which normally sets up the listener via a pending launch
                // cookie
                mTaskOrganizer.setPendingLaunchCookieListener(mLaunchCookie.binder,
                        mBubble.getTaskView().getController());

                // We use a stub transition here since we don't know what is incoming, but it
                // won't actually match any transition when queried in TaskViewTransitions,
                // which is Ok since we don't want TaskViewTransitions to handle this anyways.
                // However, we do need to use it whenever calling onExternalDone() instead of
                // the incoming transition.
                BubbleLog.d("LaunchOrConvertToBubble.onInflated() starting activity");
                mTransition = new Binder();
                return mTransition;
            });
        }

        @Override
        public void skip() {
            BubbleLog.d("LaunchOrConvertToBubble.skip()");
            cleanup();
        }

        @Override
        public Transitions.RequestResult handleRequestOnly(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            if (!BubbleFlagHelper.enableRootTaskForBubble()) {
                return null;
            }

            final WindowContainerTransaction wct = new WindowContainerTransaction();
            final Rect bounds = new Rect();
            mPositioner.getTaskViewRestBounds(bounds);
            final WindowContainerToken bubbleRootTask =
                    mBubbleHelper.getAppBubbleRootTaskToken();
            if (bubbleRootTask != null) {
                wct.setBounds(bubbleRootTask, bounds);
                wct.setAlwaysOnTop(bubbleRootTask, true);
            }

            BubbleLog.d("LaunchOrConvertToBubble.handleRequest(), set root bounds %s", bounds);
            if (BubbleFlagHelper.isBubbleTransitionPlannerEnabled()) {
                return new Transitions.RequestResult(wct,
                        Collections.singletonList(mBubbleTransitionsPlanner));
            } else {
                return new Transitions.RequestResult(wct, this);
            }
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (info.getType() != TRANSIT_SPLIT_DISMISS) {
                return;
            }
            startT.apply();
            finishCallback.onTransitionFinished(null /* wct */);
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            cleanup();
            if (mLaunchCookie != null) {
                BubbleLog.d("LaunchOrConvertToBubble.onTransitionConsumed()"
                        + " Removing pending transition for cookie=%s", mLaunchCookie.binder);
                mPendingEnterTransitions.remove(mLaunchCookie.binder);
            }
            mEnterTransitions.remove(transition);
        }

        @Override
        public boolean canAnimateTransition(@NonNull TransitionInfo info) {
            final TransitionInfo.Change enterBubbleTask = mBubbleHelper.getEnterBubbleTask(info);
            final Bubble bubble = enterBubbleTask != null
                    ? mBubbleData.getBubbleInStackWithTaskId(enterBubbleTask.getTaskInfo().taskId)
                    : null;
            if (bubble != null && bubble != mBubble) {
                // The transition was expecting to launch an Intent into the new Bubble, but in
                // this case the Intent moves an existing Bubbled Task to front.
                BubbleLog.d("LaunchOrConvertToBubble.canAnimateTransition() Cannot animate for"
                        + " expanding existing Bubble. Fallback to let others to animate.");
                return false;
            }
            return true;
        }

        /**
         * @return true As DefaultMixedTransition assumes that this transition will be handled by
         * this handler in all cases.
         */
        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            BubbleLog.d("LaunchOrConvertToBubble.startAnimation()");
            mPlayingTransition = transition;
            mFinishCb = finishCallback;

            mPlayConvertTaskAnimation = false;
            boolean found =
                    processChangesFindMatching(info, startTransaction, finishTransaction) != null;
            if (!found) {
                Slog.w(TAG, "Expected a TaskView conversion in this transition but didn't get "
                        + "one, cleaning up the task view");
                mBubble.getTaskView().getController().setTaskNotFound();
                cleanup();
                return true;
            }

            // Now update state (and talk to launcher) in parallel with snapshot stuff
            mBubbleData.notificationEntryUpdated(mBubble, /* suppressFlyout= */ true,
                    /* showInShade= */ false, mBubbleBarLocation);

            if (mPlayConvertTaskAnimation) {
                final int left = mStartBounds.left - info.getRoot(0).getOffset().x;
                final int top = mStartBounds.top - info.getRoot(0).getOffset().y;
                startTransaction.setCrop(mTaskLeash, mStartBounds);
                startTransaction.setPosition(mTaskLeash, left, top);
                startTransaction.show(mSnapshot);
                // Move snapshot to root so that it remains visible while task is moved to taskview
                startTransaction.reparent(mSnapshot, info.getRoot(0).getLeash());
                startTransaction.setPosition(mSnapshot, left, top);
                startTransaction.setLayer(mSnapshot, Integer.MAX_VALUE);
            } else {
                final int left = mStartBounds.left - info.getRoot(0).getOffset().x;
                final int top = mStartBounds.top - info.getRoot(0).getOffset().y;
                startTransaction.setPosition(mTaskLeash, left, top);
            }
            startTransaction.apply();

            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            mTransitionProgress.setTransitionReady();
            startExpandAnim();
            return true;
        }

        private void startExpandAnim() {
            final boolean animate = mExpandedViewAnimator.canExpandView(mBubble);
            if (animate) {
                mPriorBubble = mExpandedViewAnimator.prepareConvertedView(mBubble);
            }
            BubbleLog.d("LaunchOrConvertToBubble.startExpandAnim(): readyToAnimate=%b",
                    mTransitionProgress.isReadyToAnimate());
            if (mPriorBubble != null) {
                // TODO: an animation. For now though, just remove it.
                final BubbleBarExpandedView priorView = mPriorBubble.getBubbleBarExpandedView();
                mExpandedViewAnimator.removeViewFromTransition(priorView);
                mPriorBubble = null;
            }
            if (mTransitionProgress.isReadyToAnimate()) {
                playAnimation(animate);
            }
        }

        @Override
        public void continueExpand() {
            mTransitionProgress.setReadyToExpand();
        }

        @Override
        public void surfaceCreated() {
            mTransitionProgress.setSurfaceReady();
            mMainExecutor.execute(() -> {
                BubbleLog.d("LaunchOrConvertToBubble.surfaceCreated()"
                        + " mTaskLeash=%s readyToAnimate=%b",
                        mTaskLeash, mTransitionProgress.isReadyToAnimate());
                final TaskViewTaskController tvc = mBubble.getTaskView().getController();
                final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
                if (state == null) return;
                state.mVisible = true;
                if (mTransitionProgress.isReadyToAnimate()) {
                    playAnimation(true /* animate */);
                }
            });
        }

        private void playAnimation(boolean animate) {
            BubbleLog.d("LaunchOrConvertToBubble.playAnimation(): playConvert=%b",
                    mPlayConvertTaskAnimation);
            mHasPlayed = true;
            final TaskViewTaskController tv = mBubble.getTaskView().getController();
            final SurfaceControl.Transaction startT = new SurfaceControl.Transaction();
            // Set task position to 0,0 as it will be placed inside the TaskView
            startT.setPosition(mTaskLeash, 0, 0);
            if (!mPlayConvertTaskAnimation) {
                startT.reparent(mTaskLeash, mBubble.getTaskView().getSurfaceControl())
                        .setAlpha(mTaskLeash, 1f)
                        .show(mTaskLeash);
            }
            mTaskViewTransitions.prepareOpenAnimation(tv, true /* new */, startT, mFinishT,
                    (ActivityManager.RunningTaskInfo) mTaskInfo, mTaskLeash, mFinishWct);

            if (mFinishWct.isEmpty()) {
                mFinishWct = null;
            }

            if (animate) {
                if (mPlayConvertTaskAnimation) {
                    mExpandedViewAnimator.animateConvert(startT,
                            mStartBounds,
                            mStartScale,
                            mSnapshot,
                            mTaskLeash,
                            this::cleanup);
                } else {
                    startT.apply();
                    mExpandedViewAnimator.animateExpand(null, this::cleanup);
                }
            } else {
                startT.apply();
                cleanup();
            }
        }

        private void cleanup() {
            BubbleLog.d("LaunchOrConvertToBubble.cleanup(): removeCookie=%s",
                    mLaunchCookie.binder);
            mStartScale = 1.0f;
            if (!mHasPlayed) {
                // Cleanup the new Bubble which is never used.
                // This would happen when the animation is aborted.
                // TODO(b/483107404): fix bubble expanded view flick
                mBubbleData.dismissBubbleWithKey(
                        mBubble.getKey(), Bubbles.DISMISS_REPLACE_BY_EXISTING);
            }
            mBubble.setCurrentTransition(null);
            // Trigger finishCb after reset current transition as it will immediately kick off
            // the next transition, which may set transition to the previous Bubble.
            if (mFinishCb != null) {
                mFinishCb.onTransitionFinished(mFinishWct);
                mFinishCb = null;
            }
            if (mAnimationFinishCb != null) {
                mAnimationFinishCb.run();
                mAnimationFinishCb = null;
            }
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
            mPendingEnterTransitions.remove(mLaunchCookie.binder);
            mEnterTransitions.remove(mPlayingTransition);
        }

        @Nullable
        private TransitionInfo.Change processChangesFindMatching(
                @NonNull TransitionInfo transitionInfo,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            TransitionInfo.Change change = null;
            // Identify the task that we are converting or launching. Note, we iterate back to front
            // so that we can adjust alpha for revealed surfaces as needed.
            for (int i = transitionInfo.getChanges().size() - 1; i >= 0; i--) {
                final TransitionInfo.Change chg = transitionInfo.getChanges().get(i);
                final boolean isLaunchedTask = (chg.getTaskInfo() != null)
                        && (chg.getMode() == TRANSIT_CHANGE || isOpeningMode(chg.getMode()))
                        && (chg.getTaskInfo().launchCookies.contains(mLaunchCookie.binder));
                if (isLaunchedTask) {
                    mStartBounds.set(chg.getStartAbsBounds());
                    // Converting a task into taskview, so treat as "new"
                    mFinishWct = new WindowContainerTransaction();
                    mTaskInfo = chg.getTaskInfo();
                    mTaskLeash = chg.getLeash();
                    mFinishT = finishTransaction;
                    mSnapshot = chg.getSnapshot();
                    mPlayConvertTaskAnimation = !isOpeningMode(chg.getMode()) && mSnapshot != null;
                    if (change != null) {
                        BubbleLog.w(
                                "LaunchOrConvertToBubble.processChangesFindMatching() prev "
                                        + "matching change=%s, new=%s", change, chg);
                    }
                    change = chg;
                    if (BubbleFlagHelper.enableRootTaskForBubble()
                            && chg.getMode() != TRANSIT_CHANGE) {
                        // Prepare to animate in. This is normally pre-set in
                        // Transitions#setupStartState, but after root Task for Bubble, the opening
                        // leaf Task can be considered as dependent.
                        startTransaction.setAlpha(chg.getLeash(), 0f);
                    }
                } else if (transitionInfo.getType() == TRANSIT_OPEN
                        && isOpeningMode(chg.getMode())) {
                    // In core-initiated launches, the transition is of an OPEN type, and we need to
                    // manually show the surfaces behind the newly bubbled task
                    startTransaction.setAlpha(chg.getLeash(), 1f);
                }
            }
            return change;
        }

        @Override
        public void plan(@NonNull AnimationPlan plan, @NonNull TransitionInfo fullInfo,
                @NonNull IBinder transition, @NonNull TransitionInfo plannableInfo,
                @NonNull SurfaceControl.Transaction startTransaction) {
            if (!Flags.enableBubbleTransitionPlanner()) {
                throw new IllegalStateException(
                        "ITransitionPlanner.plan() should not be called if guarding flag is"
                            + " disabled");
            }
            BubbleLog.d("LaunchOrConvertToBubble.plan() bubble=%s", mBubble.getKey());
            mPlayConvertTaskAnimation = false;
            SurfaceControl.Transaction finishTransaction = new SurfaceControl.Transaction();
            TransitionInfo.Change change =
                    processChangesFindMatching(plannableInfo, startTransaction, finishTransaction);
            final WindowContainerToken token = change == null ? null : change.getContainer();
            if (token == null) {
                String reason = change == null ? "change not found" : "no container for change";
                BubbleLog.e("Expected a TaskView conversion in this transition but didn't get "
                        + "one - %s, cleaning up the task view", reason);
                mBubble.getTaskView().getController().setTaskNotFound();
                cleanup();
                return;
            }
            // Now update state (and talk to launcher) in parallel with snapshot stuff
            mBubbleData.notificationEntryUpdated(mBubble, /* suppressFlyout= */ true,
                    /* showInShade= */ false, mBubbleBarLocation);
            final int left = mStartBounds.left - plannableInfo.getRoot(0).getOffset().x;
            final int top = mStartBounds.top - plannableInfo.getRoot(0).getOffset().y;

            if (mPlayConvertTaskAnimation) {
                startTransaction.setCrop(mTaskLeash, mStartBounds);
                startTransaction.setPosition(mTaskLeash, left, top);
                startTransaction.show(mSnapshot);
                // Move snapshot to root so that it remains visible while task is
                // moved
                // to taskview
                startTransaction.reparent(mSnapshot, plannableInfo.getRoot(0).getLeash());
                startTransaction.setPosition(mSnapshot, left, top);
                startTransaction.setLayer(mSnapshot, Integer.MAX_VALUE);
            } else {
                startTransaction.setPosition(mTaskLeash, left, top);
            }
            startTransaction.apply();

            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            mTransitionProgress.setTransitionReady();
            plan.setAnimation(
                    token,
                    new ITransitionAnimation() {
                        @NonNull
                        @Override
                        public DetachResult detach(
                                @NonNull List<WindowContainerToken> containers,
                                @NonNull SurfaceControl.Transaction startTransaction) {
                            BubbleLog.w("ITransitionAnimation.detach()");
                            final ArrayList<WindowAnimationState> states =
                                    new ArrayList<>(containers.size());
                            for (int i = 0; i < containers.size(); ++i) {
                                WindowAnimationState state = null;
                                if (containers.get(i).equals(token)) {
                                    mHasPlayed = false;
                                    state = mExpandedViewAnimator.cancelAnimation();
                                }
                                if (state == null) {
                                    state = new WindowAnimationState();
                                }
                                states.add(state);
                            }
                            return new DetachResult(states);
                        }

                        @Override
                        public void start(
                                @NonNull TransitionInfo info,
                                @NonNull List<WindowAnimationState> from,
                                @NonNull IFinishedCallback onFinished) {
                            BubbleLog.d("LaunchOrConvertToBubble.ITransitionAnimation.start()");
                            if (!from.isEmpty()) {
                                WindowAnimationState state = from.getFirst();
                                if (state.bounds != null) {
                                    state.bounds.roundOut(mStartBounds);
                                }
                                mStartScale = state.scale;
                            }
                            mAnimationFinishCb = () -> onFinished.onFinished(finishTransaction);
                            startExpandAnim();
                        }

                        @NonNull
                        @Override
                        public String getDebugName() {
                            return "LaunchOrConvertToBubbleAnimation";
                        }
                    });
        }

        @NonNull
        @Override
        public String getDebugName() {
            return "LaunchOrConvertToBubble";
        }
    }

    /**
     * BubbleTransition that coordinates the process of a non-bubble task becoming a bubble. The
     * steps are as follows:
     *
     * 1. Start inflating the bubble view
     * 2. Once inflated (but not-yet visible), tell WM to do the shell-transition.
     * 3. When the transition becomes ready, notify Launcher in parallel
     * 4. Wait for surface to be created
     * 5. Once surface is ready, animate the task to a bubble
     *
     * While the animation is pending, we keep a reference to the pending transition in the bubble.
     * This allows us to check in other parts of the code that this bubble will be shown via the
     * transition animation.
     *
     * startAnimation, continueExpand and surfaceCreated are set-up to happen in either order,
     * to support UX/timing adjustments.
     */
    @VisibleForTesting
    class ConvertToBubble implements Transitions.TransitionHandler, BubbleTransition {
        final BubbleExpandedViewTransitionAnimator mExpandedViewAnimator;
        final BubblePositioner mPositioner;
        final HomeIntentProvider mHomeIntentProvider;
        Bubble mBubble;
        @Nullable
        DragData mDragData;
        IBinder mTransition;
        private Transitions.TransitionFinishCallback mFinishCb;
        private WindowContainerTransaction mFinishWct;
        final Rect mStartBounds = new Rect();
        SurfaceControl mSnapshot = null;
        TaskInfo mTaskInfo;
        BubbleViewProvider mPriorBubble = null;

        private final TransitionProgress mTransitionProgress;
        private SurfaceControl.Transaction mFinishT;
        private SurfaceControl mTaskLeash;

        ConvertToBubble(Bubble bubble, TaskInfo taskInfo, Context context,
                BubbleExpandedViewManager expandedViewManager, BubbleTaskViewFactory factory,
                BubblePositioner positioner, BubbleStackView stackView,
                BubbleBarLayerView layerView, BubbleIconFactory iconFactory,
                HomeIntentProvider homeIntentProvider, @Nullable DragData dragData,
                boolean inflateSync) {
            if (layerView != null) {
                mExpandedViewAnimator = layerView;
            } else {
                mExpandedViewAnimator = stackView;
            }
            BubbleLog.d("ConvertToBubble() expanded=%b",
                    mExpandedViewAnimator.isExpanded());
            mBubble = bubble;
            mTransitionProgress = new TransitionProgress();
            mTaskInfo = taskInfo;
            mPositioner = positioner;
            mHomeIntentProvider = homeIntentProvider;
            mDragData = dragData;
            mBubble.setInflateSynchronously(inflateSync);
            mBubble.setCurrentTransition(this);
            mBubble.inflate(
                    this::onInflated,
                    context,
                    expandedViewManager,
                    factory,
                    stackView,
                    layerView,
                    iconFactory,
                    false /* skipInflation */,
                    mBubbleViewInfoTaskFactory);
        }

        @VisibleForTesting
        void onInflated(Bubble b) {
            BubbleLog.d("ConvertToBubble.onInflated()");
            if (b != mBubble) {
                throw new IllegalArgumentException("inflate callback doesn't match bubble");
            }
            final Rect launchBounds = new Rect();
            mPositioner.getTaskViewRestBounds(launchBounds);
            final boolean reparentToTda =
                    mTaskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW
                            && mTaskInfo.getParentTaskId() != INVALID_TASK_ID;
            final WindowContainerTransaction wct = getEnterBubbleTransaction(
                    mBubbleHelper, mTaskInfo.token, launchBounds,
                    true /* isAppBubble */, reparentToTda);
            mHomeIntentProvider.addLaunchHomePendingIntent(wct, mTaskInfo.displayId,
                    mTaskInfo.userId);

            final WindowContainerToken rootToken = mBubbleHelper.getAppBubbleRootTaskToken();
            wct.setBounds(rootToken != null ? rootToken : mTaskInfo.token, launchBounds);

            final TaskView tv = b.getTaskView();
            tv.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(
                    tv.getController());
            if (state != null) {
                state.mVisible = true;
            }
            mTransitionProgress.setInflated();
            mTaskViewTransitions.enqueueExternal(tv.getController(), () -> {
                mTransition = mTransitions.startTransition(TRANSIT_CONVERT_TO_BUBBLE, wct, this);
                return mTransition;
            });
        }

        @Override
        public void skip() {
            BubbleLog.d("BubbleTransitions.ConvertToBubble.skip()");
            cleanup();
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            cleanup();
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) {
                cleanup();
                return false;
            }
            BubbleLog.d("ConvertToBubble.startAnimation()");
            boolean found = false;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change chg = info.getChanges().get(i);
                if (chg.getTaskInfo() == null) continue;
                if (chg.getMode() != TRANSIT_CHANGE && chg.getMode() != TRANSIT_TO_FRONT) continue;
                if (!mTaskInfo.token.equals(chg.getTaskInfo().token)) continue;
                mStartBounds.set(chg.getStartAbsBounds());
                // Converting a task into taskview, so treat as "new"
                mFinishWct = new WindowContainerTransaction();
                mTaskInfo = chg.getTaskInfo();
                mFinishT = finishTransaction;
                mTaskLeash = chg.getLeash();
                found = true;
                mSnapshot = chg.getSnapshot();
                break;
            }
            if (!found) {
                Slog.w(TAG, "Expected a TaskView conversion in this transition but didn't get "
                        + "one, cleaning up the task view");
                mBubble.getTaskView().getController().setTaskNotFound();
                cleanup();
                return false;
            }
            mFinishCb = finishCallback;

            if (mDragData != null) {
                mStartBounds.offsetTo((int) mDragData.getDragPosition().x,
                        (int) mDragData.getDragPosition().y);
                startTransaction.setScale(mSnapshot, mDragData.getTaskScale(),
                        mDragData.getTaskScale());
                startTransaction.setCornerRadius(mSnapshot, mDragData.getCornerRadius());
            }

            // Now update state (and talk to launcher) in parallel with snapshot stuff
            mBubbleData.notificationEntryUpdated(mBubble, /* suppressFlyout= */ true,
                    /* showInShade= */ false);

            final int left = mStartBounds.left - info.getRoot(0).getOffset().x;
            final int top = mStartBounds.top - info.getRoot(0).getOffset().y;
            startTransaction.setPosition(mTaskLeash, left, top);
            startTransaction.show(mSnapshot);
            // Move snapshot to root so that it remains visible while task is moved to taskview
            startTransaction.reparent(mSnapshot, info.getRoot(0).getLeash());
            startTransaction.setPosition(mSnapshot, left, top);
            startTransaction.setLayer(mSnapshot, Integer.MAX_VALUE);

            startTransaction.apply();

            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            mTransitionProgress.setTransitionReady();
            startExpandAnim();
            return true;
        }

        private void startExpandAnim() {
            final boolean animate = mExpandedViewAnimator.canExpandView(mBubble);
            if (animate) {
                mPriorBubble = mExpandedViewAnimator.prepareConvertedView(mBubble);
            }
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "ConvertToBubble.startExpandAnim(): "
                    + "readyToAnimate=%b", mTransitionProgress.isReadyToAnimate());
            if (mPriorBubble != null) {
                // TODO: an animation. For now though, just remove it.
                final BubbleBarExpandedView priorView = mPriorBubble.getBubbleBarExpandedView();
                mExpandedViewAnimator.removeViewFromTransition(priorView);
                mPriorBubble = null;
            }
            if (mTransitionProgress.isReadyToAnimate()) {
                playAnimation(animate);
            }
        }

        @Override
        public void continueExpand() {
            mTransitionProgress.setReadyToExpand();
        }

        @Override
        public void surfaceCreated() {
            mTransitionProgress.setSurfaceReady();
            mMainExecutor.execute(() -> {
                BubbleLog.d("ConvertToBubble.surfaceCreated() mTaskLeash=%s"
                        + " readyToAnimate=%b",
                        mTaskLeash, mTransitionProgress.isReadyToAnimate());
                final TaskViewTaskController tvc = mBubble.getTaskView().getController();
                final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
                if (state == null) return;
                state.mVisible = true;
                if (mTransitionProgress.isReadyToAnimate()) {
                    playAnimation(true /* animate */);
                }
            });
        }

        private void playAnimation(boolean animate) {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "ConvertToBubble.playAnimation()");
            final TaskViewTaskController tv = mBubble.getTaskView().getController();
            final SurfaceControl.Transaction startT = new SurfaceControl.Transaction();
            // Set task position to 0,0 as it will be placed inside the TaskView
            startT.setPosition(mTaskLeash, 0, 0);
            mTaskViewTransitions.prepareOpenAnimation(tv, true /* new */, startT, mFinishT,
                    (ActivityManager.RunningTaskInfo) mTaskInfo, mTaskLeash, mFinishWct);
            // Add the task view task listener manually since we aren't going through
            // TaskViewTransitions (which normally sets up the listener via a pending launch cookie)
            mTaskOrganizer.addListenerForTaskId(tv, mTaskInfo.taskId);

            if (mFinishWct.isEmpty()) {
                mFinishWct = null;
            }

            if (animate) {
                float startScale = mDragData != null ? mDragData.getTaskScale() : 1f;
                mExpandedViewAnimator.animateConvert(startT,
                        mStartBounds,
                        startScale,
                        mSnapshot,
                        mTaskLeash,
                        this::cleanup);
            } else {
                startT.apply();
                cleanup();
            }
        }

        private void cleanup() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "ConvertToBubble.cleanup()");
            mBubble.setCurrentTransition(null);
            if (mFinishCb != null) {
                // Trigger finishCb after reset current transition as it will immediately kick off
                // the next transition, which may set transition to the previous Bubble.
                mFinishCb.onTransitionFinished(mFinishWct);
                mFinishCb = null;
            }
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
        }
    }

    /**
     * BubbleTransition that coordinates the setup for moving a task out of a bubble. The actual
     * animation is owned by the "receiver" of the task; however, because Bubbles uses TaskView,
     * we need to do some extra coordination work to get the task surface out of the view
     * "seamlessly".
     *
     * The process here looks like:
     * 1. Send transition to WM for leaving bubbles mode
     * 2. in startAnimation, set-up a "pluck" operation to pull the task surface out of taskview
     * 3. Once "plucked", remove the view (calls continueCollapse when surfaces can be cleaned-up)
     * 4. Then re-dispatch the transition animation so that the "receiver" can animate it.
     *
     * So, constructor -> startAnimation -> continueCollapse -> re-dispatch.
     */
    @VisibleForTesting
    class ConvertFromBubble implements TransitionHandler, BubbleTransition {
        @NonNull final Bubble mBubble;
        IBinder mTransition;
        TaskInfo mTaskInfo;
        SurfaceControl mTaskLeash;
        SurfaceControl mRootLeash;
        private Transitions.TransitionFinishCallback mFinishCb;
        private WindowContainerTransaction mFinishWct;

        ConvertFromBubble(@NonNull Bubble bubble, TaskInfo taskInfo) {
            mBubble = bubble;
            mTaskInfo = taskInfo;
            BubbleLog.d("ConvertFromBubble() key=%s", bubble.getKey());
            mBubble.setCurrentTransition(this);
            final WindowContainerToken token = mTaskInfo.getToken();
            final Binder captionInsetsOwner = mBubble.getTaskView().getCaptionInsetsOwner();
            final WindowContainerTransaction wct =
                    getExitBubbleTransaction(mBubbleHelper, token, captionInsetsOwner);
            mTaskViewTransitions.enqueueExternal(
                    mBubble.getTaskView().getController(),
                    () -> {
                        mTransition = mTransitions.startTransition(TRANSIT_CHANGE, wct, this);
                        return mTransition;
                    });
        }

        @Override
        public void skip() {
            BubbleLog.d("ConvertFromBubble.skip()");
            final TaskViewTaskController tv = mBubble.getTaskView().getController();
            tv.notifyTaskRemovalStarted(tv.getTaskInfo());
            mTaskLeash = null;
            cleanup();
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @NonNull SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            skip();
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) {
                cleanup();
                return false;
            }
            BubbleLog.d("ConvertFromBubble.startAnimation()");

            final TaskViewTaskController tv =
                    mBubble.getTaskView().getController();
            if (tv == null) {
                cleanup();
                return false;
            }

            TransitionInfo.Change taskChg = null;

            boolean found = false;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change chg = info.getChanges().get(i);
                if (chg.getTaskInfo() == null) continue;
                if (chg.getMode() != TRANSIT_CHANGE && chg.getMode() != TRANSIT_TO_FRONT) continue;
                if (!mTaskInfo.token.equals(chg.getTaskInfo().token)) continue;
                found = true;
                mRepository.remove(tv);
                taskChg = chg;
                break;
            }

            if (!found) {
                Slog.w(TAG, "Expected a TaskView conversion in this transition but didn't get "
                        + "one, cleaning up the task view");
                tv.setTaskNotFound();
                skip();
                return false;
            }

            mTaskLeash = taskChg.getLeash();
            mRootLeash = info.getRoot(0).getLeash();
            mFinishCb = finishCallback;

            SurfaceControl dest = null;
            final View expandedView = getExpandedView(mBubble);
            if (expandedView != null) {
                ViewRootImpl viewRoot = expandedView.getViewRootImpl();
                if (viewRoot != null) {
                    dest = viewRoot.getSurfaceControl();
                }
            }
            final Runnable onPlucked = () -> {
                // Need to remove the taskview AFTER applying the startTransaction because
                // it isn't synchronized.
                tv.notifyTaskRemovalStarted(tv.getTaskInfo());
                // Unset after removeView so it can be used to pick a different animation.
                mBubbleData.setExpanded(false /* expanded */);
            };
            final Transitions.TransitionFinishCallback finishCb = wct -> {
                mFinishWct = wct;
                cleanup();
            };
            if (dest != null) {
                pluck(mTaskLeash, expandedView, dest,
                        taskChg.getStartAbsBounds().left - info.getRoot(0).getOffset().x,
                        taskChg.getStartAbsBounds().top - info.getRoot(0).getOffset().y,
                        getCornerRadius(mBubble), startTransaction,
                        onPlucked);
                expandedView.post(() -> mTransitions.dispatchTransition(
                        transition, info, startTransaction, finishTransaction, finishCb,
                        null));
            } else {
                onPlucked.run();
                mTransitions.dispatchTransition(transition, info, startTransaction,
                        finishTransaction, finishCb, null);
            }

            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            return true;
        }

        @Override
        public void continueCollapse() {
            mBubble.cleanupTaskView();
            if (mTaskLeash == null || !mTaskLeash.isValid() || !mRootLeash.isValid()) return;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.reparent(mTaskLeash, mRootLeash);
            t.apply();
        }

        private View getExpandedView(@NonNull Bubble bubble) {
            if (bubble.getBubbleBarExpandedView() != null) {
                return bubble.getBubbleBarExpandedView();
            }
            return bubble.getExpandedView();
        }

        private float getCornerRadius(@NonNull Bubble bubble) {
            if (bubble.getBubbleBarExpandedView() != null) {
                return bubble.getBubbleBarExpandedView().getCornerRadius();
            }
            return bubble.getExpandedView().getCornerRadius();
        }

        private void cleanup() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "ConvertFromBubble.cleanup()");
            mBubble.setCurrentTransition(null);
            if (mFinishCb != null) {
                // Trigger finishCb after reset current transition as it will immediately kick off
                // the next transition, which may set transition to the previous Bubble.
                mFinishCb.onTransitionFinished(mFinishWct);
                mFinishCb = null;
            }
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
        }
    }

    /**
     * A transition that converts a dragged bubble icon to a full screen window.
     *
     * <p>This transition assumes that the bubble is invisible so it is simply sent to front.
     */
    class DraggedBubbleIconToFullscreen implements TransitionHandler, BubbleTransition {

        IBinder mTransition;
        final Bubble mBubble;
        final Point mDropLocation;
        final TransactionProvider mTransactionProvider;
        private Transitions.TransitionFinishCallback mFinishCb;

        DraggedBubbleIconToFullscreen(Bubble bubble, Point dropLocation) {
            this(bubble, dropLocation, SurfaceControl.Transaction::new);
        }

        @VisibleForTesting
        DraggedBubbleIconToFullscreen(Bubble bubble, Point dropLocation,
                TransactionProvider transactionProvider) {
            BubbleLog.d("DraggedBubbleIconToFullscreen() key=%s", bubble.getKey());
            mBubble = bubble;
            mDropLocation = dropLocation;
            mTransactionProvider = transactionProvider;
            bubble.setCurrentTransition(this);
            final WindowContainerToken token = bubble.getTaskView().getTaskInfo().getToken();
            final Binder captionInsetsOwner = bubble.getTaskView().getCaptionInsetsOwner();
            final WindowContainerTransaction wct =
                    getExitBubbleTransaction(mBubbleHelper, token, captionInsetsOwner);
            wct.reorder(token, /* onTop= */ true);
            mTaskViewTransitions.enqueueExternal(bubble.getTaskView().getController(), () -> {
                mTransition = mTransitions.startTransition(TRANSIT_TO_FRONT, wct, this);
                return mTransition;
            });
        }

        @Override
        public void skip() {
            cleanup();
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) {
                cleanup();
                return false;
            }
            BubbleLog.d("DraggedBubbleIconToFullscreen.startAnimation()");

            mFinishCb = finishCallback;

            final TaskViewTaskController taskViewTaskController =
                    mBubble.getTaskView().getController();
            if (taskViewTaskController == null) {
                cleanup();
                return true;
            }

            TransitionInfo.Change change = findTransitionChange(info);
            if (change == null) {
                Slog.w(TAG, "Expected a TaskView transition to front but didn't find "
                        + "one, cleaning up the task view");
                taskViewTaskController.setTaskNotFound();
                cleanup();
                return true;
            }
            mRepository.remove(taskViewTaskController);

            final SurfaceControl taskLeash = change.getLeash();
            // set the initial position of the task with 0 scale
            startTransaction.setPosition(taskLeash, mDropLocation.x, mDropLocation.y);
            startTransaction.setScale(taskLeash, 0, 0);
            startTransaction.apply();

            final SurfaceControl.Transaction animT = mTransactionProvider.get();
            ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(250);
            animator.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(@NonNull Animator animation) {
                    float progress = animator.getAnimatedFraction();
                    float x = mDropLocation.x * (1 - progress);
                    float y = mDropLocation.y * (1 - progress);
                    animT.setPosition(taskLeash, x, y);
                    animT.setScale(taskLeash, progress, progress);
                    animT.apply();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(@NonNull Animator animation) {
                    animT.close();
                    cleanup();
                }
            });
            animator.start();
            taskViewTaskController.notifyTaskRemovalStarted(mBubble.getTaskView().getTaskInfo());
            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
            return true;
        }

        private TransitionInfo.Change findTransitionChange(TransitionInfo info) {
            TransitionInfo.Change result = null;
            WindowContainerToken token = mBubble.getTaskView().getTaskInfo().getToken();
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (change.getTaskInfo() == null) {
                    continue;
                }
                if (change.getMode() != TRANSIT_TO_FRONT) {
                    continue;
                }
                if (!token.equals(change.getTaskInfo().token)) {
                    continue;
                }
                result = change;
                break;
            }
            return result;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @NonNull TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @Nullable SurfaceControl.Transaction finishTransaction) {
            if (!aborted) {
                return;
            }
            cleanup();
        }

        private void cleanup() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "DraggedBubbleIconToFullscreen.cleanup()");
            mBubble.setCurrentTransition(null);
            if (mFinishCb != null) {
                // Trigger finishCb after reset current transition as it will immediately kick off
                // the next transition, which may set transition to the previous Bubble.
                mFinishCb.onTransitionFinished(null);
                mFinishCb = null;
            }
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
        }
    }

    /**
     * A transition to convert an expanded floating bubble to a bar bubble.
     *
     * <p>This transition class should be created after switching over to the new Bubbles window for
     * bubble bar mode, and adding the TaskView to the new window.
     *
     * <p>The transition is only started after calling {@link #continueConvert()}
     * once the bubble bar location on the screen is known.
     */
    class FloatingToBarConversion implements TransitionHandler, BubbleTransition {
        private final BubblePositioner mPositioner;
        private final Bubble mBubble;
        private final TransactionProvider mTransactionProvider;
        IBinder mTransition;
        private final Rect mBounds = new Rect();
        private final SurfaceControl mTaskLeash;
        private SurfaceControl.Transaction mFinishTransaction;
        private boolean mIsStarted = false;
        private boolean mHasBounds = false;
        private boolean mCanExpand = false;

        FloatingToBarConversion(Bubble bubble, BubblePositioner positioner) {
            this(bubble, SurfaceControl.Transaction::new, positioner);
        }

        @VisibleForTesting
        FloatingToBarConversion(Bubble bubble, TransactionProvider transactionProvider,
                BubblePositioner positioner) {
            mBubble = bubble;
            mBubble.setCurrentTransition(this);
            mTransactionProvider = transactionProvider;
            mPositioner = positioner;
            // with bubble root task, only the root task may be listed in the changes, so get the
            // bubble task leash directly from the task view.
            mTaskLeash = mBubble.getTaskView().getController().getTaskLeash();
            BubbleLog.d("FloatingToBarConversion() key=%s", bubble.getKey());
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @Nullable SurfaceControl.Transaction finishTransaction) {
            if (!aborted) return;
            cleanup();
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) {
                cleanup();
                return false;
            }
            BubbleLog.d("FloatingToBarConversion.startAnimation()");

            startTransaction.setAlpha(mTaskLeash, 0);
            startTransaction.apply();
            mFinishTransaction = finishTransaction;
            updateBubbleTask();
            cleanup();
            finishCallback.onTransitionFinished(null);
            return true;
        }

        @Override
        public void surfaceCreated() {
            if (canStart()) {
                startTransition();
            }
        }

        @Override
        public void continueConvert() {
            mHasBounds = true;
            if (canStart()) {
                startTransition();
            }
        }

        @Override
        public void continueExpand() {
            mCanExpand = true;
            if (canStart()) {
                startTransition();
            }
        }

        private boolean canStart() {
            return mHasBounds && mCanExpand && !mIsStarted && hasTaskInfo();
        }

        private boolean hasTaskInfo() {
            return mBubble.getTaskView().getTaskInfo() != null;
        }

        private void startTransition() {
            mIsStarted = true;
            final TaskView tv = mBubble.getTaskView();
            mPositioner.getTaskViewRestBounds(mBounds);
            WindowContainerTransaction wct = new WindowContainerTransaction();
            mTaskViewTransitions.updateTaskViewTaskBounds(wct, tv.getTaskInfo(), mBounds);
            mTransition = mTransitions.startTransition(TRANSIT_BUBBLE_CONVERT_FLOATING_TO_BAR,
                    wct, this);
            mTaskViewTransitions.removePendingTransitions(tv.getController());
            mTaskViewTransitions.enqueueRunningExternal(tv.getController(), mTransition);
        }

        @Override
        public void mergeWithUnfold(SurfaceControl taskLeash, SurfaceControl.Transaction finishT) {
            mFinishTransaction = finishT;
            updateBubbleTask();
            cleanup();
        }

        private void updateBubbleTask() {
            final TaskViewTaskController tvc = mBubble.getTaskView().getController();
            final SurfaceControl taskViewSurface = mBubble.getTaskView().getSurfaceControl();
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
            if (state == null) return;
            final View bubbleBarExpandedView = mBubble.getBubbleBarExpandedView();
            if (bubbleBarExpandedView == null) {
                BubbleLog.e("FloatingToBarConversion.updateBubbleTask %s"
                        + "bubbleBarExpandedView is null", mBubble.getKey());
                return;
            }
            state.mVisible = true;
            state.mBounds.set(mBounds);
            final SurfaceControl.Transaction startT = mTransactionProvider.get();

            // since the task view is switching windows, its surface needs to be moved over to the
            // new bubble window surface
            startT.reparent(taskViewSurface,
                    bubbleBarExpandedView.getViewRootImpl().updateAndGetBoundsLayer(startT));

            startT.reparent(mTaskLeash, taskViewSurface);
            startT.setPosition(mTaskLeash, 0, 0);
            startT.setCornerRadius(mTaskLeash,
                    mBubble.getBubbleBarExpandedView().getRestingCornerRadius());
            startT.setWindowCrop(mTaskLeash, mBounds.width(), mBounds.height());
            startT.setAlpha(mTaskLeash, 1);
            startT.apply();
            mFinishTransaction.reparent(mTaskLeash, taskViewSurface);
            mFinishTransaction.setPosition(mTaskLeash, 0, 0);
            mFinishTransaction.setWindowCrop(mTaskLeash, mBounds.width(), mBounds.height());
            mTaskViewTransitions.onExternalDone(mTransition);
            mTransition = null;
        }

        private void cleanup() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "FloatingToBarConversion.cleanup()");
            mBubble.setCurrentTransition(null);
            if (mTransition != null) {
                mTaskViewTransitions.onExternalDone(mTransition);
                mTransition = null;
            }
        }
    }

    /** A transition to convert an expanded bar bubble to a floating bubble. */
    class BarToFloatingConversion implements TransitionHandler, BubbleTransition {
        private final BubblePositioner mPositioner;
        private final Bubble mBubble;
        IBinder mTransition;
        private final Rect mBounds = new Rect();
        private SurfaceControl mTaskLeash;
        private boolean mIsStarted = false;
        private boolean mCanExpand = false;
        private boolean mSurfaceCreated = false;
        private IBinder mDummyTransition;

        @VisibleForTesting
        BarToFloatingConversion(Bubble bubble, BubblePositioner positioner) {
            mBubble = bubble;
            mBubble.setCurrentTransition(this);
            mPositioner = positioner;
            BubbleLog.d("BarToFloatingConversion key=%s", bubble.getKey());

            // enqueue a dummy transition to ignore task view bounds changes until the new views are
            // inflated and the task view is ready to be positioned.
            mDummyTransition = new Binder();
            mTaskViewTransitions.enqueueExternal(bubble.getTaskView().getController(),
                    () -> mDummyTransition);
        }

        @Override
        public void surfaceCreated() {
            mSurfaceCreated = true;
            if (canStart()) {
                startTransition();
            }
        }

        @Override
        public void continueExpand() {
            mCanExpand = true;
            if (canStart()) {
                startTransition();
            }
        }

        private boolean canStart() {
            return mCanExpand && mSurfaceCreated && !mIsStarted && hasTaskInfo();
        }

        private boolean hasTaskInfo() {
            return mBubble.getTaskView().getTaskInfo() != null;
        }

        private void startTransition() {
            mIsStarted = true;
            final TaskView tv = mBubble.getTaskView();
            mPositioner.getTaskViewRestBounds(mBounds);
            WindowContainerTransaction wct = new WindowContainerTransaction();
            mTaskViewTransitions.updateTaskViewTaskBounds(wct, tv.getTaskInfo(), mBounds);
            mTransition = mTransitions.startTransition(TRANSIT_CHANGE, wct, this);
        }

        @Override
        public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @Nullable TransitionRequestInfo request) {
            return null;
        }

        @Override
        public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startT,
                @NonNull SurfaceControl.Transaction finishT,
                @NonNull IBinder mergeTarget,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
        }

        @Override
        public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @Nullable SurfaceControl.Transaction finishTransaction) {
            cleanup();
        }

        @Override
        public boolean startAnimation(@NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull Transitions.TransitionFinishCallback finishCallback) {
            if (mTransition != transition) {
                cleanup();
                return false;
            }
            BubbleLog.d("BarToFloatingConversion.startAnimation for bubble %s", mBubble.getKey());

            final TaskViewTaskController taskViewTaskController =
                    mBubble.getTaskView().getController();

            // with bubble root task, only the root task may be listed in the changes, so get the
            // bubble task leash directly from the task view.
            mTaskLeash = taskViewTaskController.getTaskLeash();
            updateBubbleTask(startTransaction, finishTransaction);
            cleanup();
            finishCallback.onTransitionFinished(null);
            return true;
        }

        private void updateBubbleTask(SurfaceControl.Transaction startT,
                SurfaceControl.Transaction finishT) {
            final TaskView tv = mBubble.getTaskView();
            final TaskViewTaskController tvc = tv.getController();
            final SurfaceControl taskViewSurface = mBubble.getTaskView().getSurfaceControl();
            final TaskViewRepository.TaskViewState state = mRepository.byTaskView(tvc);
            if (state == null) return;
            final BubbleExpandedView expandedView = mBubble.getExpandedView();
            if (expandedView == null) {
                BubbleLog.e("BarToFloatingConversion.updateBubbleTask %s"
                        + "expandedView is null", mBubble.getKey());
                return;
            }
            state.mVisible = true;
            state.mBounds.set(mBounds);

            startT.reparent(mTaskLeash, taskViewSurface);
            startT.setPosition(mTaskLeash, 0, 0);
            startT.setCornerRadius(mTaskLeash, expandedView.getCornerRadius());
            startT.setWindowCrop(mTaskLeash, mBounds.width(), mBounds.height());
            startT.setAlpha(mTaskLeash, 1);
            startT.show(mTaskLeash);
            startT.apply();

            expandedView.setContentVisibility(true);

            finishT.reparent(mTaskLeash, taskViewSurface);
            finishT.setPosition(mTaskLeash, 0, 0);
            finishT.setWindowCrop(mTaskLeash, mBounds.width(), mBounds.height());

            mTaskViewTransitions.removePendingTransitions(tv.getController());
            mTaskViewTransitions.onExternalDone(mDummyTransition);
            mDummyTransition = null;
        }

        private void cleanup() {
            ProtoLog.d(WM_SHELL_BUBBLES_NOISY, "BarToFloatingConversion.cleanup");
            mBubble.setCurrentTransition(null);
            if (mDummyTransition != null) {
                mTaskViewTransitions.onExternalDone(mDummyTransition);
                mDummyTransition = null;
            }
        }
    }

    interface TransactionProvider {
        SurfaceControl.Transaction get();
    }
}
