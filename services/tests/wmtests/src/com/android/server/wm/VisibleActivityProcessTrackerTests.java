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

import static android.content.pm.ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.pm.ApplicationInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;


import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Tests for the {@link com.android.server.wm.VisibleActivityProcessTracker} class.
 *
 * Build/Install/Run:
 * atest WmTests:VisibleActivityProcessTrackerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class VisibleActivityProcessTrackerTests extends WindowTestsBase {

    enum Visibility { VISIBLE, INVISIBLE, REQUESTED }
    private static final boolean PINNED = true;
    private static final boolean UNPINNED = false;

    private VisibleActivityProcessTracker mTracker;

    @Before
    public void setup() {
        mTracker = mAtm.mVisibleActivityProcessTracker;
    }

    @Test
    public void testVisibleActivity() {
        WindowProcessController wpc = createWindowProcessController();
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isFalse();
        mTracker.onAnyActivityVisible(wpc);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isTrue();
        mTracker.onAllActivitiesInvisible(wpc);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isFalse();
    }

    @Test
    public void hasVisibleNotPinnedActivity_whenProcessHasNoActivities_returnsFalse() {
        WindowProcessController wpc = createWindowProcessController();
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isFalse();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
    }

    @Test
    public void hasVisibleNotPinnedActivity_whenProcessHasOnlyInvisibleActivities_returnsFalse() {
        WindowProcessController wpc = createWindowProcessController();
        addActivity(wpc, Visibility.INVISIBLE, UNPINNED);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isFalse();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
    }

    @Test
    public void hasVisibleNotPinnedActivity_whenProcessHasOnlyPinnedActivities_returnsFalse() {
        WindowProcessController wpc = createWindowProcessController();
        addActivity(wpc, Visibility.VISIBLE, PINNED);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isTrue();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
    }

    @Test
    public void hasVisibleNotPinnedActivity_whenProcessHasUnpinnedVisibleActivity_returnsTrue() {
        WindowProcessController wpc = createWindowProcessController();
        addActivity(wpc, Visibility.VISIBLE, UNPINNED);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isTrue();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isTrue();
    }

    @Test
    public void hasVisibleNotPinnedActivity_whenProcessHasUnpinnedVisReqActivity_returnsTrue() {
        WindowProcessController wpc = createWindowProcessController();
        addActivity(wpc, Visibility.REQUESTED, UNPINNED);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isTrue();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isTrue();
    }

    WindowProcessController createWindowProcessController() {
        WindowProcessListener mMockListener = mock(WindowProcessListener.class);
        ApplicationInfo info = mock(ApplicationInfo.class);
        info.packageName = "test.package.name";
        doReturn(true).when(info).isChangeEnabled(INSETS_DECOUPLED_CONFIGURATION_ENFORCED);
        WindowProcessController mWpc = new WindowProcessController(
                mAtm, info, null, 0, -1, null, mMockListener);
        mWpc.setThread(mock(IApplicationThread.class));
        return mWpc;
    }

    private static ActivityRecord addActivity(WindowProcessController wpc, Visibility visible,
            boolean pinned) {
        ActivityRecord ar = mock(ActivityRecord.class);
        Task task = mock(Task.class);
        doReturn(task).when(ar).getTask();
        switch (visible) {
            case VISIBLE:
                doReturn(true).when(ar).isVisible();
                doReturn(true).when(ar).isVisibleRequested();
                break;
            case INVISIBLE:
                doReturn(false).when(ar).isVisible();
                doReturn(false).when(ar).isVisibleRequested();
                break;
            case REQUESTED:
                doReturn(false).when(ar).isVisible();
                doReturn(true).when(ar).isVisibleRequested();
                break;
        }
        doReturn(pinned).when(ar).inPinnedWindowingMode();
        when(ar.toString()).thenReturn("ar visible=" + visible + ", pinned=" + pinned);
        wpc.addActivityIfNeeded(ar);
        wpc.computeProcessActivityState();
        return ar;
    }
}
