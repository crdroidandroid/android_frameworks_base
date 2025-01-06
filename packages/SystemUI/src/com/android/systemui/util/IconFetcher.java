/**
 * Copyright (c) 2025, The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/** A class helping to fetch different versions of icons @LineageExtension */
public class IconFetcher {

    /** A class which stores wether icon is adaptive and icon itself. */
    public class AdaptiveDrawableResult {
        public boolean isAdaptive;
        public Drawable drawable;

        public AdaptiveDrawableResult(boolean isAdaptive, Drawable drawable) {
            this.isAdaptive = isAdaptive;
            this.drawable = drawable;
        }
    }

    private final Context mContext;

    public IconFetcher(Context context) {
        mContext = context;
    }

    /**
     * Gets a standard package icon
     *
     * @param packageName name of package for which icon would be fetched
     */
    public Drawable getPackageIcon(String packageName) {
        PackageManager packageManager = mContext.getPackageManager();
        try {
            return packageManager.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return mContext.getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    /**
     * Returns a monotonic version of the app icon as a Drawable. The foreground of adaptive icons
     * is extracted and tinted, while non-adaptive icons are directly tinted.
     *
     * @param packageName The package name of the app whose icon is to be fetched.
     * @param tintColor The color to use for the monotonic tint.
     * @return A monotonic Drawable of the app icon or standard app icon within
     *     AdaptiveDrawableResult
     */
    public AdaptiveDrawableResult getMonotonicPackageIcon(String packageName) {
        try {
            PackageManager packageManager = mContext.getPackageManager();
            Drawable icon = packageManager.getApplicationIcon(packageName);

            if (icon instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable adaptiveIcon = (AdaptiveIconDrawable) icon;

                Drawable foreground = adaptiveIcon.getForeground();

                return new AdaptiveDrawableResult(true, icon);
            } else {
                return new AdaptiveDrawableResult(false, icon);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            Drawable defaultIcon = mContext.getDrawable(android.R.drawable.sym_def_app_icon);
            // The icon is not adaptive by default
            return new AdaptiveDrawableResult(false, defaultIcon);
        }
    }
}
