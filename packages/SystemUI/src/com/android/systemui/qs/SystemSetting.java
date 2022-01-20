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

package com.android.systemui.qs;

import android.app.ActivityManager;
import android.database.ContentObserver;
import android.os.Handler;

import com.android.systemui.statusbar.policy.Listenable;
import com.android.systemui.util.settings.SystemSettings;

/** Helper for managing a system setting. **/
public abstract class SystemSetting extends ContentObserver implements Listenable {
    private final SystemSettings mSystemSettings;
    private final String mSettingName;
    private final int mDefaultValue;

    private boolean mListening;
    private int mUserId;
    private int mObservedValue;

    protected abstract void handleValueChanged(int value, boolean observedChange);

    public SystemSetting(SystemSettings systemSettings, Handler handler, String settingName,
            int userId) {
        this(systemSettings, handler, settingName, userId, 0);
    }

    public SystemSetting(SystemSettings systemSettings, Handler handler, String settingName) {
        this(systemSettings, handler, settingName, ActivityManager.getCurrentUser());
    }

    public SystemSetting(SystemSettings systemSettings, Handler handler, String settingName,
            int userId, int defaultValue) {
        super(handler);
        mSystemSettings = systemSettings;
        mSettingName = settingName;
        mObservedValue = mDefaultValue = defaultValue;
        mUserId = userId;
    }

    public int getValue() {
        return mSystemSettings.getIntForUser(mSettingName, mDefaultValue, mUserId);
    }

    public void setValue(int value) {
        mSystemSettings.putIntForUser(mSettingName, value, mUserId);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) return;
        mListening = listening;
        if (listening) {
            mObservedValue = getValue();
            mSystemSettings.registerContentObserverForUser(
                    mSystemSettings.getUriFor(mSettingName), false, this, mUserId);
        } else {
            mSystemSettings.unregisterContentObserver(this);
            mObservedValue = mDefaultValue;
        }
    }

    @Override
    public void onChange(boolean selfChange) {
        final int value = getValue();
        handleValueChanged(value, value != mObservedValue);
        mObservedValue = value;
    }

    public void setUserId(int userId) {
        mUserId = userId;
        if (mListening) {
            setListening(false);
            setListening(true);
        }
    }

    public int getCurrentUser() {
        return mUserId;
    }

    public String getKey() {
        return mSettingName;
    }

    public boolean isListening() {
        return mListening;
    }
}
