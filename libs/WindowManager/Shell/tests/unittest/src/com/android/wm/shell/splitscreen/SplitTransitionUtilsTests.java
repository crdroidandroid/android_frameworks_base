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

package com.android.wm.shell.splitscreen;

import static android.view.WindowManager.TRANSIT_OPEN;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.split.SplitTransitionUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Function;

/** Tests for {@link SplitTransitionUtils} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitTransitionUtilsTests extends ShellTestCase {

    @Mock
    private StageTaskListener mStage1;
    @Mock
    private StageTaskListener mStage2;
    @Mock
    private WindowContainerToken mToken;

    private ActivityManager.RunningTaskInfo mTaskInfo1;
    private ActivityManager.RunningTaskInfo mTaskInfo2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTaskInfo1 = new ActivityManager.RunningTaskInfo();
        mTaskInfo1.token = mToken;
        mTaskInfo2 = new ActivityManager.RunningTaskInfo();
        mTaskInfo2.token = mToken;
    }

    @Test
    public void testHandleMalformedEnterTransition_notMalformed() {
        TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        info.addChange(new TransitionInfo.Change(mToken, mock(SurfaceControl.class)));
        info.getChange(mToken).setTaskInfo(mTaskInfo1);
        info.addChange(new TransitionInfo.Change(mToken, mock(SurfaceControl.class)));
        info.getChange(mToken).setTaskInfo(mTaskInfo2);

        Function<ActivityManager.RunningTaskInfo, StageTaskListener> stageResolver = (taskInfo) -> {
            if (taskInfo == mTaskInfo1) {
                return mStage1;
            } else {
                return mStage2;
            }
        };

        WindowContainerTransaction wct = SplitTransitionUtils.handleMalformedEnterTransition(info,
                stageResolver);
        assertNull(wct);
    }

    @Test
    public void testHandleMalformedEnterTransition_oneStage() {
        TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        TransitionInfo.Change chg = new TransitionInfo.Change(mToken, mock(SurfaceControl.class));
        chg.setMode(TRANSIT_OPEN);
        chg.setTaskInfo(mTaskInfo1);
        info.addChange(chg);

        Function<ActivityManager.RunningTaskInfo, StageTaskListener> stageResolver =
                (taskInfo) -> mStage1;

        WindowContainerTransaction wct = SplitTransitionUtils.handleMalformedEnterTransition(info,
                stageResolver);
        assertNotNull(wct);
    }

    @Test
    public void testHandleMalformedEnterTransition_noStages() {
        TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        info.addChange(new TransitionInfo.Change(mToken, mock(SurfaceControl.class)));
        info.getChange(mToken).setTaskInfo(mTaskInfo1);

        Function<ActivityManager.RunningTaskInfo, StageTaskListener> stageResolver =
                (taskInfo) -> null;

        WindowContainerTransaction wct = SplitTransitionUtils.handleMalformedEnterTransition(info,
                stageResolver);
        assertNull(wct);
    }

    @Test
    public void testHandleMalformedEnterTransition_nonOpeningTask() {
        TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        info.addChange(new TransitionInfo.Change(mToken, mock(SurfaceControl.class)));
        info.getChange(mToken).setTaskInfo(mTaskInfo1);
        info.getChange(mToken).setMode(TRANSIT_OPEN + 1);

        Function<ActivityManager.RunningTaskInfo, StageTaskListener> stageResolver =
                (taskInfo) -> mStage1;

        WindowContainerTransaction wct = SplitTransitionUtils.handleMalformedEnterTransition(info,
                stageResolver);
        assertNull(wct);
    }

    @Test
    public void testHandleMalformedEnterTransition_nullTaskInfo() {
        TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        info.addChange(new TransitionInfo.Change(mToken, mock(SurfaceControl.class)));

        Function<ActivityManager.RunningTaskInfo, StageTaskListener> stageResolver =
                (taskInfo) -> mStage1;

        WindowContainerTransaction wct = SplitTransitionUtils.handleMalformedEnterTransition(info,
                stageResolver);
        assertNull(wct);
    }
}
