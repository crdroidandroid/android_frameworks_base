package com.google.android.systemui.smartspace.uitemplate;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceUtils;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.SubCardTemplateData;
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

public class SubCardTemplateCard extends BcSmartspaceCardSecondary {
    public ImageView mImageView;
    public TextView mTextView;

    public SubCardTemplateCard(Context context) {
        super(context);
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        mImageView = findViewById(R.id.image_view);
        mTextView = findViewById(R.id.card_prompt);
    }

    @Override
    public final void resetUi() {
        BcSmartspaceTemplateDataUtils.updateVisibility(mImageView, View.GONE);
        BcSmartspaceTemplateDataUtils.updateVisibility(mTextView, View.GONE);
    }

    @Override
    public boolean setSmartspaceActions(
            SmartspaceTarget target,
            BcSmartspaceDataPlugin.SmartspaceEventNotifier eventNotifier,
            BcSmartspaceCardLoggingInfo loggingInfo) {
        SubCardTemplateData templateData = (SubCardTemplateData) target.getTemplateData();
        if (!BcSmartspaceCardLoggerUtil.containsValidTemplateType(templateData)) {
            Log.w("SubCardTemplateCard", "SubCardTemplateData is null or invalid template type");
            return false;
        }

        boolean isValid = false;

        Icon subCardIcon = templateData.getSubCardIcon();
        if (subCardIcon != null) {
            BcSmartspaceTemplateDataUtils.setIcon(mImageView, subCardIcon);
            BcSmartspaceTemplateDataUtils.updateVisibility(mImageView, View.VISIBLE);
            isValid = true;
        }

        Text subCardText = templateData.getSubCardText();
        if (!SmartspaceUtils.isEmpty(subCardText)) {
            BcSmartspaceTemplateDataUtils.setText(mTextView, subCardText);
            BcSmartspaceTemplateDataUtils.updateVisibility(mTextView, View.VISIBLE);
            isValid = true;
        }

        if (isValid && templateData.getSubCardAction() != null) {
            BcSmartSpaceUtil.setOnClickListener(
                    this,
                    target,
                    templateData.getSubCardAction(),
                    eventNotifier,
                    "SubCardTemplateCard",
                    loggingInfo,
                    0);
        }

        return isValid;
    }

    @Override
    public void setTextColor(int color) {
        mTextView.setTextColor(color);
    }

    public SubCardTemplateCard(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
