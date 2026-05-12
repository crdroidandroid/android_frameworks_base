/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.Flags.notificationsRedesignTemplates;
import static android.app.Notification.Action.SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_EXPANDED;
import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;

import static com.android.systemui.flags.Flags.ENABLE_NOTIFICATIONS_SIMULATE_SLOW_MEASURE;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.REDACTION_TYPE_NONE;
import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.PARENT_DISMISSED;
import static com.android.systemui.statusbar.notification.row.NotificationContentView.VISIBLE_TYPE_HEADSUP;
import static com.android.systemui.statusbar.policy.RemoteInputView.FOCUS_ANIMATION_MIN_SCALE;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.AxSandboxManager;
import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewStub;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ContrastColorUtil;
import com.android.internal.widget.CachingIconView;
import com.android.internal.widget.CallLayout;
import com.android.internal.widget.ConversationLayout;
import com.android.internal.widget.MessagingLayout;
import com.android.systemui.Flags;
import com.android.systemui.flags.RefactorFlag;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.MenuItem;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.RedactionType;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SmartReplyController;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.AboveShelfChangedListener;
import com.android.systemui.statusbar.notification.BundleInteractionLogger;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.LaunchAnimationParameters;
import com.android.systemui.statusbar.notification.NmSummarizationUiFlag;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationFadeAware;
import com.android.systemui.statusbar.notification.NotificationTransitionAnimatorController;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.PhysicsPropertyAnimator;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.collection.BundleEntryAdapter;
import com.android.systemui.statusbar.notification.collection.EntryAdapter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.PipelineEntry;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.PinnedStatus;
import com.android.systemui.statusbar.notification.logging.NotificationCounters;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi;
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation;
import com.android.systemui.statusbar.notification.row.ui.viewmodel.BundleHeaderViewModel;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationCompactMessagingTemplateViewWrapper;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.shared.TransparentHeaderFix;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.MagneticRowListener;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerLogger;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.SwipeableView;
import com.android.systemui.statusbar.NTForbiddenSwipeDownQSController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.InflatedSmartReplyState;
import com.android.systemui.statusbar.policy.RemoteInputView;
import com.android.systemui.statusbar.policy.SmartReplyConstants;
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent;
import com.android.systemui.util.Compile;
import com.android.systemui.util.DumpUtilsKt;
import com.android.systemui.util.ListenerSet;
import com.android.wm.shell.shared.animation.PhysicsAnimator;

import com.android.systemui.applocker.AxAppLockerHelper;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * View representing a notification item - this can be either the individual child notification or
 * the group summary (which contains 1 or more child notifications).
 */
public class ExpandableNotificationRow extends ActivatableNotificationView
        implements PluginListener<NotificationMenuRowPlugin>, SwipeableView,
        NotificationFadeAware.FadeOptimizedNotification {

    private static final String TAG = "ExpandableNotifRow";
    private static final boolean DEBUG_ONMEASURE =
            Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE);
    private static final int MENU_VIEW_INDEX = 0;
    public static final float DEFAULT_HEADER_VISIBLE_AMOUNT = 1.0f;
    private static final long RECENTLY_ALERTED_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(30);
    private static final SourceType BASE_VALUE = SourceType.from("BaseValue");
    private static final SourceType FROM_PARENT = SourceType.from("FromParent(ENR)");
    private static final long INITIALIZATION_DELAY = 400;

    // We don't correctly track dark mode until the content views are inflated, so always update
    // the background on first content update just in case it happens to be during a theme change.
    private boolean mUpdateSelfBackgroundOnUpdate = true;
    private boolean mIsSnoozed;
    private boolean mShowSnooze = false;
    private boolean mIsFaded;

    private boolean mHasStatusBarChipDuringHeadsUpAnimation = false;

    @Nullable
    public ImageModelIndex mImageModelIndex = null;

    /**
     * Listener for when {@link ExpandableNotificationRow} is laid out.
     */
    public interface LayoutListener {
        void onLayout();
    }

    /**
     * Listens for changes to the expansion state of this row.
     */
    public interface OnExpansionChangedListener {
        void onExpansionChanged(boolean isExpanded);
    }

    private StatusBarStateController mStatusBarStateController;
    private KeyguardBypassController mBypassController;
    private LayoutListener mLayoutListener;
    private RowContentBindStage mRowContentBindStage;
    private PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    private NotificationActivityStarter mNotificationActivityStarter;
    private AxAppLockerHelper mAxAppLockerHelper;
    private MetricsLogger mMetricsLogger;
    private NotificationChildrenContainerLogger mChildrenContainerLogger;
    private ColorUpdateLogger mColorUpdateLogger;
    private BundleInteractionLogger mBundleInteractionLogger;
    private NotificationDismissibilityProvider mDismissibilityProvider;
    private int mIconTransformContentShift;
    private int mMaxHeadsUpHeightBeforeN;
    private int mMaxHeadsUpHeightBeforeP;
    private int mMaxHeadsUpHeightBeforeS;
    private int mMaxHeadsUpHeight;
    private int mMaxSmallHeightBeforeN;
    private int mMaxSmallHeightBeforeP;
    private int mMaxSmallHeightBeforeS;
    private int mMaxSmallHeightWithSummarization;
    private int mMaxSmallHeight;
    private int mMaxExpandedHeight;
    private int mMaxExpandedHeightForPromotedOngoing;
    private int mNotificationLaunchHeight;
    private boolean mMustStayOnScreen;

    /**
     * Does this row contain layouts that can adapt to row expansion
     */
    private boolean mExpandable;
    /**
     * Has the user actively changed the expansion state of this row
     */
    private boolean mHasUserChangedExpansion;
    /**
     * If {@link #mHasUserChangedExpansion}, has the user expanded this row
     */
    private boolean mUserExpanded;
    /**
     * Has this notification been expanded while it was pinned
     */
    private boolean mExpandedWhenPinned;
    /**
     * Is the user touching this row
     */
    private boolean mUserLocked;
    /**
     * Are we showing the "public" version
     */
    private boolean mShowingPublic;
    private boolean mSensitive;
    private boolean mSensitiveHiddenInGeneral;
    private boolean mShowPublicExpander = true;
    private boolean mShowingPublicInitialized;
    private boolean mHideSensitiveForIntrinsicHeight;
    private float mHeaderVisibleAmount = DEFAULT_HEADER_VISIBLE_AMOUNT;

    /**
     * Is this notification expanded by the system. The expansion state can be overridden by the
     * user expansion.
     */
    private boolean mIsSystemExpanded;

    private Animator mTranslateAnim;
    private ArrayList<View> mTranslateableViews;
    private NotificationContentView mPublicLayout;
    private NotificationContentView mPrivateLayout;
    private NotificationContentView[] mLayouts;
    private ExpandableNotificationRowLogger mLogger;
    private String mLoggingKey;
    private String mKey;
    private NotificationGuts mGuts;
    private NotificationEntry mEntry;
    private EntryAdapter mEntryAdapter;
    private boolean mIsBundle;
    private String mAppName;
    private NotificationRebindingTracker mRebindingTracker;
    private FalsingManager mFalsingManager;

    /**
     * Whether or not the notification is using the heads up view and should peek from the top.
     */
    private boolean mIsHeadsUp;

    /**
     * Whether or not the notification is showing the app icon instead of the small icon.
     */
    private boolean mIsShowingAppIcon;

    private boolean mLastChronometerRunning = true;
    private ViewStub mChildrenContainerStub;
    private GroupMembershipManager mGroupMembershipManager;
    private GroupExpansionManager mGroupExpansionManager;
    private boolean mChildrenExpanded;
    private boolean mIsSummaryWithChildren;
    private NotificationChildrenContainer mChildrenContainer;
    private NotificationMenuRowPlugin mMenuRow;
    private ViewStub mGutsStub;
    private boolean mIsSystemChildExpanded;
    private PinnedStatus mPinnedStatus = PinnedStatus.NotPinned;
    private boolean mExpandAnimationRunning;
    private boolean mLaunchAnimationRunning;
    private AboveShelfChangedListener mAboveShelfChangedListener;
    private HeadsUpManager mHeadsUpManager;
    private Consumer<Boolean> mHeadsUpAnimatingAwayListener;
    private boolean mChildIsExpanding;

    private boolean mJustClicked;
    private boolean mAnimationRunning;
    private boolean mShowNoBackground;
    private ExpandableNotificationRow mNotificationParent;
    private OnExpandClickListener mOnExpandClickListener;
    private View.OnClickListener mOnFeedbackClickListener;
    private Path mExpandingClipPath;

    private static boolean shouldSimulateSlowMeasure() {
        return Compile.IS_DEBUG && RefactorFlag.forView(
                ENABLE_NOTIFICATIONS_SIMULATE_SLOW_MEASURE).isEnabled();
    }

    private static final String SLOW_MEASURE_SIMULATE_DELAY_PROPERTY =
            "persist.notifications.extra_measure_delay_ms";
    private static final int SLOW_MEASURE_SIMULATE_DELAY_MS =
            SystemProperties.getInt(SLOW_MEASURE_SIMULATE_DELAY_PROPERTY, 150);

    // Listener will be called when receiving a long click event.
    // Use #setLongPressPosition to optionally assign positional data with the long press.
    private LongPressListener mLongPressListener;

    private ExpandableNotificationRowDragController mDragController;

    private boolean mGroupExpansionChanging;

    /**
     * A supplier that returns true if keyguard is secure.
     */
    private BooleanSupplier mSecureStateProvider;

    /**
     * Whether or not a notification that is not part of a group of notifications can be manually
     * expanded by the user.
     */
    private boolean mEnableNonGroupedNotificationExpand;

    /**
     * Whether or not to update the background of the header of the notification when its expanded.
     * If {@code true}, the header background will disappear when expanded.
     */
    private boolean mShowGroupBackgroundWhenExpanded;

    /**
     * True if we always show the collapsed layout on lockscreen because vertical space is low.
     */
    private boolean mSaveSpaceOnLockscreen;

    // indicates when this view was first attached to a window
    // this value will reset when the view is completely removed from the shade (ie: filtered out)
    private long initializationTime = -1;

    /**
     * It is added for unit testing purpose.
     * Please do not use it for other purposes.
     */
    @VisibleForTesting
    public void setIgnoreLockscreenConstraints(boolean ignoreLockscreenConstraints) {
        mIgnoreLockscreenConstraints = ignoreLockscreenConstraints;
    }

    /**
     * True if we use intrinsic height regardless of vertical space available on lockscreen.
     */
    private boolean mIgnoreLockscreenConstraints;

    private final OnClickListener mExpandClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            toggleExpansionState(v, /* shouldLogExpandClickMetric = */true);
        }
    };

    @Override
    protected void cancelTranslationAnimations() {
        cancelSnapBackAnimation();
        cancelTranslateAnimation();
    }

    private void cancelSnapBackAnimation() {
        PhysicsAnimator<ExpandableView> animator =
                PhysicsAnimator.getInstanceIfExists(this /* target */);
        if (animator != null) {
            animator.cancel();
        }
    }

    /**
     * Toggles expansion state.
     */
    public void toggleExpansionState() {
        toggleExpansionState(this, /*shouldLogExpandClickMetric*/ false);
    }

    private void toggleExpansionState(View v, boolean shouldLogExpandClickMetric) {
        if (doesNotificationNeedAuth()) {
            promptAppUnlock();
            return;
        }
        if (isBundle()
                || (!shouldShowPublic() && (!mIsMinimized || isExpanded()) && isGroupRoot() && !NTForbiddenSwipeDownQSController.get(mContext).getForbiddenSwipeDownQS())) {
            mGroupExpansionChanging = true;

            if (NotificationBundleUi.isEnabled()) {
                final boolean wasExpanded =  mGroupExpansionManager.isGroupExpanded(mEntryAdapter);
                mOnExpandClickListener.onExpandClicked(this, mEntryAdapter, !wasExpanded);
                if (isBundle()) {
                    mBundleInteractionLogger.logBundleExpansionChanged(
                            mEntryAdapter.getBundleType(), !wasExpanded);
                } else {
                    if (shouldLogExpandClickMetric) {
                        mMetricsLogger.action(
                                MetricsEvent.ACTION_NOTIFICATION_GROUP_EXPANDER, !wasExpanded);
                    }
                }
                setUserExpanded(!wasExpanded, true);
            } else {
                final boolean wasExpanded =
                        mGroupExpansionManager.isGroupExpanded(getEntryLegacy());
                boolean nowExpanded = mGroupExpansionManager.toggleGroupExpansion(getEntryLegacy());
                mOnExpandClickListener.onExpandClicked(getEntryLegacy(), v, nowExpanded);
                if (shouldLogExpandClickMetric) {
                    mMetricsLogger.action(
                            MetricsEvent.ACTION_NOTIFICATION_GROUP_EXPANDER, nowExpanded);
                }
                onExpansionChanged(true /* userAction */, wasExpanded);
            }
        } else if (mEnableNonGroupedNotificationExpand) {
            if (v != null && v.isAccessibilityFocused()) {
                mPrivateLayout.setFocusOnVisibilityChange();
            }
            boolean nowExpanded;
            if (isPinned()) {
                nowExpanded = !mExpandedWhenPinned;
                mExpandedWhenPinned = nowExpanded;
                // Also notify any expansion changed listeners. This is necessary since the
                // expansion doesn't actually change (it's already system expanded) but it
                // changes visually
                if (mExpansionChangedListener != null) {
                    mExpansionChangedListener.onExpansionChanged(nowExpanded);
                }
            } else {
                nowExpanded = !isExpanded();
                setUserExpanded(nowExpanded);
            }

            notifyHeightChanged(/* needsAnimation= */ true);
            if (NotificationBundleUi.isEnabled()) {
                mOnExpandClickListener.onExpandClicked(this, mEntryAdapter, nowExpanded);
            } else {
                mOnExpandClickListener.onExpandClicked(getEntryLegacy(), v, nowExpanded);
            }
            if (shouldLogExpandClickMetric) {
                mMetricsLogger.action(MetricsEvent.ACTION_NOTIFICATION_EXPANDER, nowExpanded);
            }
        }
    }

    private StatusBarNotification getAppLockSbn() {
        if (NotificationBundleUi.isEnabled()) {
            return mEntryAdapter != null ? mEntryAdapter.getSbn() : null;
        }
        return mEntry != null ? mEntry.getSbn() : null;
    }

    private boolean isNotificationAppLocked() {
        StatusBarNotification sbn = getAppLockSbn();
        if (sbn == null) return false;
        if (!sbn.getNotification().extras
                .getBoolean(AxSandboxManager.EXTRA_NOTIFICATION_APP_LOCKED, false)) {
            return false;
        }
        return mAxAppLockerHelper.needsAuth(sbn.getPackageName(), sbn.getUserId());
    }

    private boolean doesNotificationNeedAuth() {
        StatusBarNotification sbn = getAppLockSbn();
        if (sbn == null) return false;
        return mAxAppLockerHelper.needsAuth(sbn.getPackageName(), sbn.getUserId());
    }

    private void promptAppUnlock() {
        StatusBarNotification sbn = getAppLockSbn();
        if (sbn == null) return;
        mAxAppLockerHelper.promptUnlock(sbn.getPackageName(), sbn.getUserId());
    }

    private boolean mKeepInParentForDismissAnimation;
    private boolean mRemoved;
    public static final FloatProperty<ExpandableNotificationRow> TRANSLATE_CONTENT =
            new FloatProperty<>("translate") {
                @Override
                public void setValue(ExpandableNotificationRow object, float value) {
                    object.setTranslation(value);
                }

                @Override
                public Float get(ExpandableNotificationRow object) {
                    return object.getTranslation();
                }
            };

    private OnClickListener mOnClickListener;
    @Nullable
    private OnClickListener mBubbleClickListener;
    private OnDragSuccessListener mOnDragSuccessListener;
    private boolean mHeadsupDisappearRunning;
    private View mChildAfterViewWhenDismissed;
    private View mGroupParentWhenDismissed;
    private boolean mAboveShelf;
    private OnUserInteractionCallback mOnUserInteractionCallback;
    private NotificationGutsManager mNotificationGutsManager;
    private boolean mIsMinimized;
    private float mTranslationWhenRemoved;
    private boolean mWasChildInGroupWhenRemoved;
    private final NotificationInlineImageResolver mImageResolver;
    private BigPictureIconManager mBigPictureIconManager;
    @Nullable
    private OnExpansionChangedListener mExpansionChangedListener;
    @Nullable
    private Runnable mOnIntrinsicHeightReachedRunnable;

    private final float mSmallRoundness;

    private final ListenerSet<DismissButtonTargetVisibilityListener>
            mDismissButtonTargetVisibilityListeners = new ListenerSet<>();
    @RedactionType
    private int mRedactionType = REDACTION_TYPE_NONE;
    public NotificationContentView[] getLayouts() {
        return Arrays.copyOf(mLayouts, mLayouts.length);
    }

    /**
     * Is this entry pinned and was expanded while doing so
     */
    public boolean isPinnedAndExpanded() {
        if (!isPinned()) {
            return false;
        }
        return mExpandedWhenPinned;
    }

    @Override
    public boolean isGroupExpansionChanging() {
        if (isChildInGroup()) {
            return mNotificationParent.isGroupExpansionChanging();
        }
        return mGroupExpansionChanging;
    }

    public void setSaveSpaceOnLockscreen(boolean saveSpace) {
        mSaveSpaceOnLockscreen = saveSpace;
    }

    public boolean getSaveSpaceOnLockscreen() {
        return mSaveSpaceOnLockscreen;
    }

    public void setGroupExpansionChanging(boolean changing) {
        mGroupExpansionChanging = changing;
    }

    @Override
    public void setActualHeightAnimating(boolean animating) {
        if (mPrivateLayout != null) {
            mPrivateLayout.setContentHeightAnimating(animating);
        }
    }

    public NotificationContentView getPrivateLayout() {
        return mPrivateLayout;
    }

    public NotificationContentView getPublicLayout() {
        return mPublicLayout;
    }

    public String getLoggingKey() {
        return mLoggingKey;
    }

    public String getKey() {
        if (NotificationBundleUi.isEnabled()) {
            return mKey;
        } else {
            return getEntryLegacy().getKey();
        }
    }

    /**
     * Sets animations running in the layouts of this row, including public, private, and children.
     *
     * @param running whether the animations should be started running or stopped.
     */
    public void setAnimationRunning(boolean running) {
        // Sets animations running in the private/public layouts.
        for (NotificationContentView l : mLayouts) {
            if (l != null) {
                l.setContentAnimationRunning(running);
                setIconAnimationRunning(running, l);
            }
        }
        // For groups summaries with children, we want to set the children containers
        // animating as well.
        if (mIsSummaryWithChildren) {
            NotificationViewWrapper viewWrapper = mChildrenContainer.getNotificationViewWrapper();
            if (viewWrapper != null) {
                setIconAnimationRunningForChild(running, viewWrapper.getIcon());
            }
            NotificationViewWrapper lowPriWrapper = mChildrenContainer
                    .getMinimizedGroupHeaderWrapper();
            if (lowPriWrapper != null) {
                setIconAnimationRunningForChild(running, lowPriWrapper.getIcon());
            }
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getAttachedChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.setAnimationRunning(running);
            }
        }
        mAnimationRunning = running;
    }

    /**
     * Starts or stops animations of the icons in all potential content views (regardless of
     * whether they're contracted, expanded, etc).
     *
     * @param running whether to start or stop the icon's animation.
     */
    private void setIconAnimationRunning(boolean running, NotificationContentView layout) {
        if (layout != null) {
            View contractedChild = layout.getContractedChild();
            View expandedChild = layout.getExpandedChild();
            View headsUpChild = layout.getHeadsUpChild();
            setIconAnimationRunningForChild(running, contractedChild);
            setIconAnimationRunningForChild(running, expandedChild);
            setIconAnimationRunningForChild(running, headsUpChild);
        }
    }

    /**
     * Starts or stops animations of the icon in the provided view's icon and right icon.
     *
     * @param running whether to start or stop the icon's animation.
     * @param child   the view with the icon to start or stop.
     */
    private void setIconAnimationRunningForChild(boolean running, View child) {
        if (child != null) {
            ImageView icon = child.findViewById(com.android.internal.R.id.icon);
            setImageViewAnimationRunning(icon, running);
            ImageView rightIcon = child.findViewById(com.android.internal.R.id.right_icon);
            setImageViewAnimationRunning(rightIcon, running);
        }
    }

    /**
     * Starts or stops the animation of a provided image view if it's an AnimationDrawable or an
     * AnimatedVectorDrawable.
     *
     * @param imageView the image view on which to start/stop animation.
     * @param running   whether to start or stop the view's animation.
     */
    private void setImageViewAnimationRunning(ImageView imageView, boolean running) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AnimationDrawable animationDrawable) {
                if (running) {
                    animationDrawable.start();
                } else {
                    animationDrawable.stop();
                }
            } else if (drawable instanceof AnimatedVectorDrawable animationDrawable) {
                if (running) {
                    animationDrawable.start();
                } else {
                    animationDrawable.stop();
                }
            }
        }
    }

    /**
     * Returns whether this row is considered non-blockable (i.e. it's a non-blockable system notif
     * or is in an allowList).
     */
    public boolean getIsNonblockable() {
        NotificationBundleUi.assertInLegacyMode();
        if (getEntryLegacy() == null) {
            return true;
        }
        return !getEntryLegacy().isBlockable();
    }

    public void onNotificationUpdated() {
        if (mIsSummaryWithChildren) {
            Trace.beginSection("ExpNotRow#onNotifUpdated (summary)");
        } else {
            Trace.beginSection("ExpNotRow#onNotifUpdated (leaf)");
        }
        if (NotificationBundleUi.isEnabled()) {
            for (NotificationContentView l : mLayouts) {
                l.onNotificationUpdated(null);
            }
        } else {
            for (NotificationContentView l : mLayouts) {
                l.onNotificationUpdated(getEntryLegacy());
            }
        }
        mShowingPublicInitialized = false;
        if (mMenuRow != null) {
            mMenuRow.onNotificationUpdated();
            mMenuRow.setAppName(mAppName);
        }
        if (mIsSummaryWithChildren) {
            if (AsyncGroupHeaderViewInflation.isEnabled()) {
                mChildrenContainer.updateGroupHeaderExpandState();
            } else {
                // We create the header from the background thread instead
                mChildrenContainer.recreateNotificationHeader(mExpandClickListener);
            }
            mChildrenContainer.onNotificationUpdated();
        }
        if (mAnimationRunning) {
            setAnimationRunning(true);
        }
        if (mLastChronometerRunning) {
            setChronometerRunning(true);
        }
        if (mNotificationParent != null) {
            mNotificationParent.updateChildrenAppearance();
        }
        onAttachedChildrenCountChanged();
        mPublicLayout.updateExpandButtons(mShowPublicExpander);
        updateLimits();
        updateShelfIconColor();
        if (mUpdateSelfBackgroundOnUpdate) {
            // Because this is triggered by UiMode change which we already propagated to children,
            // we know that child rows will receive the same event, and will update their own
            // backgrounds when they finish inflating, so propagating again would be redundant.
            mUpdateSelfBackgroundOnUpdate = false;
            updateBackgroundColorsOfSelf();
        }
        Trace.endSection();
    }

    public void updateBackgroundColorsOfSelf() {
        super.updateBackgroundColors();
        if (mColorUpdateLogger.isEnabled()) {
            mColorUpdateLogger.logNotificationEvent("ENR.updateBackgroundColorsOfSelf()",
                    mLoggingKey,
                    "normalBgColor=" + hexColorString(getNormalBgColor())
                            + " background=" + mBackgroundNormal.toDumpString());
        }
    }

    @Override
    public void updateBackgroundColors() {
        // Because this call is made by the NSSL only on attached rows at the moment of the
        // UiMode or Theme change, we have to propagate to our child views.
        updateBackgroundColorsOfSelf();
        if (mIsSummaryWithChildren) {
            for (ExpandableNotificationRow child : mChildrenContainer.getAttachedChildren()) {
                child.updateBackgroundColors();
            }
        }
    }

    /**
     * Call when bubble state has changed and the button on the notification should be updated.
     */
    public void updateBubbleButton() {
        for (NotificationContentView l : mLayouts) {
            l.updateBubbleButton(NotificationBundleUi.isEnabled()
                    ? null
                    : getEntryLegacy());
        }
    }

    @VisibleForTesting
    void updateShelfIconColor() {
        StatusBarIconView expandedIcon = getShelfIcon();
        if (expandedIcon == null) {
            // TODO(b/399736937) get shelf icon for bundle
            return;
        }
        boolean isPreL = Boolean.TRUE.equals(expandedIcon.getTag(R.id.icon_is_pre_L));
        boolean colorize = !isPreL || NotificationUtils.isGrayscale(expandedIcon,
                ContrastColorUtil.getInstance(mContext));
        int color = StatusBarIconView.NO_COLOR;
        if (colorize) {
            color = getOriginalIconColor();
        }
        expandedIcon.setStaticDrawableColor(color);
    }

    public int getOriginalIconColor() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            if (!AsyncGroupHeaderViewInflation.isEnabled()) {
                return mChildrenContainer.getVisibleWrapper().getOriginalIconColor();
            }
        }
        int color = getShowingLayout().getOriginalIconColor();
        if (color != Notification.COLOR_INVALID) {
            return color;
        } else {
            if (NotificationBundleUi.isEnabled()) {
                return mEntryAdapter.getContrastedColor(mContext, mIsMinimized && !isExpanded(),
                        getBackgroundColorWithoutTint());
            } else {
                return getEntryLegacy().getContrastedColor(mContext, mIsMinimized && !isExpanded(),
                        getBackgroundColorWithoutTint());
            }
        }
    }

    public void setAboveShelfChangedListener(AboveShelfChangedListener aboveShelfChangedListener) {
        mAboveShelfChangedListener = aboveShelfChangedListener;
    }

    /**
     * Sets a supplier that can determine whether the keyguard is secure or not.
     *
     * @param secureStateProvider A function that returns true if keyguard is secure.
     */
    public void setSecureStateProvider(BooleanSupplier secureStateProvider) {
        mSecureStateProvider = secureStateProvider;
    }

    private void updateLimits() {
        for (NotificationContentView l : mLayouts) {
            updateLimitsForView(l);
        }
    }

    public interface DismissButtonTargetVisibilityListener {
        // Called when the notification dismiss button's target visibility changes.
        // NOTE: This can be called when the dismiss button already has the target visibility.
        void onTargetVisibilityChanged(boolean targetVisible);
    }

    public void addDismissButtonTargetStateListener(
            DismissButtonTargetVisibilityListener listener) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        mDismissButtonTargetVisibilityListeners.addIfAbsent(listener);
    }

    public void removeDismissButtonTargetStateListener(
            DismissButtonTargetVisibilityListener listener) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        mDismissButtonTargetVisibilityListeners.remove(listener);
    }

    @Override
    public boolean onInterceptHoverEvent(MotionEvent event) {
        if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
            return super.onInterceptHoverEvent(event);
        }

        // Do not bother checking the dismiss button's target visibility if the notification cannot
        // be dismissed.
        if (!canExpandableViewBeDismissed()) {
            return false;
        }

        final Boolean targetVisible = getDismissButtonTargetVisibilityIfAny(event);
        if (targetVisible != null) {
            for (DismissButtonTargetVisibilityListener listener :
                    mDismissButtonTargetVisibilityListeners) {
                listener.onTargetVisibilityChanged(targetVisible);
            }
        }

        // Do not consume the hover event so that children still have a chance to process it.
        return false;
    }

    private @Nullable Boolean getDismissButtonTargetVisibilityIfAny(MotionEvent event) {
        // Returns the dismiss button's target visibility resulted by `event`. Returns null if the
        // target visibility should not change.

        if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
            // The notification dismiss button should be hidden when the hover exit event is located
            // outside of the notification. NOTE: The hover exit event can be inside the
            // notification if hover moves from one hoverable child to another.
            final Rect localBounds = new Rect(0, 0, this.getWidth(), this.getActualHeight());
            if (!localBounds.contains((int) event.getX(), (int) event.getY())) {
                return Boolean.FALSE;
            }
        } else if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            return Boolean.TRUE;
        }

        return null;
    }

    private void updateLimitsForView(NotificationContentView layout) {
        final int maxExpandedHeight;
        if (isPromotedOngoing()) {
            maxExpandedHeight = mMaxExpandedHeightForPromotedOngoing;
        } else {
            maxExpandedHeight = mMaxExpandedHeight;
        }

        View contractedView = layout.getContractedChild();
        boolean customView = contractedView != null
                && contractedView.getId()
                != com.android.internal.R.id.status_bar_latest_event_content;
        int targetSdk = Build.VERSION_CODES.CUR_DEVELOPMENT;
        if (NotificationBundleUi.isEnabled()) {
            targetSdk = mEntryAdapter.getTargetSdk();
        } else {
            targetSdk = getEntryLegacy().targetSdk;
        }

        boolean beforeN = targetSdk < Build.VERSION_CODES.N;
        boolean beforeP = targetSdk < Build.VERSION_CODES.P;
        boolean beforeS = targetSdk < Build.VERSION_CODES.S;
        int smallHeight;

        boolean isCallLayout = contractedView instanceof CallLayout;
        boolean isMessagingLayout = contractedView instanceof MessagingLayout
                || contractedView instanceof ConversationLayout;

        String summarization = null;
        if (NotificationBundleUi.isEnabled()) {
            summarization = mEntryAdapter.getSummarization();
        } else {
            summarization = getEntryLegacy().getRanking().getSummarization();
        }

        if (customView && beforeS && !mIsSummaryWithChildren) {
            if (beforeN) {
                smallHeight = mMaxSmallHeightBeforeN;
            } else if (beforeP) {
                smallHeight = mMaxSmallHeightBeforeP;
            } else {
                smallHeight = mMaxSmallHeightBeforeS;
            }
        } else if (isCallLayout) {
            smallHeight = maxExpandedHeight;
        } else if (NmSummarizationUiFlag.isEnabled()
                && isMessagingLayout
                && !TextUtils.isEmpty(summarization)) {
            smallHeight = mMaxSmallHeightWithSummarization;
        } else if (android.app.Flags.nmCollapsedLines()) {
            smallHeight = mMaxSmallHeightWithSummarization;
        } else {
            smallHeight = mMaxSmallHeight;
        }
        boolean headsUpCustom = layout.getHeadsUpChild() != null &&
                layout.getHeadsUpChild().getId()
                        != com.android.internal.R.id.status_bar_latest_event_content;
        int headsUpHeight;
        if (headsUpCustom && beforeS) {
            if (beforeN) {
                headsUpHeight = mMaxHeadsUpHeightBeforeN;
            } else if (beforeP) {
                headsUpHeight = mMaxHeadsUpHeightBeforeP;
            } else {
                headsUpHeight = mMaxHeadsUpHeightBeforeS;
            }
        } else {
            headsUpHeight = mMaxHeadsUpHeight;
        }
        NotificationViewWrapper headsUpWrapper = layout.getVisibleWrapper(
                VISIBLE_TYPE_HEADSUP);
        if (headsUpWrapper != null) {
            headsUpHeight = Math.max(headsUpHeight, headsUpWrapper.getMinLayoutHeight());
        }

        layout.setHeights(smallHeight, headsUpHeight, maxExpandedHeight);
    }

    /**
     * Check {@link NotificationBundleUi#isEnabled()}
     * and use {@link #getEntryAdapter()} when true
     * and {@link #getEntryLegacy()} when false.
     */
    @NonNull
    @Deprecated
    public NotificationEntry getEntryLegacy() {
        NotificationBundleUi.assertInLegacyMode();
        return mEntry;
    }

    /**
     * Check {@link NotificationBundleUi#isEnabled()}
     * and use {@link #getEntryAdapter()} when true
     * and {@link #getEntryLegacy()} when false.
     */
    @NonNull
    @Deprecated
    public NotificationEntry getEntry() {
        return mEntry;
    }

    @NonNull
    public EntryAdapter getEntryAdapter() {
        NotificationBundleUi.unsafeAssertInNewMode();
        return mEntryAdapter;
    }

    @Override
    public boolean isHeadsUp() {
        return mIsHeadsUp;
    }

    public void setHeadsUp(boolean isHeadsUp) {
        boolean wasAboveShelf = isAboveShelf();
        int intrinsicBefore = getIntrinsicHeight();
        mIsHeadsUp = isHeadsUp;
        mPrivateLayout.setHeadsUp(isHeadsUp);
        if (mIsSummaryWithChildren) {
            // The overflow might change since we allow more lines as HUN.
            mChildrenContainer.updateGroupOverflow();
        }
        if (intrinsicBefore != getIntrinsicHeight()) {
            notifyHeightChanged(/* needsAnimation= */ false);
        }
        if (isHeadsUp) {
            mMustStayOnScreen = true;
            setAboveShelf(true);
        } else if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
        updateIfNeeded();
    }

    /**
     * Indicate that the notification is showing the app icon instead of the small icon.
     */
    public void setIsShowingAppIcon(boolean isShowingAppIcon) {
        mIsShowingAppIcon = isShowingAppIcon;
    }

    /**
     * Whether or not the notification is showing the app icon instead of the small icon.
     */
    public boolean isShowingAppIcon() {
        return mIsShowingAppIcon;
    }

    @Override
    public boolean showingPulsing() {
        return isHeadsUpState() && (isDozing() || (mOnKeyguard && isBypassEnabled()));
    }

    /**
     * @return if the view is in heads up state, i.e either still heads upped or it's disappearing.
     */
    @Override
    public boolean isHeadsUpState() {
        return mIsHeadsUp || mHeadsupDisappearRunning;
    }

    public void setRemoteInputController(RemoteInputController r) {
        mPrivateLayout.setRemoteInputController(r);
    }

    /**
     * Return the cumulative y-value that the actions container expands via its scale animator when
     * remote input is activated.
     */
    public float getRemoteInputActionsContainerExpandedOffset() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return 0f;
        RemoteInputView expandedRemoteInput = mPrivateLayout.getExpandedRemoteInput();
        if (expandedRemoteInput == null) return 0f;
        View actionsContainerLayout = expandedRemoteInput.getActionsContainerLayout();
        if (actionsContainerLayout == null) return 0f;

        return actionsContainerLayout.getHeight() * (1 - FOCUS_ANIMATION_MIN_SCALE) * 0.5f;
    }

    public void addChildNotification(ExpandableNotificationRow row) {
        addChildNotification(row, -1);
    }

    /**
     * Set the how much the header should be visible. A value of 0 will make the header fully gone
     * and a value of 1 will make the notification look just like normal.
     * This is being used for heads up notifications, when they are pinned to the top of the screen
     * and the header content is extracted to the statusbar.
     *
     * @param headerVisibleAmount the amount the header should be visible.
     */
    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        if (mHeaderVisibleAmount != headerVisibleAmount) {
            mHeaderVisibleAmount = headerVisibleAmount;
            for (NotificationContentView l : mLayouts) {
                l.setHeaderVisibleAmount(headerVisibleAmount);
            }
            if (mChildrenContainer != null) {
                mChildrenContainer.setHeaderVisibleAmount(headerVisibleAmount);
            }
            notifyHeightChanged(/* needsAnimation= */ false);
        }
    }

    @Override
    public float getHeaderVisibleAmount() {
        return mHeaderVisibleAmount;
    }

    @Override
    public void markHeadsUpSeen() {
        super.markHeadsUpSeen();
        mMustStayOnScreen = false;
        if (mIsBlurSupported) {
            updateColors();
        }
    }

    /**
     *
     * @return true when compact version of Heads Up is on the screen.
     */
    public boolean isCompactConversationHeadsUpOnScreen() {
        final NotificationViewWrapper viewWrapper =
                getVisibleNotificationViewWrapper();

        return viewWrapper instanceof NotificationCompactMessagingTemplateViewWrapper;
    }
    /**
     * @see NotificationChildrenContainer#setUntruncatedChildCount(int)
     */
    public void setUntruncatedChildCount(int childCount) {
        if (mChildrenContainer == null) {
            mChildrenContainerStub.inflate();
        }
        mChildrenContainer.setUntruncatedChildCount(childCount);
    }

    /**
     * @see NotificationChildrenContainer#setNotificationGroupWhen(long)
     */
    public void setNotificationGroupWhen(long whenMillis) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setNotificationGroupWhen(whenMillis);
            mPublicLayout.setNotificationWhen(whenMillis);
        } else {
            Log.w(TAG, "setNotificationGroupWhen( whenMillis: " + whenMillis + ")"
                    + " mIsSummaryWithChildren: false"
                    + " mChildrenContainer has not been inflated yet.");
        }
    }

    /**
     * Called after children have been attached to set the expansion states
     */
    public void resetChildSystemExpandedStates() {
        if (isSummaryWithChildren()) {
            mChildrenContainer.updateExpansionStates();
        }
    }

    /**
     * Add a child notification to this view.
     *
     * @param row        the row to add
     * @param childIndex the index to add it at, if -1 it will be added at the end
     */
    public void addChildNotification(ExpandableNotificationRow row, int childIndex) {
        if (mChildrenContainer == null) {
            mChildrenContainerStub.inflate();
        }

        if (row.keepInParentForDismissAnimation()) {
            logSkipAttachingKeepInParentChild(row);
            return;
        }

        mChildrenContainer.addNotification(row, childIndex);
        onAttachedChildrenCountChanged();
        row.setIsChildInGroup(true, this);
    }

    public void removeChildNotification(ExpandableNotificationRow row) {
        if (mChildrenContainer != null) {
            mChildrenContainer.removeNotification(row);
            row.setKeepInParentForDismissAnimation(false);
        }
        onAttachedChildrenCountChanged();
        row.setIsChildInGroup(false, null);
    }

    /**
     * Removes the children notifications which were marked to keep for the dismissal animation.
     */
    public void removeChildrenWithKeepInParent() {
        if (mChildrenContainer == null) return;

        List<ExpandableNotificationRow> clonedList = new ArrayList<>(
                mChildrenContainer.getAttachedChildren());
        boolean childCountChanged = false;
        for (ExpandableNotificationRow child : clonedList) {
            if (child.keepInParentForDismissAnimation()) {
                mChildrenContainer.removeNotification(child);
                child.setIsChildInGroup(false, null);
                child.setKeepInParentForDismissAnimation(false);
                logKeepInParentChildDetached(child);
                childCountChanged = true;
            }
        }

        if (childCountChanged) {
            onAttachedChildrenCountChanged();
        }
    }

    /**
     * Returns the child notification at [index], or null if no such child.
     */
    @Nullable
    public ExpandableNotificationRow getChildNotificationAt(int index) {
        if (mChildrenContainer == null
                || mChildrenContainer.getAttachedChildren().size() <= index) {
            return null;
        } else {
            return mChildrenContainer.getAttachedChildren().get(index);
        }
    }

    @Override
    public boolean isChildInGroup() {
        return mNotificationParent != null;
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mNotificationParent;
    }

    /**
     * @param isChildInGroup Is this notification now in a group
     * @param parent         the new parent notification
     */
    public void setIsChildInGroup(boolean isChildInGroup, ExpandableNotificationRow parent) {
        if (mExpandAnimationRunning && !isChildInGroup && mNotificationParent != null) {
            mNotificationParent.setChildIsExpanding(false);
            mNotificationParent.setExpandingClipPath(null);
            mNotificationParent.setExtraWidthForClipping(0.0f);
            mNotificationParent.setMinimumHeightForClipping(0);
        }
        mNotificationParent = isChildInGroup ? parent : null;
        mPrivateLayout.setIsChildInGroup(isChildInGroup);
        mPublicLayout.setIsChildInGroup(isChildInGroup);

        updateBackgroundForGroupState();
        updateClickAndFocus();
        if (mNotificationParent != null) {
            setOverrideTintColor(NO_COLOR, 0.0f);
            mNotificationParent.updateBackgroundForGroupState();
        }
        updateBackgroundClipping();
        updateBaseRoundness();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Other parts of the system may intercept and handle all the falsing.
        // Otherwise, if we see motion and follow-on events, try to classify them as a tap.
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            mFalsingManager.isFalseTap(FalsingManager.MODERATE_PENALTY);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN
                || !isChildInGroup() || isGroupExpanded() || isBundledSummaryClickable()) {
            return super.onTouchEvent(event);
        } else {
            return false;
        }
    }

    @Override
    public boolean isSummaryWithChildren() {
        return mIsSummaryWithChildren;
    }

    @Override
    public boolean areChildrenExpanded() {
        return mChildrenExpanded;
    }

    public List<ExpandableNotificationRow> getAttachedChildren() {
        return mChildrenContainer == null ? null : mChildrenContainer.getAttachedChildren();
    }

    /**
     * Recursively collects the [{@link ExpandableViewState#location}]s populating the provided
     * map.
     * The visibility of each child is determined by the {@link View#getVisibility()}.
     * Locations are added to the provided map including locations from child views, that are
     * visible.
     */
    public void collectVisibleLocations(Map<String, Integer> locationsMap) {
        if (getVisibility() == View.VISIBLE) {
            locationsMap.put(getKey(), getViewState().location);
            if (mChildrenContainer != null) {
                List<ExpandableNotificationRow> children = mChildrenContainer.getAttachedChildren();
                for (int i = 0; i < children.size(); i++) {
                    children.get(i).collectVisibleLocations(locationsMap);
                }
            }
        }
    }

    /**
     * Updates states of all children.
     */
    public void updateChildrenStates() {
        if (mIsSummaryWithChildren) {
            ExpandableViewState parentState = getViewState();
            mChildrenContainer.updateState(parentState);
        }
    }

    /**
     * Applies children states.
     */
    public void applyChildrenState() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.applyState();
        }
    }

    /**
     * Starts child animations.
     */
    public void startChildAnimation(AnimationProperties properties) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.startAnimationToState(properties);
        }
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        if (!mIsSummaryWithChildren || !mChildrenExpanded) {
            return this;
        } else {
            ExpandableNotificationRow view = mChildrenContainer.getViewAtPosition(y);
            return view == null ? this : view;
        }
    }

    public NotificationGuts getGuts() {
        return mGuts;
    }

    /**
     * Set this notification to be pinned to the top if {@link #isHeadsUp()} is true. By doing this
     * the notification will be rendered on top of the screen.
     */
    public void setPinnedStatus(PinnedStatus pinnedStatus) {
        int intrinsicHeight = getIntrinsicHeight();
        boolean wasAboveShelf = isAboveShelf();
        mPinnedStatus = pinnedStatus;
        if (intrinsicHeight != getIntrinsicHeight()) {
            notifyHeightChanged(/* needsAnimation= */ false);
        }
        if (pinnedStatus.isPinned()) {
            setAnimationRunning(true);
            mExpandedWhenPinned = false;
        } else if (mExpandedWhenPinned) {
            setUserExpanded(true);
        }

        if (mIsBlurSupported) {
            updateColors();
        }
        setChronometerRunning(mLastChronometerRunning);
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
    }

    @Override
    public boolean isPinned() {
        return mPinnedStatus.isPinned();
    }

    @Override
    public PinnedStatus getPinnedStatus() {
        return mPinnedStatus;
    }

    @Override
    public int getPinnedHeadsUpHeight() {
        return getPinnedHeadsUpHeight(true /* atLeastMinHeight */);
    }

    /**
     * @param atLeastMinHeight should the value returned be at least the minimum height.
     *                         Used to avoid cyclic calls
     * @return the height of the heads up notification when pinned
     */
    private int getPinnedHeadsUpHeight(boolean atLeastMinHeight) {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getIntrinsicHeight();
        }
        if (isPromotedOngoing()) {
            return getMaxExpandHeight();
        }
        if (mExpandedWhenPinned) {
            return Math.max(getMaxExpandHeight(), getHeadsUpHeight());
        } else if (getShowingLayout().isHUNCompact()) {
            return getHeadsUpHeight();
        } else if (atLeastMinHeight) {
            return Math.max(getCollapsedHeight(), getHeadsUpHeight());
        } else {
            return getHeadsUpHeight();
        }
    }

    /**
     * Mark whether this notification was just clicked, i.e. the user has just clicked this
     * notification in this frame.
     */
    public void setJustClicked(boolean justClicked) {
        mJustClicked = justClicked;
    }

    /**
     * @return true if this notification has been clicked in this frame, false otherwise
     */
    public boolean wasJustClicked() {
        return mJustClicked;
    }

    public void setChronometerRunning(boolean running) {
        mLastChronometerRunning = running;
        setChronometerRunning(running, mPrivateLayout);
        setChronometerRunning(running, mPublicLayout);
        if (mChildrenContainer != null) {
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getAttachedChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.setChronometerRunning(running);
            }
        }
    }

    private void setChronometerRunning(boolean running, NotificationContentView layout) {
        if (layout != null) {
            running = running || isPinned();
            View contractedChild = layout.getContractedChild();
            View expandedChild = layout.getExpandedChild();
            View headsUpChild = layout.getHeadsUpChild();
            setChronometerRunningForChild(running, contractedChild);
            setChronometerRunningForChild(running, expandedChild);
            setChronometerRunningForChild(running, headsUpChild);
        }
    }

    private void setChronometerRunningForChild(boolean running, View child) {
        if (child != null) {
            View chronometer = child.findViewById(com.android.internal.R.id.chronometer);
            if (chronometer instanceof Chronometer) {
                ((Chronometer) chronometer).setStarted(running);
            }
        }
    }

    /**
     * @return the main notification view wrapper.
     */
    public NotificationViewWrapper getNotificationViewWrapper() {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getNotificationViewWrapper();
        }
        return mPrivateLayout.getNotificationViewWrapper();
    }

    /**
     * @return the currently visible notification view wrapper. This can be different from
     * {@link #getNotificationViewWrapper()} in case it is a low-priority group.
     */
    public NotificationViewWrapper getVisibleNotificationViewWrapper() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getVisibleWrapper();
        }
        return getShowingLayout().getVisibleWrapper();
    }

    /**
     * @return whether the notification row is long clickable or not.
     */
    public boolean isNotificationRowLongClickable() {
        if (mLongPressListener == null) {
            return false;
        }

        if (!areGutsExposed()) { // guts is not opened
            return true;
        }

        // if it is leave behind, it shouldn't be long clickable.
        return !isGutsLeaveBehind();
    }

    public void setLongPressListener(LongPressListener longPressListener) {
        mLongPressListener = longPressListener;
    }

    public void setDragController(ExpandableNotificationRowDragController dragController) {
        mDragController = dragController;
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        super.setOnClickListener(l);
        mOnClickListener = l;
        updateClickAndFocus();
    }

    /**
     * The click listener for the bubble button.
     */
    @Nullable
    public View.OnClickListener getBubbleClickListener() {
        return mBubbleClickListener;
    }

    /**
     * Sets the click listener for the bubble button.
     */
    public void setBubbleClickListener(@Nullable OnClickListener l) {
        mBubbleClickListener = l;
        // ensure listener is passed to the content views
        mPrivateLayout.updateBubbleButton(NotificationBundleUi.isEnabled()
                ? null
                : getEntryLegacy());
        mPublicLayout.updateBubbleButton(NotificationBundleUi.isEnabled()
                ? null
                : getEntryLegacy());
    }

    /**
     * The click listener for the snooze button.
     */
    public View.OnClickListener getSnoozeClickListener(MenuItem item) {
        return v -> {
            // Dismiss a snoozed notification if one is still left behind
            mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                    false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                    false /* resetMenu */);
            mNotificationGutsManager.openGuts(this, 0, 0, item);
            mIsSnoozed = true;
        };
    }

    @VisibleForTesting
    protected boolean isBundledSummaryClickable() {
        if (NotificationBundleUi.isEnabled()) {
            return mIsSummaryWithChildren && !isGroupExpanded() && isChildInGroup();
        } else {
            return false;
        }
    }

    private void updateClickAndFocus() {
        boolean normalChild = !isChildInGroup() || isGroupExpanded();
        boolean clickable = mOnClickListener != null
                && (normalChild || isBundledSummaryClickable());
        if (isFocusable() != normalChild) {
            setFocusable(normalChild);
        }
        if (isClickable() != clickable) {
            setClickable(clickable);
        }
    }

    public void setGutsView(MenuItem item) {
        if (getGuts() != null
                && item.getGutsContent() instanceof NotificationGuts.GutsContent gutsContent) {
            getGuts().setGutsContent(gutsContent);
        }
    }

    @Override
    public void onPluginConnected(NotificationMenuRowPlugin plugin, Context pluginContext) {
        boolean existed = mMenuRow != null && mMenuRow.getMenuView() != null;
        if (existed) {
            removeView(mMenuRow.getMenuView());
        }
        if (plugin == null) {
            return;
        }
        mMenuRow = plugin;
        if (mMenuRow.shouldUseDefaultMenuItems()) {
            ArrayList<MenuItem> items = new ArrayList<>();
            items.add(NotificationMenuRow.createConversationItem(mContext));
            items.add(NotificationMenuRow.createPartialConversationItem(mContext));
            items.add(NotificationMenuRow.createInfoItem(mContext));
            items.add(NotificationMenuRow.createSnoozeItem(mContext));
            mMenuRow.setMenuItems(items);
        }
        if (existed) {
            createMenu();
        }
    }

    @Override
    public void onPluginDisconnected(NotificationMenuRowPlugin plugin) {
        boolean existed = mMenuRow.getMenuView() != null;
        mMenuRow = new NotificationMenuRow(
                mContext, mPeopleNotificationIdentifier, mNotificationActivityStarter);
        if (existed) {
            createMenu();
        }
    }

    @Override
    public boolean hasFinishedInitialization() {
        if (NotificationBundleUi.isEnabled()) {
            return initializationTime != -1
                    && SystemClock.elapsedRealtime() > initializationTime + INITIALIZATION_DELAY;
        } else {
            return getEntryLegacy().hasFinishedInitialization();
        }
    }

    public void resetInitializationTime() {
        initializationTime = -1;
    }

    public void setInitializationTime(long time) {
        if (initializationTime == -1) {
            initializationTime = time;
        }
    }

    /**
     * Get a handle to a NotificationMenuRowPlugin whose menu view has been added to our hierarchy,
     * or null if there is no menu row
     *
     * @return a {@link NotificationMenuRowPlugin}, or null
     */
    @Nullable
    public NotificationMenuRowPlugin createMenu() {
        if (mMenuRow == null) {
            return null;
        }
        if (mMenuRow.getMenuView() == null) {
            mMenuRow.createMenu(this);
            mMenuRow.setAppName(mAppName);
            FrameLayout.LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            addView(mMenuRow.getMenuView(), MENU_VIEW_INDEX, lp);
        }
        return mMenuRow;
    }

    @Nullable
    public NotificationMenuRowPlugin getProvider() {
        return mMenuRow;
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        initDimens();
        initBackground();
        reInflateViews();
        if (mChildrenContainer != null) {
            mChildrenContainer.dispatchConfigurationChanged(getResources().getConfiguration());
        }
    }

    private void reInflateViews() {
        Trace.beginSection("ExpandableNotificationRow#reInflateViews");
        // Let's update our childrencontainer. This is intentionally not guarded with
        // mIsSummaryWithChildren since we might have had children but not anymore.
        if (mChildrenContainer != null) {
            mChildrenContainer.reInflateViews(mExpandClickListener);
        }
        if (mGuts != null) {
            NotificationGuts oldGuts = mGuts;
            int index = indexOfChild(oldGuts);
            removeView(oldGuts);
            mGuts = (NotificationGuts) LayoutInflater.from(mContext).inflate(
                    R.layout.notification_guts, this, false);
            mGuts.setVisibility(oldGuts.isExposed() ? VISIBLE : GONE);
            addView(mGuts, index);
        }
        View oldMenu = mMenuRow == null ? null : mMenuRow.getMenuView();
        if (oldMenu != null) {
            int menuIndex = indexOfChild(oldMenu);
            removeView(oldMenu);
            mMenuRow.createMenu(ExpandableNotificationRow.this);
            mMenuRow.setAppName(mAppName);
            addView(mMenuRow.getMenuView(), menuIndex);
        }
        for (NotificationContentView l : mLayouts) {
            l.reinflate();
            l.reInflateViews();
        }
        if (NotificationBundleUi.isEnabled() && !getEntryAdapter().isViewBacked()) {
            return;
        }
        // TODO: Move content inflation logic out of this call
        if (NotificationBundleUi.isEnabled()) {
            mEntryAdapter.prepareForInflation();
            getEntryAdapter().markForReinflation(mRowContentBindStage);
        } else {
            getEntryLegacy().getSbn().clearPackageContext();
            RowContentBindParams params = mRowContentBindStage.getStageParams(getEntryLegacy());
            params.setNeedsReinflation(true);
        }

        NotificationRebindingTracker.RebindFinishedCallback rebindEndCallback
                = mRebindingTracker.trackRebinding(getKey());
        if (NotificationBundleUi.isEnabled()) {
            getEntryAdapter().requestRebind(mRowContentBindStage,
                    (e) -> rebindEndCallback.onFinished());
        } else {
            mRowContentBindStage.requestRebind(getEntryLegacy(),
                    (e) -> rebindEndCallback.onFinished());
        }
        Trace.endSection();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onConfigurationChanged();
        }
        if (mImageResolver != null) {
            mImageResolver.updateMaxImageSizes();
        }
        if (mBigPictureIconManager != null) {
            mBigPictureIconManager.updateMaxImageSizes();
        }
    }

    public void onUiModeChanged() {
        if (isBundle()) {
            // If this is a bundle, then `onNotificationUpdated` will not be called as there is no
            // backing notification entry for bundles. So update the background immediately.
            updateBackgroundColorsOfSelf();
        } else {
            mUpdateSelfBackgroundOnUpdate = true;
        }
        reInflateViews();
        if (mChildrenContainer != null) {
            for (ExpandableNotificationRow child : mChildrenContainer.getAttachedChildren()) {
                child.onUiModeChanged();
            }
        }
    }

    public void setContentBackground(int customBackgroundColor, boolean animate,
            NotificationContentView notificationContentView) {
        if (getShowingLayout() == notificationContentView) {
            setTintColor(customBackgroundColor, animate);
        }
    }

    @Override
    protected void setBackgroundTintColor(int color) {
        if (usesTransparentBackground()) {
            boolean isColorized = false;
            if (NotificationBundleUi.isEnabled()) {
                if (mEntryAdapter != null) {
                    isColorized = mEntryAdapter.isColorized();
                }
            } else {
                if (getEntryLegacy() != null && getEntryLegacy().getSbn() != null) {
                    isColorized = getEntryLegacy().getSbn().getNotification().isColorized();
                }
            }
            boolean isTransparent = usesTransparentBackground();
            if (isColorized) {
                // For colorized notifications, use a color that matches the tint color at 90% alpha
                // when the row is transparent.
                color = ColorUtils.setAlphaComponent(
                        color, (int) (0xFF * (isTransparent ? 0.9f : 1)));
            } else {
                // For non-colorized notifications, use the semi-transparent normal color token
                // when the row is transparent, and the opaque color token otherwise.
                if (!isTransparent && mBgTint == NO_COLOR) {
                    color = mOpaqueColor;
                }
            }
        }
        super.setBackgroundTintColor(color);
        NotificationContentView view = getShowingLayout();
        if (view != null) {
            view.setBackgroundTintColor(color);
        }
    }

    public void closeRemoteInput() {
        for (NotificationContentView l : mLayouts) {
            l.closeRemoteInput();
        }
    }

    /**
     * Set by how much the single line view should be indented.
     */
    public void setSingleLineWidthIndention(int indention) {
        mPrivateLayout.setSingleLineWidthIndention(indention);
    }

    public HybridNotificationView getSingleLineView() {
        return mPrivateLayout.getSingleLineView();
    }

    @Override
    public void dismiss(boolean refocusOnDismiss) {
        super.dismiss(refocusOnDismiss);
        if (!isBundle()) {
            setLongPressListener(null);
            setDragController(null);
        }
        if (isBundle()) {
            mIsSystemExpanded = false;
        }
        mGroupParentWhenDismissed = mNotificationParent;
        mChildAfterViewWhenDismissed = null;
        if (isChildInGroup()) {
            List<ExpandableNotificationRow> notificationChildren =
                    mNotificationParent.getAttachedChildren();
            int i = notificationChildren.indexOf(this);
            if (i != -1 && i < notificationChildren.size() - 1) {
                mChildAfterViewWhenDismissed = notificationChildren.get(i + 1);
            }
        }
    }

    /**
     * @return if this entry should be kept in its parent during removal.
     */
    public boolean keepInParentForDismissAnimation() {
        return mKeepInParentForDismissAnimation;
    }

    public void setKeepInParentForDismissAnimation(boolean keepInParent) {
        mKeepInParentForDismissAnimation = keepInParent;
    }

    /** @return true if the User has dismissed this notif's parent */
    public boolean isParentDismissed() {
        if (NotificationBundleUi.isEnabled()) {
            return getEntryAdapter().isParentDismissed();
        } else {
            return getEntryLegacy().getDismissState() == PARENT_DISMISSED;
        }
    }

    @Override
    public boolean isRemoved() {
        return mRemoved;
    }

    public void setRemoved() {
        mRemoved = true;
        mTranslationWhenRemoved = getTranslationY();
        mWasChildInGroupWhenRemoved = isChildInGroup();
        if (isChildInGroup()) {
            mTranslationWhenRemoved += getNotificationParent().getTranslationY();
        }
        for (NotificationContentView l : mLayouts) {
            l.setRemoved();
        }
    }

    public boolean wasChildInGroupWhenRemoved() {
        return mWasChildInGroupWhenRemoved;
    }

    public float getTranslationWhenRemoved() {
        return mTranslationWhenRemoved;
    }

    public NotificationChildrenContainer getChildrenContainer() {
        return mChildrenContainer;
    }

    /**
     * @return An non-null instance of mChildrenContainer, inflate it if not yet.
     */
    public @NonNull NotificationChildrenContainer getChildrenContainerNonNull() {
        if (mChildrenContainer == null) {
            mChildrenContainerStub.inflate();
        }
        return mChildrenContainer;
    }

    /**
     * Set the group notification header view
     * @param headerView header view to set
     */
    public void setGroupHeader(NotificationHeaderView headerView) {
        NotificationChildrenContainer childrenContainer = getChildrenContainerNonNull();
        childrenContainer.setGroupHeader(
                /* headerView= */ headerView,
                /* onClickListener= */ mExpandClickListener
        );
        if (TransparentHeaderFix.isEnabled()) {
            updateBackgroundForGroupState();
        }
    }

    /**
     * Set the low-priority group notification header view
     * @param headerView header view to set
     */
    public void setMinimizedGroupHeader(NotificationHeaderView headerView) {
        NotificationChildrenContainer childrenContainer = getChildrenContainerNonNull();
        childrenContainer.setLowPriorityGroupHeader(
                /* headerViewLowPriority= */ headerView,
                /* onClickListener= */ mExpandClickListener
        );
    }

    /**
     * Set the redaction type of the row.
     */
    public void setRedactionType(@RedactionType int redactionType) {
        mRedactionType = redactionType;
    }

    public void setBundleHeaderView(@NonNull ComposeView view) {
        if (NotificationBundleUi.isUnexpectedlyInLegacyMode()) return;
        NotificationChildrenContainer childrenContainer = getChildrenContainerNonNull();
        childrenContainer.setBundleHeaderView(view);
    }

    public void setBundleHeaderViewModel(@Nullable BundleHeaderViewModel viewModel) {
        if (NotificationBundleUi.isUnexpectedlyInLegacyMode()) return;
        NotificationChildrenContainer childrenContainer = getChildrenContainerNonNull();
        childrenContainer.setBundleHeaderViewModel(viewModel);
        if (TransparentHeaderFix.isEnabled()) {
            updateBackgroundForGroupState();
        }
    }

    public float getChildRenderingStartPosition() {
        if (!isSummaryWithChildren()) {
            return 0;
        }
        return getChildrenContainerNonNull().getChildRenderingStartPosition();
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        boolean wasAboveShelf = isAboveShelf();
        boolean changed = headsUpAnimatingAway != mHeadsupDisappearRunning;
        mHeadsupDisappearRunning = headsUpAnimatingAway;
        mPrivateLayout.setHeadsUpAnimatingAway(headsUpAnimatingAway);
        if (changed && mHeadsUpAnimatingAwayListener != null) {
            mHeadsUpAnimatingAwayListener.accept(headsUpAnimatingAway);
        }
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
        if (mIsBlurSupported) {
            updateColors();
        }
    }

    public void setHeadsUpAnimatingAwayListener(Consumer<Boolean> listener) {
        mHeadsUpAnimatingAwayListener = listener;
    }

    /**
     * @return if the view was just heads upped and is now animating away. During such a time the
     * layout needs to be kept consistent
     */
    @Override
    public boolean isHeadsUpAnimatingAway() {
        return mHeadsupDisappearRunning;
    }

    public View getChildAfterViewWhenDismissed() {
        return mChildAfterViewWhenDismissed;
    }

    public View getGroupParentWhenDismissed() {
        return mGroupParentWhenDismissed;
    }

    /**
     * Dismisses the notification.
     *
     * @param fromAccessibility whether this dismiss is coming from an accessibility action
     */
    public void performDismiss(boolean fromAccessibility) {
        mMetricsLogger.count(NotificationCounters.NOTIFICATION_DISMISSED, 1);
        dismiss(fromAccessibility);
        if (canEntryBeDismissed()) {
            if (mOnUserInteractionCallback != null) {
                Runnable futureDismissal = NotificationBundleUi.isEnabled()
                        ? getEntryAdapter().registerFutureDismissal()
                        : mOnUserInteractionCallback.registerFutureDismissal(
                                getEntryLegacy(), REASON_CANCEL);
                if (futureDismissal != null) {
                    post(futureDismissal);
                }
            }
        }
    }

    @Override
    public View getShelfTransformationTarget() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            NotificationViewWrapper viewWrapper = mChildrenContainer.getVisibleWrapper();
            if (AsyncGroupHeaderViewInflation.isEnabled() && viewWrapper == null) {
                return null;
            }
            return viewWrapper.getShelfTransformationTarget();
        }
        return getShowingLayout().getShelfTransformationTarget();
    }

    /**
     * @return whether the notification is currently showing a view with an icon.
     */
    public boolean isShowingIcon() {
        if (areGutsExposed()) {
            return false;
        }
        return getShelfTransformationTarget() != null;
    }

    @Override
    protected void updateContentTransformation() {
        if (mExpandAnimationRunning) {
            return;
        }
        super.updateContentTransformation();
    }

    @Override
    protected void applyContentTransformation(float contentAlpha, float translationY) {
        super.applyContentTransformation(contentAlpha, translationY);
        if (!mIsLastChild) {
            // Don't fade views unless we're last
            contentAlpha = 1.0f;
        }
        for (NotificationContentView l : mLayouts) {
            l.setAlpha(contentAlpha);
            l.setTranslationY(translationY);
        }
        if (mChildrenContainer != null) {
            mChildrenContainer.setAlpha(contentAlpha);
            mChildrenContainer.setTranslationY(translationY);
            // TODO: handle children fade out better
        }
    }

    /**
     * Sets the alpha on the content, while leaving the background of the row itself as is.
     *
     * @param alpha alpha value to apply to the notification content
     */
    public void setContentAlpha(float alpha) {
        for (NotificationContentView l : mLayouts) {
            l.setAlpha(alpha);
        }
        if (mChildrenContainer != null) {
            mChildrenContainer.setContentAlpha(alpha);
        }
    }

    /**
     * Set if the row is minimized.
     */
    public void setIsMinimized(boolean isMinimized) {
        mIsMinimized = isMinimized;
        mPrivateLayout.setIsLowPriority(isMinimized);
        if (mChildrenContainer != null) {
            mChildrenContainer.setIsMinimized(isMinimized);
        }
    }

    public boolean isMinimized() {
        return mIsMinimized;
    }

    /**
     * Interface for logging {{@link ExpandableNotificationRow} events.}
     */
    public interface ExpandableNotificationRowLogger {
        /**
         * Called when the notification is expanded / collapsed.
         */
        void logNotificationExpansion(String key, int location, boolean userAction,
                boolean expanded);

        /**
         * Called when a notification which was previously kept in its parent for the
         * dismiss animation is finally detached from its parent.
         */
        void logKeepInParentChildDetached(String child, String oldParent);

        /**
         * Called when we want to attach a notification to a new parent,
         * but it still has the keepInParent flag set, so we skip it.
         */
        void logSkipAttachingKeepInParentChild(
                String child,
                String newParent
        );

        /**
         * Called when an ExpandableNotificationRow transient view is removed from the
         * NotificationChildrenContainer
         */
        void logRemoveTransientFromContainer(
                String childEntry,
                String containerEntry
        );

        /**
         * Called when an ExpandableNotificationRow transient view is removed from the
         * NotificationStackScrollLayout
         */
        void logRemoveTransientFromNssl(
                String childEntry
        );

        /**
         * Called when an ExpandableNotificationRow transient view is removed from a ViewGroup that
         * is not NotificationChildrenContainer or NotificationStackScrollLayout
         */
        void logRemoveTransientFromViewGroup(
                String childEntry,
                ViewGroup containerView
        );

        /**
         * Called when an ExpandableNotificationRow transient view is added to this
         * ExpandableNotificationRow
         */
        void logAddTransientRow(
                String childEntry,
                String containerEntry,
                int index
        );

        /**
         * Called when an ExpandableNotificationRow transient view is removed from this
         * ExpandableNotificationRow
         */
        void logRemoveTransientRow(
                String childEntry,
                String containerEntry
        );

        /**
         * Called when resetting the alpha value for content views
         */
        void logResetAllContentAlphas(
                String entry
        );

        /**
         * Called when resetting the alpha value for content views is skipped
         */
        void logSkipResetAllContentAlphas(
                String entry
        );

        /** Called when we start an appear animation. */
        void logStartAppearAnimation(String entry, boolean isAppear);

        /** Called when we cancel the running appear animation. */
        void logCancelAppearDrawing(String entry, boolean wasDrawing);

        /** Called when the animator of the appear animation is started. */
        void logAppearAnimationStarted(String entry, boolean isAppear);

        /** Called when we prepared an appear animation, but the animator was never started. */
        void logAppearAnimationSkipped(String entry, boolean isAppear);

        /** Called when the animator of the appear animation is finished. */
        void logAppearAnimationFinished(
                String entry,
                boolean isAppear,
                boolean cancelled
        );
    }

    /**
     * Constructs an ExpandableNotificationRow. Used by layout inflation.
     *
     * @param context passed to image resolver
     * @param attrs   attributes used to initialize parent view
     */
    public ExpandableNotificationRow(Context context, AttributeSet attrs) {
        this(context, attrs, context);
        // NOTE(b/317503801): Always crash when using the insecure constructor.
        throw new UnsupportedOperationException("Insecure constructor");
    }

    /**
     * Constructs an ExpandableNotificationRow. Used by layout inflation (with a custom {@code
     * AsyncLayoutFactory} in {@link RowInflaterTask}.
     *
     * @param context context context of the view
     * @param attrs   attributes used to initialize parent view
     * @param user   the user the row is associated to
     */
    public ExpandableNotificationRow(Context context, AttributeSet attrs, UserHandle user) {
        this(context, attrs, userContextForEntry(context, user));
        NotificationBundleUi.unsafeAssertInNewMode();
    }

    /**
     * Constructs an ExpandableNotificationRow. Used by layout inflation (with a custom {@code
     * AsyncLayoutFactory} in {@link RowInflaterTask}.
     *
     * @param context context context of the view
     * @param attrs   attributes used to initialize parent view
     * @param entry   notification that the row will be associated to (determines the user for the
     *                ImageResolver)
     */
    public ExpandableNotificationRow(Context context, AttributeSet attrs, NotificationEntry entry) {
        this(context, attrs, userContextForEntry(context, entry));
        NotificationBundleUi.assertInLegacyMode();
    }

    private static Context userContextForEntry(Context base, NotificationEntry entry) {
        if (base.getUserId() == entry.getSbn().getNormalizedUserId()) {
            return base;
        }
        return base.createContextAsUser(
                UserHandle.of(entry.getSbn().getNormalizedUserId()), /* flags= */ 0);
    }

    private static Context userContextForEntry(Context base, UserHandle user) {
        if (base.getUserId() == user.getIdentifier()) {
            return base;
        }
        return base.createContextAsUser(user, /* flags= */ 0);
    }

    private ExpandableNotificationRow(Context sysUiContext, AttributeSet attrs,
            Context userContext) {
        super(sysUiContext, attrs);
        mImageResolver = new NotificationInlineImageResolver(userContext,
                new NotificationInlineImageCache());
        float radius = getResources().getDimension(R.dimen.notification_corner_radius_small);
        mSmallRoundness = radius / getMaxRadius();
        mMagneticAnimator = new SpringAnimation(
                this, FloatPropertyCompat.createFloatPropertyCompat(TRANSLATE_CONTENT));
        initDimens();
    }

    /**
     * Initialize row.
     */
    public void initialize(
            EntryAdapter entryAdapter,
            PipelineEntry entry,
            RemoteInputViewSubcomponent.Factory rivSubcomponentFactory,
            String appName,
            @NonNull String notificationKey,
            ExpandableNotificationRowLogger logger,
            KeyguardBypassController bypassController,
            GroupMembershipManager groupMembershipManager,
            GroupExpansionManager groupExpansionManager,
            HeadsUpManager headsUpManager,
            RowContentBindStage rowContentBindStage,
            OnExpandClickListener onExpandClickListener,
            CoordinateOnClickListener onFeedbackClickListener,
            FalsingManager falsingManager,
            StatusBarStateController statusBarStateController,
            PeopleNotificationIdentifier peopleNotificationIdentifier,
            OnUserInteractionCallback onUserInteractionCallback,
            NotificationGutsManager gutsManager,
            NotificationDismissibilityProvider dismissibilityProvider,
            MetricsLogger metricsLogger,
            NotificationChildrenContainerLogger childrenContainerLogger,
            ColorUpdateLogger colorUpdateLogger,
            SmartReplyConstants smartReplyConstants,
            SmartReplyController smartReplyController,
            IStatusBarService statusBarService,
            UiEventLogger uiEventLogger,
            NotificationRebindingTracker notificationRebindingTracker,
            BundleInteractionLogger bundleInteractionLogger,
            NotificationActivityStarter notificationActivityStarter,
            AxAppLockerHelper axAppLockerHelper) {

        if (NotificationBundleUi.isEnabled()) {
            mEntryAdapter = entryAdapter;
            mIsBundle = entryAdapter instanceof BundleEntryAdapter;
            if (isBundle()) {
                // Initial dimensions for the roundable state are set upon construction, which means
                // we need to re-calculate at the point when we know whether this row is a bundle.
                updateMaxRadius();
            }
        } else {
            mEntry = (NotificationEntry) entry;
        }
        mAppName = appName;
        mRebindingTracker = notificationRebindingTracker;
        mNotificationActivityStarter = notificationActivityStarter;
        mAxAppLockerHelper = axAppLockerHelper;
        if (mMenuRow == null) {
            mMenuRow = new NotificationMenuRow(
                    mContext, peopleNotificationIdentifier, mNotificationActivityStarter);
        }
        if (mMenuRow.getMenuView() != null) {
            mMenuRow.setAppName(mAppName);
        }
        mLogger = logger;
        mKey = notificationKey;
        mLoggingKey = logKey(notificationKey);
        mBypassController = bypassController;
        mGroupMembershipManager = groupMembershipManager;
        mGroupExpansionManager = groupExpansionManager;
        mPrivateLayout.setGroupMembershipManager(groupMembershipManager);
        mHeadsUpManager = headsUpManager;
        mRowContentBindStage = rowContentBindStage;
        mOnExpandClickListener = onExpandClickListener;
        setOnFeedbackClickListener(onFeedbackClickListener);
        mFalsingManager = falsingManager;
        mStatusBarStateController = statusBarStateController;
        mPeopleNotificationIdentifier = peopleNotificationIdentifier;
        if (rivSubcomponentFactory != null) {
            for (NotificationContentView l : mLayouts) {
                l.initialize(
                        mPeopleNotificationIdentifier,
                        rivSubcomponentFactory,
                        smartReplyConstants,
                        smartReplyController,
                        statusBarService,
                        uiEventLogger
                );
            }
        }

        mOnUserInteractionCallback = onUserInteractionCallback;
        mNotificationGutsManager = gutsManager;
        mMetricsLogger = metricsLogger;
        mChildrenContainerLogger = childrenContainerLogger;
        mColorUpdateLogger = colorUpdateLogger;
        mDismissibilityProvider = dismissibilityProvider;
        mBundleInteractionLogger = bundleInteractionLogger;
        setHapticFeedbackEnabled(!Flags.msdlFeedback());
    }

    private void initDimens() {
        mMaxSmallHeightBeforeN = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_legacy);
        mMaxSmallHeightBeforeP = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_before_p);
        mMaxSmallHeightBeforeS = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_min_height_before_s);
        if (notificationsRedesignTemplates()) {
            mMaxSmallHeight = NotificationUtils.getFontScaledHeight(mContext,
                    R.dimen.notification_2025_min_height);
        } else {
            mMaxSmallHeight = NotificationUtils.getFontScaledHeight(mContext,
                    R.dimen.notification_min_height);
        }
        mMaxSmallHeightWithSummarization = NotificationUtils.getFontScaledHeight(mContext,
                com.android.internal.R.dimen.notification_collapsed_height_with_summarization);
        mMaxExpandedHeight = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_height);
        mMaxExpandedHeightForPromotedOngoing = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_height_for_promoted_ongoing);
        mMaxHeadsUpHeightBeforeN = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height_legacy);
        mMaxHeadsUpHeightBeforeP = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height_before_p);
        mMaxHeadsUpHeightBeforeS = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height_before_s);
        mMaxHeadsUpHeight = NotificationUtils.getFontScaledHeight(mContext,
                R.dimen.notification_max_heads_up_height);

        Resources res = getResources();
        mEnableNonGroupedNotificationExpand =
                res.getBoolean(R.bool.config_enableNonGroupedNotificationExpand);
        mShowGroupBackgroundWhenExpanded =
                res.getBoolean(R.bool.config_showGroupNotificationBgWhenExpanded);
    }

    NotificationInlineImageResolver getImageResolver() {
        return mImageResolver;
    }

    public BigPictureIconManager getBigPictureIconManager() {
        return mBigPictureIconManager;
    }

    public void setBigPictureIconManager(
            BigPictureIconManager bigPictureIconManager) {
        mBigPictureIconManager = bigPictureIconManager;
    }


    /**
     * Resets this view so it can be re-used for an updated notification.
     */
    public void reset() {
        mShowingPublicInitialized = false;
        unDismiss();
        if (mMenuRow == null || !mMenuRow.isMenuVisible()) {
            resetTranslation();
        }
        onHeightReset();
        requestLayout();

        setTargetPoint(null);

        if (isBundle()) {
            mIsSystemExpanded = false;
        }
    }

    /**
     * Shows the given feedback icon, or hides the icon if null.
     */
    public void setFeedbackIcon(@Nullable FeedbackIcon icon) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setFeedbackIcon(icon);
        }
        mPrivateLayout.setFeedbackIcon(icon);
        mPublicLayout.setFeedbackIcon(icon);
    }

    /**
     * Sets the last time the notification being displayed audibly alerted the user.
     */
    public void setLastAudiblyAlertedMs(long lastAudiblyAlertedMs) {
        long timeSinceAlertedAudibly = System.currentTimeMillis() - lastAudiblyAlertedMs;
        boolean alertedRecently = timeSinceAlertedAudibly < RECENTLY_ALERTED_THRESHOLD_MS;

        applyAudiblyAlertedRecently(alertedRecently);

        removeCallbacks(mExpireRecentlyAlertedFlag);
        if (alertedRecently) {
            long timeUntilNoLongerRecent = RECENTLY_ALERTED_THRESHOLD_MS - timeSinceAlertedAudibly;
            postDelayed(mExpireRecentlyAlertedFlag, timeUntilNoLongerRecent);
        }
    }

    @VisibleForTesting
    @Deprecated
    protected void setEntryLegacy(NotificationEntry entry) {
        NotificationBundleUi.assertInLegacyMode();
        mEntry = entry;
    }

    @VisibleForTesting
    protected void setEntryAdapter(EntryAdapter entry) {
        mEntryAdapter = entry;
    }

    private final Runnable mExpireRecentlyAlertedFlag = () -> applyAudiblyAlertedRecently(false);

    private void applyAudiblyAlertedRecently(boolean audiblyAlertedRecently) {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        }
        mPrivateLayout.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        mPublicLayout.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
    }

    public View.OnClickListener getFeedbackOnClickListener() {
        return mOnFeedbackClickListener;
    }

    void setOnFeedbackClickListener(CoordinateOnClickListener l) {
        mOnFeedbackClickListener = v -> {
            createMenu();
            NotificationMenuRowPlugin provider = getProvider();
            if (provider == null) {
                return;
            }
            MenuItem menuItem = provider.getFeedbackMenuItem(mContext);
            if (menuItem != null) {
                l.onClick(this, v.getWidth() / 2, v.getHeight() / 2, menuItem);
            }
        };
    }

    /**
     * Retrieves an OnClickListener for the dismiss button of this notification, which when invoked,
     * dismisses the notification represented by this ExpandableNotificationRow.
     */
    public View.OnClickListener getDismissButtonOnClickListener() {
        return v -> performDismiss(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection(appendTraceStyleTag("ExpNotRow#onMeasure"));
        if (DEBUG_ONMEASURE) {
            Log.d(TAG, "onMeasure("
                    + "widthMeasureSpec=" + MeasureSpec.toString(widthMeasureSpec) + ", "
                    + "heightMeasureSpec=" + MeasureSpec.toString(heightMeasureSpec) + ")");
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (shouldSimulateSlowMeasure()) {
            simulateExtraMeasureDelay();
        }
        Trace.endSection();
    }

    private void simulateExtraMeasureDelay() {
        // Add extra delay in a notification row instead of NotificationStackScrollLayout
        // to make sure that when the measure cache is used we won't add this delay
        try {
            Trace.beginSection("ExtraDebugMeasureDelay");
            Thread.sleep(SLOW_MEASURE_SIMULATE_DELAY_MS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Generates and appends "(MessagingStyle)" type tag to passed string for tracing.
     */
    @NonNull
    private String appendTraceStyleTag(@NonNull String traceTag) {
        if (!Trace.isEnabled()) {
            return traceTag;
        }

        if (NotificationBundleUi.isEnabled()) {
            return traceTag + "(" + getEntryAdapter().getStyle() + ")";
        } else {
            return traceTag + "(" + getEntryLegacy().getNotificationStyle() + ")";
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPublicLayout = findViewById(R.id.expandedPublic);
        mPrivateLayout = findViewById(R.id.expanded);
        mLayouts = new NotificationContentView[]{mPrivateLayout, mPublicLayout};

        for (NotificationContentView l : mLayouts) {
            l.setExpandClickListener(mExpandClickListener);
            l.setContainingNotification(this);
        }
        mGutsStub = findViewById(R.id.notification_guts_stub);
        mGutsStub.setOnInflateListener((stub, inflated) -> {
            mGuts = (NotificationGuts) inflated;
            mGuts.setClipTopAmount(getClipTopAmount());
            mGuts.setClipBottomAmount(getClipBottomAmount());
            mGuts.setTopOverlap(mTopOverlap);
            mGuts.setBottomOverlap(mBottomOverlap);
            mGuts.setActualHeight(getActualHeight());
            mGutsStub = null;
        });
        mChildrenContainerStub = findViewById(R.id.child_container_stub);
        mChildrenContainerStub.setOnInflateListener((stub, inflated) -> {
            mChildrenContainer = (NotificationChildrenContainer) inflated;
            mChildrenContainer.setIsMinimized(mIsMinimized);
            mChildrenContainer.setContainingNotification(ExpandableNotificationRow.this);
            mChildrenContainer.onNotificationUpdated();
            mChildrenContainer.setLogger(mChildrenContainerLogger);

            mTranslateableViews.add(mChildrenContainer);
        });

        // Add the views that we translate to reveal the menu
        mTranslateableViews = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            mTranslateableViews.add(getChildAt(i));
        }
        // Remove views that don't translate
        mTranslateableViews.remove(mChildrenContainerStub);
        mTranslateableViews.remove(mGutsStub);
        // We don't handle focus highlight in this view, it's done in background drawable instead
        setDefaultFocusHighlightEnabled(false);

        if (NotificationAddXOnHoverToDismiss.isEnabled()) {
            addDismissButtonTargetStateListener(findViewById(R.id.backgroundNormal));
        }
    }

    /**
     * Called once when starting drag motion after opening notification guts,
     * in case of notification that has {@link android.app.Notification#contentIntent}
     * and it is to start an activity.
     */
    public void doDragCallback(float x, float y) {
        if (mDragController != null) {
            setTargetPoint(new Point((int) x, (int) y));
            mDragController.startDragAndDrop(this);
        }
    }

    public void setOnDragSuccessListener(OnDragSuccessListener listener) {
        mOnDragSuccessListener = listener;
    }

    /**
     * Called when a notification is dropped on proper target window.
     */
    public void dragAndDropSuccess() {
        if (mOnDragSuccessListener != null) {
            if (NotificationBundleUi.isEnabled()) {
                mOnDragSuccessListener.onDragSuccess(getEntryAdapter());
            } else {
                mOnDragSuccessListener.onDragSuccess(getEntryLegacy());
            }
        }
    }

    private void doLongClickCallback() {
        doLongClickCallback(getWidth() / 2, getHeight() / 2);
    }

    public void doLongClickCallback(int x, int y) {
        createMenu();
        NotificationMenuRowPlugin provider = getProvider();
        MenuItem menuItem = null;
        if (provider != null) {
            menuItem = provider.getLongpressMenuItem(mContext);
        }
        doLongClickCallback(x, y, menuItem);
    }

    /**
     * Perform a smart action which triggers a longpress (expose guts).
     * Based on the semanticAction passed, may update the state of the guts view.
     *
     * @param semanticAction associated with this smart action click
     */
    public void doSmartActionClick(int x, int y, int semanticAction) {
        createMenu();
        NotificationMenuRowPlugin provider = getProvider();
        MenuItem menuItem = null;
        if (provider != null) {
            menuItem = provider.getLongpressMenuItem(mContext);
        }
        if (SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY == semanticAction
                && menuItem.getGutsContent() instanceof NotificationConversationInfo gutsContent) {
            gutsContent.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);
        }
        doLongClickCallback(x, y, menuItem);
    }

    private void doLongClickCallback(int x, int y, MenuItem menuItem) {
        if (mLongPressListener != null && menuItem != null) {
            mLongPressListener.onLongPress(this, x, y, menuItem);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            if (!event.isCanceled()) {
                performClick();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            doLongClickCallback();
            return true;
        }
        return false;
    }

    public void resetTranslation() {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }

        if (mDismissUsingRowTranslationX) {
            setTranslationX(0);
        } else if (mTranslateableViews != null) {
            for (int i = 0; i < mTranslateableViews.size(); i++) {
                mTranslateableViews.get(i).setTranslationX(0);
            }
            invalidateOutline();
            @Nullable final StatusBarIconView shelfIcon = getShelfIcon();
            // TODO(b/416147040): Remove null check, will be unnecessary
            if (shelfIcon != null) {
                shelfIcon.setScrollX(0);
            }
        }

        if (mMenuRow != null) {
            mMenuRow.resetMenu();
        }
    }

    void onGutsOpened() {
        resetTranslation();
        updateContentAccessibilityImportanceForGuts(false /* isEnabled */);
    }

    void onGutsClosed() {
        updateContentAccessibilityImportanceForGuts(true /* isEnabled */);
        mIsSnoozed = false;
    }

    /**
     * Updates whether all the non-guts content inside this row is important for accessibility.
     *
     * @param isEnabled whether the content views should be enabled for accessibility
     */
    private void updateContentAccessibilityImportanceForGuts(boolean isEnabled) {
        updateAccessibilityImportance(isEnabled);

        if (mChildrenContainer != null) {
            updateChildAccessibilityImportance(mChildrenContainer, isEnabled);
        }
        if (mLayouts != null) {
            for (View view : mLayouts) {
                updateChildAccessibilityImportance(view, isEnabled);
            }
        }

        if (isEnabled) {
            this.requestAccessibilityFocus();
        }
    }

    /**
     * Updates whether this view is important for accessibility based on {@code isEnabled}.
     */
    private void updateAccessibilityImportance(boolean isEnabled) {
        setImportantForAccessibility(isEnabled
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Updates whether the given childView is important for accessibility based on
     * {@code isEnabled}.
     */
    private void updateChildAccessibilityImportance(View childView, boolean isEnabled) {
        childView.setImportantForAccessibility(isEnabled
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /**
     * Reset the translation with an animation.
     */
    public void animateResetTranslation() {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }
        mTranslateAnim = getTranslateViewAnimator(0, null /* updateListener */);
        if (mTranslateAnim != null) {
            mTranslateAnim.start();
        }
    }

    /**
     * Whether to allow dismissal with the whole-row translation animation.
     *
     * If true, either animation is permissible.
     * If false, usingRTX behavior is forbidden, only clipping animation should be used.
     *
     * Usually either is OK, except for promoted notifications, where we always need to
     * dismiss with content clipping/partial translation animation instead, so that we
     * can show the demotion options.
     * @return
     */
    private boolean allowDismissUsingRowTranslationX() {
        if (Flags.permissionHelperInlineUiRichOngoing()) {
            return !isPromotedOngoing();
        } else {
            // Don't change behavior unless the flag is on.
            return true;
        }
    }

    /**
     * Set the dismiss behavior of the view.
     *
     * @param usingRowTranslationX {@code true} if the view should translate using regular
     *                             translationX, otherwise the contents will be
     *                             translated.
     * @param forceUpdateChildren {@code true} to force initialization, {@code false} if lazy
     *                             behavior is OK.
     */
    @Override
    public void setDismissUsingRowTranslationX(boolean usingRowTranslationX,
            boolean forceUpdateChildren) {
        // Before updating dismiss behavior, make sure this is an allowable configuration for this
        // notification.
        usingRowTranslationX = usingRowTranslationX && allowDismissUsingRowTranslationX();

        if (forceUpdateChildren || (usingRowTranslationX != mDismissUsingRowTranslationX)) {
            // In case we were already transitioning, let's switch over!
            float previousTranslation = getTranslation();
            if (previousTranslation != 0) {
                setTranslation(0);
            }
            super.setDismissUsingRowTranslationX(usingRowTranslationX, forceUpdateChildren);
            if (previousTranslation != 0) {
                setTranslation(previousTranslation);
            }

            if (mChildrenContainer != null) {
                List<ExpandableNotificationRow> notificationChildren =
                        mChildrenContainer.getAttachedChildren();
                for (int i = 0; i < notificationChildren.size(); i++) {
                    ExpandableNotificationRow child = notificationChildren.get(i);
                    child.setDismissUsingRowTranslationX(usingRowTranslationX, forceUpdateChildren);
                }
            }
        }
    }

    @Override
    public void setTranslation(float translationX) {
        invalidate();
        if (mDismissUsingRowTranslationX) {
            setTranslationX(translationX);
        } else if (mTranslateableViews != null) {
            // Translate the group of views
            for (int i = 0; i < mTranslateableViews.size(); i++) {
                if (mTranslateableViews.get(i) != null) {
                    mTranslateableViews.get(i).setTranslationX(translationX);
                }
            }
            invalidateOutline();

            // In order to keep the shelf in sync with this swiping, we're simply translating
            // it's icon by the same amount. The translation is already being used for the normal
            // positioning, so we can use the scrollX instead.
            StatusBarIconView shelfIcon = getShelfIcon();
            if (shelfIcon != null) {
                shelfIcon.setScrollX((int) -translationX);
            }
        }

        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onParentTranslationUpdate(translationX);
        }
        // Clipping occurs for children of a NotificationChildrenContainer every time the container
        // needs to draw a child (see drawChild of the container class). Thus, we invalidate the
        // container so that the clip path used to clip the children can access the latest
        // translation of this child.
        if (getParent() instanceof NotificationChildrenContainer childrenContainer) {
            childrenContainer.invalidate();
        }
    }

    @Override
    public float getTranslation() {
        if (mDismissUsingRowTranslationX) {
            return getTranslationX();
        }

        if (mTranslateableViews != null && !mTranslateableViews.isEmpty()) {
            // All of the views in the list should have same translation, just use first one.
            return mTranslateableViews.get(0).getTranslationX();
        }

        return 0;
    }

    public Animator getTranslateViewAnimator(final float leftTarget,
            AnimatorUpdateListener listener) {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }

        final ObjectAnimator translateAnim = ObjectAnimator.ofFloat(this, TRANSLATE_CONTENT,
                leftTarget);
        if (listener != null) {
            translateAnim.addUpdateListener(listener);
        }
        translateAnim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator anim) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator anim) {
                if (!cancelled && leftTarget == 0) {
                    if (mMenuRow != null) {
                        mMenuRow.resetMenu();
                    }
                }
                mTranslateAnim = null;
            }
        });
        mTranslateAnim = translateAnim;
        return translateAnim;
    }

    /** Cancels the ongoing translate animation if there is any. */
    public void cancelTranslateAnimation() {
        if (mTranslateAnim != null) {
            mTranslateAnim.cancel();
        }
    }

    void ensureGutsInflated() {
        if (mGuts == null) {
            mGutsStub.inflate();
        }
    }

    public boolean isBundle() {
        return mIsBundle;
    }

    private void updateChildrenVisibility() {
        boolean hideContentWhileLaunching = mExpandAnimationRunning && mGuts != null
                && mGuts.isExposed();
        mPrivateLayout.setVisibility(!mShowingPublic && !mIsSummaryWithChildren
                && !hideContentWhileLaunching ? VISIBLE : INVISIBLE);
        if (mChildrenContainer != null) {
            if (mIsBundle) {
                mChildrenContainer.setVisibility(!hideContentWhileLaunching ? VISIBLE : INVISIBLE);
            } else {
                mChildrenContainer.setVisibility(!mShowingPublic && mIsSummaryWithChildren
                        && !hideContentWhileLaunching ? VISIBLE
                        : INVISIBLE);
            }
        }
        // The limits might have changed if the view suddenly became a group or vice versa
        updateLimits();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // Add a record for the entire layout since its content is somehow small.
            // The event comes from a leaf view that is interacted with.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    public void applyLaunchAnimationParams(LaunchAnimationParameters params) {
        if (params == null) {
            // `null` params indicates the animation is over, which means we can't access
            // params.getParentStartClipTopAmount() which has the value we want to restore.
            // Fortunately, only NotificationShelf actually uses these values for anything other
            // than this launch animation, so we can restore the value to 0 and it's right for now.
            if (mNotificationParent != null) {
                mNotificationParent.setClipTopAmount(0);
            }
            setTranslationX(0);
            return;
        }

        if (!params.getVisible()) {
            if (getVisibility() == View.VISIBLE) {
                setVisibility(View.INVISIBLE);
            }
            return;
        }

        float zProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                params.getProgress(0, 50));
        float translationZ = MathUtils.lerp(params.getStartTranslationZ(),
                mNotificationLaunchHeight,
                zProgress);
        setTranslationZ(translationZ);
        float extraWidthForClipping = params.getWidth() - getWidth();
        setExtraWidthForClipping(extraWidthForClipping);

        int top;
        if (params.getStartRoundedTopClipping() > 0) {
            // If we were clipping initially, let's interpolate from the start position to the
            // top. Otherwise, we just take the top directly.
            float expandProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                    params.getProgress(0,
                            NotificationTransitionAnimatorController
                                    .ANIMATION_DURATION_TOP_ROUNDING));
            int startTop = params.getStartNotificationTop();
            top = (int) Math.min(MathUtils.lerp(startTop, params.getTop(), expandProgress),
                    startTop);
        } else {
            top = params.getTop();
        }
        int actualHeight = params.getBottom() - top;
        setFinalActualHeight(actualHeight);

        int notificationStackTop = params.getNotificationParentTop();
        top -= notificationStackTop;
        int startClipTopAmount = params.getStartClipTopAmount();
        int clipTopAmount = (int) MathUtils.lerp(startClipTopAmount, 0, params.getProgress());
        if (mNotificationParent != null) {
            float parentTranslationY = mNotificationParent.getTranslationY();
            top -= (int) parentTranslationY;
            mNotificationParent.setTranslationZ(translationZ);

            // When the expanding notification is below its parent, the parent must be clipped
            // exactly how it was clipped before the animation. When the expanding notification is
            // on or above its parent (top <= 0), then the parent must be clipped exactly 'top'
            // pixels to show the expanding notification, while still taking the decreasing
            // notification clipTopAmount into consideration, so 'top + clipTopAmount'.
            int parentStartClipTopAmount = params.getParentStartClipTopAmount();
            int parentClipTopAmount = Math.min(parentStartClipTopAmount, top + clipTopAmount);
            mNotificationParent.setClipTopAmount(parentClipTopAmount);

            mNotificationParent.setExtraWidthForClipping(extraWidthForClipping);
            float clipBottom = Math.max(params.getBottom() - notificationStackTop,
                    parentTranslationY + mNotificationParent.getActualHeight()
                            - mNotificationParent.getClipBottomAmount());
            float clipTop = Math.min(params.getTop() - notificationStackTop, parentTranslationY);
            int minimumHeightForClipping = (int) (clipBottom - clipTop);
            mNotificationParent.setMinimumHeightForClipping(minimumHeightForClipping);
        } else if (startClipTopAmount != 0) {
            setClipTopAmount(clipTopAmount);
        }
        setTranslationY(top);

        float absoluteCenterX = getLocationOnScreen()[0] + getWidth() / 2f - getTranslationX();
        setTranslationX(params.getCenterX() - absoluteCenterX);

        invalidateOutline();

        mBackgroundNormal.setExpandAnimationSize(params.getWidth(), actualHeight);
        mBackgroundNormal.setRadius(params.getTopCornerRadius(), params.getBottomCornerRadius());
    }

    public void setExpandAnimationRunning(boolean expandAnimationRunning) {
        if (expandAnimationRunning) {
            setAboveShelf(true);
            mExpandAnimationRunning = true;
            getViewState().cancelAnimations(this);
            mNotificationLaunchHeight = AmbientState.getNotificationLaunchHeight(getContext());
        } else {
            mExpandAnimationRunning = false;
            setAboveShelf(isAboveShelf());
            setVisibility(View.VISIBLE);
            if (mGuts != null) {
                mGuts.setAlpha(1.0f);
            }
            resetAllContentAlphas();
            setExtraWidthForClipping(0.0f);
            if (mNotificationParent != null) {
                mNotificationParent.setExtraWidthForClipping(0.0f);
                mNotificationParent.setMinimumHeightForClipping(0);
            }
        }
        if (mNotificationParent != null) {
            mNotificationParent.setChildIsExpanding(mExpandAnimationRunning);
        }
        updateChildrenVisibility();
        updateClipping();
        mBackgroundNormal.setExpandAnimationRunning(expandAnimationRunning);
    }

    private void setChildIsExpanding(boolean isExpanding) {
        mChildIsExpanding = isExpanding;
        updateClipping();
        invalidate();
    }

    @Override
    public boolean hasExpandingChild() {
        return mChildIsExpanding;
    }

    @Override
    public @Nullable StatusBarIconView getShelfIcon() {
        // TODO(b/416147040): Add requireNotNull check and change annotation to @NonNull
        if (NotificationBundleUi.isEnabled()) {
            return getEntryAdapter().getIcons().getShelfIcon();
        } else {
            return getEntryLegacy().getIcons().getShelfIcon();
        }
    }

    @Override
    protected boolean shouldClipToActualHeight() {
        return super.shouldClipToActualHeight() && !mExpandAnimationRunning;
    }

    @Override
    public boolean isExpandAnimationRunning() {
        return mExpandAnimationRunning;
    }

    /**
     * Tap sounds should not be played when we're unlocking.
     * Doing so would cause audio collision and the system would feel unpolished.
     */
    @Override
    public boolean isSoundEffectsEnabled() {
        final boolean mute = mStatusBarStateController != null
                && mStatusBarStateController.isDozing()
                && mSecureStateProvider != null &&
                !mSecureStateProvider.getAsBoolean();
        return !mute && super.isSoundEffectsEnabled();
    }

    public boolean isExpandable() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return !mChildrenExpanded;
        }
        if (isPromotedOngoing()) {
            return false;
        }
        return mEnableNonGroupedNotificationExpand && mExpandable;
    }

    public void setExpandable(boolean expandable) {
        mExpandable = expandable;
        mPrivateLayout.updateExpandButtons(isExpandable());
    }


    /**
     * Sets whether the status bar is showing a chip corresponding to this notification.
     *
     * Only set when this notification's heads-up status changes since that's the only time it's
     * relevant.
     */
    public void setHasStatusBarChipDuringHeadsUpAnimation(boolean hasStatusBarChip) {
        if (PromotedNotificationUi.isUnexpectedlyInLegacyMode()) {
            return;
        }
        mHasStatusBarChipDuringHeadsUpAnimation = hasStatusBarChip;
    }

    /**
     * Returns true if the status bar is showing a chip corresponding to this notification during a
     * heads-up appear or disappear animation.
     *
     * Note that this value is only set when this notification's heads-up status changes since
     * that's the only time it's relevant.
     */
    public boolean hasStatusBarChipDuringHeadsUpAnimation() {
        return PromotedNotificationUi.isEnabled() && mHasStatusBarChipDuringHeadsUpAnimation;
    }

    @Override
    public void setClipToActualHeight(boolean clipToActualHeight) {
        super.setClipToActualHeight(clipToActualHeight || isUserLocked());
        getShowingLayout().setClipToActualHeight(clipToActualHeight || isUserLocked());
    }

    /**
     * @return whether the user has changed the expansion state
     */
    public boolean hasUserChangedExpansion() {
        return mHasUserChangedExpansion;
    }

    public boolean isUserExpanded() {
        return mUserExpanded;
    }

    /**
     * Set this notification to be expanded by the user
     *
     * @param userExpanded whether the user wants this notification to be expanded
     */
    public void setUserExpanded(boolean userExpanded) {
        setUserExpanded(userExpanded, false /* allowChildExpansion */);
    }

    /**
     * Set this notification to be expanded by the user
     *
     * @param userExpanded        whether the user wants this notification to be expanded
     * @param allowChildExpansion whether a call to this method allows expanding children
     */
    public void setUserExpanded(boolean userExpanded, boolean allowChildExpansion) {
        if (isBundle()
                || (allowChildExpansion && !shouldShowPublic() && (!mIsMinimized || isExpanded()) && isGroupRoot())) {
            final boolean wasExpanded = isGroupExpanded();
            if (NotificationBundleUi.isEnabled()) {
                if (mEntryAdapter.isGroupRoot()) {
                    mGroupExpansionManager.setGroupExpanded(mEntryAdapter, userExpanded);
                }
            } else {
                mGroupExpansionManager.setGroupExpanded(getEntryLegacy(), userExpanded);
            }
            mHasUserChangedExpansion = true;
            mUserExpanded = userExpanded;
            onExpansionChanged(true /* userAction */, wasExpanded);
            return;
        }
        if (userExpanded && !mExpandable) return;
        final boolean wasExpanded = isExpanded();
        mHasUserChangedExpansion = true;
        mUserExpanded = userExpanded;
        onExpansionChanged(true /* userAction */, wasExpanded);
        if (!wasExpanded && isExpanded()
                && getActualHeight() != getIntrinsicHeight()) {
            notifyHeightChanged(/* needsAnimation= */ true);
        }
    }

    public void resetUserExpansion() {
        boolean wasExpanded = isExpanded();
        mHasUserChangedExpansion = false;
        mUserExpanded = false;
        if (wasExpanded != isExpanded()) {
            if (mIsSummaryWithChildren) {
                mChildrenContainer.onExpansionChanged();
            }
            notifyHeightChanged(/* needsAnimation= */ false);
        }
        updateShelfIconColor();
    }

    public boolean isUserLocked() {
        return mUserLocked;
    }

    public void setUserLocked(boolean userLocked) {
        if (isPromotedOngoing()) return;

        mUserLocked = userLocked;
        mPrivateLayout.setUserExpanding(userLocked);
        mPublicLayout.setUserExpanding(userLocked);
        // This is intentionally not guarded with mIsSummaryWithChildren since we might have had
        // children but not anymore.
        if (mChildrenContainer != null) {
            mChildrenContainer.setUserLocked(userLocked);
            if (mIsSummaryWithChildren && (userLocked || !isGroupExpanded())) {
                updateBackgroundForGroupState();
            }
        }
    }

    /**
     * @return has the system set this notification to be expanded
     */
    public boolean isSystemExpanded() {
        return mIsSystemExpanded;
    }

    /**
     * Set this notification to be expanded by the system.
     *
     * @param expand whether the system wants this notification to be expanded.
     */
    public void setSystemExpanded(boolean expand) {
        if (expand != mIsSystemExpanded) {
            boolean wasExpanded = isExpanded();
            mIsSystemExpanded = expand;
            if (isBundle()) {
                wasExpanded = mGroupExpansionManager.isGroupExpanded(mEntryAdapter);
                mGroupExpansionManager.setGroupExpanded(mEntryAdapter, expand);
            }
            notifyHeightChanged(/* needsAnimation= */ false);
            onExpansionChanged(false /* userAction */, wasExpanded);
            if (mIsSummaryWithChildren) {
                mChildrenContainer.updateGroupOverflow();
                resetChildSystemExpandedStates();
            }
        }
    }

    @Override
    public void setOnKeyguard(boolean onKeyguard) {
        if (onKeyguard == mOnKeyguard) {
            return;
        }

        boolean wasAboveShelf = isAboveShelf();
        final boolean wasExpanded = isExpanded();

        super.setOnKeyguard(onKeyguard);

        onExpansionChanged(false /* userAction */, wasExpanded);
        if (wasExpanded != isExpanded()) {
            if (mIsSummaryWithChildren) {
                mChildrenContainer.updateGroupOverflow();
            }
            notifyHeightChanged(/* needsAnimation= */ false);
        }
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
        if (SceneContainerFlag.isEnabled()) {
            if (mIsSummaryWithChildren) {
                mChildrenContainer.setOnKeyguard(onKeyguard);
            }
        }
        updateIfNeeded();
    }

    @Override
    public int getHeightWithoutLockscreenConstraints() {
        mIgnoreLockscreenConstraints = true;
        final int height = getIntrinsicHeight();
        mIgnoreLockscreenConstraints = false;
        return height;
    }

    @Override
    public int getIntrinsicHeight() {
        if (isUserLocked()) {
            return getActualHeight();
        }
        if (mGuts != null && mGuts.isExposed()) {
            return mGuts.getIntrinsicHeight();
        }
        if (NotificationBundleUi.isEnabled()) {
            if ((mSensitive && mHideSensitiveForIntrinsicHeight) || isNotificationAppLocked()) {
                return getMinHeight();
            }
            if (mIsSummaryWithChildren) {
                return mChildrenContainer.getIntrinsicHeight();
            }
            if (isChildInGroup() && !isGroupExpanded()) {
                return mPrivateLayout.getMinHeight();
            }
        } else {
            if (isChildInGroup() && !isGroupExpanded()) {
                return mPrivateLayout.getMinHeight();
            } else if ((isChildInGroup() && !isGroupExpanded())) {
                return mPrivateLayout.getMinHeight();
            } else if (isNotificationAppLocked()) {
                return getMinHeight();
            }
            if (mSensitive && mHideSensitiveForIntrinsicHeight) {
                return getMinHeight();
            }
            if (mIsSummaryWithChildren) {
                return mChildrenContainer.getIntrinsicHeight();
            }
        }
        if (canShowHeadsUp() && isHeadsUpState()) {
            if (isPinned() || mHeadsupDisappearRunning) {
                return getPinnedHeadsUpHeight(true /* atLeastMinHeight */);
            } else if (isExpanded()) {
                return Math.max(getMaxExpandHeight(), getHeadsUpHeight());
            } else {
                return Math.max(getCollapsedHeight(), getHeadsUpHeight());
            }
        }
        if (isExpanded()) {
            return getMaxExpandHeight();
        }
        return getCollapsedHeight();
    }

    /**
     * @return {@code true} if the notification can show it's heads up layout. This is mostly true
     * except for legacy use cases.
     */
    public boolean canShowHeadsUp() {
        boolean canEntryHun = NotificationBundleUi.isEnabled()
                ? mEntryAdapter.canPeek()
                : getEntryLegacy().isStickyAndNotDemoted();
        if (mOnKeyguard && !isDozing() && !isBypassEnabled() &&
                (!canEntryHun
                        || (!mIgnoreLockscreenConstraints && mSaveSpaceOnLockscreen))) {
            return false;
        }
        return true;
    }

    private boolean isBypassEnabled() {
        return mBypassController == null || mBypassController.getBypassEnabled();
    }

    private boolean isDozing() {
        return mStatusBarStateController != null && mStatusBarStateController.isDozing();
    }

    @Override
    public boolean isGroupExpanded() {
        if (NotificationBundleUi.isEnabled()) {
            return mGroupExpansionManager.isGroupExpanded(mEntryAdapter);
        }
        return mGroupExpansionManager.isGroupExpanded(getEntryLegacy());
    }

    /**
     * If this notification is a child of a group, returns whether the parent group is expanded.
     * This is distinct from isGroupExpanded() specifically in the case of nested groups (for
     * example, if a group is contained inside a bundle).
     *
     * Returns false if the notification is not a child of a group.
     */
    public boolean isParentGroupExpanded() {
        if (isChildInGroup()) {
            return getNotificationParent().isGroupExpanded();
        }
        return false;
    }

    @VisibleForTesting
    boolean isGroupRoot() {
        return NotificationBundleUi.isEnabled()
                ? mGroupMembershipManager.isGroupRoot(mEntryAdapter)
                : mGroupMembershipManager.isGroupSummary(getEntryLegacy());
    }

    private void onAttachedChildrenCountChanged() {
        final boolean wasSummary = mIsSummaryWithChildren;
        mIsSummaryWithChildren = mChildrenContainer != null
                && mChildrenContainer.getNotificationChildCount() > 0;
        if (mIsSummaryWithChildren) {
            Trace.beginSection("ExpNotRow#onChildCountChanged (summary)");
            if (!AsyncGroupHeaderViewInflation.isEnabled()) {
                NotificationViewWrapper wrapper = mChildrenContainer.getNotificationViewWrapper();
                if (wrapper == null || wrapper.getNotificationHeader() == null) {
                    mChildrenContainer.recreateNotificationHeader(mExpandClickListener);
                }
            }
        }
        if (!mIsSummaryWithChildren && wasSummary) {
            // Reset the 'when' once the row stops being a summary
            if (NotificationBundleUi.isEnabled()) {
                mPublicLayout.setNotificationWhen(mEntryAdapter.getWhen());
            } else {
                mPublicLayout.setNotificationWhen(
                        getEntryLegacy().getSbn().getNotification().getWhen());
            }
        }
        getShowingLayout().updateBackgroundColor(false /* animate */);
        mPrivateLayout.updateExpandButtons(isExpandable());
        updateChildrenAppearance();
        updateChildrenVisibility();
        applyChildrenRoundness();
        if (mIsSummaryWithChildren) {
            Trace.endSection();
        }
    }

    /**
     * Triggers expand click listener to expand the notification.
     */
    public void expandNotification() {
        mExpandClickListener.onClick(this);
    }

    /**
     * If this is a group, update the appearance of the children.
     */
    public void updateChildrenAppearance() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.updateChildrenAppearance();
        }
    }

    public boolean isPromotedOngoing() {
        if (!PromotedNotificationUi.isEnabled()) {
            return false;
        }

        if (NotificationBundleUi.isEnabled()) {
            if (getEntryAdapter() == null) {
                return false;
            }
            return getEntryAdapter().isPromotedOngoing();
        } else {
            if (getEntryLegacy() == null) {
                return false;
            }
            return getEntryLegacy().isPromotedOngoing();
        }
    }

    private boolean isPromotedNotificationExpanded(boolean allowOnKeyguard) {
        // public view in non group notifications is always collapsed.
        if (shouldShowPublic()) {
            return false;
        }
        // RON will always be expanded when it is not on keyguard.
        if (!mOnKeyguard) {
            return true;
        }
        // RON will always be expanded when it is allowed on keyguard.
        // allowOnKeyguard is used for getting the maximum height by NotificationContentView and
        // NotificationChildrenContainer.
        if (allowOnKeyguard) {
            return true;
        }

        // RON will be expanded when it needs to ignore lockscreen constraints.
        if (mIgnoreLockscreenConstraints) {
            return true;
        }

        // RON will need be collapsed when it needs to save space on the lock screen.
        return !mSaveSpaceOnLockscreen;
    }

    /**
     * Is this row currently showing an expanded state? This method is different from
     * {@link #isExpanded()}, because it also handles groups, and pinned notifications.
     */
    private boolean isShowingExpanded() {
        if (!shouldShowPublic() && (!mIsMinimized || isExpanded()) && isGroupRoot()) {
            // is group and expanded?
            return isGroupExpanded();
        } else if (mEnableNonGroupedNotificationExpand) {
            if (isPinned()) {
                // is pinned and expanded?
                return mExpandedWhenPinned;
            } else {
                // is regular notification and expanded?
                return isExpanded();
            }
        } else {
            return false;
        }
    }

    /**
     * Check whether the view state is currently expanded. This is given by the system in {@link
     * #setSystemExpanded(boolean)} and can be overridden by user expansion or
     * collapsing in {@link #setUserExpanded(boolean)}. Note that the visual appearance of this
     * view can differ from this state, if layout params are modified from outside.
     *
     * @return whether the view state is currently expanded.
     */
    public boolean isExpanded() {
        return isExpanded(false /* allowOnKeyguard */);
    }

    public boolean isExpanded(boolean allowOnKeyguard) {
        if (isPromotedOngoing()) {
            return isPromotedNotificationExpanded(allowOnKeyguard);
        }

        return (!shouldShowPublic()) && (!mOnKeyguard || allowOnKeyguard)
                && (!hasUserChangedExpansion()
                && (isSystemExpanded() || isSystemChildExpanded())
                || isUserExpanded());
    }

    private boolean isSystemChildExpanded() {
        return mIsSystemChildExpanded;
    }

    public void setSystemChildExpanded(boolean expanded) {
        mIsSystemChildExpanded = expanded;
    }

    public void setLayoutListener(@Nullable LayoutListener listener) {
        mLayoutListener = listener;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        Trace.beginSection(appendTraceStyleTag("ExpNotRow#onLayout"));
        int intrinsicBefore = getIntrinsicHeight();
        super.onLayout(changed, left, top, right, bottom);
        if (intrinsicBefore != getIntrinsicHeight()
                && (intrinsicBefore != 0 || getActualHeight() > 0)) {
            notifyHeightChanged(/* needsAnimation= */ true);
        }
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onParentHeightUpdate();
        }
        updateContentShiftHeight();
        if (mLayoutListener != null) {
            mLayoutListener.onLayout();
        }
        Trace.endSection();
    }

    /**
     * Updates the content shift height such that the header is completely hidden when coming from
     * the top.
     */
    private void updateContentShiftHeight() {
        NotificationViewWrapper wrapper = getVisibleNotificationViewWrapper();
        CachingIconView icon = wrapper == null ? null : wrapper.getIcon();
        if (icon != null) {
            mIconTransformContentShift = getRelativeTopPadding(icon) + icon.getHeight();
        } else {
            mIconTransformContentShift = mContentShift;
        }
    }

    @Override
    protected float getContentTransformationShift() {
        return mIconTransformContentShift;
    }

    @Override
    public void notifyHeightChanged(boolean needsAnimation) {
        super.notifyHeightChanged(needsAnimation);
        getShowingLayout().requestSelectLayout(needsAnimation || isUserLocked());
    }

    public void setSensitive(boolean sensitive, boolean hideSensitive) {
        if (notificationsRedesignTemplates()
                && sensitive == mSensitive && hideSensitive == mSensitiveHiddenInGeneral) {
            return; // nothing has changed
        }

        int intrinsicBefore = getIntrinsicHeight();
        mSensitive = sensitive;
        mSensitiveHiddenInGeneral = hideSensitive;
        int intrinsicAfter = getIntrinsicHeight();
        if (intrinsicBefore != intrinsicAfter) {
            notifyHeightChanged(/* needsAnimation= */ true);
        } else if (notificationsRedesignTemplates()) {
            // Just request the correct layout, even if the height hasn't changed
            getShowingLayout().requestSelectLayout(/* needsAnimation= */ true);
        }
        updateAppLockedShowingState();
    }

    private void updateAppLockedShowingState() {
        boolean wasPublic = mShowingPublic;
        mShowingPublic = (mSensitive && mHideSensitiveForIntrinsicHeight)
                || isNotificationAppLocked();
        if (wasPublic != mShowingPublic) {
            if (mShowingPublicInitialized) {
                mPublicLayout.setVisibility(mShowingPublic ? View.VISIBLE : View.INVISIBLE);
                updateChildrenVisibility();
            }
            notifyHeightChanged(true);
        }
    }

    /** Sets whether this notification row should show the notification expander or not */
    public void setPublicExpanderVisible(boolean showPublicExpander) {
        if (mShowPublicExpander != showPublicExpander) {
            mShowPublicExpander = showPublicExpander;
            mPublicLayout.updateExpandButtons(mShowPublicExpander);
        }
    }

    @Override
    public void setHideSensitiveForIntrinsicHeight(boolean hideSensitive) {
        mHideSensitiveForIntrinsicHeight = hideSensitive;
        if (mIsSummaryWithChildren) {
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getAttachedChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                child.setHideSensitiveForIntrinsicHeight(hideSensitive);
            }
        }
    }

    @Override
    public void setHideSensitive(boolean hideSensitive, boolean animated, long delay,
            long duration) {
        if (getVisibility() == GONE) {
            // If we are GONE, the hideSensitive parameter will not be calculated and always be
            // false, which is incorrect, let's wait until a real call comes in later.
            return;
        }
        boolean oldShowingPublic = mShowingPublic;
        mShowingPublic = (mSensitive && hideSensitive) || isNotificationAppLocked();
        boolean isShowingLayoutNotChanged = mShowingPublic == oldShowingPublic;
        if (mShowingPublicInitialized && isShowingLayoutNotChanged) {
            return;
        }

        final boolean shouldSkipHideSensitiveAnimation =
            mShowingPublic && isShowingLayoutNotChanged;
        if (!animated || shouldSkipHideSensitiveAnimation) {
            // In some edge cases, the showing layout's alpha might be 0. In these cases,
            // we need to reset content alpha!
            final NotificationContentView showingLayout = getShowingLayout();
            final boolean showingLayoutNeedsAlphaReset = showingLayout.getAlpha() == 0;
            if (mShowingPublic != oldShowingPublic || showingLayoutNeedsAlphaReset) {
                // Don't reset the alpha or cancel the animation if the showing layout doesn't
                // change
                mPublicLayout.animate().cancel();
                mPrivateLayout.animate().cancel();
                if (mChildrenContainer != null) {
                    mChildrenContainer.animate().cancel();
                }
                resetAllContentAlphas();
            } else {
                mLogger.logSkipResetAllContentAlphas(mLoggingKey);
            }
            mPublicLayout.setVisibility(mShowingPublic ? View.VISIBLE : View.INVISIBLE);
            updateChildrenVisibility();
        } else {
            animateShowingPublic(delay, duration, mShowingPublic);
        }
        NotificationContentView showingLayout = getShowingLayout();
        showingLayout.updateBackgroundColor(animated);
        mPrivateLayout.updateExpandButtons(isExpandable());
        updateShelfIconColor();
        mShowingPublicInitialized = true;
    }

    private void animateShowingPublic(long delay, long duration, boolean showingPublic) {
        View[] privateViews = mIsSummaryWithChildren
                ? new View[]{mChildrenContainer}
                : new View[]{mPrivateLayout};
        View[] publicViews = new View[]{mPublicLayout};
        View[] hiddenChildren = showingPublic ? privateViews : publicViews;
        View[] shownChildren = showingPublic ? publicViews : privateViews;
        // disappear/appear overlap: 10 percent of duration
        long overlap = duration / 10;
        // disappear duration: 1/3 of duration + half of overlap
        long disappearDuration = duration / 3 + overlap / 2;
        // appear duration: 2/3 of duration + half of overlap
        long appearDuration = (duration - disappearDuration) + overlap / 2;
        for (final View hiddenView : hiddenChildren) {
            hiddenView.setVisibility(View.VISIBLE);
            hiddenView.animate().cancel();
            hiddenView.animate()
                    .alpha(0f)
                    .setStartDelay(delay)
                    .setDuration(disappearDuration)
                    .withEndAction(() -> {
                        hiddenView.setVisibility(View.INVISIBLE);
                        resetAllContentAlphas();
                    });
        }
        for (View showView : shownChildren) {
            showView.setVisibility(View.VISIBLE);
            showView.setAlpha(0f);
            showView.animate().cancel();
            showView.animate()
                    .alpha(1f)
                    .setStartDelay(delay + duration - appearDuration)
                    .setDuration(appearDuration);
        }
    }

    @Override
    public boolean mustStayOnScreen() {
        // Must stay on screen in the open shade regardless how much the stack is scrolled if:
        // 1. Is HUN and not marked as seen yet (isHeadsUp && mustStayOnScreen)
        // 2. Is an FSI HUN (isPinned)
        return mIsHeadsUp && mMustStayOnScreen || isPinned();
    }

    /**
     * For the case of an {@link ExpandableNotificationRow}, the dismissibility of the row considers
     * the exposure of guts, the state of the  notification entry, and if the view itself is allowed
     * to be dismissed.
     */
    @Override
    public boolean canExpandableViewBeDismissed() {
        if (areGutsExposed() || !hasFinishedInitialization()) {
            return false;
        }
        return canViewBeDismissed();
    }

    /**
     * @return Whether this view is allowed to be dismissed. Only valid for visible notifications as
     * otherwise some state might not be updated.
     */
    public boolean canViewBeDismissed() {
        return canEntryBeDismissed() && (!shouldShowPublic() || !mSensitiveHiddenInGeneral);
    }

    private boolean canEntryBeDismissed() {
        return mDismissibilityProvider.isDismissable(getKey());
    }

    /**
     * @return Whether this view is allowed to be cleared with clear all. Only valid for visible
     * notifications as otherwise some state might not be updated. To request about the general
     * clearability see {@link NotificationEntry#isClearable()}.
     */
    public boolean canViewBeCleared() {
        if (NotificationBundleUi.isEnabled()) {
            return mEntryAdapter.isClearable()
                    && (!shouldShowPublic() || !mSensitiveHiddenInGeneral);
        } else {
            return getEntryLegacy().isClearable()
                    && (!shouldShowPublic() || !mSensitiveHiddenInGeneral);
        }
    }

    private boolean shouldShowPublic() {
        if (isNotificationAppLocked()) {
            return true;
        }
        return mSensitive && mHideSensitiveForIntrinsicHeight;
    }

    public void makeActionsVisibile() {
        setUserExpanded(true, true);
        if (isChildInGroup()) {
            if (!NotificationBundleUi.isEnabled()) {
                // this is only called if row.getParent() instanceof NotificationStackScrollLayout,
                // so there is never a group to expand
                mGroupExpansionManager.setGroupExpanded(getEntryLegacy(), true);
            }
        }
        notifyHeightChanged(/* needsAnimation= */ false);
    }

    public void setChildrenExpanded(boolean expanded) {
        mChildrenExpanded = expanded;
        if (mChildrenContainer != null) {
            mChildrenContainer.setChildrenExpanded(expanded);
        }
        updateBackgroundForGroupState();
        updateClickAndFocus();
    }

    public int getMaxExpandHeight() {
        return mPrivateLayout.getExpandHeight();
    }


    private int getHeadsUpHeight() {
        return getShowingLayout().getHeadsUpHeight(false /* forceNoHeader */);
    }

    public boolean areGutsExposed() {
        return (mGuts != null && mGuts.isExposed());
    }

    private boolean isGutsLeaveBehind() {
        return (mGuts != null && mGuts.isLeavebehind());
    }

    @Override
    public boolean isContentExpandable() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return true;
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.isContentExpandable();
    }

    @Override
    protected View getContentView() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer;
        }
        return getShowingLayout();
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear,
            boolean isHeadsUpCycling, Runnable onFinishRunnable) {
        mLogger.logStartAppearAnimation(mLoggingKey, /* isAppear = */ true);
        super.performAddAnimation(delay, duration, isHeadsUpAppear, isHeadsUpCycling,
                onFinishRunnable);
    }

    @Override
    public long performRemoveAnimation(long duration, long delay, float translationDirection,
            boolean isHeadsUpAnimation, boolean isHeadsUpCycling, Runnable onStartedRunnable,
            Runnable onFinishedRunnable, AnimatorListenerAdapter animationListener,
            ClipSide clipSide) {
        mLogger.logStartAppearAnimation(mLoggingKey, /* isAppear = */ false);
        if (mMenuRow != null && mMenuRow.isMenuVisible()) {
            Animator anim = getTranslateViewAnimator(0f, null /* listener */);
            if (anim != null) {
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (onStartedRunnable != null) {
                            onStartedRunnable.run();
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        ExpandableNotificationRow.super.performRemoveAnimation(duration, delay,
                                translationDirection, isHeadsUpAnimation, isHeadsUpCycling, null,
                                onFinishedRunnable, animationListener, ClipSide.BOTTOM);
                    }
                });
                anim.start();
                return anim.getDuration();
            }
        }
        return super.performRemoveAnimation(duration, delay, translationDirection,
                isHeadsUpAnimation, isHeadsUpCycling, onStartedRunnable, onFinishedRunnable,
                animationListener, clipSide);
    }

    @Override
    protected void onAppearAnimationStarted(boolean isAppear) {
        mLogger.logAppearAnimationStarted(mLoggingKey, /* isAppear = */ isAppear);
        super.onAppearAnimationStarted(isAppear);
    }

    @Override
    protected void onAppearAnimationSkipped(boolean isAppear) {
        mLogger.logAppearAnimationSkipped(mLoggingKey,  /* isAppear = */ isAppear);
        super.onAppearAnimationSkipped(isAppear);
    }

    @Override
    protected void onAppearAnimationFinished(boolean wasAppearing, boolean cancelled) {
        mLogger.logAppearAnimationFinished(
                /* entry = */ mLoggingKey,
                /* isAppear = */ wasAppearing,
                /* cancelled = */ cancelled
        );
        super.onAppearAnimationFinished(wasAppearing, cancelled);
        if (wasAppearing) {
            // During the animation the visible view might have changed, so let's make sure all
            // alphas are reset
            resetAllContentAlphas();
            if (FADE_LAYER_OPTIMIZATION_ENABLED) {
                setNotificationFaded(false);
            } else {
                setNotificationFadedOnChildren(false);
            }
        } else {
            setHeadsUpAnimatingAway(false);
        }
    }

    @Override
    public void cancelAppearDrawing() {
        mLogger.logCancelAppearDrawing(mLoggingKey, isDrawingAppearAnimation());
        super.cancelAppearDrawing();
    }

    @Override
    public void resetAllContentAlphas() {
        mLogger.logResetAllContentAlphas(mLoggingKey);
        mPrivateLayout.setAlpha(1f);
        mPrivateLayout.setLayerType(LAYER_TYPE_NONE, null);
        mPublicLayout.setAlpha(1f);
        mPublicLayout.setLayerType(LAYER_TYPE_NONE, null);
        if (mChildrenContainer != null) {
            mChildrenContainer.setAlpha(1f);
            mChildrenContainer.setLayerType(LAYER_TYPE_NONE, null);
        }
        updateIfNeeded();
    }

    /**
     * Gets the last value set with {@link #setNotificationFaded(boolean)}
     */
    @Override
    public boolean isNotificationFaded() {
        return mIsFaded;
    }

    /**
     * This class needs to delegate the faded state set on it by
     * {@link com.android.systemui.statusbar.notification.stack.ViewState} to its children.
     * Having each notification use layerType of HARDWARE anytime it fades in/out can result in
     * extremely large layers (in the case of groups, it can even exceed the device height).
     * Because these large renders can cause serious jank when rendering, we instead have
     * notifications return false from {@link #hasOverlappingRendering()} and delegate the
     * layerType to child views which really need it in order to render correctly, such as icon
     * views or the conversation face pile.
     * <p>
     * Another compounding factor for notifications is that we change clipping on each frame of the
     * animation, so the hardware layer isn't able to do any caching at the top level, but the
     * individual elements we render with hardware layers (e.g. icons) cache wonderfully because we
     * never invalidate them.
     */
    @Override
    public void setNotificationFaded(boolean faded) {
        mIsFaded = faded;
        if (childrenRequireOverlappingRendering()) {
            // == Simple Scenario ==
            // If a child (like remote input) needs this to have overlapping rendering, then set
            // the layerType of this view and reset the children to render as if the notification is
            // not fading.
            NotificationFadeAware.setLayerTypeForFaded(this, faded);
            setNotificationFadedOnChildren(false);
        } else {
            // == Delegating Scenario ==
            // This is the new normal for alpha: Explicitly reset this view's layer type to NONE,
            // and require that all children use their own hardware layer if they have bad
            // overlapping rendering.
            NotificationFadeAware.setLayerTypeForFaded(this, false);
            setNotificationFadedOnChildren(faded);
        }
    }

    /**
     * Private helper for iterating over the layouts and children containers to set faded state
     */
    private void setNotificationFadedOnChildren(boolean faded) {
        delegateNotificationFaded(mChildrenContainer, faded);
        for (NotificationContentView layout : mLayouts) {
            delegateNotificationFaded(layout, faded);
        }
    }

    private static void delegateNotificationFaded(@Nullable View view, boolean faded) {
        if (FADE_LAYER_OPTIMIZATION_ENABLED && view instanceof NotificationFadeAware) {
            ((NotificationFadeAware) view).setNotificationFaded(faded);
        } else {
            NotificationFadeAware.setLayerTypeForFaded(view, faded);
        }
    }

    /**
     * Only declare overlapping rendering if independent children of the view require it.
     */
    @Override
    public boolean hasOverlappingRendering() {
        return super.hasOverlappingRendering() && childrenRequireOverlappingRendering();
    }

    /**
     * Because RemoteInputView is designed to be an opaque view that overlaps the Actions row, the
     * row should require overlapping rendering to ensure that the overlapped view doesn't bleed
     * through when alpha fading.
     * <p>
     * Note that this currently works for top-level notifications which squish their height down
     * while collapsing the shade, but does not work for children inside groups, because the
     * accordion affect does not apply to those views, so super.hasOverlappingRendering() will
     * always return false to avoid the clipping caused when the view's measured height is less than
     * the 'actual height'.
     */
    private boolean childrenRequireOverlappingRendering() {
        if (!FADE_LAYER_OPTIMIZATION_ENABLED) {
            return true;
        }
        // The colorized background is another layer with which all other elements overlap
        if (NotificationBundleUi.isEnabled()) {
            if (mEntryAdapter.isColorized()) {
                return true;
            }
        } else {
            if (getEntryLegacy().getSbn().getNotification().isColorized()) {
                return true;
            }
        }
        // Check if the showing layout has a need for overlapping rendering.
        // NOTE: We could check both public and private layouts here, but becuause these states
        //  don't animate well, there are bigger visual artifacts if we start changing the shown
        //  layout during shade expansion.
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout != null && showingLayout.requireRowToHaveOverlappingRendering();
    }

    @Override
    public void setActualHeight(int height, boolean notifyListeners) {
        boolean changed = height != getActualHeight();
        super.setActualHeight(height, notifyListeners);
        if (changed && isRemoved()) {
            // TODO: remove this once we found the gfx bug for this.
            // This is a hack since a removed view sometimes would just stay blank. it occured
            // when sending yourself a message and then clicking on it.
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) {
                parent.invalidate();
            }
        }
        if (mGuts != null && mGuts.isExposed()) {
            mGuts.setActualHeight(height);
            return;
        }
        for (NotificationContentView l : mLayouts) {
            l.setContentHeight(height);
        }
        if (mIsSummaryWithChildren) {
            mChildrenContainer.setActualHeight(height);
        }
        if (mGuts != null) {
            mGuts.setActualHeight(height);
        }
        if (mMenuRow != null && mMenuRow.getMenuView() != null) {
            mMenuRow.onParentHeightUpdate();
        }
        handleIntrinsicHeightReached();
    }

    @Override
    public int getMaxContentHeight() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getMaxContentHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMaxHeight();
    }

    @Override
    public int getMinHeight(boolean ignoreTemporaryStates) {
        if (!ignoreTemporaryStates && mGuts != null && mGuts.isExposed()) {
            return mGuts.getIntrinsicHeight();
        } else if (!ignoreTemporaryStates && canShowHeadsUp() && mIsHeadsUp
                && mHeadsUpManager.isTrackingHeadsUp().getValue()) {
            return getPinnedHeadsUpHeight(false /* atLeastMinHeight */);
        } else if (mIsSummaryWithChildren && !isGroupExpanded() && !shouldShowPublic()) {
            return mChildrenContainer.getMinHeight();
        } else if (!ignoreTemporaryStates && canShowHeadsUp() && mIsHeadsUp) {
            return getHeadsUpHeight();
        }
        NotificationContentView showingLayout = getShowingLayout();
        return showingLayout.getMinHeight();
    }

    @Override
    public int getCollapsedHeight() {
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getCollapsedHeight();
        }
        return getMinHeight();
    }

    @Override
    public int getHeadsUpHeightWithoutHeader() {
        if (!canShowHeadsUp() || !mIsHeadsUp) {
            return getCollapsedHeight();
        }
        if (mIsSummaryWithChildren && !shouldShowPublic()) {
            return mChildrenContainer.getCollapsedHeightWithoutHeader();
        }
        return getShowingLayout().getHeadsUpHeight(true /* forceNoHeader */);
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        for (NotificationContentView l : mLayouts) {
            l.setClipTopAmount(clipTopAmount);
        }
        if (mGuts != null) {
            mGuts.setClipTopAmount(clipTopAmount);
        }
    }

    @Override
    public void setTopOverlap(int topOverlap) {
        if (mTopOverlap != topOverlap) {
            super.setTopOverlap(topOverlap);
            for (NotificationContentView l : mLayouts) {
                l.setTopOverlap(topOverlap);
            }
            if (mGuts != null) {
                mGuts.setTopOverlap(topOverlap);
            }
        }
    }

    @Override
    public void setBottomOverlap(int bottomOverlap) {
        if (mExpandAnimationRunning) {
            return;
        }
        if (bottomOverlap != mBottomOverlap) {
            super.setBottomOverlap(bottomOverlap);
            for (NotificationContentView l : mLayouts) {
                l.setBottomOverlap(bottomOverlap);
            }
            if (mGuts != null) {
                mGuts.setBottomOverlap(bottomOverlap);
            }
        }
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        if (mExpandAnimationRunning) {
            return;
        }
        if (clipBottomAmount != mClipBottomAmount) {
            super.setClipBottomAmount(clipBottomAmount);
            for (NotificationContentView l : mLayouts) {
                l.setClipBottomAmount(clipBottomAmount);
            }
            if (mGuts != null) {
                mGuts.setClipBottomAmount(clipBottomAmount);
            }
        }
        if (mChildrenContainer != null && !mChildIsExpanding) {
            // We have to update this even if it hasn't changed, since the children locations can
            // have changed
            mChildrenContainer.setClipBottomAmount(clipBottomAmount);
        }
    }

    public NotificationContentView getShowingLayout() {
        return shouldShowPublic() ? mPublicLayout : mPrivateLayout;
    }

    public void setLegacy(boolean legacy) {
        for (NotificationContentView l : mLayouts) {
            l.setLegacy(legacy);
        }
    }

    @Override
    protected void updateBackgroundTint() {
        super.updateBackgroundTint();
        updateBackgroundForGroupState();
        if (mIsSummaryWithChildren) {
            List<ExpandableNotificationRow> notificationChildren =
                    mChildrenContainer.getAttachedChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow child = notificationChildren.get(i);
                if (mIsBlurSupported) {
                    child.updateBackgroundTint();
                }
                child.updateBackgroundForGroupState();
            }
        }
    }

    /**
     * Called when a group has finished animating from collapsed or expanded state.
     */
    public void onFinishedExpansionChange() {
        mGroupExpansionChanging = false;
        updateBackgroundForGroupState();
    }

    /**
     * Updates the parent and children backgrounds in a group based on the expansion state.
     */
    public void updateBackgroundForGroupState() {
        if (mIsSummaryWithChildren && isBundle()) {
            // For Bundles we let the BundleHeader show its background permanently. This is
            // possible because collapsed Bundles don't preview their children unlike summaries.
            // It is important that backgrounds don't overlap during expansion since
            // Translucency toggle enables transparent backgrounds.
            mShowNoBackground = true;
            mChildrenContainer.updateHeaderForExpansion(mShowNoBackground);
        } else if (mIsSummaryWithChildren) {
            mShowNoBackground = !mShowGroupBackgroundWhenExpanded && isGroupExpanded()
                    && !isGroupExpansionChanging() && !isUserLocked();
            mChildrenContainer.updateHeaderForExpansion(mShowNoBackground);
            List<ExpandableNotificationRow> children = mChildrenContainer.getAttachedChildren();
            for (int i = 0; i < children.size(); i++) {
                children.get(i).updateBackgroundForGroupState();
            }
        } else if (isChildInGroup()) {
            final int childColor = getShowingLayout().getBackgroundColorForExpansionState();
            if ((usesTransparentBackground() || notificationsRedesignTemplates())
                    && childColor == Color.TRANSPARENT) {
                // If child is not customizing its background color, switch from the parent to
                // the child background when the expansion finishes.
                mShowNoBackground = !mNotificationParent.mShowNoBackground;
            } else {
                // Only show a background if the group is expanded OR if it is
                // expanding / collapsing and has a custom background color.
                final boolean showBackground = isGroupExpanded()
                        || ((mNotificationParent.isGroupExpansionChanging()
                        || mNotificationParent.isUserLocked()) && childColor != 0);
                mShowNoBackground = !showBackground;
            }
        } else {
            // Only children or parents ever need no background.
            mShowNoBackground = false;
        }
        updateOutline();
        updateBackground();
    }

    @Override
    protected boolean hideBackground() {
        return mShowNoBackground || super.hideBackground();
    }

    public int getPositionOfChild(ExpandableNotificationRow childRow) {
        if (mIsSummaryWithChildren) {
            return mChildrenContainer.getPositionInLinearLayout(childRow);
        }
        return 0;
    }

    public void onExpandedByGesture(boolean userExpanded) {
        int event = MetricsEvent.ACTION_NOTIFICATION_GESTURE_EXPANDER;
        if (isGroupRoot()) {
            event = MetricsEvent.ACTION_NOTIFICATION_GROUP_GESTURE_EXPANDER;
        }
        mMetricsLogger.action(event, userExpanded);
    }

    @Override
    protected boolean disallowSingleClick(MotionEvent event) {
        if (areGutsExposed()) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        NotificationViewWrapper wrapper = getVisibleNotificationViewWrapper();
        NotificationHeaderView header = wrapper == null ? null : wrapper.getNotificationHeader();
        // the extra translation only needs to be added, if we're translating the notification
        // contents, otherwise the motionEvent is already at the right place due to the
        // touch event system.
        float translation = !mDismissUsingRowTranslationX ? getTranslation() : 0;
        if (header != null && header.isInTouchRect(x - translation, y)) {
            return true;
        }
        if ((!mIsSummaryWithChildren || shouldShowPublic())
                && getShowingLayout().disallowSingleClick(x, y)) {
            return true;
        }
        return super.disallowSingleClick(event);
    }

    // TODO: b/388470175 - Although this does get triggered when a notification
    // is expanded by the system (e.g. the first notication in the shade), it
    // will not be when a notification is collapsed by the system (such as when
    // the shade is closed).
    private void onExpansionChanged(boolean userAction, boolean wasExpanded) {
        boolean nowExpanded;
        if (mIsSummaryWithChildren && (!mIsMinimized || wasExpanded)) {
            nowExpanded = isGroupExpanded();
        } else {
            nowExpanded = isExpanded();
        }
        // Note: nowExpanded is going to be true here on the first expansion of minimized groups,
        // even though the group itself is not expanded. Use mGroupExpansionManager to get the real
        // group expansion if needed.
        if (nowExpanded != wasExpanded) {
            updateShelfIconColor();
            if (mLogger != null) {
                mLogger.logNotificationExpansion(mLoggingKey, getViewState().location, userAction,
                        nowExpanded);
            }
            if (mIsSummaryWithChildren) {
                mChildrenContainer.onExpansionChanged();
            }
            if (mExpansionChangedListener != null) {
                mExpansionChangedListener.onExpansionChanged(nowExpanded);
            }
            notifyAccessibilityContentExpansionChanged();
        }
    }

    private void notifyAccessibilityContentExpansionChanged() {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain();
            event.setEventType(TYPE_WINDOW_CONTENT_CHANGED);
            event.setContentChangeTypes(CONTENT_CHANGE_TYPE_EXPANDED);
            sendAccessibilityEventUnchecked(event);
        }
    }

    public void setOnExpansionChangedListener(@Nullable OnExpansionChangedListener listener) {
        mExpansionChangedListener = listener;
    }

    /**
     * Perform an action when the notification height has reached its intrinsic height.
     *
     * @param runnable the runnable to run
     */
    public void performOnIntrinsicHeightReached(@Nullable Runnable runnable) {
        mOnIntrinsicHeightReachedRunnable = runnable;
        handleIntrinsicHeightReached();
    }

    private void handleIntrinsicHeightReached() {
        if (mOnIntrinsicHeightReachedRunnable != null
                && getActualHeight() == getIntrinsicHeight()) {
            mOnIntrinsicHeightReachedRunnable.run();
            mOnIntrinsicHeightReachedRunnable = null;
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        final boolean isLongClickable = isNotificationRowLongClickable();
        if (isLongClickable) {
            if (isPromotedOngoing()) {
                final AccessibilityAction longClick =
                        new AccessibilityAction(
                                AccessibilityAction.ACTION_LONG_CLICK.getId(),
                                getContext().getResources().getString(
                                        R.string.notification_promoted_ongoing_long_click));
                info.addAction(longClick);
            } else {
                info.addAction(AccessibilityAction.ACTION_LONG_CLICK);
            }
        }
        info.setLongClickable(isLongClickable);

        if (canViewBeDismissed() && !mIsSnoozed) {
            info.addAction(AccessibilityAction.ACTION_DISMISS);
        }

        if (isAccessibilityExpandable()) {
            if (isShowingExpanded()) {
                info.addAction(AccessibilityAction.ACTION_COLLAPSE);
                info.setExpandedState(AccessibilityNodeInfo.EXPANDED_STATE_FULL);
            } else {
                info.addAction(AccessibilityAction.ACTION_EXPAND);
                info.setExpandedState(AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED);
            }
        } else {
            info.setExpandedState(AccessibilityNodeInfo.EXPANDED_STATE_UNDEFINED);
        }

        NotificationMenuRowPlugin provider = getProvider();
        if (provider != null) {
            MenuItem snoozeMenu = provider.getSnoozeMenuItem(getContext());
            if (snoozeMenu != null) {
                AccessibilityAction action = new AccessibilityAction(R.id.action_snooze,
                        getContext().getResources()
                                .getString(R.string.notification_menu_snooze_action));
                info.addAction(action);
            }
        }

        if (isBundle()) {
            // Set a content description explicitly for the bundle header because we can't
            // otherwise merge accessibility information across the ViewGroup/ComposeView boundary.
            NotificationChildrenContainer childrenContainer = getChildrenContainerNonNull();
            info.setContentDescription(childrenContainer.getBundleHeaderDescription());
        }
    }

    /** @return whether this row's expansion state can be toggled by an accessibility action. */
    private boolean isAccessibilityExpandable() {
        // don't add expand accessibility actions to snoozed notifications
        return !mIsSnoozed && isContentExpandable();
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_DISMISS:
                performDismiss(true /* fromAccessibility */);
                return true;
            case AccessibilityNodeInfo.ACTION_COLLAPSE:
            case AccessibilityNodeInfo.ACTION_EXPAND:
                mExpandClickListener.onClick(this);
                return true;
            case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                doLongClickCallback();
                return true;
            default:
                if (action == R.id.action_snooze) {
                    NotificationMenuRowPlugin provider = getProvider();
                    if (provider == null) {
                        return false;
                    }
                    MenuItem snoozeMenu = provider.getSnoozeMenuItem(getContext());
                    if (snoozeMenu != null) {
                        doLongClickCallback(getWidth() / 2, getHeight() / 2, snoozeMenu);
                    }
                    return true;
                }
        }
        return false;
    }

    public interface OnExpandClickListener {
        void onExpandClicked(NotificationEntry clickedEntry, View clickedView, boolean nowExpanded);

        void onExpandClicked(ExpandableNotificationRow row, EntryAdapter clickedEntry,
                boolean nowExpanded);
    }

    @Override
    @NonNull
    public ExpandableViewState createExpandableViewState() {
        return new NotificationViewState();
    }

    @Override
    public boolean isAboveShelf() {
        return (canShowHeadsUp()
                && (mPinnedStatus.isPinned()
                || mHeadsupDisappearRunning || (mIsHeadsUp && mAboveShelf)
                || mExpandAnimationRunning || mChildIsExpanding));
    }

    @Override
    protected boolean childNeedsClipping(View child) {
        if (child instanceof NotificationContentView contentView) {
            if (isClippingNeeded()) {
                return true;
            } else if (hasRoundedCorner()
                    && contentView.shouldClipToRounding(getTopRoundness() != 0.0f,
                    getBottomRoundness() != 0.0f)) {
                return true;
            }
        } else if (child == mChildrenContainer) {
            if (isClippingNeeded() || hasRoundedCorner()) {
                return true;
            }
        } else if (child instanceof NotificationGuts) {
            return hasRoundedCorner();
        }
        return super.childNeedsClipping(child);
    }

    /**
     * Set a clip path to be set while expanding the notification. This is needed to nicely
     * clip ourselves during the launch if we were clipped rounded in the beginning
     */
    public void setExpandingClipPath(Path path) {
        mExpandingClipPath = path;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        if (mExpandingClipPath != null && (mExpandAnimationRunning || mChildIsExpanding)) {
            // If we're launching a notification, let's clip if a clip rounded to the clipPath
            canvas.clipPath(mExpandingClipPath);
        }
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    @Override
    public void applyRoundnessAndInvalidate() {
        applyChildrenRoundness();
        super.applyRoundnessAndInvalidate();
    }

    private void applyChildrenRoundness() {
        if (mIsSummaryWithChildren) {
            mChildrenContainer.requestRoundness(
                    /* top = */ getTopRoundness(),
                    /* bottom = */ getBottomRoundness(),
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false);
        }
    }

    @Override
    public Path getCustomClipPath(View child) {
        if (child instanceof NotificationGuts) {
            return getClipPath(true /* ignoreTranslation */);
        }
        return super.getCustomClipPath(child);
    }

    @Override
    float calculateMaxRadius() {
        float radius = super.calculateMaxRadius();
        if (isBundle()) {
            // Bundle headers may be small enough that the max radius used for the ENR would
            // clip more off the corner of the bundle header than the header height would allow.
            // In this case, limit the radius for rounding to half the bundle header height.
            return Math.min(radius, getContext().getResources().getDimension(
                    R.dimen.notification_bundle_header_height) / 2.0f);
        }
        return radius;
    }

    public boolean isMediaRow() {
        NotificationBundleUi.assertInLegacyMode();
        return getEntryLegacy().getSbn().getNotification().isMediaNotification();
    }

    public void setAboveShelf(boolean aboveShelf) {
        boolean wasAboveShelf = isAboveShelf();
        mAboveShelf = aboveShelf;
        if (isAboveShelf() != wasAboveShelf) {
            mAboveShelfChangedListener.onAboveShelfStateChanged(!wasAboveShelf);
        }
    }

    private static class NotificationViewState extends ExpandableViewState {

        @Override
        public void applyToView(View view) {
            if (view instanceof ExpandableNotificationRow row) {
                if (row.isExpandAnimationRunning()) {
                    return;
                }
                handleFixedTranslationZ(row);
                super.applyToView(view);
                row.applyChildrenState();
            }
        }

        private void handleFixedTranslationZ(ExpandableNotificationRow row) {
            if (row.hasExpandingChild()) {
                setZTranslation(row.getTranslationZ());
                clipTopAmount = row.getClipTopAmount();
            }
        }

        @Override
        protected void onYTranslationAnimationFinished(View view) {
            super.onYTranslationAnimationFinished(view);
            if (view instanceof ExpandableNotificationRow row) {
                if (row.isHeadsUpAnimatingAway()) {
                    row.setHeadsUpAnimatingAway(false);
                }
            }
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            if (child instanceof ExpandableNotificationRow row) {
                if (row.isExpandAnimationRunning()) {
                    return;
                }
                handleFixedTranslationZ(row);
                super.animateTo(child, properties);
                row.startChildAnimation(properties);
            }
        }
    }

    /**
     * Returns the Smart Suggestions backing the smart suggestion buttons in the notification.
     */
    public InflatedSmartReplyState getExistingSmartReplyState() {
        return mPrivateLayout.getCurrentSmartReplyState();
    }

    @VisibleForTesting
    protected void setChildrenContainer(NotificationChildrenContainer childrenContainer) {
        mChildrenContainer = childrenContainer;
    }

    @VisibleForTesting
    protected void setPrivateLayout(NotificationContentView privateLayout) {
        mPrivateLayout = privateLayout;
        mLayouts = new NotificationContentView[]{mPrivateLayout, mPublicLayout};
    }

    @VisibleForTesting
    protected void setPublicLayout(NotificationContentView publicLayout) {
        mPublicLayout = publicLayout;
        mLayouts = new NotificationContentView[]{mPrivateLayout, mPublicLayout};
    }

    @VisibleForTesting
    public void setMagneticRowListener(MagneticRowListener listener) {
        mMagneticRowListener = listener;
    }

    /**
     * Equivalent to View.OnLongClickListener with coordinates
     */
    public interface LongPressListener {
        /**
         * Equivalent to {@link View.OnLongClickListener#onLongClick(View)} with coordinates
         *
         * @return whether the longpress was handled
         */
        boolean onLongPress(View v, int x, int y, MenuItem item);
    }

    /**
     * Called when notification drag and drop is finished successfully.
     */
    public interface OnDragSuccessListener {
        /**
         * @param entry NotificationEntry that succeed to drop on proper target window.
         */
        void onDragSuccess(NotificationEntry entry);

        /**
         * @param entryAdapter The EntryAdapter that successfully dropped on the proper
         *            target window
         */
        void onDragSuccess(EntryAdapter entryAdapter);
    }

    /**
     * Equivalent to View.OnClickListener with coordinates
     */
    public interface CoordinateOnClickListener {
        /**
         * Equivalent to {@link View.OnClickListener#onClick(View)} with coordinates
         *
         * @return whether the click was handled
         */
        boolean onClick(View v, int x, int y, MenuItem item);
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        // Skip super call; dump viewState ourselves
        pw.println("Notification: " + getKey());
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            pw.println(this);
            pw.print("visibility: " + getVisibility());
            pw.print(", alpha: " + getAlpha());
            pw.print(", translationX: " + getTranslation());
            pw.print(", translationY: " + getTranslationY());
            pw.print(", entry dismissable: " + canEntryBeDismissed());
            pw.print(", mOnUserInteractionCallback==null: " + (mOnUserInteractionCallback == null));
            pw.print(", removed: " + isRemoved());
            pw.print(", dismissed: " + isDismissed());
            pw.print(", expandAnimationRunning: " + mExpandAnimationRunning);
            pw.print(", mShowingPublic: " + mShowingPublic);
            pw.print(", mShowingPublicInitialized: " + mShowingPublicInitialized);
            NotificationContentView showingLayout = getShowingLayout();
            pw.print(", privateShowing: " + (showingLayout == mPrivateLayout));
            pw.print(", childrenContainerShowing: "
                    + (!shouldShowPublic() && mIsSummaryWithChildren));
            pw.print(", mShowNoBackground: " + mShowNoBackground);
            pw.print(", clipBounds: " + getClipBounds());
            pw.print(", isPromotedOngoing: " + isPromotedOngoing());
            pw.print(", isShowingExpanded: " + isShowingExpanded());
            pw.print(", isAccessibilityExpandable: " + isAccessibilityExpandable());
            pw.print(", isExpandable: " + isExpandable());
            pw.print(", mExpandable: " + mExpandable);
            pw.print(", isUserExpanded: " + isUserExpanded());
            pw.print(", hasUserChangedExpansion: " + mHasUserChangedExpansion);
            pw.print(", isOnKeyguard: " + isOnKeyguard());
            pw.print(", isSummaryWithChildren: " + mIsSummaryWithChildren);
            pw.print(", enableNonGroupedExpand: " + mEnableNonGroupedNotificationExpand);
            pw.print(", isPinned: " + isPinned());
            pw.print(", expandedWhenPinned: " + mExpandedWhenPinned);
            pw.print(", isMinimized: " + mIsMinimized);
            pw.print(", isAboveShelf: " + isAboveShelf());
            pw.print(", redactionType: " + mRedactionType);

            pw.println();
            if (NotificationContentView.INCLUDE_HEIGHTS_TO_DUMP) {
                dumpHeights(pw);
            }
            showingLayout.dump(pw, args);
            dumpAppearAnimationProperties(pw, args);
            dumpCustomOutline(pw, args);
            dumpClipping(pw, args);
            if (getViewState() != null) {
                getViewState().dump(pw, args);
                pw.println();
            } else {
                pw.println("no viewState!!!");
            }
            pw.println(getRoundableState().debugString());
            if (mBigPictureIconManager != null) {
                mBigPictureIconManager.dump(pw, args);
            }
            dumpBackgroundView(pw, args);
            dumpPhysicalAnimationProperties(pw);

            int transientViewCount = mChildrenContainer == null
                    ? 0 : mChildrenContainer.getTransientViewCount();
            if (mIsSummaryWithChildren || transientViewCount > 0) {
                pw.println(mChildrenContainer.debugString());
                pw.println("Children Container Intrinsic Height: "
                        + mChildrenContainer.getIntrinsicHeight());
                pw.println();
                dumpChildren(pw, args);
                dumpTransientViews(transientViewCount, pw, args);
            } else if (mPrivateLayout != null) {
                mPrivateLayout.dumpSmartReplies(pw);
            }
        });
    }

    protected void dumpPhysicalAnimationProperties(IndentingPrintWriter pw) {
        pw.println("PhysicalAnimation#translationY: " +
                (getTag(PhysicsPropertyAnimator.Y_TRANSLATION.getTag()) != null
                        ? getTag(PhysicsPropertyAnimator.Y_TRANSLATION.getTag())
                        : "none"));
        pw.println("PhysicalAnimation#height: " +
                (getTag(TAG_ANIMATOR_HEIGHT) != null ? getTag(TAG_ANIMATOR_HEIGHT) : "none"));
    }

    private void dumpChildren(IndentingPrintWriter pw, String[] args) {
        List<ExpandableNotificationRow> notificationChildren = getAttachedChildren();
        pw.print("Children: " + notificationChildren.size() + " {");
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            for (ExpandableNotificationRow child : notificationChildren) {
                pw.println();
                child.dump(pw, args);
            }
        });
        pw.println("}");
    }

    private void dumpTransientViews(int transientCount, IndentingPrintWriter pw, String[] args) {
        pw.print("Transient Views: " + transientCount + " {");
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            for (int i = 0; i < transientCount; i++) {
                pw.println();
                ExpandableView child = (ExpandableView) mChildrenContainer.getTransientView(i);
                child.dump(pw, args);
            }
        });
        pw.println("}");
    }

    private void dumpHeights(IndentingPrintWriter pw) {
        pw.print("Heights: ");
        pw.print("intrinsic", getIntrinsicHeight());
        pw.print("actual", getActualHeight());
        pw.print("maxContent", getMaxContentHeight());
        pw.print("maxExpanded", getMaxExpandHeight());
        pw.print("collapsed", getCollapsedHeight());
        pw.print("headsup", getHeadsUpHeight());
        pw.print("headsup  without header", getHeadsUpHeightWithoutHeader());
        pw.print("minHeight", getMinHeight());
        pw.print("pinned headsup", getPinnedHeadsUpHeight(
                true /* atLeastMinHeight */));
        pw.println();
        pw.print("Intrinsic Height Factors: ");
        pw.print("isUserLocked()", isUserLocked());
        pw.print("isChildInGroup()", isChildInGroup());
        pw.print("isGroupExpanded()", isGroupExpanded());
        pw.print("sensitive", mSensitive);
        pw.print("hideSensitiveForIntrinsicHeight", mHideSensitiveForIntrinsicHeight);
        pw.print("isSummaryWithChildren", mIsSummaryWithChildren);
        pw.print("canShowHeadsUp()", canShowHeadsUp());
        pw.print("isHeadsUpState()", isHeadsUpState());
        pw.print("isPinned()", isPinned());
        pw.print("headsupDisappearRunning", mHeadsupDisappearRunning);
        pw.print("isExpanded()", isExpanded());
        pw.println();
    }

    private void logKeepInParentChildDetached(ExpandableNotificationRow child) {
        if (mLogger != null) {
            mLogger.logKeepInParentChildDetached(child.getLoggingKey(), mLoggingKey);
        }
    }

    private void logSkipAttachingKeepInParentChild(ExpandableNotificationRow child) {
        if (mLogger != null) {
            mLogger.logSkipAttachingKeepInParentChild(child.getLoggingKey(), mLoggingKey);
        }
    }

    private void setTargetPoint(Point p) {
        mTargetPoint = p;
    }

    /** Update the minimum roundness based on current state */
    private void updateBaseRoundness() {
        if (isChildInGroup()) {
            requestRoundnessReset(BASE_VALUE);
        } else {
            requestRoundness(mSmallRoundness, mSmallRoundness, BASE_VALUE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateBaseRoundness();
    }

    /** Set whether this notification may show a snooze action. */
    public void setShowSnooze(boolean showSnooze) {
        mShowSnooze = showSnooze;
    }

    /** Whether this notification may show a snooze action. */
    public boolean getShowSnooze() {
        return mShowSnooze;
    }

    @Override
    public void removeFromTransientContainer() {
        final ViewGroup transientContainer = getTransientContainer();
        final ViewParent parent = getParent();
        // Only log when there is real removal of transient views
        if (transientContainer == null || transientContainer != parent) {
            super.removeFromTransientContainer();
            return;
        }
        logRemoveFromTransientContainer(transientContainer);
        super.removeFromTransientContainer();
    }

    /**
     * Log calls to removeFromTransientContainer when the container is NotificationChildrenContainer
     * or NotificationStackScrollLayout.
     */
    public void logRemoveFromTransientContainer(ViewGroup transientContainer) {
        if (mLogger == null) {
            return;
        }
        if (transientContainer instanceof NotificationChildrenContainer) {
            mLogger.logRemoveTransientFromContainer(
                    /* childEntry = */ mLoggingKey,
                    /* containerEntry = */ ((NotificationChildrenContainer) transientContainer)
                            .getContainingNotification().getLoggingKey()
            );
        } else if (transientContainer instanceof NotificationStackScrollLayout) {
            mLogger.logRemoveTransientFromNssl(
                    /* childEntry = */ mLoggingKey
            );
        } else {
            mLogger.logRemoveTransientFromViewGroup(
                    /* childEntry = */ mLoggingKey,
                    /* containerView = */ transientContainer
            );
        }
    }

    @Override
    public void addTransientView(View view, int index) {
        if (view instanceof ExpandableNotificationRow) {
            logAddTransientRow((ExpandableNotificationRow) view, index);
        }
        super.addTransientView(view, index);
    }

    private void logAddTransientRow(ExpandableNotificationRow row, int index) {
        if (mLogger == null) {
            return;
        }
        mLogger.logAddTransientRow(row.getLoggingKey(), mLoggingKey, index);
    }

    @Override
    public void removeTransientView(View view) {
        if (view instanceof ExpandableNotificationRow) {
            logRemoveTransientRow((ExpandableNotificationRow) view);
        }
        super.removeTransientView(view);
    }

    private void logRemoveTransientRow(ExpandableNotificationRow row) {
        if (mLogger == null) {
            return;
        }
        mLogger.logRemoveTransientRow(row.getLoggingKey(), mLoggingKey);
    }

    /** Set whether this notification is currently used to animate a launch. */
    public void setLaunchAnimationRunning(boolean launchAnimationRunning) {
        if (NotificationBundleUi.isEnabled()) {
            mLaunchAnimationRunning = launchAnimationRunning;
        } else {
            getEntryLegacy().setExpandAnimationRunning(launchAnimationRunning);
        }
    }

    /** Whether this notification is currently used to animate a launch. */
    public boolean isLaunchAnimationRunning() {
        if (NotificationBundleUi.isEnabled()) {
            return mLaunchAnimationRunning;
        } else {
            return getEntryLegacy().isExpandAnimationRunning();
        }
    }

    @Override
    protected boolean usesTransparentBackground() {
        // Row background should be opaque when it's displayed as a heads-up notification or
        // displayed on keyguard.
        // Also, for an unpinned HUN on the unlocked shade, the row bg should be transparent.
        if (mOnKeyguard) return mIsLockscreenBlurSupported;
        return super.usesTransparentBackground()
                && !mustStayOnScreen()
                && !(isChildInGroup() && !mNotificationParent.usesTransparentBackground())
                && !mHeadsupDisappearRunning;
    }
}
