/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.res.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

public class SoundTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "sound";

    private boolean mListening = false;

    private final AudioManager mAudioManager;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    @Inject
    public SoundTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mAudioManager == null) {
            return;
        }
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            final IntentFilter filter = new IntentFilter(
                    AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        int oldState = mAudioManager.getRingerModeInternal();
        int newState = oldState;
        switch (oldState) {
            case AudioManager.RINGER_MODE_NORMAL:
                newState = AudioManager.RINGER_MODE_VIBRATE;
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                newState = AudioManager.RINGER_MODE_SILENT;
                break;
            case AudioManager.RINGER_MODE_SILENT:
                newState = AudioManager.RINGER_MODE_NORMAL;
                break;
        }
        mAudioManager.setRingerModeInternal(newState);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_SOUND_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_sound_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = getTileLabel();
        if (mAudioManager == null) {
            return;
        }
        switch (mAudioManager.getRingerModeInternal()) {
            case AudioManager.RINGER_MODE_NORMAL:
                state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_ringer_audible);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_sound_ring);
                state.state = Tile.STATE_ACTIVE;
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_ringer_vibrate);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_sound_vibrate);
                state.state = Tile.STATE_INACTIVE;
                break;
            case AudioManager.RINGER_MODE_SILENT:
                state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_ringer_silent);
                state.secondaryLabel = mContext.getString(R.string.quick_settings_sound_silent);
                state.state = Tile.STATE_INACTIVE;
                break;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }
}
