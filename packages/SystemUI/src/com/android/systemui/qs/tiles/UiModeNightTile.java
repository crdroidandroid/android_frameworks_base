/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;

import javax.inject.Inject;

/**
 * Quick Settings tile for: Night Mode / Dark Theme / Dark Mode.
 *
 * The string id of this tile is "dark" because "night" was already
 * taken by {@link NightDisplayTile}.
 */
public class UiModeNightTile extends QSTileImpl<QSTile.BooleanState> implements
        BatteryController.BatteryStateChangeCallback {

    private final Icon mIcon = ResourceIcon.get(
            com.android.internal.R.drawable.ic_qs_ui_mode_night);
    private final BatteryController mBatteryController;
    private final ThemeOverrideObserver mObserver;
    private int mThemeOverride;
    private boolean mListening;

    @Inject
    public UiModeNightTile(QSHost host, BatteryController batteryController) {
        super(host);
        mBatteryController = batteryController;
        batteryController.observe(getLifecycle(), this);
        mObserver = new ThemeOverrideObserver(mHandler);
        mObserver.startObserving();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mThemeOverride == 0) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.BERRY_THEME_OVERRIDE, 1, UserHandle.USER_CURRENT);
        } else if (mThemeOverride == 1) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.BERRY_THEME_OVERRIDE, 2, UserHandle.USER_CURRENT);
        } else if (mThemeOverride == 2) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.BERRY_THEME_OVERRIDE, 3, UserHandle.USER_CURRENT);
        } else if (mThemeOverride == 3) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.BERRY_THEME_OVERRIDE, 0, UserHandle.USER_CURRENT);
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean powerSave = mBatteryController.isPowerSave();
        boolean nightMode = (Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.BERRY_DARK_CHECK,
                0, UserHandle.USER_CURRENT) != 0);
        mThemeOverride = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.BERRY_THEME_OVERRIDE,
                0, UserHandle.USER_CURRENT);

        state.value = nightMode;
        state.label = mContext.getString(R.string.quick_settings_ui_mode_night_label);

        switch (mThemeOverride) {
            case 0:
            default:
                state.secondaryLabel = mContext.getString(R.string.quick_settings_ui_mode_automatic_wallpaper);
                break;
            case 1:
                state.secondaryLabel = mContext.getString(R.string.quick_settings_ui_mode_automatic_livedisplay);
                break;
            case 2:
                state.secondaryLabel = mContext.getString(R.string.quick_settings_ui_mode_disabled);
                break;
            case 3:
                state.secondaryLabel = mContext.getString(R.string.quick_settings_ui_mode_enabled);
                break;
        }

        state.contentDescription = state.label;
        state.icon = mIcon;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.showRippleEffect = false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.QS_UI_MODE_NIGHT;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    }

    @Override
    protected void handleSetListening(boolean listening) {
        if (mListening == listening)
            return;
        mListening = listening;
        if (listening) {
            mObserver.startObserving();
        } else {
            mObserver.endObserving();
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    private class ThemeOverrideObserver extends ContentObserver {
        public ThemeOverrideObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshState();
        }

        public void startObserving() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BERRY_DARK_CHECK),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.BERRY_THEME_OVERRIDE),
                    false, this, UserHandle.USER_ALL);
        }

        public void endObserving() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
    }
}
