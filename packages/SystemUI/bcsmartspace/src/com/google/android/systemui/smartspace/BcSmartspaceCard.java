package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.android.app.animation.Interpolators;
import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardMetadataLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import java.util.List;
import java.util.Locale;

public class BcSmartspaceCard extends ConstraintLayout {
    public DoubleShadowTextView mBaseActionIconSubtitleView;
    public IcuDateTextView mDateView;
    public final DoubleShadowIconDrawable mDndIconDrawable;
    public ImageView mDndImageView;
    public float mDozeAmount;
    public BcSmartspaceDataPlugin.SmartspaceEventNotifier mEventNotifier;
    public ViewGroup mExtrasGroup;
    public final DoubleShadowIconDrawable mIconDrawable;
    public int mIconTintColor;
    public boolean mIsDreaming;
    public final DoubleShadowIconDrawable mNextAlarmIconDrawable;
    public ImageView mNextAlarmImageView;
    public TextView mNextAlarmTextView;
    public String mPrevSmartspaceTargetId;
    public BcSmartspaceCardSecondary mSecondaryCard;
    public ViewGroup mSecondaryCardGroup;
    public TextView mSubtitleTextView;
    public SmartspaceTarget mTarget;
    public ViewGroup mTextGroup;
    public TextView mTitleTextView;
    public int mTopPadding;
    public boolean mUsePageIndicatorUi;
    public boolean mValidSecondaryCard;

    public BcSmartspaceCard(Context context) {
        this(context, null);
    }

    public BcSmartspaceCard(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mSecondaryCard = null;
        this.mPrevSmartspaceTargetId = "";
        this.mIconTintColor = GraphicsUtils.getAttrColor(getContext(), 16842806);
        this.mTextGroup = null;
        this.mSecondaryCardGroup = null;
        this.mDateView = null;
        this.mTitleTextView = null;
        this.mSubtitleTextView = null;
        this.mBaseActionIconSubtitleView = null;
        this.mExtrasGroup = null;
        this.mDndImageView = null;
        this.mNextAlarmImageView = null;
        this.mNextAlarmTextView = null;
        this.mIsDreaming = false;
        this.mIconDrawable = new DoubleShadowIconDrawable(context);
        this.mNextAlarmIconDrawable = new DoubleShadowIconDrawable(context);
        this.mDndIconDrawable = new DoubleShadowIconDrawable(context);
    }

    public static int getClickedIndex(BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo, int i) {
        List<BcSmartspaceCardMetadataLoggingInfo> list;
        BcSmartspaceSubcardLoggingInfo bcSmartspaceSubcardLoggingInfo = bcSmartspaceCardLoggingInfo.mSubcardInfo;
        if (bcSmartspaceSubcardLoggingInfo == null || (list = bcSmartspaceSubcardLoggingInfo.mSubcards) == null) {
            return 0;
        }
        for (int i2 = 0; i2 < list.size(); i2++) {
            BcSmartspaceCardMetadataLoggingInfo bcSmartspaceCardMetadataLoggingInfo = list.get(i2);
            if (bcSmartspaceCardMetadataLoggingInfo != null && bcSmartspaceCardMetadataLoggingInfo.mCardTypeId == i) {
                return i2 + 1;
            }
        }
        return 0;
    }

    public final void setDozeAmount(float f) {
        this.mDozeAmount = f;
        if (this.mTarget != null && this.mTarget.getBaseAction() != null && this.mTarget.getBaseAction().getExtras() != null) {
            Bundle extras = this.mTarget.getBaseAction().getExtras();
            if (this.mTitleTextView != null && extras.getBoolean("hide_title_on_aod")) {
                this.mTitleTextView.setAlpha(1.0f - f);
            }
            if (this.mSubtitleTextView != null && extras.getBoolean("hide_subtitle_on_aod")) {
                this.mSubtitleTextView.setAlpha(1.0f - f);
            }
        }
        if (this.mDndImageView != null) {
            this.mDndImageView.setAlpha(this.mDozeAmount);
        }
        if (this.mTextGroup != null) {
            ViewGroup viewGroup = this.mSecondaryCardGroup;
            int i = 0;
            int i2 = 1;
            boolean z = this.mDozeAmount == 1.0f || !this.mValidSecondaryCard;
            if (z) {
                i = 8;
            }
            BcSmartspaceTemplateDataUtils.updateVisibility(viewGroup, i);
            ViewGroup viewGroup2 = this.mSecondaryCardGroup;
            if (viewGroup2 != null && viewGroup2.getVisibility() != 8) {
                ViewGroup viewGroup3 = this.mTextGroup;
                if (!isRtl()) {
                    i2 = -1;
                }
                viewGroup3.setTranslationX(Interpolators.EMPHASIZED.getInterpolation(this.mDozeAmount) * this.mSecondaryCardGroup.getWidth() * i2);
                this.mSecondaryCardGroup.setAlpha(Math.max(0.0f, Math.min(1.0f, ((1.0f - this.mDozeAmount) * 9.0f) - 6.0f)));
                return;
            }
            this.mTextGroup.setTranslationX(0.0f);
        }
    }

    public final void setPrimaryTextColor(int i) {
        if (this.mTitleTextView != null) {
            this.mTitleTextView.setTextColor(i);
        }
        if (this.mDateView != null) {
            this.mDateView.setTextColor(i);
        }
        if (this.mSubtitleTextView != null) {
            this.mSubtitleTextView.setTextColor(i);
        }
        if (this.mBaseActionIconSubtitleView != null) {
            this.mBaseActionIconSubtitleView.setTextColor(i);
        }
        if (this.mSecondaryCard != null) {
            this.mSecondaryCard.setTextColor(i);
        }
        this.mIconTintColor = i;
        if (this.mNextAlarmTextView != null) {
            this.mNextAlarmTextView.setTextColor(i);
        }
        if (this.mNextAlarmImageView != null && this.mNextAlarmImageView.getDrawable() != null) {
            this.mNextAlarmImageView.getDrawable().setTint(this.mIconTintColor);
        }
        if (this.mDndImageView != null && this.mDndImageView.getDrawable() != null) {
            this.mDndImageView.getDrawable().setTint(this.mIconTintColor);
        }
        updateIconTint();
    }

    public final void setSubtitle(CharSequence charSequence, CharSequence charSequence2, boolean z) {
        DoubleShadowIconDrawable doubleShadowIconDrawable;
        int i;
        if (this.mSubtitleTextView == null) {
            Log.w("BcSmartspaceCard", "No subtitle view to update");
            return;
        }
        this.mSubtitleTextView.setText(charSequence);
        DoubleShadowIconDrawable doubleShadowIconDrawable2 = null;
        if (!TextUtils.isEmpty(charSequence) && z) {
            doubleShadowIconDrawable = this.mIconDrawable;
        } else {
            doubleShadowIconDrawable = null;
        }
        this.mSubtitleTextView.setCompoundDrawablesRelative(doubleShadowIconDrawable, null, null, null);
        SmartspaceTarget smartspaceTarget = this.mTarget;
        if (smartspaceTarget != null && smartspaceTarget.getFeatureType() == 5 && !this.mUsePageIndicatorUi) {
            i = 2;
        } else {
            i = 1;
        }
        this.mSubtitleTextView.setMaxLines(i);
        setFormattedContentDescription(this.mSubtitleTextView, charSequence, charSequence2);
        if (z) {
            doubleShadowIconDrawable2 = this.mIconDrawable;
        }
        BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(this.mSubtitleTextView, doubleShadowIconDrawable2, isRtl());
    }

    public final void setTitle(CharSequence charSequence, CharSequence charSequence2, boolean z) {
        SmartspaceAction headerAction;
        Bundle extras;
        boolean z2;
        DoubleShadowIconDrawable doubleShadowIconDrawable;
        if (this.mTitleTextView == null) {
            Log.w("BcSmartspaceCard", "No title view to update");
            return;
        }
        this.mTitleTextView.setText(charSequence);
        DoubleShadowIconDrawable doubleShadowIconDrawable2 = null;
        if (this.mTarget == null) {
            headerAction = null;
        } else {
            headerAction = this.mTarget.getHeaderAction();
        }
        if (headerAction == null) {
            extras = null;
        } else {
            extras = headerAction.getExtras();
        }
        if (extras != null && extras.containsKey("titleEllipsize")) {
            String string = extras.getString("titleEllipsize");
            try {
                this.mTitleTextView.setEllipsize(TextUtils.TruncateAt.valueOf(string));
            } catch (IllegalArgumentException e) {
                Log.e("BcSmartspaceCard", "Invalid TruncateAt value: " + string);
            }
        } else if (this.mTarget != null && this.mTarget.getFeatureType() == 2 && Locale.ENGLISH.getLanguage().equals(getContext().getResources().getConfiguration().locale.getLanguage())) {
            this.mTitleTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        } else {
            this.mTitleTextView.setEllipsize(TextUtils.TruncateAt.END);
        }
        boolean z3 = false;
        if (extras != null) {
            int i = extras.getInt("titleMaxLines");
            if (i != 0) {
                this.mTitleTextView.setMaxLines(i);
            }
            z2 = extras.getBoolean("disableTitleIcon");
        } else {
            z2 = false;
        }
        if (z && !z2) {
            z3 = true;
        }
        if (z3) {
            setFormattedContentDescription(this.mTitleTextView, charSequence, charSequence2);
        }
        if (z3) {
            doubleShadowIconDrawable = this.mIconDrawable;
        } else {
            doubleShadowIconDrawable = null;
        }
        this.mTitleTextView.setCompoundDrawablesRelative(doubleShadowIconDrawable, null, null, null);
        if (z3) {
            doubleShadowIconDrawable2 = this.mIconDrawable;
        }
        BcSmartspaceTemplateDataUtils.offsetTextViewForIcon(this.mTitleTextView, doubleShadowIconDrawable2, isRtl());
    }

    public final void updateIconTint() {
        if (this.mTarget != null && this.mIconDrawable != null) {
            boolean z = this.mTarget.getFeatureType() != 1;
            if (z) {
                this.mIconDrawable.setTint(this.mIconTintColor);
            } else {
                this.mIconDrawable.setTintList(null);
            }
        }
    }

    public final void updateZenVisibility() {
        if (this.mExtrasGroup == null) {
            return;
        }
        ImageView imageView = this.mDndImageView;
        boolean z3 = true;
        int i = 0;
        boolean z = imageView != null && imageView.getVisibility() == 0;
        ImageView imageView2 = this.mNextAlarmImageView;
        boolean z2 = imageView2 != null && imageView2.getVisibility() == 0;
        if ((!z && !z2) || (this.mUsePageIndicatorUi && (this.mTarget == null || this.mTarget.getFeatureType() != 1))) {
            z3 = false;
        }
        int i2 = this.mTopPadding;
        if (!z3) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this.mExtrasGroup, 4);
            i = i2;
        } else {
            BcSmartspaceTemplateDataUtils.updateVisibility(this.mExtrasGroup, 0);
            if (this.mNextAlarmTextView != null) {
                this.mNextAlarmTextView.setTextColor(this.mIconTintColor);
            }
            if (this.mNextAlarmImageView != null && this.mNextAlarmImageView.getDrawable() != null) {
                this.mNextAlarmImageView.getDrawable().setTint(this.mIconTintColor);
            }
            if (this.mDndImageView != null && this.mDndImageView.getDrawable() != null) {
                this.mDndImageView.getDrawable().setTint(this.mIconTintColor);
            }
        }
        setPadding(getPaddingLeft(), i, getPaddingRight(), getPaddingBottom());
    }

    public final AccessibilityNodeInfo createAccessibilityNodeInfo() {
        AccessibilityNodeInfo createAccessibilityNodeInfo = super.createAccessibilityNodeInfo();
        createAccessibilityNodeInfo.getExtras().putCharSequence("AccessibilityNodeInfo.roleDescription", " ");
        return createAccessibilityNodeInfo;
    }

    public final void onFinishInflate() {
        super.onFinishInflate();
        this.mTextGroup = (ViewGroup) findViewById(R.id.text_group);
        this.mSecondaryCardGroup = (ViewGroup) findViewById(R.id.secondary_card_group);
        this.mDateView = (IcuDateTextView) findViewById(R.id.date);
        this.mTitleTextView = (TextView) findViewById(R.id.title_text);
        this.mSubtitleTextView = (TextView) findViewById(R.id.subtitle_text);
        this.mBaseActionIconSubtitleView = (DoubleShadowTextView) findViewById(R.id.base_action_icon_subtitle);
        this.mExtrasGroup = (ViewGroup) findViewById(R.id.smartspace_extras_group);
        this.mTopPadding = getPaddingTop();
        if (this.mExtrasGroup != null) {
            this.mDndImageView = (ImageView) this.mExtrasGroup.findViewById(R.id.dnd_icon);
            this.mNextAlarmImageView = (ImageView) this.mExtrasGroup.findViewById(R.id.alarm_icon);
            this.mNextAlarmTextView = (TextView) this.mExtrasGroup.findViewById(R.id.alarm_text);
        }
    }

    public final void setFormattedContentDescription(TextView textView, CharSequence charSequence, CharSequence charSequence2) {
        String string;
        String str;
        if (TextUtils.isEmpty(charSequence)) {
            string = String.valueOf(charSequence2);
        } else if (TextUtils.isEmpty(charSequence2)) {
            string = String.valueOf(charSequence);
        } else {
            string = getContext().getString(R.string.generic_smartspace_concatenated_desc, charSequence2, charSequence);
        }
        Object[] objArr = new Object[4];
        if (textView == this.mTitleTextView) {
            str = "TITLE";
        } else if (textView == this.mSubtitleTextView) {
            str = "SUBTITLE";
        } else {
            str = "SUPPLEMENTAL";
        }
        objArr[0] = str;
        objArr[1] = charSequence;
        objArr[2] = charSequence2;
        objArr[3] = string;
        Log.i("BcSmartspaceCard", String.format("setFormattedContentDescription: textView=%s, text=%s, iconDescription=%s, contentDescription=%s", objArr));
        textView.setContentDescription(string);
    }
}
