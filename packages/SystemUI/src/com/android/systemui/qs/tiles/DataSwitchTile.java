/*
 * Copyright (C) 2020-2022 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;
import com.android.systemui.SysUIToast;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;

import java.util.concurrent.Executors;
import java.util.List;

import javax.inject.Inject;

public class DataSwitchTile extends QSTileImpl<BooleanState> {
    private boolean mCanSwitch = true;
    private boolean mRegistered = false;
    private int mSimCount = 0;
    BroadcastReceiver mSimReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "mSimReceiver:onReceive");
            refreshState();
        }
    };
    private final MyCallStateListener mPhoneStateListener;
    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyManager mTelephonyManager;

    class MyCallStateListener extends PhoneStateListener {
        MyCallStateListener() {
        }

        public void onCallStateChanged(int state, String arg1) {
            mCanSwitch = mTelephonyManager.getCallState() == 0;
            refreshState();
        }
    }

    @Inject
    public DataSwitchTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mSubscriptionManager = SubscriptionManager.from(host.getContext());
        mTelephonyManager = TelephonyManager.from(host.getContext());
        mPhoneStateListener = new MyCallStateListener();
    }

    @Override
    public boolean isAvailable() {
        int count = TelephonyManager.getDefault().getPhoneCount();
        Log.d(TAG, "phoneCount: " + count);
        return count >= 2;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            if (!mRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
                mContext.registerReceiver(mSimReceiver, filter);
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                mRegistered = true;
            }
            refreshState();
        } else if (mRegistered) {
            mContext.unregisterReceiver(mSimReceiver);
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mRegistered = false;
        }
    }

    private void updateSimCount() {
        String simState = SystemProperties.get("gsm.sim.state");
        Log.d(TAG, "DataSwitchTile:updateSimCount:simState=" + simState);
        mSimCount = 0;
        try {
            String[] sims = TextUtils.split(simState, ",");
            for (String sim : sims) {
                if (!sim.isEmpty()
                        && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_ABSENT)
                        && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_NOT_READY)) {
                    mSimCount++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error to parse sim state");
        }
        Log.d(TAG, "DataSwitchTile:updateSimCount:mSimCount=" + mSimCount);
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (!mCanSwitch) {
            Log.d(TAG, "Call state=" + mTelephonyManager.getCallState());
        } else if (mSimCount == 0) {
            Log.d(TAG, "handleClick:no sim card");
        } else if (mSimCount == 1) {
            Log.d(TAG, "handleClick:only one sim card");
        } else {
            Executors.newSingleThreadExecutor().execute(() -> {
                toggleMobileDataEnabled();
                refreshState();
            });
            mHost.collapsePanels();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_data_switch_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean activeSIMZero;
        if (arg == null) {
            int defaultPhoneId = mSubscriptionManager.getDefaultDataPhoneId();
            Log.d(TAG, "default data phone id=" + defaultPhoneId);
            activeSIMZero = defaultPhoneId == 0;
        } else {
            activeSIMZero = (Boolean) arg;
        }
        updateSimCount();
        switch (mSimCount) {
            case 0:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_data_switch_0);
                state.value = false;
                state.secondaryLabel = mContext.getString(R.string.tile_unavailable);
                break;
            case 1:
                state.icon = ResourceIcon.get(activeSIMZero
                        ? R.drawable.ic_qs_data_switch_2
                        : R.drawable.ic_qs_data_switch_1);
                state.value = false;
                state.secondaryLabel = mContext.getString(R.string.tile_unavailable);
                break;
            case 2:
                state.icon = ResourceIcon.get(activeSIMZero
                        ? R.drawable.ic_qs_data_switch_2
                        : R.drawable.ic_qs_data_switch_1);
                state.value = true;
                state.secondaryLabel = getInactiveSlotName();
                break;
            default:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_data_switch_1);
                state.value = false;
                state.secondaryLabel = mContext.getString(R.string.tile_unavailable);
                break;
        }
        if (mSimCount < 2) {
            state.state = 0;
        } else if (!mCanSwitch) {
            state.state = 0;
            Log.d(TAG, "call state isn't idle, set to unavailable.");
        } else {
            state.state = state.value ? 2 : 1;
        }

        state.contentDescription =
                mContext.getString(activeSIMZero
                        ? R.string.qs_data_switch_changed_1
                        : R.string.qs_data_switch_changed_2);
        state.label = mContext.getString(R.string.qs_data_switch_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    protected String composeChangeAnnouncement() {
        return mContext.getString(mState.value
                ? R.string.qs_data_switch_changed_1
                : R.string.qs_data_switch_changed_2);
    }

    /**
     * Set whether to enable data for {@code subId}, also whether to disable data for other
     * subscription
     */
    private void toggleMobileDataEnabled() {
        TelephonyManager telephonyManager;
        boolean dataEnabled = false;
        boolean foundActive = false;
        List<SubscriptionInfo> subInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList(true);
        if (subInfoList != null) {
            for (SubscriptionInfo subInfo : subInfoList) {
                telephonyManager =
                    mTelephonyManager.createForSubscriptionId(subInfo.getSubscriptionId());
                dataEnabled = telephonyManager.getDataEnabled();
                if (!subInfo.isOpportunistic() || !dataEnabled) {
                    telephonyManager.setDataEnabled(!dataEnabled && !foundActive);
                    // Indicate we found sim with active data, disable data on remaining sim.
                    if (!foundActive) foundActive = !dataEnabled;
                }
                Log.d(TAG, "Changed subID " + subInfo.getSubscriptionId() + " to "
                    + !dataEnabled);
            }
        }
    }

    private String getInactiveSlotName() {
        TelephonyManager telephonyManager;
        String mInitialState = mContext.getString(R.string.tile_unavailable);
        List<SubscriptionInfo> subInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList(true);
        if (subInfoList != null) {
            for (SubscriptionInfo subInfo : subInfoList) {
                telephonyManager =
                        mTelephonyManager.createForSubscriptionId(subInfo.getSubscriptionId());
                if (!telephonyManager.getDataEnabled()) {
                    // Inactive SIM found
                    return subInfo.getDisplayName().toString();
                }
            }
        }
        return mInitialState;
    }
}
