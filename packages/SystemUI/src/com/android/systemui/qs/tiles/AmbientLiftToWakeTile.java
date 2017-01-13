/*
 * Copyright (C) 2017 crDroid Android Project
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

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Prefs;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;

/** Quick settings tile: Ambient and LiftToWake mode **/
public class AmbientLiftToWakeTile extends QSTileImpl<BooleanState> {

    private AmbientDisplayConfiguration mAmbientConfig;
    private boolean isAmbientAvailable;
    private boolean isPickupAvailable;
    private boolean isSomethingEnabled() {
        boolean isAmbient = false;
        boolean isDozePickUp = false;
        if (isAmbientAvailable) {
            isAmbient = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ENABLED, 1, UserHandle.USER_CURRENT) == 1;
        }
        if (isPickupAvailable) {
            isDozePickUp = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_PULSE_ON_PICK_UP, 1, UserHandle.USER_CURRENT) == 1;
        }
        if (isAmbient || isDozePickUp) {
            return true;
        }
        return false;
    }

    public AmbientLiftToWakeTile(QSHost host) {
        super(host);
        mAmbientConfig = new AmbientDisplayConfiguration(mContext);
        isAmbientAvailable =  mAmbientConfig.pulseOnNotificationAvailable() ? true : false;
        isPickupAvailable = mAmbientConfig.pulseOnPickupAvailable() ? true : false;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (isSomethingEnabled()) {
            getUserDozeValue();
            getUserDozePickUpValue();
            setDisabled();
        } else {
            setUserValues();
        }
    }

    @Override
    public boolean isAvailable() {
        //do not show the tile if no doze features available
        return isAmbientAvailable;
    }

    private void setDisabled() {
        if (isAmbientAvailable) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ENABLED, 0, UserHandle.USER_CURRENT);
        }
        if (isPickupAvailable) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_PULSE_ON_PICK_UP, 0, UserHandle.USER_CURRENT);
        }
    }

    private void setUserValues() {
        if (isAmbientAvailable) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ENABLED, Prefs.getInt(mContext, Prefs.Key.QS_AMBIENT_DOZE, 1),
                    UserHandle.USER_CURRENT);
        }
        if (isPickupAvailable) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_PULSE_ON_PICK_UP, Prefs.getInt(mContext, Prefs.Key.QS_AMBIENT_PICKUP, 1),
                    UserHandle.USER_CURRENT);
        }
    }

    private void getUserDozeValue() {
        if (isAmbientAvailable) {
            int getUserDozeValue = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_ENABLED, 1, UserHandle.USER_CURRENT);
            Prefs.putInt(mContext, Prefs.Key.QS_AMBIENT_DOZE, getUserDozeValue);
        }
    }

    private void getUserDozePickUpValue() {
        if (isPickupAvailable) {
            int getUserDozePickUpValue =  Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.DOZE_PULSE_ON_PICK_UP, 1, UserHandle.USER_CURRENT);
            Prefs.putInt(mContext, Prefs.Key.QS_AMBIENT_PICKUP, getUserDozePickUpValue);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_doze_notifications_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (isSomethingEnabled()) {
            getUserDozeValue();
            getUserDozePickUpValue();
            state.label = mContext.getString(R.string.quick_settings_doze_notifications_label);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_ambient_on);
            state.contentDescription =  mContext.getString(
                    R.string.quick_settings_doze_notifications_label);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.label = mContext.getString(R.string.quick_settings_doze_notifications_label);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_ambient_off);
            state.contentDescription =  mContext.getString(
                    R.string.quick_settings_doze_notifications_label);
             state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    };

    @Override
    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            if (isAmbientAvailable) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_ENABLED),
                    false, mObserver, UserHandle.USER_ALL);
            }
            if (isPickupAvailable) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_PULSE_ON_PICK_UP),
                    false, mObserver, UserHandle.USER_ALL);
            }
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }
}
