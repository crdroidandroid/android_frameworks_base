package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.HeadToHeadTemplateData;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.Text;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import com.google.android.systemui.smartspace.BcSmartSpaceUtil;
import com.google.android.systemui.smartspace.BcSmartspaceCardSecondary;
import com.google.android.systemui.smartspace.BcSmartspaceTemplateDataUtils;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;

import com.android.systemui.res.R;

public class HeadToHeadTemplateCard extends BcSmartspaceCardSecondary {
    public ImageView mFirstCompetitorIcon;
    public TextView mFirstCompetitorText;
    public TextView mHeadToHeadTitle;
    public ImageView mSecondCompetitorIcon;
    public TextView mSecondCompetitorText;

    public HeadToHeadTemplateCard(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mHeadToHeadTitle = findViewById(R.id.head_to_head_title);
        mFirstCompetitorText = findViewById(R.id.first_competitor_text);
        mSecondCompetitorText = findViewById(R.id.second_competitor_text);
        mFirstCompetitorIcon = findViewById(R.id.first_competitor_icon);
        mSecondCompetitorIcon = findViewById(R.id.second_competitor_icon);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mHeadToHeadTitle, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstCompetitorText, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondCompetitorText, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mFirstCompetitorIcon, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mSecondCompetitorIcon, View.GONE);
    }

    @Override
    public boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        HeadToHeadTemplateData templateData = (HeadToHeadTemplateData) target.getTemplateData();
        if (!BcSmartspaceCardLoggerUtil.containsValidTemplateType(templateData)) {
            Log.w(
                    "HeadToHeadTemplateCard",
                    "HeadToHeadTemplateData is null or invalid template type");
            return false;
        }

        boolean isValid = false;

        Text title = templateData.getHeadToHeadTitle();
        if (title != null) {
            if (mHeadToHeadTitle == null) {
                Log.w("HeadToHeadTemplateCard", "No head-to-head title view to update");
            } else {
                BcSmartspaceTemplateDataUtils.setText(mHeadToHeadTitle, title);
                BcSmartspaceTemplateDataUtils.updateVisibility(mHeadToHeadTitle, View.VISIBLE);
                isValid = true;
            }
        }

        Text firstCompetitorText = templateData.getHeadToHeadFirstCompetitorText();
        if (firstCompetitorText != null) {
            if (mFirstCompetitorText == null) {
                Log.w("HeadToHeadTemplateCard", "No first competitor text view to update");
            } else {
                BcSmartspaceTemplateDataUtils.setText(mFirstCompetitorText, firstCompetitorText);
                BcSmartspaceTemplateDataUtils.updateVisibility(mFirstCompetitorText, View.VISIBLE);
                isValid = true;
            }
        }

        Text secondCompetitorText = templateData.getHeadToHeadSecondCompetitorText();
        if (secondCompetitorText != null) {
            if (mSecondCompetitorText == null) {
                Log.w("HeadToHeadTemplateCard", "No second competitor text view to update");
            } else {
                BcSmartspaceTemplateDataUtils.setText(mSecondCompetitorText, secondCompetitorText);
                BcSmartspaceTemplateDataUtils.updateVisibility(mSecondCompetitorText, View.VISIBLE);
                isValid = true;
            }
        }

        Icon firstCompetitorIcon = templateData.getHeadToHeadFirstCompetitorIcon();
        if (firstCompetitorIcon != null) {
            if (mFirstCompetitorIcon == null) {
                Log.w("HeadToHeadTemplateCard", "No first competitor icon view to update");
            } else {
                BcSmartspaceTemplateDataUtils.setIcon(mFirstCompetitorIcon, firstCompetitorIcon);
                BcSmartspaceTemplateDataUtils.updateVisibility(mFirstCompetitorIcon, View.VISIBLE);
                isValid = true;
            }
        }

        Icon secondCompetitorIcon = templateData.getHeadToHeadSecondCompetitorIcon();
        if (secondCompetitorIcon != null) {
            if (mSecondCompetitorIcon == null) {
                Log.w("HeadToHeadTemplateCard", "No second competitor icon view to update");
            } else {
                BcSmartspaceTemplateDataUtils.setIcon(mSecondCompetitorIcon, secondCompetitorIcon);
                BcSmartspaceTemplateDataUtils.updateVisibility(mSecondCompetitorIcon, View.VISIBLE);
                isValid = true;
            }
        }

        if (isValid && templateData.getHeadToHeadAction() != null) {
            BcSmartSpaceUtil.setOnClickListener(
                    this,
                    target,
                    templateData.getHeadToHeadAction(),
                    eventNotifier,
                    "HeadToHeadTemplateCard",
                    loggingInfo,
                    0);
        }

        return isValid;
    }

    @Override
    public final void setTextColor(int color) {
        mFirstCompetitorText.setTextColor(color);
        mSecondCompetitorText.setTextColor(color);
    }

    public HeadToHeadTemplateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
