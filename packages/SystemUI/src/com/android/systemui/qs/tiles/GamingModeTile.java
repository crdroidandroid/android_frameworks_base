/*
 * Copyright (C) 2019-2022 crDroidAndroid Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.System;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.SysUIToast;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.SystemSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.util.settings.SystemSettings;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.io.File;

import javax.inject.Inject;

/** Quick settings tile: GamingModeTile **/
public class GamingModeTile extends QSTileImpl<BooleanState> {

    private final SystemSetting mGamingModeActivated;
    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_gaming_mode);

    private static final Intent sGamingModeOn = new Intent("exthmui.intent.action.GAMING_MODE_ON");
    static {
        sGamingModeOn.addFlags(Intent.FLAG_RECEIVER_FOREGROUND |
            Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
    }
    private static final Intent sGamingModeOff = new Intent("exthmui.intent.action.GAMING_MODE_OFF");
    static {
        sGamingModeOff.addFlags(Intent.FLAG_RECEIVER_FOREGROUND |
            Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
    }

    /** Must not clash with constants in ActivityManagerService */
    public static final int MSG_SEND_GAMING_MODE_BROADCAST = 1001;

    @Inject
    public GamingModeTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            SystemSettings systemSettings) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mGamingModeActivated = new SystemSetting(systemSettings, mHandler, System.GAMING_MODE_ACTIVE) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        boolean gamingModeEnabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.GAMING_MODE_ENABLED, 0) == 1;
        mHost.collapsePanels();
        if (gamingModeEnabled) {
            if (mState.value) {
                sendBroadcast(sGamingModeOff);
            } else {
                sendBroadcast(sGamingModeOn);
            }
        } else {
            SysUIToast.makeText(mContext, mContext.getString(
                    R.string.gaming_mode_not_enabled),
                    Toast.LENGTH_LONG).show();
        }
        // Do not refresh state here
    }

    private void sendBroadcast(Intent intent) {
        if (mHandler == null) {
            return;
        }
        final Message message = new Message();
        message.what = MSG_SEND_GAMING_MODE_BROADCAST;
        message.obj = new Intent(intent);
        mContext.sendBroadcastAsUser((Intent) message.obj, UserHandle.CURRENT_OR_SELF);
    }

    @Override
    protected void handleLongClick(@Nullable View view) {
        mHost.collapsePanels();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName("org.exthmui.game","org.exthmui.game.ui.MainActivity"));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(intent);
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer)arg : mGamingModeActivated.getValue();
        final boolean enable = value == 1;
        state.value = enable;
        state.label = mContext.getString(R.string.quick_settings_gaming_mode_label);
        state.icon = mIcon;
        state.state = enable ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_gaming_mode_label);
    }

    @Override
    protected String composeChangeAnnouncement() {
        return mContext.getString(R.string.quick_settings_gaming_mode_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
