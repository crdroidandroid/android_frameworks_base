/*
 *  Copyright (C) 2015 The OmniROM Project
 *  Copyright (C) 2026 crDroid Android Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.crdroid.header;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the active header provider and pushes its image to observers.
 *
 * All provider work (settings changes, alarms, screen on, image decoding) runs on one
 * background thread, so providers never race each other or block the UI thread.
 * Observers receive callbacks on that thread and must post to their own views.
 */
public class StatusBarHeaderMachine {

    private static final String TAG = "StatusBarHeaderMachine";
    private static final boolean DEBUG = false;

    public interface IStatusBarHeaderProvider {
        public Drawable getCurrent(final Calendar time);

        public String getName();

        public void settingsChanged(Uri uri);

        public void enableProvider();

        public void disableProvider();
    }

    public interface IStatusBarHeaderMachineObserver {
        public void updateHeader(Drawable headerImage, boolean force);

        public void disableHeader();

        public void refreshHeader();
    }

    // broadcast providers sent when they update the header image
    public static final String STATUS_BAR_HEADER_UPDATE_ACTION =
            "com.android.systemui.crdroid.header.STATUS_BAR_HEADER_UPDATE";

    private static HandlerThread sThread;

    private final Context mContext;
    private final Handler mHandler;
    private final Map<String, IStatusBarHeaderProvider> mProviders = new HashMap<>();
    private final List<IStatusBarHeaderMachineObserver> mObservers = new ArrayList<>();
    private final String mDefaultProviderName;

    // Only touched on mHandler's thread.
    private boolean mReceiverRegistered;
    private boolean mObserving;
    private boolean mScreenOn = true;
    private String mCurrentProviderName;
    private Drawable mCurrentHeader;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (STATUS_BAR_HEADER_UPDATE_ACTION.equals(action)) {
                if (mScreenOn) {
                    if (DEBUG) Log.i(TAG, "status bar header background alarm triggered");
                    doUpdateStatusHeaderObservers(false);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                // we use inexact alarms so we cant be sure they have been sent during
                // screen off (sleep) - so update it on screen on
                if (!mScreenOn) {
                    if (DEBUG) Log.i(TAG, "status bar header background SCREEN_ON triggered");
                    mScreenOn = true;
                    doUpdateStatusHeaderObservers(false);
                }
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                // Every setting is per user; re-read everything for the new user.
                notifyProviderSettingsChanged(null);
                updateEnablementLocked();
            }
        }
    };

    private final ContentObserver mSettingsObserver;

    public StatusBarHeaderMachine(Context context) {
        mContext = context;
        mHandler = new Handler(getThread().getLooper());
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                onSettingChanged(uri);
            }
        };

        DaylightHeaderProvider defaultProvider = new DaylightHeaderProvider(context);
        addProvider(defaultProvider);
        mDefaultProviderName = defaultProvider.getName();
        mCurrentProviderName = mDefaultProviderName;
        addProvider(new StaticHeaderProvider(context));
        addProvider(new FileHeaderProvider(context));
    }

    private static synchronized HandlerThread getThread() {
        if (sThread == null) {
            sThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            sThread.start();
        }
        return sThread;
    }

    /** Returns the current header. Must be called on the machine's thread. */
    private Drawable getCurrent() {
        final Calendar now = Calendar.getInstance();
        IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider != null) {
            try {
                if (DEBUG) Log.i(TAG, "get current from provider " + provider.getName());
                return provider.getCurrent(now);
            } catch (Exception e) {
                Log.w(TAG, "Provider " + provider.getName() + " failed", e);
            }
        }
        return null;
    }

    public void addProvider(IStatusBarHeaderProvider provider) {
        if (!mProviders.containsKey(provider.getName())) {
            mProviders.put(provider.getName(), provider);
        }
    }

    public void addObserver(IStatusBarHeaderMachineObserver observer) {
        if (DEBUG) Log.i(TAG, "addObserver " + observer);
        mHandler.post(() -> {
            if (!mObservers.contains(observer)) {
                mObservers.add(observer);
            }
            startObservingLocked();
        });
    }

    public void removeObserver(IStatusBarHeaderMachineObserver observer) {
        mHandler.post(() -> {
            mObservers.remove(observer);
            if (mObservers.isEmpty()) {
                // Nobody to draw for: drop alarms, receivers and observers until reattached.
                stopObservingLocked();
                IStatusBarHeaderProvider provider = getCurrentProvider();
                if (provider != null) {
                    provider.disableProvider();
                }
                unregisterReceiverLocked();
                mCurrentHeader = null;
            }
        });
    }

    /** Re-evaluates enabled state and provider. Safe to call from any thread. */
    public void updateEnablement() {
        mHandler.post(this::updateEnablementLocked);
    }

    private void startObservingLocked() {
        if (mObserving) {
            return;
        }
        final String[] keys = {
                Settings.System.STATUS_BAR_CUSTOM_HEADER,
                Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE,
                Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
                Settings.System.STATUS_BAR_FILE_HEADER_IMAGE,
        };
        for (String key : keys) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, mSettingsObserver, UserHandle.USER_ALL);
        }
        mObserving = true;
    }

    private void stopObservingLocked() {
        if (mObserving) {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            mObserving = false;
        }
    }

    private void onSettingChanged(Uri uri) {
        // Shadow is applied by the shade itself (NotificationPanelViewController listens to
        // it via TunerService), so there is nothing for providers to do here.
        notifyProviderSettingsChanged(uri);
        updateEnablementLocked();
    }

    private void notifyProviderSettingsChanged(Uri uri) {
        final IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider != null) {
            try {
                provider.settingsChanged(uri);
            } catch (Exception e) {
                Log.w(TAG, "settingsChanged failed for " + provider.getName(), e);
            }
        }
    }

    private void doUpdateStatusHeaderObservers(final boolean force) {
        if (DEBUG) Log.i(TAG, "updateHeader force=" + force);
        final Drawable header = getCurrent();
        if (!force && header == mCurrentHeader) {
            return;
        }
        mCurrentHeader = header;
        for (IStatusBarHeaderMachineObserver observer : new ArrayList<>(mObservers)) {
            try {
                observer.updateHeader(header, force);
            } catch (Exception e) {
                Log.w(TAG, "observer.updateHeader failed", e);
            }
        }
    }

    private void doDisableStatusHeaderObservers() {
        mCurrentHeader = null;
        for (IStatusBarHeaderMachineObserver observer : new ArrayList<>(mObservers)) {
            try {
                observer.disableHeader();
            } catch (Exception e) {
                Log.w(TAG, "observer.disableHeader failed", e);
            }
        }
    }

    private void updateEnablementLocked() {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        final String providerName = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
                UserHandle.USER_CURRENT);

        final String wantedProvider = (providerName != null && mProviders.containsKey(providerName))
                ? providerName : mDefaultProviderName;
        if (!wantedProvider.equals(mCurrentProviderName)) {
            for (IStatusBarHeaderProvider provider : mProviders.values()) {
                if (!provider.getName().equals(wantedProvider)) {
                    if (DEBUG) Log.i(TAG, "disable provider " + provider.getName());
                    provider.disableProvider();
                }
            }
            mCurrentProviderName = wantedProvider;
        }

        final IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider == null) {
            Log.w(TAG, "updateEnablement: no active provider");
            return;
        }
        if (customHeader && !mObservers.isEmpty()) {
            if (DEBUG) Log.i(TAG, "enable provider " + provider.getName());
            provider.enableProvider();
            registerReceiverLocked();
            // we dont want to wait for the alarm
            doUpdateStatusHeaderObservers(true);
        } else {
            if (DEBUG) Log.i(TAG, "disable provider " + provider.getName());
            provider.disableProvider();
            if (unregisterReceiverLocked()) {
                doDisableStatusHeaderObservers();
            }
        }
    }

    private void registerReceiverLocked() {
        if (mReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(STATUS_BAR_HEADER_UPDATE_ACTION);
        // The update alarm is a package-scoped PendingIntent owned by SystemUI, so
        // nothing outside SystemUI needs to (or should be able to) reach this receiver.
        mContext.registerReceiverForAllUsers(mBroadcastReceiver, filter, null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
    }

    private boolean unregisterReceiverLocked() {
        if (!mReceiverRegistered) {
            return false;
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        mReceiverRegistered = false;
        return true;
    }

    private IStatusBarHeaderProvider getCurrentProvider() {
        if (mCurrentProviderName == null || !mProviders.containsKey(mCurrentProviderName)) {
            return mProviders.get(mDefaultProviderName);
        }
        return mProviders.get(mCurrentProviderName);
    }
}
