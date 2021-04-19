/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.Choreographer;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.TaskStackChangeListener;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class for handling device screen shots
 */
@Singleton
public class GlobalScreenshot implements ViewTreeObserver.OnComputeInternalInsetsListener {

    /**
     * POD used in the AsyncTask which saves an image in the background.
     */
    static class SaveImageInBackgroundData {
        public Bitmap image;
        public Consumer<Uri> finisher;
        public GlobalScreenshot.ActionsReadyListener mActionsReadyListener;
        public int errorMsgResId;
        public String appLabel;

        void clearImage() {
            image = null;
        }
    }

    /**
     * Structure returned by the SaveImageInBackgroundTask
     */
    static class SavedImageData {
        public Uri uri;
        public Notification.Action shareAction;
        public Notification.Action editAction;
        public Notification.Action deleteAction;
        public List<Notification.Action> smartActions;

        /**
         * Used to reset the return data on error
         */
        public void reset() {
            uri = null;
            shareAction = null;
            editAction = null;
            deleteAction = null;
            smartActions = null;
        }
    }

    abstract static class ActionsReadyListener {
        abstract void onActionsReady(SavedImageData imageData);
    }

    // These strings are used for communicating the action invoked to
    // ScreenshotNotificationSmartActionsProvider.
    static final String EXTRA_ACTION_TYPE = "android:screenshot_action_type";
    static final String EXTRA_ID = "android:screenshot_id";
    static final String ACTION_TYPE_DELETE = "Delete";
    static final String ACTION_TYPE_SHARE = "Share";
    static final String ACTION_TYPE_EDIT = "Edit";
    static final String EXTRA_SMART_ACTIONS_ENABLED = "android:smart_actions_enabled";
    static final String EXTRA_ACTION_INTENT = "android:screenshot_action_intent";

    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";
    static final String EXTRA_CANCEL_NOTIFICATION = "android:screenshot_cancel_notification";
    static final String EXTRA_DISALLOW_ENTER_PIP = "android:screenshot_disallow_enter_pip";

    // From WizardManagerHelper.java
    private static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    private static final String STITCHIMAGE_APP_PACKAGE_NAME = "com.asus.stitchimage";
    private static final String STITCHIMAGE_OVERLAY_SERVICE_CLASS = "com.asus.stitchimage.OverlayService";
    private static final String EXTRA_KEY_STITCHIMAGE_SETTINGS_CALLFROM = "callfrom";
    private static final String EXTRA_VALUE_STITCHIMAGE_SETTINGS_CALLFROM_ASUSSETTINGS = "AsusSettings";
    private static final String TAG = "GlobalScreenshot";

    private static final long SCREENSHOT_FLASH_IN_DURATION_MS = 133;
    private static final long SCREENSHOT_FLASH_OUT_DURATION_MS = 217;
    // delay before starting to fade in dismiss button
    private static final long SCREENSHOT_TO_CORNER_DISMISS_DELAY_MS = 200;
    private static final long SCREENSHOT_TO_CORNER_X_DURATION_MS = 234;
    private static final long SCREENSHOT_TO_CORNER_Y_DURATION_MS = 500;
    private static final long SCREENSHOT_TO_CORNER_SCALE_DURATION_MS = 234;
    private static final long SCREENSHOT_ACTIONS_EXPANSION_DURATION_MS = 200;
    private static final long SCREENSHOT_ACTIONS_ALPHA_DURATION_MS = 10;
    private static final long SCREENSHOT_DISMISS_Y_DURATION_MS = 350;
    private static final long SCREENSHOT_DISMISS_ALPHA_DURATION_MS = 183;
    private static final long SCREENSHOT_DISMISS_ALPHA_OFFSET_MS = 50; // delay before starting fade
    private static final float SCREENSHOT_ACTIONS_START_SCALE_X = .7f;
    private static final float ROUNDED_CORNER_RADIUS = .05f;
    private static final int SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS = 3000;
    private static final int MESSAGE_CORNER_TIMEOUT = 2;

    private final Interpolator mAccelerateInterpolator = new AccelerateInterpolator();

    private static ScreenshotNotificationsController mNotificationsController;
    private final UiEventLogger mUiEventLogger;

    private final Context mContext;
    private final ScreenshotSmartActions mScreenshotSmartActions;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final Display mDisplay;
    private final DisplayMetrics mDisplayMetrics;

    private View mScreenshotLayout;
    private LinearLayout mScreenshotButtonsLayout;
    private ScreenshotSelectorView mScreenshotSelectorView;
    private ImageView mScreenshotAnimatedView;
    private ImageView mScreenshotPreview;
    private ImageView mScreenshotFlash;
    private ImageView mActionsContainerBackground;
    private HorizontalScrollView mActionsContainer;
    private LinearLayout mActionsView;
    private ImageView mBackgroundProtection;
    private View mCaptureButton;
    private View mCancelButton;
    private FrameLayout mDismissButton;

    private Bitmap mScreenBitmap;
    private SaveImageInBackgroundTask mSaveInBgTask;
    private Animator mScreenshotAnimation;
    private Runnable mOnCompleteRunnable;
    private Animator mDismissAnimation;
    private SavedImageData mImageData;
    private boolean mInDarkMode;
    private boolean mDirectionLTR;
    private boolean mOrientationPortrait;
    private boolean isFullScreenshot;

    private float mCornerSizeX;
    private float mDismissDeltaY;

    private MediaActionSound mCameraSound;
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private CameraManager mCameraManager;
    private int mCamsInUse = 0;

    private int mNavMode;
    private int mLeftInset;
    private int mRightInset;

    private LayoutInflater inflater;

    // standard material ease
    private final Interpolator mFastOutSlowIn;

    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CORNER_TIMEOUT:
                    mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT);
                    if (mImageData != null) {
                        mNotificationsController.showSilentScreenshotNotification(mImageData);
                    }
                    GlobalScreenshot.this.dismissScreenshot("timeout", false);
                    mOnCompleteRunnable.run();
                    break;
                default:
                    break;
            }
        }
    };

    private ComponentName mTaskComponentName;
    private PackageManager mPm;

    private final Executor mUiBgExecutor;
    private final TaskStackChangeListener mTaskListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            mUiBgExecutor.execute(() -> {
                try {
                    final ActivityManager.StackInfo focusedStack =
                            ActivityTaskManager.getService().getFocusedStackInfo();
                    if (focusedStack != null && focusedStack.topActivity != null) {
                        mTaskComponentName = focusedStack.topActivity;
                    }
                } catch (Exception e) {}
            });
        }
    };

    private String getForegroundAppLabel() {
        try {
            final ActivityInfo ai = mPm.getActivityInfo(mTaskComponentName, 0);
            return ai.applicationInfo.loadLabel(mPm).toString();
        } catch (PackageManager.NameNotFoundException e) {
             return null;
        }
    }

    @Inject
    public GlobalScreenshot(
            Context context, @Main Resources resources,
            ScreenshotSmartActions screenshotSmartActions,
            ScreenshotNotificationsController screenshotNotificationsController,
            UiEventLogger uiEventLogger, @UiBackground Executor uiBgExecutor) {
        mContext = context;
        mScreenshotSmartActions = screenshotSmartActions;
        mNotificationsController = screenshotNotificationsController;
        mUiEventLogger = uiEventLogger;

        reloadAssets();
        Configuration config = mContext.getResources().getConfiguration();
        mInDarkMode = config.isNightModeActive();
        mDirectionLTR = config.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
        mOrientationPortrait = config.orientation == ORIENTATION_PORTRAIT;

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowLayoutParams.setFitInsetsTypes(0 /* types */);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        mCornerSizeX = resources.getDimensionPixelSize(R.dimen.global_screenshot_x_scale);
        mDismissDeltaY = resources.getDimensionPixelSize(R.dimen.screenshot_dismissal_height_delta);

        mFastOutSlowIn =
                AnimationUtils.loadInterpolator(mContext, android.R.interpolator.fast_out_slow_in);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);

        // Grab system services needed for screenshot sound
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mCameraManager.registerAvailabilityCallback(mCamCallback,
                new Handler(Looper.getMainLooper()));

        // Store UI background executor
        mUiBgExecutor = uiBgExecutor;

        // Grab PackageManager
        mPm = mContext.getPackageManager();

        // Register task stack listener
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskListener);

        // Initialize current foreground package name
        mTaskListener.onTaskStackChanged();
    }

    @Override // ViewTreeObserver.OnComputeInternalInsetsListener
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        Region touchRegion = new Region();

        Rect screenshotRect = new Rect();
        mScreenshotPreview.getBoundsOnScreen(screenshotRect);
        touchRegion.op(screenshotRect, Region.Op.UNION);
        Rect actionsRect = new Rect();
        mActionsContainer.getBoundsOnScreen(actionsRect);
        touchRegion.op(actionsRect, Region.Op.UNION);
        Rect dismissRect = new Rect();
        mDismissButton.getBoundsOnScreen(dismissRect);
        touchRegion.op(dismissRect, Region.Op.UNION);

        if (QuickStepContract.isGesturalMode(mNavMode)) {
            // Receive touches in gesture insets such that they don't cause TOUCH_OUTSIDE
            Rect inset = new Rect(0, 0, mLeftInset, mDisplayMetrics.heightPixels);
            touchRegion.op(inset, Region.Op.UNION);
            inset.set(mDisplayMetrics.widthPixels - mRightInset, 0, mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels);
            touchRegion.op(inset, Region.Op.UNION);
        }

        inoutInfo.touchableRegion.set(touchRegion);
    }

    void takeScreenshotFullscreen(Consumer<Uri> finisher, Runnable onComplete) {
        mOnCompleteRunnable = onComplete;
        mDisplay.getRealMetrics(mDisplayMetrics);

        try {
            Log.i(TAG, "Take full screenshot.");
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(STITCHIMAGE_APP_PACKAGE_NAME, STITCHIMAGE_OVERLAY_SERVICE_CLASS));
            intent.putExtra(EXTRA_KEY_STITCHIMAGE_SETTINGS_CALLFROM, EXTRA_VALUE_STITCHIMAGE_SETTINGS_CALLFROM_ASUSSETTINGS);
            mContext.startService(intent);
            isFullScreenshot = true;
        } catch (Exception e) {
            Log.e(TAG, "Trigger stitchimage failed, Exception :" + e);
        }

        mScreenshotHandler.postDelayed(() -> {
            finisher.accept(null);
            mOnCompleteRunnable.run();
            isFullScreenshot = false;
        }, 1500);
    }

    void handleImageAsScreenshot(Bitmap screenshot, Rect screenshotScreenBounds,
            Insets visibleInsets, int taskId, int userId, ComponentName topComponent,
            Consumer<Uri> finisher, Runnable onComplete) {
        // TODO: use task Id, userId, topComponent for smart handler

        mOnCompleteRunnable = onComplete;
        if (aspectRatiosMatch(screenshot, visibleInsets, screenshotScreenBounds)) {
            saveScreenshot(screenshot, finisher, screenshotScreenBounds, visibleInsets, false);
        } else {
            saveScreenshot(screenshot, finisher,
                    new Rect(0, 0, screenshot.getWidth(), screenshot.getHeight()), Insets.NONE,
                    true);
        }
    }

    void hideScreenshotSelector() {
        setLockedScreenOrientation(false);
        if (mScreenshotLayout.getWindowToken() != null) {
            mWindowManager.removeView(mScreenshotLayout);
        }
        mScreenshotSelectorView.stopSelection();
        mScreenshotSelectorView.setVisibility(View.GONE);
        mCaptureButton.setVisibility(View.GONE);
        setBlockedGesturalNavigation(false);
    }

    void setBlockedGesturalNavigation(boolean blocked) {
        IStatusBarService service = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        if (service != null) {
            try {
                service.setBlockedGesturalNavigation(blocked);
            } catch (RemoteException e) {
                // end of the world
            }
        }
    }

    void setLockedScreenOrientation(boolean locked) {
        mWindowLayoutParams.screenOrientation = locked
                ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    Rect getRotationAdjustedRect(Rect rect) {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();
        Rect adjustedRect = new Rect(rect);

        mDisplay.getRealMetrics(mDisplayMetrics);
        int rotation = defaultDisplay.getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                // properly rotated
                break;
            case Surface.ROTATION_90:
                adjustedRect.top = mDisplayMetrics.heightPixels - rect.bottom;
                adjustedRect.bottom = mDisplayMetrics.heightPixels - rect.top;
                break;
            case Surface.ROTATION_180:
                adjustedRect.left = mDisplayMetrics.widthPixels - rect.right;
                adjustedRect.top = mDisplayMetrics.heightPixels - rect.bottom;
                adjustedRect.right = mDisplayMetrics.widthPixels - rect.left;
                adjustedRect.bottom = mDisplayMetrics.heightPixels - rect.top;
                break;
            case Surface.ROTATION_270:
                adjustedRect.left = mDisplayMetrics.widthPixels - rect.right;
                adjustedRect.right = mDisplayMetrics.widthPixels - rect.left;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        return adjustedRect;
    }

    /**
     * Displays a screenshot selector
     */
    @SuppressLint("ClickableViewAccessibility")
    void takeScreenshotPartial(final Consumer<Uri> finisher, Runnable onComplete) {
        if (isFullScreenshot) {
            finisher.accept(null);
            return;
        }

        dismissScreenshot("new screenshot requested", true);
        mOnCompleteRunnable = onComplete;

        setBlockedGesturalNavigation(true);
        setLockedScreenOrientation(true);
        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        mScreenshotSelectorView.setSelectionListener((rect, firstSelection) -> {
            if (firstSelection) {
                mScreenshotLayout.post(() -> mCaptureButton.setVisibility(View.VISIBLE));
            }
        });
        mCancelButton.setOnClickListener(v -> {
            mScreenshotLayout.post(() -> {
                finisher.accept(null);
                hideScreenshotSelector();
            });
        });
        mCaptureButton.setOnClickListener(v -> {
            Rect rect = mScreenshotSelectorView.getSelectionRect();
            final Rect adjustedRect = getRotationAdjustedRect(rect);
            LayoutTransition layoutTransition = mScreenshotButtonsLayout.getLayoutTransition();
            layoutTransition.addTransitionListener(new TransitionListener() {
                @Override
                public void startTransition(LayoutTransition transition, ViewGroup container,
                        View view, int transitionType) {
                }

                @Override
                public void endTransition(LayoutTransition transition, ViewGroup container,
                        View view, int transitionType) {
                    takeScreenshotInternal(finisher, adjustedRect);
                    transition.removeTransitionListener(this);
                	if (Settings.System.getIntForUser(mContext.getContentResolver(),
                        	Settings.System.SCREENSHOT_SOUND, 1, UserHandle.USER_CURRENT) != 0) {
                    	playShutterSound();
                	}
                }
            });
            mScreenshotLayout.post(() -> hideScreenshotSelector());
        });
        mScreenshotLayout.post(() -> {
            mScreenshotSelectorView.setVisibility(View.VISIBLE);
            mScreenshotSelectorView.requestFocus();
        });
    }

    /**
     * Cancels screenshot request
     */
    void stopScreenshot() {
        // If the selector layer still presents on screen, we hide it.
        if (mScreenshotLayout.getParent() != null) {
            hideScreenshotSelector();
        }
    }

    /**
     * Clears current screenshot
     */
    void dismissScreenshot(String reason, boolean immediate) {
        Log.v(TAG, "clearing screenshot: " + reason);
        mScreenshotHandler.removeMessages(MESSAGE_CORNER_TIMEOUT);
        mScreenshotLayout.getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        if (!immediate) {
            mDismissAnimation = createScreenshotDismissAnimation();
            mDismissAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    clearScreenshot();
                }
            });
            mDismissAnimation.start();
        } else {
            clearScreenshot();
        }
    }

    private void onConfigChanged(Configuration newConfig) {
        boolean needsUpdate = false;
        // dark mode
        if (newConfig.isNightModeActive()) {
            // Night mode is active, we're using dark theme
            if (!mInDarkMode) {
                mInDarkMode = true;
                needsUpdate = true;
            }
        } else {
            // Night mode is not active, we're using the light theme
            if (mInDarkMode) {
                mInDarkMode = false;
                needsUpdate = true;
            }
        }

        // RTL configuration
        switch (newConfig.getLayoutDirection()) {
            case View.LAYOUT_DIRECTION_LTR:
                if (!mDirectionLTR) {
                    mDirectionLTR = true;
                    needsUpdate = true;
                }
                break;
            case View.LAYOUT_DIRECTION_RTL:
                if (mDirectionLTR) {
                    mDirectionLTR = false;
                    needsUpdate = true;
                }
                break;
        }

        // portrait/landscape orientation
        switch (newConfig.orientation) {
            case ORIENTATION_PORTRAIT:
                if (!mOrientationPortrait) {
                    mOrientationPortrait = true;
                    needsUpdate = true;
                }
                break;
            case ORIENTATION_LANDSCAPE:
                if (mOrientationPortrait) {
                    mOrientationPortrait = false;
                    needsUpdate = true;
                }
                break;
        }

        if (needsUpdate) {
            reloadAssets();
        }

        mNavMode = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
    }

    /**
     * Update assets (called when the dark theme status changes). We only need to update the dismiss
     * button and the actions container background, since the buttons are re-inflated on demand.
     */
    private void reloadAssets() {
        boolean wasAttached = mScreenshotLayout != null && mScreenshotLayout.isAttachedToWindow();
        if (wasAttached) {
            mWindowManager.removeView(mScreenshotLayout);
        }

        // Inflate the screenshot layout
        mScreenshotLayout = LayoutInflater.from(mContext).inflate(R.layout.global_screenshot, null);
        mScreenshotButtonsLayout = mScreenshotLayout.findViewById(R.id.global_screenshot_buttons);
        // TODO(159460485): Remove this when focus is handled properly in the system
        mScreenshotLayout.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                // Once the user touches outside, stop listening for input
                setWindowFocusable(false);
            }
            return false;
        });
        mScreenshotLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            if (QuickStepContract.isGesturalMode(mNavMode)) {
                Insets gestureInsets = insets.getInsets(
                        WindowInsets.Type.systemGestures());
                mLeftInset = gestureInsets.left;
                mRightInset = gestureInsets.right;
            } else {
                mLeftInset = mRightInset = 0;
            }
            return mScreenshotLayout.onApplyWindowInsets(insets);
        });
        mScreenshotLayout.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissScreenshot("back pressed", false);
                return true;
            }
            return false;
        });
        // Get focus so that the key events go to the layout.
        mScreenshotLayout.setFocusableInTouchMode(true);
        mScreenshotLayout.requestFocus();

        mScreenshotAnimatedView =
                mScreenshotLayout.findViewById(R.id.global_screenshot_animated_view);
        mScreenshotAnimatedView.setClipToOutline(true);
        mScreenshotAnimatedView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(new Rect(0, 0, view.getWidth(), view.getHeight()),
                        ROUNDED_CORNER_RADIUS * view.getWidth());
            }
        });
        mScreenshotPreview = mScreenshotLayout.findViewById(R.id.global_screenshot_preview);
        mScreenshotPreview.setClipToOutline(true);
        mScreenshotPreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(new Rect(0, 0, view.getWidth(), view.getHeight()),
                        ROUNDED_CORNER_RADIUS * view.getWidth());
            }
        });

        mActionsContainerBackground = mScreenshotLayout.findViewById(
                R.id.global_screenshot_actions_container_background);
        mActionsContainer = mScreenshotLayout.findViewById(
                R.id.global_screenshot_actions_container);
        mActionsView = mScreenshotLayout.findViewById(R.id.global_screenshot_actions);
        mBackgroundProtection = mScreenshotLayout.findViewById(
                R.id.global_screenshot_actions_background);
        mCaptureButton = mScreenshotLayout.findViewById(R.id.global_screenshot_selector_capture);
        mCancelButton = mScreenshotLayout.findViewById(R.id.global_screenshot_selector_cancel);
        mDismissButton = mScreenshotLayout.findViewById(R.id.global_screenshot_dismiss_button);
        mDismissButton.setOnClickListener(view -> {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_EXPLICIT_DISMISSAL);
            if (mImageData != null) {
                mNotificationsController.showSilentScreenshotNotification(mImageData);
            }
            dismissScreenshot("dismiss_button", false);
            mOnCompleteRunnable.run();
        });

        mScreenshotFlash = mScreenshotLayout.findViewById(R.id.global_screenshot_flash);
        mScreenshotSelectorView = mScreenshotLayout.findViewById(R.id.global_screenshot_selector);
        mScreenshotLayout.setFocusable(true);
        mScreenshotSelectorView.setFocusable(true);
        mScreenshotSelectorView.setFocusableInTouchMode(true);
        mScreenshotAnimatedView.setPivotX(0);
        mScreenshotAnimatedView.setPivotY(0);
        mActionsContainer.setScrollX(0);

        if (wasAttached) {
            mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        }
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshotInternal(Consumer<Uri> finisher, Rect crop) {
        // Dismiss the old screenshot first to prevent it from showing up in the new screenshot
        dismissScreenshot("new screenshot requested", true);

        // Force a new frame to be rendered now that the old screenshot has been cleared
        mScreenshotLayout.getRootView().invalidate();
        Choreographer.getInstance().postFrameCallback(time1 -> {
            // Unfortunately, we need to introduce another frame of latency because this
            // is a pre-draw callback
            mScreenshotLayout.getRootView().invalidate();

            // Finally, take the screenshot once we're sure that old screenshot view is gone
            Choreographer.getInstance().postFrameCallback(time2 -> {
                // copy the input Rect, since SurfaceControl.screenshot can mutate it
                Rect screenRect = new Rect(crop);
                int rot = mDisplay.getRotation();
                int width = crop.width();
                int height = crop.height();
                saveScreenshot(SurfaceControl.screenshot(crop, width, height, rot), finisher, screenRect,
                        Insets.NONE, true);
            });
        });
    }

    private void saveScreenshot(Bitmap screenshot, Consumer<Uri> finisher, Rect screenRect,
            Insets screenInsets, boolean showFlash) {
        if (mScreenshotLayout.isAttachedToWindow()) {
            // if we didn't already dismiss for another reason
            if (mDismissAnimation == null || !mDismissAnimation.isRunning()) {
                mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED);
            }
            dismissScreenshot("new screenshot requested", true);
        }

        mScreenBitmap = screenshot;

        if (mScreenBitmap == null) {
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
            finisher.accept(null);
            mOnCompleteRunnable.run();
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        onConfigChanged(mContext.getResources().getConfiguration());

        if (mDismissAnimation != null && mDismissAnimation.isRunning()) {
            mDismissAnimation.cancel();
        }

        // The window is focusable by default
        setWindowFocusable(true);

        saveScreenshotInWorkerThread(finisher, new ActionsReadyListener() {
            @Override
            void onActionsReady(SavedImageData imageData) {
                mNotificationsController.showSilentScreenshotNotification(imageData);
            }
        });
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(
            Consumer<Uri> finisher, @Nullable ActionsReadyListener actionsReadyListener) {
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.image = mScreenBitmap;
        data.finisher = finisher;
        data.mActionsReadyListener = actionsReadyListener;
        data.appLabel = getForegroundAppLabel();

        if (mSaveInBgTask != null) {
            // just log success/failure for the pre-existing screenshot
            mSaveInBgTask.setActionsReadyListener(new ActionsReadyListener() {
                @Override
                void onActionsReady(SavedImageData imageData) {
                    logSuccessOnActionsReady(imageData);
                }
            });
        }

        mImageData = null; // make sure we clear the current stored data
        mNotificationsController.reset();
        mNotificationsController.setImage(mScreenBitmap);

        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, mScreenshotSmartActions, data);
        mSaveInBgTask.execute();
    }

    /**
     * Logs success/failure of the screenshot saving task, and shows an error if it failed.
     */
    private void logSuccessOnActionsReady(SavedImageData imageData) {
        if (imageData.uri == null) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED);
            mNotificationsController.notifyScreenshotError(
                    R.string.screenshot_failed_to_capture_text);
        } else {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED);
        }
    }

    private AnimatorSet createScreenshotDismissAnimation() {
        ValueAnimator alphaAnim = ValueAnimator.ofFloat(0, 1);
        alphaAnim.setStartDelay(SCREENSHOT_DISMISS_ALPHA_OFFSET_MS);
        alphaAnim.setDuration(SCREENSHOT_DISMISS_ALPHA_DURATION_MS);
        alphaAnim.addUpdateListener(animation -> {
            mScreenshotLayout.setAlpha(1 - animation.getAnimatedFraction());
        });

        ValueAnimator yAnim = ValueAnimator.ofFloat(0, 1);
        yAnim.setInterpolator(mAccelerateInterpolator);
        yAnim.setDuration(SCREENSHOT_DISMISS_Y_DURATION_MS);
        float screenshotStartY = mScreenshotPreview.getTranslationY();
        float dismissStartY = mDismissButton.getTranslationY();
        yAnim.addUpdateListener(animation -> {
            float yDelta = MathUtils.lerp(0, mDismissDeltaY, animation.getAnimatedFraction());
            mScreenshotPreview.setTranslationY(screenshotStartY + yDelta);
            mDismissButton.setTranslationY(dismissStartY + yDelta);
            mActionsContainer.setTranslationY(yDelta);
            mActionsContainerBackground.setTranslationY(yDelta);
        });

        AnimatorSet animSet = new AnimatorSet();
        animSet.play(yAnim).with(alphaAnim);

        return animSet;
    }

    private void clearScreenshot() {
        if (mScreenshotLayout.isAttachedToWindow()) {
            mWindowManager.removeView(mScreenshotLayout);
        }

        // Clear any references to the bitmap
        mScreenshotPreview.setImageDrawable(null);
        mScreenshotAnimatedView.setImageDrawable(null);
        mScreenshotAnimatedView.setVisibility(View.GONE);
        mActionsContainerBackground.setVisibility(View.GONE);
        mActionsContainer.setVisibility(View.GONE);
        mBackgroundProtection.setAlpha(0f);
        mDismissButton.setVisibility(View.GONE);
        mScreenshotPreview.setVisibility(View.GONE);
        mScreenshotPreview.setLayerType(View.LAYER_TYPE_NONE, null);
        mScreenshotPreview.setContentDescription(
                mContext.getResources().getString(R.string.screenshot_preview_description));
        mScreenshotPreview.setOnClickListener(null);
        mScreenshotLayout.setAlpha(1);
        mDismissButton.setTranslationY(0);
        mActionsContainer.setTranslationY(0);
        mActionsContainerBackground.setTranslationY(0);
        mScreenshotPreview.setTranslationY(0);
        hideScreenshotSelector();
    }

    /**
     * Updates the window focusability.  If the window is already showing, then it updates the
     * window immediately, otherwise the layout params will be applied when the window is next
     * shown.
     */
    private void setWindowFocusable(boolean focusable) {
        if (focusable) {
            mWindowLayoutParams.flags &= ~FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= FLAG_NOT_FOCUSABLE;
        }
        if (mScreenshotLayout.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(mScreenshotLayout, mWindowLayoutParams);
        }
    }

    /** Does the aspect ratio of the bitmap with insets removed match the bounds. */
    private boolean aspectRatiosMatch(Bitmap bitmap, Insets bitmapInsets, Rect screenBounds) {
        int insettedWidth = bitmap.getWidth() - bitmapInsets.left - bitmapInsets.right;
        int insettedHeight = bitmap.getHeight() - bitmapInsets.top - bitmapInsets.bottom;

        if (insettedHeight == 0 || insettedWidth == 0 || bitmap.getWidth() == 0
                || bitmap.getHeight() == 0) {
            Log.e(TAG, String.format(
                    "Provided bitmap and insets create degenerate region: %dx%d %s",
                    bitmap.getWidth(), bitmap.getHeight(), bitmapInsets));
            return false;
        }

        float insettedBitmapAspect = ((float) insettedWidth) / insettedHeight;
        float boundsAspect = ((float) screenBounds.width()) / screenBounds.height();

        boolean matchWithinTolerance = Math.abs(insettedBitmapAspect - boundsAspect) < 0.1f;
        if (!matchWithinTolerance) {
            Log.d(TAG, String.format("aspectRatiosMatch: don't match bitmap: %f, bounds: %f",
                    insettedBitmapAspect, boundsAspect));
        }

        return matchWithinTolerance;
    }

    private void playShutterSound() {
        boolean playSound = readCameraSoundForced() && mCamsInUse > 0;

        switch (mAudioManager.getRingerMode()) {
            case AudioManager.RINGER_MODE_SILENT:
                // do nothing
                break;
            case AudioManager.RINGER_MODE_VIBRATE:
                if (mVibrator != null && mVibrator.hasVibrator()) {
                    mVibrator.vibrate(VibrationEffect.createOneShot(50,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                }
                break;
            case AudioManager.RINGER_MODE_NORMAL:
                // in this case we want to play sound even if not forced on
                playSound = true;
                break;
        }

        // We want to play the shutter sound when it's either forced or
        // when we use normal ringer mode
        if (playSound) {
            mCameraSound.play(MediaActionSound.SHUTTER_CLICK);
        }
    }

    private CameraManager.AvailabilityCallback mCamCallback =
            new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraOpened(String cameraId, String packageId) {
            mCamsInUse++;
        }

        @Override
        public void onCameraClosed(String cameraId) {
            mCamsInUse--;
        }
    };

    private boolean readCameraSoundForced() {
        return SystemProperties.getBoolean("audio.camerasound.force", false) ||
                mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_camera_sound_forced);
    }
}
