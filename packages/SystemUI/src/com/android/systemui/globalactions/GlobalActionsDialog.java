/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2022 droid-ng
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE_AFTER_UNLOCK;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.telecom.TelecomManager;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.Log;
import android.view.IWindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.view.RotationPolicy;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.ControlsServiceInfo;
import com.android.systemui.controls.controller.ControlsController;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsAnimations;
import com.android.systemui.controls.ui.ControlsUiController;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that may show depending
 * on whether the keyguard is showing, and whether the device is provisioned.
 * This version includes wallet and controls.
 */
public class GlobalActionsDialog extends GlobalActionsDialogLite
        implements DialogInterface.OnDismissListener,
        DialogInterface.OnShowListener,
        ConfigurationController.ConfigurationListener,
        GlobalActionsPanelPlugin.Callbacks,
        LifecycleOwner {

    private static final String TAG = "GlobalActionsDialog";

    public static final String PREFS_CONTROLS_SEEDING_COMPLETED = "SeedingCompleted";
    public static final String PREFS_CONTROLS_FILE = "controls_prefs";
    private static final int SEEDING_MAX = 2;

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardStateController mKeyguardStateController;
    private final ActivityStarter mActivityStarter;
    private final SysuiColorExtractor mSysuiColorExtractor;
    private final IStatusBarService mCentralSurfacesService;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private GlobalActionsPanelPlugin mWalletPlugin;
    private Optional<ControlsUiController> mControlsUiControllerOptional;
    private List<ControlsServiceInfo> mControlsServiceInfos = new ArrayList<>();
    private ControlsComponent mControlsComponent;
    private Optional<ControlsController> mControlsControllerOptional;
    private UserContextProvider mUserContextProvider;
    @VisibleForTesting
    boolean mShowLockScreenCardsAndControls = false;

    private final KeyguardStateController.Callback mKeyguardStateControllerListener =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            if (mDialog != null) {
                ActionsDialog dialog = (ActionsDialog) mDialog;
                boolean unlocked = mKeyguardStateController.isUnlocked();
                if (dialog.mWalletViewController != null) {
                    dialog.mWalletViewController.onDeviceLockStateChanged(!unlocked);
                }
                if (!dialog.isShowingControls() && mControlsComponent.getVisibility() == AVAILABLE) {
                    dialog.showControls(mControlsUiControllerOptional.get());
                }
                if (unlocked) {
                    dialog.hideLockMessage();
                }
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onPowerMenuLockScreenSettingsChanged();
        }
    };

    /**
     * @param context everything needs a context :(
     */
    @Inject
    public GlobalActionsDialog(
            Context context,
            GlobalActionsManager windowManagerFuncs,
            AudioManager audioManager,
            IDreamManager iDreamManager,
            DevicePolicyManager devicePolicyManager,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            GlobalSettings globalSettings,
            SecureSettings secureSettings,
            @NonNull VibratorHelper vibrator,
            @Main Resources resources,
            ConfigurationController configurationController,
            ActivityStarter activityStarter,
            KeyguardStateController keyguardStateController,
            UserManager userManager,
            TrustManager trustManager,
            IActivityManager iActivityManager,
            @Nullable TelecomManager telecomManager,
            MetricsLogger metricsLogger,
            SysuiColorExtractor colorExtractor,
            IStatusBarService statusBarService,
            NotificationShadeWindowController notificationShadeWindowController,
            IWindowManager iWindowManager,
            @Background Executor backgroundExecutor,
            UiEventLogger uiEventLogger,
            RingerModeTracker ringerModeTracker,
            @Main Handler handler,
            UserContextProvider userContextProvider,
            PackageManager packageManager,
            Optional<CentralSurfaces> statusBarOptional,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DialogLaunchAnimator dialogLaunchAnimator,
            ControlsComponent controlsComponent) {

        super(context,
                windowManagerFuncs,
                audioManager,
                iDreamManager,
                devicePolicyManager,
                lockPatternUtils,
                broadcastDispatcher,
                globalSettings,
                secureSettings,
                vibrator,
                resources,
                configurationController,
                keyguardStateController,
                userManager,
                trustManager,
                iActivityManager,
                telecomManager,
                metricsLogger,
                colorExtractor,
                statusBarService,
                notificationShadeWindowController,
                iWindowManager,
                backgroundExecutor,
                uiEventLogger,
                ringerModeTracker,
                handler,
                packageManager,
                statusBarOptional,
                keyguardUpdateMonitor,
                dialogLaunchAnimator,
                controlsComponent);

        mLockPatternUtils = lockPatternUtils;
        mKeyguardStateController = keyguardStateController;
        mSysuiColorExtractor = colorExtractor;
        mCentralSurfacesService = statusBarService;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mControlsComponent = controlsComponent;
        mControlsUiControllerOptional = controlsComponent.getControlsUiController();
        mControlsControllerOptional = controlsComponent.getControlsController();
        mUserContextProvider = userContextProvider;
        mActivityStarter = activityStarter;

        mKeyguardStateController.addCallback(mKeyguardStateControllerListener);

        if (mControlsComponent.getControlsListingController().isPresent()) {
            mControlsComponent.getControlsListingController().get()
                    .addCallback(list -> {
                        mControlsServiceInfos = list;
                        // This callback may occur after the dialog has been shown. If so, add
                        // controls into the already visible space or show the lock msg if needed.
                        if (mDialog != null) {
                            ActionsDialog dialog = (ActionsDialog) mDialog;
                            if (!dialog.isShowingControls()
                                    && mControlsComponent.getVisibility() == AVAILABLE) {
                                dialog.showControls(mControlsUiControllerOptional.get());
                            } else if (shouldShowLockMessage(dialog)) {
                                dialog.showLockMessage();
                            }
                        }
                    });
        }

        // Listen for changes to show controls on the power menu while locked
        onPowerMenuLockScreenSettingsChanged();
        mGlobalSettings.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT),
                false /* notifyForDescendants */,
                mSettingsObserver);
    }

    @Override
    protected void onRefresh() {
        super.onRefresh();
    }

    @Override
    public void destroy() {
        super.destroy();
        mKeyguardStateController.removeCallback(mKeyguardStateControllerListener);
        mGlobalSettings.unregisterContentObserver(mSettingsObserver);
    }

    /**
     * See if any available control service providers match one of the preferred components. If
     * they do, and there are no current favorites for that component, query the preferred
     * component for a limited number of suggested controls.
     */
    private void seedFavorites() {
        if (!mControlsControllerOptional.isPresent()
                || mControlsServiceInfos.isEmpty()) {
            return;
        }

        String[] preferredControlsPackages = getContext().getResources()
                .getStringArray(com.android.systemui.R.array.config_controlsPreferredPackages);

        SharedPreferences prefs = mUserContextProvider.getUserContext()
                .getSharedPreferences(PREFS_CONTROLS_FILE, Context.MODE_PRIVATE);
        Set<String> seededPackages = prefs.getStringSet(PREFS_CONTROLS_SEEDING_COMPLETED,
                Collections.emptySet());

        List<ComponentName> componentsToSeed = new ArrayList<>();
        for (int i = 0; i < Math.min(SEEDING_MAX, preferredControlsPackages.length); i++) {
            String pkg = preferredControlsPackages[i];
            for (ControlsServiceInfo info : mControlsServiceInfos) {
                if (!pkg.equals(info.componentName.getPackageName())) continue;
                if (seededPackages.contains(pkg)) {
                    break;
                } else if (mControlsControllerOptional.get()
                        .countFavoritesForComponent(info.componentName) > 0) {
                    // When there are existing controls but no saved preference, assume it
                    // is out of sync, perhaps through a device restore, and update the
                    // preference
                    addPackageToSeededSet(prefs, pkg);
                    break;
                }
                componentsToSeed.add(info.componentName);
                break;
            }
        }

        if (componentsToSeed.isEmpty()) return;

        mControlsControllerOptional.get().seedFavoritesForComponents(
                componentsToSeed,
                (response) -> {
                    Log.d(TAG, "Controls seeded: " + response);
                    if (response.getAccepted()) {
                        addPackageToSeededSet(prefs, response.getPackageName());
                    }
                });
    }

    private void addPackageToSeededSet(SharedPreferences prefs, String pkg) {
        Set<String> seededPackages = prefs.getStringSet(PREFS_CONTROLS_SEEDING_COMPLETED,
                Collections.emptySet());
        Set<String> updatedPkgs = new HashSet<>(seededPackages);
        updatedPkgs.add(pkg);
        prefs.edit().putStringSet(PREFS_CONTROLS_SEEDING_COMPLETED, updatedPkgs).apply();
    }

    /**
     * Show the global actions dialog (creating if necessary)
     *
     * @param keyguardShowing True if keyguard is showing
     */
    @Override
    public void showOrHideDialog(boolean keyguardShowing, boolean isDeviceProvisioned,
            @Nullable View v, GlobalActionsPanelPlugin walletPlugin) {
        mWalletPlugin = walletPlugin;
        super.showOrHideDialog(keyguardShowing, isDeviceProvisioned, null);
    }

    @Override
    protected void handleShow(View v) {
        seedFavorites();
        super.handleShow(null);
    }

    /**
     * Returns the maximum number of power menu items to show based on which GlobalActions
     * layout is being used.
     */
    @VisibleForTesting
    @Override
    protected int getMaxShownPowerItems() {
        return getContext().getResources().getInteger(
                com.android.systemui.R.integer.power_menu_max_columns);
    }

    /**
     * Create the global actions dialog.
     *
     * @return A new dialog.
     */
    @Override
    protected ActionsDialogLite createDialog() {
        initDialogItems();

        ControlsUiController uiController = null;
        if (mControlsComponent.getVisibility() == AVAILABLE) {
            uiController = mControlsUiControllerOptional.get();
        }
        ActionsDialog dialog = new ActionsDialog(getContext(), mAdapter, mOverflowAdapter,
        this::getWalletViewController, mSysuiColorExtractor,
                mCentralSurfacesService, mNotificationShadeWindowController,
                controlsAvailable(), uiController, this::onRefresh, mKeyguardShowing,
                mPowerAdapter, mRestartAdapter, mUsersAdapter, mUiEventLogger,
                mCentralSurfacesOptional, mKeyguardUpdateMonitor,
                mLockPatternUtils);

        dialog.setCanceledOnTouchOutside(false); // Handled by the custom class.
        dialog.setOnDismissListener(this);
        dialog.setOnShowListener(this);

        return dialog;
    }

    @Nullable
    private GlobalActionsPanelPlugin.PanelViewController getWalletViewController() {
        if (mWalletPlugin == null) {
            return null;
        }
        return mWalletPlugin.onPanelShown(this, !mKeyguardStateController.isUnlocked());
    }

    /**
     * Implements {@link GlobalActionsPanelPlugin.Callbacks#dismissGlobalActionsMenu()}, which is
     * called when the quick access wallet requests that an intent be started (with lock screen
     * shown first if needed).
     */
    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent pendingIntent) {
        mActivityStarter.startPendingIntentDismissingKeyguard(pendingIntent);
    }

    @Override
    protected int getEmergencyTextColor(Context context, boolean dummy) {
        return context.getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_text);
    }

    @Override
    protected int getEmergencyIconColor(Context context, boolean dummy) {
        return getContext().getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_text);
    }

    @Override
    protected int getEmergencyBackgroundColor(Context context, boolean dummy) {
        return getContext().getResources().getColor(
                com.android.systemui.R.color.global_actions_emergency_background);
    }

    @Override
    protected int getGridItemLayoutResource() {
        return com.android.systemui.R.layout.global_actions_grid_item_v2;
    }

    @VisibleForTesting
    class ActionsDialog extends ActionsDialogLite {

        private final Provider<GlobalActionsPanelPlugin.PanelViewController> mWalletFactory;
        @Nullable private GlobalActionsPanelPlugin.PanelViewController mWalletViewController;
        private ResetOrientationData mResetOrientationData;
        private final boolean mControlsAvailable;

        private ControlsUiController mControlsUiController;
        private ViewGroup mControlsView;
        @VisibleForTesting ViewGroup mLockMessageContainer;
        private TextView mLockMessage;

        ActionsDialog(Context context, MyAdapter adapter, MyOverflowAdapter overflowAdapter,
                Provider<GlobalActionsPanelPlugin.PanelViewController> walletFactory,
                SysuiColorExtractor sysuiColorExtractor, IStatusBarService statusBarService,
                NotificationShadeWindowController notificationShadeWindowController,
                boolean controlsAvailable, @Nullable ControlsUiController controlsUiController,
                Runnable onRotateCallback, boolean keyguardShowing,
                MyPowerOptionsAdapter powerAdapter, MyRestartOptionsAdapter restartAdapter,
                MyUsersAdapter usersAdapter, UiEventLogger uiEventLogger,
                Optional<CentralSurfaces> statusBarOptional, KeyguardUpdateMonitor keyguardUpdateMonitor,
                LockPatternUtils lockPatternUtils) {
            super(context, com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions,
                    adapter, overflowAdapter, sysuiColorExtractor, statusBarService,
                    notificationShadeWindowController, onRotateCallback,
                    keyguardShowing, powerAdapter, restartAdapter, usersAdapter, uiEventLogger, statusBarOptional,
                    keyguardUpdateMonitor, lockPatternUtils);
            mControlsAvailable = controlsAvailable;
            mControlsUiController = controlsUiController;
            mWalletFactory = walletFactory;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            initializeLayout();
            if (shouldShowLockMessage()) {
                showLockMessage();
            }
        }

        private boolean shouldShowLockMessage() {
            return GlobalActionsDialog.this.shouldShowLockMessage(this);
        }

        private boolean isShowingControls() {
            return mControlsUiController != null;
        }

        private void showControls(ControlsUiController controller) {
            mControlsUiController = controller;
            mControlsUiController.show(mControlsView, this::dismissForControlsActivity,
                    null /* activityContext */);
        }

        private boolean isWalletViewAvailable() {
            return mWalletViewController != null && mWalletViewController.getPanelContent() != null;
        }

        private void initializeWalletView() {
            if (mWalletFactory == null) {
                return;
            }
            mWalletViewController = mWalletFactory.get();
            if (!isWalletViewAvailable()) {
                return;
            }

            boolean isLandscapeWalletViewShown = mContext.getResources().getBoolean(
                    com.android.systemui.R.bool.global_actions_show_landscape_wallet_view);

            int rotation = RotationUtils.getRotation(mContext);
            boolean rotationLocked = RotationPolicy.isRotationLocked(mContext);
            if (rotation != RotationUtils.ROTATION_NONE) {
                if (rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = true;
                        mResetOrientationData.rotation = rotation;
                    }

                    // Unlock rotation, so user can choose to rotate to portrait to see the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                                    mContext, false, RotationUtils.ROTATION_NONE));

                    if (!isLandscapeWalletViewShown) {
                        return;
                    }
                }
            } else {
                if (!rotationLocked) {
                    if (mResetOrientationData == null) {
                        mResetOrientationData = new ResetOrientationData();
                        mResetOrientationData.locked = false;
                    }
                }

                boolean shouldLockRotation = !isLandscapeWalletViewShown;
                if (rotationLocked != shouldLockRotation) {
                    // Locks the screen to portrait if the landscape / seascape orientation does not
                    // show the wallet view, so the user doesn't accidentally hide the panel.
                    // This call is posted so that the rotation does not change until post-layout,
                    // otherwise onConfigurationChanged() may not get invoked.
                    mGlobalActionsLayout.post(() ->
                            RotationPolicy.setRotationLockAtAngle(
                            mContext, shouldLockRotation, RotationUtils.ROTATION_NONE));
                }
            }

            // Disable rotation suggestions, if enabled
            setRotationSuggestionsEnabled(false);

            FrameLayout panelContainer =
                    findViewById(com.android.systemui.R.id.global_actions_wallet);
            FrameLayout.LayoutParams panelParams =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT);
            if (!mControlsAvailable) {
                panelParams.topMargin = mContext.getResources().getDimensionPixelSize(
                        com.android.systemui.R.dimen.global_actions_wallet_top_margin);
            }
            View walletView = mWalletViewController.getPanelContent();
            panelContainer.addView(walletView, panelParams);
            // Smooth transitions when wallet is resized, which can happen when a card is added
            ViewGroup root = findViewById(com.android.systemui.R.id.global_actions_grid_root);
            if (root != null) {
                walletView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    int oldHeight = ob - ot;
                    int newHeight = b - t;
                    if (oldHeight > 0 && oldHeight != newHeight) {
                        TransitionSet transition = new AutoTransition()
                                .setDuration(250)
                                .setOrdering(TransitionSet.ORDERING_TOGETHER);
                        TransitionManager.beginDelayedTransition(root, transition);
                    }
                });
            }
        }

        @Override
        protected int getLayoutResource() {
            return com.android.systemui.R.layout.global_actions_grid_v2;
        }

        @Override
        protected void initializeLayout() {
            super.initializeLayout();
            mControlsView = findViewById(com.android.systemui.R.id.global_actions_controls);
            mLockMessageContainer = requireViewById(
                    com.android.systemui.R.id.global_actions_lock_message_container);
            mLockMessage = requireViewById(com.android.systemui.R.id.global_actions_lock_message);
            initializeWalletView();
            getWindow().setBackgroundDrawable(mBackgroundDrawable);
        }

        @Override
        public void show() {
            super.show();
            if (mControlsUiController != null) {
                mControlsUiController.show(mControlsView, this::dismissForControlsActivity,
                        null /* activityContext */);
            }

            ViewGroup root = (ViewGroup) mGlobalActionsLayout.getRootView();
            root.setOnApplyWindowInsetsListener((v, windowInsets) -> {
                root.setPadding(windowInsets.getStableInsetLeft(),
                        windowInsets.getStableInsetTop(),
                        windowInsets.getStableInsetRight(),
                        windowInsets.getStableInsetBottom());
                return WindowInsets.CONSUMED;
            });

            mBackgroundDrawable.setAlpha(0);
            float xOffset = mGlobalActionsLayout.getAnimationOffsetX();
            ObjectAnimator alphaAnimator =
                    ObjectAnimator.ofFloat(mContainer, "alpha", 0f, 1f);
            alphaAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            alphaAnimator.setDuration(183);
            alphaAnimator.addUpdateListener((animation) -> {
                float animatedValue = animation.getAnimatedFraction();
                int alpha = (int) (animatedValue * mScrimAlpha * 255);
                mBackgroundDrawable.setAlpha(alpha);
            });

            ObjectAnimator xAnimator =
                    ObjectAnimator.ofFloat(mContainer, "translationX", xOffset, 0f);
            xAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            xAnimator.setDuration(350);

            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(alphaAnimator, xAnimator);
            animatorSet.start();
        }

        @Override
        public void dismiss() {
            dismissWallet();
            if (mControlsUiController != null) mControlsUiController.closeDialogs(false);
            if (mControlsUiController != null) mControlsUiController.hide();
            mContainer.setTranslationX(0);
            ObjectAnimator alphaAnimator =
                    ObjectAnimator.ofFloat(mContainer, "alpha", 1f, 0f);
            alphaAnimator.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
            alphaAnimator.setDuration(233);
            alphaAnimator.addUpdateListener((animation) -> {
                float animatedValue = 1f - animation.getAnimatedFraction();
                int alpha = (int) (animatedValue * mScrimAlpha * 255);
                mBackgroundDrawable.setAlpha(alpha);
            });

            float xOffset = mGlobalActionsLayout.getAnimationOffsetX();
            ObjectAnimator xAnimator =
                    ObjectAnimator.ofFloat(mContainer, "translationX", 0f, xOffset);
            xAnimator.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
            xAnimator.setDuration(350);

            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(alphaAnimator, xAnimator);
            animatorSet.addListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator animation) {
                    completeDismiss();
                }
            });

            animatorSet.start();
        }

        void completeDismiss() {
            resetOrientation();
            super.dismiss();
        }

        private void dismissForControlsActivity() {
            dismissWallet();
            if (mControlsUiController != null) mControlsUiController.closeDialogs(false);
            if (mControlsUiController != null) mControlsUiController.hide();
            ViewGroup root = (ViewGroup) mGlobalActionsLayout.getParent();
            ControlsAnimations.exitAnimation(root, this::completeDismiss).start();
        }

        private void dismissWallet() {
            if (mWalletViewController != null) {
                mWalletViewController.onDismissed();
                // The wallet controller should not be re-used after being dismissed.
                mWalletViewController = null;
            }
        }

        private void resetOrientation() {
            if (mResetOrientationData != null) {
                RotationPolicy.setRotationLockAtAngle(mContext, mResetOrientationData.locked,
                        mResetOrientationData.rotation);
            }
            setRotationSuggestionsEnabled(true);
        }

        @Override
        public void refreshDialog() {
            // ensure dropdown menus are dismissed before re-initializing the dialog
            dismissWallet();
            if (mControlsUiController != null) {
                mControlsUiController.hide();
            }

            super.refreshDialog();
            if (mControlsUiController != null) {
                mControlsUiController.show(mControlsView, this::dismissForControlsActivity,
                        null /* activityContext */);
            }
        }

        void hideLockMessage() {
            if (mLockMessageContainer.getVisibility() == View.VISIBLE) {
                mLockMessageContainer.animate().alpha(0).setDuration(150).setListener(
                        new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                mLockMessageContainer.setVisibility(View.GONE);
                            }
                        }).start();
            }
        }

        void showLockMessage() {
            Drawable lockIcon = mContext.getDrawable(com.android.internal.R.drawable.ic_lock);
            lockIcon.setTint(mContext.getColor(com.android.systemui.R.color.control_primary_text));
            mLockMessage.setCompoundDrawablesWithIntrinsicBounds(null, lockIcon, null, null);
            mLockMessageContainer.setVisibility(View.VISIBLE);
        }

        private class ResetOrientationData {
            public boolean locked;
            public int rotation;
        }
    }

    /**
     * Determines whether or not debug mode has been activated for the Global Actions Panel.
     */
    private static boolean isPanelDebugModeEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.GLOBAL_ACTIONS_PANEL_DEBUG_ENABLED, 0) == 1;
    }

    /**
     * Determines whether or not the Global Actions menu should be forced to use the newer
     * grid-style layout.
     */
    private static boolean isForceGridEnabled(Context context) {
        return isPanelDebugModeEnabled(context);
    }

    private boolean controlsAvailable() {
        return isDeviceProvisioned()
                && mControlsComponent.isEnabled()
                && !mControlsServiceInfos.isEmpty();
    }

    private boolean shouldShowLockMessage(ActionsDialog dialog) {
        return mControlsComponent.getVisibility() == AVAILABLE_AFTER_UNLOCK
                || isWalletAvailableAfterUnlock(dialog);
    }

    // Temporary while we move items out of the power menu
    private boolean isWalletAvailableAfterUnlock(ActionsDialog dialog) {
        boolean isLockedAfterBoot = mLockPatternUtils.getStrongAuthForUser(getCurrentUser().id)
                == STRONG_AUTH_REQUIRED_AFTER_BOOT;
        return !mKeyguardStateController.isUnlocked()
                && (!mShowLockScreenCardsAndControls || isLockedAfterBoot)
                && dialog.isWalletViewAvailable();
    }

    private void onPowerMenuLockScreenSettingsChanged() {
        mShowLockScreenCardsAndControls = mSecureSettings.getInt(
                Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT, 0) != 0;
    }

    @Override
    protected boolean shouldForceDark() {
        return true;
    }

    @Override
    protected boolean shouldUseControlsLayout() {
        return true;
    }
}
