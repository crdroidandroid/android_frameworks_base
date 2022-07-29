/*
 * Copyright (C) 2022 Project Kaleidoscope
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

package com.android.systemui.statusbar.phone;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.StatusBarIconView;

import java.util.ArrayList;

public abstract class LyricViewController implements
    DarkIconDispatcher.DarkReceiver,
    NotificationListener.NotificationHandler {

    private static final String EXTRA_TICKER_ICON = "ticker_icon";
    private static final String EXTRA_TICKER_ICON_SWITCH = "ticker_icon_switch";

    private static final int HIDE_LYRIC_DELAY = 1200;

    private final Context mContext;
    private final ImageSwitcher mIconSwitcher;
    private final TextSwitcher mTextSwitcher;
    private final View mLyricContainer;

    private final ContrastColorUtil mNotificationColorUtil;

    private boolean mEnabled;
    private boolean mStarted;

    private String mCurrentNotificationPackage = null;
    private int mCurrentNotificationId;

    private ColorStateList mTintColorStateList;

    public LyricViewController(Context context, View statusBar) {
        mContext = context;
        mLyricContainer = statusBar.findViewById(R.id.lyric_container);
        mIconSwitcher = statusBar.findViewById(R.id.lyric_icon);
        mTextSwitcher = statusBar.findViewById(R.id.lyric_text);

        mNotificationColorUtil = ContrastColorUtil.getInstance(mContext);

        Animation animationIn = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_in);
        Animation animationOut = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.push_up_out);

        mTextSwitcher.setInAnimation(animationIn);
        mTextSwitcher.setOutAnimation(animationOut);
        mIconSwitcher.setInAnimation(animationIn);
        mIconSwitcher.setOutAnimation(animationOut);

        mLyricContainer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideLyricView(true);
                v.postDelayed(() -> showLyricView(true), HIDE_LYRIC_DELAY);
            }
            return false;
        });

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        Dependency.get(NotificationListener.class).addNotificationHandler(this);
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!mEnabled && mStarted) {
            stopLyric();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (!mEnabled) return;

        Notification notification = sbn.getNotification();
        boolean isLyric = ((notification.flags & Notification.FLAG_ALWAYS_SHOW_TICKER) != 0)
                && ((notification.flags & Notification.FLAG_ONLY_UPDATE_TICKER) != 0);

        boolean isCurrentNotification = mCurrentNotificationId == sbn.getId() &&
                TextUtils.equals(sbn.getPackageName(), mCurrentNotificationPackage);
        if (!isLyric) {
            if (isCurrentNotification) {
                stopLyric();
            }
        } else {
            mCurrentNotificationPackage = sbn.getPackageName();
            mCurrentNotificationId = sbn.getId();

            if (notification.tickerText == null) {
                stopLyric();
                return;
            }
            if (!isCurrentNotification || !mStarted ||
                    notification.extras.getBoolean(EXTRA_TICKER_ICON_SWITCH, false)) {
                int iconId = notification.extras.getInt(EXTRA_TICKER_ICON, -1);
                Drawable icon = iconId == -1 ? notification.getSmallIcon().loadDrawable(mContext) :
                        StatusBarIconView.getIcon(mContext, sbn.getPackageContext(mContext),
                                new StatusBarIcon(sbn.getPackageName(), sbn.getUser(),
                                    iconId, notification.iconLevel, 0, null));
                mIconSwitcher.setImageDrawable(icon);
                updateIconTint();
            }
            startLyric();
            mTextSwitcher.setText(notification.tickerText);
        }
    }

    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean isCurrentNotification = mCurrentNotificationId == sbn.getId() &&
                TextUtils.equals(sbn.getPackageName(), mCurrentNotificationPackage);
        if (isCurrentNotification) {
            stopLyric();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        onNotificationRemoved(sbn, rankingMap);
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
    }

    @Override
    public void onNotificationsInitialized() {
    }

    public void startLyric() {
        if (!mStarted) {
            mStarted = true;
            showLyricView(true);
        }
    }

    public void stopLyric() {
        if (mStarted) {
            mStarted = false;
            hideLyricView(true);
            mCurrentNotificationPackage = null;
            mCurrentNotificationId = 0;
        }
    }

    public abstract void showLyricView(boolean animate);

    public abstract void hideLyricView(boolean animate);

    public boolean isLyricStarted() {
        return mStarted;
    }

    public View getView() {
        return mLyricContainer;
    }

    private void updateIconTint() {
        Drawable drawable = ((ImageView)mIconSwitcher.getCurrentView()).getDrawable();
        boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(drawable);
        if (isGrayscale) {
            ((ImageView) mIconSwitcher.getCurrentView()).setImageTintList(mTintColorStateList);
            ((ImageView) mIconSwitcher.getNextView()).setImageTintList(mTintColorStateList);
        } else {
            ((ImageView) mIconSwitcher.getCurrentView()).setImageTintList(null);
            ((ImageView) mIconSwitcher.getNextView()).setImageTintList(null);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> area, float darkIntensity, int tint) {
        int tintColor = DarkIconDispatcher.getTint(area, mLyricContainer, tint);

        ((TextView) mTextSwitcher.getCurrentView()).setTextColor(tintColor);
        ((TextView) mTextSwitcher.getNextView()).setTextColor(tintColor);

        mTintColorStateList = ColorStateList.valueOf(tintColor);
        updateIconTint();
    }
}
