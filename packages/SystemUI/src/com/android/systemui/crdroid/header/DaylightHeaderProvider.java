/*
 *  Copyright (C) 2015 The OmniROM Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.android.internal.util.crdroid.Utils;

public class DaylightHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "DaylightHeaderProvider";
    private static final boolean DEBUG = false;

    private static class DaylightHeaderInfo {
        int mType = 0;
        int mHour = -1;
        int mDay = -1;
        /** 1-based month, as written in the header pack XML. */
        int mMonth = -1;
        String mImage;
    }

    private static final Comparator<DaylightHeaderInfo> BY_HOUR =
            Comparator.comparingInt(h -> h.mHour);

    // default in SystemUI
    private static final String HEADER_PACKAGE_DEFAULT = "com.android.systemui";

    private final Context mContext;
    private List<DaylightHeaderInfo> mHeadersList = new ArrayList<>();
    private Resources mRes;
    private String mPackageName;
    private String mHeaderName;
    private String mSettingHeaderPackage;
    private PendingIntent mAlarmIntent;
    private boolean mRandomMode;
    private boolean mLinearMode;
    private int mHeaderIndex;
    private int mAlarmIntervalMinutes = 0;
    private int mCurrentDrawableId;
    private Drawable mCurrentDrawable;

    public DaylightHeaderProvider(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "daylight";
    }

    @Override
    public void settingsChanged(Uri uri) {
        final String settingHeaderPackage = Settings.System.getStringForUser(
                mContext.getContentResolver(),
                Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK,
                UserHandle.USER_CURRENT);
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;

        if (!customHeader) {
            return;
        }
        stopAlarm();
        if (TextUtils.isEmpty(settingHeaderPackage)) {
            if (!HEADER_PACKAGE_DEFAULT.equals(mSettingHeaderPackage) || mRes == null) {
                loadDefaultHeaderPackage();
            }
        } else if (!settingHeaderPackage.equals(mSettingHeaderPackage) || mRes == null) {
            mSettingHeaderPackage = settingHeaderPackage;
            loadCustomHeaderPackage();
        }
        startAlarm();
    }

    @Override
    public void enableProvider() {
        settingsChanged(null);
    }

    @Override
    public void disableProvider() {
        stopAlarm();
    }

    private void stopAlarm() {
        if (mAlarmIntent != null) {
            final AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
            if (DEBUG) Log.i(TAG, "stop alarm");
            alarmManager.cancel(mAlarmIntent);
            mAlarmIntent.cancel();
            mAlarmIntent = null;
        }
    }

    private void startAlarm() {
        // TODO: actually this should find out the next needed alarm
        // instead of forcing it every interval
        final Calendar c = Calendar.getInstance();
        final AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);

        if (mAlarmIntent != null) {
            alarmManager.cancel(mAlarmIntent);
            mAlarmIntent.cancel();
        }

        // Package-scoped so only SystemUI's own (not exported) receiver gets it.
        Intent intent = new Intent(StatusBarHeaderMachine.STATUS_BAR_HEADER_UPDATE_ACTION)
                .setPackage(mContext.getPackageName());
        mAlarmIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        if (mAlarmIntervalMinutes == 0) {
            c.add(Calendar.HOUR_OF_DAY, 1);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
        }
        long alarmStart = c.getTimeInMillis();
        long interval = mAlarmIntervalMinutes == 0
                ? AlarmManager.INTERVAL_HOUR : mAlarmIntervalMinutes * 60L * 1000L;

        if (DEBUG) Log.i(TAG, "start alarm at " + new Date(alarmStart) + " with interval of " + interval);

        alarmManager.setInexactRepeating(AlarmManager.RTC, alarmStart, interval, mAlarmIntent);
    }

    private void loadCustomHeaderPackage() {
        if (DEBUG) Log.i(TAG, "Load header pack " + mSettingHeaderPackage);
        final int idx = mSettingHeaderPackage.indexOf('/');
        if (idx != -1) {
            mPackageName = mSettingHeaderPackage.substring(0, idx);
            mHeaderName = mSettingHeaderPackage.substring(idx + 1);
        } else {
            mPackageName = mSettingHeaderPackage;
            mHeaderName = null;
        }

        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load header pack " + mSettingHeaderPackage, e);
            mRes = null;
        }

        if (mRes == null) {
            Log.w(TAG, "Header pack loading failed - loading default");
            loadDefaultHeaderPackage();
        }
    }

    private void loadDefaultHeaderPackage() {
        if (DEBUG) Log.i(TAG, "Load default header pack");
        mPackageName = HEADER_PACKAGE_DEFAULT;
        mHeaderName = null;
        mSettingHeaderPackage = mPackageName;

        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load default header pack", e);
            mRes = null;
            mHeadersList = new ArrayList<>();
        }
    }

    private void loadHeaders() throws XmlPullParserException, IOException {
        mCurrentDrawableId = 0;
        mCurrentDrawable = null;
        mHeaderIndex = 0;

        final String configFile = (mHeaderName == null)
                ? "daylight_header.xml"
                : mHeaderName.substring(mHeaderName.lastIndexOf('.') + 1) + ".xml";
        if (DEBUG) Log.i(TAG, "Load header pack config " + configFile);

        final List<DaylightHeaderInfo> headers = new ArrayList<>();
        try (InputStream in = mRes.getAssets().open(configFile)) {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(in, "UTF-8");
            loadResourcesFromXmlParser(parser, headers);
        }
        mHeadersList = headers;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void loadResourcesFromXmlParser(XmlPullParser parser, List<DaylightHeaderInfo> headers)
            throws XmlPullParserException, IOException {
        mRandomMode = false;
        mLinearMode = false;
        mAlarmIntervalMinutes = 0;

        // Always advance the parser in the loop header; the old do/while used `continue`
        // without calling next(), which looped forever on mixed random/day packs.
        for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT;
                eventType = parser.next()) {
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }
            final String name = parser.getName();

            if (name.equalsIgnoreCase("day_header")) {
                if (mRandomMode) continue;
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 0;
                headerInfo.mDay = parseInt(parser.getAttributeValue(null, "day"), -1);
                headerInfo.mMonth = parseInt(parser.getAttributeValue(null, "month"), -1);
                headerInfo.mHour = parseInt(parser.getAttributeValue(null, "hour"), -1);
                headerInfo.mImage = parser.getAttributeValue(null, "image");
                if (headerInfo.mImage != null && headerInfo.mDay != -1 && headerInfo.mMonth != -1) {
                    headers.add(headerInfo);
                }
            } else if (name.equalsIgnoreCase("hour_header")) {
                if (mRandomMode) continue;
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 1;
                headerInfo.mHour = parseInt(parser.getAttributeValue(null, "hour"), -1);
                headerInfo.mImage = parser.getAttributeValue(null, "image");
                if (headerInfo.mImage != null && headerInfo.mHour != -1) {
                    headers.add(headerInfo);
                }
            } else if (name.equalsIgnoreCase("random_header")
                    || name.equalsIgnoreCase("list_header")) {
                mRandomMode = name.equalsIgnoreCase("random_header");
                mLinearMode = name.equalsIgnoreCase("list_header");
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 2;
                headerInfo.mImage = parser.getAttributeValue(null, "image");
                if (headerInfo.mImage != null) {
                    headers.add(headerInfo);
                }
            } else if (name.equalsIgnoreCase("change_interval")) {
                mAlarmIntervalMinutes = Math.max(0,
                        parseInt(parser.getAttributeValue(null, "minutes"), 0));
                if (DEBUG) Log.i(TAG, "Set change interval to " + mAlarmIntervalMinutes);
            }
        }

        if (mRandomMode || mLinearMode) {
            if (mRandomMode) {
                Collections.shuffle(headers);
            }
        } else {
            mAlarmIntervalMinutes = 0;
            // getMatchingHeader() walks the list in hour order.
            headers.sort(BY_HOUR);
        }
        if (DEBUG) Log.i(TAG, "loaded " + headers.size() + " headers");
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        if (mPackageName == null || !Utils.isPackageInstalled(mContext, mPackageName)) {
            Log.w(TAG, "Header pack no longer available - loading default " + mPackageName);
            loadDefaultHeaderPackage();
        }
        if (mRes == null || mHeadersList.isEmpty()) {
            return null;
        }
        try {
            if (mRandomMode || mLinearMode) {
                if (mHeaderIndex >= mHeadersList.size()) {
                    mHeaderIndex = 0;
                }
                DaylightHeaderInfo header = mHeadersList.get(mHeaderIndex);
                mHeaderIndex++;
                if (mHeaderIndex == mHeadersList.size()) {
                    if (mRandomMode) {
                        if (DEBUG) Log.i(TAG, "Shuffle random mode header pack");
                        Collections.shuffle(mHeadersList);
                    }
                    mHeaderIndex = 0;
                }
                if (DEBUG) Log.i(TAG, "Current header " + header.mImage);
                return getDrawable(header.mImage);
            }

            // first check day headers
            List<DaylightHeaderInfo> todayHeaders = getHeaders(0, now);
            if (!todayHeaders.isEmpty()) {
                DaylightHeaderInfo matching = getMatchingHeader(now, todayHeaders);
                // no day header with an hour, so just use the first one
                if (matching == null) matching = todayHeaders.get(0);
                if (DEBUG) Log.i(TAG, "Current day header " + matching.mImage);
                return getDrawable(matching.mImage);
            }

            List<DaylightHeaderInfo> hourHeaders = getHeaders(1, now);
            if (!hourHeaders.isEmpty()) {
                DaylightHeaderInfo matching = getMatchingHeader(now, hourHeaders);
                if (matching != null) {
                    if (DEBUG) Log.i(TAG, "Current hour header " + matching.mImage);
                    return getDrawable(matching.mImage);
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "No drawable found for " + now.getTime() + " in " + mPackageName);
        }
        return null;
    }

    /** Returns the cached drawable when the image did not change, so observers skip redraws. */
    private Drawable getDrawable(String image) {
        final int drawableId = mRes.getIdentifier(image, "drawable", mPackageName);
        if (drawableId == 0) {
            return null;
        }
        if (mCurrentDrawable == null || mCurrentDrawableId != drawableId) {
            mCurrentDrawable = mRes.getDrawable(drawableId, null);
            mCurrentDrawableId = drawableId;
        }
        return mCurrentDrawable;
    }

    private static boolean isItToday(final Calendar now, DaylightHeaderInfo headerInfo) {
        return now.get(Calendar.MONTH) + 1 == headerInfo.mMonth
                && now.get(Calendar.DAY_OF_MONTH) == headerInfo.mDay;
    }

    /** Headers of [type]; for day headers only today's. Keeps the list's hour order. */
    private List<DaylightHeaderInfo> getHeaders(int type, final Calendar now) {
        List<DaylightHeaderInfo> result = new ArrayList<>();
        for (DaylightHeaderInfo header : mHeadersList) {
            if (header.mType == type && (type != 0 || isItToday(now, header))) {
                result.add(header);
            }
        }
        return result;
    }

    /**
     * The header whose hour most recently started; before the first hour of the day it
     * wraps to the last one. Returns null when no header in the list has an hour.
     */
    private static DaylightHeaderInfo getMatchingHeader(final Calendar now,
            List<DaylightHeaderInfo> headerList) {
        final int hour = now.get(Calendar.HOUR_OF_DAY);
        DaylightHeaderInfo first = null;
        DaylightHeaderInfo last = null;
        DaylightHeaderInfo match = null;
        for (DaylightHeaderInfo header : headerList) { // sorted by hour
            if (header.mHour == -1) {
                continue;
            }
            if (first == null) first = header;
            last = header;
            if (header.mHour <= hour) {
                match = header;
            }
        }
        if (first == null) {
            return null;
        }
        return match != null ? match : last;
    }
}
