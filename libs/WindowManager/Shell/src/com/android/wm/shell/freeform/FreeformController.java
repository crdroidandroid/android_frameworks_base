/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_FREEFORM;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Rect;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ExecutorUtils;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

/**
 * A controller for freeform tasks.
 */
public final class FreeformController implements RemoteCallable<FreeformController>,
        FreeformListener {

    private final Context mContext;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellExecutor mMainExecutor;
    private final ShellController mShellController;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final FreeformTaskTransitionStarter mFreeformTaskTransitionStarter;
    private final FreeformTaskListener mFreeformTaskListener;
    private final FreeformShellCommandHandler mFreeformShellCommandHandler;
    private final boolean mAlwaysOnTop;

    public FreeformController(
            Context context,
            ShellCommandHandler shellCommandHandler,
            ShellInit shellInit,
            @ShellMainThread ShellExecutor mainExecutor,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            FreeformTaskTransitionStarter freeformTaskTransitionStarter,
            FreeformTaskListener freeformTaskListener) {
        mContext = context;
        mShellCommandHandler = shellCommandHandler;
        shellInit.addInitCallback(this::onInit, this);
        mMainExecutor = mainExecutor;
        mShellController = shellController;
        mShellTaskOrganizer = shellTaskOrganizer;
        mFreeformTaskTransitionStarter = freeformTaskTransitionStarter;
        mFreeformTaskListener = freeformTaskListener;
        mFreeformShellCommandHandler = new FreeformShellCommandHandler(this, context);
        mAlwaysOnTop = context.getResources().getBoolean(R.bool.config_freeformAlwaysOnTop)
                && !DesktopModeStatus.isAnyEnabled();
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    @Override
    public void onTaskEnteredFreeform(RunningTaskInfo taskInfo) {
        if (mAlwaysOnTop && !taskInfo.configuration.windowConfiguration.isAlwaysOnTop()) {
            // A freeform task appeared that was not started by the Shell, make it always-on-top.
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setAlwaysOnTop(taskInfo.token, true);
            mShellTaskOrganizer.applyTransaction(wct);
        }
    }

    private void onInit() {
        mShellCommandHandler.addCommandCallback("freeform", mFreeformShellCommandHandler, this);
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_FREEFORM,
                this::createExternalInterface, this);
        mFreeformTaskListener.registerFreeformListener(this);
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new IFreeformImpl(this);
    }

    void startTask(int taskId, Rect bounds) {
        final RunningTaskInfo taskInfo =
                mShellTaskOrganizer.getRunningTaskInfo(taskId);
        if (taskInfo == null || taskInfo.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(taskInfo.token, WINDOWING_MODE_FREEFORM);
        if (bounds != null) {
            wct.setBounds(taskInfo.token, bounds);
        }
        if (mAlwaysOnTop) {
            wct.setAlwaysOnTop(taskInfo.token, true);
        }
        wct.startTask(taskInfo.taskId, null /* options */);
        if (ENABLE_SHELL_TRANSITIONS) {
            mFreeformTaskTransitionStarter.startWindowingModeTransition(WINDOWING_MODE_FREEFORM,
                    wct);
        } else {
            mShellTaskOrganizer.applyTransaction(wct);
        }
    }

    void startIntent(PendingIntent intent, Rect bounds) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setPendingIntentBackgroundActivityLaunchAllowedByPermission(true);
        options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        if (bounds != null) {
            options.setLaunchBounds(bounds);
        }
        if (mAlwaysOnTop) {
            options.setTaskAlwaysOnTop(true);
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.sendPendingIntent(intent, null /* intent */, options.toBundle());
        if (ENABLE_SHELL_TRANSITIONS) {
            mFreeformTaskTransitionStarter.startOpenTransition(wct);
        } else {
            mShellTaskOrganizer.applyTransaction(wct);
        }
    }

    void exitFreeform(int taskId) {
        final RunningTaskInfo taskInfo =
                mShellTaskOrganizer.getRunningTaskInfo(taskId);
        if (taskInfo == null || taskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED);
        wct.setBounds(taskInfo.token, new Rect());
        wct.reorder(taskInfo.token, false /* onTop */);
        if (ENABLE_SHELL_TRANSITIONS) {
            mFreeformTaskTransitionStarter.startExitTransition(wct);
        } else {
            mShellTaskOrganizer.applyTransaction(wct);
        }
    }

    private void registerFreeformListener(FreeformListener listener) {
        mFreeformTaskListener.registerFreeformListener(listener);
    }

    private void unregisterFreeformListener(FreeformListener listener) {
        mFreeformTaskListener.unregisterFreeformListener(listener);
    }

    @BinderThread
    private static final class IFreeformImpl extends IFreeform.Stub implements
            ExternalInterfaceBinder {

        private FreeformController mController;

        private final SingleInstanceRemoteListener<FreeformController, IFreeformListener>
                mSingleInstanceRemoteListener;

        private final FreeformListener mFreeformListener = new FreeformListener() {
            @Override
            public void onTaskEnteredFreeform(RunningTaskInfo taskInfo) {
                mSingleInstanceRemoteListener.call(l -> l.onTaskEnteredFreeform(taskInfo));
            }

            @Override
            public void onTaskExitedFreeform(RunningTaskInfo taskInfo) {
                mSingleInstanceRemoteListener.call(l -> l.onTaskExitedFreeform(taskInfo));
            }
        };

        private IFreeformImpl(FreeformController controller) {
            mController = controller;
            mSingleInstanceRemoteListener = new SingleInstanceRemoteListener<>(mController,
                    c -> c.registerFreeformListener(mFreeformListener),
                    c -> c.unregisterFreeformListener(mFreeformListener));
        }

        @Override
        public void startTask(int taskId, Rect bounds) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(mController, "startTask",
                    (controller) -> controller.startTask(taskId, bounds));
        }

        @Override
        public void startIntent(PendingIntent intent, Rect bounds) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(mController, "startIntent",
                    (controller) -> controller.startIntent(intent, bounds));
        }

        @Override
        public void exitFreeform(int taskId) {
            ExecutorUtils.executeRemoteCallWithTaskPermission(mController, "exitFreeform",
                    (controller) -> controller.exitFreeform(taskId));
        }

        @Override
        public void setFreeformListener(IFreeformListener listener) {
            executeRemoteCallWithTaskPermission(mController, "setFreeformListener",
                    (controller) -> mSingleInstanceRemoteListener.register(listener));
        }

        @Override
        public void removeFreeformListener() {
            executeRemoteCallWithTaskPermission(mController, "removeFreeformListener",
                    (controller) -> mSingleInstanceRemoteListener.unregister());
        }

        @Override
        public void invalidate() {
            mSingleInstanceRemoteListener.unregister();
            mController = null;
        }
    }
}
