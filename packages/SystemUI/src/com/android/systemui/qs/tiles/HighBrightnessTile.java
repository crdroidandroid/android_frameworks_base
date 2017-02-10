/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

public class HighBrightnessTile extends QSTileImpl<BooleanState> {

    public HighBrightnessTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.HIGH_BRIGHTNESS_MODE),
                    false, mObserver);
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    public void handleClick() {
        toggleState();
        refreshState();
    }

    @Override
    public void handleLongClick() {

    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_high_brightness);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean highBrightness = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.HIGH_BRIGHTNESS_MODE, 0,
                UserHandle.USER_CURRENT) != 0;

        state.label = mContext.getString(R.string.quick_settings_high_brightness);
        state.icon = highBrightness
                ? ResourceIcon.get(R.drawable.ic_qs_brightness_high_on)
                : ResourceIcon.get(R.drawable.ic_qs_brightness_high_off);
        state.state = highBrightness ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    protected void toggleState() {
        boolean highBrightness = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.HIGH_BRIGHTNESS_MODE, 0,
                UserHandle.USER_CURRENT) != 0;
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.HIGH_BRIGHTNESS_MODE, highBrightness ? 0 : 1,
                UserHandle.USER_CURRENT);
    }
}
