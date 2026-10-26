/*
 *  Copyright (C) 2017 The OmniROM Project
 *  Copyright (C) 2026 crDroid Android Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.crdroid.header;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.Objects;

import com.android.internal.util.crdroid.Utils;

public class StaticHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "StaticHeaderProvider";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private Resources mRes;
    private String mImage;
    private String mPackageName;
    /** Cached so repeated getCurrent() calls return the same instance (no redundant redraws). */
    private Drawable mDrawable;

    public StaticHeaderProvider(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "static";
    }

    @Override
    public void settingsChanged(Uri uri) {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        final String imageUrl = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE,
                UserHandle.USER_CURRENT);

        if (!customHeader || imageUrl == null) {
            return;
        }
        final int idx = imageUrl.indexOf('/');
        if (idx <= 0 || idx == imageUrl.length() - 1) {
            Log.w(TAG, "Invalid header image setting: " + imageUrl);
            return;
        }
        final String packageName = imageUrl.substring(0, idx);
        final String image = imageUrl.substring(idx + 1);
        if (mRes != null && packageName.equals(mPackageName) && Objects.equals(image, mImage)) {
            return; // unchanged
        }
        mPackageName = packageName;
        mImage = image;
        mDrawable = null;
        loadHeaderResources();
    }

    @Override
    public void enableProvider() {
        settingsChanged(null);
    }

    @Override
    public void disableProvider() {
        // Release the bitmap while another provider (or none) is active.
        mDrawable = null;
        mRes = null;
    }

    private void loadHeaderResources() {
        if (DEBUG) Log.i(TAG, "Load header image " + mPackageName + "/" + mImage);
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load header pack " + mPackageName, e);
            mRes = null;
        }
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        if (mRes == null || mPackageName == null) {
            return null;
        }
        if (!Utils.isPackageInstalled(mContext, mPackageName)) {
            Log.w(TAG, "Header pack image " + mImage + " no longer available");
            mRes = null;
            mDrawable = null;
            return null;
        }
        if (mDrawable != null) {
            return mDrawable;
        }
        final int resId = mRes.getIdentifier(mImage, "drawable", mPackageName);
        if (resId == 0) {
            Log.w(TAG, "No drawable " + mImage + " in " + mPackageName);
            return null;
        }
        try {
            mDrawable = mRes.getDrawable(resId, null);
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "Failed to load " + mImage + " from " + mPackageName, e);
        }
        return mDrawable;
    }
}
