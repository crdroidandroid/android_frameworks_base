/*
 * Copyright (C) 2022 ShapeShiftOS
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

import static com.android.systemui.screenshot.ScreenshotController.ACTION_TYPE_LENS;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_ID;
import static com.android.systemui.screenshot.ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED;
import static com.android.systemui.screenshot.ScreenshotController.SCREENSHOT_URI_ID;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.internal.util.crdroid.Utils;

import java.util.concurrent.Executor;

import javax.inject.Inject;

public class LensScreenshotReceiver extends BroadcastReceiver {

    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final Executor mBackgroundExecutor;
    private final String ARPackageName = "com.google.ar.lens";

    @Inject
    public LensScreenshotReceiver(ScreenshotSmartActions screenshotSmartActions,
            @Background Executor backgroundExecutor) {
        mScreenshotSmartActions = screenshotSmartActions;
        mBackgroundExecutor = backgroundExecutor;
    }

    private boolean doesGoogleLensExist(Context context) {
        return Utils.isPackageInstalled(context, ARPackageName);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.hasExtra(SCREENSHOT_URI_ID)) {
            return;
        }

        final Uri uri = Uri.parse(intent.getStringExtra(SCREENSHOT_URI_ID));
        mBackgroundExecutor.execute(() -> {
            // action to execute goes here
            ContentResolver resolver = context.getContentResolver();
            if (!doesGoogleLensExist(context)) {
                Toast.makeText(context, "Google Lens is not installed", Toast.LENGTH_SHORT).show();
                // Launch an intent to the Play Store to install Google Lens
                String playURL = "https://play.google.com/store/apps/details?id=" + ARPackageName;
                Intent playAR = new Intent(Intent.ACTION_VIEW);
                playAR.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                playAR.setData(Uri.parse(playURL));
                context.startActivity(playAR);
                return;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/*");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.setPackage(ARPackageName);
            share.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(share);
        });

        if (intent.getBooleanExtra(EXTRA_SMART_ACTIONS_ENABLED, false)) {
            mScreenshotSmartActions.notifyScreenshotAction(
                    intent.getStringExtra(EXTRA_ID), ACTION_TYPE_LENS, false, null);
        }
    }
}
