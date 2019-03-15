/*
 * Copyright (C) 2017 Paranoid Android
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

package com.android.internal.icons;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.icons.RecentPanelIcons;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class IconsHandler {

    private static final String TAG = "IconsHandler";

    private Map<String, IconPackInfo> mIconPacks = new HashMap<>();
    private Map<String, String> mAppFilterDrawables = new HashMap<>();
    private List<Bitmap> mBackImages = new ArrayList<>();
    private List<String> mDrawables = new ArrayList<>();

    private Bitmap mFrontImage;
    private Bitmap mMaskImage;

    private Resources mCurrentIconPackRes;
    private Resources mOriginalIconPackRes;
    private String mIconPackPackageName = "";

    private Context mContext;
    private PackageManager mPackageManager;

    private float mFactor = 1.0f;

    private int mIconSizeId;

    private float mScaleFactor;

    private IconNormalizer mIconNormalizer;
    private ShadowGenerator mShadowGenerator;

    public IconsHandler(Context context, int iconSizeId, float scaleFactor) {
        mContext = context;
        if (iconSizeId == -1) iconSizeId = com.android.internal.R.dimen.app_icon_size;
        mIconSizeId = iconSizeId;
        mScaleFactor = scaleFactor;
        mPackageManager = context.getPackageManager();
        mIconNormalizer = new IconNormalizer(context, iconSizeId, scaleFactor);
        mShadowGenerator = new ShadowGenerator(context, iconSizeId, scaleFactor);
    }

    public void setScaleFactor(float scaleFactor) {
        mIconNormalizer = new IconNormalizer(mContext, mIconSizeId, scaleFactor);
        mShadowGenerator = new ShadowGenerator(mContext, mIconSizeId, scaleFactor);
        updatePrefs(mIconPackPackageName, true);
    }

    private void loadIconPack(String packageName, boolean fallback) {
        mIconPackPackageName = packageName;
        if (!fallback) {
            mAppFilterDrawables.clear();
            mBackImages.clear();
        } else {
            mDrawables.clear();
        }
        mFactor = 1.0f;

        XmlPullParser xpp = null;

        try {
            mOriginalIconPackRes = mPackageManager.getResourcesForApplication(mIconPackPackageName);
            mCurrentIconPackRes = mOriginalIconPackRes;
            int appfilterid = mOriginalIconPackRes.getIdentifier("appfilter", "xml", mIconPackPackageName);
            if (appfilterid > 0) {
                xpp = mOriginalIconPackRes.getXml(appfilterid);
            }

            if (xpp != null) {
                int eventType = xpp.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        if (!fallback & xpp.getName().equals("iconback")) {
                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                if (xpp.getAttributeName(i).startsWith("img")) {
                                    String drawableName = xpp.getAttributeValue(i);
                                    Bitmap iconback = loadBitmap(drawableName);
                                    if (iconback != null) {
                                        mBackImages.add(iconback);
                                    }
                                }
                            }
                        } else if (!fallback && xpp.getName().equals("iconmask")) {
                            if (xpp.getAttributeCount() > 0 && xpp.getAttributeName(0).equals("img1")) {
                                String drawableName = xpp.getAttributeValue(0);
                                mMaskImage = loadBitmap(drawableName);
                            }
                        } else if (!fallback && xpp.getName().equals("iconupon")) {
                            if (xpp.getAttributeCount() > 0 && xpp.getAttributeName(0).equals("img1")) {
                                String drawableName = xpp.getAttributeValue(0);
                                mFrontImage = loadBitmap(drawableName);
                            }
                        } else if (!fallback && xpp.getName().equals("scale")) {
                            if (xpp.getAttributeCount() > 0 && xpp.getAttributeName(0).equals("factor")) {
                                mFactor = Float.valueOf(xpp.getAttributeValue(0));
                            }
                        }
                        if (xpp.getName().equals("item")) {
                            String componentName = null;
                            String drawableName = null;

                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                if (xpp.getAttributeName(i).equals("component")) {
                                    componentName = xpp.getAttributeValue(i);
                                } else if (xpp.getAttributeName(i).equals("drawable")) {
                                    drawableName = xpp.getAttributeValue(i);
                                }
                            }
                            if (fallback && getIdentifier(packageName, drawableName, true) > 0
                                    && !mDrawables.contains(drawableName)) {
                                mDrawables.add(drawableName);
                            }
                            if (!fallback && componentName != null && drawableName != null &&
                                    !mAppFilterDrawables.containsKey(componentName)) {
                                mAppFilterDrawables.put(componentName, drawableName);
                            }
                        }
                    }
                    eventType = xpp.next();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing appfilter.xml " + e);
        }
    }

    public BitmapDrawable getBitmap(Context context, Drawable drawable) {
        if (drawable != null && drawable instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) drawable).getBitmap();
            return new BitmapDrawable(context.getResources(), RecentPanelIcons.createIconBitmap(bm, context, mIconNormalizer, mShadowGenerator));
        }
        return null;
    }

    public Drawable getIconFromHandler(Context context, ActivityInfo info) {
        final String packageName = info.applicationInfo.packageName;
        ComponentName defaultName = getDefaultName(packageName);
        ComponentName name = null;
        try{
            name = new ComponentName(packageName, info.name);
        } catch (Exception e) {}

        return getBitmap(packageName, name, defaultName, context);
    }

    public Drawable getIconFromHandler(Context context, LauncherActivityInfo info) {
        final String packageName = info.getApplicationInfo().packageName;
        ComponentName defaultName = getDefaultName(packageName);
        ComponentName name = info.getComponentName();

        return getBitmap(packageName, name, defaultName, context);
    }

    public Drawable getIconFromHandler(Context context, ApplicationInfo info, String packageName) {
        ComponentName defaultName = getDefaultName(packageName);
        ComponentName name = null;
        try{
            name = new ComponentName(packageName, info.name);
        } catch (Exception e) {}

        return getBitmap(packageName, name, defaultName, context);
    }

    private BitmapDrawable getBitmap(String packageName, ComponentName name, ComponentName defaultName, Context context) {
        Bitmap bm = getDrawableIconForPackage(packageName, name, defaultName);
        if (bm == null) {
            return null;
        }
        return new BitmapDrawable(context.getResources(), RecentPanelIcons.createIconBitmap(bm, context, mIconNormalizer, mShadowGenerator));
    }

    public ComponentName getDefaultName(String packageName) {
        Intent launchIntent = mPackageManager.getLaunchIntentForPackage(packageName);
        ComponentName defaultName = null;
        if (launchIntent != null) {
            defaultName = launchIntent.getComponent();
        }
        return defaultName;
    }

    public List<String> getAllDrawables(final String packageName) {
        loadIconPack(packageName, true);
        Collections.sort(mDrawables, new Comparator<String>() {
            @Override
            public int compare(String drawable, String drawable2) {
                return drawable.compareToIgnoreCase(drawable2);
            }
        });

        return mDrawables;
    }

    public boolean isDefaultIconPack() {
        return mIconPackPackageName.equalsIgnoreCase("");
    }

    private int getIdentifier(String packageName, String drawableName, boolean currentIconPack) {
        if (drawableName == null) {
            return 0;
        }
        if (packageName == null) {
            packageName = mIconPackPackageName;
        }
        return (!currentIconPack ? mOriginalIconPackRes : mCurrentIconPackRes).getIdentifier(
                drawableName, "drawable", packageName);
    }

    public Drawable loadDrawable(String packageName, String drawableName, boolean currentIconPack) {
        if (packageName == null) {
            packageName = mIconPackPackageName;
        }
        int id = getIdentifier(packageName, drawableName, currentIconPack);
        if (id > 0) {
            return (!currentIconPack ? mOriginalIconPackRes : mCurrentIconPackRes).getDrawable(id, mContext.getTheme());
        }
        return null;
    }

    private Bitmap loadBitmap(String drawableName) {
        Drawable bitmap = loadDrawable(null, drawableName, true);
        if (bitmap != null && bitmap instanceof BitmapDrawable) {
            return ((BitmapDrawable) bitmap).getBitmap();
        }
        return null;
    }

    private Bitmap getDefaultAppDrawable(ComponentName componentName) {
        Drawable drawable = null;
        try {
            drawable = mPackageManager.getApplicationIcon(mPackageManager.getApplicationInfo(
                    componentName.getPackageName(), 0));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Unable to find component " + componentName.toString() + e);
        }
        if (drawable == null) {
            return null;
        }

        return generateBitmap(componentName, RecentPanelIcons.createIconBitmap(drawable, mContext, mIconNormalizer, mShadowGenerator));
    }

    public Bitmap getDrawableIconForPackage(String packageName, ComponentName componentName, ComponentName defaultName) {
        String drawableName = null;
        if (componentName != null) {
            drawableName = mAppFilterDrawables.get(componentName.toString());
        }
        if (drawableName == null && defaultName != null) {
            drawableName = mAppFilterDrawables.get(defaultName.toString());
        }
        if (drawableName == null && packageName != null) {
            drawableName = mAppFilterDrawables.get(packageName.toString());
        }
        Drawable drawable = loadDrawable(null, drawableName, false);
        if (drawable != null && drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            return bitmap;
        }

        if (componentName == null) return null;

        return getDefaultAppDrawable(componentName);
    }

    private Bitmap generateBitmap(ComponentName componentName, Bitmap defaultBitmap) {
        Drawable d = new BitmapDrawable(mContext.getResources(), defaultBitmap);
        if (mBackImages.isEmpty()) {
            return RecentPanelIcons.createBadgedIconBitmap(mIconNormalizer, mShadowGenerator, d, Process.myUserHandle(),
                    mContext, Build.VERSION.SDK_INT, true, isDefaultIconPack());
        }

        Bitmap wrapped = RecentPanelIcons.createBadgedIconBitmap(mIconNormalizer, mShadowGenerator, d, Process.myUserHandle(),
                mContext, Build.VERSION.SDK_INT, false, isDefaultIconPack());

        Random random = new Random();
        int id = random.nextInt(mBackImages.size());
        Bitmap backImage = mBackImages.get(id);
        int w = backImage.getWidth();
        int h = backImage.getHeight();

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(backImage, 0, 0, null);

        if (!mIconNormalizer.isTransparentBitmap(backImage)) {
            mFactor = 0.7f;
        }
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(wrapped,
                (int) (w * mFactor), (int) (h * mFactor), false);

        Bitmap mutableMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(mutableMask);
        Bitmap targetBitmap = mMaskImage == null ? mutableMask : mMaskImage;
        maskCanvas.drawBitmap(targetBitmap, 0, 0, new Paint());

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST));
        canvas.drawBitmap(scaledBitmap, (w - scaledBitmap.getWidth()) / 2,
                (h - scaledBitmap.getHeight()) / 2, null);
        canvas.drawBitmap(mutableMask, 0, 0, paint);

        if (mFrontImage != null) {
            canvas.drawBitmap(mFrontImage, 0, 0, null);
        }
        return result;
    }

    public void updatePrefs(String iconPack) {
        updatePrefs(iconPack, false);
    }

    public void updatePrefs(String iconPack, boolean force) {
        if (iconPack == null || (!force && iconPack.equals(mIconPackPackageName))) {
            return;
        }
        mIconPackPackageName = iconPack;
        if (!TextUtils.isEmpty(iconPack) || TextUtils.isEmpty(mIconPackPackageName)) {
            refresh();
        }
        if (!TextUtils.isEmpty(mIconPackPackageName)) {
            loadIconPack(iconPack, false);
        }
    }

    public void refresh() {
        mAppFilterDrawables.clear();
        mBackImages.clear();
        mDrawables.clear();
        mFrontImage = null;
        mMaskImage = null;
        mCurrentIconPackRes = null;
        mOriginalIconPackRes = null;
    }

    public void onDpiChanged(Context ctx) {
        mContext = ctx;
        mIconNormalizer = new IconNormalizer(ctx, mIconSizeId, mScaleFactor);
        mShadowGenerator = new ShadowGenerator(ctx, mIconSizeId, mScaleFactor);
        updatePrefs(mIconPackPackageName, true);
    }

    public List<String> getMatchingDrawables(String packageName) {
        List<String> matchingDrawables = new ArrayList<>();
        ApplicationInfo info = null;
        try {
            info = mPackageManager.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
        String packageLabel = (info != null ? mPackageManager.getApplicationLabel(info).toString()
                : packageName).replaceAll("[^a-zA-Z]", "").toLowerCase().trim();
        for (String drawable : mDrawables) {
            if (drawable == null) continue;
            String filteredDrawable = drawable.replaceAll("[^a-zA-Z]", "").toLowerCase().trim();
            if (filteredDrawable.length() > 2 && (packageLabel.contains(filteredDrawable)
                    || filteredDrawable.contains(packageLabel))) {
                matchingDrawables.add(drawable);
            }
        }
        return matchingDrawables;
    }

    public Pair<List<String>, List<String>> getAllIconPacks() {
        //be sure to update the icon packs list
        loadAvailableIconPacks();

        List<String> iconPackNames = new ArrayList<>();
        List<String> iconPackLabels = new ArrayList<>();
        List<IconPackInfo> iconPacks = new ArrayList<IconPackInfo>(mIconPacks.values());
        Collections.sort(iconPacks, new Comparator<IconPackInfo>() {
            @Override
            public int compare(IconPackInfo info, IconPackInfo info2) {
                return info.label.toString().compareToIgnoreCase(info2.label.toString());
            }
        });
        for (IconPackInfo info : iconPacks) {
            iconPackNames.add(info.packageName);
            iconPackLabels.add(info.label.toString());
        }
        return new Pair<>(iconPackNames, iconPackLabels);
    }

    private void loadAvailableIconPacks() {
        Map<String, IconPackInfo> iconPacks = new HashMap<>();
        List<ResolveInfo> list;
        list = mPackageManager.queryIntentActivities(new Intent("com.novalauncher.THEME"), 0);
        list.addAll(mPackageManager.queryIntentActivities(new Intent("org.adw.launcher.icons.ACTION_PICK_ICON"), 0));
        list.addAll(mPackageManager.queryIntentActivities(new Intent("com.dlto.atom.launcher.THEME"), 0));
        list.addAll(mPackageManager.queryIntentActivities(new Intent("android.intent.action.MAIN").addCategory("com.anddoes.launcher.THEME"), 0));
        for (ResolveInfo info : list) {
            mIconPacks.put(info.activityInfo.packageName, new IconPackInfo(info, mPackageManager));
        }
    }

    public static class IconPackInfo {
        public String packageName;
        public CharSequence label;
        public Drawable icon;

        public IconPackInfo(ResolveInfo r, PackageManager packageManager) {
            packageName = r.activityInfo.packageName;
            icon = r.loadIcon(packageManager);
            label = r.loadLabel(packageManager);
        }

        public IconPackInfo(String label, Drawable icon, String packageName) {
            this.label = label;
            this.icon = icon;
            this.packageName = packageName;
        }
    }
}
