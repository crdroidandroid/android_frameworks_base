package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import java.util.List;

public class BcSmartspaceCardCombination extends BcSmartspaceCardSecondary {
    public ConstraintLayout mFirstSubCard;
    public ConstraintLayout mSecondSubCard;

    public BcSmartspaceCardCombination(Context context) {
        super(context);
    }

    public final boolean fillSubCard(ConstraintLayout constraintLayout, SmartspaceTarget smartspaceTarget, SmartspaceAction smartspaceAction, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        boolean z;
        CharSequence charSequence;
        TextView textView = (TextView) constraintLayout.findViewById(R.id.sub_card_text);
        ImageView imageView = (ImageView) constraintLayout.findViewById(R.id.sub_card_icon);
        if (textView == null) {
            Log.w("BcSmartspaceCardCombination", "No sub-card text field to update");
            return false;
        } else if (imageView == null) {
            Log.w("BcSmartspaceCardCombination", "No sub-card image field to update");
            return false;
        } else {
            BcSmartSpaceUtil.setOnClickListener(constraintLayout, smartspaceTarget, smartspaceAction, smartspaceEventNotifier, "BcSmartspaceCardCombination", bcSmartspaceCardLoggingInfo, 0);
            Drawable iconDrawable = BcSmartSpaceUtil.getIconDrawable(getContext(), smartspaceAction.getIcon());
            boolean z2 = true;
            if (iconDrawable == null) {
                BcSmartspaceTemplateDataUtils.updateVisibility(imageView, 8);
                z = false;
            } else {
                imageView.setImageDrawable(iconDrawable);
                BcSmartspaceTemplateDataUtils.updateVisibility(imageView, 0);
                z = true;
            }
            CharSequence title = smartspaceAction.getTitle();
            if (TextUtils.isEmpty(title)) {
                BcSmartspaceTemplateDataUtils.updateVisibility(textView, 8);
                z2 = z;
            } else {
                textView.setText(title);
                BcSmartspaceTemplateDataUtils.updateVisibility(textView, 0);
            }
            if (z2) {
                charSequence = smartspaceAction.getContentDescription();
            } else {
                charSequence = null;
            }
            constraintLayout.setContentDescription(charSequence);
            if (z2) {
                BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, 0);
            } else {
                BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, 8);
            }
            return z2;
        }
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void setTextColor(int i) {
    }

    public BcSmartspaceCardCombination(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mFirstSubCard, 8);
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mSecondSubCard, 8);
    }

    public final void onFinishInflate() {
        super.onFinishInflate();
        this.mFirstSubCard = findViewById(R.id.first_sub_card);
        this.mSecondSubCard = findViewById(R.id.second_sub_card);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public boolean setSmartspaceActions(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        SmartspaceAction smartspaceAction;
        boolean z3;
        List<SmartspaceAction> actionChips = smartspaceTarget.getActionChips();
        if (actionChips == null || actionChips.size() < 1 || (smartspaceAction = actionChips.get(0)) == null) {
            return false;
        }
        boolean z = this.mFirstSubCard != null && fillSubCard(this.mFirstSubCard, smartspaceTarget, smartspaceAction, smartspaceEventNotifier, bcSmartspaceCardLoggingInfo);
        if (actionChips.size() > 1 && actionChips.get(1) != null) {
            z3 = fillSubCard(this.mSecondSubCard, smartspaceTarget, actionChips.get(1), smartspaceEventNotifier, bcSmartspaceCardLoggingInfo);
        } else {
            z3 = true;
        }
        if (getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) getLayoutParams();
            if (actionChips.size() > 1 && actionChips.get(1) != null && z3) {
                layoutParams.weight = 3.0f;
            } else {
                layoutParams.weight = 1.0f;
            }
            setLayoutParams(layoutParams);
        }
        return z && z3;
    }
}
