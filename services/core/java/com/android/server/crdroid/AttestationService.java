/*
 * Copyright (C) 2024 The LeafOS Project
 * Copyright (C) 2024 crDroid Android Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.crdroid;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.Log;

import com.android.server.SystemService;
import com.android.internal.util.crdroid.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AttestationService extends SystemService {

    private static final String TAG = AttestationService.class.getSimpleName();

    private static final String API = "https://raw.githubusercontent.com/crdroidandroid/android_vendor_certification/refs/heads/15.0/gms_certified_props.json";
    private static final String SPOOF_PIXEL_PI = "persist.sys.pixelprops.pi";
    private static final String DATA_FILE = "gms_certified_props.json";
    private static final long INITIAL_DELAY = 0; // Start immediately on boot
    private static final long INTERVAL = 8; // Interval in hours
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final File mDataFile;
    private final ScheduledExecutorService mScheduler;
    private final ConnectivityManager mConnectivityManager;
    private final FetchGmsCertifiedProps mFetchRunnable;

    private boolean mPendingUpdate;

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mDataFile = new File(Environment.getDataSystemDirectory(), DATA_FILE);
        mFetchRunnable = new FetchGmsCertifiedProps();
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (Utils.isPackageInstalled(mContext, "com.google.android.gms")
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling the service");
            mScheduler.scheduleAtFixedRate(
                    mFetchRunnable, INITIAL_DELAY, INTERVAL, TimeUnit.HOURS);
        }
    }

    private String readFromFile(File file) {
        StringBuilder content = new StringBuilder();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file", e);
            }
        }
        return content.toString();
    }

    private void writeToFile(File file, String data) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
            // Set -rw-r--r-- (644) permission to make it readable by others.
            file.setReadable(true, false);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to file", e);
        }
    }

    private String fetchProps() {
        try {
            URL url = new URI(API).toURL();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            try {
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                }
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        }
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private boolean isInternetConnected() {
        Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return false;
    }

    private void registerNetworkCallback() {
        mConnectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Internet is available, resuming update");
                if (mPendingUpdate) {
                    mScheduler.schedule(mFetchRunnable, 0, TimeUnit.SECONDS);
                }
            }
        });
    }

    private class FetchGmsCertifiedProps implements Runnable {
        @Override
        public void run() {
            if (!SystemProperties.getBoolean(SPOOF_PIXEL_PI, true)) {
                mPendingUpdate = false;
                return;
            }

            try {
                dlog("FetchGmsCertifiedProps started");

                if (!isInternetConnected()) {
                    Log.e(TAG, "Internet is unavailable, deferring update");
                    mPendingUpdate = true;
                    return;
                }
                mPendingUpdate = false;

                String savedProps = readFromFile(mDataFile);
                String props = fetchProps();

                if (props != null && !savedProps.equals(props)) {
                    dlog("Found new props");
                    writeToFile(mDataFile, props);
                    dlog("FetchGmsCertifiedProps completed");
                } else {
                    dlog("No change in props");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedProps", e);
            }
        }
    }
}
