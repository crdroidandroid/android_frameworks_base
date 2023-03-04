/*
 * Copyright (C) 2022 FlamingoOS Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class to hold package level information about an
 * application for app lock.
 *
 * @hide
 */
public final class AppLockData implements Parcelable {

    public static final Parcelable.Creator<AppLockData> CREATOR =
            new Parcelable.Creator<AppLockData>() {

        @Override
        public AppLockData createFromParcel(Parcel in) {
            return new AppLockData(in);
        }

        @Override
        public AppLockData[] newArray(int size) {
            return new AppLockData[size];
        }
    };

    private final String mPackageName;
    private final boolean mShouldProtectApp;
    private final boolean mShouldRedactNotification;
    private final boolean mHideFromLauncher;

    /** @hide */
    public AppLockData(
        @NonNull final String packageName,
        final boolean shouldProtectApp,
        final boolean shouldRedactNotification,
        final boolean hideFromLauncher
    ) {
        mPackageName = packageName;
        mShouldProtectApp = shouldProtectApp;
        mShouldRedactNotification = shouldRedactNotification;
        mHideFromLauncher = hideFromLauncher;
    }

    private AppLockData(final Parcel in) {
        mPackageName = in.readString();
        mShouldProtectApp = in.readBoolean();
        mShouldRedactNotification = in.readBoolean();
        mHideFromLauncher = in.readBoolean();
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    public boolean getShouldProtectApp() {
        return mShouldProtectApp;
    }

    public boolean getShouldRedactNotification() {
        return mShouldRedactNotification;
    }

    public boolean getHideFromLauncher() {
        return mHideFromLauncher;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel parcel, final int flags) {
        parcel.writeString(mPackageName);
        parcel.writeBoolean(mShouldProtectApp);
        parcel.writeBoolean(mShouldRedactNotification);
        parcel.writeBoolean(mHideFromLauncher);
    }

    @Override
    @NonNull
    public String toString() {
        return "AppLockData[ packageName = " + mPackageName +
            ", shouldProtectApp = " + mShouldProtectApp +
            ", shouldRedactNotification = " + mShouldRedactNotification +
            ", hideFromLauncher = " + mHideFromLauncher + " ]";
    }
}
