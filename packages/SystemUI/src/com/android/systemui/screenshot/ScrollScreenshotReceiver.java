/*
 * Copyright (C) 2020 The Android Open Source Project
 * Copyright (C) 2020-2021 ProjectFluid
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.GlobalScreenshot.ACTION_TYPE_SCROLL;
import static com.android.systemui.screenshot.GlobalScreenshot.EXTRA_ID;
import static com.android.systemui.screenshot.GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED;
import static com.android.systemui.screenshot.GlobalScreenshot.SCREENSHOT_URI_ID;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.net.Uri;

import com.android.systemui.dagger.qualifiers.Background;

import java.util.concurrent.Executor;

import javax.inject.Inject;

public class ScrollScreenshotReceiver extends BroadcastReceiver {

    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final Executor mBackgroundExecutor;

    @Inject
    public ScrollScreenshotReceiver(ScreenshotSmartActions screenshotSmartActions,
            @Background Executor backgroundExecutor) {
        mScreenshotSmartActions = screenshotSmartActions;
        mBackgroundExecutor = backgroundExecutor;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.hasExtra(SCREENSHOT_URI_ID)) {
            return;
        }

        final Uri uri = Uri.parse(intent.getStringExtra(SCREENSHOT_URI_ID));
        mBackgroundExecutor.execute(() -> {
             Intent intentt = new Intent();
             intentt.setComponent(new ComponentName("com.asus.stitchimage", "com.asus.stitchimage.OverlayService"));
             intentt.putExtra("callfrom", "AsusSettings");
             context.startService(intentt);
        });
        if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
            mScreenshotSmartActions.notifyScreenshotAction(
                    context, intent.getStringExtra(EXTRA_ID), ACTION_TYPE_SCROLL, false);
        }
    }
}
