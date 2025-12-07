/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.notification;

import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

class NotificationListenerStats {
    /**
     * Maximum number of channels that a single NLS is allowed to create for <em>all</em> installed
     * packages. Should be much higher than reasonably necessary, but still prevent runaway channel
     * creation abuse.
     *
     * @see PreferencesHelper#NOTIFICATION_CHANNEL_COUNT_LIMIT
     */
    private static final int MAX_CHANNELS = 5_000;

    private static final String TAG_STATS = "nlsStats";
    private static final String TAG_NLS = "nls";
    private static final String ATT_UID = "uid";
    private static final String ATT_OWNED_CHANNEL_COUNT = "channelCount";

    private final Object mLock = new Object();

    // nlsUid => count of channels created by that (privileged) NLS.
    @GuardedBy("mLock")
    private final SparseIntArray mChannelsCreated = new SparseIntArray();

    private final int mMaxChannelsAllowed;

    NotificationListenerStats() {
        this(MAX_CHANNELS);
    }

    @VisibleForTesting
    NotificationListenerStats(int maxChannelsAllowed) {
        mMaxChannelsAllowed = maxChannelsAllowed;
    }

    boolean isAllowedToCreateChannel(ManagedServiceInfo nls) {
        synchronized (mLock) {
            int numCreated = mChannelsCreated.get(nls.uid);
            return numCreated < mMaxChannelsAllowed;
        }
    }

    void logCreatedChannels(ManagedServiceInfo nls, int increase) {
        synchronized (mLock) {
            int prevCreated = mChannelsCreated.get(nls.uid);
            mChannelsCreated.put(nls.uid, prevCreated + increase);
        }
    }

    void onPackageRemoved(int uid, String packageName) {
        // If the uninstalled package was an NLS, drop its stats.
        synchronized (mLock) {
            mChannelsCreated.delete(uid);
        }
    }

    static boolean isXmlTag(String tag) {
        return TAG_STATS.equals(tag);
    }

    void writeXml(TypedXmlSerializer out) throws IOException {
        out.startTag(null, TAG_STATS);
        synchronized (mLock) {
            for (int i = 0; i < mChannelsCreated.size(); i++) {
                out.startTag(null, TAG_NLS);
                out.attributeInt(null, ATT_UID, mChannelsCreated.keyAt(i));
                out.attributeInt(null, ATT_OWNED_CHANNEL_COUNT, mChannelsCreated.valueAt(i));
                out.endTag(null, TAG_NLS);
            }
        }
        out.endTag(null, TAG_STATS);
    }

    void readXml(TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_STATS.equals(tag)) return;

        synchronized (mLock) {
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                tag = parser.getName();
                if (type == XmlPullParser.END_TAG && TAG_STATS.equals(tag)) {
                    break;
                }
                if (type == XmlPullParser.START_TAG) {
                    if (TAG_NLS.equals(tag)) {
                        int uid = parser.getAttributeInt(null, ATT_UID, 0);
                        int channelCount = parser.getAttributeInt(null, ATT_OWNED_CHANNEL_COUNT, 0);
                        mChannelsCreated.put(uid, channelCount);
                    }
                }
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            for (int i = 0; i < mChannelsCreated.size(); i++) {
                pw.println(prefix + "NLS with uid " + mChannelsCreated.keyAt(i));
                pw.println(prefix + "  created channel count: " + mChannelsCreated.valueAt(i));
            }
        }
    }
}
