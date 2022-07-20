package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.CombinedCardsTemplateData;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.android.systemui.bcsmartspace.R;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import java.util.List;

public class CombinedCardsTemplateCard extends BcSmartspaceCardSecondary {
    public ConstraintLayout mFirstSubCard;
    public ConstraintLayout mSecondSubCard;

    public CombinedCardsTemplateCard(Context context) {
        super(context);
    }

    public CombinedCardsTemplateCard(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void setTextColor(int i) {
        if (this.mFirstSubCard.getChildCount() != 0) {
            ((BcSmartspaceCardSecondary) this.mFirstSubCard.getChildAt(0)).setTextColor(i);
        }
        if (this.mSecondSubCard.getChildCount() != 0) {
            ((BcSmartspaceCardSecondary) this.mSecondSubCard.getChildAt(0)).setTextColor(i);
        }
    }

    public final void onFinishInflate() {
        super/*android.view.ViewGroup*/.onFinishInflate();
        this.mFirstSubCard = findViewById(R.id.first_sub_card_container);
        this.mSecondSubCard = findViewById(R.id.second_sub_card_container);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mFirstSubCard, 8);
        BcSmartspaceTemplateDataUtils.updateVisibility(this.mSecondSubCard, 8);
    }

    @Override // com.google.android.systemui.smartspace.BcSmartspaceCardSecondary
    public final boolean setSmartspaceActions(SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        BaseTemplateData baseTemplateData;
        CombinedCardsTemplateData templateData = (CombinedCardsTemplateData) smartspaceTarget.getTemplateData();
        if (templateData != null && !templateData.getCombinedCardDataList().isEmpty()) {
            List combinedCardDataList = templateData.getCombinedCardDataList();
            BaseTemplateData baseTemplateData2 = (BaseTemplateData) combinedCardDataList.get(0);
            if (combinedCardDataList.size() > 1) {
                baseTemplateData = (BaseTemplateData) combinedCardDataList.get(1);
            } else {
                baseTemplateData = null;
            }
            if (!setupSubCard(this.mFirstSubCard, baseTemplateData2, smartspaceTarget, smartspaceEventNotifier, bcSmartspaceCardLoggingInfo)) {
                return false;
            }
            if (baseTemplateData != null && !setupSubCard(this.mSecondSubCard, baseTemplateData, smartspaceTarget, smartspaceEventNotifier, bcSmartspaceCardLoggingInfo)) {
                return false;
            }
            return true;
        }
        Log.w("CombinedCardsTemplateCard", "TemplateData is null or empty");
        return false;
    }

    public final boolean setupSubCard(ConstraintLayout constraintLayout, BaseTemplateData baseTemplateData, SmartspaceTarget smartspaceTarget, BcSmartspaceDataPlugin.SmartspaceEventNotifier smartspaceEventNotifier, BcSmartspaceCardLoggingInfo bcSmartspaceCardLoggingInfo) {
        if (baseTemplateData == null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, 8);
            Log.w("CombinedCardsTemplateCard", "Sub-card templateData is null or empty");
            return false;
        }
        int secondaryCardRes = BcSmartspaceTemplateDataUtils.getSecondaryCardRes(baseTemplateData.getTemplateType());
        if (secondaryCardRes == 0) {
            BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, 8);
            Log.w("CombinedCardsTemplateCard", "Combined sub-card res is null. Cannot set it up");
            return false;
        }
        BcSmartspaceCardSecondary r0 = (BcSmartspaceCardSecondary) LayoutInflater.from(constraintLayout.getContext()).inflate(secondaryCardRes, (ViewGroup) constraintLayout, false);
        r0.setSmartspaceActions(new SmartspaceTarget.Builder(smartspaceTarget.getSmartspaceTargetId(), smartspaceTarget.getComponentName(), smartspaceTarget.getUserHandle()).setTemplateData(baseTemplateData).build(), smartspaceEventNotifier, bcSmartspaceCardLoggingInfo);
        constraintLayout.removeAllViews();
        ConstraintLayout.LayoutParams layoutParams = new ConstraintLayout.LayoutParams(-2, getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height));
        layoutParams.startToStart = 0;
        layoutParams.endToEnd = 0;
        layoutParams.topToTop = 0;
        layoutParams.bottomToBottom = 0;
        BcSmartspaceTemplateDataUtils.updateVisibility(r0, 0);
        constraintLayout.addView(r0, layoutParams);
        BcSmartspaceTemplateDataUtils.updateVisibility(constraintLayout, 0);
        return true;
    }
}
