package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.viewpager.widget.PagerAdapter;
import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

public class CardPagerAdapter extends PagerAdapter {
    public static final int MAX_FEATURE_TYPE = 41;
    public static final int MIN_FEATURE_TYPE = -2;
    public final View mRoot;
    public BcSmartspaceDataPlugin mDataProvider;
    public int mCurrentTextColor;
    public int mPrimaryTextColor;
    public ArrayList<SmartspaceTarget> mSmartspaceTargets = new ArrayList<>();
    public ArrayList<SmartspaceTarget> mAODTargets = new ArrayList<>();
    public ArrayList<SmartspaceTarget> mLockscreenTargets = new ArrayList<>();
    public final ArrayList<SmartspaceTarget> mMediaTargets = new ArrayList<>();
    public final SparseArray<ViewHolder> mViewHolders = new SparseArray<>();
    public final LazyServerFlagLoader mEnableCardRecycling = new LazyServerFlagLoader("enable_card_recycling");
    public final LazyServerFlagLoader mEnableReducedCardRecycling = new LazyServerFlagLoader("enable_reduced_card_recycling");
    public final SparseArray<BaseTemplateCard> mRecycledCards = new SparseArray<>();
    public final SparseArray<BcSmartspaceCard> mRecycledLegacyCards = new SparseArray<>();
    public BcNextAlarmData mNextAlarmData = new BcNextAlarmData();
    public boolean mIsDreaming = false;
    public String mUiSurface = null;
    public float mDozeAmount = 0.0f;
    public float mLastDozeAmount = 0.0f;
    public int mDozeColor = -1;
    public String mDndDescription = null;
    public Drawable mDndImage = null;
    public boolean mKeyguardBypassEnabled = false;
    public boolean mHasDifferentTargets = false;

    List<SmartspaceTarget> getTargets() {
        return this.mSmartspaceTargets;
    }

    public CardPagerAdapter(View view) {
        this.mRoot = view;
        int attrColor = GraphicsUtils.getAttrColor(view.getContext(), 16842806);
        this.mPrimaryTextColor = attrColor;
        this.mCurrentTextColor = attrColor;
    }

    public static int getBaseLegacyCardRes(int layout) {
        return layout != 1 ? R.layout.smartspace_card : R.layout.smartspace_card_date;
    }

    public static int getLegacySecondaryCardRes(int layout) {
        if (layout != -2) {
            if (layout == -1) {
                return R.layout.smartspace_card_combination;
            }
            if (layout == 3) {
                return R.layout.smartspace_card_generic_landscape_image;
            }
            if (layout == 4) {
                return R.layout.smartspace_card_flight;
            }
            if (layout == 9) {
                return R.layout.smartspace_card_sports;
            }
            if (layout == 10) {
                return R.layout.smartspace_card_weather_forecast;
            }
            if (layout == 13) {
                return R.layout.smartspace_card_shopping_list;
            }
            if (layout == 14) {
                return R.layout.smartspace_card_loyalty;
            }
            if (layout == 18) {
                return R.layout.smartspace_card_generic_landscape_image;
            }
            if (layout != 20 && layout != 30) {
                return 0;
            }
            return R.layout.smartspace_card_doorbell;
        }
        return R.layout.smartspace_card_combination_at_store;
    }

    public static boolean useRecycledViewForAction(SmartspaceAction smartspaceAction, SmartspaceAction smartspaceAction2) {
        if (smartspaceAction == null && smartspaceAction2 == null) {
            return true;
        }
        if (smartspaceAction != null && smartspaceAction2 != null) {
            Bundle extras = smartspaceAction.getExtras();
            Bundle extras2 = smartspaceAction2.getExtras();
            if (extras == null && extras2 == null) {
                return true;
            }
            Bundle extras3 = smartspaceAction.getExtras();
            Bundle extras4 = smartspaceAction2.getExtras();
            return (extras3 == null || extras4 == null || !smartspaceAction.getExtras().keySet().equals(smartspaceAction2.getExtras().keySet())) ? false : true;
        }
        return false;
    }

    public static boolean useRecycledViewForActionsList(final List<SmartspaceAction> list, final List<SmartspaceAction> list2) {
        if (list == null && list2 == null) {
            return true;
        }
        return list != null && list2 != null && list.size() == list2.size() && IntStream.range(0, list.size()).allMatch(new IntPredicate() { // from class: com.google.android.systemui.smartspace.CardPagerAdapter.1
            @Override // java.util.function.IntPredicate
            public boolean test(int i) {
                return CardPagerAdapter.useRecycledViewForAction((SmartspaceAction) list.get(i), (SmartspaceAction) list2.get(i));
            }
        });
    }

    public static boolean useRecycledViewForNewTarget(SmartspaceTarget smartspaceTarget, SmartspaceTarget smartspaceTarget2) {
        if (smartspaceTarget2 == null || !smartspaceTarget.getSmartspaceTargetId().equals(smartspaceTarget2.getSmartspaceTargetId()) || !useRecycledViewForAction(smartspaceTarget.getHeaderAction(), smartspaceTarget2.getHeaderAction()) || !useRecycledViewForAction(smartspaceTarget.getBaseAction(), smartspaceTarget2.getBaseAction()) || !useRecycledViewForActionsList(smartspaceTarget.getActionChips(), smartspaceTarget2.getActionChips()) || !useRecycledViewForActionsList(smartspaceTarget.getIconGrid(), smartspaceTarget2.getIconGrid())) {
            return false;
        }
        BaseTemplateData templateData = smartspaceTarget.getTemplateData();
        BaseTemplateData templateData2 = smartspaceTarget2.getTemplateData();
        return (templateData == null || templateData2 == null || !templateData.equals(templateData2)) ? false : true;
    }

    public void refreshCards() {
        for (int i = 0; i < this.mViewHolders.size(); i++) {
            SparseArray<ViewHolder> sparseArray = this.mViewHolders;
            ViewHolder viewHolder = sparseArray.get(sparseArray.keyAt(i));
            if (viewHolder != null) {
                onBindViewHolder(viewHolder);
            }
        }
    }

    @Override
    public void destroyItem(ViewGroup viewGroup, int position, Object obj) {
        ViewHolder viewHolder = (ViewHolder) obj;
        if (viewHolder == null) {
            return;
        }
        BcSmartspaceCard bcSmartspaceCard = viewHolder.mLegacyCard;
        if (bcSmartspaceCard != null) {
            SmartspaceTarget smartspaceTarget = bcSmartspaceCard.mTarget;
            if (smartspaceTarget != null && this.mEnableCardRecycling.get()) {
                this.mRecycledLegacyCards.put(getFeatureType(smartspaceTarget), bcSmartspaceCard);
            }
            viewGroup.removeView(bcSmartspaceCard);
        }
        BaseTemplateCard baseTemplateCard = viewHolder.mCard;
        if (baseTemplateCard != null) {
            if (baseTemplateCard.mTarget != null && this.mEnableCardRecycling.get()) {
                this.mRecycledCards.put(baseTemplateCard.mTarget.getFeatureType(), baseTemplateCard);
            }
            viewGroup.removeView(viewHolder.mCard);
        }
        if (this.mViewHolders.get(position) == viewHolder) {
            this.mViewHolders.remove(position);
        }
    }

    @Override
    public int getCount() {
        return this.mSmartspaceTargets.size();
    }

    @Override
    public int getItemPosition(Object obj) {
        ViewHolder viewHolder = (ViewHolder) obj;
        SmartspaceTarget targetAtPosition = getTargetAtPosition(viewHolder.mPosition);
        if (viewHolder.mTarget == targetAtPosition) {
            return -1;
        }
        if (targetAtPosition != null && getFeatureType(targetAtPosition) == getFeatureType(viewHolder.mTarget) && Objects.equals(targetAtPosition.getSmartspaceTargetId(), viewHolder.mTarget.getSmartspaceTargetId())) {
            viewHolder.mTarget = targetAtPosition;
            onBindViewHolder(viewHolder);
            return -1;
        }
        return -2;
    }

    public SmartspaceTarget getTargetAtPosition(int position) {
        if (!this.mSmartspaceTargets.isEmpty() && position >= 0 && position < this.mSmartspaceTargets.size()) {
            return this.mSmartspaceTargets.get(position);
        }
        return null;
    }

    @Override // androidx.viewpager.widget.PagerAdapter
    public final Object instantiateItem(ViewGroup viewGroup, int i) {
        BcSmartspaceCard bcSmartspaceCard;
        ViewHolder viewHolder;
        BaseTemplateCard baseTemplateCard;
        BaseTemplateData.SubItemInfo subItemInfo;
        int i2;
        int secondaryCardRes;
        SmartspaceTarget smartspaceTarget = (SmartspaceTarget) this.mSmartspaceTargets.get(i);
        if (smartspaceTarget.getTemplateData() != null) {
            Log.i("SsCardPagerAdapter", "Use UI template for the feature: " + smartspaceTarget.getFeatureType());
            if (this.mEnableCardRecycling.get()) {
                baseTemplateCard = (BaseTemplateCard) this.mRecycledCards.removeReturnOld(smartspaceTarget.getFeatureType());
            } else {
                baseTemplateCard = null;
            }
            if (baseTemplateCard == null || (this.mEnableReducedCardRecycling.get() && !useRecycledViewForNewTarget(smartspaceTarget, baseTemplateCard.mTarget))) {
                BaseTemplateData templateData = smartspaceTarget.getTemplateData();
                if (templateData != null) {
                    subItemInfo = templateData.getPrimaryItem();
                } else {
                    subItemInfo = null;
                }
                if (subItemInfo != null && (!SmartspaceUtils.isEmpty(subItemInfo.getText()) || subItemInfo.getIcon() != null)) {
                    i2 = R.layout.smartspace_base_template_card;
                } else {
                    i2 = R.layout.smartspace_base_template_card_with_date;
                }
                LayoutInflater from = LayoutInflater.from(viewGroup.getContext());
                BaseTemplateCard baseTemplateCard2 = (BaseTemplateCard) from.inflate(i2, viewGroup, false);
                if (templateData != null && (secondaryCardRes = BcSmartspaceTemplateDataUtils.getSecondaryCardRes(templateData.getTemplateType())) != 0) {
                    BcSmartspaceCardSecondary bcSmartspaceCardSecondary = (BcSmartspaceCardSecondary) from.inflate(secondaryCardRes, (ViewGroup) baseTemplateCard2, false);
                    if (bcSmartspaceCardSecondary != null) {
                        Log.i("SsCardPagerAdapter", "Secondary card is found");
                    }
                    ViewGroup viewGroup2 = baseTemplateCard2.mSecondaryCardPane;
                    if (viewGroup2 != null) {
                        baseTemplateCard2.mSecondaryCard = bcSmartspaceCardSecondary;
                        BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup2, 8);
                        baseTemplateCard2.mSecondaryCardPane.removeAllViews();
                        if (bcSmartspaceCardSecondary != null) {
                            ConstraintLayout.LayoutParams layoutParams = new ConstraintLayout.LayoutParams(-2, baseTemplateCard2.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height));
                            layoutParams.setMarginStart(baseTemplateCard2.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_secondary_card_start_margin));
                            layoutParams.startToStart = 0;
                            layoutParams.topToTop = 0;
                            layoutParams.bottomToBottom = 0;
                            baseTemplateCard2.mSecondaryCardPane.addView(bcSmartspaceCardSecondary, layoutParams);
                        }
                    }
                }
                baseTemplateCard = baseTemplateCard2;
            }
            viewHolder = new ViewHolder(i, null, smartspaceTarget, baseTemplateCard);
            viewGroup.addView(baseTemplateCard);
        } else {
            if (this.mEnableCardRecycling.get()) {
                bcSmartspaceCard = (BcSmartspaceCard) this.mRecycledLegacyCards.removeReturnOld(getFeatureType(smartspaceTarget));
            } else {
                bcSmartspaceCard = null;
            }
            if (bcSmartspaceCard == null || (this.mEnableReducedCardRecycling.get() && !useRecycledViewForNewTarget(smartspaceTarget, bcSmartspaceCard.mTarget))) {
                int featureType = getFeatureType(smartspaceTarget);
                LayoutInflater from2 = LayoutInflater.from(viewGroup.getContext());
                BcSmartspaceCard bcSmartspaceCard2 = (BcSmartspaceCard) from2.inflate(getBaseLegacyCardRes(featureType), viewGroup, false);
                int legacySecondaryCardRes = getLegacySecondaryCardRes(featureType);
                if (legacySecondaryCardRes != 0) {
                    BcSmartspaceCardSecondary bcSmartspaceCardSecondary2 = (BcSmartspaceCardSecondary) from2.inflate(legacySecondaryCardRes, (ViewGroup) bcSmartspaceCard2, false);
                    ViewGroup viewGroup3 = bcSmartspaceCard2.mSecondaryCardGroup;
                    if (viewGroup3 != null) {
                        bcSmartspaceCard2.mSecondaryCard = bcSmartspaceCardSecondary2;
                        BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup3, 8);
                        bcSmartspaceCard2.mSecondaryCardGroup.removeAllViews();
                        if (bcSmartspaceCardSecondary2 != null) {
                            ConstraintLayout.LayoutParams layoutParams2 = new ConstraintLayout.LayoutParams(-2, bcSmartspaceCard2.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height));
                            layoutParams2.setMarginStart(bcSmartspaceCard2.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_secondary_card_start_margin));
                            layoutParams2.startToStart = 0;
                            layoutParams2.topToTop = 0;
                            layoutParams2.bottomToBottom = 0;
                            bcSmartspaceCard2.mSecondaryCardGroup.addView(bcSmartspaceCardSecondary2, layoutParams2);
                        }
                    }
                }
                bcSmartspaceCard = bcSmartspaceCard2;
            }
            viewHolder = new ViewHolder(i, bcSmartspaceCard, smartspaceTarget, null);
            viewGroup.addView(bcSmartspaceCard);
        }
        onBindViewHolder(viewHolder);
        this.mViewHolders.put(i, viewHolder);
        return viewHolder;
    }

    public boolean isViewFromObject(View view, Object obj) {
        ViewHolder viewHolder = (ViewHolder) obj;
        return view == viewHolder.mLegacyCard || view == viewHolder.mCard;
    }

    public void onBindViewHolder(ViewHolder viewHolder) {
        BcSmartspaceSubcardLoggingInfo createSubcardLoggingInfo;
        BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier;
        TapAction tapAction;
        BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo;
        int i;
        String uuid;
        Drawable drawable;
        int i2;
        int i3;
        int i4;
        BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier;
        String uuid2;
        BcNextAlarmData bcNextAlarmData;
        int i5;
        int i6;
        String str;
        DoubleShadowIconDrawable doubleShadowIconDrawable;
        int i7;
        TapAction tapAction2;
        int i8;
        SmartspaceTarget smartspaceTarget = this.mSmartspaceTargets.get(viewHolder.mPosition);
        BcSmartspaceCardLoggingInfo.Builder builder = new BcSmartspaceCardLoggingInfo.Builder();
        builder.mInstanceId = InstanceId.create(smartspaceTarget);
        builder.mFeatureType = smartspaceTarget.getFeatureType();
        builder.mDisplaySurface = BcSmartSpaceUtil.getLoggingDisplaySurface(this.mRoot.getContext().getPackageName(), this.mIsDreaming, this.mDozeAmount);
        builder.mRank = viewHolder.mPosition;
        builder.mCardinality = this.mSmartspaceTargets.size();
        builder.mUid = BcSmartspaceCardLoggerUtil.getUid(this.mRoot.getContext().getPackageManager(), smartspaceTarget);
        if (smartspaceTarget.getTemplateData() != null) {
            createSubcardLoggingInfo = BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(smartspaceTarget.getTemplateData());
        } else {
            createSubcardLoggingInfo = BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(smartspaceTarget);
        }
        builder.mSubcardInfo = createSubcardLoggingInfo;
        BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo2 = new BcSmartspaceCardLoggingInfo(builder);
        if (smartspaceTarget.getTemplateData() != null) {
            BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureType(bcSmartspaceCardLoggingInfo2);
            BaseTemplateCard baseTemplateCard = viewHolder.mCard;
            if (baseTemplateCard == null) {
                Log.w("SsCardPagerAdapter", "No ui-template card view can be binded");
                return;
            }
            baseTemplateCard.mIsDreaming = this.mIsDreaming;
            baseTemplateCard.mUiSurface = this.mUiSurface;
            if (this.mDataProvider == null) {
                eventNotifier = null;
            } else {
                eventNotifier = smartspaceTargetEvent -> {
                    this.mDataProvider.notifySmartspaceEvent(smartspaceTargetEvent);
                };
            }
            BcNextAlarmData bcNextAlarmData2 = this.mNextAlarmData;
            if (!smartspaceTarget.getSmartspaceTargetId().equals(baseTemplateCard.mPrevSmartspaceTargetId)) {
                baseTemplateCard.mTarget = null;
                baseTemplateCard.mTemplateData = null;
                baseTemplateCard.mFeatureType = 0;
                baseTemplateCard.mLoggingInfo = null;
                baseTemplateCard.setOnClickListener(null);
                baseTemplateCard.resetTextView(baseTemplateCard.mTitleTextView);
                baseTemplateCard.resetTextView(baseTemplateCard.mSubtitleTextView);
                baseTemplateCard.resetTextView(baseTemplateCard.mSubtitleSupplementalView);
                baseTemplateCard.resetTextView(baseTemplateCard.mSupplementalLineTextView);
                baseTemplateCard.resetTextView(baseTemplateCard.mNextAlarmTextView);
                ImageView imageView = baseTemplateCard.mNextAlarmImageView;
                if (imageView != null) {
                    imageView.setImageDrawable(null);
                }
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mTitleTextView, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mSubtitleTextView, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mSubtitleSupplementalView, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mSecondaryCardPane, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mDndImageView, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mNextAlarmImageView, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mNextAlarmTextView, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mExtrasGroup, 4);
            }
            baseTemplateCard.mPrevSmartspaceTargetId = smartspaceTarget.getSmartspaceTargetId();
            baseTemplateCard.mTarget = smartspaceTarget;
            baseTemplateCard.mTemplateData = smartspaceTarget.getTemplateData();
            baseTemplateCard.mFeatureType = smartspaceTarget.getFeatureType();
            baseTemplateCard.mLoggingInfo = bcSmartspaceCardLoggingInfo2;
            baseTemplateCard.mShouldShowPageIndicator = this.mSmartspaceTargets.size() > 1;
            baseTemplateCard.mValidSecondaryCard = false;
            ViewGroup viewGroup = baseTemplateCard.mTextGroup;
            if (viewGroup != null) {
                viewGroup.setTranslationX(0.0f);
            }
            if (baseTemplateCard.mTemplateData == null) {
                doubleShadowIconDrawable = null;
                i6 = 8;
            } else {
                BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo3 = baseTemplateCard.mLoggingInfo;
                if (bcSmartspaceCardLoggingInfo3 == null) {
                    BcSmartspaceCardLoggingInfo.Builder builder2 = new BcSmartspaceCardLoggingInfo.Builder();
                    builder2.mDisplaySurface = BcSmartSpaceUtil.getLoggingDisplaySurface(baseTemplateCard.getContext().getPackageName(), baseTemplateCard.mIsDreaming, baseTemplateCard.mDozeAmount);
                    builder2.mFeatureType = baseTemplateCard.mFeatureType;
                    builder2.mUid = BcSmartspaceCardLoggerUtil.getUid(baseTemplateCard.getContext().getPackageManager(), baseTemplateCard.mTarget);
                    bcSmartspaceCardLoggingInfo3 = new BcSmartspaceCardLoggingInfo(builder2);
                }
                baseTemplateCard.mLoggingInfo = bcSmartspaceCardLoggingInfo3;
                if (baseTemplateCard.mSecondaryCard != null) {
                    Log.i("SsBaseTemplateCard", "Secondary card is not null");
                    BcSmartspaceCardSecondary bcSmartspaceCardSecondary = baseTemplateCard.mSecondaryCard;
                    String smartspaceTargetId = smartspaceTarget.getSmartspaceTargetId();
                    if (!bcSmartspaceCardSecondary.mPrevSmartspaceTargetId.equals(smartspaceTargetId)) {
                        bcSmartspaceCardSecondary.mPrevSmartspaceTargetId = smartspaceTargetId;
                        bcSmartspaceCardSecondary.resetUi();
                    }
                    baseTemplateCard.mValidSecondaryCard = baseTemplateCard.mSecondaryCard.setSmartspaceActions(smartspaceTarget, eventNotifier, baseTemplateCard.mLoggingInfo);
                }
                ViewGroup viewGroup2 = baseTemplateCard.mSecondaryCardPane;
                if (viewGroup2 != null) {
                    if (baseTemplateCard.mDozeAmount != 1.0f && baseTemplateCard.mValidSecondaryCard) {
                        i8 = 0;
                    } else {
                        i8 = 8;
                    }
                    BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup2, i8);
                }
                BaseTemplateData.SubItemInfo primaryItem = baseTemplateCard.mTemplateData.getPrimaryItem();
                if (baseTemplateCard.mDateView == null) {
                    bcNextAlarmData = bcNextAlarmData2;
                    i5 = 8;
                } else {
                    if (primaryItem != null && primaryItem.getTapAction() != null) {
                        uuid2 = primaryItem.getTapAction().getId().toString();
                    } else {
                        uuid2 = UUID.randomUUID().toString();
                    }
                    TapAction build = new TapAction.Builder(uuid2).setIntent(BcSmartSpaceUtil.getOpenCalendarIntent()).build();
                    bcNextAlarmData = bcNextAlarmData2;
                    i5 = 8;
                    BcSmartSpaceUtil.setOnClickListener(baseTemplateCard.mDateView, baseTemplateCard.mTarget, build, eventNotifier, "SsBaseTemplateCard", bcSmartspaceCardLoggingInfo2, 0);
                }
                baseTemplateCard.setUpTextView(baseTemplateCard.mTitleTextView, baseTemplateCard.mTemplateData.getPrimaryItem(), eventNotifier);
                baseTemplateCard.setUpTextView(baseTemplateCard.mSubtitleTextView, baseTemplateCard.mTemplateData.getSubtitleItem(), eventNotifier);
                baseTemplateCard.setUpTextView(baseTemplateCard.mSubtitleSupplementalView, baseTemplateCard.mTemplateData.getSubtitleSupplementalItem(), eventNotifier);
                BaseTemplateData.SubItemInfo supplementalAlarmItem = baseTemplateCard.mTemplateData.getSupplementalAlarmItem();
                ImageView imageView2 = baseTemplateCard.mNextAlarmImageView;
                if (imageView2 != null && baseTemplateCard.mNextAlarmTextView != null) {
                    if (bcNextAlarmData.mImage == null) {
                        BcSmartspaceTemplateDataUtils.updateVisibility(imageView2, i5);
                        BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mNextAlarmTextView, i5);
                        BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(baseTemplateCard.mNextAlarmImageView, null);
                    } else {
                        DoubleShadowIconDrawable doubleShadowIconDrawable2 = new DoubleShadowIconDrawable(baseTemplateCard.getContext());
                        doubleShadowIconDrawable2.setIcon(bcNextAlarmData.mImage);
                        baseTemplateCard.mNextAlarmImageView.setImageDrawable(doubleShadowIconDrawable2);
                        BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(baseTemplateCard.mNextAlarmImageView, doubleShadowIconDrawable2);
                        BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mNextAlarmImageView, 0);
                        String description = bcNextAlarmData.getDescription(supplementalAlarmItem);
                        baseTemplateCard.mNextAlarmTextView.setContentDescription(baseTemplateCard.getContext().getString(R.string.accessibility_next_alarm, description));
                        baseTemplateCard.mNextAlarmTextView.setText(description);
                        BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mNextAlarmTextView, 0);
                        if (supplementalAlarmItem == null) {
                            tapAction2 = null;
                        } else {
                            tapAction2 = supplementalAlarmItem.getTapAction();
                        }
                        bcNextAlarmData.setOnClickListener(baseTemplateCard.mNextAlarmImageView, tapAction2, eventNotifier, BcSmartSpaceUtil.getLoggingDisplaySurface(baseTemplateCard.getContext().getPackageName(), baseTemplateCard.mIsDreaming, baseTemplateCard.mDozeAmount));
                        bcNextAlarmData.setOnClickListener(baseTemplateCard.mNextAlarmTextView, tapAction2, eventNotifier, BcSmartSpaceUtil.getLoggingDisplaySurface(baseTemplateCard.getContext().getPackageName(), baseTemplateCard.mIsDreaming, baseTemplateCard.mDozeAmount));
                    }
                }
                baseTemplateCard.setUpTextView(baseTemplateCard.mSupplementalLineTextView, baseTemplateCard.mTemplateData.getSupplementalLineItem(), eventNotifier);
                baseTemplateCard.updateZenVisibility();
                if (baseTemplateCard.mTemplateData.getPrimaryItem() != null && baseTemplateCard.mTemplateData.getPrimaryItem().getTapAction() != null) {
                    i6 = i5;
                    str = "SsBaseTemplateCard";
                    i7 = 2;
                    doubleShadowIconDrawable = null;
                    BcSmartSpaceUtil.setOnClickListener(baseTemplateCard, smartspaceTarget, baseTemplateCard.mTemplateData.getPrimaryItem().getTapAction(), eventNotifier, "SsBaseTemplateCard", baseTemplateCard.mLoggingInfo, 0);
                } else {
                    i6 = i5;
                    str = "SsBaseTemplateCard";
                    doubleShadowIconDrawable = null;
                    i7 = 2;
                }
                ViewGroup viewGroup3 = baseTemplateCard.mSecondaryCardPane;
                if (viewGroup3 == null) {
                    Log.i(str, "Secondary card pane is null");
                } else {
                    ViewGroup.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) viewGroup3.getLayoutParams();
                    ((ConstraintLayout.LayoutParams) layoutParams).matchConstraintMaxWidth = baseTemplateCard.getWidth() / i7;
                    baseTemplateCard.mSecondaryCardPane.setLayoutParams(layoutParams);
                }
            }
            if (baseTemplateCard.mDndImageView != null) {
                if (this.mDndImage == null) {
                    BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mDndImageView, i6);
                    BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(baseTemplateCard.mDndImageView, doubleShadowIconDrawable);
                } else {
                    DoubleShadowIconDrawable doubleShadowIconDrawable3 = new DoubleShadowIconDrawable(baseTemplateCard.getContext());
                    doubleShadowIconDrawable3.setIcon(this.mDndImage.mutate());
                    baseTemplateCard.mDndImageView.setImageDrawable(doubleShadowIconDrawable3);
                    baseTemplateCard.mDndImageView.setContentDescription(this.mDndDescription);
                    BcSmartspaceTemplateDataUtils.updateVisibility(baseTemplateCard.mDndImageView, 0);
                    BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(baseTemplateCard.mDndImageView, doubleShadowIconDrawable3);
                }
                baseTemplateCard.updateZenVisibility();
            }
            baseTemplateCard.setPrimaryTextColor(this.mCurrentTextColor);
            baseTemplateCard.setDozeAmount(this.mDozeAmount);
            return;
        }
        BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureTypeAndInjectWeatherSubcard(bcSmartspaceCardLoggingInfo2, smartspaceTarget);
        BcSmartspaceCard bcSmartspaceCard = viewHolder.mLegacyCard;
        if (bcSmartspaceCard == null) {
            Log.w("SsCardPagerAdapter", "No legacy card view can be binded");
            return;
        }
        bcSmartspaceCard.mIsDreaming = this.mIsDreaming;
        if (this.mDataProvider == null) {
            smartspaceEventNotifier = null;
        } else {
            smartspaceEventNotifier = smartspaceTargetEvent2 -> {
                this.mDataProvider.notifySmartspaceEvent(smartspaceTargetEvent2);
            };
        }
        String smartspaceTargetId2 = smartspaceTarget.getSmartspaceTargetId();
        if (!bcSmartspaceCard.mPrevSmartspaceTargetId.equals(smartspaceTargetId2)) {
            bcSmartspaceCard.mPrevSmartspaceTargetId = smartspaceTargetId2;
            bcSmartspaceCard.mEventNotifier = null;
            BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mSecondaryCardGroup, 8);
            bcSmartspaceCard.mIconDrawable.setIcon(null);
            bcSmartspaceCard.updateZenVisibility();
            bcSmartspaceCard.setTitle(null, null, false);
            bcSmartspaceCard.setSubtitle(null, null, false);
            bcSmartspaceCard.updateIconTint();
            bcSmartspaceCard.setOnClickListener(null);
        }
        bcSmartspaceCard.mTarget = smartspaceTarget;
        bcSmartspaceCard.mEventNotifier = smartspaceEventNotifier;
        SmartspaceAction headerAction = smartspaceTarget.getHeaderAction();
        SmartspaceAction baseAction = smartspaceTarget.getBaseAction();
        bcSmartspaceCard.mUsePageIndicatorUi = this.mSmartspaceTargets.size() > 1;
        bcSmartspaceCard.mValidSecondaryCard = false;
        ViewGroup viewGroup4 = bcSmartspaceCard.mTextGroup;
        if (viewGroup4 != null) {
            viewGroup4.setTranslationX(0.0f);
        }
        if (headerAction != null) {
            BcSmartspaceCardSecondary bcSmartspaceCardSecondary2 = bcSmartspaceCard.mSecondaryCard;
            if (bcSmartspaceCardSecondary2 != null) {
                String smartspaceTargetId3 = smartspaceTarget.getSmartspaceTargetId();
                if (!bcSmartspaceCardSecondary2.mPrevSmartspaceTargetId.equals(smartspaceTargetId3)) {
                    bcSmartspaceCardSecondary2.mPrevSmartspaceTargetId = smartspaceTargetId3;
                    bcSmartspaceCardSecondary2.resetUi();
                }
                bcSmartspaceCard.mValidSecondaryCard = bcSmartspaceCard.mSecondaryCard.setSmartspaceActions(smartspaceTarget, bcSmartspaceCard.mEventNotifier, bcSmartspaceCardLoggingInfo2);
            }
            ViewGroup viewGroup5 = bcSmartspaceCard.mSecondaryCardGroup;
            if (bcSmartspaceCard.mDozeAmount != 1.0f && bcSmartspaceCard.mValidSecondaryCard) {
                i4 = 8;
            } else {
                i4 = 0;
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup5, i4);
            Drawable iconDrawable = BcSmartSpaceUtil.getIconDrawable(bcSmartspaceCard.getContext(), headerAction.getIcon());
            boolean z6 = iconDrawable != null;
            bcSmartspaceCard.mIconDrawable.setIcon(iconDrawable);
            CharSequence title = headerAction.getTitle();
            CharSequence subtitle = headerAction.getSubtitle();
            boolean z7 = smartspaceTarget.getFeatureType() == 1 || !TextUtils.isEmpty(title);
            boolean z11 = !TextUtils.isEmpty(subtitle);
            bcSmartspaceCard.updateZenVisibility();
            if (!z7) {
                title = subtitle;
            }
            CharSequence contentDescription = headerAction.getContentDescription();
            boolean z8 = z7 != z11 && z6;
            bcSmartspaceCard.setTitle(title, contentDescription, z8);
            if (!z7 || !z11) {
                subtitle = null;
            }
            bcSmartspaceCard.setSubtitle(subtitle, headerAction.getContentDescription(), z6);
            bcSmartspaceCard.updateIconTint();
        }
        if (bcSmartspaceCard.mBaseActionIconSubtitleView != null) {
            if (baseAction != null && baseAction.getIcon() != null) {
                drawable = BcSmartSpaceUtil.getIconDrawable(bcSmartspaceCard.getContext(), baseAction.getIcon());
            } else {
                drawable = null;
            }
            if (baseAction != null && baseAction.getIcon() != null && drawable != null) {
                drawable.setTintList(null);
                bcSmartspaceCard.mBaseActionIconSubtitleView.setText(baseAction.getSubtitle());
                bcSmartspaceCard.mBaseActionIconSubtitleView.setCompoundDrawablesRelative(drawable, null, null, null);
                BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mBaseActionIconSubtitleView, 0);
                if (baseAction.getExtras() != null && !baseAction.getExtras().isEmpty()) {
                    i2 = baseAction.getExtras().getInt("subcardType", -1);
                } else {
                    i2 = -1;
                }
                if (i2 != -1) {
                    i3 = BcSmartspaceCard.getClickedIndex(bcSmartspaceCardLoggingInfo2, i2);
                } else {
                    Log.d("BcSmartspaceCard", String.format("Subcard expected but missing type. loggingInfo=%s, baseAction=%s", bcSmartspaceCardLoggingInfo2, baseAction));
                    i3 = 0;
                }
                tapAction = null;
                bcSmartspaceCardLoggingInfo = bcSmartspaceCardLoggingInfo2;
                BcSmartSpaceUtil.setOnClickListener(bcSmartspaceCard.mBaseActionIconSubtitleView, smartspaceTarget, baseAction, bcSmartspaceCard.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo2, i3);
                bcSmartspaceCard.setFormattedContentDescription(bcSmartspaceCard.mBaseActionIconSubtitleView, baseAction.getSubtitle(), baseAction.getContentDescription());
            } else {
                tapAction = null;
                bcSmartspaceCardLoggingInfo = bcSmartspaceCardLoggingInfo2;
                BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mBaseActionIconSubtitleView, 4);
                bcSmartspaceCard.mBaseActionIconSubtitleView.setOnClickListener(null);
                bcSmartspaceCard.mBaseActionIconSubtitleView.setContentDescription(null);
            }
        } else {
            tapAction = null;
            bcSmartspaceCardLoggingInfo = bcSmartspaceCardLoggingInfo2;
        }
        if (bcSmartspaceCard.mDateView != null) {
            if (headerAction != null) {
                uuid = headerAction.getId();
            } else if (baseAction != null) {
                uuid = baseAction.getId();
            } else {
                uuid = UUID.randomUUID().toString();
            }
            BcSmartSpaceUtil.setOnClickListener(bcSmartspaceCard.mDateView, smartspaceTarget, new SmartspaceAction.Builder(uuid, "unusedTitle").setIntent(BcSmartSpaceUtil.getOpenCalendarIntent()).build(), bcSmartspaceCard.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, 0);
        }
        if (headerAction != null && (headerAction.getIntent() != null || headerAction.getPendingIntent() != null)) {
            if (smartspaceTarget.getFeatureType() == 1 && bcSmartspaceCardLoggingInfo.mFeatureType == 39) {
                i = BcSmartspaceCard.getClickedIndex(bcSmartspaceCardLoggingInfo, 1);
            } else {
                i = 0;
            }
            BcSmartSpaceUtil.setOnClickListener(bcSmartspaceCard, smartspaceTarget, headerAction, bcSmartspaceCard.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, i);
        } else if (baseAction != null && (baseAction.getIntent() != null || baseAction.getPendingIntent() != null)) {
            BcSmartSpaceUtil.setOnClickListener(bcSmartspaceCard, smartspaceTarget, baseAction, bcSmartspaceCard.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, 0);
        } else {
            BcSmartSpaceUtil.setOnClickListener(bcSmartspaceCard, smartspaceTarget, headerAction, bcSmartspaceCard.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, 0);
        }
        ViewGroup viewGroup6 = bcSmartspaceCard.mSecondaryCardGroup;
        if (viewGroup6 != null) {
            ViewGroup.LayoutParams layoutParams2 = (ConstraintLayout.LayoutParams) viewGroup6.getLayoutParams();
            if (getFeatureType(smartspaceTarget) == -2) {
                ((ConstraintLayout.LayoutParams) layoutParams2).matchConstraintMaxWidth = (bcSmartspaceCard.getWidth() * 3) / 4;
            } else {
                ((ConstraintLayout.LayoutParams) layoutParams2).matchConstraintMaxWidth = bcSmartspaceCard.getWidth() / 2;
            }
            bcSmartspaceCard.mSecondaryCardGroup.setLayoutParams(layoutParams2);
        }
        bcSmartspaceCard.setPrimaryTextColor(this.mCurrentTextColor);
        bcSmartspaceCard.setDozeAmount(this.mDozeAmount);
        Drawable drawable3 = this.mDndImage;
        ImageView imageView4 = bcSmartspaceCard.mDndImageView;
        if (imageView4 != null) {
            if (drawable3 == null) {
                BcSmartspaceTemplateDataUtils.updateVisibility(imageView4, 8);
                BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(bcSmartspaceCard.mDndImageView, null);
            } else {
                bcSmartspaceCard.mDndIconDrawable.setIcon(drawable3.mutate());
                bcSmartspaceCard.mDndImageView.setImageDrawable(bcSmartspaceCard.mDndIconDrawable);
                bcSmartspaceCard.mDndImageView.setContentDescription(this.mDndDescription);
                BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(bcSmartspaceCard.mDndImageView, bcSmartspaceCard.mDndIconDrawable);
                BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mDndImageView, 0);
            }
            bcSmartspaceCard.updateZenVisibility();
        }
        BcNextAlarmData bcNextAlarmData3 = this.mNextAlarmData;
        ImageView imageView5 = bcSmartspaceCard.mNextAlarmImageView;
        if (imageView5 != null && bcSmartspaceCard.mNextAlarmTextView != null) {
            Drawable drawable4 = bcNextAlarmData3.mImage;
            if (drawable4 == null) {
                BcSmartspaceTemplateDataUtils.updateVisibility(imageView5, 8);
                BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mNextAlarmTextView, 8);
                BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(bcSmartspaceCard.mNextAlarmImageView, null);
            } else {
                bcSmartspaceCard.mNextAlarmIconDrawable.setIcon(drawable4);
                bcSmartspaceCard.mNextAlarmImageView.setImageDrawable(bcSmartspaceCard.mNextAlarmIconDrawable);
                BcSmartspaceTemplateDataUtils.offsetImageViewForIcon(bcSmartspaceCard.mNextAlarmImageView, bcSmartspaceCard.mNextAlarmIconDrawable);
                BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mNextAlarmImageView, 0);
                String description2 = bcNextAlarmData3.getDescription(null);
                bcSmartspaceCard.mNextAlarmTextView.setContentDescription(bcSmartspaceCard.getContext().getString(R.string.accessibility_next_alarm, description2));
                bcSmartspaceCard.mNextAlarmTextView.setText(description2);
                BcSmartspaceTemplateDataUtils.updateVisibility(bcSmartspaceCard.mNextAlarmTextView, 0);
                bcNextAlarmData3.setOnClickListener(bcSmartspaceCard.mNextAlarmImageView, tapAction, bcSmartspaceCard.mEventNotifier, BcSmartSpaceUtil.getLoggingDisplaySurface(bcSmartspaceCard.getContext().getPackageName(), bcSmartspaceCard.mIsDreaming, bcSmartspaceCard.mDozeAmount));
                bcNextAlarmData3.setOnClickListener(bcSmartspaceCard.mNextAlarmTextView, tapAction, bcSmartspaceCard.mEventNotifier, BcSmartSpaceUtil.getLoggingDisplaySurface(bcSmartspaceCard.getContext().getPackageName(), bcSmartspaceCard.mIsDreaming, bcSmartspaceCard.mDozeAmount));
            }
            bcSmartspaceCard.updateZenVisibility();
        }
    }

    public void setDataProvider(BcSmartspaceDataPlugin plugin) {
        this.mDataProvider = plugin;
    }

    public void setPrimaryTextColor(int i) {
        this.mPrimaryTextColor = i;
        setDozeAmount(this.mDozeAmount);
    }

    public void setDnd(Drawable drawable, String str) {
        this.mDndImage = drawable;
        this.mDndDescription = str;
        refreshCards();
    }

    public void setNextAlarm(Drawable drawable, String str) {
        BcNextAlarmData bcNextAlarmData = this.mNextAlarmData;
        bcNextAlarmData.mImage = drawable;
        if (drawable != null) {
            drawable.mutate();
        }
        bcNextAlarmData.mDescription = str;
        refreshCards();
    }

    public void setMediaTarget(SmartspaceTarget smartspaceTarget) {
        this.mMediaTargets.clear();
        if (smartspaceTarget != null) {
            this.mMediaTargets.add(smartspaceTarget);
        }
        updateTargetVisibility();
    }

    public void setDozeAmount(float f) {
        this.mCurrentTextColor = ColorUtils.blendARGB(this.mPrimaryTextColor, this.mDozeColor, f);
        this.mLastDozeAmount = this.mDozeAmount;
        this.mDozeAmount = f;
        updateTargetVisibility();
        for (int i = 0; i < this.mViewHolders.size(); i++) {
            SparseArray<ViewHolder> sparseArray = this.mViewHolders;
            ViewHolder viewHolder = sparseArray.get(sparseArray.keyAt(i));
            if (viewHolder != null) {
                BcSmartspaceCard bcSmartspaceCard = viewHolder.mLegacyCard;
                if (bcSmartspaceCard != null) {
                    bcSmartspaceCard.setPrimaryTextColor(this.mCurrentTextColor);
                    bcSmartspaceCard.setDozeAmount(this.mDozeAmount);
                }
                BaseTemplateCard baseTemplateCard = viewHolder.mCard;
                if (baseTemplateCard != null) {
                    baseTemplateCard.setPrimaryTextColor(this.mCurrentTextColor);
                    baseTemplateCard.setDozeAmount(this.mDozeAmount);
                }
            }
        }
    }

    public void updateTargetVisibility() {
        ArrayList<SmartspaceTarget> targets;
        ArrayList<SmartspaceTarget> targets2;
        if (Float.compare(this.mDozeAmount, 1.0f) == 0) {
            if (isMediaPreferred(this.mAODTargets)) {
                targets2 = this.mMediaTargets;
            } else {
                targets2 = this.mAODTargets;
            }
            this.mSmartspaceTargets = targets2;
            notifyDataSetChanged();
            return;
        }
        if (isMediaPreferred(this.mLockscreenTargets) && this.mKeyguardBypassEnabled) {
            targets = this.mMediaTargets;
        } else {
            targets = this.mLockscreenTargets;
        }
        this.mSmartspaceTargets = targets;
        if (Float.compare(this.mLastDozeAmount, 0.0f) == 0 || Float.compare(this.mLastDozeAmount, 1.0f) == 0 || Float.compare(this.mDozeAmount, 0.0f) == 0) {
            notifyDataSetChanged();
        }
    }

    public static int getFeatureType(SmartspaceTarget target) {
        List<SmartspaceAction> actionChips = target.getActionChips();
        int featureType = target.getFeatureType();
        if (actionChips != null && !actionChips.isEmpty()) {
            if (featureType != 13 || actionChips.size() != 1) {
                return -1;
            }
            return -2;
        }
        return featureType;
    }

    public void addDefaultDateCardIfEmpty(ArrayList<SmartspaceTarget> targets) {
        if (targets.isEmpty()) {
            targets.add(new SmartspaceTarget.Builder("date_card_794317_92634", new ComponentName(this.mRoot.getContext(), CardPagerAdapter.class), this.mRoot.getContext().getUser()).setFeatureType(1).build());
        }
    }

    public boolean isMediaPreferred(ArrayList<SmartspaceTarget> targets) {
        return targets.size() == 1 && targets.get(0).getFeatureType() == 1 && !this.mMediaTargets.isEmpty();
    }

    public static class ViewHolder {
        public final BaseTemplateCard mCard;
        public final BcSmartspaceCard mLegacyCard;
        public final int mPosition;
        public SmartspaceTarget mTarget;

        public ViewHolder(int position, BcSmartspaceCard legacyCard, SmartspaceTarget target, BaseTemplateCard card) {
            this.mPosition = position;
            this.mLegacyCard = legacyCard;
            this.mTarget = target;
            this.mCard = card;
        }
    }
}
