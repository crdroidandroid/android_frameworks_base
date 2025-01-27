/*
     Copyright (C) 2024 the risingOS Android Project
     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at
          http://www.apache.org/licenses/LICENSE-2.0
     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
*/
package com.android.systemui.lockscreen;

import android.annotation.NonNull;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.AttributeSet;
import android.os.UserHandle;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.settingslib.net.DataUsageController;
import com.android.settingslib.Utils;

import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.animation.Expandable;
import com.android.systemui.animation.view.LaunchableImageView;
import com.android.systemui.animation.view.LaunchableFAB;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.bluetooth.ui.viewModel.BluetoothDetailsContentViewModel;
import com.android.systemui.qs.tiles.dialog.InternetDialogManager;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
import com.android.systemui.statusbar.util.MediaSessionManagerHelper;

import com.android.internal.util.crdroid.OmniJawsClient;
import com.android.internal.util.crdroid.VibrationUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LockScreenWidgetsController implements OmniJawsClient.OmniJawsObserver, MediaSessionManagerHelper.MediaMetadataListener {

    private static final String LOCKSCREEN_WIDGETS_ENABLED =
            "lockscreen_widgets_enabled";

    private static final String LOCKSCREEN_WIDGETS =
            "lockscreen_widgets";

    private static final String LOCKSCREEN_WIDGETS_EXTRAS =
            "lockscreen_widgets_extras";
            
    private static final String LOCKSCREEN_WIDGETS_STYLE =
            "lockscreen_widgets_style";
            
    private static final String LOCKSCREEN_WIDGETS_TRANSPARENCY =
            "lockscreen_widgets_transparency";

    private static final int[] MAIN_WIDGETS_VIEW_IDS = {
            R.id.main_kg_item_placeholder1,
            R.id.main_kg_item_placeholder2
    };

    private static final int[] WIDGETS_VIEW_IDS = {
            R.id.kg_item_placeholder1,
            R.id.kg_item_placeholder2,
            R.id.kg_item_placeholder3,
            R.id.kg_item_placeholder4
    };
    
    public static final int BT_ACTIVE = R.drawable.qs_bluetooth_icon_on;
    public static final int BT_INACTIVE = R.drawable.qs_bluetooth_icon_off;
    public static final int DATA_ACTIVE = R.drawable.ic_signal_cellular_alt_24;
    public static final int DATA_INACTIVE = R.drawable.ic_mobiledata_off_24;
    public static final int RINGER_ACTIVE = R.drawable.ic_vibration_24;
    public static final int RINGER_INACTIVE = R.drawable.ic_ring_volume_24;
    public static final int TORCH_RES_ACTIVE = R.drawable.ic_flashlight_on;
    public static final int TORCH_RES_INACTIVE = R.drawable.ic_flashlight_off;
    public static final int WIFI_ACTIVE = R.drawable.ic_wifi_24;
    public static final int WIFI_INACTIVE = R.drawable.ic_wifi_off_24;
    public static final int HOTSPOT_ACTIVE = R.drawable.qs_hotspot_icon_on;
    public static final int HOTSPOT_INACTIVE = R.drawable.qs_hotspot_icon_off;

    public static final int BT_LABEL_INACTIVE = R.string.quick_settings_bluetooth_label;
    public static final int DATA_LABEL_INACTIVE = R.string.quick_settings_data_label;
    public static final int RINGER_LABEL_INACTIVE = R.string.quick_settings_ringer_label;
    public static final int TORCH_LABEL_ACTIVE = R.string.torch_active;
    public static final int TORCH_LABEL_INACTIVE = R.string.quick_settings_flashlight_label;
    public static final int WIFI_LABEL_INACTIVE = R.string.quick_settings_wifi_label;
    public static final int HOTSPOT_LABEL = R.string.accessibility_status_bar_hotspot;

    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;

    private final AccessPointController mAccessPointController;
    private final BluetoothController mBluetoothController;
    private final BluetoothDetailsContentViewModel mBluetoothDetailsContentViewModel;
    private final ConfigurationController mConfigurationController;
    private final DataUsageController mDataController;
    private final FlashlightController mFlashlightController;
    private final InternetDialogManager mInternetDialogManager;
    private final NetworkController mNetworkController;
    private final StatusBarStateController mStatusBarStateController;
    private final MediaSessionManagerHelper mMediaSessionManagerHelper;
    private final LockscreenWidgetsObserver mLockscreenWidgetsObserver;
    private final ActivityLauncherUtils mActivityLauncherUtils;
    private final HotspotController mHotspotController;

    protected final CellSignalCallback mCellSignalCallback = new CellSignalCallback();
    protected final WifiSignalCallback mWifiSignalCallback = new WifiSignalCallback();
    private final HotspotCallback mHotspotCallback = new HotspotCallback();

    private Context mContext;
    private LaunchableImageView mWidget1, mWidget2, mWidget3, mWidget4, mediaButton, torchButton, weatherButton;
    private LaunchableFAB mediaButtonFab, torchButtonFab, weatherButtonFab, hotspotButtonFab;
    private LaunchableFAB wifiButtonFab, dataButtonFab, ringerButtonFab, btButtonFab;
    private LaunchableImageView wifiButton, dataButton, ringerButton, btButton, hotspotButton;
    private int mDarkColor, mDarkColorActive, mLightColor, mLightColorActive;

    private CameraManager mCameraManager;
    private String mCameraId;
    private boolean isFlashOn = false;

    private String mMainLockscreenWidgetsList;
    private String mSecondaryLockscreenWidgetsList;
    private LaunchableFAB[] mMainWidgetViews;
    private LaunchableImageView[] mSecondaryWidgetViews;
    private List<String> mMainWidgetsList = new ArrayList<>();
    private List<String> mSecondaryWidgetsList = new ArrayList<>();
    private String mWidgetImagePath;

    private AudioManager mAudioManager;
    private String mLastTrackTitle = null;

    private boolean mDozing;
    
    private boolean mIsInflated = false;
    private GestureDetector mGestureDetector;
    private boolean mIsLongPress = false;

    private boolean mLockscreenWidgetsEnabled;
    private int mThemeStyle = 0;
    private float mTransparency = 0.3f;

    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            updateWidgetViews();
        }
        @Override
        public void onThemeChanged() {
            updateWidgetViews();
        }
    };

    private final View mView;
    private final Handler mHandler = new Handler();

    public LockScreenWidgetsController(View view) {
        mView = view;
        mContext = mView.getContext();
        mAccessPointController = Dependency.get(AccessPointController.class);
        mBluetoothDetailsContentViewModel = Dependency.get(BluetoothDetailsContentViewModel.class);
        mConfigurationController = Dependency.get(ConfigurationController.class);
        mFlashlightController = Dependency.get(FlashlightController.class);
        mInternetDialogManager = Dependency.get(InternetDialogManager.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mBluetoothController = Dependency.get(BluetoothController.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mDataController = mNetworkController.getMobileDataController();
        mHotspotController = Dependency.get(HotspotController.class);
        mMediaSessionManagerHelper = MediaSessionManagerHelper.Companion.getInstance(mContext);

        mActivityLauncherUtils = new ActivityLauncherUtils(mContext);

        mLockscreenWidgetsObserver = new LockscreenWidgetsObserver();
        mLockscreenWidgetsObserver.observe();

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        
        initResources();

        // FIX 1: OmniJawsClient no longer takes Context in constructor; use get()
        if (mWeatherClient == null) {
            mWeatherClient = OmniJawsClient.get();
        }

        try {
            mCameraId = mCameraManager.getCameraIdList()[0];
        } catch (Exception e) {}
        
        IntentFilter ringerFilter = new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
        mContext.registerReceiver(mRingerModeReceiver, ringerFilter);
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {}
        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;
            updateContainerVisibility();
        }
    };

    private final FlashlightController.FlashlightListener mFlashlightCallback =
            new FlashlightController.FlashlightListener() {
        @Override
        public void onFlashlightChanged(boolean enabled) {
            isFlashOn = enabled;
            updateTorchButtonState();
        }
        @Override
        public void onFlashlightError() {
        }
        @Override
        public void onFlashlightAvailabilityChanged(boolean available) {
            isFlashOn = mFlashlightController.isEnabled() && available;
            updateTorchButtonState();
        }
        // Add the missing method
        @Override
        public void onFlashlightStrengthChanged(int level) {
            // Handle flashlight strength changes if needed
            updateTorchButtonState();
        }
    };

    private void initResources() {
        mDarkColor = mContext.getResources().getColor(R.color.lockscreen_widget_background_color_dark);
        mLightColor = mContext.getResources().getColor(R.color.lockscreen_widget_background_color_light);
        mDarkColorActive = mContext.getResources().getColor(R.color.lockscreen_widget_active_color_dark);
        mLightColorActive = mContext.getResources().getColor(R.color.lockscreen_widget_active_color_light);
    }
    
    public void registerCallbacks() {
        if (isWidgetEnabled("hotspot")) {
            mHotspotController.addCallback(mHotspotCallback);
        }
        if (isWidgetEnabled("wifi")) {
            mNetworkController.addCallback(mWifiSignalCallback);
        }
        if (isWidgetEnabled("data")) {
            mNetworkController.addCallback(mCellSignalCallback);
        }
        if (isWidgetEnabled("bt")) {
            mBluetoothController.addCallback(mBtCallback);
        }
        if (isWidgetEnabled("torch")) {
            mFlashlightController.addCallback(mFlashlightCallback);
        }
        mConfigurationController.addCallback(mConfigurationListener);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        mMediaSessionManagerHelper.addMediaMetadataListener(this);
        updateWidgetViews();
        updateMediaPlaybackState();
    }
    
    public void unregisterCallbacks() {
        if (isWidgetEnabled("weather")) {
        	disableWeatherUpdates();
        }
        if (isWidgetEnabled("wifi")) {
            mNetworkController.removeCallback(mWifiSignalCallback);
        }
        if (isWidgetEnabled("data")) {
            mNetworkController.removeCallback(mCellSignalCallback);
        }
        if (isWidgetEnabled("bt")) {
            mBluetoothController.removeCallback(mBtCallback);
        }
        if (isWidgetEnabled("torch")) {
            mFlashlightController.removeCallback(mFlashlightCallback);
        }
        if (isWidgetEnabled("hotspot")) {
            mHotspotController.removeCallback(mHotspotCallback);
        }
        mConfigurationController.removeCallback(mConfigurationListener);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mContext.unregisterReceiver(mRingerModeReceiver);
        mLockscreenWidgetsObserver.unobserve();
        mHandler.removeCallbacksAndMessages(null);
        mMediaSessionManagerHelper.removeMediaMetadataListener(this);
    }
    
    public void initViews() {
        mMainWidgetViews = new LaunchableFAB[MAIN_WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < mMainWidgetViews.length; i++) {
            mMainWidgetViews[i] = mView.findViewById(MAIN_WIDGETS_VIEW_IDS[i]);
        }
        mSecondaryWidgetViews = new LaunchableImageView[WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < mSecondaryWidgetViews.length; i++) {
            mSecondaryWidgetViews[i] = mView.findViewById(WIDGETS_VIEW_IDS[i]);
        }
        mIsInflated = true;
        updateWidgetViews();
    }
    
    public void updateWidgetViews() {
        if (!mIsInflated) return;
        
        // Forcefully hide all widgets if lockscreen widgets are disabled
        if (!mLockscreenWidgetsEnabled) {
            // Hide all main widget views
            if (mMainWidgetViews != null) {
                for (int i = 0; i < mMainWidgetViews.length; i++) {
                    if (mMainWidgetViews[i] != null) {
                        mMainWidgetViews[i].setVisibility(View.GONE);
                    }
                }
            }
            // Hide all secondary widget views
            if (mSecondaryWidgetViews != null) {
                for (int i = 0; i < mSecondaryWidgetViews.length; i++) {
                    if (mSecondaryWidgetViews[i] != null) {
                        mSecondaryWidgetViews[i].setVisibility(View.GONE);
                    }
                }
            }
            // Hide all containers
            final View mainWidgetsContainer = mView.findViewById(R.id.main_widgets_container);
            if (mainWidgetsContainer != null) {
                mainWidgetsContainer.setVisibility(View.GONE);
            }
            final View secondaryWidgetsContainer = mView.findViewById(R.id.secondary_widgets_container);
            if (secondaryWidgetsContainer != null) {
                secondaryWidgetsContainer.setVisibility(View.GONE);
            }
            // Hide the main view itself
            mView.setVisibility(View.GONE);
            return;
        }
        
        if (mMainWidgetViews != null && mMainWidgetsList != null) {
            for (int i = 0; i < mMainWidgetViews.length; i++) {
                if (mMainWidgetViews[i] != null) {
                    mMainWidgetViews[i].setVisibility(i < mMainWidgetsList.size() ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < Math.min(mMainWidgetsList.size(), mMainWidgetViews.length); i++) {
                String widgetType = mMainWidgetsList.get(i);
                if (widgetType != null && i < mMainWidgetViews.length && mMainWidgetViews[i] != null) {
                    setUpWidgetWiews(null, mMainWidgetViews[i], widgetType);
                    updateMainWidgetResources(mMainWidgetViews[i], false);
                }
            }
        }
        if (mSecondaryWidgetViews != null && mSecondaryWidgetsList != null) {
            for (int i = 0; i < mSecondaryWidgetViews.length; i++) {
                if (mSecondaryWidgetViews[i] != null) {
                    mSecondaryWidgetViews[i].setVisibility(i < mSecondaryWidgetsList.size() ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < Math.min(mSecondaryWidgetsList.size(), mSecondaryWidgetViews.length); i++) {
                String widgetType = mSecondaryWidgetsList.get(i);
                if (widgetType != null && i < mSecondaryWidgetViews.length && mSecondaryWidgetViews[i] != null) {
                    setUpWidgetWiews(mSecondaryWidgetViews[i], null, widgetType);
                    updateWidgetsResources(mSecondaryWidgetViews[i]);
                }
            }
        }
        updateContainerVisibility();
    }

    private void updateMainWidgetResources(LaunchableFAB efab, boolean active) {
        if (efab == null) return;
        efab.setElevation(0);
        setButtonActiveState(null, efab, false);
        long visibleWidgetCount = mMainWidgetsList.stream().filter(widget -> !"none".equals(widget)).count();
        ViewGroup.LayoutParams params = efab.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) params;
            if (efab.getVisibility() == View.VISIBLE && visibleWidgetCount == 1) {
                layoutParams.width = mContext.getResources().getDimensionPixelSize(R.dimen.kg_widget_main_width);
                layoutParams.height = mContext.getResources().getDimensionPixelSize(R.dimen.kg_widget_main_height);
            } else {
                layoutParams.width = 0;
                layoutParams.weight = 1;
            }
            efab.setLayoutParams(layoutParams);
        }
    }

    private void updateContainerVisibility() {
        // If widgets are disabled, forcefully hide everything
        if (!mLockscreenWidgetsEnabled) {
            final View mainWidgetsContainer = mView.findViewById(R.id.main_widgets_container);
            if (mainWidgetsContainer != null) {
                mainWidgetsContainer.setVisibility(View.GONE);
            }
            final View secondaryWidgetsContainer = mView.findViewById(R.id.secondary_widgets_container);
            if (secondaryWidgetsContainer != null) {
                secondaryWidgetsContainer.setVisibility(View.GONE);
            }
            mView.setVisibility(View.GONE);
            return;
        }
        
        final boolean isMainWidgetsEmpty = mMainLockscreenWidgetsList == null 
            || TextUtils.isEmpty(mMainLockscreenWidgetsList);
        final boolean isSecondaryWidgetsEmpty = mSecondaryLockscreenWidgetsList == null 
            || TextUtils.isEmpty(mSecondaryLockscreenWidgetsList);
        final boolean isEmpty = isMainWidgetsEmpty && isSecondaryWidgetsEmpty;
        final View mainWidgetsContainer = mView.findViewById(R.id.main_widgets_container);
        if (mainWidgetsContainer != null) {
            mainWidgetsContainer.setVisibility(isMainWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        final View secondaryWidgetsContainer = mView.findViewById(R.id.secondary_widgets_container);
        if (secondaryWidgetsContainer != null) {
            secondaryWidgetsContainer.setVisibility(isSecondaryWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        final boolean shouldHideContainer = isEmpty || mDozing || !mLockscreenWidgetsEnabled;
        mView.setVisibility(shouldHideContainer ? View.GONE : View.VISIBLE);
    }
    
    private void updateWidgetsResources(LaunchableImageView iv) {
        if (iv == null) return;
        final int themeStyle = mThemeStyle;
        int bgRes;
        switch (themeStyle) {
            case 0:
            case 3:
            default:
                bgRes = R.drawable.lockscreen_widget_background_circle;
                break;
            case 1:
            case 2:
                bgRes = R.drawable.lockscreen_widget_background_square;
                break;
        }
        iv.setBackgroundResource(bgRes);
        setButtonActiveState(iv, null, false);
    }

    private boolean isNightMode() {
        final Configuration config = mContext.getResources().getConfiguration();
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    
    private void setUpWidgetWiews(LaunchableImageView iv, LaunchableFAB efab, String type) {
        View.OnClickListener clickListener = null;
        View.OnLongClickListener longClickListener = null;
        int drawableRes = 0;
        int stringRes = 0;

        switch (type) {
            case "none":
                if (iv != null) iv.setVisibility(View.GONE);
                if (efab != null) efab.setVisibility(View.GONE);
                return;
            case "wifi":
                clickListener = v -> toggleWiFi();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = WIFI_INACTIVE;
                stringRes = R.string.quick_settings_wifi_label;
                if (iv != null) wifiButton = iv;
                if (efab != null) wifiButtonFab = efab;
                break;
            case "data":
                clickListener = v -> toggleMobileData();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = DATA_INACTIVE;
                stringRes = DATA_LABEL_INACTIVE;
                if (iv != null) dataButton = iv;
                if (efab != null) dataButtonFab = efab;
                break;
            case "ringer":
                clickListener = v -> toggleRingerMode();
                drawableRes = RINGER_INACTIVE;
                stringRes = RINGER_LABEL_INACTIVE;
                if (iv != null) ringerButton = iv;
                if (efab != null) ringerButtonFab = efab;
                break;
            case "bt":
                clickListener = v -> toggleBluetoothState();
                longClickListener = v -> {
                    showBluetoothDialog(v);
                    return true;
                };
                drawableRes = BT_INACTIVE;
                stringRes = BT_LABEL_INACTIVE;
                if (iv != null) btButton = iv;
                if (efab != null) btButtonFab = efab;
                break;
            case "torch":
                clickListener = v -> toggleFlashlight();
                drawableRes = TORCH_RES_INACTIVE;
                stringRes = TORCH_LABEL_INACTIVE;
                if (iv != null) torchButton = iv;
                if (efab != null) torchButtonFab = efab;
                break;
            case "timer":
                clickListener = v -> mActivityLauncherUtils.launchTimer();
                drawableRes = R.drawable.ic_alarm;
                stringRes = R.string.clock_timer;
                break;
            case "calculator":
                clickListener = v -> mActivityLauncherUtils.launchCalculator();
                drawableRes = R.drawable.ic_calculator;
                stringRes = R.string.calculator;
                break;
            case "media":
                clickListener = v -> toggleMediaPlaybackState();
                longClickListener = v -> {
                    showMediaDialog(v);
                    return true;
                };
                drawableRes = R.drawable.ic_media_play;
                stringRes = R.string.controls_media_button_play;
                if (iv != null) mediaButton = iv;
                if (efab != null) mediaButtonFab = efab;
                break;
            case "weather":
                clickListener = v -> mActivityLauncherUtils.launchWeatherApp();
                drawableRes = R.drawable.ic_weather;
                stringRes = R.string.weather_data_unavailable;
                if (iv != null) weatherButton = iv;
                if (efab != null) weatherButtonFab = efab;
                enableWeatherUpdates();
                break;
            case "hotspot":
                clickListener = v -> toggleHotspot();
                longClickListener = v -> {
                    showInternetDialog(v);
                    return true;
                };
                drawableRes = HOTSPOT_INACTIVE;
                stringRes = HOTSPOT_LABEL;
                if (iv != null) hotspotButton = iv;
                if (efab != null) hotspotButtonFab = efab;
                break;
            case "wallet":
                clickListener = v -> mActivityLauncherUtils.launchWalletApp();
                drawableRes = R.drawable.ic_wallet_lockscreen;
                stringRes = R.string.google_wallet;
                break;
            case "qrscanner":
                clickListener = v -> mActivityLauncherUtils.launchQrScanner();
                drawableRes = R.drawable.ic_qr_code_scanner;
                stringRes = R.string.qr_code_scanner_title;
                break;
            default:
                return;
        }

        if (efab != null) {
            efab.setOnClickListener(clickListener);
            efab.setIcon(mContext.getDrawable(drawableRes));
            efab.setText(mContext.getResources().getString(stringRes));
            if (longClickListener != null) efab.setOnLongClickListener(longClickListener);
            if (mediaButtonFab == efab) attachSwipeGesture(efab);
        }

        if (iv != null) {
            iv.setOnClickListener(clickListener);
            if (longClickListener != null) iv.setOnLongClickListener(longClickListener);
            iv.setImageResource(drawableRes);
        }
    }

    private void attachSwipeGesture(LaunchableFAB efab) {
        final GestureDetector gestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                        VibrationUtils.triggerVibration(mContext, 2);
                    } else {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
                        VibrationUtils.triggerVibration(mContext, 2);
                    }
                    return true;
                }
                return false;
            }
            @Override
            public void onLongPress(MotionEvent e) {
                super.onLongPress(e);
                mIsLongPress = true;
                showMediaDialog(efab);
                mHandler.postDelayed(() -> {
                    mIsLongPress = false;
                }, 2500);
            }
        });
        efab.setOnTouchListener((v, event) -> {
            boolean isClick = gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !isClick && !mIsLongPress) {
                v.performClick();
            }
            return true;
        });
    }

    private void setButtonActiveState(LaunchableImageView iv, LaunchableFAB efab, boolean active) {
        int bgTint;
        int tintColor;
        if (mThemeStyle == 2 || mThemeStyle == 3) {
            if (active) {
                bgTint = Utils.applyAlpha(mTransparency, mDarkColorActive);
                tintColor = mDarkColorActive;
            } else {
                bgTint = Utils.applyAlpha(mTransparency, Color.WHITE);
                tintColor = Color.WHITE;
            }
        } else {
            if (active) {
                bgTint = isNightMode() ? mDarkColorActive : mLightColorActive;
                tintColor = isNightMode() ? mDarkColor : mLightColor;
            } else {
                bgTint = isNightMode() ? mDarkColor : mLightColor;
                tintColor = isNightMode() ? mLightColor : mDarkColor;
            }
        }
        if (iv != null) {
            iv.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            if (iv != weatherButton) {
            	iv.setImageTintList(ColorStateList.valueOf(tintColor));
            } else {
            	iv.setImageTintList(null);
            }
        }
        if (efab != null) {
            efab.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            if (efab != weatherButtonFab) {
            	efab.setIconTint(ColorStateList.valueOf(tintColor));
            } else {
            	efab.setIconTint(null);
            }
            efab.setTextColor(tintColor);
        }
    }

    private void toggleMediaPlaybackState() {
        if (mMediaSessionManagerHelper.isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE);
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY);
        }
    }
    
    private void showMediaDialog(View view) {
        String lastMediaPkg = getLastUsedMedia();
        if (TextUtils.isEmpty(lastMediaPkg)) return; // Return if null or empty
        mHandler.post(() -> {
            ((LockScreenWidgets) mView).showMediaDialog(view, lastMediaPkg);
            VibrationUtils.triggerVibration(mContext, 2); // Trigger vibration
        });
    }
    
    private String getLastUsedMedia() {
        return Settings.System.getString(mContext.getContentResolver(),
                    "media_session_last_package_name");
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) return;
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
        mHandler.postDelayed(() -> {
            updateMediaPlaybackState();
        }, 250);
    }

    private void updateMediaPlaybackState() {
        boolean isPlaying = mMediaSessionManagerHelper.isMediaPlaying();
        int stateIcon = isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play;
        if (mediaButton != null) {
            mediaButton.setImageResource(stateIcon);
            setButtonActiveState(mediaButton, null, isPlaying);
        }
        if (mediaButtonFab != null) {
            MediaMetadata mMediaMetadata = mMediaSessionManagerHelper.getCurrentMediaMetadata();
            String trackTitle = mMediaMetadata != null ? mMediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
            if (!TextUtils.isEmpty(trackTitle) && mLastTrackTitle != trackTitle) {
                mLastTrackTitle = trackTitle;
            }
            final boolean canShowTrackTitle = isPlaying || !TextUtils.isEmpty(mLastTrackTitle);
            mediaButtonFab.setIcon(mContext.getDrawable(isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play));
            mediaButtonFab.setText(canShowTrackTitle ? mLastTrackTitle : mContext.getResources().getString(R.string.controls_media_button_play));
            setButtonActiveState(null, mediaButtonFab, isPlaying);
        }
    }

    private void toggleFlashlight() {
        if (torchButton == null && torchButtonFab == null) return;
        try {
            boolean newState = !isFlashOn;
            mFlashlightController.setFlashlight(newState);
        } catch (Exception e) {}
    }

    private void toggleWiFi() {
        final WifiCallbackInfo cbi = mWifiSignalCallback.mInfo;
        mNetworkController.setWifiEnabled(!cbi.enabled);
        updateWiFiButtonState(!cbi.enabled);
        mHandler.postDelayed(() -> {
            updateWiFiButtonState(cbi.enabled);
        }, 250);
    }

    private boolean isMobileDataEnabled() {
        return mDataController.isMobileDataEnabled();
    }

    private void toggleMobileData() {
        mDataController.setMobileDataEnabled(!isMobileDataEnabled());
        updateMobileDataState(!isMobileDataEnabled());
        mHandler.postDelayed(() -> {
            updateMobileDataState(isMobileDataEnabled());
        }, 250);
    }
    
    private void showInternetDialog(View view) {
        mHandler.post(() -> mInternetDialogManager.create(true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi(), Expandable.fromView(view)));
        VibrationUtils.triggerVibration(mContext, 2);
    }

    private void toggleRingerMode() {
        if (mAudioManager != null) {
            int mode = mAudioManager.getRingerMode();
            if (mode == mAudioManager.RINGER_MODE_NORMAL) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
            updateRingerButtonState();
        }
    }

    private void updateTileButtonState(
            LaunchableImageView iv, LaunchableFAB efab, 
            boolean active, int activeResource, int inactiveResource,
            String activeString, String inactiveString) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (iv != null) {
                    iv.setImageResource(active ? activeResource : inactiveResource);
                    setButtonActiveState(iv, null, active);
                }
                if (efab != null) {
                    efab.setIcon(mContext.getDrawable(active ? activeResource : inactiveResource));
                    efab.setText(active ? activeString : inactiveString);
                    setButtonActiveState(null, efab, active);
                }
            }
        });
    }
    
    public void updateTorchButtonState() {
        if (!isWidgetEnabled("torch")) return;
        String activeString = mContext.getResources().getString(TORCH_LABEL_ACTIVE);
        String inactiveString = mContext.getResources().getString(TORCH_LABEL_INACTIVE);
        updateTileButtonState(torchButton, torchButtonFab, isFlashOn, 
            TORCH_RES_ACTIVE, TORCH_RES_INACTIVE, activeString, inactiveString);
    }

    private BroadcastReceiver mRingerModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRingerButtonState();
        }
    };

    private final BluetoothController.Callback mBtCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            updateBtState();
        }
        @Override
        public void onBluetoothDevicesChanged() {
            updateBtState();
        }
    };

    private void updateWiFiButtonState(boolean enabled) {
        if (!isWidgetEnabled("wifi")) return;
        if (wifiButton == null && wifiButtonFab == null) return;
        final WifiCallbackInfo cbi = mWifiSignalCallback.mInfo;
        String inactiveString = mContext.getResources().getString(WIFI_LABEL_INACTIVE);
        updateTileButtonState(wifiButton, wifiButtonFab, enabled, 
            WIFI_ACTIVE, WIFI_INACTIVE, cbi.ssid != null ? removeDoubleQuotes(cbi.ssid) : inactiveString, inactiveString);
    }

    private void updateRingerButtonState() {
        if (!isWidgetEnabled("ringer")) return;
        if (ringerButton == null && ringerButtonFab == null) return;
        if (mAudioManager != null) {
            boolean isVibrateActive = mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;
            String inactiveString = mContext.getResources().getString(RINGER_LABEL_INACTIVE);
            updateTileButtonState(ringerButton, ringerButtonFab, isVibrateActive, 
                RINGER_ACTIVE, RINGER_INACTIVE, inactiveString, inactiveString);
        }
    }

    private void updateMobileDataState(boolean enabled) {
        if (!isWidgetEnabled("data")) return;
        if (dataButton == null && dataButtonFab == null) return;
        String networkName = mNetworkController == null ? "" : mNetworkController.getMobileDataNetworkName();
        boolean hasNetwork = !TextUtils.isEmpty(networkName) && mNetworkController != null 
            && mNetworkController.hasMobileDataFeature();
        String inactiveString = mContext.getResources().getString(DATA_LABEL_INACTIVE);
        updateTileButtonState(dataButton, dataButtonFab, enabled, 
            DATA_ACTIVE, DATA_INACTIVE, hasNetwork && enabled ? networkName : inactiveString, inactiveString);
    }
    
    private void toggleBluetoothState() {
        mBluetoothController.setBluetoothEnabled(!isBluetoothEnabled());
        updateBtState();
        mHandler.postDelayed(() -> {
            updateBtState();
        }, 250);
    }
    
    private void showBluetoothDialog(View view) {
        mHandler.post(() -> 
            mBluetoothDetailsContentViewModel.showDialog(Expandable.fromView(view)));
        VibrationUtils.triggerVibration(mContext, 2);
    }
    
    private void updateBtState() {
        if (!isWidgetEnabled("bt")) return;
        if (btButton == null && btButtonFab == null) return;
        String deviceName = isBluetoothEnabled() ? mBluetoothController.getConnectedDeviceName() : "";
        boolean isConnected = !TextUtils.isEmpty(deviceName);
        String inactiveString = mContext.getResources().getString(BT_LABEL_INACTIVE);
        updateTileButtonState(btButton, btButtonFab, isBluetoothEnabled(), 
            BT_ACTIVE, BT_INACTIVE, isConnected ? deviceName : inactiveString, inactiveString);
    }
    
    private boolean isBluetoothEnabled() {
        final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();
    }

    @Nullable
    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    protected static final class WifiCallbackInfo {
        boolean enabled;
        @Nullable
        String ssid;
    }

    protected final class WifiSignalCallback implements SignalCallback {
        final WifiCallbackInfo mInfo = new WifiCallbackInfo();
        @Override
        public void setWifiIndicators(@NonNull WifiIndicators indicators) {
            if (indicators.qsIcon == null) {
                updateWiFiButtonState(false);
                return;
            }
            mInfo.enabled = indicators.enabled;
            mInfo.ssid = indicators.description;
            updateWiFiButtonState(mInfo.enabled);
        }
    }

    private final class CellSignalCallback implements SignalCallback {
        @Override
        public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
            if (indicators.qsIcon == null) {
                updateMobileDataState(false);
                return;
            }
            updateMobileDataState(isMobileDataEnabled());
        }
        @Override
        public void setNoSims(boolean show, boolean simDetected) {
            updateMobileDataState(simDetected && isMobileDataEnabled());
        }
        @Override
        public void setIsAirplaneMode(@NonNull IconState icon) {
            updateMobileDataState(!icon.visible && isMobileDataEnabled());
        }
    }

    // FIX 2: addObserver now requires Context as first argument
    public void enableWeatherUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.addObserver(mContext, this);
            queryAndUpdateWeather();
        }
    }

    // FIX 3: removeObserver now requires Context as first argument
    public void disableWeatherUpdates() {
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(mContext, this);
        }
    }

    @Override
    public void weatherError(int errorReason) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void updateSettings() {
        queryAndUpdateWeather();
    }

    private void queryAndUpdateWeather() {
        try {
            // FIX 4: isOmniJawsEnabled and queryWeather now require Context argument
            // FIX 5: getWeatherConditionImage now requires Context as first argument
            if (mWeatherClient == null || !mWeatherClient.isOmniJawsEnabled(mContext)) return;
            mWeatherClient.queryWeather(mContext);
            mWeatherInfo = mWeatherClient.getWeatherInfo();
            if (mWeatherInfo != null) {
                // OpenWeatherMap
                String formattedCondition = mWeatherInfo.condition;
                if (formattedCondition.toLowerCase().contains("clouds")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_clouds);
                } else if (formattedCondition.toLowerCase().contains("rain")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_rain);
                } else if (formattedCondition.toLowerCase().contains("clear")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_clear);
                } else if (formattedCondition.toLowerCase().contains("storm")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_storm);
                } else if (formattedCondition.toLowerCase().contains("snow")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_snow);
                } else if (formattedCondition.toLowerCase().contains("wind")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_wind);
                } else if (formattedCondition.toLowerCase().contains("mist")) {
                    formattedCondition = mContext.getResources().getString(R.string.weather_condition_mist);
                }
                // MET Norway
                if (formattedCondition.toLowerCase().contains("_")) {
                    final String[] words = formattedCondition.split("_");
                    final StringBuilder formattedConditionBuilder = new StringBuilder();
                    for (String word : words) {
                        final String capitalizedWord = word.substring(0, 1).toUpperCase() + word.substring(1);
                        formattedConditionBuilder.append(capitalizedWord).append(" ");
                    }
                    formattedCondition = formattedConditionBuilder.toString().trim();
                }
                final Drawable d = mWeatherClient.getWeatherConditionImage(mContext, mWeatherInfo.conditionCode);
                if (weatherButtonFab != null) {
                    weatherButtonFab.setIcon(d);
                    weatherButtonFab.setText(mWeatherInfo.temp + mWeatherInfo.tempUnits + " \u2022 " + formattedCondition);
                    weatherButtonFab.setIconTint(null);
                }
                if (weatherButton != null) {
                    weatherButton.setImageDrawable(d);
                    weatherButton.setImageTintList(null);
                }
            }
        } catch(Exception e) {}
    }
        
    private boolean isWidgetEnabled(String widget) {
        return (mMainLockscreenWidgetsList != null 
            && mMainLockscreenWidgetsList.contains(widget)) 
        	|| (mSecondaryLockscreenWidgetsList != null 
        	&& mSecondaryLockscreenWidgetsList.contains(widget));
    }
    
    @Override
    public void onMediaMetadataChanged() {
        updateMediaPlaybackState();
    }

    @Override
    public void onPlaybackStateChanged() {
        updateMediaPlaybackState();
    }
    
    private class LockscreenWidgetsObserver extends ContentObserver {
        public LockscreenWidgetsObserver() {
            super(null);
        }
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            updateSettings();
        }
        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_ENABLED), 
                    false, 
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS), 
                    false, 
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_EXTRAS), 
                    false, 
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_STYLE), 
                    false, 
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(LOCKSCREEN_WIDGETS_TRANSPARENCY), 
                    false, 
                    this);
            updateSettings();
        }
        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }
        void updateSettings() {
            mLockscreenWidgetsEnabled = Settings.System.getInt(mContext.getContentResolver(), 
                             LOCKSCREEN_WIDGETS_ENABLED, 0) == 1;
            mMainLockscreenWidgetsList = Settings.System.getString(mContext.getContentResolver(), 
                           LOCKSCREEN_WIDGETS);
            mSecondaryLockscreenWidgetsList = Settings.System.getString(mContext.getContentResolver(), 
                           LOCKSCREEN_WIDGETS_EXTRAS);
            mThemeStyle = Settings.System.getInt(mContext.getContentResolver(), 
                           LOCKSCREEN_WIDGETS_STYLE, 0);
            mTransparency = Settings.System.getInt(mContext.getContentResolver(), 
                           LOCKSCREEN_WIDGETS_TRANSPARENCY, 30) / 100f;
            if (mMainLockscreenWidgetsList != null) {
                mMainWidgetsList = Arrays.asList(mMainLockscreenWidgetsList.split(","));
            }
            if (mSecondaryLockscreenWidgetsList != null) {
                mSecondaryWidgetsList = Arrays.asList(mSecondaryLockscreenWidgetsList.split(","));
            }
            updateWidgetViews();
        }
    };

    private void updateHotspotState() {
        if (!isWidgetEnabled("hotspot")) return;
        if (hotspotButton == null && hotspotButtonFab == null) return;
        String hotspotString = mContext.getResources().getString(HOTSPOT_LABEL);
        updateTileButtonState(hotspotButton, hotspotButtonFab, mHotspotController.isHotspotEnabled(), 
            HOTSPOT_ACTIVE, HOTSPOT_INACTIVE, hotspotString, hotspotString);
    }

    private void toggleHotspot() {
        mHotspotController.setHotspotEnabled(!mHotspotController.isHotspotEnabled());
        updateHotspotState();
        mHandler.postDelayed(() -> {
            updateHotspotState();
        }, 250);
    }
    
    private final class HotspotCallback implements HotspotController.Callback {
        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            updateHotspotState();
        }
        @Override
        public void onHotspotAvailabilityChanged(boolean available) {}
    }
}
