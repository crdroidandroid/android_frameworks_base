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

package com.android.systemui.screenshot;

import static android.content.Context.NOTIFICATION_SERVICE;
import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT;
import static com.android.systemui.screenshot.ScreenshotController.SCREENSHOT_URI_ID;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.util.NotificationChannels;

import javax.inject.Inject;

/**
 * Convenience class to handle showing and hiding notifications while taking a screenshot.
 */
public class ScreenshotNotificationsController {
    private static final String TAG = "ScreenshotNotificationManager";
    private static final String MIME = "image/*";
    private static final String GROUP_KEY = "screenshot_post_action_group";
    private static final int GROUP_ID = GROUP_KEY.hashCode();

    private final Context mContext;
    private final Resources mResources;
    private final NotificationManager mNotificationManager;

    @Inject
    ScreenshotNotificationsController(Context context, WindowManager windowManager) {
        mContext = context;
        mResources = context.getResources();
        mNotificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(displayMetrics);
    }

    /**
     * Sends a notification that the screenshot capture has failed.
     */
    public void notifyScreenshotError(int msgResId) {
        Resources res = mContext.getResources();
        String errorMsg = res.getString(msgResId);

        // Repurpose the existing notification to notify the user of the error
        Notification.Builder b = new Notification.Builder(mContext, NotificationChannels.ALERTS)
                .setTicker(res.getString(R.string.screenshot_failed_title))
                .setContentTitle(res.getString(R.string.screenshot_failed_title))
                .setContentText(errorMsg)
                .setSmallIcon(R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        final Intent intent =
                dpm.createAdminSupportIntent(DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        if (intent != null) {
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
            b.setContentIntent(pendingIntent);
        }

        SystemUIApplication.overrideNotificationAppName(mContext, b, true);

        Notification n = new Notification.BigTextStyle(b)
                .bigText(errorMsg)
                .build();
        mNotificationManager.notify(NOTE_GLOBAL_SCREENSHOT, n);
    }

    /**
     * Shows a notification containing the screenshot and the chip actions
     * @param imageData for actions, uri. cannot be null
     * @param bitmap for image preview. can be null
     */
    public void showPostActionNotification(
            ScreenshotController.SavedImageData imageData, Bitmap bitmap) {
        Uri uri = imageData.uri;
        // notification channel ID is the URI hash as string - to allow notifications to pile up
        // and still be able to get the same ID someplace else for dismiss
        int requestCode = uri.toString().hashCode();
        Resources res = mContext.getResources();
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(uri, MIME);
        PendingIntent pi = PendingIntent.getActivity(
                mContext, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent shareIntent = PendingIntent.getActivity(mContext, requestCode,
                new Intent(Intent.ACTION_SEND)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .setType(MIME)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder actionShare = new Notification.Action.Builder(0 /* no icon */,
                res.getText(R.string.screenrecord_share_label), shareIntent);

        PendingIntent deleteIntent = PendingIntent.getBroadcast(mContext, requestCode,
                new Intent(mContext, DeleteScreenshotReceiver.class)
                        .putExtra(SCREENSHOT_URI_ID, uri.toString())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder actionDelete = new Notification.Action.Builder(0 /* no icon */,
                res.getText(R.string.screenshot_delete_label), deleteIntent);

        Notification.Builder b = new Notification.Builder(mContext, NotificationChannels.SCREENSHOTS_HEADSUP)
                .setTicker(res.getString(R.string.screenshot_saved_title))
                .setContentTitle(res.getString(R.string.screenshot_saved_title))
                .setContentText(res.getString(R.string.screenrecord_save_text))
                .setSmallIcon(R.drawable.screenshot_image)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .setStyle(new Notification.BigPictureStyle()
                        .bigPicture(bitmap).bigLargeIcon(bitmap))
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .addAction(actionShare.build())
                .addAction(actionDelete.build())
                .setContentIntent(pi);

        mNotificationManager.notify(requestCode, b.build());
        maybePostGroup();
    }

    public void dismissPostActionNotification(int id) {
        mNotificationManager.cancel(id);
        maybeDismissGroup();
        maybeCloseSystemDialogs();
    }

    private void maybePostGroup() {
        if (countGroupedNotifications() < 2)
            return; // only post after we show the 2nd notification
        Notification.Builder b = new Notification.Builder(mContext, NotificationChannels.SCREENSHOTS_HEADSUP)
                .setSmallIcon(R.drawable.screenshot_image)
                .setContentTitle(mContext.getResources().getString(R.string.screenshot_saved_title))
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true);
        mNotificationManager.notify(GROUP_ID, b.build());
    }

    private void maybeDismissGroup() {
        if (countGroupedNotifications() >= 1)
            return; // dismiss only when we have one notification left
        mNotificationManager.cancel(GROUP_ID);
    }

    private void maybeCloseSystemDialogs() {
        if (countGroupedNotifications() > 0)
            return; // only dismiss when we cancel the last group notification
        mContext.closeSystemDialogs();
    }

    private int countGroupedNotifications() {
        StatusBarNotification[] notifications = mNotificationManager.getActiveNotifications();
        int count = 0;
        for (StatusBarNotification notification : notifications) {
            final int id = notification.getId();
            if (id != GROUP_ID && id != NOTE_GLOBAL_SCREENSHOT)
                count++;
        }
        return count;
    }
}
