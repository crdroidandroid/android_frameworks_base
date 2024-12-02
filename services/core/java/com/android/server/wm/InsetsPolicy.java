/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.InsetsSource.ID_IME;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static com.android.window.flags.Flags.relativeInsets;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.StatusBarManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.InsetsController;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.util.List;

/**
 * Policy that implements who gets control over the windows generating insets.
 */
class InsetsPolicy {

    public static final int CONTROLLABLE_TYPES = WindowInsets.Type.statusBars()
            | WindowInsets.Type.navigationBars()
            | WindowInsets.Type.ime();

    @NonNull
    private final InsetsStateController mStateController;
    @NonNull
    private final DisplayContent mDisplayContent;
    @NonNull
    private final DisplayPolicy mPolicy;

    /** Used to show system bars transiently. This won't affect the layout. */
    @NonNull
    private final InsetsControlTarget mShowingTransientControlTarget;

    /** Used to show system bars permanently. This can affect the layout. */
    @NonNull
    private final InsetsControlTarget mShowingPermanentControlTarget;

    /** Used to hide system bars permanently. This can affect the layout. */
    @NonNull
    private final InsetsControlTarget mHidingPermanentControlTarget;

    /**
     * Used to override the visibility of {@link Type#statusBars()} when dispatching insets to
     * clients.
     */
    @Nullable
    private InsetsControlTarget mFakeStatusControlTarget;

    /**
     * Used to override the visibility of {@link Type#navigationBars()} when dispatching insets to
     * clients.
     */
    @Nullable
    private InsetsControlTarget mFakeNavControlTarget;

    /**
     * Used to check if the caller is qualified to abort the transient state of status bar.
     */
    @Nullable
    private InsetsControlTarget mHidingTransientStatusControlTarget;

    /**
     * Used to check if the caller is qualified to abort the transient state of nav bar.
     */
    @Nullable
    private InsetsControlTarget mHidingTransientNavControlTarget;

    @Nullable
    private WindowState mFocusedWin;
    @NonNull
    private final BarWindow mStatusBar = new BarWindow(StatusBarManager.WINDOW_STATUS_BAR);
    @NonNull
    private final BarWindow mNavBar = new BarWindow(StatusBarManager.WINDOW_NAVIGATION_BAR);

    /**
     * Types shown transiently because of the user action.
     */
    @InsetsType
    private int mShowingTransientTypes;

    /**
     * Types shown transiently are now hiding.
     */
    @InsetsType
    private int mHidingTransientTypes;

    /**
     * Types shown permanently by the upstream caller.
     */
    @InsetsType
    private int mForciblyShowingTypes;

    /**
     * Types hidden permanently by the upstream caller.
     */
    @InsetsType
    private int mForciblyHidingTypes;

    private final boolean mHideNavBarForKeyboard;

    private long mLastSwipeTime;
    private long mLastUnlockedTime;
    private boolean mLockedGesture = false;

    InsetsPolicy(@NonNull InsetsStateController stateController,
            @NonNull DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
        final Resources r = mPolicy.getContext().getResources();
        mHideNavBarForKeyboard = r.getBoolean(R.bool.config_hideNavBarForKeyboard);
        mShowingTransientControlTarget = new ControlTarget(
                this, true /* showing */, false /* permanent */);
        mShowingPermanentControlTarget = new ControlTarget(
                this, true /* showing */, true /* permanent */);
        mHidingPermanentControlTarget = new ControlTarget(
                this, false /* showing */, true /* permanent */);
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        @InsetsType
        final int[] requestedVisibleTypes =
                {focusedWin != null ? focusedWin.getRequestedVisibleTypes() : 0};
        if ((mShowingTransientTypes & Type.statusBars()) != 0
                        && mFakeStatusControlTarget != null
                        && mFakeStatusControlTarget != getStatusControlTarget(
                                focusedWin, true, requestedVisibleTypes)
                || (mShowingTransientTypes & Type.navigationBars()) != 0
                        && mFakeNavControlTarget != null
                        && mFakeNavControlTarget != getNavControlTarget(
                                focusedWin, true, requestedVisibleTypes)) {
            // The fake control target is the target which was hiding the system bar before showing
            // the transient bar. Abort the transient bar if any of the fake control targets is
            // changed, so the request of the new target can be applied.
            abortTransient();
        }
        mFocusedWin = focusedWin;
        final WindowState notificationShade = mPolicy.getNotificationShade();
        final WindowState topApp = mPolicy.getTopFullscreenOpaqueWindow();
        final InsetsControlTarget statusControlTarget =
                getStatusControlTarget(focusedWin, false /* fake */, requestedVisibleTypes);
        mFakeStatusControlTarget = statusControlTarget == mShowingTransientControlTarget
                ? getStatusControlTarget(focusedWin, true /* fake */, requestedVisibleTypes)
                : statusControlTarget == notificationShade
                        ? getStatusControlTarget(topApp, true /* fake */, requestedVisibleTypes)
                        : null;
        final InsetsControlTarget navControlTarget =
                getNavControlTarget(focusedWin, false /* fake */, requestedVisibleTypes);
        mFakeNavControlTarget = navControlTarget == mShowingTransientControlTarget
                ? getNavControlTarget(focusedWin, true /* fake */, requestedVisibleTypes)
                : navControlTarget == notificationShade
                        ? getNavControlTarget(topApp, true /* fake */, requestedVisibleTypes)
                        : null;
        mStateController.onBarControlTargetChanged(
                statusControlTarget, mFakeStatusControlTarget,
                navControlTarget, mFakeNavControlTarget);

        if (statusControlTarget == mDisplayContent.mRemoteInsetsControlTarget
                && navControlTarget == mDisplayContent.mRemoteInsetsControlTarget) {
            notifyRemoteInsetsController(focusedWin, requestedVisibleTypes[0]);
        }

        mStatusBar.updateVisibility(statusControlTarget, Type.statusBars());
        mNavBar.updateVisibility(navControlTarget, Type.navigationBars());

        if (((mHidingTransientTypes & Type.statusBars()) != 0
                        && mHidingTransientStatusControlTarget != mFakeStatusControlTarget
                        && mHidingTransientStatusControlTarget != statusControlTarget)
                || ((mHidingTransientTypes & Type.navigationBars()) != 0
                        && mHidingTransientNavControlTarget != mFakeNavControlTarget
                        && mHidingTransientNavControlTarget != navControlTarget)) {
            // The target responsible for playing the animation of hiding transient bars is gone.
            // Here aborts the transient state.
            abortTransient();
        }
    }

    boolean hasHiddenSources(@InsetsType int types) {
        final InsetsState state = mStateController.getRawInsetsState();
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if ((source.getType() & types) == 0) {
                continue;
            }
            if (!source.getFrame().isEmpty() && !source.isVisible()) {
                return true;
            }
        }
        return false;
    }

    void showTransient(@InsetsType int types, boolean isGestureOnSystemBar) {
        showTransient(types, isGestureOnSystemBar, false);
    }

    void showTransient(@InsetsType int types, boolean isGestureOnSystemBar, boolean swipeOnStatusBar) {
        @InsetsType
        int showingTransientTypes = mShowingTransientTypes;
        final InsetsState rawState = mStateController.getRawInsetsState();
        final boolean isGestureLocked = isGestureLocked(!swipeOnStatusBar);
        for (int i = rawState.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = rawState.sourceAt(i);
            if (source.isVisible()) {
                continue;
            }
            @InsetsType
            final int type = source.getType();
            if ((source.getType() & types) == 0) {
                continue;
            }
            if (isGestureLocked && (
                    ((type & Type.statusBars()) != 0 && !swipeOnStatusBar) ||
                    ((type & Type.navigationBars()) != 0)
                    )) {
                continue;
            }
            showingTransientTypes |= type;
        }
        if (isGestureLocked && !swipeOnStatusBar) {
            warnGestureLocked();
        }
        if (mShowingTransientTypes != showingTransientTypes) {
            mShowingTransientTypes = showingTransientTypes;
            mHidingTransientTypes &= ~showingTransientTypes;
            StatusBarManagerInternal statusBarManagerInternal =
                    mPolicy.getStatusBarManagerInternal();
            if (statusBarManagerInternal != null) {
                statusBarManagerInternal.showTransient(mDisplayContent.getDisplayId(),
                        showingTransientTypes, isGestureOnSystemBar);
            }
            updateBarControlTarget(mFocusedWin);
            dispatchTransientSystemBarsVisibilityChanged(
                    mFocusedWin,
                    (showingTransientTypes & (Type.statusBars() | Type.navigationBars())) != 0,
                    isGestureOnSystemBar);
        }
    }

    @VisibleForTesting
    @NonNull
    InsetsControlTarget getShowingTransientControlTarget() {
        return mShowingTransientControlTarget;
    }

    @VisibleForTesting
    @NonNull
    InsetsControlTarget getShowingPermanentControlTarget() {
        return mShowingPermanentControlTarget;
    }

    void hideTransient() {
        if (mShowingTransientTypes == 0) {
            return;
        }
        mHidingTransientTypes = mShowingTransientTypes;
        mHidingTransientStatusControlTarget = mFakeStatusControlTarget;
        mHidingTransientNavControlTarget = mFakeNavControlTarget;

        dispatchTransientSystemBarsVisibilityChanged(
                mFocusedWin,
                /* areVisible= */ false,
                /* wereRevealedFromSwipeOnSystemBar= */ false);

        mShowingTransientTypes = 0;
        updateBarControlTarget(mFocusedWin);
    }

    void onAnimatingTypesChanged(InsetsControlTarget caller,
            @InsetsType int lastTypes, @InsetsType int newTypes) {
        final @InsetsType int diff = lastTypes ^ newTypes;
        @InsetsType int abortTypes = 0;
        if (caller == mHidingTransientStatusControlTarget
                && (mHidingTransientTypes & Type.statusBars()) != 0
                && (diff & Type.statusBars()) != 0
                && (newTypes & Type.statusBars()) == 0) {
            mHidingTransientStatusControlTarget = null;
            mHidingTransientTypes &= ~Type.statusBars();
            abortTypes |= Type.statusBars();
        }
        if (caller == mHidingTransientNavControlTarget
                && (mHidingTransientTypes & Type.navigationBars()) != 0
                && (diff & Type.navigationBars()) != 0
                && (newTypes & Type.navigationBars()) == 0) {
            mHidingTransientNavControlTarget = null;
            mHidingTransientTypes &= ~Type.navigationBars();
            abortTypes |= Type.navigationBars();
        }
        if (abortTypes != 0) {
            sendAbortTransient(abortTypes);
        }
    }

    boolean isTransient(@InsetsType int type) {
        return (mShowingTransientTypes & type) != 0;
    }

    /**
     * Adjusts the sources in {@code originalState} to account for things like transient bars, IME
     * & rounded corners.
     */
    @NonNull
    InsetsState adjustInsetsForWindow(@NonNull WindowState target,
            @NonNull InsetsState originalState, boolean includesTransient) {
        InsetsState state;
        if (!includesTransient) {
            state = adjustVisibilityForFakeControllingSources(originalState);
        } else {
            state = originalState;
        }
        state = adjustVisibilityForIme(target, state, state == originalState);
        state = mPolicy.replaceInsetsSourcesIfNeeded(state, state == originalState);
        return adjustInsetsForRoundedCorners(target.mToken, state, state == originalState);
    }

    @NonNull
    InsetsState adjustInsetsForWindow(@NonNull WindowState target,
            @NonNull InsetsState originalState) {
        return adjustInsetsForWindow(target, originalState, false);
    }

    /**
     * @see WindowState#getInsetsState()
     */
    void getInsetsForWindowMetrics(@Nullable WindowToken token,
            @NonNull InsetsState outInsetsState) {
        final InsetsState srcState = token != null && token.isFixedRotationTransforming()
                ? token.getFixedRotationTransformInsetsState()
                : mStateController.getRawInsetsState();
        outInsetsState.set(srcState, true /* copySources */);
        for (int i = outInsetsState.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = outInsetsState.sourceAt(i);
            if (isTransient(source.getType())) {
                source.setVisible(false);
            }
        }
        adjustInsetsForRoundedCorners(token, outInsetsState, false /* copyState */);
        if (token != null && token.hasSizeCompatBounds()) {
            outInsetsState.scale(1f / token.getCompatScale());
        }
    }

    /**
     * Modifies the given {@code state} according to insets provided by the target. When performing
     * layout of the target or dispatching insets to the target, we need to exclude sources which
     * should not be received by the target. e.g., the visible (non-gesture-wise) source provided by
     * the target window itself.
     *
     * <p>We also need to exclude certain types of insets source for client within specific
     * windowing modes.
     *
     * @param target the target on which the policy is applied
     * @param state  the input inset state containing all the sources
     * @return The state stripped of the necessary information.
     */
    @NonNull
    InsetsState enforceInsetsPolicyForTarget(@NonNull WindowState target,
            @NonNull InsetsState state) {
        final InsetsState originalState = state;
        final WindowManager.LayoutParams attrs = target.mAttrs;

        // The caller should not receive the visible insets provided by itself.
        if (attrs.type == TYPE_INPUT_METHOD) {
            state = new InsetsState(state);
            state.removeSource(ID_IME);
        } else if (attrs.providedInsets != null) {
            for (InsetsFrameProvider provider : attrs.providedInsets) {
                if ((provider.getType() & WindowInsets.Type.systemBars()) == 0) {
                    continue;
                }
                if (state == originalState) {
                    state = new InsetsState(state);
                }
                state.removeSource(provider.getId());
            }
        }

        if (!relativeInsets() && (!attrs.isFullscreen() || attrs.getFitInsetsTypes() != 0)) {
            if (state == originalState) {
                state = new InsetsState(originalState);
            }
            // Explicitly exclude floating windows from receiving caption insets. This is because we
            // hard code caption insets for windows due to a synchronization issue that leads to
            // flickering that bypasses insets frame calculation, which consequently needs us to
            // remove caption insets from floating windows.
            // TODO(b/254128050): Remove this workaround after we find a way to update window frames
            //  and caption insets frames simultaneously.
            for (int i = state.sourceSize() - 1; i >= 0; i--) {
                if (state.sourceAt(i).getType() == Type.captionBar()) {
                    state.removeSourceAt(i);
                }
            }
        }

        final SparseArray<InsetsSourceProvider> providers = mStateController.getSourceProviders();
        final int windowType = attrs.type;
        for (int i = providers.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider otherProvider = providers.valueAt(i);
            if (otherProvider.overridesFrame(windowType)) {
                if (state == originalState) {
                    state = new InsetsState(state);
                }
                final InsetsSource override = new InsetsSource(otherProvider.getSource());
                override.setFrame(otherProvider.getOverriddenFrame(windowType));
                state.addSource(override);
            }
        }

        final @WindowConfiguration.WindowingMode int windowingMode = target.getWindowingMode();
        if (WindowConfiguration.isFloating(windowingMode)
                || (windowingMode == WINDOWING_MODE_MULTI_WINDOW && target.isAlwaysOnTop())) {
            // Keep frames, caption, and IME.
            int types = WindowInsets.Type.captionBar();
            if (windowingMode != WINDOWING_MODE_PINNED
                    && mDisplayContent.getImeInputTarget() instanceof WindowState imeTarget
                    && (target == imeTarget
                    || (target.getTask() != null && target.getTask() == imeTarget.getTask()))
                    && imeTarget.isRequestedVisible(WindowInsets.Type.ime())) {
                types |= WindowInsets.Type.ime();
            }
            final InsetsState newState = new InsetsState();
            newState.set(state, types);
            state = newState;
        }

        return state;
    }

    @NonNull
    private InsetsState adjustVisibilityForFakeControllingSources(
            @NonNull InsetsState originalState) {
        if (mFakeStatusControlTarget == null && mFakeNavControlTarget == null) {
            return originalState;
        }
        InsetsState state = originalState;
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            state = adjustVisibilityForFakeControllingSource(state, Type.statusBars(), source,
                    mFakeStatusControlTarget);
            state = adjustVisibilityForFakeControllingSource(state, Type.navigationBars(), source,
                    mFakeNavControlTarget);
        }
        return state;
    }

    @NonNull
    private static InsetsState adjustVisibilityForFakeControllingSource(
            @NonNull InsetsState originalState, @InsetsType int type,
            @NonNull InsetsSource source, @Nullable InsetsControlTarget target) {
        if (source.getType() != type || target == null) {
            return originalState;
        }
        final boolean isRequestedVisible = target.isRequestedVisible(type);
        if (source.isVisible() == isRequestedVisible) {
            return originalState;
        }
        // The source will be modified, create a non-deep copy to store the new one.
        final InsetsState state = new InsetsState(originalState);

        // Replace the source with a copy with the overridden visibility.
        final InsetsSource outSource = new InsetsSource(source);
        outSource.setVisible(isRequestedVisible);
        state.addSource(outSource);
        return state;
    }

    @NonNull
    private InsetsState adjustVisibilityForIme(@NonNull WindowState w,
            @NonNull InsetsState originalState, boolean copyState) {
        if (w.mIsImWindow) {
            InsetsState state = originalState;
            // If navigation bar is not hidden by IME, IME should always receive visible
            // navigation bar insets.
            final boolean navVisible = !mHideNavBarForKeyboard;
            for (int i = originalState.sourceSize() - 1; i >= 0; i--) {
                final InsetsSource source = originalState.sourceAt(i);
                if (source.getType() != Type.navigationBars() || source.isVisible() == navVisible) {
                    continue;
                }
                if (state == originalState && copyState) {
                    state = new InsetsState(originalState);
                }
                final InsetsSource navSource = new InsetsSource(source);
                navSource.setVisible(navVisible);
                state.addSource(navSource);
            }
            return state;
        } else if (w.mImeInsetsConsumed) {
            // Set the IME source (if there is one) to be invisible if it has been consumed.
            final InsetsSource originalImeSource = originalState.peekSource(ID_IME);
            if (originalImeSource != null && originalImeSource.isVisible()) {
                final InsetsState state = copyState
                        ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource imeSource = new InsetsSource(originalImeSource);
                imeSource.setVisible(false);
                state.addSource(imeSource);
                return state;
            }
        } else if ((w.mMergedExcludeInsetsTypes & WindowInsets.Type.ime()) != 0) {
            // In some cases (e.g. split screen from when the IME was requested and the animation
            // actually starts) the insets should not be send, unless the flag is unset.
            final InsetsSource originalImeSource = originalState.peekSource(ID_IME);
            if (originalImeSource != null && originalImeSource.isVisible()) {
                final InsetsState state = copyState
                        ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource imeSource = new InsetsSource(originalImeSource);
                // Setting the height to zero, pretending we're in floating mode
                imeSource.setFrame(0, 0, 0, 0);
                imeSource.setVisibleFrame(imeSource.getFrame());
                state.addSource(imeSource);
                return state;
            }
        }
        return originalState;
    }

    @NonNull
    private InsetsState adjustInsetsForRoundedCorners(@Nullable WindowToken token,
            @NonNull InsetsState originalState, boolean copyState) {
        if (token != null) {
            final ActivityRecord activityRecord = token.asActivityRecord();
            final Task task = activityRecord != null ? activityRecord.getTask() : null;
            if (task != null && !task.getWindowConfiguration().tasksAreFloating()) {
                // Use task bounds to calculating rounded corners if the task is not floating.
                final InsetsState state = copyState ? new InsetsState(originalState)
                        : originalState;
                state.setRoundedCornerFrame(token.isFixedRotationTransforming()
                        ? token.getFixedRotationTransformDisplayBounds()
                        : task.getBounds());
                return state;
            }
        }
        return originalState;
    }

    void onRequestedVisibleTypesChanged(@NonNull InsetsTarget caller, @InsetsType int changedTypes,
            @Nullable ImeTracker.Token statsToken) {
        mStateController.onRequestedVisibleTypesChanged(caller, changedTypes, statsToken);
        checkAbortTransient(caller);
        updateBarControlTarget(mFocusedWin);
    }

    /**
     * Called when a control target modified the insets state. If the target set a insets source to
     * visible while it is shown transiently, we need to abort the transient state. While IME is
     * requested visible, we also need to abort the transient state of navigation bar if it is shown
     * transiently.
     *
     * @param caller who changed the insets state.
     */
    private void checkAbortTransient(@NonNull InsetsTarget caller) {
        if (mShowingTransientTypes == 0) {
            return;
        }
        final boolean isImeVisible = mStateController.getImeSourceProvider().isClientVisible();
        final @InsetsType int fakeControllingTypes =
                mStateController.getFakeControllingTypes(caller);
        final @InsetsType int abortTypes =
                (fakeControllingTypes & caller.getRequestedVisibleTypes())
                        | (isImeVisible ? Type.navigationBars() : 0);
        mShowingTransientTypes &= ~abortTypes;
        mHidingTransientTypes &= ~abortTypes;
        if (abortTypes != 0) {
            if ((abortTypes & Type.statusBars()) != 0) {
                mHidingTransientStatusControlTarget = null;
            }
            if ((abortTypes & Type.navigationBars()) != 0) {
                mHidingTransientNavControlTarget = null;
            }
            mDisplayContent.setLayoutNeeded();
            mDisplayContent.mWmService.requestTraversal();
            sendAbortTransient(abortTypes);
        }
    }

    /**
     * If the caller is not {@link #updateBarControlTarget}, it should call
     * updateBarControlTarget(mFocusedWin) after this invocation.
     */
    private void abortTransient() {
        if (mShowingTransientTypes == 0 && mHidingTransientTypes == 0) {
            return;
        }
        sendAbortTransient(mShowingTransientTypes | mHidingTransientTypes);
        mShowingTransientTypes = 0;
        mHidingTransientTypes = 0;
        mHidingTransientStatusControlTarget = null;
        mHidingTransientNavControlTarget = null;
        mDisplayContent.setLayoutNeeded();
        mDisplayContent.mWmService.requestTraversal();

        dispatchTransientSystemBarsVisibilityChanged(
                mFocusedWin,
                /* areVisible= */ false,
                /* wereRevealedFromSwipeOnSystemBar= */ false);
    }

    private void sendAbortTransient(@InsetsType int types) {
        final StatusBarManagerInternal statusBarManager = mPolicy.getStatusBarManagerInternal();
        if (statusBarManager != null) {
            statusBarManager.abortTransient(mDisplayContent.getDisplayId(), types);
        }
    }

    @Nullable
    private InsetsControlTarget getStatusControlTarget(@Nullable WindowState focusedWin,
            boolean fake, @InsetsType int[] requestedVisibleTypes) {
        final InsetsControlTarget target = getStatusControlTargetInner(focusedWin, fake);
        if (remoteInsetsControllerControlsSystemBars(target)) {
            requestedVisibleTypes[0] = (requestedVisibleTypes[0] & ~Type.statusBars())
                    | (target.getRequestedVisibleTypes() & Type.statusBars());
            return mDisplayContent.mRemoteInsetsControlTarget;
        }
        return target;
    }

    @Nullable
    private InsetsControlTarget getStatusControlTargetInner(@Nullable WindowState focusedWin,
            boolean fake) {
        if (!fake && isTransient(Type.statusBars())) {
            return mShowingTransientControlTarget;
        }
        final WindowState notificationShade = mPolicy.getNotificationShade();
        if (focusedWin == notificationShade) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (areTypesForciblyShown(Type.statusBars())) {
            // Status bar is forcibly shown. We don't want the client to control the status bar, and
            // we will dispatch the real visibility of status bar to the client.
            return mShowingPermanentControlTarget;
        }
        if (mPolicy.areInsetsTypesForciblyShownTransiently(Type.statusBars()) && !fake) {
            // Status bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mShowingTransientControlTarget;
        }
        if (areTypesForciblyHidden(Type.statusBars())) {
            // Status bar is forcibly hidden. We don't want the client to control the status bar,
            // and we will dispatch the real visibility of status bar to the client.
            return mHidingPermanentControlTarget;
        }
        if (!canBeTopFullscreenOpaqueWindow(focusedWin)
                && mPolicy.topAppHidesSystemBar(Type.statusBars())
                && (notificationShade == null || !notificationShade.canReceiveKeys())) {
            // Non-fullscreen focused window should not break the state that the top-fullscreen-app
            // window hides status bar, unless the notification shade can receive keys.
            return mPolicy.getTopFullscreenOpaqueWindow();
        }
        return focusedWin;
    }

    private static boolean canBeTopFullscreenOpaqueWindow(@Nullable WindowState win) {
        // The condition doesn't use WindowState#canAffectSystemUiFlags because the window may
        // haven't drawn or committed the visibility.
        final boolean nonAttachedAppWindow = win != null
                && win.mAttrs.type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                && win.mAttrs.type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
        return nonAttachedAppWindow && win.mAttrs.isFullscreen() && !win.isFullyTransparent()
                && !win.inMultiWindowMode();
    }

    @Nullable
    private InsetsControlTarget getNavControlTarget(@Nullable WindowState focusedWin, boolean fake,
            @InsetsType int[] requestedVisibleTypes) {
        final InsetsControlTarget target = getNavControlTargetInner(focusedWin, fake);
        if (remoteInsetsControllerControlsSystemBars(target)) {
            requestedVisibleTypes[0] = (requestedVisibleTypes[0] & ~Type.navigationBars())
                    | (target.getRequestedVisibleTypes() & Type.navigationBars());
            return mDisplayContent.mRemoteInsetsControlTarget;
        }
        return target;
    }

    @Nullable
    private InsetsControlTarget getNavControlTargetInner(@Nullable WindowState focusedWin,
            boolean fake) {
        final WindowState imeWin = mDisplayContent.mInputMethodWindow;
        if (imeWin != null && imeWin.isVisible() && !mHideNavBarForKeyboard) {
            // Force showing navigation bar while IME is visible and if navigation bar is not
            // configured to be hidden by the IME.
            return mShowingPermanentControlTarget;
        }
        if (!fake && isTransient(Type.navigationBars())) {
            return mShowingTransientControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (focusedWin != null) {
            final InsetsSourceProvider provider = focusedWin.getControllableInsetProvider();
            if (provider != null && provider.getSource().getType() == Type.navigationBars()) {
                // Navigation bar has control if it is focused.
                return focusedWin;
            }
        }
        if (areTypesForciblyShown(Type.navigationBars())) {
            // Navigation bar is forcibly shown. We don't want the client to control the navigation
            // bar, and we will dispatch the real visibility of navigation bar to the client.
            return mShowingPermanentControlTarget;
        }
        if (mPolicy.areInsetsTypesForciblyShownTransiently(Type.navigationBars()) && !fake) {
            // Navigation bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mShowingTransientControlTarget;
        }
        if (areTypesForciblyHidden(Type.navigationBars())) {
            // Navigation bar is forcibly hidden. We don't want the client to control the navigation
            // bar, and we will dispatch the real visibility of navigation bar to the client.
            return mHidingPermanentControlTarget;
        }
        final WindowState notificationShade = mPolicy.getNotificationShade();
        if (!canBeTopFullscreenOpaqueWindow(focusedWin)
                && mPolicy.topAppHidesSystemBar(Type.navigationBars())
                && (notificationShade == null || !notificationShade.canReceiveKeys())) {
            // Non-fullscreen focused window should not break the state that the top-fullscreen-app
            // window hides navigation bar, unless the notification shade can receive keys.
            return mPolicy.getTopFullscreenOpaqueWindow();
        }
        return focusedWin;
    }

    private void notifyRemoteInsetsController(@Nullable WindowState win,
            @InsetsType int requestVisibleTypes) {
        if (win == null) {
            return;
        }
        ComponentName component = win.mActivityRecord != null
                ? win.mActivityRecord.mActivityComponent : null;

        mDisplayContent.mRemoteInsetsControlTarget.topFocusedWindowChanged(
                component, requestVisibleTypes);
    }

    boolean areTypesForciblyShown(@InsetsType int types) {
        return (mForciblyShowingTypes & types) == types;
    }

    boolean areTypesForciblyHidden(@InsetsType int types) {
        return (mForciblyHidingTypes & types) == types;
    }

    void updateSystemBars(@Nullable WindowState win, @InsetsType int displayForciblyShowingTypes,
            @InsetsType int displayForciblyHidingTypes, boolean showSystemBarsByLegacyPolicy) {
        final boolean hasDisplayOverride = displayForciblyShowingTypes != 0
                || displayForciblyHidingTypes != 0;
        mForciblyShowingTypes =
                // Force showing navigation bar as long as forceShowingNavigationBars returns true.
                (forceShowingNavigationBars(win)
                        ? Type.navigationBars()
                        : 0)
                | (hasDisplayOverride
                        // Add types forcibly shown by the display if there is any.
                        ? displayForciblyShowingTypes
                        // Otherwise, fallback to the legacy policy.
                        : showSystemBarsByLegacyPolicy
                                ? (Type.statusBars() | Type.navigationBars())
                                : 0);
        mForciblyHidingTypes = displayForciblyHidingTypes;

        // The client app won't be able to control these types of system bars. Here makes the client
        // forcibly consume these types to prevent the app content from getting obscured.
        mStateController.setForcedConsumingTypes(
                mForciblyShowingTypes | (remoteInsetsControllerControlsSystemBars(win)
                        ? (Type.statusBars() | Type.navigationBars())
                        : 0));

        updateBarControlTarget(win);
    }

    private boolean forceShowingNavigationBars(@Nullable WindowState win) {
        // When "force show navigation bar" is enabled, it means both force visible is true, and
        // we are in 3-button navigation. In this mode, the navigation bar is forcibly shown
        // when activity type is ACTIVITY_TYPE_STANDARD which means Launcher or Recent could
        // still control the navigation bar in this mode.
        return mPolicy.isForceShowNavigationBarEnabled() && win != null
                && win.getActivityType() == ACTIVITY_TYPE_STANDARD;
    }

    /**
     * Determines whether the remote insets controller should take control of system bars for all
     * windows.
     */
    final boolean remoteInsetsControllerControlsSystemBars(@Nullable InsetsControlTarget target) {
        if (!(target instanceof WindowState win)) {
            return false;
        }

        if (!mPolicy.isRemoteInsetsControllerControllingSystemBars()) {
            return false;
        }
        if (mDisplayContent == null || mDisplayContent.mRemoteInsetsControlTarget == null) {
            // No remote insets control target to take control of insets.
            return false;
        }
        // If necessary, auto can control application windows when
        // config_remoteInsetsControllerControlsSystemBars is set to true. This is useful in cases
        // where we want to dictate system bar inset state for applications.
        return win.mAttrs.type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                && win.mAttrs.type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
    }

    private void dispatchTransientSystemBarsVisibilityChanged(@Nullable WindowState focusedWindow,
            boolean areVisible, boolean wereRevealedFromSwipeOnSystemBar) {
        if (focusedWindow == null) {
            return;
        }

        Task task = focusedWindow.getTask();
        if (task == null) {
            return;
        }

        int taskId = task.mTaskId;
        boolean isValidTaskId = taskId != ActivityTaskManager.INVALID_TASK_ID;
        if (!isValidTaskId) {
            return;
        }

        mDisplayContent.mWmService.mTaskSystemBarsListenerController
                .dispatchTransientSystemBarVisibilityChanged(
                        taskId,
                        areVisible,
                        wereRevealedFromSwipeOnSystemBar);
    }

    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.println(prefix + "InsetsPolicy");
        prefix = prefix + "  ";
        pw.println(prefix + "status: " + StatusBarManager.windowStateToString(mStatusBar.mState));
        pw.println(prefix + "nav: " + StatusBarManager.windowStateToString(mNavBar.mState));
        if (mShowingTransientTypes != 0) {
            pw.println(prefix + "mShowingTransientTypes="
                    + WindowInsets.Type.toString(mShowingTransientTypes));
        }
        if (mHidingTransientTypes != 0) {
            pw.println(prefix + "mHidingTransientTypes="
                    + WindowInsets.Type.toString(mHidingTransientTypes));
        }
        if (mForciblyShowingTypes != 0) {
            pw.println(prefix + "mForciblyShowingTypes="
                    + WindowInsets.Type.toString(mForciblyShowingTypes));
        }
        if (mForciblyHidingTypes != 0) {
            pw.println(prefix + "mForciblyHidingTypes="
                    + WindowInsets.Type.toString(mForciblyHidingTypes));
        }
        if (mFakeStatusControlTarget != null) {
            pw.println(prefix + "mFakeStatusControlTarget=" + mFakeStatusControlTarget);
        }
        if (mFakeNavControlTarget != null) {
            pw.println(prefix + "mFakeNavControlTarget=" + mFakeNavControlTarget);
        }
        if (mHidingTransientStatusControlTarget != null) {
            pw.println(prefix + "mHidingTransientStatusControlTarget="
                    + mHidingTransientStatusControlTarget);
        }
        if (mHidingTransientNavControlTarget != null) {
            pw.println(prefix + "mHidingTransientNavControlTarget="
                    + mHidingTransientNavControlTarget);
        }
    }

    private final class BarWindow {

        private final int mId;
        @StatusBarManager.WindowVisibleState
        private int mState = StatusBarManager.WINDOW_STATE_SHOWING;

        BarWindow(int id) {
            mId = id;
        }

        private void updateVisibility(@Nullable InsetsControlTarget controlTarget,
                @InsetsType int type) {
            setVisible(controlTarget == null || controlTarget.isRequestedVisible(type));
        }

        private void setVisible(boolean visible) {
            final int state = visible ? WINDOW_STATE_SHOWING : WINDOW_STATE_HIDDEN;
            if (mState != state) {
                mState = state;
                StatusBarManagerInternal statusBarManagerInternal =
                        mPolicy.getStatusBarManagerInternal();
                if (statusBarManagerInternal != null) {
                    statusBarManagerInternal.setWindowState(
                            mDisplayContent.getDisplayId(), mId, state);
                }
            }
        }
    }

    private static class ControlTarget implements InsetsControlTarget, Runnable {

        private static final String FORMAT = "%s%sControlTarget";

        @NonNull
        private final Handler mHandler;
        @NonNull
        private final Object mGlobalLock;
        @NonNull
        private final InsetsState mState = new InsetsState();
        @NonNull
        private final InsetsPolicy mInsetsPolicy;
        @NonNull
        private final InsetsStateController mStateController;
        @NonNull
        private final InsetsController mInsetsController;
        @InsetsType
        private final int mRequestedVisibleTypes;
        @NonNull
        private final String mName;
        @InsetsType
        private int mAnimatingTypes;

        ControlTarget(@NonNull InsetsPolicy insetsPolicy, boolean showing, boolean permanent) {
            final String name = String.format(FORMAT,
                    showing ? "Showing" : "Hiding",
                    permanent ? "Permanent" : "Transient");
            final DisplayContent displayContent = insetsPolicy.mDisplayContent;
            mHandler = displayContent.mWmService.mH;
            mGlobalLock = displayContent.mWmService.mGlobalLock;
            mStateController = displayContent.getInsetsStateController();
            mInsetsPolicy = insetsPolicy;
            mInsetsController = new InsetsController(new Host(mHandler, name, this));
            mRequestedVisibleTypes = Type.defaultVisible() & ~(showing ? 0 : Type.systemBars());
            if (!showing) {
                mInsetsController.hide(Type.systemBars());
            }
            mName = name;
        }

        @Override
        public void notifyInsetsControlChanged(int displayId) {
            mHandler.post(this);
        }

        @Override
        public void run() {
            synchronized (mGlobalLock) {
                mState.set(mStateController.getRawInsetsState(), true /* copySources */);
                mInsetsController.onStateChanged(mState);
                mInsetsController.onControlsChanged(mStateController.getControlsForDispatch(this));
            }
        }

        @Override
        public boolean canShowTransient() {
            return true;
        }

        @Override
        public boolean isRequestedVisible(@InsetsType int types) {
            return (mRequestedVisibleTypes & types) != 0;
        }

        @InsetsType
        @Override
        public int getRequestedVisibleTypes() {
            return mRequestedVisibleTypes;
        }

        @InsetsType
        @Override
        public int getAnimatingTypes() {
            return mAnimatingTypes;
        }

        @Override
        public void setAnimatingTypes(@InsetsType int animatingTypes,
                @Nullable ImeTracker.Token statsToken) {
            mInsetsPolicy.onAnimatingTypesChanged(this, mAnimatingTypes, animatingTypes);
            mAnimatingTypes = animatingTypes;
        }

        @NonNull
        @Override
        public String toString() {
            return mName;
        }
    }

    private static class Host implements InsetsController.Host {

        @NonNull
        private final float[] mTmpFloat9 = new float[9];
        @NonNull
        private final Handler mHandler;
        @NonNull
        private final String mName;
        @NonNull
        private final InsetsControlTarget mControlTarget;

        Host(@NonNull Handler handler, @NonNull String name, @NonNull InsetsControlTarget target) {
            mHandler = handler;
            mName = name;
            mControlTarget = target;
        }

        @NonNull
        @Override
        public Handler getHandler() {
            return mHandler;
        }

        @Override
        public void notifyInsetsChanged() {
        }

        @Override
        public void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation) {
        }

        @NonNull
        @Override
        public Bounds dispatchWindowInsetsAnimationStart(@NonNull WindowInsetsAnimation animation,
                @NonNull Bounds bounds) {
            return bounds;
        }

        @NonNull
        @Override
        public WindowInsets dispatchWindowInsetsAnimationProgress(@NonNull WindowInsets insets,
                @NonNull List<WindowInsetsAnimation> runningAnimations) {
            return insets;
        }

        @Override
        public void dispatchWindowInsetsAnimationEnd(@NonNull WindowInsetsAnimation animation) {
        }

        @Override
        public void applySurfaceParams(
                @NonNull SyncRtSurfaceTransactionApplier.SurfaceParams... p) {
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (int i = p.length - 1; i >= 0; i--) {
                SyncRtSurfaceTransactionApplier.applyParams(t, p[i], mTmpFloat9);
            }
            t.apply();
            t.close();
        }

        @Override
        public void updateAnimatingTypes(@InsetsType int animatingTypes,
                @Nullable ImeTracker.Token statsToken) {
            mControlTarget.setAnimatingTypes(animatingTypes, statsToken);
        }

        @Override
        public void updateRequestedVisibleTypes(@InsetsType int types,
                @Nullable ImeTracker.Token statsToken) {
        }

        @Override
        public boolean hasAnimationCallbacks() {
            return false;
        }

        @Override
        public void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask) {
        }

        @Appearance
        @Override
        public int getSystemBarsAppearance() {
            return 0;
        }

        @Override
        public void setSystemBarsBehavior(@Behavior int behavior) {
        }

        @Behavior
        @Override
        public int getSystemBarsBehavior() {
            return BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        }

        @Override
        public void releaseSurfaceControlFromRt(@NonNull SurfaceControl surfaceControl) {
            surfaceControl.release();
        }

        @Override
        public void addOnPreDrawRunnable(@NonNull Runnable r) {
        }

        @Override
        public void postInsetsAnimationCallback(@NonNull Runnable r) {
        }

        @Nullable
        @Override
        public InputMethodManager getInputMethodManager() {
            return null;
        }

        @NonNull
        @Override
        public String getRootViewTitle() {
            return mName;
        }

        @Override
        public int dipToPx(int dips) {
            return 0;
        }

        @Nullable
        @Override
        public IBinder getWindowToken() {
            return null;
        }
    }

    void updateLockedStatus() {
        mLastSwipeTime = 0L;
        mLastUnlockedTime = 0L;
        mLockedGesture = Settings.System.getIntForUser(mPolicy.getContext().getContentResolver(),
                Settings.System.LOCK_GESTURE_STATUS, 0, UserHandle.USER_CURRENT) == 1;
    }

    void warnGestureLocked() {
        Toast.makeText(mPolicy.getUiContext(), R.string.gesture_locked_warning, Toast.LENGTH_SHORT).show();
    }

    boolean isGestureLocked(boolean updateSwipeTime) {
        if (!mLockedGesture) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - mLastUnlockedTime <= 3500) {
            mLastUnlockedTime = now;
            return false;
        }
        if (now - mLastSwipeTime > 2500) {
            if (updateSwipeTime) {
                mLastSwipeTime = now;
            }
            return true;
        }
        mLastUnlockedTime = now;
        return false;
    }
}
