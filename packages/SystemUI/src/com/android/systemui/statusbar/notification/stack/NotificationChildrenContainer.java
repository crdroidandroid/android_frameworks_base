/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Trace;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.NotificationExpandButton;
import com.android.systemui.Flags;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.NotificationGroupingUtil;
import com.android.systemui.statusbar.notification.NotificationFadeAware;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.Roundable;
import com.android.systemui.statusbar.notification.RoundableState;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.HybridGroupManager;
import com.android.systemui.statusbar.notification.row.HybridNotificationView;
import com.android.systemui.statusbar.notification.row.ui.viewmodel.BundleHeaderViewModel;
import com.android.systemui.statusbar.notification.row.wrapper.BundleHeaderViewWrapper;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A container containing child notifications
 */
public class NotificationChildrenContainer extends ViewGroup
        implements NotificationFadeAware, Roundable {

    private static final String TAG = "NotificationChildrenContainer";

    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_BUNDLE_COLLAPSED = 0;
    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_BUNDLE_SYSTEM_EXPANDED = 30;
    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_BUNDLE_EXPANDED = 50;
    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_WHEN_COLLAPSED = 2;
    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_WHEN_COLLAPSED_BUNDLED = 5;
    @VisibleForTesting
    static final int NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED = 5;
    public static final int NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED = 8;
    private static final AnimationProperties ALPHA_FADE_IN = new AnimationProperties() {
        private final AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);
    private static final SourceType FROM_PARENT = SourceType.from("FromParent(NCC)");

    private final List<View> mDividers = new ArrayList<>();
    private final List<ExpandableNotificationRow> mAttachedChildren = new ArrayList<>();
    private final HybridGroupManager mHybridGroupManager;
    private int mChildPadding;
    private int mDividerHeight;
    private float mDividerAlpha;

    private int mHeaderHeight;
    private int mCollapsedHeaderMargin;
    private int mAdditionalExpandedHeaderMargin;

    private float mCollapsedBottomPadding;
    private boolean mChildrenExpanded;
    private ExpandableNotificationRow mContainingNotification;
    private TextView mOverflowNumber;
    private ViewState mGroupOverFlowState;
    private int mRealHeight;
    private boolean mIsUserSwipingToExpandRow;
    private int mActualHeight;
    private boolean mNeverAppliedGroupState;

    /**
     * Whether or not individual notifications that are part of this container will have shadows.
     */
    private boolean mEnableShadowOnChildNotifications;

    /**
     * This view is only set when this NCC is a bundle. If this view is set, all other header
     * view variants have to be null.
     */
    private ComposeView mBundleHeaderView;
    @Nullable private BundleHeaderViewModel mBundleHeaderViewModel;
    private BundleHeaderViewWrapper mBundleHeaderWrapper;

    private NotificationHeaderView mGroupHeader;
    private NotificationHeaderViewWrapper mGroupHeaderWrapper;
    private NotificationHeaderView mMinimizedGroupHeader;
    private NotificationHeaderViewWrapper mMinimizedGroupHeaderWrapper;
    private NotificationGroupingUtil mGroupingUtil;
    private ViewState mHeaderViewState;
    private ViewState mTopLineViewState;
    private ViewState mExpandButtonViewState;
    private int mClipBottomAmount;
    private boolean mIsMinimized;
    private OnClickListener mHeaderClickListener;
    private ViewGroup mCurrentHeader;
    private Path mChildClipPath = null;
    private final Path mHeaderPath = new Path();
    private boolean mShowGroupCountInExpander;
    private boolean mShowDividersWhenExpanded;
    private boolean mHideDividersDuringExpand;
    private int mTranslationForHeader;
    private int mCurrentHeaderTranslation = 0;
    private float mHeaderVisibleAmount = 1.0f;
    private int mUntruncatedChildCount;
    private boolean mContainingNotificationIsFaded = false;
    private final RoundableState mRoundableState;
    private int mMinSingleLineHeight;

    private NotificationChildrenContainerLogger mLogger;
    private ArrayMap<String, RectF> mExpandedClipRect = new ArrayMap<>();

    public NotificationChildrenContainer(Context context) {
        this(context, null);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationChildrenContainer(
            Context context,
            AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mHybridGroupManager = new HybridGroupManager(getContext());
        mRoundableState = new RoundableState(this, this, 0f);
        initDimens();
        setClipChildren(false);
    }

    private void initDimens() {
        Resources res = getResources();
        mChildPadding = res.getDimensionPixelOffset(R.dimen.notification_children_padding);
        mDividerHeight = res.getDimensionPixelOffset(
                R.dimen.notification_children_container_divider_height);
        mDividerAlpha = res.getFloat(R.dimen.notification_divider_alpha);
        mHeaderHeight = res.getDimensionPixelSize(R.dimen.notification_2025_header_height);
        mCollapsedHeaderMargin = Notification.Builder.getContentMarginTop(getContext(),
                R.dimen.notification_2025_children_container_margin_top);
        mAdditionalExpandedHeaderMargin = getHeaderHeight() - getCollapsedHeaderMargin();

        mCollapsedBottomPadding = res.getDimensionPixelOffset(
                R.dimen.notification_children_collapsed_bottom_padding);
        mEnableShadowOnChildNotifications =
                res.getBoolean(R.bool.config_enableShadowOnChildNotifications);
        mShowGroupCountInExpander =
                res.getBoolean(R.bool.config_showNotificationGroupCountInExpander);
        mShowDividersWhenExpanded =
                res.getBoolean(R.bool.config_showDividersWhenGroupNotificationExpanded);
        mHideDividersDuringExpand =
                res.getBoolean(R.bool.config_hideDividersDuringExpand);
        mTranslationForHeader = res.getDimensionPixelOffset(
                com.android.internal.R.dimen.notification_2025_margin)
                - getCollapsedHeaderMargin();
        mHybridGroupManager.initDimens();
        mMinSingleLineHeight = getResources().getDimensionPixelSize(
                R.dimen.conversation_single_line_face_pile_size);
        if (mBundleHeaderView != null) {
            initBundleDimens();
        }
    }

    @NonNull
    @Override
    public RoundableState getRoundableState() {
        return mRoundableState;
    }

    @Override
    public int getClipHeight() {
        return Math.max(mActualHeight - mClipBottomAmount, 0);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount =
                Math.min(mAttachedChildren.size(), getNumberOfChildrenWhenExpanded());
        for (int i = 0; i < childCount; i++) {
            View child = mAttachedChildren.get(i);
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
            child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            mDividers.get(i).layout(0, 0, getWidth(), mDividerHeight);
        }
        if (mOverflowNumber != null) {
            boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int left = (isRtl ? 0 : getWidth() - mOverflowNumber.getMeasuredWidth());
            int right = left + mOverflowNumber.getMeasuredWidth();
            mOverflowNumber.layout(left, 0, right, mOverflowNumber.getMeasuredHeight());
        }
        if (mGroupHeader != null) {
            mGroupHeader.layout(0, 0, mGroupHeader.getMeasuredWidth(),
                    mGroupHeader.getMeasuredHeight());
        }
        if (mMinimizedGroupHeader != null) {
            mMinimizedGroupHeader.layout(0, 0,
                    mMinimizedGroupHeader.getMeasuredWidth(),
                    mMinimizedGroupHeader.getMeasuredHeight());
        }
        if (mBundleHeaderView != null) {
            mBundleHeaderView.layout(0, 0, mBundleHeaderView.getMeasuredWidth(),
                    mBundleHeaderView.getMeasuredHeight());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection("NotificationChildrenContainer#onMeasure");
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int newHeightSpec = heightMeasureSpec;
        if (hasFixedHeight || isHeightLimited) {
            newHeightSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mOverflowNumber != null) {
            mOverflowNumber.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    newHeightSpec);
        }
        int dividerHeightSpec = MeasureSpec.makeMeasureSpec(mDividerHeight, MeasureSpec.EXACTLY);
        int height = getCollapsedHeaderMargin() + getAdditionalExpandedHeaderMargin();
        int childCount =
                Math.min(mAttachedChildren.size(), getNumberOfChildrenWhenExpanded());
        int collapsedChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        int overflowIndex = childCount > collapsedChildren ? collapsedChildren - 1 : -1;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            // We need to measure all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen.
            boolean isOverflow = i == overflowIndex;
            child.setSingleLineWidthIndention(isOverflow && mOverflowNumber != null
                    ? mOverflowNumber.getMeasuredWidth() : 0);
            child.measure(widthMeasureSpec, newHeightSpec);
            // layout the divider
            View divider = mDividers.get(i);
            divider.measure(widthMeasureSpec, dividerHeightSpec);
            if (child.getVisibility() != GONE) {
                height += child.getMeasuredHeight() + mDividerHeight;
            }
        }
        mRealHeight = height;
        if (heightMode != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, size);
        }

        int headerHeightSpec = MeasureSpec.makeMeasureSpec(getHeaderHeight(), MeasureSpec.EXACTLY);
        if (mGroupHeader != null) {
            mGroupHeader.measure(widthMeasureSpec, headerHeightSpec);
        }
        if (mMinimizedGroupHeader != null) {
            mMinimizedGroupHeader.measure(widthMeasureSpec, headerHeightSpec);
        }
        if (mBundleHeaderView != null) {
            mBundleHeaderView.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(getHeaderHeight(), MeasureSpec.UNSPECIFIED));
        }

        setMeasuredDimension(width, height);
        Trace.endSection();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public boolean pointInView(float localX, float localY, float slop) {
        return localX >= -slop && localY >= -slop && localX < ((mRight - mLeft) + slop) &&
                localY < (mRealHeight + slop);
    }

    /**
     * Set the untruncated number of children in the group so that the view can update the UI
     * appropriately. Note that this may differ from the number of views attached as truncated
     * children will not have views.
     */
    public void setUntruncatedChildCount(int childCount) {
        mUntruncatedChildCount = childCount;
        updateGroupOverflow();
    }

    /**
     * Set the notification time in the group so that the view can show the latest event in the UI
     * appropriately.
     */
    public void setNotificationGroupWhen(long whenMillis) {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setNotificationWhen(whenMillis);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setNotificationWhen(whenMillis);
        }
    }

    /**
     * Add a child notification to this view.
     *
     * @param row        the row to add
     * @param childIndex the index to add it at, if -1 it will be added at the end
     */
    public void addNotification(ExpandableNotificationRow row, int childIndex) {
        ensureRemovedFromTransientContainer(row);
        int newIndex = childIndex < 0 ? mAttachedChildren.size() : childIndex;
        mAttachedChildren.add(newIndex, row);
        addView(row);
        row.setUserSwipingToExpandRow(mIsUserSwipingToExpandRow);

        View divider = inflateDivider();
        addView(divider);
        mDividers.add(newIndex, divider);

        row.setContentTransformationAmount(0, false /* isLastChild */);
        row.setNotificationFaded(mContainingNotificationIsFaded);

        // It doesn't make sense to keep old animations around, lets cancel them!
        ExpandableViewState viewState = row.getViewState();
        if (viewState != null) {
            viewState.cancelAnimations(row);
            row.cancelAppearDrawing();
        }

        applyRoundnessAndInvalidate();
    }

    private void ensureRemovedFromTransientContainer(View v) {
        if (v.getParent() != null && v instanceof ExpandableView) {
            // If the child is animating away, it will still have a parent, so detach it first
            // TODO: We should really cancel the active animations here. This will
            //  happen automatically when the view's intro animation starts, but
            //  it's a fragile link.
            ((ExpandableView) v).removeFromTransientContainerForAdditionTo(this);
        }
    }

    public void removeNotification(ExpandableNotificationRow row) {
        int childIndex = mAttachedChildren.indexOf(row);
        mAttachedChildren.remove(row);
        removeView(row);

        final View divider = mDividers.remove(childIndex);
        removeView(divider);
        getOverlay().add(divider);
        CrossFadeHelper.fadeOut(divider, new Runnable() {
            @Override
            public void run() {
                getOverlay().remove(divider);
            }
        });

        row.setSystemChildExpanded(false);
        row.setNotificationFaded(false);
        row.setUserSwipingToExpandRow(false);
        if (!row.isRemoved()) {
            mGroupingUtil.restoreChildNotification(row);
        }

        row.requestRoundnessReset(FROM_PARENT, /* animate = */ false);
        applyRoundnessAndInvalidate();
    }

    /**
     * @return The number of notification children in the container.
     */
    public int getNotificationChildCount() {
        return mAttachedChildren.size();
    }

    /**
     * Update the expand state of the group header.
     */
    public void updateGroupHeaderExpandState() {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setExpanded(mChildrenExpanded);
        }
    }

    private void removeGroupHeader() {
        if (mGroupHeader == null) {
            return;
        }
        removeView(mGroupHeader);
        mGroupHeader = null;
        mGroupHeaderWrapper = null;
    }

    private void removeLowPriorityGroupHeader() {
        if (mMinimizedGroupHeader == null) {
            return;
        }
        removeView(mMinimizedGroupHeader);
        mMinimizedGroupHeader = null;
        mMinimizedGroupHeaderWrapper = null;
    }

    public void setBundleHeaderView(@NonNull ComposeView view) {
        initBundleDimens();
        mBundleHeaderView = view;
        addView(mBundleHeaderView);
        mBundleHeaderWrapper = (BundleHeaderViewWrapper) NotificationViewWrapper.wrap(getContext(),
                mBundleHeaderView, mContainingNotification);
        mBundleHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
        invalidate();
    }

    public void setBundleHeaderViewModel(@Nullable BundleHeaderViewModel viewModel) {
        mBundleHeaderViewModel = viewModel;
        setChildrenExpanded(mContainingNotification.isGroupExpanded());
        invalidate();
    }

    private void initBundleDimens() {
        Resources res = getResources();
        mCollapsedHeaderMargin = getHeaderHeight();
        mAdditionalExpandedHeaderMargin = 0;
        mCollapsedBottomPadding = 0;
        mDividerHeight = res.getDimensionPixelOffset(
                R.dimen.bundle_children_container_divider_height);
    }

    /**
     * Set the group header view
     * @param headerView view to set
     * @param onClickListener OnClickListener of the header view
     */
    public void setGroupHeader(
            NotificationHeaderView headerView,
            OnClickListener onClickListener
    ) {
        mHeaderClickListener = onClickListener;

        removeGroupHeader();

        if (headerView == null) {
            return;
        }

        mGroupHeader = headerView;
        mGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                .setVisibility(VISIBLE);
        mGroupHeader.setOnClickListener(mHeaderClickListener);
        mGroupHeaderWrapper =
                (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                        getContext(),
                        mGroupHeader,
                        mContainingNotification);
        mGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
        addView(mGroupHeader, 0);
        invalidate();

        mGroupHeaderWrapper.setExpanded(mChildrenExpanded);
        mGroupHeaderWrapper.onContentUpdated(mContainingNotification);
        updateGroupOverflow();
        resetHeaderVisibilityIfNeeded(mGroupHeader, calculateDesiredHeader());
        updateHeaderVisibility(false /* animate */);
        updateChildrenAppearance();

        Trace.endSection();
    }

    /**
     * Set the low-priority group header view
     * @param headerViewLowPriority header view to set
     * @param onClickListener OnClickListener of the header view
     */
    public void setLowPriorityGroupHeader(
            NotificationHeaderView headerViewLowPriority,
            OnClickListener onClickListener
    ) {
        removeLowPriorityGroupHeader();
        if (headerViewLowPriority == null) {
            return;
        }

        mMinimizedGroupHeader = headerViewLowPriority;
        mMinimizedGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                .setVisibility(VISIBLE);
        mMinimizedGroupHeader.setOnClickListener(onClickListener);
        mMinimizedGroupHeaderWrapper =
                (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                        getContext(),
                        mMinimizedGroupHeader,
                        mContainingNotification);
        mMinimizedGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
        addView(mMinimizedGroupHeader, 0);
        invalidate();

        mMinimizedGroupHeaderWrapper.onContentUpdated(mContainingNotification);
        resetHeaderVisibilityIfNeeded(mMinimizedGroupHeader, calculateDesiredHeader());
        updateHeaderVisibility(false /* animate */);
        updateChildrenAppearance();
    }

    public float getChildRenderingStartPosition() {
        return getHeaderHeight() + mDividerHeight;
    }

    /**
     * Recreate the low-priority header.
     *
     * @param builder a builder to reuse. Otherwise the builder will be recovered.
     */
    @VisibleForTesting
    void recreateLowPriorityHeader(Notification.Builder builder) {
        RemoteViews header;
        StatusBarNotification notification = mContainingNotification.getEntryAdapter().getSbn();
        if (notification == null) {
            return;
        }
        if (mIsMinimized) {
            if (builder == null) {
                builder = Notification.Builder.recoverBuilder(getContext(),
                        notification.getNotification());
            }
            header = builder.makeLowPriorityContentView(true /* useRegularSubtext */);
            if (mMinimizedGroupHeader == null) {
                mMinimizedGroupHeader = (NotificationHeaderView) header.apply(getContext(),
                        this);
                mMinimizedGroupHeader.findViewById(com.android.internal.R.id.expand_button)
                        .setVisibility(VISIBLE);
                mMinimizedGroupHeader.setOnClickListener(mHeaderClickListener);
                mMinimizedGroupHeaderWrapper =
                        (NotificationHeaderViewWrapper) NotificationViewWrapper.wrap(
                                getContext(),
                                mMinimizedGroupHeader,
                                mContainingNotification);
                mGroupHeaderWrapper.setOnRoundnessChangedListener(this::invalidate);
                addView(mMinimizedGroupHeader, 0);
                invalidate();
            } else {
                header.reapply(getContext(), mMinimizedGroupHeader);
            }
            mMinimizedGroupHeaderWrapper.onContentUpdated(mContainingNotification);
            resetHeaderVisibilityIfNeeded(mMinimizedGroupHeader, calculateDesiredHeader());
        } else {
            removeView(mMinimizedGroupHeader);
            mMinimizedGroupHeader = null;
            mMinimizedGroupHeaderWrapper = null;
        }
    }

    /**
     * Update the appearance of the children to reduce redundancies.
     */
    public void updateChildrenAppearance() {
        mGroupingUtil.updateChildrenAppearance();
    }

    private void setExpandButtonNumber(NotificationViewWrapper wrapper) {
        View expandButton = wrapper == null
                ? null : wrapper.getExpandButton();
        if (expandButton instanceof NotificationExpandButton) {
            ((NotificationExpandButton) expandButton).setNumber(mUntruncatedChildCount);
        }
    }

    public void updateGroupOverflow() {
        if (mShowGroupCountInExpander) {
            setExpandButtonNumber(mGroupHeaderWrapper);
            setExpandButtonNumber(mMinimizedGroupHeaderWrapper);
            return;
        }
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        if (mUntruncatedChildCount > maxAllowedVisibleChildren) {
            int number = mUntruncatedChildCount - maxAllowedVisibleChildren;
            mOverflowNumber = mHybridGroupManager.bindOverflowNumber(mOverflowNumber, number, this);
            if (mGroupOverFlowState == null) {
                mGroupOverFlowState = new ViewState();
                mNeverAppliedGroupState = true;
            }
        } else if (mOverflowNumber != null) {
            removeView(mOverflowNumber);
            if (isShown() && isAttachedToWindow()) {
                final View removedOverflowNumber = mOverflowNumber;
                addTransientView(removedOverflowNumber, getTransientViewCount());
                CrossFadeHelper.fadeOut(removedOverflowNumber, new Runnable() {
                    @Override
                    public void run() {
                        removeTransientView(removedOverflowNumber);
                    }
                });
            }
            mOverflowNumber = null;
            mGroupOverFlowState = null;
        }
    }

    private void updateBundleHeaderRounding() {
        if (mBundleHeaderWrapper != null) {
            mBundleHeaderWrapper.recalculateRadius();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateGroupOverflow();
        updateBundleHeaderRounding();
    }

    private View inflateDivider() {
        View divider = LayoutInflater.from(mContext).inflate(
                R.layout.notification_children_divider, this, false);
        divider.setAlpha(0f);
        return divider;
    }

    /**
     * Get notification children that are attached currently.
     */
    public List<ExpandableNotificationRow> getAttachedChildren() {
        return mAttachedChildren;
    }

    /**
     * Sets the alpha on the content, while leaving the background of the container itself as is.
     *
     * @param alpha alpha value to apply to the content
     */
    public void setContentAlpha(float alpha) {
        if (mGroupHeader != null) {
            for (int i = 0; i < mGroupHeader.getChildCount(); i++) {
                mGroupHeader.getChildAt(i).setAlpha(alpha);
            }
        }
        for (ExpandableNotificationRow child : getAttachedChildren()) {
            child.setContentAlpha(alpha);
        }
    }

    /**
     * To be called any time the rows have been updated
     */
    public void updateExpansionStates() {
        if (mChildrenExpanded || mIsUserSwipingToExpandRow) {
            // we don't modify it the group is expanded or if we are expanding it
            return;
        }
        int size = mAttachedChildren.size();
        for (int i = 0; i < size; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.setSystemChildExpanded(i == 0 && size == 1);
        }
    }

    /**
     * @return the intrinsic size of this children container, i.e the natural fully expanded state
     */
    public int getIntrinsicHeight() {
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        return getIntrinsicHeight(maxAllowedVisibleChildren);
    }

    /**
     * @return the intrinsic height with a number of children given
     * in @param maxAllowedVisibleChildren
     */
    private int getIntrinsicHeight(float maxAllowedVisibleChildren) {
        if (showingAsLowPriority()) {
            return getHeaderHeight();
        }
        int intrinsicHeight = getCollapsedHeaderMargin() + mCurrentHeaderTranslation;
        int visibleChildren = 0;
        int childCount = mAttachedChildren.size();
        boolean firstChild = true;
        float expandFactor = 0;
        if (mIsUserSwipingToExpandRow) {
            expandFactor = getGroupExpandFraction();
        }
        boolean childrenExpanded = mChildrenExpanded;
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            if (!firstChild) {
                if (mIsUserSwipingToExpandRow) {
                    intrinsicHeight += NotificationUtils.interpolate(mChildPadding, mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += childrenExpanded ? mDividerHeight : mChildPadding;
                }
            } else {
                if (mIsUserSwipingToExpandRow) {
                    intrinsicHeight += NotificationUtils.interpolate(
                            0,
                            getAdditionalExpandedHeaderMargin() + mDividerHeight,
                            expandFactor);
                } else {
                    intrinsicHeight += childrenExpanded
                            ? getAdditionalExpandedHeaderMargin() + mDividerHeight
                            : 0;
                }
                firstChild = false;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            intrinsicHeight += child.getIntrinsicHeight();
            visibleChildren++;
        }
        if (mIsUserSwipingToExpandRow) {
            intrinsicHeight += NotificationUtils.interpolate(mCollapsedBottomPadding, 0.0f,
                    expandFactor);
        } else if (!childrenExpanded) {
            intrinsicHeight += mCollapsedBottomPadding;
        }

        if (Flags.notificationChildrenContainerMinHeight()) {
            // The height should at the very minimum be able to accommodate the header.
            return Math.max(getHeaderHeight(), intrinsicHeight);
        } else {
            return intrinsicHeight;
        }
    }

    /**
     * Update the state of all its children based on a linear layout algorithm.
     *
     * @param parentState  the state of the parent
     */
    public void updateState(ExpandableViewState parentState) {
        int childCount = mAttachedChildren.size();
        int yPosition = getCollapsedHeaderMargin() + mCurrentHeaderTranslation;
        boolean firstChild = true;
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        int lastVisibleIndex = maxAllowedVisibleChildren - 1;
        int firstOverflowIndex = lastVisibleIndex + 1;
        float expandFactor = 0;
        boolean expandingToExpandedGroup = mIsUserSwipingToExpandRow && !showingAsLowPriority();
        if (mIsUserSwipingToExpandRow) {
            expandFactor = getGroupExpandFraction();
            firstOverflowIndex = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        }

        boolean childrenExpandedAndNotAnimating = mChildrenExpanded
                && !mContainingNotification.isGroupExpansionChanging();
        int launchTransitionCompensation = 0;
        mExpandedClipRect.clear();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.updateChildrenStates();
            if (!firstChild) {
                if (expandingToExpandedGroup) {
                    yPosition += NotificationUtils.interpolate(mChildPadding, mDividerHeight,
                            expandFactor);
                } else {
                    yPosition += mChildrenExpanded ? mDividerHeight : mChildPadding;
                }
            } else { // first child
                if (expandingToExpandedGroup) {
                    yPosition += NotificationUtils.interpolate(
                            0,
                            getAdditionalExpandedHeaderMargin() + mDividerHeight,
                            expandFactor);
                } else {
                    yPosition += mChildrenExpanded
                            ? getAdditionalExpandedHeaderMargin() + mDividerHeight
                            : 0;
                }
                firstChild = false;
            }

            ExpandableViewState childState = child.getViewState();
            int intrinsicHeight = child.getIntrinsicHeight();
            childState.height = intrinsicHeight;
            childState.setYTranslation(yPosition + launchTransitionCompensation,
                    "NotificationChildrenContainer.updateState.child");
            childState.hidden = false;
            if (child.isExpandAnimationRunning() || mContainingNotification.hasExpandingChild()) {
                // Not modifying translationZ during launch animation. The translationZ of the
                // expanding child is handled inside ExpandableNotificationRow and the translationZ
                // of the other children inside the group should remain unchanged. In particular,
                // they should not take over the translationZ of the parent, since the parent has
                // a positive translationZ set only for the expanding child to be drawn above other
                // notifications.
                childState.setZTranslation(child.getTranslationZ());
            } else if (childrenExpandedAndNotAnimating && mEnableShadowOnChildNotifications) {
                // When the group is expanded, the children cast the shadows rather than the parent
                // so use the parent's elevation here.
                childState.setZTranslation(parentState.getZTranslation());
            } else {
                childState.setZTranslation(0);
            }
            childState.hideSensitive = parentState.hideSensitive;
            childState.belowSpeedBump = parentState.belowSpeedBump;
            childState.clipTopAmount = 0;
            childState.setAlpha(0, "ncc updateState");
            if (i < firstOverflowIndex) {
                childState.setAlpha(showingAsLowPriority() ? expandFactor : 1.0f, "ncc overflow");
            } else if (expandFactor == 1.0f && i <= lastVisibleIndex) {
                float rawAlpha = (mActualHeight - childState.getYTranslation()) / childState.height;
                childState.setAlpha(Math.max(0.0f, Math.min(1.0f, rawAlpha)), "ncc alpha");
            }
            childState.location = parentState.location;
            childState.inShelf = parentState.inShelf;
            yPosition += intrinsicHeight;

            // If this is a group summary and a child of a bundle, then the clip path needs to
            // be expanded otherwise the summary children will be clipped when translated
            if (childNeedsExpandedClipPath(child)) {
                RectF expandClipRect = getExpandedClipRect(child);
                mExpandedClipRect.put(child.getKey(), expandClipRect);
            }
        }
        if (mOverflowNumber != null) {
            ExpandableNotificationRow overflowView = mAttachedChildren.get(Math.min(
                    getMaxAllowedVisibleChildren(true /* likeCollapsed */), childCount) - 1);
            mGroupOverFlowState.copyFrom(overflowView.getViewState());

            if (!mChildrenExpanded) {
                HybridNotificationView alignView = overflowView.getSingleLineView();
                if (alignView != null) {
                    View mirrorView = alignView.getTextView();
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = alignView.getTitleView();
                    }
                    if (mirrorView.getVisibility() == GONE) {
                        mirrorView = alignView;
                    }
                    mGroupOverFlowState.setAlpha(mirrorView.getAlpha(), "group overflow");
                    float yTranslation = mGroupOverFlowState.getYTranslation()
                            + NotificationUtils.getRelativeYOffset(
                            mirrorView, overflowView);
                    mGroupOverFlowState.setYTranslation(yTranslation,
                            "NotificationChildrenContainer.updateState.overflow");
                }
            } else {
                mGroupOverFlowState.setYTranslation(
                        mGroupOverFlowState.getYTranslation() + getCollapsedHeaderMargin(),
                        "NotificationChildrenContainer.updateState.overflow.collapsed");
                mGroupOverFlowState.setAlpha(0.0f, "group overflow");
            }
        }
        if (mGroupHeader != null) {
            mHeaderViewState = initStateForGroupHeader(mHeaderViewState);

            if (mContainingNotification.hasExpandingChild()) {
                // Not modifying translationZ during expand animation.
                mHeaderViewState.setZTranslation(mGroupHeader.getTranslationZ());
            } else if (childrenExpandedAndNotAnimating) {
                mHeaderViewState.setZTranslation(parentState.getZTranslation());
            } else {
                mHeaderViewState.setZTranslation(0);
            }
            mHeaderViewState.setYTranslation(mCurrentHeaderTranslation,
                    "NotificationChildrenContainer.updateState.header");
            mHeaderViewState.setAlpha(mHeaderVisibleAmount, "header visible amount");

            // While mUserLocked, the expandFactor reflects where in the drag-to-expand gesture
            // we are so that we can calculate the intermediary translation needed for the
            // header components. Otherwise, we just set the final desired translation based
            // on whether the group is expanded or not.
            float topLineTranslation = 0, expandButtonTranslation = 0;
            if (mIsUserSwipingToExpandRow) {
                topLineTranslation = mGroupHeader.getTopLineTranslation() * expandFactor;
                expandButtonTranslation =
                        mGroupHeader.getExpandButtonTranslation() * expandFactor;
            } else if (mChildrenExpanded) {
                topLineTranslation = mGroupHeader.getTopLineTranslation();
                expandButtonTranslation = mGroupHeader.getExpandButtonTranslation();
            }

            mTopLineViewState = initStateForGroupHeader(mTopLineViewState);
            mTopLineViewState.setYTranslation(topLineTranslation,
                    "NotificationChildrenContainer.updateState.topLine");

            mExpandButtonViewState = initStateForGroupHeader(mExpandButtonViewState);
            mExpandButtonViewState.setYTranslation(expandButtonTranslation,
                    "NotificationChildrenContainer.updateState.expandButton");
        }
    }

    /**
     * Initialise a new ViewState for the group header or its children, or update and return
     * {@code existingState} if not null.
     */
    private ViewState initStateForGroupHeader(ViewState existingState) {
        ViewState viewState = existingState;
        if (viewState == null) {
            viewState = new ViewState();
        }
        viewState.initFrom(mGroupHeader);
        // The hiding is done automatically by the alpha, otherwise we'll pick it up again
        // in the next frame with the initFrom call above and have an invisible header
        viewState.hidden = false;
        return viewState;
    }

    boolean isBundle() {
        return mBundleHeaderView != null;
    }

    @VisibleForTesting
    int getMaxAllowedVisibleChildren() {
        return getMaxAllowedVisibleChildren(false /* likeCollapsed */);
    }

    @VisibleForTesting
    int getMaxAllowedVisibleChildren(boolean likeCollapsed) {
        if (isBundle()) {
            if (mContainingNotification.isGroupExpanded()
                    || mContainingNotification.isUserSwipingToExpandRow()) {
                return getNumberOfChildrenWhenExpanded();
            } else {
                return getNumberOfChildrenWhenCollapsed();
            }
        }
        if (!likeCollapsed
                && (mChildrenExpanded || mContainingNotification.isUserSwipingToExpandRow())
                && !showingAsLowPriority()) {
            return getNumberOfChildrenWhenExpanded();
        }

        if (mIsMinimized
                || (!mContainingNotification.isOnKeyguard() && mContainingNotification.isExpanded())
                || (mContainingNotification.isHeadsUpState()
                && mContainingNotification.canShowHeadsUp())) {
            return getNumberOfChildrenWhenSystemExpanded();
        }
        return getNumberOfChildrenWhenCollapsed();
    }

    private int getNumberOfChildrenWhenExpanded() {
        if (isBundle()) {
            return NUMBER_OF_CHILDREN_BUNDLE_EXPANDED;
        } else {
            return NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        }
    }

    private int getNumberOfChildrenWhenCollapsed() {
        if (isBundle()) {
            return NUMBER_OF_CHILDREN_BUNDLE_COLLAPSED;
        } else {
            if (getContainingNotification().getEntryAdapter().isBundled()) {
                return NUMBER_OF_CHILDREN_WHEN_COLLAPSED_BUNDLED;
            }
            return NUMBER_OF_CHILDREN_WHEN_COLLAPSED;
        }
    }

    private int getNumberOfChildrenWhenSystemExpanded() {
        if (isBundle()) {
            return NUMBER_OF_CHILDREN_BUNDLE_SYSTEM_EXPANDED;
        } else {
            return NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED;
        }
    }

    /**
     * Applies state to children.
     */
    public void applyState() {
        int childCount = mAttachedChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = 0.0f;
        if (mIsUserSwipingToExpandRow) {
            expandFraction = getGroupExpandFraction();
        }
        final boolean isExpanding = !showingAsLowPriority()
                && (mIsUserSwipingToExpandRow || mContainingNotification.isGroupExpansionChanging());
        final boolean dividersVisible = (mChildrenExpanded && mShowDividersWhenExpanded)
                || (isExpanding && !mHideDividersDuringExpand);
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            ExpandableViewState viewState = child.getViewState();
            viewState.applyToView(child);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.setYTranslation(viewState.getYTranslation() - mDividerHeight,
                    "NotificationChildrenContainer.applyState.divider");
            float alpha = mChildrenExpanded && viewState.getAlpha() != 0 ? mDividerAlpha : 0;
            if (mIsUserSwipingToExpandRow && !showingAsLowPriority() && viewState.getAlpha() != 0) {
                alpha = NotificationUtils.interpolate(0, mDividerAlpha,
                        Math.min(viewState.getAlpha(), expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.setAlpha(alpha, "divider");
            tmpState.applyToView(divider);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mGroupOverFlowState != null) {
            mGroupOverFlowState.applyToView(mOverflowNumber);
            mNeverAppliedGroupState = false;
        }
        if (mHeaderViewState != null) {
            mHeaderViewState.applyToView(mGroupHeader);
        }
        // Only apply the special viewState for the header's children if we're not currently showing
        // the minimized header.
        if (!showingAsLowPriority()) {
            if (mTopLineViewState != null) {
                mTopLineViewState.applyToView(mGroupHeader.getTopLineView());
            }
            if (mExpandButtonViewState != null) {
                mExpandButtonViewState.applyToView(mGroupHeader.getExpandButton());
            }
        }
        updateChildrenClipping();
    }

    private void updateChildrenClipping() {
        if (mContainingNotification.hasExpandingChild()) {
            return;
        }
        int childCount = mAttachedChildren.size();
        int layoutEnd = mContainingNotification.getActualHeight() - mClipBottomAmount;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            float childTop = child.getTranslationY();
            float childBottom = childTop + child.getActualHeight();
            boolean visible = true;
            int clipBottomAmount = 0;
            if (childTop > layoutEnd) {
                visible = false;
            } else if (childBottom > layoutEnd) {
                clipBottomAmount = (int) (childBottom - layoutEnd);
            }

            boolean isVisible = child.getVisibility() == VISIBLE;
            if (visible != isVisible) {
                child.setVisibility(visible ? VISIBLE : INVISIBLE);
            }

            child.setClipBottomAmount(clipBottomAmount);
        }
    }

    @VisibleForTesting
    protected boolean childNeedsExpandedClipPath(ExpandableNotificationRow child) {
        return isBundle() && mChildrenExpanded && child.isSummaryWithChildren();
    }

    @VisibleForTesting
    protected RectF getExpandedClipRect(ExpandableNotificationRow summaryRow) {
        RectF expandedRect;
        float maxAbsChildTranslation = getWidth() / 2.0f;
        expandedRect = new RectF();
        expandedRect.left = -maxAbsChildTranslation;
        expandedRect.top = 0;
        expandedRect.bottom = mContainingNotification.getActualHeight();
        expandedRect.right = summaryRow.getWidth() + maxAbsChildTranslation;
        return expandedRect;
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        boolean isCanvasChanged = false;

        Path clipPath = mChildClipPath;
        if (clipPath != null) {
            final float translation;
            RectF expandClipRect = null;
            if (child instanceof ExpandableNotificationRow notificationRow) {
                translation = notificationRow.getTranslation();
                expandClipRect = mExpandedClipRect.get(notificationRow.getKey());
            } else {
                translation = child.getTranslationX();
            }

            isCanvasChanged = true;
            canvas.save();
            if (translation != 0f) {
                clipPath.offset(translation, 0f);
                canvas.clipPath(clipPath);
                clipPath.offset(-translation, 0f);
            } else {
                if (expandClipRect != null) {
                    // Expand clip path with a rect that has the same height as the parent
                    // but is wider by width/2 so that grand-child translations are not clipped
                    // Only applies to bundle headers
                    clipPath = new Path(clipPath);
                    clipPath.addRect(expandClipRect, Direction.CW);
                }
                canvas.clipPath(clipPath);
            }
        }

        boolean isHeader = child instanceof NotificationHeaderView || (isBundle()
                && child instanceof ComposeView);
        if (isHeader && getRoundableHeaderWrapper().hasRoundedCorner()) {
            float[] radii = getRoundableHeaderWrapper().getUpdatedRadii();
            mHeaderPath.reset();
            mHeaderPath.addRoundRect(
                    child.getLeft(),
                    child.getTop(),
                    child.getRight(),
                    child.getBottom(),
                    radii,
                    Direction.CW
            );
            if (!isCanvasChanged) {
                isCanvasChanged = true;
                canvas.save();
            }
            canvas.clipPath(mHeaderPath);
        }

        if (isCanvasChanged) {
            boolean result = super.drawChild(canvas, child, drawingTime);
            canvas.restore();
            return result;
        } else {
            // If there have been no changes to the canvas we can proceed as usual
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    /**
     * Animate to a given state.
     */
    public void startAnimationToState(AnimationProperties properties) {
        int childCount = mAttachedChildren.size();
        ViewState tmpState = new ViewState();
        float expandFraction = getGroupExpandFraction();
        final boolean isExpansionChanging = !showingAsLowPriority()
                && (mIsUserSwipingToExpandRow || mContainingNotification.isGroupExpansionChanging());
        final boolean dividersVisible = (mChildrenExpanded && mShowDividersWhenExpanded)
                || (isExpansionChanging && !mHideDividersDuringExpand);
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            ExpandableViewState viewState = child.getViewState();
            viewState.animateTo(child, properties);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.setYTranslation(
                    viewState.getYTranslation() - mDividerHeight,
                    "NotificationChildrenContainer.startAnimationToState.divider");
            float alpha = mChildrenExpanded && viewState.getAlpha() != 0 ? mDividerAlpha : 0;
            if (mIsUserSwipingToExpandRow && !showingAsLowPriority() && viewState.getAlpha() != 0) {
                alpha = NotificationUtils.interpolate(0, mDividerAlpha,
                        Math.min(viewState.getAlpha(), expandFraction));
            }
            tmpState.hidden = !dividersVisible;
            tmpState.setAlpha(alpha, "divider");
            tmpState.animateTo(divider, properties);
            // There is no fake shadow to be drawn on the children
            child.setFakeShadowIntensity(0.0f, 0.0f, 0, 0);
        }
        if (mOverflowNumber != null) {
            if (mNeverAppliedGroupState) {
                float oldAlpha = mGroupOverFlowState.getAlpha();
                String oldReason = mGroupOverFlowState.getAlphaReason();
                mGroupOverFlowState.setAlpha(0, "group overflow init");
                mGroupOverFlowState.applyToView(mOverflowNumber);
                mGroupOverFlowState.setAlpha(oldAlpha, oldReason);
                mNeverAppliedGroupState = false;
            }
            mGroupOverFlowState.animateTo(mOverflowNumber, properties);
        }
        if (mGroupHeader != null) {
            if (mHeaderViewState != null) {
                // TODO(389839492): For Groups in Bundles mGroupHeader might be initialized
                //  but mHeaderViewState is null.
                mHeaderViewState.applyToView(mGroupHeader);
            }

            if (mCurrentHeader instanceof NotificationHeaderView groupHeader) {
                if (mTopLineViewState != null) {
                    mTopLineViewState.animateTo(groupHeader.getTopLineView(), properties);
                }
                if (mExpandButtonViewState != null) {
                    mExpandButtonViewState.animateTo(groupHeader.getExpandButton(), properties);
                }
            }
        }
        updateChildrenClipping();
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        // find the view under the pointer, accounting for GONE views
        final int count = mAttachedChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow slidingChild = mAttachedChildren.get(childIdx);
            float childTop = slidingChild.getTranslationY();
            float top = childTop + Math.max(Math.max(0, slidingChild.getClipTopAmount()),
                    slidingChild.getTopOverlap());
            float bottom = childTop + slidingChild.getActualHeight();
            if (y >= top && y <= bottom) {
                return slidingChild.getViewAtPosition(y - top);
            }
        }
        return null;
    }

    public void setChildrenExpanded(boolean childrenExpanded) {
        mChildrenExpanded = childrenExpanded;
        updateExpansionStates();
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setExpanded(childrenExpanded);
        }
        if (mBundleHeaderViewModel != null) {
            mBundleHeaderViewModel.setExpansionState(childrenExpanded);
        }
        final int count = mAttachedChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow child = mAttachedChildren.get(childIdx);
            if (!child.isSummaryWithChildren()) {
                child.setChildrenExpanded(childrenExpanded);
            }
        }
        updateHeaderTouchability();
    }

    public void setContainingNotification(ExpandableNotificationRow parent) {
        mContainingNotification = parent;
        mGroupingUtil = new NotificationGroupingUtil(mContainingNotification);
    }

    public ExpandableNotificationRow getContainingNotification() {
        return mContainingNotification;
    }

    @Nullable
    public NotificationViewWrapper getNotificationViewWrapper() {
        return mGroupHeaderWrapper;
    }

    @Nullable
    public NotificationViewWrapper getMinimizedGroupHeaderWrapper() {
        return mMinimizedGroupHeaderWrapper;
    }

    @Nullable
    public NotificationHeaderView getGroupHeader() {
        return mGroupHeader;
    }

    @Nullable
    public NotificationHeaderView getMinimizedNotificationHeader() {
        return mMinimizedGroupHeader;
    }

    private void updateHeaderVisibility(boolean animate) {
        if (isBundle()) return;
        ViewGroup desiredHeader;
        ViewGroup currentHeader = mCurrentHeader;
        desiredHeader = calculateDesiredHeader();

        if (currentHeader == desiredHeader) {
            return;
        }

        if (desiredHeader == null) {
            return;
        }

        if (animate) {
            if (desiredHeader != null && currentHeader != null) {
                currentHeader.setVisibility(VISIBLE);
                desiredHeader.setVisibility(VISIBLE);
                NotificationViewWrapper visibleWrapper = getWrapperForView(desiredHeader);
                NotificationViewWrapper hiddenWrapper = getWrapperForView(currentHeader);
                visibleWrapper.transformFrom(hiddenWrapper);
                hiddenWrapper.transformTo(visibleWrapper, () -> updateHeaderVisibility(false));
                startChildAlphaAnimations(desiredHeader == mGroupHeader);
            } else {
                animate = false;
            }
        }
        if (!animate) {
            if (desiredHeader != null) {
                getWrapperForView(desiredHeader).setVisible(true);
                desiredHeader.setVisibility(VISIBLE);
            }
            if (currentHeader != null) {
                // Wrapper can be null if we were a low priority notification
                // and just destroyed it by calling setIsLowPriority(false)
                NotificationViewWrapper wrapper = getWrapperForView(currentHeader);
                if (wrapper != null) {
                    wrapper.setVisible(false);
                }
                currentHeader.setVisibility(INVISIBLE);
            }
        }

        resetHeaderVisibilityIfNeeded(mGroupHeader, desiredHeader);
        resetHeaderVisibilityIfNeeded(mMinimizedGroupHeader, desiredHeader);

        mCurrentHeader = desiredHeader;
    }

    private void resetHeaderVisibilityIfNeeded(View header, View desiredHeader) {
        if (header == null) {
            return;
        }
        if (header != mCurrentHeader && header != desiredHeader) {
            getWrapperForView(header).setVisible(false);
            header.setVisibility(INVISIBLE);
        }
        if (header == desiredHeader && header.getVisibility() != VISIBLE) {
            getWrapperForView(header).setVisible(true);
            header.setVisibility(VISIBLE);
        }
    }

    private NotificationHeaderView calculateDesiredHeader() {
        if (showingAsLowPriority()) {
            return mMinimizedGroupHeader;
        } else {
            return mGroupHeader;
        }
    }

    private void startChildAlphaAnimations(boolean toVisible) {
        float target = toVisible ? 1.0f : 0.0f;
        float start = 1.0f - target;
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (i >= NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.setAlpha(start);
            ViewState viewState = new ViewState();
            viewState.initFrom(child);
            viewState.setAlpha(target, "child alpha animation");
            ALPHA_FADE_IN.setDelay(i * 50);
            viewState.animateTo(child, ALPHA_FADE_IN);
        }
    }


    private void updateHeaderTransformation() {
        if (mIsUserSwipingToExpandRow && showingAsLowPriority()) {
            float fraction = getGroupExpandFraction();
            mGroupHeaderWrapper.transformFrom(mMinimizedGroupHeaderWrapper,
                    fraction);
            mGroupHeader.setVisibility(VISIBLE);
            mMinimizedGroupHeaderWrapper.transformTo(mGroupHeaderWrapper,
                    fraction);
        }

    }

    private NotificationViewWrapper getWrapperForView(View visibleHeader) {
        if (visibleHeader == mGroupHeader) {
            return mGroupHeaderWrapper;
        }
        return mMinimizedGroupHeaderWrapper;
    }

    /**
     * Called when a groups expansion changes to adjust the background of the header view.
     *
     * @param expanded whether the group is expanded.
     */
    public void updateHeaderForExpansion(boolean expanded) {
        if (mGroupHeader != null) {
            if (expanded) {
                ColorDrawable cd = new ColorDrawable();
                cd.setColor(mContainingNotification.calculateBgColor());
                mGroupHeader.setHeaderBackgroundDrawable(cd);
            } else {
                mGroupHeader.setHeaderBackgroundDrawable(null);
            }
        }
        if (mBundleHeaderViewModel != null) {
            if (expanded) {
                ColorDrawable cd = new ColorDrawable();
                cd.setColor(mContainingNotification.calculateBgColor());
                mBundleHeaderViewModel.setBackgroundDrawable(cd);
            } else {
                mBundleHeaderViewModel.setBackgroundDrawable(null);
            }
        }
    }

    public String getBundleHeaderDescription() {
        if (mBundleHeaderViewModel != null) {
            return mBundleHeaderViewModel.getHeaderContentDescription();
        }
        return null;
    }

    public int getMaxContentHeight() {
        if (showingAsLowPriority()) {
            return getMinHeight(NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED, true
                    /* likeHighPriority */);
        }
        int maxContentHeight = getCollapsedHeaderMargin() + mCurrentHeaderTranslation
                + getAdditionalExpandedHeaderMargin();
        int visibleChildren = 0;
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED) {
                break;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            maxContentHeight += childHeight;
            visibleChildren++;
        }
        if (visibleChildren > 0) {
            maxContentHeight += visibleChildren * mDividerHeight;
        }
        return maxContentHeight;
    }

    public void setActualHeight(int actualHeight) {
        if (!mIsUserSwipingToExpandRow) {
            return;
        }
        mActualHeight = actualHeight;
        float fraction = getGroupExpandFraction();
        boolean showingLowPriority = showingAsLowPriority();
        updateHeaderTransformation();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            float childHeight;
            if (showingLowPriority) {
                childHeight = child.getShowingLayout().getMinHeight(false /* likeGroupExpanded */);
            } else if (child.isExpanded(true /* allowOnKeyguard */)) {
                childHeight = child.getMaxExpandHeight();
            } else {
                childHeight = child.getShowingLayout().getMinHeight(
                        true /* likeGroupExpanded */);
            }
            if (i < maxAllowedVisibleChildren) {
                float singleLineHeight = child.getShowingLayout().getMinHeight(
                        false /* likeGroupExpanded */);
                childHeight = NotificationUtils.interpolate(singleLineHeight,
                        childHeight, fraction);
                child.setFinalActualHeight((int) childHeight);
            } else {
                child.setFinalActualHeight((int) childHeight);
            }
        }
    }

    public float getGroupExpandFraction() {
        int visibleChildrenExpandedHeight = showingAsLowPriority() ? getMaxContentHeight()
                : getVisibleChildrenExpandHeight();
        int minExpandHeight = getCollapsedHeight();
        float factor = (mActualHeight - minExpandHeight)
                / (float) (visibleChildrenExpandedHeight - minExpandHeight);
        return Math.max(0.0f, Math.min(1.0f, factor));
    }

    private int getVisibleChildrenExpandHeight() {
        int intrinsicHeight = getCollapsedHeaderMargin() + mCurrentHeaderTranslation
                + getAdditionalExpandedHeaderMargin() + mDividerHeight;
        int visibleChildren = 0;
        int childCount = mAttachedChildren.size();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* forceCollapsed */);
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            float childHeight = child.isExpanded(true /* allowOnKeyguard */)
                    ? child.getMaxExpandHeight()
                    : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            intrinsicHeight += childHeight;
            visibleChildren++;
        }
        return intrinsicHeight;
    }

    public int getMinHeight() {
        return getMinHeight(getNumberOfChildrenWhenCollapsed(), false /* likeHighPriority */);
    }

    public int getCollapsedHeight() {
        return getMinHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */),
                false /* likeHighPriority */);
    }

    public int getCollapsedHeightWithoutHeader() {
        return getMinHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */),
                false /* likeHighPriority */, 0);
    }

    /**
     * Get the minimum Height for this group.
     *
     * @param maxAllowedVisibleChildren the number of children that should be visible
     * @param likeHighPriority          if the height should be calculated as if it were not low
     *                                  priority
     */
    private int getMinHeight(int maxAllowedVisibleChildren, boolean likeHighPriority) {
        return getMinHeight(maxAllowedVisibleChildren, likeHighPriority, mCurrentHeaderTranslation);
    }

    /**
     * Get the minimum Height for this group.
     *
     * @param maxAllowedVisibleChildren the number of children that should be visible
     * @param likeHighPriority          if the height should be calculated as if it were not low
     *                                  priority
     * @param headerTranslation         the translation amount of the header
     */
    private int getMinHeight(
            int maxAllowedVisibleChildren,
            boolean likeHighPriority,
            int headerTranslation) {
        if (!likeHighPriority && showingAsLowPriority()) {
            return getHeaderHeight();
        }
        int minExpandHeight = getCollapsedHeaderMargin() + headerTranslation;
        int visibleChildren = 0;
        boolean firstChild = true;
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            if (!firstChild) {
                minExpandHeight += mChildPadding;
            } else {
                firstChild = false;
            }
            ExpandableNotificationRow child = mAttachedChildren.get(i);

            if (child.isSummaryWithChildren()) {
                minExpandHeight += child.isExpanded(true /* allowOnKeyguard */)
                        ? child.getMaxExpandHeight()
                        : child.getShowingLayout().getMinHeight(true /* likeGroupExpanded */);
            } else if (child.getSingleLineView() != null) {
                minExpandHeight += child.getSingleLineView().getHeight();
            } else {
                minExpandHeight += mMinSingleLineHeight;
            }
            visibleChildren++;
        }
        minExpandHeight += mCollapsedBottomPadding;

        if (Flags.notificationChildrenContainerMinHeight()) {
            // The height should at the very minimum be able to accommodate the header.
            return Math.max(minExpandHeight, getHeaderHeight());
        } else {
            return minExpandHeight;
        }
    }

    public boolean showingAsLowPriority() {
        return mIsMinimized && !mContainingNotification.isExpanded();
    }

    public void reInflateViews() {
        initDimens();
        for (int i = 0; i < mDividers.size(); i++) {
            View prevDivider = mDividers.get(i);
            int index = indexOfChild(prevDivider);
            removeView(prevDivider);
            View divider = inflateDivider();
            addView(divider, index);
            mDividers.set(i, divider);
        }
    }

    public void setUserSwipingToExpandRow(boolean isUserSwiping) {
        mIsUserSwipingToExpandRow = isUserSwiping;
        if (!mIsUserSwipingToExpandRow) {
            updateHeaderVisibility(false /* animate */);
        }
        int childCount = mAttachedChildren.size();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            child.setUserSwipingToExpandRow(isUserSwiping && !showingAsLowPriority());
        }
        updateHeaderTouchability();
    }

    private void updateHeaderTouchability() {
        if (mGroupHeader != null) {
            mGroupHeader.setAcceptAllTouches(mChildrenExpanded || mIsUserSwipingToExpandRow);
        }
    }

    public void onNotificationUpdated() {
        if (mShowGroupCountInExpander) {
            // The overflow number is not used, so its color is irrelevant; skip this
            return;
        }
        int color = mContext.getColor(com.android.internal.R.color.materialColorPrimary);
        mHybridGroupManager.setOverflowNumberColor(mOverflowNumber, color);
    }

    public int getPositionInLinearLayout(View childInGroup) {
        int position = getCollapsedHeaderMargin() + mCurrentHeaderTranslation
                + getAdditionalExpandedHeaderMargin();

        for (int i = 0; i < mAttachedChildren.size(); i++) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            boolean notGone = child.getVisibility() != View.GONE;
            if (notGone) {
                position += mDividerHeight;
            }
            if (child == childInGroup) {
                return position;
            }
            if (notGone) {
                position += child.getIntrinsicHeight();
            }
        }
        return 0;
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        updateChildrenClipping();
    }

    /**
     * Set whether the children container is minimized.
     */
    public void setIsMinimized(boolean isMinimized) {
        mIsMinimized = isMinimized;
        if (mContainingNotification != null) { /* we're not yet set up yet otherwise */
            updateHeaderVisibility(false /* animate */);
        }
        if (mIsUserSwipingToExpandRow) {
            setUserSwipingToExpandRow(true);
        }
    }

    /**
     * @return the view wrapper for the currently showing priority.
     */
    public NotificationViewWrapper getVisibleWrapper() {
        if (showingAsLowPriority()) {
            return mMinimizedGroupHeaderWrapper;
        }
        return mGroupHeaderWrapper;
    }

    public void onExpansionChanged() {
        if (mIsMinimized) {
            if (mIsUserSwipingToExpandRow) {
                setUserSwipingToExpandRow(mIsUserSwipingToExpandRow);
            }
            updateHeaderVisibility(true /* animate */);
        }
    }

    @VisibleForTesting
    public boolean isUserSwipingToExpandRow() {
        return mIsUserSwipingToExpandRow;
    }

    @Override
    public void applyRoundnessAndInvalidate() {
        boolean last = true;
        if (getRoundableHeaderWrapper() != null) {
            getRoundableHeaderWrapper().requestTopRoundness(
                    /* value = */ getTopRoundness(),
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false
            );
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.requestTopRoundness(
                    /* value = */ getTopRoundness(),
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false
            );
        }
        for (int i = mAttachedChildren.size() - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mAttachedChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (android.app.Flags.richOngoingImprovements()) {
                child.requestRoundnessReset(
                        NotificationSectionsManager.Companion.getGROUPING_DISABLED_SECTION());
            }
            child.requestRoundness(
                    /* top = */ 0f,
                    /* bottom = */ last ? getBottomRoundness() : 0f,
                    /* sourceType = */ FROM_PARENT,
                    /* animate = */ false);
            last = false;
        }
        Roundable.super.applyRoundnessAndInvalidate();
    }

    public void setHeaderVisibleAmount(float headerVisibleAmount) {
        mHeaderVisibleAmount = headerVisibleAmount;
        mCurrentHeaderTranslation = (int) ((1.0f - headerVisibleAmount) * mTranslationForHeader);
    }

    public void setRecentlyAudiblyAlerted(boolean audiblyAlertedRecently) {
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setRecentlyAudiblyAlerted(audiblyAlertedRecently);
        }
    }

    @Override
    public void setNotificationFaded(boolean faded) {
        mContainingNotificationIsFaded = faded;
        if (mGroupHeaderWrapper != null) {
            mGroupHeaderWrapper.setNotificationFaded(faded);
        }
        if (mMinimizedGroupHeaderWrapper != null) {
            mMinimizedGroupHeaderWrapper.setNotificationFaded(faded);
        }
        for (ExpandableNotificationRow child : mAttachedChildren) {
            child.setNotificationFaded(faded);
        }
    }

    /**
     * Allow to define a path the clip the children in #drawChild()
     *
     * @param childClipPath path used to clip the children
     */
    public void setChildClipPath(@Nullable Path childClipPath) {
        mChildClipPath = childClipPath;
        invalidate();
    }

    // Returns the Roundable notification header wrapper, generalized to handle either bundle
    // headers or group headers.
    public Roundable getRoundableHeaderWrapper() {
        return isBundle() ? mBundleHeaderWrapper : mGroupHeaderWrapper;
    }

    public void setBundleHeaderOnClickListener(OnClickListener l) {
        mBundleHeaderView.setOnClickListener(l);
    }

    public NotificationHeaderViewWrapper getNotificationHeaderWrapper() {
        return mGroupHeaderWrapper;
    }

    public void setLogger(NotificationChildrenContainerLogger logger) {
        mLogger = logger;
    }

    @Override
    public void addTransientView(View view, int index) {
        if (mLogger != null && view instanceof ExpandableNotificationRow) {
            mLogger.addTransientRow(
                    ((ExpandableNotificationRow) view).getLoggingKey(),
                    getContainingNotification().getLoggingKey(),
                    index
            );
        }
        super.addTransientView(view, index);
    }

    @Override
    public void removeTransientView(View view) {
        if (mLogger != null && view instanceof ExpandableNotificationRow) {
            mLogger.removeTransientRow(
                    ((ExpandableNotificationRow) view).getLoggingKey(),
                    getContainingNotification().getLoggingKey()
            );
        }
        super.removeTransientView(view);
    }

    public String debugString() {
        return TAG + " { "
                + "visibility: " + getVisibility()
                + ", alpha: " + getAlpha()
                + ", translationY: " + getTranslationY()
                + ", clipBounds: " + getClipBounds()
                + ", roundableState: " + getRoundableState().debugString() + "}";
    }

    public void setOnKeyguard(boolean onKeyguard) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        for (ExpandableNotificationRow child : mAttachedChildren) {
            child.setOnKeyguard(onKeyguard);
        }
    }

    /** Propagates blur region suppression to the group's child rows. */
    public void setBlurRegionSuppressed(boolean suppressed) {
        for (ExpandableNotificationRow child : mAttachedChildren) {
            child.setBlurRegionSuppressed(suppressed);
        }
    }

    /**
     * Spacing needed in addition to {@link #mCollapsedHeaderMargin} when the group is expanded, to
     * accommodate the full header. It doesn't include the spacing needed for the first divider.
     */
    private int getAdditionalExpandedHeaderMargin() {
        return mAdditionalExpandedHeaderMargin;
    }

    private int getHeaderHeight() {
        return isBundle() ? mBundleHeaderView.getMeasuredHeight() : mHeaderHeight;
    }

    /** Margin needed at the top in the collapsed group, to allow space for the header text. */
    private int getCollapsedHeaderMargin() {
        return isBundle() ? getHeaderHeight() : mCollapsedHeaderMargin;
    }
}
