/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import static android.provider.Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.res.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.retail.domain.interactor.RetailModeInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiStatusTrackerFactory;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.GlobalSettings;

import com.android.settingslib.wifi.WifiStatusTracker;

import java.util.Arrays;
import javax.inject.Inject;

/**
 * Controller for {@link QSFooterView}.
 */
@QSScope
public class QSFooterViewController extends ViewController<QSFooterView>
        implements QSFooter, TunerService.Tunable {

    private static final String QS_SHOW_DATA_USAGE =
            "system:" + Settings.System.QS_SHOW_DATA_USAGE;

    private final UserTracker mUserTracker;
    private final QSPanelController mQsPanelController;
    private final PageIndicator mPageIndicator;
    private final View mEditButton;
    private final FalsingManager mFalsingManager;
    private final ActivityStarter mActivityStarter;
    private final RetailModeInteractor mRetailModeInteractor;
    private final WifiStatusTracker mWifiTracker;
    private final NetworkController mNetworkController;
    private final Context mContext;
    private final TunerService mTunerService;
    private final GlobalSettings mGlobalSettings;
    private final SubscriptionManager mSubManager;

    private static final String INTERNET_TILE = "internet";

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mWifiTracker.handleBroadcast(intent);
            onWifiStatusUpdated();
        }
    };

    private final SignalCallback mSignalCallback = new SignalCallback() {
        @Override
        public void setNoSims(boolean show, boolean simDetected) {
            mView.setNoSims(show);
        }
    };

    private final ContentObserver mDataSwitchObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            onDefaultDataSimChanged();
        }
    };

    @Inject
    QSFooterViewController(QSFooterView view,
            UserTracker userTracker,
            FalsingManager falsingManager,
            ActivityStarter activityStarter,
            QSPanelController qsPanelController,
            RetailModeInteractor retailModeInteractor,
            NetworkController networkController,
            WifiStatusTrackerFactory trackerFactory,
            Context context,
            TunerService tunerService,
            GlobalSettings globalSettings) {
        super(view);
        mUserTracker = userTracker;
        mQsPanelController = qsPanelController;
        mFalsingManager = falsingManager;
        mActivityStarter = activityStarter;
        mRetailModeInteractor = retailModeInteractor;
        mNetworkController = networkController;
        mContext = context;
        mTunerService = tunerService;
        mGlobalSettings = globalSettings;
        mSubManager = context.getSystemService(SubscriptionManager.class);
        mWifiTracker = trackerFactory.createTracker(this::onWifiStatusUpdated, null);
        mPageIndicator = mView.findViewById(R.id.footer_page_indicator);
        mEditButton = mView.findViewById(android.R.id.edit);
    }

    @Override
    protected void onViewAttached() {
        mEditButton.setOnClickListener(view -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }
            mActivityStarter
                    .postQSRunnableDismissingKeyguard(() -> mQsPanelController.showEdit(view));
        });
        mQsPanelController.setFooterPageIndicator(mPageIndicator);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        mContext.registerReceiver(mReceiver, filter);
        mWifiTracker.fetchInitialState();
        mWifiTracker.setListening(true);
        mNetworkController.addCallback(mSignalCallback);
        mTunerService.addTunable(this, QS_SHOW_DATA_USAGE);
        mGlobalSettings.registerContentObserver(MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                mDataSwitchObserver);

        // set initial values
        onWifiStatusUpdated();
        onDefaultDataSimChanged();
    }

    @Override
    protected void onViewDetached() {
        mContext.unregisterReceiver(mReceiver);
        mNetworkController.removeCallback(mSignalCallback);
        mTunerService.removeTunable(this);
        mGlobalSettings.unregisterContentObserver(mDataSwitchObserver);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
         if (key.equals(QS_SHOW_DATA_USAGE)) {
            mView.setShowDataUsage(TunerService.parseIntegerSwitch(newValue, false));
         }
    }

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
        mEditButton
                .setVisibility(mRetailModeInteractor.isInRetailMode() ? View.GONE : View.VISIBLE);
        mEditButton.setClickable(visibility == View.VISIBLE);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mView.setExpanded(expanded);
    }

    @Override
    public void setExpansion(float expansion) {
        mView.setExpansion(expansion);
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        mView.setKeyguardShowing();
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state2);
    }

    private void onWifiStatusUpdated() {
        mView.setIsWifiConnected(mWifiTracker.connected);
        mView.setWifiSsid(mWifiTracker.ssid);
    }

    private void onDefaultDataSimChanged() {
        int subId = mSubManager.getDefaultDataSubscriptionId();
        mView.setCurrentDataSubId(subId);
    }
}
