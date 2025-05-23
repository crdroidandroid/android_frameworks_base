package com.android.systemui.util;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

public class ScreenAnimationController {
    private static ScreenAnimationController sInstance;
    private AmbientDisplayConfiguration mAmbientDisplayConfiguration = null;
    private DisplayManager mDisplayManager = null;
    private ContentResolver mContentResolver = null;
    private boolean mPanelExpandedWhenScreenOff = false;
    private boolean mLandscapeWhenScreenOff = false;
    private boolean mIsPressSleepButton = false;
    private boolean mUnlockAnimPlaying = false;
    private boolean mAnimationEnabled = true;
    
    public static final String SCREEN_ANIMATION_ENABLED = "screen_animation_enabled";
    
    private ContentObserver mSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }
    };
    
    private ScreenAnimationController() {}
    
    public static synchronized ScreenAnimationController INSTANCE() {
        if (sInstance == null) {
            sInstance = new ScreenAnimationController();
        }
        return sInstance;
    }
    
    public void updateCsfStates(boolean expanded, boolean landscape, boolean powerButton) {
        mPanelExpandedWhenScreenOff = expanded;
        mLandscapeWhenScreenOff = landscape;
        mIsPressSleepButton = powerButton;
    }
    
    public void init(Context context, AmbientDisplayConfiguration ambientConfig, DisplayManager displayManager) {
        mAmbientDisplayConfiguration = ambientConfig;
        mDisplayManager = displayManager;
        mContentResolver = context.getContentResolver();
        
        register();
    }
    
    public void register() {
        if (mContentResolver != null) {
            mContentResolver.registerContentObserver(Settings.System.getUriFor(SCREEN_ANIMATION_ENABLED), false, mSettingsObserver, UserHandle.USER_ALL);
            updateSettings();
        }
    }
    
    private void updateSettings() {
        if (mContentResolver != null) {
            mAnimationEnabled = Settings.System.getInt(mContentResolver, SCREEN_ANIMATION_ENABLED, 1) == 1;
        }
    }
    
    public void setAnimationPlaying(boolean playing) {
        mUnlockAnimPlaying = playing;
    }
    
    public boolean isLandscapeScreenOff() {
        return mLandscapeWhenScreenOff;
    }
    
    public boolean isPanelExpandedWhenScreenOff() {
        return mPanelExpandedWhenScreenOff;
    }
    
    public boolean isUnlockAnimPlaying() {
        return mUnlockAnimPlaying;
    }
    
    public int getCurDisplayState() {
        return mDisplayManager != null 
            ? mDisplayManager.getDisplay(0).getCommittedState()
            : 0;
    }
    
    public boolean shouldPlayAnimation() {
        if (!mAnimationEnabled) {
            return false;
        }
        
        return (mPanelExpandedWhenScreenOff 
            || mLandscapeWhenScreenOff 
            || mAmbientDisplayConfiguration != null && !mAmbientDisplayConfiguration.enabled(ActivityManager.getCurrentUser())
            || mIsPressSleepButton) ? false : true;
    }
    
    public void setAnimationEnabled(boolean enabled) {
        if (mContentResolver != null) {
            Settings.System.putInt(mContentResolver, SCREEN_ANIMATION_ENABLED, enabled ? 1 : 0);
        }
    }

    public boolean isAnimationEnabled() {
        return mAnimationEnabled;
    }

    public void destroy() {
        if (mContentResolver != null && mSettingsObserver != null) {
            mContentResolver.unregisterContentObserver(mSettingsObserver);
        }
    }
}
