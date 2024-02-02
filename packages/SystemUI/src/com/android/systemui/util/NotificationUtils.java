/*
 * Copyright (C) 2023 the risingOS Android Project
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
package com.android.systemui.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.media.AppVolume;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;

import com.android.systemui.R;

public class NotificationUtils {

    private static final String CHANNEL_ID = "np_playback_service";
    private static final int NOTIFICATION_ID = 7383646;
    private Context context;
    private NotificationManager notificationManager;

    public NotificationUtils(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Now Playing",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setBlockable(true);
        channel.setSound(null, null);
        channel.setVibrationPattern(new long[]{0});
        notificationManager.createNotificationChannel(channel);
    }

    public String getActiveVolumeApp() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String mAppVolumeActivePackageName = "";
        for (AppVolume av : audioManager.listAppVolumes()) {
            if (av.isActive()) {
                mAppVolumeActivePackageName = av.getPackageName();
                break;
            }
        }
        return mAppVolumeActivePackageName;
    }

    public void showNowPlayingNotification(MediaMetadata metadata) {
        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (title == null || artist == null) return;
        String packageName = getActiveVolumeApp();
        if (packageName.equals("")) return;
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) return;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PackageManager packageManager = context.getPackageManager();
        String appLabel;
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            appLabel = (String) packageManager.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        String nowPlayingTitle = context.getString(R.string.now_playing_on, appLabel);
        String contentText = context.getString(R.string.by_artist, title, artist);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setLargeIcon(albumArt)
                .setContentTitle(nowPlayingTitle)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    public void cancelNowPlayingNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
