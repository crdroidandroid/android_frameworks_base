package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.AdapterListUpdateCallback;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.res.R;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
/* loaded from: classes2.dex */
public final class CardRecyclerViewAdapter
        extends RecyclerView.Adapter<CardRecyclerViewAdapter.ViewHolder> {
    public static final Set<Integer> legacySecondaryCardResourceIdSet =
            BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP.values().stream()
                    .collect(Collectors.toSet());
    public static final Set<Integer> templateSecondaryCardResourceIdSet =
            BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES.values().stream()
                    .collect(Collectors.toSet());
    public final List<SmartspaceTarget> _aodTargets;
    public float dozeAmount;
    public boolean _isBackgroundEnabled;
    public final List<SmartspaceTarget> _lockscreenTargets;
    public final Drawable backgroundDrawable;
    public final Drawable backgroundOutlineDrawable;
    public Handler bgHandler;
    public final int bgNonRemoteViewsHorizontalPadding;
    public BcSmartspaceConfigPlugin configProvider;
    public Drawable currentBackgroundDrawable;
    public int currentTextColor;
    public BcSmartspaceDataPlugin dataProvider;
    public final int defaultNonRemoteViewsPaddingStart;
    public final int dozeColor;
    public boolean hasAodLockscreenTransition;
    public boolean hasDifferentTargets;
    public boolean keyguardBypassEnabled;
    public final AsyncListDiffer<SmartspaceTarget> mDiffer;
    public final List<SmartspaceTarget> mediaTargets;
    public Integer nonRemoteViewsHorizontalPadding;
    public float previousDozeAmount;
    public int primaryTextColor;
    public final BcSmartspaceView root;
    public List<SmartspaceTarget> smartspaceTargets;
    public final GradientDrawable solidBackgroundDrawable;
    public final int textColorOnBg;
    public BcSmartspaceDataPlugin.TimeChangedDelegate timeChangedDelegate;
    public TransitionType transitioningTo;
    public String uiSurface;
    public final SparseArray<ViewHolder> viewHolders;
    public final ViewPager2 viewPager2;

    /* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
    public final class DiffUtilItemCallback extends DiffUtil.ItemCallback<SmartspaceTarget> {
        @Override // androidx.recyclerview.widget.DiffUtil.Callback
        public final boolean areItemsTheSame(SmartspaceTarget oldItem, SmartspaceTarget newItem) {
            return oldItem.getSmartspaceTargetId().equals(newItem.getSmartspaceTargetId());
        }

        @Override // androidx.recyclerview.widget.DiffUtil.Callback
        public final boolean areContentsTheSame(
                SmartspaceTarget oldItem, SmartspaceTarget newItem) {
            return false;
        }
    }

    public enum TransitionType {
        NOT_IN_TRANSITION,
        TO_LOCKSCREEN,
        TO_AOD
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public SmartspaceCard card;

        public final void setBackground(Drawable drawable) {
            if (itemView instanceof BcSmartspaceRemoteViewsCard) {
                return;
            }
            ViewGroup viewGroup = itemView instanceof ViewGroup ? (ViewGroup) itemView : null;
            View childAt = viewGroup != null ? viewGroup.getChildAt(0) : null;
            if (childAt != null) {
                childAt.setBackground(drawable);
            }
        }

        public ViewHolder(View itemView) {
            super(itemView);
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    public CardRecyclerViewAdapter(BcSmartspaceView root, BcSmartspaceConfigPlugin configProvider) {
        DiffUtilItemCallback diffUtilItemCallback = new DiffUtilItemCallback();
        AsyncDifferConfig<SmartspaceTarget> asyncDifferConfig =
                new AsyncDifferConfig.Builder<SmartspaceTarget>(diffUtilItemCallback).build();
        mDiffer = new AsyncListDiffer<>(new AdapterListUpdateCallback(this), asyncDifferConfig);
        mDiffer.addListListener((previousList, currentList) -> {});
        this.root = root;
        View view = root.findViewById(R.id.smartspace_card_pager);
        GradientDrawable gradientDrawable = null;
        viewPager2 = view instanceof ViewPager2 ? (ViewPager2) view : null;
        viewHolders = new SparseArray<>();
        backgroundOutlineDrawable =
                root.getContext().getDrawable(R.drawable.bg_non_remoteviews_card_outline);
        backgroundDrawable = root.getContext().getDrawable(R.drawable.bg_non_remoteviews_card);
        try {
            gradientDrawable = getSolidBackgroundDrawable();
        } catch (IllegalStateException e) {
            Log.w("SsCardRecyclerViewAdapter", "Failed to get solid background drawable", e);
        }
        solidBackgroundDrawable = gradientDrawable;
        smartspaceTargets = new ArrayList<>();
        _aodTargets = new ArrayList<>();
        _lockscreenTargets = new ArrayList<>();
        mediaTargets = new ArrayList<>();
        dozeColor = -1;
        int attrColor =
                GraphicsUtils.getAttrColor(root.getContext(), android.R.attr.textColorPrimary);
        primaryTextColor = attrColor;
        textColorOnBg = -1;
        currentTextColor = _isBackgroundEnabled ? -1 : attrColor;
        configProvider = configProvider;
        bgNonRemoteViewsHorizontalPadding =
                root.getContext()
                        .getResources()
                        .getDimensionPixelSize(R.dimen.bg_non_remoteviews_card_padding_horizontal);
        defaultNonRemoteViewsPaddingStart =
                root.getContext()
                        .getResources()
                        .getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start);
        transitioningTo = TransitionType.NOT_IN_TRANSITION;
    }

    public static boolean isTemplateCard(SmartspaceTarget target) {
        return target.getTemplateData() != null
                && BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
    }

    public final void addDefaultDateCardIfEmpty(List<SmartspaceTarget> targets) {
        if (targets.isEmpty()) {
            targets.add(
                    new SmartspaceTarget.Builder(
                                    "date_card_794317_92634",
                                    new ComponentName(
                                            root.getContext(), CardRecyclerViewAdapter.class),
                                    root.getContext().getUser())
                            .setFeatureType(1)
                            .setTemplateData(new BaseTemplateData.Builder(1).build())
                            .build());
        }
    }

    @Override
    public final int getItemCount() {
        return mDiffer.getCurrentList().size();
    }

    @Override
    public final int getItemViewType(int position) {
        SmartspaceTarget target = mDiffer.getCurrentList().get(position);
        BaseTemplateData templateData = target.getTemplateData();
        if (target.getRemoteViews() != null) {
            return target.getRemoteViews().getLayoutId();
        }
        if (!isTemplateCard(target)) {
            Integer layoutId =
                    (Integer)
                            BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP.get(
                                    BcSmartSpaceUtil.getFeatureType(target));
            return layoutId != null ? layoutId : R.layout.smartspace_card;
        }
        BaseTemplateData.SubItemInfo primaryItem = templateData.getPrimaryItem();
        if (primaryItem == null) {
            return R.layout.smartspace_base_template_card_with_date;
        }
        if (SmartspaceUtils.isEmpty(primaryItem.getText()) && primaryItem.getIcon() == null) {
            return R.layout.smartspace_base_template_card_with_date;
        }
        BaseTemplateData.SubItemLoggingInfo loggingInfo = primaryItem.getLoggingInfo();
        if (loggingInfo != null && loggingInfo.getFeatureType() == 1) {
            return R.layout.smartspace_base_template_card_with_date;
        }
        Integer layoutId =
                (Integer)
                        BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES.get(
                                templateData.getTemplateType());
        return layoutId != null ? layoutId : R.layout.smartspace_base_template_card;
    }

    public BcSmartspaceCard getLegacyCardAtPosition(int position) {
        SmartspaceCard card =
                viewHolders.get(position) != null ? viewHolders.get(position).card : null;
        return card instanceof BcSmartspaceCard ? (BcSmartspaceCard) card : null;
    }

    public final int getNonRemoteViewsPaddingEnd() {
        if (_isBackgroundEnabled) {
            return bgNonRemoteViewsHorizontalPadding;
        }
        if (nonRemoteViewsHorizontalPadding == null) {
            return 0;
        }
        return nonRemoteViewsHorizontalPadding;
    }

    public final int getNonRemoteViewsPaddingStart() {
        if (_isBackgroundEnabled) {
            return bgNonRemoteViewsHorizontalPadding;
        }
        if (nonRemoteViewsHorizontalPadding == null) {
            return defaultNonRemoteViewsPaddingStart;
        }
        return nonRemoteViewsHorizontalPadding;
    }

    public final GradientDrawable getSolidBackgroundDrawable() {
        if (solidBackgroundDrawable != null) {
            return solidBackgroundDrawable;
        }
        if (backgroundDrawable == null) {
            throw new IllegalStateException("Background drawable is null");
        }
        if (!(backgroundDrawable instanceof LayerDrawable)) {
            throw new IllegalStateException("Background drawable isn't a LayerDrawable");
        }
        Drawable findDrawableByLayerId =
                ((LayerDrawable) backgroundDrawable).findDrawableByLayerId(R.id.solid);
        if (findDrawableByLayerId == null) {
            throw new IllegalStateException("Solid background drawable is null");
        }
        if (findDrawableByLayerId instanceof GradientDrawable) {
            return (GradientDrawable) findDrawableByLayerId;
        }
        throw new IllegalStateException("Solid background drawable isn't a LayerDrawable");
    }

    public SmartspaceTarget getTargetAtPosition(int position) {
        if (position < 0 || position >= getItemCount()) {
            return null;
        }
        return mDiffer.getCurrentList().get(position);
    }

    public BaseTemplateCard getTemplateCardAtPosition(int position) {
        SmartspaceCard card =
                viewHolders.get(position) != null ? viewHolders.get(position).card : null;
        return card instanceof BaseTemplateCard ? (BaseTemplateCard) card : null;
    }

    public final boolean needToSetToLockscreenTargets() {
        if (dozeAmount == 0.0f) {
            return true;
        }
        return 1.0f - dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_LOCKSCREEN;
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public final void onBindViewHolder(ViewHolder holder, int position) {
        SmartspaceTarget target = mDiffer.getCurrentList().get(position);
        boolean isTemplateCard = isTemplateCard(target);
        BcSmartspaceCardLoggingInfo.Builder loggingInfoBuilder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setInstanceId(InstanceId.create(target))
                        .setFeatureType(target.getFeatureType())
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(uiSurface, dozeAmount))
                        .setRank(position)
                        .setCardinality(smartspaceTargets.size())
                        .setUid(-1);
        if (isTemplateCard) {
            loggingInfoBuilder.setSubcardInfo(
                    BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(target.getTemplateData()));
        } else {
            loggingInfoBuilder.setSubcardInfo(
                    BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(target));
        }
        loggingInfoBuilder.setDimensionalInfo(
                BcSmartspaceCardLoggerUtil.createDimensionalLoggingInfo(target.getTemplateData()));
        BcSmartspaceCardLoggingInfo loggingInfo =
                new BcSmartspaceCardLoggingInfo(loggingInfoBuilder);
        SmartspaceCard card = holder.card;
        if (target.getRemoteViews() != null) {
            if (!(card instanceof BcSmartspaceRemoteViewsCard)) {
                Log.w("SsCardRecyclerViewAdapter", "[rmv] No RemoteViews card view can be binded");
                return;
            }
            Log.d("SsCardRecyclerViewAdapter", "[rmv] Refreshing RemoteViews card");
        } else if (isTemplateCard) {
            if (target.getTemplateData() == null) {
                throw new IllegalStateException("Required value was null.");
            }
            BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureTypeOrUpdateLogInfoFromTemplateData(
                    loggingInfo, target.getTemplateData());
            if (!(card instanceof BaseTemplateCard)) {
                Log.w("SsCardRecyclerViewAdapter", "No ui-template card view can be binded");
                return;
            }
            BaseTemplateCard baseTemplateCard = (BaseTemplateCard) card;
            baseTemplateCard.mBgHandler = bgHandler;
            IcuDateTextView icuDateTextView = baseTemplateCard.mDateView;
            if (icuDateTextView != null) {
                icuDateTextView.mBgHandler = bgHandler;
            }
            baseTemplateCard.setPaddingRelative(
                    getNonRemoteViewsPaddingStart(),
                    baseTemplateCard.getPaddingTop(),
                    getNonRemoteViewsPaddingEnd(),
                    baseTemplateCard.getPaddingBottom());
        } else if (!(card instanceof BcSmartspaceCard)) {
            Log.w("SsCardRecyclerViewAdapter", "No legacy card view can be binded");
            return;
        } else {
            BcSmartspaceCard bcSmartspaceCard = (BcSmartspaceCard) card;
            bcSmartspaceCard.setPaddingRelative(
                    getNonRemoteViewsPaddingStart(),
                    bcSmartspaceCard.getPaddingTop(),
                    getNonRemoteViewsPaddingEnd(),
                    bcSmartspaceCard.getPaddingBottom());
        }
        card.bindData(
                target,
                dataProvider != null ? dataProvider.getEventNotifier() : null,
                loggingInfo,
                smartspaceTargets.size() > 1);
        holder.setBackground(_isBackgroundEnabled ? currentBackgroundDrawable : null);
        card.setPrimaryTextColor(currentTextColor);
        card.setDozeAmount(dozeAmount);
        viewHolders.put(position, holder);
    }

    @Override
    public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        SmartspaceCard card;
        Integer secondaryCardResId = null;
        FrameLayout frameLayout;
        if (templateSecondaryCardResourceIdSet.contains(viewType)
                || viewType == R.layout.smartspace_base_template_card_with_date
                || viewType == R.layout.smartspace_base_template_card) {
            if (templateSecondaryCardResourceIdSet.contains(viewType)) {
                secondaryCardResId = viewType;
                viewType = R.layout.smartspace_base_template_card;
            }
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            BaseTemplateCard templateCard =
                    (BaseTemplateCard) inflater.inflate(viewType, parent, false);
            templateCard.mUiSurface = uiSurface;
            if (templateCard.mDateView != null
                    && TextUtils.equals(
                            uiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
                if (templateCard.mDateView.isAttachedToWindow()) {
                    throw new IllegalStateException("Must call before attaching view to window.");
                }
                templateCard.mDateView.mUpdatesOnAod = true;
            }
            if (templateCard.mDateView != null) {
                if (templateCard.mDateView.isAttachedToWindow()) {
                    throw new IllegalStateException("Must call before attaching view to window.");
                }
                templateCard.mDateView.mTimeChangedDelegate = timeChangedDelegate;
            }
            templateCard.setLayoutParams(
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            if (secondaryCardResId != null) {
                BcSmartspaceCardSecondary bcSmartspaceCardSecondary =
                        (BcSmartspaceCardSecondary)
                                inflater.inflate(
                                        secondaryCardResId, (ViewGroup) templateCard, false);
                Log.i("SsCardRecyclerViewAdapter", "Secondary card is found");
                ViewGroup viewGroup = templateCard.mSecondaryCardPane;

                if (viewGroup != null) {
                    templateCard.mSecondaryCard = bcSmartspaceCardSecondary;
                    BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup, View.GONE);
                    templateCard.mSecondaryCardPane.removeAllViews();
                    if (bcSmartspaceCardSecondary != null) {
                        ConstraintLayout.LayoutParams layoutParams =
                                new ConstraintLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        templateCard
                                                .getResources()
                                                .getDimensionPixelSize(
                                                        R.dimen.enhanced_smartspace_card_height));
                        layoutParams.setMarginStart(
                                templateCard
                                        .getResources()
                                        .getDimensionPixelSize(
                                                R.dimen
                                                        .enhanced_smartspace_secondary_card_start_margin));
                        layoutParams.startToStart = 0;
                        layoutParams.topToTop = 0;
                        layoutParams.bottomToBottom = 0;
                        templateCard.mSecondaryCardPane.addView(
                                bcSmartspaceCardSecondary, layoutParams);
                    }
                }
            }
            card = templateCard;
        } else {
            if (legacySecondaryCardResourceIdSet.contains(viewType)
                    || viewType == R.layout.smartspace_card) {
                if (legacySecondaryCardResourceIdSet.contains(viewType)) {
                    secondaryCardResId = viewType;
                    viewType = R.layout.smartspace_card;
                }
                LayoutInflater inflater = LayoutInflater.from(parent.getContext());
                BcSmartspaceCard legacyCard =
                        (BcSmartspaceCard) inflater.inflate(viewType, parent, false);
                legacyCard.mUiSurface = uiSurface;
                legacyCard.setLayoutParams(
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                if (secondaryCardResId != null) {
                    BcSmartspaceCardSecondary bcSmartspaceCardSecondary =
                            (BcSmartspaceCardSecondary)
                                    inflater.inflate(
                                            secondaryCardResId, (ViewGroup) legacyCard, false);
                    ViewGroup viewGroup = legacyCard.mSecondaryCardGroup;
                    if (viewGroup != null) {
                        legacyCard.mSecondaryCard = bcSmartspaceCardSecondary;
                        BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup, View.GONE);
                        legacyCard.mSecondaryCardGroup.removeAllViews();
                        if (bcSmartspaceCardSecondary != null) {
                            ConstraintLayout.LayoutParams layoutParams =
                                    new ConstraintLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            legacyCard
                                                    .getResources()
                                                    .getDimensionPixelSize(
                                                            R.dimen
                                                                    .enhanced_smartspace_card_height));
                            layoutParams.setMarginStart(
                                    legacyCard
                                            .getResources()
                                            .getDimensionPixelSize(
                                                    R.dimen
                                                            .enhanced_smartspace_secondary_card_start_margin));
                            layoutParams.startToStart = 0;
                            layoutParams.topToTop = 0;
                            layoutParams.bottomToBottom = 0;
                            legacyCard.mSecondaryCardGroup.addView(
                                    bcSmartspaceCardSecondary, layoutParams);
                        }
                    }
                }
                card = legacyCard;
            } else {
                BcSmartspaceRemoteViewsCard remoteViewsCard =
                        new BcSmartspaceRemoteViewsCard(parent.getContext());
                remoteViewsCard.mUiSurface = uiSurface;
                remoteViewsCard.setLayoutParams(
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                card = remoteViewsCard;
            }
        }
        if (card instanceof BcSmartspaceRemoteViewsCard) {
            frameLayout = (BcSmartspaceRemoteViewsCard) card;
        } else {
            frameLayout = new FrameLayout(parent.getContext());
            frameLayout.setLayoutParams(
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            View view = new View(parent.getContext());
            ViewGroup.MarginLayoutParams marginLayoutParams =
                    new ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
            marginLayoutParams.topMargin =
                    view.getContext()
                            .getResources()
                            .getDimensionPixelSize(R.dimen.background_top_padding);
            marginLayoutParams.bottomMargin =
                    view.getContext()
                            .getResources()
                            .getDimensionPixelSize(R.dimen.background_bottom_padding);
            view.setLayoutParams(marginLayoutParams);
            frameLayout.addView(view);
            frameLayout.addView(card.getView());
        }
        ViewHolder viewHolder = new ViewHolder(frameLayout);
        viewHolder.card = card;
        return viewHolder;
    }

    public final void refreshCardBackground() {
        for (int i = 0; i < viewHolders.size(); i++) {
            ViewHolder viewHolder = (ViewHolder) viewHolders.get(viewHolders.keyAt(i));
            if (viewHolder != null) {
                viewHolder.setBackground(_isBackgroundEnabled ? currentBackgroundDrawable : null);
            }
        }
    }

    public final void refreshCardPaddings() {
        int nonRemoteViewsPaddingStart = getNonRemoteViewsPaddingStart();
        int nonRemoteViewsPaddingEnd = getNonRemoteViewsPaddingEnd();
        for (int i = 0; i < viewHolders.size(); i++) {
            BcSmartspaceCard legacyCardAtPosition = getLegacyCardAtPosition(viewHolders.keyAt(i));
            if (legacyCardAtPosition != null) {
                legacyCardAtPosition.setPaddingRelative(
                        nonRemoteViewsPaddingStart,
                        legacyCardAtPosition.getPaddingTop(),
                        nonRemoteViewsPaddingEnd,
                        legacyCardAtPosition.getPaddingBottom());
            }
            BaseTemplateCard templateCardAtPosition =
                    getTemplateCardAtPosition(viewHolders.keyAt(i));
            if (templateCardAtPosition != null) {
                templateCardAtPosition.setPaddingRelative(
                        nonRemoteViewsPaddingStart,
                        templateCardAtPosition.getPaddingTop(),
                        nonRemoteViewsPaddingEnd,
                        templateCardAtPosition.getPaddingBottom());
            }
        }
    }

    public void setMediaTarget(SmartspaceTarget target) {
        mediaTargets.clear();
        if (target != null) {
            mediaTargets.add(target);
        }
        updateTargetVisibility(null, true);
    }

    public void updateCurrentTextColor() {
        currentTextColor =
                ColorUtils.blendARGB(
                        _isBackgroundEnabled ? textColorOnBg : primaryTextColor,
                        dozeColor,
                        dozeAmount);
        for (int i = 0; i < viewHolders.size(); i++) {
            ViewHolder holder = viewHolders.get(viewHolders.keyAt(i));
            if (holder != null) {
                holder.card.setPrimaryTextColor(currentTextColor);
                holder.card.setDozeAmount(dozeAmount);
            }
        }
    }

    public final void updateTargetVisibility(Runnable runnable, boolean force) {
        List<SmartspaceTarget> aodTargets =
                !mediaTargets.isEmpty()
                        ? mediaTargets
                        : (hasDifferentTargets ? _aodTargets : _lockscreenTargets);

        List<SmartspaceTarget> lockscreenTargets =
                (mediaTargets.isEmpty() || !keyguardBypassEnabled)
                        ? _lockscreenTargets
                        : mediaTargets;

        List<SmartspaceTarget> currentTargets = smartspaceTargets;

        boolean showAodTargets =
                dozeAmount == 1.0f
                        || (dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_AOD);

        List<SmartspaceTarget> newTargets = currentTargets;
        if (showAodTargets) {
            if (currentTargets != aodTargets) {
                Log.d(
                        "SsCardRecyclerViewAdapter",
                        "Updating Smartspace targets to targets for AOD");
                newTargets = aodTargets;
            }
        } else if (needToSetToLockscreenTargets()) {
            if (currentTargets != lockscreenTargets) {
                Log.d(
                        "SsCardRecyclerViewAdapter",
                        "Updating Smartspace targets to targets for Lockscreen");
                newTargets = lockscreenTargets;
            }
        }

        if (newTargets != currentTargets || force) {
            smartspaceTargets = newTargets;
            viewHolders.clear();
            mDiffer.submitList(new ArrayList<>(smartspaceTargets), runnable);
        }

        hasAodLockscreenTransition = aodTargets != lockscreenTargets;
        BcSmartspaceTemplateDataUtils.updateVisibility(
                root, smartspaceTargets.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public final void setTargets(List<SmartspaceTarget> list, Runnable runnable) {
        Bundle extras;
        _aodTargets.clear();
        _lockscreenTargets.clear();
        hasDifferentTargets = false;
        Iterator<SmartspaceTarget> it = list.iterator();
        while (it.hasNext()) {
            SmartspaceTarget smartspaceTarget = it.next();
            if (smartspaceTarget.getFeatureType() == 34
                    || (smartspaceTarget.getRemoteViews() == null
                            && !isTemplateCard(smartspaceTarget)
                            && smartspaceTarget.getFeatureType() == 1)) {
                Log.e(
                        "SsCardRecyclerViewAdapter",
                        "No card can be created for target: " + smartspaceTarget.getFeatureType());
            } else {
                SmartspaceAction baseAction = smartspaceTarget.getBaseAction();

                int screenExtra =
                        (baseAction == null || (extras = baseAction.getExtras()) == null)
                                ? 3
                                : extras.getInt("SCREEN_EXTRA", 3);

                if ((screenExtra & 2) != 0) {
                    _aodTargets.add(smartspaceTarget);
                }
                if ((screenExtra & 1) != 0) {
                    _lockscreenTargets.add(smartspaceTarget);
                }
                if (screenExtra != 3) {
                    hasDifferentTargets = true;
                }
            }
        }

        if (BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN.equals(uiSurface)) {
            addDefaultDateCardIfEmpty(_aodTargets);
            addDefaultDateCardIfEmpty(_lockscreenTargets);
        }

        updateTargetVisibility(runnable, true);
    }
}
