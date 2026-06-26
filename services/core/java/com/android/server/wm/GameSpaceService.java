/*
 * Copyright (C) 2025-2026 AxionOS
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Process;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

public final class GameSpaceService extends SystemService {
    private static final String TAG = "GameSpaceService";
    private static final long BOOST_DELAY_MS = 500L;

    private final GameListManager mGameListManager;
    private final GameStateDispatcher mGameStateDispatcher;
    private final GamePackageHandler mGamePackageHandler;
    private final ServiceThread mThread;
    private final Handler mHandler;

    private boolean mStarted;
    private String mCurrentGame;
    private Runnable mPendingBoost;

    public GameSpaceService(Context context) {
        super(context);
        mThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND, true);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mGameListManager = new GameListManager(context);
        mGameStateDispatcher = new GameStateDispatcher(context);
        mGamePackageHandler = new GamePackageHandler(context, mGameListManager, mHandler);
    }

    @Override
    public void onStart() {
        publishLocalService(GameSpaceService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            startGameSpace();
        }
    }

    public void onAppFocusChanged(ActivityRecord record, Task task) {
        if (record == null || record.packageName == null) {
            return;
        }
        final String packageName = record.packageName;
        final boolean freeformTask = task != null
                && task.getWindowingMode() == WINDOWING_MODE_FREEFORM;
        mHandler.post(() -> updateFocusedPackage(packageName, freeformTask));
    }

    public void removeTask(Task task) {
        if (task == null) {
            return;
        }
        final String packageName = getTaskPackageName(task);
        if (packageName == null) {
            return;
        }
        mHandler.post(() -> {
            if (packageName.equals(mCurrentGame)) {
                mCurrentGame = null;
                stopOverlay();
            }
        });
    }

    private String getTaskPackageName(Task task) {
        final ActivityRecord top = task.getTopMostActivity();
        if (top != null && top.packageName != null) {
            return top.packageName;
        }
        final Intent baseIntent = task.getBaseIntent();
        final ComponentName component = baseIntent != null ? baseIntent.getComponent() : null;
        return component != null ? component.getPackageName() : null;
    }

    public void onKeyguardChanged(boolean showing) {
        mHandler.post(() -> {
            if (mCurrentGame == null) {
                return;
            }
            if (showing) {
                stopOverlay();
            } else {
                startOverlay();
            }
        });
    }

    private void startGameSpace() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mGamePackageHandler.registerPackageReceiver();
        mGameListManager.registerGameListObserver(mHandler);
        mGameListManager.addListener(() -> mHandler.post(() -> {
            if (mCurrentGame != null && mGameListManager.isGame(mCurrentGame)) {
                mGameStateDispatcher.boostGame(mGameListManager.isGameInPerfMode(mCurrentGame));
            }
        }));
        Slog.i(TAG, "GameSpaceService initialized");
    }

    private void updateFocusedPackage(String packageName, boolean freeformTask) {
        final boolean gameActive = mCurrentGame != null && isCurrentGameTopApp();
        if (freeformTask && gameActive) {
            return;
        }

        final boolean isGame = mGameListManager.isGame(packageName);
        boolean shouldStartOverlay = false;
        boolean shouldStopOverlay = false;

        if (isGame) {
            if (!packageName.equals(mCurrentGame)) {
                if (mCurrentGame != null) {
                    shouldStopOverlay = true;
                }
                mCurrentGame = packageName;
                shouldStartOverlay = true;
            }
        } else if (mCurrentGame != null) {
            mCurrentGame = null;
            shouldStopOverlay = true;
        }

        if (shouldStopOverlay) {
            stopOverlay();
        }
        if (shouldStartOverlay) {
            startOverlay();
        }
    }

    private boolean isCurrentGameTopApp() {
        final ActivityTaskManagerInternal activityTaskManager =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        final WindowProcessController topApp = activityTaskManager != null
                ? activityTaskManager.getTopApp() : null;
        return topApp != null && topApp.containsPackage(mCurrentGame);
    }

    private void startOverlay() {
        final String currentGame = mCurrentGame;
        if (currentGame == null) {
            return;
        }
        cancelPendingBoost();
        if (mGameListManager.isGameInPerfMode(currentGame)) {
            mPendingBoost = () -> mGameStateDispatcher.boostGame(true);
            mHandler.postDelayed(mPendingBoost, BOOST_DELAY_MS);
        }
        mGameStateDispatcher.dispatchGameState(true, currentGame);
    }

    private void stopOverlay() {
        cancelPendingBoost();
        mGameStateDispatcher.dispatchGameState(false, null);
        mGameStateDispatcher.boostGame(false);
    }

    private void cancelPendingBoost() {
        if (mPendingBoost == null) {
            return;
        }
        mHandler.removeCallbacks(mPendingBoost);
        mPendingBoost = null;
    }
}
