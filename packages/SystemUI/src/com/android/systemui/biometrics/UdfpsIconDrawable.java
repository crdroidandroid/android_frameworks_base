/*
 * Copyright (C) 2024-2025 crDroid Android Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.android.internal.util.crdroid.Utils;
import com.android.systemui.res.R;

/**
 * Abstract base class for drawable displayed when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsIconDrawable extends Drawable {

    private final String udfpsResourcesPackage = "com.crdroid.udfps.icons";

    @NonNull private final Context mContext;
    private Drawable mUdfpsDrawable;
    private Resources udfpsRes;
    private String[] mUdfpsIcons;

    private final ContentObserver mContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            int selectedIcon = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.UDFPS_ICON, 0, UserHandle.USER_CURRENT);
            if (mUdfpsDrawable != null) {
                mUdfpsDrawable.setCallback(null);  // Clear previous drawable callback to avoid leaks
                mUdfpsDrawable = null;
            }
            mUdfpsDrawable = selectedIcon == 0 ? null
                    : loadDrawable(udfpsRes, mUdfpsIcons[selectedIcon]);
            invalidateSelf();
        }
    };

    public UdfpsIconDrawable(@NonNull Context context) {
        mContext = context;
        init();
    }

    private void init() {
        if (Utils.isPackageInstalled(mContext, udfpsResourcesPackage)) {
            try {
                PackageManager pm = mContext.getPackageManager();
                udfpsRes = pm.getResourcesForApplication(udfpsResourcesPackage);
                int res = udfpsRes.getIdentifier("udfps_icons", "array", udfpsResourcesPackage);
                mUdfpsIcons = udfpsRes.getStringArray(res);
                Uri uri = Settings.System.getUriFor(Settings.System.UDFPS_ICON);
                mContext.getContentResolver().registerContentObserver(
                        uri, false, mContentObserver, UserHandle.USER_CURRENT);
                // Load initial value
                int selectedIcon = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.UDFPS_ICON, 0, UserHandle.USER_CURRENT);
                mUdfpsDrawable = selectedIcon == 0 ? null
                        : loadDrawable(udfpsRes, mUdfpsIcons[selectedIcon]);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e("UdfpsIconDrawable", "UDFPS package not found", e);
            }
        }
    }

    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setAlpha(alpha);
        }
        invalidateSelf();
    }

    Drawable getUdfpsDrawable() {
        return mUdfpsDrawable;
    }

    private Drawable loadDrawable(Resources res, String resName) {
        if (res == null || resName == null) {
            return null;
        }
        int resId = res.getIdentifier(resName, "drawable", udfpsResourcesPackage);
        return resId != 0 ? ResourcesCompat.getDrawable(res, resId, null) : null;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setBounds(left , top, right, bottom);
        }
        invalidateSelf();
    }

    @Override
    public void setBounds(Rect bounds) {
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setBounds(bounds);
        }
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (mUdfpsDrawable != null) {
            mUdfpsDrawable.setBounds(bounds);
        }
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Do not set color filter
    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
