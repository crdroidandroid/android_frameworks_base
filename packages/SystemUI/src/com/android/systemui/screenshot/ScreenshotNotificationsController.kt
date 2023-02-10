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
package com.android.systemui.screenshot

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.view.Display
import com.android.internal.R
import com.android.internal.messages.nano.SystemMessageProto
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT_EXTERNAL_DISPLAY
import com.android.systemui.screenshot.ScreenshotController.SCREENSHOT_URI_ID
import com.android.systemui.SystemUIApplication
import com.android.systemui.util.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Convenience class to handle showing and hiding notifications while taking a screenshot. */
class ScreenshotNotificationsController
@AssistedInject
internal constructor(
    @Assisted private val displayId: Int,
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val devicePolicyManager: DevicePolicyManager,
) {
    private val res = context.resources

    /**
     * Sends a notification that the screenshot capture has failed.
     *
     * Errors for the non-default display are shown in a unique separate notification.
     */
    fun notifyScreenshotError(msgResId: Int) {
        val displayErrorString =
            if (displayId != Display.DEFAULT_DISPLAY) {
                " ($externalDisplayString)"
            } else {
                ""
            }
        val errorMsg = res.getString(msgResId) + displayErrorString

        // Repurpose the existing notification or create a new one
        val builder =
            Notification.Builder(context, NotificationChannels.ALERTS)
                .setTicker(res.getString(com.android.systemui.res.R.string.screenshot_failed_title))
                .setContentTitle(
                    res.getString(com.android.systemui.res.R.string.screenshot_failed_title)
                )
                .setContentText(errorMsg)
                .setSmallIcon(com.android.systemui.res.R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(context.getColor(R.color.system_notification_accent_color))
        val intent =
            devicePolicyManager.createAdminSupportIntent(
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE
            )
        if (intent != null) {
            val pendingIntent =
                PendingIntent.getActivityAsUser(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                    null,
                    UserHandle.CURRENT
                )
            builder.setContentIntent(pendingIntent)
        }
        SystemUIApplication.overrideNotificationAppName(context, builder, true)
        val notification = Notification.BigTextStyle(builder).bigText(errorMsg).build()
        // A different id for external displays to keep the 2 error notifications separated.
        val id =
            if (displayId == Display.DEFAULT_DISPLAY) {
                NOTE_GLOBAL_SCREENSHOT
            } else {
                NOTE_GLOBAL_SCREENSHOT_EXTERNAL_DISPLAY
            }
        notificationManager.notify(TAG, id, notification)
    }

    /**
     * Shows a notification containing the screenshot and the chip actions
     * @param imageData for actions, uri. cannot be null
     * @param bitmap for image preview. can be null
     */
    fun showPostActionNotification(imageData: ScreenshotController.SavedImageData, bitmap: Bitmap) {
        val uri = imageData.uri
        // notification channel ID is the URI hash as string - to allow notifications to pile up
        // and still be able to get the same ID someplace else for dismiss
        val requestCode = uri.toString().hashCode()
        val res = context.getResources()
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setDataAndType(uri, MIME)
        val pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val shareIntent = PendingIntent.getActivity(context, requestCode,
                Intent(Intent.ACTION_SEND)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .setType(MIME)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        PendingIntent.FLAG_IMMUTABLE)
        val actionShare = Notification.Action.Builder(0 /* no icon */,
                res.getText(com.android.systemui.res.R.string.screenrecord_share_label), shareIntent)

        val deleteIntent = PendingIntent.getBroadcast(context, requestCode,
                Intent(context, DeleteScreenshotReceiver::class.java)
                        .putExtra(SCREENSHOT_URI_ID, uri.toString())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        PendingIntent.FLAG_IMMUTABLE)
        val actionDelete = Notification.Action.Builder(0 /* no icon */,
                res.getText(com.android.systemui.res.R.string.screenshot_delete_label), deleteIntent)

        val b = Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
                .setTicker(res.getString(
                        com.android.systemui.res.R.string.screenshot_saved_title))
                .setContentTitle(res.getString(
                        com.android.systemui.res.R.string.screenshot_saved_title))
                .setContentText(res.getString(
                        com.android.systemui.res.R.string.screenrecord_save_text))
                .setSmallIcon(com.android.systemui.res.R.drawable.screenshot_image)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .setStyle(Notification.BigPictureStyle()
                        .bigPicture(bitmap).bigLargeIcon(bitmap))
                .setColor(context.getColor(R.color.system_notification_accent_color))
                .addAction(actionShare.build())
                .addAction(actionDelete.build())
                .setContentIntent(pi)

        notificationManager.notify(TAG, requestCode, b.build())
        maybePostGroup()
    }

    public fun dismissPostActionNotification(id: Int) {
        notificationManager.cancel(TAG, id)
        maybeDismissGroup()
        maybeCloseSystemDialogs()
    }

    public fun maybePostGroup() {
        if (countGroupedNotifications() < 2)
            return // only post after we show the 2nd notification
        val b = Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
                .setSmallIcon(com.android.systemui.res.R.drawable.screenshot_image)
                .setContentTitle(context.getResources().getString(
                        com.android.systemui.res.R.string.screenshot_saved_title))
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
        notificationManager.notify(TAG, GROUP_ID, b.build())
    }

    public fun maybeDismissGroup() {
        if (countGroupedNotifications() >= 1)
            return // dismiss only when we have one notification left
        notificationManager.cancel(TAG, GROUP_ID)
    }

    public fun maybeCloseSystemDialogs() {
        if (countGroupedNotifications() > 0)
            return // only dismiss when we cancel the last group notification
        context.closeSystemDialogs()
    }

    private fun countGroupedNotifications(): Int {
        val notifications = notificationManager.getActiveNotifications()
        var count = 0
        for (notification in notifications) {
            val tag = notification.getTag()
            if (tag == null || !tag.equals(TAG)) continue
            val id = notification.getId()
            if (id != GROUP_ID && id != NOTE_GLOBAL_SCREENSHOT && id != NOTE_GLOBAL_SCREENSHOT_EXTERNAL_DISPLAY)
                count++
        }
        return count
    }

    private val externalDisplayString: String
        get() =
            res.getString(
                com.android.systemui.res.R.string.screenshot_failed_external_display_indication
            )

    /** Factory for [ScreenshotNotificationsController]. */
    @AssistedFactory
    interface Factory {
        fun create(displayId: Int = Display.DEFAULT_DISPLAY): ScreenshotNotificationsController
    }

    companion object {
        private const val TAG = "ScreenshotNotificationManager"
        private const val MIME = "image/*"
        private const val GROUP_KEY = "screenshot_post_action_group"
        private const val GROUP_ID = 2107821532
    }
}
