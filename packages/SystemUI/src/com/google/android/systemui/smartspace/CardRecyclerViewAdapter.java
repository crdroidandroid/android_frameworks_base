package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import androidx.recyclerview.widget.AdapterListUpdateCallback;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.IcuDateTextView;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
/* loaded from: classes2.dex */
public final class CardRecyclerViewAdapter extends RecyclerView.Adapter<CardRecyclerViewAdapter.ViewHolder> implements CardAdapter {
    public static final Set<Integer> legacySecondaryCardResourceIdSet =
            BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP.values().stream()
                    .collect(Collectors.toSet());
    public static final Set<Integer> templateSecondaryCardResourceIdSet =
            BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES.values().stream()
                    .collect(Collectors.toSet());
    public final List<SmartspaceTarget> _aodTargets;
    public float dozeAmount;
    public final List<SmartspaceTarget> _lockscreenTargets;
    public Handler bgHandler;
    public BcSmartspaceConfigPlugin configProvider;
    public int currentTextColor;
    public BcSmartspaceDataPlugin dataProvider;
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
        public final boolean areContentsTheSame(SmartspaceTarget oldItem, SmartspaceTarget newItem) {
            return false;
        }
    }

    public enum TransitionType {
        NOT_IN_TRANSITION,
        TO_LOCKSCREEN,
        TO_AOD
    }

    /* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        public SmartspaceCard card;

        public ViewHolder(SmartspaceCard card) {
            super(card.getView());
            this.card = card;
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    public CardRecyclerViewAdapter(BcSmartspaceView root, BcSmartspaceConfigPlugin configProvider) {
        DiffUtilItemCallback diffUtilItemCallback = new DiffUtilItemCallback();
        AsyncDifferConfig<SmartspaceTarget> asyncDifferConfig = 
                new AsyncDifferConfig.Builder<SmartspaceTarget>(diffUtilItemCallback)
                .build();
        mDiffer = new AsyncListDiffer<>(new AdapterListUpdateCallback(this), asyncDifferConfig);
        mDiffer.addListListener((previousList, currentList) -> {
        });
        this.root = root;
        viewHolders = new SparseArray<>();
        smartspaceTargets = new ArrayList<>();
        _aodTargets = new ArrayList<>();
        _lockscreenTargets = new ArrayList<>();
        mediaTargets = new ArrayList<>();
        dozeColor = -1;
        int attrColor = GraphicsUtils.getAttrColor(root.getContext(), android.R.attr.textColorPrimary);
        primaryTextColor = attrColor;
        currentTextColor = attrColor;
        configProvider = configProvider;
        transitioningTo = TransitionType.NOT_IN_TRANSITION;
    }

    public static boolean isTemplateCard(SmartspaceTarget target) {
        return target.getTemplateData() != null && BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
    }

    public final void addDefaultDateCardIfEmpty(List<SmartspaceTarget> targets) {
        if (targets.isEmpty()) {
            targets.add(new SmartspaceTarget.Builder("date_card_794317_92634", new ComponentName(root.getContext(), CardRecyclerViewAdapter.class), root.getContext().getUser()).setFeatureType(1).setTemplateData(new BaseTemplateData.Builder(1).build()).build());
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
            Integer layoutId = (Integer) BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP.get(BcSmartSpaceUtil.getFeatureType(target));
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
        Integer layoutId = (Integer) BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES.get(templateData.getTemplateType());
        return layoutId != null ? layoutId : R.layout.smartspace_base_template_card;
    }

    @Override
    public BcSmartspaceCard getLegacyCardAtPosition(int position) {
        SmartspaceCard card = getCardAtPosition(position);
        return card instanceof BcSmartspaceCard ? (BcSmartspaceCard) card : null;
    }

    @Override
    public List<SmartspaceTarget> getLockscreenTargets() {
        return (mediaTargets.isEmpty() || !keyguardBypassEnabled) ? _lockscreenTargets : mediaTargets;
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
            BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureTypeOrUpdateLogInfoFromTemplateData(loggingInfo, target.getTemplateData());
            if (!(card instanceof BaseTemplateCard)) {
                Log.w("SsCardRecyclerViewAdapter", "No ui-template card view can be binded");
                return;
            }
            ((BaseTemplateCard) card).mBgHandler = bgHandler;
            IcuDateTextView icuDateTextView = ((BaseTemplateCard) card).mDateView;
            if (icuDateTextView != null) {
                icuDateTextView.mBgHandler = bgHandler;
            }
        } else if (!(card instanceof BcSmartspaceCard)) {
            Log.w("SsCardRecyclerViewAdapter", "No legacy card view can be binded");
            return;
        }
        card.bindData(target, dataProvider != null ? dataProvider.getEventNotifier() : null, loggingInfo, smartspaceTargets.size() > 1);
        card.setPrimaryTextColor(currentTextColor);
        card.setDozeAmount(dozeAmount);
        viewHolders.put(position, holder);
    }

    @Override
    public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        SmartspaceCard card;
        Integer secondaryCardResId = null;
        if (templateSecondaryCardResourceIdSet.contains(viewType) || viewType == R.layout.smartspace_base_template_card_with_date || viewType == R.layout.smartspace_base_template_card) {
            if (templateSecondaryCardResourceIdSet.contains(viewType)) {
                secondaryCardResId = viewType;
                viewType = R.layout.smartspace_base_template_card;
            }
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            BaseTemplateCard templateCard = (BaseTemplateCard) inflater.inflate(viewType, parent, false);
            templateCard.mUiSurface = uiSurface;
            if (templateCard.mDateView != null && TextUtils.equals(uiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
                if (templateCard.mDateView.isAttachedToWindow()) {
                    throw new IllegalStateException("Must call before attaching view to window.");
                }
                templateCard.mDateView.mUpdatesOnAod = true;
            }
            if (nonRemoteViewsHorizontalPadding != null) {
                templateCard.setPaddingRelative(nonRemoteViewsHorizontalPadding, templateCard.getPaddingTop(), nonRemoteViewsHorizontalPadding, templateCard.getPaddingBottom());
            }
            if (templateCard.mDateView != null) {
                if (templateCard.mDateView.isAttachedToWindow()) {
                    throw new IllegalStateException("Must call before attaching view to window.");
                }
                templateCard.mDateView.mTimeChangedDelegate = timeChangedDelegate;
            }
            if (secondaryCardResId != null) {
                BcSmartspaceCardSecondary secondaryCard = (BcSmartspaceCardSecondary) inflater.inflate(secondaryCardResId, (ViewGroup) templateCard, false);
                Log.i("SsCardRecyclerViewAdapter", "Secondary card is found");
                templateCard.setSecondaryCard(secondaryCard);
            }
            card = templateCard;
        } else {
            if (legacySecondaryCardResourceIdSet.contains(viewType) || viewType == R.layout.smartspace_card) {
                if (legacySecondaryCardResourceIdSet.contains(viewType)) {
                    secondaryCardResId = viewType;
                    viewType = R.layout.smartspace_card;
                }
                LayoutInflater inflater = LayoutInflater.from(parent.getContext());
                BcSmartspaceCard legacyCard = (BcSmartspaceCard) inflater.inflate(viewType, parent, false);
                legacyCard.mUiSurface = uiSurface;
                if (nonRemoteViewsHorizontalPadding != null) {
                    legacyCard.setPaddingRelative(nonRemoteViewsHorizontalPadding, legacyCard.getPaddingTop(), nonRemoteViewsHorizontalPadding, legacyCard.getPaddingBottom());
                }
                if (secondaryCardResId != null) {
                    legacyCard.setSecondaryCard((BcSmartspaceCardSecondary) inflater.inflate(secondaryCardResId, (ViewGroup) legacyCard, false));
                }
                card = legacyCard;
            } else {
                BcSmartspaceRemoteViewsCard remoteViewsCard = new BcSmartspaceRemoteViewsCard(parent.getContext());
                remoteViewsCard.mUiSurface = uiSurface;
                card = remoteViewsCard;
            }
        }
        card.getView()
                .setLayoutParams(
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
        return new ViewHolder(card);
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
        transitioningTo = previousDozeAmount > dozeAmount ? TransitionType.TO_LOCKSCREEN : previousDozeAmount < dozeAmount ? TransitionType.TO_AOD : TransitionType.NOT_IN_TRANSITION;
        previousDozeAmount = dozeAmount;
        updateTargetVisibility(null, false);
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
        for (int i = 0; i < viewHolders.size(); i++) {
            int key = viewHolders.keyAt(i);
            BcSmartspaceCard legacyCard = getLegacyCardAtPosition(key);
            if (legacyCard != null) {
                legacyCard.setPaddingRelative(padding, legacyCard.getPaddingTop(), padding, legacyCard.getPaddingBottom());
            }
            BaseTemplateCard templateCard = getTemplateCardAtPosition(key);
            if (templateCard != null) {
                templateCard.setPaddingRelative(padding, templateCard.getPaddingTop(), padding, templateCard.getPaddingBottom());
            }
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
    /* JADX WARN: Removed duplicated region for block: B:14:0x003b  */
    /* JADX WARN: Removed duplicated region for block: B:23:0x0055  */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0068 A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:28:0x007f  */
    /* JADX WARN: Removed duplicated region for block: B:31:0x008a  */
    /* JADX WARN: Removed duplicated region for block: B:40:? A[ADDED_TO_REGION, RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:42:0x005d  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public final void updateTargetVisibility(Runnable runnable, boolean z) {
        boolean z2;
        boolean z3;
        List<SmartspaceTarget> targets = !mediaTargets.isEmpty() ? mediaTargets : hasDifferentTargets ? _aodTargets : getLockscreenTargets();
        List<SmartspaceTarget> lockscreenTargets = getLockscreenTargets();
        if (smartspaceTargets != targets) {
            if (dozeAmount == 1.0f || (dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_AOD)) {
                z2 = true;
                if (smartspaceTargets != lockscreenTargets) {
                    if (dozeAmount == 0.0f || (1.0f - dozeAmount >= 0.36f && transitioningTo == TransitionType.TO_LOCKSCREEN)) {
                        z3 = true;
                        if (z2) {
                            Log.d("SsCardRecyclerViewAdapter", "Updating Smartspace targets to targets for AOD");
                            smartspaceTargets = targets;
                        } else if (z3) {
                            Log.d("SsCardRecyclerViewAdapter", "Updating Smartspace targets to targets for Lockscreen");
                            smartspaceTargets = lockscreenTargets;
                        }
                        if (!z || z2 || z3) {
                            viewHolders.clear();
                            mDiffer.submitList(new ArrayList<>(smartspaceTargets), runnable);
                        }
                        hasAodLockscreenTransition = targets != lockscreenTargets;
                        if (!configProvider.isDefaultDateWeatherDisabled() || !BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN.equalsIgnoreCase(uiSurface)) {
                            return;
                        }
                        BcSmartspaceTemplateDataUtils.updateVisibility(root, smartspaceTargets.isEmpty() ? View.GONE : View.VISIBLE);
                        return;
                    }
                }
                z3 = false;
                if (z2) {
                }
                if (!z) {
                }
                viewHolders.clear();
                mDiffer.submitList(new ArrayList<>(smartspaceTargets), runnable);
                hasAodLockscreenTransition = targets != lockscreenTargets;
                if (configProvider.isDefaultDateWeatherDisabled()) {
                    return;
                } else {
                    return;
                }
            }
        }
        z2 = false;
        if (smartspaceTargets != lockscreenTargets) {
        }
        z3 = false;
        if (z2) {
        }
        if (!z) {
        }
        viewHolders.clear();
        mDiffer.submitList(new ArrayList<>(smartspaceTargets), runnable);
        hasAodLockscreenTransition = targets != lockscreenTargets;
        if (configProvider.isDefaultDateWeatherDisabled()) {
        }
    }

    public final void setTargets(List<SmartspaceTarget> list, Runnable runnable) {
        Bundle extras;
        _aodTargets.clear();
        _lockscreenTargets.clear();
        hasDifferentTargets = false;
        Iterator<SmartspaceTarget> it = list.iterator();
        while (it.hasNext()) {
            SmartspaceTarget smartspaceTarget = it.next();
            if (smartspaceTarget.getFeatureType() == 34 || 
                (smartspaceTarget.getRemoteViews() == null && !isTemplateCard(smartspaceTarget) && smartspaceTarget.getFeatureType() == 1)) {
                Log.e("SsCardRecyclerViewAdapter", "No card can be created for target: " + smartspaceTarget.getFeatureType());
            } else {
                SmartspaceAction baseAction = smartspaceTarget.getBaseAction();
                
                int screenExtra = (baseAction == null || (extras = baseAction.getExtras()) == null) 
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
