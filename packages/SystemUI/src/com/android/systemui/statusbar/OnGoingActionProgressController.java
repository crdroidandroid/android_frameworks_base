/**
 * Copyright (c) 2025 VoltageOS
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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.res.R;
import com.android.systemui.util.IconFetcher;
import com.android.systemui.statusbar.OnGoingActionProgressGroup;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.util.MediaSessionManagerHelper;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class OnGoingActionProgressController implements NotificationListener.NotificationHandler,
        KeyguardStateController.Callback, OnHeadsUpChangedListener {
    private static final String TAG = "OngoingActionProgressController";
    private static final String ONGOING_ACTION_CHIP_ENABLED = "ongoing_action_chip";
    private static final String ONGOING_MEDIA_PROGRESS = "ongoing_media_progress";
    private static final String ONGOING_COMPACT_MODE_ENABLED = "ongoing_compact_mode";
    private static final int SWIPE_THRESHOLD = 100;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;
    private static final int MEDIA_UPDATE_INTERVAL_MS = 1000;
    private static final int DEBOUNCE_DELAY_MS = 150;
    private static final int MAX_ICON_CACHE_SIZE = 20;
    private static final int STALE_PROGRESS_CHECK_INTERVAL_MS = 5000;
    private static final int PROGRESS_TIMEOUT_MS = 30000;

    private static final VibrationEffect VIBRATION_EFFECT =
            VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

    public interface StateCallback {
        void onStateChanged(boolean isVisible, int progress, int maxProgress, 
                          Drawable icon, boolean isIconAdaptive, String packageName,
                          boolean isCompactMode, boolean showMediaControls);
    }

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final SettingsObserver mSettingsObserver;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationListener mNotificationListener;
    private final HeadsUpManager mHeadsUpManager;
    private final VibratorHelper mVibrator;
    private final IconFetcher mIconFetcher;
    private final MediaSessionManagerHelper mMediaSessionHelper;
    private final Executor mBackgroundExecutor;
    private StateCallback mStateCallback = null;
    private final boolean mIsComposeMode;
    private final Object mLock = new Object();
    private final Runnable mUiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mUpdatePending = false;
                mLastUpdateTime = System.currentTimeMillis();
                updateViews();
            }
        }
    };

    private final ProgressBar mProgressBar;
    private final ProgressBar mCircularProgressBar;
    private final View mProgressRootView;
    private final View mCompactRootView;
    private final ImageView mIconView;
    private final ImageView mCompactIconView;

    private final HashMap<String, IconFetcher.AdaptiveDrawableResult> mIconCache = new HashMap<>();

    private boolean mShowMediaProgress = true;
    private boolean mIsTrackingProgress = false;
    private boolean mIsForceHidden = false;
    private boolean mHeadsUpPinned = false;
    private long mLastProgressUpdateTime = 0;
    private boolean mIsEnabled;
    private boolean mIsCompactModeEnabled = false;
    private int mCurrentProgress = 0;
    private int mCurrentProgressMax = 0;
    private Drawable mCurrentIcon = null;
    private boolean mCurrentIconIsAdaptive = false;
    private boolean mIsMenuVisible = false;
    private boolean mIsSystemChipVisible = false;

    private String mTrackedNotificationKey;
    private String mTrackedPackageName;
    private PopupWindow mMediaPopup;
    private boolean mIsPopupActive = false;
    private boolean mNeedsFullUiUpdate = true;
    private boolean mIsViewAttached = false;
    private boolean mIsExpanded = false;

    private boolean mUpdatePending = false;
    private long mLastUpdateTime = 0;

    private final GestureDetector mGestureDetector;
    private final Handler mMediaProgressHandler = new Handler(Looper.getMainLooper());
    private final Runnable mMediaProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
                updateMediaProgressOnly();
                mMediaProgressHandler.postDelayed(this, MEDIA_UPDATE_INTERVAL_MS);
            }
        }
    };

    private final Runnable mStaleProgressChecker = new Runnable() {
        @Override
        public void run() {
            synchronized (OnGoingActionProgressController.this) {
                checkForStaleProgress();
            }
            if (mIsViewAttached) {
                mHandler.postDelayed(this, STALE_PROGRESS_CHECK_INTERVAL_MS);
            }
        }
    };

    private final Runnable mCompactCollapseRunnable = () -> {
        if (mIsCompactModeEnabled && mIsExpanded) {
            mIsExpanded = false;
            requestUiUpdate();
        }
    };

    private final Runnable mMenuCollapseRunnable = () -> {
        mIsMenuVisible = false;
        notifyStateCallback();
    };

    private final MediaSessionManagerHelper.MediaMetadataListener mMediaMetadataListener = 
            new MediaSessionManagerHelper.MediaMetadataListener() {
                @Override
                public void onMediaMetadataChanged() {
                    mNeedsFullUiUpdate = true;
                    requestUiUpdate();
                }

                @Override
                public void onPlaybackStateChanged() {
                    mNeedsFullUiUpdate = true;
                    requestUiUpdate();
                }
            };

    public OnGoingActionProgressController(
            Context context, OnGoingActionProgressGroup progressGroup,
            NotificationListener notificationListener, KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager, VibratorHelper vibrator) {

        mIsComposeMode = (progressGroup.rootView == null && progressGroup.compactRootView == null);

        if (progressGroup == null) {
            Log.wtf(TAG, "progressGroup is null");
            throw new IllegalArgumentException("progressGroup cannot be null");
        }

        mNotificationListener = notificationListener;
        if (mNotificationListener == null) {
            Log.wtf(TAG, "mNotificationListener is null");
            throw new IllegalArgumentException("notificationListener cannot be null");
        }

        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManager;
        mContext = context;
        mContentResolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        mSettingsObserver = new SettingsObserver(mHandler);
        mBackgroundExecutor = Executors.newSingleThreadExecutor();
        mVibrator = vibrator;

        mProgressBar = progressGroup.progressBarView;
        mCircularProgressBar = progressGroup.circularProgressBarView;
        mProgressRootView = progressGroup.rootView;
        mCompactRootView = progressGroup.compactRootView;
        mIconView = progressGroup.iconView;
        mCompactIconView = progressGroup.compactIconView;

        mIconFetcher = new IconFetcher(context);
        mMediaSessionHelper = MediaSessionManagerHelper.Companion.getInstance(context);

        mGestureDetector = mIsComposeMode ? null : new GestureDetector(mContext, new MediaGestureListener());
        mKeyguardStateController.addCallback(this);
        mHeadsUpManager.addListener(this);
        mNotificationListener.addNotificationHandler(this);
        mSettingsObserver.register();

        if (!mIsComposeMode) {
            if (mProgressRootView != null && mGestureDetector != null) {
                mProgressRootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));
            }

            if (mCompactRootView != null && mGestureDetector != null) {
                mCompactRootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));

                mCompactRootView.setOnClickListener(v -> {
                    onInteraction();
                });
            }
        }

        mMediaSessionHelper.addMediaMetadataListener(mMediaMetadataListener);

        mIsViewAttached = true;
        updateSettings();

        mHandler.postDelayed(mStaleProgressChecker, STALE_PROGRESS_CHECK_INTERVAL_MS);
    }

    /**
     * Sets a callback for Compose to receive state updates
     * @param callback Callback to be notified of state changes, or null to unregister
     */
    public void setStateCallback(StateCallback callback) {
        mStateCallback = callback;
        notifyStateCallback();
    }

    public void expandCompactView() {
        mIsExpanded = true;

        // Reset collapse timer
        mHandler.removeCallbacks(mCompactCollapseRunnable);
        mHandler.postDelayed(mCompactCollapseRunnable, 5000);

        if (mIsComposeMode) {
            notifyStateCallback();
            return;
        }

        if (mCompactRootView != null) mCompactRootView.setVisibility(View.GONE);
        if (mProgressRootView != null) mProgressRootView.setVisibility(View.VISIBLE);

        requestUiUpdate();
    }

    private class MediaGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            onInteraction();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
                toggleMediaPlaybackState();
            }
            mVibrator.vibrate(VIBRATION_EFFECT);
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
                openMediaApp();
            }
            mVibrator.vibrate(VIBRATION_EFFECT);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (!(mShowMediaProgress && mMediaSessionHelper.isMediaPlaying())) {
                return false;
            }
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(e2.getY() - e1.getY()) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffX > 0) {
                    skipToNextTrack();
                } else {
                    skipToPreviousTrack();
                }
                return true;
            }
            return false;
        }
    }

    private void requestUiUpdate() {
        long currentTime = System.currentTimeMillis();
        synchronized (mLock) {
            if (!mUpdatePending && (currentTime - mLastUpdateTime > DEBOUNCE_DELAY_MS)) {
                mUpdatePending = false;
                mLastUpdateTime = currentTime;
                updateViews();
            } else if (!mUpdatePending) {
                mUpdatePending = true;
                mHandler.postDelayed(mUiUpdateRunnable, DEBOUNCE_DELAY_MS);
            }
        }
    }

    /**
     * Notifies the Compose callback of current state
     */
    private void notifyStateCallback() {
        if (mStateCallback == null) {
            return;
        }

        boolean isVisible = !mIsForceHidden && !mHeadsUpPinned && !mIsSystemChipVisible;

        boolean isMediaPlaying = mShowMediaProgress && mMediaSessionHelper.isMediaPlaying();
        boolean hasNotificationProgress = mIsEnabled && mIsTrackingProgress;

        isVisible = isVisible && (isMediaPlaying || hasNotificationProgress);

        if (isVisible) {
            boolean isCompact = mIsCompactModeEnabled && !mIsExpanded;
            mStateCallback.onStateChanged(
                true, mCurrentProgress, mCurrentProgressMax, 
                mCurrentIcon, mCurrentIconIsAdaptive, mTrackedPackageName,
                isCompact, mIsMenuVisible
            );
        } else {
            mStateCallback.onStateChanged(false, 0, 0, null, false, null, false, false);
        }
    }

    private void updateViews() {
        if (!mIsViewAttached) {
            if (mIsComposeMode) {
                notifyStateCallback();
            }
            return;
        }

        if (mIsForceHidden || mHeadsUpPinned) {
            if (!mIsComposeMode) {
                if (mProgressRootView != null) mProgressRootView.setVisibility(View.GONE);
                if (mCompactRootView != null) mCompactRootView.setVisibility(View.GONE);
            }
            notifyStateCallback();
            return;
        }

        boolean isMediaPlaying = mShowMediaProgress && mMediaSessionHelper.isMediaPlaying();

        if (mIsCompactModeEnabled && !mIsExpanded) {
            if (!mIsComposeMode && mProgressRootView != null) {
                mProgressRootView.setVisibility(View.GONE);
            }

            if (!mIsEnabled && !isMediaPlaying) {
                if (!mIsComposeMode && mCompactRootView != null) {
                    mCompactRootView.setVisibility(View.GONE);
                }
                notifyStateCallback();
                return;
            }

            if (!mIsComposeMode && mCompactRootView != null) {
                mCompactRootView.setVisibility(View.VISIBLE);
            }

            if (isMediaPlaying) {
                updateMediaProgressCompact();
            } else {
                updateNotificationProgressCompact();
            }
        } else {
            if (!mIsComposeMode && mCompactRootView != null) {
                mCompactRootView.setVisibility(View.GONE);
            }

            if (isMediaPlaying) {
                if (!mIsComposeMode && mProgressRootView != null) {
                    mProgressRootView.setVisibility(View.VISIBLE);
                }

                if (mNeedsFullUiUpdate) {
                    updateMediaProgressFull();
                    mNeedsFullUiUpdate = false;
                } else {
                    updateMediaProgressOnly();
                }
            } else {
                updateNotificationProgress();
            }
        }
        notifyStateCallback();
    }

    private void updateMediaProgressOnly() {
        if (!mIsViewAttached && !mIsComposeMode) {
            return;
        }

        long totalDuration = mMediaSessionHelper.getTotalDuration();

        android.media.session.PlaybackState playbackState = mMediaSessionHelper.getMediaControllerPlaybackState();
        long currentProgress = 0;

        if (playbackState != null) {
            currentProgress = playbackState.getPosition();
        }

        mCurrentProgress = (int) currentProgress;
        mCurrentProgressMax = (int) totalDuration;
        if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

        if (!mIsComposeMode && mProgressRootView != null && 
            mProgressRootView.getVisibility() == View.VISIBLE && mProgressBar != null && totalDuration > 0) {
            mProgressBar.setMax((int) totalDuration);
            mProgressBar.setProgress((int) currentProgress);
        }

        if (!mIsComposeMode && mCompactRootView != null && 
            mCompactRootView.getVisibility() == View.VISIBLE && mCircularProgressBar != null && totalDuration > 0) {
            mCircularProgressBar.setMax((int) totalDuration);
            mCircularProgressBar.setProgress((int) currentProgress);
        }

        if (mIsComposeMode) {
            notifyStateCallback();
        }
    }

    private void updateMediaProgressFull() {
        if (!mIsViewAttached && !mIsComposeMode) return;

        if (!mIsComposeMode && mProgressRootView != null) {
            mProgressRootView.setVisibility(View.VISIBLE);
        }

        mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
        mMediaProgressHandler.post(mMediaProgressRunnable);

        Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

        if (mediaAppIcon != null) {
            mCurrentIcon = mediaAppIcon;
            mCurrentIconIsAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
            if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(mediaAppIcon);
        } else {
            String packageName = null;

            android.media.session.PlaybackState playbackState = mMediaSessionHelper.getMediaControllerPlaybackState();
            if (playbackState != null && playbackState.getExtras() != null) {
                packageName = playbackState.getExtras().getString("package");
            }
            if (packageName != null) {
                loadIconInBackground(packageName, result -> {
                    Drawable drawable = result != null ? result.drawable : null;
                    boolean isAdaptive = result != null ? result.isAdaptive : false;

                    if (drawable != null) {
                        mCurrentIcon = drawable;
                        mCurrentIconIsAdaptive = isAdaptive;
                        if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(drawable);
                    } else {
                        setDefaultMediaIcon();
                    }
                    if (mIsComposeMode) notifyStateCallback();
                });
            } else {
                setDefaultMediaIcon();
            }
        }

        updateMediaProgressOnly();
    }

    private void setDefaultMediaIcon() {
        mCurrentIcon = mContext.getResources().getDrawable(R.drawable.ic_default_music_icon);
        mCurrentIconIsAdaptive = false;
        if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(mCurrentIcon);
    }

    private void updateMediaProgressCompact() {
        if (!mIsViewAttached && !mIsComposeMode) return;

        if (!mIsComposeMode && mCompactRootView != null) {
            mCompactRootView.setVisibility(View.VISIBLE);
        }

        mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
        mMediaProgressHandler.post(mMediaProgressRunnable);

        long totalDuration = mMediaSessionHelper.getTotalDuration();

        android.media.session.PlaybackState playbackState = mMediaSessionHelper.getMediaControllerPlaybackState();
        long currentProgress = 0;

        if (playbackState != null) {
            currentProgress = playbackState.getPosition();
        }

        mCurrentProgress = (int) currentProgress;
        mCurrentProgressMax = (int) totalDuration;
        if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

        if (!mIsComposeMode && totalDuration > 0 && mCircularProgressBar != null) {
            mCircularProgressBar.setMax((int) totalDuration);
            mCircularProgressBar.setProgress((int) currentProgress);
        }

        Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

        if (mediaAppIcon != null) {
            mCurrentIcon = mediaAppIcon;
            mCurrentIconIsAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
            if (!mIsComposeMode && mCompactIconView != null) {
                mCompactIconView.setImageDrawable(mediaAppIcon);
            }
        } else {
            String packageName = null;
            if (playbackState != null && playbackState.getExtras() != null) {
                packageName = playbackState.getExtras().getString("package");
            }

            if (packageName != null) {
                loadIconInBackground(packageName, result -> {
                    Drawable drawable = result != null ? result.drawable : null;
                    boolean isAdaptive = result != null ? result.isAdaptive : false;

                    if (drawable != null) {
                        mCurrentIcon = drawable;
                        mCurrentIconIsAdaptive = isAdaptive;
                        if (!mIsComposeMode && mCompactIconView != null) mCompactIconView.setImageDrawable(drawable);
                    } else {
                        setDefaultMediaIconCompact();
                    }
                    if (mIsComposeMode) notifyStateCallback();
                });
            } else {
                setDefaultMediaIconCompact();
            }
        }
    }

    private void setDefaultMediaIconCompact() {
        mCurrentIcon = mContext.getResources().getDrawable(R.drawable.ic_default_music_icon);
        mCurrentIconIsAdaptive = false;
        if (!mIsComposeMode && mCompactIconView != null) mCompactIconView.setImageDrawable(mCurrentIcon);
    }

    private void updateNotificationProgress() {
        if (!mIsViewAttached && !mIsComposeMode) return;

        if (!mIsEnabled || !mIsTrackingProgress) {
            if (!mIsComposeMode && mProgressRootView != null) {
                mProgressRootView.setVisibility(View.GONE);
            }
            mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
            return;
        }

        if (!mIsComposeMode && mProgressRootView != null) {
            mProgressRootView.setVisibility(View.VISIBLE);
        }
        if (mCurrentProgressMax <= 0) {
            Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
            mCurrentProgressMax = 100;
        }

        if (!mIsComposeMode && mProgressBar != null) {
            mProgressBar.setMax(mCurrentProgressMax);
            mProgressBar.setProgress(mCurrentProgress);
        }

        if (mTrackedPackageName != null) {
            loadIconInBackground(mTrackedPackageName, result -> {
                Drawable drawable = result != null ? result.drawable : null;
                boolean isAdaptive = result != null ? result.isAdaptive : false;

                mCurrentIcon = drawable;
                mCurrentIconIsAdaptive = isAdaptive;
                if (!mIsComposeMode && mIconView != null && drawable != null) {
                    mIconView.setImageDrawable(drawable);
                }
                if (mIsComposeMode) notifyStateCallback();
            });
        }
    }

    private void updateNotificationProgressCompact() {
        if (!mIsViewAttached && !mIsComposeMode) return;

        if (!mIsEnabled || !mIsTrackingProgress) {
            if (!mIsComposeMode && mCompactRootView != null) {
                mCompactRootView.setVisibility(View.GONE);
            }
            mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
            return;
        }

        if (!mIsComposeMode && mCompactRootView != null) {
            mCompactRootView.setVisibility(View.VISIBLE);
        }
        if (mCurrentProgressMax <= 0) {
            Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
            mCurrentProgressMax = 100;
        }

        if (!mIsComposeMode && mCircularProgressBar != null) {
            mCircularProgressBar.setMax(mCurrentProgressMax);
            mCircularProgressBar.setProgress(mCurrentProgress);
        }

        if (mTrackedPackageName != null) {
            loadIconInBackground(mTrackedPackageName, result -> {
                Drawable drawable = result != null ? result.drawable : null;
                boolean isAdaptive = result != null ? result.isAdaptive : false;

                mCurrentIcon = drawable;
                mCurrentIconIsAdaptive = isAdaptive;
                if (!mIsComposeMode && mCompactIconView != null && drawable != null) {
                    mCompactIconView.setImageDrawable(drawable);
                }
                if (mIsComposeMode) notifyStateCallback();
            });
        }
    }

    private void loadIconInBackground(String packageName, IconCallback callback) {
        if (packageName == null) return;

        if (mIconCache.containsKey(packageName)) {
            IconFetcher.AdaptiveDrawableResult cachedResult = mIconCache.get(packageName);
            if (cachedResult != null) {
                callback.onIconLoaded(cachedResult);
                return;
            }
        }

        mBackgroundExecutor.execute(() -> {
            final IconFetcher.AdaptiveDrawableResult iconResult = 
                    mIconFetcher.getMonotonicPackageIcon(packageName);

            if (iconResult != null && iconResult.drawable != null) {
                if (mIsComposeMode) {
                    int sizePx = (int) (24 * mContext.getResources().getDisplayMetrics().density);
                    iconResult.drawable.setBounds(0, 0, sizePx, sizePx);

                    if (iconResult.isAdaptive && iconResult.drawable instanceof AdaptiveIconDrawable) {
                    }
                }

                mIconCache.put(packageName, iconResult);

                mHandler.post(() -> {
                    callback.onIconLoaded(iconResult);
                });
            }
        });
    }

    private interface IconCallback {
        void onIconLoaded(@Nullable IconFetcher.AdaptiveDrawableResult result);
    }

    private void extractProgress(Notification notification) {
        Bundle extras = notification.extras;
        mCurrentProgressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100);
        mCurrentProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0);
    }

    private void trackProgress(final StatusBarNotification sbn) {
        mIsTrackingProgress = true;
        mTrackedNotificationKey = sbn.getKey();
        mTrackedPackageName = sbn.getPackageName();
        mLastProgressUpdateTime = System.currentTimeMillis();
        extractProgress(sbn.getNotification());
        requestUiUpdate();
    }

    private void clearProgressTracking() {
        mIsTrackingProgress = false;
        mTrackedNotificationKey = null;
        mTrackedPackageName = null;
        mCurrentProgress = 0;
        mCurrentProgressMax = 0;
        mLastProgressUpdateTime = 0;
        requestUiUpdate();
    }

    private void checkForStaleProgress() {
        if (!mIsTrackingProgress || mTrackedNotificationKey == null) return;

        StatusBarNotification sbn = findNotificationByKey(mTrackedNotificationKey);
        if (sbn == null) {
            clearProgressTracking();
            return;
        }

        if (!hasProgress(sbn.getNotification())) {
            clearProgressTracking();
            return;
        }

        if (mLastProgressUpdateTime == 0) {
            mLastProgressUpdateTime = System.currentTimeMillis();
            return;
        }

        if (System.currentTimeMillis() - mLastProgressUpdateTime > PROGRESS_TIMEOUT_MS
                && mCurrentProgressMax > 0
                && mCurrentProgress >= mCurrentProgressMax) {
            clearProgressTracking();
        }
    }

    private void updateProgressIfNeeded(final StatusBarNotification sbn) {
        if (!mIsTrackingProgress) return;

        if (sbn.getKey().equals(mTrackedNotificationKey)) {
            if (!hasProgress(sbn.getNotification())) {
                clearProgressTracking();
                return;
            }

            mLastProgressUpdateTime = System.currentTimeMillis();
            extractProgress(sbn.getNotification());
            requestUiUpdate();
        }
    }

    @Nullable
    private StatusBarNotification findNotificationByKey(String key) {
        if (key == null || mNotificationListener == null) return null;

        for (StatusBarNotification notification : mNotificationListener.getActiveNotifications()) {
            if (notification.getKey().equals(key)) {
                return notification;
            }
        }
        return null;
    }

    private static boolean hasProgress(@NonNull final Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return false;

        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        boolean maxProgressValid = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0;
        return extras.containsKey(Notification.EXTRA_PROGRESS) &&
               extras.containsKey(Notification.EXTRA_PROGRESS_MAX) &&
               !indeterminate && maxProgressValid;
    }

    public void onInteraction() {
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            if (mIsComposeMode) {
                mIsMenuVisible = !mIsMenuVisible;
                notifyStateCallback();
                if (mIsMenuVisible) {
                    mHandler.removeCallbacks(mMenuCollapseRunnable);
                    mHandler.postDelayed(mMenuCollapseRunnable, 5000);
                }
            } else {
                showMediaPopup(mProgressRootView);
            }
        } else {
            openTrackedApp();
        }
        mVibrator.vibrate(VIBRATION_EFFECT);
    }

    public void onLongPress() {
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            openMediaApp();
        } else {
            openTrackedApp();
        }
        mVibrator.vibrate(VIBRATION_EFFECT);
    }

    public void onDoubleTap() {
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            toggleMediaPlaybackState();
            mVibrator.vibrate(VIBRATION_EFFECT);
        }
    }

    public void onSwipe(boolean isNext) {
        if (isNext) skipToNextTrack();
        else skipToPreviousTrack();
    }

    public void onMediaAction(int action) {
        if (action == 0) skipToPreviousTrack();
        else if (action == 1) toggleMediaPlaybackState();
        else if (action == 2) skipToNextTrack();
        mHandler.removeCallbacks(mMenuCollapseRunnable);
        mHandler.postDelayed(mMenuCollapseRunnable, 5000);
    }

    public void onMediaMenuDismiss() {
        mIsMenuVisible = false;
        notifyStateCallback();
    }

    public void setSystemChipVisible(boolean visible) {
        if (mIsSystemChipVisible != visible) {
            mIsSystemChipVisible = visible;
            notifyStateCallback();
            requestUiUpdate();
        }
    }

    private void showMediaPopup(View anchorView) {
        if (mIsComposeMode || anchorView == null) {
            return;
        }
        if (mIsPopupActive) {
            if (mMediaPopup != null) {
                mMediaPopup.dismiss();
            }
            mIsPopupActive = false;
            return;
        }

        Context context = anchorView.getContext();
        View popupView = LayoutInflater.from(context).inflate(R.layout.media_control_popup, null);

        if (mMediaPopup != null && mMediaPopup.isShowing()) {
            mMediaPopup.dismiss();
        }

        mMediaPopup = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mMediaPopup.setOutsideTouchable(true);
        mMediaPopup.setFocusable(true);
        mMediaPopup.setOnDismissListener(() -> mIsPopupActive = false);

        ImageButton btnPrevious = popupView.findViewById(R.id.btn_previous);
        ImageButton btnNext = popupView.findViewById(R.id.btn_next);

        if (btnPrevious != null) {
            btnPrevious.setOnClickListener(v -> {
                skipToPreviousTrack();
                mMediaPopup.dismiss();
            });
        }

        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                skipToNextTrack();
                mMediaPopup.dismiss();
            });
        }

        anchorView.post(() -> {
            if (!mIsViewAttached) return;

            int offsetX = -popupView.getWidth() / 3;
            int offsetY = -anchorView.getHeight();
            mMediaPopup.showAsDropDown(anchorView, offsetX, offsetY);
            mIsPopupActive = true;
        });
    }

    private void openTrackedApp() {
        if (mTrackedPackageName == null) {
            Log.w(TAG, "No tracked package available");
            return;
        }

        Intent launchIntent = mContext.getPackageManager().getLaunchIntentForPackage(mTrackedPackageName);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(launchIntent);
        } else {
            Log.w(TAG, "No launch intent for package: " + mTrackedPackageName);
        }
    }

    private void onNotificationPosted(final StatusBarNotification sbn) {
        if (sbn == null || !mIsEnabled) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        synchronized (this) {
            boolean hasValidProgress = hasProgress(notification);
            String currentKey = mTrackedNotificationKey;

            if (!hasValidProgress) {
                if (currentKey != null && currentKey.equals(sbn.getKey())) {
                    clearProgressTracking();
                }
                return;
            }

            if (!mIsTrackingProgress) {
                trackProgress(sbn);
            } else if (sbn.getKey().equals(currentKey)) {
                updateProgressIfNeeded(sbn);
            }
        }
    }

    private void onNotificationRemoved(final StatusBarNotification sbn) {
        if (sbn == null) return;

        synchronized (this) {
            if (!mIsTrackingProgress) return;

            if (sbn.getKey().equals(mTrackedNotificationKey)) {
                clearProgressTracking();
                return;
            }

            if (sbn.getPackageName().equals(mTrackedPackageName)) {
                StatusBarNotification currentSbn = findNotificationByKey(mTrackedNotificationKey);
                if (currentSbn == null || !hasProgress(currentSbn.getNotification())) {
                    clearProgressTracking();
                }
            }
        }
    }

    public void setForceHidden(final boolean forceHidden) {
        if (mIsForceHidden != forceHidden) {
            Log.d(TAG, "setForceHidden " + forceHidden);
            mIsForceHidden = forceHidden;
            notifyStateCallback();
            requestUiUpdate();
        }
    }

    private void toggleMediaPlaybackState() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.toggleMediaPlaybackState(); 
        }
    }

    private void skipToNextTrack() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.nextSong(); 
        }
    }

    private void skipToPreviousTrack() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.prevSong(); 
        }
    }

    private void openMediaApp() { 
        if (mMediaSessionHelper != null) {
            mMediaSessionHelper.launchMediaApp(); 
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap) {
        onNotificationPosted(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap) {
        onNotificationRemoved(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap, int _reason) {
        onNotificationRemoved(sbn);
    }

     @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        mHeadsUpPinned = inPinnedMode;
        notifyStateCallback();
        requestUiUpdate();
    }

    @Override
    public void onNotificationRankingUpdate(NotificationListenerService.RankingMap _rankingMap) {
    }

    @Override
    public void onNotificationsInitialized() {
    }

    @Override
    public void onKeyguardShowingChanged() {
        setForceHidden(mKeyguardStateController.isShowing());
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) { super(handler); }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED)) ||
                    uri.equals(Settings.System.getUriFor(ONGOING_MEDIA_PROGRESS)) ||
                    uri.equals(Settings.System.getUriFor(ONGOING_COMPACT_MODE_ENABLED))) {
                updateSettings();
            }
        }

        public void register() {
            mContentResolver.registerContentObserver(Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(ONGOING_MEDIA_PROGRESS), 
                    false, this, UserHandle.USER_ALL);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(ONGOING_COMPACT_MODE_ENABLED), 
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        public void unregister() { 
            mContentResolver.unregisterContentObserver(this); 
        }
    }

    private void updateSettings() {
        boolean wasEnabled = mIsEnabled;
        boolean wasShowingMedia = mShowMediaProgress;
        boolean wasCompactMode = mIsCompactModeEnabled;

        mIsEnabled = Settings.System.getIntForUser(mContentResolver, 
                ONGOING_ACTION_CHIP_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
        mShowMediaProgress = Settings.System.getIntForUser(mContentResolver, 
                ONGOING_MEDIA_PROGRESS, 0, UserHandle.USER_CURRENT) == 1;
        mIsCompactModeEnabled = Settings.System.getIntForUser(mContentResolver, 
                ONGOING_COMPACT_MODE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;

        if (wasEnabled != mIsEnabled || wasShowingMedia != mShowMediaProgress || wasCompactMode != mIsCompactModeEnabled) {
            mNeedsFullUiUpdate = true;
            mIsExpanded = false;
        }

        requestUiUpdate();
    }

    public void destroy() {
        mIsViewAttached = false;

        mHandler.removeCallbacks(mStaleProgressChecker);

        mSettingsObserver.unregister();
        mKeyguardStateController.removeCallback(this);
        mHeadsUpManager.removeListener(this);
        mMediaSessionHelper.removeMediaMetadataListener(mMediaMetadataListener);

        mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
        mHandler.removeCallbacksAndMessages(null);

        if (mMediaPopup != null && mMediaPopup.isShowing()) {
            mMediaPopup.dismiss();
        }

        synchronized (mLock) {
            mIsTrackingProgress = false;
            mTrackedNotificationKey = null;
            mTrackedPackageName = null;
            mIconCache.clear();
        }

        if (!mIsComposeMode && mIconView != null) {
            mIconView.setImageDrawable(null);
        }

        if (!mIsComposeMode && mCompactIconView != null) {
            mCompactIconView.setImageDrawable(null);
        }
        mCurrentIcon = null;

        // Shutdown the background executor
        if (mBackgroundExecutor instanceof ExecutorService) {
            ((ExecutorService) mBackgroundExecutor).shutdown();
        }
    }

    private static int getThemeColor(Context context, int attrResId) {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(attrResId, typedValue, true);
        return typedValue.data;
    }
}
