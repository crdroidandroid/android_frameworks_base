package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.android.app.animation.Interpolators;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardMetadataLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.utils.ContentDescriptionUtil;
import com.android.systemui.res.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* compiled from: go/retraceme af8e0b46c0cb0ee2c99e9b6d0c434e5c0b686fd9230eaab7fb9a40e3a9d0cf6f */
/* loaded from: classes2.dex */
public class BcSmartspaceCard extends ConstraintLayout implements SmartspaceCard {
    public final DoubleShadowIconDrawable mBaseActionIconDrawable;
    public Rect mBaseActionIconSubtitleHitRect;
    public DoubleShadowTextView mBaseActionIconSubtitleView;
    public float mDozeAmount;
    public BcSmartspaceDataPlugin.SmartspaceEventNotifier mEventNotifier;
    public final DoubleShadowIconDrawable mIconDrawable;
    public int mIconTintColor;
    public BcSmartspaceCardLoggingInfo mLoggingInfo;
    public BcSmartspaceCardSecondary mSecondaryCard;
    public ViewGroup mSecondaryCardGroup;
    public TextView mSubtitleTextView;
    public SmartspaceTarget mTarget;
    public ViewGroup mTextGroup;
    public TextView mTitleTextView;
    public boolean mTouchDelegateIsDirty;
    public String mUiSurface;
    public boolean mUsePageIndicatorUi;
    public boolean mValidSecondaryCard;

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    public BcSmartspaceCard(Context context) {
        this(context, null);
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    public static int getClickedIndex(BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo, int i) {
        List list;
        BcSmartspaceSubcardLoggingInfo bcSmartspaceSubcardLoggingInfo = bcSmartspaceCardLoggingInfo.mSubcardInfo;
        if (bcSmartspaceSubcardLoggingInfo != null && (list = bcSmartspaceSubcardLoggingInfo.mSubcards) != null) {
            int i2 = 0;
            while (true) {
                ArrayList arrayList = (ArrayList) list;
                if (i2 >= arrayList.size()) {
                    break;
                }
                BcSmartspaceCardMetadataLoggingInfo bcSmartspaceCardMetadataLoggingInfo = (BcSmartspaceCardMetadataLoggingInfo) arrayList.get(i2);
                if (bcSmartspaceCardMetadataLoggingInfo != null && bcSmartspaceCardMetadataLoggingInfo.mCardTypeId == i) {
                    return i2 + 1;
                }
                i2++;
            }
        }
        return 0;
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    @Override // com.google.android.systemui.smartspace.SmartspaceCard
    public final void bindData(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo, boolean z) {
        SmartspaceAction smartspaceAction;
        BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo2 = bcSmartspaceCardLoggingInfo;
        Drawable drawable = null;
        this.mLoggingInfo = null;
        this.mEventNotifier = null;
        int i = 8;
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mSecondaryCardGroup, 8);
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mBaseActionIconSubtitleView, 8);
        this.mIconDrawable.mIconDrawable = null;
        this.mBaseActionIconDrawable.mIconDrawable = null;
        int i2 = 0;
        setTitle(null, null, false);
        setSubtitle(null, null, false);
        setBaseActionIconSubtitle(null, null, null);
        updateIconTint();
        setOnClickListener(null);
        TextView textView = this.mTitleTextView;
        if (textView != null) {
            textView.setOnClickListener(null);
            textView.setClickable(false);
        }
        TextView textView2 = this.mSubtitleTextView;
        if (textView2 != null) {
            textView2.setOnClickListener(null);
            textView2.setClickable(false);
        }
        DoubleShadowTextView doubleShadowTextView = this.mBaseActionIconSubtitleView;
        if (doubleShadowTextView != null) {
            doubleShadowTextView.setOnClickListener(null);
            doubleShadowTextView.setClickable(false);
        }
        this.mTarget = smartspaceTarget;
        this.mEventNotifier = smartspaceEventNotifier;
        SmartspaceAction headerAction = smartspaceTarget.getHeaderAction();
        SmartspaceAction baseAction = smartspaceTarget.getBaseAction();
        this.mLoggingInfo = bcSmartspaceCardLoggingInfo2;
        this.mUsePageIndicatorUi = z;
        this.mValidSecondaryCard = false;
        ViewGroup viewGroup = this.mTextGroup;
        if (viewGroup != null) {
            viewGroup.setTranslationX(0.0f);
        }
        if (headerAction != null) {
            BcSmartspaceCardSecondary bcSmartspaceCardSecondary = this.mSecondaryCard;
            if (bcSmartspaceCardSecondary != null) {
                bcSmartspaceCardSecondary.reset(smartspaceTarget.getSmartspaceTargetId());
                this.mValidSecondaryCard = this.mSecondaryCard.setSmartspaceActions(smartspaceTarget, this.mEventNotifier, bcSmartspaceCardLoggingInfo2);
            }
            ViewGroup viewGroup2 = this.mSecondaryCardGroup;
            if (viewGroup2 != null) {
                viewGroup2.setAlpha(1.0f);
            }
            ViewGroup viewGroup3 = this.mSecondaryCardGroup;
            if (this.mDozeAmount != 1.0f && this.mValidSecondaryCard) {
                i = 0;
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup3, i);
            Icon icon = headerAction.getIcon();
            Context context = getContext();
            FalsingManager falsingManager = BcSmartSpaceUtil.sFalsingManager;
            Drawable iconDrawableWithCustomSize = BcSmartSpaceUtil.getIconDrawableWithCustomSize(icon, context, context.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size));
            boolean z2 = iconDrawableWithCustomSize != null;
            this.mIconDrawable.setIcon(iconDrawableWithCustomSize);
            CharSequence title = headerAction.getTitle();
            CharSequence subtitle = headerAction.getSubtitle();
            boolean z3 = smartspaceTarget.getFeatureType() == 1 || !TextUtils.isEmpty(title);
            boolean isEmpty = TextUtils.isEmpty(subtitle);
            boolean z4 = !isEmpty;
            if (!z3) {
                title = subtitle;
            }
            setTitle(title, headerAction.getContentDescription(), z3 != z4 && z2);
            if (!z3 || isEmpty) {
                subtitle = null;
            }
            setSubtitle(subtitle, headerAction.getContentDescription(), z2);
        }
        if (baseAction != null) {
            int i3 = (baseAction.getExtras() == null || baseAction.getExtras().isEmpty()) ? -1 : baseAction.getExtras().getInt("subcardType", -1);
            if (baseAction.getIcon() != null) {
                Icon icon2 = baseAction.getIcon();
                Context context2 = getContext();
                FalsingManager falsingManager2 = BcSmartSpaceUtil.sFalsingManager;
                drawable = BcSmartSpaceUtil.getIconDrawableWithCustomSize(icon2, context2, context2.getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_icon_size));
            }
            this.mBaseActionIconDrawable.setIcon(drawable);
            setBaseActionIconSubtitle(baseAction.getSubtitle(), baseAction.getContentDescription(), this.mBaseActionIconDrawable);
            if (i3 != -1) {
                i2 = getClickedIndex(bcSmartspaceCardLoggingInfo2, i3);
            } else {
                Log.d("BcSmartspaceCard", "Subcard expected but missing type. loggingInfo=" + bcSmartspaceCardLoggingInfo2.toString() + ", baseAction=" + baseAction.toString());
            }
            BcSmartSpaceUtil.setOnClickListener(this.mBaseActionIconSubtitleView, smartspaceTarget, baseAction, this.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, i2);
            smartspaceAction = baseAction;
            bcSmartspaceCardLoggingInfo2 = bcSmartspaceCardLoggingInfo;
        } else {
            smartspaceAction = baseAction;
        }
        updateIconTint();
        if (headerAction == null || (headerAction.getIntent() == null && headerAction.getPendingIntent() == null)) {
            SmartspaceAction smartspaceAction2 = smartspaceAction;
            if (smartspaceAction2 == null || (smartspaceAction2.getIntent() == null && smartspaceAction2.getPendingIntent() == null)) {
                if (headerAction != null) {
                    BcSmartSpaceUtil.setOnClickListener(this, smartspaceTarget, headerAction, this.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, 0);
                }
            } else if (smartspaceAction2 != null) {
                BcSmartSpaceUtil.setOnClickListener(this, smartspaceTarget, smartspaceAction2, this.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo, 0);
            }
        } else {
            if (smartspaceTarget.getFeatureType() == 1 && bcSmartspaceCardLoggingInfo2.mFeatureType == 39) {
                getClickedIndex(bcSmartspaceCardLoggingInfo2, 1);
            }
            if (headerAction != null) {
                BcSmartSpaceUtil.setOnClickListener(this, smartspaceTarget, headerAction, this.mEventNotifier, "BcSmartspaceCard", bcSmartspaceCardLoggingInfo2, 0);
            }
        }
        ViewGroup viewGroup4 = this.mSecondaryCardGroup;
        if (viewGroup4 == null) {
            return;
        }
        ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) viewGroup4.getLayoutParams();
        if (BcSmartSpaceUtil.getFeatureType(smartspaceTarget) == -2) {
            layoutParams.matchConstraintMaxWidth = (getWidth() * 3) / 4;
        } else {
            layoutParams.matchConstraintMaxWidth = getWidth() / 2;
        }
        this.mSecondaryCardGroup.setLayoutParams(layoutParams);
        this.mTouchDelegateIsDirty = true;
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
                        .setFeatureType(mTarget != null ? mTarget.getFeatureType() : 0)
                        .setUid(-1);
        return new BcSmartspaceCardLoggingInfo(builder);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        setPaddingRelative(getResources().getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start), getPaddingTop(), getPaddingEnd(), getPaddingBottom());
        mTextGroup = (ViewGroup) findViewById(R.id.text_group);
        mSecondaryCardGroup = (ViewGroup) findViewById(R.id.secondary_card_group);
        mTitleTextView = (TextView) findViewById(R.id.title_text);
        mSubtitleTextView = (TextView) findViewById(R.id.subtitle_text);
        mBaseActionIconSubtitleView = findViewById(R.id.base_action_icon_subtitle);
        if (mBaseActionIconSubtitleView != null) {
            mBaseActionIconSubtitleHitRect = new Rect();
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    @Override // androidx.constraintlayout.widget.ConstraintLayout, android.view.ViewGroup, android.view.View
    public final void onLayout(boolean z, int i, int i2, int i3, int i4) {
        super.onLayout(z, i, i2, i3, i4);
        if (z || this.mTouchDelegateIsDirty) {
            this.mTouchDelegateIsDirty = false;
            setTouchDelegate(null);
            DoubleShadowTextView doubleShadowTextView = this.mBaseActionIconSubtitleView;
            if (doubleShadowTextView == null || doubleShadowTextView.getVisibility() != 0) {
                return;
            }
            int dimensionPixelSize = (getResources().getDimensionPixelSize(R.dimen.subtitle_hit_rect_height) - this.mBaseActionIconSubtitleView.getHeight()) / 2;
            this.mBaseActionIconSubtitleView.getHitRect(this.mBaseActionIconSubtitleHitRect);
            offsetDescendantRectToMyCoords((View) this.mBaseActionIconSubtitleView.getParent(), this.mBaseActionIconSubtitleHitRect);
            if (dimensionPixelSize > 0 || this.mBaseActionIconSubtitleHitRect.bottom != getHeight()) {
                if (dimensionPixelSize > 0) {
                    this.mBaseActionIconSubtitleHitRect.top -= dimensionPixelSize;
                }
                this.mBaseActionIconSubtitleHitRect.bottom = getHeight();
                setTouchDelegate(new TouchDelegate(this.mBaseActionIconSubtitleHitRect, this.mBaseActionIconSubtitleView));
            }
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    public final void setBaseActionIconSubtitle(CharSequence charSequence, CharSequence charSequence2, Drawable drawable) {
        if (this.mBaseActionIconSubtitleView == null) {
            Log.w("BcSmartspaceCard", "No base action icon subtitle view to update");
            return;
        }
        if (TextUtils.isEmpty(charSequence)) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this.mBaseActionIconSubtitleView, 8);
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mBaseActionIconSubtitleView, 0);
        this.mBaseActionIconSubtitleView.setText(charSequence);
        this.mBaseActionIconSubtitleView.setCompoundDrawablesRelative(drawable, null, null, null);
        ContentDescriptionUtil.setFormattedContentDescription("BcSmartspaceCard", this.mBaseActionIconSubtitleView, charSequence, charSequence2);
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    @Override // com.google.android.systemui.smartspace.SmartspaceCard
    public final void setDozeAmount(float f) {
        this.mDozeAmount = f;
        SmartspaceTarget smartspaceTarget = this.mTarget;
        if (smartspaceTarget != null && smartspaceTarget.getBaseAction() != null && this.mTarget.getBaseAction().getExtras() != null) {
            Bundle extras = this.mTarget.getBaseAction().getExtras();
            if (this.mTitleTextView != null && extras.getBoolean("hide_title_on_aod")) {
                this.mTitleTextView.setAlpha(1.0f - f);
            }
            if (this.mSubtitleTextView != null && extras.getBoolean("hide_subtitle_on_aod")) {
                this.mSubtitleTextView.setAlpha(1.0f - f);
            }
        }
        if (this.mTextGroup == null) {
            return;
        }
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mSecondaryCardGroup, (this.mDozeAmount == 1.0f || !this.mValidSecondaryCard) ? 8 : 0);
        SmartspaceTarget smartspaceTarget2 = this.mTarget;
        if (smartspaceTarget2 == null || smartspaceTarget2.getFeatureType() != 30) {
            ViewGroup viewGroup = this.mSecondaryCardGroup;
            if (viewGroup == null || viewGroup.getVisibility() == 8) {
                this.mTextGroup.setTranslationX(0.0f);
                return;
            }
            this.mTextGroup.setTranslationX(((PathInterpolator) Interpolators.EMPHASIZED).getInterpolation(this.mDozeAmount) * this.mSecondaryCardGroup.getWidth() * (isRtl() ? 1 : -1));
            this.mSecondaryCardGroup.setAlpha(Math.max(0.0f, Math.min(1.0f, ((1.0f - this.mDozeAmount) * 9.0f) - 6.0f)));
        }
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    @Override // com.google.android.systemui.smartspace.SmartspaceCard
    public final void setPrimaryTextColor(int i) {
        TextView textView = this.mTitleTextView;
        if (textView != null) {
            textView.setTextColor(i);
        }
        TextView textView2 = this.mSubtitleTextView;
        if (textView2 != null) {
            textView2.setTextColor(i);
        }
        DoubleShadowTextView doubleShadowTextView = this.mBaseActionIconSubtitleView;
        if (doubleShadowTextView != null) {
            doubleShadowTextView.setTextColor(i);
        }
        BcSmartspaceCardSecondary bcSmartspaceCardSecondary = this.mSecondaryCard;
        if (bcSmartspaceCardSecondary != null) {
            bcSmartspaceCardSecondary.setTextColor(i);
        }
        this.mIconTintColor = i;
        updateIconTint();
    }

    public final void setSecondaryCard(BcSmartspaceCardSecondary secondaryCard) {
        if (mSecondaryCardGroup == null) {
            return;
        }
        mSecondaryCard = secondaryCard;
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondaryCardGroup, View.GONE);
        mSecondaryCardGroup.removeAllViews();
        if (secondaryCard != null) {
            ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height));
            params.setMarginStart(getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_secondary_card_start_margin));
            params.startToStart = 0;
            params.topToTop = 0;
            params.bottomToBottom = 0;
            mSecondaryCardGroup.addView(secondaryCard, params);
        }
    }

    public final void setSubtitle(CharSequence text, CharSequence contentDescription, boolean useIcon) {
        if (mSubtitleTextView == null) {
            Log.w("BcSmartspaceCard", "No subtitle view to update");
            return;
        }
        mSubtitleTextView.setText(text);
        mSubtitleTextView.setCompoundDrawablesRelative((TextUtils.isEmpty(text) || !useIcon) ? null : mIconDrawable, null, null, null);
        mSubtitleTextView.setMaxLines((mTarget == null || mTarget.getFeatureType() != 5 || mUsePageIndicatorUi) ? 1 : 2);
        ContentDescriptionUtil.setFormattedContentDescription("BcSmartspaceCard", mSubtitleTextView, text, contentDescription);
        BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(mSubtitleTextView, useIcon ? mIconDrawable : null, isRtl());
    }

    /* JADX DEBUG: Don't trust debug lines info. Lines numbers was adjusted: min line is 1 */
    public final void setTitle(CharSequence text, CharSequence contentDescription, boolean useIcon) {
        boolean z2;
        if (mTitleTextView == null) {
            Log.w("BcSmartspaceCard", "No title view to update");
            return;
        }
        mTitleTextView.setText(text);
        SmartspaceAction headerAction = mTarget == null ? null : mTarget.getHeaderAction();
        Bundle extras = headerAction == null ? null : headerAction.getExtras();
        if (extras == null || !extras.containsKey("titleEllipsize")) {
            if (mTarget != null && mTarget.getFeatureType() == 2 && Locale.ENGLISH.getLanguage().equals(getResources().getConfiguration().locale.getLanguage())) {
                mTitleTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            } else {
                mTitleTextView.setEllipsize(TextUtils.TruncateAt.END);
            }
        } else {
            try {
                mTitleTextView.setEllipsize(TextUtils.TruncateAt.valueOf(extras.getString("titleEllipsize")));
            } catch (IllegalArgumentException unused) {
                Log.w(
                        "BcSmartspaceCard",
                        "Invalid TruncateAt value: " + extras.getString("titleEllipsize"));
            }
        }
        boolean z3 = false;
        if (extras != null) {
            if (extras.getInt("titleMaxLines") != 0) {
                mTitleTextView.setMaxLines(extras.getInt("titleMaxLines"));
            }
            z2 = extras.getBoolean("disableTitleIcon");
        } else {
            z2 = false;
        }
        if (useIcon && !z2) {
            z3 = true;
        }
        if (z3) {
            ContentDescriptionUtil.setFormattedContentDescription("BcSmartspaceCard", mTitleTextView, text, contentDescription);
        }
        mTitleTextView.setCompoundDrawablesRelative(z3 ? mIconDrawable : null, null, null, null);
        BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(mTitleTextView, z3 ? mIconDrawable : null, isRtl());
    }

    public final void updateIconTint() {
        if (mTarget == null) {
            return;
        }
        if (mTarget.getFeatureType() == 1) {
            mIconDrawable.setTintList(null);
        } else {
            mIconDrawable.setTint(mIconTintColor);
        }
        SmartspaceAction baseAction = mTarget.getBaseAction();
        int subcardType = -1;
        if (baseAction != null && baseAction.getExtras() != null && !baseAction.getExtras().isEmpty()) {
            subcardType = baseAction.getExtras().getInt("subcardType", -1);
        }
        if (subcardType == 1) {
            mBaseActionIconDrawable.setTintList(null);
        } else {
            mBaseActionIconDrawable.setTint(mIconTintColor);
        }
    }

    public BcSmartspaceCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSecondaryCard = null;
        mIconTintColor = GraphicsUtils.getAttrColor(context, android.R.attr.textColorPrimary);
        mTextGroup = null;
        mSecondaryCardGroup = null;
        mTitleTextView = null;
        mSubtitleTextView = null;
        mBaseActionIconSubtitleView = null;
        mBaseActionIconSubtitleHitRect = null;
        mUiSurface = null;
        mTouchDelegateIsDirty = false;
        context.getTheme().applyStyle(R.style.Smartspace, false);
        mIconDrawable = new DoubleShadowIconDrawable(context);
        mBaseActionIconDrawable = new DoubleShadowIconDrawable(context);
        setDefaultFocusHighlightEnabled(false);
    }

    @Override
    public final View getView() {
        return this;
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
    }
}
