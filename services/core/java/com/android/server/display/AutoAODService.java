/*
 * Copyright (C) 2021 Yet Another AOSP Project
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
package com.android.server.display;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.util.Calendar;

public class AutoAODService extends SystemService {

    private static final String TAG = "AutoAODService";
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final int WAKELOCK_TIMEOUT_MS = 3000;

    /**
     * Disabled state (default)
     */
    private static final int MODE_DISABLED = 0;
    /**
     * Active from sunset to sunrise
     */
    private static final int MODE_NIGHT = 1;
    /**
     * Active at a user set time
     */
    private static final int MODE_TIME = 2;

    private final AlarmManager mAlarmManager;
    private final Context mContext;
    private final Handler mHandler = new Handler();
    private TwilightManager mTwilightManager;
    private TwilightState mTwilightState;

    /**
     * Current operation mode
     * Can either be {@link MODE_DISABLED}, {@link MODE_NIGHT} or {@link MODE_TIME}
     */
    private int mMode = MODE_DISABLED;
    /**
     * Whether AOD is currently activated by the service
     */
    private boolean mActive = false;
    /**
     * Whether next alarm should enable or disable AOD
     */
    private boolean mIsNextActivate = false;

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            if (mMode != MODE_NIGHT) {
                // just incase
                mTwilightManager.unregisterListener(mTwilightListener);
                return;
            }
            Slog.v(TAG, "onTwilightStateChanged state: " + state);
            if (state == null) return;
            mTwilightState = state;
            mHandler.post(() -> maybeActivateAOD());
        }
    };

    private final BroadcastReceiver mTimeChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mMode != MODE_TIME) {
                // just incase
                mContext.unregisterReceiver(mTimeChangedReceiver);
                return;
            }
            Slog.v(TAG, "mTimeChangedReceiver onReceive");
            mHandler.post(() -> maybeActivateTime());
        }
    };

    /**
     * A class to manage and handle alarms
     */
    private class Alarm implements AlarmManager.OnAlarmListener {
        @Override
        public void onAlarm() {
            Slog.v(TAG, "onAlarm");
            mHandler.post(() -> setAutoAODActive(mIsNextActivate));
            if (mMode == MODE_TIME) mHandler.post(() -> maybeActivateTime(false));
            else maybeActivateNight(false);
        }

        /**
         * Set a new alarm using a Calendar
         * @param time time as Calendar
         */
        public void set(Calendar time) {
            set(time.getTimeInMillis());
        }

        /**
         * Set a new alarm using ms since epoch
         * @param time time as ms since epoch
         */
        public void set(long time) {
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                    time, TAG, this, mHandler);
            Slog.v(TAG, "new alarm set to " + time
                    + " mIsNextActivate=" + String.valueOf(mIsNextActivate));
        }

        public void cancel() {
            mAlarmManager.cancel(this);
            Slog.v(TAG, "alarm cancelled");
        }
    }

    private Alarm mAlarm = new Alarm();

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DOZE_ALWAYS_ON_AUTO_TIME),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mHandler.post(() -> initState());
        }
    }

    private SettingsObserver mSettingsObserver;

    public AutoAODService(Context context) {
        super(context);
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting " + TAG);
        publishLocalService(AutoAODService.class, this);
        mSettingsObserver.observe();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Slog.v(TAG, "onBootPhase PHASE_SYSTEM_SERVICES_READY");
            mTwilightManager = getLocalService(TwilightManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Slog.v(TAG, "onBootPhase PHASE_BOOT_COMPLETED");
            mHandler.post(() -> initState());
        }
    }

    /**
     * Initiates the state according to user settings
     * Registers or unregisters listeners and calls {@link maybeActivateAOD()}
     */
    private void initState() {
        int pMode = mMode;
        mMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON_AUTO_MODE, MODE_DISABLED,
                UserHandle.USER_CURRENT);
        mAlarm.cancel(); // cancelling set alarm
        // shifting to MODE_DISABLED
        if (mMode == MODE_DISABLED && pMode != MODE_DISABLED)
            setAutoAODActive(false);
        // we are still disabled
        if (mMode == MODE_DISABLED) return;
        if (mMode == MODE_TIME && pMode != MODE_TIME) {
            // shifting to MODE_TIME
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mContext.registerReceiver(mTimeChangedReceiver, intentFilter);
        } else if (pMode == MODE_TIME && mMode != MODE_TIME) {
            // shifting out of MODE_TIME
            mContext.unregisterReceiver(mTimeChangedReceiver);
        }
        if (mMode == MODE_NIGHT && pMode != MODE_NIGHT) {
            // shifting to MODE_NIGHT
            mTwilightManager.registerListener(mTwilightListener, mHandler);
            mTwilightState = mTwilightManager.getLastTwilightState();
        } else if (pMode == MODE_NIGHT && mMode != MODE_NIGHT) {
            // shifting out of MODE_NIGHT
            mTwilightManager.unregisterListener(mTwilightListener);
        }
        maybeActivateAOD();
    }

    /**
     * Calls the correct function to set the next alarm according to {@link mMode}
     */
    private void maybeActivateAOD() {
        switch (mMode) {
            default:
            case MODE_DISABLED:
                break;
            case MODE_NIGHT:
                maybeActivateNight();
                break;
            case MODE_TIME:
                maybeActivateTime();
                break;
        }
    }

    /**
     * See {@link maybeActivateNight(boolean)}
     */
    private void maybeActivateNight() {
        maybeActivateNight(true);
    }

    /**
     * Sets the next alarm for {@link MODE_NIGHT}
     * @param setActive Whether to set activation state.
     *                  When false only updates the alarm
     */
    private void maybeActivateNight(boolean setActive) {
        if (mTwilightState == null) {
            Slog.e(TAG, "aborting maybeActivateNight(). mTwilightState is null");
            return;
        }
        mIsNextActivate = !mTwilightState.isNight();
        mAlarm.set(mIsNextActivate ? mTwilightState.sunsetTimeMillis()
                : mTwilightState.sunriseTimeMillis());
        if (setActive) mHandler.post(() -> setAutoAODActive(!mIsNextActivate));
    }

    /**
     * See {@link maybeActivateTime(boolean)}
     */
    private void maybeActivateTime() {
        maybeActivateTime(true);
    }

    /**
     * Sets the next alarm for {@link MODE_TIME}
     * @param setActive Whether to set activation state
     *                  When false only updates the alarm
     */
    private void maybeActivateTime(boolean setActive) {
        Calendar currentTime = Calendar.getInstance();
        Calendar since = Calendar.getInstance();
        Calendar till = Calendar.getInstance();
        String value = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON_AUTO_TIME, UserHandle.USER_CURRENT);
        if (value == null || value.equals("")) value = "20:00,07:00";
        String[] times = value.split(",", 0);
        String[] sinceValues = times[0].split(":", 0);
        String[] tillValues = times[1].split(":", 0);
        since.set(Calendar.HOUR_OF_DAY, Integer.valueOf(sinceValues[0]));
        since.set(Calendar.MINUTE, Integer.valueOf(sinceValues[1]));
        since.set(Calendar.SECOND, 0);
        till.set(Calendar.HOUR_OF_DAY, Integer.valueOf(tillValues[0]));
        till.set(Calendar.MINUTE, Integer.valueOf(tillValues[1]));
        till.set(Calendar.SECOND, 0);
        // roll to the next day if needed be
        if (since.after(till)) till.roll(Calendar.DATE, true);
        if (currentTime.after(since) && currentTime.compareTo(till) >= 0) {
            since.roll(Calendar.DATE, true);
            till.roll(Calendar.DATE, true);
        }
        // abort if the user was dumb enough to set the same time
        if (since.compareTo(till) == 0) {
            Slog.e(TAG, "Aborting maybeActivateTime(). Time diff is 0");
            return;
        }

        // update the next alarm
        mIsNextActivate = currentTime.before(since);
        mAlarm.set(mIsNextActivate ? since : till);

        // activate or disable according to current time
        if (setActive) setAutoAODActive(currentTime.compareTo(since) >= 0
                && currentTime.before(till));
    }

    /**
     * Activates or inactivates AOD
     * @param active Whether to enable or disable AOD
     */
    private void setAutoAODActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        Slog.v(TAG, "setAutoAODActive: active=" + String.valueOf(active));
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, active ? 1 : 0,
                UserHandle.USER_CURRENT);

        // triggering doze to update the screen state
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        final Intent intent = new Intent(PULSE_ACTION);
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
    }
}
