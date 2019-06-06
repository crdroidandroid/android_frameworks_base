/**
 * Copyright (C) 2018 The LineageOS project
 * Copyright (C) 2019 The PixelExperience project
 * Copyright (C) 2019 The Syberia project
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

package com.android.internal.util.crdroid;

import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;

import android.provider.Settings;
import android.provider.Settings.Global;
import android.widget.Toast;

public class GamingModeController {
    private Context mContext;
    final Context mUiContext;
    ApplicationInfo appInfo;
    AudioManager mAudioManager;
    NotificationManager mNotificationManager;
    PackageManager pm;
    private boolean gamingEnabled;
    private boolean gamingModeMaster;
    private boolean isDynamicGamingMode;
    private Toast toast;
    private String mGamingPackageList;
    private ArrayList<String> mGameApp = new ArrayList<String>();

    private static final String TAG = "GamingModeController";
    private static final int GAMING_NOTIFICATION_ID = 420;

    public GamingModeController(Context context) {
        mContext = context;
        mUiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        gamingModeMaster = Settings.System.getInt(mContext.getContentResolver(),
                            Settings.System.GAMING_MODE_MASTER_SWITCH, 1) == 1;

        isDynamicGamingMode = Settings.System.getInt(mContext.getContentResolver(),
                               Settings.System.GAMING_MODE_DYNAMIC_STATE, 1) == 1;

        parsePackageList();

        SettingsObserver observer = new SettingsObserver(
                new Handler(Looper.getMainLooper()));
        observer.observe();
    }

    private void parsePackageList() {
        final String gamingApp = Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_VALUES);
        splitAndAddToArrayList(mGameApp, gamingApp, "\\|");
    }

    private void savePackageList(ArrayList<String> arrayList) {
        String setting = Settings.System.GAMING_MODE_VALUES;

        List<String> settings = new ArrayList<String>();
        for (String app : arrayList) {
            settings.add(app.toString());
        }
        final String value = TextUtils.join("|", settings);
            if (TextUtils.equals(setting, Settings.System.GAMING_MODE_VALUES)) {
                mGamingPackageList = value;
            }
        Settings.System.putString(mContext.getContentResolver(),
                setting, value);
    }

    private void addGameApp(String packageName) {
        if (!mGameApp.contains(packageName)) {
            mGameApp.add(packageName);
            savePackageList(mGameApp);
        }
    }

    private boolean isGameApp(String packageName) {
        return (mGameApp.contains(packageName));
    }

    public boolean gamingModeMaster() {
        return gamingModeMaster;
    }

    public void notePackageUninstalled(String pkgName) {
     // remove from list
        if (mGameApp.remove(pkgName)) {
            savePackageList(mGameApp);
        }
    }

    public boolean topAppChanged(String packageName) {
        return isGameApp(packageName);
    }

    public void noteStarted(String packageName) {
        if (isGameApp(packageName)) {
            return;
        }
        appInfo = getAppInfoFromPkgName(mContext, packageName);
        if(isDynamicGamingMode && appInfo != null && (appInfo.category == ApplicationInfo.CATEGORY_GAME ||
            (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) == ApplicationInfo.FLAG_IS_GAME)) {
                addGameApp(packageName);
            if (!gamingEnabled)  {
                Settings.System.putInt(mContext.getContentResolver(),
                          Settings.System.ENABLE_GAMING_MODE, 1);
            }
        }
    }

    private void splitAndAddToArrayList(ArrayList<String> arrayList,
            String baseString, String separator) {
        // clear first
        arrayList.clear();
        if (baseString != null) {
            final String[] array = TextUtils.split(baseString, separator);
            for (String item : array) {
                arrayList.add(item.trim());
            }
        }
    }

    private static ApplicationInfo getAppInfoFromPkgName(Context context, String Packagename) {
      try {
        PackageManager packageManager = context.getPackageManager();
        ApplicationInfo info = packageManager.getApplicationInfo(Packagename, PackageManager.GET_META_DATA);
        return info;
      } catch (PackageManager.NameNotFoundException e) {
        e.printStackTrace();
        return null;
      }
    }

    private int getZenMode() {
        if (mNotificationManager == null) {
             mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        try {
            return mNotificationManager.getZenMode();
        } catch (Exception e) {
             return -1;
        }
    }

    private void setZenMode(int mode) {
        if (mNotificationManager == null) {
             mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        try {
            mNotificationManager.setZenMode(mode, null, TAG);
        } catch (Exception e) {
        }
    }

    private int getRingerModeInternal() {
        if (mAudioManager == null) {
             mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        try {
            return mAudioManager.getRingerModeInternal();
        } catch (Exception e) {
             return -1;
        }
    }

    private void setRingerModeInternal(int mode) {
        if (mAudioManager == null) {
             mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        }
        try {
            mAudioManager.setRingerModeInternal(mode);
        } catch (Exception e) {
        }
    }

    private void showToast(String msg, int duration) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
        @Override
        public void run() {
            if (toast != null) toast.cancel();
            toast = Toast.makeText(mUiContext, msg, duration);
            toast.show();
            }
        });
    }

    private void enableGamingFeatures() {
      if (!ActivityManager.isSystemReady())
          return;
        // Lock brightness
        boolean enableManualBrightness = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_MANUAL_BRIGHTNESS_TOGGLE, 1) == 1;
        if (enableManualBrightness) {
            final boolean isAdaptiveEnabledByUser = Settings.System.getInt(mContext.getContentResolver(),
                              Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.GAMING_SCREEN_BRIGHTNESS_MODE, isAdaptiveEnabledByUser ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, SCREEN_BRIGHTNESS_MODE_MANUAL);
        }
        boolean enableHeadsUp = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_HEADSUP_TOGGLE, 1) == 1;
        // Heads up
        if (enableHeadsUp) {
            final boolean isHeadsUpEnabledByUser = Settings.Global.getInt(mContext.getContentResolver(),
                              Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1;
                Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.GAMING_HEADS_UP_NOTIFICATIONS_ENABLED, isHeadsUpEnabledByUser ? 1 : 0);
                Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 0);
        }
        // Capacitive keys
        boolean disableHwKeys = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_HW_KEYS_TOGGLE, 1) == 1;
        if (disableHwKeys) {
            final boolean isHwKeysEnabledByUser = Settings.Secure.getInt(mContext.getContentResolver(),
                              Settings.Secure.HARDWARE_KEYS_DISABLE, 0) == 0;
                Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.GAMING_HARDWARE_KEYS_DISABLE, isHwKeysEnabledByUser ? 0 : 1);
                Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.HARDWARE_KEYS_DISABLE, 1);
        }
        // Ringer mode (0: OFF, 1: Vibrate, 2:DND: 3:Silent
            int ringerMode = Settings.System.getInt(mContext.getContentResolver(),
                 Settings.System.GAMING_MODE_RINGER_MODE, 0);
            int userState = getRingerModeInternal();
              if (userState >= 0) {
                    Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.GAMING_RINGER_STATE, userState);
              }
            int userZenState = getZenMode();
              if (userZenState >= 0) {
                    Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.GAMING_MODE_ZEN_STATE, userZenState);
              }
        if (ringerMode != 0 || ringerMode != userState) {
            if (ringerMode == 1) {
                setRingerModeInternal(AudioManager.RINGER_MODE_VIBRATE);
                setZenMode(ZEN_MODE_OFF);
            }
            else if (ringerMode == 2) {
                setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                setZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
            }
            else if (ringerMode == 3) {
                setRingerModeInternal(AudioManager.RINGER_MODE_SILENT);
                setZenMode(ZEN_MODE_OFF);
            }
        }

        int mNotifications = Settings.System.getInt(mContext.getContentResolver(),
                          Settings.System.GAMING_MODE_NOTIFICATIONS, 3);
        if(mNotifications == 2 || mNotifications == 3)
            showToast(mContext.getResources().getString(R.string.gaming_mode_enabled_toast), Toast.LENGTH_LONG);
        if(mNotifications == 1 || mNotifications == 3)
            addNotification();
    }

    private void disableGamingFeatures() {
        if (!ActivityManager.isSystemReady())
             return;
        ContentResolver resolver = mContext.getContentResolver();
            final boolean wasAdaptiveEnabledByUser = Settings.System.getInt(resolver,
                              Settings.System.GAMING_SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
                                   Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                Settings.System.putInt(resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE, wasAdaptiveEnabledByUser ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            final boolean wasHeadsUpEnabledByUser = Settings.Global.getInt(resolver,
                              Settings.Global.GAMING_HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1;
                Settings.Global.putInt(resolver,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, wasHeadsUpEnabledByUser ? 1 : 0);
            final boolean wasHwKeyEnabledByUser = Settings.Secure.getInt(resolver,
                              Settings.Secure.GAMING_HARDWARE_KEYS_DISABLE, 0) == 0;
                Settings.Secure.putInt(resolver,
                    Settings.Secure.HARDWARE_KEYS_DISABLE, wasHwKeyEnabledByUser ? 0 : 1);
            int ringerMode = Settings.System.getInt(mContext.getContentResolver(),
                 Settings.System.GAMING_MODE_RINGER_MODE, 0);
            final int ringerState = Settings.System.getInt(resolver,
                  Settings.System.GAMING_RINGER_STATE, AudioManager.RINGER_MODE_NORMAL);
            final int zenState = Settings.System.getInt(resolver,
                  Settings.System.GAMING_MODE_ZEN_STATE, ZEN_MODE_OFF);
            if (getRingerModeInternal() >= 0 && ringerState != getRingerModeInternal())
                 setRingerModeInternal(ringerState);
            if (getZenMode() >= 0 && zenState != getZenMode())
                   setZenMode(zenState);

            int mNotifications = Settings.System.getInt(mContext.getContentResolver(),
                          Settings.System.GAMING_MODE_NOTIFICATIONS, 3);
            if(mNotifications == 2 || mNotifications == 3)
                showToast(mContext.getResources().getString(R.string.gaming_mode_disabled_toast), Toast.LENGTH_LONG);
            mNotificationManager.cancel(GAMING_NOTIFICATION_ID);
        }


    private void addNotification() {
        final Resources r = mContext.getResources();
        // Display a notification
        Notification.Builder builder = new Notification.Builder(mContext, SystemNotificationChannels.GAMING)
            .setTicker(r.getString(com.android.internal.R.string.gaming_notif_ticker))
            .setContentTitle(r.getString(com.android.internal.R.string.gaming_notif_ticker))
            .setSmallIcon(com.android.internal.R.drawable.ic_gaming_notif)
            .setWhen(java.lang.System.currentTimeMillis())
            .setOngoing(true);

        Notification notif = builder.build();
        mNotificationManager.notify(GAMING_NOTIFICATION_ID, notif);
    }


    public boolean getEnabled() {
      return gamingEnabled;
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.GAMING_MODE_MASTER_SWITCH), false, this,
                    UserHandle.USER_ALL);

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.ENABLE_GAMING_MODE), false, this,
                    UserHandle.USER_ALL);

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.GAMING_MODE_VALUES), false, this,
                    UserHandle.USER_ALL);

            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.GAMING_MODE_DYNAMIC_STATE), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(
                             Settings.System.GAMING_MODE_VALUES))) {
                parsePackageList();
            } else if (uri.equals(Settings.System.getUriFor(
                                   Settings.System.ENABLE_GAMING_MODE))) {
                updateGamingMode();
            } else if (uri.equals(Settings.System.getUriFor(
                                   Settings.System.GAMING_MODE_DYNAMIC_STATE))) {
                isDynamicGamingMode = Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.GAMING_MODE_DYNAMIC_STATE, 1) == 1;
            } else if (uri.equals(Settings.System.getUriFor(
                                   Settings.System.GAMING_MODE_MASTER_SWITCH))) {
                gamingModeMaster = Settings.System.getInt(mContext.getContentResolver(),
                                       Settings.System.GAMING_MODE_MASTER_SWITCH, 1) == 1;
            }
        }

        public void updateGamingMode() {
            ContentResolver resolver = mContext.getContentResolver();

            gamingEnabled = Settings.System.getInt(resolver,
                             Settings.System.ENABLE_GAMING_MODE, 0) == 1;

            if (gamingEnabled)  enableGamingFeatures();
            if (!gamingEnabled) disableGamingFeatures();
        }
    }
}
