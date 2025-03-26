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
    public void testVisibleNotPinnedActivity() {
        WindowProcessController wpc = createWindowProcessController();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
        mTracker.onAnyActivityVisible(wpc);
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isTrue();
        mTracker.onAllActivitiesInvisible(wpc);
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
    }

    @Test
    public void testVisiblePinnedActivity() {
        WindowProcessController wpc = createWindowProcessController();
        wpc.getConfiguration().windowConfiguration.setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_PINNED);
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
        mTracker.onAnyActivityVisible(wpc);
        assertThat(mTracker.hasVisibleActivity(wpc.mUid)).isTrue();
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
        mTracker.onAllActivitiesInvisible(wpc);
        assertThat(mTracker.hasVisibleNotPinnedActivity(wpc.mUid)).isFalse();
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
}
