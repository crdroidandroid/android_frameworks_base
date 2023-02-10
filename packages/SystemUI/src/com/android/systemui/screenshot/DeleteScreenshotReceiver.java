/*
 * Copyright (C) 2024 Yet Another AOSP Project
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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Display;

import com.android.systemui.dagger.qualifiers.Background;

import java.util.concurrent.Executor;

import javax.inject.Inject;

public class DeleteScreenshotReceiver extends BroadcastReceiver {
    private final Executor mBackgroundExecutor;
    private final ScreenshotNotificationsController mNotificationsController;

    @Inject
    public DeleteScreenshotReceiver(@Background Executor backgroundExecutor,
            ScreenshotNotificationsController.Factory notificationsControllerFactory) {
        mBackgroundExecutor = backgroundExecutor;
        mNotificationsController = notificationsControllerFactory.create(Display.DEFAULT_DISPLAY);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Uri data = intent.getData();
        if (data == null) {
            return;
        }

        mBackgroundExecutor.execute(() -> {
            ContentResolver resolver = context.getContentResolver();
            resolver.delete(data, null, null);
        });

        // dismiss the notification if any
        mNotificationsController.dismissPostActionNotification(data.toString().hashCode());
    }
}
