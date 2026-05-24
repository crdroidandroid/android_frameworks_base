/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.view.WindowManager.transitTypeToString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Trace;
import android.util.Slog;

import com.android.window.flags.Flags;

import java.util.ArrayList;

/**
 * Represents a chain of WM actions where each action is "caused by" the prior action (except the
 * first one of course). A whole chain is associated with one Transition (in fact, the purpose
 * of this object is to communicate, to all callees, which transition they are part of).
 *
 * A single action is defined as "one logical thing requested of WM". This usually corresponds to
 * each ingress-point into the process. For example, when starting an activity:
 *   * the first action is to pause the current/top activity.
 *       At this point, control leaves the process while the activity pauses.
 *   * Then WM receives completePause (a new ingress). This is a new action that gets linked
 *       to the prior action. This action involves resuming the next activity, at which point,
 *       control leaves the process again.
 *   * Eventually, when everything is done, we will have formed a chain of actions.
 *
 * We don't technically need to hold onto each prior action in the chain once a new action has
 * been linked to the same transition; however, keeping the whole chain enables improved
 * debugging and the ability to detect anomalies.
 */
public class ActionChain {
    private static final String TAG = "TransitionChain";

    /**
     * Normal link type. This means the action was expected and is properly linked to the
     * current chain.
     */
    static final int TYPE_NORMAL = 0;

    /**
     * This is the "default" link. It means we haven't done anything to properly track this case
     * so it may or may not be correct. It represents the behavior as if there was no tracking.
     *
     * Any type that has "default" behavior uses the global "collecting transition" if it exists,
     * otherwise it doesn't use any transition.
     */
    static final int TYPE_DEFAULT = 1;

    /**
     * This means the action was performed via a legacy code-path. These should be removed
     * eventually. This will have the "default" behavior.
     */
    static final int TYPE_LEGACY = 2;

    /** This is for a test. */
    static final int TYPE_TEST = 3;

    /** This is finishing a transition. Collection isn't supported during this. */
    static final int TYPE_FINISH = 4;

    /**
     * Something unexpected happened so this action was started to recover from the unexpected
     * state. This means that a "real" chain-link couldn't be determined. For now, the behavior of
     * this is the same as "default".
     */
    static final int TYPE_FAILSAFE = 5;

    /**
     * Types of chain links (ie. how is this action associated with the chain it is linked to).
     * @hide
     */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_NORMAL,
            TYPE_DEFAULT,
            TYPE_LEGACY,
            TYPE_TEST,
            TYPE_FINISH,
            TYPE_FAILSAFE
    })
    public @interface LinkType {}

    /** Identifies the entry-point of this action. */
    @NonNull
    final String mSource;

    /** Reference to ATMS. TEMPORARY! ONLY USE THIS WHEN tracker_plumbing flag is DISABLED! */
    @Nullable
    ActivityTaskManagerService mTmpAtm;

    /** The transition that this chain's changes belong to. */
    @Nullable
    private Transition mTransition;

    /** The previous action in the chain. */
    @Nullable
    ActionChain mPrevious = null;

    /** Classification of how this action is connected to the chain. */
    @LinkType int mType = TYPE_NORMAL;

    /** When this Action started. */
    long mCreateTimeMs;

    private ActionChain(String source, @LinkType int type, Transition transit) {
        mSource = source;
        mCreateTimeMs = System.currentTimeMillis();
        mType = type;
        mTransition = transit;
        if (mTransition != null) {
            mTransition.recordChain(this);
        }
    }

    void attachTransition(Transition transit) {
        if (mTransition != null) {
            throw new IllegalStateException("can't attach transition to chain that is already"
                    + " attached to a transition");
        }
        if (mPrevious != null) {
            throw new IllegalStateException("Can only attach transition to the head of a chain");
        }
        mTransition = transit;
        if (mTransition != null) {
            mTransition.recordChain(this);
        }
    }

    @Nullable
    Transition getTransition() {
        if (!Flags.transitTrackerPlumbing()) {
            return isFinishing() ? mTransition
                    : mTmpAtm.getTransitionController().getCollectingTransition();
        }
        return mTransition;
    }

    void detachTransition() {
        mTransition = null;
    }

    boolean isFinishing() {
        return mType == TYPE_FINISH;
    }

    boolean isCollecting() {
        final Transition transition = getTransition();
        return transition != null && transition.isCollecting();
    }

    /** Returns {@code true} if the display contains a collecting transition. */
    boolean isCollectingOnDisplay(@NonNull DisplayContent dc) {
        return isCollecting() && getTransition().isOnDisplay(dc);
    }

    /**
     * Some common checks to determine (and report) whether this chain has a collecting transition.
     * Returns the collecting transition or {@code null} if there is an issue.
     */
    private Transition expectCollecting() {
        final Transition transition = getTransition();
        if (transition == null) {
            Slog.e(TAG, "Can't collect into a chain with no transition");
            return null;
        }
        if (isFinishing()) {
            Slog.e(TAG, "Trying to collect into a finished transition");
            return null;
        }
        if (transition.mController.getCollectingTransition() != mTransition) {
            Slog.e(TAG, "Mismatch between current collecting ("
                    + transition.mController.getCollectingTransition() + ") and chain ("
                    + transition + ")");
            return null;
        }
        return transition;
    }

    /**
     * Helper to collect a container into the associated transition. This will automatically do
     * nothing if the chain isn't associated with a collecting transition.
     */
    void collect(@NonNull WindowContainer wc) {
        if (!wc.mTransitionController.isShellTransitionsEnabled()) return;
        final Transition transition = expectCollecting();
        if (transition == null) return;
        transition.collect(wc);
    }

    /**
     * Collects a window container which will be removed or invisible.
     */
    void collectClose(@NonNull WindowContainer<?> wc) {
        if (!wc.mTransitionController.isShellTransitionsEnabled()) return;
        final Transition transition = expectCollecting();
        if (transition == null)
            return;
        if (wc.isVisibleRequested()) {
            transition.collectExistenceChange(wc);
        } else {
            // Removing a non-visible window doesn't require a transition, but if there is one
            // collecting, this should be a member just in case.
            collect(wc);
        }
    }

    /**
     * @return The chain link where this chain was first associated with a transition.
     */
    private ActionChain getTransitionSource() {
        if (mTransition == null) return null;
        ActionChain out = this;
        while (out.mPrevious != null && out.mPrevious.mTransition != null) {
            out = out.mPrevious;
        }
        return out;
    }

    private static class AsyncStart {
        final int mStackPos;
        long mThreadId;
        AsyncStart(int stackPos) {
            mStackPos = stackPos;
        }
    }

    /**
     * An interface for creating and tracking action chains.
     */
    static class Tracker {
        private final ActivityTaskManagerService mAtm;

        /**
         * Track the current stack of nested chain entries within a synchronous operation. Chains
         * can nest when some entry-points are, themselves, used within the logic of another
         * entry-point.
         */
        private final ArrayList<ActionChain> mStack = new ArrayList<>();

        /** thread-id of the current action. Used to detect mismatched start/end situations. */
        private long mCurrentThread;

        /** Stack of suspended actions for dealing with async-start "gaps". */
        private final ArrayList<AsyncStart> mAsyncStarts = new ArrayList<>();

        private final Stats mStats = new Stats();

        Tracker(ActivityTaskManagerService atm) {
            mAtm = atm;
        }

        private ActionChain makeChain(String source, @LinkType int type, Transition transit) {
            int base = getThreadBase();
            if (base < mStack.size()) {
                // verify thread-id matches. This isn't a perfect check, but it should be
                // reasonably effective at detecting imbalance.
                long expectedThread = mAsyncStarts.isEmpty() ? mCurrentThread
                        : mAsyncStarts.getLast().mThreadId;
                if (Thread.currentThread().getId() != expectedThread) {
                    // This means something went wrong. Reset the stack.
                    String msg = "Likely improperly balanced ActionChain: ["
                            + mStack.get(base).mSource;
                    for (int i = (base + 1); i < mStack.size(); ++i) {
                        msg += ", " + mStack.get(i).mSource;
                    }
                    Slog.wtfStack(TAG, msg + "]");
                    mStack.subList(base, mStack.size()).clear();
                }
            } else if (!mAsyncStarts.isEmpty()) {
                mAsyncStarts.getLast().mThreadId = Thread.currentThread().getId();
            } else {
                mCurrentThread = Thread.currentThread().getId();
            }
            mStack.add(new ActionChain(source, type, transit));
            if (!Flags.transitTrackerPlumbing()) {
                mStack.getLast().mTmpAtm = mAtm;
            }
            return mStack.getLast();
        }

        private ActionChain makeChain(String source, @LinkType int type) {
            return makeChain(source, type,
                    mAtm.getTransitionController().getCollectingTransition());
        }

        /**
         * async start is the one "gap" where normally-contained actions can "interrupt"
         * an ongoing one, so detect/handle those specially.
         */
        private int getThreadBase() {
            if (mAsyncStarts.isEmpty()) return 0;
            return mAsyncStarts.getLast().mStackPos;
        }

        /**
         * There are some complicated call-paths through WM which are unnecessarily messy to plumb
         * through or which travel out of the WMS/ATMS domain (eg. into policy). For these cases,
         * we assume that as long as we still have a synchronous call stack, the same initial
         * action should apply. This means we can use a stack of "nesting" chains to associate
         * deep call-paths with their shallower counterparts.
         *
         * Starting a chain will push onto the stack, calling {@link #endPartial} will pop off the
         * stack, and calling `end` here will *clear* the stack.
         *
         * Unlike {@link #endPartial}, this `end` call is for closing a top-level session. It will
         * error if its associated start/end are, themselves, nested. This is used as a safety
         * measure to catch cases where a start is missing a corresponding end.
         *
         * @see #endPartial
         */
        void end() {
            int base = getThreadBase();
            if (mStack.size() > (base + 1)) {
                String msg = "Improperly balanced ActionChain: [" + mStack.get(base).mSource;
                for (int i = (base + 1); i < mStack.size(); ++i) {
                    msg += ", " + mStack.get(i).mSource;
                }
                Slog.wtfStack(TAG, msg + "]");
            }
            mStack.subList(base, mStack.size()).clear();
        }

        /**
         * Like {@link #end} except it just simply pops without checking if it is a root-level
         * session. This should only be used when there's a chance that the associated start/end
         * will, itself, be nested.
         *
         * @see #end
         */
        void endPartial() {
            if (mStack.isEmpty()) {
                Slog.wtfStack(TAG, "Trying to double-close action-chain");
                return;
            }
            mStack.removeLast();
        }

        /**
         * Temporary query. Eventually anything that needs to check this should have its own chain
         * link.
         */
        boolean isInChain() {
            return !mStack.isEmpty();
        }

        /**
         * Special handling during "gaps" in atomicity while using the async-start hack. The
         * "end" tracking needs to account for this and we also want to track/report how often
         * this happens.
         */
        void pushAsyncStart() {
            if (mStack.isEmpty()) {
                Slog.wtfStack(TAG, "AsyncStart outside of chain!?");
                return;
            }
            mAsyncStarts.add(new AsyncStart(mStack.size()));
        }

        void popAsyncStart() {
            mAsyncStarts.removeLast();
        }

        /**
         * Starts tracking a normal action.
         * @see #TYPE_NORMAL
         */
        @NonNull
        ActionChain start(String source, Transition transit) {
            boolean isTransitionNew = transit.mChainHead == null;
            final ActionChain out = makeChain(source, TYPE_NORMAL, transit);
            if (isTransitionNew) {
                mStats.onTransitionCreated(out);
            } else {
                mStats.onTransitionContinued(out);
            }
            return out;
        }

        /** @see #TYPE_DEFAULT */
        @NonNull
        ActionChain startDefault(String source) {
            return makeChain(source, TYPE_DEFAULT);
        }

        /**
         * Create a chain-link for a decision-point between making a new transition or using the
         * global collecting one. A new transition is the desired outcome in this case.
         */
        @NonNull
        ActionChain startTransit(String source) {
            final ActionChain out = makeChain(source, TYPE_DEFAULT);
            if (out.isCollecting()) {
                mStats.onTransitionCombined(out);
            }
            return out;
        }

        /**
         * Starts tracking an action that finishes a transition.
         * @see #TYPE_NORMAL
         */
        @NonNull
        ActionChain startFinish(String source, Transition finishTransit) {
            return makeChain(source, TYPE_FINISH, finishTransit);
        }

        /** @see #TYPE_LEGACY */
        @NonNull
        ActionChain startLegacy(String source) {
            return makeChain(source, TYPE_LEGACY, null);
        }

        /** @see #TYPE_FAILSAFE */
        @NonNull
        ActionChain startFailsafe(String source) {
            return makeChain(source, TYPE_FAILSAFE);
        }
    }

    /** Helpers for usage in tests. */
    @NonNull
    static ActionChain test() {
        return new ActionChain("test", TYPE_TEST, null /* transition */);
    }

    @NonNull
    static ActionChain testFinish(Transition toFinish) {
        return new ActionChain("test", TYPE_FINISH, toFinish);
    }

    static class Stats {
        void onTransitionCreated(ActionChain head) {
        }

        /**
         * A chain link was added for a unique transition that was forced to be combined into an
         * already-collecting transition.
         */
        void onTransitionCombined(ActionChain head) {
            final Transition transit = head.getTransition();
            if (!Flags.transitTrackerPlumbing() && transit == null) {
                return;
            }
            final String tsum = transitSummary(transit);
            final ActionChain tsource = head.getTransitionSource();
            Trace.instantForTrack(Trace.TRACE_TAG_WINDOW_MANAGER, "TransitCombine",
                    head.mSource + "->" + tsum + ":" + tsource.mSource);
            Slog.w(TAG, "Combining " + head.mSource + " into " + "#" + transit.getSyncId()
                    + "(" + tsum + ") from " + tsource.mSource);
        }

        /**
         * A chain link was added to continue an already-collecting transition.
         */
        void onTransitionContinued(ActionChain head) {
            if (!Trace.isTagEnabled(Trace.TRACE_TAG_WINDOW_MANAGER)) {
                return;
            }
            final Transition transit = head.getTransition();
            if (!Flags.transitTrackerPlumbing() && transit == null) {
                return;
            }
            final String tsum = transitSummary(transit);
            final ActionChain tsource = head.getTransitionSource();
            Trace.instantForTrack(Trace.TRACE_TAG_WINDOW_MANAGER, "TransitContinue",
                    head.mSource + "->" + tsum + ":" + tsource.mSource);
        }

        private static String transitSummary(Transition t) {
            return transitTypeToString(t.mType) + "|" + (t.mLogger.mFromPlayer ? "" : "R")
                    + "|0x" + Integer.toHexString(t.getFlags());
        }
    }
}
