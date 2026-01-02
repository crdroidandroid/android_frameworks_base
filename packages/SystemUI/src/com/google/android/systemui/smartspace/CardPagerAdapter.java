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

import androidx.viewpager.widget.PagerAdapter;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

public final class CardPagerAdapter extends PagerAdapter implements CardAdapter {
    public static final Companion Companion = new Companion();
    private final List<SmartspaceTarget> _aodTargets = new ArrayList<>();
    private final List<SmartspaceTarget> _lockscreenTargets = new ArrayList<>();
    private Handler bgHandler;
    private BcSmartspaceConfigPlugin configProvider;
    private int currentTextColor;
    private BcSmartspaceDataPlugin dataProvider;
    private float dozeAmount;
    private int dozeColor = -1;
    private final LazyServerFlagLoader enableCardRecycling =
            new LazyServerFlagLoader("enable_card_recycling");
    private final LazyServerFlagLoader enableReducedCardRecycling =
            new LazyServerFlagLoader("enable_reduced_card_recycling");
    private boolean hasAodLockscreenTransition;
    private boolean hasDifferentTargets;
    private boolean keyguardBypassEnabled;
    private final List<SmartspaceTarget> mediaTargets = new ArrayList<>();
    private Integer nonRemoteViewsHorizontalPadding;
    private float previousDozeAmount;
    private int primaryTextColor;
    private final SparseArray<BaseTemplateCard> recycledCards = new SparseArray<>();
    private final SparseArray<BcSmartspaceCard> recycledLegacyCards = new SparseArray<>();
    private final SparseArray<BcSmartspaceRemoteViewsCard> recycledRemoteViewsCards =
            new SparseArray<>();
    private BcSmartspaceView root;
    private List<SmartspaceTarget> smartspaceTargets = new ArrayList<>();
    private BcSmartspaceDataPlugin.TimeChangedDelegate timeChangedDelegate;
    private TransitionType transitioningTo = TransitionType.NOT_IN_TRANSITION;
    private String uiSurface;
    private final SparseArray<ViewHolder> viewHolders = new SparseArray<>();

    public CardPagerAdapter(BcSmartspaceView root, BcSmartspaceConfigPlugin configProvider) {
        this.root = root;
        this.configProvider = configProvider;
        int color = GraphicsUtils.getAttrColor(root.getContext(), android.R.attr.textColorPrimary);
        primaryTextColor = color;
        currentTextColor = color;
    }

    public static class Companion {
        public static boolean useRecycledViewForAction(SmartspaceAction newAction, SmartspaceAction recycledAction) {
            Map map = BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES;
            if (newAction == null && recycledAction == null) {
                return true;
            }
            if (newAction == null || recycledAction == null) {
                return false;
            }
            Bundle newExtras = newAction.getExtras();
            Bundle recycledExtras = recycledAction.getExtras();
            if (newExtras == null && recycledExtras == null) {
                return true;
            }
            if (newExtras == null || recycledExtras == null) {
                return false;
            }
            Set<String> newKeys = newExtras.keySet();
            Set<String> recycledKeys = recycledExtras.keySet();
            return Objects.equals(newKeys, recycledKeys);
        }

        public static boolean useRecycledViewForActionsList(List<SmartspaceAction> newActions, List<SmartspaceAction> recycledActions) {
            Map map = BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES;
            if (newActions == null && recycledActions == null) {
                return true;
            }

            if (newActions == null || recycledActions == null || newActions.size() != recycledActions.size()) {
                return false;
            }

            return IntStream.range(0, newActions.size())
                    .allMatch(i -> useRecycledViewForAction(newActions.get(i), recycledActions.get(i)));
        }

        public int getBaseLegacyCardRes(int featureType) {
            switch (featureType) {
                case -2:
                case -1:
                case 1:
                case 2:
                case 3:
                case 4:
                case 6:
                case 9:
                case 10:
                case 18:
                case 20:
                case 30:
                case 13:
                case 14:
                case 15:
                    return R.layout.smartspace_card;
                default:
                    return R.layout.smartspace_card;
            }
        }

        public final boolean useRecycledViewForNewTarget(SmartspaceTarget newTarget, SmartspaceTarget recycledTarget) {
            if (recycledTarget == null) {
                return false;
            }
            if (!newTarget.getSmartspaceTargetId().equals(recycledTarget.getSmartspaceTargetId())) {
                return false;
            }
            if (!useRecycledViewForAction(
                    newTarget.getHeaderAction(), recycledTarget.getHeaderAction())) {
                return false;
            }
            if (!useRecycledViewForAction(
                    newTarget.getBaseAction(), recycledTarget.getBaseAction())) {
                return false;
            }
            if (!useRecycledViewForActionsList(
                    newTarget.getActionChips(), recycledTarget.getActionChips())) {
                return false;
            }
            if (!useRecycledViewForActionsList(
                    newTarget.getIconGrid(), recycledTarget.getIconGrid())) {
                return false;
            }
            BaseTemplateData newTemplateData = newTarget.getTemplateData();
            BaseTemplateData recycledTemplateData = recycledTarget.getTemplateData();
            if (newTemplateData == null && recycledTemplateData == null) {
                return true;
            }
            return (newTemplateData == null || recycledTemplateData == null || !newTemplateData.equals(recycledTemplateData)) ? false : true;
        }
    }

    public enum TransitionType {
        NOT_IN_TRANSITION,
        TO_LOCKSCREEN,
        TO_AOD
    }

    public final class ViewHolder {
        public final BaseTemplateCard card;
        public final BcSmartspaceCard legacyCard;
        public final int position;
        public final BcSmartspaceRemoteViewsCard remoteViewsCard;
        public SmartspaceTarget target;

        public ViewHolder(int position, BcSmartspaceCard legacyCard, SmartspaceTarget target, BaseTemplateCard card, BcSmartspaceRemoteViewsCard remoteViewsCard) {
            this.position = position;
            this.legacyCard = legacyCard;
            this.target = target;
            this.card = card;
            this.remoteViewsCard = remoteViewsCard;
        }

        public void setTarget(SmartspaceTarget target) {
            this.target = target;
        }
    }

    public static final int getBaseLegacyCardRes(int featureType) {
        return Companion.getBaseLegacyCardRes(featureType);
    }

    public static final boolean useRecycledViewForNewTarget(SmartspaceTarget newTarget, SmartspaceTarget recycledTarget) {
        return Companion.useRecycledViewForNewTarget(newTarget, recycledTarget);
    }

    public final void addDefaultDateCardIfEmpty(List<SmartspaceTarget> targets) {
        if (targets.isEmpty()) {
            targets.add(new SmartspaceTarget.Builder("date_card_794317_92634", new ComponentName(root.getContext(), (Class<?>) CardPagerAdapter.class), root.getContext().getUser()).setFeatureType(1).setTemplateData(new BaseTemplateData.Builder(1).build()).build());
        }
    }

    @Override
    public final void destroyItem(ViewGroup container, int position, Object object) {
        ViewHolder holder = (ViewHolder) object;
        if (holder.legacyCard != null) {
            SmartspaceTarget smartspaceTarget = holder.legacyCard.mTarget;
            if (smartspaceTarget != null && enableCardRecycling.get()) {
                recycledLegacyCards.put(BcSmartSpaceUtil.getFeatureType(smartspaceTarget), holder.legacyCard);
            }
            container.removeView(holder.legacyCard);
        }
        if (holder.card != null) {
            SmartspaceTarget smartspaceTarget2 = holder.card.mTarget;
            if (smartspaceTarget2 != null && enableCardRecycling.get()) {
                recycledCards.put(smartspaceTarget2.getFeatureType(), holder.card);
            }
            container.removeView(holder.card);
        }
        if (holder.remoteViewsCard != null) {
            if (enableCardRecycling.get()) {
                Log.d("SsCardPagerAdapter", "[rmv] Caching RemoteViews card");
                recycledRemoteViewsCards.put(BcSmartSpaceUtil.getFeatureType(holder.target), holder.remoteViewsCard);
            }
            Log.d("SsCardPagerAdapter", "[rmv] Removing RemoteViews card");
            container.removeView(holder.remoteViewsCard);
        }
        if (viewHolders.get(position) == holder) {
            viewHolders.remove(position);
        }
    }

    @Override
    public final SmartspaceCard getCardAtPosition(int position) {
        ViewHolder holder = viewHolders.get(position);
        if (holder != null) {
            if (holder.card != null) {
                return holder.card;
            }
            if (holder.legacyCard != null) {
                return holder.legacyCard;
            }
            return holder.remoteViewsCard;
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
    public int getItemPosition(Object object) {
        ViewHolder holder = (ViewHolder) object;
        SmartspaceTarget currentTarget = getTargetAtPosition(holder.position);
        if (holder.target == currentTarget) {
            return POSITION_UNCHANGED;
        }
        if (currentTarget != null
                && BcSmartSpaceUtil.getFeatureType(currentTarget)
                        == BcSmartSpaceUtil.getFeatureType(holder.target)
                && currentTarget
                        .getSmartspaceTargetId()
                        .equals(holder.target.getSmartspaceTargetId())) {
            holder.setTarget(currentTarget);
            onBindViewHolder(holder);
            return POSITION_UNCHANGED;
        }
        return POSITION_NONE;
    }

    @Override
    public final BcSmartspaceCard getLegacyCardAtPosition(int position) {
        ViewHolder holder = viewHolders.get(position);
        if (holder != null) {
            return holder.legacyCard;
        }
        return null;
    }

    @Override
    public final List<SmartspaceTarget> getLockscreenTargets() {
        return (mediaTargets.isEmpty() || !keyguardBypassEnabled) ? _lockscreenTargets : mediaTargets;
    }

    @Override
    public final BcSmartspaceRemoteViewsCard getRemoteViewsCardAtPosition(int position) {
        ViewHolder holder = viewHolders.get(position);
        if (holder != null) {
            return holder.remoteViewsCard;
        }
        return null;
    }

    @Override
    public final List<SmartspaceTarget> getSmartspaceTargets() {
        return smartspaceTargets;
    }

    @Override
    public final SmartspaceTarget getTargetAtPosition(int position) {
        if (smartspaceTargets.isEmpty() || position < 0 || position >= smartspaceTargets.size()) {
            return null;
        }
        return smartspaceTargets.get(position);
    }

    @Override
    public final BaseTemplateCard getTemplateCardAtPosition(int position) {
        ViewHolder holder = viewHolders.get(position);
        if (holder != null) {
            return holder.card;
        }
        return null;
    }

    @Override
    public final String getUiSurface() {
        return uiSurface;
    }

    @Override
    public final Object instantiateItem(ViewGroup container, int position) {
        BcSmartspaceCard bcSmartspaceCard;
        ViewHolder holder;
        BaseTemplateData.SubItemLoggingInfo loggingInfo;
        SmartspaceTarget target = smartspaceTargets.get(position);
        BcSmartspaceCard bcSmartspaceCard2 = null;
        if (target.getRemoteViews() != null) {
            Log.i("SsCardPagerAdapter", "[rmv] Use RemoteViews for the feature: " + target.getFeatureType());
            BcSmartspaceRemoteViewsCard remoteViewsCard = enableCardRecycling.get() ? recycledRemoteViewsCards.removeReturnOld(BcSmartSpaceUtil.getFeatureType(target)) : null;
            if (remoteViewsCard == null) {
                remoteViewsCard = new BcSmartspaceRemoteViewsCard(container.getContext());
                remoteViewsCard.mUiSurface = uiSurface;
                remoteViewsCard.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            holder = new ViewHolder(position, null, target, null, remoteViewsCard);
            container.addView(remoteViewsCard);
        } else {
            boolean containsValidTemplateType = BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
            if (containsValidTemplateType) {
                Log.i("SsCardPagerAdapter", "Use UI template for the feature: " + target.getFeatureType());
                BaseTemplateCard templateCard = enableCardRecycling.get() ? (BaseTemplateCard) recycledCards.removeReturnOld(target.getFeatureType()) : null;
                if (templateCard == null || (enableReducedCardRecycling.get() && !Companion.useRecycledViewForNewTarget(target, templateCard.mTarget))) {
                    BaseTemplateData templateData = target.getTemplateData();
                    BaseTemplateData.SubItemInfo primaryItem = templateData != null ? templateData.getPrimaryItem() : null;
                    int layoutRes = (primaryItem == null || (SmartspaceUtils.isEmpty(primaryItem.getText()) && primaryItem.getIcon() == null) || ((loggingInfo = primaryItem.getLoggingInfo()) != null && loggingInfo.getFeatureType() == 1)) ? R.layout.smartspace_base_template_card_with_date : R.layout.smartspace_base_template_card;
                    LayoutInflater inflater = LayoutInflater.from(container.getContext());
                    templateCard = (BaseTemplateCard) inflater.inflate(layoutRes, (ViewGroup) container, false);
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
                    templateCard.mBgHandler = bgHandler != null ? bgHandler : null;
                    if (templateCard.mDateView != null) {
                        templateCard.mDateView.mBgHandler = templateCard.mBgHandler;
                    }
                    if (templateCard.mDateView != null) {
                        if (templateCard.mDateView.isAttachedToWindow()) {
                            throw new IllegalStateException("Must call before attaching view to window.");
                        }
                        templateCard.mDateView.mTimeChangedDelegate = timeChangedDelegate;
                    }
                    Map<Integer, Integer> templateTypeToSecondaryCardRes =
                            BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES;
                    Integer secondaryRes =
                            templateTypeToSecondaryCardRes.get(
                                    target.getTemplateData().getTemplateType());
                    if (templateData != null && secondaryRes != null) {
                        BcSmartspaceCardSecondary secondaryCard = (BcSmartspaceCardSecondary) inflater.inflate(secondaryRes, (ViewGroup) templateCard, false);
                        Log.i("SsCardPagerAdapter", "Secondary card is found");
                        templateCard.setSecondaryCard(secondaryCard);
                    }
                }
                holder = new ViewHolder(position, null, target, templateCard, null);
                container.addView(templateCard);
            } else {
                BcSmartspaceCard legacyCard = enableCardRecycling.get() ? recycledLegacyCards.removeReturnOld(BcSmartSpaceUtil.getFeatureType(target)) : null;
                if (legacyCard == null || (enableReducedCardRecycling.get() && !Companion.useRecycledViewForNewTarget(target, legacyCard.mTarget))) {
                    int featureType = BcSmartSpaceUtil.getFeatureType(target);
                    LayoutInflater inflater = LayoutInflater.from(container.getContext());
                    int layoutRes = Companion.getBaseLegacyCardRes(featureType);
                    if (layoutRes == 0) {
                        Log.w(
                                "SsCardPagerAdapter",
                                "No legacy card can be created for feature type: " + featureType);
                    } else {
                        legacyCard = (BcSmartspaceCard) inflater.inflate(layoutRes, (ViewGroup) container, false);
                        legacyCard.mUiSurface = uiSurface;
                        if (nonRemoteViewsHorizontalPadding != null) {
                            legacyCard.setPaddingRelative(nonRemoteViewsHorizontalPadding, legacyCard.getPaddingTop(), nonRemoteViewsHorizontalPadding, legacyCard.getPaddingBottom());
                        }
                        Integer secondaryRes = (Integer) BcSmartSpaceUtil.FEATURE_TYPE_TO_SECONDARY_CARD_RESOURCE_MAP.get(featureType);
                        if (secondaryRes != null) {
                            legacyCard.setSecondaryCard((BcSmartspaceCardSecondary) inflater.inflate(secondaryRes, (ViewGroup) legacyCard, false));
                        }
                    }
                    bcSmartspaceCard = legacyCard;
                } else {
                    bcSmartspaceCard = legacyCard;
                }
                holder = new ViewHolder(position, bcSmartspaceCard, target, null, null);
                if (bcSmartspaceCard != null) {
                    container.addView(bcSmartspaceCard);
                }
            }
        }
        onBindViewHolder(holder);
        viewHolders.put(position, holder);
        return holder;
    }

    @Override
    public final boolean isViewFromObject(View view, Object object) {
        ViewHolder holder = (ViewHolder) object;
        return view == holder.legacyCard || view == holder.card || view == holder.remoteViewsCard;
    }

    public final void onBindViewHolder(ViewHolder holder) {
        SmartspaceTarget target = smartspaceTargets.get(holder.position);
        boolean hasValidTemplate = BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
        BcSmartspaceCardLoggingInfo.Builder loggingInfoBuilder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setInstanceId(InstanceId.create(target))
                        .setFeatureType(target.getFeatureType())
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(uiSurface, dozeAmount))
                        .setRank(holder.position)
                        .setCardinality(smartspaceTargets.size())
                        .setUid(-1);
        BcSmartspaceSubcardLoggingInfo subcardInfo =
                hasValidTemplate
                        ? BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(
                                target.getTemplateData())
                        : BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(target);
        loggingInfoBuilder.setSubcardInfo(subcardInfo);
        loggingInfoBuilder.setDimensionalInfo(
                BcSmartspaceCardLoggerUtil.createDimensionalLoggingInfo(target.getTemplateData()));
        BcSmartspaceCardLoggingInfo loggingInfo =
                new BcSmartspaceCardLoggingInfo(loggingInfoBuilder);
        if (target.getRemoteViews() != null) {
            if (holder.remoteViewsCard == null) {
                Log.w("SsCardPagerAdapter", "[rmv] No RemoteViews card view can be binded");
                return;
            }
            Log.d("SsCardPagerAdapter", "[rmv] Refreshing RemoteViews card");
            holder.remoteViewsCard.bindData(target, dataProvider != null ? dataProvider.getEventNotifier() : null, loggingInfo, smartspaceTargets.size() > 1);
            return;
        }
        if (!hasValidTemplate) {
            if (holder.legacyCard == null) {
                Log.w("SsCardPagerAdapter", "No legacy card view can be binded");
                return;
            }
            holder.legacyCard.bindData(target, dataProvider != null ? dataProvider.getEventNotifier() : null, loggingInfo, smartspaceTargets.size() > 1);
            holder.legacyCard.setPrimaryTextColor(currentTextColor);
            holder.legacyCard.setDozeAmount(dozeAmount);
            return;
        }
        if (target.getTemplateData() == null) {
            throw new IllegalStateException("Required value was null.");
        }
        BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureTypeOrUpdateLogInfoFromTemplateData(loggingInfo, target.getTemplateData());
        if (holder.card == null) {
            Log.w("SsCardPagerAdapter", "No ui-template card view can be binded");
            return;
        }
        holder.card.bindData(target, dataProvider != null ? dataProvider.getEventNotifier() : null, loggingInfo, smartspaceTargets.size() > 1);
        holder.card.setPrimaryTextColor(currentTextColor);
        holder.card.setDozeAmount(dozeAmount);
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
    public void setDozeAmount(float dozeAmount) {
        this.dozeAmount = dozeAmount;
        TransitionType newTransition =
                previousDozeAmount > dozeAmount
                        ? TransitionType.TO_LOCKSCREEN
                        : previousDozeAmount < dozeAmount
                                ? TransitionType.TO_AOD
                                : TransitionType.NOT_IN_TRANSITION;
        transitioningTo = newTransition;
        previousDozeAmount = dozeAmount;
        updateTargetVisibility();
        updateCurrentTextColor();
    }

    @Override
    public final void setKeyguardBypassEnabled(boolean enabled) {
        keyguardBypassEnabled = enabled;
        updateTargetVisibility();
    }

    @Override
    public void setMediaTarget(SmartspaceTarget target) {
        mediaTargets.clear();
        if (target != null) {
            mediaTargets.add(target);
        }
        updateTargetVisibility();
        notifyDataSetChanged();
    }

    @Override
    public final void setNonRemoteViewsHorizontalPadding(Integer padding) {
        nonRemoteViewsHorizontalPadding = padding;
        for (int i = 0; i < viewHolders.size(); i++) {
            int keyAt = viewHolders.keyAt(i);
            BcSmartspaceCard legacyCardAtPosition = getLegacyCardAtPosition(keyAt);
            if (legacyCardAtPosition != null) {
                legacyCardAtPosition.setPaddingRelative(padding, legacyCardAtPosition.getPaddingTop(), padding, legacyCardAtPosition.getPaddingBottom());
            }
            BaseTemplateCard templateCardAtPosition = getTemplateCardAtPosition(keyAt);
            if (templateCardAtPosition != null) {
                templateCardAtPosition.setPaddingRelative(padding, templateCardAtPosition.getPaddingTop(), padding, templateCardAtPosition.getPaddingBottom());
            }
        }
        for (int i = 0; i < recycledCards.size(); i++) {
            BaseTemplateCard baseTemplateCard = (BaseTemplateCard) recycledCards.valueAt(i);
            baseTemplateCard.setPaddingRelative(padding, baseTemplateCard.getPaddingTop(), padding, baseTemplateCard.getPaddingBottom());
        }
        for (int i = 0; i < recycledLegacyCards.size(); i++) {
            BcSmartspaceCard bcSmartspaceCard = (BcSmartspaceCard) recycledLegacyCards.valueAt(i);
            bcSmartspaceCard.setPaddingRelative(padding, bcSmartspaceCard.getPaddingTop(), padding, bcSmartspaceCard.getPaddingBottom());
        }
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        primaryTextColor = color;
        updateCurrentTextColor();
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
        for (int i = 0; i < viewHolders.size(); i++) {
            ViewHolder holder = viewHolders.valueAt(i);
            if (holder != null && holder.card != null) {
                holder.card.setScreenOn(screenOn);
            }
        }
    }

    @Override
    public void setTargets(List<SmartspaceTarget> targets) {
        _aodTargets.clear();
        _lockscreenTargets.clear();
        hasDifferentTargets = false;
        for (SmartspaceTarget target : targets) {
            if (target.getFeatureType() == 34) {
                continue;
            }
            int screenExtra =
                    target.getBaseAction() != null && target.getBaseAction().getExtras() != null
                            ? target.getBaseAction().getExtras().getInt("SCREEN_EXTRA", 3)
                            : 3;
            if ((screenExtra & 2) != 0) {
                _aodTargets.add(target);
            }
            if ((screenExtra & 1) != 0) {
                _lockscreenTargets.add(target);
            }
            if (screenExtra != 3) {
                hasDifferentTargets = true;
            }
        }
        if (!configProvider.isDefaultDateWeatherDisabled()) {
            addDefaultDateCardIfEmpty(_aodTargets);
            addDefaultDateCardIfEmpty(_lockscreenTargets);
        }
        updateTargetVisibility();
        notifyDataSetChanged();
    }

    @Override
    public final void setTimeChangedDelegate(BcSmartspaceDataPlugin.TimeChangedDelegate delegate) {
        timeChangedDelegate = delegate;
    }

    @Override
    public final void setUiSurface(String uiSurface) {
        this.uiSurface = uiSurface;
    }

    public final void updateCurrentTextColor() {
        currentTextColor = ColorUtils.blendARGB(primaryTextColor, dozeColor, dozeAmount);
        for (int i = 0; i < viewHolders.size(); i++) {
            ViewHolder holder = viewHolders.valueAt(i);
            if (holder != null) {
                if (holder.legacyCard != null) {
                    holder.legacyCard.setPrimaryTextColor(currentTextColor);
                    holder.legacyCard.setDozeAmount(dozeAmount);
                }
                if (holder.card != null) {
                    holder.card.setPrimaryTextColor(currentTextColor);
                    holder.card.setDozeAmount(dozeAmount);
                }
            }
        }
    }

    public void updateTargetVisibility() {
        List<SmartspaceTarget> targetList =
                !mediaTargets.isEmpty()
                        ? mediaTargets
                        : hasDifferentTargets ? _aodTargets : getLockscreenTargets();
        List<SmartspaceTarget> lockscreenTargets = getLockscreenTargets();
        boolean shouldUpdate =
                smartspaceTargets != targetList
                        && (dozeAmount == 1f
                                || (dozeAmount >= 0.36f
                                        && transitioningTo == TransitionType.TO_AOD));
        boolean shouldUpdateLockscreen =
                smartspaceTargets != lockscreenTargets
                        && (dozeAmount == 0f
                                || (1f - dozeAmount >= 0.36f
                                        && transitioningTo == TransitionType.TO_LOCKSCREEN));
        if (shouldUpdate || shouldUpdateLockscreen) {
            smartspaceTargets = shouldUpdate ? targetList : lockscreenTargets;
            notifyDataSetChanged();
        }
        hasAodLockscreenTransition = targetList != lockscreenTargets;
        if (configProvider.isDefaultDateWeatherDisabled() && !BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN.equals(uiSurface)) {
            BcSmartspaceTemplateDataUtils.updateVisibility(
                    root, smartspaceTargets.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
}
