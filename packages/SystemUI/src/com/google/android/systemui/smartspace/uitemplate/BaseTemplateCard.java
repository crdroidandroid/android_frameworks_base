package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.TapAction;
import android.app.smartspace.uitemplatedata.Text;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.app.animation.Interpolators;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.smartspace.nano.SmartspaceProto;

import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.DoubleShadowIconDrawable;
import com.google.android.systemui.smartspace.DoubleShadowTextView;
import com.google.android.systemui.smartspace.IcuDateTextView;
import com.google.android.systemui.smartspace.SmartspaceCard;
import com.google.android.systemui.smartspace.TouchDelegateComposite;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardMetadataLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.utils.ContentDescriptionUtil;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BaseTemplateCard extends ConstraintLayout implements SmartspaceCard {
    public Handler mBgHandler;
    public IcuDateTextView mDateView;
    public float mDozeAmount;
    public ViewGroup mExtrasGroup;
    public int mFeatureType;
    public int mIconTintColor;
    public BcSmartspaceCardLoggingInfo mLoggingInfo;
    public BcSmartspaceCardSecondary mSecondaryCard;
    public ViewGroup mSecondaryCardPane;
    public boolean mShouldShowPageIndicator;
    public ViewGroup mSubtitleGroup;
    public Rect mSubtitleHitRect;
    public Rect mSubtitleSupplementalHitRect;
    public DoubleShadowTextView mSubtitleSupplementalView;
    public DoubleShadowTextView mSubtitleTextView;
    public DoubleShadowTextView mSupplementalLineTextView;
    public SmartspaceTarget mTarget;
    public BaseTemplateData mTemplateData;
    public ViewGroup mTextGroup;
    public DoubleShadowTextView mTitleTextView;
    public final TouchDelegateComposite mTouchDelegateComposite;
    public boolean mTouchDelegateIsDirty;
    public String mUiSurface;
    public boolean mValidSecondaryCard;

    public BaseTemplateCard(Context context) {
        this(context, null);
    }

    public static boolean shouldTint(BaseTemplateData.SubItemInfo subItemInfo) {
        if (subItemInfo == null || subItemInfo.getIcon() == null) {
            return false;
        }
        return subItemInfo.getIcon().shouldTint();
    }

    @Override
    public final void bindData(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo, boolean shouldShowPageIndicator) {
        mTarget = null;
        mTemplateData = null;
        mFeatureType = 0;
        mLoggingInfo = null;
        setOnClickListener(null);
        setClickable(false);
        if (mDateView != null) {
            mDateView.setOnClickListener(null);
            mDateView.setClickable(false);
        }
        resetTextView(mTitleTextView);
        resetTextView(mSubtitleTextView);
        resetTextView(mSubtitleSupplementalView);
        resetTextView(mSupplementalLineTextView);
        BcSmartspaceTemplateDataUtils.updateVisibility(mTitleTextView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSubtitleGroup, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSubtitleTextView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSubtitleSupplementalView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondaryCardPane, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mExtrasGroup, View.GONE);
        mTarget = target;
        mTemplateData = target.getTemplateData();
        mFeatureType = target.getFeatureType();
        mLoggingInfo = loggingInfo;
        mShouldShowPageIndicator = shouldShowPageIndicator;
        mValidSecondaryCard = false;
        if (mTextGroup != null) {
            mTextGroup.setTranslationX(0.0f);
        }
        if (mTemplateData == null) {
            return;
        }
        mLoggingInfo = getLoggingInfo();
        if (mSecondaryCard != null) {
            Log.i("SsBaseTemplateCard", "Secondary card is not null");
            mSecondaryCard.reset(target.getSmartspaceTargetId());
            mValidSecondaryCard = mSecondaryCard.setSmartspaceActions(target, eventNotifier, mLoggingInfo);
        }
        if (mSecondaryCardPane != null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(mSecondaryCardPane, (mDozeAmount == 1.0f || !mValidSecondaryCard) ? View.GONE : View.VISIBLE);
        }
        if (mDateView == null) {
            Log.d("SsBaseTemplateCard", "No date view can be set up");
        } else {
            if (TextUtils.isEmpty(mDateView.getText())) {
                Log.d("SsBaseTemplateCard", "Date view text is empty");
            }
            TapAction tapAction = new TapAction.Builder((mTemplateData.getPrimaryItem() == null || mTemplateData.getPrimaryItem().getTapAction() == null) ? UUID.randomUUID().toString() : mTemplateData.getPrimaryItem().getTapAction().getId().toString()).setIntent(BcSmartSpaceUtil.getOpenCalendarIntent()).build();
            BcSmartSpaceUtil.setOnClickListener(this, mTarget, tapAction, eventNotifier, "SsBaseTemplateCard", loggingInfo, 0);
            BcSmartSpaceUtil.setOnClickListener(mDateView, mTarget, tapAction, eventNotifier, "SsBaseTemplateCard", loggingInfo, 0);
        }
        setUpTextView(mTitleTextView, mTemplateData.getPrimaryItem(), eventNotifier, mDateView == null);
        setUpTextView(mSubtitleTextView, mTemplateData.getSubtitleItem(), eventNotifier, true);
        setUpTextView(mSubtitleSupplementalView, mTemplateData.getSubtitleSupplementalItem(), eventNotifier, true);
        setUpTextView(mSupplementalLineTextView, mTemplateData.getSupplementalLineItem(), eventNotifier, true);
        if (mExtrasGroup != null) {
            if (mSupplementalLineTextView == null || mSupplementalLineTextView.getVisibility() != View.VISIBLE || (mShouldShowPageIndicator && mDateView == null)) {
                BcSmartspaceTemplateDataUtils.updateVisibility(mExtrasGroup, View.GONE);
            } else {
                BcSmartspaceTemplateDataUtils.updateVisibility(mExtrasGroup, View.VISIBLE);
                updateZenColors();
            }
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(mSubtitleGroup, mSubtitleTextView.getVisibility() != View.GONE || mSubtitleSupplementalView.getVisibility() != View.GONE ? View.VISIBLE : View.GONE);
        if (target.getFeatureType() == 1 && mSubtitleSupplementalView != null && mSubtitleSupplementalView.getVisibility() == View.VISIBLE) {
            mSubtitleTextView.setEllipsize(null);
        }
        if (mDateView == null && mTemplateData.getPrimaryItem() != null && mTemplateData.getPrimaryItem().getTapAction() != null) {
            BcSmartSpaceUtil.setOnClickListener(this, target, mTemplateData.getPrimaryItem().getTapAction(), eventNotifier, "SsBaseTemplateCard", mLoggingInfo, 0);
            if (mDateView == null && mTitleTextView != null && mTitleTextView.getVisibility() == View.VISIBLE && mSubtitleTextView != null && mSubtitleTextView.getVisibility() == View.VISIBLE && mTemplateData.getPrimaryItem() != null && mTemplateData.getPrimaryItem().getTapAction() != null && mTemplateData.getSubtitleItem() != null && mTemplateData.getSubtitleItem().getTapAction() != null) {
                if (mTemplateData.getPrimaryItem().getTapAction().getIntent() != null && !mTemplateData.getPrimaryItem().getTapAction().getIntent().filterEquals(mTemplateData.getSubtitleItem().getTapAction().getIntent())) {
                    Log.d("SsBaseTemplateCard", "Primary item tapAction intent = " + mTemplateData.getPrimaryItem().getTapAction().getIntent());
                    Log.d("SsBaseTemplateCard", "Subtitle item tapAction intent = " + mTemplateData.getSubtitleItem().getTapAction().getIntent());
                } else if (mTemplateData.getPrimaryItem().getTapAction().getPendingIntent() == null || mTemplateData.getPrimaryItem().getTapAction().getPendingIntent().equals(mTemplateData.getSubtitleItem().getTapAction().getPendingIntent())) {
                    mTitleTextView.setOnClickListener(null);
                    mTitleTextView.setClickable(false);
                    mSubtitleTextView.setOnClickListener(null);
                    mSubtitleTextView.setClickable(false);
                } else {
                    Log.d("SsBaseTemplateCard", "Primary item tapAction pendingIntent = " + mTemplateData.getPrimaryItem().getTapAction().getPendingIntent());
                    Log.d("SsBaseTemplateCard", "Subtitle item tapAction pendingIntent = " + mTemplateData.getSubtitleItem().getTapAction().getPendingIntent());
                }
            }
        }
        if (mSecondaryCardPane == null) {
            Log.i("SsBaseTemplateCard", "Secondary card pane is null");
            return;
        }
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) mSecondaryCardPane.getLayoutParams();
        params.matchConstraintMaxWidth = getWidth() / 2;
        mSecondaryCardPane.setLayoutParams(params);
        mTouchDelegateIsDirty = true;
    }

    @Override
    public final AccessibilityNodeInfo createAccessibilityNodeInfo() {
        AccessibilityNodeInfo info = super.createAccessibilityNodeInfo();
        AccessibilityNodeInfoCompat.wrap(info).setRoleDescription(" ");
        return info;
    }

    @Override
    public final BcSmartspaceCardLoggingInfo getLoggingInfo() {
        if (mLoggingInfo != null) {
            return mLoggingInfo;
        }
        BcSmartspaceCardLoggingInfo.Builder builder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(mUiSurface, mDozeAmount))
                        .setFeatureType(mFeatureType)
                        .setUid(-1);
        builder.setDimensionalInfo(
                BcSmartspaceCardLoggerUtil.createDimensionalLoggingInfo(mTarget.getTemplateData()));
        return new BcSmartspaceCardLoggingInfo(builder);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        setPaddingRelative(getResources().getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start), getPaddingTop(), getPaddingEnd(), getPaddingBottom());
        mTextGroup = findViewById(R.id.text_group);
        mSecondaryCardPane = findViewById(R.id.secondary_card_group);
        mDateView = findViewById(R.id.date);
        mTitleTextView = findViewById(R.id.title_text);
        mSubtitleGroup = findViewById(R.id.smartspace_subtitle_group);
        mSubtitleTextView = findViewById(R.id.subtitle_text);
        mSubtitleSupplementalView = findViewById(R.id.base_action_icon_subtitle);
        mExtrasGroup = findViewById(R.id.smartspace_extras_group);
        if (mSubtitleTextView != null) {
            mSubtitleHitRect = new Rect();
        }
        if (mSubtitleSupplementalView != null) {
            mSubtitleSupplementalHitRect = new Rect();
        }
        if (mSubtitleTextView != null || mSubtitleSupplementalView != null) {
            setTouchDelegate(mTouchDelegateComposite);
        }
        if (mExtrasGroup != null) {
            mSupplementalLineTextView = mExtrasGroup.findViewById(R.id.supplemental_line_text);
        }
        if (mBgHandler != null) {
            mDateView.mBgHandler = mBgHandler;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!changed && !mTouchDelegateIsDirty) {
            return;
        }
        mTouchDelegateIsDirty = false;
        TouchDelegate touchDelegate = getTouchDelegate();
        if (touchDelegate == null || !(touchDelegate instanceof TouchDelegateComposite)) {
            return;
        }
        TouchDelegateComposite composite = (TouchDelegateComposite) touchDelegate;
        composite.mDelegates.clear();
        if (mSubtitleGroup == null || mSubtitleGroup.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean subtitleTextVisible = mSubtitleTextView != null 
                && mSubtitleTextView.getVisibility() == View.VISIBLE
                && mSubtitleTextView.hasOnClickListeners();
        boolean subtitleSupplementalVisible = mSubtitleSupplementalView != null
                && mSubtitleSupplementalView.getVisibility() == View.VISIBLE
                && mSubtitleSupplementalView.hasOnClickListeners();
        if (!subtitleTextVisible && !subtitleSupplementalVisible) {
            return;
        }
        int padding =
                getResources().getDimensionPixelSize(R.dimen.subtitle_hit_rect_height)
                        - mSubtitleGroup.getHeight();
        padding = Math.max(padding / 2, 0);
        if (padding <= 0 && mSubtitleGroup.getBottom() == getHeight()) {
            return;
        }
        if (subtitleTextVisible) {
            mSubtitleTextView.getHitRect(mSubtitleHitRect);
            offsetDescendantRectToMyCoords(mSubtitleGroup, mSubtitleHitRect);
            if (padding > 0) {
                mSubtitleHitRect.top -= padding;
            }
            mSubtitleHitRect.bottom = getBottom();
            composite.mDelegates.add(new TouchDelegate(mSubtitleHitRect, mSubtitleTextView));
        }
        if (subtitleSupplementalVisible) {
            mSubtitleSupplementalView.getHitRect(mSubtitleSupplementalHitRect);
            offsetDescendantRectToMyCoords(mSubtitleGroup, mSubtitleSupplementalHitRect);
            if (padding > 0) {
                mSubtitleSupplementalHitRect.top -= padding;
            }
            mSubtitleSupplementalHitRect.bottom = getBottom();
            composite.mDelegates.add(
                    new TouchDelegate(mSubtitleSupplementalHitRect, mSubtitleSupplementalView));
        }
    }

    public final void resetTextView(DoubleShadowTextView textView) {
        if (textView == null) {
            return;
        }
        textView.setCompoundDrawablesRelative(null, null, null, null);
        textView.setOnClickListener(null);
        textView.setClickable(false);
        textView.setContentDescription(null);
        textView.setText((CharSequence) null);
        textView.setTranslationX(0.0f);
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
        mDozeAmount = dozeAmount;
        if (mTarget != null && mTarget.getBaseAction() != null && mTarget.getBaseAction().getExtras() != null) {
            Bundle extras = mTarget.getBaseAction().getExtras();
            if (mTitleTextView != null && extras.getBoolean("hide_title_on_aod")) {
                mTitleTextView.setAlpha(1.0f - dozeAmount);
            }
            if (mSubtitleTextView != null && extras.getBoolean("hide_subtitle_on_aod")) {
                mSubtitleTextView.setAlpha(1.0f - dozeAmount);
            }
        }
        if (mTextGroup == null) {
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondaryCardPane, (mDozeAmount == 1.0f || !mValidSecondaryCard) ? View.GONE : View.VISIBLE);
        if (mSecondaryCardPane == null || mSecondaryCardPane.getVisibility() == View.GONE) {
            mTextGroup.setTranslationX(0.0f);
            return;
        }
        mTextGroup.setTranslationX(((PathInterpolator) Interpolators.EMPHASIZED).getInterpolation(mDozeAmount) * mSecondaryCardPane.getWidth() * (isRtl() ? 1 : -1));
        mSecondaryCardPane.setAlpha(Math.max(0.0f, Math.min(1.0f, ((1.0f - mDozeAmount) * 9.0f) - 6.0f)));
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        mIconTintColor = color;
        if (mTitleTextView != null) {
            mTitleTextView.setTextColor(color);
            if (mTemplateData != null) {
                updateTextViewIconTint(mTitleTextView, shouldTint(mTemplateData.getPrimaryItem()));
            }
        }
        if (mDateView != null) {
            mDateView.setTextColor(color);
        }
        if (mSubtitleTextView != null) {
            mSubtitleTextView.setTextColor(color);
            if (mTemplateData != null) {
                updateTextViewIconTint(mSubtitleTextView, shouldTint(mTemplateData.getSubtitleItem()));
            }
        }
        if (mSubtitleSupplementalView != null) {
            mSubtitleSupplementalView.setTextColor(color);
            if (mTemplateData != null) {
                updateTextViewIconTint(mSubtitleSupplementalView, shouldTint(mTemplateData.getSubtitleSupplementalItem()));
            }
        }
        updateZenColors();
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
        if (mDateView != null) {
            mDateView.mIsInteractive = screenOn;
            mDateView.rescheduleTicker();
        }
    }
    public final void setSecondaryCard(BcSmartspaceCardSecondary secondaryCard) {
        if (mSecondaryCardPane == null) {
            return;
        }
        mSecondaryCard = secondaryCard;
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondaryCardPane, View.GONE);
        mSecondaryCardPane.removeAllViews();
        if (secondaryCard != null) {
            ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height));
            params.setMarginStart(getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_secondary_card_start_margin));
            params.startToStart = 0;
            params.topToTop = 0;
            params.bottomToBottom = 0;
            mSecondaryCardPane.addView(secondaryCard, params);
        }
    }

    public final void setUpTextView(DoubleShadowTextView textView, BaseTemplateData.SubItemInfo subItemInfo, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, boolean z) {
        if (textView == null) {
            Log.d("SsBaseTemplateCard", "No text view can be set up");
            return;
        }
        resetTextView(textView);
        if (subItemInfo == null) {
            Log.d("SsBaseTemplateCard", "Passed-in item info is null");
            BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.GONE);
            return;
        }
        Text text = subItemInfo.getText();
        BcSmartspaceTemplateDataUtils.setText(textView, subItemInfo.getText());
        if (!SmartspaceUtils.isEmpty(text)) {
            textView.setTextColor(this.mIconTintColor);
        }
        Icon icon = subItemInfo.getIcon();
        if (icon != null) {
            DoubleShadowIconDrawable drawable = new DoubleShadowIconDrawable(getContext());
            drawable.setIcon(BcSmartSpaceUtil.getIconDrawableWithCustomSize(icon.getIcon(), getContext(), getContext().getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size)));
            textView.setCompoundDrawablesRelative(drawable, null, null, null);
            ContentDescriptionUtil.setFormattedContentDescription("SsBaseTemplateCard", textView, SmartspaceUtils.isEmpty(text) ? "" : text.getText(), icon.getContentDescription());
            updateTextViewIconTint(textView, icon.shouldTint());
            if (z) {
                BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(textView, drawable, isRtl());
            }
        }
        int subCardRank = 0;
        BcSmartspaceTemplateDataUtils.updateVisibility(textView, View.VISIBLE);
        TapAction tapAction = subItemInfo.getTapAction();
        if (mLoggingInfo != null && mLoggingInfo.mSubcardInfo != null && mLoggingInfo.mSubcardInfo.mSubcards != null && !mLoggingInfo.mSubcardInfo.mSubcards.isEmpty() && subItemInfo.getLoggingInfo() != null) {
            int targetFeatureType = subItemInfo.getLoggingInfo().getFeatureType();
            if (targetFeatureType != mLoggingInfo.mFeatureType) {
                for (int i = 0; i < mLoggingInfo.mSubcardInfo.mSubcards.size(); i++) {
                    BcSmartspaceCardMetadataLoggingInfo subCard =
                            mLoggingInfo.mSubcardInfo.mSubcards.get(i);
                    if (subCard.mInstanceId == subItemInfo.getLoggingInfo().getInstanceId()
                            && subCard.mCardTypeId == targetFeatureType) {
                        subCardRank = i + 1;
                        break;
                    }
                }
            }
        }
        BcSmartSpaceUtil.setOnClickListener(textView, mTarget, tapAction, eventNotifier, "SsBaseTemplateCard", mLoggingInfo, subCardRank);
    }

    public final void updateTextViewIconTint(DoubleShadowTextView textView, boolean shouldTint) {
        for (Drawable drawable : textView.getCompoundDrawablesRelative()) {
            if (drawable != null) {
                if (shouldTint) {
                    drawable.setTint(mIconTintColor);
                } else {
                    drawable.setTintList(null);
                }
            }
        }
    }

    public final void updateZenColors() {
        if (mSupplementalLineTextView != null) {
            mSupplementalLineTextView.setTextColor(mIconTintColor);
            if (BcSmartspaceCardLoggerUtil.containsValidTemplateType(mTemplateData)) {
                updateTextViewIconTint(mSupplementalLineTextView, shouldTint(mTemplateData.getSupplementalLineItem()));
            }
        }
    }

    public BaseTemplateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSecondaryCard = null;
        mFeatureType = 0;
        mLoggingInfo = null;
        mIconTintColor = GraphicsUtils.getAttrColor(context, android.R.attr.textColorPrimary);
        mTextGroup = null;
        mSecondaryCardPane = null;
        mDateView = null;
        mTitleTextView = null;
        mSubtitleGroup = null;
        mSubtitleTextView = null;
        mSubtitleSupplementalView = null;
        mSubtitleHitRect = null;
        mSubtitleSupplementalHitRect = null;
        mExtrasGroup = null;
        mSupplementalLineTextView = null;
        mTouchDelegateComposite = new TouchDelegateComposite(new Rect(), this);
        mTouchDelegateIsDirty = false;
        context.getTheme().applyStyle(R.style.Smartspace, false);
        setDefaultFocusHighlightEnabled(false);
    }

    @Override
    public final View getView() {
        return this;
    }
}
