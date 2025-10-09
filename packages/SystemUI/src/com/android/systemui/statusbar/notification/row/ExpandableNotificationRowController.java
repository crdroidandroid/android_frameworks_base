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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Flags;
import com.android.systemui.flags.FeatureFlagsClassic;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.notification.BundleInteractionLogger;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.collection.EntryAdapterFactory;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.PipelineEntry;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.NotifViewController;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowScope;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerLogger;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationRowStatsLogger;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent;
import com.android.systemui.util.ScrimUtils;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor;

import com.google.android.msdl.data.model.MSDLToken;
import com.google.android.msdl.domain.MSDLPlayer;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link ExpandableNotificationRow}.
 */
@NotificationRowScope
public class ExpandableNotificationRowController implements NotifViewController {
    private static final String TAG = "NotifRowController";

    static final Uri BUBBLES_SETTING_URI =
            Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_BUBBLES);
    private static final String BUBBLES_SETTING_ENABLED_VALUE = "1";
    private final ExpandableNotificationRow mView;
    private final NotificationListContainer mListContainer;
    private final RemoteInputViewSubcomponent.Factory mRemoteInputViewSubcomponentFactory;
    private final ActivatableNotificationViewController mActivatableNotificationViewController;
    private final PluginManager mPluginManager;
    private final SystemClock mClock;
    private final ColorUpdateLogger mColorUpdateLogger;
    private final KeyguardBypassController mKeyguardBypassController;
    private final GroupMembershipManager mGroupMembershipManager;
    private final GroupExpansionManager mGroupExpansionManager;
    private final RowContentBindStage mRowContentBindStage;
    private final NotificationRowStatsLogger mStatsLogger;
    private final NotificationRowLogger mLogBufferLogger;
    private final BundleInteractionLogger mBundleInteractionLogger;
    private final HeadsUpManager mHeadsUpManager;
    private final ExpandableNotificationRow.OnExpandClickListener mOnExpandClickListener;
    private final StatusBarStateController mStatusBarStateController;
    private final MetricsLogger mMetricsLogger;
    private final NotificationChildrenContainerLogger mChildrenContainerLogger;
    private final ExpandableNotificationRow.CoordinateOnClickListener mOnFeedbackClickListener;
    private final NotificationGutsManager mNotificationGutsManager;
    private final OnUserInteractionCallback mOnUserInteractionCallback;
    private final FalsingManager mFalsingManager;
    private final NotificationRebindingTracker mNotificationRebindingTracker;
    private final FeatureFlagsClassic mFeatureFlags;
    private final boolean mAllowLongPress;
    private final PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    private final SmartReplyConstants mSmartReplyConstants;
    private final SmartReplyController mSmartReplyController;
    private final ExpandableNotificationRowDragController mDragController;
    private final NotificationDismissibilityProvider mDismissibilityProvider;
    private final IStatusBarService mStatusBarService;
    private final UiEventLogger mUiEventLogger;
    private final MSDLPlayer mMSDLPlayer;
    private final NotificationSettingsController mSettingsController;
    private final EntryAdapterFactory mEntryAdapterFactory;
    private final WindowRootViewBlurInteractor mWindowRootViewBlurInteractor;
    private final NotificationActivityStarter mNotificationActivityStarter;
    private final Context mContext;

    @VisibleForTesting
    final NotificationSettingsController.Listener mSettingsListener =
            new NotificationSettingsController.Listener() {
                @Override
                public void onSettingChanged(Uri setting, int userId, String value) {
                    if (BUBBLES_SETTING_URI.equals(setting)) {
                        if (NotificationBundleUi.isEnabled()
                                && mView.getEntryAdapter().getSbn() == null) {
                            // only valid for notification rows
                            return;
                        }
                        final int viewUserId = NotificationBundleUi.isEnabled()
                            ? mView.getEntryAdapter().getSbn().getUserId()
                            : mView.getEntryLegacy().getSbn().getUserId();
                        if (viewUserId == UserHandle.USER_ALL || viewUserId == userId) {
                            mView.getPrivateLayout().setBubblesEnabledForUser(
                                    BUBBLES_SETTING_ENABLED_VALUE.equals(value));
                        }
                    }
                }
            };
    private final ExpandableNotificationRow.ExpandableNotificationRowLogger mLoggerCallback =
            new ExpandableNotificationRow.ExpandableNotificationRowLogger() {
                @Override
                public void logNotificationExpansion(String key, int location, boolean userAction,
                        boolean expanded) {
                    mStatsLogger.onNotificationExpansionChanged(key, expanded, location,
                            userAction);
                }

                @Override
                public void logKeepInParentChildDetached(
                        String child,
                        String oldParent
                ) {
                    mLogBufferLogger.logKeepInParentChildDetached(child, oldParent);
                }

                @Override
                public void logSkipAttachingKeepInParentChild(
                        String child,
                        String newParent
                ) {
                    mLogBufferLogger.logSkipAttachingKeepInParentChild(child, newParent);
                }

                @Override
                public void logRemoveTransientFromContainer(
                        String childEntry,
                        String containerEntry
                ) {
                    mLogBufferLogger.logRemoveTransientFromContainer(childEntry, containerEntry);
                }

                @Override
                public void logRemoveTransientFromNssl(
                        String childEntry
                ) {
                    mLogBufferLogger.logRemoveTransientFromNssl(childEntry);
                }

                @Override
                public void logRemoveTransientFromViewGroup(
                        String childEntry,
                        ViewGroup containerView
                ) {
                    mLogBufferLogger.logRemoveTransientFromViewGroup(childEntry, containerView);
                }

                @Override
                public void logAddTransientRow(
                        String childEntry,
                        String containerEntry,
                        int index
                ) {
                    mLogBufferLogger.logAddTransientRow(childEntry, containerEntry, index);
                }

                @Override
                public void logRemoveTransientRow(
                        String childEntry,
                        String containerEntry
                ) {
                    mLogBufferLogger.logRemoveTransientRow(childEntry, containerEntry);
                }

                @Override
                public void logResetAllContentAlphas(
                        String entry
                ) {
                    mLogBufferLogger.logResetAllContentAlphas(entry);
                }

                @Override
                public void logSkipResetAllContentAlphas(
                        String entry
                ) {
                    mLogBufferLogger.logSkipResetAllContentAlphas(entry);
                }

                @Override
                public void logStartAppearAnimation(String entry, boolean isAppear) {
                    mLogBufferLogger.logStartAppearAnimation(entry, isAppear);
                }

                @Override
                public void logCancelAppearDrawing(String entry, boolean wasDrawing) {
                    mLogBufferLogger.logCancelAppearDrawing(entry, wasDrawing);
                }

                @Override
                public void logAppearAnimationStarted(String entry, boolean isAppear) {
                    mLogBufferLogger.logAppearAnimationStarted(entry, isAppear);
                }

                @Override
                public void logAppearAnimationSkipped(String entry, boolean isAppear) {
                    mLogBufferLogger.logAppearAnimationSkipped(entry, isAppear);
                }

                @Override
                public void logAppearAnimationFinished(String entry, boolean isAppear,
                        boolean cancelled) {
                    mLogBufferLogger.logAppearAnimationFinished(entry, isAppear, cancelled);
                }
            };

    @Inject
    public ExpandableNotificationRowController(
            ExpandableNotificationRow view,
            ActivatableNotificationViewController activatableNotificationViewController,
            RemoteInputViewSubcomponent.Factory rivSubcomponentFactory,
            MetricsLogger metricsLogger,
            ColorUpdateLogger colorUpdateLogger,
            NotificationRowLogger logBufferLogger,
            NotificationChildrenContainerLogger childrenContainerLogger,
            NotificationListContainer listContainer,
            SmartReplyConstants smartReplyConstants,
            SmartReplyController smartReplyController,
            PluginManager pluginManager,
            SystemClock clock,
            Context context,
            KeyguardBypassController keyguardBypassController,
            GroupMembershipManager groupMembershipManager,
            GroupExpansionManager groupExpansionManager,
            RowContentBindStage rowContentBindStage,
            NotificationRowStatsLogger statsLogger,
            HeadsUpManager headsUpManager,
            ExpandableNotificationRow.OnExpandClickListener onExpandClickListener,
            StatusBarStateController statusBarStateController,
            NotificationGutsManager notificationGutsManager,
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            OnUserInteractionCallback onUserInteractionCallback,
            FalsingManager falsingManager,
            FeatureFlagsClassic featureFlags,
            PeopleNotificationIdentifier peopleNotificationIdentifier,
            NotificationSettingsController settingsController,
            ExpandableNotificationRowDragController dragController,
            NotificationDismissibilityProvider dismissibilityProvider,
            IStatusBarService statusBarService,
            UiEventLogger uiEventLogger,
            MSDLPlayer msdlPlayer,
            NotificationRebindingTracker notificationRebindingTracker,
            EntryAdapterFactory entryAdapterFactory,
            WindowRootViewBlurInteractor windowRootViewBlurInteractor,
            BundleInteractionLogger bundleInteractionLogger,
            NotificationActivityStarter notificationActivityStarter) {
        mView = view;
        mContext = context;
        mListContainer = listContainer;
        mRemoteInputViewSubcomponentFactory = rivSubcomponentFactory;
        mActivatableNotificationViewController = activatableNotificationViewController;
        mPluginManager = pluginManager;
        mClock = clock;
        mKeyguardBypassController = keyguardBypassController;
        mGroupMembershipManager = groupMembershipManager;
        mGroupExpansionManager = groupExpansionManager;
        mRowContentBindStage = rowContentBindStage;
        mStatsLogger = statsLogger;
        mHeadsUpManager = headsUpManager;
        mOnExpandClickListener = onExpandClickListener;
        mStatusBarStateController = statusBarStateController;
        mNotificationGutsManager = notificationGutsManager;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mFalsingManager = falsingManager;
        mNotificationRebindingTracker = notificationRebindingTracker;
        mOnFeedbackClickListener = mNotificationGutsManager::openGuts;
        mAllowLongPress = allowLongPress;
        mFeatureFlags = featureFlags;
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
        mSettingsController = settingsController;
        mDragController = dragController;
        mMetricsLogger = metricsLogger;
        mChildrenContainerLogger = childrenContainerLogger;
        mColorUpdateLogger = colorUpdateLogger;
        mLogBufferLogger = logBufferLogger;
        mSmartReplyConstants = smartReplyConstants;
        mSmartReplyController = smartReplyController;
        mDismissibilityProvider = dismissibilityProvider;
        mStatusBarService = statusBarService;
        mUiEventLogger = uiEventLogger;
        mMSDLPlayer = msdlPlayer;
        mEntryAdapterFactory = entryAdapterFactory;
        mWindowRootViewBlurInteractor = windowRootViewBlurInteractor;
        mBundleInteractionLogger = bundleInteractionLogger;
        mNotificationActivityStarter = notificationActivityStarter;
    }

    String loadsGutsAppName(Context context, PipelineEntry pipelineEntry) {
        if (pipelineEntry instanceof NotificationEntry notificationEntry) {
            StatusBarNotification statusBarNotification = notificationEntry.getSbn();
            if (statusBarNotification == null) {
                return "";
            }
            // Get the app name.
            // Note that Notification.Builder#bindHeaderAppName has similar logic
            // but since this field is used in the guts, it must be accurate.
            // Therefore we will only show the application label, or, failing that, the
            // package name. No substitutions.
            PackageManager pmUser = CentralSurfaces.getPackageManagerForUser(
                    context, statusBarNotification.getUser().getIdentifier());
            final String pkg = statusBarNotification.getPackageName();
            try {
                final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES
                                | PackageManager.MATCH_DISABLED_COMPONENTS);
                if (info != null) {
                    return String.valueOf(pmUser.getApplicationLabel(info));
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Do nothing
            }
            return pkg;
        }
        // Bundles have no app name
        return "";
    }

    /**
     * Initialize the controller.
     */
    public void init(PipelineEntry entry) {
        mActivatableNotificationViewController.init();
        mView.initialize(
                mEntryAdapterFactory.create(entry),
                entry,
                mRemoteInputViewSubcomponentFactory,
                loadsGutsAppName(mContext, entry),
                entry.getKey(),
                mLoggerCallback,
                mKeyguardBypassController,
                mGroupMembershipManager,
                mGroupExpansionManager,
                mHeadsUpManager,
                mRowContentBindStage,
                mOnExpandClickListener,
                mOnFeedbackClickListener,
                mFalsingManager,
                mStatusBarStateController,
                mPeopleNotificationIdentifier,
                mOnUserInteractionCallback,
                mNotificationGutsManager,
                mDismissibilityProvider,
                mMetricsLogger,
                mChildrenContainerLogger,
                mColorUpdateLogger,
                mSmartReplyConstants,
                mSmartReplyController,
                mStatusBarService,
                mUiEventLogger,
                mNotificationRebindingTracker,
                mBundleInteractionLogger,
                mNotificationActivityStarter
        );
        mView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (mAllowLongPress) {
            if (mFeatureFlags.isEnabled(
                    com.android.systemui.flags.Flags.NOTIFICATION_DRAG_TO_CONTENTS)) {
                mView.setDragController(mDragController);
            }

            mView.setLongPressListener((v, x, y, item) -> {
                if (com.android.systemui.Flags.msdlFeedback()) {
                    mMSDLPlayer.playToken(MSDLToken.LONG_PRESS, null);
                }

                if (mView.isSummaryWithChildren() && !mView.isBundle()) {
                    mView.expandNotification();
                    return true;
                }
                return mNotificationGutsManager.openGuts(v, x, y, item);
            });
        }
        if (ENABLE_REMOTE_INPUT) {
            mView.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (NotificationBundleUi.isEnabled()) {
                    mView.setInitializationTime(mClock.elapsedRealtime());
                    if (mView.getEntryAdapter().getSbn() != null) {
                        mSettingsController.addCallback(BUBBLES_SETTING_URI, mSettingsListener);
                    }
                } else {
                    mView.getEntryLegacy().setInitializationTime(mClock.elapsedRealtime());
                    mSettingsController.addCallback(BUBBLES_SETTING_URI, mSettingsListener);
                }
                mPluginManager.addPluginListener(mView,
                        NotificationMenuRowPlugin.class, false /* Allow multiple */);
                if (!SceneContainerFlag.isEnabled()) {
                    mView.setOnKeyguard(mStatusBarStateController.getState() == KEYGUARD);
                    mStatusBarStateController.addCallback(mStatusBarStateListener);
                }
                ScrimUtils.get().addListener(mScrimListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mPluginManager.removePluginListener(mView);
                if (!SceneContainerFlag.isEnabled()) {
                    mStatusBarStateController.removeCallback(mStatusBarStateListener);
                }
                mSettingsController.removeCallback(BUBBLES_SETTING_URI, mSettingsListener);
                ScrimUtils.get().removeListener(mScrimListener);
            }
        });

        collectFlow(
                mView,
                mWindowRootViewBlurInteractor.isTranslucentSupported(),
                mView::setIsBlurSupported);
        collectFlow(
                mView,
                mWindowRootViewBlurInteractor.isLockscreenTranslucentSupported(),
                mView::setIsLockscreenBlurSupported);
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mView.setOnKeyguard(newState == KEYGUARD);
                }
            };

    private final ScrimUtils.ScrimEventListener mScrimListener =
            new ScrimUtils.ScrimEventListener() {
                @Override
                public void onNotificationPosted(StatusBarNotification sbn) {
                    mView.updateIfNeeded();
                }
            };

    @Override
    @NonNull
    public String getNodeLabel() {
        return NotificationBundleUi.isEnabled()
                ? mView.getLoggingKey()
                : logKey(mView.getEntryLegacy());
    }

    @Override
    @NonNull
    public View getView() {
        return mView;
    }

    @Override
    public View getChildAt(int index) {
        return mView.getChildNotificationAt(index);
    }

    @Override
    public void addChildAt(NodeController child, int index) {
        ExpandableNotificationRow childView = (ExpandableNotificationRow) child.getView();

        mView.addChildNotification((ExpandableNotificationRow) child.getView(), index);
        mListContainer.notifyGroupChildAdded(childView);
        childView.setChangingPosition(false);
    }

    @Override
    public void moveChildTo(NodeController child, int index) {
        ExpandableNotificationRow childView = (ExpandableNotificationRow) child.getView();
        childView.setChangingPosition(true);
        mView.removeChildNotification(childView);
        mView.addChildNotification(childView, index);
        childView.setChangingPosition(false);
    }

    @Override
    public void removeChild(NodeController child, boolean isTransfer) {
        ExpandableNotificationRow childView = (ExpandableNotificationRow) child.getView();

        if (isTransfer) {
            childView.setChangingPosition(true);
        }
        mView.removeChildNotification(childView);
        if (!isTransfer) {
            mListContainer.notifyGroupChildRemoved(childView, mView.getChildrenContainer());
        }
    }

    @Override
    public void onViewAdded() {
    }

    @Override
    public void onViewMoved() {
    }

    @Override
    public void onViewRemoved() {
    }

    @Override
    public int getChildCount() {
        final List<ExpandableNotificationRow> mChildren = mView.getAttachedChildren();
        return mChildren != null ? mChildren.size() : 0;
    }

    @Override
    public void setUntruncatedChildCount(int childCount) {
        if (mView.isSummaryWithChildren()) {
            mView.setUntruncatedChildCount(childCount);
        } else {
            Log.w(TAG, "Called setUntruncatedChildCount(" + childCount + ") on a leaf row");
        }
    }

    @Override
    public void setNotificationGroupWhen(long whenMillis) {
        if (mView.isSummaryWithChildren()) {
            mView.setNotificationGroupWhen(whenMillis);
        } else {
            Log.w(TAG, "Called setNotificationTime(" + whenMillis + ") on a leaf row");
        }
    }

    @Override
    public void setSystemExpanded(boolean systemExpanded) {
        mView.setSystemExpanded(systemExpanded);
    }

    @Override
    public void setLastAudibleMs(long lastAudibleMs) {
        mView.setLastAudiblyAlertedMs(lastAudibleMs);
    }

    @Override
    public void setFeedbackIcon(@Nullable FeedbackIcon icon) {
        mView.setFeedbackIcon(icon);
    }

    @Override
    public boolean offerToKeepInParentForAnimation() {
        //If the User dismissed the notification's parent, we want to keep it attached until the
        //dismiss animation is ongoing. Therefore we don't want to remove it in the ShadeViewDiffer.
        if (mView.isParentDismissed()) {
            mView.setKeepInParentForDismissAnimation(true);
            return true;
        }

        //Otherwise the view system doesn't do the removal, so we rely on the ShadeViewDiffer
        return false;
    }

    @Override
    public boolean removeFromParentIfKeptForAnimation() {
        ExpandableNotificationRow parent = mView.getNotificationParent();
        if (mView.keepInParentForDismissAnimation() && parent != null) {
            parent.removeChildNotification(mView);
            return true;
        }

        return false;
    }

    @Override
    public void resetKeepInParentForAnimation() {
        mView.setKeepInParentForDismissAnimation(false);
    }
}
