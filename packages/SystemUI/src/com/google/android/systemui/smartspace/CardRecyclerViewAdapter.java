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

import androidx.recyclerview.widget.AdapterListUpdateCallback;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
/* loaded from: classes2.dex */
public final class CardRecyclerViewAdapter
        extends RecyclerView.Adapter<CardRecyclerViewAdapter.ViewHolder> implements CardAdapter {
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
    public BcSmartspaceDataPlugin.TimeChangedDelegate timeChangedDelegate;
    public TransitionType transitioningTo;
    public String uiSurface;
    public final SparseArray<ViewHolder> viewHolders;

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
        GradientDrawable gradientDrawable;
        DiffUtilItemCallback diffUtilItemCallback = new DiffUtilItemCallback();
        AsyncDifferConfig<SmartspaceTarget> asyncDifferConfig =
                new AsyncDifferConfig.Builder<SmartspaceTarget>(diffUtilItemCallback).build();
        mDiffer = new AsyncListDiffer<>(new AdapterListUpdateCallback(this), asyncDifferConfig);
        mDiffer.addListListener((previousList, currentList) -> {});
        this.root = root;
        viewHolders = new SparseArray<>();
        backgroundOutlineDrawable =
                root.getContext().getDrawable(R.drawable.bg_non_remoteviews_card_outline);
        backgroundDrawable = root.getContext().getDrawable(R.drawable.bg_non_remoteviews_card);
        try {
            gradientDrawable = getSolidBackgroundDrawable();
        } catch (IllegalStateException e) {
            Log.w("SsCardRecyclerViewAdapter", "Failed to get solid background drawable", e);
            gradientDrawable = null;
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
        currentTextColor = attrColor;
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
    public final SmartspaceCard getCardAtPosition(int position) {
        ViewHolder holder = viewHolders.get(position);
        if (holder != null) {
            return holder.card;
        }
        return null;
    }

    @Override
    public final int getCount() {
        return smartspaceTargets.size();
    }

    @Override
    public final float getDozeAmount() {
        return dozeAmount;
    }

    @Override
    public final boolean getHasAodLockscreenTransition() {
        return hasAodLockscreenTransition;
    }

    @Override
    public final boolean getHasDifferentTargets() {
        return hasDifferentTargets;
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

    @Override
    public BcSmartspaceCard getLegacyCardAtPosition(int position) {
        SmartspaceCard card = getCardAtPosition(position);
        return card instanceof BcSmartspaceCard ? (BcSmartspaceCard) card : null;
    }

    @Override
    public List<SmartspaceTarget> getLockscreenTargets() {
        return (mediaTargets.isEmpty() || !keyguardBypassEnabled)
                ? _lockscreenTargets
                : mediaTargets;
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

    @Override
    public BcSmartspaceRemoteViewsCard getRemoteViewsCardAtPosition(int position) {
        SmartspaceCard card = getCardAtPosition(position);
        return card instanceof BcSmartspaceRemoteViewsCard
                ? (BcSmartspaceRemoteViewsCard) card
                : null;
    }

    @Override
    public List<SmartspaceTarget> getSmartspaceTargets() {
        return smartspaceTargets;
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

    @Override
    public SmartspaceTarget getTargetAtPosition(int position) {
        if (position < 0 || position >= getItemCount()) {
            return null;
        }
        return mDiffer.getCurrentList().get(position);
    }

    @Override
    public BaseTemplateCard getTemplateCardAtPosition(int position) {
        SmartspaceCard card = getCardAtPosition(position);
        return card instanceof BaseTemplateCard ? (BaseTemplateCard) card : null;
    }

    @Override
    public String getUiSurface() {
        return uiSurface;
    }

    public final boolean needToSetToLockscreenTargets() {
        if (dozeAmount == 0.0f) {
            return true;
        }
        return 1.0f - dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_LOCKSCREEN;
    }

    @Override
    public final void onBackgroundToggled(boolean z) {
        _isBackgroundEnabled = z;
        refreshCardBackground();
        refreshCardPaddings();
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
                templateCard.setSecondaryCard(bcSmartspaceCardSecondary);
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
                    legacyCard.setSecondaryCard(
                            (BcSmartspaceCardSecondary)
                                    inflater.inflate(
                                            secondaryCardResId, (ViewGroup) legacyCard, false));
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
                    parent.getContext()
                            .getResources()
                            .getDimensionPixelSize(R.dimen.background_top_padding);
            marginLayoutParams.bottomMargin =
                    parent.getContext()
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

    public final void resetListIfNeeded() {
        if (root != null && root.getSelectedPage() != 0) {
            return;
        }
        if ((root != null ? root.mScrollState : 0) != 0) {
            return;
        }
        if (mDiffer.getCurrentList().size() == 1 && smartspaceTargets.size() == 1) {
            return;
        }
        String smartspaceTargetId =
                mDiffer.getCurrentList().stream()
                        .findFirst()
                        .map(SmartspaceTarget::getSmartspaceTargetId)
                        .orElse(null);
        String smartspaceTargetId2 =
                smartspaceTargets.stream()
                        .findFirst()
                        .map(SmartspaceTarget::getSmartspaceTargetId)
                        .orElse(null);
        if (Objects.equals(smartspaceTargetId, smartspaceTargetId2)) {
            return;
        }
        mDiffer.submitList(null, null);
    }

    @Override
    public final void setBgHandler(Handler handler) {
        bgHandler = handler;
    }

    @Override
    public final void setConfigProvider(BcSmartspaceConfigPlugin configProvider) {
        this.configProvider = configProvider;
    }

    @Override
    public final void setDataProvider(BcSmartspaceDataPlugin dataProvider) {
        this.dataProvider = dataProvider;
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
        this.dozeAmount = dozeAmount;
        transitioningTo =
                previousDozeAmount > dozeAmount
                        ? TransitionType.TO_LOCKSCREEN
                        : previousDozeAmount < dozeAmount
                                ? TransitionType.TO_AOD
                                : TransitionType.NOT_IN_TRANSITION;
        previousDozeAmount = dozeAmount;
        updateTargetVisibility(null, false);

        if (dozeAmount == 1.0f
                || (dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_AOD)) {
            if (currentBackgroundDrawable != backgroundOutlineDrawable) {
                currentBackgroundDrawable = backgroundOutlineDrawable;
                refreshCardBackground();
            }
        } else if (currentBackgroundDrawable != backgroundDrawable
                && needToSetToLockscreenTargets()) {
            currentBackgroundDrawable = backgroundDrawable;
            refreshCardBackground();
        }

        updateCurrentTextColor();
    }

    @Override
    public final void setKeyguardBypassEnabled(boolean enabled) {
        keyguardBypassEnabled = enabled;
        updateTargetVisibility(null, false);
    }

    @Override
    public void setMediaTarget(SmartspaceTarget target) {
        mediaTargets.clear();
        if (target != null) {
            mediaTargets.add(target);
        }
        updateTargetVisibility(null, true);
    }

    @Override
    public final void setNonRemoteViewsHorizontalPadding(Integer padding) {
        nonRemoteViewsHorizontalPadding = padding;
        if (!_isBackgroundEnabled) {
            refreshCardPaddings();
        }
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        primaryTextColor = color;
        updateCurrentTextColor();
    }

    @Override
    public void setScreenOn(boolean screenOn) {
        for (int i = 0; i < viewHolders.size(); i++) {
            ViewHolder holder = viewHolders.get(viewHolders.keyAt(i));
            if (holder != null) {
                holder.card.setScreenOn(screenOn);
            }
        }
    }

    @Override
    public final void setTargets(List<SmartspaceTarget> targets) {
        setTargets(targets, null);
    }

    @Override
    public final void setTimeChangedDelegate(BcSmartspaceDataPlugin.TimeChangedDelegate delegate) {
        timeChangedDelegate = delegate;
    }

    @Override
    public final void setUiSurface(String uiSurface) {
        this.uiSurface = uiSurface;
    }

    public void updateCurrentTextColor() {
        currentTextColor = ColorUtils.blendARGB(primaryTextColor, dozeColor, dozeAmount);
        for (int i = 0; i < viewHolders.size(); i++) {
            ViewHolder holder = viewHolders.get(viewHolders.keyAt(i));
            if (holder != null) {
                holder.card.setPrimaryTextColor(currentTextColor);
                holder.card.setDozeAmount(dozeAmount);
            }
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0048  */
    /* JADX WARN: Removed duplicated region for block: B:24:0x0075  */
    /* JADX WARN: Removed duplicated region for block: B:38:0x0050  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final void updateTargetVisibility(Runnable runnable, boolean z) {
        boolean z2;
        List<SmartspaceTarget> targets =
                !mediaTargets.isEmpty()
                        ? mediaTargets
                        : hasDifferentTargets ? _aodTargets : getLockscreenTargets();
        List<SmartspaceTarget> lockscreenTargets = getLockscreenTargets();
        if (smartspaceTargets != targets) {
            if (dozeAmount == 1.0f
                    || (dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_AOD)) {
                z2 = true;
                boolean z3 =
                        smartspaceTargets == lockscreenTargets && needToSetToLockscreenTargets();
                if (!z2) {
                    Log.d(
                            "SsCardRecyclerViewAdapter",
                            "Updating Smartspace targets to targets for AOD");
                    smartspaceTargets = targets;
                } else if (z3) {
                    Log.d(
                            "SsCardRecyclerViewAdapter",
                            "Updating Smartspace targets to targets for Lockscreen");
                    smartspaceTargets = lockscreenTargets;
                }
                if (!z || z2 || z3) {
                    viewHolders.clear();
                    resetListIfNeeded();
                    mDiffer.submitList(
                            smartspaceTargets.stream().collect(Collectors.toList()), runnable);
                }
                hasAodLockscreenTransition = targets != lockscreenTargets;
                if (configProvider.isDefaultDateWeatherDisabled()
                        || BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN.equals(uiSurface)) {}
                BcSmartspaceTemplateDataUtils.updateVisibility(
                        root, smartspaceTargets.isEmpty() ? View.GONE : View.VISIBLE);
                return;
            }
        }
        z2 = false;
        if (smartspaceTargets == lockscreenTargets) {}
        if (!z2) {}
        if (!z) {}
        viewHolders.clear();
        resetListIfNeeded();
        mDiffer.submitList(smartspaceTargets.stream().collect(Collectors.toList()), runnable);
        hasAodLockscreenTransition = targets != lockscreenTargets;
        if (configProvider.isDefaultDateWeatherDisabled()) {}
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

        if (!configProvider.isDefaultDateWeatherDisabled()) {
            addDefaultDateCardIfEmpty(_aodTargets);
            addDefaultDateCardIfEmpty(_lockscreenTargets);
        }

        updateTargetVisibility(runnable, true);
    }
}
