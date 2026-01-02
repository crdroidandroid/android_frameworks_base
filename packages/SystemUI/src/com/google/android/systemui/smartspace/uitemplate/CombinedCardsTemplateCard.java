package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.CombinedCardsTemplateData;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

import java.util.List;

public class CombinedCardsTemplateCard extends BcSmartspaceCardSecondary {
    public ConstraintLayout mFirstSubCard;
    public ConstraintLayout mSecondSubCard;

    public CombinedCardsTemplateCard(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mFirstSubCard = findViewById(R.id.first_sub_card_container);
        mSecondSubCard = findViewById(R.id.second_sub_card_container);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstSubCard, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondSubCard, View.GONE);
    }

    @Override
    public final boolean setSmartspaceActions(SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        reset(target.getSmartspaceTargetId());
        CombinedCardsTemplateData templateData = (CombinedCardsTemplateData) target.getTemplateData();
        if (!BcSmartspaceCardLoggerUtil.containsValidTemplateType(templateData) || templateData.getCombinedCardDataList().isEmpty()) {
            Log.w("CombinedCardsTemplateCard", "TemplateData is null or empty or invalid template type");
            return false;
        }
        List<BaseTemplateData> combinedCardDataList = templateData.getCombinedCardDataList();
        BaseTemplateData firstCardData = combinedCardDataList.get(0);
        BaseTemplateData secondCardData = combinedCardDataList.size() > 1 ? combinedCardDataList.get(1) : null;
        return setupSubCard(mFirstSubCard, firstCardData, target, eventNotifier, loggingInfo) && (secondCardData == null || setupSubCard(mSecondSubCard, secondCardData, target, eventNotifier, loggingInfo));
    }

    @Override
    public void setTextColor(int color) {
        if (mFirstSubCard.getChildCount() > 0) {
            BcSmartspaceCardSecondary firstSubCard =
                    (BcSmartspaceCardSecondary) mFirstSubCard.getChildAt(0);
            firstSubCard.setTextColor(color);
        }
        if (mSecondSubCard.getChildCount() > 0) {
            BcSmartspaceCardSecondary secondSubCard =
                    (BcSmartspaceCardSecondary) mSecondSubCard.getChildAt(0);
            secondSubCard.setTextColor(color);
        }
    }

    public final boolean setupSubCard(ViewGroup container, BaseTemplateData templateData, SmartspaceTarget target, BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier, BcSmartspaceCardLoggingInfo loggingInfo) {
        if (templateData == null) {
            BcSmartspaceTemplateDataUtils.updateVisibility(container, View.GONE);
            Log.w("CombinedCardsTemplateCard", "Sub-card templateData is null or empty");
            return false;
        }
        Integer subCardResId =
                BcSmartspaceTemplateDataUtils.TEMPLATE_TYPE_TO_SECONDARY_CARD_RES.get(
                        templateData.getTemplateType());
        if (subCardResId == 0) {
            BcSmartspaceTemplateDataUtils.updateVisibility(container, View.GONE);
            Log.w("CombinedCardsTemplateCard", "Combined sub-card res is null. Cannot set it up");
            return false;
        }
        BcSmartspaceCardSecondary subCard = (BcSmartspaceCardSecondary) LayoutInflater.from(container.getContext()).inflate(subCardResId, container, false);
        subCard.setSmartspaceActions(new SmartspaceTarget.Builder(target.getSmartspaceTargetId(), target.getComponentName(), target.getUserHandle()).setTemplateData(templateData).build(), eventNotifier, loggingInfo);
        container.removeAllViews();
        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_card_height));
        params.startToStart = 0;
        params.endToEnd = 0;
        params.topToTop = 0;
        params.bottomToBottom = 0;
        BcSmartspaceTemplateDataUtils.updateVisibility(subCard, View.VISIBLE);
        container.addView(subCard, params);
        BcSmartspaceTemplateDataUtils.updateVisibility(container, View.VISIBLE);
        return true;
    }

    public CombinedCardsTemplateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
