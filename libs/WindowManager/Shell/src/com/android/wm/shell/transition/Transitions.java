/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_OCCLUDING;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_SLEEP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.fixScale;
import static android.window.TransitionInfo.FLAGS_IS_NON_APP_WINDOW;
import static android.window.TransitionInfo.FLAG_BACK_GESTURE_ANIMATED;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_BEHIND_STARTING_WINDOW;
import static android.window.TransitionInfo.FLAG_IS_OCCLUDED;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_MOVED_TO_TOP;
import static android.window.TransitionInfo.FLAG_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import static com.android.window.flags.Flags.unifyShellBinders;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TRANSITIONS;
import static com.android.wm.shell.shared.TransitionUtil.FLAG_IS_DESKTOP_WALLPAPER_ACTIVITY;
import static com.android.wm.shell.shared.TransitionUtil.isClosingType;
import static com.android.wm.shell.shared.TransitionUtil.isOpeningType;
import static com.android.wm.shell.shared.TransitionUtil.setUpSurface;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseBooleanArray;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.StartingWindowRemovalInfo;
import android.window.TaskFragmentOrganizer;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionMetrics;
import android.window.TransitionRequestInfo;
import android.window.WindowAnimationState;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.protolog.ProtoLog;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.dagger.UsedDownstream;
import com.android.wm.shell.desktopmode.DesktopModeTransitionTypes;
import com.android.wm.shell.desktopmode.DesktopWallpaperActivity;
import com.android.wm.shell.keyguard.KeyguardTransitionHandler;
import com.android.wm.shell.shared.FocusTransitionListener;
import com.android.wm.shell.shared.IFocusTransitionListener;
import com.android.wm.shell.shared.IHomeTransitionListener;
import com.android.wm.shell.shared.IOverviewOverlayLeashInvalidationCallback;
import com.android.wm.shell.shared.IShellTransitions;
import com.android.wm.shell.shared.ShellTransitions;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.shared.annotations.ExternalThread;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.tracing.PerfettoTransitionTracer;
import com.android.wm.shell.transition.tracing.TransitionTracer;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Plays transition animations. Within this player, each transition has a lifecycle.
 * 1. When a transition is directly started or requested, it is added to "pending" state.
 * 2. Once WMCore applies the transition and notifies, the transition moves to "ready" state.
 * 3. When a transition starts animating, it is moved to the "active" state.
 *
 * Basically: --start--> PENDING --onTransitionReady--> READY --play--> ACTIVE --finish--> |
 *                                                            --merge--> MERGED --^
 *
 * The READY and beyond lifecycle is managed per "track". Within a track, all the animations are
 * serialized as described; however, multiple tracks can play simultaneously. This implies that,
 * within a track, only one transition can be animating ("active") at a time.
 *
 * While a transition is animating in a track, transitions dispatched to the track will be queued
 * in the "ready" state for their turn. At the same time, whenever a transition makes it to the
 * head of the "ready" queue, it will attempt to merge to with the "active" transition. If the
 * merge succeeds, it will be moved to the "active" transition's "merged" list and then the next
 * "ready" transition can attempt to merge. Once the "active" transition animation is finished,
 * the next "ready" transition can play.
 *
 * Track assignments are expected to be provided by WMCore and this generally tries to maintain
 * the same assignments. If, however, WMCore decides that a transition conflicts with >1 active
 * track, it will be marked as SYNC. This means that all currently active tracks must be flushed
 * before the SYNC transition can play.
 */
public class Transitions implements RemoteCallable<Transitions>,
        ShellCommandHandler.ShellCommandActionHandler {
    static final String TAG = "ShellTransitions";

    // If set, will print the stack trace for transition starts/finishes within the process
    static final boolean DEBUG_START_TRANSITION = Build.IS_DEBUGGABLE &&
            SystemProperties.getBoolean("persist.wm.debug.start_shell_transition", false);
    static final boolean DEBUG_FINISH_TRANSITION = Build.IS_DEBUGGABLE &&
            SystemProperties.getBoolean("persist.wm.debug.finish_shell_transition", false);

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS = true;
    public static final boolean SHELL_TRANSITIONS_ROTATION =
            SystemProperties.getBoolean("persist.wm.debug.shell_transit_rotate", false);

    /** Transition type for exiting PIP via the Shell, via pressing the expand button. */
    public static final int TRANSIT_EXIT_PIP = TRANSIT_FIRST_CUSTOM + 1;

    public static final int TRANSIT_EXIT_PIP_TO_SPLIT =  TRANSIT_FIRST_CUSTOM + 2;

    /** Transition type for removing PIP via the Shell, either via Dismiss bubble or Close. */
    public static final int TRANSIT_REMOVE_PIP = TRANSIT_FIRST_CUSTOM + 3;

    /** Transition type for launching 2 tasks simultaneously. */
    public static final int TRANSIT_SPLIT_SCREEN_PAIR_OPEN = TRANSIT_FIRST_CUSTOM + 4;

    /** Transition type for entering split by opening an app into side-stage. */
    public static final int TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE = TRANSIT_FIRST_CUSTOM + 5;

    /** Transition type for dismissing split-screen via dragging the divider off the screen. */
    public static final int TRANSIT_SPLIT_DISMISS_SNAP = TRANSIT_FIRST_CUSTOM + 6;

    /** Transition type for dismissing split-screen. */
    public static final int TRANSIT_SPLIT_DISMISS = TRANSIT_FIRST_CUSTOM + 7;

    /** Transition type for freeform to maximize transition. */
    public static final int TRANSIT_MAXIMIZE = WindowManager.TRANSIT_FIRST_CUSTOM + 8;

    /** Transition type for maximize to freeform transition. */
    public static final int TRANSIT_RESTORE_FROM_MAXIMIZE = WindowManager.TRANSIT_FIRST_CUSTOM + 9;

    /**
     * Transition to change the bounds of a PiP task, either by resizing or moving to another
     * display.
     */
    public static final int TRANSIT_PIP_BOUNDS_CHANGE = TRANSIT_FIRST_CUSTOM + 16;

    /**
     * The task fragment drag resize transition used by activity embedding.
     */
    public static final int TRANSIT_TASK_FRAGMENT_DRAG_RESIZE =
            // TRANSIT_FIRST_CUSTOM + 17
            TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_DRAG_RESIZE;

    /** Remote Transition that split accepts but ultimately needs to be animated by the remote. */
    public static final int TRANSIT_SPLIT_PASSTHROUGH = TRANSIT_FIRST_CUSTOM + 18;

    /** Transition to set windowing mode after exit pip transition is finished animating. */
    public static final int TRANSIT_CLEANUP_PIP_EXIT = WindowManager.TRANSIT_FIRST_CUSTOM + 19;

    /** Transition type to minimize a task. */
    public static final int TRANSIT_MINIMIZE = WindowManager.TRANSIT_FIRST_CUSTOM + 20;

    /** Transition to start the recents transition */
    public static final int TRANSIT_START_RECENTS_TRANSITION = TRANSIT_FIRST_CUSTOM + 21;

    /** Transition to end the recents transition */
    public static final int TRANSIT_END_RECENTS_TRANSITION = TRANSIT_FIRST_CUSTOM + 22;

    /** Transition type for app compat reachability. */
    public static final int TRANSIT_MOVE_LETTERBOX_REACHABILITY = TRANSIT_FIRST_CUSTOM + 23;

    /** Transition type for converting a task to a bubble. */
    public static final int TRANSIT_CONVERT_TO_BUBBLE = TRANSIT_FIRST_CUSTOM + 24;

    /** Transition type for converting a floating bubble to a bar bubble. */
    public static final int TRANSIT_BUBBLE_CONVERT_FLOATING_TO_BAR = TRANSIT_FIRST_CUSTOM + 25;

    /** Transition type for cancelling split-screen. */
    public static final int TRANSIT_SPLIT_CANCEL = TRANSIT_FIRST_CUSTOM + 26;

    /** Transition type for desktop mode transitions. */
    public static final int TRANSIT_DESKTOP_MODE_TYPES =
            WindowManager.TRANSIT_FIRST_CUSTOM + 100;

    private final ShellTaskOrganizer mOrganizer;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionLeashManager mTransitionLeashManager;
    private final TransitionPlayerImpl mPlayerImpl;
    private final DefaultTransitionHandler mDefaultTransitionHandler;
    private final RemoteTransitionHandler mRemoteTransitionHandler;
    private final DisplayController mDisplayController;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellController mShellController;
    private final ShellTransitionImpl mImpl = new ShellTransitionImpl();
    private final SleepHandler mSleepHandler = new SleepHandler();
    private final TransitionTracer mTransitionTracer;

    private final TransactionPool mTransactionPool;
    private final TransitionMixpatcher mMixpatcher;
    private final ActivityPlanner mActivityPlanner;

    /** List of possible handlers. Ordered by specificity (eg. tapped back to front). */
    private final ArrayList<TransitionHandler> mHandlers = new ArrayList<>();

    private final ArrayList<TransitionObserver> mObservers = new ArrayList<>();

    private HomeTransitionObserver mHomeTransitionObserver;
    private FocusTransitionObserver mFocusTransitionObserver;

    /** List of {@link Runnable} instances to run when the last active transition has finished.  */
    private final ArrayList<Runnable> mRunWhenIdleQueue = new ArrayList<>();

    /** Surfaces to be released after all transitions have completed. */
    private final ArrayList<SurfaceControl> mCleanupSurfaces = new ArrayList<>();

    private float mTransitionAnimationScaleSetting = 1.0f;

    /**
     * How much time we allow for an animation to finish itself on sync. If it takes longer, we
     * will force-finish it (on this end) which may leave it in a bad state but won't hang the
     * device. This needs to be pretty small because it is an allowance for each queued animation,
     * however it can't be too small since there is some potential IPC involved.
     */
    private static final int SYNC_ALLOWANCE_MS = 120;

    /** For testing only. Disables the force-finish timeout on sync. */
    private boolean mDisableForceSync = false;

    private static final class ActiveTransition {
        final IBinder mToken;

        TransitionHandler mHandler;
        boolean mAborted;
        TransitionInfo mInfo;
        SurfaceControl.Transaction mStartT;
        SurfaceControl.Transaction mFinishT;
        List<ITransitionPlanner> mInterestedPlanners;

        /** Ordered list of transitions which have been merged into this one. */
        private ArrayList<ActiveTransition> mMerged;

        /** When using mixpatcher, this tracks the anim wrapper across multiple plannings. */
        MixpatchAnimationWrapper mMixpatchWrapper;

        ActiveTransition(IBinder token) {
            mToken = token;
        }

        boolean isSync() {
            return (mInfo.getFlags() & TransitionInfo.FLAG_SYNC) != 0;
        }

        int getTrack() {
            return mInfo != null ? mInfo.getTrack() : -1;
        }

        @Override
        public String toString() {
            if (mInfo != null && mInfo.getDebugId() >= 0) {
                return "(#" + mInfo.getDebugId() + ") " + mToken + "@" + getTrack();
            }
            return mToken.toString() + "@" + getTrack();
        }
    }

    private static class Track {
        /** Keeps track of transitions which are ready to play but still waiting for their turn. */
        final ArrayList<ActiveTransition> mReadyTransitions = new ArrayList<>();

        /** The currently playing transition in this track. */
        ActiveTransition mActiveTransition = null;

        boolean isIdle() {
            return mActiveTransition == null && mReadyTransitions.isEmpty();
        }
    }

    /** All transitions that we have created, but not yet finished. */
    private final ArrayMap<IBinder, ActiveTransition> mKnownTransitions = new ArrayMap<>();

    /** Keeps track of transitions which have been started, but aren't ready yet. */
    private final ArrayList<ActiveTransition> mPendingTransitions = new ArrayList<>();

    /**
     * Transitions which are ready to play, but haven't been sent to a track yet because a sync
     * is ongoing.
     */
    private final ArrayList<ActiveTransition> mReadyDuringSync = new ArrayList<>();

    private final ArrayList<Track> mTracks = new ArrayList<>();

    private final MixpatchLegacyPlanner mMixpatchLegacyPlanner = new MixpatchLegacyPlanner(null);
    final MixpatchLegacyPrePlanner mMixpatchLegacyPrePlanner = new MixpatchLegacyPrePlanner();
    private final ArrayList<MixpatchAnimationWrapper> mMixpatchAnimations = new ArrayList<>();
    private final WindowContainerToken mSleepOrKeyguardProxy =
            WindowContainerToken.createProxy("LegacySleepOrKG");

    public Transitions(@NonNull Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull ShellTaskOrganizer organizer,
            @NonNull TransactionPool pool,
            @NonNull DisplayController displayController,
            @NonNull DisplayInsetsController displayInsetsController,
            @NonNull ShellExecutor mainExecutor,
            @NonNull Handler mainHandler,
            @NonNull ShellExecutor animExecutor,
            @NonNull TransitionLeashManager transitionLeashManager,
            @NonNull HomeTransitionObserver homeTransitionObserver,
            @NonNull FocusTransitionObserver focusTransitionObserver) {
        this(context, shellInit, new ShellCommandHandler(), shellController, organizer, pool,
                displayController, displayInsetsController, mainExecutor, mainHandler, animExecutor,
                transitionLeashManager,
                new RootTaskDisplayAreaOrganizer(mainExecutor, context, shellInit),
                homeTransitionObserver, focusTransitionObserver);
    }

    public Transitions(@NonNull Context context,
            @NonNull ShellInit shellInit,
            @Nullable ShellCommandHandler shellCommandHandler,
            @NonNull ShellController shellController,
            @NonNull ShellTaskOrganizer organizer,
            @NonNull TransactionPool pool,
            @NonNull DisplayController displayController,
            @NonNull DisplayInsetsController displayInsetsController,
            @NonNull ShellExecutor mainExecutor,
            @NonNull Handler mainHandler,
            @NonNull ShellExecutor animExecutor,
            @NonNull TransitionLeashManager transitionLeashManager,
            @NonNull RootTaskDisplayAreaOrganizer rootTDAOrganizer,
            @NonNull HomeTransitionObserver homeTransitionObserver,
            @NonNull FocusTransitionObserver focusTransitionObserver) {
        mOrganizer = organizer;
        mContext = context;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mTransitionLeashManager = transitionLeashManager;
        mDisplayController = displayController;
        mPlayerImpl = new TransitionPlayerImpl();
        mDefaultTransitionHandler = new DefaultTransitionHandler(context, shellInit,
                displayController, displayInsetsController, pool, mainExecutor, mainHandler,
                animExecutor, rootTDAOrganizer, InteractionJankMonitor.getInstance());
        mRemoteTransitionHandler =
                new RemoteTransitionHandler(mMainExecutor, mTransitionLeashManager);
        mShellCommandHandler = shellCommandHandler;
        mShellController = shellController;
        // The very last handler (0 in the list) should be the default one.
        mHandlers.add(mDefaultTransitionHandler);
        ProtoLog.v(WM_SHELL_TRANSITIONS, "addHandler: Default");
        // Next lowest priority is remote transitions.
        mHandlers.add(mRemoteTransitionHandler);
        ProtoLog.v(WM_SHELL_TRANSITIONS, "addHandler: Remote");
        shellInit.addInitCallback(this::onInit, this);
        mHomeTransitionObserver = homeTransitionObserver;
        mFocusTransitionObserver = focusTransitionObserver;
        mTransactionPool = pool;

        mTransitionTracer = new PerfettoTransitionTracer();
        if (com.android.window.flags.Flags.transitMixpatcherBase()) {
            mMixpatcher = new TransitionMixpatcher(mOrganizer, mMainExecutor);
            mMixpatcher.overridePrePlanner(mMixpatchLegacyPrePlanner);
            mActivityPlanner = new ActivityPlanner(context, pool,
                    displayController, displayInsetsController, mainExecutor, animExecutor);

            addPlanner(mMixpatchLegacyPlanner);
            addPlanner(mActivityPlanner);
        } else {
            mMixpatcher = null;
            mActivityPlanner = null;
        }
    }

    private void onInit() {
        mOrganizer.shareTransactionQueue();
        mShellController.addExternalInterface(IShellTransitions.DESCRIPTOR,
                this::createExternalInterface, this);

        ContentResolver resolver = mContext.getContentResolver();
        mTransitionAnimationScaleSetting = getTransitionAnimationScaleSetting();
        dispatchAnimScaleSetting(mTransitionAnimationScaleSetting);

        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false,
                new SettingsObserver());

        // Register this transition handler with Core
        if (unifyShellBinders()) {
            mOrganizer.initializeDependencies(this);
        } else {
            try {
                mOrganizer.registerTransitionPlayer(mPlayerImpl);
            } catch (RuntimeException e) {
                throw e;
            }
        }
        // Pre-load the instance.
        TransitionMetrics.getInstance();

        mShellCommandHandler.addCommandCallback("transitions", this, this);
        mShellCommandHandler.addDumpCallback(this::dump, this);
    }

    private float getTransitionAnimationScaleSetting() {
        return fixScale(Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, mContext.getResources().getFloat(
                                R.dimen.config_appTransitionAnimationDurationScaleDefault)));
    }

    public ShellTransitions asRemoteTransitions() {
        return mImpl;
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new IShellTransitionsImpl(this);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    private void dispatchAnimScaleSetting(float scale) {
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            mHandlers.get(i).setAnimScaleSetting(scale);
        }
        if (mMixpatcher != null) {
            mMixpatcher.setAnimScaleSetting(scale);
        }
    }

    /**
     * Adds a handler candidate.
     * @see TransitionHandler
     */
    public void addHandler(@NonNull TransitionHandler handler) {
        if (mHandlers.isEmpty()) {
            throw new RuntimeException("Unexpected handler added prior to initialization, please "
                    + "use ShellInit callbacks to ensure proper ordering");
        }
        mHandlers.add(handler);
        // Set initial scale settings.
        handler.setAnimScaleSetting(mTransitionAnimationScaleSetting);
        ProtoLog.v(WM_SHELL_TRANSITIONS, "addHandler: %s",
                handler.getClass().getSimpleName());
    }

    /**
     * Adds an {@link ITransitionPlanner} to the mixpatcher. Registered planners are traversed
     * in reverse order, meaning the most recently registered planner is used first.
     * @see ITransitionPlanner
     */
    public void addPlanner(@NonNull ITransitionPlanner planner) {
        if (com.android.window.flags.Flags.transitMixpatcherBase()) {
            ProtoLog.v(WM_SHELL_TRANSITIONS, "addPlanner: %s", planner.getDebugName());
            mMixpatcher.mPlanners.add(planner);
        }
    }

    public ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    public ShellExecutor getAnimExecutor() {
        return mAnimExecutor;
    }

    public TransitionLeashManager getLeashManager() {
        return mTransitionLeashManager;
    }

    /** Only use this in tests. This is used to avoid running animations during tests. */
    @VisibleForTesting
    void replaceDefaultHandlerForTest(TransitionHandler handler) {
        mHandlers.set(0, handler);
    }

    /**
     * Register a remote transition to be used for all operations except takeovers when its filter
     * matches an incoming transition.
     */
    public void registerRemote(@NonNull RemoteTransition remoteTransition) {
        mRemoteTransitionHandler.addFiltered(remoteTransition);
    }

    /**
     * Register a remote transition to be used only for takeovers when `filter`
     * matches an incoming transition.
     */
    public void registerRemoteForTakeover(@NonNull RemoteTransition remoteTransition) {
        mRemoteTransitionHandler.addFilteredForTakeover(remoteTransition);
    }

    /** Unregisters a remote transition and all associated filters */
    public void unregisterRemote(@NonNull RemoteTransition remoteTransition) {
        mRemoteTransitionHandler.removeFiltered(remoteTransition);
    }

    /**
     * Check whether a given TransitionInfo object would be handled by the TransitionFilter(s)
     * registered with the RemoteTransitionHandler.
     *
     * @param info the TransitionInfo to check with the RemoteTransitionHandler.
     * @return true if the info matches with a registered TransitionFilter, otherwise false.
     */
    @UsedDownstream(product="wear")
    public boolean matchesRemoteFilter(TransitionInfo info) {
        for (Pair<TransitionFilter, RemoteTransition> filterPair
                : mRemoteTransitionHandler.mFilters) {
            if (filterPair.first.matches(info)) {
                return true;
            }
        }
        return false;
    }

    RemoteTransitionHandler getRemoteTransitionHandler() {
        return mRemoteTransitionHandler;
    }

    /** Registers an observer on the lifecycle of transitions. */
    public void registerObserver(@NonNull TransitionObserver observer) {
        mObservers.add(observer);
    }

    /** Unregisters the observer. */
    public void unregisterObserver(@NonNull TransitionObserver observer) {
        mObservers.remove(observer);
    }

    /** Boosts the process priority of remote animation player. */
    public static void setRunningRemoteTransitionDelegate(IBinder transitionToken) {
        if (transitionToken == null) return;
        try {
            ActivityTaskManager.getService().setRunningRemoteTransitionDelegate(transitionToken);
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to boost animation process. This should only happen"
                    + " during unit tests");
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Runs the given {@code runnable} when the last active transition has finished, or immediately
     * if there are currently no active transitions.
     *
     * <p>This method should be called on the Shell main-thread, where the given {@code runnable}
     * will be executed when the last active transition is finished.
     */
    public void runOnIdle(Runnable runnable) {
        if (com.android.window.flags.Flags.transitMixpatcherBase()) {
            mMixpatcher.runOnIdle(runnable);
            return;
        }
        if (isIdle()) {
            runnable.run();
        } else {
            mRunWhenIdleQueue.add(runnable);
        }
    }

    void setDisableForceSyncForTest(boolean disable) {
        mDisableForceSync = disable;
    }

    /**
     * Sets up visibility/alpha/transforms to resemble the starting state of an animation.
     */
    @VisibleForTesting
    static void setupStartState(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        final SparseBooleanArray isRevealingOnDisplay = new SparseBooleanArray();
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.hasFlags(FLAGS_IS_NON_APP_WINDOW & ~FLAG_IS_WALLPAPER)) {
                // Currently system windows are controlled by WindowState, so don't change their
                // surfaces. Otherwise their surfaces could be hidden or cropped unexpectedly.
                // This includes IME (associated with app), because there may not be a transition
                // associated with their visibility changes, and currently they don't need a
                // transition animation.
                continue;
            }
            final SurfaceControl leash = change.getLeash();
            final int mode = change.getMode();

            if (mode == TRANSIT_TO_FRONT) {
                // When the window is moved to front, make sure the crop is updated to prevent it
                // from using the old crop.
                t.setPosition(leash, change.getEndRelOffset().x, change.getEndRelOffset().y);
                if (change.getActivityTransitionInfo() == null) {
                    // We don't want to crop if it's an activity, because it can have
                    // letterbox child surface that is position at a negative position related to
                    // the activity's surface.
                    t.setWindowCrop(leash, change.getEndAbsBounds().width(),
                            change.getEndAbsBounds().height());
                }
            }

            // Don't move anything that isn't independent within its parents
            if (!TransitionInfo.isIndependent(change, info)) {
                if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT || mode == TRANSIT_CHANGE) {
                    t.show(leash);
                    t.setMatrix(leash, 1, 0, 0, 1);
                    t.setAlpha(leash, 1.f);
                    t.setPosition(leash, change.getEndRelOffset().x, change.getEndRelOffset().y);
                    if (change.getActivityTransitionInfo() == null) {
                        // We don't want to crop if it's an activity, because it can have
                        // letterbox child surface that is position at a negative position related
                        // to the activity's surface.
                        t.setWindowCrop(leash, change.getEndAbsBounds().width(),
                                change.getEndAbsBounds().height());
                    }
                }
                continue;
            }
            boolean isOpening;
            if (com.android.window.flags.Flags.crossDisplayTransitionV2()) {
                // If a window is closing or moving to another display, it reveals the content
                // behind it.
                if (isClosingType(mode) || change.getStartDisplayId() != change.getEndDisplayId()) {
                    isRevealingOnDisplay.put(change.getStartDisplayId(), true);
                }
                // TODO(b/486224132): make predictive-back transitions consistent
                // This prevents windows already being animated by a back navigation gesture from
                // being incorrectly hidden at the start of the transition.
                final boolean isExcluded =
                        change.hasFlags(FLAG_MOVED_TO_TOP | FLAG_BACK_GESTURE_ANIMATED);
                isOpening = !isRevealingOnDisplay.get(change.getEndDisplayId()) && isExcluded;
            } else {
                isOpening = isOpeningType(info.getType());
            }
            if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                t.show(leash);
                t.setMatrix(leash, 1, 0, 0, 1);
                if (isOpening
                        // If this is a transferred starting window, we want it immediately visible.
                        && (change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) == 0) {
                    t.setAlpha(leash, 0.f);
                }
                finishT.show(leash);
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                finishT.hide(leash);
            } else if (isOpening && mode == TRANSIT_CHANGE) {
                // Just in case there is a race with another animation (eg. recents finish()).
                // Changes are visible->visible so it's a problem if it isn't visible.
                t.show(leash);
                // If there is a transient launch followed by a launch of one of the pausing tasks,
                // we may end up with TRANSIT_TO_BACK followed by a CHANGE (w/ flag MOVE_TO_TOP),
                // but since we are hiding the leash in the finish transaction above, we should also
                // update the finish transaction here to reflect the change in visibility
                finishT.show(leash);
            }
        }
    }

    /**
     * Reparents all participants into a shared parent and orders them based on: the global transit
     * type, their transit mode, and their destination z-order.
     */
    private static void setupAnimHierarchy(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        for (int i = 0; i < info.getRootCount(); ++i) {
            t.show(info.getRoot(i).getLeash());
        }

        // changes should be ordered top-to-bottom in z
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            setUpSurface(info.getChanges().get(i), info, i, t);
        }
    }

    private static int findByToken(ArrayList<ActiveTransition> list, IBinder token) {
        for (int i = list.size() - 1; i >= 0; --i) {
            if (list.get(i).mToken == token) return i;
        }
        return -1;
    }

    private Track getOrCreateTrack(int trackId) {
        while (trackId >= mTracks.size()) {
            mTracks.add(new Track());
        }
        return mTracks.get(trackId);
    }

    /** @see ITransitionPlayer#onTransitionReady */
    public void onTransitionReady(@NonNull IBinder transitionToken, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        if (com.android.window.flags.Flags.transitMixpatcherBase()) {
            mMixpatcher.onTransitionReady(transitionToken, info, t, finishT);
            return;
        }
        onTransitionReadyInner(transitionToken, info, t, finishT);
    }

    private void onTransitionReadyInner(@NonNull IBinder transitionToken,
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl.Transaction finishT) {
        info.setUnreleasedWarningCallSiteForAllSurfaces("Transitions.onTransitionReady");
        ProtoLog.v(WM_SHELL_TRANSITIONS, "onTransitionReady (#%d) %s: %s",
                info.getDebugId(), transitionToken, info.toString("    " /* prefix */));
        int activeIdx = findByToken(mPendingTransitions, transitionToken);
        if (activeIdx < 0) {
            final ActiveTransition existing = mKnownTransitions.get(transitionToken);
            if (existing != null) {
                Log.e(TAG, "Got duplicate transitionReady for " + transitionToken);
                // The transition is already somewhere else in the pipeline, so just return here.
                info.releaseAnimSurfaces();
                t.apply();
                if (existing.mFinishT != null) {
                    existing.mFinishT.merge(finishT);
                } else {
                    existing.mFinishT = finishT;
                }
                return;
            }
            // This usually means the system is in a bad state and may not recover; however,
            // there's an incentive to propagate bad states rather than crash, so we're kinda
            // required to do the same thing I guess.
            Log.wtf(TAG, "Got transitionReady for non-pending transition "
                    + transitionToken + ". expecting one of "
                    + Arrays.toString(mPendingTransitions.stream().map(
                            activeTransition -> activeTransition.mToken).toArray()));
            final ActiveTransition fallback = new ActiveTransition(transitionToken);
            mKnownTransitions.put(transitionToken, fallback);
            mPendingTransitions.add(fallback);
            activeIdx = mPendingTransitions.size() - 1;
        }
        // Move from pending to ready
        final ActiveTransition active = mPendingTransitions.remove(activeIdx);
        active.mInfo = info;
        active.mStartT = t;
        active.mFinishT = finishT;
        if (activeIdx > 0) {
            Log.i(TAG, "Transition might be ready out-of-order " + activeIdx + " for " + active
                    + ". This is ok if it's on a different track.");
        }
        if (!mReadyDuringSync.isEmpty()) {
            mReadyDuringSync.add(active);
        } else {
            dispatchReady(active);
        }
    }

    /**
     * Returns true if dispatching succeeded, otherwise false. Dispatching can fail if it is
     * blocked by a sync or sleep.
     */
    boolean dispatchReady(ActiveTransition active) {
        final TransitionInfo info = active.mInfo;

        if (info.getType() == TRANSIT_SLEEP || active.isSync()) {
            // Adding to *front*! If we are here, it means that it was pulled off the front
            // so we are just putting it back; or, it is the first one so it doesn't matter.
            mReadyDuringSync.add(0, active);
            boolean hadPreceding = false;
            // Now flush all the tracks.
            for (int i = 0; i < mTracks.size(); ++i) {
                final Track tr = mTracks.get(i);
                if (tr.isIdle()) continue;
                hadPreceding = true;
                // Sleep starts a process of forcing all prior transitions to finish immediately
                ProtoLog.v(WM_SHELL_TRANSITIONS,
                        "Start finish-for-sync track %d", i);
                finishForSync(active.mToken, i, null /* forceFinish */);
            }
            if (hadPreceding) {
                return false;
            }
            // Actually able to process the sleep now, so re-remove it from the queue and continue
            // the normal flow.
            mReadyDuringSync.remove(active);
        }

        // If any of the changes are on DesktopWallpaperActivity, add the flag to the change.
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getTaskInfo() != null
                    && DesktopWallpaperActivity.isWallpaperTask(change.getTaskInfo())) {
                change.setFlags(change.getFlags() | FLAG_IS_DESKTOP_WALLPAPER_ACTIVITY);
            }
        }

        final Track track = getOrCreateTrack(info.getTrack());
        track.mReadyTransitions.add(active);

        for (int i = 0; i < mObservers.size(); ++i) {
            final boolean useTrace = Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER);
            if (useTrace) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                        mObservers.get(i).getClass().getSimpleName() + "#onTransitionReady: "
                                + transitTypeToString(info.getType()));
            }
            mObservers.get(i).onTransitionReady(
                    active.mToken, info, active.mStartT, active.mFinishT);
            if (useTrace) {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }

        /*
         * Some transitions we always need to report to keyguard even if they are empty.
         * TODO (b/274954192): Remove this once keyguard dispatching fully moves to Shell.
         */
        if (info.getRootCount() == 0 && !KeyguardTransitionHandler.handles(info)) {
            // No root-leashes implies that the transition is empty/no-op, so just do
            // housekeeping and return.
            ProtoLog.v(WM_SHELL_TRANSITIONS, "No transition roots in %s so abort", active);
            onAbort(active);
            return true;
        }

        final int changeSize = info.getChanges().size();
        boolean taskChange = false;
        boolean transferStartingWindow = false;
        int animBehindStartingWindow = 0;
        boolean allOccluded = changeSize > 0;
        for (int i = changeSize - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            taskChange |= change.getTaskInfo() != null;
            transferStartingWindow |= change.hasFlags(FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT);
            if (change.hasAllFlags(FLAG_IS_BEHIND_STARTING_WINDOW | FLAG_NO_ANIMATION)
                    || change.hasAllFlags(
                            FLAG_IS_BEHIND_STARTING_WINDOW | FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY)) {
                animBehindStartingWindow++;
            }
            if (!change.hasFlags(FLAG_IS_OCCLUDED)) {
                allOccluded = false;
            } else if (change.hasAllFlags(TransitionInfo.FLAGS_IS_OCCLUDED_NO_ANIMATION)) {
                // Remove the change because it should be invisible in the animation.
                info.getChanges().remove(i);
                continue;
            }
        }
        // There does not need animation when:
        // A. Transfer starting window. Apply transfer starting window directly if there is no other
        // task change. Since this is an activity->activity situation, we can detect it by selecting
        // transitions with changes where
        // 1. none are tasks, and
        // 2. one is a starting-window recipient, or all change is behind starting window.
        if (!taskChange && (transferStartingWindow || animBehindStartingWindow == changeSize)
                && changeSize >= 1
                // B. It's visibility change if the TRANSIT_TO_BACK/TO_FRONT happened when all
                // changes are underneath another change.
                || ((info.getType() == TRANSIT_TO_BACK || info.getType() == TRANSIT_TO_FRONT)
                && allOccluded)) {
            // Treat this as an abort since we are bypassing any merge logic and effectively
            // finishing immediately.
            ProtoLog.v(WM_SHELL_TRANSITIONS,
                    "Non-visible anim so abort: %s", active);
            onAbort(active);
            return true;
        }

        setupStartState(active.mInfo, active.mStartT, active.mFinishT);

        if (track.mReadyTransitions.size() > 1) {
            // There are already transitions waiting in the queue, so just return.
            return true;
        }
        processReadyQueue(track);
        return true;
    }

    private boolean areTracksIdle() {
        for (int i = 0; i < mTracks.size(); ++i) {
            if (!mTracks.get(i).isIdle()) return false;
        }
        return true;
    }

    private boolean isAnimating() {
        return !mReadyDuringSync.isEmpty() || !areTracksIdle();
    }

    private boolean isIdle() {
        return mPendingTransitions.isEmpty() && !isAnimating();
    }

    void processReadyQueue(Track track) {
        if (track.mReadyTransitions.isEmpty()) {
            if (track.mActiveTransition == null) {
                ProtoLog.v(WM_SHELL_TRANSITIONS, "Track %d became idle",
                        mTracks.indexOf(track));
                if (areTracksIdle()) {
                    if (!mReadyDuringSync.isEmpty()) {
                        // Dispatch everything unless we hit another sync
                        while (!mReadyDuringSync.isEmpty()) {
                            ActiveTransition next = mReadyDuringSync.remove(0);
                            boolean success = dispatchReady(next);
                            // Hit a sync or sleep, so stop dispatching.
                            if (!success) break;
                        }
                    } else if (mPendingTransitions.isEmpty()) {
                        ProtoLog.v(WM_SHELL_TRANSITIONS, "All active transition "
                                + "animations finished");
                        mKnownTransitions.clear();
                        // Run all runnables from the run-when-idle queue.
                        for (int i = 0; i < mRunWhenIdleQueue.size(); i++) {
                            mRunWhenIdleQueue.get(i).run();
                        }
                        mRunWhenIdleQueue.clear();
                        releaseCleanupSurfaces();
                    }
                }
            }
            return;
        }
        final ActiveTransition ready = track.mReadyTransitions.get(0);
        if (track.mActiveTransition == null) {
            // The normal case, just play it.
            track.mReadyTransitions.remove(0);
            track.mActiveTransition = ready;
            if (ready.mAborted) {
                if (ready.mStartT != null) {
                    ready.mStartT.apply();
                }
                // finish now since there's nothing to animate. Calls back into processReadyQueue
                onFinish(ready.mToken, null);
                return;
            }
            playTransitionWithTracing(ready);
            // Attempt to merge any more queued-up transitions.
            processReadyQueue(track);
            return;
        }
        // An existing animation is playing, so see if we can merge.
        final ActiveTransition playing = track.mActiveTransition;
        final IBinder playingToken = playing.mToken;
        final IBinder readyToken = ready.mToken;

        if (ready.mAborted) {
            // record as merged since it is no-op. Calls back into processReadyQueue
            onMerged(playingToken, readyToken);
            return;
        }
        ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition %s ready while"
                + " %s is still animating. Notify the animating transition"
                + " in case they can be merged", ready, playing);
        mTransitionTracer.logMergeRequested(ready.mInfo.getDebugId(), playing.mInfo.getDebugId());
        playing.mHandler.mergeAnimation(ready.mToken, ready.mInfo, ready.mStartT, ready.mFinishT,
                playing.mToken, (wct) -> onMerged(playingToken, readyToken));
    }

    private void onMerged(@NonNull IBinder playingToken, @NonNull IBinder mergedToken) {
        mMainExecutor.assertCurrentThread();

        ActiveTransition playing = mKnownTransitions.get(playingToken);
        if (playing == null) {
            Log.e(TAG, "Merging into a non-existent transition: " + playingToken);
            return;
        }

        ActiveTransition merged = mKnownTransitions.get(mergedToken);
        if (merged == null) {
            Log.e(TAG, "Merging a non-existent transition: " + mergedToken);
            return;
        }

        if (playing.getTrack() != merged.getTrack()) {
            throw new IllegalStateException("Can't merge across tracks: " + merged + " into "
                    + playing);
        }

        final Track track = mTracks.get(playing.getTrack());
        ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition was merged: %s into %s",
                merged, playing);
        int readyIdx = 0;
        if (track.mReadyTransitions.isEmpty() || track.mReadyTransitions.get(0) != merged) {
            Log.e(TAG, "Merged transition out-of-order? " + merged);
            readyIdx = track.mReadyTransitions.indexOf(merged);
            if (readyIdx < 0) {
                Log.e(TAG, "Merged a transition that is no-longer queued? " + merged);
                return;
            }
        }
        track.mReadyTransitions.remove(readyIdx);
        if (playing.mMerged == null) {
            playing.mMerged = new ArrayList<>();
        }
        playing.mMerged.add(merged);
        // if it was aborted, then onConsumed has already been reported.
        if (merged.mHandler != null && !merged.mAborted) {
            merged.mHandler.onTransitionConsumed(merged.mToken, false /* abort */, merged.mFinishT);
        }
        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionMerged(merged.mToken, playing.mToken);
        }
        mTransitionTracer.logMerged(merged.mInfo.getDebugId(), playing.mInfo.getDebugId());
        // See if we should merge another transition.
        processReadyQueue(track);
    }

    private void playTransitionWithTracing(@NonNull ActiveTransition active) {
        final boolean useTrace = Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER);
        if (useTrace) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "playTransition: " + transitTypeToString(active.mInfo.getType()));
        }
        playTransition(active);
        if (useTrace) {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    private void playTransition(@NonNull ActiveTransition active) {
        ProtoLog.v(WM_SHELL_TRANSITIONS, "Playing animation for %s", active);
        final var token = active.mToken;

        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionStarting(token);
        }

        setupAnimHierarchy(active.mInfo, active.mStartT);

        // If a handler already chose to run this animation, try delegating to it first.
        if (active.mHandler != null) {
            ProtoLog.v(WM_SHELL_TRANSITIONS, " try firstHandler %s",
                    active.mHandler);
            boolean consumed = active.mHandler.startAnimation(token, active.mInfo,
                    active.mStartT, active.mFinishT, (wct) -> onFinish(token, wct));
            if (consumed) {
                ProtoLog.v(WM_SHELL_TRANSITIONS, " animated by firstHandler");
                mTransitionTracer.logDispatched(active.mInfo.getDebugId(), active.mHandler);
                if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                    Trace.instant(TRACE_TAG_WINDOW_MANAGER,
                            active.mHandler.getClass().getSimpleName()
                                    + "#startAnimation animated "
                                    + transitTypeToString(active.mInfo.getType()));
                }
                return;
            }
        }
        // Otherwise give every other handler a chance
        active.mHandler = dispatchTransition(token, active.mInfo, active.mStartT,
                active.mFinishT, (wct) -> onFinish(token, wct), active.mHandler);
    }

    /**
     * Gives every handler (in order) a chance to animate until one consumes the transition.
     * @return the handler which consumed the transition.
     */
    public TransitionHandler dispatchTransition(
            @NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull TransitionFinishCallback finishCB,
            @Nullable TransitionHandler skip
    ) {
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == skip) {
                ProtoLog.v(WM_SHELL_TRANSITIONS, " skip handler %s",
                        mHandlers.get(i));
                continue;
            }
            boolean consumed = mHandlers.get(i).startAnimation(transition, info, startT, finishT,
                    finishCB);
            if (consumed) {
                ProtoLog.v(WM_SHELL_TRANSITIONS, " animated by %s",
                        mHandlers.get(i));
                mTransitionTracer.logDispatched(info.getDebugId(), mHandlers.get(i));
                if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                    Trace.instant(TRACE_TAG_WINDOW_MANAGER,
                            mHandlers.get(i).getClass().getSimpleName()
                                    + "#startAnimation animated "
                                    + transitTypeToString(info.getType()));
                }
                return mHandlers.get(i);
            }
        }
        throw new IllegalStateException(
                "This shouldn't happen, maybe the default handler is broken.");
    }

    private RequestResult dispatchRequestWithTracing(
            @NonNull IBinder transition, @NonNull TransitionRequestInfo request,
            @Nullable TransitionHandler skip) {
        final boolean useTrace = Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER);
        if (useTrace) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "dispatchRequest: " + transitTypeToString(request.getType()));
        }
        final RequestResult result = dispatchRequest(transition, request, skip);
        if (useTrace) {
            if (result != null) {
                Trace.instant(TRACE_TAG_WINDOW_MANAGER, result.mHandler.getClass().getSimpleName()
                        + "#handleRequest handled " + transitTypeToString(request.getType()));
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        return result;
    }

    /**
     * Gives every handler (in order) a chance to handle request until one consumes the transition.
     * @return the WindowContainerTransaction given by the handler which consumed the transition.
     */
    public RequestResult dispatchRequest(
            @NonNull IBinder transition, @NonNull TransitionRequestInfo request,
            @Nullable TransitionHandler skip) {
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == skip) continue;
            RequestResult result = mHandlers.get(i).handleRequestOnly(transition, request);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /** Aborts a transition. This will still queue it up to maintain order. */
    private void onAbort(ActiveTransition transition) {
        final Track track = mTracks.get(transition.getTrack());
        transition.mAborted = true;

        mTransitionTracer.logAborted(transition.mInfo.getDebugId());

        if (transition.mHandler != null) {
            // Notifies to clean-up the aborted transition.
            transition.mHandler.onTransitionConsumed(
                    transition.mToken, true /* aborted */, transition.mFinishT);
        }

        releaseSurfaces(transition.mInfo);

        // This still went into the queue (to maintain the correct finish ordering).
        if (track.mReadyTransitions.size() > 1) {
            // There are already transitions waiting in the queue, so just return.
            return;
        }
        processReadyQueue(track);
    }

    /**
     * Releases an info's animation-surfaces. These don't need to persist and we need to release
     * them asap so that SF can free memory sooner.
     */
    private void releaseSurfaces(@Nullable TransitionInfo info) {
        if (info == null) return;
        info.releaseAnimSurfaces();
        if (com.android.window.flags.Flags.releaseAllTransitionSurfacesOnIdle()) {
            recordReleaseSurfaces(mCleanupSurfaces, info.getChanges());
        }
    }

    /** Called when all transitions are finished. */
    private void releaseCleanupSurfaces() {
        final int size = mCleanupSurfaces.size();
        if (size == 0) return;
        for (int i = size - 1; i >= 0; --i) {
            mCleanupSurfaces.get(i).release();
        }
        mCleanupSurfaces.clear();
    }

    /** Populates the surfaces from changes that should be released later. */
    static void recordReleaseSurfaces(@NonNull ArrayList<SurfaceControl> outCleanupSurfaces,
            @NonNull List<TransitionInfo.Change> changes) {
        for (int i = changes.size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = changes.get(i);
            SurfaceControl sc = change.getLeash();
            if (sc.isValid()) {
                outCleanupSurfaces.add(sc);
            }
            sc = change.getSnapshot();
            if (sc != null && sc.isValid()) {
                outCleanupSurfaces.add(sc);
            }
            sc = change.getTopCompatActivityLeash();
            if (sc != null && sc.isValid()) {
                outCleanupSurfaces.add(sc);
            }
        }
    }

    /**
     * Finds the mixpatcher animation wrapper for `transition` and finishes/cleans it up. Should
     * only be used when mixpatcher is enabled.
     */
    private void finishMixWrapAnim(@NonNull IBinder transition,
            @Nullable SurfaceControl.Transaction finishT) {
        for (int i = mMixpatchAnimations.size() - 1; i >= 0; --i) {
            final MixpatchAnimationWrapper anim = mMixpatchAnimations.get(i);
            if (anim.mTransition == transition) {
                anim.mFinishCB.onFinished(finishT);
                mMixpatchAnimations.remove(i);
                return;
            }
        }
        Log.wtf(TAG, "Couldn't find mixpatch animation for " + transition);
    }

    private void onFinish(IBinder token, @Nullable WindowContainerTransaction wct) {
        mMainExecutor.assertCurrentThread();

        final ActiveTransition active = mKnownTransitions.get(token);
        if (active == null) {
            Log.e(TAG, "Trying to finish a non-existent transition: " + token);
            return;
        }
        if (DEBUG_FINISH_TRANSITION) {
            final String name = active.mHandler != null
                    ?  active.mHandler.getClass().getName() : "null";
            Log.d(TAG, "finishTransition: type=" + transitTypeToString(active.mInfo.getType())
                            + " wct=" + wct + " handler=" + name, new Throwable());
        }

        final Track track = mTracks.get(active.getTrack());
        if (track == null || track.mActiveTransition != active) {
            Log.e(TAG, "Trying to finish a non-running transition. Either remote crashed or "
                    + " a handler didn't properly deal with a merge. " + active,
                    new RuntimeException());
            return;
        }
        track.mActiveTransition = null;

        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionFinished(active.mToken, active.mAborted);
        }
        ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition animation finished "
                + "(aborted=%b), notifying core %s", active.mAborted, active);
        if (active.mStartT != null) {
            // Applied by now, so clear immediately to remove any references. Do not set to null
            // yet, though, since nullness is used later to disambiguate malformed transitions.
            active.mStartT.clear();
        }
        // Merge all associated transactions together
        SurfaceControl.Transaction fullFinish = active.mFinishT;
        if (active.mMerged != null) {
            for (int iM = 0; iM < active.mMerged.size(); ++iM) {
                final ActiveTransition toMerge = active.mMerged.get(iM);
                // Include start. It will be a no-op if it was already applied. Otherwise, we need
                // it to maintain consistent state.
                if (toMerge.mStartT != null) {
                    if (fullFinish == null) {
                        fullFinish = toMerge.mStartT;
                    } else {
                        fullFinish.merge(toMerge.mStartT);
                    }
                }
                if (toMerge.mFinishT != null) {
                    if (fullFinish == null) {
                        fullFinish = toMerge.mFinishT;
                    } else {
                        fullFinish.merge(toMerge.mFinishT);
                    }
                }
            }
        }
        if (mMixpatchAnimations.isEmpty()) {
            if (fullFinish != null) {
                fullFinish.apply();
            }
            // Now perform all the finish callbacks (starting with the playing one and then all the
            // transitions merged into it).
            releaseSurfaces(active.mInfo);
            mOrganizer.finishTransition(active.mToken, wct);
        } else {
            // Note: fullFinish is sent to mixpatcher who will merge/apply it once all animations
            // have finished.
            finishMixWrapAnim(active.mToken, fullFinish);
            if (wct != null && !wct.isEmpty()) {
                mOrganizer.applyTransaction(wct);
                Log.w(TAG, "Applying finishWCT out-of-band for #" + active.mInfo.getDebugId());
            }
        }
        if (active.mMerged != null) {
            for (int iM = 0; iM < active.mMerged.size(); ++iM) {
                ActiveTransition merged = active.mMerged.get(iM);
                if (mMixpatchAnimations.isEmpty()) {
                    mOrganizer.finishTransition(merged.mToken, null /* wct */);
                    releaseSurfaces(merged.mInfo);
                } else {
                    // Note: fullFinish is sent to mixpatcher who will merge/apply it.
                    finishMixWrapAnim(merged.mToken, fullFinish);
                }
                mKnownTransitions.remove(merged.mToken);
            }
            active.mMerged.clear();
        }
        mKnownTransitions.remove(token);

        // Now that this is done, check the ready queue for more work.
        processReadyQueue(track);
    }

    /** @see ITransitionPlayer#requestStartTransition  */
    public void requestStartTransition(@NonNull IBinder transitionToken,
            @Nullable TransitionRequestInfo request) {
        ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition requested (#%d): %s %s",
                request.getDebugId(), transitionToken, request);
        if (transitionToken == null) {
            throw new IllegalArgumentException("Null transitionToken specified for request="
                    + request);
        }
        if (mKnownTransitions.containsKey(transitionToken)) {
            throw new RuntimeException("Transition already started " + transitionToken);
        }
        final ActiveTransition active = new ActiveTransition(transitionToken);
        WindowContainerTransaction wct = null;

        // If we have sleep, we use a special handler and we try to finish everything ASAP.
        if (request.getType() == TRANSIT_SLEEP) {
            mSleepHandler.handleRequestOnly(transitionToken, request);
            active.mHandler = mSleepHandler;
        } else {
            RequestResult requestResult =
                    dispatchRequestWithTracing(transitionToken, request, /* skip= */ null);
            if (requestResult != null) {
                active.mHandler = requestResult.mHandler;
                active.mInterestedPlanners = requestResult.mInterest;
                wct = requestResult.mWct;
                if (com.android.window.flags.Flags.transitMixpatcherBase()) {
                    if (requestResult.mHandler != null) {
                        // Legacy handling expects that the handler which responded to the request
                        // is the first to animate, so insert legacy planner as interest.
                        active.mInterestedPlanners = List.of(mMixpatchLegacyPlanner);
                        ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition (#%d): request redirected to "
                                + "legacy handler %s", request.getDebugId(),
                                active.mHandler.getClass().getSimpleName());
                    } else {
                        ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition (#%d): request redirected to "
                                + "%s", request.getDebugId(), active.mInterestedPlanners);
                    }
                } else if (active.mHandler != null) {
                    ProtoLog.v(WM_SHELL_TRANSITIONS, "Transition (#%d): request handled by %s",
                            request.getDebugId(), active.mHandler.getClass().getSimpleName());
                }
            }
            if (request.getDisplayChanges() != null) {
                final List<TransitionRequestInfo.DisplayChange> changes = request
                        .getDisplayChanges();

                for (int i = 0; i < changes.size(); i++) {
                    TransitionRequestInfo.DisplayChange change = changes.get(i);
                    if (change.getStartRotation() != change.getEndRotation()
                            || (change.getStartAbsBounds() != null
                            && !change.getStartAbsBounds().equals(change.getEndAbsBounds()))) {
                        // Is a display change, so dispatch to all displayChange listeners
                        if (wct == null) {
                            wct = new WindowContainerTransaction();
                        }
                        mDisplayController.onDisplayChangeRequested(wct, change.getDisplayId(),
                                change.getStartAbsBounds(), change.getEndAbsBounds(),
                                change.getStartRotation(), change.getEndRotation(),
                                change.getEndInsetsState(), change.getEndDisplayAreaInfo());
                    }
                }
            }
        }
        final boolean isOccludingKeyguard = request.getType() == TRANSIT_KEYGUARD_OCCLUDE
                || ((request.getFlags() & TRANSIT_FLAG_KEYGUARD_OCCLUDING) != 0);
        if (isOccludingKeyguard && request.getTriggerTask() != null
                && request.getTriggerTask().getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            // This freeform task is on top of keyguard, so its windowing mode should be changed to
            // fullscreen.
            if (wct == null) {
                wct = new WindowContainerTransaction();
            }
            wct.setWindowingMode(request.getTriggerTask().token, WINDOWING_MODE_FULLSCREEN);
            wct.setBounds(request.getTriggerTask().token, null);
        }
        if (com.android.window.flags.Flags.transitMixpatcherBase()) {
            mMixpatcher.startTransition(transitionToken, request.getType(), wct,
                    active.mInterestedPlanners);
            return;
        }
        mKnownTransitions.put(transitionToken, active);
        mOrganizer.startTransition(transitionToken, wct != null && wct.isEmpty() ? null : wct);
        // Currently, WMCore only does one transition at a time. If it makes a requestStart, it
        // is already collecting that transition on core-side, so it will be the next one to
        // become ready. There may already be pending transitions added as part of direct
        // `startNewTransition` but if we have a request now, it means WM created the request
        // transition before it acknowledged any of the pending `startNew` transitions. So, insert
        // it at the front.
        mPendingTransitions.add(0, active);
    }

    void removeStartingWindow(StartingWindowRemovalInfo removalInfo) {
        mOrganizer.removeStartingWindow(removalInfo);
    }

    /**
     * Start a new transition directly.
     * @param handler if null, the transition will be dispatched to the registered set of transition
     *                handlers to be handled
     */
    public IBinder startTransition(@WindowManager.TransitionType int type,
            @NonNull WindowContainerTransaction wct, @Nullable TransitionHandler handler) {
        mMainExecutor.assertCurrentThread();
        if (type < 0) {
            throw new IllegalArgumentException("Invalid transition type provided (" + type
                    + "), type must be > 0");
        }

        if (com.android.window.flags.Flags.transitMixpatcherBase()) {
            List<ITransitionPlanner> interest = null;
            if (handler != null) {
                interest = List.of(new MixpatchLegacyPlanner(handler));
            }
            return mMixpatcher.startTransition(null /* token */, type, wct, interest);
        }

        ProtoLog.v(WM_SHELL_TRANSITIONS, "Directly starting a new transition "
                + "type=%s wct=%s handler=%s", transitTypeToString(type), wct, handler);
        if (DEBUG_START_TRANSITION) {
            Log.d(TAG, "startTransition: type=" + transitTypeToString(type)
                    + " wct=" + wct + " handler="
                    + (handler != null ? handler.getClass().getName() : null), new Throwable());
        }
        final ActiveTransition active =
                new ActiveTransition(mOrganizer.startNewTransition(type, wct));
        active.mHandler = handler;
        mKnownTransitions.put(active.mToken, active);
        mPendingTransitions.add(active);
        return active.mToken;
    }

    /**
     * Checks whether a handler exists capable of taking over the given transition, and returns it.
     * Otherwise it returns null.
     */
    @Nullable
    public TransitionHandler getHandlerForTakeover(
            @NonNull IBinder transition, @NonNull TransitionInfo info) {
        for (TransitionHandler handler : mHandlers) {
            TransitionHandler candidate = handler.getHandlerForTakeover(transition, info);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Finish running animations (almost) immediately when a SLEEP transition comes in. We use this
     * as both a way to reduce unnecessary work (animations not visible while screen off) and as a
     * failsafe to unblock "stuck" animations (in particular remote animations).
     *
     * This works by "merging" the sleep transition into the currently-playing transition (even if
     * its out-of-order) -- turning SLEEP into a signal. If the playing transition doesn't finish
     * within `SYNC_ALLOWANCE_MS` from this merge attempt, this will then finish it directly (and
     * send an abort/consumed message).
     *
     * This is then repeated until there are no more pending sleep transitions.
     *
     * @param reason The token for the SLEEP transition that triggered this round of finishes.
     *               We will continue looping round finishing transitions until this is ready.
     * @param forceFinish When non-null, this is the transition that we last sent the SLEEP merge
     *                    signal to -- so it will be force-finished if it's still running.
     */
    private void finishForSync(IBinder reason,
            int trackIdx, @Nullable ActiveTransition forceFinish) {
        if (!mKnownTransitions.containsKey(reason)) {
            Log.d(TAG, "finishForSleep: already played sync transition " + reason);
            return;
        }
        final Track track = mTracks.get(trackIdx);
        if (forceFinish != null) {
            final Track trk = mTracks.get(forceFinish.getTrack());
            if (trk != track) {
                Log.e(TAG, "finishForSleep: mismatched Tracks between forceFinish and logic "
                        + forceFinish.getTrack() + " vs " + trackIdx);
            }
            if (trk.mActiveTransition == forceFinish) {
                Log.e(TAG, "Forcing transition to finish due to sync timeout: " + forceFinish);
                forceFinish.mAborted = true;
                // Last notify of it being consumed. Note: mHandler should never be null,
                // but check just to be safe.
                if (forceFinish.mHandler != null) {
                    forceFinish.mHandler.onTransitionConsumed(
                            forceFinish.mToken, true /* aborted */, null /* finishTransaction */);
                }
                onFinish(forceFinish.mToken, null);
            }
        }
        if (track.isIdle() || mReadyDuringSync.isEmpty()) {
            // Done finishing things.
            return;
        }
        final SurfaceControl.Transaction dummyT = new SurfaceControl.Transaction();
        final TransitionInfo dummyInfo = new TransitionInfo(TRANSIT_SLEEP, 0 /* flags */);
        while (track.mActiveTransition != null && !mReadyDuringSync.isEmpty()) {
            final ActiveTransition playing = track.mActiveTransition;
            final ActiveTransition nextSync = mReadyDuringSync.get(0);
            if (!nextSync.isSync()) {
                Log.e(TAG, "Somehow blocked on a non-sync transition? " + nextSync);
            }
            // Attempt to merge a SLEEP info to signal that the playing transition needs to
            // fast-forward.
            ProtoLog.v(WM_SHELL_TRANSITIONS, " Attempt to merge sync %s"
                    + " into %s via a SLEEP proxy", nextSync, playing);
            playing.mHandler.mergeAnimation(nextSync.mToken, dummyInfo, dummyT, dummyT,
                    playing.mToken, (wct) -> {});
            // it's possible to complete immediately. If that happens, just repeat the signal
            // loop until we either finish everything or start playing an animation that isn't
            // finishing immediately.
            if (track.mActiveTransition == playing) {
                if (!mDisableForceSync) {
                    // Give it a short amount of time to process it before forcing.
                    mMainExecutor.executeDelayed(
                            () -> finishForSync(reason, trackIdx, playing), SYNC_ALLOWANCE_MS);
                }
                break;
            }
        }
    }

    private SurfaceControl getHomeTaskOverlayContainer() {
        return mOrganizer.getHomeTaskOverlayContainer();
    }

    @Nullable
    private SurfaceControl getOverviewOverlayContainer(int displayId) {
        return mOrganizer.getOverviewOverlayContainer(displayId);
    }

    public void registerOverviewOverlayLeashInvalidationCallback(
            int displayId, IOverviewOverlayLeashInvalidationCallback callback) {
        mOrganizer.registerOverviewOverlayLeashInvalidationCallback(displayId, callback);
    }

    public void unregisterOverviewOverlayLeashInvalidationCallback(
            int displayId, IOverviewOverlayLeashInvalidationCallback callback) {
        mOrganizer.unregisterOverviewOverlayLeashInvalidationCallback(displayId, callback);
    }

    /**
     * Adapter which presents the handler-based dispatch to the Mixpatcher as if it was a planner.
     *
     * It basically "plans" for all animations to be handled by a singular wrapper around handler
     * dispatch.
     *
     * In order to also capture/wrap the {@link #startTransition} flow (where a priority
     * handler is attached to the transition), an instance of this will be created with `mInterest`
     * populated and then this planner will, in turn, be add as an `interest` to Mixpatcher's
     * {@link TransitionMixpatcher#startTransition}.
     */
    private class MixpatchLegacyPlanner implements ITransitionPlanner {
        private final @Nullable TransitionHandler mInterest;

        MixpatchLegacyPlanner(@Nullable TransitionHandler interest) {
            mInterest = interest;
        }

        @Override
        public void plan(@NonNull AnimationPlan plan,
                @NonNull TransitionInfo fullInfo, @NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction) {
            if (info.getChanges().isEmpty()) {
                // This means that something else (in mixpatcher) claimed the animations or the
                // transition was aborted. Either way, it means we need to forward this information
                // to any handlers which were expecting a response (with abort=true because there
                // are no changes)
                int activeIdx = findByToken(mPendingTransitions, transition);
                if (activeIdx < 0) {
                    if (mInterest != null) {
                        mInterest.onTransitionConsumed(transition, true /* aborted */,
                                // Mixpatcher expects animators to provide finishT while the legacy
                                // handlers expect to be given a finishT to populate. Either way,
                                // in the consume situation, what's important is that we pass in a
                                // transaction which will be applied later, so using the
                                // startTransaction here should fulfill that purpose.
                                startTransaction);
                    }
                    return;
                } else if (mPendingTransitions.get(activeIdx).mMixpatchWrapper == null) {
                    throw new IllegalStateException("Pending transition registered outside"
                            + " mixpatch");
                }
            }
            // Since direct transitions go through mixpatcher, we may need to hack-in the state now.
            final ActiveTransition active = ensureActive(Transitions.this, transition);
            if (active.mMixpatchWrapper == null) {
                active.mMixpatchWrapper = new MixpatchAnimationWrapper(transition);
                active.mStartT = active.mMixpatchWrapper.mStartT;
                active.mStartT.merge(startTransaction);
            }
            for (int i = 0; i < info.getChanges().size(); ++i) {
                plan.setAnimation(info.getChanges().get(i).getContainer(), active.mMixpatchWrapper);
            }
            if (active.mHandler == null) {
                // If mInterest != null, then it means this is a direct request but has already
                // been "intercepted" by the PrePlanner (ie. it is sleep/keyguard).
                active.mHandler = mInterest;
            }
        }

        static ActiveTransition ensureActive(@NonNull Transitions ctx,
                @NonNull IBinder transition) {
            int activeIdx = findByToken(ctx.mPendingTransitions, transition);
            if (activeIdx >= 0) {
                return ctx.mPendingTransitions.get(activeIdx);
            }
            final ActiveTransition active = new ActiveTransition(transition);
            ctx.mKnownTransitions.put(active.mToken, active);
            ctx.mPendingTransitions.add(active);
            return active;
        }

        @NonNull
        @Override
        public String getDebugName() {
            return "LegacyDispatch";
        }
    }

    /** Replaces the sleep/keyguard planners so we can route those to legacy dispatching. */
    private class MixpatchLegacyPrePlanner implements ITransitionPlanner {
        @Override
        public void plan(@NonNull AnimationPlan plan,
                @NonNull TransitionInfo fullInfo, @NonNull IBinder transition,
                @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction) {
            final boolean isSleepOrKeyguard =
                    (info.getType() == TRANSIT_SLEEP
                            || (info.getFlags() & TransitionInfo.FLAG_SYNC) != 0)
                            || KeyguardTransitionHandler.handles(info);
            if (!isSleepOrKeyguard && !TransitionUtil.isDreamTransition(info)) {
                return;
            }
            // Synthesize a change for sleep/keyguard (in order to ensure at-least one
            // animation record so that control returns).
            final MixpatchAnimationWrapper next = new MixpatchAnimationWrapper(transition);
            final TransitionInfo.Change sleepKgChg = new TransitionInfo.Change(
                    mSleepOrKeyguardProxy,
                    new SurfaceControl.Builder().setName("SleepKeyguardProxy").build());
            info.addChange(sleepKgChg);
            plan.setAnimation(sleepKgChg.getContainer(), next);
            ProtoLog.v(WM_SHELL_TRANSITIONS, "Build sleep/keyguard proxy in transition #%d",
                    info.getDebugId());
            // Since direct transitions go through mixpather, we may need to hack-in the state now.
            final ActiveTransition active = MixpatchLegacyPlanner.ensureActive(Transitions.this,
                    transition);
            active.mMixpatchWrapper = next;
            active.mStartT = next.mStartT;
            active.mStartT.merge(startTransaction);
        }

        @Override
        public String getDebugName() {
            return "LegacyPreDispatch";
        }
    }

    private class MixpatchAnimationWrapper implements ITransitionAnimation {
        final IBinder mTransition;
        TransitionInfo mInfo = null;
        final SurfaceControl.Transaction mStartT = mTransactionPool.acquire();
        final SurfaceControl.Transaction mFinishT = mTransactionPool.acquire();
        ITransitionAnimation.IFinishedCallback mFinishCB = null;

        MixpatchAnimationWrapper(@NonNull IBinder transit) {
            mTransition = transit;
        }

        @Override
        public DetachResult detach(
                @NonNull List<WindowContainerToken> containers,
                @NonNull SurfaceControl.Transaction startTransaction) {
            final ArrayList<WindowAnimationState> states = new ArrayList<>(containers.size());
            for (int i = 0; i < containers.size(); ++i) {
                final WindowAnimationState state = new WindowAnimationState();
                states.add(state);
                final TransitionInfo.Change chg = mInfo.getChange(containers.get(i));
                if (chg == null) {
                    Log.wtf(TAG, "Trying to detach container that was never in animation");
                    continue;
                }
                // The handler system doesn't intrinsically support mid-state handoffs. The common
                // handling of merge is to "jump to end", so for now we populate the handoff state
                // based on that.
                state.bounds = new android.graphics.RectF(chg.getEndAbsBounds());
                state.scale = 1.f;
                state.timestamp = System.currentTimeMillis();
            }
            return new DetachResult(states);
        }

        @Override
        public void start(@NonNull TransitionInfo info,
                @NonNull List<WindowAnimationState> from,
                @NonNull ITransitionAnimation.IFinishedCallback onFinished) {
            mInfo = info;
            mFinishCB = onFinished;
            mMixpatchAnimations.add(this);
            // Remove the proxy (for compatibility)
            info.getChanges().removeIf(change -> change.getContainer() == mSleepOrKeyguardProxy);
            onTransitionReadyInner(mTransition, info, mStartT, mFinishT);
        }

        @Override
        public String getDebugName() {
            return "LegacyAnim";
        }
    }

    /**
     * Interface for a callback that must be called after a TransitionHandler finishes playing an
     * animation.
     */
    public interface TransitionFinishCallback {
        /**
         * This must be called on the main thread when a transition finishes playing an animation.
         * The transition must not touch the surfaces after this has been called.
         *
         * @param wct A WindowContainerTransaction to run along with the transition clean-up.
         */
        void onTransitionFinished(@Nullable WindowContainerTransaction wct);
    }

    /**
     * Result of processing a transition request. It includes work that needs to be added to
     * the transition.
     *
     * If using mixpatcher, this can provide a list of interested planners (planners which want to
     * be first to plan the resulting transition info).
     *
     * If NOT using mixpatcher, the {@link #mHandler} will be first in line to play the resulting
     * animation.
     */
    public static final class RequestResult {
        @NonNull public final WindowContainerTransaction mWct;
        public final TransitionHandler mHandler;
        List<ITransitionPlanner> mInterest = List.of();

        public RequestResult(@NonNull WindowContainerTransaction wct,
                @NonNull List<ITransitionPlanner> interest) {
            mWct = wct;
            mHandler = null;
            mInterest = interest;
        }

        public RequestResult(@NonNull WindowContainerTransaction wct) {
            mWct = wct;
            mHandler = null;
        }

        @Deprecated
        public RequestResult(@NonNull WindowContainerTransaction wct,
                @NonNull TransitionHandler handler) {
            mWct = wct;
            mHandler = handler;
        }

        /** Checks whether there are any interested planners. */
        public boolean hasInterestPlanners() {
            return !mInterest.isEmpty();
        }
    }

    /**
     * Interface for something which can handle a subset of transitions.
     */
    public interface TransitionHandler {
        /**
         * Starts a transition animation. This is always called if handleRequest returned non-null
         * for a particular transition. Otherwise, it is only called if no other handler before
         * it handled the transition.
         * @param startTransaction the transaction given to the handler to be applied before the
         *                         transition animation. Note the handler is expected to call on
         *                         {@link SurfaceControl.Transaction#apply()} for startTransaction.
         * @param finishTransaction the transaction given to the handler to be applied after the
         *                       transition animation. Unlike startTransaction, the handler is NOT
         *                       expected to apply this transaction. The Transition system will
         *                       apply it when finishCallback is called. If additional transitions
         *                       are merged, then the finish transactions for those transitions
         *                       will be applied after this transaction.
         * @param finishCallback Call this when finished. This MUST be called on main thread.
         * @return true if transition was handled, false if not (falls-back to default).
         */
        boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull TransitionFinishCallback finishCallback);

        /**
         * Like {@link #startAnimation(IBinder, TransitionInfo, SurfaceControl.Transaction,
         * SurfaceControl.Transaction, TransitionFinishCallback)} when {@code info} is not null.
         * When {@code info} is null, startAnimation won't do any active animation, but will just
         * collect information about the compatibility of the handler and the transition in
         * {@code dispatchState}.
         */
        default boolean startAnimation(@NonNull IBinder transition,
                                       @Nullable TransitionInfo consumableInfo,
                                       @NonNull TransitionDispatchState dispatchState,
                                       @NonNull SurfaceControl.Transaction startTransaction,
                                       @NonNull SurfaceControl.Transaction finishTransaction,
                                       @NonNull TransitionFinishCallback finishCallback) {
            if (consumableInfo != null) {
                return startAnimation(transition, consumableInfo, startTransaction,
                        finishTransaction, finishCallback);
            }
            return false;
        }

        /**
         * See {@link #mergeAnimation(IBinder, TransitionInfo, SurfaceControl.Transaction, SurfaceControl.Transaction, IBinder, TransitionFinishCallback)}
         *
         * This deprecated method header is provided until downstream implementation can migrate to
         * the call that takes both start & finish transactions.
         */
        @Deprecated
        default void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull IBinder mergeTarget, @NonNull TransitionFinishCallback finishCallback) { }

        /**
         * Attempts to merge a different transition's animation into an animation that this handler
         * is currently playing. If a merge is not possible/supported, this should be a no-op.
         *
         * This gets called if another transition becomes ready while this handler is still playing
         * an animation. This is called regardless of whether this handler claims to support that
         * particular transition or not.
         *
         * When this happens, there are 2 options:
         *  1. Do nothing. This effectively rejects the merge request. This is the "safest" option.
         *  2. Merge the incoming transition into this one. The implementation is up to this
         *     handler. To indicate that this handler has "consumed" the merge transition, it
         *     must call the finishCallback immediately, or at-least before the original
         *     transition's finishCallback is called.
         *
         * @param transition This is the transition that wants to be merged.
         * @param info Information about what is changing in the transition.
         * @param startTransaction The start transaction containing surface changes that resulted
         *                         from the incoming transition. This should be applied by this
         *                         active handler only if it chooses to merge the transition.
         * @param finishTransaction The finish transaction for the incoming transition. Unlike
         *                          startTransaction, the handler is NOT expected to apply this
         *                          transaction. If the transition is merged, the Transition system
         *                          will apply after finishCallback is called following the finish
         *                          transaction provided in `#startAnimation()`.
         * @param mergeTarget This is the transition that we are attempting to merge with (ie. the
         *                    one this handler is currently already animating).
         * @param finishCallback Call this if merged. This MUST be called on main thread.
         */
        default void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull IBinder mergeTarget, @NonNull TransitionFinishCallback finishCallback) {
            // Call the legacy implementation by default
            mergeAnimation(transition, info, startTransaction, mergeTarget, finishCallback);
        }

        /**
         * Checks whether this handler is capable of taking over a transition matching `info`.
         * {@link TransitionHandler#takeOverAnimation(IBinder, TransitionInfo,
         * SurfaceControl.Transaction, TransitionFinishCallback, WindowAnimationState[])} is
         * guaranteed to succeed if called on the handler returned by this method.
         *
         * Note that the handler returned by this method can either be itself, or a different one
         * selected by this handler to take care of the transition on its behalf.
         *
         * @param transition The transition that should be taken over.
         * @param info Information about the transition to be taken over.
         * @return A handler capable of taking over a matching transition, or null.
         */
        @Nullable
        default TransitionHandler getHandlerForTakeover(
                @NonNull IBinder transition, @NonNull TransitionInfo info) {
            return null;
        }

        /**
         * Attempt to take over a running transition. This must succeed if this handler was returned
         * by {@link TransitionHandler#getHandlerForTakeover(IBinder, TransitionInfo)}.
         *
         * @param transition The transition that should be taken over.
         * @param info Information about the what is changing in the transition.
         * @param transaction Contains surface changes that resulted from the transition. Any
         *                    additional changes should be added to this transaction and committed
         *                    inside this method.
         * @param finishCallback Call this at the end of the animation, if the take-over succeeds.
         *                       Note that this will be called instead of the callback originally
         *                       passed to startAnimation(), so the caller should make sure all
         *                       necessary cleanup happens here. This MUST be called on main thread.
         * @param states The animation states of the transition's window at the time this method was
         *               called.
         * @return true if the transition was taken over, false if not.
         */
        default boolean takeOverAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction transaction,
                @NonNull TransitionFinishCallback finishCallback,
                @NonNull WindowAnimationState[] states) {
            return false;
        }

        /**
         * @deprecated Use {@link #handleRequestOnly}.
         */
        @Nullable
        @Deprecated
        default WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @NonNull TransitionRequestInfo request) {
            throw new UnsupportedOperationException("Must implement either handleRequestOnly"
                    + " (preferred) or handleRequest (deprecated)");
        }

        /**
         * Potentially handles a startTransition request.
         *
         * The default implementation falls through to the legacy {@link #handleRequest}.
         *
         * @param transition The transition whose start is being requested.
         * @param request Information about what is requested.
         * @return Extra work to submit with the transition or null. See {@link RequestResult} for
         *         more information about how this result is used.
         */
        @Nullable
        default RequestResult handleRequestOnly(
                @NonNull IBinder transition, @NonNull TransitionRequestInfo request) {
            final WindowContainerTransaction wct = handleRequest(transition, request);
            if (wct == null) {
                return null;
            }
            return new RequestResult(wct, this);
        }

        /**
         * Called when a transition which was already "claimed" by this handler has been merged
         * into another animation or has been aborted. Gives this handler a chance to clean-up any
         * expectations.
         *
         * @param transition The transition been consumed.
         * @param aborted Whether the transition is aborted or not.
         * @param finishTransaction The transaction to be applied after the transition animated.
         */
        default void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @Nullable SurfaceControl.Transaction finishTransaction) { }

        /**
         * Sets transition animation scale settings value to handler.
         *
         * @param scale The setting value of transition animation scale.
         */
        default void setAnimScaleSetting(float scale) {}
    }

    /**
     * Interface for something that needs to know the lifecycle of some transitions, but never
     * handles any transition by itself.
     */
    public interface TransitionObserver {
        /**
         * Called when the transition is ready to play. It may later be merged into other
         * transitions. Note this doesn't mean this transition will be played anytime soon.
         *
         * @param transition the unique token of this transition
         * @param startTransaction the transaction given to the handler to be applied before the
         *                         transition animation. This will be applied when the transition
         *                         handler that handles this transition starts the transition.
         * @param finishTransaction the transaction given to the handler to be applied after the
         *                          transition animation. The Transition system will apply it when
         *                          finishCallback is called by the transition handler.
         */
        default void onTransitionReady(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction) {}

        /**
         * Called when the transition is starting to play. It isn't called for merged transitions.
         *
         * @param transition the unique token of this transition
         */
        default void onTransitionStarting(@NonNull IBinder transition) {}

        /**
         * Called when a transition is merged into another transition. There won't be any following
         * lifecycle calls for the merged transition.
         *
         * @param merged the unique token of the transition that's merged to another one
         * @param playing the unique token of the transition that accepts the merge
         */
        default void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing) {}

        /**
         * Called when the transition is finished. This isn't called for merged transitions.
         *
         * @param transition the unique token of this transition
         * @param aborted {@code true} if this transition is aborted; {@code false} otherwise.
         */
        default void onTransitionFinished(@NonNull IBinder transition, boolean aborted) {}
    }

    @BinderThread
    private class TransitionPlayerImpl extends ITransitionPlayer.Stub {
        @Override
        public void onTransitionReady(IBinder iBinder, TransitionInfo transitionInfo,
                SurfaceControl.Transaction t, SurfaceControl.Transaction finishT)
                throws RemoteException {
            ProtoLog.v(WM_SHELL_TRANSITIONS, "onTransitionReady(transaction=%d)",
                    t.getId());
            mMainExecutor.execute(() -> Transitions.this.onTransitionReady(
                    iBinder, transitionInfo, t, finishT));
        }

        @Override
        public void requestStartTransition(IBinder iBinder,
                TransitionRequestInfo request) throws RemoteException {
            mMainExecutor.execute(() -> Transitions.this.requestStartTransition(iBinder, request));
        }

        @Override
        public void removeStartingWindow(StartingWindowRemovalInfo removalInfo) {
            mMainExecutor.execute(() -> Transitions.this.removeStartingWindow(removalInfo));
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class ShellTransitionImpl implements ShellTransitions {
        @Override
        public void registerRemote(@NonNull RemoteTransition remoteTransition) {
            mMainExecutor.execute(
                    () -> mRemoteTransitionHandler.addFiltered(remoteTransition));
        }

        @Override
        public void registerRemoteForTakeover(@NonNull RemoteTransition remoteTransition) {
            mMainExecutor.execute(() ->
                    mRemoteTransitionHandler.addFilteredForTakeover(remoteTransition));
        }

        @Override
        public void unregisterRemote(@NonNull RemoteTransition remoteTransition) {
            mMainExecutor.execute(
                    () -> mRemoteTransitionHandler.removeFiltered(remoteTransition));
        }

        @Override
        public void setFocusTransitionListener(FocusTransitionListener listener,
                Executor executor) {
            mMainExecutor.execute(() ->
                    mFocusTransitionObserver.setLocalFocusTransitionListener(listener, executor));

        }

        @Override
        public void unsetFocusTransitionListener(FocusTransitionListener listener) {
            mMainExecutor.execute(() ->
                    mFocusTransitionObserver.unsetLocalFocusTransitionListener(listener));

        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IShellTransitionsImpl extends IShellTransitions.Stub
            implements ExternalInterfaceBinder {
        private Transitions mTransitions;

        IShellTransitionsImpl(Transitions transitions) {
            mTransitions = transitions;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        @Override
        public void invalidate() {
            mTransitions.mHomeTransitionObserver.invalidate(mTransitions);
            mTransitions = null;
        }

        @Override
        public void registerRemote(@NonNull RemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mTransitions, "registerRemote",
                    (transitions) -> transitions.mRemoteTransitionHandler.addFiltered(
                            remoteTransition));
        }

        @Override
        public void registerRemoteForTakeover(@NonNull RemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mTransitions, "registerRemoteForTakeover",
                    (transitions) -> transitions.mRemoteTransitionHandler.addFilteredForTakeover(
                            remoteTransition));
        }

        @Override
        public void unregisterRemote(@NonNull RemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mTransitions, "unregisterRemote",
                    (transitions) ->
                            transitions.mRemoteTransitionHandler.removeFiltered(remoteTransition));
        }

        @Override
        public IBinder getShellApplyToken() {
            return SurfaceControl.Transaction.getDefaultApplyToken();
        }

        @Override
        public void setHomeTransitionListener(IHomeTransitionListener listener, int userId) {
            executeRemoteCallWithTaskPermission(mTransitions, "setHomeTransitionListener",
                    (transitions) -> {
                        transitions.mHomeTransitionObserver.setHomeTransitionListener(transitions,
                                listener, userId);
                    });
        }

        @Override
        public void setFocusTransitionListener(IFocusTransitionListener listener) {
            executeRemoteCallWithTaskPermission(mTransitions, "setFocusTransitionListener",
                    (transitions) -> {
                        transitions.mFocusTransitionObserver.setRemoteFocusTransitionListener(
                                transitions, listener);
                    });
        }

        @Override
        public SurfaceControl getOverviewOverlayContainer(int displayId) {
            SurfaceControl[] result = new SurfaceControl[1];
            executeRemoteCallWithTaskPermission(mTransitions, "getOverviewOverlayContainer",
                    (controller) -> {
                        result[0] = controller.getOverviewOverlayContainer(displayId);
                    }, true /* blocking */);
            if (result[0] == null) {
                Log.wtf("WindowManagerShell", "Null overview overlay surface, "
                        + "mTransitions=%s" + (mTransitions != null) + "displayId: " + displayId);
            }
            // Return a copy as writing to parcel releases the original surface
            return new SurfaceControl(result[0], "Transitions.OverviewOverlay");
        }

        @Override
        public void registerOverviewOverlayLeashInvalidationCallback(
                int displayId, IOverviewOverlayLeashInvalidationCallback callback) {
            executeRemoteCallWithTaskPermission(mTransitions,
                    "registerOverviewOverlayLeashInvalidationListener",
                    controller -> controller.registerOverviewOverlayLeashInvalidationCallback(
                            displayId, callback),
                    false /* blocking */);
        }

        @Override
        public void unregisterOverviewOverlayLeashInvalidationCallback(
                int displayId, IOverviewOverlayLeashInvalidationCallback callback) {
            executeRemoteCallWithTaskPermission(mTransitions,
                    "unregisterOverviewOverlayLeashInvalidationCallback",
                    controller -> controller.unregisterOverviewOverlayLeashInvalidationCallback(
                            displayId, callback),
                    false /* blocking */);
        }

        @Override
        public SurfaceControl getHomeTaskOverlayContainer() {
            SurfaceControl[] result = new SurfaceControl[1];
            executeRemoteCallWithTaskPermission(mTransitions, "getHomeTaskOverlayContainer",
                    (controller) -> {
                        result[0] = controller.getHomeTaskOverlayContainer();
                    }, true /* blocking */);
            if (result[0] == null) {
                Log.wtf("WindowManagerShell", "Null home task overlay surface, "
                        + "mTransitions=%s" + (mTransitions != null));
            }
            // Return a copy as writing to parcel releases the original surface
            return new SurfaceControl(result[0], "Transitions.HomeOverlay");
        }
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mTransitionAnimationScaleSetting = getTransitionAnimationScaleSetting();

            mMainExecutor.execute(() -> dispatchAnimScaleSetting(mTransitionAnimationScaleSetting));
        }
    }

    @Override
    public boolean onShellCommand(String[] args, PrintWriter pw) {
        if (args.length == 0) {
            printShellCommandHelp(pw, "");
            return false;
        }
        switch (args[0]) {
            case "tracing": {
                pw.println("Command not supported. Use the Perfetto command instead to start "
                        + "and stop this trace instead.");
                return false;
            }
            default: {
                pw.println("Invalid command: " + args[0]);
                printShellCommandHelp(pw, "");
                return false;
            }
        }
    }

    @Override
    public void printShellCommandHelp(PrintWriter pw, String prefix) {}

    private void dump(@NonNull PrintWriter pw, String prefix) {
        pw.println(prefix + TAG);

        final String innerPrefix = prefix + "  ";
        pw.println(prefix + "Handlers (ordered by priority):");
        for (int i = mHandlers.size() - 1; i >= 0; i--) {
            final TransitionHandler handler = mHandlers.get(i);
            pw.print(innerPrefix);
            pw.print(handler.getClass().getSimpleName());
            pw.println(" (" + Integer.toHexString(System.identityHashCode(handler)) + ")");
        }

        mRemoteTransitionHandler.dump(pw, prefix);

        pw.println(prefix + "Observers:");
        for (TransitionObserver observer : mObservers) {
            pw.print(innerPrefix);
            pw.println(observer.getClass().getSimpleName());
        }

        pw.println(prefix + "Pending Transitions:");
        for (ActiveTransition transition : mPendingTransitions) {
            pw.print(innerPrefix + "token=");
            pw.println(transition.mToken);
            pw.print(innerPrefix + "id=");
            pw.println(transition.mInfo != null
                    ? transition.mInfo.getDebugId()
                    : -1);
            pw.print(innerPrefix + "handler=");
            pw.println(transition.mHandler != null
                    ? transition.mHandler.getClass().getSimpleName()
                    : null);
        }
        if (mPendingTransitions.isEmpty()) {
            pw.println(innerPrefix + "none");
        }

        pw.println(prefix + "Ready-during-sync Transitions:");
        for (ActiveTransition transition : mReadyDuringSync) {
            pw.print(innerPrefix + "token=");
            pw.println(transition.mToken);
            pw.print(innerPrefix + "id=");
            pw.println(transition.mInfo != null
                    ? transition.mInfo.getDebugId()
                    : -1);
            pw.print(innerPrefix + "handler=");
            pw.println(transition.mHandler != null
                    ? transition.mHandler.getClass().getSimpleName()
                    : null);
        }
        if (mReadyDuringSync.isEmpty()) {
            pw.println(innerPrefix + "none");
        }

        pw.println(prefix + "Tracks:");
        for (int i = 0; i < mTracks.size(); i++) {
            final ActiveTransition transition = mTracks.get(i).mActiveTransition;
            pw.println(innerPrefix + "Track #" + i);
            pw.print(innerPrefix + "active=");
            pw.println(transition);
            if (transition != null) {
                pw.print(innerPrefix + "hander=");
                pw.println(transition.mHandler);
            }
        }
    }

    /**
     * Like WindowManager#transitTypeToString(), but also covers known custom transition types as
     * well.
     */
    public static String transitTypeToString(int transitType) {
        if (transitType < TRANSIT_FIRST_CUSTOM) {
            return WindowManager.transitTypeToString(transitType);
        }

        String typeStr = switch (transitType) {
            case TRANSIT_EXIT_PIP -> "EXIT_PIP";
            case TRANSIT_EXIT_PIP_TO_SPLIT -> "EXIT_PIP_TO_SPLIT";
            case TRANSIT_REMOVE_PIP -> "REMOVE_PIP";
            case TRANSIT_SPLIT_SCREEN_PAIR_OPEN -> "SPLIT_SCREEN_PAIR_OPEN";
            case TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE -> "SPLIT_SCREEN_OPEN_TO_SIDE";
            case TRANSIT_SPLIT_DISMISS_SNAP -> "SPLIT_DISMISS_SNAP";
            case TRANSIT_SPLIT_DISMISS -> "SPLIT_DISMISS";
            case TRANSIT_MAXIMIZE -> "MAXIMIZE";
            case TRANSIT_RESTORE_FROM_MAXIMIZE -> "RESTORE_FROM_MAXIMIZE";
            case TRANSIT_PIP_BOUNDS_CHANGE -> "PIP_BOUNDS_CHANGE";
            case TRANSIT_TASK_FRAGMENT_DRAG_RESIZE -> "TASK_FRAGMENT_DRAG_RESIZE";
            case TRANSIT_SPLIT_PASSTHROUGH -> "SPLIT_PASSTHROUGH";
            case TRANSIT_CLEANUP_PIP_EXIT -> "CLEANUP_PIP_EXIT";
            case TRANSIT_MINIMIZE -> "MINIMIZE";
            case TRANSIT_START_RECENTS_TRANSITION -> "START_RECENTS_TRANSITION";
            case TRANSIT_END_RECENTS_TRANSITION -> "END_RECENTS_TRANSITION";
            case TRANSIT_CONVERT_TO_BUBBLE -> "CONVERT_TO_BUBBLE";
            case TRANSIT_BUBBLE_CONVERT_FLOATING_TO_BAR -> "BUBBLE_CONVERT_FLOATING_TO_BAR";
            default -> "";
        };
        if (typeStr.isEmpty()) {
            typeStr = DesktopModeTransitionTypes.transitTypeToString(transitType);
        }
        return typeStr + "(FIRST_CUSTOM+" + (transitType - TRANSIT_FIRST_CUSTOM) + ")";
    }
}
