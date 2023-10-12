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

package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static com.android.wm.shell.animation.Interpolators.ALPHA_IN;
import static com.android.wm.shell.animation.Interpolators.ALPHA_OUT;
import static com.android.wm.shell.animation.Interpolators.FAST_OUT_SLOW_IN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SurfaceUtils;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.ResizeVeil;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link Transitions.TransitionHandler} that handles freeform task maximizing and restoring
 * transitions.
 */
public class FreeformTaskTransitionHandler
        implements Transitions.TransitionHandler, FreeformTaskTransitionStarter {

    private static final int FULLSCREEN_ANIMATION_DURATION = 300;
    private static final int FREEFORM_ANIMATION_DURATION = 300;
    private static final int ALPHA_ANIMATION_DURATION = 100;

    private final Transitions mTransitions;
    private final WindowDecorViewModel mWindowDecorViewModel;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;

    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();
    private final ArrayMap<IBinder, ArrayList<Animator>> mAnimations = new ArrayMap<>();
    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    public FreeformTaskTransitionHandler(
            ShellInit shellInit,
            Transitions transitions,
            WindowDecorViewModel windowDecorViewModel,
            Context context,
            ShellExecutor mainExecutor,
            ShellExecutor animExecutor) {
        mTransitions = transitions;
        mWindowDecorViewModel = windowDecorViewModel;
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            shellInit.addInitCallback(this::onInit, this);
        }
        mContext = context;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
    }

    private void onInit() {
        mWindowDecorViewModel.setFreeformTaskTransitionStarter(this);
    }

    @Override
    public void startWindowingModeTransition(
            int targetWindowingMode, WindowContainerTransaction wct) {
        final int type;
        switch (targetWindowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                type = Transitions.TRANSIT_MAXIMIZE;
                break;
            case WINDOWING_MODE_FREEFORM:
                type = Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE;
                break;
            default:
                throw new IllegalArgumentException("Unexpected target windowing mode "
                        + WindowConfiguration.windowingModeToString(targetWindowingMode));
        }
        final IBinder token = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(token);
    }

    @Override
    public void startMinimizedModeTransition(WindowContainerTransaction wct) {
        final int type = WindowManager.TRANSIT_TO_BACK;
        mPendingTransitionTokens.add(mTransitions.startTransition(type, wct, this));
    }


    @Override
    public void startRemoveTransition(WindowContainerTransaction wct) {
        final int type = WindowManager.TRANSIT_CLOSE;
        mPendingTransitionTokens.add(mTransitions.startTransition(type, wct, this));
    }

    @Override
    public void startOpenTransition(WindowContainerTransaction wct) {
        final int type = WindowManager.TRANSIT_OPEN;
        mPendingTransitionTokens.add(mTransitions.startTransition(type, wct, this));
    }

    @Override
    public void startExitTransition(WindowContainerTransaction wct) {
        final int type = Transitions.TRANSIT_EXIT_FREEFORM;
        mPendingTransitionTokens.add(mTransitions.startTransition(type, wct, this));
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        boolean transitionHandled = false;
        final ArrayList<Animator> animations = new ArrayList<>();
        final Runnable onAnimFinish = () -> {
            if (!animations.isEmpty()) return;
            mMainExecutor.execute(() -> {
                mAnimations.remove(transition);
                finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
            });
        };

        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0) {
                continue;
            }

            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue;
            }

            switch (change.getMode()) {
                case WindowManager.TRANSIT_CHANGE:
                    transitionHandled |= startChangeTransition(transition, info.getType(), change,
                            startT, animations, onAnimFinish);
                    break;
                case WindowManager.TRANSIT_TO_BACK:
                    transitionHandled |= startToBackTransition(transition, info.getType(), change,
                            animations, onAnimFinish);
                    break;
                case WindowManager.TRANSIT_OPEN:
                case WindowManager.TRANSIT_TO_FRONT:
                    if (change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                        transitionHandled |= startOpenTransition(transition, change, animations,
                                onAnimFinish);
                    }
                    break;
            }
        }
        if (!transitionHandled) {
            return false;
        }
        mAnimations.put(transition, animations);
        // startT must be applied before animations start.
        startT.apply();
        mAnimExecutor.execute(() -> {
            for (Animator anim : animations) {
                anim.start();
            }
        });
        // Run this here in case no animators are created.
        onAnimFinish.run();
        mPendingTransitionTokens.remove(transition);
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        ArrayList<Animator> animations = mAnimations.get(mergeTarget);
        if (animations == null) return;
        mAnimExecutor.execute(() -> {
            for (Animator anim : animations) {
                anim.end();
            }
        });
    }

    private boolean startChangeTransition(
            IBinder transition,
            int type,
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            ArrayList<Animator> animations,
            Runnable onAnimFinish) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }

        boolean handled = false;
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type == Transitions.TRANSIT_MAXIMIZE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            handled = handleToFullscreenTransition(change, startT, animations, onAnimFinish);
        }

        if (type == Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            handled = handleToFreeformTransition(change, startT, animations, onAnimFinish);
        }

        return handled;
    }

    private boolean handleToFreeformTransition(
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            ArrayList<Animator> animations,
            Runnable onAnimFinish) {
        final SurfaceControl leash = change.getLeash();
        final Rect startBounds = change.getStartAbsBounds();
        final Rect endBounds = change.getEndAbsBounds();
        // Hide the first (freeform) frame because the animation will start from the fullscreen
        // size.
        startT.hide(leash)
                .setWindowCrop(leash, endBounds.width(), endBounds.height());
        // Set up veil layer.
        final int veilColorId = ResizeVeil.getBackgroundColorId(mContext);
        final int veilColor = mContext.getColor(veilColorId);
        final SurfaceControl veilLayer = SurfaceUtils.makeColorLayer(
                leash,
                leash + "_veil_layer",
                mSurfaceSession);
        startT.setLayer(veilLayer, Integer.MAX_VALUE)
                .setColor(veilLayer, new float[]{
                        Color.red(veilColor) / 255f,
                        Color.green(veilColor) / 255f,
                        Color.blue(veilColor) / 255f
                });
        final ValueAnimator animator =
                ValueAnimator.ofObject(new RectEvaluator(), startBounds, endBounds)
                        .setDuration(FREEFORM_ANIMATION_DURATION);
        animator.setInterpolator(FAST_OUT_SLOW_IN);
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        animator.addUpdateListener(animation -> {
            // Show veil while resizing.
            t.show(veilLayer);
            final Rect animatedValue = (Rect) animator.getAnimatedValue();
            t.setPosition(leash, animatedValue.left, animatedValue.top)
                    .setWindowCrop(leash, animatedValue.width(), animatedValue.height())
                    .show(leash)
                    .apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                t.setPosition(leash, endBounds.left, endBounds.top)
                        .setWindowCrop(leash, endBounds.width(), endBounds.height())
                        .hide(veilLayer)
                        .remove(veilLayer)
                        .apply();
                t.close();
                animations.remove(animator);
                onAnimFinish.run();
            }
        });
        animations.add(animator);
        return true;
    }

    private boolean handleToFullscreenTransition(
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            ArrayList<Animator> animations,
            Runnable onAnimFinish) {
        final SurfaceControl leash = change.getLeash();
        final Rect startBounds = change.getStartAbsBounds();
        final Rect endBounds = change.getEndAbsBounds();
        // Hide the first (fullscreen) frame because the animation will start from the freeform
        // size.
        startT.hide(leash)
                .setWindowCrop(leash, endBounds.width(), endBounds.height());
        final ValueAnimator animator =
                ValueAnimator.ofObject(new RectEvaluator(), startBounds, endBounds)
                        .setDuration(FULLSCREEN_ANIMATION_DURATION);
        animator.setInterpolator(FAST_OUT_SLOW_IN);
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        animator.addUpdateListener(animation -> {
            final Rect animationValue = (Rect) animator.getAnimatedValue();
            t.setPosition(leash, animationValue.left, animationValue.top)
                    .setWindowCrop(leash, animationValue.width(), animationValue.height())
                    .show(leash)
                    .apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                t.setPosition(leash, endBounds.left, endBounds.top)
                        .setWindowCrop(leash, endBounds.width(), endBounds.height())
                        .apply();
                t.close();
                animations.remove(animator);
                onAnimFinish.run();
            }
        });
        animations.add(animator);
        return true;
    }

    private boolean startToBackTransition(
            IBinder transition,
            int type,
            TransitionInfo.Change change,
            List<Animator> animations,
            Runnable onAnimFinish) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }
        boolean handled = false;
        if (type == Transitions.TRANSIT_EXIT_FREEFORM) {
            handled = handleExitTransition(change, animations, onAnimFinish);
        } else if (change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            handled = handleMinimizeTransition();
        }
        return handled;
    }

    private boolean handleExitTransition(
            TransitionInfo.Change change,
            List<Animator> animations,
            Runnable onAnimFinish) {
        startFadeAnimation(change.getLeash(), animations, onAnimFinish, false /* show */);
        return true;
    }

    private boolean handleMinimizeTransition() {
        // TODO animation
        return true;
    }

    private boolean startOpenTransition(
            IBinder transition,
            TransitionInfo.Change change,
            List<Animator> animations,
            Runnable onAnimFinish) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }
        startFadeAnimation(change.getLeash(), animations, onAnimFinish, true /* show */);
        return true;
    }

    private void startFadeAnimation(
            SurfaceControl leash,
            List<Animator> animations,
            Runnable onAnimFinish,
            boolean show) {
        final float end = show ? 1.f : 0.f;
        final float start = 1.f - end;
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        final ValueAnimator animator = ValueAnimator.ofFloat(start, end)
                .setDuration(ALPHA_ANIMATION_DURATION);
        animator.setInterpolator(show ? ALPHA_IN : ALPHA_OUT);
        animator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            t.setAlpha(leash, start * (1.f - fraction) + end * fraction).apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                t.setAlpha(leash, end).apply();
                t.close();
                animations.remove(animator);
                onAnimFinish.run();
            }
        });
        animations.add(animator);
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }
}
